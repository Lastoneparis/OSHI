package com.oshi.messenger.service.media

import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.SealedFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persistent ledger of in-flight `/v2/blobs` SENDS — port of iOS `V2SendResumeStore`
 * (`V2MessageRouter.swift:1290-1457`), consumed by `sendFile` at `:775-802`.
 *
 * ## What it buys
 *
 * A 40 MB video interrupted at 95 % (screen off, network flip, process killed) used to
 * restart from byte 0 with a brand-new reservation and a brand-new file key, orphaning
 * the partially uploaded blob server-side. With a record, the next attempt reuses the
 * SAME `fileKey`/`fileNonce` — so re-encryption is byte-identical — hands the old
 * `blobId` back to the uploader, and tops up only what `/status` still reports missing.
 *
 * This is NOT a new transfer mechanism; it is persistence for the one that exists.
 *
 * ## SAFETY INVARIANT (iOS `V2MessageRouter.swift:1299-1307` — not optional)
 *
 * Reusing an AES-GCM (key, nonce) pair across DIFFERENT plaintexts is a total break of
 * confidentiality AND authenticity. Every record therefore carries a SHA-256 of the
 * plaintext it was created for, and [record] refuses to hand key material back unless
 * that digest matches byte for byte. A mismatched, missing, expired or corrupt record
 * yields null and the caller starts fresh — the failure mode of this class is
 * "no resume", never "bad crypto".
 *
 * Takes a plain [File] root rather than a `Context` so the whole class is exercisable
 * from a JVM unit test, exactly like [MediaAttachmentStore].
 *
 * At rest (2026-09-22): the ledger holds per-blob `fileKey`/`fileNonce`, so it is
 * sealed with [SealedFile] (`OSHISEA1`) under the Keystore-wrapped [AtRestKey];
 * a legacy plaintext ledger is still read and is re-sealed by [migrateLegacy] or by
 * the next write.
 */
class V2SendResumeStore(
    root: File,
    private val keys: AtRestKeyProvider = AtRestKey,
) {

    companion object {
        /** iOS `maxAge` (`V2MessageRouter.swift:1327`) — matched to the blob store's TTL. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        private const val FILE_NAME = "v2_send_resume.json"

        /** [SealedFile] AAD label — binds the ciphertext to this store. */
        internal const val SEAL_LABEL = "v2_send_resume"

        /**
         * Ledger key for ONE (message, recipient) pair — iOS `fanoutId` (`:1353`).
         * A 1:1 send owns one blob; a GROUP send reserves one blob PER MEMBER (the blob
         * store authorises a single `recipientKey`), so the same logical message owns N
         * records. A userKey is base64 and never contains `#`, so this cannot collide
         * with a bare 1:1 id.
         */
        fun fanoutId(msgId: String, recipientKey: String): String = "$msgId#$recipientKey"

        /** SHA-256 of the plaintext, lower-case hex — the nonce-reuse guard. */
        fun digest(plaintext: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(plaintext)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    data class Record(
        val id: String,
        val recipientKey: String,
        /** SHA-256 of the PLAINTEXT file bytes. */
        val plaintextSha256: String,
        val blobId: String,
        val fileKey: ByteArray,
        val fileNonce: ByteArray,
        val chunkCount: Int,
        val createdAt: Long,
        /**
         * __MEDIA_PADDING_2026_11_10__ Whether the reservation's chunks were sealed PADDED.
         * Part of the binding: the same key/nonce over the padded tail and over the unpadded
         * one are two plaintexts under one GCM nonce. Records written before this field: false.
         */
        val padded: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Record) return false
            return id == other.id && recipientKey == other.recipientKey &&
                plaintextSha256 == other.plaintextSha256 && blobId == other.blobId &&
                fileKey.contentEquals(other.fileKey) && fileNonce.contentEquals(other.fileNonce) &&
                chunkCount == other.chunkCount && createdAt == other.createdAt && padded == other.padded
        }

        override fun hashCode(): Int {
            var r = id.hashCode()
            r = 31 * r + recipientKey.hashCode()
            r = 31 * r + plaintextSha256.hashCode()
            r = 31 * r + blobId.hashCode()
            r = 31 * r + fileKey.contentHashCode()
            r = 31 * r + fileNonce.contentHashCode()
            r = 31 * r + chunkCount
            r = 31 * r + createdAt.hashCode()
            r = 31 * r + padded.hashCode()
            return r
        }
    }

    private val file = File(root, FILE_NAME)
    private val lock = Any()

    /**
     * The usable record for this (message, recipient), or null → the caller mints a
     * fresh key and reserves a new blob. [plaintextSha256] is verified BEFORE any key
     * material is returned.
     */
    fun record(
        msgId: String,
        recipientKey: String,
        plaintextSha256: String,
        now: Long = System.currentTimeMillis(),
        /** __MEDIA_PADDING_2026_11_10__ the padding decision of THIS attempt; must match the record's. */
        padded: Boolean = false,
    ): Record? {
        val id = fanoutId(msgId, recipientKey)
        val r = synchronized(lock) { load()[id] } ?: return null
        if (now - r.createdAt >= MAX_AGE_MS) { remove(msgId, recipientKey); return null }
        if (r.recipientKey != recipientKey) return null
        if (r.plaintextSha256 != plaintextSha256) {
            // Same id, different bytes: this record's key material must never touch
            // this plaintext.
            remove(msgId, recipientKey)
            return null
        }
        if (r.padded != padded) {
            // __MEDIA_PADDING_2026_11_10__ The padding decision changed (a recipient device
            // gained/lost the capability): the reservation's chunks are the other plaintext.
            remove(msgId, recipientKey)
            return null
        }
        return r
    }

    /**
     * Remember a reservation. Call the instant `reserve` returns — BEFORE the first
     * chunk goes out — because the point is to survive a process that dies at an
     * arbitrary moment during the transfer (iOS `:1385-1400`).
     */
    fun put(
        msgId: String,
        recipientKey: String,
        plaintextSha256: String,
        blobId: String,
        fileKey: ByteArray,
        fileNonce: ByteArray,
        chunkCount: Int,
        now: Long = System.currentTimeMillis(),
        /** __MEDIA_PADDING_2026_11_10__ see [Record.padded]. */
        padded: Boolean = false,
    ) {
        val id = fanoutId(msgId, recipientKey)
        synchronized(lock) {
            val all = load().toMutableMap()
            all[id] = Record(id, recipientKey, plaintextSha256, blobId, fileKey, fileNonce, chunkCount, now, padded)
            save(prune(all, now))
        }
    }

    /** Drop the record once the send has committed (or is abandoned for good). */
    fun remove(msgId: String, recipientKey: String) {
        val id = fanoutId(msgId, recipientKey)
        synchronized(lock) {
            val all = load().toMutableMap()
            if (all.remove(id) != null) save(all)
        }
    }

    /** Drop everything older than [MAX_AGE_MS]. Cheap; safe to call on every start. */
    fun prune(now: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            val all = load()
            val kept = prune(all, now)
            if (kept.size != all.size) save(kept)
            return all.size - kept.size
        }
    }

    internal fun size(): Int = synchronized(lock) { load().size }

    /** Seal a ledger left in plaintext by an older build. No-op without a key. */
    fun migrateLegacy(): Boolean = synchronized(lock) {
        try { SealedFile.migrate(file, SEAL_LABEL, keys) } catch (_: Throwable) { false }
    }

    // ---- Persistence --------------------------------------------------------

    private fun prune(all: Map<String, Record>, now: Long): Map<String, Record> =
        all.filterValues { now - it.createdAt < MAX_AGE_MS }

    private fun load(): Map<String, Record> {
        if (!file.isFile) return emptyMap()
        return try {
            val text = SealedFile.readText(file, SEAL_LABEL, keys) ?: return emptyMap()
            val arr = JSONArray(text)
            val out = LinkedHashMap<String, Record>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = Record(
                    id = o.getString("id"),
                    recipientKey = o.getString("recipientKey"),
                    plaintextSha256 = o.getString("plaintextSha256"),
                    blobId = o.getString("blobId"),
                    fileKey = hex(o.getString("fileKey")),
                    fileNonce = hex(o.getString("fileNonce")),
                    chunkCount = o.getInt("chunkCount"),
                    createdAt = o.getLong("createdAt"),
                    padded = o.optBoolean("padded", false),
                )
                out[r.id] = r
            }
            out
        } catch (e: Throwable) {
            // A corrupt ledger is not worth a crash: it degrades to "no resume".
            emptyMap()
        }
    }

    private fun save(all: Map<String, Record>) {
        val arr = JSONArray()
        for (r in all.values) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("recipientKey", r.recipientKey)
                    .put("plaintextSha256", r.plaintextSha256)
                    .put("blobId", r.blobId)
                    .put("fileKey", hex(r.fileKey))
                    .put("fileNonce", hex(r.fileNonce))
                    .put("chunkCount", r.chunkCount)
                    .put("createdAt", r.createdAt)
                    .apply { if (r.padded) put("padded", true) }
            )
        }
        try {
            // Temp + fsync + atomic rename, sealed when the at-rest key is available.
            SealedFile.writeAtomic(file, SEAL_LABEL, keys, arr.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
