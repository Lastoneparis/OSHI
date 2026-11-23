//
//  SettingsView.swift
//  OSHI - Modern WhatsApp-style Settings
//  ✅ REDESIGNED: Profile header + Card-based sections + Modern UX
//  🔧 FIX: Replaced AnyView with lazy navigationDestination to fix iPhone rendering
//

import SwiftUI
import LocalAuthentication
import UniformTypeIdentifiers
import UserNotifications
import PhotosUI
import CoreImage.CIFilterBuiltins

// MARK: - Brand Color
private let settingsBrandColor = Theme.brand

struct SettingsView: View {
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @EnvironmentObject var messageManager: MessageManager
    @Environment(\.colorScheme) var colorScheme
    @Environment(\.dismiss) private var settingsDismiss
    @Environment(\.requestReview) private var requestReview

    @State private var showingClearSessionsAlert = false
    @State private var showingWalletInfo = false
    @State private var showingBackupSeed = false
    @State private var showingQRCode = false
    @State private var showingIdentityVault = false
    @State private var showingDisconnectAlert = false
    @State private var showingClearDataAlert = false
    @State private var showExportSheet = false
    @State private var exportURL: URL?
    @State private var isExporting = false
    @State private var showImporter = false
    @State private var importResultMessage: String?
    @State private var showingProfileImagePicker = false
    @State private var showingNicknameEditor = false
    @State private var nicknameDraft = ""
    @State private var profileImage: UIImage?
    @AppStorage("userName") private var userName = ""
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()

    private var keyCreationDate: Date {
        identityManager.getKeyCreationDate()
    }

    // __DUO_POLISH_2026_11_18__ Settings is a list | page split when the
    // window is wide (open iPhone Duo, iPad, Pro Max on its side), like the
    // Settings app itself. The open pages are ONE array in both layouts — the
    // narrow stack's path, or [sidebar selection] + the detail stack's path —
    // so folding/unfolding keeps the page the user is on.
    @Environment(\.horizontalSizeClass) private var settingsSizeClass
    @State private var settingsWidth: CGFloat = 0
    @State private var settingsPath: [SettingsHubTarget] = []

    private var settingsWide: Bool {
        ThreadSplitMetrics.isWide(sizeClass: settingsSizeClass, width: settingsWidth)
    }

    /// The page shown in the detail pane: the open one, else Account (the
    /// Settings app never leaves its detail pane empty either).
    private var settingsSelection: SettingsHubTarget {
        settingsPath.first ?? .category(.account)
    }

    var body: some View {
        settingsPresentations(
            SwiftUI.Group {
                if settingsWide {
                    settingsSplit
                } else {
                    NavigationStack(path: $settingsPath) {
                        settingsHubScroll
                            // 🔧 FIX: Lazy navigation — views created only when tapped, not during body evaluation.
                            // Previously all 10 destinations were eagerly instantiated via AnyView(), blocking iPhone rendering.
                            // __SETTINGS_CATEGORIES_2026_10_26__ Category pages and destinations
                            // share one route type, registered on the root of the stack,
                            // so the nav rows INSIDE a page still push normally.
                            .navigationDestination(for: SettingsHubTarget.self) { target in
                                settingsPage(target)
                                    .modifier(SettingsCloseButton(close: { settingsDismiss() }))
                            }
                    }
                }
            }
            .measureWidth(into: $settingsWidth)
        )
    }

    /// Wide: the hub in a sidebar, the selected page next to it.
    private var settingsSplit: some View {
        HStack(spacing: 0) {
            NavigationStack {
                settingsHubScroll
            }
            .frame(width: ThreadSplitMetrics.sidebarWidth(for: settingsWidth))

            Divider()
                .ignoresSafeArea()

            NavigationStack(path: Binding(
                get: { Array(settingsPath.dropFirst()) },
                set: { settingsPath = [settingsSelection] + $0 }
            )) {
                settingsPage(settingsSelection)
                    .navigationDestination(for: SettingsHubTarget.self) { target in
                        settingsPage(target)
                    }
            }
            // A fresh stack per sidebar row: what was pushed inside Account
            // must not stay on top when Privacy is picked.
            .id(settingsSelection)
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("settings.detail")
        }
    }

    @ViewBuilder
    private func settingsPage(_ target: SettingsHubTarget) -> some View {
        switch target {
        case .category(let category): categoryPage(category)
        case .destination(let destination): settingsDestinationView(destination)
        }
    }

    private var settingsHubScroll: some View {
            ScrollView {
                VStack(spacing: 16) {
                    // MARK: - Profile Header Card
                    profileHeaderCard

                    // MARK: - Quick Stats Card
                    quickStatsCard

                    // MARK: - Categories
                    //
                    // __SETTINGS_CATEGORIES_2026_10_26__ Settings was twelve
                    // cards deep in one scroll. A user who had just restored
                    // onto a new phone asked for it by name: categories, not an
                    // endless scroll. So the one screen became ten rows, and
                    // every section below moved behind the row it belongs to —
                    // nothing was removed and nothing changed behaviour.
                    //
                    // Rows that already pushed a screen of their own still push
                    // it directly; only the three groups that were several cards
                    // (account, network, data) gained a page. Account deletion
                    // moved one tap deeper, inside Account, which is where iOS
                    // itself keeps it and still "findable in the app" for
                    // guideline 5.1.1(v).
                    settingsHubCard(hubItems)

                    // Version info
                    versionInfo

                    // MARK: - Disconnect Button
                    disconnectButton

                    Spacer(minLength: 40)
                }
                .padding()
                // iPad / landscape: keep the settings column readable instead of
                // stretching cards edge-to-edge on wide screens.
                .frame(maxWidth: 700)
                .frame(maxWidth: .infinity)
            }
            .glassAwareGround(Color(.systemGroupedBackground))  // __GLASS_ALL_THEMES_2026_11_21__
            .navigationTitle("settings.title".localized)
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { settingsDismiss() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundColor(.secondary)
                    }
                    .accessibilityLabel("common.close".localized)
                }
            }
    }

    /// Sheets and alerts, the same in both layouts. A function (not inline
    /// in `body`) for the type-checker budget.
    private func settingsPresentations<Content: View>(_ content: Content) -> some View {
        content
            .sheet(isPresented: $showingIdentityVault) {
                IdentityVaultView()
                    .environmentObject(identityManager)
            }
            .sheet(isPresented: $showingWalletInfo) {
                WalletInfoView()
            }
            .sheet(isPresented: $showingBackupSeed) {
                BackupSeedView()
            }
            .sheet(isPresented: $showingQRCode) {
                QRCodeView(address: identityManager.identityAddress, publicKey: identityManager.publicKey)
            }
            .alert("settings.alert.clearSessions.title".localized, isPresented: $showingClearSessionsAlert) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("settings.alert.clearSessions.action".localized, role: .destructive) {
                    messageManager.clearRatchetSessions()
                }
            } message: {
                Text("settings.alert.clearSessions.message".localized)
            }
            .alert("settings.alert.disconnect.title".localized, isPresented: $showingDisconnectAlert) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("settings.alert.disconnect.action".localized, role: .destructive) {
                    identityManager.disconnectWallet()
                }
            } message: {
                Text("settings.alert.disconnect.message".localized)
            }
            .alert("settings.alert.clearData.title".localized, isPresented: $showingClearDataAlert) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("settings.alert.clearData.action".localized, role: .destructive) {
                    messageManager.clearAllMessages()
                }
            } message: {
                Text("settings.alert.clearData.message".localized)
            }
            .sheet(isPresented: $showExportSheet, onDismiss: {
                // __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ L'export ne reste pas
                // sur l'appareil une fois partagé.
                if let url = exportURL { try? FileManager.default.removeItem(at: url) }
                exportURL = nil
            }) {
                if let url = exportURL {
                    ShareSheet(activityItems: [url])
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data]) { result in
                importChatMessages(result)
            }
            .alert(NSLocalizedString("settings.data.importMessages", value: "Import Messages", comment: ""),
                   isPresented: Binding(get: { importResultMessage != nil }, set: { if !$0 { importResultMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importResultMessage ?? "")
            }
    }

    /// Extracted from `body` (type-checker budget): the screen each settings
    /// row pushes.
    @ViewBuilder
    private func settingsDestinationView(_ destination: SettingsDestination) -> some View {
        switch destination {
        case .privacy: PrivacySettingsView()
        case .deleteAccount: AccountDeletionView()
        case .loraNode: LoRaNodeSetupView()
        case .security: SecuritySettingsView()
        case .calls: CallSettingsView()
        case .steganography: PrivacyDisguiseSettingsView()
        case .notifications: NotificationSettingsView()
        case .appearance: AppearanceSettingsView()
        case .bots: BotManagementView()
        case .webSessions: WebSessionsView()
        case .help: HelpView()
        case .about: AboutView()
        case .profileLinks: ProfileLinksSettingsView()
        case .meshtastic: MeshtasticSettingsView()
        case .linkedDevices: LinkedDevicesView()
        }
    }

    // MARK: - Share Sheet for Export
    struct ShareSheet: UIViewControllerRepresentable {
        let activityItems: [Any]

        func makeUIViewController(context: Context) -> UIActivityViewController {
            UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
        }

        func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
    }

    // MARK: - Identity Header Card

    private var profileHeaderContent: some View {
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                Button(action: { showingProfileImagePicker = true }) {
                    ZStack(alignment: .bottomTrailing) {
                        if let image = profileImage {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 70, height: 70)
                                .clipShape(Circle())
                        } else if let data = identityManager.profileImageData,
                                  let uiImage = UIImage(data: data) {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 70, height: 70)
                                .clipShape(Circle())
                        } else {
                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: [settingsBrandColor, settingsBrandColor.opacity(0.6)],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 70, height: 70)
                                .overlay(
                                    Image(systemName: "person.fill")
                                        .font(.scaled(28, weight: .bold))
                                        .foregroundColor(.white)
                                )
                        }

                        Circle()
                            .fill(settingsBrandColor)
                            .frame(width: 24, height: 24)
                            .overlay(
                                Image(systemName: "camera.fill")
                                    .font(.scaled(10, weight: .bold))
                                    .foregroundColor(.white)
                            )
                            .overlay(
                                Circle()
                                    .stroke(Color(.systemBackground), lineWidth: 2)
                            )
                    }
                }
                .accessibilityLabel("settings.identity.editPhoto".localized)
                .accessibilityHint("settings.identity.editPhoto.hint".localized)

                VStack(alignment: .leading, spacing: 6) {
                    Text(displayNickname)
                        .font(.title3)
                        .fontWeight(.semibold)
                        .foregroundColor(.primary)

                    Button {
                        nicknameDraft = userName
                        showingNicknameEditor = true
                    } label: {
                        Label("settings.profile.editNickname".localized, systemImage: "pencil")
                            .font(.caption)
                            .foregroundColor(settingsBrandColor)
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("settings.profile.editNickname.hint".localized)

                    Text(formatAddress(identityManager.publicKey))
                        .font(.system(.caption, design: .monospaced))
                        .foregroundColor(settingsBrandColor)
                        // __DUO_POLISH_2026_11_18__ In the wide layout's sidebar
                        // (~300 pt) the key broke as "…z3EmA" / "=": one line.
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)

                    HStack(spacing: 4) {
                        Image(systemName: "calendar")
                            .font(.caption2)
                        Text("settings.identity.created".localized + " " + formattedCreationDate)
                            .font(.caption)
                    }
                    .foregroundColor(.secondary)
                }

                Spacer()
            }
            .padding()
        }
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }

    /// __ANATOLE_1311_IOS__ (N3) The card's modifiers (picker sheet, nickname
    /// alert, upload on change) stay on the root page; the Account page shows
    /// the same card through `profileHeaderContent`, whose buttons set the same
    /// state, so the photo and the name can be edited from either page without
    /// two copies of the sheets fighting over one binding.
    private var profileHeaderCard: some View {
        profileHeaderContent
        .sheet(isPresented: $showingProfileImagePicker) {
            ProfileImagePicker(image: $profileImage)
        }
        .onChange(of: profileImage) { oldValue, newValue in
            if let image = newValue {
                // 512px / 0.8 quality matches Android's `compressBitmap` — the same
                // photo syncs to both a sender's and a recipient's app, so the two
                // platforms should land on the same ballpark rather than one being
                // visibly softer. Still small enough for a LoRa-constrained peer.
                let resizedImage = resizeProfileImage(image, targetSize: CGSize(width: 512, height: 512))
                if let data = resizedImage.jpegData(compressionQuality: 0.8) {
                    OshiLog.ui.info("📸 Profile image compressed: \(data.count / 1024) KB")
                    identityManager.setProfileImage(data)
                    // The archive is encrypted with this identity's private-key
                    // derived sync key, so linked iPhone/Mac/Android sessions
                    // receive the avatar without exposing it to the service.
                    MultiDeviceSyncManager.shared.uploadProfileForSync()
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    broadcastProfileUpdate()
                }
            }
        }
        .onAppear {
            if let data = identityManager.profileImageData,
               let image = UIImage(data: data) {
                profileImage = image
            }
        }
        .alert("settings.profile.editNickname".localized, isPresented: $showingNicknameEditor) {
            TextField("settings.profile.nickname.placeholder".localized, text: $nicknameDraft)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
            Button("common.cancel".localized, role: .cancel) {}
            Button("common.save".localized) {
                saveNickname()
            }
        } message: {
            Text("settings.profile.nickname.message".localized)
        }
    }

    private var displayNickname: String {
        let trimmed = userName.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "settings.identity.title".localized : trimmed
    }

    /// A nickname is profile data: keep it compact, persist it locally, and
    /// immediately update the encrypted archive shared by devices with this key.
    private func saveNickname() {
        let normalized = String(nicknameDraft.trimmingCharacters(in: .whitespacesAndNewlines).prefix(48))
        guard !normalized.isEmpty else { return }

        userName = normalized
        MultiDeviceSyncManager.shared.uploadProfileForSync()
        broadcastProfileUpdate()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    private func broadcastProfileUpdate() {
        OshiLog.ui.info("📸 Broadcasting profile update to all contacts...")
        ContactPresenceManager.shared.broadcastProfileUpdateToContacts(via: messageManager)
    }

    private var formattedCreationDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: keyCreationDate)
    }

    // MARK: - Quick Stats Card

    private var quickStatsCard: some View {
        HStack(spacing: 0) {
            StatItem(
                icon: "message.fill",
                value: "\(messageManager.messages.count)",
                label: "settings.stats.messages".localized,
                color: settingsBrandColor
            )

            Divider()
                .frame(height: 40)

            StatItem(
                icon: "person.2.fill",
                value: "\(messageManager.conversations.count)",
                label: "settings.stats.chats".localized,
                color: .green
            )

            Divider()
                .frame(height: 40)

            StatItem(
                icon: "antenna.radiowaves.left.and.right",
                value: "\(meshManager.connectedPeers.count)",
                label: "settings.stats.peers".localized,
                color: .orange
            )
        }
        .padding(.vertical, 16)
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }

    // MARK: - Settings Section (Action Items)

    // MARK: - Category Hub  __SETTINGS_CATEGORIES_2026_10_26__

    /**
     The ten rows that replaced the twelve-card scroll.

     Every title is a string key THAT ALREADY SHIPS in all 34 locales, and the
     subtitles are built from the row titles inside each category rather than
     written fresh — a settings screen that opened in English for everyone but
     en would have been a worse bug than the scroll.
     */
    private var hubItems: [SettingsHubItem] {
        [
            SettingsHubItem(
                icon: "person.crop.circle.fill", iconColor: settingsBrandColor,
                title: "settings.account".localized,
                subtitle: twoUp("settings.identity.showQR", "settings.identityVault.title"),
                target: .category(.account)
            ),
            SettingsHubItem(
                icon: "hand.raised.fill", iconColor: .green,
                title: "settings.privacy.title".localized, subtitle: "",
                target: .destination(.privacy)
            ),
            SettingsHubItem(
                icon: "lock.shield.fill", iconColor: settingsBrandColor,
                title: "settings.security.title".localized, subtitle: "",
                target: .destination(.security)
            ),
            SettingsHubItem(
                icon: "eye.slash.fill", iconColor: .orange,
                title: "disguise.title".localized, subtitle: "",
                target: .destination(.steganography)
            ),
            SettingsHubItem(
                icon: "phone.fill", iconColor: .blue,
                title: "settings.calls.title".localized, subtitle: "",
                target: .destination(.calls)
            ),
            SettingsHubItem(
                icon: "bell.badge.fill", iconColor: .red,
                title: "settings.notifications".localized, subtitle: "",
                target: .destination(.notifications)
            ),
            SettingsHubItem(
                icon: "paintbrush.fill", iconColor: .purple,
                title: "settings.appearance".localized, subtitle: "",
                target: .destination(.appearance)
            ),
            SettingsHubItem(
                icon: "antenna.radiowaves.left.and.right", iconColor: .green,
                title: "settings.network".localized,
                subtitle: twoUp("meshtastic.title", "settings.webSessions.title"),
                target: .category(.network)
            ),
            SettingsHubItem(
                icon: "externaldrive.fill", iconColor: .blue,
                title: "settings.data_management".localized,
                subtitle: twoUp("settings.data.exportMessages", "map_open_preference"),
                target: .category(.data)
            ),
            SettingsHubItem(
                icon: "info.circle.fill", iconColor: .gray,
                title: "settings.about".localized,
                subtitle: twoUp("settings.help.title", "settings.rate_oshi"),
                target: .category(.about)
            )
        ]
    }

    /// "A · B" from two localized keys. Separator only, so it needs no translation.
    private func twoUp(_ first: String, _ second: String) -> String {
        "\(first.localized) · \(second.localized)"
    }

    private func settingsHubCard(_ items: [SettingsHubItem]) -> some View {
        VStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { index in
                hubRow(items[index])

                if index < items.count - 1 {
                    Divider()
                        .padding(.leading, 60)
                }
            }
        }
        .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(12)
    }

    /// One row. The two target kinds are two different `NavigationLink(value:)`
    /// types, which is why this is a switch and not one link with a payload.
    ///
    /// __DUO_POLISH_2026_11_18__ One route type now (`SettingsHubTarget`), so the
    /// path is a plain array both layouts share. Wide, a row SELECTS its page
    /// into the detail pane (and shows it is the selected one) instead of pushing.
    @ViewBuilder
    private func hubRow(_ item: SettingsHubItem) -> some View {
        if settingsWide {
            let selected = settingsSelection == item.target
            Button {
                settingsPath = [item.target]
            } label: {
                hubRowLabel(item, selected: selected)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? .isSelected : [])
        } else {
            NavigationLink(value: item.target) { hubRowLabel(item) }
        }
    }

    private func hubRowLabel(_ item: SettingsHubItem, selected: Bool = false) -> some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(item.iconColor.opacity(0.15))
                    .frame(width: 32, height: 32)

                Image(systemName: item.icon)
                    .font(.scaled(15, weight: .semibold))
                    .foregroundColor(item.iconColor)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.body)
                    .fontWeight(selected ? .semibold : .regular)
                    .foregroundColor(.primary)

                if !item.subtitle.isEmpty {
                    Text(item.subtitle)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer()

            // Wide, the row selects in place (sidebar): no "pushes" chevron.
            if !settingsWide {
                Image(systemName: "chevron.right")
                    .font(.scaled(13, weight: .semibold))
                    .foregroundColor(Color(.tertiaryLabel))
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(selected ? Theme.brand.opacity(0.16) : Color.clear)
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
        )
        .contentShape(Rectangle())
    }

    // MARK: - Category Pages

    @ViewBuilder
    private func categoryPage(_ category: SettingsCategory) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                switch category {
                case .account: accountSections
                case .network: networkSections
                case .data: dataSections
                case .about: aboutSections
                }

                Spacer(minLength: 40)
            }
            .padding()
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
        }
        .glassAwareGround(Color(.systemGroupedBackground))  // __GLASS_ALL_THEMES_2026_11_21__
        .navigationTitle(category.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var accountSections: some View {
        // __ANATOLE_1311_IOS__ (N3) "modifier directement la photo de profil
        // et le nom dès la première page" of Account.
        profileHeaderContent

        settingsSection(
            title: "settings.section.account".localized,
            items: [
                SettingsItem(icon: "qrcode", iconColor: settingsBrandColor, title: "settings.identity.showQR".localized, action: { showingQRCode = true }),
                SettingsItem(icon: "key.fill", iconColor: .orange, title: "settings.identity.backupKeys".localized, action: { showingBackupSeed = true }),
                SettingsItem(icon: "info.circle.fill", iconColor: .blue, title: "settings.identity.details".localized, action: { showingWalletInfo = true }),
                SettingsItem(icon: "person.2.fill", iconColor: .green, title: "settings.identityVault.title".localized, action: { showingIdentityVault = true })
            ]
        )

        settingsSectionNav(
            title: "settings.section.profileLinks".localized,
            items: [
                SettingsNavItem(icon: "link.circle.fill", iconColor: .blue, title: "profile_links.title".localized, destination: .profileLinks)
            ]
        )

        // Still its own card rather than a fifth row above: deleting messages
        // and deleting an ACCOUNT are different promises, and Apple looks for
        // the second one being findable (guideline 5.1.1(v)).
        settingsSectionNav(
            title: "",
            items: [
                SettingsNavItem(icon: "person.crop.circle.badge.xmark", iconColor: .red, title: "account.delete.title".localized, destination: .deleteAccount)
            ]
        )
    }

    @ViewBuilder
    private var networkSections: some View {
        settingsSectionNav(
            title: "settings.section.longRange".localized,
            items: [
                SettingsNavItem(icon: "antenna.radiowaves.left.and.right", iconColor: .green, title: "meshtastic.title".localized, destination: .meshtastic),
                SettingsNavItem(icon: "dot.radiowaves.left.and.right", iconColor: .purple, title: "lora.node.title".localized, destination: .loraNode)
            ]
        )

        settingsSectionNav(
            title: "settings.section.oshiWeb".localized,
            items: [
                SettingsNavItem(icon: "desktopcomputer", iconColor: settingsBrandColor, title: "settings.webSessions.title".localized, destination: .webSessions)
            ] + (DevSyncFlag.enabled ? [
                // __DEVSYNC_DIRECT_2026_09_23__ own-device direct sync (behind /v2/config devsync_enabled)
                SettingsNavItem(icon: "ipad.and.iphone", iconColor: .green, title: "devsync.title".localized, destination: .linkedDevices)
            ] : [])
        )

        settingsSectionNav(
            title: "settings.section.bots".localized,
            items: [
                SettingsNavItem(icon: "cpu.fill", iconColor: settingsBrandColor, title: "bot.management_title".localized, destination: .bots)
            ]
        )
    }

    @ViewBuilder
    private var dataSections: some View {
        settingsSection(
            title: "settings.section.dataManagement".localized,
            items: [
                SettingsItem(icon: "square.and.arrow.up", iconColor: .blue, title: "settings.data.exportMessages".localized, action: { exportChatMessages() }),
                SettingsItem(icon: "square.and.arrow.down", iconColor: .blue, title: NSLocalizedString("settings.data.importMessages", value: "Import Messages", comment: "Restore an encrypted OSHI message export"), action: { showImporter = true }),
                SettingsItem(icon: "arrow.triangle.2.circlepath", iconColor: .orange, title: "settings.data.clearSessions".localized, action: { showingClearSessionsAlert = true }),
                SettingsItem(icon: "trash.fill", iconColor: .red, title: "settings.data.clearMessages".localized, isDestructive: true, action: { showingClearDataAlert = true })
            ]
        )

        MapPreferenceSection()
    }

    @ViewBuilder
    private var aboutSections: some View {
        settingsSectionNav(
            title: "settings.section.about".localized,
            items: [
                SettingsNavItem(icon: "questionmark.circle.fill", iconColor: .blue, title: "settings.help.title".localized, destination: .help),
                SettingsNavItem(icon: "info.circle.fill", iconColor: .gray, title: "settings.about.title".localized, destination: .about)
            ]
        )

        settingsSection(
            title: "",
            items: [
                SettingsItem(icon: "star.fill", iconColor: .yellow, title: "settings.rate_oshi".localized, action: {
                    // Apple's own sheet while the yearly budget (shared with the
                    // automatic ask) allows; the write-review page once it is spent.
                    RateAppManager.shared.handleRateButton(
                        request: { requestReview() },
                        openStorePage: { AppReviewManager.shared.openAppStoreReview() })
                })
            ]
        )
    }

    private func settingsSection(title: String, items: [SettingsItem]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundColor(.secondary)
                .padding(.horizontal, 4)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                ForEach(items.indices, id: \.self) { index in
                    Button {
                        items[index].action()
                    } label: {
                        HStack(spacing: 14) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 8)
                                    .fill(items[index].iconColor.opacity(0.15))
                                    .frame(width: 32, height: 32)

                                Image(systemName: items[index].icon)
                                    .font(.scaled(15, weight: .semibold))
                                    .foregroundColor(items[index].iconColor)
                            }

                            Text(items[index].title)
                                .font(.body)
                                .foregroundColor(items[index].isDestructive ? .red : .primary)

                            Spacer()

                            Image(systemName: "chevron.right")
                                .font(.scaled(13, weight: .semibold))
                                .foregroundColor(Color(.tertiaryLabel))
                                .accessibilityHidden(true)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }

                    if index < items.count - 1 {
                        Divider()
                            .padding(.leading, 60)
                    }
                }
            }
            .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
            .cornerRadius(12)
        }
    }

    // MARK: - Settings Section (Navigation Items)

    private func settingsSectionNav(title: String, items: [SettingsNavItem]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundColor(.secondary)
                .padding(.horizontal, 4)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                ForEach(items.indices, id: \.self) { index in
                    // 🔧 FIX: NavigationLink(value:) defers view creation to navigationDestination(for:)
                    NavigationLink(value: SettingsHubTarget.destination(items[index].destination)) {
                        HStack(spacing: 14) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 8)
                                    .fill(items[index].iconColor.opacity(0.15))
                                    .frame(width: 32, height: 32)

                                Image(systemName: items[index].icon)
                                    .font(.scaled(15, weight: .semibold))
                                    .foregroundColor(items[index].iconColor)
                            }

                            Text(items[index].title)
                                .font(.body)
                                .foregroundColor(.primary)

                            Spacer()

                            Image(systemName: "chevron.right")
                                .font(.scaled(13, weight: .semibold))
                                .foregroundColor(Color(.tertiaryLabel))
                                .accessibilityHidden(true)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }

                    if index < items.count - 1 {
                        Divider()
                            .padding(.leading, 60)
                    }
                }
            }
            .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
            .cornerRadius(12)
        }
    }

    // MARK: - Version Info

    private var versionInfo: some View {
        VStack(spacing: 4) {
            Image("AppIcon60x60")
                .resizable()
                .frame(width: 60, height: 60)
                .cornerRadius(12)
                .shadow(color: .black.opacity(0.1), radius: 4)
                .accessibilityHidden(true)

            Text("OSHI")
                .font(.headline)
                .foregroundColor(.primary)

            Text("settings.version".localized + " " + (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.3"))
                .font(.caption)
                .foregroundColor(.secondary)

            Link(destination: URL(string: "https://oshi-messenger.com")!) {
                Text("oshi-messenger.com")
                    .font(.caption)
                    .foregroundColor(settingsBrandColor)
            }
        }
        .padding(.vertical, 20)
    }

    // MARK: - Disconnect Button

    private var disconnectButton: some View {
        Button {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            showingDisconnectAlert = true
        } label: {
            HStack {
                Image(systemName: "rectangle.portrait.and.arrow.right")
                Text("settings.disconnect.button".localized)
            }
            .font(.body)
            .fontWeight(.medium)
            .foregroundColor(.red)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.red.opacity(0.1))
            .cornerRadius(12)
        }
        .accessibilityHint("settings.disconnect.hint".localized)
    }

    // MARK: - Helpers

    private func formatAddress(_ address: String) -> String {
        guard address.count > 12 else { return address }
        let start = address.prefix(8)
        let end = address.suffix(6)
        return "\(start)...\(end)"
    }

    private func resizeProfileImage(_ image: UIImage, targetSize: CGSize) -> UIImage {
        let size = image.size
        let widthRatio = targetSize.width / size.width
        let heightRatio = targetSize.height / size.height
        let ratio = min(widthRatio, heightRatio)

        let newSize = CGSize(width: size.width * ratio, height: size.height * ratio)
        let rect = CGRect(origin: .zero, size: newSize)

        // 🔧 FIX: `UIGraphicsBeginImageContextWithOptions` used to hardcode scale
        // 1.0 here, which rendered the resize at literal 1x pixel density on
        // every Retina device — a 512pt target came out as a 512px bitmap when
        // the screen is 2x/3x, well below what the screen can actually show.
        // `UIGraphicsImageRenderer` defaults to the current screen scale and is
        // the modern replacement for the old context APIs.
        let format = UIGraphicsImageRendererFormat()
        format.scale = UIScreen.main.scale
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: newSize, format: format)
        return renderer.image { _ in
            image.draw(in: rect)
        }
    }

    /// __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ L'export est chiffré avec la clé
    /// du compte (voir `EncryptedMessageExport`). Avant: tout l'historique en
    /// JSON clair — `plaintextContent` et médias — écrit dans Documents (donc
    /// sauvegardé sur iCloud) puis remis à la feuille de partage.
    private func exportChatMessages() {
        isExporting = true
        // __EXPORT_V2_INTEROP_2026_09_22__ 1:1 + groupes + alias, charge v2 neutre.
        let snapshot = MessageExportV2.capture(messageManager, identity: identityManager)
        Task.detached(priority: .userInitiated) {
            do {
                let url = try EncryptedMessageExport.write(snapshot, identity: identityManager)
                await MainActor.run {
                    isExporting = false
                    exportURL = url
                    showExportSheet = true
                }
            } catch {
                await MainActor.run {
                    isExporting = false
                    importResultMessage = error.localizedDescription
                }
                OshiLog.ui.info("Export failed: \(error)")
            }
        }
    }

    private func importChatMessages(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        Task.detached(priority: .userInitiated) {
            do {
                let imported = try EncryptedMessageExport.read(url, identity: identityManager)
                await MainActor.run {
                    // __EXPORT_V2_INTEROP_2026_09_22__ export iOS, Android ou Desktop.
                    let counts = MessageExportV2.apply(imported, to: messageManager)
                    var text = String(
                        format: NSLocalizedString("settings.data.importMessages.done",
                                                  value: "%d messages restored.",
                                                  comment: "After importing an encrypted export"),
                        counts.imported)
                    if counts.skippedGroups > 0 {
                        text += " " + String(
                            format: NSLocalizedString("settings.data.importMessages.skippedGroups",
                                                      value: "%d group messages skipped: join those groups on this device, then import again.",
                                                      comment: "Import: messages of groups this device is not in"),
                            counts.skippedGroups)
                    }
                    importResultMessage = text
                }
            } catch {
                await MainActor.run { importResultMessage = error.localizedDescription }
            }
        }
    }
}

// MARK: - Settings Item Models

struct SettingsItem {
    let icon: String
    let iconColor: Color
    let title: String
    var isDestructive: Bool = false
    let action: () -> Void
}

// 🔧 FIX: Lazy navigation destinations — views created only when navigated to.
// Previously used AnyView which eagerly instantiated all 10 destination views
// during SettingsView body evaluation, blocking iPhone rendering.
enum SettingsDestination: Hashable {
    case privacy, security, calls, steganography, notifications, appearance
    case bots, webSessions, help, about, profileLinks
    case meshtastic
    /// Guideline 5.1.1(v): deletion has to be reachable from inside the app.
    case deleteAccount
    /// The screen that gets a freshly flashed node actually transmitting.
    case loraNode
    /// __DEVSYNC_DIRECT_2026_09_23__ Linked devices (direct own-device sync).
    case linkedDevices
}

struct SettingsNavItem {
    let icon: String
    let iconColor: Color
    let title: String
    let destination: SettingsDestination
}

/// __ANATOLE_1311_IOS__ (N3, N13) Leave Settings from ANY depth in one tap.
///
/// Settings is a full-screen cover over the tab bar, so Anatole's "quitter les
/// réglages en appuyant directement sur Discussions" cannot be literal on iOS:
/// the Messages tab is not on screen. Until now only the root page had a close
/// button, so leaving from Account or Security meant backing out page by page
/// first. Every pushed page now carries the same ⓧ, which dismisses the whole
/// cover and lands on whichever tab was underneath (Messages included).
struct SettingsCloseButton: ViewModifier {
    let close: () -> Void

    func body(content: Content) -> some View {
        content.toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: close) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .foregroundColor(.secondary)
                }
                .accessibilityLabel("common.close".localized)
                .accessibilityIdentifier("settings_close_any_page")
            }
        }
    }
}

// MARK: - Settings Categories  __SETTINGS_CATEGORIES_2026_10_26__

/// A page that groups several of the old cards. Only the groups that were more
/// than one card are here — a category holding a single row would be a tap that
/// buys nothing.
enum SettingsCategory: Hashable {
    /// Identity, profile links, account deletion.
    case account
    /// Long-range radio, OSHI Web / linked devices, bots.
    case network
    /// Export, import, clearing, and where locations open.
    case data
    /// Help, about, rate.
    case about

    var title: String {
        switch self {
        case .account: return "settings.account".localized
        case .network: return "settings.network".localized
        case .data: return "settings.data_management".localized
        case .about: return "settings.about".localized
        }
    }
}

/// One hub row. `target` is two kinds on purpose: a category pushes a page
/// built here, a destination pushes a screen that already existed.
struct SettingsHubItem {
    let icon: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let target: SettingsHubTarget
}

/// Also the Settings navigation route (`Hashable`): one type for every page,
/// so the open pages are a plain array shared by the narrow and wide layouts.
enum SettingsHubTarget: Hashable {
    case category(SettingsCategory)
    case destination(SettingsDestination)
}

// MARK: - Stat Item View

struct StatItem: View {
    let icon: String
    let value: String
    let label: String
    let color: Color

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.scaled(18))
                .foregroundColor(color)

            Text(value)
                .font(.scaled(20, weight: .bold, design: .rounded))
                .foregroundColor(.primary)

            Text(label)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(value) \(label)")
    }
}

// MARK: - Notification Settings View

struct NotificationSettingsView: View {
    @AppStorage("notificationsEnabled") private var notificationsEnabled = true
    @AppStorage("notificationSound") private var notificationSound = true
    @AppStorage("notificationPreview") private var notificationPreview = true
    @AppStorage("notificationVibrate") private var notificationVibrate = true
    @AppStorage("inAppSounds") private var inAppSounds = true

    var body: some View {
        List {
            Section {
                Toggle(isOn: $notificationsEnabled) {
                    Label("settings.notifications.enable".localized, systemImage: "bell.fill")
                }
                .tint(settingsBrandColor)
            } footer: {
                Text("settings.notifications.enable.footer".localized)
            }

            if notificationsEnabled {
                Section {
                    Toggle(isOn: $notificationSound) {
                        Label("settings.notifications.sound".localized, systemImage: "speaker.wave.2.fill")
                    }
                    .tint(settingsBrandColor)

                    Toggle(isOn: $notificationVibrate) {
                        Label("settings.notifications.vibrate".localized, systemImage: "iphone.radiowaves.left.and.right")
                    }
                    .tint(settingsBrandColor)

                    Toggle(isOn: $notificationPreview) {
                        Label("settings.notifications.preview".localized, systemImage: "eye.fill")
                    }
                    .tint(settingsBrandColor)
                    // __NOTIFICATION_NAMES_HIDDEN_2026_11_20__ the extension reads the App Group.
                    .onChange(of: notificationPreview) { _, _ in MessageBannerPolicy.mirrorShowsNames() }
                } header: {
                    Text("settings.notifications.section.alerts".localized)
                }

                Section {
                    Toggle(isOn: $inAppSounds) {
                        Label("settings.notifications.inAppSounds".localized, systemImage: "speaker.fill")
                    }
                    .tint(settingsBrandColor)
                } header: {
                    Text("settings.notifications.section.inApp".localized)
                }
            }
        }
        .navigationTitle("settings.notifications.title".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Appearance Settings View

struct AppearanceSettingsView: View {
    @AppStorage("appTheme") private var appTheme = "system"
    @AppStorage(OLEDBlack.storageKey) private var oledBlack = OLEDBlack.defaultEnabled
    @AppStorage(GlassEffect.storageKey) private var glassEffect = GlassEffect.defaultEnabled
    @AppStorage(GlassEffect.oledStorageKey) private var glassEffectOLED = GlassEffect.oledDefaultEnabled
    /// __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes ».
    @AppStorage(MergedChatsSetting.storageKey) private var mergedChats = false
    @AppStorage("chatBubbleStyle") private var chatBubbleStyle = "modern"
    @AppStorage("fontSize") private var fontSize = 1.0
    @Environment(\.colorScheme) var deviceColorScheme
    @ObservedObject private var wallpaperManager = ChatWallpaperManager.shared
    @AppStorage(MascotReactionPreference.key) private var animatedMascots = MascotReactionPreference.defaultEnabled

    let themes = ["system", "light", "dark"]
    let bubbleStyles = ["modern", "classic", "minimal"]

    private var effectiveTheme: ColorScheme {
        switch appTheme {
        case "light": return .light
        case "dark": return .dark
        default: return deviceColorScheme
        }
    }

    private var themeDescription: String {
        switch appTheme {
        case "system":
            let currentMode = deviceColorScheme == .dark ? "settings.appearance.theme.dark".localized : "settings.appearance.theme.light".localized
            return String(format: "settings.appearance.theme.system.description".localized, currentMode)
        case "light":
            return "settings.appearance.theme.light.description".localized
        case "dark":
            return "settings.appearance.theme.dark.description".localized
        default:
            return ""
        }
    }

    /// Says when the option will actually show, so turning it on under the
    /// Light theme does not look broken.
    private var oledBlackFootnote: String {
        if appTheme == "light" { return "settings.appearance.oledBlack.lightNote".localized }
        return "settings.appearance.oledBlack.description".localized
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    ThemePreviewCard(theme: "system", isSelected: appTheme == "system", icon: "iphone", previewLight: true, previewDark: true, deviceColorScheme: deviceColorScheme) { appTheme = "system" }
                    ThemePreviewCard(theme: "light", isSelected: appTheme == "light", icon: "sun.max.fill", previewLight: true, previewDark: false, deviceColorScheme: deviceColorScheme) { appTheme = "light" }
                    ThemePreviewCard(theme: "dark", isSelected: appTheme == "dark", icon: "moon.fill", previewLight: false, previewDark: true, deviceColorScheme: deviceColorScheme) { appTheme = "dark" }
                }
                .padding(.vertical, 8)

                HStack(spacing: 8) {
                    Image(systemName: appTheme == "system" ? "info.circle" : "checkmark.circle.fill")
                        .foregroundColor(appTheme == "system" ? .secondary : settingsBrandColor)
                    Text(themeDescription)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .padding(.top, 4)

                // __OLED_BLACK_2026_11_16__ « Noir OLED »: an option OF the dark
                // look, not a fourth theme — it applies to "Dark" and to
                // "System" while the phone is dark, and never to a light screen.
                Toggle(isOn: $oledBlack) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("settings.appearance.oledBlack".localized)
                        Text(oledBlackFootnote)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
                .tint(settingsBrandColor)
                .accessibilityIdentifier("settings.appearance.oledBlack")

                // __GLASS_ALL_THEMES_2026_11_21__ « Effet verre »: the frosted,
                // translucent surfaces OLED black introduced, in every theme.
                // Stored per look (Light/Dark off by default, OLED black on):
                // the switch edits the look on screen right now.
                Toggle(isOn: OLEDBlack.isActive(enabled: oledBlack, appTheme: appTheme,
                                                systemScheme: deviceColorScheme)
                       ? $glassEffectOLED : $glassEffect) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("appearance_glass_effect".localized)
                        Text("appearance_glass_effect_footer".localized)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
                .tint(settingsBrandColor)
                .accessibilityIdentifier("settings.appearance.glassEffect")
            } header: {
                Text("settings.appearance.section.theme".localized)
            }

            // __MERGED_CHATS_2026_11_18__ One chat list (All · Unread · Groups ·
            // People) instead of the Messages and Groups segments. Per device.
            Section {
                Toggle(isOn: $mergedChats.animation()) {
                    HStack(spacing: 12) {
                        Image(systemName: "bubble.left.and.text.bubble.right.fill")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(width: 30, height: 30)
                            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(settingsBrandColor))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("chats.merged.setting_title".localized)
                            Text("chats.merged.setting_desc".localized)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .tint(settingsBrandColor)
                .accessibilityIdentifier("settings.appearance.mergedChats")
            }

            Section {
                VStack(alignment: .leading, spacing: 12) {
                    Text("settings.appearance.fontSize".localized)
                    HStack {
                        Text("A").font(.caption).accessibilityHidden(true)
                        Slider(value: $fontSize, in: 0.8...1.4, step: 0.1).tint(settingsBrandColor)
                            .accessibilityLabel("settings.appearance.fontSize".localized)
                            .accessibilityValue("\(Int(fontSize * 100))%")
                        Text("A").font(.title2).accessibilityHidden(true)
                    }
                    Text("settings.appearance.fontSize.preview".localized)
                        .font(.system(size: 16 * fontSize))
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(settingsBrandColor.opacity(0.1))
                        .cornerRadius(12)
                }
            } header: {
                Text("settings.appearance.section.text".localized)
            }

            // __WALLPAPER_TONE_2026_09_24__ Chat wallpaper: tone (light / dark /
            // automatic, independent of the theme above) + default backdrop.
            Section {
                NavigationLink {
                    ChatWallpaperSettingsView()
                } label: {
                    HStack(spacing: 12) {
                        WallpaperMiniChat(wallpaper: wallpaperManager.defaultWallpaper,
                                          scheme: ChatWallpaperAppearance.resolve(
                                            wallpaper: wallpaperManager.defaultWallpaper,
                                            tone: wallpaperManager.defaultTone,
                                            system: deviceColorScheme).contentScheme,
                                          compact: true)
                            .frame(width: 44, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings.appearance.wallpaper".localized)
                            Text(wallpaperManager.defaultTone.titleKey.localized)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 2)
                }
                // __O_BUBBLE_MASCOT_2026_11_16__ Draw reactions as the O bubble
                // mascot. Display only: what is sent is the same emoji either way.
                Toggle(isOn: $animatedMascots) {
                    HStack(spacing: 12) {
                        if let love = OSHIMascotCatalog.named("love"), animatedMascots {
                            MascotGlyph(mascot: love, side: 32, motion: .loop)
                                .accessibilityHidden(true)
                        } else {
                            Text("❤️").font(.system(size: 24)).frame(width: 32, height: 32)
                                .accessibilityHidden(true)
                        }
                        Text("settings.appearance.animatedReactions".localized)
                    }
                }
                .tint(settingsBrandColor)
                .accessibilityIdentifier("settings.animatedReactions")
            } header: {
                Text("settings.appearance.section.chat".localized)
            } footer: {
                Text("settings.appearance.animatedReactions.footer".localized)
            }
        }
        .glassAwareListGround()  // __GLASS_ALL_THEMES_2026_11_21__
        .navigationTitle("settings.appearance.title".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Theme Preview Card

struct ThemePreviewCard: View {
    let theme: String
    let isSelected: Bool
    let icon: String
    let previewLight: Bool
    let previewDark: Bool
    let deviceColorScheme: ColorScheme
    let onTap: () -> Void
    /// __OLED_GLASS_2026_11_16__ The OLED choice (read OUTSIDE the forced
    /// scheme below): a dark preview then shows what the app will actually
    /// look like — a black ground, a glass bubble, a crystal rim.
    @Environment(\.oledBlack) private var oledChoice

    private var previewIsOLED: Bool {
        guard oledChoice else { return false }
        switch theme {
        case "dark": return true
        case "light": return false
        default: return deviceColorScheme == .dark
        }
    }

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(previewBackgroundColor)
                    .frame(width: 60, height: 80)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .strokeBorder(isSelected ? AnyShapeStyle(settingsBrandColor)
                                          : previewIsOLED ? AnyShapeStyle(OLEDGlass.hairline)
                                          : AnyShapeStyle(Color.gray.opacity(0.3)),
                                          lineWidth: isSelected ? 2 : 1)
                    )
                    .shadow(color: previewIsOLED && isSelected ? settingsBrandColor.opacity(0.45) : .clear, radius: 8)

                VStack(spacing: 4) {
                    HStack {
                        RoundedRectangle(cornerRadius: 4).fill(previewBubbleColor).frame(width: 30, height: 10)
                        Spacer()
                    }.padding(.horizontal, 6)
                    HStack {
                        Spacer()
                        RoundedRectangle(cornerRadius: 4).fill(settingsBrandColor).frame(width: 25, height: 10)
                    }.padding(.horizontal, 6)
                    RoundedRectangle(cornerRadius: 3).fill(previewBubbleColor.opacity(0.5)).frame(width: 48, height: 8).padding(.top, 4)
                }

                if theme == "system" {
                    HStack(spacing: 0) {
                        Rectangle().fill(Color.white.opacity(0.3)).frame(width: 30, height: 80)
                        Rectangle().fill(Color.black.opacity(0.3)).frame(width: 30, height: 80)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .opacity(0.4)
                }
            }

            Image(systemName: icon).font(.scaled(14)).foregroundColor(isSelected ? settingsBrandColor : .secondary)
            Text(themeLabel).font(.caption).fontWeight(isSelected ? .semibold : .regular).foregroundColor(isSelected ? settingsBrandColor : .primary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12).fill(isSelected ? settingsBrandColor.opacity(0.1) : Color.clear))
        // 🔧 FIX R19: Force correct color scheme on preview cards so they don't invert in dark mode
        .environment(\.colorScheme, theme == "light" ? .light : theme == "dark" ? .dark : deviceColorScheme)
        .onTapGesture { onTap() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(themeLabel)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    private var themeLabel: String {
        switch theme {
        case "system": return "settings.appearance.theme.system".localized
        case "light": return "settings.appearance.theme.light".localized
        case "dark": return "settings.appearance.theme.dark".localized
        default: return theme.capitalized
        }
    }

    private var previewBackgroundColor: Color {
        if previewIsOLED { return .black }
        switch theme {
        case "light": return Color(.systemGray6)
        case "dark": return Color(.systemGray)
        default: return deviceColorScheme == .dark ? Color(.systemGray) : Color(.systemGray6)
        }
    }

    private var previewBubbleColor: Color {
        if previewIsOLED { return OLEDGlass.solid }
        switch theme {
        case "light": return Color(.systemGray5)
        case "dark": return Color(.systemGray4)
        default: return deviceColorScheme == .dark ? Color(.systemGray4) : Color(.systemGray5)
        }
    }
}

// MARK: - Help View

struct HelpView: View {
    var body: some View {
        List {
            Section {
                // __IN_APP_GUIDES_2026_11_02__ The two questions people actually
                // ask, answered in the app rather than on the website — which is
                // no use to someone stuck on a screen, and no use at all offline,
                // in an app whose whole argument is working without a network.
                NavigationLink(destination: GuideView(guide: .addContact)) {
                    Label("guide.add_contact.title".localized, systemImage: "person.badge.plus")
                }
                NavigationLink(destination: GuideView(guide: .cashu)) {
                    Label("guide.cashu.title".localized, systemImage: "bolt.fill")
                }
                NavigationLink(destination: FAQView()) {
                    Label("settings.help.faq".localized, systemImage: "questionmark.circle")
                }
                Link(destination: URL(string: "https://oshi-messenger.com/support")!) {
                    HStack {
                        Label("settings.help.contact".localized, systemImage: "envelope")
                        Spacer()
                        Image(systemName: "arrow.up.right").font(.caption).foregroundColor(.secondary)
                    }
                }
                Link(destination: URL(string: "https://oshi-messenger.com/privacy")!) {
                    HStack {
                        Label("settings.help.privacy".localized, systemImage: "hand.raised")
                        Spacer()
                        Image(systemName: "arrow.up.right").font(.caption).foregroundColor(.secondary)
                    }
                }
                Link(destination: URL(string: "https://oshi-messenger.com/terms")!) {
                    HStack {
                        Label("settings.help.terms".localized, systemImage: "doc.text")
                        Spacer()
                        Image(systemName: "arrow.up.right").font(.caption).foregroundColor(.secondary)
                    }
                }
            }
        }
        .navigationTitle("settings.help.title".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct FAQView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                FAQItem(question: "faq.q1.question".localized, answer: "faq.q1.answer".localized)
                FAQItem(question: "faq.q2.question".localized, answer: "faq.q2.answer".localized)
                FAQItem(question: "faq.q3.question".localized, answer: "faq.q3.answer".localized)
                FAQItem(question: "faq.q4.question".localized, answer: "faq.q4.answer".localized)
                FAQItem(question: "faq.q5.question".localized, answer: "faq.q5.answer".localized)
                FAQItem(question: "faq.q6.question".localized, answer: "faq.q6.answer".localized)
            }
            .padding()
        }
        .navigationTitle("settings.help.faq".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct FAQItem: View {
    let question: String
    let answer: String
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { isExpanded.toggle() }
            } label: {
                HStack {
                    Text(question).font(.headline).foregroundColor(.primary).multilineTextAlignment(.leading)
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down").font(.caption).foregroundColor(.secondary)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityHint(isExpanded ? "common.collapse".localized : "common.expand".localized)
            if isExpanded {
                Text(answer).font(.body).foregroundColor(.secondary).transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(12)
    }
}

// MARK: - Privacy Relay (Oblivious HTTP)

/// __OHTTP_PRIVACY_RELAY_2026_11_09__ "Privacy relay (beta)": routes `/v2/*`
/// control-plane requests through an independent Oblivious HTTP relay
/// (`ObliviousHTTP.swift`). OFF by default; disabled while the build carries no
/// relay URL / pinned gateway key. With it on, a relay failure makes the
/// request fail and retry — it never silently goes direct.
struct PrivacyRelaySection: View {
    @AppStorage(OHTTPSettings.enabledKey) private var enabled = false
    // __OHTTP_RELAY_ADDRESS_2026_11_10__
    @State private var draft = OHTTPSettings.userRelayURLString
    @State private var invalid = false
    @State private var revision = 0

    var body: some View {
        let _ = revision
        let configured = OHTTPSettings.isConfigured
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text("settings.privacy.ohttp.address".localized)
                Text("settings.privacy.ohttp.address.hint".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                HStack {
                    TextField("https://", text: $draft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .onChange(of: draft) { _, _ in invalid = false }
                        .accessibilityIdentifier("privacy.ohttpRelayAddress")
                    Button("save".localized) {
                        if OHTTPSettings.setUserRelayURL(draft) {
                            enabled = UserDefaults.standard.bool(forKey: OHTTPSettings.enabledKey)
                            revision += 1
                        } else {
                            invalid = true
                        }
                    }
                    .accessibilityIdentifier("privacy.ohttpRelayAddressSave")
                }
                if invalid {
                    Text("settings.privacy.ohttp.address.invalid".localized)
                        .font(.caption)
                        .foregroundColor(.red)
                }
            }
            Toggle(isOn: $enabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("settings.privacy.ohttp.title".localized)
                    Text("settings.privacy.ohttp.subtitle".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .tint(settingsBrandColor)
            .disabled(!configured)
            .accessibilityIdentifier("privacy.ohttpRelay")
        } header: {
            Text("settings.privacy.section.network".localized)
        } footer: {
            Text(!configured ? (OHTTPSettings.isSupportedOS ? "settings.privacy.ohttp.address.not_set".localized
                                                            : "settings.privacy.ohttp.unavailable".localized)
                 : (enabled ? "settings.privacy.ohttp.footer.on".localized
                            : "settings.privacy.ohttp.footer.off".localized))
        }
        .onChange(of: enabled) { _, _ in _ = OHTTPSettings.userEnabled }   // mirror to the app group
    }
}

// MARK: - Privacy Settings View

struct PrivacySettingsView: View {
    @AppStorage("readReceiptsEnabled") private var readReceipts = true
    @AppStorage("lastSeenEnabled") private var lastSeen = true
    @AppStorage("profilePhotoVisible") private var profilePhoto = true
    @AppStorage("typingIndicatorsEnabled") private var typingIndicators = true
    /// __SUGGESTIONS_ARE_A_CHOICE_2026_10_27__ Same key ChatView gates on.
    @AppStorage(SmartReplyPreference.enabledKey) private var smartRepliesEnabled = true
    // Off by default: it removes Forward and Copy, actions people expect to be
    // there. Opt-in, never a surprise. Key shared with MessagePrivacy so the
    // enforcement sites and this toggle cannot drift apart.
    @AppStorage(MessagePrivacy.blockForwardAndCopyKey) private var blockForwardAndCopy = false
    // __KEYBOARD_PRIVACY_2026_10_01__ keys shared with KeyboardPrivacy (app delegate +
    // composers) so the toggles and the enforcement cannot drift. Block = default ON
    // (costs nothing for most people); Private typing = default OFF (costs autocorrect).
    @AppStorage(KeyboardPrivacy.blockThirdPartyKeyboardsKey) private var blockThirdPartyKeyboards = true
    @AppStorage(KeyboardPrivacy.privateKeyboardKey) private var privateKeyboard = false
    @State private var showingKeyboardPrivacyInfo = false

    var body: some View {
        List {
            Section {
                Toggle("settings.privacy.readReceipts".localized, isOn: $readReceipts).tint(settingsBrandColor)
                Toggle("settings.privacy.lastSeen".localized, isOn: $lastSeen).tint(settingsBrandColor)
                Toggle("settings.privacy.profilePhoto".localized, isOn: $profilePhoto).tint(settingsBrandColor)
                Toggle("settings.privacy.typing".localized, isOn: $typingIndicators).tint(settingsBrandColor)
            } header: {
                Text("settings.privacy.section.visibility".localized)
            } footer: {
                Text("settings.privacy.visibility.footer".localized)
            }

            Section {
                Toggle("settings.privacy.blockForwardCopy".localized, isOn: $blockForwardAndCopy)
                    .tint(settingsBrandColor)
                // __SUGGESTIONS_ARE_A_CHOICE_2026_10_27__ Reporter's N1. The key
                // comes from SmartReplyPreference rather than a literal, so this
                // switch and the one ChatView reads cannot drift apart.
                Toggle("settings.messages.smartReplies".localized, isOn: $smartRepliesEnabled)
                    .tint(settingsBrandColor)
            } header: {
                Text("settings.privacy.section.messages".localized)
            } footer: {
                Text("settings.privacy.blockForwardCopy.footer".localized
                     + "\n\n" + "settings.messages.smartReplies.footer".localized)
            }

            // __KEYBOARD_PRIVACY_2026_10_01__ protect what is typed BEFORE it is encrypted
            Section {
                // __DEVICE_INTEGRITY_2026_10_01__ WHY: keyboard protections mean
                // nothing on a jailbroken phone — say so where they are configured.
                // Heuristic: shown only when signs were found, never a "safe" badge.
                if DeviceIntegrity.detectedSigns {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "exclamationmark.shield.fill")
                            .foregroundColor(.red)
                            .accessibilityHidden(true)
                        Text("deviceIntegrity.settings.warning".localized)
                            .font(.subheadline)
                            .foregroundColor(.red)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 2)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("kbd.integrityWarning")
                }
                Toggle("settings.privacy.blockThirdPartyKeyboards".localized, isOn: $blockThirdPartyKeyboards)
                    .tint(settingsBrandColor)
                    .accessibilityIdentifier("kbd.blockThirdParty")
                Toggle(isOn: $privateKeyboard) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("settings.privacy.privateKeyboard".localized)
                        Text("settings.privacy.privateKeyboard.description".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .tint(settingsBrandColor)
                .accessibilityIdentifier("kbd.privateTyping")
                Button {
                    showingKeyboardPrivacyInfo = true
                } label: {
                    Label("settings.privacy.keyboard.learnMore".localized, systemImage: "info.circle")
                }
                .accessibilityIdentifier("kbd.learnMore")
            } header: {
                Text("settings.privacy.section.keyboard".localized)
            } footer: {
                Text("settings.privacy.keyboard.footer".localized)
            }

            // __OHTTP_PRIVACY_RELAY_2026_11_09__ hide the IP from OSHI's server.
            PrivacyRelaySection()

            Section {
                NavigationLink(destination: BlockedContactsView()) {
                    HStack {
                        Image(systemName: "hand.raised.fill").foregroundColor(.red)
                        Text("settings.privacy.blockedContacts".localized)
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }

                // 🚩 Reports the user has filed (App Store guideline 1.2).
                NavigationLink(destination: ReportedItemsView()) {
                    HStack {
                        Image(systemName: "flag.fill").foregroundColor(.orange)
                        Text("settings.privacy.reports".localized)
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }

                Toggle(isOn: Binding(
                    get: { UserDefaults.standard.bool(forKey: "callsFromContactsOnly") },
                    set: { UserDefaults.standard.set($0, forKey: "callsFromContactsOnly") }
                )) {
                    HStack {
                        Image(systemName: "person.crop.circle.badge.checkmark").foregroundColor(.blue)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings.privacy.callsFromContactsOnly".localized)
                            Text("settings.privacy.callsFromContactsOnly.description".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            } header: {
                Text("settings.privacy.section.contacts".localized)
            }
        }
        .navigationTitle("settings.privacy.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingKeyboardPrivacyInfo) {
            KeyboardPrivacyExplainerView()
        }
        // __KEYBOARD_PRIVACY_2026_10_01__ the notification "Reply" action depends on
        // these toggles (inline text field only when both are off) — re-register.
        .onChange(of: blockThirdPartyKeyboards) { _, _ in
            NotificationManager.shared.setupNotificationCategories()
        }
        .onChange(of: privateKeyboard) { _, _ in
            NotificationManager.shared.setupNotificationCategories()
        }
    }
}

// MARK: - Keyboard privacy explainer (__KEYBOARD_PRIVACY_2026_10_01__)

/// WHY: an honest account of what OSHI can do about the keyboard and what no app
/// can. A compromised phone (spyware) sees keystrokes before any app encrypts
/// them — the explainer says so rather than over-claiming.
struct KeyboardPrivacyExplainerView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("keyboardPrivacy.intro".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Section {
                    row("keyboard.badge.ellipsis", .green, "keyboardPrivacy.protected.thirdParty")
                    row("text.badge.xmark", .green, "keyboardPrivacy.protected.learning")
                    row("lock.fill", .green, "keyboardPrivacy.protected.encryption")
                    row("eye.slash.fill", .green, "keyboardPrivacy.protected.screen")
                    // __CLIPBOARD_HYGIENE_2026_10_01__
                    row("doc.on.clipboard", .green, "keyboardPrivacy.protected.clipboard")
                } header: {
                    Text("keyboardPrivacy.protected.header".localized)
                }
                Section {
                    row("exclamationmark.triangle.fill", .orange, "keyboardPrivacy.limits.spyware")
                    // __DEVICE_INTEGRITY_2026_10_01__
                    row("exclamationmark.shield.fill", .orange, "keyboardPrivacy.limits.jailbreak")
                    row("keyboard", .orange, "keyboardPrivacy.limits.systemKeyboard")
                    row("mic.fill", .orange, "keyboardPrivacy.limits.dictation")
                } header: {
                    Text("keyboardPrivacy.limits.header".localized)
                }
            }
            .navigationTitle("keyboardPrivacy.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done".localized) { dismiss() }
                }
            }
        }
    }

    private func row(_ icon: String, _ color: Color, _ key: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(color)
                .frame(width: 24)
                .accessibilityHidden(true)
            Text(key.localized)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - Security Settings View

struct SecuritySettingsView: View {
    @AppStorage("biometricEnabled") private var biometricEnabled = false
    @AppStorage("autoLockEnabled") private var autoLock = false
    // __LEAK_FIX_2026_09_25__ app-switcher cover, default ON (PrivacyCoverController)
    @AppStorage(PrivacyCoverController.defaultsKey) private var privacyCover = true
    /// __ANATOLE_1311_IOS__ (N6) Triple-tap wipe on the OSHI title, opt-in.
    @AppStorage(PanicGesture.enabledDefaultsKey) private var panicWipeArmed = false

    var body: some View {
        List {
            Section {
                Toggle("settings.security.panicWipe".localized, isOn: $panicWipeArmed)
                    .tint(settingsBrandColor)
                    .accessibilityIdentifier("settings.security.panicWipe")
            } footer: {
                Text("settings.security.panicWipe.footer".localized)
            }

            #if !targetEnvironment(macCatalyst)
            Section {
                Toggle("settings.security.privacyCover".localized, isOn: $privacyCover)
                    .tint(settingsBrandColor)
            } footer: {
                Text("settings.security.privacyCover.footer".localized)
            }
            #endif

            Section {
                Toggle("settings.security.biometric".localized, isOn: $biometricEnabled)
                if biometricEnabled {
                    Toggle("settings.security.autoLock".localized, isOn: $autoLock)
                }
            } header: {
                Text("settings.security.section.authentication".localized)
            } footer: {
                Text(biometricEnabled ?
                     (autoLock ? "settings.security.autoLock.enabled".localized : "settings.security.autoLock.disabled".localized) :
                     "settings.security.authentication.footer".localized)
            }

            Section {
                HStack {
                    Image(systemName: "lock.shield.fill").foregroundColor(.green)
                    VStack(alignment: .leading) {
                        Text("settings.security.e2e.title".localized).font(.headline)
                        Text("settings.security.e2e.description".localized).font(.caption).foregroundColor(.secondary)
                    }
                }
            } header: {
                Text("settings.security.section.encryption".localized)
            }
        }
        .navigationTitle("settings.security.title".localized)
    }
}

// MARK: - About View

struct AboutView: View {
    private let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.2"
    private let buildNumber = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"

    var body: some View {
        List {
            Section {
                HStack {
                    Text("settings.about.version".localized)
                    Spacer()
                    Text("\(appVersion) (\(buildNumber))").foregroundColor(.secondary)
                }
            }

            Section {
                Link(destination: URL(string: "https://oshi-messenger.com/privacy")!) {
                    HStack {
                        Text("settings.help.privacy".localized)
                        Spacer()
                        Image(systemName: "arrow.up.right").font(.caption).foregroundColor(.secondary)
                    }
                }
                Link(destination: URL(string: "https://oshi-messenger.com/terms")!) {
                    HStack {
                        Text("settings.help.terms".localized)
                        Spacer()
                        Image(systemName: "arrow.up.right").font(.caption).foregroundColor(.secondary)
                    }
                }
            } header: {
                Text("settings.about.section.legal".localized)
            }

            Section {
                VStack(alignment: .center, spacing: 8) {
                    Image(systemName: "lock.shield.fill").font(.scaled(40)).foregroundColor(Theme.brand)
                    Text("OSHI").font(.title2).fontWeight(.bold)
                    Text("settings.about.tagline".localized).font(.caption).foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding()
            }
        }
        .navigationTitle("settings.about.title".localized)
    }
}

// MARK: - Wallet Info View

struct WalletInfoView: View {
    @EnvironmentObject var identityManager: IdentityManager
    @Environment(\.dismiss) var dismiss
    @State private var copied = false

    var body: some View {
        NavigationView {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("settings.wallet.address".localized).font(.caption).foregroundColor(.secondary)
                        Text(identityManager.identityAddress).font(.system(.body, design: .monospaced)).lineLimit(2)
                    }
                    Button {
                        UIPasteboard.general.string = identityManager.identityAddress
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                    } label: {
                        HStack {
                            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                            Text(copied ? "common.copied".localized : "common.copy".localized)
                        }
                    }
                } header: {
                    Text("settings.wallet.section.address".localized)
                }

                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("settings.wallet.publicKey".localized).font(.caption).foregroundColor(.secondary)
                        Text(identityManager.publicKey).font(.system(.caption, design: .monospaced)).lineLimit(3)
                    }
                } header: {
                    Text("settings.wallet.section.keys".localized)
                }
            }
            .navigationTitle("settings.wallet.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) { dismiss() }
                }
            }
        }
    }
}

// MARK: - Backup Seed View

struct BackupSeedView: View {
    @EnvironmentObject var identityManager: IdentityManager
    @Environment(\.dismiss) var dismiss
    @State private var showSeed = false
    @State private var copied = false

    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Image(systemName: "key.fill").font(.scaled(60)).foregroundColor(Theme.brand)
                Text("settings.backup.warning.title".localized).font(.headline).multilineTextAlignment(.center)
                Text("settings.backup.warning.description".localized).font(.body).foregroundColor(.secondary).multilineTextAlignment(.center).padding(.horizontal)

                if showSeed {
                    VStack(spacing: 12) {
                        // 🔧 FIX: Show the actual private recovery key (not public key)
                        // This is base64 of 64 bytes (encryption + signing private keys)
                        // that can be imported to restore the exact same identity
                        if let recoveryKey = identityManager.exportRecoveryKey() {
                            ScrollView { Text(recoveryKey).font(.system(.caption, design: .monospaced)).padding().frame(maxWidth: .infinity).background(Color(.secondarySystemBackground)).cornerRadius(12) }
                                .frame(maxHeight: 110)
                            // 🆕 QR code of the recovery key — scan from the new device's
                            // Settings → Sauvegardes → "Importer" pour restaurer l'identité.
                            if let qr = Self.generateRecoveryKeyQR(from: recoveryKey) {
                                Image(uiImage: qr)
                                    .interpolation(.none)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 220, height: 220)
                                    .background(Color.white)
                                    .cornerRadius(12)
                                    .padding(.vertical, 4)
                                Text("settings.backup.scanToRestore".localized)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Button {
                                // Security (H-WEB-1): the recovery key is raw private-key material.
                                // Copy it device-only (no Universal Clipboard sync to other devices)
                                // and auto-expire it from the pasteboard after 60s so it does not
                                // linger for other apps to read.
                                // __CLIPBOARD_HYGIENE_2026_10_01__ now the shared helper (same policy).
                                SecureClipboard.copy(recoveryKey)
                                copied = true
                                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                            } label: {
                                HStack {
                                    Image(systemName: copied ? "checkmark.circle.fill" : "doc.on.doc")
                                    Text(copied ? "common.copied".localized : "settings.backup.copyKey".localized)
                                }
                                .frame(maxWidth: .infinity).padding()
                                .background(Theme.brand).foregroundColor(.white).cornerRadius(12)
                            }
                        } else {
                            Text("settings.backup.noRecoveryKey".localized).font(.caption).foregroundColor(.secondary)
                        }
                    }.padding()
                } else {
                    Button {
                        Self.authenticateOwner(reason: "settings.backup.revealKey".localized) { ok in
                            if ok { showSeed = true }
                        }
                    } label: {
                        Text("settings.backup.revealKey".localized)
                            .frame(maxWidth: .infinity).padding()
                            .background(Theme.brand).foregroundColor(.white).cornerRadius(12)
                    }.padding(.horizontal)
                }

                Spacer()
            }
            .padding()
            .navigationTitle("settings.backup.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) { dismiss() }
                }
            }
        }
        // Security (H-WEB-1): the recovery key + its QR are raw private-key material. Show the
        // launch screen instead of the key in screenshots / screen recordings (the helper already
        // existed in ScreenshotBlocker.swift but was never applied anywhere).
        .protectWithLaunchScreen()
    }

    /// __RECOVERY_KEY_NEEDS_OWNER_2026_11_20__ The recovery key IS the identity's
    /// private key. Anyone holding an unlocked phone for a few seconds (a seized
    /// phone at a checkpoint, a phone snatched mid-use) could tap "Reveal" and
    /// photograph the QR, and from then on read and send as this person from
    /// any device. Ask for Face ID / Touch ID / the passcode first.
    ///
    /// A device with NO passcode has no owner check to give; refusing there would
    /// lock the user out of their own backup while protecting nothing, so it
    /// proceeds (the policy evaluation reports `passcodeNotSet`).
    static func authenticateOwner(reason: String, completion: @escaping (Bool) -> Void) {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            let noPasscode = (error as? LAError)?.code == .passcodeNotSet
            DispatchQueue.main.async { completion(noPasscode) }
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, _ in
            DispatchQueue.main.async { completion(success) }
        }
    }

    /// Build a QR code bitmap from the recovery key string. Uses CIQRCodeGenerator (no
    /// extra dep). The receiver scans this QR from another OSHI device's "Importer"
    /// flow to restore the exact same identity. Format matches the Android scheme
    /// `oshi-recovery:<recoveryKeyBase64>` so iOS↔Android cross-device works.
    static func generateRecoveryKeyQR(from recoveryKey: String) -> UIImage? {
        let payload = "oshi-recovery:\(recoveryKey)"
        guard let data = payload.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let ci = filter.outputImage else { return nil }
        let scale: CGFloat = 8
        let scaled = ci.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// MARK: - QR Code View

struct QRCodeView: View {
    let address: String
    let publicKey: String
    @Environment(\.dismiss) var dismiss
    @State private var qrImage: UIImage?
    @State private var copied = false
    /// __FIRST_CONTACT_REMOTE_2026_10_10__ Share my invite link.
    @State private var showInviteShare = false

    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                if let qrImage = qrImage {
                    Image(uiImage: qrImage).interpolation(.none).resizable().scaledToFit()
                        .frame(width: 250, height: 250).padding()
                        .background(Color.white).cornerRadius(16)
                        .shadow(color: .black.opacity(0.1), radius: 10, x: 0, y: 5)
                        .accessibilityLabel("settings.qr.image.label".localized)
                } else {
                    ProgressView().frame(width: 250, height: 250)
                }

                Text("settings.qr.scanHint".localized).font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center).padding(.horizontal)

                VStack(spacing: 8) {
                    Text("settings.qr.yourAddress".localized).font(.caption).foregroundColor(.secondary)
                    Text(formatAddress(publicKey)).font(.system(.body, design: .monospaced))
                }

                // __FIRST_CONTACT_REMOTE_2026_10_10__ Sending a link is the
                // primary action here now, and copying the key is secondary.
                //
                // This screen's only remote-capable action used to be "Copy
                // address", which puts a ~44-character base64 public key on the
                // clipboard. That is a fine thing to offer an expert and a dead
                // end for everyone else: the person then has to paste a blob
                // into some other app and the recipient has to know where to
                // paste it back. A link does the same job in one tap and
                // explains itself on arrival.
                Button {
                    showInviteShare = true
                } label: {
                    HStack {
                        Image(systemName: "paperplane.fill")
                        Text("invite.share_my_link".localized)
                    }
                    .frame(maxWidth: .infinity).padding()
                    .background(Theme.brand).foregroundColor(.white).cornerRadius(12)
                }.padding(.horizontal)

                Button {
                    UIPasteboard.general.string = publicKey
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                } label: {
                    HStack {
                        Image(systemName: copied ? "checkmark.circle.fill" : "doc.on.doc")
                        Text(copied ? "common.copied".localized : "settings.qr.copyAddress".localized)
                    }
                    .font(.subheadline)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .foregroundColor(.primary)
                    .background(Theme.brand.opacity(0.18)).cornerRadius(12)
                }.padding(.horizontal)

                Spacer()
            }
            .padding()
            .navigationTitle("settings.qr.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) { dismiss() }
                }
            }
            .onAppear { generateQRCode() }
            .sheet(isPresented: $showInviteShare) {
                ShareSheet(items: InviteLink.shareItems(
                    forMyPublicKey: publicKey,
                    senderName: UserDefaults.standard.string(forKey: "userName")))
            }
        }
    }

    private func generateQRCode() {
        let data = publicKey.data(using: .utf8)
        let filter = CIFilter(name: "CIQRCodeGenerator")
        filter?.setValue(data, forKey: "inputMessage")
        filter?.setValue("H", forKey: "inputCorrectionLevel")

        if let outputImage = filter?.outputImage {
            let transform = CGAffineTransform(scaleX: 10, y: 10)
            let scaledImage = outputImage.transformed(by: transform)
            let context = CIContext()
            if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                qrImage = UIImage(cgImage: cgImage)
            }
        }
    }

    private func formatAddress(_ address: String) -> String {
        guard address.count > 16 else { return address }
        let start = address.prefix(8)
        let end = address.suffix(6)
        return "\(start)...\(end)"
    }
}

// MARK: - Blocked Contacts View

struct BlockedContactsView: View {
    @StateObject private var blockedManager = BlockedContactsManager.shared

    var body: some View {
        List {
            if blockedManager.blockedContacts.isEmpty {
                Section {
                    HStack {
                        Spacer()
                        VStack(spacing: 12) {
                            Image(systemName: "hand.raised.slash").font(.scaled(40)).foregroundColor(.secondary)
                            Text("settings.privacy.blockedContacts.empty".localized).foregroundColor(.secondary)
                        }
                        .padding(.vertical, 40)
                        Spacer()
                    }
                }
            } else {
                Section {
                    ForEach(blockedManager.blockedContacts, id: \.publicKey) { contact in
                        HStack(spacing: 12) {
                            Circle().fill(Color.gray.opacity(0.3)).frame(width: 44, height: 44)
                                .overlay(Text(String((contact.displayName ?? contact.publicKey).prefix(2)).uppercased()).font(.caption).fontWeight(.bold))

                            VStack(alignment: .leading, spacing: 4) {
                                Text(contact.displayName ?? "settings.privacy.blockedContacts.unknown".localized).font(.body)
                                Text(String(contact.publicKey.prefix(12)) + "...").font(.caption).foregroundColor(.secondary)
                            }

                            Spacer()

                            Button {
                                blockedManager.unblockContact(publicKey: contact.publicKey)
                            } label: {
                                Text("settings.privacy.blockedContacts.unblock".localized)
                                    .font(.subheadline).fontWeight(.medium).foregroundColor(.white)
                                    .padding(.horizontal, 12).padding(.vertical, 6)
                                    .background(Color.red).cornerRadius(16)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                } header: {
                    Text("\(blockedManager.blockedContacts.count) " + "settings.privacy.blockedContacts.count".localized)
                }
            }
        }
        .navigationTitle("settings.privacy.blockedContacts".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Call Settings View

struct CallSettingsView: View {
    @AppStorage("callKitDisabled") private var callKitDisabled = false
    @AppStorage("callPrivacyMode") private var callPrivacyMode = "direct"
    // Phase 4.5: force TURN relay for strict networks (symmetric NAT, corp VPNs).
    @AppStorage("oshi.call.forceRelay") private var forceRelay: Bool = false
    // Android parity: pre-select starting voice effect for calls (CallVoiceEffect.rawValue).
    @AppStorage("defaultVoiceEffect") private var defaultVoiceEffect: String = "None"
    // Android parity: Auto (adaptive), Low (capped), High (start high).
    @AppStorage("callQuality") private var callQuality: String = "Auto"
    @State private var showRestartAlert = false

    private let brandColor = Theme.brand

    private var isChina: Bool {
        ChinaRegionDetector.shared.isChina
    }

    /// __CALL_PRIVACY_HONEST_2026_10_09__ One intent, both keys.
    ///
    /// `oshi.call.forceRelay` is what moves audio onto the OSHI server;
    /// `callPrivacyMode` is what travels to the peer in the signal JSON as
    /// `privacy_mode`. They described the same choice and could disagree, so
    /// they are now always written together and the UI reads only the first.
    private func setCallPrivacy(_ mode: String) {
        callPrivacyMode = mode
        forceRelay = (mode == "relay")
    }

    /// Brings a device that was left with the two keys disagreeing onto one
    /// answer. Only ever turns the relay ON, and only for someone who had
    /// already asked for it with the button that used to do nothing — it must
    /// not quietly move anybody onto the relay who never chose it.
    private func reconcileCallPrivacyKeys() {
        if callPrivacyMode == "relay" && !forceRelay {
            forceRelay = true
        } else if forceRelay && callPrivacyMode != "relay" {
            // The working toggle was on; make the wire field agree with it.
            callPrivacyMode = "relay"
        }
    }

    var body: some View {
        List {
            // MARK: - Call Privacy (TURN/STUN transport)
            Section {
                Button {
                    // __CALL_PRIVACY_HONEST_2026_10_09__ Also writes
                    // `oshi.call.forceRelay`. See the note on the section below.
                    setCallPrivacy("relay")
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "shield.checkered")
                            .font(.title2)
                            .foregroundColor(.green)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings_call_privacy_relay".localized)
                                .font(.body)
                                .foregroundColor(.primary)
                            Text("settings_call_privacy_relay_desc".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        if forceRelay {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(brandColor)
                        }
                    }
                }

                Button {
                    setCallPrivacy("direct")
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "bolt.horizontal.fill")
                            .font(.title2)
                            .foregroundColor(.orange)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings_call_privacy_direct".localized)
                                .font(.body)
                                .foregroundColor(.primary)
                            Text("settings_call_privacy_direct_desc".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        if !forceRelay {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(brandColor)
                        }
                    }
                }
            } header: {
                Text("settings_call_privacy".localized)
            } footer: {
                // __CALL_PRIVACY_HONEST_2026_10_09__ These two buttons used to
                // set `callPrivacyMode` and nothing else — and NOTHING reads
                // that key to route anything. It is written in
                // `negotiatedPrivacyMode` (VoiceCallManager.swift:5345), reset
                // (:9113), logged (:5346) and never read again; the only other
                // consumer is the `privacy_mode` field of the signal JSON
                // (:15262), which the peer also does not act on. The TURN-relay
                // media path it was designed to drive is switched off
                // (`P2PTransport.swift:186`, measured broken 2026-09-23) and
                // its sender is a `return false` stub (:2520).
                //
                // So "Relay" was a button that changed a log line. The key that
                // genuinely moves audio onto the OSHI server is
                // `oshi.call.forceRelay` — read at VoiceCallManager.swift:11358
                // and P2PTransport.swift:345 — which was a SECOND, separate
                // toggle further down this screen. Two controls for one idea,
                // and the obvious one was the inert one.
                //
                // Now the buttons write both, and the duplicate toggle is gone.
                // `callPrivacyMode` is still written so the wire field keeps its
                // meaning for peers.
                Text("settings.calls.forcerelay.subtitle".localized)
            }

            Section {
                Toggle(isOn: $forceRelay) {
                    HStack(spacing: 12) {
                        Image(systemName: "network.badge.shield.half.filled")
                            .font(.title2)
                            .foregroundColor(brandColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings.calls.forcerelay.title".localized)
                                .font(.body)
                            Text("settings.calls.forcerelay.subtitle".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .tint(brandColor)
                // __AEC_TOGGLE_REMOVED_2026_08_26__ Le test a deux appareils a eu
                // lieu, involontairement, et il a tranche contre l'interrupteur.
                //
                // Le commentaire qui vivait ici disait: « reste optionnel tant
                // qu'un test a deux appareils ne l'a pas prouve », en pariant sur
                // l'auto-reparation ajoutee en aout. Rapport terrain du
                // 2026-08-26, appel reel entre deux personnes: activer l'option
                // a COUPE LE SON DES DEUX COTES. C'est mot pour mot le mode de
                // panne que `__AEC_STAYS_OFF_2026_08_14__` decrit dans
                // `VoiceCallManager` — VPIO mal negocie, tampons pleins mais
                // entierement a zero — et le second chien de garde n'a pas
                // rattrape a temps, exactement comme la fois d'avant.
                //
                // Un interrupteur qui rend l'app muette n'est pas une option
                // « experimentale », c'est un piege: la panne qu'il provoque
                // ressemble a un probleme de reseau, donc personne ne fait le
                // lien avec le reglage qu'il vient d'activer, et personne ne
                // pense a revenir l'eteindre.
                //
                // Retire de l'interface. La capacite reste dans le moteur pour
                // qui veut la tester en Debug (`call_aec_enabled`), mais elle ne
                // s'offre plus a quelqu'un qui veut simplement moins d'echo.
                // Le vrai echo se corrigera dans le chemin codec/resampling,
                // pas par cet interrupteur.

                // Diagnosing one side of a call needs the OTHER side's log, and
                // there was no way to get one off a device. Shares a REDACTED
                // copy — call-path lines only; the raw log carries decrypted
                // message previews and must never leave the phone.
                //
                // __CALL_LOG_AT_REST_2026_09_23__ The export used to be BUILT here,
                // in the view body: every render read the whole log and left a
                // plaintext copy in tmp/. It is now produced only when the user
                // shares, off the main thread, by decrypting the sealed log.
                if CallFileLogger.shared.hasDiagnostics {
                    ShareLink(item: CallDiagnosticsExport(),
                              preview: SharePreview(CallFileLogger.exportFileName)) {
                        HStack(spacing: 12) {
                            Image(systemName: "square.and.arrow.up.on.square")
                                .font(.title2)
                                .foregroundColor(brandColor)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("settings.calls.diaglog.title".localized)
                                    .font(.body)
                                Text("settings.calls.diaglog.subtitle".localized)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            } header: {
                Text("settings.calls.section.connection".localized)
            } footer: {
                Text(forceRelay
                     ? "settings.calls.forcerelay.footer.on".localized
                     : "settings.calls.forcerelay.footer.off".localized)
            }

            Section {
                Toggle(isOn: Binding(
                    get: { !callKitDisabled },
                    set: { newValue in
                        callKitDisabled = !newValue
                        showRestartAlert = true
                    }
                )) {
                    HStack(spacing: 12) {
                        Image(systemName: "phone.circle.fill").font(.title2).foregroundColor(brandColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings.calls.callkit".localized).font(.body)
                            Text("settings.calls.callkit.subtitle".localized).font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
                .tint(brandColor)
                .disabled(isChina)
            } header: {
                Text("settings.calls.section.system".localized)
            } footer: {
                if isChina {
                    Text("settings.calls.callkit.china".localized).foregroundColor(.orange)
                } else {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(callKitDisabled ?
                             "settings.calls.callkit.disabled.footer".localized :
                             "settings.calls.callkit.enabled.footer".localized)
                        Text("settings.calls.region.guidance".localized)
                            .foregroundColor(.orange)
                    }
                }
            }

            Section {
                VStack(alignment: .leading, spacing: 12) {
                    callModeRow(icon: "bell.badge.fill", color: callKitDisabled ? .green : .secondary, title: "settings.calls.native.ringtone".localized, active: callKitDisabled)
                    callModeRow(icon: "clock.arrow.circlepath", color: callKitDisabled ? .green : .secondary, title: "settings.calls.native.nohistory".localized, active: callKitDisabled)
                    callModeRow(icon: "lock.shield.fill", color: callKitDisabled ? .green : .secondary, title: "settings.calls.native.private".localized, active: callKitDisabled)
                    if callKitDisabled {
                        callModeRow(icon: "exclamationmark.triangle.fill", color: .orange, title: "settings.calls.native.limitation".localized, active: true)
                    }
                }
                .padding(.vertical, 4)
            } header: {
                Text(callKitDisabled ? "settings.calls.section.nativemode".localized : "settings.calls.section.systemmode".localized)
            }

            Section {
                Picker(selection: $defaultVoiceEffect) {
                    ForEach(CallVoiceEffect.allCases) { effect in
                        Text(effect.displayName).tag(effect.rawValue)
                    }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "waveform")
                            .font(.title2)
                            .foregroundColor(brandColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings.calls.voiceeffect.title".localized).font(.body)
                            Text("settings.calls.voiceeffect.subtitle".localized).font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
            } header: {
                Text("settings.calls.section.voiceeffects".localized)
            }

            Section {
                Picker(selection: $callQuality) {
                    Text("settings.calls.quality.auto".localized).tag("Auto")
                    Text("settings.calls.quality.low".localized).tag("Low")
                    Text("settings.calls.quality.high".localized).tag("High")
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "dial.high")
                            .font(.title2)
                            .foregroundColor(brandColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("settings.calls.quality.title".localized).font(.body)
                            Text("settings.calls.quality.subtitle".localized).font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
            } header: {
                Text("settings.calls.section.quality".localized)
            }

            Section {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "info.circle.fill").foregroundColor(.blue).font(.title3)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("settings.calls.info.title".localized).font(.subheadline).fontWeight(.medium)
                        Text("settings.calls.info.description".localized).font(.caption).foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .navigationTitle("settings.calls.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        // __CALL_PRIVACY_HONEST_2026_10_09__ The two keys used to be set by two
        // different controls; bring an existing device onto one answer.
        .onAppear { reconcileCallPrivacyKeys() }
        .alert("settings.calls.restart.title".localized, isPresented: $showRestartAlert) {
            Button("common.ok".localized) {}
        } message: {
            Text("settings.calls.restart.message".localized)
        }
    }

    private func callModeRow(icon: String, color: Color, title: String, active: Bool) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).foregroundColor(color).frame(width: 24)
            Text(title).font(.subheadline).foregroundColor(active ? .primary : .secondary)
            Spacer()
            if active {
                Image(systemName: "checkmark.circle.fill").foregroundColor(.green).font(.caption)
            }
        }
    }
}

// MARK: - Preview

// MARK: - Map Preference Section

struct MapPreferenceSection: View {
    @AppStorage("mapOpenPreference") private var mapPreference: String = "oshi"
    /// Which tiles the map tab draws — see `MapBackground`. A different
    /// question from `mapPreference` above, which is about opening a place.
    @AppStorage(MapBackground.preferenceKey) private var mapBackground: String = MapBackground.oshi.rawValue
    // Shared key — also read by MapRoutingEngine and the tile-overlay agent.
    // When ON, all map features (tiles, search, routing) skip network fallback.
    @AppStorage("oshi.map.offlineOnly") private var offlineOnly: Bool = false

    private let options: [(value: String, labelKey: String)] = [
        ("oshi", "map_option_oshi"),
        ("apple", "map_option_apple"),
        ("google", "map_option_google")
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // "Open locations with" — not "Maps": the choice applies to OPENING
            // a place (shared location, check-in, address in a chat); the Map
            // tab always stays OSHI's own map. See MapOpener.
            Text("map_open_with_title".localized)
                .font(.subheadline.bold())
                .foregroundColor(.secondary)
                .padding(.horizontal, 4)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                    Button {
                        mapPreference = option.value
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: iconName(for: option.value))
                                .font(.scaled(16))
                                .foregroundColor(iconColor(for: option.value))
                                .frame(width: 30, height: 30)
                                .background(iconColor(for: option.value).opacity(0.15))
                                .clipShape(RoundedRectangle(cornerRadius: 7))

                            Text(option.labelKey.localized)
                                .foregroundColor(.primary)

                            Spacer()

                            if mapPreference == option.value {
                                Image(systemName: "checkmark")
                                    .font(.scaled(14, weight: .semibold))
                                    .foregroundColor(Theme.brand)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 13)
                    }

                    if index < options.count - 1 {
                        Divider().padding(.leading, 60)
                    }
                }

            }
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemGroupedBackground))
            )

            Text("map_open_preference".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .padding(.horizontal, 4)
                .padding(.top, 6)
                .padding(.bottom, 16)

            // __MAP_BACKGROUND_CHOICE_2026_11_05__ A SEPARATE choice from the
            // one above. "Open locations with" decides which app opens a shared
            // place; this decides what the map tab draws. Overloading one key
            // would have broken a real combination — shared pins in Apple Maps,
            // OSHI's offline tiles in the app. See `MapBackground`.
            Text("map_background_title".localized)
                .font(.subheadline.bold())
                .foregroundColor(.secondary)
                .padding(.horizontal, 4)
                .padding(.bottom, 8)

            VStack(spacing: 0) {
                ForEach(Array(MapBackground.allCases.enumerated()), id: \.element) { index, option in
                    Button {
                        mapBackground = option.rawValue
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: option.symbolName)
                                .font(.scaled(16))
                                .foregroundColor(Theme.brand)
                                .frame(width: 30, height: 30)
                                .background(Theme.brand.opacity(0.15))
                                .clipShape(RoundedRectangle(cornerRadius: 7))

                            VStack(alignment: .leading, spacing: 2) {
                                Text(option.titleKey.localized)
                                    .foregroundColor(.primary)
                                // The trade is stated on the row, not discovered
                                // in a dead zone: Apple's basemap needs the
                                // network and ignores downloaded regions.
                                Text(option.detailKey.localized)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }

                            Spacer()

                            if mapBackground == option.rawValue {
                                Image(systemName: "checkmark")
                                    .font(.scaled(14, weight: .semibold))
                                    .foregroundColor(Theme.brand)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 13)
                    }
                    .accessibilityAddTraits(mapBackground == option.rawValue ? [.isSelected] : [])

                    if index < MapBackground.allCases.count - 1 {
                        Divider().padding(.leading, 60)
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemGroupedBackground))
            )

            // Says out loud why Google is absent here but present above, so the
            // gap reads as a licence constraint rather than an oversight.
            Text("map_background_footer".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .padding(.horizontal, 4)
                .padding(.top, 6)
                .padding(.bottom, 16)

            VStack(spacing: 0) {
                // Offline-only toggle (applies to tiles, search, routing).
                HStack(spacing: 14) {
                    Image(systemName: "wifi.slash")
                        .font(.scaled(16))
                        .foregroundColor(.orange)
                        .frame(width: 30, height: 30)
                        .background(Color.orange.opacity(0.15))
                        .clipShape(RoundedRectangle(cornerRadius: 7))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("settings_maps_offline_only_title".localized)
                            .foregroundColor(.primary)
                        Text("settings_maps_offline_only_subtitle".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    Spacer()

                    Toggle("", isOn: $offlineOnly)
                        .labelsHidden()
                        .tint(Theme.brand)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemGroupedBackground))
            )
        }
    }

    private func iconName(for value: String) -> String {
        switch value {
        case "oshi": return "map.fill"
        case "apple": return "apple.logo"
        case "google": return "globe"
        default: return "map"
        }
    }

    private func iconColor(for value: String) -> Color {
        switch value {
        case "oshi": return Theme.brand
        case "apple": return .blue
        case "google": return .green
        default: return .gray
        }
    }
}

/// __CALL_LOG_AT_REST_2026_09_23__ The redacted call log, decrypted and written
/// to a temporary `.txt` only once the share sheet asks for it. The system copies
/// the file (`allowAccessingOriginalFile` is false), and `CallFileLogger` deletes
/// our copy at the next launch or export — the only moment we know it is done.
struct CallDiagnosticsExport: Transferable {
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .plainText) { _ in
            // Detached: the export waits on the logger queue and decrypts megabytes.
            let url = await Task.detached(priority: .userInitiated) {
                CallFileLogger.shared.exportRedactedCallLog()
            }.value
            guard let url else { throw CocoaError(.fileReadNoSuchFile) }
            return SentTransferredFile(url)
        }
    }
}

#Preview {
    SettingsView()
        .environmentObject(IdentityManager())
        .environmentObject(MeshNetworkManager())
        .environmentObject(MessageManager())
}
