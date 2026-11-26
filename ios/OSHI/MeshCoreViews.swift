//
//  MeshCoreViews.swift
//  OSHI — what a MeshCore conversation shows around its composer (__MESHCORE_INTEROP_2026_11_25__).
//
//  The decisions live in `MeshCoreUI` (MeshCoreRadio.swift) and are unit-tested; this file only draws them.
//

import SwiftUI

/// Above the composer of a MeshCore person or channel: who can read the thread, whether the radio can send
/// right now (with a way to fix it), and the character counter near the one-message limit.
struct MeshCoreThreadBanner: View {
    let conversationKey: String
    @ObservedObject var draft: ComposerDraft
    @ObservedObject private var radio = MeshCoreManager.shared
    @State private var showRadioSettings = false

    private var isChannel: Bool { MeshCoreInterop.isChannelKey(conversationKey) }

    private var radioNoteKey: String? {
        MeshCoreUI.radioNoteKey(key: conversationKey, state: radio.state, isPaired: MeshCoreManager.isPaired,
                                channelOnRadio: radio.channelSlot(forKey: conversationKey) != nil)
    }

    private var count: MeshCoreUI.ComposerCount {
        MeshCoreUI.composerCount(draft.text, isChannel: isChannel, selfName: radio.selfName)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: isChannel ? "number" : "antenna.radiowaves.left.and.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .accessibilityHidden(true)
                Text(MeshCoreUI.threadNoteKey(key: conversationKey,
                                              channel: radio.channel(forKey: conversationKey)).localized)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let note = radioNoteKey {
                HStack(spacing: 8) {
                    if radio.state == .connecting || radio.state == .configuring {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "exclamationmark.circle.fill")
                            .font(.caption)
                            .foregroundColor(.orange)
                            .accessibilityHidden(true)
                    }
                    Text(note.localized)
                        .font(.caption2.weight(.medium))
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 4)
                    Button("meshcore.radio.open".localized) { showRadioSettings = true }
                        .font(.caption2.weight(.semibold))
                        .buttonStyle(.borderless)
                }
            }
            if count.visible || count.tooLong {
                HStack(spacing: 6) {
                    if count.tooLong {
                        Text("meshcore.counter.tooLong".localized(with: String(MeshCoreInterop.maxParts)))
                            .foregroundColor(.red)
                    } else if count.parts > 1 {
                        Text("meshcore.counter.parts".localized(with: String(count.parts)))
                            .foregroundColor(.orange)
                    }
                    Spacer(minLength: 0)
                    Text("\(count.chars)/\(count.limit)")
                        .monospacedDigit()
                        .foregroundColor(count.chars > count.limit ? .orange : .secondary)
                }
                .font(.caption2)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(radioNoteKey == nil ? Color.secondary.opacity(0.08) : Color.orange.opacity(0.12))
        #if DEBUG
        // Screenshot capture: `-OSHIMeshCoreDraft "<text>"` puts text in the composer to show the counter.
        .onAppear {
            if draft.text.isEmpty, let t = UserDefaults.standard.string(forKey: "OSHIMeshCoreDraft") { draft.text = t }
        }
        #endif
        .sheet(isPresented: $showRadioSettings) {
            NavigationStack {
                MeshtasticSettingsView()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("done".localized) { showRadioSettings = false }
                        }
                    }
            }
        }
    }
}

#if DEBUG
/// Screenshot capture of the MeshCore screens (`-OSHIScreens -OSHIMeshCoreDemo dm|channel|settings`, with
/// `-OSHIMeshCoreRadio ready|off|pin|pairing|scan|announced`). Seeds a radio without Bluetooth and a DM + a
/// channel conversation built by the same code the radio path uses. DEBUG only.
enum MeshCoreDemo {
    static var screen: String? { UserDefaults.standard.string(forKey: "OSHIMeshCoreDemo") }

    static let leaKey = Data((0..<32).map { UInt8(truncatingIfNeeded: 0xA1 &+ $0 &* 7) })
    static var dmThread: String { MeshCoreInterop.contactKey(publicKey: leaKey) }
    static let channelThread = "mcchan!Public"

    /// Returns the conversation to open (nil for the settings screen).
    @MainActor
    static func seed(identity: IdentityManager) -> Conversation? {
        let radio = MeshCoreManager.shared
        let contacts = [
            MeshCore.Contact(publicKey: leaKey, type: 1, name: "Léa 🏔", lastAdvert: UInt32(Date().timeIntervalSince1970 - 240)),
            MeshCore.Contact(publicKey: Data(repeating: 0x3C, count: 32), type: 1, name: "Marco T-Deck",
                             lastAdvert: UInt32(Date().timeIntervalSince1970 - 3_900)),
            MeshCore.Contact(publicKey: Data(repeating: 0x5E, count: 32), type: 1, name: "Refuge Bellevue", lastAdvert: 0),
            MeshCore.Contact(publicKey: Data(repeating: 0x77, count: 32), type: 2, name: "Repeater Col", lastAdvert: 0),
        ]
        let channels = [
            MeshCore.ChannelInfo(idx: 0, name: "Public", secret: MeshCore.publicChannelSecret),
            MeshCore.ChannelInfo(idx: 1, name: OSHIBridgeWire.channelName, secret: OSHIBridgeWire.channelSecret),
            MeshCore.ChannelInfo(idx: 2, name: "#alps", secret: MeshCoreInterop.hashtagSecret("#alps")),
            MeshCore.ChannelInfo(idx: 3, name: "Team", secret: Data(repeating: 9, count: 16)),
        ]
        radio.seedDemo(phase: UserDefaults.standard.string(forKey: "OSHIMeshCoreRadio") ?? "ready",
                       name: "MeshCore-OSHI-TEA-MC", channels: channels, contacts: contacts)
        guard let mm = MessageManager.shared else { return nil }
        let me = identity.publicKey
        let now = Date()
        func incoming(_ prefix: Data?, channel: Int?, _ text: String, _ ago: TimeInterval) {
            let t = MeshCore.IncomingText(source: channel.map { .channel(idx: $0) } ?? .contact(prefix: prefix!),
                                          txtType: 0, timestamp: UInt32(now.timeIntervalSince1970 - ago), text: text)
            guard var m = MeshCoreManager.interopMessage(for: t, contacts: contacts, channels: channels, bridgeIdx: 1,
                                                         myKey: me, now: now.addingTimeInterval(-ago)) else { return }
            m.isRead = true
            mm.receiveMessage(m, suppressNotification: true)
        }
        func outgoing(_ key: String, _ text: String, _ status: DeliveryStatus, _ ago: TimeInterval) {
            let ts = now.addingTimeInterval(-ago)
            mm.addMessage(SecureMessage(
                id: "mcdemo-\(key.suffix(6))-\(Int(ago))", senderAddress: me, recipientAddress: key,
                encryptedContent: EncryptedMessage(ciphertext: "", signature: MeshCoreInterop.signature,
                                                   senderPublicKey: me, timestamp: ts),
                timestamp: ts, isRead: true, deliveryStatus: status, senderPublicKey: me, recipientPublicKey: key,
                plaintextContent: text, deliveryMethod: .loraPlaintext))
        }
        let lea = leaKey.prefix(6)
        incoming(lea, channel: nil, "Hi! Are you on the ridge tonight?", 900)
        outgoing(dmThread, "Yes, leaving the hut at 6. No phone signal up here, only the radio.", .delivered, 840)
        incoming(lea, channel: nil, "Perfect. Ping me when you reach the col 👍", 780)
        outgoing(dmThread, "Will do. The forecast says wind from 9 pm, so we may turn back early if the gusts pick up on the last section before the summit.", .delivered, 300)
        outgoing(dmThread, "Can you check the north path for snow?", .failed, 120)
        incoming(nil, channel: 0, "Marco T-Deck: Anyone near Refuge Bellevue? Repeater on the col is back up.", 1500)
        incoming(nil, channel: 0, "Refuge Bellevue: Yes, we hear you 5/5. Soup is on 🍲", 1200)
        outgoing(channelThread, "Hello from OSHI 👋 heading up from the valley.", .sent, 600)
        incoming(nil, channel: 0, "Marco T-Deck: Welcome! Hashtag #alps for the hike group.", 500)
        mm.refreshConversations()
        switch screen {
        case "dm": return mm.conversations.first { $0.participantPublicKey == dmThread }
        case "channel": return mm.conversations.first { $0.participantPublicKey == channelThread }
        default: return nil
        }
    }
}
#endif
