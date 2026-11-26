//
//  SharedContentHandler.swift
//  OSHI
//
//  Handles content shared from the Share Extension and Deep Links
//  ✅ FULLY LOCALIZED
//  ✅ FIXED: Uses correct MessageManager API
//

import Foundation
import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import Combine

/// Manages shared content received from the Share Extension and Deep Links
class SharedContentHandler: ObservableObject {
    static let shared = SharedContentHandler()
    
    @Published var showSharePicker = false
    @Published var pendingContent: SharedContent?

    /// __NO_SHEET_WHEN_THE_CHOICE_IS_MADE_2026_08_18__
    ///
    /// Set instead of `showSharePicker` when the recipient was already chosen
    /// inside the share sheet. The earlier attempt kept presenting the sheet and
    /// only swapped its CONTENTS for a confirmation — which still flashed a panel
    /// on screen for about a second, because presenting a sheet at all is the
    /// thing the user was complaining about. Nothing to decide means nothing to
    /// present.
    @Published var pendingAutoSendRecipient: String?
    /// Recipient display hint for the toast, resolved before the send.
    @Published var autoSendToast: String?
    
    // Deep link handling
    @Published var showAddContact = false
    @Published var pendingPublicKey: String?
    /// (N1) The name offered by an `…/add?key=…&name=…` link, if any.
    @Published var pendingContactName: String?
    @Published var pendingGroupId: String?
    @Published var showJoinGroup = false

    // 🆕 Group invite extras (from oshi://group/join?gid=…&name=…&inviter=…)
    @Published var pendingGroupName: String?
    @Published var pendingGroupInviter: String?
    @Published var pendingGroupInviteInvalid: Bool = false
    // __GROUP_INFO_SETTINGS_2026_11_20__ §3.2 `t` of a reset private link, and
    // §3.1 public link `/g/<handle>` (raw, validated by the join sheet).
    @Published var pendingGroupInviteToken: String?
    @Published var pendingPublicHandle: String?
    @Published var showPublicJoin = false
    /// __BOTS_PHASE1_2026_11_24__ `…/bot/<username>` → the bot's profile sheet.
    @Published var pendingBotLink: BotLink?
    
    private let appGroupIdentifier = "group.Moriceau-lab.Genesis"
    
    struct SharedContent {
        var images: [Data] = []
        var videos: [URL] = []
        var urls: [URL] = []
        var text: String?
        var files: [URL] = []
    }
    
    init() {
        // Check for pending content on init
        checkForPendingContent()
    }
    
    /// Called when app receives oshi:// URL or Universal Link (https://oshi-messenger.com/...)
    func handleURL(_ url: URL) {
        OshiLog.messaging.info("📥 SharedContentHandler: Received URL: \(url)")
        
        // Determine the route from either custom scheme or Universal Link
        let route: String?
        let effectiveURL: URL
        
        if url.scheme == "oshi" {
            // Custom URL scheme: oshi://group/UUID, oshi://add?key=..., etc.
            route = url.host
            effectiveURL = url
        } else if url.scheme == "https",
                  let host = url.host,
                  (host == "oshi-messenger.com" || host == "www.oshi-messenger.com" ||
                   host == "oshi-messenger.org" || host == "www.oshi-messenger.org") {
            // Universal Link: https://oshi-messenger.com/group/UUID, /add?key=..., etc.
            let pathComponents = url.pathComponents.filter { $0 != "/" }
            route = pathComponents.first
            effectiveURL = url
        } else {
            return
        }
        
        // __CHANNEL_WEB_2026_11_26__ channel web links: oshi://channel?h=&n= and
        // https://oshi-messenger.com/c/<h>[/<n>] → open the channel (scroll to the
        // post), or the public join flow when we are not a member.
        if ChannelWeb.parseLink(effectiveURL) != nil {
            OshiLog.messaging.info("🌐 Deep link: channel web link")
            DispatchQueue.main.async {
                _ = ChannelWebRouter.handle(effectiveURL) { raw in
                    self.pendingPublicHandle = raw
                    self.showPublicJoin = true
                }
            }
            return
        }

        // __GROUP_INFO_SETTINGS_2026_11_20__ public group links (§3.1):
        // https://oshi-messenger.com/g/<h>, oshi://g/<h>, oshi://group/join?h=<h>.
        if let h = GroupPublicLink.rawHandle(from: effectiveURL) {
            OshiLog.messaging.info("👥 Deep link: public group handle")
            DispatchQueue.main.async {
                self.pendingPublicHandle = h
                self.showPublicJoin = true
            }
            return
        }

        // __BOTS_PHASE1_2026_11_24__ https://oshi-messenger.com/bot/<username>
        // and oshi://bot/<username>: the bot's card, with "Add to group /
        // channel". Checked before the switch: no other route is named "bot".
        if let botLink = BotLink.parse(effectiveURL) {
            OshiLog.messaging.info("🤖 Deep link: bot profile")
            DispatchQueue.main.async { self.pendingBotLink = botLink }
            return
        }

        switch route {
        case "share":
            // Share Extension content - add small delay to ensure app is ready
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.checkForPendingContent()
            }
            
        case "add":
            // Add contact deep link: oshi://add?key=KEY or https://oshi-messenger.com/add?key=KEY
            if let components = URLComponents(url: effectiveURL, resolvingAgainstBaseURL: false),
               let keyParam = components.queryItems?.first(where: { $0.name == "key" || $0.name == "k" }),
               let publicKey = keyParam.value, !publicKey.isEmpty {
                OshiLog.messaging.info("🔑 Deep link: Add contact with key: \(publicKey.prefix(20))...")
                self.pendingPublicKey = publicKey
                self.pendingContactName = components.queryItems?
                    .first(where: { $0.name == "name" })?.value
                    .flatMap { $0.isEmpty ? nil : $0 }
                DispatchQueue.main.async {
                    self.showAddContact = true
                }
            }
            
        case "chat":
            // Open chat: oshi://chat?key=KEY or https://oshi-messenger.com/chat?key=KEY
            if let components = URLComponents(url: effectiveURL, resolvingAgainstBaseURL: false),
               let keyParam = components.queryItems?.first(where: { $0.name == "key" || $0.name == "k" }),
               let publicKey = keyParam.value {
                OshiLog.messaging.info("💬 Deep link: Open chat with key: \(publicKey.prefix(20))...")
                // TODO: Navigate to existing chat
            }

        case "group":
            // Supported invite formats (in priority order):
            //   • oshi://group/join?gid=<UUID>&name=<urlencoded>&inviter=<pubkey>
            //   • https://oshi-messenger.com/group?gid=<UUID>&name=…&inviter=…
            //   • oshi://group/<UUID>                       (legacy)
            //   • https://oshi-messenger.com/group/<UUID>   (legacy)
            var groupId: String = ""
            var groupName: String? = nil
            var inviterKey: String? = nil

            // 1. Look for query params first (new format)
            if let components = URLComponents(url: effectiveURL, resolvingAgainstBaseURL: false),
               let queryItems = components.queryItems {
                if let gid = queryItems.first(where: { $0.name == "gid" })?.value, !gid.isEmpty {
                    groupId = gid
                }
                groupName = queryItems.first(where: { $0.name == "name" })?.value
                inviterKey = queryItems.first(where: { $0.name == "inviter" })?.value
                pendingGroupInviteToken = queryItems.first(where: { $0.name == "t" })?.value
            }

            // 2. Fall back to legacy path-based UUID
            if groupId.isEmpty {
                if url.scheme == "oshi" {
                    // oshi://group/UUID  OR  oshi://group/join (no params – invalid)
                    let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    // If host is "group" and path is "join", we already searched query above; treat empty path as invalid
                    if path != "join" {
                        groupId = path
                    }
                } else {
                    // Universal link: /group/UUID (legacy) — pathComponents includes leading "/"
                    let pathComponents = url.pathComponents.filter { $0 != "/" }
                    if pathComponents.count > 1 && pathComponents[1] != "join" {
                        groupId = pathComponents[1]
                    }
                }
            }

            // 3. Validate UUID format
            if groupId.isEmpty || UUID(uuidString: groupId) == nil {
                OshiLog.messaging.info("⚠️ Invalid group invite link (missing/invalid gid): \(url.absoluteString)")
                DispatchQueue.main.async {
                    self.pendingGroupInviteInvalid = true
                }
                return
            }

            OshiLog.messaging.info("👥 Deep link: Join group \(groupId), name=\(groupName ?? "n/a"), inviter=\(inviterKey?.prefix(12) ?? "n/a")")
            self.pendingGroupId = groupId
            self.pendingGroupName = groupName
            self.pendingGroupInviter = inviterKey
            DispatchQueue.main.async {
                self.showJoinGroup = true
            }

        default:
            OshiLog.messaging.info("⚠️ Unknown deep link route: \(route ?? "nil")")
        }
    }
    
    /// Clear pending add contact
    func clearPendingAddContact() {
        pendingPublicKey = nil
        pendingContactName = nil
        showAddContact = false
    }
    
    /// Check App Group for pending shared content
    func checkForPendingContent() {
        OshiLog.messaging.info("🔍 Checking for pending shared content...")
        
        guard let userDefaults = UserDefaults(suiteName: appGroupIdentifier) else {
            OshiLog.messaging.info("❌ Could not access App Group UserDefaults")
            return
        }
        
        // Check if there's pending content
        // __LEAK_FIX_2026_09_25__ new extension writes a protected, backup-excluded file;
        // the old UserDefaults key is still read (content queued before the update).
        let queueFile = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)?
            .appendingPathComponent("shared_pending_content.json")
        let fileData = queueFile.flatMap { try? Data(contentsOf: $0) }
        guard let contentData = fileData ?? userDefaults.data(forKey: "pendingSharedContent"),
              let timestamp = userDefaults.object(forKey: "sharedContentTimestamp") as? Date else {
            OshiLog.messaging.info("ℹ️ No pending shared content found")
            return
        }
        
        // Check if content is recent (within last 5 minutes)
        if Date().timeIntervalSince(timestamp) > 300 {
            OshiLog.messaging.info("⚠️ Shared content is too old, clearing...")
            clearPendingContent()
            return
        }
        
        do {
            let contentInfo = try JSONDecoder().decode(SharedContentInfo.self, from: contentData)
            var content = SharedContent()
            
            // Load images
            if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
                for imageName in contentInfo.imageFileNames {
                    let imageURL = containerURL.appendingPathComponent(imageName)
                    if let imageData = try? Data(contentsOf: imageURL) {
                        content.images.append(imageData)
                    }
                }
                
                // Load videos
                for videoName in contentInfo.videoFileNames {
                    let videoURL = containerURL.appendingPathComponent(videoName)
                    if FileManager.default.fileExists(atPath: videoURL.path) {
                        content.videos.append(videoURL)
                    }
                }
                
                // Load files
                for fileName in contentInfo.fileNames {
                    let fileURL = containerURL.appendingPathComponent(fileName)
                    if FileManager.default.fileExists(atPath: fileURL.path) {
                        content.files.append(fileURL)
                    }
                }
            }
            
            // Load URLs
            content.urls = contentInfo.urls.compactMap { URL(string: $0) }
            
            // Load text
            content.text = contentInfo.text
            
            // Set pending content, then decide whether there is anything to ASK.
            if !content.images.isEmpty || !content.videos.isEmpty || !content.urls.isEmpty ||
               content.text != nil || !content.files.isEmpty {
                OshiLog.messaging.info("✅ Loaded shared content: \(content.images.count) images, \(content.videos.count) videos, \(content.urls.count) URLs")
                self.pendingContent = content

                // The recipient chosen in the share extension. Consumed here, once.
                let preChosen = userDefaults.string(forKey: "pendingShareRecipient")
                    .flatMap { $0.isEmpty ? nil : $0 }
                if preChosen != nil {
                    userDefaults.removeObject(forKey: "pendingShareRecipient")
                }

                DispatchQueue.main.async {
                    if let recipient = preChosen {
                        // No sheet. None. The user already chose; presenting a
                        // picker — even one showing a confirmation — is the flash
                        // they reported.
                        OshiLog.messaging.info("📨 Auto-send to pre-chosen recipient, no picker")
                        self.pendingAutoSendRecipient = recipient
                    } else {
                        self.showSharePicker = true
                    }
                }
            }
            
        } catch {
            OshiLog.messaging.info("❌ Failed to decode shared content: \(error)")
        }
    }
    
    /// Sends the pending shared content to a recipient the user already chose in
    /// the share extension — with NO picker, NO sheet, and no second decision.
    ///
    /// This lives on the handler rather than inside `SharePickerView` for one
    /// reason: a view can only send if it is on screen, and the whole point here is
    /// that nothing goes on screen. The managers are passed in because the handler
    /// deliberately owns no references to them.
    ///
    /// Returns a refusal when the send was declined (rate limit, body too long) so
    /// the caller can say so. A silent failure here would be the worst outcome of
    /// all: the user shared a photo, saw nothing, and nothing was sent.
    @MainActor
    @discardableResult
    func performAutoSend(to recipientPublicKey: String,
                         messageManager: MessageManager,
                         identityManager: IdentityManager,
                         meshManager: MeshNetworkManager) -> MessageSendOutcome? {
        guard let content = pendingContent else { return nil }
        var refusal: MessageSendOutcome? = nil

        for imageData in content.images {
            let attachment = MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: imageData,
                thumbnailData: nil,
                fileName: "shared_image_\(Date().timeIntervalSince1970).jpg",
                fileSize: imageData.count,
                timestamp: Date(),
                originalSize: imageData.count,
                compressionRatio: 1.0,
                isCompressed: false
            )
            do {
                try messageManager.sendMediaMessage(
                    attachment: attachment,
                    recipientAddress: recipientPublicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    mediaManager: MediaManager()
                )
            } catch {
                OshiLog.messaging.info("❌ Auto-send image failed: \(error)")
            }
        }

        if let text = content.text {
            let outcome = messageManager.sendMessage(
                content: text, recipientAddress: recipientPublicKey,
                identityManager: identityManager, meshManager: meshManager)
            if !outcome.didSend { refusal = outcome }
        }
        for url in content.urls {
            let outcome = messageManager.sendMessage(
                content: url.absoluteString, recipientAddress: recipientPublicKey,
                identityManager: identityManager, meshManager: meshManager)
            if !outcome.didSend, refusal == nil { refusal = outcome }
        }

        // A refusal keeps the content: the user can open OSHI and retry rather than
        // discovering later that the photo evaporated.
        if refusal == nil { clearPendingContent() }
        return refusal
    }

    /// Clear pending shared content
    func clearPendingContent() {
        guard let userDefaults = UserDefaults(suiteName: appGroupIdentifier) else { return }
        
        userDefaults.removeObject(forKey: "pendingSharedContent")
        userDefaults.removeObject(forKey: "sharedContentTimestamp")
        
        // Clean up files
        if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            let fileManager = FileManager.default
            if let files = try? fileManager.contentsOfDirectory(at: containerURL, includingPropertiesForKeys: nil) {
                for file in files {
                    if file.lastPathComponent.hasPrefix("shared_") {
                        try? fileManager.removeItem(at: file)
                    }
                }
            }
        }
        
        pendingContent = nil
        showSharePicker = false
        OshiLog.messaging.info("🧹 Cleared pending shared content")
    }
}

// MARK: - Shared Content Info (Codable)

struct SharedContentInfo: Codable {
    var imageFileNames: [String] = []
    var videoFileNames: [String] = []
    var urls: [String] = []
    var text: String?
    var fileNames: [String] = []
}

// MARK: - Share Picker View

struct SharePickerView: View {
    @ObservedObject var sharedContentHandler: SharedContentHandler
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @Environment(\.dismiss) var dismiss

    // 🔧 FIX: read the user-set aliases so the share-target list shows
    // "Maya" instead of the raw base64 pubkey. Same source as ContactRow.
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    private var contactAliases: [String: String] {
        guard !contactAliasesData.isEmpty else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
    }

    /// 🔔 2026-08-18 — Read at init, i.e. BEFORE the first `body` render, and
    /// deliberately WITHOUT consuming the marker (`autoRouteSharedRecipientIfNeeded`
    /// still does that on appear).
    ///
    /// This is what killed the "app opens, a screen flashes for a second, then
    /// vanishes" complaint. The recipient was already chosen inside the share
    /// sheet, but this view still built the whole contact picker, showed it, and
    /// only then did `.onAppear` fire the send and dismiss. The user was shown a
    /// list they had no reason to see and no time to use. Knowing the answer
    /// before the first render means we can draw the confirmation instead.
    private let preselectedRecipient: String? = {
        guard let defaults = UserDefaults(suiteName: "group.Moriceau-lab.Genesis"),
              let recipient = defaults.string(forKey: "pendingShareRecipient"),
              !recipient.isEmpty else { return nil }
        return recipient
    }()

    @State private var isSending = false
    @State private var sentTo: String?
    @State private var didAutoRoute = false
    /// Set when a send is refused (100 msg/min limit, or a body too long to send
    /// even split up). Without this the sheet would report success, dismiss, and
    /// the shared text would be gone — a silent drop.
    @State private var sendErrorMessage: String? = nil

    var body: some View {
        NavigationView {
            // ⚠️ `SwiftUI.Group` in full: OSHI declares its own `Group` model type,
            // and a bare `Group { }` here binds to THAT — the compiler then reports
            // a trailing closure passed to `any Decoder`, which points nowhere near
            // the real problem.
            SwiftUI.Group {
            if let target = preselectedRecipient {
                autoRouteConfirmation(for: target)
            } else {
            VStack {
                if let content = sharedContentHandler.pendingContent {
                    // Preview section
                    VStack(spacing: 12) {
                        if !content.images.isEmpty {
                            HStack {
                                Image(systemName: "photo.fill")
                                    .foregroundColor(appBrandColor)
                                Text("\(content.images.count) " + (content.images.count == 1 ? "share.image".localized : "share.images".localized))
                            }
                        }
                        
                        if !content.videos.isEmpty {
                            HStack {
                                Image(systemName: "video.fill")
                                    .foregroundColor(.purple)
                                Text("\(content.videos.count) " + (content.videos.count == 1 ? "share.video".localized : "share.videos".localized))
                            }
                        }
                        
                        if !content.urls.isEmpty {
                            HStack {
                                Image(systemName: "link")
                                    .foregroundColor(.green)
                                Text("share.link".localized)
                            }
                        }
                        
                        if content.text != nil {
                            HStack {
                                Image(systemName: "text.quote")
                                    .foregroundColor(.orange)
                                Text("share.text".localized)
                            }
                        }
                        
                        if !content.files.isEmpty {
                            HStack {
                                Image(systemName: "doc.fill")
                                    .foregroundColor(.gray)
                                Text("\(content.files.count) " + (content.files.count == 1 ? "share.file".localized : "share.files".localized))
                            }
                        }
                    }
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    .padding()
                    
                    // Sending indicator
                    if isSending {
                        HStack {
                            ProgressView()
                                .padding(.trailing, 8)
                            Text("share.sending".localized)
                        }
                        .padding()
                    } else if sentTo != nil {
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                            Text("share.sent".localized)
                        }
                        .padding()
                    }
                    
                    // Contact list
                    Text("share.to".localized)
                        .font(.headline)
                        .padding(.horizontal)
                    
                    if messageManager.conversations.isEmpty {
                        VStack(spacing: 20) {
                            Image(systemName: "person.crop.circle.badge.plus")
                                .font(.scaled(60))
                                .foregroundColor(.gray)
                            Text("share.no_contacts".localized)
                                .foregroundColor(.secondary)
                            Text("share.add_contact_first".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(.top, 40)
                    } else {
                        List {
                            ForEach(messageManager.conversations) { conversation in
                                // __SHARED_NICKNAME_2026_09_22__ alias local > pseudo partage.
                                let alias = SharedNickname.resolve(
                                    alias: contactAliases[conversation.participantPublicKey],
                                    nickname: SharedNickname.nickname(for: conversation.participantPublicKey))
                                let displayName = alias ?? formatAddress(conversation.participantAddress)
                                // Avatar initials prefer the alias's first letter(s);
                                // fall back to the address's first char when no alias.
                                let avatarChar: String = {
                                    if let alias = alias, !alias.isEmpty {
                                        return String(alias.prefix(1)).uppercased()
                                    }
                                    return String(conversation.participantAddress.prefix(1)).uppercased()
                                }()
                                Button {
                                    sendContent(to: conversation.participantPublicKey)
                                } label: {
                                    HStack {
                                        Circle()
                                            .fill(LinearGradient(
                                                colors: [appBrandColor, appBrandColor.opacity(0.7)],
                                                startPoint: .topLeading,
                                                endPoint: .bottomTrailing
                                            ))
                                            .frame(width: 50, height: 50)
                                            .overlay(
                                                Text(avatarChar)
                                                    .font(.title2.bold())
                                                    .foregroundColor(.white)
                                            )

                                        VStack(alignment: .leading) {
                                            // Primary line: user-friendly alias if set,
                                            // otherwise the formatted address.
                                            Text(displayName)
                                                .font(.headline)
                                                .foregroundColor(.primary)
                                            // Secondary line: short pubkey hint, only
                                            // shown when we have an alias above it (so
                                            // users can still confirm identity); hidden
                                            // when displayName IS the address already.
                                            if alias != nil {
                                                Text(String(conversation.participantPublicKey.prefix(20)) + "…")
                                                    .font(.caption)
                                                    .foregroundColor(.secondary)
                                                    .lineLimit(1)
                                            }
                                        }
                                        
                                        Spacer()
                                        
                                        if sentTo == conversation.participantPublicKey {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundColor(.green)
                                        } else {
                                            Image(systemName: "chevron.right")
                                                .foregroundColor(.secondary)
                                        }
                                    }
                                }
                                .disabled(isSending)
                            }
                        }
                        .listStyle(.plain)
                    }
                } else {
                    Text("share.no_content_to_share".localized)
                        .foregroundColor(.secondary)
                }
                
                Spacer()
            }
            }
            }
            .navigationTitle("share.to".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // No Cancel while auto-routing: the send is already in flight,
                // and this button CLEARS the pending content — tapping it mid-send
                // would drop the very photo the user just shared.
                if preselectedRecipient == nil {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("common.cancel".localized) {
                            sharedContentHandler.clearPendingContent()
                            dismiss()
                        }
                    }
                }
            }
            .onAppear { autoRouteSharedRecipientIfNeeded() }
            .alert("alert.error".localized,
                   isPresented: Binding(get: { sendErrorMessage != nil },
                                        set: { if !$0 { sendErrorMessage = nil } })) {
                Button("common.ok".localized, role: .cancel) { sendErrorMessage = nil }
            } message: {
                Text(sendErrorMessage ?? "")
            }
        }
    }

    /// The whole screen when the recipient was already chosen in the share
    /// sheet: who it is going to, and whether it has gone. No contact list, no
    /// second decision — there is nothing left to decide.
    ///
    /// Only strings that already exist in all 34 locales are used here, so this
    /// screen is never the one English sentence in someone's German app.
    @ViewBuilder
    private func autoRouteConfirmation(for recipient: String) -> some View {
        // __SHARED_NICKNAME_2026_09_22__ alias local > pseudo partage.
        let alias = SharedNickname.resolve(alias: contactAliases[recipient],
                                           nickname: SharedNickname.nickname(for: recipient))
        let displayName = alias ?? formatAddress(recipient)
        let avatarChar = String(displayName.prefix(1)).uppercased()

        VStack(spacing: 20) {
            Spacer()

            Circle()
                .fill(LinearGradient(colors: [appBrandColor, appBrandColor.opacity(0.7)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 72, height: 72)
                .overlay(
                    Text(avatarChar)
                        .font(.largeTitle.bold())
                        .foregroundColor(.white)
                )

            Text(displayName)
                .font(.title3.weight(.semibold))
                .foregroundColor(.primary)
                .lineLimit(1)

            if sentTo != nil {
                Label("share.sent".localized, systemImage: "checkmark.circle.fill")
                    .foregroundColor(.green)
                    .font(.headline)
            } else {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("share.sending".localized)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(displayName) — \(sentTo != nil ? "share.sent".localized : "share.sending".localized)")
    }

    /// 🔔 2026-06-29 — If the user already picked a recipient INSIDE the share
    /// sheet (the OSHIShare extension stores it in the App Group as
    /// `pendingShareRecipient`), send straight to that contact and skip the
    /// manual list entirely. Falls back to the manual picker when the chosen
    /// recipient isn't among existing conversations (so nothing is lost).
    private func autoRouteSharedRecipientIfNeeded() {
        guard !didAutoRoute else { return }
        let appGroupIdentifier = "group.Moriceau-lab.Genesis"
        guard let defaults = UserDefaults(suiteName: appGroupIdentifier),
              let recipient = defaults.string(forKey: "pendingShareRecipient"),
              !recipient.isEmpty else { return }
        // Consume the marker + route straight to the recipient the user already
        // picked inside the share sheet.
        //
        // 🔧 FIX: the old code gated this on `messageManager.conversations.contains`.
        // But conversations load asynchronously and were routinely still empty at
        // `.onAppear` (which fires moments after a cold launch from the share deep
        // link) — so the guard failed, the marker was already consumed, and the user
        // was forced to pick the SAME contact a second time in this list. That was
        // the "I pick in the extension, then OSHI shows me the picker again" bug.
        // `sendContent` only needs the raw public key (the extension already
        // validated it against the contact snapshot), so route directly.
        defaults.removeObject(forKey: "pendingShareRecipient")
        didAutoRoute = true
        sendContent(to: recipient)
    }

    private func formatAddress(_ address: String) -> String {
        guard address.count > 12 else { return address }
        return String(address.prefix(6)) + "..." + String(address.suffix(4))
    }
    
    private func sendContent(to recipientPublicKey: String) {
        guard let content = sharedContentHandler.pendingContent else { return }
        
        isSending = true
        
        // Send images
        for imageData in content.images {
            // Create proper MediaAttachment
            let attachment = MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: imageData,
                thumbnailData: nil,
                fileName: "shared_image_\(Date().timeIntervalSince1970).jpg",
                fileSize: imageData.count,
                timestamp: Date(),
                originalSize: imageData.count,
                compressionRatio: 1.0,
                isCompressed: false
            )
            do {
                try messageManager.sendMediaMessage(
                    attachment: attachment,
                    recipientAddress: recipientPublicKey,
                    identityManager: identityManager,
                    meshManager: meshManager,
                    mediaManager: MediaManager()
                )
            } catch {
                OshiLog.messaging.info("❌ Failed to send image: \(error)")
            }
        }
        
        // Send text
        var refusal: MessageSendOutcome? = nil
        if let text = content.text {
            let outcome = messageManager.sendMessage(
                content: text,
                recipientAddress: recipientPublicKey,
                identityManager: identityManager,
                meshManager: meshManager
            )
            if !outcome.didSend { refusal = outcome }
        }

        // Send URLs as text
        for url in content.urls {
            let outcome = messageManager.sendMessage(
                content: url.absoluteString,
                recipientAddress: recipientPublicKey,
                identityManager: identityManager,
                meshManager: meshManager
            )
            if !outcome.didSend, refusal == nil { refusal = outcome }
        }

        // A refused send must not be reported as delivered and must not take the
        // shared content down with it: keep the sheet open, say why, leave
        // `pendingContent` intact so the user can retry.
        if let refusal {
            isSending = false
            sendErrorMessage = refusal.userFacingMessage ?? "alert.error".localized
            return
        }

        // Mark as sent
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            isSending = false
            sentTo = recipientPublicKey
            
            // Auto close after 1 second
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                sharedContentHandler.clearPendingContent()
                dismiss()
            }
        }
    }
}

// MARK: - Add Contact From Link View

struct AddContactFromLinkView: View {
    @ObservedObject var sharedContentHandler: SharedContentHandler
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    @Environment(\.dismiss) var dismiss
    
    @State private var alias: String
    @State private var isAdding = false
    @State private var showError = false
    @State private var errorMessage = ""
    @State private var addedSuccessfully = false
    @State private var navigateToChat = false
    @State private var addedPublicKey: String = ""

    /// (N1) Prefilled from the link's `&name=`, if any — a plain editable
    /// field, so it's easy to correct, not a value forced on the contact.
    init(sharedContentHandler: SharedContentHandler) {
        self.sharedContentHandler = sharedContentHandler
        _alias = State(initialValue: sharedContentHandler.pendingContactName ?? "")
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                // Success state
                if addedSuccessfully {
                    successView
                } else {
                    // Icon
                    ZStack {
                        Circle()
                            .fill(
                                LinearGradient(
                                    colors: [appBrandColor.opacity(0.2), appBrandColor.opacity(0.15)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 100, height: 100)
                        
                        Image(systemName: "person.badge.plus")
                            .font(.scaled(40))
                            .foregroundStyle(
                                LinearGradient(
                                    colors: [appBrandColor, appBrandColor.opacity(0.7)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                    }
                    .padding(.top, 20)
                    
                    // Title
                    Text("deeplink.add_contact".localized)
                        .font(.title2.bold())
                    
                    Text("deeplink.add_contact_desc".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                    
                    // Public key display
                    if let publicKey = sharedContentHandler.pendingPublicKey {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("deeplink.public_key".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                            
                            Text(publicKey)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundColor(.primary)
                                .padding()
                                .background(Color(.systemGray6))
                                .cornerRadius(12)
                                .lineLimit(3)
                        }
                        .padding(.horizontal)
                    }
                    
                    // Alias input
                    VStack(alignment: .leading, spacing: 8) {
                        Text("deeplink.contact_name".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        TextField("deeplink.name_placeholder".localized, text: $alias)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                    }
                    .padding(.horizontal)
                    
                    Spacer()
                    
                    // Add button
                    Button {
                        addContact()
                    } label: {
                        HStack {
                            if isAdding {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            } else {
                                Image(systemName: "person.badge.plus")
                                Text("deeplink.add_and_chat".localized)
                            }
                        }
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(
                            LinearGradient(
                                colors: [appBrandColor, appBrandColor.opacity(0.7)],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .cornerRadius(14)
                    }
                    .disabled(isAdding || sharedContentHandler.pendingPublicKey == nil)
                    .padding(.horizontal)
                    .padding(.bottom)
                }
            }
            .navigationTitle("deeplink.new_contact".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.cancel".localized) {
                        sharedContentHandler.clearPendingAddContact()
                        dismiss()
                    }
                }
            }
            .alert("alert.error".localized, isPresented: $showError) {
                Button("common.ok".localized, role: .cancel) { }
            } message: {
                Text(errorMessage)
            }
        }
    }
    
    private var successView: some View {
        VStack(spacing: 24) {
            Spacer()
            
            ZStack {
                Circle()
                    .fill(Color.green.opacity(0.2))
                    .frame(width: 120, height: 120)
                
                Image(systemName: "checkmark.circle.fill")
                    .font(.scaled(60))
                    .foregroundColor(.green)
            }
            
            Text("deeplink.contact_added".localized)
                .font(.title2.bold())
            
            Text("deeplink.start_chatting".localized)
                .foregroundColor(.secondary)
            
            Spacer()
            
            Button {
                // Open the chat with this contact
                openChatWithContact()
            } label: {
                Text("deeplink.open_chat".localized)
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(
                        LinearGradient(
                            colors: [.green, appBrandColor],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(14)
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
    }
    
    private func openChatWithContact() {
        // Post notification to navigate to chat - use StartChatWithContact which MessagesListView listens for
        NotificationCenter.default.post(
            name: NSNotification.Name("StartChatWithContact"),
            object: nil,
            userInfo: [
                "publicKey": addedPublicKey,
                "alias": alias
            ]
        )
        
        // Clear and dismiss
        sharedContentHandler.clearPendingAddContact()
        dismiss()
    }
    
    private func addContact() {
        guard let publicKey = sharedContentHandler.pendingPublicKey else { return }
        
        // Validate public key format
        guard publicKey.count >= 20 else {
            errorMessage = "deeplink.invalid_key".localized
            showError = true
            return
        }
        
        // Check if it's not our own key
        if publicKey == identityManager.publicKey {
            errorMessage = "deeplink.own_key_error".localized
            showError = true
            return
        }
        
        // Store the key for navigation
        addedPublicKey = publicKey
        
        // Check if contact already exists
        if messageManager.conversations.contains(where: { $0.participantPublicKey == publicKey }) {
            // Contact already exists, directly navigate to chat
            openChatWithContact()
            return
        }
        
        isAdding = true
        
        // Create placeholder message
        let encryptedMsg = EncryptedMessage(
            ciphertext: "",
            signature: "",
            senderPublicKey: identityManager.publicKey,
            timestamp: Date()
        )
        
        let placeholderMessage = SecureMessage(
            id: UUID().uuidString,
            senderAddress: identityManager.publicKey,
            recipientAddress: publicKey,
            encryptedContent: encryptedMsg,
            timestamp: Date(),
            isRead: true,
            deliveryStatus: .delivered
        )
        
        // Create conversation
        let newConversation = Conversation(
            participantAddress: publicKey,
            participantPublicKey: publicKey,
            lastMessage: placeholderMessage,
            unreadCount: 0
        )
        
        // Add to message manager
        messageManager.conversations.append(newConversation)
        
        // Save alias if provided
        if !alias.isEmpty {
            saveAlias(alias, for: publicKey)
        }
        
        // Directly navigate to chat instead of showing success view
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            isAdding = false
            openChatWithContact()
        }
    }
    
    private func saveAlias(_ alias: String, for publicKey: String) {
        let key = "contactAliases"
        var aliases: [String: String] = [:]
        
        if let data = UserDefaults.standard.data(forKey: key),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            aliases = decoded
        }
        
        aliases[publicKey] = alias
        
        if let encoded = try? JSONEncoder().encode(aliases) {
            UserDefaults.standard.set(encoded, forKey: key)
        }
    }
}
