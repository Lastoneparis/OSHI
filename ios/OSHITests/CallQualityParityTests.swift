import XCTest
import CryptoKit
@testable import OSHI

/// __CALL_QUALITY_TIERS_2026_10_28__ The SAME table of inputs on iOS and on the desktop
/// client, reduced to ONE digest.
///
/// The desktop client did not have call-quality tiers at all (PARITY row 2.1-d listed them
/// as not ported). Porting them meant transcribing the thresholds in
/// `CallQualityStats.quality` below into Kotlin — and a transcription is exactly the kind of
/// thing that drifts the next time one side is edited.
///
/// Matching test NAMES would not catch that drift. This repository has already paid for
/// that lesson: two cores with identically named tests produced "Donateur 310" on Android
/// and "253" on iOS, and nothing was red. So each row below is formatted with its inputs
/// AND the tier they produced, the rows are joined, and one SHA-256 is compared against the
/// constant that `CallQualityParityTest.kt` holds too.
///
/// This file asserts nothing about iOS behaviour on its own — iOS is the reference here.
/// What it asserts is that the reference has not moved away from the port, in either
/// direction.
final class CallQualityParityTests: XCTestCase {

    private struct V {
        let latencyMs: Int
        let loss: Double
        let jitterMs: Int
    }

    /// Kept identical, row for row, to `CallQualityParityTest.vectors()` on the desktop.
    private func vectors() -> [V] {
        [
            // nothing measured — the all-zero case. Both platforms' classifiers call this
            // EXCELLENT, because every threshold is an upper bound and zero clears them
            // all; only the desktop BADGE refuses to show it (`CallQualityStats.measured`).
            V(latencyMs: 0, loss: 0.0, jitterMs: 0),
            // the five tiers, comfortably inside each band
            V(latencyMs: 40, loss: 0.5, jitterMs: 20),
            V(latencyMs: 150, loss: 3.0, jitterMs: 20),
            V(latencyMs: 250, loss: 7.0, jitterMs: 20),
            V(latencyMs: 350, loss: 15.0, jitterMs: 20),
            V(latencyMs: 40, loss: 25.0, jitterMs: 20),
            // every latency bound, from just inside to just outside
            V(latencyMs: 99, loss: 0.0, jitterMs: 0), V(latencyMs: 100, loss: 0.0, jitterMs: 0),
            V(latencyMs: 199, loss: 0.0, jitterMs: 0), V(latencyMs: 200, loss: 0.0, jitterMs: 0),
            V(latencyMs: 299, loss: 0.0, jitterMs: 0), V(latencyMs: 300, loss: 0.0, jitterMs: 0),
            V(latencyMs: 399, loss: 0.0, jitterMs: 0), V(latencyMs: 400, loss: 0.0, jitterMs: 0),
            V(latencyMs: 401, loss: 0.0, jitterMs: 0), V(latencyMs: 5_000, loss: 0.0, jitterMs: 0),
            // every loss bound
            V(latencyMs: 10, loss: 1.999, jitterMs: 0), V(latencyMs: 10, loss: 2.0, jitterMs: 0),
            V(latencyMs: 10, loss: 4.999, jitterMs: 0), V(latencyMs: 10, loss: 5.0, jitterMs: 0),
            V(latencyMs: 10, loss: 9.999, jitterMs: 0), V(latencyMs: 10, loss: 10.0, jitterMs: 0),
            V(latencyMs: 10, loss: 19.999, jitterMs: 0), V(latencyMs: 10, loss: 20.0, jitterMs: 0),
            V(latencyMs: 10, loss: 100.0, jitterMs: 0),
            // the jitter asymmetry: it may deny EXCELLENT and must never push below GOOD
            V(latencyMs: 10, loss: 0.0, jitterMs: 149),
            V(latencyMs: 10, loss: 0.0, jitterMs: 150),
            V(latencyMs: 10, loss: 0.0, jitterMs: 10_000),
            V(latencyMs: 10, loss: 25.0, jitterMs: 10_000),
            // a negative latency should never be believed as a fast one
            V(latencyMs: -5, loss: 0.0, jitterMs: 0),
        ]
    }

    /// The desktop names its tiers in Kotlin's `UPPER_SNAKE`; iOS's raw values are
    /// `"Excellent"`, `"Very Poor"`… Mapped here rather than renaming either enum, so the
    /// digest compares the DECISION and not two spelling conventions.
    private func name(_ q: ConnectionQuality) -> String {
        switch q {
        case .excellent: return "EXCELLENT"
        case .good: return "GOOD"
        case .fair: return "FAIR"
        case .poor: return "POOR"
        case .veryPoor: return "VERY_POOR"
        case .reconnecting: return "RECONNECTING"
        case .disconnected: return "DISCONNECTED"
        }
    }

    private func transcript() -> String {
        vectors().map { v in
            var stats = CallQualityStats()
            stats.latencyMs = v.latencyMs
            stats.packetLossPercent = v.loss
            stats.jitterMs = v.jitterMs
            return "\(v.latencyMs)|\(String(format: "%.3f", v.loss))|\(v.jitterMs)=>\(name(stats.quality))"
        }.joined(separator: "\n")
    }

    /// SHA-256 of the transcript. The SAME literal appears in the desktop's
    /// `CallQualityParityTest.POLICY_DIGEST`.
    private let policyDigest = "85e5110bcbb4431290d93414f0bbb6e7e84bb64a127a3284a3aa9786a133dc21"

    func testTheTierDigestMatchesTheOneTheDesktopComputes() {
        let t = transcript()
        XCTAssertEqual(30, t.split(separator: "\n", omittingEmptySubsequences: false).count,
                       "the table must not lose or gain rows silently")
        let hex = SHA256.hash(data: Data(t.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(policyDigest, hex,
                       "iOS and the desktop client disagree about call-quality tiers, OR a "
                       + "threshold moved on purpose. In the second case update BOTH this "
                       + "constant and CallQualityParityTest.POLICY_DIGEST, in the same "
                       + "commit.\n" + t)
    }

    /// The one claim this file makes about iOS alone: that the jitter test guards `excellent`
    /// and nothing else.
    ///
    /// It is the fix recorded in `__QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__` — call
    /// `6216CD2F`, 29 minutes, WiFi ↔ 5G, raw jitter 22–43 ms with p95 bursts to 300 ms and
    /// 0.0 % of audio actually lost, which used to mark whichever side held the 5G leg
    /// "poor" for an entire perfect call. A tidy-up that moves the 150 ms test into the
    /// second branch reintroduces it on BOTH platforms now, since the port copied the
    /// asymmetry deliberately.
    func testJitterCanOnlyDenyExcellent() {
        var stats = CallQualityStats()
        stats.latencyMs = 1
        stats.packetLossPercent = 0
        stats.jitterMs = 10_000
        XCTAssertEqual("GOOD", name(stats.quality),
                       "huge jitter on an otherwise perfect line must land at GOOD")
        stats.jitterMs = 149
        XCTAssertEqual("EXCELLENT", name(stats.quality), "the bound is 150, not 149")
    }
}
