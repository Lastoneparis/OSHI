//
//  PeersView.swift
//  FIXED: Simplified Toggle bindings to fix type-checking error
//

import SwiftUI
import MultipeerConnectivity

struct PeersView: View {
    @EnvironmentObject var meshManager: MeshNetworkManager
    @Environment(\.colorScheme) var colorScheme // ✅ Add for light mode support
    @State private var showingInfo = false
    
    // ✅ FIX: Extract computed properties for Toggle bindings
    private var advertisingBinding: Binding<Bool> {
        Binding(
            get: { meshManager.isAdvertising },
            set: { isOn in
                if isOn {
                    meshManager.startAdvertising()
                } else {
                    meshManager.stopAdvertising()
                }
            }
        )
    }
    
    private var browsingBinding: Binding<Bool> {
        Binding(
            get: { meshManager.isBrowsing },
            set: { isOn in
                if isOn {
                    meshManager.startBrowsing()
                } else {
                    meshManager.stopBrowsing()
                }
            }
        )
    }
    
    var body: some View {
        NavigationView {
            List {
                // Bluetooth Status Warning
                if !meshManager.isAdvertising && !meshManager.isBrowsing {
                    Section {
                        HStack {
                            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                                .foregroundColor(.orange)
                            VStack(alignment: .leading, spacing: 5) {
                                Text("Mesh Network Unavailable")
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                                Text("Make sure Bluetooth is enabled in Settings")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                        .padding(.vertical, 5)
                    }
                }
                
                // Connected Peers
                Section {
                    if meshManager.connectedPeers.isEmpty {
                        EmptyPeersRow(
                            icon: "wifi.slash",
                            text: "No connected peers"
                        )
                    } else {
                        ForEach(meshManager.connectedPeers, id: \.self) { peer in
                            PeerRow(peer: peer, isConnected: true)
                        }
                    }
                } header: {
                    Text("Connected Peers")
                } footer: {
                    Text("These devices are directly connected via Bluetooth or WiFi Direct")
                        .font(.caption)
                }
                
                // Discovered Peers
                Section {
                    if meshManager.discoveredPeers.isEmpty {
                        EmptyPeersRow(
                            icon: "magnifyingglass",
                            text: "No nearby peers found"
                        )
                    } else {
                        ForEach(meshManager.discoveredPeers, id: \.self) { peer in
                            if !meshManager.connectedPeers.contains(peer) {
                                Button {
                                    meshManager.invitePeer(peer)
                                } label: {
                                    PeerRow(peer: peer, isConnected: false)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Nearby Peers")
                } footer: {
                    Text("Tap to connect with a nearby peer")
                        .font(.caption)
                }
                
                // Network Settings
                Section {
                    // ✅ FIX: Use extracted binding properties
                    Toggle(isOn: advertisingBinding) {
                        Label("Make Discoverable", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .tint(.blue)
                    
                    Toggle(isOn: browsingBinding) {
                        Label("Discover Peers", systemImage: "magnifyingglass")
                    }
                    .tint(.blue)
                } header: {
                    Text("Network Settings")
                } footer: {
                    Text("Both settings should be enabled for optimal mesh networking")
                        .font(.caption)
                }
            }
            .navigationTitle("Nearby Peers")
            // ✅ Add navigation bar color scheme
            .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingInfo = true
                    } label: {
                        Image(systemName: "info.circle")
                            .foregroundColor(colorScheme == .dark ? .white : .primary)
                    }
                }
            }
            .sheet(isPresented: $showingInfo) {
                MeshNetworkInfoView()
            }
        }
    }
}

// ✅ Extract empty state to separate component
struct EmptyPeersRow: View {
    let icon: String
    let text: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundColor(colorScheme == .dark ? .orange : .orange)
            Text(text)
                .foregroundColor(.secondary)
        }
    }
}

struct PeerRow: View {
    let peer: MCPeerID
    let isConnected: Bool
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        HStack(spacing: 15) {
            // Status Icon
            statusIcon
            
            // Peer Info
            peerInfo
            
            Spacer()
            
            // Trailing Icon
            trailingIcon
        }
        .padding(.vertical, 5)
    }
    
    // ✅ Break up into computed properties
    private var statusIcon: some View {
        ZStack {
            Circle()
                .fill(isConnected ? Color.green : Color.blue)
                .frame(width: 40, height: 40)
            
            Image(systemName: isConnected ? "checkmark" : "antenna.radiowaves.left.and.right")
                .foregroundColor(.white)
                .font(.headline)
        }
    }
    
    private var peerInfo: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(peer.displayName)
                .font(.headline)
                .foregroundColor(colorScheme == .dark ? .white : .black)
                .lineLimit(1)
            
            Text(isConnected ? "Connected" : "Tap to connect")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
    
    private var trailingIcon: some View {
        Group {
            if isConnected {
                Image(systemName: "link")
                    .foregroundColor(.green)
            } else {
                Image(systemName: "chevron.right")
                    .foregroundColor(.secondary)
            }
        }
    }
}

struct MeshNetworkInfoView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 25) {
                    // Header
                    headerSection
                    
                    // Info Blocks
                    InfoBlock(
                        title: "What is Mesh Networking?",
                        description: "Mesh networking allows your device to communicate with nearby devices without internet. Messages can hop through multiple devices to reach their destination."
                    )
                    
                    InfoBlock(
                        title: "How It Works",
                        description: "Your device uses Bluetooth and WiFi Direct to connect with nearby peers. When you send a message, it can travel through the mesh network to reach the recipient even if they're not directly connected to you."
                    )
                    
                    InfoBlock(
                        title: "Privacy & Security",
                        description: "All messages are end-to-end encrypted. Even if a message hops through other devices, those devices cannot read the content. Only the intended recipient can decrypt the message."
                    )
                    
                    InfoBlock(
                        title: "Range & Connectivity",
                        description: "Bluetooth: ~30 feet (10m)\nWiFi Direct: ~200 feet (60m)\n\nThe more devices in the network, the farther your messages can reach!"
                    )
                    
                    InfoBlock(
                        title: "Best Practices",
                        description: "• Keep both 'Make Discoverable' and 'Discover Peers' enabled\n• Allow Bluetooth and Local Network permissions\n• Stay in range of other users for best results\n• Messages queue automatically when offline"
                    )
                }
                .padding()
            }
            .navigationTitle("Mesh Network")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(colorScheme == .dark ? .dark : .light, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
    
    // ✅ Extract header section
    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: "network")
                .font(.system(size: 60))
                .foregroundColor(.blue)
            
            Text("Mesh Networking")
                .font(.title)
                .fontWeight(.bold)
                .foregroundColor(colorScheme == .dark ? .white : .black)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.bottom, 10)
    }
}

struct InfoBlock: View {
    let title: String
    let description: String
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
                .foregroundColor(.blue)
            
            Text(description)
                .font(.subheadline)
                .foregroundColor(colorScheme == .dark ? .secondary : Color(.darkGray))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5))
        .cornerRadius(12)
    }
}

#Preview {
    PeersView()
        .environmentObject(MeshNetworkManager())
}
