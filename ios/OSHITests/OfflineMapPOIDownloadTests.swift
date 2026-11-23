//
//  OfflineMapPOIDownloadTests.swift
//  OSHITests
//
//  END-TO-END check of the POI download path against the LIVE server. This is
//  the one test that proves the 2026-07-28 fix actually works, because the bug
//  it guards was never a logic error — the code was correct and simply never
//  reached (see docs/OFFLINE_MAP_REFERENCE.md §2).
//
//  It exercises the real production chain, no mocks:
//
//      OfflineMapManager.fetchManifest()          real HTTPS
//        -> manifest region "ca-nu" (124 KB, smallest region on the server)
//        -> OfflineMapManager.downloadPOIDatabase(region:)
//             real download from files.poi.url, real SHA-256 verification
//        -> file lands at Documents/Maps/regions/ca-nu/poi.db
//        -> MapPOIDatabase().open(regionId:) + poiCount + search
//
//  ⚠️ NETWORK + OPT-IN. Skipped unless OSHI_NET_TESTS=1 is set, so CI and the
//  normal `xcodebuild test` run stay hermetic. Note the `TEST_RUNNER_` prefix —
//  xcodebuild only forwards environment variables to the test host when they
//  carry it; a plain `OSHI_NET_TESTS=1` silently leaves the test skipped:
//
//      TEST_RUNNER_OSHI_NET_TESTS=1 xcodebuild test \
//        -project OSHI.xcodeproj -scheme Genesis \
//        -destination 'platform=iOS Simulator,name=iPhone 15 Plus' \
//        -only-testing:OSHITests/OfflineMapPOIDownloadTests
//
//  Verified passing 2026-07-28 (1.74 s): 234 regions, ca-nu poi.db 124 KB,
//  SHA-256 match, 503 POIs, FTS5 search non-empty.
//
//  ⚠️ It writes into the test host's real Documents directory and removes the
//  region directory it created in tearDown.
//

import XCTest
@testable import OSHI

final class OfflineMapPOIDownloadTests: XCTestCase {

    /// Smallest POI database published (ca-nu / Nunavut, ~124 KB, 503 POIs), so
    /// the test stays fast and cannot be mistaken for a bandwidth test.
    private let regionId = "ca-nu"

    private var regionDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Maps/regions/\(regionId)", isDirectory: true)
    }

    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["OSHI_NET_TESTS"] == "1",
                          "network test — set OSHI_NET_TESTS=1 to run")
        try? FileManager.default.removeItem(at: regionDir)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: regionDir)
        super.tearDown()
    }

    /// The regression this guards: before 2026-07-28 nothing ever enqueued the
    /// POI download, so this file never appeared no matter how many regions the
    /// user downloaded.
    func testManifestPOIDownloadsVerifiesAndOpens() throws {
        let manager = OfflineMapManager.shared

        // --- 1. real manifest ---
        let manifestLoaded = expectation(description: "manifest")
        DispatchQueue.main.async {
            manager.fetchManifest()
        }
        // fetchManifest has no completion handler; poll the @Published value.
        var waited = 0.0
        while manager.manifest == nil && waited < 30.0 {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
            waited += 0.25
        }
        manifestLoaded.fulfill()
        wait(for: [manifestLoaded], timeout: 1.0)

        let manifest = try XCTUnwrap(manager.manifest, "manifest did not load in 30 s")
        XCTAssertGreaterThan(manifest.regions.count, 200,
                             "got \(manifest.regions.count) regions — this is the hardcoded fallback, not the server manifest")

        let region = try XCTUnwrap(manifest.regions.first { $0.id == regionId },
                                   "\(regionId) missing from the served manifest")
        let poi = try XCTUnwrap(region.files.poi, "\(regionId) advertises no POI layer")
        XCTAssertTrue(poi.isAvailable, "\(regionId) POI marked not-built; pick another fixture region")

        // --- 2. real download + SHA-256 verification ---
        XCTAssertFalse(manager.isPOIDatabaseAvailable(regionId: regionId))
        manager.downloadPOIDatabase(region: region)

        waited = 0.0
        while !manager.isPOIDatabaseAvailable(regionId: regionId) && waited < 120.0 {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
            waited += 0.5
        }
        XCTAssertTrue(manager.isPOIDatabaseAvailable(regionId: regionId),
                      "poi.db never landed — a SHA-256 mismatch deletes it silently, check the manifest hash")

        // --- 3. the file is the size the manifest promised ---
        let dbURL = manager.poiPath(for: regionId)
        let attrs = try FileManager.default.attributesOfItem(atPath: dbURL.path)
        XCTAssertEqual(attrs[.size] as? Int, poi.realSize)

        // --- 4. MapPOIDatabase can actually consume it ---
        let db = MapPOIDatabase()
        XCTAssertTrue(db.open(regionId: regionId), "MapPOIDatabase could not open the downloaded file")
        XCTAssertGreaterThan(db.poiCount(regionId: regionId), 0, "downloaded POI database is empty")
        // FTS5 mirror must be populated too — search is what the map tab uses.
        XCTAssertFalse(db.search(query: "a", limit: 5).isEmpty,
                       "pois_fts is empty; build_pois.py finalize step did not run for this region")
        db.close()
    }
}
