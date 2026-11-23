//
//  IdentityVaultView.swift
//  OSHI
//
//  Identity vault UI: list, switch, import, delete identities.
//

import SwiftUI

struct IdentityVaultView: View {
    @EnvironmentObject var identityManager: IdentityManager
    @Environment(\.dismiss) var dismiss

    @State private var vault: [StoredIdentity] = []
    @State private var importError = false
    @State private var showQRScanner = false

    @State private var switchTarget: StoredIdentity?
    @State private var showSwitchConfirm = false

    @State private var deleteTarget: StoredIdentity?
    @State private var showDeleteConfirm = false
    @State private var showDeleteActiveWarning = false

    @State private var editingNickname: StoredIdentity?
    @State private var nicknameText = ""

    private let brandColor = Theme.brand

    var body: some View {
        NavigationView {
            List {
                // MARK: - Identities List
                if vault.isEmpty {
                    Section {
                        VStack(spacing: 12) {
                            Image(systemName: "person.crop.circle.badge.questionmark")
                                .font(.scaled(40))
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)
                            Text("vault.empty.title".localized)
                                .font(.headline)
                                .foregroundColor(.secondary)
                            Text("vault.empty.description".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 20)
                        .accessibilityElement(children: .combine)
                    }
                } else {
                    Section(header: Text("vault.section.saved".localized)) {
                        ForEach(vault) { identity in
                            identityRow(identity)
                        }
                    }
                }

                // MARK: - Import Section
                Section {
                    Button {
                        importError = false
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        showQRScanner = true
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "square.and.arrow.down")
                                .foregroundColor(brandColor)
                                .accessibilityHidden(true)
                            Text("vault.import".localized)
                                .foregroundColor(.primary)
                        }
                    }
                } footer: {
                    Text("vault.import.footer".localized)
                        .font(.caption)
                }
            }
            .navigationTitle("settings.identityVault.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) { dismiss() }
                }
            }
            .onAppear { reloadVault() }

            // MARK: - Switch Confirmation
            .alert("vault.switch.title".localized, isPresented: $showSwitchConfirm) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("vault.switch.action".localized, role: .destructive) {
                    if let target = switchTarget {
                        IdentityVaultManager.shared.switchToIdentity(target, identityManager: identityManager)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { reloadVault() }
                    }
                }
            } message: {
                if let target = switchTarget {
                    Text(String(format: "vault.switch.message".localized, target.nickname ?? truncatedKey(target.publicKey)))
                }
            }

            // MARK: - Delete Confirmation
            .alert("vault.delete.title".localized, isPresented: $showDeleteConfirm) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("common.delete".localized, role: .destructive) {
                    if let target = deleteTarget {
                        IdentityVaultManager.shared.deleteIdentity(id: target.id)
                        reloadVault()
                    }
                }
            } message: {
                Text("vault.delete.message".localized)
            }

            // MARK: - Delete Active Warning
            .alert("common.warning".localized, isPresented: $showDeleteActiveWarning) {
                Button("common.cancel".localized, role: .cancel) {}
                Button("vault.delete.anyway".localized, role: .destructive) {
                    if let target = deleteTarget {
                        IdentityVaultManager.shared.deleteIdentity(id: target.id)
                        reloadVault()
                    }
                }
            } message: {
                Text("vault.delete.active.message".localized)
            }

            // MARK: - QR Scanner / Import Sheet
            .sheet(isPresented: $showQRScanner) {
                QRScannerImportView { scannedKey in
                    if let _ = IdentityVaultManager.shared.importIdentity(recoveryKey: scannedKey) {
                        reloadVault()
                    } else {
                        importError = true
                    }
                }
            }

            // MARK: - Nickname Edit
            .alert("vault.nickname.title".localized, isPresented: .init(
                get: { editingNickname != nil },
                set: { if !$0 { editingNickname = nil } }
            )) {
                TextField("vault.nickname.placeholder".localized, text: $nicknameText)
                Button("common.cancel".localized, role: .cancel) { editingNickname = nil }
                Button("common.save".localized) {
                    if let target = editingNickname {
                        let name = nicknameText.trimmingCharacters(in: .whitespacesAndNewlines)
                        IdentityVaultManager.shared.updateNickname(id: target.id, nickname: name.isEmpty ? nil : name)
                        reloadVault()
                    }
                    editingNickname = nil
                }
            } message: {
                Text("vault.nickname.message".localized)
            }
        }
    }

    // MARK: - Identity Row

    private func identityRow(_ identity: StoredIdentity) -> some View {
        let isActive = identity.publicKey == identityManager.publicKey

        return HStack(spacing: 12) {
            // Identity icon
            ZStack {
                Circle()
                    .fill(isActive ? brandColor.opacity(0.15) : Color(.systemGray5))
                    .frame(width: 40, height: 40)
                Image(systemName: isActive ? "person.crop.circle.badge.checkmark" : "person.crop.circle")
                    .font(.scaled(18))
                    .foregroundColor(isActive ? brandColor : .secondary)
            }
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(identity.nickname ?? "vault.identity.default".localized)
                        .font(.subheadline)
                        .fontWeight(.medium)
                    if isActive {
                        Text("vault.active".localized)
                            .font(.caption2)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(brandColor)
                            .cornerRadius(4)
                    }
                }
                Text(truncatedKey(identity.publicKey))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .monospaced()
                Text(formatDate(identity.createdAt))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isActive ? .isSelected : [])
        .contextMenu {
            if !isActive {
                Button {
                    switchTarget = identity
                    showSwitchConfirm = true
                } label: {
                    Label("vault.switchTo".localized, systemImage: "arrow.triangle.2.circlepath")
                }
            }

            Button {
                nicknameText = identity.nickname ?? ""
                editingNickname = identity
            } label: {
                Label("vault.nickname.title".localized, systemImage: "pencil")
            }

            Button(role: .destructive) {
                deleteTarget = identity
                if isActive {
                    showDeleteActiveWarning = true
                } else {
                    showDeleteConfirm = true
                }
            } label: {
                Label("common.delete".localized, systemImage: "trash")
            }
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                deleteTarget = identity
                if isActive {
                    showDeleteActiveWarning = true
                } else {
                    showDeleteConfirm = true
                }
            } label: {
                Label("common.delete".localized, systemImage: "trash")
            }
        }
        .swipeActions(edge: .leading) {
            if !isActive {
                Button {
                    switchTarget = identity
                    showSwitchConfirm = true
                } label: {
                    Label("vault.switch.action".localized, systemImage: "arrow.triangle.2.circlepath")
                }
                .tint(brandColor)
            }
        }
    }

    // MARK: - Helpers

    private func reloadVault() {
        vault = IdentityVaultManager.shared.loadVault()
    }

    private func truncatedKey(_ key: String) -> String {
        guard key.count > 16 else { return key }
        return "\(key.prefix(8))...\(key.suffix(4))"
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
