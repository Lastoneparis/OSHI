package com.oshi.messenger.network.v2

import android.util.Base64
import android.util.Log
import com.oshi.messenger.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A V2 relay envelope (v=4), matching iOS V2ClientModels. */
data class V2Envelope(
    val msgId: String,
    val from: String,
    val to: String,
    val type: String = "1to1",
    val groupId: String? = null,
    val x3dh: V2Session.X3DHHeader? = null,
    val header: ByteArray,       // 40-byte ratchet header
    val ciphertext: ByteArray,   // ct‖tag
    val ts: Long,
    val seq: Long = -1,          // set on pull only
    // __PER_DEVICE_MAILBOX_2026_09_23__ optional routing ids (32 lowercase hex). Absent on
    // every envelope of today's path, so the wire bytes of a legacy send are unchanged.
    val toDevice: String? = null,
    val fromDevice: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 4)
        put("msgId", msgId)
        put("from", from)
        put("to", to)
        put("type", type)
        groupId?.let { put("groupId", it) }
        x3dh?.let {
            put("x3dh", JSONObject().apply {
                put("identityKey", b64(it.identityKey))
                put("ephemeralKey", b64(it.ephemeralKey))
                put("signedPreKeyId", it.signedPreKeyId)
                it.oneTimePreKeyId?.let { id -> put("oneTimePreKeyId", id) }
            })
        }
        put("header", b64(header))
        put("ciphertext", b64(ciphertext))
        put("ts", ts)
        toDevice?.let { put("toDevice", it) }
        fromDevice?.let { put("fromDevice", it) }
    }

    companion object {
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

        fun fromJson(o: JSONObject): V2Envelope {
            val x = o.optJSONObject("x3dh")?.let {
                V2Session.X3DHHeader(
                    identityKey = unb64(it.getString("identityKey")),
                    ephemeralKey = unb64(it.getString("ephemeralKey")),
                    signedPreKeyId = it.optString("signedPreKeyId"),
                    oneTimePreKeyId = if (it.has("oneTimePreKeyId") && !it.isNull("oneTimePreKeyId"))
                        it.optString("oneTimePreKeyId") else null,
                )
            }
            return V2Envelope(
                msgId = o.getString("msgId"),
                from = o.getString("from"),
                to = o.getString("to"),
                type = o.optString("type", "1to1"),
                groupId = if (o.has("groupId") && !o.isNull("groupId")) o.optString("groupId") else null,
                x3dh = x,
                header = unb64(o.getString("header")),
                ciphertext = unb64(o.getString("ciphertext")),
                ts = o.optLong("ts", 0),
                seq = o.optLong("seq", -1),
                toDevice = o.optString("toDevice", "").takeIf { it.length == 32 },
                fromDevice = o.optString("fromDevice", "").takeIf { it.length == 32 },
            )
        }
    }
}

/** Result of a pull. */
data class V2Pull(val messages: List<V2Envelope>, val maxSeq: Long)

/**
 * OSHI V2 message relay (/v2/messages), byte-compatible with iOS V2Client.
 * Send (signed POST of the envelope), pull (non-destructive GET after a seq), and
 * ack (POST upToSeq — the relay drops ≤seq after ack). Query strings are excluded
 * from the signed path (V2Signer), matching iOS URLComponents.percentEncodedPath.
 */
@Singleton
class V2MessagesClient @Inject constructor(
    private val coverSettings: CoverSettings,
    private val signer: V2Signer,
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(coverSettings.interceptor())
        .noSilentReplay()   // __SIGNED_NO_SILENT_REPLAY_2026_11_18__ (CoverInterceptor.kt)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Send one envelope. Returns the accepted msgIds (empty on failure). */
    suspend fun send(env: V2Envelope): List<String> = withContext(Dispatchers.IO) {
        // Keep ciphertext/message ID fixed, but refresh request authentication on
        // each attempt. The relay deduplicates an accepted send whose reply was lost.
        val bodyBytes = env.toJson().toString().toByteArray(Charsets.UTF_8)
        val path = "/v2/messages"
        for (attempt in 0..2) {
            try {
                val headers = signer.sign("POST", path, bodyBytes)
                val req = Request.Builder()
                    .url("${BuildConfig.VPS_URL}$path")
                    .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code in 200..299) {
                        val accepted = JSONObject(resp.body?.string().orEmpty()).optJSONArray("accepted") ?: JSONArray()
                        return@withContext (0 until accepted.length()).map { accepted.getString(it) }
                    }
                    if (resp.code != 429 && resp.code !in 500..599) return@withContext emptyList()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("V2MessagesClient", "send attempt failed: ${e.message}") }
            if (attempt < 2) delay(1000L shl attempt)
        }
        emptyList()
    }

    /** Pull messages after `afterSeq` for our own userKey. Non-destructive. */
    suspend fun pull(myUserKey: String, afterSeq: Long): V2Pull? = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/messages/${signer.encodeIdentity(myUserKey)}"  // query excluded from signature
            val headers = signer.sign("GET", path, ByteArray(0))
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$path?after=$afterSeq")
                .get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext null
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONArray("messages") ?: JSONArray()
                val msgs = (0 until arr.length()).map { V2Envelope.fromJson(arr.getJSONObject(it)) }
                V2Pull(msgs, json.optLong("maxSeq", afterSeq))
            }
        } catch (e: Exception) {
            Log.e("V2MessagesClient", "pull failed: ${e.message}", e); null
        }
    }

    /** Ack delivery up to `upToSeq`; the relay drops ≤seq. */
    suspend fun ack(myUserKey: String, upToSeq: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyBytes = JSONObject().apply { put("upToSeq", upToSeq) }.toString().toByteArray(Charsets.UTF_8)
            val path = "/v2/messages/${signer.encodeIdentity(myUserKey)}/ack"
            val headers = signer.sign("POST", path, bodyBytes)
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$path")
                .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp -> resp.code in 200..299 }
        } catch (e: Exception) {
            Log.e("V2MessagesClient", "ack failed: ${e.message}", e); false
        }
    }
}
