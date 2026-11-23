//
//  WatchBridgeProtocol.swift
//  OSHI — shared by the iPhone app, the Apple Watch app and OSHITests.
//
//  __APPLE_WATCH_2027__ The iPhone <-> Apple Watch wire format.
//
//  ONE source file compiled into BOTH the `OSHI` target (the phone answers) and
//  the `OSHI Watch Watch App` target (the watch asks) — same trick as
//  `WatchSendDecision.swift` — so the two sides cannot drift.
//
//  It is the Swift twin of Android's `WearProtocol.kt` (branch feat/wear-os,
//  module wear-bridge): same operations, same statuses, same limits, same
//  seven quick reactions, so the two watch apps behave the same.
//
//  SECURITY MODEL. The watch is a remote VIEW. It never holds an identity key,
//  never talks to an OSHI server and never encrypts anything. It sends a small
//  request over WatchConnectivity (`WCSession.sendMessage`, only ever between
//  the paired iPhone and the watch app embedded in THIS iOS app), and the phone
//  answers from its local store or performs the send / reaction through its
//  own existing send path. The phone refuses everything while the "Smartwatch"
//  setting is OFF (the default) or while OSHI's app lock is engaged. Nothing
//  that comes back is written to the watch's disk.
//
//  Pure Foundation, no WatchConnectivity / UIKit, so OSHITests exercises it.
//

import Foundation

nonisolated enum WatchBridge {
    static let version = 1

    /// Key of the one entry a bridge message carries in the WCSession
    /// dictionary. The legacy `type`-keyed messages never use it, so the two
    /// cannot be confused.
    static let payloadKey = "oshiw"

    /// `applicationContext` key of the phone's latest `WatchBridgeSnapshot`.
    static let snapshotKey = "oshiw.snapshot"

    // ---- limits (enforced by both sides) -------------------------------

    /// `WCSession.sendMessage` refuses dictionaries above ~65 KB; stay well under.
    static let maxPayloadBytes = 60_000
    static let maxConversations = 30
    static let maxThreadMessages = 20
    static let maxSendChars = 2_000
    static let maxPreviewChars = 120
    static let maxBubbleChars = 600
    static let maxNameChars = 64
    /// The widget shows at most this many conversation NAMES (never text).
    static let maxSnapshotNames = 3

    /// The quick reactions a watch may send, in display order: the OSHI "O"
    /// then the six O-bubble mascots' WIRE emoji — the exact strings of the
    /// phone's `ReactionEmoji.quickReactions` (`[.oshiO] +
    /// MascotWirePolicy.quickBar`), byte for byte the same as Android's
    /// `WearProtocol.QUICK_REACTIONS`. `WatchBridgeProtocolTests` fails if the
    /// two ever differ. The phone refuses anything else.
    static let quickReactions: [String] = [
        "\u{2B55}",            // ⭕ OSHI O (no variation selector)
        "\u{2764}\u{FE0F}",    // ❤️
        "\u{1F602}",           // 😂
        "\u{1F622}",           // 😢
        "\u{1F44D}",           // 👍
        "\u{1F62E}",           // 😮
        "\u{1F525}",           // 🔥
    ]

    /// Clip to `max` characters, adding "…" when cut. Works on grapheme
    /// clusters, so an emoji or a flag is never split in half.
    static func clip(_ s: String, _ max: Int) -> String {
        guard s.count > max, max > 1 else { return s }
        return String(s.prefix(max - 1)) + "…"
    }
}

nonisolated enum WatchOp: String, Codable, CaseIterable {
    case list, thread, send, react
    /// The watch opened a thread / tapped "Mark read" on a notification.
    case markRead = "read"
}

/// What happened to a request. Every status except `.ok` means "show a status
/// screen, no data".
nonisolated enum WatchStatus: String, Codable, CaseIterable {
    case ok
    /// "Smartwatch" is off in OSHI on the iPhone (the default).
    case disabled
    /// OSHI's app lock is engaged on the iPhone.
    case locked
    /// The phone app has no identity loaded yet (cold, not set up, wiped).
    case unavailable
    /// Malformed / over-limit / not allowed (e.g. a reaction outside the quick set).
    case invalid
    /// The conversation or message no longer exists.
    case notFound = "not_found"
    /// This conversation cannot be answered (system thread).
    case readOnly = "read_only"
    case error

    /// Unknown wire values read as `.error`, never as `.ok`.
    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = WatchStatus(rawValue: raw) ?? .error
    }
}

nonisolated enum WatchConvKind: String, Codable {
    case direct = "dm"
    case group
}

/// Media is never sent to the watch: only what KIND it was, drawn as a
/// labelled placeholder.
nonisolated enum WatchMedia: String, Codable, CaseIterable {
    case photo, video, voice, gif, file, location, contact
}

nonisolated struct WatchRequest: Codable, Equatable {
    var v: Int = WatchBridge.version
    let rid: String
    let op: WatchOp
    var kind: WatchConvKind? = nil
    /// Contact public key (direct) or group id (group).
    var cid: String? = nil
    var text: String? = nil
    var mid: String? = nil
    var emoji: String? = nil

    /// Shape check shared by both sides. The phone calls it before touching
    /// anything; a request that fails it is answered `.invalid`.
    var isWellFormed: Bool {
        guard v == WatchBridge.version, !rid.isEmpty, rid.count <= 64 else { return false }
        if let cid, cid.isEmpty || cid.count > 256 { return false }
        switch op {
        case .list:
            return true
        case .thread, .markRead:
            return kind != nil && cid != nil
        case .send:
            guard kind != nil, cid != nil, let text else { return false }
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return !trimmed.isEmpty && text.count <= WatchBridge.maxSendChars
        case .react:
            guard kind != nil, cid != nil, let mid, !mid.isEmpty, mid.count <= 128,
                  let emoji else { return false }
            return WatchBridge.quickReactions.contains(emoji)
        }
    }

    func encode() -> [String: Any] {
        guard let data = try? JSONEncoder().encode(self) else { return [:] }
        return [WatchBridge.payloadKey: data]
    }

    /// nil for anything that is not a request this version understands.
    static func decode(_ message: [String: Any]) -> WatchRequest? {
        guard let data = message[WatchBridge.payloadKey] as? Data,
              !data.isEmpty, data.count <= WatchBridge.maxPayloadBytes,
              let r = try? JSONDecoder().decode(WatchRequest.self, from: data),
              r.v == WatchBridge.version else { return nil }
        return r
    }
}

nonisolated struct WatchConversation: Codable, Equatable, Identifiable {
    let kind: WatchConvKind
    /// Contact public key or group id.
    let cid: String
    let name: String
    /// One line, already rendered by the phone ("📷 Photo", a call summary, the text).
    let preview: String
    let unread: Int
    /// Seconds since 1970.
    let ts: Double
    /// The key the avatar colour is derived from (`AvatarIdentity.slot`).
    let akey: String
    /// Last line was mine (drawn "You: …").
    var mine: Bool = false
    /// Read-only (system thread): no Reply button.
    var ro: Bool = false
    /// The last message was a media of this kind (drawn as a localized label).
    var pmedia: WatchMedia? = nil
    /// The last message was view-once (its content never crosses).
    var pvo: Bool = false

    var id: String { kind.rawValue + ":" + cid }
}

nonisolated struct WatchMessage: Codable, Equatable, Identifiable {
    let mid: String
    let mine: Bool
    /// Sender display name; group threads only.
    var sender: String? = nil
    var skey: String? = nil
    /// Empty for media without caption and for view-once messages.
    var text: String
    var media: WatchMedia? = nil
    /// View-once: the content NEVER crosses; the watch says "open it on your phone".
    var vo: Bool = false
    let ts: Double
    /// Distinct reactions on it, at most three, for the chip under the bubble.
    var reacts: [String] = []
    var myr: String? = nil

    var id: String { mid }
}

nonisolated struct WatchResponse: Codable, Equatable {
    var v: Int = WatchBridge.version
    let rid: String
    let status: WatchStatus
    var convs: [WatchConversation] = []
    var msgs: [WatchMessage] = []
    /// Thread title (THREAD) so the watch can draw it after a notification tap.
    var title: String? = nil
    /// Thread is read-only (THREAD).
    var ro: Bool? = nil

    static func status(_ rid: String, _ status: WatchStatus) -> WatchResponse {
        WatchResponse(rid: rid, status: status)
    }

    /// Encodes, clipping every string and trimming the OLDEST thread messages
    /// / the LAST conversations until the payload fits, so a thread of long
    /// messages degrades to fewer bubbles instead of failing to send.
    func encode() -> [String: Any] {
        var r = self
        r.title = title.map { WatchBridge.clip($0, WatchBridge.maxNameChars) }
        r.convs = Array(convs.prefix(WatchBridge.maxConversations)).map { c in
            var c2 = WatchConversation(kind: c.kind, cid: c.cid,
                                       name: WatchBridge.clip(c.name, WatchBridge.maxNameChars),
                                       preview: WatchBridge.clip(c.preview, WatchBridge.maxPreviewChars),
                                       unread: max(0, c.unread), ts: c.ts, akey: c.akey)
            c2.mine = c.mine; c2.ro = c.ro; c2.pmedia = c.pmedia; c2.pvo = c.pvo
            return c2
        }
        r.msgs = Array(msgs.suffix(WatchBridge.maxThreadMessages)).map { m in
            var m2 = m
            m2.text = WatchBridge.clip(m.text, WatchBridge.maxBubbleChars)
            m2.sender = m.sender.map { WatchBridge.clip($0, WatchBridge.maxNameChars) }
            m2.reacts = Array(m.reacts.prefix(3))
            return m2
        }
        let encoder = JSONEncoder()
        while true {
            guard let data = try? encoder.encode(r) else { return [:] }
            if data.count <= WatchBridge.maxPayloadBytes || (r.convs.isEmpty && r.msgs.isEmpty) {
                return [WatchBridge.payloadKey: data]
            }
            if !r.msgs.isEmpty { r.msgs.removeFirst() } else { r.convs.removeLast() }
        }
    }

    static func decode(_ message: [String: Any]) -> WatchResponse? {
        guard let data = message[WatchBridge.payloadKey] as? Data,
              !data.isEmpty, data.count <= WatchBridge.maxPayloadBytes,
              let r = try? JSONDecoder().decode(WatchResponse.self, from: data),
              r.v == WatchBridge.version else { return nil }
        return r
    }
}

/// What the phone pushes in `applicationContext` for the watch's widget /
/// complication: a state, a NUMBER and at most three conversation NAMES —
/// never a message, a preview or a key. When the bridge is off or the app is
/// locked the phone pushes a snapshot with no names and no count, which
/// REPLACES (the system keeps only the latest context) the previous one.
nonisolated struct WatchBridgeSnapshot: Codable, Equatable {
    var v: Int = WatchBridge.version
    let status: WatchStatus
    var unread: Int = 0
    var names: [String] = []
    /// Seconds since 1970, so an old context is never mistaken for a new one.
    let at: Double

    /// The only shape the phone is allowed to produce: names and count are
    /// dropped unless the status is `.ok`.
    static func make(status: WatchStatus, unread: Int, names: [String], at: Date = Date()) -> WatchBridgeSnapshot {
        guard status == .ok else { return WatchBridgeSnapshot(status: status, at: at.timeIntervalSince1970) }
        return WatchBridgeSnapshot(
            status: .ok,
            unread: max(0, unread),
            names: Array(names.prefix(WatchBridge.maxSnapshotNames)).map { WatchBridge.clip($0, 24) },
            at: at.timeIntervalSince1970)
    }

    func encode() -> [String: Any] {
        guard let data = try? JSONEncoder().encode(self) else { return [:] }
        return [WatchBridge.snapshotKey: data]
    }

    static func decode(_ context: [String: Any]) -> WatchBridgeSnapshot? {
        guard let data = context[WatchBridge.snapshotKey] as? Data,
              data.count <= 4_096,
              let s = try? JSONDecoder().decode(WatchBridgeSnapshot.self, from: data),
              s.v == WatchBridge.version else { return nil }
        return make(status: s.status, unread: s.unread, names: s.names,
                    at: Date(timeIntervalSince1970: s.at))
    }
}
