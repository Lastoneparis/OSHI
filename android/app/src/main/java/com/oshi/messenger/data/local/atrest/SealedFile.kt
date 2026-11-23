package com.oshi.messenger.data.local.atrest

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * `OSHISEA1` — whole-file AES-256-GCM for the small JSON stores (scheduled messages,
 * pending V2 envelopes, push retry queue, V2 send-resume ledger).
 *
 * ```
 * file = "OSHISEA1" (8) | nonce (12, random) | AES-GCM(key, nonce, aad, plaintext) (+16 tag)
 * aad  = "OSHISEA1" | label (UTF-8)
 * ```
 *
 * The [label] names the store, so a file copied from one store into another's path
 * fails authentication instead of being parsed as the wrong kind of document.
 *
 * Readers accept LEGACY plaintext (any file not starting with the magic); writers
 * always seal when a key is available. Writers refuse to replace a sealed file with
 * plaintext when the key is unavailable, so a Keystore hiccup can never turn into
 * "loaded nothing, saved nothing over the real data".
 */
object SealedFile {

    val MAGIC: ByteArray = "OSHISEA1".toByteArray(Charsets.US_ASCII)
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun isSealed(data: ByteArray): Boolean {
        if (data.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return true
    }

    fun seal(key: SecretKey, label: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(label))
        val ct = cipher.doFinal(plaintext)
        return MAGIC + nonce + ct
    }

    /** @throws IOException when the data is not sealed or fails authentication. */
    fun open(key: SecretKey, label: String, data: ByteArray): ByteArray {
        if (!isSealed(data) || data.size < MAGIC.size + NONCE_SIZE + TAG_BITS / 8) {
            throw IOException("not an OSHISEA1 file")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_BITS, data, MAGIC.size, NONCE_SIZE),
            )
            cipher.updateAAD(aad(label))
            cipher.doFinal(data, MAGIC.size + NONCE_SIZE, data.size - MAGIC.size - NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            throw IOException("OSHISEA1: authentication failed ($label)", e)
        }
    }

    /**
     * Plaintext of [file], or null when it does not exist.
     * Legacy (unsealed) files are returned as-is.
     * @throws IOException when the file is sealed and cannot be opened (no key,
     *   wrong key, tampered). Callers decide whether that is fatal.
     */
    fun readBytes(file: File, label: String, keys: AtRestKeyProvider): ByteArray? {
        if (!file.isFile) return null
        val raw = file.readBytes()
        if (!isSealed(raw)) return raw
        val key = AtRestSubKeys.sealed(keys) ?: throw IOException("OSHISEA1: at-rest key unavailable")
        return open(key, label, raw)
    }

    fun readText(file: File, label: String, keys: AtRestKeyProvider): String? =
        readBytes(file, label, keys)?.toString(Charsets.UTF_8)

    /** True when [file] exists and is still legacy plaintext. */
    fun isLegacyPlaintext(file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() < MAGIC.size) return true
        val head = ByteArray(MAGIC.size)
        file.inputStream().use { ChunkedMediaCipher.readFully(it, head, 0, head.size) }
        return !isSealed(head)
    }

    /**
     * Atomically replace [file] with [plaintext], sealed when a key is available.
     * temp sibling → fsync → atomic rename, under the process-wide lock for the path.
     *
     * @throws IOException on failure, or when no key is available and the existing
     *   file is sealed (we refuse to downgrade / clobber data we cannot read).
     */
    fun writeAtomic(file: File, label: String, keys: AtRestKeyProvider, plaintext: ByteArray) {
        val key = AtRestSubKeys.sealed(keys)
        synchronized(AtRestFileLocks.forFile(file)) {
            if (key == null && file.isFile && !isLegacyPlaintext(file)) {
                throw IOException("OSHISEA1: key unavailable, refusing to overwrite sealed ${file.name}")
            }
            val bytes = if (key != null) seal(key, label, plaintext) else plaintext
            replaceAtomically(file, bytes)
        }
    }

    /**
     * One-shot migration of a legacy plaintext [file] to `OSHISEA1`:
     * seal → write temp → re-open and compare with the source → atomic replace, and
     * only if the file is still the one that was read (same inode/size/mtime).
     * Never deletes or truncates the source on any failure.
     *
     * @return true when the file was migrated; false when there was nothing to do
     *   (absent, already sealed, no key) or the migration was abandoned.
     */
    fun migrate(file: File, label: String, keys: AtRestKeyProvider): Boolean {
        val key = AtRestSubKeys.sealed(keys) ?: return false
        synchronized(AtRestFileLocks.forFile(file)) {
            if (!file.isFile || !isLegacyPlaintext(file)) return false
            val before = fingerprint(file) ?: return false
            val plain = file.readBytes()
            val sealed = seal(key, label, plain)
            // Verify what we are about to install actually opens back to the source.
            if (!MessageDigest.isEqual(open(key, label, sealed), plain)) return false
            if (fingerprint(file) != before) return false
            return try {
                replaceAtomically(file, sealed, verify = { tmp ->
                    MessageDigest.isEqual(open(key, label, tmp.readBytes()), plain)
                })
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    // ---------------------------------------------------------------------

    private fun aad(label: String): ByteArray = MAGIC + label.toByteArray(Charsets.UTF_8)

    internal data class Fingerprint(val fileKey: Any?, val size: Long, val mtime: Long)

    internal fun fingerprint(file: File): Fingerprint? = try {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        Fingerprint(a.fileKey(), a.size(), a.lastModifiedTime().toMillis())
    } catch (_: Exception) {
        null
    }

    /**
     * Write [bytes] to a unique temp sibling, fsync, optionally [verify] the temp,
     * then rename over [file]. The temp is removed on every failure path; [file]
     * is untouched unless the rename succeeds.
     */
    internal fun replaceAtomically(file: File, bytes: ByteArray, verify: ((File) -> Boolean)? = null) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent for ${file.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        AtRestFileLocks.markInFlight(tmp)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (verify != null && !verify(tmp)) throw IOException("verification failed for ${file.name}")
            moveReplacing(tmp, file)
        } finally {
            AtRestFileLocks.clearInFlight(tmp)
            tmp.delete()
        }
    }

    /** Atomic rename when the filesystem allows it, plain replace otherwise. */
    internal fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
