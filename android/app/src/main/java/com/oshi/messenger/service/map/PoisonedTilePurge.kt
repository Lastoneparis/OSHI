package com.oshi.messenger.service.map

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * PoisonedTilePurge — removes one exact, known-bad picture saved as map tiles.
 *
 * From 2026-11-13 the OSHI tile proxy (`https://oshi-messenger.com/tiles/{z}/{x}/{y}.png`)
 * passed through OpenStreetMap's "Access blocked" picture with **HTTP 200**. Every
 * writer in the app (region download, interactive map, chat map previews) checked
 * `isSuccessful`, saw success and stored it as a real tile. The server is fixed, but
 * the saved copies keep showing the blocked card forever, and a region re-download
 * skipped them because "the file already exists".
 *
 * That picture is exactly [POISON_SIZE] bytes with sha256 [POISON_SHA256]. Unlike
 * [TileCacheSanitizer] (duplicate rule, already spent on an earlier generation), this
 * matches that one image only: size first (a `length()` call, free), then the hash,
 * so a real tile that happens to be 6725 bytes is kept.
 *
 * Places swept:
 *  - osmdroid's tile cache folder (`Mapnik/{z}/{x}/{y}.png.tile` and the other layer folders),
 *    written by `OfflineMapManager.downloadRegion`, `OshiOfflineTileProvider` and
 *    `OshiStaticMapRenderer`;
 *  - `files/Maps/regions/<id>/tiles/…` (per-region tile folders);
 *  - osmdroid's own `SqlTileWriter` database (`cache.db`), which the stock
 *    `MapTileProviderBasic` fills before our provider replaces it.
 *
 * Runs once per app version code (see [runOncePerVersion]).
 */
object PoisonedTilePurge {

    private const val TAG = "PoisonedTilePurge"

    /** Exact byte size of the OSM "Access blocked" picture served from 13/11/2026. */
    const val POISON_SIZE: Long = 6725L

    /** sha256 of that picture, lowercase hex. */
    const val POISON_SHA256: String =
        "109722facb0459991ce2c9983b3ae40232e84be7273aaa454071b50049e3d88b"

    /** Version code that last completed the purge, in [OshiOfflineTileProvider.PREF_FILE]. */
    const val PREF_KEY_VERSION = "oshi.map.poisonedTilePurgeVersion"

    /** True only for the exact poisoned picture. */
    @JvmStatic
    fun isPoisoned(bytes: ByteArray): Boolean =
        bytes.size.toLong() == POISON_SIZE && sha256Hex(bytes) == POISON_SHA256

    /** True only for a file holding the exact poisoned picture. Size is checked first. */
    @JvmStatic
    fun isPoisonedFile(file: File): Boolean {
        if (!file.isFile || file.length() != POISON_SIZE) return false
        return runCatching { isPoisoned(file.readBytes()) }.getOrDefault(false)
    }

    /**
     * Delete every poisoned file under [roots] (recursively). Never throws.
     * @return number of files deleted.
     */
    @JvmStatic
    fun purgeFiles(roots: Collection<File>): Int {
        var deleted = 0
        roots.distinctBy { it.absolutePath }.forEach { root ->
            if (!root.isDirectory) return@forEach
            runCatching {
                root.walkTopDown()
                    .filter { it.isFile && it.length() == POISON_SIZE }
                    .forEach { f ->
                        if (isPoisonedFile(f) && runCatching { f.delete() }.getOrDefault(false)) deleted++
                    }
            }.onFailure { Log.w(TAG, "Walk failed under ${root.path}: ${it.message}") }
        }
        return deleted
    }

    /**
     * Delete poisoned rows from osmdroid's `SqlTileWriter` database
     * (`tiles(key, provider, tile BLOB, expires)`). Never throws.
     */
    @JvmStatic
    fun purgeSqlCache(dbFile: File): Int {
        if (!dbFile.isFile) return 0
        return runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            db.use {
                val bad = mutableListOf<Pair<Long, String>>()
                it.rawQuery(
                    "SELECT key, provider, tile FROM tiles WHERE length(tile) = ?",
                    arrayOf(POISON_SIZE.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val blob = c.getBlob(2) ?: continue
                        if (isPoisoned(blob)) bad += c.getLong(0) to c.getString(1)
                    }
                }
                bad.sumOf { (key, provider) ->
                    it.delete("tiles", "key = ? AND provider = ?", arrayOf(key.toString(), provider))
                }
            }
        }.onFailure { Log.w(TAG, "SQL tile cache purge failed: ${it.message}") }.getOrDefault(0)
    }

    /**
     * Every place Android stores map tiles. Kept here so the purge and any future
     * writer agree on one list.
     */
    @JvmStatic
    fun tileRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        runCatching {
            val cfg = org.osmdroid.config.Configuration.getInstance()
            cfg.osmdroidTileCache?.let { roots += it }
            cfg.osmdroidBasePath?.let { roots += File(it, "tiles") }
        }
        roots += File(File(context.filesDir, "osmdroid"), "tiles")
        roots += File(context.filesDir, "Maps/regions")
        context.externalCacheDir?.let { roots += File(File(it, "osmdroid"), "tiles") }
        return roots.distinctBy { it.absolutePath }
    }

    /**
     * Purge once per [versionCode]. Blocking IO — call off the main thread. Never throws.
     * @return files + rows deleted, or -1 when already done for this version.
     */
    @JvmStatic
    fun runOncePerVersion(context: Context, versionCode: Int): Int {
        val prefs = context.getSharedPreferences(OshiOfflineTileProvider.PREF_FILE, Context.MODE_PRIVATE)
        if (prefs.getInt(PREF_KEY_VERSION, 0) == versionCode) return -1
        return try {
            val roots = tileRoots(context)
            var deleted = purgeFiles(roots)
            roots.forEach { deleted += purgeSqlCache(File(it, "cache.db")) }
            prefs.edit().putInt(PREF_KEY_VERSION, versionCode).apply()
            Log.i(TAG, "Removed $deleted poisoned tile(s) (\"Access blocked\" picture)")
            deleted
        } catch (e: Exception) {
            // Flag not written: retry on next launch.
            Log.w(TAG, "Poisoned tile purge failed: ${e.message}")
            0
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
