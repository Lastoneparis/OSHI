//
//  StrictSecurityModeView.swift
//  OSHI
//
//  __STRICT_SECURITY_MODE_2026_11_22__ Settings › Privacy (and › Security):
//  the switch, its two options, and the plain account of what it does and what
//  it cannot do. Logic lives in StrictSecurityMode.swift.
//

import SwiftUI

private let strictBrandColor = Theme.brand

/// The row shown at the top of Settings › Privacy and Settings › Security.
struct StrictSecurityModeEntrySection: View {
    @AppStorage(StrictSecurityMode.enabledKey) private var strict = false

    var body: some View {
        Section {
            NavigationLink(destination: StrictSecurityModeView()) {
                HStack(spacing: 12) {
                    Image(systemName: strict ? "lock.shield.fill" : "lock.shield")
                        .foregroundColor(strict ? .green : strictBrandColor)
                        .font(.title3)
                        .frame(width: 28)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("strict_title".localized)
                        Text("strict_subtitle".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 4)
                    Text((strict ? "strict_status_on" : "strict_status_off").localized)
                        .foregroundColor(.secondary)
                }
            }
            .accessibilityIdentifier("strict.entry")
        }
    }
}

/// Footer note for a toggle the mode is currently holding.
struct StrictLockedNote: View {
    var body: some View {
        Label("strict_locked_footer".localized, systemImage: "lock.fill")
            .font(.caption)
            .foregroundColor(.secondary)
    }
}

struct StrictSecurityModeView: View {
    @AppStorage(StrictSecurityMode.enabledKey) private var strict = false
    @AppStorage(StrictSecurityMode.blockPasteKey) private var blockPaste = true
    @AppStorage(StrictSecurityMode.hideReceiptsKey) private var hideReceipts = true
    @State private var showingExplainer = false
    @State private var confirmingEnable = false
    @State private var authFailed = false
    @State private var detected: [RemoteControlDetector.Tool] = []

    var body: some View {
        List {
            Section {
                Toggle(isOn: Binding(get: { strict }, set: { requestChange(to: $0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("strict_title".localized).font(.headline)
                        Text("strict_subtitle".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .tint(strictBrandColor)
                .accessibilityIdentifier("strict.toggle")
            } footer: {
                Text((strict ? "strict_footer_on" : "strict_footer_off").localized)
            }

            Section {
                Toggle(isOn: $blockPaste) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("strict_block_paste".localized)
                        Text("strict_block_paste_desc".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .tint(strictBrandColor)
                .accessibilityIdentifier("strict.blockPaste")
                Toggle(isOn: Binding(get: { hideReceipts },
                                     set: { StrictSecurityMode.updateHideReceipts($0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("strict_hide_receipts".localized)
                        Text("strict_hide_receipts_desc".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .tint(strictBrandColor)
                .accessibilityIdentifier("strict.hideReceipts")
            } header: {
                Text("strict_options_header".localized)
            }

            #if targetEnvironment(macCatalyst)
            if strict {
                Section {
                    if detected.isEmpty {
                        Text("strict_remote_none".localized)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(detected, id: \.self) { tool in
                            Label(tool.name, systemImage: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)
                        }
                    }
                } header: {
                    Text("strict_remote_section".localized)
                }
            }
            #endif

            Section {
                Button {
                    showingExplainer = true
                } label: {
                    Label("strict_learn_more".localized, systemImage: "info.circle")
                }
                .accessibilityIdentifier("strict.learnMore")
            }
        }
        .navigationTitle("strict_title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { detected = RemoteControlDetector.detectRunning() }
        .sheet(isPresented: $showingExplainer) {
            StrictSecurityExplainerView(onEnable: nil)
        }
        .sheet(isPresented: $confirmingEnable) {
            StrictSecurityExplainerView(onEnable: {
                confirmingEnable = false
                Task { await enable() }
            })
        }
        .alert("strict_title".localized, isPresented: $authFailed) {
            Button("common.ok".localized, role: .cancel) {}
        } message: {
            Text("strict_auth_failed".localized)
        }
    }

    /// ON goes through the explanation first; OFF asks for the device owner.
    private func requestChange(to on: Bool) {
        guard on != strict else { return }
        if on {
            confirmingEnable = true
        } else {
            Task { await disable() }
        }
    }

    @MainActor
    private func enable() async {
        if StrictSecurityMode.deviceAuthAvailable() {
            guard await StrictSecurityMode.authenticate(reason: "strict_auth_enable".localized) else { return }
            NotificationCenter.default.post(name: StrictSecurityMode.didAuthenticateNotification, object: nil)
        }
        StrictSecurityMode.turnOn()
        detected = RemoteControlDetector.detectRunning()
    }

    @MainActor
    private func disable() async {
        guard await StrictSecurityMode.authenticate(reason: "strict_auth_disable".localized) else {
            authFailed = true
            return
        }
        StrictSecurityMode.turnOff()
    }
}

/// What the mode does and what no app can do. Shown before turning it on (with
/// the "Turn on" button) and from "What strict mode does".
struct StrictSecurityExplainerView: View {
    @Environment(\.dismiss) private var dismiss
    let onEnable: (() -> Void)?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("strict_explainer_intro".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Section {
                    row("doc.on.doc", .green, "strict_does_copy")
                    row("doc.on.clipboard", .green, "strict_does_paste")
                    row("eye.slash.fill", .green, "strict_does_screen")
                    row("sparkles", .green, "strict_does_ai")
                    #if targetEnvironment(macCatalyst)
                    row("desktopcomputer", .green, "strict_does_mac")
                    #endif
                    row("keyboard.badge.ellipsis", .green, "strict_does_keyboard")
                    row("bell.slash.fill", .green, "strict_does_notifications")
                    row("faceid", .green, "strict_does_lock")
                    row("link", .green, "strict_does_network")
                    row("checkmark.message", .green, "strict_does_receipts")
                    row("photo.on.rectangle", .green, "strict_does_media")
                    row("arrow.uturn.backward.circle", .green, "strict_does_restore")
                } header: {
                    Text("strict_does_header".localized)
                }
                Section {
                    row("exclamationmark.triangle.fill", .orange, "strict_limits_malware")
                    row("camera.fill", .orange, "strict_limits_camera")
                    row("accessibility", .orange, "strict_limits_accessibility")
                    #if targetEnvironment(macCatalyst)
                    row("cursorarrow.motionlines", .orange, "strict_limits_mac_agents")
                    #endif
                    row("mic.fill", .orange, "strict_limits_system")
                } header: {
                    Text("strict_limits_header".localized)
                }
                if let onEnable {
                    Section {
                        Button {
                            onEnable()
                        } label: {
                            Text("strict_enable_button".localized)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(strictBrandColor)
                        .listRowBackground(Color.clear)
                        .accessibilityIdentifier("strict.confirmEnable")
                    }
                }
            }
            .navigationTitle("strict_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: onEnable == nil ? .confirmationAction : .cancellationAction) {
                    Button((onEnable == nil ? "common.done" : "common.cancel").localized) { dismiss() }
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
