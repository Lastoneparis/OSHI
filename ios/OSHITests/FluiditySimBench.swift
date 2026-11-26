//
//  FluiditySimBench.swift
//  OSHITests
//
//  __CALL_FLUIDITY_BENCH_2026_11_21__ The iOS 0x19 receive chain, before and after, in VIRTUAL
//  time under the 12 `NetworkImpairment` scenarios of the desktop/Android benches, analysed
//  by `FluidityProbe`. The chains are in FluidityChains.swift.
//
//  Full table: `OSHI_FLUIDITY_BENCH=1` (xcodebuild: `TEST_RUNNER_OSHI_FLUIDITY_BENCH=1`); it is
//  printed, and written to `OSHI_FLUIDITY_OUT` when that is set. Without it only the short
//  regression guard runs.
//

import XCTest
@testable import OSHI

final class FluiditySimBench: XCTestCase {

    func testSimulateAllScenarios() throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["OSHI_FLUIDITY_BENCH"] == "1", "set OSHI_FLUIDITY_BENCH=1")
        var rows: [FluidityRun.Row] = []
        for s in NetworkImpairment.scenarios {
            rows.append(FluidityRun.run(s, OldIOS()))
            rows.append(FluidityRun.run(s, NewIOS()))
        }
        let t = FluidityRun.table(rows)
        print(t)
        for r in rows { print("\(r.scenario) \(r.impl): \(r.counters)") }
        if let path = env["OSHI_FLUIDITY_OUT"] { try? t.write(toFile: path, atomically: true, encoding: .utf8) }
    }

    /// Always on, short: the two scenarios the change exists for must stay won.
    func testJitterAndStallStayFixed() {
        guard let jitter = NetworkImpairment.scenarios.first(where: { $0.name == "jitter 100ms" }),
              let stall = NetworkImpairment.scenarios.first(where: { $0.name == "stall 1.5s @8s" }) else { return XCTFail() }
        let oldJ = FluidityRun.run(jitter, OldIOS(), durationMs: 8_000)
        let newJ = FluidityRun.run(jitter, NewIOS(), durationMs: 8_000)
        XCTAssertLessThan(newJ.r.gapRatio, oldJ.r.gapRatio, "jitter 100ms: \(newJ.r.gapRatio) vs \(oldJ.r.gapRatio)")
        XCTAssertLessThan(newJ.r.medianLatency, oldJ.r.medianLatency)
        let newS = FluidityRun.run(stall, NewIOS(), durationMs: 12_000)
        XCTAssertEqual(newS.r.gapRatio, 0, accuracy: 0.001)
        XCTAssertLessThanOrEqual(newS.r.postOutageExtraMs, 40, "a stall must not leave its backlog in the delay")
    }
}
