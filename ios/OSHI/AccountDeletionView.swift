//
//  AccountDeletionView.swift
//  OSHI — the in-app account deletion path.
//
//  App Store guideline 5.1.1(v) requires an app with account creation to offer
//  deletion from inside the app. The GDPR requires that it erase something.
//  Before this existed there was neither a route nor a screen: a user who
//  deleted the app left their X3DH prekey bundle, their encrypted sync archive,
//  any queued envelopes, any uncollected media and their push tokens on the
//  server permanently (docs/DATA_RETENTION_AUDIT.md category 16).
//
//  The screen is deliberately explicit about the one thing it CANNOT do —
//  reach into other people's devices — because a deletion flow that implies
//  otherwise is worse than none.
//

import SwiftUI

struct AccountDeletionView: View {
    @Environment(\.dismiss) private var dismiss
    // Injected, not a singleton: IdentityManager is an ObservableObject owned by
    // the app scene (`@StateObject` in SecureWeb3MessengerApp) and reached via
    // the environment everywhere else, including SettingsView. There is no
    // `.shared`, and creating a second instance here would erase a DIFFERENT
    // identity than the one on screen.
    @EnvironmentObject private var identityManager: IdentityManager

    /// Typed confirmation. A destructive, irreversible action that a single tap
    /// can trigger is the wrong shape — this is the same bar the App Store's own
    /// deletion guidance sets.
    @State private var typed: String = ""
    @State private var phase: Phase = .idle
    @State private var receipt: V2Client.AccountDeletion?
    @State private var failure: String?

    private enum Phase { case idle, deleting, done, failed }

    private let brandColor = Theme.brand

    /// The word the user must type. Localised, because "DELETE" is not a word in
    /// 33 of the languages this app ships in.
    private var confirmWord: String { "account.delete.confirm.word".localized }

    private var canDelete: Bool {
        phase == .idle
            && typed.trimmingCharacters(in: .whitespaces).uppercased() == confirmWord.uppercased()
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Image(systemName: "person.crop.circle.badge.xmark")
                        .font(.scaled(34))
                        .foregroundColor(.red)
                        .accessibilityHidden(true)
                    Text("account.delete.headline".localized)
                        .font(.headline)
                    Text("account.delete.body".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 6)
            }

            Section {
                bullet("key.fill", "account.delete.item.keys")
                bullet("arrow.triangle.2.circlepath", "account.delete.item.sync")
                bullet("tray.full.fill", "account.delete.item.queued")
                bullet("photo.on.rectangle.angled", "account.delete.item.media")
                bullet("bell.slash.fill", "account.delete.item.push")
                bullet("iphone", "account.delete.item.device")
            } header: {
                Text("account.delete.section.removed".localized)
            }

            Section {
                bullet("person.2.fill", "account.delete.keeps.recipients", tint: .orange)
                bullet("flag.fill", "account.delete.keeps.reports", tint: .orange)
            } header: {
                Text("account.delete.section.kept".localized)
            } footer: {
                Text("account.delete.section.kept.footer".localized).font(.caption2)
            }

            if phase == .done, let r = receipt {
                Section {
                    receiptView(r)
                } header: {
                    Text("account.delete.section.receipt".localized)
                }
            } else if phase == .failed {
                Section {
                    Text(failure ?? "account.delete.failed.generic".localized)
                        .font(.footnote)
                        .foregroundColor(.orange)
                }
            } else {
                Section {
                    TextField(confirmWord, text: $typed)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .disabled(phase == .deleting)
                    Button(role: .destructive) {
                        Task { await run() }
                    } label: {
                        HStack {
                            if phase == .deleting {
                                ProgressView().padding(.trailing, 6)
                            }
                            Text("account.delete.action".localized)
                        }
                    }
                    .disabled(!canDelete)
                } header: {
                    Text("account.delete.section.confirm".localized)
                } footer: {
                    Text(String(format: "account.delete.confirm.footer".localized, confirmWord))
                        .font(.caption2)
                }
            }
        }
        .navigationTitle("account.delete.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled(phase == .deleting)
    }

    private func bullet(_ icon: String, _ key: String, tint: Color = .secondary) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16))
                .foregroundColor(tint)
                .frame(width: 24)
                .accessibilityHidden(true)
            Text(key.localized)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private func receiptView(_ r: V2Client.AccountDeletion) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(r.complete ? "account.delete.done.complete".localized
                             : "account.delete.done.partial".localized,
                  systemImage: r.complete ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundColor(r.complete ? .green : .orange)
            // Counts, not adjectives: the user can see exactly what left the
            // server, including the number that did NOT.
            line("account.delete.receipt.keys", r.prekeysErased ? "1" : "0")
            line("account.delete.receipt.sync", "\(r.syncItems)")
            line("account.delete.receipt.queued", "\(r.relayEnvelopes)")
            line("account.delete.receipt.media", "\(r.inboundBlobs)")
            if r.outboundBlobsLeft > 0 {
                line("account.delete.receipt.outbound", "\(r.outboundBlobsLeft)")
            }
            if !r.unreachable.isEmpty {
                Text(String(format: "account.delete.receipt.unreachable".localized,
                            r.unreachable.joined(separator: ", ")))
                    .font(.caption)
                    .foregroundColor(.orange)
            }
            Button("account.delete.done.close".localized) { dismiss() }
                .buttonStyle(.borderedProminent)
                .tint(brandColor)
                .padding(.top, 4)
        }
        .padding(.vertical, 4)
    }

    private func line(_ key: String, _ value: String) -> some View {
        HStack {
            Text(key.localized).font(.footnote).foregroundColor(.secondary)
            Spacer()
            Text(value).font(.footnote.monospacedDigit())
        }
    }

    /// Server first, then the device.
    ///
    /// Order matters and is not interchangeable: the request has to be SIGNED by
    /// the identity being deleted, and wiping locally first destroys the signing
    /// key — leaving the server records permanently unreachable, by anyone,
    /// including their owner. So a server failure aborts before anything local
    /// is touched, and the user can retry.
    private func run() async {
        phase = .deleting
        failure = nil
        do {
            // Same construction as MessageManager+V2 and V2MessageRouter — the
            // client is cheap and holds no session state of its own.
            let r = try await V2Client().deleteAccount()
            receipt = r
            wipeLocalIdentity()
            phase = .done
        } catch {
            failure = String(format: "account.delete.failed.detail".localized,
                             String(describing: error))
            phase = .failed
        }
    }

    /// Erase this identity from the device, for real.
    ///
    /// `IdentityManager.disconnectWallet()` is not enough on its own: it ARCHIVES
    /// the identity's messages, groups, ratchet sessions, aliases and profile
    /// under per-identity UserDefaults keys so a later switch-back can restore
    /// them. That is right for switching identities and wrong for deleting an
    /// account, so the archives go too.
    private func wipeLocalIdentity() {
        let pubKey = identityManager.publicKey
        // Mirrors MessageManager.archiveMessagesForIdentity's key derivation.
        let safeKey = pubKey
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")

        identityManager.disconnectWallet()   // unregisters push, clears live state

        if !pubKey.isEmpty {
            let defaults = UserDefaults.standard
            // Anything keyed by this identity — archives written by
            // disconnectWallet a moment ago included.
            for key in defaults.dictionaryRepresentation().keys
            where key.contains(pubKey) || key.contains(safeKey) {
                defaults.removeObject(forKey: key)
            }
            // And drop it from the vault so it is not offered on the switch screen.
            for stored in IdentityVaultManager.shared.loadVault()
            where stored.publicKey == pubKey {
                IdentityVaultManager.shared.deleteIdentity(id: stored.id)
            }
        }
    }
}
