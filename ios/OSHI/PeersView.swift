//
//  PeersView.swift
//  OSHI - Compact Nearby Peers View
//  ✅ REDESIGNED: Single screen layout, no scrolling needed
//

import SwiftUI
import MultipeerConnectivity

// MARK: - Brand Color
private let peerBrandColor = Theme.brand

struct PeersView: View {
    @EnvironmentObject var meshManager: MeshNetworkManager
    @Environment(\.colorScheme) var colorScheme
    @State private var showingInfo = false
    @State private var manuallyDisconnectedPeers: Set<String> = []
    @State private var autoReconnectEnabled = true
    @State private var lastDiscoveredPeers: Set<MCPeerID> = []
    @State private var isScanning = false
    
    var body: some View {
        PeersContentView(
            meshManager: meshManager,
            showingInfo: $showingInfo,
            manuallyDisconnectedPeers: $manuallyDisconnectedPeers,
            autoReconnectEnabled: $autoReconnectEnabled,
            isScanning: $isScanning
        )
    }
}

// Separate content view to help type checker
private struct PeersContentView: View {
    @ObservedObject var meshManager: MeshNetworkManager
    @ObservedObject var crossPlatformMesh = CrossPlatformMesh.shared
    @Binding var showingInfo: Bool
    @Binding var manuallyDisconnectedPeers: Set<String>
    @Binding var autoReconnectEnabled: Bool
    @Binding var isScanning: Bool

    /// Whether a LoRa radio has ever been paired on this phone.
    ///
    /// Read from UserDefaults rather than from `MeshtasticManager.shared` ON
    /// PURPOSE: that singleton's init builds a `CBCentralManager`, which is what
    /// raises the Bluetooth permission prompt. Merely opening Nearby must not
    /// cost that to the overwhelming majority of users who own no radio — the
    /// same rule the app launch path follows (SecureWeb3MessengerApp.swift,
    /// "only wake the manager when this key exists"). So the LoRa card is not
    /// even constructed until the key is there, and `MeshtasticManager.shared`
    /// is touched only inside it.
    @State private var loRaPaired = UserDefaults.standard.string(forKey: "oshi.meshtastic.peripheralId") != nil

    /// __THE_CONNECT_BUTTON_WAS_REMOVED_INSTEAD_OF_FIXED_2026_11_07__ What the
    /// last press on Connect did. A button that runs code and says nothing is
    /// indistinguishable from the dead one this replaces.
    @State private var connectNotice: String?

    /// The identity two sightings of one phone must agree on before they are
    /// merged: its public key, or — only when it has not sent one yet — its name.
    ///
    /// __NEARBY_LISTED_EVERY_PHONE_TWICE_2026_11_02__ This used to be the lowercased
    /// display name alone, which is wrong in both directions: two people who both
    /// called themselves "Lou" collapsed into one row (one of them simply vanished
    /// from Nearby), while one phone seen under two names stayed doubled. The
    /// public key is the only thing that actually identifies a peer, and
    /// `CrossPlatformPeer` has carried it all along.
    private static func identity(of peer: CrossPlatformPeer) -> String {
        NearbyPeerCensus.identity(of: peer)
    }

    // Deduplicated cross-platform peers (safety net against dual BLE+Bonjour discovery)
    private var uniqueConnectedCPPeers: [CrossPlatformPeer] {
        var seen = Set<String>()
        return crossPlatformMesh.connectedPeers.filter { seen.insert(Self.identity(of: $0)).inserted }
    }

    private var uniqueDiscoveredCPPeers: [CrossPlatformPeer] {
        // Everything the "connected" section above already draws, by the same
        // identity rule, so a peer never appears as both connected and nearby.
        var seen = Set(crossPlatformMesh.connectedPeers.map(Self.identity(of:)))
        return crossPlatformMesh.crossPlatformPeers.filter { seen.insert(Self.identity(of: $0)).inserted }
    }

    // MARK: - Cross-transport deduplication
    //
    // __NEARBY_LISTED_EVERY_PHONE_TWICE_2026_11_02__ Two transports discover the
    // same room: MultipeerConnectivity (`meshManager`) and the BLE/Bonjour
    // cross-platform mesh (`crossPlatformMesh`). Both run at once by design, so
    // an iPhone in range is normally found by BOTH — and the screen drew each
    // list in its own section with no dedup between them, so that one phone got
    // two rows and `totalConnectedCount` counted it twice.
    //
    // Matching the two on name cannot work and never could: an MPC
    // `displayName` is `UIDevice.current.name` + four hex digits ("iPhone de
    // Lou-9F3A"), a `CrossPlatformPeer.displayName` is the OSHI profile name
    // ("Lou"). The only thing both sides agree on is the OSHI public key, which
    // `MeshNetworkManager` has been recording all along (`peerIDToPublicKey`)
    // and now exposes.
    //
    // The cross-platform row is the one kept, because it is the richer one: it
    // carries the profile name the user recognises and the iOS/Android badge.
    // An MPC peer whose key is not yet known is NOT a proven duplicate, so it is
    // kept — showing a phone twice for a second is better than hiding it.

    /// Public keys already drawn by one of the two cross-platform sections.
    private var crossPlatformKeysOnScreen: Set<String> {
        var ids = Set(crossPlatformMesh.connectedPeers.map(NearbyPeerCensus.identity(of:)))
        ids.formUnion(crossPlatformMesh.crossPlatformPeers.map(NearbyPeerCensus.identity(of:)))
        return ids
    }

    /// MPC peers that the cross-platform list is not already showing.
    private func withoutCrossPlatformDuplicates(_ peers: [MCPeerID]) -> [MCPeerID] {
        let drawn = crossPlatformKeysOnScreen
        guard !drawn.isEmpty else { return peers }
        return peers.filter { peer in
            guard let key = meshManager.publicKey(for: peer) else { return true }
            return !drawn.contains(NearbyPeerCensus.identity(publicKey: key, displayName: peer.displayName))
        }
    }

    private var dedupedConnectedMCPeers: [MCPeerID] {
        withoutCrossPlatformDuplicates(meshManager.connectedPeers)
    }

    // Combined counts including cross-platform peers
    private var totalConnectedCount: Int {
        dedupedConnectedMCPeers.count + uniqueConnectedCPPeers.count
    }

    private var totalDiscoveredCount: Int {
        availableDiscoveredPeers.count + uniqueDiscoveredCPPeers.count
    }
    
    var body: some View {
        NavigationView {
            VStack(spacing: 12) {
                // Compact Status Header
                compactStatusHeader
                
                // Quick Controls Row
                quickControlsRow
                
                // Peers List (main content)
                peersListSection

                // LoRa radios — its own card, deliberately NOT merged into the
                // list above. A node on the mesh can be kilometres away and is
                // not something you "connect to" from here, so mixing it into
                // the phone rows would misread. Only built when a radio has been
                // paired; it draws nothing at all otherwise.
                if loRaPaired {
                    LoRaNodesCard()
                }

                Spacer(minLength: 0)
            }
            // Cap content width so cards stay centered and readable on iPad /
            // landscape rather than stretching edge-to-edge.
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .padding(.horizontal)
            .padding(.top, 8)
            .frame(maxWidth: .infinity)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("peers.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Nearby had no Refresh at all, and the three toggles below the
                // header all drive MultipeerConnectivity — nothing on this
                // screen could restart the browse that finds Android phones.
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        refreshPeers()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .foregroundColor(peerBrandColor)
                    }
                    .accessibilityLabel("common.refresh".localized)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingInfo = true
                    } label: {
                        Image(systemName: "info.circle")
                            .foregroundColor(peerBrandColor)
                    }
                    .accessibilityLabel("a11y.mesh_info".localized)
                    .accessibilityHint("a11y.mesh_info.hint".localized)
                }
            }
            .overlay(alignment: .bottom) {
                if let notice = connectNotice {
                    Text(notice)
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(Color.black.opacity(0.8)))
                        .padding(.bottom, 24)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .accessibilityAddTraits(.isStaticText)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: connectNotice)
            .task(id: connectNotice) {
                guard connectNotice != nil else { return }
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard !Task.isCancelled else { return }
                connectNotice = nil
            }
            .sheet(isPresented: $showingInfo) {
                MeshNetworkInfoView()
            }
            .onAppear {
                // Re-checked on every appearance so a radio paired in Settings
                // shows up the next time this tab is opened.
                loRaPaired = UserDefaults.standard.string(forKey: "oshi.meshtastic.peripheralId") != nil
            }
            .onChange(of: meshManager.discoveredPeers) { oldPeers, newPeers in
                handlePeerDiscovery(oldPeers: oldPeers, newPeers: newPeers)
            }
            .onChange(of: meshManager.connectedPeers) { oldConnected, newConnected in
                handleConnectionChanges(oldConnected: oldConnected, newConnected: newConnected)
            }
        }
    }
    
    // MARK: - Compact Status Header
    
    /// __STATUS_TEXT_WAS_A_RIBBON_2026_10_27__ Same shape as the Android bug a
    /// reporter screenshotted, fixed the same way so the two platforms keep
    /// matching. This was one HStack holding the icon, the status text and an
    /// unbounded pair of stat badges: at large Dynamic Type the badge labels
    /// ("Connecte", "A proximite") take the width they need, the Spacer
    /// collapses, and the status text is squeezed into a narrow column.
    /// SwiftUI compresses more gracefully than Compose does — it never produced
    /// the one-character-per-line ribbon seen on Android — but the weakness is
    /// the same one, so the badges move to their own row here too.
    private var compactStatusHeader: some View {
        VStack(alignment: .leading, spacing: 12) {
        HStack(spacing: 16) {
            // Status Icon
            ZStack {
                if meshManager.isAdvertising || meshManager.isBrowsing {
                    Circle()
                        .fill(peerBrandColor.opacity(0.2))
                        .frame(width: 56, height: 56)
                        .scaleEffect(isScanning ? 1.2 : 1.0)
                        .opacity(isScanning ? 0 : 0.5)
                        .animation(.easeInOut(duration: 1.5).repeatForever(autoreverses: false), value: isScanning)
                }
                
                Circle()
                    .fill(
                        LinearGradient(
                            colors: meshManager.isAdvertising || meshManager.isBrowsing ?
                                [peerBrandColor, peerBrandColor.opacity(0.7)] :
                                [.gray, .gray.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 50, height: 50)
                
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.scaled(22))
                    .foregroundColor(.white)
            }
            .onAppear { isScanning = true }
            
            // Status Text
            VStack(alignment: .leading, spacing: 2) {
                Text(statusTitle)
                    .font(.headline)
                    .fontWeight(.bold)

                Text(statusSubtitle)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .accessibilityElement(children: .combine)

            Spacer(minLength: 0)
        }

            // Stats (combined Nearby + CrossPlatform), on their own row so no
            // Dynamic Type size and no longer translation can starve the text.
            HStack(spacing: 12) {
                VStack(spacing: 2) {
                    Text("\(totalConnectedCount)")
                        .font(.title3)
                        .fontWeight(.bold)
                        .foregroundColor(.green)
                        .contentTransition(.numericText())
                    Text("peers.stats.connected".localized)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                .accessibilityElement(children: .combine)

                VStack(spacing: 2) {
                    Text("\(totalDiscoveredCount)")
                        .font(.title3)
                        .fontWeight(.bold)
                        .foregroundColor(.orange)
                        .contentTransition(.numericText())
                    Text("peers.stats.nearby".localized)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .animation(.easeInOut(duration: 0.25), value: totalConnectedCount)
            .animation(.easeInOut(duration: 0.25), value: totalDiscoveredCount)
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    private var statusTitle: String {
        if totalConnectedCount > 0 {
            return "peers.status.connected".localized
        } else if totalDiscoveredCount > 0 {
            return "peers.status.peersFound".localized
        } else {
            return "peers.status.searching".localized
        }
    }

    private var statusSubtitle: String {
        if !meshManager.isAdvertising && !meshManager.isBrowsing && !crossPlatformMesh.isRunning {
            return "peers.status.offline".localized
        } else if totalConnectedCount > 0 {
            let cpCount = uniqueConnectedCPPeers.count
            let mcCount = meshManager.connectedPeers.count
            if cpCount > 0 && mcCount > 0 {
                return String(format: "peers.status.connectedTo".localized, totalConnectedCount) + " (\(mcCount) iOS, \(cpCount) Android)"
            }
            return String(format: "peers.status.connectedTo".localized, totalConnectedCount)
        } else if crossPlatformMesh.isRunning {
            let diag = crossPlatformMesh.diagnostics
            return diag.isEmpty ? "Scanning via BLE & Bonjour..." : diag
        } else {
            return "peers.status.lookingForPeers".localized
        }
    }
    
    // MARK: - Quick Controls Row
    
    private var quickControlsRow: some View {
        HStack(spacing: 8) {
            // Visible Toggle
            CompactControlButton(
                icon: "eye",
                title: "peers.controls.visible".localized,
                isActive: meshManager.isAdvertising,
                color: .green
            ) {
                if meshManager.isAdvertising {
                    meshManager.stopAdvertising()
                } else {
                    meshManager.startAdvertising()
                }
            }
            
            // Scan Toggle
            CompactControlButton(
                icon: "magnifyingglass",
                title: "peers.controls.scan".localized,
                isActive: meshManager.isBrowsing,
                color: .orange
            ) {
                if meshManager.isBrowsing {
                    meshManager.stopBrowsing()
                } else {
                    meshManager.startBrowsing()
                }
            }
            
            // Auto Toggle
            CompactControlButton(
                icon: "arrow.triangle.2.circlepath",
                title: "peers.controls.auto".localized,
                isActive: autoReconnectEnabled,
                color: peerBrandColor
            ) {
                autoReconnectEnabled.toggle()
            }
        }
    }
    
    // MARK: - Peers List Section
    
    private var peersListSection: some View {
        VStack(spacing: 0) {
            // Section Header
            HStack {
                Image(systemName: "person.2.fill")
                    .foregroundColor(peerBrandColor)
                Text("peers.section.devices".localized)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Spacer()
                if meshManager.isBrowsing {
                    ProgressView()
                        .scaleEffect(0.7)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            
            // Peers List
            if totalConnectedCount == 0 && totalDiscoveredCount == 0 && manuallyDisconnectedPeers.isEmpty {
                // Empty State
                emptyStateView
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        // Connected Peers (MultipeerConnectivity, minus any the
                        // cross-platform section below already draws)
                        ForEach(dedupedConnectedMCPeers, id: \.self) { peer in
                            CompactPeerRow(
                                name: peer.displayName,
                                status: .connected,
                                platformBadge: nil,
                                action: { disconnectPeer(peer) }
                            )
                            Divider().padding(.leading, 56)
                        }

                        // Connected Peers (CrossPlatformMesh - Android peers, deduplicated)
                        ForEach(uniqueConnectedCPPeers, id: \.meshIdentity) { peer in
                            CompactPeerRow(
                                name: peer.displayName,
                                status: .connected,
                                platformBadge: peer.platform == "android" ? "Android" : "iOS"
                            )
                            Divider().padding(.leading, 56)
                        }

                        // Discovered Peers (MultipeerConnectivity)
                        ForEach(availableDiscoveredPeers, id: \.self) { peer in
                            CompactPeerRow(
                                name: peer.displayName,
                                status: .nearby,
                                platformBadge: nil,
                                action: { invitePeer(peer) }
                            )
                            Divider().padding(.leading, 56)
                        }

                        // Discovered Peers (CrossPlatformMesh - not yet connected, deduplicated)
                        //
                        // __THE_CONNECT_BUTTON_WAS_REMOVED_INSTEAD_OF_FIXED_2026_11_07__
                        // This row had no `action:` since 2026-08-18, when a
                        // button that did nothing was deleted rather than
                        // wired, on the premise that the transport "connects by
                        // itself on discovery". It does — once, on a Bonjour
                        // browse that reports each service a single time. A
                        // peer whose one dial failed had no way back, and this
                        // screen has no Refresh either, so there was nothing at
                        // all to press. `connectNow` is the public call that
                        // premise said did not exist.
                        ForEach(uniqueDiscoveredCPPeers, id: \.meshIdentity) { peer in
                            CompactPeerRow(
                                name: peer.displayName,
                                status: .nearby,
                                platformBadge: peer.platform == "android" ? "Android" : "iOS",
                                action: { connectCrossPlatformPeer(peer) }
                            )
                            Divider().padding(.leading, 56)
                        }

                        // Disconnected Peers
                        ForEach(Array(manuallyDisconnectedPeers), id: \.self) { peerName in
                            CompactPeerRow(
                                name: peerName,
                                status: .disconnected,
                                platformBadge: nil,
                                action: { reconnectPeer(peerName) }
                            )
                            if peerName != manuallyDisconnectedPeers.sorted().last {
                                Divider().padding(.leading, 56)
                            }
                        }
                    }
                }
            }
        }
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    private var emptyStateView: some View {
        VStack(spacing: 8) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                .font(.scaled(36))
                .foregroundColor(.secondary.opacity(0.5))
            
            Text("peers.empty.title".localized)
                .font(.subheadline)
                .fontWeight(.medium)
            
            Text("peers.empty.subtitle".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            
            if !meshManager.isBrowsing {
                Button {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    meshManager.startBrowsing()
                    meshManager.startAdvertising()
                } label: {
                    Text("peers.empty.startButton".localized)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .frame(minHeight: 44)
                        .background(peerBrandColor)
                        .cornerRadius(16)
                }
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: 400)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .accessibilityElement(children: .contain)
    }
    
    private var availableDiscoveredPeers: [MCPeerID] {
        withoutCrossPlatformDuplicates(meshManager.discoveredPeers).filter { peer in
            !meshManager.connectedPeers.contains(where: { $0.displayName == peer.displayName }) &&
            !manuallyDisconnectedPeers.contains(peer.displayName)
        }
    }
    
    // MARK: - Actions
    
    private func disconnectPeer(_ peer: MCPeerID) {
        manuallyDisconnectedPeers.insert(peer.displayName)
        meshManager.disconnectPeer(peer)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
    
    private func reconnectPeer(_ peerName: String) {
        manuallyDisconnectedPeers.remove(peerName)
        if let peer = meshManager.discoveredPeers.first(where: { $0.displayName == peerName }) {
            invitePeer(peer)
        }
    }
    
    /// Connect to a nearby Android phone, because the user pressed Connect.
    private func connectCrossPlatformPeer(_ peer: CrossPlatformPeer) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        let key: String
        switch crossPlatformMesh.connectNow(publicKey: peer.publicKey) {
        case .alreadyConnected: key = "peers.connect.already"
        case .dialing: key = "peers.connect.dialing"
        case .searching, .notDiscovered: key = "peers.connect.searching"
        }
        connectNotice = String(format: key.localized, peer.displayName)
    }

    /// Restart BOTH lanes. `meshManager` is MultipeerConnectivity (iPhone to
    /// iPhone); `crossPlatformMesh` is the BLE/Bonjour/TCP bridge to Android,
    /// and only `restartDiscovery` makes an already-published peer announce
    /// itself again.
    private func refreshPeers() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        meshManager.startAdvertising()
        meshManager.startBrowsing()
        crossPlatformMesh.restartDiscovery(force: true)
    }

    private func invitePeer(_ peer: MCPeerID) {
        meshManager.invitePeer(peer, to: "ihsotahs")
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
    
    private func handlePeerDiscovery(oldPeers: [MCPeerID], newPeers: [MCPeerID]) {
        guard autoReconnectEnabled else { return }
        for peer in newPeers {
            if !oldPeers.contains(peer) &&
               !meshManager.connectedPeers.contains(peer) &&
               !manuallyDisconnectedPeers.contains(peer.displayName) {
                invitePeer(peer)
            }
        }
    }
    
    private func handleConnectionChanges(oldConnected: [MCPeerID], newConnected: [MCPeerID]) {
        for peer in newConnected {
            if manuallyDisconnectedPeers.contains(peer.displayName) {
                manuallyDisconnectedPeers.remove(peer.displayName)
            }
        }
    }
}

// MARK: - LoRa Nodes Card

/// One row per LoRa node, ready to draw. Built outside the view body: the
/// string work (signal, estimated range, hops, last heard) is exactly the kind
/// of expression that blows this file's view-builder type-check budget.
private struct LoRaRowModel: Identifiable {
    let id: UInt32
    let name: String
    /// True for the radio this phone is paired to over Bluetooth, false for a
    /// node that is merely audible on the mesh. The difference matters: one is
    /// in your pocket, the other can be over the horizon.
    let isPairedRadio: Bool
    let detail: String
    let seen: String?
}

/// Long-range LoRa nodes, shown alongside the Bluetooth/Wi-Fi peers.
///
/// Separate card, separate accent, radio glyph instead of an initials avatar,
/// and no Connect button — everything here says "this is a different kind of
/// thing from the phone in the next room".
///
/// Degrades to nothing: while the radio is off, out of range or still doing its
/// config handshake, `isReady` is false and the whole card returns an empty
/// body. No placeholder section, no spinner.
private struct LoRaNodesCard: View {
    @ObservedObject private var mesh = MeshtasticManager.shared
    /// Caps the node list so a busy mesh cannot push the page off screen.
    /// Scaled, so it grows with the user's text size instead of clipping rows.
    @ScaledMetric(relativeTo: .subheadline) private var listMaxHeight: CGFloat = 190

    var body: some View {
        if mesh.isReady {
            card
        }
    }

    private var card: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(spacing: 0) {
                    ForEach(rows) { row in
                        LoRaNodeRow(row: row)
                        if row.id != rows.last?.id {
                            Divider().padding(.leading, 56)
                        }
                    }
                }
            }
            .frame(maxHeight: listMaxHeight)

            Text("peers.lora.footer".localized)
                .font(.caption2)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 12)
        }
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }

    private var header: some View {
        HStack {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .foregroundColor(Theme.brandSecondary)
            Text("peers.lora.section".localized)
                .font(.subheadline)
                .fontWeight(.semibold)
            Spacer()
            Text("\(rows.count)")
                .font(.caption)
                .foregroundColor(.secondary)
                .contentTransition(.numericText())
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }

    // MARK: Rows

    private var rows: [LoRaRowModel] {
        var out: [LoRaRowModel] = []

        // The paired radio first. Its own NodeInfo can arrive after the config
        // handshake finishes, so fall back to the BLE name rather than leaving
        // the one node the user definitely owns off the list.
        let mine = mesh.nodes.first { $0.num == mesh.myNodeNum }
        let mineName = (mine?.longName).flatMap { $0.isEmpty ? nil : $0 }
            ?? mesh.displayName
            ?? "lora.node.unnamed".localized
        out.append(LoRaRowModel(id: mesh.myNodeNum,
                                name: mineName,
                                isPairedRadio: true,
                                // The node id, once the radio has told us what it
                                // is. Never "!00000000" — that is "not known yet",
                                // not an address.
                                detail: mesh.myNodeNum == 0 ? "" : String(format: "!%08x", mesh.myNodeNum),
                                seen: nil))

        // Then everything heard over the air, most recently heard first.
        let heard = mesh.nodes
            .filter { $0.num != mesh.myNodeNum }
            .sorted { $0.lastHeard > $1.lastHeard }
        for node in heard {
            out.append(LoRaRowModel(id: node.num,
                                    name: displayName(of: node),
                                    isPairedRadio: false,
                                    detail: signalDetail(of: node),
                                    seen: seenText(node.lastHeard)))
        }
        return out
    }

    private func displayName(of node: MeshtasticNode) -> String {
        if !node.longName.isEmpty { return node.longName }
        if !node.userId.isEmpty { return node.userId }
        return String(format: "!%08x", node.num)
    }

    /// Signal first, because that is what tells you whether the node is usable.
    /// Every part is omitted when the radio never reported it — no zeros
    /// standing in for "unknown".
    private func signalDetail(of node: MeshtasticNode) -> String {
        var parts: [String] = []

        // Range is an ESTIMATE from signal strength (most boards have no GPS),
        // and `estimatedRangeKm` already refuses to answer when the model stops
        // meaning anything. Shown as a band with a "~" for that reason.
        if let range = node.estimatedRangeKm {
            parts.append("~" + span(range.min, range.max))
        }
        if let rssi = node.rssi { parts.append("\(rssi) dBm") }
        if node.snr != 0 { parts.append(String(format: "SNR %.1f dB", node.snr)) }
        if let hops = node.hopsAway {
            parts.append(hops == 0 ? "meshtastic.direct".localized
                                   : String(format: "meshtastic.hops".localized, hops))
        }
        return parts.joined(separator: " · ")
    }

    /// "300–900 m" or "1.2–4.8 km" — the unit comes from the far end so the two
    /// halves never land in different units.
    private func span(_ lo: Double, _ hi: Double) -> String {
        if hi < 1 { return String(format: "%.0f–%.0f m", lo * 1000, hi * 1000) }
        return String(format: "%.1f–%.1f km", lo, hi)
    }

    private func seenText(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return String(format: "peers.lora.seen".localized,
                      formatter.localizedString(for: date, relativeTo: Date()))
    }
}

private struct LoRaNodeRow: View {
    let row: LoRaRowModel

    var body: some View {
        HStack(spacing: 12) {
            badge

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(row.name)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(1)

                    // Reads as a transport tag next to the name, the same slot
                    // the iOS/Android badge occupies on a phone row.
                    Text("LoRa")
                        .font(.scaled(9, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(Theme.brandSecondary)
                        .cornerRadius(4)
                }

                Text(row.isPairedRadio ? "peers.lora.connected".localized
                                       : "peers.lora.heard".localized)
                    .font(.caption2)
                    .foregroundColor(row.isPairedRadio ? .green : .secondary)

                if !row.detail.isEmpty || row.seen != nil {
                    Text([row.detail, row.seen].compactMap { $0 }
                            .filter { !$0.isEmpty }
                            .joined(separator: " · "))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
            }
            .accessibilityElement(children: .combine)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var badge: some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Theme.brandSecondary, Theme.brandSecondary.opacity(0.6)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: 40, height: 40)
                .overlay(
                    Image(systemName: row.isPairedRadio
                          ? "antenna.radiowaves.left.and.right"
                          : "dot.radiowaves.left.and.right")
                        .font(.scaled(16, weight: .semibold))
                        .foregroundColor(.white)
                )

            if row.isPairedRadio {
                Circle()
                    .fill(.green)
                    .frame(width: 12, height: 12)
                    .overlay(Circle().stroke(.white, lineWidth: 2))
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Compact Control Button

private struct CompactControlButton: View {
    let icon: String
    let title: String
    let isActive: Bool
    let color: Color
    let action: () -> Void
    
    var body: some View {
        Button {
            UISelectionFeedbackGenerator().selectionChanged()
            action()
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.scaled(18))
                    .foregroundColor(isActive ? .white : color)
                Text(title)
                    .font(.caption2)
                    .foregroundColor(isActive ? .white : .primary)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .padding(.vertical, 10)
            .background(isActive ? color : color.opacity(0.1))
            .cornerRadius(12)
            .animation(.easeInOut(duration: 0.2), value: isActive)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isActive ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: - Compact Peer Row

private enum PeerStatus {
    case connected, nearby, disconnected
}

private struct CompactPeerRow: View {
    let name: String
    let status: PeerStatus
    var platformBadge: String? = nil
    /// __PEER_ROW_NO_DEAD_BUTTON_2026_08_18__ OPTIONNEL : pas de bouton quand il
    /// n'y a vraiment rien à faire.
    ///
    /// La ligne affichait toujours un bouton — « Déconnecter » en rouge sur un
    /// pair connecté, « Connecter » sur un pair détecté. Pour les pairs
    /// CrossPlatformMesh (les appareils Android), les deux sites d'appel
    /// passaient `action: { }`: le bouton était donc peint, libellé, tapable, et
    /// ne faisait RIEN. Aucun changement d'état, aucun retour, aucune erreur.
    ///
    /// __CORRIGÉ LE 2026-11-07__ — la suite de ce commentaire disait : « il ne
    /// pouvait rien faire : `connectToPeer` et `cleanupConnection` sont privées
    /// dans `CrossPlatformMesh`, qui se connecte tout seul à la découverte. Il
    /// n'y a pas d'action utilisateur à offrir sur ce transport. »
    ///
    /// La prémisse était FAUSSE. Il se connecte tout seul **une seule fois** :
    /// `connectToPeer` n'est appelable que depuis la lecture BLE et depuis
    /// `netServiceDidResolveAddress`, et un `NetServiceBrowser` ne signale un
    /// service qu'une fois par parcours. Un pair dont l'unique appel TCP échoue
    /// n'a aucun recours. Supprimer un contrôle mort a donc laissé l'utilisateur
    /// SANS contrôle — la même erreur que de le renommer. `connectNow` est
    /// désormais publique : la ligne « à proximité » a de nouveau un bouton, et
    /// il fait quelque chose.
    ///
    /// Reste optionnel : une ligne CONNECTÉE côté cross-platform n'offre
    /// toujours pas de déconnexion manuelle, et n'affiche donc pas de bouton.
    var action: (() -> Void)? = nil
    
    private var statusColor: Color {
        switch status {
        case .connected: return .green
        case .nearby: return .orange
        case .disconnected: return .gray
        }
    }
    
    private var statusText: String {
        switch status {
        case .connected: return "peers.peer.connected".localized
        case .nearby: return "peers.peer.nearby".localized
        case .disconnected: return "peers.peer.disconnected".localized
        }
    }
    
    private var actionText: String {
        switch status {
        case .connected: return "peers.peer.disconnect".localized
        case .nearby: return "peers.peer.connect".localized
        case .disconnected: return "peers.peer.reconnect".localized
        }
    }
    
    var body: some View {
        HStack(spacing: 12) {
            // Avatar
            ZStack(alignment: .bottomTrailing) {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [statusColor, statusColor.opacity(0.6)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 40, height: 40)
                    .overlay(
                        Text(String(name.prefix(2)).uppercased())
                            .font(.scaled(14, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                    )
                
                if status == .connected {
                    Circle()
                        .fill(.green)
                        .frame(width: 12, height: 12)
                        .overlay(Circle().stroke(.white, lineWidth: 2))
                }
            }
            
            // Info
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(name)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(1)

                    if let badge = platformBadge {
                        Text(badge)
                            .font(.scaled(9, weight: .semibold))
                            .foregroundColor(badge == "Android" ? Color.green : Color.blue)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background((badge == "Android" ? Color.green : Color.blue).opacity(0.15))
                            .cornerRadius(4)
                    }
                }

                Text(statusText)
                    .font(.caption2)
                    .foregroundColor(statusColor)
            }
            .accessibilityElement(children: .combine)

            Spacer()

            // Action Button — seulement s'il y a une action.
            if let action {
                Button(action: action) {
                        Text(actionText)
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(status == .connected ? .red : peerBrandColor)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .frame(minHeight: 44)
                        .background(status == .connected ? Color.red.opacity(0.1) : peerBrandColor.opacity(0.1))
                        .cornerRadius(6)
                }
                .accessibilityLabel(actionText)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

// MARK: - Mesh Network Info View

struct MeshNetworkInfoView: View {
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            List {
                Section {
                    MeshInfoRow(title: "info.mesh.what_title".localized,
                           description: "info.mesh.what_description".localized)
                } header: {
                    Text("info.mesh.section.about".localized)
                }
                
                Section {
                    MeshInfoRow(title: "info.mesh.range_title".localized,
                           description: "info.mesh.range_description".localized)
                    MeshInfoRow(title: "info.mesh.connection_title".localized,
                           description: "info.mesh.connection_description".localized)
                    MeshInfoRow(title: "info.mesh.encryption_title".localized,
                           description: "info.mesh.encryption_description".localized)
                } header: {
                    Text("info.mesh.section.features".localized)
                }

                // Same disclaimer as the onboarding "How your messages travel" page, repeated
                // here because this sheet is where a user checks the mesh before depending on
                // it. States the three limits that the feature rows above do not: both devices
                // need OSHI open (no BLE background mode in the built plist; Multipeer stops on
                // resign-active), ~100 m direct and much less indoors, and a silent fallback to
                // the internet path when nobody is in range.
                Section {
                    MeshInfoRow(title: "info.mesh.disclaimer_title".localized,
                           description: "info.mesh.disclaimer_description".localized)
                }

                Section {
                    TipRow(number: 1, text: "info.mesh.tip1".localized)
                    TipRow(number: 2, text: "info.mesh.tip2".localized)
                    TipRow(number: 3, text: "info.mesh.tip3".localized)
                } header: {
                    Text("info.mesh.section.tips".localized)
                }
            }
            .navigationTitle("info.mesh.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) {
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct MeshInfoRow: View {
    let title: String
    let description: String
    
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.headline)
            Text(description)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }
}

private struct TipRow: View {
    let number: Int
    let text: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.caption)
                .fontWeight(.bold)
                .foregroundColor(.white)
                .frame(width: 20, height: 20)
                .background(peerBrandColor)
                .clipShape(Circle())
            
            Text(text)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - Preview

#Preview {
    PeersView()
        .environmentObject(MeshNetworkManager())
}
