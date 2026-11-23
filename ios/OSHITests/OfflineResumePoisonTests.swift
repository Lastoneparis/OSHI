import XCTest
@testable import OSHI

/// __MAP_STALLED_AT_24_PERCENT_2026_10_28__ The rule behind "2% to 24% instantly, then
/// blocked at 24%".
///
/// A resumed download that FAILS must throw its resume state away; a FRESH download that
/// fails must keep it. Those are opposite responses to what looks like the same event, and
/// the old code did the same thing in both cases — it saved.
///
/// Why that is fatal rather than untidy: resume data embeds the server's validator for the
/// bytes already on disk. `fr-74-roadgraph.db.gz` was regenerated on 2026-10-27, so any
/// resume stored before that can never succeed. It fails instantly, the same dead blob is
/// written back, and the next attempt repeats it. For ever. 272 MB — 77% of that region's
/// download — never transferring a byte, while the ring sat at the percentage its other
/// components had already reached.
final class OfflineResumePoisonTests: XCTestCase {

    /// THE CASE. A resume that failed is the one piece of state guaranteed to be bad.
    func testAFailedResumeIsPoisoned() {
        XCTAssertTrue(OfflineDownloadResume.resumeIsPoisoned(didResume: true))
    }

    /// And the opposite case, which is the whole reason resume data is stored at all: a
    /// fresh download interrupted by a tunnel keeps its state so the next attempt continues.
    func testAFailedFreshDownloadKeepsItsState() {
        XCTAssertFalse(OfflineDownloadResume.resumeIsPoisoned(didResume: false))
    }

    /// `makeTask` must REPORT whether it resumed, or the caller cannot tell the two cases
    /// apart — which is exactly the information the old code did not have.
    func testMakeTaskReportsThatItDidNotResumeWhenThereIsNoState() {
        let session = URLSession(configuration: .ephemeral)
        var didResume = true   // seeded wrong on purpose: the call must overwrite it
        let task = OfflineDownloadResume.makeTask(
            session: session,
            url: URL(string: "https://example.invalid/none")!,
            namespace: "OSHITest_Resume_\(UUID().uuidString)",
            regionId: "nothing-stored",
            didResume: &didResume,
            completion: { _, _, _ in }
        )
        task.cancel()
        XCTAssertFalse(didResume, "with nothing stored it cannot have resumed")
    }

    /// The three downloaders that share the pattern must all consult the rule. Checked on
    /// the SOURCE, like `RatchetHealCooldownTests` does, because the failure is a branch
    /// that is never taken rather than a value that is wrong — and a copy that silently
    /// reverts to saving would look identical at runtime until a file changed on the server.
    func testEveryDownloaderThatResumesAlsoDiscardsAPoisonedResume() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("OSHI")
        for file in ["OfflineRoadGraphDownloader.swift",
                     "OfflineStreetDatabase.swift",
                     "OfflineMapManager.swift"] {
            let src = try String(contentsOf: root.appendingPathComponent(file), encoding: .utf8)
            guard src.contains("OfflineDownloadResume.makeTask") else { continue }
            XCTAssertTrue(
                src.contains("resumeIsPoisoned"),
                "\(file) resumes but never discards a resume that failed — one regenerated file on the server and that component stops for good"
            )
        }
    }
}
