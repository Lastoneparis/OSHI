//
//  CallSignalGuards.swift
//  OSHI
//
//  Pure decisions about which incoming call signals / media may touch the current call.
//  Port of Android `service/CallSignalGuards.kt` (commit 10e1ab5f). Unit-tested in
//  `CallSignalGuardsTests`.
//

import Foundation

enum CallSignalGuards {

    /// __CROSS_CALL_GUARD_2026_11_21__ The decrypted packet types that act on "the" call
    /// without carrying its callId inside the packet — only the relay / WebSocket
    /// ENVELOPE names the call. An accept, decline or end for another callId must not
    /// touch this one.
    ///
    /// Evidence (call server journal 21/11/2026): 12ba9315 — an iPhone 1.0.43 re-sent the
    /// callEnd of a 7-minute-old call; the relay pushed it over the WebSocket (where iOS had
    /// NO callId check at all — only the HTTP poll path compared ids, and only for terminal
    /// type strings, never for an accept) and the receiver ended the new call. ab49980b — the
    /// decline of the just-cancelled call reached the caller the second it redialled.
    static let guardedPacketTypes: Set<CallPacketType> = [.callAccept, .videoCallAccept, .callDecline, .callEnd]

    /// Trimmed of NUL / control / whitespace (old builds leaked a trailing `\0` into the
    /// callId) and case-folded (iOS mints upper-case UUIDs, Android lower-case).
    static func normalizeCallId(_ id: String?) -> String {
        guard let id else { return "" }
        let trimmed = id.unicodeScalars.drop(while: { isJunk($0) })
        var scalars = Array(trimmed)
        while let last = scalars.last, isJunk(last) { scalars.removeLast() }
        return String(String.UnicodeScalarView(scalars)).lowercased()
    }

    private static func isJunk(_ s: Unicode.Scalar) -> Bool {
        CharacterSet.whitespacesAndNewlines.contains(s) || CharacterSet.controlCharacters.contains(s)
    }

    /// True when `signalCallId` names a call other than `activeCallId` and the signal must
    /// be dropped. An empty id on either side is never "foreign": a legacy / mesh signal
    /// with no envelope keeps today's behaviour.
    static func isForeignCallSignal(_ type: CallPacketType, signalCallId: String?, activeCallId: String?) -> Bool {
        guard guardedPacketTypes.contains(type) else { return false }
        let incoming = normalizeCallId(signalCallId)
        let active = normalizeCallId(activeCallId)
        if incoming.isEmpty || active.isEmpty { return false }
        return incoming != active
    }

    /// __AUTO_ACCEPT_AUTHENTICATED_2026_11_21__ May a media frame stand in for the peer's
    /// accept? Only one that DECRYPTED under this call's session key (the caller mints a
    /// fresh key per call, so a straggler from the previous call — the VPS audio queue is
    /// keyed by recipient only — or anything not from the peer, cannot), while our
    /// outgoing call rings. Before this, the FIRST PACKET OF ANY KIND moved the caller to
    /// `.inCall` before a single byte was authenticated: a cleartext keepAlive, a 9-byte
    /// video control, or a leftover frame of the previous call "answered" the call.
    static func mayAutoAcceptFromMedia(ringingOutgoing: Bool, decryptedUnderSessionKey: Bool) -> Bool {
        ringingOutgoing && decryptedUnderSessionKey
    }

    /// __ANSWER_WITHOUT_KEY_2026_11_21__ May the callee send its accept and go `.inCall`?
    /// Not without the session key the offer carries: every media frame in both directions
    /// is AES-GCM under it. Accepting without it "connects" a call in which nothing can be
    /// encrypted or decrypted — and once `.inCall`, a late copy of the offer from the same
    /// peer is dropped as a duplicate, so the call stays connected and silent until someone
    /// hangs up ("answered, no media").
    static func mayCompleteAccept(hasSessionKey: Bool) -> Bool { hasSessionKey }
}

extension CallSignalGuards {
    /// Mesh / legacy signals carry no callId. Was this one made before the current call
    /// started, by more than `toleranceSeconds` of clock disagreement between the peers?
    /// The sender's stamp is read on the server clock (``ServerClock``) and rebased by our
    /// effective offset. A stamp more than a day away from the call start is not a stamp
    /// (an old build without one, or garbage) and never condemns the signal.
    static func predatesCurrentCall(packetStampMs: Int64, callStart: Date?, effectiveOffsetMs: Int64,
                                    toleranceSeconds: Double = 2.0) -> Bool {
        guard let callStart else { return false }
        let local = Double(packetStampMs - effectiveOffsetMs) / 1000.0
        let start = callStart.timeIntervalSince1970
        guard abs(local - start) < 86_400 else { return false }
        return local < start - toleranceSeconds
    }
}
