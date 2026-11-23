package com.oshi.messenger.data.local.atrest

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Source of the 256-bit master key that seals app-private files at rest.
 *
 * Returns null when no key is available (not installed yet, JVM unit test, or the
 * Android Keystore refused). The small JSON stores treat null as "write plaintext,
 * read what you can" — the same FAIL-SAFE policy as the SQLCipher passphrase
 * ([com.oshi.messenger.data.local.DatabasePassphrase]). Attachments and link previews
 * do NOT (2026-09-24): `MediaAttachmentStore` holds the bytes in memory until the key
 * is back, `LinkPreviewCache` stays memory-only — neither ever writes plaintext.
 *
 * A `fun interface` so JVM tests can hand a fixed key in without Android.
 */
fun interface AtRestKeyProvider {
    /** The raw master key. Callers must NOT mutate the returned array. */
    fun masterKey(): ByteArray?

    companion object {
        /** Never provides a key — plaintext passthrough (legacy behaviour). */
        val NONE = AtRestKeyProvider { null }
    }
}

/**
 * Domain separation: the master key is never used directly as an AES key. Each
 * purpose gets its own sub-key, `HMAC-SHA256(master, "OSHI-at-rest-v1|<purpose>")`
 * (HKDF-Expand with a single block, which is exactly this). A media file can
 * therefore never be opened as a JSON store or vice versa even before the AAD /
 * magic checks run.
 */
object AtRestSubKeys {
    const val PURPOSE_MEDIA = "media"
    const val PURPOSE_SEALED = "sealed-file"

    fun derive(master: ByteArray, purpose: String): SecretKey {
        require(master.size == 32) { "at-rest master key must be 256 bits" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        val info = "OSHI-at-rest-v1|$purpose".toByteArray(Charsets.UTF_8)
        mac.update(info)
        mac.update(0x01)
        return SecretKeySpec(mac.doFinal(), "AES")
    }

    fun media(provider: AtRestKeyProvider): SecretKey? =
        provider.masterKey()?.let { derive(it, PURPOSE_MEDIA) }

    fun sealed(provider: AtRestKeyProvider): SecretKey? =
        provider.masterKey()?.let { derive(it, PURPOSE_SEALED) }
}

/**
 * Process-wide lock per file path. Several objects write the same files (three
 * `MediaAttachmentStore` instances share `files/media`; the startup migration runs
 * beside the real stores), so an instance lock is not enough: the migration's
 * final "is the file still the one I read? then replace it" step must be atomic
 * with respect to every other writer of that path.
 */
internal object AtRestFileLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forFile(file: File): Any =
        locks.getOrPut(file.absoluteFile.normalize().path) { Any() }

    /**
     * Temp files currently being written by this process. `sweepTempFiles()` skips
     * them, so a sweep running beside a write (startup purge + migration, or a purge
     * during a download) can no longer delete the staging file out from under it.
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun markInFlight(file: File) { inFlight.add(file.absoluteFile.normalize().path) }
    fun clearInFlight(file: File) { inFlight.remove(file.absoluteFile.normalize().path) }
    fun isInFlight(file: File): Boolean = file.absoluteFile.normalize().path in inFlight
}
