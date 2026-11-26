//
//  ServerClockTests.swift
//  OSHITests
//
//  __CALL_CLOCK_SKEW_2026_11_21__ Every boundary of the server-clock correction: the
//  offset sample, the 5 s threshold below which nothing changes, signing, the call-signal
//  freshness window (sender stamp, relay stamp, replay still refused) and the push age.
//

import XCTest
@testable import OSHI

final class ServerClockTests: XCTestCase {

    override func setUp() { super.setUp(); ServerClock.resetForTesting() }
    override func tearDown() { ServerClock.resetForTesting(); super.tearDown() }

    private let now: Int64 = 1_795_000_000_000   // Nov 2026, ms

    // MARK: sample

    func testSampleCentresTheTruncatedDateOnTheRoundTripMidpoint() {
        // Server says 12:00:00 (really 12:00:00.000–.999), request took 200 ms.
        XCTAssertEqual(ServerClock.sampleOffset(serverDateMs: now, sentAtMs: now - 100, receivedAtMs: now + 100), 500)
        // A phone 90 s fast.
        XCTAssertEqual(ServerClock.sampleOffset(serverDateMs: now, sentAtMs: now + 90_000, receivedAtMs: now + 90_000), -89_500)
    }

    func testUnusableSamplesAreRefused() {
        XCTAssertNil(ServerClock.sampleOffset(serverDateMs: 0, sentAtMs: now, receivedAtMs: now))
        XCTAssertNil(ServerClock.sampleOffset(serverDateMs: now, sentAtMs: now, receivedAtMs: now - 1))
        XCTAssertNil(ServerClock.sampleOffset(serverDateMs: now, sentAtMs: now, receivedAtMs: now + 10_001))
        XCTAssertNotNil(ServerClock.sampleOffset(serverDateMs: now, sentAtMs: now, receivedAtMs: now + 10_000))
    }

    func testHTTPDateParses() {
        let d = ServerClock.parseHTTPDate("Sat, 21 Nov 2026 18:23:57 GMT")
        XCTAssertNotNil(d)
        XCTAssertEqual(d.map { Int64($0.timeIntervalSince1970) }, 1_795_285_437)
        XCTAssertNil(ServerClock.parseHTTPDate("yesterday"))
        XCTAssertNil(ServerClock.parseHTTPDate(nil))
    }

    func testObserveReadsTheDateHeaderAndIgnoresCachedResponses() {
        let url = URL(string: "https://oshi-messenger.com/voip/signals/x")!
        let sent = Date(timeIntervalSince1970: 1_795_285_437 - 120)   // phone 2 min slow
        let fresh = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil,
                                    headerFields: ["Date": "Sat, 21 Nov 2026 18:23:57 GMT"])
        let cached = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil,
                                     headerFields: ["Date": "Sat, 21 Nov 2026 18:00:00 GMT", "Age": "1437"])
        ServerClock.observe(response: cached, sentAt: sent, receivedAt: sent)
        XCTAssertEqual(ServerClock.rawOffsetMs, 0, "a cached Date is not now")
        ServerClock.observe(response: fresh, sentAt: sent, receivedAt: sent)
        XCTAssertEqual(ServerClock.rawOffsetMs, 120_500)
    }

    // MARK: threshold & signing

    func testEffectiveOffsetIsZeroBelowFiveSeconds() {
        XCTAssertEqual(ServerClock.effectiveOffset(0), 0)
        XCTAssertEqual(ServerClock.effectiveOffset(4_999), 0)
        XCTAssertEqual(ServerClock.effectiveOffset(-4_999), 0)
        XCTAssertEqual(ServerClock.effectiveOffset(5_000), 5_000)
        XCTAssertEqual(ServerClock.effectiveOffset(-61_000), -61_000)
    }

    func testHealthyClockSignsWithTheLocalClock() {
        let local = Date(timeIntervalSince1970: Double(now) / 1000)
        XCTAssertEqual(ServerClock.signingNowMs(localNow: local), now, "no sample yet")
        ServerClock.observe(serverDateMs: now + 1_500, sentAtMs: now, receivedAtMs: now)  // 2 s: NTP noise
        XCTAssertEqual(ServerClock.signingNowMs(localNow: local), now)
        XCTAssertEqual(HTTPRequestSignature.currentTimestampMillis().count, 13)
    }

    func testWrongClockSignsWithServerTime() {
        let local = Date(timeIntervalSince1970: Double(now) / 1000)
        // Phone 45 s fast: server Date is 45 s behind the local midpoint.
        ServerClock.observe(serverDateMs: now - 45_500, sentAtMs: now, receivedAtMs: now)
        XCTAssertEqual(ServerClock.rawOffsetMs, -45_000)
        XCTAssertEqual(ServerClock.signingNowMs(localNow: local), now - 45_000)
        // And the shared signer used by call/push AND v2 follows (±1 s of wall time).
        let signed = Int64(HTTPRequestSignature.currentTimestampMillis())!
        XCTAssertLessThan(abs(signed - (ServerClock.millis(Date()) - 45_000)), 1_000)
    }

    // MARK: freshness

    private func judge(sender: Int64, relay: Int64? = nil, offset: Int64 = 0, maxAge: Double = 30) -> ServerClock.Freshness {
        ServerClock.judgeFreshness(senderStampMs: sender, relayStampMs: relay, localNowMs: now,
                                   rawOffsetMs: offset, maxAgeSeconds: maxAge)
    }

    func testHealthyClocksKeepThePreFixWindow() {
        XCTAssertEqual(judge(sender: now - 30_000), .fresh(packetTimeLocal: Date(timeIntervalSince1970: Double(now - 30_000) / 1000), viaRelayStamp: false))
        XCTAssertEqual(judge(sender: now - 30_001), .stale(ageSeconds: 30.001))
        XCTAssertEqual(judge(sender: now + 5_000), .fresh(packetTimeLocal: Date(timeIntervalSince1970: Double(now + 5_000) / 1000), viaRelayStamp: false))
        XCTAssertEqual(judge(sender: now + 5_001), .future(ageSeconds: -5.001))
        // 4.9 s of measured skew changes nothing.
        XCTAssertEqual(judge(sender: now + 5_001, offset: 4_900), .future(ageSeconds: -5.001))
    }

    func testThisPhoneFastStillAcceptsAPeerOnServerTime() {
        // We are 90 s fast (offset −90 s). A healthy peer stamps server time = our now − 90 s.
        // Pre-fix: age 90 s ⇒ stale ⇒ never rang (k#063ffd shape).
        if case .fresh(let t, let viaRelay) = judge(sender: now - 90_000 + 1_000, offset: -90_000) {
            XCTAssertFalse(viaRelay)
            // …and the packet time lands on OUR clock, comparable with currentCallStartTime.
            XCTAssertEqual(ServerClock.millis(t), now + 1_000)
        } else { XCTFail("expected fresh") }
    }

    func testThisPhoneSlowStillAcceptsAPeerOnServerTime() {
        // We are 40 s slow (offset +40 s). A healthy peer's stamp looks 40 s in the future.
        if case .fresh = judge(sender: now + 40_000, offset: 40_000) {} else { XCTFail("expected fresh") }
        XCTAssertEqual(judge(sender: now + 40_000), .future(ageSeconds: -40), "uncorrected = pre-fix rejection")
    }

    func testRelayStampVouchesForAPeerWithAWrongClock() {
        // An old build 2 min fast stamps +120 s; the relay received it 1 s ago on server time.
        if case .fresh(let t, let viaRelay) = judge(sender: now + 120_000, relay: now - 1_000) {
            XCTAssertTrue(viaRelay)
            XCTAssertEqual(ServerClock.millis(t), now - 1_000)
        } else { XCTFail("expected fresh via relay") }
    }

    func testReplayIsStillRefusedOnServerTime() {
        // A parked / replayed signal: sender stamp AND relay stamp both 45 s old.
        XCTAssertEqual(judge(sender: now - 45_000, relay: now - 45_000), .stale(ageSeconds: 45))
        // Relay stamp cannot rescue a signal it says is old either.
        XCTAssertEqual(judge(sender: now + 120_000, relay: now - 31_000), .future(ageSeconds: -120))
        // Nor a relay stamp in the future.
        XCTAssertEqual(judge(sender: now - 300_000, relay: now + 6_000), .stale(ageSeconds: 300))
        // Termination signals keep their longer 60 s window, not more.
        if case .fresh = judge(sender: now - 59_000, maxAge: 60) {} else { XCTFail() }
        XCTAssertEqual(judge(sender: now - 61_000, maxAge: 60), .stale(ageSeconds: 61))
    }

    // MARK: VoIP push

    func testPushAgeUsesServerTimeOnlyWhenOff() {
        let local = Date(timeIntervalSince1970: Double(now) / 1000)
        let stamped = Double(now) / 1000 - 10
        XCTAssertEqual(ServerClock.pushAgeSeconds(callTimestampSeconds: stamped, localNow: local, rawOffsetMs: 3_000), 10, accuracy: 0.001)
        // Phone 90 s fast: a 10 s old push looked 100 s old and was buried as stale.
        XCTAssertEqual(ServerClock.pushAgeSeconds(callTimestampSeconds: stamped - 90, localNow: local, rawOffsetMs: -90_000), 10, accuracy: 0.001)
    }
}
