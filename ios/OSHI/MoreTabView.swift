import SwiftUI

/// "More" tab — links to Nearby, Portal, and Settings.
/// Each item opens via fullScreenCover to avoid nested NavigationStack conflicts.
struct MoreTabView: View {
    let nearbyPeersCount: Int

    @State private var showNearby = false
    @State private var showPortal = false
    @State private var showSettings = false

    private let brandColor = Theme.brand

    var body: some View {
        NavigationStack {
            List {
                // Nearby Peers
                Button {
                    showNearby = true
                } label: {
                    HStack {
                        Label {
                            Text("tab_nearby".localized)
                        } icon: {
                            Image(systemName: "network")
                                .foregroundColor(brandColor)
                        }
                        Spacer()
                        if nearbyPeersCount > 0 {
                            Text("\(nearbyPeersCount)")
                                .font(.caption)
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(brandColor)
                                .clipShape(Capsule())
                        }
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .foregroundColor(.primary)
                .oledGlassListRow()  // __OLED_GLASS_2026_11_16__

                // Portal
                Button {
                    showPortal = true
                } label: {
                    HStack {
                        Label("tab_portal".localized, systemImage: "cpu.fill")
                            .foregroundColor(.primary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .foregroundColor(.primary)

                // Settings
                Button {
                    showSettings = true
                } label: {
                    HStack {
                        Label("tab_settings".localized, systemImage: "gear")
                            .foregroundColor(.primary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .foregroundColor(.primary)
            }
            .navigationTitle("tab_more".localized)
        }
        .fullScreenCover(isPresented: $showNearby) {
            NavigationStack {
                PeersView()
                    .toolbar {
                        ToolbarItem(placement: .navigationBarLeading) {
                            Button { showNearby = false } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.title3)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
            }
        }
        .fullScreenCover(isPresented: $showPortal) {
            NavigationStack {
                PortalView()
                    .toolbar {
                        ToolbarItem(placement: .navigationBarLeading) {
                            Button { showPortal = false } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.title3)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
            }
        }
        .fullScreenCover(isPresented: $showSettings) {
            SettingsView()
        }
    }
}
