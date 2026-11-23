//
//  MapTabIntegration.swift
//  OSHI - Map Tab Integration
//
//  MAP TAB INTEGRATION INSTRUCTIONS
//  ---------------------------------
//  In ContentView.swift, replace the Portal tab (tag 4) with the Map tab:
//
//  Change this block:
//
//      // Portal Tab (Bots, Channels, Apps)
//      PortalView()
//      .tabItem {
//          Label("tab_portal".localized, systemImage: "cpu.fill")
//      }
//      .tag(4)
//
//  To this:
//
//      // Map Tab (Offline Maps)
//      MapTabView()
//      .tabItem {
//          Label("Map", systemImage: "map.fill")
//      }
//      .tag(4)
//
//  Then move PortalView into SettingsView.swift as a NavigationLink:
//
//      NavigationLink {
//          PortalView()
//      } label: {
//          Label("tab_portal".localized, systemImage: "cpu.fill")
//      }
//
//  This keeps total tab count at 6 (Messages, Groups, New, Nearby, Map, Settings).
//

import SwiftUI

/// Wrapper for the Map tab — no NavigationStack here because
/// OfflineMapView manages its own overlays and sheets directly.
struct MapTabView: View {
    var body: some View {
        OfflineMapView()
    }
}
