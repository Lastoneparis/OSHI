package com.oshi.messenger.service.map

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * MapArtifactVerifier — the download integrity gate for every offline-map artifact.
 *
 * WHY THIS EXISTS
 * ---------------
 * Before 2026-08-13 the only verification helper in the codebase was
 * `MapStorageManager.verifyFile`, which
 *   (a) `return true` when `expectedHash.isBlank()`, and
 *   (b) had exactly ONE reference in the whole tree — its own definition.
 * Nothing was ever verified, and a truncated download reported "ready".
 *
 * The rules here are deliberately fail-CLOSED and mirror iOS
 * (`OfflineRoadGraphDownloader.swift:406-417`, which rejects an empty digest and
 * an all-zeros digest before comparing):
 *
 *   • A blank or absent hash is **NOT verified**. It is not a pass.
 *   • An all-zeros digest is the manifest's placeholder (the `tiles` entry ships
 *     64 zeros) — also **NOT verified**.
 *   • A declared size that the file on disk does not match is a truncation and
 *     fails before the (expensive) digest is even computed.
 *
 * Kept free of `android.*` so it is exercised by plain JVM unit tests.
 */
object MapArtifactVerifier {

    /** Outcome of gating one downloaded artifact. Only [VERIFIED] may be kept. */
    enum class Result {
        /** Digest present, non-placeholder, and matching. Size matched too. */
        VERIFIED,

        /** The manifest carried no usable digest — we cannot claim integrity. */
        NO_HASH,

        /** File length ≠ the size the manifest declared (truncated / padded). */
        SIZE_MISMATCH,

        /** Digest present and did NOT match. */
        HASH_MISMATCH,

        /** File missing or empty. */
        MISSING;

        val isVerified: Boolean get() = this == VERIFIED
    }

    /**
     * A digest we are allowed to gate on. `null` for blank, and for the all-zeros
     * placeholder the server writes for artifacts it never built.
     */
    fun usableHash(expected: String?): String? =
        expected?.trim()?.takeIf { it.isNotEmpty() && it.any { c -> c != '0' } }

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String = sha256(file.inputStream().buffered())

    /**
     * Gate one artifact.
     *
     * @param expectedSize declared byte count, or 0/`null` when the manifest has
     *   none (a `shared: true` entry reports `size: 0` and carries the real count
     *   in `shared_size` — pass `MapFile.realSize`).
     */
    fun verify(file: File, expectedSha256: String?, expectedSize: Long = 0L): Result {
        if (!file.exists() || file.length() <= 0L) return Result.MISSING
        if (expectedSize > 0L && file.length() != expectedSize) return Result.SIZE_MISMATCH
        val want = usableHash(expectedSha256) ?: return Result.NO_HASH
        val got = sha256(file)
        return if (got.equals(want, ignoreCase = true)) Result.VERIFIED else Result.HASH_MISMATCH
    }

    /**
     * Verify and, on anything other than [Result.VERIFIED], delete the file so a
     * half-written artifact can never be mistaken for a downloaded one by the
     * `exists() && length() > 0` checks scattered through the map stack.
     *
     * [Result.NO_HASH] deletes too when [strict] — the caller decides whether an
     * unverifiable artifact is acceptable. The roadgraph country-level fallback
     * is the one place iOS knowingly proceeds without a digest
     * (`OfflineRoadGraphDownloader.swift:176-182`), because the country file's
     * SHA is not in the sub-region's manifest entry.
     */
    fun verifyOrDelete(
        file: File,
        expectedSha256: String?,
        expectedSize: Long = 0L,
        strict: Boolean = true,
    ): Result {
        val result = verify(file, expectedSha256, expectedSize)
        val keep = result.isVerified || (result == Result.NO_HASH && !strict)
        if (!keep) file.delete()
        return result
    }
}
