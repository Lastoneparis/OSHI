package com.oshi.messenger.mail

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oshi.messenger.network.v2.V2Signer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OSHI Mail on Android — the counterpart of iOS `OSHIMailClient`.
 *
 * There is no mail password: the mailbox is owned by this device's OSHI identity, and
 * [V2Signer] (already used for every `/v2/` call) proves it. The mail X25519 key lives
 * only here; see [MailCrypto] for the sealing scheme and the frozen cross-platform vector.
 *
 * Base URL is the APEX host, not the mail domain: the API is served from
 * oshi-messenger.com under its existing certificate, while addresses are
 * `@mail.oshi-messenger.com`.
 */
@Singleton
class OshiMailClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signer: V2Signer,
    private val cryptoManager: com.oshi.messenger.network.encryption.CryptoManager,
) {
    companion object {
        const val MAIL_DOMAIN = "mail.oshi-messenger.com"
        private const val BASE_URL = "https://oshi-messenger.com"
        private const val PREFS = "oshi_mail"
        private const val KEY_MAIL_PRIVATE = "mail_x25519_private"
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }

    class MailException(val code: String, val status: Int = 0, message: String? = null) :
        Exception(message ?: code)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // ---- the mail key ------------------------------------------------------------

    /**
     * Stored in EncryptedSharedPreferences, backed by a Keystore master key that is not
     * exportable. Deliberately NOT in any cloud backup: whoever holds this key can read
     * the mailbox, so it stays on the device that created it — and losing it loses the
     * mail, because the server has no readable copy.
     */
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyGenParameterSpec(
                KeyGenParameterSpec.Builder(
                    "oshi_mail_master",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun mailPrivateKey(): ByteArray {
        // Preferred: derive deterministically from the OSHI identity so the mailbox and
        // drive are recoverable from the seed / key backup alone — lose the phone, keep
        // the key, keep the mail. Matches iOS OSHIMailClient.mailPrivateKey. The server
        // never sees this key: zero-access is unchanged.
        cryptoManager.deriveMailKeyMaterial()?.let { return it }
        // Fallback (no identity yet, e.g. a legacy install): device-local key, NOT
        // recoverable. The identity path above is what makes recovery possible.
        prefs.getString(KEY_MAIL_PRIVATE, null)?.let { return Base64.getDecoder().decode(it) }
        val (priv, _) = MailCrypto.generateMailKeyPair()
        prefs.edit().putString(KEY_MAIL_PRIVATE, Base64.getEncoder().encodeToString(priv)).apply()
        return priv
    }

    fun mailPublicKeyHex(): String = with(MailCrypto) { publicKeyFor(mailPrivateKey()).toHex() }

    /**
     * A valid local-part derived from this device's public key, for an alias that reads
     * like an identity rather than a chosen name. The full key is 64 hex chars (too long
     * for a local-part), so a stable 18-char prefix is used; hex is already in [a-z0-9].
     */
    fun keyBasedLocalpart(): String = mailPublicKeyHex().take(18).lowercase()

    fun open(blob: ByteArray): ByteArray = MailCrypto.open(blob, mailPrivateKey())

    fun seal(plaintext: ByteArray): ByteArray =
        MailCrypto.seal(plaintext, MailCrypto.publicKeyFor(mailPrivateKey()))

    // ---- signed transport --------------------------------------------------------

    private fun build(method: String, path: String, body: ByteArray?, isJson: Boolean = true): Request {
        // The signature covers the path WITHOUT the query string and the EXACT body bytes,
        // so both are fixed here before anything can re-serialize them.
        val toHash = body ?: ByteArray(0)
        val headers = signer.sign(method, path.substringBefore('?'), toHash)
        val builder = Request.Builder().url(BASE_URL + path)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        val requestBody = when {
            body != null -> body.toRequestBody(if (isJson) JSON else OCTET)
            method in setOf("POST", "PUT", "DELETE") -> ByteArray(0).toRequestBody(JSON)
            else -> null
        }
        return builder.method(method, requestBody).build()
    }

    private suspend fun call(method: String, path: String, body: ByteArray? = null, isJson: Boolean = true): ByteArray =
        withContext(Dispatchers.IO) {
            http.newCall(build(method, path, body, isJson)).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    val code = runCatching { JSONObject(String(bytes)).optString("error", "unknown") }
                        .getOrDefault("unknown")
                    throw MailException(code, response.code, String(bytes).take(300))
                }
                bytes
            }
        }

    private suspend fun callJson(method: String, path: String, body: JSONObject? = null): JSONObject {
        val raw = call(method, path, body?.toString()?.toByteArray(Charsets.UTF_8))
        return if (raw.isEmpty()) JSONObject() else JSONObject(String(raw, Charsets.UTF_8))
    }

    // ---- account -----------------------------------------------------------------

    data class Account(
        val address: String,
        val localpart: String,
        val aliases: List<String>,
        val aliasLimit: Int,
        val storageUsed: Long,
        val storageQuota: Long,
        val messages: Int,
        val sent: Int,
        val sendQuota: Int,
        val sendRemaining: Int,
        val driveUsed: Long,
        val driveQuota: Long,
        val driveFiles: Int,
    )

    private fun parseAccount(o: JSONObject): Account {
        val a = o.getJSONObject("account")
        val storage = a.getJSONObject("storage")
        val sending = a.getJSONObject("sending")
        val drive = a.getJSONObject("drive")
        val aliases = a.optJSONArray("aliases") ?: JSONArray()
        return Account(
            address = a.getString("address"),
            localpart = a.getString("localpart"),
            aliases = (0 until aliases.length()).map { aliases.getString(it) },
            aliasLimit = a.optInt("aliasLimit", 10),
            storageUsed = storage.getLong("used"),
            storageQuota = storage.getLong("quota"),
            messages = storage.optInt("messages"),
            sent = sending.getInt("sent"),
            sendQuota = sending.getInt("quota"),
            sendRemaining = sending.getInt("remaining"),
            driveUsed = drive.getLong("used"),
            driveQuota = drive.getLong("quota"),
            driveFiles = drive.optInt("files"),
        )
    }

    /** Unauthenticated and rate-limited server-side; safe to call while the user types. */
    suspend fun isAvailable(localpart: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/mail/v1/availability?localpart=${localpart.lowercase()}"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            response.isSuccessful && JSONObject(body).optBoolean("available", false)
        }
    }

    /** Claims an address, publishing only the PUBLIC half of the mail key. */
    suspend fun register(localpart: String): Account = parseAccount(
        callJson(
            "POST", "/mail/v1/register",
            JSONObject()
                .put("localpart", localpart.lowercase())
                .put("mailPubKey", mailPublicKeyHex())
                // The X25519 messaging identity — the same key the push service already
                // holds this device's FCM token under — so a delivered mail can wake the
                // device. Not secret, not the mail key. Zero-access is unchanged.
                .apply {
                    runCatching { cryptoManager.exportPublicKeyBase64() }
                        .getOrNull()?.let { put("identityPubKey", it) }
                },
        ),
    )

    suspend fun account(): Account = parseAccount(callJson("GET", "/mail/v1/account"))

    // ---- inbox -------------------------------------------------------------------

    data class Envelope(
        val from: String,
        val to: String,
        val subject: String,
        val date: String,
        val cc: String = "",
        /**
         * List preview computed by the server at delivery, from the plaintext it held for
         * one function call, and sealed with the rest of this envelope. Null for mail
         * delivered before the server change (and for client-built envelopes without one):
         * the list then falls back to a snippet of a body opened on this device.
         */
        val snippet: String? = null,
        val hasAttachments: Boolean = false,
        val attachmentCount: Int = 0,
        val hasImages: Boolean = false,
    )

    data class Message(
        val id: String,
        val receivedAt: String,
        val bytes: Long,
        val seen: Boolean,
        val folder: String,
        val deliveredTo: String,
        /** null when the envelope was sealed for a different device. */
        val envelope: Envelope?,
    )

    object EnvelopeJson {
        private val IMAGE_NAME = Regex("""\.(jpe?g|png|gif|heic|heif|webp|bmp|tiff?)$""", RegexOption.IGNORE_CASE)

        /**
         * Decodes an opened (decrypted) envelope. Every field past the original five is
         * optional and type-checked, so an envelope from an older server, a draft sealed by
         * an older app, or a malformed value never fails the row — it just shows less.
         *
         * Sent copies have always carried `attachments: [filename…]`; when the newer
         * counters are absent that list still says whether a paperclip belongs on the row.
         */
        fun parseEnvelope(json: String): Envelope {
            val plain = JSONObject(json)
            val legacyNames = plain.optJSONArray("attachments")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.opt(it) as? String } }
                .orEmpty()
            val count = when (val c = plain.opt("attachmentCount")) {
                is Number -> c.toInt().coerceIn(0, 999)
                else -> legacyNames.size
            }
            val hasAttachments = (plain.opt("hasAttachments") as? Boolean ?: false) || count > 0
            val hasImages = plain.opt("hasImages") as? Boolean ?: legacyNames.any { IMAGE_NAME.containsMatchIn(it) }
            val snippet = (plain.opt("snippet") as? String)
                ?.replace(Regex("\\s+"), " ")?.trim()
                ?.takeIf { it.isNotEmpty() }
            return Envelope(
                from = plain.optString("from"),
                to = plain.optString("to"),
                subject = plain.optString("subject"),
                date = plain.optString("date"),
                cc = plain.optString("cc"),
                snippet = snippet,
                hasAttachments = hasAttachments,
                attachmentCount = if (hasAttachments) count.coerceAtLeast(1) else 0,
                hasImages = hasImages,
            )
        }
    }

    suspend fun inbox(): List<Message> {
        val messages = callJson("GET", "/mail/v1/inbox").optJSONArray("messages") ?: JSONArray()
        return (0 until messages.length()).map { i ->
            val m = messages.getJSONObject(i)
            var envelope: Envelope? = null
            m.optString("sealedEnvelope", "").takeIf { it.isNotEmpty() }?.let { sealed ->
                runCatching {
                    envelope = EnvelopeJson.parseEnvelope(String(open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8))
                }
            }
            Message(
                id = m.getString("id"),
                receivedAt = m.getString("receivedAt"),
                bytes = m.getLong("bytes"),
                seen = m.optBoolean("seen"),
                folder = m.optString("folder", "inbox"),
                deliveredTo = m.optString("deliveredTo"),
                envelope = envelope,
            )
        }
    }

    /** The full RFC822 message, decrypted on this device. */
    suspend fun message(id: String): ByteArray = open(call("GET", "/mail/v1/message/$id"))

    suspend fun markSeen(id: String, seen: Boolean = true) {
        callJson("POST", "/mail/v1/message/$id/seen", JSONObject().put("seen", seen))
    }

    /**
     * First call moves a live message to Trash; a second call (the message already being
     * in Trash) destroys it. [permanent] skips straight to destruction — used when a draft
     * turns into a sent message and should not linger in Trash. Returns whether this call
     * actually destroyed it, so the caller can say "Moved to Trash" vs "Deleted for ever".
     */
    suspend fun delete(id: String, permanent: Boolean = false): Boolean {
        val path = if (permanent) "/mail/v1/message/$id?permanent=1" else "/mail/v1/message/$id"
        return callJson("DELETE", path).optBoolean("deleted", false)
    }

    /** folder in inbox|archive|sent|trash|draft. */
    suspend fun move(id: String, folder: String) {
        callJson("POST", "/mail/v1/message/$id/move", JSONObject().put("folder", folder))
    }

    // ---- drafts --------------------------------------------------------------------

    /**
     * Autosaves the composer. Both halves are sealed by the CALLER before this is invoked —
     * this method never sees plaintext. Passing the id returned by a previous call reuses
     * the same draft instead of piling up duplicates as the user keeps typing.
     */
    suspend fun saveDraft(id: String?, sealedEnvelope: ByteArray, sealedBody: ByteArray): String {
        val payload = JSONObject()
            .put("sealedEnvelope", Base64.getEncoder().encodeToString(sealedEnvelope))
            .put("sealedBody", Base64.getEncoder().encodeToString(sealedBody))
        id?.let { payload.put("id", it) }
        return callJson("POST", "/mail/v1/draft", payload).getString("id")
    }

    // ---- sending -----------------------------------------------------------------

    /**
     * Counted against the 150/month allowance. [from] may only be one of this account's
     * own aliases — the server answers 403 to anything else rather than letting a client
     * forge a sender.
     */
    data class Attachment(val filename: String, val mimeType: String, val bytes: ByteArray)

    /**
     * The server caps one message at 25 MB and base64 inflates by about a third, so this
     * is the real ceiling on raw attachment bytes. Checked before the upload rather than
     * after it.
     */
    val maxAttachmentTotalBytes = 18 * 1024 * 1024

    suspend fun send(
        to: List<String>,
        subject: String,
        text: String,
        cc: List<String> = emptyList(),
        from: String? = null,
        inReplyTo: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): String {
        val payload = JSONObject()
            .put("to", JSONArray(to))
            .put("subject", subject)
            .put("text", text)
        if (cc.isNotEmpty()) payload.put("cc", JSONArray(cc))
        from?.let { payload.put("from", it) }
        inReplyTo?.let { payload.put("inReplyTo", it) }

        if (attachments.isNotEmpty()) {
            require(attachments.sumOf { it.bytes.size } <= maxAttachmentTotalBytes) {
                "attachments exceed $maxAttachmentTotalBytes bytes"
            }
            val arr = JSONArray()
            attachments.forEach {
                arr.put(
                    JSONObject()
                        .put("filename", it.filename)
                        .put("mimeType", it.mimeType)
                        .put("dataBase64", Base64.getEncoder().encodeToString(it.bytes)),
                )
            }
            payload.put("attachments", arr)
        }
        return callJson("POST", "/mail/v1/send", payload).optString("messageId")
    }

    // ---- browser pairing ----------------------------------------------------------

    data class PairingRequest(val code: String, val browserPublicKey: ByteArray, val ageSeconds: Int)
    data class WebSession(val id: String, val label: String, val createdAt: String, val lastSeenAt: String)

    /**
     * Looks up what a typed code refers to, so the user can see what they are approving
     * before the key leaves the device. Unauthenticated by design — it reveals only an
     * ephemeral public key and how old the request is.
     */
    suspend fun inspectPairing(code: String): PairingRequest = withContext(Dispatchers.IO) {
        val clean = code.uppercase().filter { !it.isWhitespace() }
        val url = "$BASE_URL/mail/v1/web/pair/inspect?code=$clean"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw MailException(runCatching { JSONObject(body).optString("error") }.getOrDefault("unknown"), response.code)
            }
            val o = JSONObject(body)
            PairingRequest(clean, MailCrypto.hexToBytes(o.getString("browserPubKey")), o.optInt("ageMs") / 1000)
        }
    }

    /**
     * Hands this mailbox's key to that browser, sealed to the browser's own ephemeral key.
     * The server relays a blob it cannot open. This is the only action that lets another
     * device read the mailbox, so it is signed by the phone and never by a browser token.
     */
    suspend fun approvePairing(pairing: PairingRequest, label: String): String {
        val sealedKey = MailCrypto.seal(mailPrivateKey(), pairing.browserPublicKey)
        val out = callJson(
            "POST", "/mail/v1/web/pair/claim",
            JSONObject()
                .put("code", pairing.code)
                .put("sealedMailKey", Base64.getEncoder().encodeToString(sealedKey))
                .put("label", label),
        )
        return out.optString("address")
    }

    suspend fun webSessions(): List<WebSession> {
        val arr = callJson("GET", "/mail/v1/web/sessions").optJSONArray("sessions") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WebSession(o.getString("id"), o.optString("label"), o.optString("createdAt"), o.optString("lastSeenAt"))
        }
    }

    /** [id] is a session id, or "all" to sign every browser out. */
    suspend fun revokeWebSession(id: String): Int =
        callJson("DELETE", "/mail/v1/web/sessions/$id").optInt("revoked")

    // ---- aliases -----------------------------------------------------------------

    suspend fun addAlias(alias: String): List<String> {
        val out = callJson("POST", "/mail/v1/alias", JSONObject().put("alias", alias.lowercase()))
        val arr = out.optJSONArray("aliases") ?: JSONArray()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun removeAlias(alias: String): List<String> {
        val localpart = alias.substringBefore('@')
        val out = callJson("DELETE", "/mail/v1/alias/$localpart")
        val arr = out.optJSONArray("aliases") ?: JSONArray()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ---- drive (1 GB) ------------------------------------------------------------

    data class DriveFile(val id: String, val bytes: Long, val createdAt: String, val name: String?)

    suspend fun driveFiles(): List<DriveFile> {
        val files = callJson("GET", "/mail/v1/drive").optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            val name = runCatching {
                val meta = open(Base64.getDecoder().decode(f.getString("sealedMeta")))
                JSONObject(String(meta, Charsets.UTF_8)).optString("name")
            }.getOrNull()
            DriveFile(f.getString("id"), f.getLong("bytes"), f.optString("createdAt"), name)
        }
    }

    /**
     * [encrypted] must ALREADY be sealed by the caller (use [seal]) — this method never
     * sees plaintext. Chunks are resumable: the server rejects an out-of-order offset and
     * names the one it expects, so a dropped connection resumes instead of restarting.
     */
    suspend fun uploadToDrive(
        encrypted: ByteArray,
        sealedName: ByteArray,
        onProgress: ((Float) -> Unit)? = null,
    ): String {
        val init = callJson(
            "POST", "/mail/v1/drive/upload",
            JSONObject()
                .put("bytes", encrypted.size)
                .put("sealedMeta", Base64.getEncoder().encodeToString(sealedName)),
        )
        val uploadId = init.getString("uploadId")
        val chunkSize = init.optInt("chunkSize", 8 * 1024 * 1024)

        var offset = 0
        while (offset < encrypted.size) {
            val end = minOf(offset + chunkSize, encrypted.size)
            val chunk = encrypted.copyOfRange(offset, end)
            val raw = call("PUT", "/mail/v1/drive/upload/$uploadId?offset=$offset", chunk, isJson = false)
            offset = JSONObject(String(raw, Charsets.UTF_8)).optInt("received", end)
            onProgress?.invoke(offset.toFloat() / encrypted.size)
        }

        val done = callJson("POST", "/mail/v1/drive/upload/$uploadId/complete")
        return done.optJSONObject("file")?.optString("id") ?: uploadId
    }

    suspend fun downloadFromDrive(id: String): ByteArray = open(call("GET", "/mail/v1/drive/file/$id"))

    suspend fun deleteFromDrive(id: String) { callJson("DELETE", "/mail/v1/drive/file/$id") }

    /**
     * Renames a drive file. [sealedMeta] must ALREADY be sealed by the caller (use [seal])
     * — the name lives entirely inside the encrypted blob, so the server that stores it
     * never learns either the old or the new name.
     */
    suspend fun renameDriveFile(id: String, sealedMeta: ByteArray) {
        callJson(
            "PATCH", "/mail/v1/drive/file/$id/meta",
            JSONObject().put("sealedMeta", Base64.getEncoder().encodeToString(sealedMeta)),
        )
    }
}
