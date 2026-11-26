//
//  ServerClock.swift
//  OSHI
//
//  __CALL_CLOCK_SKEW_2026_11_21__ The offset between this device's wall clock and the
//  OSHI server's, learned from the `Date` header of call / push / v2 server responses.
//  Port of Android `network/auth/ServerClock.kt` (commit 10e1ab5f), same constants.
//
//  ## Why (production evidence, call server journal 21/11/2026)
//
//  A phone whose clock was off signed EVERY request with an `x-oshi-timestamp` the
//  server judged "expired" (outside ±30 s), its `/relay-token` was refused, and the calls
//  placed TO it never rang. On iOS the same skew bites in three more places, because
//  call signals carry the SENDER's wall clock (`createCallPacket` stamps 8 bytes of
//  epoch ms) and `validateAndExtractPayload` rejects anything > 5 s in the future or
//  > 30 s old by the RECEIVER's wall clock:
//
//   1. a caller (or callee) more than 5 s fast / 30 s slow cannot ring us;
//   2. a VoIP push whose `callTimestamp` looks > 60 s old is reported and ended, not rung;
//   3. a rejected offer installs no session key — CallKit still rings from the push, the
//      user answers, and the call "connects" with nothing able to decrypt the media.
//
//  The server is the authority on freshness, so the client reads the server's clock:
//  stamp and sign with server time, and read the peer's stamp as server time — but ONLY
//  when this device is ≥ 5 s off. A healthy clock behaves exactly as before.
//
//  Pure arithmetic in `sampleOffset` / `effectiveOffset` / `judgeFreshness` so every
//  boundary is a unit test (`ServerClockTests`).
//

import Foundation

enum ServerClock {

    /// Below this the correction is NOT applied (signing, stamping, freshness). `Date`
    /// has one-second resolution and a sample carries up to RTT/2 of error; a healthy NTP
    /// phone must keep using its own clock exactly as before. Well inside the server's ±30 s.
    static let signingThresholdMs: Int64 = 5_000

    /// A sample from a round trip slower than this is too imprecise to keep.
    static let maxSampleRttMs: Int64 = 10_000

    private static let lock = NSLock()
    private static var rawOffsetStorage: Int64 = 0
    private static var haveSample = false

    // MARK: - Pure arithmetic

    /// Offset (server − local, ms) implied by one response. `Date` is truncated to the
    /// second, so +500 ms centres it; the local instant is the midpoint of the round trip.
    /// `nil` when the sample is unusable.
    static func sampleOffset(serverDateMs: Int64, sentAtMs: Int64, receivedAtMs: Int64) -> Int64? {
        guard serverDateMs > 0, sentAtMs > 0, receivedAtMs >= sentAtMs else { return nil }
        guard receivedAtMs - sentAtMs <= maxSampleRttMs else { return nil }
        let localMid = sentAtMs + (receivedAtMs - sentAtMs) / 2
        return serverDateMs + 500 - localMid
    }

    /// The correction actually applied: none for a sane clock, the full offset otherwise.
    static func effectiveOffset(_ rawOffsetMs: Int64) -> Int64 {
        abs(rawOffsetMs) >= signingThresholdMs ? rawOffsetMs : 0
    }

    /// Parse an RFC 7231 `Date` header (IMF-fixdate). `nil` if absent or malformed.
    static func parseHTTPDate(_ value: String?) -> Date? {
        guard let value, !value.isEmpty else { return nil }
        return httpDateFormatter.date(from: value)
    }

    private static let httpDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE',' dd MMM yyyy HH':'mm':'ss zzz"
        return f
    }()

    // MARK: - State

    /// Record one response. Ignores anything without a usable `Date`, and anything served
    /// from a cache (`Age` header present and > 0): a cached `Date` is the time the copy was
    /// made, not now. Safe to call from any thread.
    static func observe(response: URLResponse?, sentAt: Date, receivedAt: Date = Date()) {
        guard let http = response as? HTTPURLResponse else { return }
        if let age = http.value(forHTTPHeaderField: "Age"), let a = Int(age.trimmingCharacters(in: .whitespaces)), a > 0 {
            return
        }
        guard let date = parseHTTPDate(http.value(forHTTPHeaderField: "Date")) else { return }
        observe(serverDateMs: millis(date), sentAtMs: millis(sentAt), receivedAtMs: millis(receivedAt))
    }

    static func observe(serverDateMs: Int64, sentAtMs: Int64, receivedAtMs: Int64) {
        guard let s = sampleOffset(serverDateMs: serverDateMs, sentAtMs: sentAtMs, receivedAtMs: receivedAtMs) else { return }
        lock.lock()
        let wasCorrecting = haveSample && effectiveOffset(rawOffsetStorage) != 0
        let crossedThreshold = wasCorrecting != (effectiveOffset(s) != 0)
        rawOffsetStorage = s
        haveSample = true
        lock.unlock()
        if crossedThreshold {
            // One line when the correction switches on/off or moves — never per response.
            CallFileLogger.shared.log("🕰️ DIAG_CLOCK_SKEW | serverMinusLocalMs=\(s) | correcting=\(effectiveOffset(s) != 0)")
        }
    }

    /// Current raw estimate (server − local, ms); 0 before the first sample.
    static var rawOffsetMs: Int64 {
        lock.lock(); defer { lock.unlock() }
        return haveSample ? rawOffsetStorage : 0
    }

    /// The correction in force right now (0 unless this device is ≥ 5 s off).
    static var currentEffectiveOffsetMs: Int64 { effectiveOffset(rawOffsetMs) }

    /// "Now" for `x-oshi-timestamp` and for call-packet stamps. Equals the local clock
    /// unless it is ≥ 5 s off the server.
    static func signingNowMs(localNow: Date = Date()) -> Int64 {
        millis(localNow) + currentEffectiveOffsetMs
    }

    /// Test hook.
    static func resetForTesting() {
        lock.lock(); rawOffsetStorage = 0; haveSample = false; lock.unlock()
    }

    static func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }

    // MARK: - Call-signal freshness

    enum Freshness: Equatable {
        /// Accept. `packetTimeLocal` = the instant the signal was made, on THIS device's
        /// clock — what `currentCallStartTime` / `callConnectedTime` are compared against.
        /// `viaRelayStamp` = the sender's own stamp failed and the relay's arrival stamp
        /// (server time) vouched for it instead.
        case fresh(packetTimeLocal: Date, viaRelayStamp: Bool)
        case stale(ageSeconds: Double)
        case future(ageSeconds: Double)
    }

    /// Is a call signal fresh enough to act on?
    ///
    /// - `senderStampMs`: the 8-byte stamp inside the packet. The sender wrote it with its
    ///   own clock, or with server time if it runs this fix and its clock is ≥ 5 s off — so
    ///   it is read as SERVER time and rebased onto our clock by our effective offset.
    /// - `relayStampMs`: the call server's `Date.now()` from the poll / WebSocket envelope,
    ///   when the signal came through the relay. It vouches for a signal whose SENDER's
    ///   clock is wrong (an old build that cannot correct itself) — the window is still
    ///   [−maxFuture, +maxAge] around SERVER time, so a replayed or parked signal is
    ///   refused exactly as before. Mesh signals have no relay stamp.
    ///
    /// With a correct clock on both ends (offset < 5 s) and no relay stamp this is the
    /// pre-fix rule byte for byte.
    static func judgeFreshness(senderStampMs: Int64,
                               relayStampMs: Int64?,
                               localNowMs: Int64,
                               rawOffsetMs: Int64,
                               maxAgeSeconds: Double,
                               maxFutureSeconds: Double = 5.0) -> Freshness {
        let eff = effectiveOffset(rawOffsetMs)
        let senderLocalMs = senderStampMs - eff
        let age = Double(localNowMs - senderLocalMs) / 1000.0
        if age <= maxAgeSeconds && age >= -maxFutureSeconds {
            return .fresh(packetTimeLocal: Date(timeIntervalSince1970: Double(senderLocalMs) / 1000.0), viaRelayStamp: false)
        }
        if let relay = relayStampMs, relay > 0 {
            let relayLocalMs = relay - eff
            let relayAge = Double(localNowMs - relayLocalMs) / 1000.0
            if relayAge <= maxAgeSeconds && relayAge >= -maxFutureSeconds {
                return .fresh(packetTimeLocal: Date(timeIntervalSince1970: Double(relayLocalMs) / 1000.0), viaRelayStamp: true)
            }
        }
        return age > maxAgeSeconds ? .stale(ageSeconds: age) : .future(ageSeconds: age)
    }

    /// VoIP push `callTimestamp` (epoch SECONDS, written by the caller) — its age on the
    /// server's clock when this device is ≥ 5 s off, on the local clock otherwise.
    static func pushAgeSeconds(callTimestampSeconds: Double, localNow: Date = Date(), rawOffsetMs: Int64? = nil) -> Double {
        let eff = effectiveOffset(rawOffsetMs ?? self.rawOffsetMs)
        return (localNow.timeIntervalSince1970 + Double(eff) / 1000.0) - callTimestampSeconds
    }
}
