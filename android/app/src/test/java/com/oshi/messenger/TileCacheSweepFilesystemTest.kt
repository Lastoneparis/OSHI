package com.oshi.messenger

import com.oshi.messenger.service.map.TileCacheSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The tile-cache sweep, exercised **on a real filesystem**.
 *
 * ## Why this file exists
 *
 * `DeviceWalkthroughFixesTest` covers [TileCacheSanitizer.poisonedPaths] — the
 * pure `Map<path, hash> -> Set<path>` core. That is the easy half. Until this
 * file, **nothing ever ran [TileCacheSanitizer.sweep]**: the half that walks a
 * directory, hashes each file's *bytes*, and calls `delete()`. A guard nobody
 * has watched trip is not a guard, and the sweep is the app's only defence
 * against the failure that once made the Map tab 100 % dead — OSM answering
 * "App is not following the tile usage policy" with **HTTP 200**, so
 * `isSuccessful` cached the placeholder 259 times under 259 different
 * `{z}/{x}/{y}` paths.
 *
 * Every assertion below is about bytes on disk, not about a hash map handed to
 * the function by the test.
 *
 * ## Fixture is modelled on a real device census
 *
 * Measured on `emulator-5554`, 2026-08-23, `files/osmdroid/tiles`:
 * **72 `.tile` files, 66 distinct SHA-256s**, sizes 103 B … 44 350 B. The
 * repeats were two groups of genuinely uniform tiles — 5 × and 3 × — at 103 B
 * each (open ocean / empty desert; z15 x=12100 y=11875 is the North Atlantic).
 * Both groups are below [TileCacheSanitizer.DUPLICATE_THRESHOLD] = 8 and must
 * survive, which is precisely the boundary `a healthy cache is left alone`
 * pins here on disk rather than in the abstract.
 */
class TileCacheSweepFilesystemTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    /** Write `content` to `Mapnik/{z}/{x}/{y}.png.tile` under [root]. */
    private fun tile(root: File, z: Int, x: Int, y: Int, content: ByteArray): File {
        val f = File(root, "Mapnik/$z/$x/$y.png.tile")
        f.parentFile?.mkdirs()
        f.writeBytes(content)
        return f
    }

    private fun placeholderBytes(): ByteArray =
        // Stands in for OSM's "Access blocked" card: identical bytes at every index.
        ByteArray(6987) { (it % 251).toByte() }

    private fun realTileBytes(seed: Int): ByteArray =
        // A real tile is a picture of a specific place — distinct bytes per index.
        ByteArray(30_000 + seed) { ((it * 31 + seed * 7) % 251).toByte() }

    // ───────────────────────────────────────────────────────────────────
    // The guard firing
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `sweep deletes the placeholder and keeps every real tile`() {
        val root = tmp.newFolder("tiles")

        // 12 copies of one placeholder, at 12 distinct {z}/{x}/{y} indices.
        val poisoned = (0 until 12).map { i ->
            tile(root, 12, 2073 + i, 1407, placeholderBytes())
        }
        // 5 real tiles, all distinct.
        val healthy = (0 until 5).map { i ->
            tile(root, 15, 16594 + i, 11270, realTileBytes(i))
        }

        poisoned.forEach { assertTrue("fixture not written: $it", it.exists()) }
        healthy.forEach { assertTrue("fixture not written: $it", it.exists()) }

        val deleted = TileCacheSanitizer.sweep(root)

        assertEquals("sweep must delete exactly the 12 placeholders", 12, deleted)
        poisoned.forEach { assertFalse("placeholder survived: $it", it.exists()) }
        healthy.forEachIndexed { i, f ->
            assertTrue("real tile was destroyed: $f", f.exists())
            assertTrue("real tile was corrupted: $f", f.readBytes().contentEquals(realTileBytes(i)))
        }
    }

    /**
     * The rule is about CONTENT, not about the path. Two placeholders stored
     * under unrelated zoom levels and folder names are still one placeholder.
     */
    @Test
    fun `sweep matches on bytes, not on filename or directory`() {
        val root = tmp.newFolder("tiles")
        val bytes = placeholderBytes()
        val spread = listOf(
            File(root, "Mapnik/3/1/1.png.tile"),
            File(root, "Mapnik/9/271/186.png.tile"),
            File(root, "Mapnik/12/2073/1407.png.tile"),
            File(root, "Mapnik/14/8299/5635.png.tile"),
            File(root, "Mapnik/15/16598/11273.png.tile"),
            File(root, "Mapnik/16/33196/22546.png.tile"),
            File(root, "Mapnik/17/66392/45092.png.tile"),
            File(root, "Mapnik/18/132784/90184.png.tile"),
        )
        spread.forEach { it.parentFile?.mkdirs(); it.writeBytes(bytes) }

        assertEquals(8, TileCacheSanitizer.sweep(root))
        spread.forEach { assertFalse("survived: $it", it.exists()) }
    }

    // ───────────────────────────────────────────────────────────────────
    // The guard NOT firing (the property that protects a paid download)
    // ───────────────────────────────────────────────────────────────────

    /**
     * Reproduces the measured `emulator-5554` cache: 72 files, 66 distinct, with
     * a 5× and a 3× uniform-tile group. Nothing may be deleted — the same tree
     * holds a user's downloaded offline regions, which can be multiple GB.
     */
    @Test
    fun `a healthy cache matching the measured device census is left alone`() {
        val root = tmp.newFolder("tiles")
        val all = mutableListOf<File>()

        // 64 distinct real tiles.
        repeat(64) { i -> all += tile(root, 15, 5000 + i, 12632, realTileBytes(i)) }
        // One uniform ocean tile at 5 indices (103 B, as measured).
        val ocean = ByteArray(103) { 0x11 }
        repeat(5) { i -> all += tile(root, 15, 5888 + i, 12632, ocean) }
        // A second uniform tile at 3 indices.
        val desert = ByteArray(103) { 0x22 }
        repeat(3) { i -> all += tile(root, 15, 12100 + i, 11875, desert) }

        assertEquals(72, all.size)

        val deleted = TileCacheSanitizer.sweep(root)

        assertEquals("a healthy cache must lose nothing", 0, deleted)
        all.forEach { assertTrue("deleted from a healthy cache: $it", it.exists()) }
    }

    /**
     * The threshold is a `>=`, and the boundary is where a bug would hide: 7
     * copies survive, 8 do not. Both directions are asserted so that moving
     * [TileCacheSanitizer.DUPLICATE_THRESHOLD] cannot pass unnoticed.
     */
    @Test
    fun `the duplicate threshold boundary is exact on disk`() {
        val below = tmp.newFolder("below")
        val bytes = placeholderBytes()
        repeat(TileCacheSanitizer.DUPLICATE_THRESHOLD - 1) { i ->
            tile(below, 12, 100 + i, 200, bytes)
        }
        assertEquals(
            "one below the threshold must survive",
            0,
            TileCacheSanitizer.sweep(below)
        )

        val at = tmp.newFolder("at")
        repeat(TileCacheSanitizer.DUPLICATE_THRESHOLD) { i ->
            tile(at, 12, 100 + i, 200, bytes)
        }
        assertEquals(
            "exactly at the threshold must be swept",
            TileCacheSanitizer.DUPLICATE_THRESHOLD,
            TileCacheSanitizer.sweep(at)
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // Degenerate inputs — the sweep must never throw
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `an empty, missing or file-shaped cache root sweeps to zero`() {
        assertEquals(0, TileCacheSanitizer.sweep(tmp.newFolder("empty")))
        assertEquals(0, TileCacheSanitizer.sweep(File(tmp.root, "does-not-exist")))
        // A regular file where a directory was expected.
        val notADir = tmp.newFile("regular.txt").apply { writeText("x") }
        assertEquals(0, TileCacheSanitizer.sweep(notADir))
    }

    /**
     * Zero-length files are skipped before hashing (`length() > 0`), so a cache
     * full of empty stubs — which all share the same digest — is NOT mistaken
     * for a placeholder and deleted. Deleting them would be harmless, but the
     * count it reports is the number in the log line an operator reads.
     */
    @Test
    fun `zero-length files are not counted as duplicates`() {
        val root = tmp.newFolder("tiles")
        repeat(20) { i ->
            val f = File(root, "Mapnik/12/$i/1.png.tile")
            f.parentFile?.mkdirs()
            f.createNewFile()
        }
        assertEquals(0, TileCacheSanitizer.sweep(root))
    }
}
