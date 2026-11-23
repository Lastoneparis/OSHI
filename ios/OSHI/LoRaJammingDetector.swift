//
//  LoRaJammingDetector.swift
//  OSHI — is the radio channel being jammed, merely busy, or fine?
//
//  __ANTI_JAMMING_PHASE1_2026_11_13__ iOS port of Android's
//  `service/lora/LoRaJammingDetector.kt` (commit 2699c942). Same states, same
//  thresholds, same hysteresis; `LoRaJammingDetectorTests` mirrors the Kotlin
//  tests one for one. Keep the two in step: a phone that calls the channel
//  jammed while the other platform calls it clear would route the same
//  conversation two different ways.
//
//  Phase 1 DETECTS and lets the transport policy route around LoRa. Nothing
//  here changes what the radio does on the air (no frequency hopping: that
//  would break Meshtastic interop).
//
//  Pure Foundation, no clock of its own: every input carries its timestamp (ms),
//  so the whole decision is unit-testable. `MeshtasticManager` feeds it and
//  publishes `jamming`.
//
//  INPUTS (everything a radio already tells its phone)
//   - onStats: channel utilisation / TX airtime / noise floor / RX counters.
//       * stock Meshtastic Telemetry.device_metrics (channel_utilization,
//         air_util_tx), every ~60 s;
//       * stock Meshtastic Telemetry.local_stats (+ num_packets_rx,
//         num_packets_rx_bad, noise_floor), every 15 min or on request;
//       * the OSHI Mesh firmware's RADIO frame ("OS" 0x18), every 30 s
//         (`OMP.parseRadio`, docs/omp/OMP-v1.md §4.7 in the firmware repo).
//   - onValidPacket: a packet from ANOTHER node reached the phone decoded —
//     proof the channel still carries traffic.
//   - onAckResult: what the radio said about a unicast we sent with want_ack,
//     or a pending ack that never resolved.
//
//  DECISION
//   RF anomaly — any one of, from readings no older than signalStaleMs:
//     - noise floor ≥ noiseAbsoluteDbm, or ≥ noiseRiseDb above the quietest
//       reading this session (the baseline: a jammer raises the floor, never
//       lowers it);
//     - channel utilisation ≥ jamUtilPct;
//     - between two counter readings, ≥ minBadPackets bad packets making
//       ≥ badRatio of RX.
//   Silence — no valid packet from another node (phone-visible, or the radio's
//     rxGood counter moving) for silenceMs.
//   Link failing — ≥ minAckFailures failed acks in windowMs and no success.
//   Strong RF — noise ≥ baseline + strongNoiseRiseDb or ≥ the absolute
//     ceiling, or utilisation ≥ strongUtilPct.
//
//   PROBABLE_JAMMING ⇐ anomaly ∧ silence ∧ (link failing ∨ strong RF)
//   CONGESTED        ⇐ otherwise: utilisation ≥ congestedUtilPct (held until
//                      it drops below congestedExitUtilPct), TX airtime
//                      ≥ congestedAirTxPct, or an RF anomaly while packets
//                      still arrive (a busy mesh, not a jammed one)
//   CLEAR            ⇐ otherwise.
//
//  HYSTERESIS
//   Once PROBABLE_JAMMING, the state is left only on POSITIVE evidence: a valid
//   packet or a successful ack after entering (immediate), or — after at least
//   exitHoldMs — no RF anomaly in the current readings.
//

import Foundation

final class LoRaJammingDetector {

    enum State: String, Equatable {
        case clear
        case congested
        case probableJamming
    }

    struct Config: Equatable {
        var windowMs: Int64 = 10 * 60_000
        var silenceMs: Int64 = 3 * 60_000
        var signalStaleMs: Int64 = 20 * 60_000
        var congestedUtilPct: Float = 25
        var congestedExitUtilPct: Float = 20
        var congestedAirTxPct: Float = 8
        var jamUtilPct: Float = 50
        var strongUtilPct: Float = 75
        var noiseAbsoluteDbm: Int = -90
        var noiseRiseDb: Int = 15
        var strongNoiseRiseDb: Int = 25
        var badRatio: Double = 0.5
        var minBadPackets: Int64 = 5
        var minAckFailures: Int = 2
        var exitHoldMs: Int64 = 2 * 60_000
    }

    /// One radio report. Any field may be nil: a DeviceMetrics packet carries
    /// utilisation only, an old firmware's LocalStats has no noise floor.
    /// Counters are CUMULATIVE since the radio booted.
    struct Sample: Equatable {
        var atMs: Int64
        var channelUtilPct: Float? = nil
        var airUtilTxPct: Float? = nil
        var noiseFloorDbm: Int? = nil
        var rxTotal: Int64? = nil
        var rxBad: Int64? = nil
    }

    /// The state plus WHY, for the traffic log and for tests.
    struct Assessment: Equatable {
        var state: State
        var reasons: [String]
    }

    private let config: Config

    private var startedAtMs: Int64?
    private var lastValidRxMs: Int64?

    private var utilPct: Float?; private var utilAtMs: Int64 = 0
    private var airTxPct: Float?; private var airTxAtMs: Int64 = 0
    private var noiseDbm: Int?; private var noiseAtMs: Int64 = 0
    private var noiseBaselineDbm: Int?
    private var lastCounters: (total: Int64, bad: Int64)?
    private var badBurst = false; private var badBurstAtMs: Int64 = 0

    private var acks: [(atMs: Int64, success: Bool)] = []

    private var current = Assessment(state: .clear, reasons: [])
    private var jammedSinceMs: Int64?

    init(config: Config = Config()) {
        self.config = config
    }

    var state: State { current.state }
    var assessment: Assessment { current }

    /// Forget everything: a new radio, a reconnect, or a radio that rebooted.
    func reset(nowMs: Int64) {
        startedAtMs = nowMs
        lastValidRxMs = nil
        utilPct = nil; airTxPct = nil; noiseDbm = nil; noiseBaselineDbm = nil
        lastCounters = nil; badBurst = false
        acks.removeAll()
        current = Assessment(state: .clear, reasons: [])
        jammedSinceMs = nil
    }

    @discardableResult
    func onStats(_ s: Sample) -> Assessment {
        ensureStarted(s.atMs)
        if let u = s.channelUtilPct, u.isFinite, u >= 0 { utilPct = u; utilAtMs = s.atMs }
        if let a = s.airUtilTxPct, a.isFinite, a >= 0 { airTxPct = a; airTxAtMs = s.atMs }
        // 0 is what stock LocalStats reports when it has no sample (no presence
        // bit); a real floor is negative. -128 is RadioLib's "invalid", and
        // anything under -127 cannot be a reading.
        if let n = s.noiseFloorDbm, n < 0, n >= -127 {
            noiseDbm = n; noiseAtMs = s.atMs
            // The baseline only learns while we are not calling it jamming: a
            // jammer must not become normal.
            if current.state != .probableJamming {
                noiseBaselineDbm = min(noiseBaselineDbm ?? n, n)
            }
        }
        if let total = s.rxTotal, let bad = s.rxBad {
            let prev = lastCounters
            lastCounters = (total, bad)
            if let prev, total >= prev.total, bad >= prev.bad {
                let dTotal = total - prev.total
                let dBad = bad - prev.bad
                let dGood = dTotal - dBad
                // The radio decoded a packet with a valid CRC: the channel still
                // carries traffic, even if nothing of it was for the phone.
                if dGood > 0 { markValid(s.atMs) }
                badBurst = dBad >= config.minBadPackets
                    && Double(dBad) / Double(max(1, dTotal)) >= config.badRatio
                badBurstAtMs = s.atMs
            } else {
                badBurst = false   // first reading, or the counters went backwards: the radio rebooted
            }
        }
        return evaluate(nowMs: s.atMs)
    }

    @discardableResult
    func onValidPacket(atMs: Int64) -> Assessment {
        ensureStarted(atMs)
        markValid(atMs)
        return evaluate(nowMs: atMs)
    }

    @discardableResult
    func onAckResult(atMs: Int64, success: Bool) -> Assessment {
        ensureStarted(atMs)
        acks.append((atMs, success))
        if acks.count > 64 { acks.removeFirst(acks.count - 64) }
        if success { markValid(atMs) }
        return evaluate(nowMs: atMs)
    }

    @discardableResult
    func evaluate(nowMs: Int64) -> Assessment {
        ensureStarted(nowMs)
        let cutoff = nowMs - config.windowMs
        while let first = acks.first, first.atMs < cutoff { acks.removeFirst() }

        var reasons: [String] = []
        let util = utilPct.flatMap { nowMs - utilAtMs <= config.signalStaleMs ? $0 : nil }
        let airTx = airTxPct.flatMap { nowMs - airTxAtMs <= config.signalStaleMs ? $0 : nil }
        let noise = noiseDbm.flatMap { nowMs - noiseAtMs <= config.signalStaleMs ? $0 : nil }
        let bursty = badBurst && nowMs - badBurstAtMs <= config.signalStaleMs
        let baseline = noiseBaselineDbm

        var noiseRaised = false, noiseStrong = false
        if let noise {
            noiseRaised = noise >= config.noiseAbsoluteDbm
                || (baseline.map { noise - $0 >= config.noiseRiseDb } ?? false)
            noiseStrong = noise >= config.noiseAbsoluteDbm
                || (baseline.map { noise - $0 >= config.strongNoiseRiseDb } ?? false)
        }
        let utilJam = util.map { $0 >= config.jamUtilPct } ?? false
        let utilStrong = util.map { $0 >= config.strongUtilPct } ?? false

        if noiseRaised, let noise {
            reasons.append("noise floor \(noise) dBm (baseline \(baseline.map(String.init) ?? "?"))")
        }
        if utilJam, let util { reasons.append("channel utilisation \(String(format: "%.0f", util))%") }
        if bursty { reasons.append("bad packets dominate RX") }
        let rfAnomaly = noiseRaised || utilJam || bursty

        let sinceValid = nowMs - (lastValidRxMs ?? startedAtMs ?? nowMs)
        let silent = sinceValid >= config.silenceMs
        if silent { reasons.append("no valid packet for \(sinceValid / 1000) s") }

        let failures = acks.filter { !$0.success }.count
        let successes = acks.filter { $0.success }.count
        let linkFailing = failures >= config.minAckFailures && successes == 0
        if linkFailing { reasons.append("\(failures) unanswered sends") }
        let strongRf = noiseStrong || utilStrong

        let jammedNow = rfAnomaly && silent && (linkFailing || strongRf)

        let next: State
        if current.state == .probableJamming {
            let since = jammedSinceMs ?? nowMs
            let linkBack = (lastValidRxMs ?? Int64.min) > since
                || acks.contains { $0.success && $0.atMs > since }
            if linkBack {
                next = congestionOrClear(util: util, airTx: airTx, rfAnomaly: rfAnomaly)
            } else if !rfAnomaly && nowMs - since >= config.exitHoldMs {
                next = congestionOrClear(util: util, airTx: airTx, rfAnomaly: false)
            } else {
                next = .probableJamming
            }
        } else if jammedNow {
            next = .probableJamming
        } else {
            next = congestionOrClear(util: util, airTx: airTx, rfAnomaly: rfAnomaly)
        }
        if next == .probableJamming && current.state != .probableJamming { jammedSinceMs = nowMs }
        if next != .probableJamming { jammedSinceMs = nil }
        current = Assessment(state: next, reasons: reasons)
        return current
    }

    private func congestionOrClear(util: Float?, airTx: Float?, rfAnomaly: Bool) -> State {
        let wasCongested = current.state == .congested
        let utilBusy = util.map {
            $0 >= (wasCongested ? config.congestedExitUtilPct : config.congestedUtilPct)
        } ?? false
        let txBusy = airTx.map { $0 >= config.congestedAirTxPct } ?? false
        return (utilBusy || txBusy || rfAnomaly) ? .congested : .clear
    }

    private func markValid(_ atMs: Int64) {
        lastValidRxMs = max(lastValidRxMs ?? atMs, atMs)
    }

    private func ensureStarted(_ nowMs: Int64) {
        if startedAtMs == nil { startedAtMs = nowMs }
    }
}

// MARK: - "Send over LoRa anyway"

/// __ANTI_JAMMING_OVERRIDE_2026_11_13__ The user's way past the detector.
///
/// The detector is a heuristic, and a heuristic that cannot be overruled turns
/// its own false positive into an outage: someone standing next to a noisy
/// generator, with a working radio and nothing else, would lose LoRa for as
/// long as the noise lasts. So the LoRa screen's warning carries one action,
/// "Send over LoRa anyway", which lifts the jamming exclusion for
/// `duration` (10 minutes) OR until the detector reports CLEAR, whichever comes
/// first. A later jamming episode needs a fresh tap: the override is a
/// decision about THIS episode, not a setting.
///
/// Pure value, no clock of its own, so every edge is a unit test
/// (`LoRaJammingDetectorTests`, "override" cases).
struct LoRaJammingOverride: Equatable {
    static let duration: TimeInterval = 10 * 60

    private(set) var until: Date?

    /// Only meaningful while the channel is called jammed: anywhere else there
    /// is nothing to override, and arming it in advance would silently disable
    /// the next episode's protection. Returns whether it took effect.
    @discardableResult
    mutating func activate(state: LoRaJammingDetector.State, now: Date) -> Bool {
        guard state == .probableJamming else { return false }
        until = now.addingTimeInterval(Self.duration)
        return true
    }

    mutating func cancel() { until = nil }

    /// Feed every detector state change. CLEAR ends the override at once;
    /// CONGESTED does not (the channel has not been proven clean yet).
    mutating func observe(_ state: LoRaJammingDetector.State) {
        if state == .clear { until = nil }
    }

    /// Drop an override whose time is up. Returns true when it just expired, so
    /// the caller can publish the change.
    @discardableResult
    mutating func expire(now: Date) -> Bool {
        guard let u = until, now >= u else { return false }
        until = nil
        return true
    }

    func isActive(now: Date) -> Bool {
        guard let u = until else { return false }
        return now < u
    }

    /// THE question the send path asks: is LoRa off the table right now?
    func excludesLoRa(state: LoRaJammingDetector.State, now: Date) -> Bool {
        state == .probableJamming && !isActive(now: now)
    }
}

// MARK: - Telemetry (TELEMETRY_APP, portnum 67)

/// What a TELEMETRY_APP payload says about the radio channel. Only the fields
/// the jamming detector reads; nil when the payload is neither DeviceMetrics nor
/// LocalStats.
///
///   Telemetry    : 1 fixed32 time, 2 DeviceMetrics, 6 LocalStats
///   DeviceMetrics: 3 float channel_utilization, 4 float air_util_tx
///   LocalStats   : 2 float channel_utilization, 3 float air_util_tx,
///                  5 num_packets_rx (good + bad), 6 num_packets_rx_bad,
///                  15 int32 noise_floor (0 = no sample: there is no presence bit)
struct LoRaRadioTelemetry: Equatable {
    var channelUtilPct: Float?
    var airUtilTxPct: Float?
    var noiseFloorDbm: Int?
    var rxTotal: Int64?
    var rxBad: Int64?
    var isLocalStats: Bool

    static let portnum: UInt64 = 67

    static func parse(_ payload: Data) -> LoRaRadioTelemetry? {
        func float(_ f: ProtoField) -> Float { Float(bitPattern: UInt32(truncatingIfNeeded: f.varintValue)) }
        for f in parseProtoFields(payload) where f.wire == 2 {
            if f.number == 2 {
                var util: Float?, air: Float?
                for m in parseProtoFields(f.payload) {
                    if m.number == 3 && m.wire == 5 { util = float(m) }
                    if m.number == 4 && m.wire == 5 { air = float(m) }
                }
                return LoRaRadioTelemetry(channelUtilPct: util, airUtilTxPct: air, noiseFloorDbm: nil,
                                          rxTotal: nil, rxBad: nil, isLocalStats: false)
            }
            if f.number == 6 {
                var util: Float?, air: Float?, noise: Int?, rx: Int64?, bad: Int64?
                for m in parseProtoFields(f.payload) {
                    switch (m.number, m.wire) {
                    case (2, 5): util = float(m)
                    case (3, 5): air = float(m)
                    case (5, 0): rx = Int64(m.varintValue & 0xFFFF_FFFF)
                    case (6, 0): bad = Int64(m.varintValue & 0xFFFF_FFFF)
                    case (15, 0):
                        let v = Int(Int32(truncatingIfNeeded: m.varintValue))
                        noise = v == 0 ? nil : v
                    default: break
                    }
                }
                // proto3 omits zero counters, so a LocalStats without them means 0, not "unknown".
                return LoRaRadioTelemetry(channelUtilPct: util, airUtilTxPct: air, noiseFloorDbm: noise,
                                          rxTotal: rx ?? 0, rxBad: bad ?? 0, isLocalStats: true)
            }
        }
        return nil
    }

    /// `Telemetry { local_stats {} }` — asks our own radio for its LocalStats
    /// (sent with want_response, addressed to our own node: answered over
    /// Bluetooth, never transmitted).
    static func localStatsRequest() -> Data {
        var w = ProtoWriter()
        w.bytes(6, Data())
        return w.data
    }
}
