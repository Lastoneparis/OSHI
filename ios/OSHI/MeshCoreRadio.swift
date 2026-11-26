//
//  MeshCoreRadio.swift
//  OSHI — OSHI messages over a MeshCore radio, and the phone as a Meshtastic <-> MeshCore bridge.
//
//  A LoRa radio listens to one network at a time, and MeshCore is not Meshtastic (other radio settings,
//  other packets). So OSHI rides MeshCore the way oshi-meshcore-bridge does, byte for byte:
//
//    OMP DATA frame (OshiMeshRadio.swift)           the same frames as on Meshtastic, origin = our id
//      → "OB" envelope, split to ≤160 B              OSHIBridgeWire below == oshi_bridge/wire.py
//      → MeshCore group-channel datagram             CMD_SEND_CHANNEL_DATA, data_type 0xFF4F,
//                                                    channel "#oshi-bridge" (secret = SHA-256(name)[:16])
//
//  so a phone on a MeshCore radio talks to other such phones through MeshCore repeaters, and to the
//  Meshtastic side through any oshi-meshcore-bridge — or through a phone that holds one radio of each
//  (`LoRaCrossBridge`). MeshCore users without that channel never see the traffic.
//
//  Companion protocol: MeshCore examples/companion_radio/MyMesh.cpp (BLE Nordic UART, one frame per
//  write/notify, request/response strictly one at a time; PUSH codes ≥ 0x80 are unsolicited).
//

import Foundation
import CoreBluetooth
import CryptoKit
import Combine

// MARK: - MeshCore companion protocol

enum MeshCore {
    static let serviceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let rxUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")   // app → radio (write)
    static let txUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")   // radio → app (notify)

    static let cmdAppStart: UInt8 = 1
    static let cmdSyncNextMessage: UInt8 = 10
    static let cmdGetChannel: UInt8 = 31
    static let cmdSetChannel: UInt8 = 32
    static let cmdSendChannelData: UInt8 = 62

    static let respOK: UInt8 = 0
    static let respErr: UInt8 = 1
    static let respSelfInfo: UInt8 = 5
    static let respNoMoreMessages: UInt8 = 10
    static let respChannelInfo: UInt8 = 18
    static let respChannelDataRecv: UInt8 = 27
    static let pushMsgWaiting: UInt8 = 0x83

    /// MAX_FRAME_SIZE (176) - 9 in the current firmware.
    static let maxChannelData = 167
    static let channelSlots = 8
    static let pathFlood: UInt8 = 0xFF

    // __MESHCORE_INTEROP_2026_11_25__ Text with ordinary MeshCore users (MyMesh.cpp byte layouts).
    static let cmdSendTxtMsg: UInt8 = 2
    static let cmdSendChannelTxtMsg: UInt8 = 3
    static let cmdGetContacts: UInt8 = 4
    static let cmdSendSelfAdvert: UInt8 = 7
    /// Same layout as a contact frame (RESP_CONTACT / PUSH_NEW_ADVERT): adds or updates that table entry.
    static let cmdAddUpdateContact: UInt8 = 9
    /// code + key + type + flags + path len + path + name + last advert + lat + lon + lastmod.
    static let contactFrameBytes = 1 + 32 + 3 + 64 + 32 + 16

    static let respContactsStart: UInt8 = 2
    static let respContact: UInt8 = 3
    static let respEndOfContacts: UInt8 = 4
    static let respSent: UInt8 = 6
    static let respContactMsgRecv: UInt8 = 7
    static let respChannelMsgRecv: UInt8 = 8
    static let respContactMsgRecvV3: UInt8 = 16
    static let respChannelMsgRecvV3: UInt8 = 17

    static let pushAdvert: UInt8 = 0x80
    static let pushSendConfirmed: UInt8 = 0x82
    static let pushNewAdvert: UInt8 = 0x8A

    static let txtTypePlain: UInt8 = 0
    static let txtTypeCliData: UInt8 = 1
    static let txtTypeSignedPlain: UInt8 = 2

    /// MAX_TEXT_LEN (10 × CIPHER_BLOCK_SIZE). A channel message also carries "<node name>: " inside it.
    static let maxTextBytes = 160
    static let maxFrameSize = 176
    static let pubKeySize = 32
    static let maxPathSize = 64
    /// The "Public" channel every companion radio is born with (PUBLIC_GROUP_PSK, izOH6cXN6mrJ5e26oRXNcg==).
    static let publicChannelSecret = Data([0x8b, 0x33, 0x87, 0xe9, 0xc5, 0xcd, 0xea, 0x6a,
                                           0xc9, 0xe5, 0xed, 0xba, 0xa1, 0x15, 0xcd, 0x72])

    static func appStart(appName: String = "OSHI") -> Data {
        Data([cmdAppStart]) + Data(count: 7) + Data(appName.utf8)
    }

    static func getChannel(_ idx: Int) -> Data { Data([cmdGetChannel, UInt8(idx)]) }

    static func setChannel(_ idx: Int, name: String, secret: Data) -> Data {
        var n = Data(name.utf8.prefix(31))
        n.append(Data(count: 32 - n.count))
        return Data([cmdSetChannel, UInt8(idx)]) + n + secret.prefix(16)
    }

    static func sendChannelData(_ idx: Int, dataType: UInt16, payload: Data) -> Data {
        Data([cmdSendChannelData, UInt8(idx), pathFlood, UInt8(dataType & 0xFF), UInt8(dataType >> 8)]) + payload
    }

    static let syncNextMessage = Data([cmdSyncNextMessage])

    static func le32(_ v: UInt32) -> Data { Data([UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 24)]) }
    static func readLE32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) | UInt32(b[i + 1]) << 8 | UInt32(b[i + 2]) << 16 | UInt32(b[i + 3]) << 24
    }

    /// CMD_SEND_TXT_MSG: code, txt_type, attempt, timestamp (LE32), recipient public key prefix (6), text.
    /// The radio answers RESP_CODE_SENT with the ACK tag to wait for, or ERR NOT_FOUND for an unknown contact.
    static func sendText(attempt: UInt8, timestamp: UInt32, pubKeyPrefix: Data, text: String) -> Data {
        var prefix = Data(pubKeyPrefix.prefix(6))
        if prefix.count < 6 { prefix.append(Data(count: 6 - prefix.count)) }
        return Data([cmdSendTxtMsg, txtTypePlain, min(attempt, 3)]) + le32(timestamp) + prefix + Data(text.utf8)
    }

    /// CMD_SEND_CHANNEL_TXT_MSG: code, txt_type (0), channel idx, timestamp (LE32), text. Answered OK / ERR.
    static func sendChannelText(_ idx: Int, timestamp: UInt32, text: String) -> Data {
        Data([cmdSendChannelTxtMsg, txtTypePlain, UInt8(idx & 0xFF)]) + le32(timestamp) + Data(text.utf8)
    }

    /// CMD_GET_CONTACTS without `since`: CONTACTS_START, CONTACT × N, END_OF_CONTACTS.
    static let getContacts = Data([cmdGetContacts])

    /// CMD_SEND_SELF_ADVERT: announce this radio (name + key) so MeshCore users can add it and write to it.
    /// `flood` relays it through repeaters; otherwise only radios in direct range hear it. Answered OK / ERR.
    static func sendSelfAdvert(flood: Bool = true) -> Data { Data([cmdSendSelfAdvert, flood ? 1 : 0]) }

    /// CMD_ADD_UPDATE_CONTACT from a contact frame the radio pushed (PUSH_NEW_ADVERT, auto-add off): the
    /// firmware reads it with the same layout (MyMesh.cpp updateContactFromFrame), so the frame is replayed
    /// with the command code. Without it the radio answers a DM to that key ERR NOT_FOUND. Same as Android.
    static func addContact(from contactFrame: Data) -> Data? {
        guard contactFrame.count >= contactFrameBytes else { return nil }
        var d = Data(contactFrame)
        d[d.startIndex] = cmdAddUpdateContact
        return d
    }

    /// SELF_INFO's node name, which the radio puts in front of every channel message we send ("name: text").
    /// Offset 58 in the current layout (after radio params); nil on a frame too short to carry it.
    static func selfName(_ f: Data) -> String? {
        let b = [UInt8](f)
        guard b.count > 58, b[0] == respSelfInfo else { return nil }
        let n = String(decoding: b[58...].prefix { $0 != 0 }, as: UTF8.self)
        return n.isEmpty ? nil : n
    }

    struct Contact: Equatable, Codable {
        var publicKey: Data
        /// ADV_TYPE_*: 1 chat, 2 repeater, 3 room, 4 sensor.
        var type: UInt8
        var name: String
        var lastAdvert: UInt32
        var hex: String { publicKey.map { String(format: "%02x", $0) }.joined() }
    }

    /// RESP_CODE_CONTACT / PUSH_CODE_NEW_ADVERT: code, public key (32), type, flags, out_path_len,
    /// out_path (64), name (32, NUL-padded), last_advert (LE32), lat, lon, lastmod.
    static func parseContact(_ f: Data) -> Contact? {
        let b = [UInt8](f)
        let nameAt = 1 + pubKeySize + 3 + maxPathSize          // 100
        guard b.count >= nameAt + 32 + 4, b[0] == respContact || b[0] == pushNewAdvert else { return nil }
        let name = String(decoding: b[nameAt..<(nameAt + 32)].prefix { $0 != 0 }, as: UTF8.self)
        return Contact(publicKey: Data(b[1...pubKeySize]), type: b[1 + pubKeySize], name: name,
                       lastAdvert: readLE32(b, nameAt + 32))
    }

    /// RESP_CODE_SENT: code, flood (0/1), expected ACK (LE32), estimated timeout in ms (LE32).
    struct Sent: Equatable { var flood: Bool; var ackTag: UInt32; var timeoutMs: UInt32 }
    static func parseSent(_ f: Data) -> Sent? {
        let b = [UInt8](f)
        guard b.count >= 10, b[0] == respSent else { return nil }
        return Sent(flood: b[1] != 0, ackTag: readLE32(b, 2), timeoutMs: readLE32(b, 6))
    }

    /// PUSH_CODE_SEND_CONFIRMED: code, ACK (LE32), round trip ms (LE32).
    static func parseSendConfirmed(_ f: Data) -> UInt32? {
        let b = [UInt8](f)
        guard b.count >= 5, b[0] == pushSendConfirmed else { return nil }
        return readLE32(b, 1)
    }

    /// Text a MeshCore user sent: to us (a contact) or into one of the radio's channels.
    struct IncomingText: Equatable {
        enum Source: Equatable { case contact(prefix: Data), channel(idx: Int) }
        var source: Source
        var txtType: UInt8
        var timestamp: UInt32
        var text: String
    }

    /// CONTACT_MSG_RECV (7): code, prefix (6), path_len, txt_type, timestamp (LE32), [4-byte signer prefix
    /// when txt_type is SIGNED_PLAIN], text. CHANNEL_MSG_RECV (8): code, channel idx, path_len, txt_type,
    /// timestamp, text. The V3 forms (16, 17) put SNR and two reserved bytes after the code.
    static func parseIncomingText(_ f: Data) -> IncomingText? {
        let b = [UInt8](f)
        guard let code = b.first else { return nil }
        let base: Int
        switch code {
        case respContactMsgRecv, respChannelMsgRecv: base = 1
        case respContactMsgRecvV3, respChannelMsgRecvV3: base = 4
        default: return nil
        }
        func text(_ from: Int) -> String {
            guard from < b.count else { return "" }
            return String(decoding: b[from...].prefix { $0 != 0 }, as: UTF8.self)
        }
        if code == respContactMsgRecv || code == respContactMsgRecvV3 {
            guard b.count >= base + 12 else { return nil }
            let type = b[base + 7]
            let at = base + 12 + (type == txtTypeSignedPlain ? 4 : 0)
            return IncomingText(source: .contact(prefix: Data(b[base..<(base + 6)])), txtType: type,
                                timestamp: readLE32(b, base + 8), text: text(at))
        }
        guard b.count >= base + 7 else { return nil }
        return IncomingText(source: .channel(idx: Int(b[base])), txtType: b[base + 2],
                            timestamp: readLE32(b, base + 3), text: text(base + 7))
    }

    /// A channel message reads "<sender node name>: <text>"; the radio adds the prefix when it sends.
    static func splitChannelSender(_ text: String) -> (sender: String?, body: String) {
        guard let r = text.range(of: ": ") else { return (nil, text) }
        let name = String(text[..<r.lowerBound])
        guard !name.isEmpty, name.utf8.count <= 31 else { return (nil, text) }
        return (name, String(text[r.upperBound...]))
    }

    /// RESP_CODE_SELF_INFO: code, adv type, tx power, max tx power, public key (32), ...
    static func selfPublicKey(_ f: Data) -> Data? {
        let b = [UInt8](f)
        guard b.count >= 36, b[0] == respSelfInfo else { return nil }
        return Data(b[4..<36])
    }

    struct ChannelInfo: Equatable { var idx: Int; var name: String; var secret: Data }

    /// RESP_CODE_CHANNEL_INFO: code, idx, name (32, NUL-padded), secret (16).
    static func parseChannelInfo(_ f: Data) -> ChannelInfo? {
        let b = [UInt8](f)
        guard b.count >= 50, b[0] == respChannelInfo else { return nil }
        let nameBytes = b[2..<34].prefix { $0 != 0 }
        return ChannelInfo(idx: Int(b[1]), name: String(decoding: nameBytes, as: UTF8.self), secret: Data(b[34..<50]))
    }

    struct ChannelData: Equatable { var channelIdx: Int; var dataType: UInt16; var payload: Data }

    /// RESP_CODE_CHANNEL_DATA_RECV: code, snr×4, 0, 0, channel idx, path len, data type (LE16), len, data.
    static func parseChannelData(_ f: Data) -> ChannelData? {
        let b = [UInt8](f)
        guard b.count >= 9, b[0] == respChannelDataRecv else { return nil }
        let len = Int(b[8])
        guard b.count >= 9 + len else { return nil }
        return ChannelData(channelIdx: Int(b[4]), dataType: UInt16(b[6]) | UInt16(b[7]) << 8,
                           payload: Data(b[9..<(9 + len)]))
    }
}

// MARK: - "OB" envelope (oshi-meshcore-bridge/oshi_bridge/wire.py)

enum OSHIBridgeWire {
    static let marker: [UInt8] = [0x4F, 0x42]   // "OB"
    static let version: UInt8 = 1
    static let headerLen = 9
    static let maxDatagram = 160                // conservative vs MeshCore's 167
    static let dataType: UInt16 = 0xFF4F        // MeshCore's unregistered dev range
    static let channelName = "#oshi-bridge"
    static var channelSecret: Data { Data(SHA256.hash(data: Data(channelName.utf8))).prefix(16) }

    struct Part: Equatable { var sender: UInt32; var seq: UInt8; var part: Int; var total: Int; var chunk: Data }

    /// `sender` is the bridge field: whoever put the frame on MeshCore (a bridge's Meshtastic node number,
    /// or our own MeshCore-derived id). Receivers ignore their own.
    static func split(_ frame: Data, sender: UInt32, seq: UInt8, maxDatagram: Int = maxDatagram) -> [Data]? {
        let room = maxDatagram - headerLen
        let bytes = [UInt8](frame)
        let chunks: [ArraySlice<UInt8>] = bytes.isEmpty ? [[]] :
            stride(from: 0, to: bytes.count, by: room).map { bytes[$0..<min(bytes.count, $0 + room)] }
        guard chunks.count <= 15 else { return nil }
        var s = sender.littleEndian
        let senderBytes = withUnsafeBytes(of: &s) { Data($0) }
        return chunks.enumerated().map { i, c in
            Data(marker) + Data([version << 4]) + senderBytes + Data([seq, UInt8(i << 4 | chunks.count)]) + Data(c)
        }
    }

    static func parse(_ d: Data) -> Part? {
        let b = [UInt8](d)
        guard b.count >= headerLen, b[0] == marker[0], b[1] == marker[1], b[2] >> 4 == version else { return nil }
        let sender = UInt32(b[3]) | UInt32(b[4]) << 8 | UInt32(b[5]) << 16 | UInt32(b[6]) << 24
        let part = Int(b[8] >> 4), total = Int(b[8] & 0x0F)
        guard total > 0, part < total else { return nil }
        return Part(sender: sender, seq: b[7], part: part, total: total, chunk: Data(b[headerLen...]))
    }

    /// Multi-part envelopes, keyed by (sender, seq); incomplete sets expire.
    final class Joiner {
        private struct Pending { var total: Int; var first: Date; var chunks: [Int: Data] }
        private var pending: [String: Pending] = [:]
        let timeout: TimeInterval
        let maxPending: Int
        init(timeout: TimeInterval = 90, maxPending: Int = 128) { self.timeout = timeout; self.maxPending = maxPending }

        func push(_ p: Part, now: Date = Date()) -> Data? {
            if p.total == 1 { return p.chunk }
            pending = pending.filter { now.timeIntervalSince($0.value.first) <= timeout }
            let key = "\(p.sender):\(p.seq)"
            var e = pending[key]
            if e == nil || e!.total != p.total {
                if pending.count >= maxPending, let oldest = pending.min(by: { $0.value.first < $1.value.first })?.key {
                    pending.removeValue(forKey: oldest)
                }
                e = Pending(total: p.total, first: now, chunks: [:])
            }
            e!.chunks[p.part] = p.chunk
            guard e!.chunks.count == e!.total else { pending[key] = e; return nil }
            pending.removeValue(forKey: key)
            return (0..<e!.total).reduce(into: Data()) { $0.append(e!.chunks[$1] ?? Data()) }
        }
    }
}

// MARK: - Text with ordinary MeshCore users (__MESHCORE_INTEROP_2026_11_25__)

/// Conversation ids, text splitting and the routing rules for plain MeshCore text. Mirrors
/// OSHI-Android `MeshCoreProtocol.kt`: the ids below are shared by both apps and must stay identical.
///
/// This lane is NOT OSHI end-to-end encryption. A MeshCore direct message is protected by MeshCore's own
/// AES-128 + 2-byte MAC between the two radios' keys; a channel message by the channel secret, which for
/// the Public channel and every #hashtag channel anyone can derive. Every bubble says so.
enum MeshCoreInterop {
    /// A MeshCore person: `meshcore!<public key hex>` (64 hex), or `meshcore!<6-byte prefix hex>` while the
    /// radio's contact table does not know the full key.
    static let contactPrefix = "meshcore!"
    /// A MeshCore channel, by its name on the radio: `mcchan!Public`, `mcchan!#paris`.
    static let channelPrefix = "mcchan!"
    /// Who spoke in a channel. MeshCore channel text carries only the sender's node NAME ("name: text"),
    /// never a key, so the speaker is that name and nothing more.
    static let speakerPrefix = "mcname!"
    /// The `EncryptedMessage.signature` of every stored MeshCore interop row, inbound and outbound.
    static let signature = "meshcore_interop"

    /// ~133 characters is what fits one MeshCore message with room for the sender name in a channel.
    static let maxCharsPerMessage = 133
    static let maxParts = 20

    static func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    static func isContactKey(_ key: String) -> Bool { key.hasPrefix(contactPrefix) }
    static func isChannelKey(_ key: String) -> Bool { key.hasPrefix(channelPrefix) }
    static func isSpeakerKey(_ key: String) -> Bool { key.hasPrefix(speakerPrefix) }
    /// Any MeshCore thread or speaker: never an OSHI identity, never sent to the relay.
    static func isInteropKey(_ key: String) -> Bool { isContactKey(key) || isChannelKey(key) || isSpeakerKey(key) }

    static func contactKey(publicKey: Data) -> String { contactPrefix + hex(publicKey) }

    /// A DM arrives with only the sender's 6-byte key prefix: resolved through the contact table when exactly
    /// one contact matches, else the prefix itself.
    static func contactKey(forPrefix prefix: Data, in contacts: [MeshCore.Contact]) -> String {
        let matches = contacts.filter { $0.publicKey.prefix(prefix.count) == prefix }
        if matches.count == 1 { return contactKey(publicKey: matches[0].publicKey) }
        return contactPrefix + hex(prefix)
    }

    /// The key bytes (full key or prefix) back out of a contact id; nil for anything malformed.
    static func publicKeyBytes(fromContactKey key: String) -> Data? {
        guard isContactKey(key) else { return nil }
        let h = Array(key.dropFirst(contactPrefix.count))
        guard h.count >= 12, h.count <= 64, h.count % 2 == 0, h.allSatisfy(\.isHexDigit) else { return nil }
        var out = Data()
        var i = 0
        while i < h.count { out.append(UInt8(String(h[i..<(i + 2)]), radix: 16)!); i += 2 }
        return out
    }

    /// nil for an empty slot and for our own "#oshi-bridge", which is plumbing, never a room.
    static func channelKey(name: String) -> String? {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty, n != OSHIBridgeWire.channelName else { return nil }
        return channelPrefix + n
    }

    static func channelName(fromKey key: String) -> String? {
        guard isChannelKey(key) else { return nil }
        let n = String(key.dropFirst(channelPrefix.count))
        return n.isEmpty ? nil : n
    }

    static func speakerKey(name: String?) -> String {
        let n = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return speakerPrefix + (n.isEmpty ? "?" : n)
    }

    /// The name to draw for a MeshCore thread or speaker; nil for every other key.
    static func displayName(forKey key: String, contacts: [MeshCore.Contact]) -> String? {
        if let n = channelName(fromKey: key) { return n }
        if isSpeakerKey(key) { return String(key.dropFirst(speakerPrefix.count)) }
        guard let bytes = publicKeyBytes(fromContactKey: key) else { return nil }
        if let c = contacts.first(where: { $0.publicKey.prefix(bytes.count) == bytes }), !c.name.isEmpty { return c.name }
        return String(hex(bytes).prefix(8))
    }

    /// A hashtag channel's secret: the first 16 bytes of SHA-256 of its name, '#' included.
    static func hashtagSecret(_ name: String) -> Data { Data(SHA256.hash(data: Data(name.utf8))).prefix(16) }

    /// "paris" / " #paris " → "#paris". nil when empty, spaced or too long for the radio's 31-byte name.
    static func normalizedHashtag(_ input: String) -> String? {
        var n = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !n.hasPrefix("#") { n = "#" + n }
        guard n.count > 1, !n.contains(where: \.isWhitespace), n.utf8.count <= 31 else { return nil }
        return n
    }

    /// 32 hex characters → 16-byte secret; nil otherwise.
    static func parseSecret(_ input: String) -> Data? {
        let h = Array(input.filter { !$0.isWhitespace })
        guard h.count == 32, h.allSatisfy(\.isHexDigit) else { return nil }
        return Data(stride(from: 0, to: 32, by: 2).map { UInt8(String(h[$0..<($0 + 2)]), radix: 16)! })
    }

    /// The Public channel and every hashtag channel: anyone who knows the name holds the key.
    static func isPubliclyReadable(name: String, secret: Data) -> Bool {
        secret == MeshCore.publicChannelSecret || (name.hasPrefix("#") && secret == hashtagSecret(name))
    }

    /// The UTF-8 room of one direct message: MAX_TEXT_LEN minus 4, the same margin as Android
    /// (`MeshCoreText.DM_MAX_BYTES`), so both apps cut a long text at the same places.
    static let dmTextBytes = MeshCore.maxTextBytes - 4

    /// The UTF-8 room a channel message has: the radio prepends "<node name>: " inside MAX_TEXT_LEN (31
    /// bytes assumed when the name is unknown), plus 2 bytes of margin. Identical to Android's
    /// `MeshCoreText.channelMaxBytes`.
    static func channelTextBytes(nodeName: String?) -> Int {
        MeshCore.maxTextBytes - min(31, nodeName?.utf8.count ?? 31) - 2 - 2
    }

    /// The split rule BOTH apps follow (Android `MeshCoreText.split`), so a long text leaves in the same parts
    /// from either. Characters are Unicode code points (Kotlin `codePointCount`, Swift `unicodeScalars`):
    ///
    /// 1. Trim. Empty → nil. Fits whole (≤ `maxChars` code points AND ≤ `maxBytes` UTF-8 bytes) → as is, no label.
    /// 2. Otherwise label every part "(i/n) ". The label room is reserved at its widest for the digit count:
    ///    "(9/9) " (6) while n ≤ 9, "(99/99) " (8) beyond — so "(1/12) " and "(12/12) " cost the same.
    /// 3. Each part: as many code points as fit both budgets (minus the label); never ending inside a grapheme
    ///    cluster (an emoji with its modifiers / ZWJ sequence stays whole, unless the cluster alone is the part);
    ///    then cut at the last U+0020 at or before that end when it sits at index ≥ end / 2 (and > 0).
    ///    Each piece is trimmed; the rest is trimmed at its start.
    /// 4. More than `maxParts` parts → nil (the composer says the text is too long).
    static func splitText(_ text: String, maxChars: Int = maxCharsPerMessage,
                          maxBytes: Int = dmTextBytes) -> [String]? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        if fits(Array(t.unicodeScalars), maxChars: maxChars, maxBytes: maxBytes) { return [t] }
        var digits = 1
        while digits <= 2 {
            let nines = String(repeating: "9", count: digits)
            let label = "(\(nines)/\(nines)) ".count               // ASCII: characters == bytes
            let chunks = chunk(t, maxChars: maxChars - label, maxBytes: maxBytes - label)
            if chunks.count <= Int(pow(10.0, Double(digits))) - 1 {
                guard chunks.count <= maxParts else { return nil }
                return chunks.enumerated().map { "(\($0.offset + 1)/\(chunks.count)) \($0.element)" }
            }
            digits += 1
        }
        return nil
    }

    private static func fits(_ s: ArraySlice<Unicode.Scalar>, maxChars: Int, maxBytes: Int) -> Bool {
        s.count <= maxChars && s.reduce(0) { $0 + UTF8.width($1) } <= maxBytes
    }
    private static func fits(_ s: [Unicode.Scalar], maxChars: Int, maxBytes: Int) -> Bool {
        fits(s[...], maxChars: maxChars, maxBytes: maxBytes)
    }

    private static func string(_ s: ArraySlice<Unicode.Scalar>) -> String {
        var v = String.UnicodeScalarView(); v.append(contentsOf: s); return String(v)
    }

    private static func chunk(_ t: String, maxChars: Int, maxBytes: Int) -> [String] {
        var out: [String] = []
        var rest = t.trimmingCharacters(in: .whitespacesAndNewlines)
        while !rest.isEmpty {
            let s = Array(rest.unicodeScalars)
            if fits(s, maxChars: maxChars, maxBytes: maxBytes) { out.append(rest); break }
            var end = 0, bytes = 0
            while end < s.count {
                let b = UTF8.width(s[end])
                if end + 1 > maxChars || bytes + b > maxBytes { break }
                bytes += b; end += 1
            }
            if end == 0 { end = 1 }
            // Never end inside a grapheme cluster: back up to the start of the cluster `end` falls in.
            var boundary = 0, lastStart = 0
            for ch in rest {
                if boundary >= end { break }
                lastStart = boundary
                boundary += ch.unicodeScalars.count
            }
            if boundary != end && lastStart > 0 { end = lastStart }
            var cut = end
            if let space = s[0...min(end, s.count - 1)].lastIndex(of: " "), space >= end / 2, space > 0 { cut = space }
            let piece = string(s[0..<cut]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !piece.isEmpty { out.append(piece) }
            rest = String(string(s[cut...]).drop(while: { $0.isWhitespace || $0.isNewline }))
        }
        return out
    }

    // MARK: Routing rules

    /// LoRa counts as a route when EITHER radio can carry an OSHI envelope: a phone with only a MeshCore
    /// radio is off-grid capable too, and used to be treated as radio-less by the router.
    static func radioReady(meshtasticReady: Bool, meshCoreReady: Bool) -> Bool { meshtasticReady || meshCoreReady }

    /// Jamming is measured on the Meshtastic radio's channel; a ready MeshCore radio still carries the message.
    static func radioJammed(meshtasticJammed: Bool, meshCoreReady: Bool) -> Bool { meshtasticJammed && !meshCoreReady }

    /// The status a MeshCore text ends in. A channel message is a broadcast nobody acknowledges: `.sent`
    /// at best, never `.delivered`. A direct message is `.delivered` only on the recipient's ACK.
    static func finalStatus(isChannel: Bool, acked: Bool, radioAccepted: Bool) -> DeliveryStatus {
        guard radioAccepted else { return .failed }
        if isChannel { return .sent }
        return acked ? .delivered : .failed
    }
}

// MARK: - The radio

/// One MeshCore companion radio over Bluetooth. Mirrors MeshtasticManager's shape (singleton, main queue,
/// state restoration) so the settings screen and the message pipeline treat both radios alike.
final class MeshCoreManager: NSObject, ObservableObject {
    static let shared = MeshCoreManager()

    enum ConnState: String { case off, scanning, connecting, configuring, ready }

    @Published private(set) var state: ConnState = .off
    @Published private(set) var discovered: [CBPeripheral] = []
    @Published private(set) var connectedName: String?
    /// The slot our "#oshi-bridge" channel lives in, or nil while unknown / when every slot is taken.
    @Published private(set) var channelIdx: Int?
    @Published private(set) var lastError: String?
    /// The radio is asking for its PIN (iOS shows the system prompt): between subscribing to the radio and
    /// the radio letting us in. The settings row tells the person what to type.
    @Published private(set) var awaitingPin = false
    /// Signal strength of each radio found by the scan.
    @Published private(set) var discoveredRSSI: [UUID: Int] = [:]
    /// When the radio last announced itself to MeshCore users at our request (nil: not this session).
    @Published private(set) var lastAdvertAt: Date?
    /// The channel the radio just accepted from "Join a channel" (its thread key), so Settings opens it.
    @Published private(set) var lastJoinedChannelKey: String?
    @Published private(set) var framesSent = 0
    @Published private(set) var messagesReceived = 0
    /// __MESHCORE_INTEROP_2026_11_25__ The radio's contact table (last sync, kept across launches so a
    /// thread keeps its name while the radio is away).
    @Published private(set) var contacts: [MeshCore.Contact] = []
    /// Every channel slot the radio reported, empty ones included (they are where a join goes).
    @Published private(set) var channels: [MeshCore.ChannelInfo] = []
    /// The radio's advertised node name, which it puts in front of our channel messages.
    @Published private(set) var selfName: String?

    /// OSHI envelopes need "#oshi-bridge"; plain MeshCore text only needs the radio.
    var isReady: Bool { state == .ready && channelIdx != nil }
    var isTextReady: Bool { state == .ready }

    /// The channels a user can talk in: named, not our bridge.
    var userChannels: [MeshCore.ChannelInfo] {
        channels.filter { MeshCoreInterop.channelKey(name: $0.name) != nil }.sorted { $0.idx < $1.idx }
    }

    /// Our OMP origin / envelope sender on MeshCore: the first four bytes of the radio's public key.
    /// Meshtastic node numbers are 32-bit too; a collision only costs a reassembly key, never a message.
    private(set) var nodeId: UInt32 = 0

    private static let pairedKey = "oshi.meshcore.peripheralId"
    private static let contactsKey = "oshi.meshcore.contacts"
    private static let restoreIdentifier = "oshi.meshcore.central"
    private static let sendGap: TimeInterval = 2.5   // OMP pacing; a MeshCore datagram is ~0.5-1 s of air
    private static let textPartGap: TimeInterval = 1.5
    /// CMD_SEND_TXT_MSG `attempt` runs 0...3; the firmware floods on the last ones when the path is stale.
    private static let lastAttempt: UInt8 = 3

    private var central: CBCentralManager?
    /// Where companion commands go: a GATT write to the radio, or a test's recorder.
    private var writer: ((Data) -> Void)?
    /// Where a received message goes: MessageManager (through the same notification as every mesh path).
    var deliver: (SecureMessage) -> Void = { msg in
        NotificationCenter.default.post(name: NSNotification.Name("MeshMessageReceived"), object: nil,
                                        userInfo: ["message": msg])
    }
    /// Where the radio's verdict on an outgoing text goes.
    var statusSink: (String, DeliveryStatus) -> Void = { id, status in
        MessageManager.shared?.meshCoreTextStatus(messageId: id, status: status)
    }
    /// A failure the person has to act on (wrong PIN, not MeshCore firmware, no answer): do not reconnect in
    /// a loop, which would re-open the PIN prompt over and over. "Try again" clears it.
    private var holdReconnect = false
    private var setupTimer: Timer?
    fileprivate static let setupTimeoutValue: TimeInterval = 15
    /// Contacts the radio pushed (PUSH_NEW_ADVERT, auto-add off) but does not hold: key → that frame, replayed
    /// as CMD_ADD_UPDATE_CONTACT before the first DM to them (Android `notOnRadio`).
    private var heardOnlyFrames: [Data: Data] = [:]
    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?

    /// Companion requests are strictly one at a time: the next goes out when the previous is answered.
    private var commands: [Data] = []
    private var awaiting = false
    /// The command code the radio is answering, so OK / ERR are attributed to the right request.
    private var inFlight: UInt8?
    private var probing: Int?                    // GET_CHANNEL slot being inspected at setup
    private var firstEmptySlot: Int?
    private var datagrams: [Data] = []
    private var sendTimer: Timer?
    private var seq: UInt8 = 0
    private var contactListing: [MeshCore.Contact]?
    /// A channel the user asked to join or leave, waiting for SET_CHANNEL's answer.
    private var pendingChannelSet: MeshCore.ChannelInfo?

    private let joiner = OSHIBridgeWire.Joiner()
    private let reassembler = OMPReassembler()

    private override init() {
        super.init()
        if let d = UserDefaults.standard.data(forKey: Self.contactsKey),
           let saved = try? JSONDecoder().decode([MeshCore.Contact].self, from: d) {
            contacts = saved
        }
        central = CBCentralManager(delegate: self, queue: .main,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier])
    }

    /// A radio without Bluetooth for tests: `attachForTesting` stands in for the BLE link, `receiveForTesting`
    /// for the radio's notifications. Nothing is persisted.
    init(testing: Void) {
        persist = false
        super.init()
    }
    private var persist = true

    func attachForTesting(write: @escaping (Data) -> Void) {
        writer = write
        connectedName = "MeshCore-Test"
        state = .configuring
        awaiting = false; inFlight = nil
        enqueue(MeshCore.appStart())
    }

    func receiveForTesting(_ frame: Data) { handle(frame) }

    /// Runs the pending text timer now (ACK wait or the gap between parts) instead of waiting for it.
    func fireTextTimerForTesting() {
        guard let action = textTimerAction else { return }
        textTimer?.invalidate(); textTimer = nil; textTimerAction = nil
        action()
    }

    func dropLinkForTesting() { linkLost() }

    #if DEBUG
    private(set) var demoSeeded = false
    /// Screenshot capture (`-OSHIMeshCoreDemo`): a radio in a chosen state, without Bluetooth. DEBUG only.
    func seedDemo(phase: String, name: String, channels: [MeshCore.ChannelInfo], contacts: [MeshCore.Contact]) {
        demoSeeded = true
        writer = { _ in }
        if phase != "scan" { UserDefaults.standard.set(UUID().uuidString, forKey: Self.pairedKey) }
        else { UserDefaults.standard.removeObject(forKey: Self.pairedKey) }
        connectedName = name
        self.channels = channels
        self.contacts = contacts
        switch phase {
        case "off": connectedName = nil; state = .off; lastError = MeshCoreError.connectionLost
        case "pin": state = .configuring; awaitingPin = true
        case "pairing": connectedName = nil; state = .off; lastError = MeshCoreError.pairingFailed; holdReconnect = true
        case "scan":
            connectedName = nil; self.channels = []; state = .scanning
        default:
            selfName = "Hugo-OSHI"; channelIdx = 1; state = .ready; lastAdvertAt = phase == "announced" ? Date() : nil
        }
    }
    #endif

    static var isPaired: Bool { UserDefaults.standard.string(forKey: pairedKey) != nil }

    /// Any LoRa radio this phone can end up sending through — what sets the 12-frame message budget.
    static var anyLoRaRadioPaired: Bool { isPaired || MeshtasticManager.isRadioPaired }

    // MARK: Pairing

    func startScan() {
        guard let central, central.state == .poweredOn, state == .off else { return }
        discovered = []
        discoveredRSSI = [:]
        state = .scanning
        central.scanForPeripherals(withServices: [MeshCore.serviceUUID])
    }

    func stopScan() {
        central?.stopScan()
        if state == .scanning { state = .off }
    }

    func connect(_ p: CBPeripheral) {
        central?.stopScan()
        holdReconnect = false
        lastError = nil
        peripheral = p
        p.delegate = self
        state = .connecting
        UserDefaults.standard.set(p.identifier.uuidString, forKey: Self.pairedKey)
        central?.connect(p)
    }

    /// "Try again" after a wrong PIN, a radio that did not answer, or a lost link the person wants back now.
    func retryConnection() {
        holdReconnect = false
        lastError = nil
        if let p = peripheral, state != .off { central?.cancelPeripheralConnection(p) }
        reset(); state = .off
        reconnectIfPaired()
    }

    func forget() {
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        UserDefaults.standard.removeObject(forKey: Self.pairedKey)
        reset()
        lastError = nil
        holdReconnect = false
        state = .off
    }

    private func reconnectIfPaired() {
        guard peripheral == nil, !holdReconnect, let central, let s = UserDefaults.standard.string(forKey: Self.pairedKey),
              let uuid = UUID(uuidString: s), let p = central.retrievePeripherals(withIdentifiers: [uuid]).first else { return }
        connect(p)
    }

    private func reset() {
        peripheral = nil; rx = nil; tx = nil; writer = nil
        commands = []; awaiting = false; inFlight = nil; probing = nil; firstEmptySlot = nil
        datagrams = []; sendTimer?.invalidate(); sendTimer = nil
        setupTimer?.invalidate(); setupTimer = nil; awaitingPin = false
        channelIdx = nil; connectedName = nil; nodeId = 0
        contactListing = nil; pendingChannelSet = nil; channels = []; selfName = nil
        heardOnlyFrames = [:]
        abandonTexts()
    }

    /// The link went away under a working radio: say so, fail what was in flight, keep the pairing.
    private func linkLost() {
        let wasReady = state == .ready
        reset()
        state = .off
        if wasReady { lastError = MeshCoreError.connectionLost }
    }

    /// Stop on a failure the person must fix; the radio stays paired so "Try again" can reconnect.
    private func failSetup(_ error: String) {
        lastError = error
        holdReconnect = true
        let p = peripheral
        reset()
        state = .off
        if let p { central?.cancelPeripheralConnection(p) }
    }

    // MARK: Sending

    /// The same entry point as MeshtasticManager.sendSecureMessage: the whole envelope, broadcast, as OMP.
    @discardableResult
    func sendSecureMessage(_ message: SecureMessage) -> Bool {
        guard isReady else { return false }
        // Same opt-in as the Meshtastic side: media over LoRa is off unless the user turned it on.
        let carriesMedia = message.mediaAttachment != nil || message.originalMediaData != nil
        if carriesMedia && !MeshtasticManager.mediaOverLoRaEnabled { return false }
        guard let json = try? JSONEncoder().encode(message.meshWireSafe()) else { return false }
        let body = OMP.encodeBody(json)
        guard let frames = OMP.dataFrames(msgId: UInt32.random(in: 1...UInt32.max), dest: OMP.broadcast,
                                          body: body, origin: nodeId), frames.count <= 12 else { return false }
        frames.forEach { sendFrame($0) }
        return true
    }

    /// One OMP frame onto MeshCore under our envelope. Also what the cross-network bridge calls.
    func sendFrame(_ frame: Data) {
        guard isReady, let idx = channelIdx else { return }
        seq &+= 1
        guard let parts = OSHIBridgeWire.split(frame, sender: nodeId, seq: seq) else { return }
        for p in parts { datagrams.append(MeshCore.sendChannelData(idx, dataType: OSHIBridgeWire.dataType, payload: p)) }
        drainDatagrams()
    }

    private func drainDatagrams() {
        guard sendTimer == nil, !datagrams.isEmpty else { return }
        enqueue(datagrams.removeFirst())
        framesSent += 1
        sendTimer = Timer.scheduledTimer(withTimeInterval: Self.sendGap, repeats: false) { [weak self] _ in
            self?.sendTimer = nil
            self?.drainDatagrams()
        }
    }

    // MARK: Plain MeshCore text (__MESHCORE_INTEROP_2026_11_25__)

    enum TextTarget: Equatable { case contact(Data), channel(Int) }

    private struct OutText {
        let messageId: String
        let target: TextTarget
        let parts: [String]
        let timestamp: UInt32
        var part = 0
        var attempt: UInt8 = 0
        /// Every ACK tag the radio handed out for the current part: a late ACK for an earlier attempt counts.
        var ackTags: Set<UInt32> = []
        var accepted = false
        /// A contact the radio only heard: added to its table before the first part (CMD_ADD_UPDATE_CONTACT).
        var addContact: Data?
    }

    private var textQueue: [OutText] = []
    private var textActive: OutText?
    private var textTimer: Timer?
    private var textTimerAction: (() -> Void)?

    private func scheduleText(after seconds: TimeInterval, _ action: @escaping () -> Void) {
        textTimer?.invalidate()
        textTimerAction = action
        textTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            guard let self, let a = self.textTimerAction else { return }
            self.textTimer = nil; self.textTimerAction = nil
            a()
        }
    }

    private func cancelTextTimer() { textTimer?.invalidate(); textTimer = nil; textTimerAction = nil }

    /// Messages handed to the radio that it has not taken yet (bubble clock) — for tests and diagnostics.
    var queuedTextIds: [String] { (textActive.map { [$0.messageId] } ?? []) + textQueue.map(\.messageId) }

    /// The slot a `mcchan!<name>` thread talks in on THIS radio; nil when the radio has no such channel.
    func channelSlot(forKey key: String) -> Int? {
        guard let name = MeshCoreInterop.channelName(fromKey: key) else { return nil }
        return userChannels.first { $0.name.trimmingCharacters(in: .whitespacesAndNewlines) == name }?.idx
    }

    func channel(forKey key: String) -> MeshCore.ChannelInfo? {
        guard let idx = channelSlot(forKey: key) else { return nil }
        return channels.first { $0.idx == idx }
    }

    /// The text as the parts it will leave in; nil when it is empty or needs more than `maxParts`.
    func textParts(_ text: String, for target: TextTarget) -> [String]? {
        switch target {
        case .contact: return MeshCoreInterop.splitText(text, maxBytes: MeshCoreInterop.dmTextBytes)
        case .channel: return MeshCoreInterop.splitText(text, maxBytes: MeshCoreInterop.channelTextBytes(nodeName: selfName))
        }
    }

    /// Queue one stored message (already split) for the radio. Status reaches MessageManager as the radio
    /// answers: `.sent` when it takes the first part, `.delivered` when every DM part is ACKed, `.sent` for
    /// a channel (nobody ACKs a broadcast), `.failed` when the radio refuses or every attempt goes unheard.
    @discardableResult
    func sendText(_ parts: [String], to target: TextTarget, messageId: String) -> Bool {
        guard isTextReady, !parts.isEmpty else { return false }
        // A retry of a message still queued replaces it rather than sending it twice.
        textQueue.removeAll { $0.messageId == messageId }
        if textActive?.messageId == messageId { return true }
        var out = OutText(messageId: messageId, target: target, parts: parts,
                          timestamp: UInt32(Date().timeIntervalSince1970))
        if case .contact(let prefix) = target,
           let frame = heardOnlyFrames.first(where: { $0.key.prefix(prefix.count) == prefix })?.value {
            out.addContact = MeshCore.addContact(from: frame)
        }
        textQueue.append(out)
        driveText()
        return true
    }

    private func driveText() {
        guard textActive == nil, !textQueue.isEmpty else { return }
        textActive = textQueue.removeFirst()
        if let add = textActive?.addContact {
            textActive?.addContact = nil
            enqueue(add)
        }
        transmitActivePart()
    }

    private func transmitActivePart() {
        cancelTextTimer()
        guard let t = textActive, t.part < t.parts.count else { return }
        // One timestamp per part, the same on every attempt: the recipient's radio dedupes retries on it.
        let ts = t.timestamp &+ UInt32(t.part)
        switch t.target {
        case .contact(let key):
            enqueue(MeshCore.sendText(attempt: t.attempt, timestamp: ts, pubKeyPrefix: key, text: t.parts[t.part]))
        case .channel(let idx):
            enqueue(MeshCore.sendChannelText(idx, timestamp: ts, text: t.parts[t.part]))
        }
    }

    private func textAccepted(_ t: inout OutText) {
        guard !t.accepted else { return }
        t.accepted = true
        report(t.messageId, .sent)
    }

    /// RESP_CODE_SENT for a direct message: wait for its ACK, then retry with the next attempt.
    private func textDirectSent(_ s: MeshCore.Sent) {
        guard var t = textActive, case .contact = t.target else { return }
        textAccepted(&t)
        t.ackTags.insert(s.ackTag)
        textActive = t
        let wait = min(max(Double(s.timeoutMs) / 1000 * 1.25 + 2, 6), 60)
        scheduleText(after: wait) { [weak self] in self?.textAckTimedOut() }
    }

    private func textAcked(_ ack: UInt32) {
        guard var t = textActive, t.ackTags.contains(ack) else { return }
        t.ackTags = []; t.attempt = 0; t.part += 1
        textActive = t
        nextPartOrFinish(acked: true)
    }

    private func textAckTimedOut() {
        guard var t = textActive else { return }
        guard t.attempt < Self.lastAttempt else {
            finishText(MeshCoreInterop.finalStatus(isChannel: false, acked: false, radioAccepted: t.accepted))
            return
        }
        t.attempt += 1
        textActive = t
        OshiLog.mesh.info("📡 MeshCore: no ACK for \(t.messageId.prefix(8)) part \(t.part + 1), attempt \(t.attempt)")
        transmitActivePart()
    }

    /// RESP_OK for a channel message: on the air, and that is all anyone will ever know.
    private func textChannelSent() {
        guard var t = textActive, case .channel = t.target else { return }
        textAccepted(&t)
        t.part += 1
        textActive = t
        nextPartOrFinish(acked: false)
    }

    private func nextPartOrFinish(acked: Bool) {
        guard let t = textActive else { return }
        if t.part >= t.parts.count {
            let isChannel: Bool = { if case .channel = t.target { return true }; return false }()
            finishText(MeshCoreInterop.finalStatus(isChannel: isChannel, acked: acked, radioAccepted: t.accepted))
            return
        }
        scheduleText(after: Self.textPartGap) { [weak self] in self?.transmitActivePart() }
    }

    private func textRefused() {
        guard textActive != nil else { return }
        OshiLog.mesh.info("📡 MeshCore: radio refused a text (unknown contact or channel)")
        finishText(.failed)
    }

    private func finishText(_ status: DeliveryStatus) {
        cancelTextTimer()
        guard let t = textActive else { return }
        textActive = nil
        report(t.messageId, status)
        // The next message keeps the same breathing room as the next part: back to back they collide.
        if !textQueue.isEmpty { scheduleText(after: Self.textPartGap) { [weak self] in self?.driveText() } }
    }

    /// The radio went away: nothing in flight will be answered. Failed, so tap-to-retry is offered.
    private func abandonTexts() {
        cancelTextTimer()
        let lost = (textActive.map { [$0] } ?? []) + textQueue
        textActive = nil; textQueue = []
        for t in lost { report(t.messageId, t.accepted && t.target.isChannel ? .sent : .failed) }
    }

    private func report(_ messageId: String, _ status: DeliveryStatus) {
        statusSink(messageId, status)
    }

    /// Announce this radio (its name and key) so MeshCore users nearby, and through repeaters, can add it as a
    /// contact and write to it. False when no radio is ready.
    @discardableResult
    func announce() -> Bool {
        guard isTextReady else { return false }
        if !commands.contains(where: { $0.first == MeshCore.cmdSendSelfAdvert }) { enqueue(MeshCore.sendSelfAdvert(flood: true)) }
        return true
    }

    // MARK: Channels and contacts

    /// Join a hashtag channel ("#paris": anyone who knows the name can read it) or a private one (name +
    /// 16-byte secret) in the first free slot. False when nothing is connected or no slot is free.
    @discardableResult
    func joinChannel(name: String, secret: Data) -> Bool {
        guard isTextReady, secret.count == 16, MeshCoreInterop.channelKey(name: name) != nil else { return false }
        if channels.contains(where: { $0.name == name && $0.secret == secret }) { return true }
        guard let free = channels.filter({ $0.idx > 0 && $0.name.isEmpty && $0.secret.allSatisfy { $0 == 0 } })
                .map(\.idx).min() ?? channels.filter({ $0.idx > 0 && $0.name.isEmpty }).map(\.idx).min() else {
            lastError = MeshCoreError.noFreeChannel
            return false
        }
        pendingChannelSet = MeshCore.ChannelInfo(idx: free, name: name, secret: secret)
        enqueue(MeshCore.setChannel(free, name: name, secret: secret))
        return true
    }

    /// Empties the slot (empty name, zero secret), the documented way to delete a channel.
    func leaveChannel(idx: Int) {
        guard isTextReady, idx != channelIdx, idx > 0 else { return }
        pendingChannelSet = MeshCore.ChannelInfo(idx: idx, name: "", secret: Data(count: 16))
        enqueue(MeshCore.setChannel(idx, name: "", secret: Data(count: 16)))
    }

    func refreshContacts() {
        guard isTextReady, inFlight != MeshCore.cmdGetContacts,
              !commands.contains(MeshCore.getContacts) else { return }
        enqueue(MeshCore.getContacts)
    }

    private func recordChannel(_ info: MeshCore.ChannelInfo) {
        if let i = channels.firstIndex(where: { $0.idx == info.idx }) { channels[i] = info } else { channels.append(info) }
    }

    private func storeContacts(_ list: [MeshCore.Contact]) {
        contacts = list
        if persist, let d = try? JSONEncoder().encode(list) { UserDefaults.standard.set(d, forKey: Self.contactsKey) }
    }

    private func upsertContact(_ c: MeshCore.Contact) {
        var list = contacts
        if let i = list.firstIndex(where: { $0.publicKey == c.publicKey }) { list[i] = c } else { list.append(c) }
        storeContacts(list)
    }

    // MARK: Companion request queue

    private func enqueue(_ cmd: Data) {
        commands.append(cmd)
        pump()
    }

    private func pump() {
        guard !awaiting, !commands.isEmpty, let writer else { return }
        awaiting = true
        let cmd = commands.removeFirst()
        inFlight = cmd.first
        writer(cmd)
    }

    // MARK: Receiving

    private func handlePush(_ code: UInt8, _ f: Data) {
        switch code {
        case MeshCore.pushMsgWaiting:
            enqueue(MeshCore.syncNextMessage)
        case MeshCore.pushNewAdvert:
            // Heard but NOT in the radio's table (its owner turned auto-add off): listed, and remembered so the
            // first DM adds it to the radio first.
            if let c = MeshCore.parseContact(f) { upsertContact(c); heardOnlyFrames[c.publicKey] = f }
        case MeshCore.pushAdvert:
            // A contact re-advertised; only a key we have never listed is worth a sync.
            let key = f.dropFirst().prefix(MeshCore.pubKeySize)
            if key.count == MeshCore.pubKeySize, !contacts.contains(where: { $0.publicKey == key }) { refreshContacts() }
        case MeshCore.pushSendConfirmed:
            if let ack = MeshCore.parseSendConfirmed(f) { textAcked(ack) }
        default:
            break
        }
    }

    private func handle(_ f: Data) {
        guard let code = f.first else { return }
        if code >= 0x80 { handlePush(code, f); return }
        let answering = inFlight
        // GET_CONTACTS is the one request answered by many frames: stay on it until END_OF_CONTACTS.
        if answering == MeshCore.cmdGetContacts, code == MeshCore.respContactsStart || code == MeshCore.respContact {
            if code == MeshCore.respContactsStart { contactListing = [] }
            else if let c = MeshCore.parseContact(f) {
                contactListing = (contactListing ?? []) + [c]
                heardOnlyFrames.removeValue(forKey: c.publicKey)
            }
            return
        }
        awaiting = false
        inFlight = nil
        defer { pump() }
        switch code {
        case MeshCore.respSelfInfo:
            setupTimer?.invalidate(); setupTimer = nil
            if let key = MeshCore.selfPublicKey(f) {
                let k = [UInt8](key)
                nodeId = UInt32(k[0]) | UInt32(k[1]) << 8 | UInt32(k[2]) << 16 | UInt32(k[3]) << 24
            }
            selfName = MeshCore.selfName(f)
            probing = 0
            enqueue(MeshCore.getChannel(0))
        case MeshCore.respChannelInfo:
            guard let info = MeshCore.parseChannelInfo(f) else { return }
            channelProbed(info)
        case MeshCore.respErr:
            switch answering {
            case MeshCore.cmdGetChannel where probing != nil:
                // A slot the firmware does not have ends the scan like an unknown one.
                probing = MeshCore.channelSlots
                channelProbed(nil)
            case MeshCore.cmdSendTxtMsg, MeshCore.cmdSendChannelTxtMsg:
                textRefused()
            case MeshCore.cmdSetChannel:
                if pendingChannelSet != nil {
                    pendingChannelSet = nil
                    lastError = MeshCoreError.channelFailed
                } else if state == .configuring {
                    // The bridge slot could not be claimed: OSHI envelopes cannot ride, plain text still can.
                    channelIdx = nil
                    lastError = MeshCoreError.noFreeChannel
                    becomeReady()
                }
            case MeshCore.cmdGetContacts:
                contactListing = nil
            default:
                break
            }
        case MeshCore.respOK:
            switch answering {
            case MeshCore.cmdSetChannel:
                if let set = pendingChannelSet {
                    pendingChannelSet = nil
                    recordChannel(set)
                    lastError = nil
                    if !set.name.isEmpty { lastJoinedChannelKey = MeshCoreInterop.channelKey(name: set.name) }
                } else if state == .configuring, let idx = channelIdx {
                    recordChannel(MeshCore.ChannelInfo(idx: idx, name: OSHIBridgeWire.channelName,
                                                       secret: OSHIBridgeWire.channelSecret))
                    becomeReady()
                }
            case MeshCore.cmdSendChannelTxtMsg:
                textChannelSent()
            case MeshCore.cmdSendSelfAdvert:
                lastAdvertAt = Date()
            default:
                break
            }
        case MeshCore.respEndOfContacts:
            // The radio's table, plus contacts it only heard, which it never lists.
            if let list = contactListing {
                let heardOnly = contacts.filter { c in heardOnlyFrames[c.publicKey] != nil && !list.contains { $0.publicKey == c.publicKey } }
                storeContacts(list + heardOnly)
            }
            contactListing = nil
        case MeshCore.respSent:
            if answering == MeshCore.cmdSendTxtMsg, let s = MeshCore.parseSent(f) { textDirectSent(s) }
        case MeshCore.respChannelDataRecv:
            if let d = MeshCore.parseChannelData(f) { receive(d) }
            enqueue(MeshCore.syncNextMessage)
        case MeshCore.respNoMoreMessages:
            break
        case MeshCore.respContactMsgRecv, MeshCore.respChannelMsgRecv,
             MeshCore.respContactMsgRecvV3, MeshCore.respChannelMsgRecvV3:
            if let t = MeshCore.parseIncomingText(f) { receiveText(t) }
            enqueue(MeshCore.syncNextMessage)
        default:
            break
        }
    }

    /// Setup: read every slot 0-7 (the list Settings shows), find "#oshi-bridge", else claim the first EMPTY
    /// slot. Never overwrite a channel the owner configured: their MeshCore app would silently lose it.
    private func channelProbed(_ info: MeshCore.ChannelInfo?) {
        if let info {
            recordChannel(info)
            if channelIdx == nil && info.name == OSHIBridgeWire.channelName && info.secret == OSHIBridgeWire.channelSecret {
                channelIdx = info.idx
            }
            if info.name.isEmpty && info.idx > 0 && firstEmptySlot == nil { firstEmptySlot = info.idx }
            let next = info.idx + 1
            if probing != nil, next < MeshCore.channelSlots { probing = next; enqueue(MeshCore.getChannel(next)); return }
        }
        probing = nil
        if channelIdx != nil { becomeReady(); return }
        guard let slot = firstEmptySlot else {
            lastError = MeshCoreError.noFreeChannel
            OshiLog.mesh.info("📡 MeshCore: every channel slot is in use; OSHI cannot add #oshi-bridge")
            becomeReady()   // plain MeshCore text still works without the bridge channel
            return
        }
        channelIdx = slot
        enqueue(MeshCore.setChannel(slot, name: OSHIBridgeWire.channelName, secret: OSHIBridgeWire.channelSecret))
    }

    private func becomeReady() {
        guard state != .ready else { return }
        state = .ready
        if channelIdx != nil { lastError = nil }
        OshiLog.mesh.info("📡 MeshCore: ready, #oshi-bridge in slot \(self.channelIdx ?? -1), id \(String(format: "%08x", self.nodeId))")
        enqueue(MeshCore.getContacts)       // names for the DMs the sync below may bring
        enqueue(MeshCore.syncNextMessage)   // anything the radio queued while we were away
        NotificationCenter.default.post(name: NSNotification.Name("MeshCoreReady"), object: nil)
    }

    private func receive(_ d: MeshCore.ChannelData) {
        guard d.channelIdx == channelIdx, d.dataType == OSHIBridgeWire.dataType,
              let part = OSHIBridgeWire.parse(d.payload), part.sender != nodeId,
              let frame = joiner.push(part), OMP.type(of: frame) == .data,
              let f = OMP.parseData(frame) else { return }
        LoRaCrossBridge.shared.heardOnMeshCore(frame, parsed: f)
        guard let body = reassembler.accept(f), let json = OMP.decodeBody(body),
              var msg = try? JSONDecoder().decode(SecureMessage.self, from: json) else { return }
        msg = msg.meshInboundSafe()   // __MESH_INBOUND_PLAINTEXT_2026_11_20__
        msg.deliveryMethod = .lora
        if let myKey = MessageManager.sharedIdentityManager?.publicKey, !myKey.isEmpty,
           MeshtasticManager.normalizeKey(msg.recipientPublicKey) != MeshtasticManager.normalizeKey(myKey) {
            return
        }
        messagesReceived += 1
        OshiLog.mesh.info("📡 MeshCore: OSHI msg \(msg.id.prefix(8)) (\(f.count) frames)")
        // No loraNodeNum: that key binds MESHTASTIC node numbers to identities, and ours is MeshCore-derived.
        deliver(msg)
    }

    /// __MESHCORE_INTEROP_2026_11_25__ Text from an ordinary MeshCore user — was acknowledged and dropped.
    /// Filed exactly like raw Meshtastic text (`handleInteropText`): plaintext row, `.loraPlaintext`, the
    /// sender an id that is NOT an OSHI identity, so nothing about it can read as end-to-end encrypted.
    private func receiveText(_ t: MeshCore.IncomingText) {
        guard t.txtType == MeshCore.txtTypePlain || t.txtType == MeshCore.txtTypeSignedPlain else { return }
        guard let msg = Self.interopMessage(for: t, contacts: contacts, channels: channels, bridgeIdx: channelIdx,
                                            myKey: MessageManager.sharedIdentityManager?.publicKey ?? "") else { return }
        OshiLog.mesh.info("📡 MeshCore: text from \(msg.senderPublicKey.prefix(18)) → \(msg.recipientPublicKey.prefix(18))")
        deliver(msg)
    }

    /// The stored row for one incoming MeshCore text; nil for empty text and for our own bridge channel.
    /// Static and pure so the id mapping is tested without a radio.
    static func interopMessage(for t: MeshCore.IncomingText, contacts: [MeshCore.Contact],
                               channels: [MeshCore.ChannelInfo], bridgeIdx: Int?, myKey: String,
                               now: Date = Date()) -> SecureMessage? {
        let raw = t.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }
        let senderKey: String, senderName: String, recipientKey: String, recipientAddress: String, text: String
        switch t.source {
        case .contact(let prefix):
            senderKey = MeshCoreInterop.contactKey(forPrefix: prefix, in: contacts)
            senderName = MeshCoreInterop.displayName(forKey: senderKey, contacts: contacts) ?? senderKey
            recipientKey = myKey
            recipientAddress = String(myKey.prefix(10))
            text = raw
        case .channel(let idx):
            guard idx != bridgeIdx else { return nil }
            let slotName = channels.first { $0.idx == idx }?.name ?? ""
            guard let channelKey = MeshCoreInterop.channelKey(name: slotName.isEmpty ? "#\(idx)" : slotName) else { return nil }
            let (speaker, body) = MeshCore.splitChannelSender(raw)
            guard !body.isEmpty else { return nil }
            senderKey = MeshCoreInterop.speakerKey(name: speaker)
            senderName = speaker ?? "?"
            recipientKey = channelKey
            recipientAddress = MeshCoreInterop.channelName(fromKey: channelKey) ?? channelKey
            text = body
        }
        // Stable id: the sender's radio retries with the same timestamp, and a retried copy must not show twice.
        let seed = "\(senderKey)|\(recipientKey)|\(t.timestamp)|\(text)"
        let id = "mc-" + MeshCoreInterop.hex(Data(SHA256.hash(data: Data(seed.utf8))).prefix(16))
        return SecureMessage(
            id: id,
            senderAddress: senderName,
            recipientAddress: recipientAddress,
            encryptedContent: EncryptedMessage(ciphertext: Data(text.utf8).base64EncodedString(),
                                               signature: MeshCoreInterop.signature,
                                               senderPublicKey: senderKey,
                                               timestamp: now),
            timestamp: now,
            isRead: false,
            deliveryStatus: .delivered,
            senderPublicKey: senderKey,
            recipientPublicKey: recipientKey,
            plaintextContent: text,
            deliveryMethod: .loraPlaintext
        )
    }
}

private extension MeshCoreManager.TextTarget {
    var isChannel: Bool { if case .channel = self { return true }; return false }
}

/// The connection failures the settings screen explains (keys of Localizable.strings), shared with Android's
/// `MeshCoreManager.ERROR_*` where they exist.
enum MeshCoreError {
    static let noFreeChannel = "meshcore.noFreeChannel"
    static let channelFailed = "meshcore.channel.failed"
    /// Wrong PIN, cancelled prompt, or an iOS bond the radio no longer has (reflashed, PIN changed).
    static let pairingFailed = "meshcore.error.pairing"
    /// Connected, but nothing answered the companion protocol.
    static let noAnswer = "meshcore.error.noAnswer"
    /// The device has no MeshCore companion service: other firmware (Meshtastic, MeshCore repeater/room server).
    static let notMeshCore = "meshcore.error.notMeshCore"
    /// A working radio went away (out of range, switched off); OSHI keeps reconnecting.
    static let connectionLost = "meshcore.error.connectionLost"

    /// The errors that stop OSHI and wait for the person ("Try again" / "Forget").
    static func needsAction(_ e: String?) -> Bool { e == pairingFailed || e == noAnswer || e == notMeshCore }

    /// Wrong PIN / stale bond, as iOS reports it on the subscription the PIN protects.
    static func isPairingError(_ error: Error) -> Bool {
        if let att = error as? CBATTError {
            return [.insufficientAuthentication, .insufficientEncryption, .insufficientEncryptionKeySize,
                    .insufficientAuthorization].contains(att.code)
        }
        if let cb = error as? CBError {
            return cb.code == .peerRemovedPairingInformation || cb.code == .encryptionTimedOut
        }
        let ns = error as NSError
        return ns.domain == CBATTErrorDomain && [5, 8, 12, 15].contains(ns.code)
    }
}

extension MeshCoreManager: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        #if DEBUG
        if demoSeeded { return }     // screenshot capture: no Bluetooth on the simulator
        #endif
        if central.state == .poweredOn { reconnectIfPaired() } else { reset(); state = .off }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
            peripheral = p
            p.delegate = self
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        if !discovered.contains(where: { $0.identifier == p.identifier }) { discovered.append(p) }
        if RSSI.intValue < 0 { discoveredRSSI[p.identifier] = RSSI.intValue }
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        connectedName = p.name
        state = .configuring
        if lastError == MeshCoreError.connectionLost { lastError = nil }
        p.discoverServices([MeshCore.serviceUUID])
        armSetupWatchdog()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        if let error, MeshCoreError.isPairingError(error) { failSetup(MeshCoreError.pairingFailed); return }
        reset(); state = .off
        if Self.isPaired, !holdReconnect { central.connect(p); peripheral = p; p.delegate = self; state = .connecting }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        // A disconnect while iOS waited for the PIN is the prompt refused or a wrong PIN.
        if awaitingPin { failSetup(MeshCoreError.pairingFailed); return }
        if let error, MeshCoreError.isPairingError(error) { failSetup(MeshCoreError.pairingFailed); return }
        linkLost()
        // Walked out of range or the radio rebooted: keep trying, the way the Meshtastic side does.
        if Self.isPaired, !holdReconnect { central.connect(p); peripheral = p; p.delegate = self; state = .connecting }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == MeshCore.serviceUUID }) else {
            failSetup(MeshCoreError.notMeshCore)
            return
        }
        p.discoverCharacteristics([MeshCore.rxUUID, MeshCore.txUUID], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        rx = s.characteristics?.first { $0.uuid == MeshCore.rxUUID }
        tx = s.characteristics?.first { $0.uuid == MeshCore.txUUID }
        guard let rx, let tx else { failSetup(MeshCoreError.notMeshCore); return }
        writer = { [weak p] d in p?.writeValue(d, for: rx, type: .withResponse) }
        // Subscribing triggers the radio's PIN pairing on first use (iOS shows its own prompt).
        awaitingPin = true
        armSetupWatchdog(extra: 45)   // a person typing a PIN gets time
        p.setNotifyValue(true, for: tx)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == MeshCore.txUUID else { return }
        awaitingPin = false
        if let error {
            failSetup(MeshCoreError.isPairingError(error) ? MeshCoreError.pairingFailed : MeshCoreError.noAnswer)
            return
        }
        guard c.isNotifying else { return }
        awaiting = false
        inFlight = nil
        armSetupWatchdog()
        enqueue(MeshCore.appStart())
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == MeshCore.txUUID, let v = c.value else { return }
        handle(v)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        // An encrypted write refused: the bond is gone (radio reflashed / PIN changed) or the PIN was wrong.
        if let error, MeshCoreError.isPairingError(error) { failSetup(MeshCoreError.pairingFailed) }
    }

    /// Setup must reach SELF_INFO in reasonable time; otherwise the radio is not answering the companion
    /// protocol (wrong firmware mode, busy with another phone) and the person is told so.
    private func armSetupWatchdog(extra: TimeInterval = 0) {
        setupTimer?.invalidate()
        setupTimer = Timer.scheduledTimer(withTimeInterval: Self.setupTimeoutValue + extra, repeats: false) { [weak self] _ in
            guard let self, self.state != .ready else { return }
            self.failSetup(self.awaitingPin ? MeshCoreError.pairingFailed : MeshCoreError.noAnswer)
        }
    }
}

// MARK: - The phone as a bridge

/// When this phone holds a Meshtastic/OSHI radio AND a MeshCore radio, every OSHI DATA frame heard on one
/// side is repeated on the other — what oshi-meshcore-bridge does on a Raspberry Pi, carried in a pocket.
/// Frames keep their origin and msgId, so receivers dedupe them like any relayed frame; a frame already
/// repeated is never repeated again, which stops two bridging phones from ping-ponging it.
final class LoRaCrossBridge {
    static let shared = LoRaCrossBridge()
    static let enabledKey = "oshi.lora.crossBridge"

    private var seen: [String: Date] = [:]
    private static let ttl: TimeInterval = 1800
    private static let maxSeen = 2048

    var isEnabled: Bool { UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true }

    func heardOnMeshtastic(_ frame: Data, parsed f: OMP.DataFrame) {
        guard isEnabled, MeshCoreManager.shared.isReady, firstTime(f) else { return }
        MeshCoreManager.shared.sendFrame(frame)
    }

    func heardOnMeshCore(_ frame: Data, parsed f: OMP.DataFrame) {
        guard isEnabled, MeshtasticManager.shared.isReady, firstTime(f) else { return }
        MeshtasticManager.shared.relayOMPFrame(frame)
    }

    private func firstTime(_ f: OMP.DataFrame, now: Date = Date()) -> Bool {
        // Only broadcast DATA: unicast OMP belongs to the firmware's SACK/receipt machinery, which a phone
        // repeating fragments would confuse. Everything the OSHI apps send is broadcast.
        guard f.dest == OMP.broadcast else { return false }
        // Not the origin: an OSHI Mesh radio re-stamps the origin of what its phone hands it, the msgId it keeps.
        let key = "\(f.msgId):\(f.idx):\(f.count)"
        if let t = seen[key], now.timeIntervalSince(t) < Self.ttl { return false }
        if seen.count >= Self.maxSeen { seen = seen.filter { now.timeIntervalSince($0.value) < Self.ttl } }
        if seen.count >= Self.maxSeen, let oldest = seen.min(by: { $0.value < $1.value })?.key { seen.removeValue(forKey: oldest) }
        seen[key] = now
        return true
    }
}

extension MeshCoreInterop {
    /// The title of a MeshCore thread (contact or channel name) for screens that would otherwise shorten
    /// the raw id; nil for every non-MeshCore key.
    static func threadTitle(forKey key: String) -> String? {
        guard isContactKey(key) || isChannelKey(key) else { return nil }
        return displayName(forKey: key, contacts: MeshCoreManager.shared.contacts)
    }
}

extension MessageManager {
    /// Open the thread for a MeshCore contact or channel from Settings, creating the empty conversation
    /// first when nobody has spoken in it yet (the chat list only navigates to conversations it has).
    @MainActor
    func openMeshCoreThread(key: String, title: String) {
        let wanted = MessageManager.normalizeAliasKey(key)
        if !conversations.contains(where: { MessageManager.normalizeAliasKey($0.participantPublicKey) == wanted }),
           let identity = MessageManager.sharedIdentityManager {
            // A pending conversation survives the list's rebuilds (a bare insert was dropped by the next one).
            addPendingConversation(publicKey: key, identityManager: identity)
        }
        NotificationCenter.default.post(name: NSNotification.Name("OpenChat"), object: nil,
                                        userInfo: ["peerPublicKey": key])
    }
}

// MARK: - What a person sees (__MESHCORE_INTEROP_2026_11_25__)

/// The decisions behind the MeshCore screens, pure so they are tested without a view (Android `MeshCoreUi`):
/// the composer's counter, the note above it, the radio's status line, the words on a failed bubble.
enum MeshCoreUI {
    /// Shown once the text is this close to the one-message limit.
    static let warnBefore = 20

    /// The composer's counter: `chars` of `limit` code points for one radio message, and how many numbered
    /// messages the text will leave as.
    struct ComposerCount: Equatable {
        var chars: Int
        var limit: Int
        var parts: Int
        var visible: Bool { parts > 1 || chars >= limit - MeshCoreUI.warnBefore }
        var tooLong: Bool { parts == 0 && chars > 0 }
    }

    static func composerCount(_ text: String, isChannel: Bool, selfName: String?) -> ComposerCount {
        let budget = isChannel ? MeshCoreInterop.channelTextBytes(nodeName: selfName) : MeshCoreInterop.dmTextBytes
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = t.isEmpty ? 0 : (MeshCoreInterop.splitText(t, maxBytes: budget)?.count ?? 0)
        return ComposerCount(chars: t.unicodeScalars.count, limit: MeshCoreInterop.maxCharsPerMessage, parts: parts)
    }

    /// Who can read this thread, in one calm sentence (Localizable key).
    static func threadNoteKey(key: String, channel: MeshCore.ChannelInfo?) -> String {
        guard MeshCoreInterop.isChannelKey(key) else { return "meshcore.interop.dm.detail" }
        // Unknown on this radio (not connected, or left): say the cautious thing.
        guard let ch = channel, !MeshCoreInterop.isPubliclyReadable(name: ch.name, secret: ch.secret) else {
            return "meshcore.interop.channel.public"
        }
        return "meshcore.interop.channel.private"
    }

    /// Why this thread cannot send right now (Localizable key), or nil when it can.
    static func radioNoteKey(key: String, state: MeshCoreManager.ConnState, isPaired: Bool,
                             channelOnRadio: Bool) -> String? {
        switch state {
        case .ready:
            return MeshCoreInterop.isChannelKey(key) && !channelOnRadio ? "meshcore.radio.channelMissing" : nil
        case .connecting, .configuring:
            return "meshcore.radio.connecting"
        case .off, .scanning:
            return isPaired ? "meshcore.radio.away" : "meshcore.radio.notConnected"
        }
    }

    /// The red line under a failed outgoing bubble. A DM that failed while the radio is connected went
    /// unanswered four times: most likely out of range.
    static func failedTextKey(recipientKey: String, radioReady: Bool) -> String {
        radioReady && MeshCoreInterop.isContactKey(recipientKey) ? "meshcore.failed.dm" : "message.tap_to_retry"
    }

    /// The radio row's status line (Localizable key); nil when the row has nothing to add.
    static func statusKey(state: MeshCoreManager.ConnState, awaitingPin: Bool, lastError: String?,
                          isPaired: Bool, bridgeReady: Bool) -> String? {
        if awaitingPin { return "meshcore.state.pin" }
        switch state {
        case .ready: return bridgeReady ? "meshcore.ready" : "meshcore.ready.textOnly"
        case .configuring: return "meshcore.state.reading"
        case .connecting:
            return lastError == MeshCoreError.connectionLost ? "meshcore.error.connectionLost" : "meshtastic.state.connecting"
        case .scanning: return "meshtastic.searching"
        case .off:
            if let e = lastError, MeshCoreError.needsAction(e) || e == MeshCoreError.connectionLost { return e }
            return isPaired ? "meshtastic.state.connecting" : nil
        }
    }
}
