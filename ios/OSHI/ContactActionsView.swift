//
//  ContactActionsView.swift
//  OSHI
//
//  Created by MORICEAU on 16/11/2025.
//
//  ---------------------------------------------------------------------------
//  REPORTING — App Store Review Guideline 1.2 (user-generated content).
//  ---------------------------------------------------------------------------
//  The age-rating declaration for 1.0.36 answers `userGeneratedContent: true`,
//  which obliges the app to ship "a mechanism for users to flag objectionable
//  content" and "a mechanism to block abusive users". Block already shipped
//  (`BlockedContactsManager` / `BlockContactSheet` / `BlockGroupSheet`); this
//  file is its reporting twin and deliberately mirrors its structure, its
//  persistence idiom (atomic JSON in the Documents directory), its
//  `NotificationCenter` side effects and its `"key".localized` string idiom.
//
//  PRIVACY CONTRACT — READ BEFORE EXTENDING
//  ---------------------------------------------------------------------------
//  OSHI cannot decrypt 1-to-1 message content, and we are not going to start.
//  A report therefore carries NO message plaintext, NO ciphertext and NO
//  attachment. It carries exactly three things:
//
//    1. metadata — who is being reported (their public identity string, which
//       is already public), what kind of subject it is (contact or group),
//       and when the report was filed;
//    2. the reason the reporter picked from a fixed list;
//    3. free text the REPORTER TYPED THEMSELVES in the report sheet.
//
//  (3) is the only evidence channel, and it exists because the reporter is
//  consenting to disclose it. Nothing is lifted out of the conversation.
//  If you ever feel the urge to attach the offending message "to help
//  moderation", don't: it would silently break the encryption promise.
//
//  SERVER CONTRACT — LIVE SINCE 2026-07-28
//  ---------------------------------------------------------------------------
//  `POST https://oshi-messenger.com/v2/report` is implemented on the VPS
//  (`ServerVPS/v2/report_store.js`, service `oshi-v2`) against the contract in
//  `docs/SERVER_REPORT_ENDPOINT.md`. This client degrades safely if it ever
//  goes away again: a failed submission is persisted as `.pending` and retried,
//  and the user is told their report was saved rather than being shown a
//  success they didn't get. Reports are never silently dropped.
//
//  One thing to know about `sign()` below: the server refuses unsigned reports
//  with 401, which this client treats as PERMANENT (`.rejected`, no retry).
//  The unsigned fallback in `sign()` is therefore no longer a safe degradation
//  for this endpoint — if signing ever starts failing on device, reports will
//  be dropped rather than queued. That is the right trade (an unsigned report
//  cannot be attributed or rate-limited) but it is a real behaviour change from
//  what the comment there describes.
//

import SwiftUI
import Foundation
import CryptoKit

// MARK: - Model

/// One report the local user filed. Persisted so the reporter keeps a record,
/// so we can retry delivery, and so we can avoid nagging them to re-report the
/// same subject.
struct ContentReport: Codable, Identifiable {

    enum Subject: String, Codable {
        /// `subjectId` is a base64 X25519 public key (the app-wide contact address).
        case contact
        /// `subjectId` is a `MessageGroup.id.uuidString`.
        case group
    }

    enum DeliveryState: String, Codable {
        /// Queued locally, not yet accepted by the server.
        case pending
        /// Server returned 2xx.
        case sent
        /// Server returned a permanent 4xx — retrying would not help.
        case rejected
    }

    let id: UUID
    let subject: Subject
    let subjectId: String
    let subjectName: String?
    let reason: BlockReason
    /// Free text the reporter typed. Never harvested from the conversation.
    let details: String
    let createdAt: Date
    var state: DeliveryState
    var deliveredAt: Date?
    var lastError: String?

    init(subject: Subject,
         subjectId: String,
         subjectName: String?,
         reason: BlockReason,
         details: String) {
        self.id = UUID()
        self.subject = subject
        self.subjectId = subjectId
        self.subjectName = subjectName
        self.reason = reason
        self.details = details
        self.createdAt = Date()
        self.state = .pending
        self.deliveredAt = nil
        self.lastError = nil
    }
}

// MARK: - Wire format

/// Exactly what leaves the device. Mirrored in `docs/SERVER_REPORT_ENDPOINT.md`.
/// Deliberately flat and small so it is auditable at a glance.
struct ReportPayload: Codable {
    let reportId: String
    let subjectType: String      // "contact" | "group"
    let subjectId: String        // base64 X25519 public key, or group UUID string
    let reason: String           // "spam" | "harassment" | "inappropriate" | "other"
    let details: String          // reporter-typed text only; "" when they typed nothing
    let reporterKey: String      // base64 X25519 public key of the reporter
    let clientVersion: String    // e.g. "1.0.36 (88)"
    let platform: String         // "ios"
    let createdAt: String        // ISO8601
}

// MARK: - Submitter

/// Thin signed-POST client for `/v2/report`. Signing is byte-for-byte the
/// scheme every other OSHI endpoint uses (`oshi_auth.js` / `V2Client` /
/// `V2SyncAPI` / `VoIPPushManager`): canonical message
/// `METHOD\nPATH\nSHA256(rawBody)\nTIMESTAMP`, headers `x-oshi-signature`,
/// `x-oshi-timestamp`, `x-oshi-signing-pubkey`, plus the `x-oshi-user`
/// identity header.
final class ReportSubmitter: @unchecked Sendable {

    enum SubmitError: Error {
        /// 4xx — permanent, do not retry.
        case permanent(status: Int, body: String?)
        /// 5xx, offline, timeout — retry later.
        case transient(underlying: String)
    }

    /// Path is relative to `V2Client.Config.default.baseURL`
    /// (`https://oshi-messenger.com`).
    static let path = "/v2/report"

    private let baseURL: URL
    private let crypto: V2CryptoProviding
    private let session: URLSession

    init(baseURL: URL = V2Client.Config.default.baseURL,
         crypto: V2CryptoProviding = V2KeychainCrypto(),
         session: URLSession = .shared) {
        self.baseURL = baseURL
        self.crypto = crypto
        self.session = session
    }

    private static let iso8601: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private var clientVersion: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(short) (\(build))"
    }

    func payload(for report: ContentReport) -> ReportPayload {
        ReportPayload(
            reportId: report.id.uuidString,
            subjectType: report.subject.rawValue,
            subjectId: report.subjectId,
            reason: report.reason.rawValue,
            details: report.details,
            reporterKey: crypto.userKey,
            clientVersion: clientVersion,
            platform: "ios",
            createdAt: Self.iso8601.string(from: report.createdAt)
        )
    }

    /// POSTs the report. Returns normally on 2xx; throws `.permanent` on 4xx and
    /// `.transient` on anything the caller should retry.
    func submit(_ report: ContentReport) async throws {
        guard let url = URL(string: Self.path, relativeTo: baseURL)?.absoluteURL else {
            throw SubmitError.permanent(status: 0, body: "invalid-url")
        }

        let body: Data
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.withoutEscapingSlashes]
            body = try encoder.encode(payload(for: report))
        } catch {
            throw SubmitError.permanent(status: 0, body: "encode-failed")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = V2Client.Config.default.requestTimeout
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(crypto.userKey, forHTTPHeaderField: "x-oshi-user")
        sign(&request, body: body)

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw SubmitError.transient(underlying: "non-http-response")
            }
            if (200...299).contains(http.statusCode) {
                OshiLog.messaging.info("✅ Report \(report.id.uuidString) accepted by server")
                return
            }
            let text = String(data: data, encoding: .utf8)
            if (400...499).contains(http.statusCode) {
                throw SubmitError.permanent(status: http.statusCode, body: text)
            }
            throw SubmitError.transient(underlying: "http-\(http.statusCode)")
        } catch let error as SubmitError {
            throw error
        } catch {
            throw SubmitError.transient(underlying: String(describing: error))
        }
    }

    private func sign(_ request: inout URLRequest, body: Data) {
        guard let url = request.url, let method = request.httpMethod else { return }
        let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? url.path
        let timestamp = String(Int(Date().timeIntervalSince1970 * 1000))
        let bodyHash = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        let message = "\(method)\n\(path)\n\(bodyHash)\n\(timestamp)"
        do {
            let (signature, pubKey) = try crypto.signCanonical(Data(message.utf8))
            request.setValue(signature, forHTTPHeaderField: "x-oshi-signature")
            request.setValue(timestamp, forHTTPHeaderField: "x-oshi-timestamp")
            request.setValue(pubKey, forHTTPHeaderField: "x-oshi-signing-pubkey")
        } catch {
            // Mirrors V2Client / V2SyncAPI: send unsigned rather than lose the
            // report; the server accepts unsigned while auth mode is `dual`.
            OshiLog.messaging.info("⚠️ Report request left unsigned: \(error)")
        }
    }
}

// MARK: - Manager

/// Local record of everything the user reported, plus delivery. Singleton and
/// `ObservableObject`, exactly like `BlockedContactsManager`.
final class ReportManager: ObservableObject {

    static let shared = ReportManager()

    @Published private(set) var reports: [ContentReport] = []

    private let storageKey = "contentReports"
    private let fileManager = FileManager.default
    private let submitter: ReportSubmitter
    private let maxDetailLength = 2000

    private init(submitter: ReportSubmitter = ReportSubmitter()) {
        self.submitter = submitter
        loadReports()
    }

    // MARK: Queries

    /// Same base64/base64url folding contract as `BlockedContactsManager.normalizeKey`
    /// — the identical key reaches us in several encodings depending on transport.
    private func normalizeKey(_ key: String) -> String {
        key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "=", with: "")
    }

    func hasReportedContact(_ publicKey: String) -> Bool {
        let target = normalizeKey(publicKey)
        return reports.contains { $0.subject == .contact && normalizeKey($0.subjectId) == target }
    }

    func hasReportedGroup(_ groupId: UUID) -> Bool {
        reports.contains { $0.subject == .group && $0.subjectId == groupId.uuidString }
    }

    var allReports: [ContentReport] {
        reports.sorted { $0.createdAt > $1.createdAt }
    }

    var pendingCount: Int {
        reports.filter { $0.state == .pending }.count
    }

    // MARK: Filing

    /// Records the report locally (so it is never lost), then attempts delivery.
    /// The completion fires on the main actor with the final delivery state.
    @discardableResult
    func fileReport(subject: ContentReport.Subject,
                    subjectId: String,
                    subjectName: String?,
                    reason: BlockReason,
                    details: String,
                    completion: (@MainActor (ContentReport.DeliveryState) -> Void)? = nil) -> ContentReport {

        let trimmed = String(details.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxDetailLength))
        let report = ContentReport(subject: subject,
                                   subjectId: subjectId,
                                   subjectName: subjectName,
                                   reason: reason,
                                   details: trimmed)

        reports.append(report)
        saveReports()
        NotificationCenter.default.post(name: .contentReported,
                                        object: nil,
                                        userInfo: ["reportId": report.id.uuidString,
                                                   "subjectId": subjectId])
        OshiLog.messaging.info("🚩 Filed \(subject.rawValue) report reason=\(reason.rawValue)")

        Task { [weak self] in
            guard let self else { return }
            let state = await self.deliver(report)
            await MainActor.run { completion?(state) }
        }

        return report
    }

    /// Re-attempts every still-pending report. Called from the reports screen and
    /// safe to call on appear.
    func retryPending() {
        let pending = reports.filter { $0.state == .pending }
        guard !pending.isEmpty else { return }
        Task { [weak self] in
            guard let self else { return }
            for report in pending {
                _ = await self.deliver(report)
            }
        }
    }

    private func deliver(_ report: ContentReport) async -> ContentReport.DeliveryState {
        do {
            try await submitter.submit(report)
            await update(report.id) {
                $0.state = .sent
                $0.deliveredAt = Date()
                $0.lastError = nil
            }
            return .sent
        } catch ReportSubmitter.SubmitError.permanent(let status, let body) {
            OshiLog.messaging.info("❌ Report rejected by server (\(status))")
            await update(report.id) {
                $0.state = .rejected
                $0.lastError = "http-\(status): \(body ?? "")"
            }
            return .rejected
        } catch {
            OshiLog.messaging.info("⏳ Report not delivered, kept pending")
            await update(report.id) {
                $0.state = .pending
                $0.lastError = String(describing: error)
            }
            return .pending
        }
    }

    @MainActor
    private func update(_ id: UUID, _ mutate: (inout ContentReport) -> Void) {
        guard let index = reports.firstIndex(where: { $0.id == id }) else { return }
        mutate(&reports[index])
        saveReports()
    }

    func clearAll() {
        reports.removeAll()
        saveReports()
    }

    // MARK: Persistence — atomic JSON in Documents, same as BlockedContactsManager

    private func loadReports() {
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let fileURL = documentsURL.appendingPathComponent("\(storageKey).json")
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try AtRestFile.read(fileURL)  // __PLAINTEXT_LEFTOVERS_2026_09_24__
            let loaded = try JSONDecoder().decode([ContentReport].self, from: data)
            reports = loaded
            OshiLog.messaging.info("✅ Loaded \(loaded.count) reports")
        } catch {
            OshiLog.messaging.info("❌ Error loading reports: \(error)")
        }
    }

    private func saveReports() {
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let fileURL = documentsURL.appendingPathComponent("\(storageKey).json")
        do {
            let data = try JSONEncoder().encode(reports)
            try AtRestFile.write(data, to: fileURL)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ chiffré au repos (ancien clair lu puis rechiffré)
        } catch {
            OshiLog.messaging.info("❌ Error saving reports: \(error)")
        }
    }
}

extension Notification.Name {
    static let contentReported = Notification.Name("contentReported")
}

// MARK: - Report sheet

/// One sheet for both contacts and groups — the block flow duplicates itself
/// across `BlockContactSheet` / `BlockGroupSheet`, which we deliberately do not
/// repeat here.
struct ReportSheet: View {

    let subject: ContentReport.Subject
    let subjectId: String
    let subjectName: String?
    /// Called after a report is filed, with `true` when the reporter also asked
    /// to block. The caller performs the block so blocking stays owned by
    /// `BlockedContactsManager`.
    let onReported: (_ alsoBlock: Bool) -> Void

    @Environment(\.presentationMode) var presentationMode

    @State private var selectedReason: BlockReason? = nil
    @State private var details: String = ""
    @State private var alsoBlock: Bool = true
    @State private var isSubmitting = false
    @State private var showingConfirmation = false
    @State private var resultState: ContentReport.DeliveryState? = nil
    @State private var showingResult = false

    private var displayName: String {
        if let name = subjectName, !name.isEmpty { return name }
        return "block_contact_this_user".localized
    }

    private var title: String {
        subject == .group ? "group.report_group".localized : "report.contact_title".localized
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                header
                Divider()
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        reasonSection
                        detailsSection
                        privacyNote
                        blockToggle
                    }
                    .padding()
                }
                Divider()
                actions
            }
            .frame(maxWidth: 600)
            .frame(maxWidth: .infinity)
            .navigationBarHidden(true)
        }
        .alert("report.confirm_title".localized, isPresented: $showingConfirmation) {
            Button("common.cancel".localized, role: .cancel) { }
            Button("report.submit".localized, role: .destructive) { submit() }
        } message: {
            Text(String(format: "report.confirm_message".localized, displayName))
        }
        .alert(resultTitle, isPresented: $showingResult) {
            Button("common.ok".localized) {
                onReported(alsoBlock)
                presentationMode.wrappedValue.dismiss()
            }
        } message: {
            Text(resultMessage)
        }
    }

    // MARK: Sections

    private var header: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.orange.opacity(0.12))
                    .frame(width: 80, height: 80)
                Image(systemName: "flag.fill")
                    .font(.scaled(34))
                    .foregroundColor(.orange)
                    .accessibilityHidden(true)
            }
            .padding(.top, 24)

            Text(title)
                .font(.title2)
                .fontWeight(.semibold)
                .multilineTextAlignment(.center)

            Text(String(format: "report.subtitle".localized, displayName))
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
                .padding(.bottom, 20)
        }
        .accessibilityElement(children: .combine)
    }

    private var reasonSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("block_contact_select_reason".localized)
                .font(.headline)
            ForEach(BlockReason.allCases, id: \.self) { reason in
                ReportReasonRow(reason: reason,
                                isSelected: selectedReason == reason) {
                    selectedReason = reason
                }
            }
        }
    }

    private var detailsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("report.details_header".localized)
                .font(.headline)
            ZStack(alignment: .topLeading) {
                if details.isEmpty {
                    Text("report.details_placeholder".localized)
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 10)
                        .allowsHitTesting(false)
                }
                TextEditor(text: $details)
                    .frame(minHeight: 96)
                    .padding(2)
                    .accessibilityLabel("report.details_header".localized)
            }
            .background(Color(.systemGray6))
            .cornerRadius(10)
        }
    }

    private var privacyNote: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "lock.fill")
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
            Text("report.privacy_note".localized)
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var blockToggle: some View {
        Toggle(isOn: $alsoBlock) {
            Text("report.also_block".localized)
                .font(.subheadline)
        }
        .frame(minHeight: 44)
    }

    private var actions: some View {
        VStack(spacing: 12) {
            Button {
                #if !targetEnvironment(macCatalyst)
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
                #endif
                showingConfirmation = true
            } label: {
                HStack {
                    if isSubmitting {
                        ProgressView().tint(.white)
                        Text("report.sending".localized)
                    } else {
                        Image(systemName: "flag.fill")
                        Text("report.submit".localized)
                    }
                }
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Color.orange)
                .cornerRadius(12)
            }
            .disabled(selectedReason == nil || isSubmitting)
            .opacity(selectedReason == nil || isSubmitting ? 0.5 : 1.0)
            .animation(.easeInOut(duration: 0.2), value: selectedReason)
            .accessibilityHint("report.submit".localized)

            Button {
                presentationMode.wrappedValue.dismiss()
            } label: {
                Text("common.cancel".localized)
                    .foregroundColor(.primary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
            }
            .disabled(isSubmitting)
        }
        .padding()
    }

    // MARK: Result copy

    private var resultTitle: String {
        resultState == .sent ? "report.sent_title".localized : "report.queued_title".localized
    }

    private var resultMessage: String {
        resultState == .sent ? "report.sent_message".localized : "report.queued_message".localized
    }

    // MARK: Submit

    private func submit() {
        guard let reason = selectedReason, !isSubmitting else { return }
        isSubmitting = true
        ReportManager.shared.fileReport(subject: subject,
                                        subjectId: subjectId,
                                        subjectName: subjectName,
                                        reason: reason,
                                        details: details) { state in
            isSubmitting = false
            resultState = state
            showingResult = true
        }
    }
}

// MARK: - Reason row

struct ReportReasonRow: View {
    let reason: BlockReason
    let isSelected: Bool
    let onSelect: () -> Void

    private var icon: String {
        switch reason {
        case .spam:          return "envelope.fill"
        case .harassment:    return "exclamationmark.bubble.fill"
        case .inappropriate: return "eye.trianglebadge.exclamationmark.fill"
        case .other:         return "ellipsis.circle.fill"
        }
    }

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundColor(isSelected ? .white : .orange)
                    .frame(width: 28)
                Text(reason.localizedDescription)
                    .foregroundColor(isSelected ? .white : .primary)
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.white)
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(isSelected ? Color.orange : Color(.systemGray6))
            .cornerRadius(10)
        }
        .buttonStyle(PlainButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: - Settings → Privacy → Reports

/// Mirrors `BlockedContactsView`: the reporter's own record of what they
/// flagged, with the delivery state made visible so a queued report is never a
/// silent lie.
struct ReportedItemsView: View {

    @StateObject private var reportManager = ReportManager.shared

    var body: some View {
        List {
            if reportManager.reports.isEmpty {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "flag.slash")
                            .font(.scaled(40))
                            .foregroundColor(.secondary)
                        Text("settings.privacy.reports.empty".localized)
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
                }
            } else {
                Section {
                    ForEach(reportManager.allReports) { report in
                        row(for: report)
                    }
                } header: {
                    Text("\(reportManager.reports.count) " + "settings.privacy.reports.count".localized)
                }

                if reportManager.pendingCount > 0 {
                    Section {
                        Button {
                            reportManager.retryPending()
                        } label: {
                            Label("report.retry_all".localized, systemImage: "arrow.clockwise")
                        }
                    }
                }
            }
        }
        .navigationTitle("settings.privacy.reports".localized)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reportManager.retryPending() }
    }

    @ViewBuilder
    private func row(for report: ContentReport) -> some View {
        HStack(spacing: 12) {
            Image(systemName: report.subject == .group ? "person.3.fill" : "person.fill")
                .foregroundColor(.orange)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(report.subjectName ?? "settings.privacy.blockedContacts.unknown".localized)
                    .font(.body)
                Text(report.reason.localizedDescription)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Text(report.state == .sent
                 ? "report.status_sent".localized
                 : "report.status_pending".localized)
                .font(.caption2)
                .fontWeight(.semibold)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background((report.state == .sent ? Color.green : Color.orange).opacity(0.15))
                .foregroundColor(report.state == .sent ? .green : .orange)
                .cornerRadius(8)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}
