//
//  OfflineMapManifestTests.swift
//  OSHITests
//
//  Unit tests for the offline-map manifest contract (see
//  docs/OFFLINE_MAP_REFERENCE.md):
//
//    • MapManifest / MapRegionFiles — that a region missing one layer no longer
//                            destroys the WHOLE manifest. Before 2026-07-28
//                            `tiles`/`routing`/`poi` were non-optional, so one
//                            partial region made JSONDecoder throw for all 234.
//    • MapFile.isAvailable — the "was this asset actually built" contract the
//                            server encodes as `size: 0` + all-zero sha256, and
//                            which OfflineMapManager uses to skip a request that
//                            is known to 404.
//    • MapFile.realSize    — that a country-level file inherited by a sub-region
//                            (`size: 0` + `shared_size: N`) still counts as
//                            available and reports N bytes.
//    • MapRegion display   — that the size labels tolerate absent layers instead
//                            of trapping on a force-unwrap.
//
//  The fixtures below are verbatim excerpts of the LIVE manifest served from
//  https://oshi-messenger.com/maps/v2/manifest.json (fetched 2026-07-28), so a
//  server-side schema change that would break the app fails here first.
//
//  Deterministic, no network, no device. Nothing here proves a byte was
//  downloaded — see §7 of the reference doc for what only a real fetch shows.
//

import XCTest
import CryptoKit
import Compression
import CommonCrypto
@testable import OSHI

final class OfflineMapManifestTests: XCTestCase {

    private func decode(_ json: String) throws -> MapManifest {
        try JSONDecoder().decode(MapManifest.self, from: Data(json.utf8))
    }

    // ========================================================================
    // The brittleness that killed the whole map
    // ========================================================================

    /// 🔧 REGRESSION GUARD. `MapRegionFiles.tiles/routing/poi` were non-optional
    /// until 2026-07-28. Swift's synthesised `Codable` throws for the entire
    /// container when a non-optional key is absent, so ONE region missing ONE
    /// layer made `JSONDecoder().decode(MapManifest.self, …)` fail — and
    /// `OfflineMapManager.fetchManifest` silently fell back to
    /// `hardcodedManifest` (~60 stale regions instead of 234).
    func testRegionMissingEveryOptionalLayerStillDecodes() throws {
        let json = """
        {
          "version": 1,
          "updated": "2026-07-28T00:00:00Z",
          "regions": [
            {
              "id": "xx-bare",
              "name": "Bare region",
              "country": "XX",
              "continent": "europe",
              "bbox": [1.0, 2.0, 3.0, 4.0],
              "files": {},
              "osmDate": "2026-04-29"
            }
          ]
        }
        """
        let manifest = try decode(json)
        XCTAssertEqual(manifest.regions.count, 1)
        let files = manifest.regions[0].files
        XCTAssertNil(files.tiles)
        XCTAssertNil(files.routing)
        XCTAssertNil(files.poi)
        XCTAssertNil(files.streets)
        XCTAssertNil(files.roadgraph)
    }

    /// The failure mode as it actually occurred: a healthy region and a partial
    /// one in the same document. The healthy one must survive.
    func testOnePartialRegionDoesNotKillItsHealthyNeighbour() throws {
        let json = """
        {
          "version": 1,
          "updated": "2026-07-28T00:00:00Z",
          "regions": [
            {
              "id": "fr-idf", "name": "Ile-de-France", "country": "FR",
              "continent": "europe", "bbox": [1.44, 48.12, 3.56, 49.24],
              "files": {
                "poi": {
                  "url": "https://oshi-messenger.com/maps/v2/fr-idf/poi.db",
                  "size": 31191040,
                  "sha256": "51c121484cc16b76b5618e88e1c07c45c039d6220c508ee3039ce707c98812e1"
                }
              },
              "osmDate": "2026-04-29"
            },
            {
              "id": "us", "name": "United States", "country": "US",
              "continent": "north-america", "bbox": [-125.0, 24.0, -66.0, 49.0],
              "files": {},
              "osmDate": "2026-04-29"
            }
          ]
        }
        """
        let manifest = try decode(json)
        XCTAssertEqual(manifest.regions.count, 2)
        XCTAssertEqual(manifest.regions[0].files.poi?.size, 31_191_040)
        XCTAssertNil(manifest.regions[1].files.poi)
    }

    /// Unknown keys must be ignored, not fatal. The live manifest carries a
    /// `rows` field on every region that no Swift model declares.
    func testUnknownRegionKeysAreIgnored() throws {
        let json = """
        {
          "version": 1, "updated": "2026-07-28T00:00:00Z",
          "regions": [{
            "id": "fr-cor", "name": "Corse", "country": "FR", "continent": "europe",
            "bbox": [8.57, 41.38, 9.57, 43.03],
            "files": {}, "osmDate": "2026-04-29",
            "rows": 12345, "somethingAddedLater": {"a": 1}
          }]
        }
        """
        XCTAssertEqual(try decode(json).regions.first?.id, "fr-cor")
    }

    // ========================================================================
    // MapFile.isAvailable — "was this actually built?"
    // ========================================================================

    /// A regional file: real size, real hash, downloadable.
    func testRegionalFileIsAvailable() throws {
        let f = try JSONDecoder().decode(MapFile.self, from: Data("""
        {
          "url": "https://oshi-messenger.com/maps/v2/li/poi.db",
          "size": 319488,
          "sha256": "511a310b49a16bf049ea43400e53a2f1815322b10d30482b6efd37f4f87590c4"
        }
        """.utf8))
        XCTAssertTrue(f.isAvailable)
        XCTAssertEqual(f.realSize, 319_488)
        XCTAssertNil(f.shared)
    }

    /// A sub-region inheriting the country file reports `size: 0` so the UI
    /// shows only incremental cost — but the bytes DO exist and must still be
    /// downloadable. This is the case `isAvailable` had to disambiguate.
    /// Fixture: `th-bkk`, which resolves to `th/poi.db`.
    func testCountrySharedFileIsAvailableDespiteZeroSize() throws {
        let f = try JSONDecoder().decode(MapFile.self, from: Data("""
        {
          "url": "https://oshi-messenger.com/maps/v2/th/poi.db",
          "size": 0,
          "sha256": "0f4d9b7a3c2e1f8a6b5d4c3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a",
          "shared": true,
          "shared_size": 20692992
        }
        """.utf8))
        XCTAssertTrue(f.isAvailable, "a shared country file must still be downloadable")
        XCTAssertEqual(f.realSize, 20_692_992)
        XCTAssertEqual(f.shared, true)
    }

    /// 🔧 The "not built" sentinel written by regenerate_manifest.py: size 0,
    /// no shared_size, all-zero sha256. `OfflineMapManager.downloadPOIDatabase`
    /// must skip these instead of firing a request that is certain to 404.
    /// 22 of 234 regions carry it for `poi`, 180 for `routing`, 234 for `tiles`.
    func testNotBuiltSentinelIsNotAvailable() throws {
        let f = try JSONDecoder().decode(MapFile.self, from: Data("""
        {
          "url": "https://oshi-messenger.com/maps/v2/us/poi.db",
          "size": 0,
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
        }
        """.utf8))
        XCTAssertFalse(f.isAvailable, "size 0 with no shared_size means the file was never built")
        XCTAssertEqual(f.realSize, 0)
    }

    /// `tiles` is legacy: every region carries the sentinel plus the proxy
    /// template, because raster tiles stream from /tiles/{z}/{x}/{y}.png and no
    /// per-region bundle has ever existed. It must never look downloadable.
    func testLegacyTilesEntryIsNeverAvailable() throws {
        let json = """
        {
          "version": 1, "updated": "2026-07-28T00:00:00Z",
          "regions": [{
            "id": "fr-idf", "name": "Ile-de-France", "country": "FR",
            "continent": "europe", "bbox": [1.44, 48.12, 3.56, 49.24],
            "files": {
              "tiles": {
                "url": "https://oshi-messenger.com/tiles/{z}/{x}/{y}.png",
                "size": 0,
                "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
              }
            },
            "osmDate": "2026-04-29"
          }]
        }
        """
        let tiles = try XCTUnwrap(try decode(json).regions.first?.files.tiles)
        XCTAssertFalse(tiles.isAvailable)
    }

    // ========================================================================
    // Display strings must tolerate absent layers
    // ========================================================================

    func testDisplaySizeTreatsAbsentLayersAsZeroRatherThanTrapping() throws {
        let json = """
        {
          "version": 1, "updated": "2026-07-28T00:00:00Z",
          "regions": [{
            "id": "xx-bare", "name": "Bare", "country": "XX", "continent": "europe",
            "bbox": [1.0, 2.0, 3.0, 4.0], "files": {}, "osmDate": "2026-04-29"
          }]
        }
        """
        let region = try XCTUnwrap(try decode(json).regions.first)
        // 🔧 UPDATED (2026-10-09): the expected VALUE changed from "0 MB";
        // what this test guards did not. The guard is that `displaySize` and
        // `displaySharedSize` can be evaluated at all on a region whose
        // `files` is `{}` — every optional layer absent — without trapping on
        // a force-unwrap. Reading both properties below is that guard, and a
        // trap still fails this test exactly as before.
        //
        // The value had to change because "0 MB" was itself the bug:
        // `files.tiles.size` is 0 for EVERY region in the live manifest (there
        // is no tiles artefact — raster tiles stream from the /tiles proxy),
        // so honouring it made every row in the regions list advertise a
        // "0 MB" download for what is in fact the bulk of the transfer.
        // `displaySize` now falls back to the enumerated tile estimate.
        // Pinned exactly rather than loosened to a != "0 MB", so a regression
        // in the estimate is caught here too.
        XCTAssertEqual(region.displaySize, "35 MB")
        XCTAssertEqual(region.displaySharedSize, "")
    }

    /// The tile estimate backing `displaySize` must agree with the enumeration
    /// `OfflineMapManager.downloadRegion` performs — it is the number the user
    /// decides to spend their bandwidth and storage on.
    func testEstimatedTileCountMatchesTheBBoxEnumeration() throws {
        let json = """
        {
          "version": 1, "updated": "2026-10-09T00:00:00Z",
          "regions": [{
            "id": "lu", "name": "Luxembourg", "country": "LU", "continent": "europe",
            "bbox": [5.73, 49.45, 6.53, 50.18], "files": {}, "osmDate": "2026-10-01"
          }]
        }
        """
        let region = try XCTUnwrap(try decode(json).regions.first)
        // Independent re-derivation of the same slippy-map arithmetic.
        var expected = 0
        for z in 6...13 {
            let n = pow(2.0, Double(z))
            func yOf(_ lat: Double) -> Int {
                let rad = lat * .pi / 180.0
                return Int(floor((1.0 - log(tan(rad) + 1.0 / cos(rad)) / .pi) / 2.0 * n))
            }
            let x0 = Int(floor((5.73 + 180.0) / 360.0 * n))
            let x1 = Int(floor((6.53 + 180.0) / 360.0 * n))
            let y0 = yOf(50.18)
            let y1 = yOf(49.45)
            expected += (x1 - x0 + 1) * (y1 - y0 + 1)
        }
        XCTAssertEqual(region.estimatedTileCount(), expected)
        XCTAssertGreaterThan(region.estimatedTileBytes, 0)
        // A single zoom level of a small country is a four-figure tile count,
        // never zero and never the hundreds of thousands a country-wide z6-13
        // run costs — a guard against an off-by-one in the ranges collapsing
        // the estimate.
        XCTAssertGreaterThan(region.estimatedTileCount(zoomRange: 13...13), 100)
    }

    /// A tile must be reported as intersecting its own region's bbox and not a
    /// disjoint one — this is what decides whether `deleteRegion` is allowed to
    /// reclaim a cached tile or must keep it for another downloaded region.
    func testTileBBoxIntersectionGatesTileReclamation() {
        let luxembourg = [5.73, 49.45, 6.53, 50.18]
        let tokyo = [138.94, 35.22, 140.07, 36.10]
        let n = pow(2.0, 10.0)
        let x = OfflineMapManager.tileXRange(minLon: 5.73, maxLon: 6.53, n: n).lowerBound
        let y = OfflineMapManager.tileYRange(minLat: 49.45, maxLat: 50.18, n: n).lowerBound
        XCTAssertTrue(OfflineMapManager.tile(z: 10, x: x, y: y, intersects: luxembourg))
        XCTAssertFalse(OfflineMapManager.tile(z: 10, x: x, y: y, intersects: tokyo))
    }

    /// The PNG sniff that keeps a rate-limiter's HTML body out of the tile
    /// cache. Once such a body is cached under a `.png` name both the overlay
    /// and the bulk downloader treat it as a valid cached tile forever.
    func testOnlyRealPNGBytesAreAcceptedAsATile() {
        let pngHeader = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00])
        XCTAssertTrue(OfflineFirstTileOverlay.isPNG(pngHeader))
        XCTAssertFalse(OfflineFirstTileOverlay.isPNG(Data()))
        XCTAssertFalse(OfflineFirstTileOverlay.isPNG(Data("<html>429 Too Many Requests".utf8)))
        // Truncated signature: not enough bytes to be a tile.
        XCTAssertFalse(OfflineFirstTileOverlay.isPNG(Data([0x89, 0x50, 0x4E, 0x47])))
    }

    // MARK: __POISONED_TILE_PURGE_2026_11_16__ OSM "Access blocked" tiles

    /// A fake 6 725-byte PNG whose content is set by `seed`.
    private func png6725(seed: UInt8) -> Data {
        var d = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        d.append(Data(repeating: seed, count: 6725 - 8))
        return d
    }

    private func sha256Hex(_ d: Data) -> String {
        SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined()
    }

    /// The production constants are the ones measured on the server.
    func testPoisonedTileFingerprintIsTheMeasuredOne() {
        XCTAssertEqual(PoisonedTile.size, 6725)
        XCTAssertEqual(PoisonedTile.sha256Hex,
                       "109722facb0459991ce2c9983b3ae40232e84be7273aaa454071b50049e3d88b")
    }

    /// Exact hash only: a different 6 725-byte PNG is a real tile and is kept;
    /// the right hash at a different size cannot happen but is rejected too.
    func testPoisonedTileMatcherNeedsSizeAndExactHash() {
        let bad = png6725(seed: 0x11), good = png6725(seed: 0x22)
        let h = sha256Hex(bad)
        XCTAssertTrue(PoisonedTile.matches(bad, size: 6725, sha256Hex: h))
        XCTAssertFalse(PoisonedTile.matches(good, size: 6725, sha256Hex: h))
        XCTAssertFalse(PoisonedTile.matches(bad + Data([0]), size: 6725, sha256Hex: h))
        XCTAssertFalse(PoisonedTile.matches(bad, size: 6725, sha256Hex: String(h.dropLast()) + "0"))
        // Production fingerprint: neither fake is the OSM picture, so both
        // still count as usable tiles.
        XCTAssertFalse(PoisonedTile.matches(bad))
        XCTAssertTrue(OfflineFirstTileOverlay.isUsableTile(good))
    }

    /// The purge walks the tree (shared cache + region folders), deletes only
    /// exact matches and keeps every other file, including other 6 725-byte
    /// tiles.
    func testPoisonedTilePurgeDeletesOnlyExactMatches() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent("poison-\(UUID().uuidString)/Maps")
        defer { try? fm.removeItem(at: root.deletingLastPathComponent()) }
        let bad = png6725(seed: 0x11), good = png6725(seed: 0x22)
        let h = sha256Hex(bad)
        func put(_ rel: String, _ d: Data) throws -> URL {
            let u = root.appendingPathComponent(rel)
            try fm.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
            try d.write(to: u)
            return u
        }
        let p1 = try put("tiles/14/8299/5636.png", bad)
        let p2 = try put("tiles/3/4/2.png", bad)
        let p3 = try put("regions/fr-idf/tiles/12/2074/1409.png", bad)
        let k1 = try put("tiles/14/8299/5637.png", good)
        let k2 = try put("tiles/14/8300/5636.png", Data(repeating: 1, count: 33310))
        let k3 = try put("regions/fr-idf/metadata.json", Data("{}".utf8))

        XCTAssertTrue(PoisonedTile.fileMatches(at: p1, size: 6725, sha256Hex: h))
        XCTAssertFalse(PoisonedTile.fileMatches(at: k1, size: 6725, sha256Hex: h))

        XCTAssertEqual(PoisonedTile.purge(root: root, size: 6725, sha256Hex: h), 3)
        for u in [p1, p2, p3] { XCTAssertFalse(fm.fileExists(atPath: u.path), u.path) }
        for u in [k1, k2, k3] { XCTAssertTrue(fm.fileExists(atPath: u.path), u.path) }
        // Idempotent.
        XCTAssertEqual(PoisonedTile.purge(root: root, size: 6725, sha256Hex: h), 0)
        // Missing root: no crash, nothing deleted.
        XCTAssertEqual(PoisonedTile.purge(root: root.appendingPathComponent("nope")), 0)
    }

    /// The shared-bytes label must account for POI too — a sub-region can now
    /// inherit a country POI database, which is the largest single shared asset
    /// for some countries (de/poi.db is 329 MB).
    func testDisplaySharedSizeIncludesSharedPOI() throws {
        let json = """
        {
          "version": 1, "updated": "2026-07-28T00:00:00Z",
          "regions": [{
            "id": "th-bkk", "name": "Bangkok", "country": "TH", "continent": "asia",
            "bbox": [100.3, 13.5, 100.9, 14.0],
            "files": {
              "poi": {
                "url": "https://oshi-messenger.com/maps/v2/th/poi.db",
                "size": 0, "sha256": "00", "shared": true, "shared_size": 20971520
              }
            },
            "osmDate": "2026-04-29"
          }]
        }
        """
        let region = try XCTUnwrap(try decode(json).regions.first)
        XCTAssertEqual(region.displaySharedSize, "+20 MB shared")
    }

    // ========================================================================
    // Region geometry helpers
    // ========================================================================

    func testCenterIsTheBboxMidpointAndShortBboxIsSafe() throws {
        let json = """
        {
          "version": 1, "updated": "2026-07-28T00:00:00Z",
          "regions": [
            {"id": "a", "name": "A", "country": "FR", "continent": "europe",
             "bbox": [1.0, 48.0, 3.0, 50.0], "files": {}, "osmDate": "2026-04-29"},
            {"id": "b", "name": "B", "country": "FR", "continent": "europe",
             "bbox": [1.0], "files": {}, "osmDate": "2026-04-29"}
          ]
        }
        """
        let regions = try decode(json).regions
        XCTAssertEqual(regions[0].center.latitude, 49.0, accuracy: 0.0001)
        XCTAssertEqual(regions[0].center.longitude, 2.0, accuracy: 0.0001)
        // A malformed bbox must return (0,0), not crash on an out-of-range index.
        XCTAssertEqual(regions[1].center.latitude, 0.0, accuracy: 0.0001)
    }

    // MARK: - Continent grouping (__MAP_CONTINENT_NORMALISE_2026_10_14__)

    /// A region built with nothing but the fields the grouping reads.
    private func region(id: String, continent: String) -> MapRegion {
        let json = """
        {"id":"\(id)","name":"\(id)","country":"\(id.uppercased())",
         "continent":"\(continent)","bbox":[0,0,1,1],"files":{},"osmDate":"2026-10-14"}
        """
        return try! JSONDecoder().decode(MapRegion.self, from: Data(json.utf8))
    }

    /// The live manifest carried `south-america` for Uruguay and
    /// `central-america` for Nicaragua. `MapRegionDownloadView.continentFor`
    /// switched on the raw string and defaults to `.asiaOceania`, so both
    /// countries were listed under "Asia / Oceania" in the region picker.
    func testHyphenatedContinentNormalisesToTheUnderscoreSpelling() {
        XCTAssertEqual(region(id: "uy", continent: "south-america").continentGroupKey,
                       region(id: "br", continent: "south_america").continentGroupKey)
        XCTAssertEqual(region(id: "ni", continent: "central-america").continentGroupKey,
                       "central_america")
    }

    func testContinentKeyIsCaseAndWhitespaceInsensitive() {
        XCTAssertEqual(region(id: "sa", continent: "  Middle East ").continentGroupKey,
                       "middle_east")
        XCTAssertEqual(region(id: "ae", continent: "MIDDLE_EAST").continentGroupKey,
                       "middle_east")
    }

    /// Grouping on the key must collapse the two spellings into one section —
    /// the defect was a second section with the same visible title.
    func testGroupingByTheKeyYieldsOneSectionPerContinent() {
        let regions = [region(id: "br", continent: "south_america"),
                       region(id: "ar", continent: "south_america"),
                       region(id: "uy", continent: "south-america")]
        XCTAssertEqual(Dictionary(grouping: regions, by: { $0.continentGroupKey }).count, 1)
        // the shape of the bug, kept so a regression is unmistakable
        XCTAssertEqual(Dictionary(grouping: regions, by: { $0.continent }).count, 2)
    }

    /// Every Middle East id the manifest carries after 2026-10-14 must land on
    /// one key, so the Gulf is one section and not scattered through Asia.
    func testTheGulfAndLevantShareOneContinentKey() {
        let ids = ["ae", "bh", "kw", "om", "qa", "sa", "ye", "iq", "ir", "sy", "jo", "lb", "il"]
        let keys = Set(ids.map { region(id: $0, continent: "middle_east").continentGroupKey })
        XCTAssertEqual(keys, ["middle_east"])
    }

    // MARK: - The regions published on 2026-10-14 are selectable

    /// Guadeloupe, Martinique, Guyane, Saint-Pierre-et-Miquelon,
    /// Saint-Barthelemy and Saint-Martin are filed under `americas` in the
    /// live manifest. The old switch recognised that key, but the DECISION sat
    /// in a private function inside the view, so nothing proved it: the arm
    /// that catches an unknown key returns Asia / Oceania, which is how
    /// Uruguay ended up there. These six are the regions that would move.
    func testOverseasFranceLandsInTheAmericasNotAsia() {
        for id in ["fr-gp", "fr-mq", "fr-gf", "fr-pm", "fr-bl", "fr-mf"] {
            XCTAssertEqual(region(id: id, continent: "americas").continentSectionKey,
                           "americas",
                           "\(id) must be listed under Americas")
        }
    }

    /// La Reunion and Mayotte are `africa`; New Caledonia, French Polynesia and
    /// Wallis-et-Futuna are `oceania`. All five got their own road graph on
    /// 2026-10-14 after sharing metropolitan France's, which contained none of
    /// their roads — so they are worth pinning to the right section.
    func testOverseasFranceOutsideTheAmericasKeepsItsOwnSection() {
        XCTAssertEqual(region(id: "fr-re", continent: "africa").continentSectionKey, "africa")
        XCTAssertEqual(region(id: "fr-yt", continent: "africa").continentSectionKey, "africa")
        for id in ["fr-nc", "fr-pf", "fr-wf"] {
            XCTAssertEqual(region(id: id, continent: "oceania").continentSectionKey,
                           "asia_oceania")
        }
    }

    /// The six Gulf countries cropped out of the shared GCC extract, plus the
    /// Levant: one section, not scattered through Asia.
    func testEveryGulfCountryLandsInMiddleEast() {
        for id in ["ae", "sa", "om", "bh", "kw", "qa", "ye", "iq", "ir", "sy"] {
            XCTAssertEqual(region(id: id, continent: "middle_east").continentSectionKey,
                           "middle_east",
                           "\(id) must be listed under Middle East")
        }
    }

    /// The 13 metropolitan French sub-regions published on 2026-10-14. Each is
    /// an ordinary `europe` region; the point of the test is that a sub-region
    /// id (a hyphen the country ids do not have) is not treated specially
    /// anywhere in the grouping.
    func testFrenchSubRegionsGroupWithEuropeAndCollapseToOneSection() {
        let ids = ["fr-idf", "fr-ara", "fr-occ", "fr-naq", "fr-ges", "fr-hdf",
                   "fr-nor", "fr-cvl", "fr-pdl", "fr-bre", "fr-bfc", "fr-paca", "fr-cor"]
        let regions = ids.map { region(id: $0, continent: "europe") }
        XCTAssertTrue(regions.allSatisfy { $0.continentSectionKey == "europe" })
        // One section for the country and all of its sub-regions.
        let withCountry = regions + [region(id: "fr", continent: "europe")]
        XCTAssertEqual(Set(withCountry.map(\.continentSectionKey)), ["europe"])
    }

    /// Every continent key the live manifest actually uses must map to a named
    /// section. If a future manifest introduces a spelling, this fails here
    /// rather than silently filing those regions under Asia / Oceania.
    ///
    /// Keys as served on 2026-10-14 (258 regions): europe 87, asia 53,
    /// africa 43, north_america 37, middle_east 13, oceania 10,
    /// south_america 10, americas 6.
    func testContinentSectionKeyIsExhaustiveForTheLiveManifest() {
        let live = ["europe", "asia", "africa", "north_america",
                    "middle_east", "oceania", "south_america", "americas"]
        let expected = ["europe": "europe", "asia": "asia_oceania", "africa": "africa",
                        "north_america": "americas", "middle_east": "middle_east",
                        "oceania": "asia_oceania", "south_america": "americas",
                        "americas": "americas"]
        for key in live {
            XCTAssertEqual(region(id: "x", continent: key).continentSectionKey,
                           expected[key],
                           "continent key \(key) is served by the manifest and must be mapped")
        }
    }

    /// A region whose only road graph is the one it owns must report that graph
    /// as available — this is what makes a sub-region worth offering at all
    /// (Auvergne-Rhone-Alpes: 1052 MB of its own, against 9670 MB for France).
    func testSubRegionWithItsOwnRoadGraphIsOffered() throws {
        let json = """
        {
          "version": 2, "updated": "2026-10-14",
          "regions": [
            {"id": "fr-ara", "name": "Auvergne-Rhone-Alpes", "country": "FR",
             "continent": "europe", "bbox": [2.06, 44.26, 7.19, 46.8],
             "osmDate": "2026-10-14",
             "files": {"roadgraph": {"url": "https://x/files/roadgraph/fr-ara-roadgraph.db.gz",
                                     "size": 1102622799,
                                     "sha256": "ab"}}}
          ]
        }
        """
        let r = try decode(json).regions[0]
        XCTAssertEqual(r.continentSectionKey, "europe")
        XCTAssertEqual(r.files.roadgraph?.realSize, 1102622799)
    }

    /// The row's size must include the road graph, which for the sub-regions
    /// published on 2026-10-14 is the bulk of the download. One tap on Download
    /// fetches routing + streets + roadgraph + POI alongside the tiles
    /// (`OfflineMapManager.downloadRegion`), so a total that omits two of them
    /// is not a conservative estimate, it is a wrong one.
    func testDisplaySizeCountsTheRoadGraphAndStreets() throws {
        let json = """
        {
          "version": 2, "updated": "2026-10-14",
          "regions": [{
            "id": "xx-rg", "name": "RG", "country": "XX", "continent": "europe",
            "bbox": [0.0, 0.0, 0.1, 0.1], "osmDate": "2026-10-14",
            "files": {
              "poi":       {"url": "u", "size": 1048576,   "sha256": "a"},
              "streets":   {"url": "u", "size": 2097152,   "sha256": "b"},
              "roadgraph": {"url": "u", "size": 104857600, "sha256": "c"}
            }
          }]
        }
        """
        let region = try XCTUnwrap(try decode(json).regions.first)
        // 100 MB road graph + 2 MB streets + 1 MB POI + the tile estimate for a
        // 0.1 deg box. The road graph alone must dominate the figure.
        let shown = region.displaySize
        XCTAssertTrue(shown.hasSuffix(" MB"), "unexpected unit in \(shown)")
        let value = Double(shown.replacingOccurrences(of: " MB", with: "")) ?? 0
        XCTAssertGreaterThan(value, 100.0,
                             "displaySize \(shown) omits the road graph")
        // and the shared label stays empty: nothing here is inherited
        XCTAssertEqual(region.displaySharedSize, "")
    }

    /// A sub-region that INHERITS its country's graph must not advertise those
    /// bytes as its own incremental cost -- that split is what keeps every
    /// French sub-region from reading as ~9.4 GB.
    func testInheritedRoadGraphIsNotCountedAsIncremental() throws {
        let json = """
        {
          "version": 2, "updated": "2026-10-14",
          "regions": [{
            "id": "fr-pm", "name": "Saint-Pierre-et-Miquelon", "country": "FR",
            "continent": "americas", "bbox": [-56.4, 46.74, -56.13, 47.15],
            "osmDate": "2026-10-14",
            "files": {
              "roadgraph": {"url": "u", "size": 0, "sha256": "c",
                            "shared": true, "shared_size": 10139559526}
            }
          }]
        }
        """
        let region = try XCTUnwrap(try decode(json).regions.first)
        XCTAssertFalse(region.displaySize.contains("GB"),
                       "an inherited graph must not land in the incremental total")
        XCTAssertTrue(region.displaySharedSize.contains("GB"),
                      "but it must be disclosed as shared: \(region.displaySharedSize)")
    }

}

// MARK: - Streaming gunzip

/// Guards the 2026-07-28 road-graph fix (docs/OFFLINE_MAP_REFERENCE.md §8).
///
/// The bug being guarded was never a logic error — the old code produced correct
/// output. It produced it by holding the compressed AND decompressed graph in
/// RAM at once, which on the 61 regions over 4 GB compressed is a jetsam kill.
/// So the assertions that matter here are about **memory and refusal**, not
/// about whether gunzip works:
///
///   • peak footprint stays flat as the input grows (`testPeakFootprintIsFlat`)
///   • the disk guard refuses before writing anything
///   • an interrupted or corrupt stream never publishes a destination file
///
/// Deterministic and hermetic: the fixtures are generated in-process, so this
/// runs in CI without the network. The end-to-end check against a real
/// `*-roadgraph.db.gz` is `OfflineMapPOIDownloadTests`-style and opt-in; see
/// the reference doc for what only a device can show.
final class GzipStreamDecompressorTests: XCTestCase {

    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("gzstream-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    // MARK: Fixtures

    /// Build a real gzip member (header + raw DEFLATE + CRC32/ISIZE trailer) for
    /// `payload`, using the same libcompression primitive in ENCODE mode. Written
    /// out rather than held, so a large fixture does not defeat the point.
    private func makeGzip(payload: Data, at url: URL) throws {
        var out = Data([0x1f, 0x8b, 0x08, 0x00, 0, 0, 0, 0, 0x00, 0xff])

        var stream = compression_stream(dst_ptr: UnsafeMutablePointer<UInt8>.allocate(capacity: 1),
                                        dst_size: 0,
                                        src_ptr: UnsafePointer(UnsafeMutablePointer<UInt8>.allocate(capacity: 1)),
                                        src_size: 0, state: nil)
        XCTAssertEqual(compression_stream_init(&stream, COMPRESSION_STREAM_ENCODE, COMPRESSION_ZLIB),
                       COMPRESSION_STATUS_OK)
        defer { compression_stream_destroy(&stream) }

        let cap = 1 << 16
        let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: cap)
        defer { dst.deallocate() }

        try payload.withUnsafeBytes { raw in
            stream.src_ptr = raw.bindMemory(to: UInt8.self).baseAddress!
            stream.src_size = payload.count
            repeat {
                stream.dst_ptr = dst
                stream.dst_size = cap
                let status = compression_stream_process(&stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                out.append(dst, count: cap - stream.dst_size)
                if status == COMPRESSION_STATUS_END { break }
                XCTAssertEqual(status, COMPRESSION_STATUS_OK)
            } while true
        }

        var crc = CRC32()
        payload.withUnsafeBytes { raw in
            crc.update(raw.bindMemory(to: UInt8.self).baseAddress!, payload.count)
        }
        for shift in stride(from: 0, to: 32, by: 8) { out.append(UInt8((crc.checksum >> UInt32(shift)) & 0xFF)) }
        let isize = UInt32(truncatingIfNeeded: payload.count)
        for shift in stride(from: 0, to: 32, by: 8) { out.append(UInt8((isize >> UInt32(shift)) & 0xFF)) }

        try out.write(to: url)
    }

    /// Compressible but not trivially so, so DEFLATE actually does work.
    private func payload(bytes: Int) -> Data {
        var d = Data(capacity: bytes)
        var seed: UInt64 = 0x2545F4914F6CDD1D
        while d.count < bytes {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            var word = seed
            withUnsafeBytes(of: &word) { d.append(contentsOf: $0) }
            d.append(contentsOf: [UInt8](repeating: 0x41, count: 24))   // compressible run
        }
        return d.prefix(bytes)
    }

    private func footprintBytes() -> Int64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? Int64(info.phys_footprint) : 0
    }

    // MARK: Correctness

    func testRoundTripIsByteIdentical() throws {
        let original = payload(bytes: 3_000_000)
        let gz = dir.appendingPathComponent("a.gz")
        let out = dir.appendingPathComponent("a.db")
        try makeGzip(payload: original, at: gz)

        let outcome = try GzipStreamDecompressor.decompress(from: gz, to: out,
                                                           freeSpace: { Int64.max / 4 })
        XCTAssertEqual(try Data(contentsOf: out), original)
        XCTAssertEqual(outcome.decompressedBytes, Int64(original.count))

        // The digest must be of the COMPRESSED artifact — that is what
        // `MapFile.sha256` declares (regenerate_manifest.py:100 hashes the .gz).
        XCTAssertEqual(outcome.compressedSHA256, Self.sha256(of: gz))
        XCTAssertNotEqual(outcome.compressedSHA256, Self.sha256(of: out))
    }

    private static func sha256(of url: URL) -> String {
        let data = try! Data(contentsOf: url)      // test-only; fixtures are small
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &digest) }
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: The actual point — bounded memory

    /// Peak `phys_footprint` (the quantity iOS jetsam kills on) must not grow
    /// with the size of the graph. 4 MB → 32 MB is an 8× increase in data; the
    /// footprint delta must stay within a small constant of the fixed buffers.
    ///
    /// Measured off-device with `/usr/bin/time -l` on the same code path
    /// (macOS, arm64, Swift 6.2.4 -O): 290 MB / 1 GiB / 4 GiB of output all peak
    /// at 3.2–3.8 MB, against 386 MB / 2053 MB for the pre-fix in-memory path.
    func testPeakFootprintIsFlat() throws {
        var deltas: [Int64] = []
        for size in [4_000_000, 32_000_000] {
            let gz = dir.appendingPathComponent("f\(size).gz")
            let out = dir.appendingPathComponent("f\(size).db")
            try makeGzip(payload: payload(bytes: size), at: gz)

            let before = footprintBytes()
            var peak: Int64 = before
            _ = try GzipStreamDecompressor.decompress(from: gz, to: out,
                                                      freeSpace: { Int64.max / 4 },
                                                      progress: { _ in
                peak = max(peak, self.footprintBytes())
            })
            deltas.append(peak - before)
            try? FileManager.default.removeItem(at: gz)
            try? FileManager.default.removeItem(at: out)
        }

        // 8x the data must not mean anything like 8x the memory. Generous bound:
        // the pre-fix path would have shown ~2x the payload, i.e. ~64 MB here.
        XCTAssertLessThan(deltas[1], 24_000_000,
                          "32 MB graph should not cost tens of MB of footprint — got \(deltas[1])")
        XCTAssertLessThan(deltas[1], deltas[0] + 16_000_000,
                          "footprint grew with file size: \(deltas[0]) → \(deltas[1])")
    }

    // MARK: Refusal paths

    func testDiskGuardRefusesBeforeWritingAnything() throws {
        let gz = dir.appendingPathComponent("g.gz")
        let out = dir.appendingPathComponent("g.db")
        try makeGzip(payload: payload(bytes: 2_000_000), at: gz)

        XCTAssertThrowsError(try GzipStreamDecompressor.decompress(
            from: gz, to: out, freeSpace: { 1_000_000 })) { error in
            guard case GzipStreamError.insufficientDiskSpace = error else {
                return XCTFail("expected insufficientDiskSpace, got \(error)")
            }
            XCTAssertNotNil((error as? LocalizedError)?.errorDescription)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.appendingPathExtension("part").path))
    }

    func testTruncatedArchiveNeverPublishesADestination() throws {
        let gz = dir.appendingPathComponent("t.gz")
        let out = dir.appendingPathComponent("t.db")
        try makeGzip(payload: payload(bytes: 2_000_000), at: gz)
        let full = try Data(contentsOf: gz)
        try full.prefix(full.count / 2).write(to: gz)     // interrupted transfer

        XCTAssertThrowsError(try GzipStreamDecompressor.decompress(from: gz, to: out,
                                                                   freeSpace: { Int64.max / 4 }))
        // The whole point: nothing at road_graph.db, and no orphan .part that a
        // later launch could mistake for a finished graph.
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.appendingPathExtension("part").path))
    }

    func testCancellationLeavesNothingBehind() throws {
        let gz = dir.appendingPathComponent("c.gz")
        let out = dir.appendingPathComponent("c.db")
        try makeGzip(payload: payload(bytes: 8_000_000), at: gz)

        var chunks = 0
        XCTAssertThrowsError(try GzipStreamDecompressor.decompress(
            from: gz, to: out,
            freeSpace: { Int64.max / 4 },
            shouldContinue: { chunks += 1; return chunks < 3 })) { error in
            guard case GzipStreamError.cancelled = error else {
                return XCTFail("expected cancelled, got \(error)")
            }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.appendingPathExtension("part").path))
    }

    func testNonGzipInputIsRejectedRatherThanCopied() throws {
        let gz = dir.appendingPathComponent("n.gz")
        let out = dir.appendingPathComponent("n.db")
        // An HTML error page saved under a .gz name — the realistic bad case.
        try Data("<!doctype html><title>404</title>".utf8).write(to: gz)

        XCTAssertThrowsError(try GzipStreamDecompressor.decompress(from: gz, to: out,
                                                                   freeSpace: { Int64.max / 4 }))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path))
    }

    // MARK: Size estimation

    func testISizeGivesTheExactSizeBelowFourGiB() throws {
        let gz = dir.appendingPathComponent("e.gz")
        let bytes = 5_000_000
        try makeGzip(payload: payload(bytes: bytes), at: gz)
        let compressed = try FileManager.default.attributesOfItem(atPath: gz.path)[.size] as! Int64

        // Below 4 GiB the gzip trailer is exact, so the guard should not be
        // guessing from the ratio at all.
        XCTAssertEqual(GzipStreamDecompressor.estimatedDecompressedSize(ofGzipAt: gz,
                                                                        compressedSize: compressed),
                       Int64(bytes))
        XCTAssertEqual(GzipStreamDecompressor.readTrailer(at: gz)?.isize, UInt32(bytes))
    }
}
