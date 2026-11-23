package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.model.DownloadedRegionMetadata
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MapStorageManager — file operations for offline map tile and POI storage.
 *
 * Directory layout:
 *   files/Maps/regions/{regionId}/tiles/   — .pbf or .png tile files
 *   files/Maps/regions/{regionId}/pois.db  — SQLite POI database
 *   files/Maps/regions/{regionId}/meta.json — DownloadedRegionMetadata
 */
@Singleton
class MapStorageManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "MapStorageManager"
        private const val MAPS_DIR = "Maps"
        private const val REGIONS_DIR = "regions"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Root maps directory */
    val mapsRoot: File
        get() = File(context.filesDir, MAPS_DIR).also { it.mkdirs() }

    /** Region directory */
    fun regionDir(regionId: String): File =
        File(mapsRoot, "$REGIONS_DIR/$regionId").also { it.mkdirs() }

    /** Tiles directory for a region */
    fun tilesDir(regionId: String): File =
        File(regionDir(regionId), "tiles").also { it.mkdirs() }

    /** POI database file for a region */
    fun poisDbFile(regionId: String): File =
        File(regionDir(regionId), "pois.db")

    /** Metadata file for a region */
    private fun metaFile(regionId: String): File =
        File(regionDir(regionId), "meta.json")

    /** Check if a region is downloaded (has metadata) */
    fun isRegionDownloaded(regionId: String): Boolean =
        metaFile(regionId).exists()

    /** Save region metadata after successful download */
    fun saveMetadata(metadata: DownloadedRegionMetadata) {
        try {
            val file = metaFile(metadata.regionId)
            val jsonStr = json.encodeToString(
                DownloadedRegionMetadata.serializer(),
                metadata
            )
            file.writeText(jsonStr)
            Log.d(TAG, "Saved metadata for ${metadata.regionId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    /** Load region metadata */
    fun loadMetadata(regionId: String): DownloadedRegionMetadata? {
        val file = metaFile(regionId)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(
                DownloadedRegionMetadata.serializer(),
                file.readText()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata for $regionId: ${e.message}")
            null
        }
    }

    /** Delete a downloaded region and all its files */
    fun deleteRegion(regionId: String): Boolean {
        return try {
            val dir = regionDir(regionId)
            if (dir.exists()) {
                dir.deleteRecursively()
                Log.d(TAG, "Deleted region $regionId")
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete region $regionId: ${e.message}")
            false
        }
    }

    /** Get total storage used by all downloaded maps */
    fun totalStorageUsed(): Long {
        val regionsRoot = File(mapsRoot, REGIONS_DIR)
        if (!regionsRoot.exists()) return 0L
        return regionsRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Get storage used by a specific region */
    fun regionStorageUsed(regionId: String): Long {
        val dir = regionDir(regionId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** List all downloaded region IDs */
    fun listDownloadedRegionIds(): List<String> {
        val regionsRoot = File(mapsRoot, REGIONS_DIR)
        if (!regionsRoot.exists()) return emptyList()
        return regionsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "meta.json").exists() }
            ?.map { it.name }
            ?: emptyList()
    }

    /** SHA-256 hash of a file for verification */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify a downloaded file against an expected SHA-256 hash.
     *
     * Fails CLOSED. The previous implementation returned `true` when the expected
     * hash was blank, so a manifest entry with no digest — or a truncated download
     * whose digest never arrived — reported "verified". Use
     * [com.oshi.messenger.service.map.MapArtifactVerifier] for the full check
     * (size + all-zero digest handling); this remains for callers that only have
     * a hash to compare.
     */
    fun verifyFile(file: File, expectedHash: String): Boolean {
        if (expectedHash.isBlank()) {
            Log.w(TAG, "No SHA-256 provided for ${file.name} — treating as UNVERIFIED")
            return false
        }
        val actual = sha256(file)
        val matches = actual.equals(expectedHash, ignoreCase = true)
        if (!matches) {
            Log.w(TAG, "SHA-256 mismatch for ${file.name}: expected=$expectedHash actual=$actual")
        }
        return matches
    }

    /** Format bytes to human-readable string */
    fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format("%.1f GB", mb / 1024.0)
        } else {
            String.format("%.1f MB", mb)
        }
    }
}
