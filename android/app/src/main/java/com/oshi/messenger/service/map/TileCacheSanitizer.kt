package com.oshi.messenger.service.map

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * TileCacheSanitizer — evicts placeholder images that were cached as if they were
 * map tiles.
 *
 * ## Why this is needed even though the tile source is fixed
 *
 * Pointing the app at its own CDN ([OshiTileSource]) stops NEW poison arriving,
 * but it does nothing for the cache every existing user already has. Verified on
 * `emulator-5554` immediately after installing the fixed build: the Map tab was
 * still a wall of OSM's yellow/black "Access blocked — App is not following the
 * tile usage policy" cards, served entirely from disk. The cache held **290**
 * `.tile` files, **259 of them byte-identical** (sha256
 * `b02c4425…`, 6 987 bytes each) — one placeholder image stored 259 times under
 * 259 different `{z}/{x}/{y}` paths.
 *
 * The reason it was cached at all: OSM returns the blocked placeholder with
 * **HTTP 200**, not 403. `NetworkTileModule` checked `response.isSuccessful`,
 * saw success, and wrote the image to disk like any other tile. Without this
 * sweep the fix would appear to have changed nothing for anyone who ever opened
 * the old Map tab.
 *
 * ## The rule
 *
 * A rendered map tile is a picture of a specific place, so two tiles at two
 * different `{z}/{x}/{y}` indices are essentially never byte-identical. A
 * *placeholder* is the same bytes at every index — that is what makes it a
 * placeholder. So: **evict any content that appears at [DUPLICATE_THRESHOLD] or
 * more distinct tile indices.**
 *
 * This is deliberately hash-agnostic rather than an allowlist of known-bad
 * checksums: it catches whatever placeholder any server ever returns, including
 * variants we have never seen, without needing an update.
 *
 * The one false positive it can produce is a genuinely uniform tile — open ocean,
 * blank desert — which really is identical at many indices. Deleting one of those
 * is harmless: it is re-fetched from the first-party proxy on next view, and it
 * carries no information to lose. The rule can never delete a tile that has
 * distinguishing content, which is the property that matters.
 *
 * ## Scope
 *
 * Only osmdroid's shared tile cache is swept. It is shared by two writers —
 * `OfflineMapManager.downloadRegion` (offline region downloads) and the
 * interactive map's network module — so a blanket wipe would destroy a user's
 * downloaded regions. The duplicate rule leaves every real downloaded tile
 * untouched.
 *
 * Runs once per [CURRENT_GENERATION], recorded in the map preferences.
 */
object TileCacheSanitizer {

    private const val TAG = "TileCacheSanitizer"

    /** Preference recording the last sweep generation that ran. */
    const val PREF_KEY_GENERATION = "oshi.map.tileCacheSweepGeneration"

    /**
     * Bump to force the sweep to run again on upgrade. 1 = the 2026-08-13 sweep
     * that removes the OSM "Access blocked" placeholders.
     */
    const val CURRENT_GENERATION = 1

    /**
     * How many distinct tile indices must share identical content before it is
     * treated as a placeholder rather than a map.
     *
     * Eight is comfortably above what an accidental collision produces and far
     * below the hundreds a real placeholder produces (259 on the test device).
     */
    const val DUPLICATE_THRESHOLD = 8

    /**
     * Pure core, kept separate from the filesystem so it is unit-testable.
     *
     * @param contentHashByPath tile path → content hash.
     * @return the paths whose content should be evicted.
     */
    @JvmStatic
    fun poisonedPaths(
        contentHashByPath: Map<String, String>,
        threshold: Int = DUPLICATE_THRESHOLD
    ): Set<String> {
        if (threshold <= 1) return emptySet()
        val countByHash = contentHashByPath.values.groupingBy { it }.eachCount()
        return contentHashByPath
            .filterValues { (countByHash[it] ?: 0) >= threshold }
            .keys
    }

    /**
     * Run the sweep at most once per [CURRENT_GENERATION].
     *
     * Blocking IO — call from a background thread. Never throws: a failed sweep
     * must not take the app down, and the worst case is that the user keeps a
     * stale cache, which is exactly today's behaviour.
     *
     * @return the number of files deleted, or -1 if the sweep was skipped.
     */
    @JvmStatic
    fun sweepOnce(context: Context, vararg cacheRoots: File): Int {
        val prefs = context.getSharedPreferences(
            OshiOfflineTileProvider.PREF_FILE,
            Context.MODE_PRIVATE
        )
        if (prefs.getInt(PREF_KEY_GENERATION, 0) >= CURRENT_GENERATION) return -1

        return try {
            // Every root is swept before the generation is recorded — recording it
            // per-root would mark the job done after the first one and skip the rest.
            val deleted = cacheRoots
                .distinctBy { it.absolutePath }
                .sumOf { sweep(it) }
            prefs.edit().putInt(PREF_KEY_GENERATION, CURRENT_GENERATION).apply()
            Log.i(
                TAG,
                "🧹 Tile cache sweep: removed $deleted placeholder tile(s) from " +
                    cacheRoots.joinToString { it.absolutePath }
            )
            deleted
        } catch (e: Exception) {
            Log.w(TAG, "Tile cache sweep failed: ${e.message}")
            // Not marking the generation done — retry on next launch.
            0
        }
    }

    /** Sweep [cacheRoot] unconditionally. Returns the number of files deleted. */
    @JvmStatic
    fun sweep(cacheRoot: File): Int {
        if (!cacheRoot.isDirectory) return 0

        val hashByPath = HashMap<String, String>()
        cacheRoot.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .forEach { file ->
                runCatching { hashByPath[file.absolutePath] = sha256(file) }
            }
        if (hashByPath.isEmpty()) return 0

        var deleted = 0
        poisonedPaths(hashByPath).forEach { path ->
            if (runCatching { File(path).delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
