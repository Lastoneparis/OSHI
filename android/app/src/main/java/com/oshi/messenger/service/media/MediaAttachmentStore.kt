package com.oshi.messenger.service.media

import com.oshi.messenger.data.local.atrest.AtRestFileLocks
import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.AtRestSubKeys
import com.oshi.messenger.data.local.atrest.ChunkedMediaCipher
import com.oshi.messenger.data.local.atrest.PlaintextScratch
import com.oshi.messenger.data.local.atrest.SealedFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.crypto.SecretKey

/**
 * MediaAttachmentStore — file-backed blob storage for message attachments.
 *
 * ## Why this class exists
 *
 * `MessageRepository.sanitizeMediaForInsert` refuses to write a `mediaData` blob
 * larger than ~1.5 MB into the Room row, because Android's CursorWindow (2 MB hard
 * limit) throws `SQLiteBlobTooBigException` on any later `SELECT *` that touches the
 * row. Before this class existed the repository simply set `mediaData = null` and
 * logged a warning, deferring to a "MediaAttachmentManager (file-based)" that was
 * never written — so every video and every photo above the cap rendered as an empty
 * bubble on BOTH the sender's and the recipient's device.
 *
 * ## iOS reference lifecycle (this is a port, not an invention)
 *
 *  - `OSHI/MediaManager.swift:762-771` — `getAttachmentURL(for:)` puts every
 *    attachment in `Documents/Attachments/<id>.dat`, creating the directory on
 *    demand. `saveAttachment` (`:737-750`) writes it; `loadAttachment` (`:751-757`)
 *    reads it back by id; `deleteAttachment` (`:758-761`) removes it.
 *  - `OSHI/FileStorage.swift:330-341` — `saveMedia(data:filename:)` writes
 *    ATOMICALLY (`options: [.atomic, …]`); `:344-352` `loadMedia` returns nil when
 *    the file is absent rather than throwing; `:354-357` `deleteMedia` also drops
 *    the derived thumbnail.
 *  - `OSHI/MediaAttachmentCache.swift:37-38` — the in-memory decrypted-media cache
 *    is an NSCache with `countLimit = 60` and `totalCostLimit = 96 MB`;
 *    `:44-51` purges it on a memory warning; `:67-69` `remove()` evicts a single
 *    entry when a message is deleted or a view-once item is consumed.
 *
 * The same three limits are mirrored here so the hydration budget in
 * `MessageRepository` behaves like iOS's cache rather than loading an unbounded
 * number of multi-megabyte blobs into the heap on every Flow emission.
 *
 * ## Design constraints
 *
 *  - Takes a plain [File] root (not a `Context`) so the whole class is exercisable
 *    from a JVM unit test with a temp directory — this module has no Robolectric.
 *  - File names are a REVERSIBLE hex encoding of the message id's UTF-8 bytes, so
 *    [purgeOrphans] can recover the id from a directory listing and drop blobs whose
 *    message row is gone. A raw id cannot be used: inbound ids come from the peer and
 *    may contain path separators.
 *
 * ## Encryption at rest (2026-09-22)
 *
 * Blobs are written in the chunked `OSHIMED1` AES-256-GCM format
 * ([ChunkedMediaCipher]) under a Keystore-wrapped key ([AtRestKey]); the plaintext
 * never touches `files/`. The API is unchanged for callers: [save]/[load] still speak
 * plaintext, [sizeOf] still reports the PLAINTEXT size (callers compare it with a
 * byte count), and the new [openStream]/[decryptToTempFile] serve consumers that must
 * not hold a whole video in RAM or that need a path/URI (see [PlaintextScratch]).
 *
 * Readers accept LEGACY plaintext blobs written by earlier versions;
 * [migrateLegacy] converts them in place (encrypt to temp → decrypt-verify against
 * the source digest → atomic replace only if the file is unchanged). No path ever
 * deletes a blob because it failed to decrypt.
 *
 * ## No plaintext fallback (__PLAINTEXT_LEFTOVERS_2026_09_24__)
 *
 * Without a key (broken/locked Keystore) the store used to write PLAINTEXT blobs. It
 * now never does: [save] returns false (not durable) and the bytes are HELD IN MEMORY
 * ([AwaitingKey], process-wide, served by [load]/[openStream]/[has]/[sizeOf]) and
 * sealed to disk by the first store call that finds the key back ([AtRestKey] retries
 * after a back-off instead of giving up for the whole process). Nothing is lost while
 * the process lives; what dies with the process is what would otherwise have been
 * written in clear. Group media is also re-offered by `GroupManager` on every flush
 * (its save is retried while `sizeOf` differs). Legacy plaintext blobs stay READABLE.
 */
class MediaAttachmentStore(
    root: File,
    private val keys: AtRestKeyProvider = AtRestKey,
) {

    companion object {
        /** Sub-directory of `filesDir`. iOS: `Documents/Attachments` (MediaManager.swift:766). */
        const val DIR_NAME = "media"

        /** iOS `MediaAttachmentCache.swift:37` — `cache.countLimit = 60`. */
        const val CACHE_COUNT_LIMIT = 60

        /** iOS `MediaAttachmentCache.swift:38` — `cache.totalCostLimit = 96 * 1024 * 1024`. */
        const val CACHE_TOTAL_COST_LIMIT = 96L * 1024 * 1024

        /** iOS `MediaManager.swift:769` — `"\(id).dat"`. */
        private const val EXT = ".dat"

        /** Bounded transfer buffer for the [save] overload that streams a file. */
        private const val COPY_BUFFER_BYTES = 64 * 1024

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * Blobs accepted while no at-rest key was available, keyed by
         * `<directory>\u0000<messageId>`. Process-wide because several instances share
         * one directory. Either the bytes, or the caller's plaintext scratch FILE it
         * handed over with `moveSource` (a V2 download in `cacheDir/v2blobs`).
         */
        private val AwaitingKey = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** Reversible, filesystem-safe encoding of an arbitrary message id. */
        internal fun encodeName(messageId: String): String {
            val bytes = messageId.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        /** Inverse of [encodeName]; null when the name is not one of ours. */
        internal fun decodeName(fileName: String): String? {
            if (!fileName.endsWith(EXT)) return null
            val hex = fileName.substring(0, fileName.length - EXT.length)
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return String(out, Charsets.UTF_8)
        }
    }

    /** `filesDir/media`, created lazily exactly like iOS's `createDirectory(…, withIntermediateDirectories: true)`. */
    val directory: File = File(root, DIR_NAME)

    /**
     * Bounded in-memory mirror of the on-disk blobs.
     * Access-ordered LinkedHashMap rather than `android.util.LruCache` so the class
     * stays unit-testable on the JVM. Evicts by BOTH count and total cost, matching
     * `MediaAttachmentCache.swift:37-38`.
     */
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {}
    private var cacheCost: Long = 0L
    private val lock = Any()

    private fun fileFor(messageId: String) = File(directory, encodeName(messageId) + EXT)

    private fun awaitingKeyOf(messageId: String) = directory.absolutePath + "\u0000" + messageId

    private fun awaiting(messageId: String): Any? =
        if (AwaitingKey.isEmpty()) null else AwaitingKey[awaitingKeyOf(messageId)]

    /**
     * Seal every blob of THIS directory that was held in memory for lack of a key, now
     * that there is one. Cheap no-op in the normal case (empty map).
     */
    fun flushAwaitingKey(): Int {
        if (AwaitingKey.isEmpty()) return 0
        val key = mediaKey() ?: return 0
        val prefix = directory.absolutePath + "\u0000"
        var flushed = 0
        for ((k, v) in AwaitingKey.entries.toList()) {
            if (!k.startsWith(prefix)) continue
            val id = k.substring(prefix.length)
            val ok = when (v) {
                is ByteArray -> ensureDir() && writeViaTemp(fileFor(id)) { out -> writeContent(key, v.inputStream(), out) }
                is File -> if (!v.isFile) { AwaitingKey.remove(k, v); false } else ensureDir() && writeViaTemp(fileFor(id)) { out ->
                    BufferedInputStream(v.inputStream(), COPY_BUFFER_BYTES).use { writeContent(key, it, out) }
                }.also { if (it) v.delete() }
                else -> false
            }
            if (ok && AwaitingKey.remove(k, v)) flushed++
        }
        return flushed
    }

    private fun ensureDir(): Boolean = directory.isDirectory || directory.mkdirs()

    // =====================================================================
    // WRITE
    // =====================================================================

    /** Per-purpose sub-key, or null (no key ⇒ held in memory, never written in clear). */
    private fun mediaKey(): SecretKey? = try { AtRestSubKeys.media(keys) } catch (_: Throwable) { null }

    /**
     * Persist [bytes] for [messageId].
     *
     * Written to a temp sibling then renamed, which is this platform's equivalent of
     * iOS's `Data.write(to:options:.atomic)` (`FileStorage.swift:334`): a process
     * death mid-write can never leave a truncated blob that would later decode as a
     * corrupt image. Encrypted as `OSHIMED1` when the at-rest key is available.
     *
     * @return true when the bytes are durably on disk.
     */
    fun save(messageId: String, bytes: ByteArray): Boolean {
        if (messageId.isEmpty()) return false
        val key = mediaKey()
        if (key == null) {
            // Never plaintext on disk: hold it until the key is back (see the class note).
            AwaitingKey[awaitingKeyOf(messageId)] = bytes
            putInCache(messageId, bytes)
            return false
        }
        flushAwaitingKey()
        if (!ensureDir()) return false
        val ok = writeViaTemp(fileFor(messageId)) { out -> writeContent(key, bytes.inputStream(), out) }
        if (ok) {
            AwaitingKey.remove(awaitingKeyOf(messageId))
            putInCache(messageId, bytes)
        }
        return ok
    }

    /**
     * Persist a blob that is ALREADY on disk at [source], without ever holding the
     * whole file in memory.
     *
     * This is the entry point the streaming V2 download needs. `V2MessageRouter`
     * writes the decrypted plaintext to a scratch file one 2 MiB chunk at a time
     * (`OSHICryptoV2Streaming.decryptToStream`); handing those bytes to
     * [save]`(String, ByteArray)` would allocate the whole file again and undo the
     * bound — that whole-file copy was the last mile of M-2.
     *
     * With the at-rest key the source is STREAMED through [ChunkedMediaCipher] into a
     * temp sibling (one 64 KiB chunk in memory) and renamed into place; with
     * [moveSource] the plaintext scratch file is deleted afterwards. Without a key the
     * old behaviour is kept: [moveSource] renames the source into the staging slot (a
     * metadata-only move on the shared `cacheDir`/`filesDir` mount), otherwise a
     * streaming copy through a [COPY_BUFFER_BYTES] buffer.
     *
     * The in-memory cache is deliberately NOT populated: a caller reaching for this
     * overload is avoiding the heap, and a stale entry for [messageId] (a superseded
     * reveal layer, say) is EVICTED so the next read cannot serve the old blob.
     *
     * On failure the caller's [source] is never lost.
     *
     * @param moveSource the caller has handed [source] over and it may be consumed.
     * @return true when the bytes are durably on disk.
     */
    fun save(messageId: String, source: File, moveSource: Boolean = false): Boolean {
        if (messageId.isEmpty()) return false
        if (!source.isFile) return false
        val target = fileFor(messageId)
        val key = mediaKey()
        if (key == null) {
            // No plaintext copy into files/. A handed-over scratch file (already plaintext
            // in cacheDir, swept at startup) is held by reference and sealed once the key
            // is back; a borrowed one stays the caller's.
            if (moveSource) {
                // Renamed beside itself: the V2 router deletes the ORIGINAL path in its
                // `finally` once we return. Still in cacheDir (swept at startup), never files/.
                val held = File(source.parentFile, "${source.name}.held")
                if (source.renameTo(held)) {
                    AwaitingKey[awaitingKeyOf(messageId)] = held
                    evict(messageId)
                }
            }
            return false
        }
        flushAwaitingKey()
        if (!ensureDir()) return false
        val ok = writeViaTemp(target) { out ->
            BufferedInputStream(source.inputStream(), COPY_BUFFER_BYTES).use { writeContent(key, it, out) }
        }
        if (ok) {
            if (moveSource) source.delete()
            AwaitingKey.remove(awaitingKeyOf(messageId))
            evict(messageId)
        }
        return ok
    }

    private fun writeContent(key: SecretKey, input: InputStream, out: OutputStream) {
        ChunkedMediaCipher.encrypt(key, input, out)
    }

    /** A unique staging name per write: two concurrent saves of one id no longer share a temp. */
    private fun newTempFor(target: File) = File(directory, "${target.name}.${System.nanoTime()}.tmp")

    /**
     * temp sibling → [block] → flush + fsync → atomic rename over [target] (under the
     * process-wide path lock, so it cannot interleave with a migration's replace).
     */
    private fun writeViaTemp(target: File, block: (OutputStream) -> Unit): Boolean {
        val tmp = newTempFor(target)
        AtRestFileLocks.markInFlight(tmp)
        return try {
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos, COPY_BUFFER_BYTES)
                block(out)
                out.flush()
                fos.fd.sync()
            }
            synchronized(AtRestFileLocks.forFile(target)) { SealedFile.moveReplacing(tmp, target) }
            true
        } catch (e: Throwable) {
            false
        } finally {
            AtRestFileLocks.clearInFlight(tmp)
            tmp.delete()
        }
    }

    // =====================================================================
    // READ
    // =====================================================================

    /**
     * Bytes for [messageId], or null when nothing was ever stored — or when the blob
     * is encrypted and cannot be authenticated (no key, wrong key, tampered). The file
     * is never deleted for failing to decrypt. Mirrors `FileStorage.loadMedia` (`:344-352`).
     */
    fun load(messageId: String): ByteArray? {
        synchronized(lock) { cache[messageId] }?.let { return it }
        when (val held = awaiting(messageId)) {
            is ByteArray -> return held
            is File -> return runCatching { held.readBytes() }.getOrNull()
        }
        flushAwaitingKey()
        val f = fileFor(messageId)
        if (!f.isFile) return null
        return try {
            val bytes = readWhole(f) ?: return null
            putInCache(messageId, bytes)
            bytes
        } catch (e: Throwable) {
            null
        }
    }

    private fun readWhole(f: File): ByteArray? {
        if (!ChunkedMediaCipher.isEncryptedFile(f)) return f.readBytes()      // legacy plaintext
        val key = mediaKey() ?: return null
        val size = ChunkedMediaCipher.plaintextLength(f)
        if (size < 0 || size > Int.MAX_VALUE - 16) return null
        val out = ByteArray(size.toInt())
        ChunkedMediaCipher.decryptingStream(key, BufferedInputStream(f.inputStream(), COPY_BUFFER_BYTES)).use { input ->
            if (ChunkedMediaCipher.readFully(input, out, 0, out.size) != out.size) throw IOException("short blob")
            // Forces authentication of the final chunk (and of an empty file's only chunk).
            if (input.read() != -1) throw IOException("blob longer than its layout")
        }
        return out
    }

    /**
     * A plaintext stream over the stored blob, without loading it into memory; null
     * when absent or when the blob is encrypted and no key is available. Reading a
     * tampered/truncated blob throws [IOException] from the stream — it never
     * returns silently short data. The caller closes it.
     */
    fun openStream(messageId: String): InputStream? {
        synchronized(lock) { cache[messageId] }?.let { return it.inputStream() }
        when (val held = awaiting(messageId)) {
            is ByteArray -> return held.inputStream()
            is File -> return runCatching { BufferedInputStream(held.inputStream(), COPY_BUFFER_BYTES) }.getOrNull()
        }
        val f = fileFor(messageId)
        if (!f.isFile) return null
        return try {
            if (ChunkedMediaCipher.isEncryptedFile(f)) {
                val key = mediaKey() ?: return null
                ChunkedMediaCipher.decryptingStream(key, BufferedInputStream(f.inputStream(), COPY_BUFFER_BYTES))
            } else {
                BufferedInputStream(f.inputStream(), COPY_BUFFER_BYTES)
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Decrypt the blob to a fresh file under `cacheDir/oshi_plain/` for consumers that
     * need a path or URI (ExoPlayer, PdfRenderer, FileProvider share). The CALLER
     * deletes it when done; [PlaintextScratch.sweepAtStartup] removes any leftover at
     * the next launch. Null when absent or not decryptable (the partial file is removed).
     */
    fun decryptToTempFile(messageId: String, cacheDir: File, suffix: String = ".bin"): File? {
        val input = openStream(messageId) ?: return null
        return try {
            PlaintextScratch.write(cacheDir, input, suffix)
        } catch (e: Throwable) {
            null
        }
    }

    /** True when a blob exists on disk (cheap — no read). */
    fun has(messageId: String): Boolean =
        synchronized(lock) { cache.containsKey(messageId) } || awaiting(messageId) != null || fileFor(messageId).isFile

    /**
     * PLAINTEXT size in bytes of the stored blob, or -1 when absent (or when an
     * encrypted blob's layout is invalid). Computed from the header and file length —
     * no decryption — so callers comparing it with a byte count keep working.
     */
    fun sizeOf(messageId: String): Long {
        when (val held = awaiting(messageId)) {
            is ByteArray -> return held.size.toLong()
            is File -> return if (held.isFile) held.length() else -1L
        }
        val f = fileFor(messageId)
        if (!f.isFile) return -1L
        return if (ChunkedMediaCipher.isEncryptedFile(f)) ChunkedMediaCipher.plaintextLength(f) else f.length()
    }

    /** Diagnostic / test hook: true when the blob on disk is `OSHIMED1`. */
    fun isEncryptedOnDisk(messageId: String): Boolean = ChunkedMediaCipher.isEncryptedFile(fileFor(messageId))

    // =====================================================================
    // MIGRATION (legacy plaintext → OSHIMED1)
    // =====================================================================

    data class MigrationResult(
        val migrated: Int,
        val alreadyEncrypted: Int,
        val failed: Int,
        val keyAvailable: Boolean,
    )

    /**
     * Encrypt every legacy plaintext blob in place. Per file: stream-encrypt to a temp
     * sibling while hashing the source → fsync → decrypt the temp and compare length and
     * SHA-256 with the source → under the path lock, replace ONLY if the file is still
     * the one that was read (inode, size, mtime). Any failure leaves the plaintext blob
     * exactly where it was (still readable) and it is retried on the next run.
     * Blocking and I/O heavy — call off the main thread.
     */
    fun migrateLegacy(): MigrationResult {
        val key = mediaKey() ?: return MigrationResult(0, 0, 0, keyAvailable = false)
        var migrated = 0
        var already = 0
        var failed = 0
        for (f in directory.listFiles() ?: emptyArray()) {
            if (!f.isFile || decodeName(f.name) == null) continue
            if (ChunkedMediaCipher.isEncryptedFile(f)) { already++; continue }
            if (migrateOne(f, key)) migrated++ else failed++
        }
        return MigrationResult(migrated, already, failed, keyAvailable = true)
    }

    private fun migrateOne(target: File, key: SecretKey): Boolean {
        val before = SealedFile.fingerprint(target) ?: return false
        val tmp = File(directory, "${target.name}.mig${System.nanoTime()}.tmp")
        AtRestFileLocks.markInFlight(tmp)
        try {
            val srcDigest = MessageDigest.getInstance("SHA-256")
            val srcLen: Long = FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos, COPY_BUFFER_BYTES)
                val n = DigestInputStream(BufferedInputStream(target.inputStream(), COPY_BUFFER_BYTES), srcDigest)
                    .use { ChunkedMediaCipher.encrypt(key, it, out) }
                out.flush()
                fos.fd.sync()
                n
            }
            if (srcLen != before.size) return false
            if (ChunkedMediaCipher.plaintextLength(tmp) != srcLen) return false
            val chkDigest = MessageDigest.getInstance("SHA-256")
            var chkLen = 0L
            ChunkedMediaCipher.decryptingStream(key, BufferedInputStream(tmp.inputStream(), COPY_BUFFER_BYTES)).use { input ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    chkDigest.update(buf, 0, n)
                    chkLen += n
                }
            }
            if (chkLen != srcLen || !MessageDigest.isEqual(srcDigest.digest(), chkDigest.digest())) return false
            synchronized(AtRestFileLocks.forFile(target)) {
                // Rewritten (or deleted) while we worked: the newer file wins, untouched.
                if (SealedFile.fingerprint(target) != before) return false
                SealedFile.moveReplacing(tmp, target)
            }
            return true
        } catch (e: Throwable) {
            return false
        } finally {
            AtRestFileLocks.clearInFlight(tmp)
            tmp.delete()
        }
    }

    // =====================================================================
    // DELETE / CLEANUP
    // =====================================================================

    /**
     * Remove the blob and its cache entry.
     * iOS: `MediaManager.deleteAttachment(id:)` (`:758-761`) plus
     * `MediaAttachmentCache.remove()` (`:67-69`), which the delete and view-once
     * consume paths both call.
     */
    fun delete(messageId: String) {
        evict(messageId)
        (AwaitingKey.remove(awaitingKeyOf(messageId)) as? File)?.delete()
        val f = fileFor(messageId)
        // Under the path lock so a migration that already read this blob cannot
        // re-create it after the delete (a burnt view-once coming back from the dead).
        try { synchronized(AtRestFileLocks.forFile(f)) { f.delete() } } catch (_: Throwable) {}
    }

    fun deleteAll(messageIds: Collection<String>) {
        messageIds.forEach { delete(it) }
    }

    /** Drop a single entry from the in-memory cache without touching disk. */
    fun evict(messageId: String) {
        synchronized(lock) {
            cache.remove(messageId)?.let { cacheCost -= it.size.toLong() }
        }
    }

    /** iOS `MediaAttachmentCache.swift:44-51` — full purge on memory pressure. */
    fun clearCache() {
        synchronized(lock) {
            cache.clear()
            cacheCost = 0L
        }
    }

    /**
     * Delete every blob whose message id is NOT in [liveIds].
     *
     * This is the local analogue of iOS's retention sweep
     * (`PinataCleanupService.swift:23,262,305`): the remote pin is unpinned after
     * delivery, and the local copy has to go the same way when its row disappears —
     * a conversation delete (`deleteAllForContact`) removes rows in bulk and would
     * otherwise leak megabytes per deleted chat forever.
     *
     * @return the number of files removed.
     */
    fun purgeOrphans(liveIds: Set<String>): Int {
        var removed = sweepTempFiles()
        for (id in listStoredIds()) {
            if (id !in liveIds) { delete(id); removed++ }
        }
        return removed
    }

    /**
     * Every message id that currently has a blob on disk.
     *
     * Production enumerates these and asks the message table about each one
     * (`getMessageById(id) != null`) rather than building a "live" set from the contact
     * list: enumerating by contact misses messages whose contact row was removed, and a
     * purge driven by an incomplete list deletes attachments whose rows are still there.
     * That query is `suspend`, hence a listing here and the loop at the caller.
     */
    fun listStoredIds(): List<String> =
        directory.listFiles()?.mapNotNull { decodeName(it.name) } ?: emptyList()

    /** Remove leftover `.tmp` files from a write interrupted by process death. */
    fun sweepTempFiles(): Int =
        directory.listFiles()?.count {
            // A temp this process is still writing (a save or a migration running beside
            // the sweep) is not a leftover.
            it.name.endsWith(".tmp") && !AtRestFileLocks.isInFlight(it) && it.delete()
        } ?: 0

    /** Total bytes currently on disk. */
    fun totalBytesOnDisk(): Long =
        directory.listFiles()?.sumOf { it.length() } ?: 0L

    // =====================================================================
    // Cache internals
    // =====================================================================

    private fun putInCache(messageId: String, bytes: ByteArray) {
        // A single blob larger than the whole budget must not evict everything else
        // only to be evicted itself on the next put.
        if (bytes.size > CACHE_TOTAL_COST_LIMIT) return
        synchronized(lock) {
            cache.remove(messageId)?.let { cacheCost -= it.size.toLong() }
            cache[messageId] = bytes
            cacheCost += bytes.size.toLong()
            val it = cache.entries.iterator()
            while (it.hasNext() && (cache.size > CACHE_COUNT_LIMIT || cacheCost > CACHE_TOTAL_COST_LIMIT)) {
                val e = it.next()          // access-ordered ⇒ eldest = least recently used
                if (e.key == messageId) continue
                cacheCost -= e.value.size.toLong()
                it.remove()
            }
        }
    }

    /** Test/diagnostic hook. */
    internal fun cachedCount(): Int = synchronized(lock) { cache.size }
}
