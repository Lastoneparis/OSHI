import XCTest
@testable import OSHI

/// __MAP_STALLED_AT_24_PERCENT_2026_10_28__
///
/// Reported from an iPhone 17: downloading Haute-Savoie "went from 2% to 24% almost
/// instantly, then blocked at 24%".
///
/// The arithmetic behind both halves, from the live manifest for `fr-74`: poi 3 215 360 B,
/// streets 78 470 916 B, roadgraph 272 382 337 B. `aggregateProgress` weights by bytes, so
/// streets alone is 22.2% and poi 0.9%. `fr-streets.db.gz` is SHARED by every French region,
/// so a user who already has one French region has it on disk and those 23.1% land the
/// instant the tap does — the jump is honest.
///
/// The freeze is not. When the roadgraph (76.9%) stopped, its progress entry was deleted,
/// the weighted average fell, and `aggregateProgressFloor` — which exists so a ring never
/// runs backwards — pinned the display at 24% for ever. Nothing read the error.
final class MapComponentFailureTests: XCTestCase {

    private func c(_ id: String, available: Bool = false, downloading: Bool = false,
                   error: String? = nil) -> MapComponentFailure.Component {
        .init(id: id, available: available, downloading: downloading, error: error)
    }

    // MARK: - Nothing to report

    func testNoFailureWhileEverythingIsStillWorking() {
        XCTAssertNil(MapComponentFailure.first(in: [
            c("roadgraph", downloading: true),
            c("streets", available: true),
        ]))
    }

    func testNoFailureWhenEverythingFinished() {
        XCTAssertNil(MapComponentFailure.first(in: [
            c("roadgraph", available: true),
            c("streets", available: true),
        ]))
    }

    /// A component that has not started is not a failure. The region screen starts them in
    /// parallel but a slow one can legitimately have no state yet.
    func testNotStartedIsNotAFailure() {
        XCTAssertNil(MapComponentFailure.first(in: [c("poi")]))
    }

    // MARK: - The reported case

    /// THE BUG. Streets done, roadgraph dead, and the only thing on screen was 24%.
    func testTheRoadgraphFailureIsReported() {
        let failed = MapComponentFailure.first(in: [
            c("roadgraph", error: "Not enough space: needs 1.4 GB, 300 MB free"),
            c("streets", available: true),
            c("poi", available: true),
        ])
        XCTAssertEqual(failed?.id, "roadgraph")
        XCTAssertEqual(failed?.error, "Not enough space: needs 1.4 GB, 300 MB free")
    }

    /// Heaviest first: naming the 0.9% component while 77% is the real blocker would be
    /// true and useless.
    func testTheHeaviestFailingComponentIsNamedFirst() {
        let failed = MapComponentFailure.first(in: [
            c("roadgraph", error: "no space"),
            c("poi", error: "HTTP 500"),
        ])
        XCTAssertEqual(failed?.id, "roadgraph")
    }

    // MARK: - Not a failure, however it looks

    /// A stale error must not keep a finished component looking broken: a component can
    /// fail once, be retried and succeed.
    func testAnAvailableComponentIsNeverTheFailureEvenWithAStaleError() {
        XCTAssertNil(MapComponentFailure.first(in: [
            c("roadgraph", available: true, error: "a failure from the previous attempt"),
        ]))
    }

    /// A retry in flight is not a failure yet, in the other direction.
    func testARetryInFlightIsNotAFailure() {
        XCTAssertNil(MapComponentFailure.first(in: [
            c("roadgraph", downloading: true, error: "the attempt before this one"),
        ]))
    }

    func testAnEmptyErrorStringIsNotAFailure() {
        XCTAssertNil(MapComponentFailure.first(in: [c("poi", error: "")]))
        XCTAssertNil(MapComponentFailure.first(in: [c("poi", error: "   ")]))
    }

    // MARK: - Slow versus stuck

    /// A quarter of a gigabyte of road graph on a phone on a train is SLOW. Reporting that
    /// as stuck would be worse than the bug being fixed.
    func testADownloadWithAnythingInFlightIsNotStopped() {
        XCTAssertFalse(MapComponentFailure.allStopped([
            c("roadgraph", downloading: true),
            c("poi"),
        ]))
    }

    func testEverythingUnfinishedAndIdleCountsAsStopped() {
        XCTAssertTrue(MapComponentFailure.allStopped([
            c("roadgraph", error: "no space"),
            c("streets", available: true),
        ]))
    }

    /// A finished download is not "stopped" — there is nothing left to wait for.
    func testACompleteDownloadIsNotStopped() {
        XCTAssertFalse(MapComponentFailure.allStopped([
            c("roadgraph", available: true),
            c("streets", available: true),
        ]))
    }
}
