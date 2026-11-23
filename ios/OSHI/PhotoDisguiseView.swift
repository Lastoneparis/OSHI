//
//  PhotoDisguiseView.swift
//  OSHI — "Privacy disguise" settings, "Disguise as photo" sheet, reveal flow.
//
//  Audit 2026-11-18 (findings 4): the former "Enable Steganography" toggle was
//  wired to nothing and its strings promised "bypass censorship by hiding
//  messages in images". It is replaced by a REAL feature, STEGO-v1
//  (docs/stego/STEGO-v1.md, StegoV1.swift), interoperable with Android, and by
//  captions that say exactly what the cover transport does and does not hide.
//

import SwiftUI
import UIKit
import PhotosUI
import ImageIO
import UniformTypeIdentifiers
import CryptoKit

// MARK: - Glue (identity, contacts, covers)

nonisolated enum PhotoDisguise {
    static let maxCoverSide = 1600

    struct Revealed: Identifiable {
        let id = UUID()
        let senderPublicKey: String
        let senderName: String?
        let payload: StegoV1.Payload
    }

    enum Outcome { case found(Revealed), damaged(String?), notForYou }

    static func keyBytes(_ b64: String) -> Data? {
        var s = b64.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        guard let d = Data(base64Encoded: s), d.count == 32 else { return nil }
        return d
    }

    @MainActor static func myKeys() -> (priv: Data, pub: Data)? {
        guard let k = MessageManager.sharedIdentityManager?.privateKey else { return nil }
        return (k.rawRepresentation, k.publicKey.rawRepresentation)
    }

    /// Every key we hold a conversation or an alias for, with its display name.
    @MainActor static func contacts() -> [(key: String, name: String?)] {
        let aliasData = UserDefaults.standard.data(forKey: "contactAliases") ?? Data()
        let aliases = (try? JSONDecoder().decode([String: String].self, from: aliasData)) ?? [:]
        var keys = Set(aliases.keys)
        for c in MessageManager.shared?.conversations ?? [] { keys.insert(c.participantPublicKey) }
        return keys.map { k in (k, SharedNickname.resolve(alias: aliases[k], nickname: SharedNickname.nickname(for: k))) }
    }

    /// Exact samples for a PNG; UIKit for anything else (which then simply holds no message).
    static func image(from data: Data) -> StegoV1.RgbImage? {
        if StegoPng.isPng(data), let img = try? StegoPng.decode(data) { return img }
        // __STEGO_DAMAGED_PNG_2026_11_18__ A picture whose long side is over 4096 px
        // is downscaled below and so cannot carry a message: refuse it from its
        // header, before UIKit decodes it. A PNG declaring 65535 x 65535 used to keep
        // the reveal busy for ~30 s (Simulator), and a 48-MP camera photo was fully
        // decoded only to be thrown away.
        if !Self.mayCarryMessage(data) { return nil }
        guard let ui = UIImage(data: data), let cg = ui.cgImage else { return nil }
        return rgb(from: cg, maxSide: 4096)
    }

    /// Header-only size check (ImageIO properties, no pixel decode).
    static func mayCarryMessage(_ data: Data) -> Bool {
        if let s = StegoPng.declaredSize(data) { return s.width <= 4096 && s.height <= 4096 }
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int else { return false }
        return w > 0 && h > 0 && w <= 4096 && h <= 4096
    }

    static func rgb(from cg: CGImage, maxSide: Int) -> StegoV1.RgbImage? {
        let scale = min(1.0, Double(maxSide) / Double(max(cg.width, cg.height)))
        let w = max(1, Int(Double(cg.width) * scale)), h = max(1, Int(Double(cg.height) * scale))
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        for i in 0..<(w * h) { rgb[i * 3] = rgba[i * 4]; rgb[i * 3 + 1] = rgba[i * 4 + 1]; rgb[i * 3 + 2] = rgba[i * 4 + 2] }
        return StegoV1.RgbImage(width: w, height: h, rgb: rgb)
    }

    static func uiImage(_ img: StegoV1.RgbImage) -> UIImage? {
        var rgba = [UInt8](repeating: 255, count: img.width * img.height * 4)
        for i in 0..<(img.width * img.height) { rgba[i * 4] = img.rgb[i * 3]; rgba[i * 4 + 1] = img.rgb[i * 3 + 1]; rgba[i * 4 + 2] = img.rgb[i * 3 + 2] }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let cg = CGImage(width: img.width, height: img.height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: img.width * 4,
                               space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// An innocuous generated landscape with film grain (not part of the wire format).
    static func generatedCover(w: Int = 1280, h: Int = 960) -> StegoV1.RgbImage {
        var g = SystemRandomNumberGenerator()
        let palettes: [[Double]] = [[255, 170, 120, 90, 120, 200], [120, 180, 240, 220, 235, 250], [250, 200, 150, 160, 120, 170]]
        let sky = palettes.randomElement(using: &g)!
        func rnd(_ upper: Double) -> Double { Double.random(in: 0..<upper, using: &g) }
        var hills: [(Double, Double, Double, Double)] = []
        var hillColor: [[Double]] = []
        for k in 0..<4 {
            let dk = Double(k)
            let base: Double = 0.45 + dk * 0.12 + rnd(0.05)
            let phase: Double = rnd(2 * Double.pi)
            let freq: Double = 1.5 + rnd(2.5)
            let amp: Double = 0.03 + rnd(0.05)
            hills.append((base, phase, freq, amp))
            let r: Double = 40 + dk * 18 + rnd(20)
            let gr: Double = 70 + dk * 20 + rnd(30)
            let b: Double = 50 + dk * 10 + rnd(20)
            hillColor.append([r, gr, b])
        }
        let sunX: Double = 0.2 + rnd(0.6)
        let sunY: Double = 0.25 + rnd(0.15)
        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        var noise = [UInt8](repeating: 0, count: w * h * 3)
        _ = SecRandomCopyBytes(kSecRandomDefault, noise.count, &noise)
        for y in 0..<h {
            let fy = Double(y) / Double(h)
            for x in 0..<w {
                let fx = Double(x) / Double(w)
                var c: [Double] = [sky[0] + (sky[3] - sky[0]) * fy * 1.6,
                                   sky[1] + (sky[4] - sky[1]) * fy * 1.6,
                                   sky[2] + (sky[5] - sky[2]) * fy * 1.6]
                let dy: Double = (fy - sunY) * Double(h) / Double(w)
                let d: Double = hypot(fx - sunX, dy)
                if d < 0.05 {
                    c = [255, 240, 210]
                } else if d < 0.12 {
                    let t: Double = (0.12 - d) / 0.07 * 0.5
                    for i in 0..<3 { c[i] += (255 - c[i]) * t }
                }
                for k in 0..<4 {
                    let (base, phase, freq, amp) = hills[k]
                    let w1: Double = sin(fx * freq * 2 * Double.pi + phase)
                    let w2: Double = sin(fx * freq * 5.3 * Double.pi + phase * 2)
                    let ridge: Double = base + amp * w1 + amp * 0.4 * w2
                    if fy > ridge {
                        let shade: Double = (fy - ridge) * 60
                        for i in 0..<3 { c[i] = hillColor[k][i] - shade }
                    }
                }
                let o = (y * w + x) * 3
                for ch in 0..<3 { rgb[o + ch] = UInt8(max(0, min(255, Int(c[ch]) + Int(noise[o + ch] % 7) - 3))) }
            }
        }
        return StegoV1.RgbImage(width: w, height: h, rgb: rgb)
    }

    static func hide(recipientKey: String, cover: StegoV1.RgbImage, payload: StegoV1.Payload, me: (priv: Data, pub: Data)) throws -> URL {
        guard let r = keyBytes(recipientKey) else { throw StegoV1.Failure.invalidKey }
        let out = try StegoV1.hide(cover: cover, payload: payload, senderPriv: me.priv, senderPub: me.pub, recipientPub: r)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("disguise", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMdd_HHmmss"
        let url = dir.appendingPathComponent("IMG_\(f.string(from: Date())).png")
        try StegoPng.encode(out).write(to: url, options: .completeFileProtection)
        return url
    }

    static func reveal(_ data: Data, me: (priv: Data, pub: Data), contacts: [(key: String, name: String?)]) -> Outcome {
        guard let img = image(from: data) else { return .notForYou }
        let byKey = contacts.compactMap { c in keyBytes(c.key).map { ($0, c) } }
        switch StegoV1.reveal(image: img, myPriv: me.priv, myPub: me.pub, contacts: byKey.map { $0.0 }) {
        case .found(let s, let p):
            let c = byKey.first { $0.0 == s }!.1
            return .found(Revealed(senderPublicKey: c.key, senderName: c.name, payload: p))
        case .damaged(let s): return .damaged(byKey.first { $0.0 == s }?.1.name)
        case .notForYou: return .notForYou
        }
    }
}

private enum DisguiseRows {
    static func warning(_ key: String) -> some View {
    Label { Text(key.localized).font(.caption).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true) }
        icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange) }
    }
}

// MARK: - Settings: Privacy disguise

struct PrivacyDisguiseSettingsView: View {
    @AppStorage("covertChannelEnabled") private var covertChannelEnabled = false
    /// __RESTRICTED_NETWORK_HINT_2026_11_18__ default ON.
    @AppStorage(RestrictedNetworkHint.settingKey) private var restrictedHint = true

    var body: some View {
        List {
            Section {
                Toggle(isOn: $covertChannelEnabled) {
                    Label("disguise.cover.title".localized, systemImage: "network.badge.shield.half.filled")
                }
                .tint(Theme.brand)
                .accessibilityIdentifier("disguise.cover.toggle")
                Text("disguise.cover.caption".localized).font(.caption).foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Label { Text("disguise.cover.not_hidden".localized).font(.caption).foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true) }
                    icon: { Image(systemName: "info.circle").foregroundColor(.orange) }
                Toggle(isOn: $restrictedHint) {
                    Label("rn_hint.setting_title".localized, systemImage: "eye.slash")
                }
                .tint(Theme.brand)
                .disabled(!covertChannelEnabled)
                .accessibilityIdentifier("rn_hint.setting")
                Text("rn_hint.setting_caption".localized).font(.caption).foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Section {
                Text("disguise.photo.explainer".localized).font(.callout).fixedSize(horizontal: false, vertical: true)
                DisguiseRows.warning("disguise.warning.recompress")
                DisguiseRows.warning("disguise.warning.forensic")
                NavigationLink {
                    RevealHiddenMessageView()
                } label: {
                    #if targetEnvironment(macCatalyst)
                    HStack {
                        Label("disguise.reveal.button".localized, systemImage: "eye")
                        Spacer()
                        MacShortcutHint(text: "⇧⌘R")
                    }
                    #else
                    Label("disguise.reveal.button".localized, systemImage: "eye")
                    #endif
                }
                .accessibilityIdentifier("disguise.reveal.link")
                #if targetEnvironment(macCatalyst)
                // The phone explainer says "+ → Disguise as photo"; on the Mac the
                // same action is also in the menu bar, which is where Mac users look.
                Label { Text("disguise.mac.menu_hint".localized).font(.caption).foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true) }
                    icon: { Image(systemName: "menubar.rectangle").foregroundColor(Theme.brand) }
                #endif
            } header: {
                Text("disguise.photo.title".localized)
            }
        }
        #if targetEnvironment(macCatalyst)
        // A settings pane stretched across a 1400 pt window reads as a spreadsheet.
        .frame(maxWidth: 720)
        .frame(maxWidth: .infinity)
        #endif
        .onAppear { _ = CoverTransport.mode }
        .onChange(of: covertChannelEnabled) { _, _ in _ = CoverTransport.mode }
        .navigationTitle("disguise.title".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Chat: Disguise as photo

struct DisguiseAsPhotoSheet: View {
    let contactPublicKey: String
    let contactName: String
    @State var text: String
    /// A photo already made (previews and screen captures of the Mac "Photo ready" state).
    var initialCreated: URL? = nil
    @Environment(\.dismiss) private var dismiss

    @State private var file: StegoV1.Attachment?
    @State private var cover: StegoV1.RgbImage?
    @State private var preview: UIImage?
    @State private var usingPhoto = false
    @State private var photoItem: PhotosPickerItem?
    @State private var importer: DisguiseImport?
    @State private var working = false
    @State private var error: String?
    @State private var shareURL: URL?
    #if targetEnvironment(macCatalyst)
    @State private var created: URL?
    @State private var showExporter = false
    @State private var coverDropTargeted = false
    #endif

    /// One `.fileImporter` per view: SwiftUI honours only the last of several.
    private enum DisguiseImport: Identifiable { case attachment, cover; var id: Self { self } }

    private var needed: Int {
        14 + text.utf8.count + (file.map { 8 + $0.name.utf8.count + $0.mime.utf8.count + $0.data.count } ?? 0)
    }
    private var capacity: Int { cover.map { StegoV1.capacityBytes(slots: $0.slots) } ?? 0 }
    private var fits: Bool { cover != nil && needed <= capacity }
    private var canCreate: Bool {
        fits && !working && !(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && file == nil)
    }
    private func size(_ n: Int) -> String { ByteCountFormatter.string(fromByteCount: Int64(n), countStyle: .file) }

    var body: some View {
        NavigationStack {
            Form {
                #if targetEnvironment(macCatalyst)
                if let url = created { MacDisguiseResultSection(url: url, contactName: contactName, showExporter: $showExporter) }
                #endif
                Section {
                    TextEditor(text: $text).frame(minHeight: 100).accessibilityIdentifier("disguise.text")
                    if let f = file {
                        Button(role: .destructive) { file = nil } label: {
                            Label(String(format: "disguise.remove_file".localized, "\(f.name) (\(size(f.data.count)))"), systemImage: "xmark.circle")
                        }
                    } else {
                        Button { importer = .attachment } label: { Label("disguise.add_file".localized, systemImage: "paperclip") }
                    }
                } header: { Text("disguise.message_hint".localized) }
                  footer: { Text(String(format: "disguise.sheet.for".localized, contactName)) }

                Section {
                    if let p = preview {
                        coverPreview(p)
                    }
                    Picker("", selection: Binding(get: { usingPhoto }, set: { v in
                        if !v { usingPhoto = false; Task { await setCover(PhotoDisguise.generatedCover()) } }
                    })) {
                        Text("disguise.cover.generated".localized).tag(false)
                        Text("disguise.cover.photo".localized).tag(true)
                    }.pickerStyle(.segmented)
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label("disguise.cover.photo".localized, systemImage: "photo")
                    }
                    #if targetEnvironment(macCatalyst)
                    // Most photos on a Mac live in Finder, not in the Photos library.
                    Button { importer = .cover } label: { Label("disguise.mac.cover_file".localized, systemImage: "folder") }
                    #endif
                    Text(String(format: "disguise.capacity".localized, size(needed), size(capacity)))
                        .font(.caption).foregroundColor(fits ? .secondary : .red)
                    if cover != nil && !fits { Text("disguise.too_big".localized).font(.caption).foregroundColor(.red) }
                }
                Section {
                    DisguiseRows.warning("disguise.warning.recompress")
                    DisguiseRows.warning("disguise.warning.forensic")
                }
                if let e = error { Text(e).foregroundColor(.red).font(.caption) }
                Section {
                    Button {
                        create()
                    } label: {
                        HStack {
                            if working { ProgressView() }
                            Text(working ? "disguise.working".localized : createTitle)
                            #if targetEnvironment(macCatalyst)
                            Spacer()
                            MacShortcutHint(text: "⌘↩")
                            #endif
                        }
                    }
                    .disabled(!canCreate)
                    // ⌘↩, not ↩: Return belongs to the message editor above.
                    .keyboardShortcut(.return, modifiers: .command)
                    .accessibilityIdentifier("disguise.create")
                }
            }
            .navigationTitle("disguise.photo.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // An explicit, Esc-bound way out: a Catalyst sheet cannot be swiped away.
                    Button(closeTitle) { dismiss() }.keyboardShortcut(.cancelAction)
                }
            }
            .task {
                #if targetEnvironment(macCatalyst)
                if created == nil, let u = initialCreated { created = u }
                #endif
                if cover == nil { await setCover(PhotoDisguise.generatedCover()) }
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) { await useCover(data) }
                }
            }
            .fileImporter(isPresented: Binding(get: { importer != nil }, set: { if !$0 { importer = nil } }),
                          allowedContentTypes: importer == .cover ? [.image] : [.item]) { result in
                let mode = importer
                importer = nil
                guard case .success(let url) = result else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if mode == .cover {
                    if let data = try? Data(contentsOf: url) { Task { await useCover(data) } }
                    return
                }
                guard let data = try? Data(contentsOf: url), data.count <= StegoV1.maxFile else {
                    error = "disguise.file_too_large".localized; return
                }
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                file = StegoV1.Attachment(name: url.lastPathComponent, mime: mime, data: data); error = nil
            }
            #if targetEnvironment(macCatalyst)
            .fileExporter(isPresented: $showExporter,
                          document: created.flatMap { try? Data(contentsOf: $0) }.map { DisguiseFileDocument(data: $0) },
                          contentType: .png,
                          defaultFilename: created?.lastPathComponent ?? "IMG.png") { _ in }
            #else
            .sheet(item: Binding(get: { shareURL.map { IdentifiedURL(url: $0) } }, set: { if $0 == nil { shareURL = nil; dismiss() } })) { u in
                ShareSheet(items: [u.url])
            }
            #endif
        }
        .macSheetSizing(minWidth: 520, idealWidth: 600, minHeight: 640, idealHeight: 780)
    }

    private var closeTitle: String {
        #if targetEnvironment(macCatalyst)
        created == nil ? "cancel".localized : "disguise.mac.done".localized
        #else
        "cancel".localized
        #endif
    }

    private var createTitle: String {
        #if targetEnvironment(macCatalyst)
        created == nil ? "disguise.mac.create".localized : "disguise.mac.create_again".localized
        #else
        "disguise.create_share".localized
        #endif
    }

    @ViewBuilder private func coverPreview(_ p: UIImage) -> some View {
        let image = Image(uiImage: p).resizable().scaledToFill()
        #if targetEnvironment(macCatalyst)
        // Drop any image from Finder onto the cover to use it instead.
        image.frame(height: 220).clipped().cornerRadius(12)
            .overlay(RoundedRectangle(cornerRadius: 12)
                .strokeBorder(Theme.brand, style: StrokeStyle(lineWidth: coverDropTargeted ? 3 : 0, dash: [7, 5])))
            .overlay(alignment: .bottomTrailing) {
                Text("disguise.mac.drop_cover".localized).font(.caption2.weight(.medium))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.ultraThinMaterial, in: Capsule()).padding(8)
            }
            .onDrop(of: [.fileURL, .image], isTargeted: $coverDropTargeted) { providers in
                PhotoDisguise.loadDroppedData(providers) { data in Task { await useCover(data) } }
            }
        #else
        image.frame(height: 150).clipped().cornerRadius(12)
        #endif
    }

    private struct IdentifiedURL: Identifiable { let url: URL; var id: String { url.path } }

    @MainActor private func useCover(_ data: Data) async {
        guard let ui = UIImage(data: data), let cg = ui.cgImage,
              let img = PhotoDisguise.rgb(from: cg, maxSide: PhotoDisguise.maxCoverSide) else { return }
        usingPhoto = true
        await setCover(img)
    }

    @MainActor private func setCover(_ img: StegoV1.RgbImage) async {
        cover = img
        preview = PhotoDisguise.uiImage(img)
    }

    private func create() {
        guard canCreate else { return }
        guard let c = cover, let me = PhotoDisguise.myKeys() else { error = "disguise.failed".localized; return }
        working = true; error = nil
        let payload = StegoV1.Payload(text: text, createdAtMs: Int64(Date().timeIntervalSince1970 * 1000), file: file)
        let key = contactPublicKey
        Task.detached(priority: .userInitiated) {
            let result = Result { try PhotoDisguise.hide(recipientKey: key, cover: c, payload: payload, me: me) }
            await MainActor.run {
                working = false
                switch result {
                case .success(let url):
                    #if targetEnvironment(macCatalyst)
                    // On the Mac the photo stays in the window: drag it out, save it
                    // or share it. A share sheet inside a sheet is a popover with
                    // nothing to point at.
                    withAnimation(.easeOut(duration: 0.2)) { created = url }
                    #else
                    shareURL = url
                    #endif
                case .failure: error = "disguise.failed".localized
                }
            }
        }
    }
}

// MARK: - Reveal

struct RevealHiddenMessageView: View {
    /// Pre-computed outcome (a disguised photo dropped onto a chat).
    var initialOutcome: PhotoDisguise.Outcome? = nil
    @State private var showImporter = false
    @State private var photoItem: PhotosPickerItem?
    @State private var checking = false
    @State private var outcome: PhotoDisguise.Outcome?
    @State private var dropTargeted = false

    var body: some View {
        List {
            #if targetEnvironment(macCatalyst)
            // On the Mac the answer goes FIRST: the photo was just dropped here,
            // and a result below the fold reads as "nothing happened".
            resultSection
            Section {
                MacRevealDropZone(targeted: dropTargeted)
                    .listRowInsets(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12))
            }
            #endif
            Section {
                Button { showImporter = true } label: { macRow("disguise.reveal.from_files".localized, "folder", "⌘O") }
                    .keyboardShortcut("o", modifiers: .command)
                    .accessibilityIdentifier("disguise.reveal.files")
                PhotosPicker(selection: $photoItem, matching: .images) { Label("disguise.reveal.from_photos".localized, systemImage: "photo") }
                Button { pasteImage() } label: { macRow("disguise.reveal.paste".localized, "doc.on.clipboard", "⌘V") }
                    .keyboardShortcut("v", modifiers: .command)
                    .disabled(!UIPasteboard.general.contains(pasteboardTypes: [UTType.png.identifier, UTType.fileURL.identifier]))
            } footer: { Text("disguise.reveal.hint".localized) }
            #if !targetEnvironment(macCatalyst)
            resultSection
            #endif
        }
        .navigationTitle("disguise.reveal.button".localized)
        .navigationBarTitleDisplayMode(.inline)
        // Drop a PNG anywhere on the list (Finder, Mail, the iPad's split view).
        .onDrop(of: [.fileURL, .png], isTargeted: $dropTargeted) { providers in
            PhotoDisguise.loadDroppedData(providers) { data in Task { @MainActor in run(data) } }
        }
        .onAppear { if outcome == nil, let o = initialOutcome { outcome = o } }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.png, .image, .data]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let d = try? Data(contentsOf: url) { run(d) } else { outcome = .notForYou }
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task { if let d = try? await item.loadTransferable(type: Data.self) { run(d) } }
        }
    }

    @ViewBuilder private var resultSection: some View {
        if checking {
            Section { HStack { ProgressView(); Text("disguise.reveal.checking".localized) } }
        } else if let o = outcome {
            Section { RevealOutcomeView(outcome: o) } header: { Text("disguise.reveal.title".localized) }
        }
    }

    @ViewBuilder private func macRow(_ title: String, _ icon: String, _ shortcut: String) -> some View {
        #if targetEnvironment(macCatalyst)
        HStack { Label(title, systemImage: icon); Spacer(); MacShortcutHint(text: shortcut) }
        #else
        Label(title, systemImage: icon)
        #endif
    }

    private func pasteImage() {
        let pb = UIPasteboard.general
        if let d = pb.data(forPasteboardType: UTType.png.identifier) { run(d); return }
        if let u = pb.url, u.isFileURL, let d = try? Data(contentsOf: u) { run(d) }
    }

    private func run(_ data: Data) {
        guard let me = PhotoDisguise.myKeys() else { outcome = .notForYou; return }
        let contacts = PhotoDisguise.contacts()
        checking = true; outcome = nil
        Task.detached(priority: .userInitiated) {
            let o = PhotoDisguise.reveal(data, me: me, contacts: contacts)
            await MainActor.run { outcome = o; checking = false }
        }
    }
}

struct RevealOutcomeView: View {
    let outcome: PhotoDisguise.Outcome
    @State private var fileURL: URL?
    #if targetEnvironment(macCatalyst)
    @State private var exportFile: StegoV1.Attachment?
    #endif

    var body: some View {
        switch outcome {
        case .found(let r):
            VStack(alignment: .leading, spacing: 8) {
                #if targetEnvironment(macCatalyst)
                HStack(spacing: 8) {
                    Image(systemName: "lock.open.fill").foregroundColor(Theme.brand)
                    Text(String(format: "disguise.reveal.from".localized, r.senderName ?? String(r.senderPublicKey.prefix(12)) + "…")).font(.headline)
                }
                #else
                Text(String(format: "disguise.reveal.from".localized, r.senderName ?? String(r.senderPublicKey.prefix(12)) + "…")).font(.headline)
                #endif
                // __RESTRICTED_NETWORK_HINT_2026_11_18__ a revealed photo always went
                // around the network: same chip as a flagged bubble (local flag).
                RestrictedNetworkChip()
                Text(String(format: "disguise.reveal.hidden_on".localized,
                            DateFormatter.localizedString(from: Date(timeIntervalSince1970: Double(r.payload.createdAtMs) / 1000), dateStyle: .medium, timeStyle: .short)))
                    .font(.caption).foregroundColor(.secondary)
                if !r.payload.text.isEmpty {
                    Text(r.payload.text).textSelection(.enabled).accessibilityIdentifier("disguise.reveal.text")
                        #if targetEnvironment(macCatalyst)
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.brand.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                        #endif
                    Button("disguise.reveal.copy".localized) { UIPasteboard.general.string = r.payload.text }
                        .keyboardShortcut("c", modifiers: [.command, .shift])
                }
                if let f = r.payload.file {
                    #if targetEnvironment(macCatalyst)
                    Button { exportFile = f } label: {
                        Label(String(format: "disguise.mac.save_file".localized, f.name), systemImage: "square.and.arrow.down")
                    }
                    #else
                    Button(String(format: "disguise.reveal.open_file".localized, f.name)) {
                        let url = FileManager.default.temporaryDirectory.appendingPathComponent(
                            f.name.replacingOccurrences(of: "/", with: "_").prefix(80).description)
                        try? f.data.write(to: url, options: .completeFileProtection)
                        fileURL = url
                    }
                    #endif
                }
            }
            #if targetEnvironment(macCatalyst)
            .fileExporter(isPresented: Binding(get: { exportFile != nil }, set: { if !$0 { exportFile = nil } }),
                          document: exportFile.map { DisguiseFileDocument(data: $0.data) },
                          contentType: exportFile.flatMap { UTType(mimeType: $0.mime) } ?? .data,
                          defaultFilename: exportFile.map { String($0.name.replacingOccurrences(of: "/", with: "_").prefix(80)) }) { _ in }
            #else
            .sheet(item: Binding(get: { fileURL.map { RevealFile(url: $0) } }, set: { if $0 == nil { fileURL = nil } })) { ShareSheet(items: [$0.url]) }
            #endif
        case .damaged(let name):
            Label { Text(String(format: "disguise.reveal.damaged".localized, name ?? "?")) }
                icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange) }
        case .notForYou:
            Label { Text("disguise.reveal.not_found".localized) }
                icon: { Image(systemName: "eye.slash").foregroundColor(.secondary) }
        }
    }
    private struct RevealFile: Identifiable { let url: URL; var id: String { url.path } }
}

/// Reveal in its own sheet, with a way out: the Mac menu command (⇧⌘R) and a
/// disguised photo dropped onto a chat both open this.
struct RevealHiddenMessageSheet: View {
    var initialOutcome: PhotoDisguise.Outcome? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            RevealHiddenMessageView(initialOutcome: initialOutcome)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("disguise.mac.done".localized) { dismiss() }.keyboardShortcut(.cancelAction)
                    }
                }
        }
        .macSheetSizing(minWidth: 520, idealWidth: 580, minHeight: 560, idealHeight: 680)
    }
}

/// Identifiable box so an outcome can drive `.sheet(item:)`.
struct RevealOutcomeBox: Identifiable {
    let id = UUID()
    let outcome: PhotoDisguise.Outcome
}

// MARK: - Shared helpers (drop, export)

extension PhotoDisguise {
    /// First dropped item as bytes: a file URL (Finder, Mail attachment) or raw PNG data.
    /// Calls back on an arbitrary queue. Returns whether a provider was accepted.
    @discardableResult
    static func loadDroppedData(_ providers: [NSItemProvider], _ done: @escaping @Sendable (Data) -> Void) -> Bool {
        guard let p = providers.first else { return false }
        if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            _ = p.loadObject(ofClass: URL.self) { url, _ in
                guard let url else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if let d = try? Data(contentsOf: url) { done(d) }
            }
            return true
        }
        for type in [UTType.png, UTType.image] where p.hasItemConformingToTypeIdentifier(type.identifier) {
            p.loadDataRepresentation(forTypeIdentifier: type.identifier) { d, _ in if let d { done(d) } }
            return true
        }
        return false
    }

    /// Pre-check for a file dropped onto a chat, BEFORE it is sent: a PNG that
    /// carries a STEGO-v1 message for us is opened, not forwarded. Anything else
    /// (not a PNG, made for someone else, made BY us) returns nil and is sent as usual.
    /// Pure — call off the main thread with keys/contacts captured on it.
    static func revealIfDisguised(fileAt url: URL, me: (priv: Data, pub: Data), contacts: [(key: String, name: String?)]) -> Outcome? {
        guard let d = try? Data(contentsOf: url), StegoPng.isPng(d) else { return nil }
        let o = reveal(d, me: me, contacts: contacts)
        if case .notForYou = o { return nil }
        return o
    }
}

/// Raw bytes for `.fileExporter` (a PNG, or a revealed attachment).
nonisolated struct DisguiseFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.png, .data] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let d = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        data = d
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

extension View {
    /// A Mac sheet sized like a document window; identity elsewhere.
    @ViewBuilder func macSheetSizing(minWidth: CGFloat, idealWidth: CGFloat, minHeight: CGFloat, idealHeight: CGFloat) -> some View {
        #if targetEnvironment(macCatalyst)
        if #available(macCatalyst 18.0, *) {
            self.frame(minWidth: minWidth, idealWidth: idealWidth, minHeight: minHeight, idealHeight: idealHeight)
                .presentationSizing(.fitted)
        } else {
            self.frame(minWidth: minWidth, idealWidth: idealWidth, minHeight: minHeight, idealHeight: idealHeight)
        }
        #else
        self
        #endif
    }
}

// MARK: - Mac (Catalyst) pieces

/// A key-cap style hint ("⇧⌘R") at the end of a row, the way Mac menus show shortcuts.
struct MacShortcutHint: View {
    let text: String
    var body: some View {
        Text(text).font(.caption.monospaced()).foregroundColor(.secondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 5).strokeBorder(Color.secondary.opacity(0.35)))
            .accessibilityHidden(true)
    }
}

#if targetEnvironment(macCatalyst)

/// The big "drop a photo here" well at the top of Reveal on the Mac.
struct MacRevealDropZone: View {
    let targeted: Bool
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: targeted ? "photo.badge.checkmark" : "photo.on.rectangle.angled")
                .font(.system(size: 38, weight: .regular))
                .foregroundColor(targeted ? Theme.brand : .secondary)
                .symbolRenderingMode(.hierarchical)
            Text("disguise.mac.drop_title".localized).font(.headline)
            Text("disguise.mac.drop_subtitle".localized).font(.caption).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 150)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.brand.opacity(targeted ? 0.12 : 0.04)))
        .overlay(RoundedRectangle(cornerRadius: 14)
            .strokeBorder(targeted ? Theme.brand : Color.secondary.opacity(0.4),
                          style: StrokeStyle(lineWidth: targeted ? 2.5 : 1.5, dash: [8, 6])))
        .animation(.easeOut(duration: 0.15), value: targeted)
        .accessibilityElement(children: .combine)
    }
}

/// "Photo ready" on the Mac: the photo itself (drag it into Mail or Finder),
/// Save… (⌘S, a real save panel) and Share… (the system share menu).
private struct MacDisguiseResultSection: View {
    let url: URL
    let contactName: String
    @Binding var showExporter: Bool

    var body: some View {
        Section {
            HStack(alignment: .top, spacing: 14) {
                SwiftUI.Group {
                    if let img = UIImage(contentsOfFile: url.path) {
                        Image(uiImage: img).resizable().scaledToFill()
                    } else {
                        Color.secondary.opacity(0.2)
                    }
                }
                .frame(width: 150, height: 112).clipped().cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Color.secondary.opacity(0.25)))
                .onDrag { NSItemProvider(contentsOf: url) ?? NSItemProvider() }
                .accessibilityLabel(Text(url.lastPathComponent))
                VStack(alignment: .leading, spacing: 6) {
                    Label("disguise.mac.ready".localized, systemImage: "checkmark.seal.fill")
                        .font(.headline).foregroundColor(Theme.brand)
                    Text(url.lastPathComponent).font(.caption.monospaced()).foregroundColor(.secondary)
                    Text("disguise.mac.drag_hint".localized).font(.caption).foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 4)
            HStack(spacing: 12) {
                Button { showExporter = true } label: {
                    Label("disguise.mac.save".localized, systemImage: "square.and.arrow.down")
                }
                .keyboardShortcut("s", modifiers: .command)
                .buttonStyle(.borderedProminent).tint(Theme.brand)
                ShareLink(item: url) { Label("disguise.mac.share".localized, systemImage: "square.and.arrow.up") }
                    .buttonStyle(.bordered)
                Button {
                    if let d = try? Data(contentsOf: url) { UIPasteboard.general.setData(d, forPasteboardType: UTType.png.identifier) }
                } label: { Label("disguise.mac.copy_photo".localized, systemImage: "doc.on.doc") }
                    .buttonStyle(.bordered)
                Spacer(minLength: 0)
            }
        }
        .accessibilityIdentifier("disguise.mac.result")
    }
}

/// Menu bar: File ▸ Disguise as Photo… (⇧⌘D, enabled in a 1:1 chat) and
/// File ▸ Reveal Hidden Message… (⇧⌘R, always).
struct PrivacyDisguiseCommands: Commands {
    @FocusedValue(\.disguiseAsPhoto) private var disguise: DisguiseFocusedAction?

    var body: some Commands {
        CommandGroup(after: .importExport) {
            Section {
                Button("disguise.mac.menu_disguise".localized) { disguise?.open() }
                    .keyboardShortcut("d", modifiers: [.command, .shift])
                    .disabled(disguise == nil)
                Button("disguise.mac.menu_reveal".localized) {
                    NotificationCenter.default.post(name: PhotoDisguise.revealRequested, object: nil)
                }
                .keyboardShortcut("r", modifiers: [.command, .shift])
            }
        }
    }
}

/// Root-level host for the Reveal sheet the menu command opens.
struct PrivacyDisguiseRevealHost: ViewModifier {
    @State private var show = false
    func body(content: Content) -> some View {
        content
            .onReceive(NotificationCenter.default.publisher(for: PhotoDisguise.revealRequested)) { _ in show = true }
            .sheet(isPresented: $show) { RevealHiddenMessageSheet() }
    }
}

#endif

/// The open-the-disguise-sheet action of the focused 1:1 chat (Mac menu bar).
struct DisguiseFocusedAction {
    let open: () -> Void
}

nonisolated struct DisguiseFocusedActionKey: FocusedValueKey {
    typealias Value = DisguiseFocusedAction
}

extension FocusedValues {
    var disguiseAsPhoto: DisguiseFocusedAction? {
        get { self[DisguiseFocusedActionKey.self] }
        set { self[DisguiseFocusedActionKey.self] = newValue }
    }
}

extension PhotoDisguise {
    static let revealRequested = Notification.Name("oshi.disguise.revealRequested")
}
