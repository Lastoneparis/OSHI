//
//  MailBrowserPairingView.swift
//  OSHI
//
//  Letting a browser read this mailbox.
//
//  This screen performs the only operation that gives another device the ability to read
//  your mail, so it is deliberately explicit: you type a code you can see on the other
//  screen, you are told which mailbox is about to be shared and how old the request is,
//  and nothing happens until you tap Approve.
//
//  What actually crosses the network is the mail private key SEALED to an ephemeral key
//  that exists only inside that browser tab. The server relays it without being able to
//  open it, and hands it over exactly once.
//

import SwiftUI

struct MailBrowserPairingView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var identityManager: IdentityManager
    @StateObject private var mail = OSHIMailClient.shared

    @State private var code = ""
    @State private var pairing: OSHIMailClient.PairingRequest?
    @State private var sessions: [OSHIMailClient.WebSession] = []
    @State private var working = false
    @State private var error: String?
    @State private var approvedAddress: String?
    @State private var showScanner = false
    @State private var scannedPayload = ""

    private var cleanCode: String { code.uppercased().filter { !$0.isWhitespace } }

    var body: some View {
        Form {
                if let approvedAddress {
                    Section {
                        Label("mail.pair.connected".localized, systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Text(String(format: "mail.pair.canRead".localized, approvedAddress))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                } else if let pairing {
                    Section {
                        Text("mail.pair.asking".localized)
                            .font(.callout)
                        LabeledContent("Code", value: pairing.code)
                        LabeledContent("Requested", value: pairing.ageSeconds < 60
                            ? "\(pairing.ageSeconds)s ago"
                            : "\(pairing.ageSeconds / 60) min ago")
                        if let address = mail.account?.address {
                            LabeledContent("Mailbox", value: address)
                        }
                    } footer: {
                        Text("mail.pair.approveHint".localized)
                    }

                    Section {
                        Button {
                            Task { await approve() }
                        } label: {
                            if working { ProgressView() } else { Text("mail.pair.approve".localized) }
                        }
                        .disabled(working)

                        Button("common.cancel".localized, role: .cancel) { self.pairing = nil }
                    }
                } else {
                    Section {
                        Button {
                            showScanner = true
                        } label: {
                            Label(working ? "Connecting…" : "Scan a QR code", systemImage: "qrcode.viewfinder")
                        }
                        .disabled(working)
                    } header: {
                        Text("mail.browser.connect".localized)
                    } footer: {
                        Text("mail.pair.openHint".localized)
                    }

                    Section {
                        TextField("mail.pair.codePlaceholder".localized, text: $code)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .font(.title3.monospaced())
                        Button {
                            Task { await lookUp() }
                        } label: {
                            if working { ProgressView() } else { Text("common.continue".localized) }
                        }
                        .disabled(cleanCode.count < 8 || working)
                    } footer: {
                        Text("mail.pair.noCamera".localized)
                    }
                }

                if let error {
                    Section { Text(error).font(.footnote).foregroundStyle(.red) }
                }

                if !sessions.isEmpty {
                    Section {
                        ForEach(sessions) { session in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(session.label).font(.callout)
                                Text(String(format: "mail.pair.lastUsed".localized, String(session.lastSeenAt.prefix(16))))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .onDelete { indexSet in
                            Task {
                                for i in indexSet where i < sessions.count {
                                    try? await mail.revokeWebSession(id: sessions[i].id)
                                }
                                await loadSessions()
                            }
                        }

                        Button("mail.pair.signoutAll".localized, role: .destructive) {
                            Task {
                                try? await mail.revokeWebSession(id: "all")
                                await loadSessions()
                            }
                        }
                    } header: {
                        Text("mail.pair.connectedList".localized)
                    } footer: {
                        Text("mail.pair.signoutHint".localized)
                    }
                }
            }
            .navigationTitle("mail.pair.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("common.done".localized) { dismiss() } }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerView(scannedAddress: $scannedPayload)
            }
            .onChange(of: scannedPayload) { _, value in
                // The webmail QR encodes "OSHIMAIL:PAIR:<code>". Anything else is not a mail
                // pairing and is ignored, so scanning a contact's identity QR here does nothing.
                let prefix = "OSHIMAIL:PAIR:"
                guard value.hasPrefix(prefix) else { return }
                code = String(value.dropFirst(prefix.count))
                showScanner = false
                scannedPayload = ""
                Task { await lookUp() }
            }
        .task {
            mail.configure(identity: identityManager)
            await loadSessions()
        }
    }

    private func lookUp() async {
        working = true; error = nil
        defer { working = false }
        do {
            pairing = try await mail.inspectPairing(code: cleanCode)
        } catch {
            // The common cases are a typo and an expired code; say which.
            self.error = (error as? OSHIMailError).map {
                if case .server(_, let c) = $0, c == "no-such-pairing" {
                    return "No browser is waiting with that code. Check it, or refresh the page to get a new one."
                }
                return $0.localizedDescription
            } ?? error.localizedDescription
        }
    }

    private func approve() async {
        guard let pairing else { return }
        working = true; error = nil
        defer { working = false }
        do {
            approvedAddress = try await mail.approvePairing(pairing, label: deviceLabel())
            self.pairing = nil
            code = ""
            await loadSessions()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func loadSessions() async {
        sessions = (try? await mail.webSessions()) ?? []
    }

    /// Labels the session by the PHONE that approved it — the browser cannot be trusted to
    /// name itself, and this is what the user will recognise in the list later.
    private func deviceLabel() -> String {
        "Approved from \(UIDevice.current.name)"
    }
}
