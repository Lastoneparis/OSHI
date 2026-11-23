//
//  ProfileLinksManager.swift
//  OSHI - Profile Links System
//  Allows users to share website/social links as pinned bar in conversations
//

import Foundation
import SwiftUI
import Combine

// MARK: - Profile Links Data Model

struct ProfileLinks: Codable, Equatable {
    var websiteURL: String?
    var linkedinURL: String?
    var twitterURL: String?
    var instagramURL: String?
    var githubURL: String?
    /// The Waypath ID — a bare account handle, not a URL. Waypath's own
    /// `ProfileStore` already carries the reverse direction (`oshiMeshPublicKey`),
    /// so this closes the loop: publish it here and a contact taps straight
    /// into your Waypath profile. Optional like every other field, so a payload
    /// written by a build that predates it still decodes.
    var waypathID: String?
    /// Last edit timestamp — used by multi-device sync to resolve conflicts
    /// (newer wins) when the same identity is active on two devices.
    var updatedAt: Date?

    var hasAnyLink: Bool {
        [websiteURL, linkedinURL, twitterURL, instagramURL, githubURL, waypathID]
            .compactMap { $0 }
            .contains(where: { !$0.isEmpty })
    }

    var filledLinks: [(type: LinkType, url: String)] {
        var result: [(LinkType, String)] = []
        if let u = websiteURL, !u.isEmpty { result.append((.website, u)) }
        if let u = linkedinURL, !u.isEmpty { result.append((.linkedin, u)) }
        if let u = twitterURL, !u.isEmpty { result.append((.twitter, u)) }
        if let u = instagramURL, !u.isEmpty { result.append((.instagram, u)) }
        if let u = githubURL, !u.isEmpty { result.append((.github, u)) }
        if let u = waypathID, !u.isEmpty { result.append((.waypath, u)) }
        return result
    }

    enum LinkType: String, Codable, CaseIterable {
        case website, linkedin, twitter, instagram, github, waypath

        var icon: String {
            switch self {
            case .website: return "globe"
            case .linkedin: return "briefcase.fill"
            case .twitter: return "at"
            case .instagram: return "camera.fill"
            case .github: return "chevron.left.forwardslash.chevron.right"
            case .waypath: return "figure.run"
            }
        }

        var label: String {
            switch self {
            case .website: return "Web"
            case .linkedin: return "LinkedIn"
            case .twitter: return "X"
            case .instagram: return "Instagram"
            case .github: return "GitHub"
            case .waypath: return "Waypath"
            }
        }

        var color: Color {
            switch self {
            case .website: return .blue
            case .linkedin: return Color(red: 0.0, green: 0.47, blue: 0.71)
            case .twitter: return .primary
            case .instagram: return Color(red: 0.88, green: 0.19, blue: 0.42)
            case .github: return .primary
            case .waypath: return Color(red: 0.55, green: 0.36, blue: 0.96)
            }
        }

        /// Canonical URL prefix prepended automatically when the user starts editing.
        /// `website` returns `nil` so the user can paste any URL, and `waypath`
        /// returns `nil` because the field holds a handle rather than a URL.
        var urlPrefix: String? {
            switch self {
            case .website:   return nil
            case .linkedin:  return "https://www.linkedin.com/in/"
            case .twitter:   return "https://x.com/"
            case .instagram: return "https://www.instagram.com/"
            case .github:    return "https://github.com/"
            case .waypath:   return nil
            }
        }

        /// True when the stored value is an account handle, not a URL.
        ///
        /// Waypath is the only one: it has no usable web profile. `waypath.app`
        /// resolves to an unrelated product, so an `https://` link there would
        /// send the tapper somewhere wrong rather than nowhere. The handle is
        /// turned into a `waypath://` deep link at tap time instead.
        var storesHandle: Bool { self == .waypath }

        /// Where a tap on this link should go.
        ///
        /// Every URL-shaped type keeps the historical behaviour — assume https
        /// when the user typed a bare host. `waypath` builds the deep link that
        /// `WaypathApp.swift`'s router understands (`waypath://user/<handle>`,
        /// case `"user", "athlete", "profile"`).
        func destinationURL(for value: String) -> URL? {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return nil }
            if self == .waypath {
                guard let handle = Self.normalizedWaypathID(trimmed) else { return nil }
                return URL(string: "waypath://user/\(handle)")
            }
            let str = trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://")
                ? trimmed : "https://\(trimmed)"
            return URL(string: str)
        }

        /// Where to send someone whose device cannot open `destinationURL` —
        /// only meaningful for the app-scheme link, i.e. Waypath is not installed.
        var storeFallbackURL: URL? {
            self == .waypath ? URL(string: "https://apps.apple.com/app/id6788553690") : nil
        }

        /// The grammar Waypath's own backend enforces on a handle
        /// (`server/migrations/0002_profiles.sql`: `^[a-z0-9_]{3,20}$`). Mirrored
        /// here so an ID that saves in OSHI is one that can actually resolve.
        static let waypathIDGrammar = "^[a-z0-9_]{3,20}$"

        /// Accepts what people actually paste — `@hugo`, `waypath://user/hugo`,
        /// a full profile URL — and returns the bare lowercase handle, or nil
        /// when what is left does not satisfy `waypathIDGrammar`.
        static func normalizedWaypathID(_ raw: String) -> String? {
            var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            for scheme in ["waypath://", "https://", "http://"] where s.hasPrefix(scheme) {
                s = String(s.dropFirst(scheme.count))
            }
            // Drop a host and/or a routing segment, keeping the last path component:
            // `waypath.app/user/hugo`, `user/hugo`, `athlete/hugo`, `profile/hugo`.
            if let last = s.split(separator: "/").last { s = String(last) }
            s = s.trimmingCharacters(in: CharacterSet(charactersIn: "@ "))
            guard s.range(of: waypathIDGrammar, options: .regularExpression) != nil else { return nil }
            return s
        }
    }
}

// MARK: - Opening a Link

extension ProfileLinks {
    /// Opens a profile link. One implementation for every place a link is
    /// tappable (the chat pinned bar, the group member list) — they used to
    /// carry a copy each that hardcoded `https://`, which is wrong for the
    /// `waypath://` deep link.
    ///
    /// If the app-scheme link cannot be opened — Waypath is not installed —
    /// the App Store page is opened instead, so the tap ends somewhere useful
    /// rather than doing nothing at all.
    static func open(_ type: LinkType, value: String) {
        guard let url = type.destinationURL(for: value) else { return }
        UIApplication.shared.open(url, options: [:]) { opened in
            guard !opened, let fallback = type.storeFallbackURL else { return }
            UIApplication.shared.open(fallback)
        }
    }
}

// MARK: - Links Visibility Payload (sent between users)

struct LinksVisibilityPayload: Codable {
    var visible: Bool
    var links: ProfileLinks?
}

// MARK: - Profile Links Manager

class ProfileLinksManager: ObservableObject {
    static let shared = ProfileLinksManager()

    @Published var myLinks = ProfileLinks()
    @Published private(set) var contactLinks: [String: ProfileLinks] = [:]  // publicKey -> links
    @Published private(set) var contactLinksVisible: [String: Bool] = [:]  // publicKey -> remote activated
    @Published private(set) var hiddenByMe: [String: Bool] = [:]  // publicKey -> hidden by recipient

    static let messagePrefix = "🔗LINKS_VISIBILITY🔗"

    private let myLinksKey = "profileLinks"
    private let contactLinksFile = "contact_links.json"
    private let visibleKey = "linksVisible"
    private let hiddenKey = "linksHidden"
    private let sharingKey = "linksSharingEnabled"
    private let autoSharedHashKey = "linksAutoSharedHash"

    private init() {
        loadMyLinks()
        loadContactLinks()
        loadVisibility()
    }

    // MARK: - My Links

    func saveMyLinks() {
        myLinks.updatedAt = Date()
        if let data = try? JSONEncoder().encode(myLinks) {
            UserDefaults.standard.set(data, forKey: myLinksKey)
            // Also persist for current identity so it restores on switch-back
            if let pubKey = UserDefaults.standard.string(forKey: "publicKey"), !pubKey.isEmpty {
                UserDefaults.standard.set(data, forKey: "profileLinks_\(pubKey)")
            }
        }
        // Invalidate every per-contact "last shared hash" so the next open of
        // ANY chat re-fires autoShareMessageIfNeeded and pushes the freshened
        // links payload to the contact's cache. Without this, a contact whose
        // cached copy held a wrong value (e.g. an `oraclbet.com` URL someone
        // pasted into the LinkedIn field once and auto-shared) keeps showing
        // that stale URL until you happen to update again from inside their
        // chat — by which time you've usually forgotten which one is wrong.
        let allKeys = UserDefaults.standard.dictionaryRepresentation().keys
        for key in allKeys where key.hasPrefix("\(autoSharedHashKey)_") {
            UserDefaults.standard.removeObject(forKey: key)
        }
        // Push immediately to other devices on the same identity so we don't
        // wait for the 30s sync timer (closes the conflict window).
        MultiDeviceSyncManager.shared.uploadProfileForSync()
    }

    private func loadMyLinks() {
        if let data = UserDefaults.standard.data(forKey: myLinksKey),
           let links = try? JSONDecoder().decode(ProfileLinks.self, from: data) {
            myLinks = links
        }
    }

    /// Reload links from UserDefaults (called after multi-device sync updates the store)
    func reloadMyLinks() {
        loadMyLinks()
        objectWillChange.send()
    }

    // MARK: - Per-Conversation Sharing

    func isSharingEnabled(for publicKey: String) -> Bool {
        UserDefaults.standard.bool(forKey: "\(sharingKey)_\(publicKey)")
    }

    func setSharingEnabled(_ enabled: Bool, for publicKey: String) {
        UserDefaults.standard.set(enabled, forKey: "\(sharingKey)_\(publicKey)")
        objectWillChange.send()
    }

    // MARK: - Auto-Share (sends links automatically when user has links filled)

    /// Returns a hash of current links for change detection
    private var myLinksHash: String {
        let parts = [myLinks.websiteURL, myLinks.linkedinURL, myLinks.twitterURL,
                     myLinks.instagramURL, myLinks.githubURL, myLinks.waypathID]
            .compactMap { $0 }
            .joined(separator: "|")
        return parts
    }

    /// Check if we need to auto-share links with this contact
    /// Returns the visibility message to send, or nil if no action needed
    func autoShareMessageIfNeeded(for publicKey: String) -> String? {
        guard myLinks.hasAnyLink else {
            // If user had links before but now removed them all, send visibility=false
            let lastHash = UserDefaults.standard.string(forKey: "\(autoSharedHashKey)_\(publicKey)") ?? ""
            if !lastHash.isEmpty {
                UserDefaults.standard.set("", forKey: "\(autoSharedHashKey)_\(publicKey)")
                setSharingEnabled(false, for: publicKey)
                return createVisibilityMessage(visible: false)
            }
            return nil
        }

        let currentHash = myLinksHash
        let lastHash = UserDefaults.standard.string(forKey: "\(autoSharedHashKey)_\(publicKey)") ?? ""

        // Auto-share if: never shared, or links have changed
        if currentHash != lastHash {
            UserDefaults.standard.set(currentHash, forKey: "\(autoSharedHashKey)_\(publicKey)")
            setSharingEnabled(true, for: publicKey)
            return createVisibilityMessage(visible: true)
        }

        return nil
    }

    // MARK: - Contact Links (from other users)

    func updateContactLinks(publicKey: String, links: ProfileLinks) {
        contactLinks[publicKey] = links
        saveContactLinks()
    }

    func setContactLinksVisible(publicKey: String, visible: Bool) {
        contactLinksVisible[publicKey] = visible
        saveVisibility()
    }

    func getVisibleLinks(for publicKey: String) -> ProfileLinks? {
        guard contactLinksVisible[publicKey] == true else { return nil }
        return contactLinks[publicKey]
    }

    // MARK: - Hidden by Recipient

    func isLinksHiddenFor(conversationKey: String) -> Bool {
        hiddenByMe[conversationKey] ?? false
    }

    func toggleLinksHidden(for conversationKey: String) {
        hiddenByMe[conversationKey] = !(hiddenByMe[conversationKey] ?? false)
        saveVisibility()
    }

    func hideLinksFor(conversationKey: String) {
        hiddenByMe[conversationKey] = true
        saveVisibility()
    }

    // MARK: - Process Incoming

    func processIncomingVisibility(from publicKey: String, content: String) {
        let jsonStr = content.replacingOccurrences(of: Self.messagePrefix, with: "")
        guard let data = jsonStr.data(using: .utf8),
              let payload = try? JSONDecoder().decode(LinksVisibilityPayload.self, from: data) else {
            return
        }
        DispatchQueue.main.async {
            self.contactLinksVisible[publicKey] = payload.visible
            if let links = payload.links, payload.visible {
                self.contactLinks[publicKey] = links
            }
            self.saveContactLinks()
            self.saveVisibility()
        }
    }

    func createVisibilityMessage(visible: Bool) -> String {
        let payload = LinksVisibilityPayload(visible: visible, links: visible ? myLinks : nil)
        if let data = try? JSONEncoder().encode(payload),
           let json = String(data: data, encoding: .utf8) {
            return "\(Self.messagePrefix)\(json)"
        }
        return ""
    }

    // MARK: - Persistence

    private func saveContactLinks() {
        let url = getDocumentsURL().appendingPathComponent(contactLinksFile)
        if let data = try? JSONEncoder().encode(contactLinks) {
            try? AtRestFile.write(data, to: url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ chiffré au repos (ancien clair lu puis rechiffré)
        }
    }

    private func loadContactLinks() {
        let url = getDocumentsURL().appendingPathComponent(contactLinksFile)
        if let data = try? AtRestFile.read(url),  // __PLAINTEXT_LEFTOVERS_2026_09_24__
           let links = try? JSONDecoder().decode([String: ProfileLinks].self, from: data) {
            contactLinks = links
        }
    }

    private func saveVisibility() {
        if let data = try? JSONEncoder().encode(contactLinksVisible) {
            UserDefaults.standard.set(data, forKey: visibleKey)
        }
        if let data = try? JSONEncoder().encode(hiddenByMe) {
            UserDefaults.standard.set(data, forKey: hiddenKey)
        }
    }

    private func loadVisibility() {
        if let data = UserDefaults.standard.data(forKey: visibleKey),
           let map = try? JSONDecoder().decode([String: Bool].self, from: data) {
            contactLinksVisible = map
        }
        if let data = UserDefaults.standard.data(forKey: hiddenKey),
           let map = try? JSONDecoder().decode([String: Bool].self, from: data) {
            hiddenByMe = map
        }
    }

    private func getDocumentsURL() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
    }

    // MARK: - Per-Identity Save/Restore

    /// Save current links for the given identity public key (call before switching away)
    func saveLinksForIdentity(_ publicKey: String) {
        guard !publicKey.isEmpty else { return }
        let key = "profileLinks_\(publicKey)"
        if let data = try? JSONEncoder().encode(myLinks) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    /// Restore links for the given identity public key (call after switching to)
    func restoreLinksForIdentity(_ publicKey: String) {
        guard !publicKey.isEmpty else { return }
        let key = "profileLinks_\(publicKey)"
        if let data = UserDefaults.standard.data(forKey: key),
           let links = try? JSONDecoder().decode(ProfileLinks.self, from: data) {
            myLinks = links
        } else {
            myLinks = ProfileLinks()
        }
        saveMyLinks()
        objectWillChange.send()
    }

    /// Clear all contact links and visibility state (call on identity removal)
    func clearForIdentitySwitch() {
        contactLinks = [:]
        contactLinksVisible = [:]
        hiddenByMe = [:]
        saveContactLinks()
        saveVisibility()
        // Clear auto-shared hashes
        let allKeys = UserDefaults.standard.dictionaryRepresentation().keys
        for key in allKeys where key.hasPrefix(autoSharedHashKey) || key.hasPrefix(sharingKey) {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}

// MARK: - Profile Links Settings View

struct ProfileLinksSettingsView: View {
    @ObservedObject private var manager = ProfileLinksManager.shared
    @State private var websiteURL: String = ""
    @State private var linkedinURL: String = ""
    @State private var twitterURL: String = ""
    @State private var instagramURL: String = ""
    @State private var githubURL: String = ""
    @State private var waypathID: String = ""
    /// Suppresses `.onChange` -> saveLinks during the initial onAppear seeding,
    /// otherwise pre-filled bare prefixes would bump updatedAt and clobber
    /// real links saved on another device.
    @State private var didLoad: Bool = false

    private let brandColor = Theme.brand

    var body: some View {
        List {
            Section {
                linkField(type: .website, text: $websiteURL, placeholder: "https://yourwebsite.com")
                linkField(type: .linkedin, text: $linkedinURL, placeholder: "https://linkedin.com/in/username")
                linkField(type: .twitter, text: $twitterURL, placeholder: "https://x.com/username")
                linkField(type: .instagram, text: $instagramURL, placeholder: "https://instagram.com/username")
                linkField(type: .github, text: $githubURL, placeholder: "https://github.com/username")
            } header: {
                Text("profile_links.section.your_links".localized)
            } footer: {
                Text("profile_links.sharing.footer".localized)
                    .font(.caption2)
            }

            Section {
                WaypathIDRow(text: $waypathID)
            } header: {
                Text("profile_links.section.waypath".localized)
            } footer: {
                // Two different footers: the grammar hint while the field is
                // unusable, the promise of what a tap does once it is valid.
                if !waypathID.trimmingCharacters(in: .whitespaces).isEmpty,
                   ProfileLinks.LinkType.normalizedWaypathID(waypathID) == nil {
                    Text("profile_links.waypath.invalid".localized)
                        .font(.caption2)
                        .foregroundColor(.orange)
                } else {
                    Text("profile_links.waypath.footer".localized)
                        .font(.caption2)
                }
            }
        }
        .navigationTitle("profile_links.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            websiteURL = manager.myLinks.websiteURL ?? ""
            // Pre-fill the canonical prefix for typed platforms so the user
            // only needs to append their handle. Done on appear (not just on
            // focus) so the form looks ready-to-fill the moment it opens.
            linkedinURL = manager.myLinks.linkedinURL ?? (ProfileLinks.LinkType.linkedin.urlPrefix ?? "")
            twitterURL = manager.myLinks.twitterURL ?? (ProfileLinks.LinkType.twitter.urlPrefix ?? "")
            instagramURL = manager.myLinks.instagramURL ?? (ProfileLinks.LinkType.instagram.urlPrefix ?? "")
            githubURL = manager.myLinks.githubURL ?? (ProfileLinks.LinkType.github.urlPrefix ?? "")
            waypathID = manager.myLinks.waypathID ?? ""
            DispatchQueue.main.async { didLoad = true }
        }
        .onChange(of: websiteURL) { if didLoad { saveLinks() } }
        .onChange(of: linkedinURL) { if didLoad { saveLinks() } }
        .onChange(of: twitterURL) { if didLoad { saveLinks() } }
        .onChange(of: instagramURL) { if didLoad { saveLinks() } }
        .onChange(of: githubURL) { if didLoad { saveLinks() } }
        .onChange(of: waypathID) { if didLoad { saveLinks() } }
    }

    private func linkField(type: ProfileLinks.LinkType, text: Binding<String>, placeholder: String) -> some View {
        LinkInputRow(type: type, text: text, placeholder: placeholder)
    }

    /// Strip a bare prefix (e.g. user pre-filled `https://x.com/` but never
    /// typed a handle) so we don't persist a useless URL or share it.
    private func meaningfulValue(_ raw: String, type: ProfileLinks.LinkType) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if let prefix = type.urlPrefix, trimmed == prefix { return nil }
        return trimmed
    }

    private func saveLinks() {
        manager.myLinks = ProfileLinks(
            websiteURL: meaningfulValue(websiteURL, type: .website),
            linkedinURL: meaningfulValue(linkedinURL, type: .linkedin),
            twitterURL: meaningfulValue(twitterURL, type: .twitter),
            instagramURL: meaningfulValue(instagramURL, type: .instagram),
            githubURL: meaningfulValue(githubURL, type: .github),
            // Stored normalised, so what a contact receives is always a handle
            // that can build a working deep link. A half-typed ID persists as
            // nil rather than as a link that opens Waypath onto nothing.
            waypathID: ProfileLinks.LinkType.normalizedWaypathID(waypathID)
        )
        manager.saveMyLinks()
    }
}

/// The Waypath ID field. Separate from `LinkInputRow` because this one holds a
/// handle: no URL prefix to pre-fill, `@` shown as a fixed ornament rather than
/// typed, and the value is validated against Waypath's own grammar as you type.
private struct WaypathIDRow: View {
    @Binding var text: String
    @FocusState private var isFocused: Bool

    private var isUsable: Bool {
        ProfileLinks.LinkType.normalizedWaypathID(text) != nil
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: ProfileLinks.LinkType.waypath.icon)
                .font(.system(size: 18))
                .foregroundColor(ProfileLinks.LinkType.waypath.color)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("profile_links.waypath.field".localized)
                    .font(.caption.weight(.medium))
                    .foregroundColor(.secondary)
                HStack(spacing: 2) {
                    Text("@")
                        .font(.body)
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                    TextField("yourname", text: $text)
                        .accessibilityLabel("profile_links.waypath.field".localized)
                        .font(.body)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($isFocused)
                }
            }
            if !text.trimmingCharacters(in: .whitespaces).isEmpty {
                Image(systemName: isUsable ? "checkmark.circle.fill" : "exclamationmark.circle")
                    .font(.scaled(15))
                    .foregroundColor(isUsable ? .green : .orange)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 4)
    }
}

/// Single profile-link row. When the user focuses an empty field for a typed
/// platform (LinkedIn / X / GitHub / Instagram), the canonical URL prefix is
/// pre-filled so the user only has to type their handle. Website stays free-form.
private struct LinkInputRow: View {
    let type: ProfileLinks.LinkType
    @Binding var text: String
    let placeholder: String
    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: type.icon)
                .font(.system(size: 18))
                .foregroundColor(type.color)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(type.label)
                    .font(.caption.weight(.medium))
                    .foregroundColor(.secondary)
                TextField(placeholder, text: $text)
                    .accessibilityLabel(type.label)
                    .font(.body)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($isFocused)
                    .onChange(of: isFocused) { _, focused in
                        if focused, text.isEmpty, let prefix = type.urlPrefix {
                            text = prefix
                        }
                    }
                    .onChange(of: text) { _, newValue in
                        // If the user trims the field below the prefix while it's still focused,
                        // restore it. If they explicitly clear it (length 0), let it stay empty.
                        if isFocused, !newValue.isEmpty,
                           let prefix = type.urlPrefix,
                           !newValue.hasPrefix(prefix),
                           prefix.hasPrefix(newValue) {
                            text = prefix
                        }
                    }
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Profile Links Pinned Bar (in ChatView)

struct ProfileLinksPinnedBar: View {
    let contactPublicKey: String
    @ObservedObject private var linksManager = ProfileLinksManager.shared
    @StateObject private var faviconLoader = FaviconLoader()

    private let brandColor = Theme.brand

    var body: some View {
        if let links = linksManager.getVisibleLinks(for: contactPublicKey),
           links.hasAnyLink,
           !linksManager.isLinksHiddenFor(conversationKey: contactPublicKey) {
            let filled = links.filledLinks
            // Centered, width adapts to icon count
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                HStack(spacing: 0) {
                    ForEach(filled, id: \.type) { link in
                        Button {
                            ProfileLinks.open(link.type, value: link.url)
                        } label: {
                            VStack(spacing: 2) {
                                if link.type == .website, let favicon = faviconLoader.favicons[link.url] {
                                    Image(uiImage: favicon)
                                        .resizable()
                                        .aspectRatio(contentMode: .fit)
                                        .frame(width: 18, height: 18)
                                        .clipShape(RoundedRectangle(cornerRadius: 4))
                                } else {
                                    Image(systemName: link.type.icon)
                                        .font(.scaled(15, weight: .medium))
                                        .foregroundColor(link.type.color)
                                }
                                Text(link.type == .website ? extractDomain(link.url) : link.type.label)
                                    .font(.scaled(8, weight: .semibold))
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }
                            .frame(width: 48, height: 36)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PlainButtonStyle())
                        .accessibilityLabel(link.type == .website ? extractDomain(link.url) : link.type.label)
                        .accessibilityHint(link.type == .waypath
                                           ? "profile_links.waypath.open.hint".localized
                                           : "profile_links.open.hint".localized)
                    }

                    // Hide button (compact, inline)
                    Button {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        withAnimation(.easeInOut(duration: 0.2)) {
                            linksManager.hideLinksFor(conversationKey: contactPublicKey)
                        }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(.secondary.opacity(0.4))
                            .frame(width: 20, height: 36)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("profile_links.hide.label".localized)
                }
                .padding(.horizontal, 4)
                .background(
                    Capsule()
                        .fill(Color(.secondarySystemGroupedBackground))
                        .shadow(color: .black.opacity(0.06), radius: 2, y: 1)
                )
                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
            .transition(.move(edge: .top).combined(with: .opacity))
            .onAppear {
                // Fetch favicons for website links
                for link in filled where link.type == .website {
                    faviconLoader.loadFavicon(for: link.url)
                }
            }
        }
    }

    private func extractDomain(_ urlString: String) -> String {
        var str = urlString
        if !str.hasPrefix("http") { str = "https://\(str)" }
        guard let url = URL(string: str), let host = url.host else { return "Web" }
        // Remove www. prefix
        let clean = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        // Return short domain (e.g. "google.com" → "Google")
        let parts = clean.components(separatedBy: ".")
        if parts.count >= 2 {
            return parts[parts.count - 2].capitalized
        }
        return clean.capitalized
    }
}

// MARK: - Favicon Loader

class FaviconLoader: ObservableObject {
    @Published var favicons: [String: UIImage] = [:]
    private var loadingURLs: Set<String> = []

    func loadFavicon(for urlString: String) {
        guard !loadingURLs.contains(urlString), favicons[urlString] == nil else { return }
        loadingURLs.insert(urlString)

        var str = urlString
        if !str.hasPrefix("http://") && !str.hasPrefix("https://") {
            str = "https://\(str)"
        }
        guard let siteURL = URL(string: str), let host = siteURL.host else { return }
        let baseURL = (siteURL.scheme ?? "https") + "://" + host

        // Step 1: Fetch the HTML page to find the real favicon link
        var request = URLRequest(url: siteURL)
        request.timeoutInterval = 5
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")

        URLSession.shared.dataTask(with: request) { [weak self] data, _, error in
            guard let self = self else { return }

            var faviconCandidates: [String] = []

            if let data = data, let html = String(data: data, encoding: .utf8) {
                // Parse HTML for favicon links (apple-touch-icon first for best quality)
                let patterns = [
                    "<link[^>]*rel=\"apple-touch-icon\"[^>]*href=\"([^\"]+)\"",
                    "<link[^>]*href=\"([^\"]+)\"[^>]*rel=\"apple-touch-icon\"",
                    "<link[^>]*rel=\"icon\"[^>]*href=\"([^\"]+)\"[^>]*sizes=\"(32x32|48x48|64x64|96x96|128x128|192x192|256x256)\"",
                    "<link[^>]*rel=\"icon\"[^>]*type=\"image/png\"[^>]*href=\"([^\"]+)\"",
                    "<link[^>]*rel=\"icon\"[^>]*href=\"([^\"]+)\"",
                    "<link[^>]*rel=\"shortcut icon\"[^>]*href=\"([^\"]+)\"",
                    "<link[^>]*href=\"([^\"]+)\"[^>]*rel=\"icon\"",
                    "<link[^>]*href=\"([^\"]+)\"[^>]*rel=\"shortcut icon\""
                ]

                for pattern in patterns {
                    if let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
                       let match = regex.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
                       let range = Range(match.range(at: 1), in: html) {
                        let href = String(html[range])
                            .replacingOccurrences(of: "&amp;", with: "&")
                        let absolute = self.makeAbsolute(href, base: baseURL)
                        if !faviconCandidates.contains(absolute) {
                            faviconCandidates.append(absolute)
                        }
                    }
                }
            }

            // Fallback candidates
            faviconCandidates.append(baseURL + "/apple-touch-icon.png")
            faviconCandidates.append(baseURL + "/favicon-32x32.png")
            faviconCandidates.append(baseURL + "/favicon.ico")

            // Try each candidate until one works
            self.tryLoadImage(from: faviconCandidates, index: 0, forKey: urlString)
        }.resume()
    }

    private func tryLoadImage(from urls: [String], index: Int, forKey key: String) {
        guard index < urls.count else { return } // All failed, no favicon
        guard let url = URL(string: urls[index]) else {
            tryLoadImage(from: urls, index: index + 1, forKey: key)
            return
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 4

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            // Check for valid image response
            if let data = data,
               let httpResponse = response as? HTTPURLResponse,
               httpResponse.statusCode == 200,
               data.count > 100, // Skip tiny placeholder/error responses
               let image = UIImage(data: data) {
                DispatchQueue.main.async {
                    self?.favicons[key] = image
                }
            } else {
                // Try next candidate
                self?.tryLoadImage(from: urls, index: index + 1, forKey: key)
            }
        }.resume()
    }

    private func makeAbsolute(_ href: String, base: String) -> String {
        if href.hasPrefix("http://") || href.hasPrefix("https://") {
            return href
        }
        if href.hasPrefix("//") {
            return "https:" + href
        }
        if href.hasPrefix("/") {
            return base + href
        }
        return base + "/" + href
    }
}

// MARK: - Conversation Links Sharing Toggle View

struct ConversationLinksSharingView: View {
    let contactPublicKey: String
    let contactAlias: String
    @ObservedObject private var manager = ProfileLinksManager.shared
    @State private var isSharing: Bool = false

    var body: some View {
        Toggle(isOn: $isSharing) {
            HStack(spacing: 10) {
                Image(systemName: "link.circle.fill")
                    .font(.scaled(22))
                    .foregroundColor(.blue)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("profile_links.share_with".localized)
                        .font(.subheadline)
                    Text(contactAlias)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .onAppear {
            isSharing = manager.isSharingEnabled(for: contactPublicKey)
        }
        .onChange(of: isSharing) { _, newValue in
            manager.setSharingEnabled(newValue, for: contactPublicKey)
            // Send visibility update to contact
            let message = manager.createVisibilityMessage(visible: newValue)
            NotificationCenter.default.post(
                name: NSNotification.Name("SendLinksVisibilityUpdate"),
                object: nil,
                userInfo: ["recipient": contactPublicKey, "content": message]
            )
        }
    }
}
