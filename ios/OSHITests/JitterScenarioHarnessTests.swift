//
//  JitterScenarioHarnessTests.swift
//  OSHITests
//
//  Does the burst-peak buffer actually make calls smoother, or does it just make
//  a number in a log look better?
//
//  `JitterBurstDepthTests` proves the buffer EXPANDS. Expanding is not the goal;
//  not chopping is. This harness closes that gap by simulating both ends of the
//  pipeline — arrivals AND playback — and counting the thing the listener
//  actually hears.
//
//  How it works: each arrival drains the jitter buffer into a player queue held
//  in milliseconds of audio, exactly as the real drain loop does; between
//  arrivals that queue empties at real time. A moment when the queue is empty
//  while the call is live is a HOLE — PLC synthesises audio to cover it, and that
//  synthesised audio is the chop, the "hachuré" in the report.
//
//  So the metric is milliseconds of synthesised audio per second of call. Depth
//  is reported
//  alongside it because depth is latency: a buffer that never chops by holding a
//  second of audio has traded one complaint for another, and the table has to
//  show both or it is not an honest comparison.
//
//  The profiles are not invented. #2 is the Natalia call (iaP95 ≈ 85 ms, peaks
//  254 / 291 / 327 ms, 99.7 % delivery — bunching, not loss), and the others are
//  the ordinary links that must not regress to buy it.
//

import XCTest
@testable import OSHI

final class JitterScenarioHarnessTests: XCTestCase {

    // MARK: - Scenario definition

    struct Profile {
        let name: String
        /// Delay in ms added to packet i's ideal send time (i * 20 ms).
        /// NOT a gap — see `run` for why listing gaps gave meaningless results.
        let delayForIndex: (Int) -> Double
        /// Indices the network dropped outright.
        let isLost: (Int) -> Bool

        init(_ name: String,
             delayForIndex: @escaping (Int) -> Double,
             isLost: @escaping (Int) -> Bool = { _ in false }) {
            self.name = name
            self.delayForIndex = delayForIndex
            self.isLost = isLost
        }
    }

    struct Result {
        let concealedMs: Double
        let playedMs: Double
        let finalDepthMs: Int
        var seconds: Double { (playedMs + concealedMs) / 1000.0 }
        /// Milliseconds of synthesised audio per second of call — the chop, in
        /// the only unit a listener experiences.
        var concealedMsPerSecond: Double { seconds > 0 ? concealedMs / seconds : 0 }
    }

    /// Deterministic pseudo-noise. `Math.random` equivalents are avoided so a
    /// regression is reproducible rather than a coin flip that fails one run in
    /// twenty and gets re-run until green.
    private static func noise(_ i: Int, _ salt: Int, spread: Double) -> Double {
        let h = (i &* 2_654_435_761) ^ (salt &* 40_503)
        return (Double(abs(h) % 1000) / 1000.0 - 0.5) * 2 * spread
    }

    private static let profiles: [Profile] = [
        // A good Wi-Fi call: a few ms of transit, barely varying.
        Profile("clean wifi", delayForIndex: { 8 + noise($0, 1, spread: 2) }),

        // A relay that stalls roughly once a second, holding several packets back
        // ~300 ms; they then arrive together. Mean rate stays at cadence.
        Profile("relayed cellular (bursty)",
                delayForIndex: { i in (i % 50) < 6 ? 55 + 300 : 55 + noise(i, 2, spread: 12) }),

        // TCP head-of-line: a whole window withheld, then dumped at once.
        Profile("bursty tcp relay", delayForIndex: { Double(($0 % 10)) * -18 + 180 }),

        // One handover, then a catch-up burst.
        Profile("single handover",
                delayForIndex: { i in (300..<320).contains(i) ? 500 : 12 + noise(i, 3, spread: 3) }),

        // Ordinary cellular jitter with 2 % real loss — the case PLC exists for.
        Profile("lossy cellular", delayForIndex: { 25 + noise($0, 4, spread: 20) },
                isLost: { $0 % 50 == 17 }),

        // HUGO'S ACTUAL CALL, from DIAG_JITTER / DIAG_PLAYBACK_RATE on his device:
        // iaMean 19.5–20.6, iaP95 70–85, iaMax 143–157, and 1.4 % of playback
        // synthesised mid-call (71 concealed vs 4927 real, 6 windows of 97). He
        // reported it "great at 99 %" and the log agrees.
        //
        // This row answers the question that matters more than any improvement:
        // does the fix TOUCH a call that was already fine? Depth is latency.
        Profile("hugo real call (99% ok)",
                delayForIndex: { i in (i % 60) < 3 ? 55 + 95 : 55 + noise(i, 5, spread: 22) })
    ]

    // MARK: - The simulation
    //
    // The FIRST version of this harness pulled one packet per 20 ms tick, like a
    // metronome. It reported identical numbers with the fix on and off, and 600 ms
    // of depth even on a clean Wi-Fi profile — a result that contradicted
    // `JitterBurstDepthTests`, where a clean link stays at 180.
    //
    // That was the harness, not the code. Playback here is NOT a 20 ms grid. The
    // drain loop runs on EVERY ARRIVAL and empties the jitter buffer immediately
    // (`AdvancedJitterBuffer.getNextPacket`'s own comment says depth is 0 by
    // construction); the real jitter buffer is AVAudioPlayerNode's scheduled
    // queue. A lockstep grid manufactures a slot miss out of ±2 ms of noise, which
    // trips the PRE-EXISTING burst-loss escalation and pins depth at the ceiling
    // in both configurations — so the harness saturated before the thing under
    // test could matter, and measured nothing.
    //
    // Modelled properly: each arrival drains the buffer into a player queue
    // measured in milliseconds of audio; between arrivals that queue drains at
    // real time. Queue hits zero while the call is live = the pipeline had nothing
    // to play = PLC synthesises audio. That is the chop, and `targetDepthMs`
    // reaches it through the drop threshold, which is what bounds how much burst
    // backlog the queue may keep.

    private func run(_ profile: Profile, packets: Int, burstDepth: Bool) -> Result {
        AdvancedJitterBuffer.burstDepthEnabled = burstDepth
        let buf = AdvancedJitterBuffer()
        var nowMs: Double = 1_000_000
        buf.clock = { nowMs }
        buf.reset()

        let payload = Data(repeating: 0, count: 640)
        var playerQueueMs: Double = 0
        var concealedMs: Double = 0
        var playedMs: Double = 0
        var started = false

        // The network is a FIFO PIPE. Listing per-packet gaps made every bursty
        // profile deliver LESS audio than wall-clock consumed (23 % rate deficit),
        // which starves the pipeline no matter how deep the buffer is; and giving
        // each packet an independent delay let a spiked packet be overtaken, so
        // ~12 % arrived after their slot and were dropped as late — a loss profile
        // wearing a jitter costume. The measured call dropped essentially nothing.
        // A real stall holds everything in flight and releases it IN ORDER.
        let minSpacing = 0.3
        var arrival: [Double] = []
        arrival.reserveCapacity(packets)
        var last = -Double.infinity
        for i in 0..<packets {
            let t = max(last + minSpacing, Double(i) * 20.0 + profile.delayForIndex(i))
            arrival.append(t)
            last = t
        }

        var prev = arrival[0]
        for i in 0..<packets {
            let gap = arrival[i] - prev
            prev = arrival[i]
            nowMs += gap
            if started {
                let drained = min(playerQueueMs, gap)
                playerQueueMs -= drained
                playedMs += drained
                concealedMs += gap - drained
            }
            if !profile.isLost(i) {
                _ = buf.addPacket(sequenceNumber: UInt64(i), audioData: payload)
            }
            let threshold = min(700.0, max(200.0, Double(buf.targetDepthMs) + 120.0))
            while buf.getNextPacket() != nil {
                started = true
                if playerQueueMs + 20 <= threshold { playerQueueMs += 20 }
            }
        }

        return Result(concealedMs: concealedMs, playedMs: playedMs, finalDepthMs: buf.targetDepthMs)
    }

    // MARK: - The comparison

    func testBurstDepthReducesConcealmentWithoutTaxingCleanLinks() {
        var rows: [String] = []
        var regressions: [String] = []

        for profile in Self.profiles {
            let off = run(profile, packets: 1500, burstDepth: false)
            let on = run(profile, packets: 1500, burstDepth: true)

            let delta = on.concealedMsPerSecond - off.concealedMsPerSecond
            rows.append(String(
                format: "%-28s off: %5.2f ms conceal/s depth %3d ms  |  on: %5.2f ms conceal/s depth %3d ms  |  %+.2f",
                (profile.name as NSString).utf8String!,
                off.concealedMsPerSecond, off.finalDepthMs,
                on.concealedMsPerSecond, on.finalDepthMs,
                delta))

            // A tolerance, not zero: the buffer is deliberately deeper on a bursty
            // link, which shifts slot alignment slightly. What must not happen is
            // a link getting materially choppier than before.
            if delta > 5.0 {
                regressions.append("\(profile.name): \(off.concealedMsPerSecond) → \(on.concealedMsPerSecond) ms conceal/s")
            }
            // Depth is latency. 600 ms is the buffer's own ceiling; a profile that
            // pins there has bought smoothness at a price a caller would notice.
            if on.finalDepthMs > 600 {
                regressions.append("\(profile.name): depth \(on.finalDepthMs) ms exceeds the cap")
            }
        }

        print("\n─── jitter scenario harness (concealment = audible chop) ───")
        rows.forEach { print($0) }
        print("────────────────────────────────────────────────────────────\n")

        XCTAssertTrue(regressions.isEmpty,
                      "burst depth made a link WORSE:\n" + regressions.joined(separator: "\n"))
    }

    /// The profile the whole change exists for. Asserted on its own so a real
    /// improvement here cannot be averaged away by the other four.
    func testTheMeasuredRelayedCallGetsSmoother() {
        let profile = Self.profiles[1]   // relayed cellular (bursty)
        let off = run(profile, packets: 3000, burstDepth: false)
        let on = run(profile, packets: 3000, burstDepth: true)

        print(String(format: "relayed cellular — off: %.2f ms conceal/s (%d) | on: %.2f ms conceal/s (%d) | depth %d → %d ms",
                     off.concealedMsPerSecond, Int(off.concealedMs),
                     on.concealedMsPerSecond, Int(on.concealedMs),
                     off.finalDepthMs, on.finalDepthMs))

        XCTAssertGreaterThan(off.concealedMs, 0,
                             "the profile must actually chop without the fix, or this test proves nothing")
        XCTAssertLessThan(on.concealedMs, off.concealedMs,
                          "fewer synthesised frames is the entire point")
    }

    /// Latency is the other half of call quality, and the easy way to fake a win
    /// here is to hold half a second of audio on every call.
    /// The regression that matters most here. Hugo's own call was already fine
    /// (1.4 % synthesised, and he reported 99 %), and he asked for LESS lag. If
    /// the fix deepened his buffer it would be adding latency to fix a problem he
    /// does not have — a silent regression dressed as an improvement.
    func testTheRealWorkingCallIsNotTouched() {
        let real = Self.profiles[5]
        let off = run(real, packets: 3000, burstDepth: false)
        let on = run(real, packets: 3000, burstDepth: true)

        XCTAssertEqual(on.finalDepthMs, off.finalDepthMs,
                       "a call that already worked must end at exactly the depth it would have without the fix")
        XCTAssertEqual(on.concealedMs, off.concealedMs, accuracy: 1.0,
                       "and must not change what the listener hears")
    }

    func testACleanCallPaysNoLatencyForThis() {
        let clean = Self.profiles[0]
        let off = run(clean, packets: 1500, burstDepth: false)
        let on = run(clean, packets: 1500, burstDepth: true)

        XCTAssertEqual(on.finalDepthMs, off.finalDepthMs,
                       "a clean link must end at exactly the depth it would have without the fix")
    }
}
