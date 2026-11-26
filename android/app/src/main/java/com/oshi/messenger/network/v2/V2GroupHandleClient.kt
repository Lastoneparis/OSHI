package com.oshi.messenger.network.v2

import android.content.Context
import android.util.Log
import com.oshi.messenger.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
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

/**
 * Public group link registry client — `/v2/g/<handle>` (docs/GROUP_INFO_SETTINGS_SPEC.md §4).
 *
 * Writes (PUT/DELETE) are signed exactly like every other v2 route ([V2Signer], with
 * `x-oshi-user`); the server refuses them unless the signature is bound to our identity.
 * The lookup is unsigned: a joiner who does not have the app set up yet must still be able
 * to resolve a link, and the server rate-limits it per IP.
 *
 * The group id never leaves the device: only `groupRef` (a hash of it) is sent.
 */
@Singleton
class V2GroupHandleClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coverSettings: CoverSettings,
    private val signer: V2Signer,
) {
    enum class Result { OK, TAKEN, INVALID, NETWORK }

    data class Resolved(
        val handle: String,
        val joinKeys: List<String>,
        val previewName: String?,
        val previewDescription: String?,
        val previewMemberCount: Int?,
    )

    private val client = OkHttpClient.Builder()
        .addInterceptor(coverSettings.interceptor())
        .noSilentReplay()   // __SIGNED_NO_SILENT_REPLAY_2026_11_18__ (CoverInterceptor.kt)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * DEBUG builds only: `adb shell am broadcast`-free override for testing against a local
     * dev server (`run-as com.oshi.messenger` + this prefs file). Release builds always use
     * the production base.
     */
    private fun baseUrl(): String {
        if (BuildConfig.DEBUG) {
            val o = context.getSharedPreferences(DEV_PREFS, Context.MODE_PRIVATE).getString(DEV_KEY_BASE, null)
            if (!o.isNullOrBlank()) return o.trimEnd('/')
        }
        return BuildConfig.VPS_URL
    }

    suspend fun register(
        handle: String,
        groupRef: String,
        joinKeys: List<String>,
        controllers: List<String>,
        previewName: String?,
        previewDescription: String?,
        previewMemberCount: Int?,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val body = buildRegisterBody(groupRef, joinKeys, controllers, previewName, previewDescription, previewMemberCount)
            val bytes = body.toByteArray(Charsets.UTF_8)
            val path = "/v2/g/$handle"
            val headers = signer.sign("PUT", path, bytes, withUserHeader = true)
            val b = Request.Builder().url(baseUrl() + path).put(bytes.toRequestBody(JSON))
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r ->
                Log.d(TAG, "PUT /v2/g/$handle -> HTTP ${r.code}")
                when (r.code) {
                    200 -> Result.OK
                    409 -> Result.TAKEN
                    400, 403 -> Result.INVALID
                    else -> { Log.w(TAG, "register HTTP ${r.code}"); Result.NETWORK }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "register failed: ${e.message}")
            Result.NETWORK
        }
    }

    suspend fun unregister(handle: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/g/$handle"
            val headers = signer.sign("DELETE", path, ByteArray(0), withUserHeader = true)
            val b = Request.Builder().url(baseUrl() + path).delete()
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r -> r.code == 200 || r.code == 404 }
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed: ${e.message}")
            false
        }
    }

    /** null = not found (404); throws on network trouble so the UI can tell them apart. */
    suspend fun resolve(handle: String): Resolved? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl() + "/v2/g/$handle").get().build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (r.code != 200) throw java.io.IOException("HTTP ${r.code}")
            parseResolved(r.body?.string().orEmpty())
        }
    }

    companion object {
        private const val TAG = "V2GroupHandle"
        private val JSON = "application/json".toMediaType()
        const val DEV_PREFS = "oshi_dev_overrides"
        const val DEV_KEY_BASE = "group_handle_base_url"

        /** Pure, tested: the PUT body (spec §4.2). */
        fun buildRegisterBody(
            groupRef: String,
            joinKeys: List<String>,
            controllers: List<String>,
            previewName: String?,
            previewDescription: String?,
            previewMemberCount: Int?,
        ): String = JSONObject().apply {
            put("groupRef", groupRef)
            put("joinKeys", JSONArray(joinKeys))
            put("controllers", JSONArray(controllers))
            if (previewName != null || previewDescription != null || previewMemberCount != null) {
                put("preview", JSONObject().apply {
                    previewName?.let { put("name", it) }
                    previewDescription?.let { put("description", it) }
                    previewMemberCount?.let { put("memberCount", it) }
                })
            }
        }.toString()

        /** Pure, tested: the GET answer; anything malformed ⇒ null (treated as not found). */
        fun parseResolved(body: String): Resolved? {
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val handle = o.optString("handle").ifEmpty { return null }
            val keys = o.optJSONArray("joinKeys")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }
                .orEmpty().take(8)
            if (keys.isEmpty()) return null
            val p = o.optJSONObject("preview")
            return Resolved(
                handle = handle,
                joinKeys = keys,
                previewName = p?.optString("name")?.ifEmpty { null },
                previewDescription = p?.optString("description")?.ifEmpty { null },
                previewMemberCount = p?.takeIf { it.has("memberCount") }?.optInt("memberCount"),
            )
        }
    }
}
