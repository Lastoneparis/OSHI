package com.oshi.messenger.network.v2

import android.util.Log
import com.oshi.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Receipt from DELETE /v2/account — a partial answer, not a boolean. */
data class AccountDeletionReceipt(
    val complete: Boolean,
    val prekeysErased: Boolean,
    val relayEnvelopes: Int,
    val syncItems: Int,
    val inboundBlobs: Int,
    val outboundBlobsLeft: Int,
    val unreachable: List<String>,
)

/**
 * First OSHI V2 endpoint on Android: server-side account deletion.
 * `DELETE /v2/account`, byte-compatible with iOS `V2Client.deleteAccount`
 * (V2Client.swift:389-428). Needs only the §1 signing seam + userKey — no
 * session/crypto state — so it doubles as the signing-seam smoke test AND
 * closes the Play/GDPR in-app-deletion gap (Android previously wiped only
 * locally, leaving prekeys/relay/sync/blobs on the server forever).
 *
 * Body-to-hash is the 2-byte string `""` (verify-first route); x-oshi-user set.
 * HTTP 200–207 all count as success (207 = partial).
 */
@Singleton
class V2AccountClient @Inject constructor(
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

    suspend fun deleteAccount(): Result<AccountDeletionReceipt> = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/account"
            val headers = signer.sign(
                method = "DELETE",
                path = path,
                bodyToHash = signer.emptyJsonStringBody(),
                withUserHeader = true,
            )
            val builder = Request.Builder()
                .url("${BuildConfig.VPS_URL}$path")
                .delete()
            headers.forEach { (k, v) -> builder.header(k, v) }

            client.newCall(builder.build()).execute().use { resp ->
                // 2xx incl. 207 (partial) = success.
                if (resp.code !in 200..207) {
                    return@withContext Result.failure(
                        RuntimeException("account delete HTTP ${resp.code}")
                    )
                }
                val body = resp.body?.string().orEmpty()
                Result.success(parse(body))
            }
        } catch (e: Exception) {
            Log.e("V2AccountClient", "deleteAccount failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parse(body: String): AccountDeletionReceipt {
        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val erased = json.optJSONObject("erased")
        val relay = erased?.optJSONObject("relay")
        val sync = erased?.optJSONObject("sync")
        val blobs = erased?.optJSONObject("blobs")
        val remote = json.optJSONObject("remote")
        val unreachable = mutableListOf<String>()
        if (remote != null) {
            for (key in remote.keys()) {
                if (remote.optJSONObject(key)?.optBoolean("ok", false) != true) unreachable.add(key)
            }
            unreachable.sort()
        }
        return AccountDeletionReceipt(
            complete = json.optBoolean("complete", unreachable.isEmpty()),
            prekeysErased = erased?.optBoolean("prekeys", false) ?: false,
            relayEnvelopes = relay?.optInt("envelopes", 0) ?: 0,
            syncItems = sync?.optInt("items", 0) ?: 0,
            inboundBlobs = blobs?.optInt("deleted", 0) ?: 0,
            outboundBlobsLeft = blobs?.optInt("outboundLeft", 0) ?: 0,
            unreachable = unreachable,
        )
    }
}
