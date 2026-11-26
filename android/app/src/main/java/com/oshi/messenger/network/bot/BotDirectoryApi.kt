package com.oshi.messenger.network.bot

import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.util.BotAdminRights
import com.oshi.messenger.util.BotPublicProfile
import com.oshi.messenger.util.BotUsername
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
 * __BOTS_PHASE1_2026_11_24__ The bot-directory routes BotFather and "Add a bot" need
 * (bots-phase1-contract §3 KEEP list + OWNER RE-SCOPE). Behind an interface: the
 * server patch is not deployed, the screens and their logic are tested against a fake.
 *
 * Real implementation: [HttpBotDirectoryApi].
 */
interface BotDirectoryApi {
    /** `GET /api/bot/public?username=` (or `?botId=`). 404 for unknown AND non-public. */
    suspend fun publicProfile(username: String? = null, botId: String? = null): BotApiResult<BotPublicProfile>

    /** `GET /api/bot/search?q=` — public bots, prefix on username and name, ≤20. */
    suspend fun search(query: String): BotApiResult<List<BotPublicProfile>>

    /** `POST /api/bot/setMyProfile` (owner, by token). 409 = username taken. */
    suspend fun setMyProfile(token: String, update: BotProfileUpdate): BotApiResult<Unit>

    /**
     * S1: `GET /api/bot/usernameAvailable?username=[&token=]` — changes nothing. With the
     * owner's [token], the bot's own current username reads available.
     */
    suspend fun usernameAvailable(username: String, token: String? = null): BotApiResult<Boolean>

    /**
     * S3: `POST /api/bot/revokeToken {token}` → the NEW token and the bot's (unchanged)
     * botId. The old token is dead at once.
     */
    suspend fun revokeToken(token: String): BotApiResult<RevokedToken>

    /**
     * S4: `POST /api/bot/addToGroup` — add a bot I do NOT own (found by search or link) to
     * a group or channel I administer, with the rights I chose. Signed as the adder.
     */
    suspend fun addToGroup(
        botId: String,
        groupId: String,
        isChannel: Boolean,
        memberKeys: List<String>,
        rights: BotAdminRights,
    ): BotApiResult<Unit>

    /** S4: `POST /api/bot/removeFromGroup {botId, groupId, adderPublicKey}`, signed. */
    suspend fun removeFromGroup(botId: String, groupId: String): BotApiResult<Unit>
}

data class RevokedToken(val token: String, val botId: String)

/** Owner edit. A null field is left as it is on the server. */
data class BotProfileUpdate(
    val username: String? = null,
    val name: String? = null,
    val description: String? = null,
    val about: String? = null,
    val avatarEmoji: String? = null,
    val public: Boolean? = null,
) {
    fun toJson(token: String): JSONObject = JSONObject().apply {
        put("token", token)
        username?.let { put("username", it) }
        name?.let { put("name", it) }
        description?.let { put("description", it) }
        about?.let { put("about", it) }
        avatarEmoji?.let { put("avatarEmoji", it) }
        public?.let { put("public", it) }
    }
}

/** Every route answers one of these; the UI maps each to one sentence. */
sealed interface BotApiResult<out T> {
    data class Ok<T>(val value: T) : BotApiResult<T>

    /** 404 with a JSON body: unknown, or not public (indistinguishable on purpose). */
    data object NotFound : BotApiResult<Nothing>

    /** 409 `username_taken`. */
    data object UsernameTaken : BotApiResult<Nothing>

    /** 400 with the server's code (`username_invalid`, …). */
    data class Invalid(val code: String) : BotApiResult<Nothing>

    /** 401 / 403. */
    data object Unauthorized : BotApiResult<Nothing>

    /** 429. */
    data object RateLimited : BotApiResult<Nothing>

    /** HTML 404: the route itself is not on the server (patch not deployed). */
    data object Unavailable : BotApiResult<Nothing>

    /** Network or 5xx. [reason] is for the log only. */
    data class Failed(val reason: String) : BotApiResult<Nothing>

    companion object {
        /**
         * Pure: HTTP status + body → result, shared by every route. A 404 WITHOUT a JSON
         * body is Express's default page — the route is missing — and must never be shown
         * as "this bot doesn't exist".
         */
        @JvmStatic
        fun <T> classify(code: Int, body: String, onSuccess: (JSONObject) -> T?): BotApiResult<T> {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val error = json?.optString("error", "").orEmpty()
            return when {
                code == 404 && json == null -> Unavailable
                code == 404 -> NotFound
                code == 409 -> UsernameTaken
                code == 401 || code == 403 -> Unauthorized
                code == 429 -> RateLimited
                code == 400 && error == "username_taken" -> UsernameTaken
                code == 400 -> Invalid(error.ifEmpty { "bad_request" })
                code !in 200..299 -> Failed("HTTP $code")
                json == null -> Failed("unparseable response")
                !json.optBoolean("success", false) -> Failed(error.ifEmpty { "success=false" })
                else -> onSuccess(json)?.let { Ok(it) } ?: Failed("missing fields")
            }
        }
    }
}

@Singleton
class HttpBotDirectoryApi @Inject constructor(
    private val signer: V2Signer,
) : BotDirectoryApi {

    companion object {
        private const val TAG = "BotDirectoryApi"
        private const val JSON = "application/json"
        const val PATH_PUBLIC = "/api/bot/public"
        const val PATH_SEARCH = "/api/bot/search"
        const val PATH_SET_PROFILE = "/api/bot/setMyProfile"
        const val PATH_REVOKE = "/api/bot/revokeToken"
        const val PATH_ADD = "/api/bot/addToGroup"
        const val PATH_REMOVE = "/api/bot/removeFromGroup"
        const val PATH_AVAILABLE = "/api/bot/usernameAvailable"
    }

    private val http = OkHttpClient.Builder()
        .addInterceptor(com.oshi.messenger.network.auth.ServerClock.interceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun enc(v: String) = android.net.Uri.encode(v)

    private suspend fun <T> call(request: Request, onSuccess: (JSONObject) -> T?): BotApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { r ->
                    BotApiResult.classify(r.code, r.body?.string().orEmpty(), onSuccess)
                }
            } catch (e: Exception) {
                Log.w(TAG, "${request.url.encodedPath} failed: ${e.message}")
                BotApiResult.Failed(e.message ?: "network error")
            }
        }

    private fun post(path: String, body: JSONObject, signed: Boolean): Request {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val b = Request.Builder().url("${BuildConfig.VPS_URL}$path").post(bytes.toRequestBody(JSON.toMediaType()))
        if (signed) runCatching { signer.sign("POST", path, bytes, withUserHeader = true) }.getOrNull()
            ?.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    override suspend fun publicProfile(username: String?, botId: String?): BotApiResult<BotPublicProfile> {
        val q = when {
            !username.isNullOrBlank() -> "username=${enc(BotUsername.normalize(username))}"
            !botId.isNullOrBlank() -> "botId=${enc(botId)}"
            else -> return BotApiResult.NotFound
        }
        return call(Request.Builder().url("${BuildConfig.VPS_URL}$PATH_PUBLIC?$q").get().build()) {
            BotPublicProfile.fromJson(it.optJSONObject("bot"))
        }
    }

    override suspend fun search(query: String): BotApiResult<List<BotPublicProfile>> {
        val q = query.trim().removePrefix("@").take(32)
        if (q.isEmpty()) return BotApiResult.Ok(emptyList())
        return call(Request.Builder().url("${BuildConfig.VPS_URL}$PATH_SEARCH?q=${enc(q)}").get().build()) { json ->
            val arr = json.optJSONArray("bots") ?: JSONArray()
            (0 until arr.length()).mapNotNull { BotPublicProfile.fromJson(arr.optJSONObject(it)) }.take(20)
        }
    }

    override suspend fun setMyProfile(token: String, update: BotProfileUpdate): BotApiResult<Unit> =
        call(post(PATH_SET_PROFILE, update.toJson(token), signed = false)) { }

    override suspend fun usernameAvailable(username: String, token: String?): BotApiResult<Boolean> {
        val q = "username=${enc(BotUsername.normalize(username))}" + (token?.let { "&token=${enc(it)}" } ?: "")
        return call(Request.Builder().url("${BuildConfig.VPS_URL}$PATH_AVAILABLE?$q").get().build()) {
            if (it.has("available")) it.optBoolean("available", false) else null
        }
    }

    override suspend fun revokeToken(token: String): BotApiResult<RevokedToken> =
        call(post(PATH_REVOKE, JSONObject().put("token", token), signed = false)) {
            val t = it.optString("token", "")
            val id = it.optString("botId", "")
            if (t.length >= 16 && id.isNotEmpty()) RevokedToken(t, id) else null
        }

    override suspend fun addToGroup(
        botId: String,
        groupId: String,
        isChannel: Boolean,
        memberKeys: List<String>,
        rights: BotAdminRights,
    ): BotApiResult<Unit> {
        val me = signer.userKey()
        val members = (memberKeys.filterNot { it.startsWith("bot:") } + me).distinct()
        val body = JSONObject()
            .put("botId", botId)
            .put("groupId", groupId)
            .put("kind", if (isChannel) "channel" else "group")
            .put("adderPublicKey", me)
            .put("members", JSONArray(members))
            .put("permissions", rights.toJson())
        return call(post(PATH_ADD, body, signed = true)) { }
    }

    override suspend fun removeFromGroup(botId: String, groupId: String): BotApiResult<Unit> =
        call(post(PATH_REMOVE, JSONObject().put("botId", botId).put("groupId", groupId).put("adderPublicKey", signer.userKey()), signed = true)) { }
}
