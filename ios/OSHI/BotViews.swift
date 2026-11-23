//
//  BotViews.swift
//  OSHI - Bot Platform & Portal UI
//
//  Telegram BotFather-like bot creation, management, and discovery portal.
//  Includes: Portal (Channels/Bots/Apps tabs), Bot Creation wizard,
//  Bot Detail/Edit, Trigger configuration, and Script editor.
//

import SwiftUI
import MultipeerConnectivity
import SafariServices
import WebKit

// MARK: - Portal View (Discovery Hub)

struct PortalView: View {
    @StateObject private var botManager = BotManager.shared
    @StateObject private var groupManager = MessageGroupManager.shared
    @StateObject private var discoveryManager = DiscoveredGroupsManager.shared
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @State private var selectedTab: PortalTab = .bots
    @State private var showCreateBot = false
    @State private var showCreateChannel = false
    @State private var searchText = ""

    private let brandColor = Theme.brand

    enum PortalTab: String, CaseIterable {
        case channels = "portal.tab.channels"
        case bots = "portal.tab.bots"
        case apps = "portal.tab.apps"

        var icon: String {
            switch self {
            case .channels: return "megaphone.fill"
            case .bots: return "cpu.fill"
            case .apps: return "square.grid.2x2.fill"
            }
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Tab selector
                HStack(spacing: 0) {
                    ForEach(PortalTab.allCases, id: \.rawValue) { tab in
                        Button {
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            withAnimation(.easeInOut(duration: 0.2)) { selectedTab = tab }
                        } label: {
                            VStack(spacing: 4) {
                                Image(systemName: tab.icon)
                                    .font(.scaled(18))
                                Text(tab.rawValue.localized)
                                    .font(.caption2.weight(.medium))
                            }
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding(.vertical, 6)
                            .foregroundColor(selectedTab == tab ? brandColor : .secondary)
                            .background(
                                selectedTab == tab ?
                                    brandColor.opacity(0.1) : Color.clear
                            )
                            .cornerRadius(12)
                            .contentShape(Rectangle())
                        }
                        .accessibilityLabel(tab.rawValue.localized)
                        .accessibilityAddTraits(selectedTab == tab ? [.isSelected, .isButton] : .isButton)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)

                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                    TextField("portal.search".localized, text: $searchText)
                        .textFieldStyle(.plain)
                }
                .padding(10)
                .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
                .cornerRadius(12)
                .padding(.horizontal)
                .padding(.vertical, 8)

                // Content
                switch selectedTab {
                case .channels:
                    channelsTab
                case .bots:
                    botsTab
                case .apps:
                    appsTab
                }
            }
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .navigationTitle("portal.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        showCreateBot = true
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .foregroundColor(brandColor)
                    }
                    .accessibilityLabel("bot.create_new".localized)
                    .accessibilityHint("a11y.portal.create_hint".localized)
                }
            }
            .sheet(isPresented: $showCreateBot) {
                BotCreationView(isPresented: $showCreateBot)
            }
            .sheet(isPresented: $showCreateChannel) {
                CreateGroupView(preselectedType: .publicGroup)
                    .environmentObject(groupManager)
                    .environmentObject(identityManager)
            }
        }
    }

    // MARK: - Channels Tab (Channels = Public Groups)

    private var channelsTab: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                // My channels (public groups I'm a member of)
                if !myChannels.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("portal.my_channels".localized)
                            .font(.headline)
                            .padding(.horizontal)
                        Text("portal.my_channels_desc".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.horizontal)
                    }
                    .padding(.top, 8)

                    ForEach(myChannels) { group in
                        NavigationLink(destination: GroupChatView(group: group)
                            .environmentObject(groupManager)
                            .environmentObject(identityManager)
                        ) {
                            channelRow(name: group.name, memberCount: group.memberCount, isJoined: true, icon: group.type.icon)
                        }
                        .buttonStyle(.plain)
                    }
                }

                // Discovered channels via mesh
                if !discoveredChannels.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("portal.discovered_channels".localized)
                                .font(.headline)
                            Spacer()
                            HStack(spacing: 4) {
                                ProgressView()
                                    .scaleEffect(0.6)
                                Text("portal.scanning".localized)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                        }
                        .padding(.horizontal)
                    }
                    .padding(.top, myChannels.isEmpty ? 8 : 16)

                    ForEach(discoveredChannels, id: \.groupId) { ad in
                        let alreadyJoined = groupManager.groups.contains(where: { $0.id == ad.groupId })
                        Button {
                            if !alreadyJoined {
                                UINotificationFeedbackGenerator().notificationOccurred(.success)
                                joinDiscoveredChannel(ad)
                            }
                        } label: {
                            channelRow(
                                name: ad.groupName,
                                memberCount: ad.memberCount,
                                isJoined: alreadyJoined,
                                icon: "globe"
                            )
                        }
                        .buttonStyle(.plain)
                        .disabled(alreadyJoined)
                        .accessibilityElement(children: .combine)
                        .accessibilityHint(alreadyJoined ? "" : "a11y.channel.join_hint".localized)
                    }
                }

                // Create channel button
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    showCreateChannel = true
                } label: {
                    HStack(spacing: 12) {
                        Circle()
                            .fill(brandColor.opacity(0.15))
                            .frame(width: 44, height: 44)
                            .overlay(
                                Image(systemName: "plus")
                                    .font(.scaled(18, weight: .semibold))
                                    .foregroundColor(brandColor)
                            )
                            .accessibilityHidden(true)
                        Text("portal.create_channel".localized)
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(brandColor)
                        Spacer()
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    .background(brandColor.opacity(0.05))
                    .cornerRadius(12)
                    .padding(.horizontal)
                    .contentShape(Rectangle())
                }
                .accessibilityLabel("portal.create_channel".localized)
                .padding(.top, 8)

                if myChannels.isEmpty && discoveredChannels.isEmpty {
                    emptyState(
                        icon: "megaphone",
                        title: "portal.no_channels".localized,
                        subtitle: "portal.no_channels_desc".localized
                    )
                }
            }
            .padding(.bottom, 20)
        }
    }

    private func channelRow(name: String, memberCount: Int, isJoined: Bool, icon: String) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(brandColor.opacity(0.2))
                .frame(width: 44, height: 44)
                .overlay(
                    Image(systemName: "megaphone.fill")
                        .foregroundColor(brandColor)
                )
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(.primary)
                HStack(spacing: 4) {
                    Image(systemName: icon)
                        .font(.caption2)
                        .accessibilityHidden(true)
                    Text("\(memberCount) " + "portal.members".localized)
                        .font(.caption2)
                }
                .foregroundColor(.secondary)
            }

            Spacer()

            if isJoined {
                Text("portal.joined".localized)
                    .font(.caption2.weight(.medium))
                    .foregroundColor(.green)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.green.opacity(0.1))
                    .cornerRadius(8)
            } else {
                Text("portal.join".localized)
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(brandColor)
                    .cornerRadius(8)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(12)
        .padding(.horizontal)
    }

    private var myChannels: [MessageGroup] {
        let channels = groupManager.groups.filter { $0.type == .publicGroup || $0.type == .adminOnly }
        if searchText.isEmpty { return channels }
        return channels.filter { $0.name.localizedCaseInsensitiveContains(searchText) }
    }

    private var discoveredChannels: [PublicGroupAd] {
        let myGroupIds = Set(groupManager.groups.map { $0.id })
        let discovered = discoveryManager.discoveredGroups.filter { !myGroupIds.contains($0.groupId) }
        if searchText.isEmpty { return discovered }
        return discovered.filter { $0.groupName.localizedCaseInsensitiveContains(searchText) }
    }

    private func joinDiscoveredChannel(_ ad: PublicGroupAd) {
        let newGroup = MessageGroup(
            id: ad.groupId,
            name: ad.groupName,
            type: .publicGroup,
            adminPublicKey: ad.adminPublicKey,
            members: [GroupMember(
                publicKey: identityManager.publicKey,
                alias: nil,
                joinedAt: Date(),
                isAdmin: false
            )],
            createdAt: ad.timestamp,
            lastActivity: Date()
        )
        groupManager.groups.append(newGroup)
        groupManager.groupMessages[newGroup.id] = []
        // Trigger persistence via addSystemMessage (saveGroups is private)
        groupManager.addSystemMessage(to: newGroup.id, type: .memberJoined, byPublicKey: identityManager.publicKey)
    }

    // MARK: - Bots Tab

    private var botsTab: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                // My bots section
                if !botManager.bots.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("portal.my_bots".localized)
                            .font(.headline)
                            .padding(.horizontal)
                    }
                    .padding(.top, 8)

                    ForEach(filteredBots) { bot in
                        NavigationLink(destination: BotDetailView(bot: bot)) {
                            botRow(bot)
                        }
                        .buttonStyle(.plain)
                    }
                }

                // Bot templates section
                VStack(alignment: .leading, spacing: 8) {
                    Text("portal.bot_templates".localized)
                        .font(.headline)
                        .padding(.horizontal)

                    Text("portal.templates_description".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(.horizontal)
                }
                .padding(.top, botManager.bots.isEmpty ? 8 : 16)

                ForEach(BotManager.templates) { template in
                    Button {
                        createFromTemplate(template)
                    } label: {
                        templateRow(template)
                    }
                    .buttonStyle(.plain)
                }

                if botManager.bots.isEmpty && BotManager.templates.isEmpty {
                    emptyState(
                        icon: "cpu",
                        title: "portal.no_bots".localized,
                        subtitle: "portal.no_bots_desc".localized
                    )
                }
            }
            .padding(.bottom, 20)
        }
    }

    private var filteredBots: [OSHIBot] {
        if searchText.isEmpty { return botManager.bots }
        return botManager.bots.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            $0.username.localizedCaseInsensitiveContains(searchText)
        }
    }

    private func botRow(_ bot: OSHIBot) -> some View {
        HStack(spacing: 12) {
            Text(bot.avatarEmoji)
                .font(.title2)
                .frame(width: 44, height: 44)
                .background(brandColor.opacity(0.1))
                .cornerRadius(12)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(bot.name)
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(.primary)
                    if bot.isEnabled {
                        Circle()
                            .fill(.green)
                            .frame(width: 6, height: 6)
                    }
                }
                Text("@\(bot.username)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text(bot.type.localizedKey.localized)
                    .font(.caption2)
                    .foregroundColor(brandColor)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(brandColor.opacity(0.1))
                    .cornerRadius(6)
                Text(String(format: "bot.messages_count".localized, bot.messagesSent))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(12)
        .padding(.horizontal)
        .accessibilityElement(children: .combine)
    }

    private func templateRow(_ template: BotTemplate) -> some View {
        HStack(spacing: 12) {
            Image(systemName: template.icon)
                .font(.title3)
                .foregroundColor(.white)
                .frame(width: 44, height: 44)
                .background(brandColor.gradient)
                .cornerRadius(12)

            VStack(alignment: .leading, spacing: 2) {
                Text(template.name)
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(.primary)
                Text(template.description)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }

            Spacer()

            Image(systemName: "plus.circle")
                .foregroundColor(brandColor)
                .accessibilityHidden(true)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(12)
        .padding(.horizontal)
        .accessibilityElement(children: .combine)
        .accessibilityHint("a11y.bot.template_hint".localized)
    }

    private func createFromTemplate(_ template: BotTemplate) {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        let bot = botManager.createBot(
            name: template.name,
            username: template.name.lowercased().replacingOccurrences(of: " ", with: "_") + "_bot",
            description: template.description,
            type: template.type,
            ownerPublicKey: identityManager.publicKey
        )
        var updated = bot
        updated.triggers = template.triggers
        if let script = template.scriptTemplate {
            updated.scriptContent = script
        }
        botManager.updateBot(updated)
        botManager.registerBotWithServer(updated)
    }

    // MARK: - Apps Tab (Mesh-enabled utilities)

    private var appsTab: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("portal.mesh_apps".localized)
                        .font(.headline)
                        .padding(.horizontal)
                    Text("portal.apps_description".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(.horizontal)
                }
                .padding(.top, 8)

                // Mesh status banner
                meshStatusBanner

                // Functional mesh apps
                ForEach(meshApps.filter { app in
                    searchText.isEmpty || app.name.localizedCaseInsensitiveContains(searchText)
                }, id: \.id) { app in
                    NavigationLink(destination: app.destination) {
                        meshAppRow(app)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.bottom, 20)
        }
    }

    private var meshStatusBanner: some View {
        let peerCount = meshManager.connectedPeers.count
        let isActive = meshManager.meshAvailable

        return HStack(spacing: 10) {
            Image(systemName: isActive ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                .font(.title3)
                .foregroundColor(isActive ? .green : .secondary)

            VStack(alignment: .leading, spacing: 2) {
                Text(isActive ? "portal.mesh_active".localized : "portal.mesh_inactive".localized)
                    .font(.subheadline.weight(.medium))
                Text(isActive
                     ? String(format: "portal.peers_connected".localized, peerCount)
                     : "portal.mesh_inactive_hint".localized)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Circle()
                .fill(isActive ? Color.green : Color.secondary.opacity(0.3))
                .frame(width: 10, height: 10)
        }
        .padding()
        .background(isActive ? Color.green.opacity(0.08) : Color(.secondarySystemGroupedBackground))
        .cornerRadius(14)
        .padding(.horizontal)
    }

    private struct MeshApp: Identifiable {
        let id: String
        let name: String
        let desc: String
        let icon: String
        let color: Color
        let destination: AnyView
    }

    private var meshApps: [MeshApp] {
        [
            MeshApp(
                id: "radar",
                name: "portal.app.radar".localized,
                desc: "portal.app.radar_desc".localized,
                icon: "radar",
                color: .blue,
                destination: AnyView(MeshRadarView().environmentObject(meshManager))
            ),
            MeshApp(
                id: "channels",
                name: "portal.app.public_channels".localized,
                desc: "portal.app.public_channels_desc".localized,
                icon: "megaphone.fill",
                color: brandColor,
                destination: AnyView(DiscoverPublicGroupsView()
                    .environmentObject(groupManager)
                    .environmentObject(identityManager))
            ),
            MeshApp(
                id: "file_share",
                name: "portal.app.file_share".localized,
                desc: "portal.app.file_share_desc".localized,
                icon: "arrow.up.arrow.down.circle.fill",
                color: .orange,
                destination: AnyView(MeshFileShareView().environmentObject(meshManager))
            ),
            MeshApp(
                id: "relay",
                name: "portal.app.relay".localized,
                desc: "portal.app.relay_desc".localized,
                icon: "arrow.triangle.swap",
                color: .purple,
                destination: AnyView(MeshRelayView().environmentObject(meshManager))
            ),
            MeshApp(
                id: "network_map",
                name: "portal.app.network_map".localized,
                desc: "portal.app.network_map_desc".localized,
                icon: "point.3.connected.trianglepath.dotted",
                color: .teal,
                destination: AnyView(MeshNetworkMapView().environmentObject(meshManager))
            ),
        ]
    }

    private func meshAppRow(_ app: MeshApp) -> some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 12)
                .fill(app.color.opacity(0.15))
                .frame(width: 44, height: 44)
                .overlay(
                    Image(systemName: app.icon)
                        .font(.scaled(20))
                        .foregroundColor(app.color)
                )
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(app.name)
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(.primary)
                Text(app.desc)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(12)
        .padding(.horizontal)
        .accessibilityElement(children: .combine)
    }

    private func emptyState(icon: String, title: String, subtitle: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.scaled(40))
                .foregroundColor(.secondary.opacity(0.5))
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
                .foregroundColor(.secondary)
            Text(subtitle)
                .font(.caption)
                .foregroundColor(.secondary.opacity(0.7))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(40)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Mesh Radar View (Nearby Peers)

struct MeshRadarView: View {
    @EnvironmentObject var mesh: MeshNetworkManager

    private let brandColor = Theme.brand

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: mesh.meshAvailable ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                        .font(.title2)
                        .foregroundColor(mesh.meshAvailable ? .green : .secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(mesh.meshAvailable ? "portal.radar.mesh_on".localized : "portal.radar.mesh_off".localized)
                            .font(.headline)
                        Text(String(format: "portal.radar.peer_summary".localized, mesh.connectedPeers.count, mesh.discoveredPeers.count))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }

            if !mesh.connectedPeers.isEmpty {
                Section("portal.radar.connected".localized) {
                    ForEach(mesh.connectedPeers, id: \.self) { peer in
                        HStack(spacing: 10) {
                            Circle()
                                .fill(.green)
                                .frame(width: 8, height: 8)
                            Text(peer.displayName)
                                .font(.subheadline)
                            Spacer()
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                                .font(.caption)
                        }
                    }
                }
            }

            if !mesh.discoveredPeers.isEmpty {
                Section("portal.radar.discovered".localized) {
                    ForEach(mesh.discoveredPeers.filter { !mesh.connectedPeers.contains($0) }, id: \.self) { peer in
                        HStack(spacing: 10) {
                            Circle()
                                .fill(.orange)
                                .frame(width: 8, height: 8)
                            Text(peer.displayName)
                                .font(.subheadline)
                            Spacer()
                            Image(systemName: "wave.3.right")
                                .foregroundColor(.orange)
                                .font(.caption)
                        }
                    }
                }
            }

            if mesh.connectedPeers.isEmpty && mesh.discoveredPeers.isEmpty {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "radar")
                            .font(.scaled(40))
                            .foregroundColor(.secondary.opacity(0.5))
                        Text("portal.radar.no_peers".localized)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Text("portal.radar.no_peers_hint".localized)
                            .font(.caption)
                            .foregroundColor(.secondary.opacity(0.7))
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                }
            }
        }
        .navigationTitle("portal.app.radar".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Mesh File Share View

struct MeshFileShareView: View {
    @EnvironmentObject var mesh: MeshNetworkManager
    @State private var showFilePicker = false
    @State private var statusMessage = ""

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("portal.fileshare.info".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            if mesh.connectedPeers.isEmpty {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "wifi.slash")
                            .font(.scaled(36))
                            .foregroundColor(.secondary.opacity(0.5))
                        Text("portal.fileshare.no_peers".localized)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Text("portal.fileshare.no_peers_hint".localized)
                            .font(.caption)
                            .foregroundColor(.secondary.opacity(0.7))
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                }
            } else {
                Section("portal.fileshare.send_to".localized) {
                    ForEach(mesh.connectedPeers, id: \.self) { peer in
                        HStack(spacing: 10) {
                            Circle()
                                .fill(.green)
                                .frame(width: 8, height: 8)
                            Text(peer.displayName)
                                .font(.subheadline)
                            Spacer()
                            Button {
                                showFilePicker = true
                            } label: {
                                Label("portal.fileshare.send".localized, systemImage: "arrow.up.circle.fill")
                                    .font(.caption)
                            }
                        }
                    }
                }
            }

            if !statusMessage.isEmpty {
                Section {
                    Text(statusMessage)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("portal.app.file_share".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Mesh Relay View (Store & Forward)

struct MeshRelayView: View {
    @EnvironmentObject var mesh: MeshNetworkManager

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.triangle.swap")
                            .foregroundColor(.purple)
                        Text("portal.relay.title".localized)
                            .font(.headline)
                    }
                    Text("portal.relay.desc".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Section("portal.relay.how_it_works".localized) {
                Label("portal.relay.step1".localized, systemImage: "1.circle.fill")
                    .font(.subheadline)
                Label("portal.relay.step2".localized, systemImage: "2.circle.fill")
                    .font(.subheadline)
                Label("portal.relay.step3".localized, systemImage: "3.circle.fill")
                    .font(.subheadline)
            }

            Section("portal.relay.status".localized) {
                HStack {
                    Text("portal.relay.connected_peers".localized)
                        .font(.subheadline)
                    Spacer()
                    Text("\(mesh.connectedPeers.count)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(mesh.connectedPeers.isEmpty ? .secondary : .green)
                }
                HStack {
                    Text("portal.relay.mesh_status".localized)
                        .font(.subheadline)
                    Spacer()
                    Text(mesh.meshAvailable ? "portal.relay.active".localized : "portal.relay.inactive".localized)
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(mesh.meshAvailable ? .green : .secondary)
                }
            }
        }
        .navigationTitle("portal.app.relay".localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Mesh Network Map View

struct MeshNetworkMapView: View {
    @EnvironmentObject var mesh: MeshNetworkManager

    private let brandColor = Theme.brand

    var body: some View {
        VStack(spacing: 0) {
            topologyView
            statsList
        }
        .navigationTitle("portal.app.network_map".localized)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var topologyView: some View {
        ZStack {
            centerNodeView
            peerNodesView
        }
        .frame(height: 280)
        .frame(maxWidth: .infinity)
        .background(Color(.secondarySystemGroupedBackground))
    }

    @ViewBuilder
    private var centerNodeView: some View {
        let label = "portal.netmap.you".localized
        Circle()
            .fill(brandColor)
            .frame(width: 56, height: 56)
            .overlay(Image(systemName: "iphone").foregroundColor(.white).font(.title3))
            .overlay(Text(label).font(.caption2.weight(.bold)).foregroundColor(brandColor).offset(y: 38))
    }

    @ViewBuilder
    private var peerNodesView: some View {
        let peers = Array(mesh.connectedPeers)
        let total = max(peers.count, 1)
        ForEach(0..<peers.count, id: \.self) { index in
            let peer = peers[index]
            let angle = (Double(index) / Double(total)) * 2.0 * Double.pi - Double.pi / 2.0
            let xOff = CGFloat(cos(angle)) * 100.0
            let yOff = CGFloat(sin(angle)) * 100.0
            VStack(spacing: 4) {
                Circle()
                    .fill(Color.green.opacity(0.2))
                    .frame(width: 40, height: 40)
                    .overlay(Image(systemName: "iphone.gen2").foregroundColor(.green).font(.caption))
                Text(String(peer.displayName.prefix(8)))
                    .font(.scaled(9))
                    .foregroundColor(.secondary)
            }
            .offset(x: xOff, y: yOff)
        }
    }

    private var statsList: some View {
        List {
            Section("portal.netmap.stats".localized) {
                HStack {
                    Label("portal.netmap.connected".localized, systemImage: "link")
                    Spacer()
                    Text("\(mesh.connectedPeers.count)")
                        .fontWeight(.semibold)
                }
                HStack {
                    Label("portal.netmap.discovered".localized, systemImage: "eye")
                    Spacer()
                    Text("\(mesh.discoveredPeers.count)")
                        .fontWeight(.semibold)
                }
                HStack {
                    Label("portal.netmap.mesh_active".localized, systemImage: "checkmark.circle")
                    Spacer()
                    Image(systemName: mesh.meshAvailable ? "checkmark.circle.fill" : "xmark.circle")
                        .foregroundColor(mesh.meshAvailable ? .green : .red)
                }
            }
        }
    }
}

// MARK: - Bot Creation View (BotFather Wizard)

struct BotCreationView: View {
    @Binding var isPresented: Bool
    @StateObject private var botManager = BotManager.shared
    @EnvironmentObject var identityManager: IdentityManager
    @State private var step: CreationStep = .name
    @State private var botName = ""
    @State private var botUsername = ""
    @State private var botDescription = ""
    @State private var selectedType: BotType = .automation
    @State private var selectedEmoji = "🤖"
    @State private var createdBot: OSHIBot?
    @State private var tokenCopied = false

    private let brandColor = Theme.brand

    enum CreationStep {
        case name, type, description, done
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Progress indicator
                HStack(spacing: 4) {
                    ForEach(0..<4, id: \.self) { i in
                        RoundedRectangle(cornerRadius: 2)
                            .fill(i <= stepIndex ? brandColor : Color(.systemGray4))
                            .frame(height: 3)
                    }
                }
                .padding(.horizontal)
                .padding(.top, 12)

                Spacer()

                // Step content
                switch step {
                case .name:
                    nameStep
                case .type:
                    typeStep
                case .description:
                    descriptionStep
                case .done:
                    doneStep
                }

                Spacer()

                // Navigation buttons
                HStack(spacing: 12) {
                    if step != .name && step != .done {
                        Button {
                            withAnimation { goBack() }
                        } label: {
                            Text("common.back".localized)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(Color(.tertiarySystemGroupedBackground))
                                .foregroundColor(.primary)
                                .cornerRadius(14)
                        }
                    }

                    if step != .done {
                        Button {
                            withAnimation { goNext() }
                        } label: {
                            Text(step == .description ? "bot.create".localized : "common.next".localized)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(canProceed ? brandColor : Color(.systemGray4))
                                .foregroundColor(canProceed ? .white : .secondary)
                                .cornerRadius(14)
                        }
                        .disabled(!canProceed)
                    } else {
                        Button {
                            isPresented = false
                        } label: {
                            Text("common.done".localized)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(brandColor)
                                .foregroundColor(.white)
                                .cornerRadius(14)
                        }
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 16)
            }
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
            .navigationTitle("bot.create_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { isPresented = false }
                }
            }
        }
    }

    // MARK: - Steps

    private var nameStep: some View {
        VStack(spacing: 20) {
            // Emoji picker
            Text(selectedEmoji)
                .font(.scaled(60))
                .frame(width: 100, height: 100)
                .background(brandColor.opacity(0.1))
                .cornerRadius(24)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(["🤖", "🔧", "⚡", "🎯", "📡", "🛡️", "🔔", "📊", "🌐", "🎲", "🧠", "💡"], id: \.self) { emoji in
                        Button {
                            UISelectionFeedbackGenerator().selectionChanged()
                            selectedEmoji = emoji
                        } label: {
                            Text(emoji)
                                .font(.title2)
                                .frame(width: 44, height: 44)
                                .background(selectedEmoji == emoji ? brandColor.opacity(0.2) : Color.clear)
                                .cornerRadius(10)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel(emoji)
                        .accessibilityAddTraits(selectedEmoji == emoji ? [.isSelected, .isButton] : .isButton)
                    }
                }
                .padding(.horizontal)
            }

            VStack(spacing: 12) {
                TextField("bot.name_placeholder".localized, text: $botName)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)

                HStack {
                    Text("@")
                        .foregroundColor(.secondary)
                    TextField("bot.username_placeholder".localized, text: $botUsername)
                        .textFieldStyle(.plain)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
                .padding(10)
                .oledGlassCard(RoundedRectangle(cornerRadius: 10), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
                .cornerRadius(10)
                .padding(.horizontal)
            }

            Text("bot.name_hint".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    private var typeStep: some View {
        VStack(spacing: 16) {
            Text("bot.choose_type".localized)
                .font(.title3.weight(.semibold))

            ForEach(BotType.allCases, id: \.rawValue) { type in
                Button {
                    UISelectionFeedbackGenerator().selectionChanged()
                    withAnimation(.easeInOut(duration: 0.15)) { selectedType = type }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: type.icon)
                            .font(.title3)
                            .foregroundColor(selectedType == type ? .white : brandColor)
                            .frame(width: 44, height: 44)
                            .background(selectedType == type ? brandColor : brandColor.opacity(0.1))
                            .cornerRadius(12)
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(type.localizedKey.localized)
                                .font(.subheadline.weight(.medium))
                                .foregroundColor(.primary)
                            Text("bot.type.\(type.rawValue).desc".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(2)
                        }

                        Spacer()

                        if selectedType == type {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(brandColor)
                                .accessibilityHidden(true)
                        }
                    }
                    .padding(12)
                    .background(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(selectedType == type ? brandColor : Color(.systemGray4), lineWidth: selectedType == type ? 2 : 1)
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(selectedType == type ? [.isSelected, .isButton] : .isButton)
                .padding(.horizontal)
            }
        }
    }

    private var descriptionStep: some View {
        VStack(spacing: 20) {
            HStack {
                Text(selectedEmoji)
                    .font(.title)
                VStack(alignment: .leading) {
                    Text(botName)
                        .font(.headline)
                    Text("@\(effectiveUsername)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("bot.description_label".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)

                TextEditor(text: $botDescription)
                    .frame(height: 100)
                    .padding(8)
                    .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
                    .cornerRadius(12)
                    .padding(.horizontal)
            }

            Text("bot.description_hint".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    private var doneStep: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.scaled(50))
                    .foregroundColor(.green)

                Text("bot.created_title".localized)
                    .font(.title2.weight(.bold))

                if let bot = createdBot {
                    VStack(spacing: 6) {
                        Text(bot.avatarEmoji)
                            .font(.scaled(36))
                        Text(bot.name)
                            .font(.headline)
                        Text("@\(bot.username)")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }

                    // Token with copy button
                    VStack(spacing: 8) {
                        Text("bot.token_label".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)

                        HStack {
                            Text(bot.token)
                                .font(.system(.caption2, design: .monospaced))
                                .foregroundColor(brandColor)
                                .lineLimit(1)

                            Spacer()

                            Button {
                                // __CLIPBOARD_HYGIENE_2026_10_01__ a bot token is a credential
                                SecureClipboard.copy(bot.token)
                                let generator = UINotificationFeedbackGenerator()
                                generator.notificationOccurred(.success)
                                withAnimation { tokenCopied = true }
                                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { withAnimation { tokenCopied = false } }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: tokenCopied ? "checkmark" : "doc.on.doc")
                                    Text(tokenCopied ? "common.copied".localized : "common.copy".localized)
                                }
                                .font(.caption2.weight(.medium))
                                .foregroundColor(tokenCopied ? .green : brandColor)
                            }
                            .accessibilityLabel(tokenCopied ? "common.copied".localized : "a11y.bot.copy_token".localized)
                        }
                        .padding(10)
                        .background(brandColor.opacity(0.05))
                        .cornerRadius(10)

                        Text("bot.token_hint".localized)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 8)

                    // Quick Start Guide
                    VStack(alignment: .leading, spacing: 10) {
                        Text("bot.quickstart_title".localized)
                            .font(.subheadline.weight(.semibold))

                        quickStartRow(number: "1", icon: "bolt.fill", text: "bot.quickstart_step1".localized)
                        quickStartRow(number: "2", icon: "person.3.fill", text: "bot.quickstart_step2".localized)
                        quickStartRow(number: "3", icon: "paperplane.fill", text: "bot.quickstart_step3".localized)
                    }
                    .padding(14)
                    .oledGlassCard(RoundedRectangle(cornerRadius: 14), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
                    .cornerRadius(14)
                    .padding(.horizontal, 8)

                    // API Usage Examples
                    if let bot = createdBot {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("bot.api_title".localized)
                                .font(.subheadline.weight(.semibold))

                            Text("bot.api_description".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)

                            // curl example
                            VStack(alignment: .leading, spacing: 4) {
                                Text("curl")
                                    .font(.caption2.weight(.bold))
                                    .foregroundColor(brandColor)
                                Text("""
                                curl -X POST https://oshi-messenger.com/api/bot/send \\
                                  -H "Content-Type: application/json" \\
                                  -d '{"token":"\(bot.token)","groupId":"\(bot.assignedGroups.first?.uuidString ?? "<group-id>")","content":"Hello!"}'
                                """)
                                    .font(.scaled(10, design: .monospaced))
                                    .foregroundColor(.primary)
                                    .padding(8)
                                    .background(Color(.systemGray6))
                                    .cornerRadius(8)
                            }

                            // Python example
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Python")
                                    .font(.caption2.weight(.bold))
                                    .foregroundColor(brandColor)
                                Text("""
                                import requests
                                requests.post("https://oshi-messenger.com/api/bot/send", json={
                                    "token": "\(bot.token)",
                                    "groupId": "\(bot.assignedGroups.first?.uuidString ?? "<group-id>")",
                                    "content": "Hello from Python!"
                                })
                                """)
                                    .font(.scaled(10, design: .monospaced))
                                    .foregroundColor(.primary)
                                    .padding(8)
                                    .background(Color(.systemGray6))
                                    .cornerRadius(8)

                            // Group ID hint
                            if bot.assignedGroups.isEmpty {
                                HStack(spacing: 6) {
                                    Image(systemName: "info.circle")
                                        .foregroundColor(.orange)
                                        .font(.caption2)
                                    Text("bot.groupid_hint".localized)
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }

                            }
                        }
                        .padding(14)
                        .oledGlassCard(RoundedRectangle(cornerRadius: 14), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
                        .cornerRadius(14)
                        .padding(.horizontal, 8)
                    }
                }
            }
            .padding(.top, 8)
        }
    }

    private func quickStartRow(number: String, icon: String, text: String) -> some View {
        HStack(spacing: 10) {
            Text(number)
                .font(.caption.weight(.bold))
                .foregroundColor(.white)
                .frame(width: 22, height: 22)
                .background(brandColor)
                .clipShape(Circle())
            Image(systemName: icon)
                .font(.caption)
                .foregroundColor(brandColor)
                .frame(width: 20)
            Text(text)
                .font(.caption)
                .foregroundColor(.primary)
        }
    }

    // MARK: - Navigation Logic

    private var stepIndex: Int {
        switch step {
        case .name: return 0
        case .type: return 1
        case .description: return 2
        case .done: return 3
        }
    }

    private var canProceed: Bool {
        switch step {
        case .name: return !botName.trimmingCharacters(in: .whitespaces).isEmpty
        case .type: return true
        case .description: return true
        case .done: return true
        }
    }

    private var effectiveUsername: String {
        let uname = botUsername.trimmingCharacters(in: .whitespaces)
        if uname.isEmpty {
            return botName.lowercased().replacingOccurrences(of: " ", with: "_") + "_bot"
        }
        return uname
    }

    private func goNext() {
        switch step {
        case .name: step = .type
        case .type: step = .description
        case .description:
            let bot = botManager.createBot(
                name: botName.trimmingCharacters(in: .whitespaces),
                username: effectiveUsername,
                description: botDescription.trimmingCharacters(in: .whitespaces),
                type: selectedType,
                ownerPublicKey: identityManager.publicKey
            )
            var updated = bot
            updated.avatarEmoji = selectedEmoji
            botManager.updateBot(updated)
            botManager.registerBotWithServer(updated)
            createdBot = updated
            step = .done
        case .done: break
        }
    }

    private func goBack() {
        switch step {
        case .name: break
        case .type: step = .name
        case .description: step = .type
        case .done: break
        }
    }
}

// MARK: - Bot Detail View

struct BotDetailView: View {
    @State var bot: OSHIBot
    @StateObject private var botManager = BotManager.shared
    @State private var showAddTrigger = false
    @State private var showScriptEditor = false
    @State private var showDeleteConfirm = false
    @State private var showGroupPicker = false
    @State private var tokenCopied = false
    @State private var postMessageText = ""
    @State private var selectedPostGroup: UUID?
    @State private var messageSent = false
    @Environment(\.dismiss) private var dismiss

    private let brandColor = Theme.brand

    var body: some View {
        List {
            // Header
            Section {
                HStack(spacing: 16) {
                    Text(bot.avatarEmoji)
                        .font(.scaled(40))
                        .frame(width: 64, height: 64)
                        .background(brandColor.opacity(0.1))
                        .cornerRadius(16)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(bot.name)
                            .font(.title3.weight(.bold))
                        Text("@\(bot.username)")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        HStack(spacing: 6) {
                            Image(systemName: bot.type.icon)
                                .font(.caption2)
                            Text(bot.type.localizedKey.localized)
                                .font(.caption2)
                        }
                        .foregroundColor(brandColor)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(brandColor.opacity(0.1))
                        .cornerRadius(8)
                    }
                }
                .padding(.vertical, 4)
            }

            // Status
            Section {
                Toggle(isOn: Binding(
                    get: { bot.isEnabled },
                    set: { newValue in
                        bot.isEnabled = newValue
                        botManager.toggleBot(bot.id)
                    }
                )) {
                    Label("bot.enabled".localized, systemImage: "power")
                }
                .tint(brandColor)

                HStack {
                    Label("bot.messages_sent".localized, systemImage: "paperplane.fill")
                    Spacer()
                    Text("\(bot.messagesSent)")
                        .foregroundColor(.secondary)
                }

                if let lastActive = bot.lastActivity {
                    HStack {
                        Label("bot.last_active".localized, systemImage: "clock.fill")
                        Spacer()
                        Text(lastActive, style: .relative)
                            .foregroundColor(.secondary)
                    }
                }
            } header: {
                Text("bot.section.status".localized)
            }

            // Token & API
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("bot.token_label".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(bot.token)
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundColor(brandColor)
                    }

                    Spacer()

                    Button {
                        // __CLIPBOARD_HYGIENE_2026_10_01__ a bot token is a credential
                        SecureClipboard.copy(bot.token)
                        let generator = UINotificationFeedbackGenerator()
                        generator.notificationOccurred(.success)
                        withAnimation { tokenCopied = true }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { withAnimation { tokenCopied = false } }
                    } label: {
                        Image(systemName: tokenCopied ? "checkmark.circle.fill" : "doc.on.doc")
                            .foregroundColor(tokenCopied ? .green : brandColor)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(tokenCopied ? "common.copied".localized : "a11y.bot.copy_token".localized)
                }

                Text("bot.token_hint".localized)
                    .font(.caption2)
                    .foregroundColor(.secondary)

                if bot.type == .webhook {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("bot.webhook_url".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("https://...", text: Binding(
                            get: { bot.webhookURL ?? "" },
                            set: { bot.webhookURL = $0.isEmpty ? nil : $0; botManager.updateBot(bot) }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                    }
                }

                // API Usage with group ID
                if !bot.assignedGroups.isEmpty {
                    let firstGroupId = bot.assignedGroups.first!.uuidString
                    VStack(alignment: .leading, spacing: 8) {
                        Text("bot.api_example_title".localized)
                            .font(.caption.weight(.semibold))

                        Text("""
                        curl -X POST https://oshi-messenger.com/api/bot/send \\
                          -H "Content-Type: application/json" \\
                          -d '{"token":"\(bot.token)","groupId":"\(firstGroupId)","content":"Hello!"}'
                        """)
                            .font(.scaled(9, design: .monospaced))
                            .foregroundColor(.primary)
                            .padding(8)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)

                        Button {
                            UIPasteboard.general.string = firstGroupId
                            let generator = UINotificationFeedbackGenerator()
                            generator.notificationOccurred(.success)
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "doc.on.doc")
                                Text("bot.copy_group_id".localized)
                            }
                            .font(.caption2.weight(.medium))
                            .foregroundColor(brandColor)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("bot.copy_group_id".localized)
                    }
                }

            } header: {
                Text("bot.section.api".localized)
            }

            // Triggers
            Section {
                ForEach(bot.triggers) { trigger in
                    HStack(spacing: 10) {
                        Image(systemName: trigger.type.icon)
                            .foregroundColor(brandColor)
                            .frame(width: 24)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(trigger.type.localizedKey.localized)
                                .font(.subheadline.weight(.medium))
                            Text(trigger.condition)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Circle()
                            .fill(trigger.isActive ? .green : .gray)
                            .frame(width: 8, height: 8)
                    }
                }
                .onDelete { indexSet in
                    bot.triggers.remove(atOffsets: indexSet)
                    botManager.updateBot(bot)
                }

                Button {
                    showAddTrigger = true
                } label: {
                    Label("bot.add_trigger".localized, systemImage: "plus.circle")
                        .foregroundColor(brandColor)
                }
            } header: {
                Text("bot.section.triggers".localized)
            }

            // Assigned Groups
            Section {
                ForEach(bot.assignedGroups, id: \.self) { groupId in
                    HStack {
                        Image(systemName: "person.3.fill")
                            .foregroundColor(brandColor)
                        Text(groupNameFor(groupId))
                            .font(.subheadline)
                        Spacer()
                        Button {
                            bot.assignedGroups.removeAll { $0 == groupId }
                            botManager.updateBot(bot)
                            botManager.registerBotWithServer(bot)
                        } label: {
                            Image(systemName: "minus.circle.fill")
                                .foregroundColor(.red)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("a11y.bot.remove_from_group".localized)
                    }
                }

                Button {
                    showGroupPicker = true
                } label: {
                    Label("bot.add_to_group".localized, systemImage: "plus.circle")
                        .foregroundColor(brandColor)
                }
            } header: {
                Text("bot.section.groups".localized)
            }

            // Post as Bot
            if !bot.assignedGroups.isEmpty {
                Section {
                    if bot.assignedGroups.count > 1 {
                        Picker("bot.post_to_group".localized, selection: Binding(
                            get: { selectedPostGroup ?? bot.assignedGroups.first ?? UUID() },
                            set: { selectedPostGroup = $0 }
                        )) {
                            ForEach(bot.assignedGroups, id: \.self) { groupId in
                                Text(groupNameFor(groupId)).tag(groupId)
                            }
                        }
                        .pickerStyle(.menu)
                    }

                    HStack {
                        TextField("bot.post_placeholder".localized, text: $postMessageText)
                            .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                            .textFieldStyle(.roundedBorder)

                        Button {
                            let targetGroup = selectedPostGroup ?? bot.assignedGroups.first!
                            botManager.postAsBot(bot.id, toGroup: targetGroup, content: postMessageText)
                            postMessageText = ""
                            messageSent = true
                            let generator = UINotificationFeedbackGenerator()
                            generator.notificationOccurred(.success)
                            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { withAnimation { messageSent = false } }
                        } label: {
                            Image(systemName: messageSent ? "checkmark.circle.fill" : "paperplane.fill")
                                .foregroundColor(postMessageText.isEmpty ? .secondary : brandColor)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("a11y.bot.send_message".localized)
                        .disabled(postMessageText.trimmingCharacters(in: .whitespaces).isEmpty)
                    }

                    if messageSent {
                        Text("bot.message_sent".localized)
                            .font(.caption)
                            .foregroundColor(.green)
                    }
                } header: {
                    Text("bot.section.post".localized)
                }
            }

            // Script
            if bot.scriptContent != nil {
                Section {
                    Button {
                        showScriptEditor = true
                    } label: {
                        Label("bot.edit_script".localized, systemImage: "chevron.left.forwardslash.chevron.right")
                            .foregroundColor(brandColor)
                    }
                } header: {
                    Text("bot.section.script".localized)
                }
            }

            // Permissions
            Section {
                Toggle("bot.perm.send".localized, isOn: Binding(
                    get: { bot.permissions.canSendMessages },
                    set: { bot.permissions.canSendMessages = $0; botManager.updateBot(bot) }
                ))
                Toggle("bot.perm.read".localized, isOn: Binding(
                    get: { bot.permissions.canReadMessages },
                    set: { bot.permissions.canReadMessages = $0; botManager.updateBot(bot) }
                ))
                Toggle("bot.perm.media".localized, isOn: Binding(
                    get: { bot.permissions.canSendMedia },
                    set: { bot.permissions.canSendMedia = $0; botManager.updateBot(bot) }
                ))
                Toggle("bot.perm.pin".localized, isOn: Binding(
                    get: { bot.permissions.canPinMessages },
                    set: { bot.permissions.canPinMessages = $0; botManager.updateBot(bot) }
                ))
                Toggle("bot.perm.members".localized, isOn: Binding(
                    get: { bot.permissions.canManageMembers },
                    set: { bot.permissions.canManageMembers = $0; botManager.updateBot(bot) }
                ))
            } header: {
                Text("bot.section.permissions".localized)
            }

            // Delete
            Section {
                Button(role: .destructive) {
                    showDeleteConfirm = true
                } label: {
                    Label("bot.delete".localized, systemImage: "trash.fill")
                        .foregroundColor(.red)
                }
            }
        }
        .navigationTitle(bot.name)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showAddTrigger) {
            AddTriggerView(bot: $bot)
        }
        .sheet(isPresented: $showScriptEditor) {
            ScriptEditorView(bot: $bot)
        }
        .alert("bot.delete_confirm".localized, isPresented: $showDeleteConfirm) {
            Button("bot.delete".localized, role: .destructive) {
                botManager.deleteBot(bot)
                dismiss()
            }
            Button("common.cancel".localized, role: .cancel) {}
        }
        .sheet(isPresented: $showGroupPicker) {
            BotGroupPickerView(bot: $bot)
        }
        .onAppear {
            if selectedPostGroup == nil, let first = bot.assignedGroups.first {
                selectedPostGroup = first
            }
        }
    }

    private func groupNameFor(_ groupId: UUID) -> String {
        MessageGroupManager.shared.groups.first(where: { $0.id == groupId })?.name
            ?? String(groupId.uuidString.prefix(8)) + "..."
    }
}

// MARK: - Bot Group Picker

struct BotGroupPickerView: View {
    @Binding var bot: OSHIBot
    @StateObject private var botManager = BotManager.shared
    @Environment(\.dismiss) private var dismiss

    private let brandColor = Theme.brand
    private var groups: [MessageGroup] { MessageGroupManager.shared.groups }

    var body: some View {
        NavigationView {
            List {
                if groups.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "person.3")
                            .font(.scaled(36))
                            .foregroundColor(.secondary)
                        Text("bot.no_groups_available".localized)
                            .font(.headline)
                            .foregroundColor(.secondary)
                        Text("bot.no_groups_hint".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                } else {
                    ForEach(groups) { group in
                        let alreadyAssigned = bot.assignedGroups.contains(group.id)
                        Button {
                            if !alreadyAssigned {
                                bot.assignedGroups.append(group.id)
                                botManager.updateBot(bot)
                                botManager.registerBotWithServer(bot)
                                dismiss()
                            }
                        } label: {
                            HStack(spacing: 12) {
                                // __ONE_AVATAR_NOT_THREE_2026_11_07__ keyed on
                                // the group id so this picker shows the same
                                // colour the group list does.
                                DefaultAvatar(key: group.id.uuidString,
                                              name: group.name, size: 40)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(group.name)
                                        .font(.subheadline.weight(.medium))
                                        .foregroundColor(.primary)
                                    Text("\(group.members.count) " + "bot.group_members".localized)
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }

                                Spacer()

                                if alreadyAssigned {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundColor(.green)
                                } else {
                                    Image(systemName: "plus.circle")
                                        .foregroundColor(brandColor)
                                }
                            }
                        }
                        .disabled(alreadyAssigned)
                    }
                }
            }
            .navigationTitle("bot.add_to_group".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
            }
        }
    }
}

// MARK: - Add Trigger View

struct AddTriggerView: View {
    @Binding var bot: OSHIBot
    @StateObject private var botManager = BotManager.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selectedType: BotTrigger.TriggerType = .command
    @State private var condition = ""
    @State private var response = ""

    private let brandColor = Theme.brand

    var body: some View {
        NavigationView {
            List {
                Section {
                    Picker("bot.trigger_type".localized, selection: $selectedType) {
                        ForEach(BotTrigger.TriggerType.allCases, id: \.rawValue) { type in
                            Label(type.localizedKey.localized, systemImage: type.icon)
                                .tag(type)
                        }
                    }
                } header: {
                    Text("bot.trigger_type".localized)
                }

                Section {
                    TextField(conditionPlaceholder, text: $condition)
                        .autocapitalization(.none)
                } header: {
                    Text("bot.trigger_condition".localized)
                } footer: {
                    Text(conditionHint)
                }

                Section {
                    TextEditor(text: $response)
                        .frame(minHeight: 80)
                } header: {
                    Text("bot.trigger_response".localized)
                } footer: {
                    Text("bot.trigger_variables".localized)
                }
            }
            .navigationTitle("bot.add_trigger".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save".localized) {
                        let trigger = BotTrigger(
                            id: UUID(),
                            type: selectedType,
                            condition: condition,
                            responseTemplate: response,
                            isActive: true
                        )
                        bot.triggers.append(trigger)
                        botManager.updateBot(bot)
                        dismiss()
                    }
                    .disabled(condition.isEmpty || response.isEmpty)
                }
            }
        }
    }

    private var conditionPlaceholder: String {
        switch selectedType {
        case .keyword: return "hello"
        case .command: return "/help"
        case .schedule: return "3600"
        case .memberJoin: return "member_join"
        case .memberLeave: return "member_leave"
        case .regex: return "\\d{4}"
        }
    }

    private var conditionHint: String {
        switch selectedType {
        case .keyword: return "bot.hint.keyword".localized
        case .command: return "bot.hint.command".localized
        case .schedule: return "bot.hint.schedule".localized
        case .memberJoin: return "bot.hint.member_join".localized
        case .memberLeave: return "bot.hint.member_leave".localized
        case .regex: return "bot.hint.regex".localized
        }
    }
}

// MARK: - Script Editor View

struct ScriptEditorView: View {
    @Binding var bot: OSHIBot
    @StateObject private var botManager = BotManager.shared
    @Environment(\.dismiss) private var dismiss
    @State private var scriptText: String = ""

    private let brandColor = Theme.brand

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Script editor
                TextEditor(text: $scriptText)
                    .font(.system(.body, design: .monospaced))
                    .padding(8)
                    .background(Color(.systemBackground))

                // Variables reference
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(["{{input}}", "{{bot_name}}", "{{date}}", "{{timestamp}}"], id: \.self) { variable in
                            Button {
                                scriptText += " \(variable)"
                            } label: {
                                Text(variable)
                                    .font(.system(.caption2, design: .monospaced))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(brandColor.opacity(0.1))
                                    .foregroundColor(brandColor)
                                    .cornerRadius(6)
                            }
                        }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                }
                .background(Color(.secondarySystemGroupedBackground))
            }
            .navigationTitle("bot.script_editor".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save".localized) {
                        bot.scriptContent = scriptText
                        botManager.updateBot(bot)
                        dismiss()
                    }
                }
            }
            .onAppear {
                scriptText = bot.scriptContent ?? ""
            }
        }
    }
}

// MARK: - Bot Management View (for Settings)

struct BotManagementView: View {
    @StateObject private var botManager = BotManager.shared
    @State private var showCreateBot = false

    private let brandColor = Theme.brand

    var body: some View {
        List {
            if botManager.bots.isEmpty {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "cpu")
                            .font(.scaled(36))
                            .foregroundColor(.secondary)
                        Text("bot.empty_title".localized)
                            .font(.headline)
                        Text("bot.empty_desc".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
                }
            }

            Section {
                ForEach(botManager.bots) { bot in
                    NavigationLink(destination: BotDetailView(bot: bot)) {
                        HStack(spacing: 12) {
                            Text(bot.avatarEmoji)
                                .font(.title3)
                                .frame(width: 36, height: 36)
                                .background(brandColor.opacity(0.1))
                                .cornerRadius(8)

                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 4) {
                                    Text(bot.name)
                                        .font(.subheadline.weight(.medium))
                                    if bot.isEnabled {
                                        Circle().fill(.green).frame(width: 6, height: 6)
                                    }
                                }
                                Text("@\(bot.username)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        botManager.deleteBot(botManager.bots[index])
                    }
                }
            } header: {
                if !botManager.bots.isEmpty {
                    Text("bot.your_bots".localized)
                }
            }

            Section {
                Button {
                    showCreateBot = true
                } label: {
                    Label("bot.create_new".localized, systemImage: "plus.circle.fill")
                        .foregroundColor(brandColor)
                }
            }
        }
        .navigationTitle("bot.management_title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showCreateBot) {
            BotCreationView(isPresented: $showCreateBot)
        }
    }
}

// MARK: - Inline keyboard  (__BOT_INLINE_KEYBOARD_2026_09_16__)

/// The rows of buttons a bot can hang under its own message.
///
/// Lives here rather than in `GroupViews` for the reason that file states about
/// itself twice: `GroupChatView` has already hit the type-checker budget, and
/// this is a self-contained subtree that the chat only has to instantiate.
///
/// Visual language is borrowed, not invented: the chat already paints bot
/// attribution in the group accent (`groupBrandColor`), so the buttons are the
/// same accent at bubble weight — a washed fill, a hairline, and the accent for
/// the label — sitting directly under the received bubble the way a reaction row
/// does.
struct BotInlineKeyboardView: View {
    let record: BotReplyMarkupRecord
    /// The LOCAL `GroupMessage.id`. Identifies the button slot on screen;
    /// `record.sourceMessageId` is what goes back to the server.
    let localMessageId: String
    /// `bot:<prefix>` — how the token is resolved, when this device holds it.
    let senderKey: String
    let tint: Color
    var maxWidth: CGFloat = 320

    @ObservedObject private var botManager = BotManager.shared

    private var botName: String { record.botName ?? "Bot" }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(record.keyboard.rows.enumerated()), id: \.offset) { rowIndex, row in
                HStack(spacing: 6) {
                    ForEach(Array(row.enumerated()), id: \.offset) { columnIndex, button in
                        buttonView(button, row: rowIndex, column: columnIndex)
                    }
                }
            }
        }
        .frame(maxWidth: maxWidth, alignment: .leading)
        .padding(.top, 2)
    }

    @ViewBuilder
    private func buttonView(_ button: BotInlineButton, row: Int, column: Int) -> some View {
        let inFlight = botManager.isCallbackInFlight(messageId: localMessageId, row: row, column: column)
        Button {
            tap(button, row: row, column: column)
        } label: {
            HStack(spacing: 5) {
                if inFlight {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.mini)
                        .tint(tint)
                } else {
                    Image(systemName: button.icon)
                        .font(.system(size: 10, weight: .semibold))
                        .opacity(0.75)
                }
                Text(button.text)
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .foregroundColor(tint)
            .frame(maxWidth: .infinity)
            .frame(height: 34)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(tint.opacity(0.12))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(tint.opacity(0.22), lineWidth: 0.5)
            )
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(inFlight)
        .opacity(inFlight ? 0.65 : 1)
        .accessibilityLabel(Text(button.text))
    }

    private func tap(_ button: BotInlineButton, row: Int, column: Int) {
        switch button.kind {
        case .callback:
            guard let data = button.callbackData else { return }
            botManager.tapCallbackButton(record: record,
                                         senderKey: senderKey,
                                         localMessageId: localMessageId,
                                         row: row,
                                         column: column,
                                         callbackData: data)
        case .url:
            guard let link = button.urlString else { return }
            botManager.tapURLButton(link, botName: botName)
        case .webApp:
            guard let link = button.webAppURL else { return }
            botManager.tapWebAppButton(record: record,
                                       senderKey: senderKey,
                                       fallbackURL: link,
                                       row: row,
                                       column: column,
                                       localMessageId: localMessageId)
        }
    }
}

// MARK: - Bot platform surfaces (toast, alert, web sheet)

/// The three things a bot button can produce that do NOT belong to the bubble
/// that was tapped: a banner, an alert, and a web sheet.
///
/// They hang off the CHAT, not off the row. A message bubble lives in a lazy
/// stack — scroll it off screen while its `.sheet` is open and SwiftUI is free to
/// tear the row down, taking the sheet with it. So the state sits on
/// `BotManager` and exactly one host presents it.
struct BotPlatformSurfaces: ViewModifier {
    @ObservedObject private var botManager = BotManager.shared

    func body(content: Content) -> some View {
        content
            .sheet(item: $botManager.botPresentation) { presentation in
                BotWebPresentationView(presentation: presentation)
            }
            .overlay(alignment: .top) {
                if let toast = botManager.botToast {
                    BotCallbackToast(payload: toast)
                        .padding(.horizontal, 14)
                        .padding(.top, 8)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.2), value: botManager.botToast)
            // A banner that never leaves is a banner the user has to fight, so it
            // times out — keyed on the payload so a second answer restarts the
            // clock instead of inheriting the first one's remaining time.
            .task(id: botManager.botToast?.id) {
                guard botManager.botToast != nil else { return }
                try? await Task.sleep(nanoseconds: 3_200_000_000)
                guard !Task.isCancelled else { return }
                botManager.botToast = nil
            }
            .alert(botManager.botAlert?.botName ?? "Bot",
                   isPresented: Binding(get: { botManager.botAlert != nil },
                                        set: { if !$0 { botManager.botAlert = nil } })) {
                Button("common.ok".localized, role: .cancel) { botManager.botAlert = nil }
            } message: {
                Text(botManager.botAlert?.text ?? "")
            }
    }
}

extension View {
    /// Apply once per screen that can show bot messages.
    func botPlatformSurfaces() -> some View { modifier(BotPlatformSurfaces()) }
}

/// The banner form of `bot_callback_answer` (`showAlert == false`).
struct BotCallbackToast: View {
    let payload: BotToastPayload

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "cpu")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(.white.opacity(0.9))
            VStack(alignment: .leading, spacing: 1) {
                Text(payload.botName)
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(.white.opacity(0.75))
                Text(payload.text)
                    .font(.footnote)
                    .foregroundColor(.white)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.black.opacity(0.82))
        )
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}

// MARK: - Web surfaces

/// Routes a tapped button to the right browser: a plain link goes to Safari, a
/// mini app goes to a `WKWebView` that can carry `initData`.
struct BotWebPresentationView: View {
    let presentation: BotWebPresentation

    var body: some View {
        if let initData = presentation.initData {
            BotMiniAppView(url: presentation.url,
                           initData: initData,
                           botName: presentation.botName)
        } else {
            BotSafariView(url: presentation.url)
                .ignoresSafeArea()
        }
    }
}

/// `url` buttons. `SFSafariViewController` and not a `WKWebView`: the page is
/// somebody else's website, and Safari's chrome is what tells the user so.
struct BotSafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let configuration = SFSafariViewController.Configuration()
        configuration.entersReaderIfAvailable = false
        let controller = SFSafariViewController(url: url, configuration: configuration)
        controller.dismissButtonStyle = .close
        return controller
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}

/// `web_app` buttons — an OSHI mini app.
///
/// `initData` reaches the page TWICE, on purpose:
///
///   1. in the URL fragment as `#oshiWebAppData=<percent-encoded>`, which is
///      where Telegram puts `tgWebAppData` and therefore where a ported page
///      looks first. A fragment is never sent to the server in the request line,
///      so a signed blob placed there does not land in someone's access log.
///   2. as `window.oshiWebAppData` / `window.OshiWebApp.initData`, injected at
///      DOCUMENT START — before any of the page's own script runs — plus a
///      `postMessage` and an `oshiWebAppReady` event once the load finishes, so a
///      bridge that registers a listener still gets told.
///
/// Both, because a page that reads only one of them must not be the thing that
/// decides whether the user is identified.
struct BotMiniAppView: View {
    let url: URL
    let initData: String
    let botName: String

    @Environment(\.dismiss) private var dismiss
    @State private var isLoading = true

    var body: some View {
        NavigationView {
            ZStack {
                BotMiniAppWebView(url: url, initData: initData, isLoading: $isLoading) {
                    dismiss()
                }
                .ignoresSafeArea(edges: .bottom)

                if isLoading {
                    VStack(spacing: 10) {
                        ProgressView()
                        Text("bot.miniapp.loading".localized)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle(botName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("bot.miniapp.close".localized) { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}

struct BotMiniAppWebView: UIViewRepresentable {
    let url: URL
    let initData: String
    @Binding var isLoading: Bool
    var onClose: () -> Void

    private static let bridgeName = "oshi"

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let controller = WKUserContentController()
        controller.add(context.coordinator, name: BotMiniAppWebView.bridgeName)
        controller.addUserScript(
            WKUserScript(source: BotMiniAppWebView.bootstrapScript(initData: initData),
                         injectionTime: .atDocumentStart,
                         forMainFrameOnly: true)
        )

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        // A mini app is a session, not a browser: it gets no access to whatever
        // cookies or storage another page on the same host left behind.
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsInlineMediaPlayback = true

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.load(URLRequest(url: BotMiniAppWebView.fragmentURL(base: url, initData: initData)))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        // `add(_:name:)` holds the handler STRONGLY; without this the coordinator,
        // the web view and the content controller keep each other alive for the
        // lifetime of the process, one leak per mini app the user ever opened.
        uiView.configuration.userContentController
            .removeScriptMessageHandler(forName: bridgeName)
        uiView.stopLoading()
        uiView.navigationDelegate = nil
    }

    /// `#oshiWebAppData=<initData>`, preserving any fragment the URL already had.
    static func fragmentURL(base: URL, initData: String) -> URL {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=+#?%")
        let encoded = initData.addingPercentEncoding(withAllowedCharacters: allowed) ?? initData
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return base }
        let entry = "oshiWebAppData=\(encoded)"
        if let existing = components.percentEncodedFragment, !existing.isEmpty {
            components.percentEncodedFragment = existing + "&" + entry
        } else {
            components.percentEncodedFragment = entry
        }
        return components.url ?? base
    }

    /// Injected at document start, before the page's own scripts.
    static func bootstrapScript(initData: String) -> String {
        let literal = jsStringLiteral(initData)
        return """
        (function () {
          window.oshiWebAppData = \(literal);
          // Tell the bridge what this host does NOT provide natively. The Mini App
          // chrome here is a title and a Close button — there is no native main
          // button and no native popup, so oshi-webapp.js must keep its DOM
          // fallbacks. Declared rather than left to the bridge's default: if a
          // native main button is ever added here and this stays false, the user
          // sees two buttons, and that is a visible bug rather than a silent one.
          window.oshiWebAppCapabilities = { mainButton: false, popups: false };
          var app = window.OshiWebApp || {};
          app.initData = window.oshiWebAppData;
          app.platform = "ios";
          app.version = "1.0";
          app.close = function () {
            try { window.webkit.messageHandlers.oshi.postMessage({ action: "close" }); } catch (e) {}
          };
          window.OshiWebApp = app;
        })();
        """
    }

    /// Fires once the document is there to receive it.
    static var handshakeScript: String {
        """
        (function () {
          var payload = { type: "oshi_webapp_init", initData: window.oshiWebAppData, platform: "ios" };
          try { window.postMessage(payload, "*"); } catch (e) {}
          try { window.dispatchEvent(new CustomEvent("oshiWebAppReady", { detail: payload })); } catch (e) {}
        })();
        """
    }

    /// Quote and escape a Swift string so it can be pasted into JS source.
    /// Hand-rolled escaping is how a quote or a newline in a signed blob becomes
    /// a syntax error — or worse, executable — so the JSON encoder does it.
    static func jsStringLiteral(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [value], options: []),
              let text = String(data: data, encoding: .utf8),
              text.count >= 2 else { return "\"\"" }
        return String(text.dropFirst().dropLast())
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        private let parent: BotMiniAppWebView

        init(_ parent: BotMiniAppWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            webView.evaluateJavaScript(BotMiniAppWebView.handshakeScript, completionHandler: nil)
            parent.isLoading = false
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            parent.isLoading = false
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.isLoading = false
        }

        func userContentController(_ userContentController: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard message.name == BotMiniAppWebView.bridgeName,
                  let body = message.body as? [String: Any],
                  let action = body["action"] as? String else { return }
            if action == "close" {
                DispatchQueue.main.async { self.parent.onClose() }
            }
        }
    }
}
