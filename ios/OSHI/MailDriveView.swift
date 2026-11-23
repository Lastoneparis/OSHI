//
//  MailDriveView.swift
//  OSHI
//
//  The 1 GB drive. Files are encrypted on this device before they are uploaded, so what
//  the server stores — including the FILENAME — is opaque to it. That is why this screen
//  shows a spinner while a list loads: the names you are reading were decrypted here.
//
//  Same key as the mailbox, and therefore the same warning: lose the device key and the
//  files are gone. There is no server-side copy of anything readable.
//

import SwiftUI
import UniformTypeIdentifiers
import QuickLook

/// Extensions QuickLook can render inline (images, PDF, plain text). Anything else falls
/// back to download-only, matching the web client's `previewFile`.
private let previewableExtensions: Set<String> = [
    "png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "tiff",
    "pdf",
    "txt", "md", "json", "csv", "log", "xml", "html", "rtf",
]

private func isPreviewable(_ name: String?) -> Bool {
    guard let name else { return false }
    let ext = (name as NSString).pathExtension.lowercased()
    return previewableExtensions.contains(ext)
}

struct MailDriveView: View {
    @StateObject private var mail = OSHIMailClient.shared

    @State private var files: [OSHIMailClient.DriveFile] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var showImporter = false
    @State private var uploadProgress: Double?
    @State private var exportURL: URL?
    @State private var previewItem: PreviewPayload?
    @State private var renamingFile: OSHIMailClient.DriveFile?
    @State private var renameText = ""

    /// Whole-file sealing happens in memory (see `OSHIMailClient.seal`), so the picker
    /// refuses what would not comfortably fit there. The server would accept up to 256 MB;
    /// the limit here is about this device, not about the quota.
    private let maxUploadBytes = 50 * 1024 * 1024

    var body: some View {
        List {
            if let account = mail.account {
                Section {
                    quotaBar(used: account.drive.used, quota: account.drive.quota)
                } header: {
                    Text("\(account.drive.files) file\(account.drive.files == 1 ? "" : "s")")
                        .textCase(nil)
                }
            }

            Section("mail.drive.filesSection".localized) {
                if isLoading {
                    HStack { ProgressView(); Text("mail.drive.decrypting".localized).foregroundStyle(.secondary) }
                        .font(.footnote)
                } else if files.isEmpty {
                    Text("mail.drive.emptyTitle".localized).foregroundStyle(.secondary).font(.callout)
                } else {
                    ForEach(files) { file in
                        Button { Task { await handleTap(file) } } label: {
                            HStack {
                                Image(systemName: isPreviewable(file.name) ? "eye" : "doc")
                                    .foregroundStyle(Theme.brand)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(file.name ?? "Sealed for another device")
                                        .font(.callout)
                                        .foregroundStyle(file.name == nil ? .secondary : .primary)
                                        .lineLimit(1)
                                    Text(ByteCountFormatter.string(fromByteCount: Int64(file.bytes), countStyle: .file))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: isPreviewable(file.name) ? "eye.circle" : "arrow.down.circle")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) { Task { await remove(file) } } label: {
                                Label("common.delete".localized, systemImage: "trash")
                            }
                            Button { Task { await download(file) } } label: {
                                Label("mail.drive.download".localized, systemImage: "square.and.arrow.down")
                            }.tint(Theme.brand)
                        }
                        .swipeActions(edge: .leading) {
                            Button { startRename(file) } label: {
                                Label("mail.drive.rename".localized, systemImage: "pencil")
                            }.tint(.orange)
                        }
                    }
                    .onDelete { indexSet in
                        Task {
                            for i in indexSet where i < files.count {
                                await remove(files[i])
                            }
                        }
                    }
                }
            }

            if let uploadProgress {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("mail.drive.uploading".localized).font(.caption)
                        ProgressView(value: uploadProgress).tint(Theme.brand)
                    }
                }
            }

            if let error {
                Section { Text(error).font(.footnote).foregroundStyle(.red) }
            }
        }
        .navigationTitle("mail.drive.title".localized)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showImporter = true } label: { Image(systemName: "plus") }
                    .disabled(uploadProgress != nil)
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.item]) { result in
            Task { await upload(result) }
        }
        .sheet(item: Binding(
            get: { exportURL.map { ExportPayload(url: $0) } },
            set: { if $0 == nil { exportURL = nil } })) { payload in
            ShareSheet(items: [payload.url])
        }
        .sheet(item: $previewItem) { payload in
            NavigationStack {
                QuickLookPreview(url: payload.url)
                    .navigationTitle(payload.name)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("common.done".localized) { previewItem = nil }
                        }
                    }
            }
        }
        .alert("mail.drive.renameTitle".localized, isPresented: Binding(
            get: { renamingFile != nil },
            set: { if !$0 { renamingFile = nil } })) {
            TextField("mail.drive.namePlaceholder".localized, text: $renameText)
                .textInputAutocapitalization(.never)
            Button("common.cancel".localized, role: .cancel) { renamingFile = nil }
            Button("common.save".localized) { Task { await performRename() } }
        } message: {
            Text("mail.drive.renameHint".localized)
        }
        .refreshable { await load() }
        .task { await load() }
    }

    private func quotaBar(used: Int, quota: Int) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text("mail.drive.title".localized).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text("\(ByteCountFormatter.string(fromByteCount: Int64(used), countStyle: .file)) of \(ByteCountFormatter.string(fromByteCount: Int64(quota), countStyle: .file))")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            ProgressView(value: Double(used), total: Double(max(quota, 1)))
                .tint(Double(used) / Double(max(quota, 1)) > 0.9 ? .red : Theme.brand)
        }
        .padding(.vertical, 4)
    }

    // MARK: Actions

    private func load() async {
        error = nil
        do {
            files = try await mail.listDriveFiles()
            _ = try? await mail.refreshAccount()
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    private func upload(_ result: Result<URL, Error>) async {
        error = nil
        do {
            let url = try result.get()
            // A file picked from iCloud Drive or another app arrives security-scoped;
            // reading it without this returns nothing and looks like an empty file.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }

            let data = try Data(contentsOf: url)
            guard data.count <= maxUploadBytes else {
                error = "That file is \(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file)). The limit is \(ByteCountFormatter.string(fromByteCount: Int64(maxUploadBytes), countStyle: .file)) per file."
                return
            }

            uploadProgress = 0
            defer { uploadProgress = nil }

            // Name and body are sealed separately: the server needs the body's length to
            // bill the quota, and must not learn the name at all.
            let meta = try JSONSerialization.data(withJSONObject: ["name": url.lastPathComponent])
            let sealedName = try mail.seal(meta)
            let sealedBody = try mail.seal(data)

            _ = try await mail.uploadToDrive(encrypted: sealedBody, sealedName: sealedName) { p in
                uploadProgress = p
            }
            await load()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func download(_ file: OSHIMailClient.DriveFile) async {
        error = nil
        do {
            let sealed = try await mail.downloadFromDrive(id: file.id)
            let plain = try mail.open(sealed)
            let name = file.name ?? "oshi-file"
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ dossier de partage protégé, purgé
            // au partage suivant / au lancement (avant: `tmp/<nom>`, jamais supprimé).
            exportURL = try MediaPlaintextTemp.writeForShare(plain, fileName: name)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func remove(_ file: OSHIMailClient.DriveFile) async {
        do {
            try await mail.deleteFromDrive(id: file.id)
            await load()
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Tapping a row previews it when the type supports that, and otherwise falls back
    /// to the same download this row always offered — nothing that used to work stops.
    private func handleTap(_ file: OSHIMailClient.DriveFile) async {
        if isPreviewable(file.name) {
            await preview(file)
        } else {
            await download(file)
        }
    }

    /// Decrypts a drive file and hands it to QuickLook. The plaintext is written to a
    /// fresh, per-preview temp directory (not the shared download one) so two previews in
    /// a row never collide on the same filename.
    private func preview(_ file: OSHIMailClient.DriveFile) async {
        error = nil
        do {
            let sealed = try await mail.downloadFromDrive(id: file.id)
            let plain = try mail.open(sealed)
            let name = file.name ?? "oshi-file"
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ copie protégée, supprimée à la
            // fermeture de l'aperçu (`Coordinator.deinit`) et balayée au lancement.
            let dest = try MediaPlaintextTemp.write(plain, fileName: name)
            previewItem = PreviewPayload(url: dest, name: name)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func startRename(_ file: OSHIMailClient.DriveFile) {
        renamingFile = file
        renameText = file.name ?? ""
    }

    /// Re-seals the metadata HERE and PATCHes only the new sealed blob — the server swaps
    /// one opaque value for another and never learns either name.
    private func performRename() async {
        guard let file = renamingFile else { return }
        let name = renameText.trimmingCharacters(in: .whitespaces)
        renamingFile = nil
        guard !name.isEmpty else { return }
        do {
            try await mail.renameDriveFile(id: file.id, name: name)
            await load()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// `sheet(item:)` needs an Identifiable; a bare URL is not one.
private struct ExportPayload: Identifiable {
    let url: URL
    var id: String { url.path }
}

private struct PreviewPayload: Identifiable {
    let url: URL
    let name: String
    var id: String { url.path }
}

/// Wraps `QLPreviewController` so images, PDFs and text files can be shown inline without
/// hand-rolling a renderer for each — QuickLook already knows all three, and the framework
/// is available on Mac Catalyst as well as iOS.
struct QuickLookPreview: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        deinit { MediaPlaintextTemp.remove(url) }  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

/// Picks a drive file to attach to an email.
///
/// The file has to be fetched and DECRYPTED here before it can be attached: it is stored
/// sealed, and the server cannot unseal it to attach it on our behalf. That is why this
/// shows a progress state rather than attaching instantly.
struct MailDriveAttachPicker: View {
    let onPick: (OSHIMailClient.Attachment) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var mail = OSHIMailClient.shared
    @State private var files: [OSHIMailClient.DriveFile] = []
    @State private var loading = true
    @State private var fetching: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if loading {
                    HStack { ProgressView(); Text("mail.drive.decrypting".localized).foregroundStyle(.secondary) }
                        .font(.footnote)
                } else if files.isEmpty {
                    Text("mail.drive.emptyBody".localized).foregroundStyle(.secondary)
                } else {
                    ForEach(files) { file in
                        Button {
                            Task { await pick(file) }
                        } label: {
                            HStack {
                                Image(systemName: "doc").foregroundStyle(Theme.brand)
                                Text(file.name ?? "Sealed for another device")
                                    .foregroundStyle(file.name == nil ? .secondary : .primary)
                                    .lineLimit(1)
                                Spacer()
                                if fetching == file.id {
                                    ProgressView()
                                } else {
                                    Text(ByteCountFormatter.string(fromByteCount: Int64(file.bytes), countStyle: .file))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .disabled(file.name == nil || fetching != nil)
                    }
                }

                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
            .navigationTitle("mail.compose.attachDrive".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("common.cancel".localized) { dismiss() } } }
        }
        .task {
            files = (try? await mail.listDriveFiles()) ?? []
            loading = false
        }
    }

    private func pick(_ file: OSHIMailClient.DriveFile) async {
        fetching = file.id
        defer { fetching = nil }
        do {
            let sealed = try await mail.downloadFromDrive(id: file.id)
            let plain = try mail.open(sealed)
            onPick(.init(filename: file.name ?? "file",
                         mimeType: "application/octet-stream",
                         data: plain))
        } catch {
            self.error = error.localizedDescription
        }
    }
}
