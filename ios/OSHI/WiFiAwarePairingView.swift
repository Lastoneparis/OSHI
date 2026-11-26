//
//  WiFiAwarePairingView.swift
//  OSHI
//
//  __WIFI_AWARE_2026_11_25__ The one place the user meets Wi-Fi Aware: a
//  section on the contact card (ContactProfileView).
//
//    not paired → one button, "Pair for fast nearby transfers", + one line
//    pairing    → Apple's own picker / PIN flow (DeviceDiscoveryUI)
//    paired     → "Paired · Wi-Fi Aware" (or "Connected · …") and "Unpair"
//
//  Nothing else ever asks the user anything: once paired, the link connects
//  by itself whenever both phones are near each other, and when it cannot,
//  messages simply take the routes they always took.
//
//  `WiFiAwareContactSection` compiles on every platform; it renders NOTHING on
//  Mac Catalyst, iOS < 26, unsupported devices and builds without the feature
//  (see WiFiAwareCore.swift).
//

import SwiftUI
#if os(iOS) && !targetEnvironment(macCatalyst) && canImport(WiFiAware)
import WiFiAware
import DeviceDiscoveryUI
#endif

struct WiFiAwareContactSection: View {
    let publicKey: String
    let displayName: String

    var body: some View {
        #if os(iOS) && !targetEnvironment(macCatalyst) && canImport(WiFiAware)
        if #available(iOS 26.0, *) {
            WiFiAwareContactSectionImpl(publicKey: publicKey, displayName: displayName)
        }
        #endif
    }
}

#if os(iOS) && !targetEnvironment(macCatalyst) && canImport(WiFiAware)
@available(iOS 26.0, *)
private struct WiFiAwareContactSectionImpl: View {
    let publicKey: String
    let displayName: String

    @ObservedObject private var bridge = WiFiAwareBridge.shared
    @State private var showPairing = false

    var body: some View {
        let state = bridge.state(for: publicKey)
        if state != .unsupported {
            Section {
                if state.isPaired {
                    HStack(spacing: 10) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .foregroundColor(state == .connected ? .green : .secondary)
                            .accessibilityHidden(true)
                        Text(state == .connected
                             ? "wifi_aware.status_connected".localized
                             : "wifi_aware.status_paired".localized)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                    Button(role: .destructive) {
                        bridge.unpair(contact: publicKey)
                    } label: {
                        Text("wifi_aware.unpair".localized)
                    }
                } else {
                    Button {
                        showPairing = true
                    } label: {
                        Label("wifi_aware.pair_button".localized,
                              systemImage: "antenna.radiowaves.left.and.right")
                    }
                }
            } footer: {
                if !state.isPaired {
                    Text("wifi_aware.pair_footer".localized(with: displayName))
                }
            }
            .sheet(isPresented: $showPairing) {
                WiFiAwarePairingSheet(publicKey: publicKey, displayName: displayName)
            }
        }
    }
}

@available(iOS 26.0, *)
private struct WiFiAwarePairingSheet: View {
    let publicKey: String
    let displayName: String

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var bridge = WiFiAwareBridge.shared
    @State private var paired = false

    private var subscribable: WASubscribableService? { WiFiAwareLink.subscribableService }
    private var publishable: WAPublishableService? { WiFiAwareLink.publishableService }

    var body: some View {
        NavigationView {
            List {
                Section {
                    Text("wifi_aware.sheet_explainer".localized(with: displayName))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                if paired {
                    Section {
                        Label("wifi_aware.paired_done".localized, systemImage: "checkmark.circle.fill")
                            .foregroundColor(.green)
                    }
                } else if let subscribable, let publishable {
                    Section {
                        DevicePicker(
                            WASubscriberBrowser.wifiAware(.connecting(to: .userSpecifiedDevices, from: subscribable)),
                            onSelect: { endpoint in
                                bridge.didPair(contact: publicKey, device: endpoint.device.id)
                                finish()
                            },
                            label: {
                                Label("wifi_aware.find".localized(with: displayName),
                                      systemImage: "magnifyingglass")
                            },
                            fallback: {
                                Text("wifi_aware.unavailable".localized).foregroundColor(.secondary)
                            }
                        )
                        DevicePairingView(
                            WAPublisherListener.wifiAware(.connecting(to: publishable, from: .userSpecifiedDevices)),
                            label: {
                                Label("wifi_aware.be_visible".localized(with: displayName),
                                      systemImage: "dot.radiowaves.left.and.right")
                            },
                            fallback: {
                                Text("wifi_aware.unavailable".localized).foregroundColor(.secondary)
                            }
                        )
                    }
                } else {
                    Section {
                        Text("wifi_aware.unavailable".localized).foregroundColor(.secondary)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("wifi_aware.sheet_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done".localized) { dismiss() }
                }
            }
        }
        .onAppear {
            bridge.setPairingScreenVisible(true)
            bridge.beginPairing(contact: publicKey)
        }
        .onDisappear {
            bridge.setPairingScreenVisible(false)
        }
        .task { await watchForNewlyPairedDevice() }
    }

    private func finish() {
        paired = true
        Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            dismiss()
        }
    }

    /// The "Be visible" side gets no callback from Apple's pairing view, so it
    /// watches the paired-device list: a device that appears while this
    /// contact's sheet is open is the one the user just paired with them.
    /// (The link then still requires that device to PROVE it is this contact
    /// before anything is sent — see WiFiAwareLink.acceptVerified.)
    private func watchForNewlyPairedDevice() async {
        do {
            let before = Set(try await WAPairedDevice.allDevices.current()?.keys.map { $0 } ?? [])
            for try await devices in WAPairedDevice.allDevices {
                if Task.isCancelled { return }
                let fresh = Set(devices.keys).subtracting(before)
                let alreadyBound = bridge.store.pairedDeviceIDs
                if let device = fresh.first(where: { !alreadyBound.contains($0) }) {
                    if !bridge.store.isPaired(publicKey) {
                        bridge.didPair(contact: publicKey, device: device)
                    }
                    finish()
                    return
                }
            }
        } catch {
            // Silent by design: the picker path and the identity proof still work.
        }
    }
}
#endif
