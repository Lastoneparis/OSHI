//
//  ContentView.swift
//  FIXED: Tab preloading + Badges + LIGHT MODE VISIBILITY
//

import SwiftUI

struct ContentView: View {
    @EnvironmentObject var walletManager: WalletManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var groupManager: GroupManager
    @StateObject private var permissionHelper = PermissionHelper()
    
    var body: some View {
        Group {
            if walletManager.isConnected {
                MainTabView()
            } else {
                WalletConnectionView()
            }
        }
        .alert("Bluetooth is Off", isPresented: $permissionHelper.showBluetoothAlert) {
            Button("Open Settings") {
                permissionHelper.openSettings()
            }
            Button("Cancel", role: .cancel) {
                permissionHelper.showBluetoothAlert = false
            }
        } message: {
            Text("Please enable Bluetooth in Settings to discover nearby devices and send messages offline.")
        }
        .alert("Network Required", isPresented: $permissionHelper.showNetworkAlert) {
            Button("OK") {
                permissionHelper.showNetworkAlert = false
            }
        } message: {
            Text("Please enable WiFi for better connectivity with nearby devices.")
        }
    }
}

struct MainTabView: View {
    @EnvironmentObject var walletManager: WalletManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var groupManager: GroupManager
    
    // ✅ ADD: Color scheme detection for light mode fixes
    @Environment(\.colorScheme) var colorScheme
    
    // Track which tabs have been loaded
    @State private var loadedTabs: Set<Int> = [0] // Load Messages by default
    @State private var selectedTab = 0
    
    var body: some View {
        TabView(selection: $selectedTab) {
            // Messages Tab
            MessagesListView()
                .tabItem {
                    Label("Messages", systemImage: "message.fill")
                }
                .badge(totalUnreadCount)
                .tag(0)
            
            // Groups Tab
            Group {
                if loadedTabs.contains(1) {
                    GroupsListView()
                } else {
                    ProgressView()
                }
            }
            .tabItem {
                Label("Groups", systemImage: "person.3.fill")
            }
            .badge(unreadGroupsCount)
            .tag(1)
            
            // New Message Tab
            Group {
                if loadedTabs.contains(2) {
                    NewMessageView()
                } else {
                    Color.clear
                        .onAppear {
                            loadedTabs.insert(2)
                        }
                }
            }
            .tabItem {
                Label("New", systemImage: "square.and.pencil")
            }
            .tag(2)
            
            // Nearby Tab
            Group {
                if loadedTabs.contains(3) {
                    PeersView()
                } else {
                    ProgressView()
                }
            }
            .tabItem {
                Label("Nearby", systemImage: "network")
            }
            .badge(nearbyPeersCount)
            .tag(3)
            
            // Settings Tab
            Group {
                if loadedTabs.contains(4) {
                    SettingsView()
                } else {
                    ProgressView()
                }
            }
            .tabItem {
                Label("Settings", systemImage: "gear")
            }
            .tag(4)
        }
        // ✅ CRITICAL FIX: Configure tab bar appearance for light/dark mode
        .onAppear {
            configureTabBarAppearance()
            
            // Preload all tabs after 2 seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                loadedTabs = [0, 1, 2, 3, 4]
            }
        }
        // ✅ NEW: Update tab bar when color scheme changes
        .onChange(of: colorScheme) { _, _ in
            configureTabBarAppearance()
        }
        .onChange(of: selectedTab) { _, newTab in
            // Preload tab when selected
            loadedTabs.insert(newTab)
            
            // Also preload adjacent tabs
            loadedTabs.insert((newTab + 1) % 5)
            if newTab > 0 {
                loadedTabs.insert(newTab - 1)
            }
        }
    }
    
    // ✅ NEW: Configure tab bar for proper visibility in light mode
    private func configureTabBarAppearance() {
        let appearance = UITabBarAppearance()
        
        if colorScheme == .dark {
            // Dark mode - use default appearance
            appearance.configureWithDefaultBackground()
        } else {
            // ✅ Light mode - ensure icons/labels are visible
            appearance.configureWithDefaultBackground()
            appearance.backgroundColor = UIColor.systemBackground
            
            // ✅ Unselected state - dark gray for visibility
            appearance.stackedLayoutAppearance.normal.iconColor = UIColor.darkGray
            appearance.stackedLayoutAppearance.normal.titleTextAttributes = [
                .foregroundColor: UIColor.darkGray
            ]
            
            // ✅ Selected state - blue
            appearance.stackedLayoutAppearance.selected.iconColor = UIColor.systemBlue
            appearance.stackedLayoutAppearance.selected.titleTextAttributes = [
                .foregroundColor: UIColor.systemBlue
            ]
        }
        
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }
    
    // MARK: - Badge Counts
    
    /// Total unread messages count (returns 0 if none, which hides badge automatically)
    private var totalUnreadCount: Int {
        messageManager.conversations.reduce(0) { $0 + $1.unreadCount }
    }
    
    /// Unread groups count (returns 0 if none, which hides badge automatically)
    private var unreadGroupsCount: Int {
        groupManager.groups.filter { group in
            groupManager.getUnreadCount(for: group.id, myPublicKey: walletManager.publicKey) > 0
        }.count
    }
    
    /// Nearby connected peers count (returns 0 if none, which hides badge automatically)
    private var nearbyPeersCount: Int {
        meshManager.connectedPeers.count
    }
}

#Preview {
    ContentView()
        .environmentObject(WalletManager())
        .environmentObject(MeshNetworkManager())
        .environmentObject(MessageManager())
        .environmentObject(GroupManager())
}
