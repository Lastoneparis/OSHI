//
//  LoRaJammingDetectorTests.swift
//  OSHITests
//
//  __ANTI_JAMMING_PHASE1_2026_11_13__ The detector's contract, on synthetic
//  timelines. One test per Android `LoRaJammingDetectorTest` case, same names,
//  same numbers, so the two platforms cannot drift apart unnoticed.
//
//  The distinction that matters most: a BUSY mesh (high utilisation, packets
//  still arriving) is CONGESTED, a LONELY radio (silence, unanswered sends,
//  quiet RF) is CLEAR, and only RF trouble together with silence and failing
//  sends — or overwhelming RF trouble with silence — is PROBABLE_JAMMING.
//
//  Also here: the "Send over LoRa anyway" override, the TELEMETRY_APP parser and
//  the OMP RADIO frame (firmware vector).
//

import XCTest
@testable import OSHI

final class LoRaJammingDetectorTests: XCTestCase {

    private typealias Sample = LoRaJammingDetector.Sample
    private let min: Int64 = 60_000
    private var d: LoRaJammingDetector!

    override func setUp() {
        super.setUp()
        d = LoRaJammingDetector()
        d.reset(nowMs: 0)
    }

    // MARK: - Detector (mirrors LoRaJammingDetectorTest.kt)

    func testStartsClear() {
        XCTAssertEqual(d.evaluate(nowMs: 10 * min).state, .clear)
    }

    func testBusyMeshIsCongestedNotJammed() {
        var t: Int64 = 0
        for _ in 0..<20 {
            t += 30_000
            d.onValidPacket(atMs: t)
            d.onStats(Sample(atMs: t, channelUtilPct: 65, noiseFloorDbm: -112))
        }
        XCTAssertEqual(d.state, .congested)
    }

    func testALonelyRadioWithQuietRfIsNotJammed() {
        d.onStats(Sample(atMs: 1_000, channelUtilPct: 2, noiseFloorDbm: -118))
        d.onAckResult(atMs: 4 * min, success: false)
        d.onAckResult(atMs: 5 * min, success: false)
        d.onAckResult(atMs: 6 * min, success: false)
        XCTAssertEqual(d.evaluate(nowMs: 7 * min).state, .clear)
    }

    func testRaisedNoiseWithSilenceAndFailedSendsIsJamming() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -118))          // baseline
        d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -100))        // +18 dB: anomaly, not "strong"
        XCTAssertNotEqual(d.state, .probableJamming)                 // no failed sends yet
        d.onAckResult(atMs: 4 * min + 10_000, success: false)
        let a = d.onAckResult(atMs: 5 * min, success: false)
        XCTAssertEqual(a.state, .probableJamming)
        XCTAssertTrue(a.reasons.joined(separator: ", ").contains("noise floor"))
    }

    func testRaisedNoiseWithoutFailedSendsIsOnlyCongested() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -118))
        XCTAssertEqual(d.onStats(Sample(atMs: 5 * min, noiseFloorDbm: -100)).state, .congested)
    }

    func testOverwhelmingNoiseWithSilenceIsJammingEvenWithoutSends() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -88)).state, .probableJamming)
    }

    func testOverwhelmingNoiseWhilePacketsStillArriveIsNotJamming() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        d.onValidPacket(atMs: 3 * min + 50_000)
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -88)).state, .congested)
    }

    func testSaturatedChannelWithSilenceIsJamming() {
        // A LoRa-modulated jammer keeps the channel "busy" (CAD sees preambles) and nothing decodes.
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, channelUtilPct: 90)).state, .probableJamming)
    }

    func testBadPacketBurstWithSilenceAndFailuresIsJamming() {
        d.onStats(Sample(atMs: 1_000, rxTotal: 100, rxBad: 3))
        d.onAckResult(atMs: 3 * min, success: false)
        d.onAckResult(atMs: 3 * min + 30_000, success: false)
        // 24 new packets, 22 of them bad: the 2 good ones count as traffic at
        // this sample, so silence is measured from here and jamming needs it to persist.
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, rxTotal: 124, rxBad: 25)).state, .congested)
        d.onAckResult(atMs: 7 * min + 10_000, success: false)
        d.onAckResult(atMs: 7 * min + 20_000, success: false)
        XCTAssertEqual(d.onStats(Sample(atMs: 7 * min + 30_000, rxTotal: 140, rxBad: 41)).state, .probableJamming)
    }

    func testMovingGoodCounterIsTrafficEvenIfNothingReachedThePhone() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117, rxTotal: 10, rxBad: 0))
        d.onAckResult(atMs: 3 * min, success: false); d.onAckResult(atMs: 3 * min + 1, success: false)
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -100, rxTotal: 15, rxBad: 0)).state, .congested)
    }

    func testCountersGoingBackwardsAreARebootNotABurst() {
        d.onStats(Sample(atMs: 1_000, rxTotal: 500, rxBad: 100))
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, rxTotal: 10, rxBad: 9)).state, .clear)
    }

    func testAValidPacketEndsJammingAtOnce() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -85)).state, .probableJamming)
        XCTAssertEqual(d.evaluate(nowMs: 5 * min).state, .probableJamming)
        XCTAssertNotEqual(d.onValidPacket(atMs: 5 * min + 1).state, .probableJamming)
    }

    func testASuccessfulAckEndsJamming() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -85))
        XCTAssertNotEqual(d.onAckResult(atMs: 5 * min, success: true).state, .probableJamming)
    }

    func testQuietRfEndsJammingOnlyAfterTheHold() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -85)).state, .probableJamming)
        // Noise back to normal 30 s later: too soon, a pulsed jammer would make the state flap.
        XCTAssertEqual(d.onStats(Sample(atMs: 4 * min + 30_000, noiseFloorDbm: -116)).state, .probableJamming)
        XCTAssertEqual(d.onStats(Sample(atMs: 6 * min + 1_000, noiseFloorDbm: -116)).state, .clear)
    }

    func testTheBaselineDoesNotLearnTheJammer() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -85))
        // Hours of jamming at -100 must still read as +17 dB over the -117 baseline, not as the new normal.
        for i in 1...10 { d.onStats(Sample(atMs: 4 * min + Int64(i) * 30_000, noiseFloorDbm: -100)) }
        d.onAckResult(atMs: 10 * min, success: false); d.onAckResult(atMs: 10 * min + 1, success: false)
        XCTAssertEqual(d.evaluate(nowMs: 10 * min + 2).state, .probableJamming)
    }

    func testStaleReadingsStopCounting() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        d.onStats(Sample(atMs: 4 * min, channelUtilPct: 60))
        d.onAckResult(atMs: 4 * min + 1, success: false); d.onAckResult(atMs: 4 * min + 2, success: false)
        XCTAssertEqual(d.state, .probableJamming)
        // No utilisation report for 25 minutes: the anomaly is no longer evidenced.
        XCTAssertEqual(d.evaluate(nowMs: 30 * min).state, .clear)
    }

    func testCongestionHasHysteresis() {
        d.onValidPacket(atMs: 1_000)
        XCTAssertEqual(d.onStats(Sample(atMs: 2_000, channelUtilPct: 26)).state, .congested)
        XCTAssertEqual(d.onStats(Sample(atMs: 3_000, channelUtilPct: 22)).state, .congested)
        XCTAssertEqual(d.onStats(Sample(atMs: 4_000, channelUtilPct: 18)).state, .clear)
        XCTAssertEqual(d.onStats(Sample(atMs: 5_000, channelUtilPct: 22)).state, .clear)
    }

    func testOwnAirtimeNearTheDutyCycleIsCongestion() {
        d.onValidPacket(atMs: 1_000)
        XCTAssertEqual(d.onStats(Sample(atMs: 2_000, airUtilTxPct: 9)).state, .congested)
    }

    func testPlaceholderNoiseValuesAreIgnored() {
        // Stock LocalStats sends 0 when it has no sample; -128 is RadioLib's "invalid".
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: 0))
        d.onStats(Sample(atMs: 2_000, noiseFloorDbm: -128))
        XCTAssertEqual(d.evaluate(nowMs: 5 * min).state, .clear)
    }

    func testOldAckFailuresFallOutOfTheWindow() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        d.onAckResult(atMs: 2 * min, success: false); d.onAckResult(atMs: 2 * min + 1, success: false)
        // 15 minutes later the failures are history; the raised noise alone is only congestion.
        XCTAssertEqual(d.onStats(Sample(atMs: 17 * min, noiseFloorDbm: -100)).state, .congested)
    }

    func testResetForgetsEverything() {
        d.onStats(Sample(atMs: 1_000, noiseFloorDbm: -117))
        d.onStats(Sample(atMs: 4 * min, noiseFloorDbm: -85))
        d.reset(nowMs: 5 * min)
        XCTAssertEqual(d.state, .clear)
        XCTAssertEqual(d.evaluate(nowMs: 6 * min).state, .clear)
    }

    // MARK: - "Send over LoRa anyway" (iOS addition, __ANTI_JAMMING_OVERRIDE_2026_11_13__)

    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testWithoutOverrideAJammedChannelExcludesLoRaAndNothingElseDoes() {
        let o = LoRaJammingOverride()
        XCTAssertTrue(o.excludesLoRa(state: .probableJamming, now: t0))
        XCTAssertFalse(o.excludesLoRa(state: .congested, now: t0))
        XCTAssertFalse(o.excludesLoRa(state: .clear, now: t0))
    }

    func testOverrideLiftsTheExclusionForTenMinutes() {
        var o = LoRaJammingOverride()
        XCTAssertTrue(o.activate(state: .probableJamming, now: t0))
        XCTAssertEqual(LoRaJammingOverride.duration, 600)
        XCTAssertFalse(o.excludesLoRa(state: .probableJamming, now: t0))
        XCTAssertFalse(o.excludesLoRa(state: .probableJamming, now: t0.addingTimeInterval(599)))
        // Time is up: the protection is back, even before anyone calls expire().
        XCTAssertTrue(o.excludesLoRa(state: .probableJamming, now: t0.addingTimeInterval(600)))
        XCTAssertFalse(o.expire(now: t0.addingTimeInterval(599)))
        XCTAssertTrue(o.expire(now: t0.addingTimeInterval(600)))
        XCTAssertNil(o.until)
    }

    func testOverrideEndsWhenTheChannelIsClearAndTheNextEpisodeIsProtectedAgain() {
        var o = LoRaJammingOverride()
        o.activate(state: .probableJamming, now: t0)
        o.observe(.congested)                      // not proven clean yet: still overridden
        XCTAssertTrue(o.isActive(now: t0.addingTimeInterval(60)))
        o.observe(.clear)
        XCTAssertNil(o.until)
        // Jammed again two minutes later: a fresh tap is needed.
        XCTAssertTrue(o.excludesLoRa(state: .probableJamming, now: t0.addingTimeInterval(120)))
    }

    func testOverrideCannotBeArmedInAdvance() {
        var o = LoRaJammingOverride()
        XCTAssertFalse(o.activate(state: .clear, now: t0))
        XCTAssertFalse(o.activate(state: .congested, now: t0))
        XCTAssertNil(o.until)
        XCTAssertTrue(o.excludesLoRa(state: .probableJamming, now: t0.addingTimeInterval(1)))
    }

    func testCancellingTheOverrideRestoresTheExclusion() {
        var o = LoRaJammingOverride()
        o.activate(state: .probableJamming, now: t0)
        o.cancel()
        XCTAssertTrue(o.excludesLoRa(state: .probableJamming, now: t0.addingTimeInterval(1)))
    }

    /// The override feeds the transport policy through `radioJammed`: with it
    /// active the radio is back in the order exactly where it was.
    func testOverrideRestoresTheRadioInTheTransportOrder() {
        var o = LoRaJammingOverride()
        let state = LoRaJammingDetector.State.probableJamming
        func conditions(_ now: Date) -> TransportPolicy.Conditions {
            .init(physicalPath: false, peerOnMesh: false, radioReady: true,
                  peerLikelyOnline: true, carriesMedia: false,
                  radioJammed: o.excludesLoRa(state: state, now: now))
        }
        XCTAssertEqual(TransportPolicy.order(conditions(t0)), [])
        o.activate(state: state, now: t0)
        XCTAssertEqual(TransportPolicy.order(conditions(t0.addingTimeInterval(1))), [.loRa])
        XCTAssertEqual(TransportPolicy.order(conditions(t0.addingTimeInterval(601))), [])
    }

    // MARK: - TELEMETRY_APP (mirrors LoRaPacketParserTest.kt additions)

    private func f32(_ x: Float) -> UInt32 { x.bitPattern }

    func testLocalStatsCarriesCountersAndANegativeNoiseFloor() {
        var stats = ProtoWriter()
        stats.varint(1, 3600)                                   // uptime_seconds
        stats.fixed32(2, f32(31.5))                              // channel_utilization
        stats.fixed32(3, f32(2.25))                              // air_util_tx
        stats.varint(4, 77)                                      // num_packets_tx
        stats.varint(5, 120)                                     // num_packets_rx (good + bad)
        stats.varint(6, 9)                                       // num_packets_rx_bad
        stats.varint(15, UInt64(bitPattern: -104))               // noise_floor, int32: 10-byte varint
        var telemetry = ProtoWriter()
        telemetry.fixed32(1, 1_700_000_000)
        telemetry.bytes(6, stats.data)
        let t = try! XCTUnwrap(LoRaRadioTelemetry.parse(telemetry.data))
        XCTAssertTrue(t.isLocalStats)
        XCTAssertEqual(t.channelUtilPct ?? -1, 31.5, accuracy: 0.001)
        XCTAssertEqual(t.airUtilTxPct ?? -1, 2.25, accuracy: 0.001)
        XCTAssertEqual(t.rxTotal, 120)
        XCTAssertEqual(t.rxBad, 9)
        XCTAssertEqual(t.noiseFloorDbm, -104)
    }

    func testLocalStatsWithoutNoiseSampleOrCountersMeansUnknownNoiseAndZeroCounters() {
        var stats = ProtoWriter(); stats.fixed32(2, f32(5))
        var telemetry = ProtoWriter(); telemetry.bytes(6, stats.data)
        let t = try! XCTUnwrap(LoRaRadioTelemetry.parse(telemetry.data))
        XCTAssertNil(t.noiseFloorDbm)
        XCTAssertEqual(t.rxTotal, 0)
        XCTAssertEqual(t.rxBad, 0)
    }

    func testDeviceMetricsCarriesUtilisationOnly() {
        var dm = ProtoWriter()
        dm.varint(1, 101); dm.fixed32(2, f32(4.1)); dm.fixed32(3, f32(12)); dm.fixed32(4, f32(1.5))
        var telemetry = ProtoWriter(); telemetry.bytes(2, dm.data)
        let t = try! XCTUnwrap(LoRaRadioTelemetry.parse(telemetry.data))
        XCTAssertFalse(t.isLocalStats)
        XCTAssertEqual(t.channelUtilPct ?? -1, 12, accuracy: 0.001)
        XCTAssertEqual(t.airUtilTxPct ?? -1, 1.5, accuracy: 0.001)
        XCTAssertNil(t.rxTotal)
        XCTAssertNil(t.noiseFloorDbm)
    }

    func testEnvironmentTelemetryIsNotRadioTelemetry() {
        var env = ProtoWriter(); env.fixed32(1, f32(21))
        var telemetry = ProtoWriter(); telemetry.bytes(3, env.data)
        XCTAssertNil(LoRaRadioTelemetry.parse(telemetry.data))
    }

    func testLocalStatsRequestIsAnEmptyLocalStats() {
        XCTAssertEqual(LoRaRadioTelemetry.localStatsRequest(), Data([0x32, 0x00]))
        XCTAssertEqual(LoRaRadioTelemetry.portnum, 67)
    }

    // MARK: - OMP RADIO frame (mirrors OshiMeshProtocolTest.radioFrameLayoutMatchesFirmware)

    /// Same bytes as test_radio_stats_roundtrip in the firmware's
    /// test/test_oshi_protocol and the example in OMP-v1.md §4.7: noise is a
    /// SIGNED byte (-97 dBm = 0x9F), -128 means "no sample", counters little-endian.
    func testRadioFrameLayoutMatchesFirmware() {
        let expect = Data([0x4F, 0x53, 0x18, 0x9F, 0x25, 0x04, 0xD2, 0x04, 0, 0, 0x38, 0, 0, 0,
                           0x15, 0x03, 0, 0, 0x02, 0])
        let stats = OMP.RadioStats(noiseFloorDbm: -97, channelUtilPct: 37, airUtilTxPct: 4,
                                   rxGood: 1234, rxBad: 56, txGood: 789, txDrop: 2)
        XCTAssertEqual(OMP.radioFrame(stats), expect)
        XCTAssertEqual(OMP.type(of: expect), .radio)
        XCTAssertEqual(OMP.parseRadio(expect), stats)
        // Longer frames are a newer firmware appending fields: still read.
        XCTAssertEqual(OMP.parseRadio(expect + Data([1, 2, 3])), stats)
        // Truncated: rejected.
        XCTAssertNil(OMP.parseRadio(expect.prefix(19)))
        // No idle-RSSI sample yet.
        var unknown = expect; unknown[3] = 0x80
        XCTAssertNil(OMP.parseRadio(unknown)?.noiseFloorDbm)
        XCTAssertNotNil(OMP.parseRadio(unknown))
        // A BEACON is not a RADIO frame.
        XCTAssertNil(OMP.parseRadio(OMP.beaconProbe()))
    }
}
