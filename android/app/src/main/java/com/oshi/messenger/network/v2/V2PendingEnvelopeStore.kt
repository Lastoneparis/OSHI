package com.oshi.messenger.network.v2

import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.SealedFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Persist ciphertext before network I/O so a retry never encrypts the same ID twice.
 *
 * At rest (2026-09-22): each record is sealed with [SealedFile] (`OSHISEA1`) under the
 * Keystore-wrapped [AtRestKey] — the envelope is E2E ciphertext, but the record also
 * carries a digest of the PLAINTEXT and the (sender, recipient) routing. Legacy
 * plaintext records are still read and re-sealed on read or by [migrateLegacy].
 * A sealed record that cannot be opened makes [load] THROW rather than return null:
 * null would let the caller re-encrypt the same message id, which is exactly what this
 * store exists to prevent.
 */
class V2PendingEnvelopeStore(
    root: File,
    private val keys: AtRestKeyProvider = AtRestKey,
) {
    private companion object {
        const val SEAL_LABEL = "v2_pending_envelope"
    }
    private val directory = File(root, "v2_pending_envelopes")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun file(sender: String, recipient: String, id: String) =
        File(directory, digest(JSONObject().put("from", sender).put("to", recipient).put("id", id).toString().toByteArray()) + ".json")

    @Synchronized fun load(sender: String, recipient: String, id: String, plaintext: ByteArray): JSONObject? {
        val file = file(sender, recipient, id)
        if (!file.exists()) return null
        val legacy = SealedFile.isLegacyPlaintext(file)
        val text = SealedFile.readText(file, SEAL_LABEL, keys) ?: return null
        val record = JSONObject(text)
        if (legacy) try { SealedFile.migrate(file, SEAL_LABEL, keys) } catch (_: Throwable) {}
        if (record.getString("digest") != digest(plaintext)) throw IOException("Pending message content changed; use a new message ID")
        return record.getJSONObject("envelope")
    }

    @Synchronized fun save(sender: String, recipient: String, id: String, plaintext: ByteArray, envelope: JSONObject) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot persist pending message")
        val file = file(sender, recipient, id)
        if (!file.exists() && (directory.listFiles()?.count { it.extension == "json" } ?: 0) >= 500) {
            throw IOException("Pending message storage is full")
        }
        val bytes = JSONObject().put("digest", digest(plaintext)).put("envelope", envelope).toString().toByteArray()
        // temp → fsync → atomic rename, sealed when the at-rest key is available.
        SealedFile.writeAtomic(file, SEAL_LABEL, keys, bytes)
    }

    /** Seal records left in plaintext by an older build. Returns how many were migrated. */
    @Synchronized fun migrateLegacy(): Int =
        directory.listFiles()?.count { f ->
            f.isFile && f.extension == "json" &&
                try { SealedFile.migrate(f, SEAL_LABEL, keys) } catch (_: Throwable) { false }
        } ?: 0

    @Synchronized fun remove(sender: String, recipient: String, id: String) {
        val file = file(sender, recipient, id)
        if (file.exists() && !file.delete()) throw IOException("Cannot clear pending message")
    }
}
