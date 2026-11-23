package com.oshi.messenger

import com.oshi.messenger.data.model.MapManifest
import com.oshi.messenger.data.model.MapPOI
import com.oshi.messenger.data.model.POICategory
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.service.map.OshiTileSource
import com.oshi.messenger.ui.screens.continentKey
import com.oshi.messenger.ui.viewmodel.MapViewModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the defects found by driving the app on `emulator-5554`
 * (`parity/verify/2026-08-13-device-walkthrough-2.md`).
 *
 * Every assertion here corresponds to something that was observed on screen, not
 * inferred, and each would have failed against the build under test.
 */
class DeviceWalkthroughFixesTest {

    // ─────────────────────────────────────────────────────────────────────
    // P0 #3 — 🖕 in the default one-tap reaction row
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The long-press sheet rendered `✅ ❌ 😢 🖕 😘 👍` because both chat screens
     * iterated `ReactionEmojis.ALL` and the UI took the first six. iOS's quick bar
     * is a different, deliberate list (`MessageReactionManager.swift:83`).
     */
    @Test
    fun `quick reaction row matches iOS quickReactions exactly`() {
        // OSHI/MessageReactionManager.swift:82-84
        //   static var quickReactions: [ReactionEmoji] {
        //       [.check, .cross, .sad, .thumbsUp, .kiss, .hundred]
        //   }
        val expected = listOf(
            ReactionEmojis.CHECK,
            ReactionEmojis.CROSS,
            ReactionEmojis.SAD,
            ReactionEmojis.THUMBS_UP,
            ReactionEmojis.KISS,
            ReactionEmojis.HUNDRED
        )
        assertEquals(expected, ReactionEmojis.QUICK)
    }

    /**
     * The specific store/brand risk: whatever else changes, the middle finger must
     * never be one tap away by default. `ALL.take(6)` — what the screens actually
     * rendered — contained it; `QUICK` must not.
     */
    @Test
    fun `middle finger is absent from the quick row but present in ALL take 6 regression`() {
        assertFalse(
            "🖕 must never be a default one-tap reaction",
            ReactionEmojis.QUICK.contains(ReactionEmojis.MIDDLE_FINGER)
        )
        // Documents the exact shape of the bug that shipped, so a future refactor
        // that reintroduces `ALL.take(6)` is unambiguous rather than subtle.
        assertTrue(ReactionEmojis.ALL.take(6).contains(ReactionEmojis.MIDDLE_FINGER))
    }

    // ─────────────────────────────────────────────────────────────────────
    // P0 #1 — every map tile was OSM's 403 "not following the tile usage policy"
    // ─────────────────────────────────────────────────────────────────────

    /**
     * `MapScreen.kt` sent `userAgentValue = context.packageName` (a bare
     * `com.oshi.messenger`) to `tile.openstreetmap.org`. Both halves are fixed:
     * the origin is now first-party, and the UA carries a contact URL, matching
     * iOS `OfflineFirstTileOverlay.swift:167-170`.
     */
    @Test
    fun `tiles come from the first-party proxy and never from OSM volunteer servers`() {
        val url = OshiTileSource.tileUrl(z = 10, x = 511, y = 340)
        assertEquals("https://oshi-messenger.com/tiles/10/511/340.png", url)
        assertFalse(
            "OSM's volunteer tile servers must not be contacted directly",
            url.contains("tile.openstreetmap.org")
        )
        assertEquals("https://oshi-messenger.com/tiles/", OshiTileSource.BASE_URL)
    }

    @Test
    fun `tile user agent is contactable and is not the bare package name`() {
        val ua = OshiTileSource.USER_AGENT
        assertFalse("a bare application id is not a valid tile-client identity", ua == "com.oshi.messenger")
        assertTrue("UA must name the product", ua.startsWith("OSHI-Messenger/"))
        assertTrue("UA must carry a way to contact the operator", ua.contains("https://oshi-messenger.com"))
        assertTrue("UA must name the platform", ua.contains("Android"))
        // iOS sends the identical shape with the platform token swapped:
        // "OSHI-Messenger/1.0 (iOS; https://oshi-messenger.com)"
        assertEquals("OSHI-Messenger/1.0 (Android; https://oshi-messenger.com)", ua)
        assertEquals("https://oshi-messenger.com/", OshiTileSource.REFERER)
    }

    // ─────────────────────────────────────────────────────────────────────
    // P1 #14 — impossible offline-region sizes
    // ─────────────────────────────────────────────────────────────────────

    private val manifest: MapManifest by lazy {
        val json = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("maps_v2_manifest_trimmed.json")
        ).bufferedReader().readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(MapManifest.serializer(), json)
    }

    private fun region(id: String) = manifest.regions.first { it.id == id }

    /**
     * The displayed size used to be `tiles + routing + poi` only, so a whole
     * country came out at a few MB — Algeria showed **8 MB** while its manifest
     * row carries an 801 MB roadgraph. `streets` and `roadgraph` are both fetched
     * by `OfflineMapManager.downloadRegion`, so both must be counted.
     */
    @Test
    fun `region size counts the streets and roadgraph artifacts the downloader fetches`() {
        val gb = region("gb")
        val files = gb.files
        val tiles = files.tiles?.size ?: 0L
        val routing = files.routing?.size ?: 0L
        val poi = files.poi?.size ?: 0L
        val streets = files.streets?.size ?: 0L
        val roadgraph = files.roadgraph?.size ?: 0L

        // Guard the premise: the fixture really does carry non-zero streets +
        // roadgraph, otherwise this test would pass vacuously.
        assertTrue(streets > 0L)
        assertTrue(roadgraph > 0L)

        assertEquals(tiles + routing + poi + streets + roadgraph, gb.fileSize)
        // The old formula, kept explicit so the regression is legible.
        val oldFormula = tiles + routing + poi
        assertTrue("the new figure must be strictly larger", gb.fileSize > oldFormula)
    }

    /**
     * `tiles` is a legacy key the server always reports as `size: 0` (raster tiles
     * are streamed per zoom from the /tiles proxy), and shared artifacts report
     * `size: 0` with the real count in `shared_size`. A sub-region must therefore
     * still report only its own incremental bytes — over-counting a shared
     * country artifact per sub-region would be a new lie, not a fix.
     */
    @Test
    fun `shared artifacts stay out of the incremental size and are surfaced separately`() {
        val frIdf = region("fr-idf")
        assertEquals(31_191_040L, frIdf.fileSize)   // poi only; streets+routing are shared
        assertTrue(frIdf.hasKnownSize)
        assertTrue(
            "the shared country artifacts must still be disclosed to the user",
            frIdf.sharedSizeFormatted.isNotEmpty()
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // P1 #14 — the section header printed the raw server key: "🌍 africa"
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The emoji/label lookup only matched Title Case, while the live manifest
     * sends lowercase snake_case — so all eight continent values fell through to
     * the generic globe and the raw key was printed.
     */
    @Test
    fun `continent keys from the live manifest normalise to stable lookup keys`() {
        // The eight values the live manifest actually emits.
        assertEquals("africa", continentKey("africa"))
        assertEquals("americas", continentKey("americas"))
        assertEquals("asia", continentKey("asia"))
        assertEquals("europe", continentKey("europe"))
        assertEquals("middle_east", continentKey("middle_east"))
        assertEquals("north_america", continentKey("north_america"))
        assertEquals("oceania", continentKey("oceania"))
        assertEquals("south_america", continentKey("south_america"))

        // The Title-Case spellings the offline fallback manifest uses must land on
        // the SAME key, or the header would regress whenever the CDN is
        // unreachable and the fallback kicks in.
        assertEquals("north_america", continentKey("North America"))
        assertEquals("europe", continentKey(" Europe "))
        assertEquals("middle_east", continentKey("Middle-East"))
    }

    @Test
    fun `every continent in the manifest fixture normalises to a known key`() {
        val known = setOf(
            "africa", "americas", "asia", "europe",
            "middle_east", "north_america", "oceania", "south_america"
        )
        manifest.regions.forEach { r ->
            assertTrue(
                "unmapped continent key '${r.continent}' would print raw",
                continentKey(r.continent) in known
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // P1 #15 — map search returned duplicates and unrelated results
    // ─────────────────────────────────────────────────────────────────────

    private fun hit(
        id: String,
        name: String,
        address: String,
        lat: Double,
        lon: Double,
        importance: Double = 0.0
    ) = MapViewModel.OnlineHit(
        poi = MapPOI(
            id = id,
            name = name,
            category = POICategory.OTHER,
            lat = lat,
            lon = lon,
            address = address,
            regionId = "online"
        ),
        importance = importance
    )

    /**
     * The exact case observed on device: searching "Paris" returned
     * "Paris, France" **twice** plus "Bali, Indonesia".
     *
     * The duplicate survived because Nominatim models a city as several OSM
     * objects (node / relation / boundary way), each with its own `osm_id`, and
     * every dedupe in the file keys on `MapPOI.id`.
     */
    @Test
    fun `duplicate geocoder objects for one place collapse to a single result`() {
        val results = MapViewModel.refineOnlineHits(
            query = "Paris",
            hits = listOf(
                hit("nominatim_7444", "Paris", "Paris, France", 48.8566, 2.3522, importance = 0.9),
                // Same place, different OSM object → different id → used to survive.
                hit("nominatim_71525", "Paris", "Paris, France", 48.8570, 2.3510, importance = 0.8)
            ),
            userLat = null,
            userLon = null
        )
        assertEquals(1, results.size)
        assertEquals("Paris", results[0].name)
        // First occurrence wins, so the row the user sees is stable across runs.
        assertEquals("nominatim_7444", results[0].id)
    }

    /**
     * "Bali, Indonesia" came back for the query "Paris" and was displayed with
     * equal weight because the online list was assigned in Nominatim's own order
     * with no ranking at all. It is demoted, not censored — hiding a match the
     * geocoder returned would be its own failure mode.
     */
    @Test
    fun `an unrelated match is ranked below the exact one instead of by arrival order`() {
        val results = MapViewModel.refineOnlineHits(
            query = "Paris",
            hits = listOf(
                // Arrives FIRST, as it did on device.
                hit("nominatim_555", "Paris Salon", "Bali, Indonesia", -8.4095, 115.1889),
                hit("nominatim_7444", "Paris", "Paris, France", 48.8566, 2.3522)
            ),
            userLat = null,
            userLon = null
        )
        assertEquals(2, results.size)
        assertEquals("Paris", results[0].name)
        assertEquals("Paris Salon", results[1].name)
    }

    @Test
    fun `proximity breaks ties between equally good name matches`() {
        // Both are an exact name match; the near one must win when we know where
        // the user is.
        val results = MapViewModel.refineOnlineHits(
            query = "Springfield",
            hits = listOf(
                hit("far", "Springfield", "Illinois, USA", 39.7817, -89.6501),
                hit("near", "Springfield", "Massachusetts, USA", 42.1015, -72.5898)
            ),
            userLat = 42.3601,   // Boston
            userLon = -71.0589
        )
        assertEquals("near", results[0].id)
    }

    @Test
    fun `ranking falls back to geocoder importance when nothing else separates hits`() {
        val results = MapViewModel.refineOnlineHits(
            query = "zzz",   // matches neither name
            hits = listOf(
                hit("low", "Alpha", "Nowhere", 1.0, 1.0, importance = 0.1),
                hit("high", "Beta", "Elsewhere", 2.0, 2.0, importance = 0.9)
            ),
            userLat = null,
            userLon = null
        )
        assertEquals("high", results[0].id)
    }

    @Test
    fun `dedupe is accent and case insensitive`() {
        val results = MapViewModel.refineOnlineHits(
            query = "Orleans",
            hits = listOf(
                hit("a", "Orléans", "Loiret, France", 47.9029, 1.9093),
                hit("b", "ORLEANS", "loiret, france", 47.9030, 1.9094)
            ),
            userLat = null,
            userLon = null
        )
        assertEquals(1, results.size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // P0 #1 (part 2) — the poisoned tile cache the fix alone does not clear
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reproduces what was actually on disk after installing the fixed build:
     * 290 cached tiles, 259 of them byte-identical (OSM's "Access blocked"
     * placeholder, served with HTTP 200 so `isSuccessful` cached it), plus a
     * handful of real ones. Only the placeholder must be evicted.
     */
    @Test
    fun `placeholder repeated across many tile indices is evicted, real tiles are kept`() {
        val poison = "b02c44252dac5a5e820ecef1e9bf9200e9407c042df668a466a1aa81a9ecca7a"
        val cache = buildMap {
            repeat(259) { i -> put("Mapnik/12/20$i/1408.png.tile", poison) }
            put("Mapnik/12/2073/1409.png.tile", "realtile-a")
            put("Mapnik/12/2074/1410.png.tile", "realtile-b")
            put("Mapnik/13/4148/2818.png.tile", "realtile-c")
        }

        val evicted = com.oshi.messenger.service.map.TileCacheSanitizer.poisonedPaths(cache)

        assertEquals(259, evicted.size)
        assertTrue(evicted.all { cache[it] == poison })
        assertFalse(evicted.contains("Mapnik/12/2073/1409.png.tile"))
        assertFalse(evicted.contains("Mapnik/13/4148/2818.png.tile"))
    }

    /**
     * The rule must never touch a downloaded offline region: those tiles all have
     * distinct content, and they share the cache tree with the interactive map
     * (`OfflineMapManager.downloadRegion` writes into the same `Mapnik/` dir), so
     * a blanket wipe would have destroyed a user's multi-GB download.
     */
    @Test
    fun `a cache of only distinct tiles is left completely alone`() {
        val cache = (0 until 500).associate { "Mapnik/13/$it/2818.png.tile" to "content-$it" }
        assertTrue(com.oshi.messenger.service.map.TileCacheSanitizer.poisonedPaths(cache).isEmpty())
    }

    /**
     * A small accidental repeat — e.g. a couple of identical open-ocean tiles —
     * must stay below the threshold rather than being swept.
     */
    @Test
    fun `a few identical tiles stay below the placeholder threshold`() {
        val cache = mapOf(
            "a" to "ocean", "b" to "ocean", "c" to "ocean",
            "d" to "land-1", "e" to "land-2"
        )
        assertTrue(com.oshi.messenger.service.map.TileCacheSanitizer.poisonedPaths(cache).isEmpty())
    }

    @Test
    fun `haversine distance is correct to within a kilometre`() {
        // Paris ↔ London, ~344 km.
        val km = MapViewModel.haversineKm(48.8566, 2.3522, 51.5074, -0.1278)
        assertTrue("expected ~344 km, got $km", km in 343.0..345.0)
        assertEquals(0.0, MapViewModel.haversineKm(48.8566, 2.3522, 48.8566, 2.3522), 1e-9)
    }
}
