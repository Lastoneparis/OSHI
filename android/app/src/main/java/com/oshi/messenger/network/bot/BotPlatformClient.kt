package com.oshi.messenger.network.bot

import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.data.local.BotDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the OSHI bot platform's interactive routes.
 *
 * ```
 * POST /api/bot/dispatch                {token, kind, groupId, fromPublicKey, …}
 * POST /api/bot/miniapp/session         {token, userPublicKey, groupId}
 * GET  /api/bot/getMyCommands?token=…
 * ```
 *
 * The write routes take EITHER the bot's secret `token` (owner-only) or its public
 * `botId` paired with the caller's own public key, which the server authorises by
 * checking group membership. [resolveAuth] picks between them and is allowed to return
 * null — a client that cannot identify a bot says so rather than inventing a credential.
 */
@Singleton
class BotPlatformClient @Inject constructor(
    private val botDao: BotDao
) {

    companion object {
        private const val TAG = "BotPlatformClient"
        private const val BASE = "/api/bot"
        private const val JSON_MEDIA = "application/json"

        /**
         * Pick the credential: an OWNED token beats a public id; a public id beats
         * nothing.
         *
         * The order matters and is not arbitrary. `botId` authorises through group
         * membership, so it is strictly the weaker claim — an owner acting on their own
         * bot from a group they have since left would be refused by a `botId` call and
         * accepted by a token call. Preferring the token keeps an owner's own bot
         * working in every case it worked before this route existed.
         *
         * Pure and static so the preference can be tested without a database.
         */
        @JvmStatic
        fun chooseAuth(ownedToken: String?, botId: String?): BotAuth? = when {
            !ownedToken.isNullOrEmpty() -> BotAuth.ByToken(ownedToken)
            !botId.isNullOrEmpty() -> BotAuth.ByBotId(botId)
            else -> null
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ── Results ──────────────────────────────────────────────────────────────

    /**
     * How a call to the platform's write routes identifies itself.
     *
     * __BOT_PUBLIC_ID_2026_09_16__ Until the server grew [ByBotId], the only answer was
     * the bot's secret, which a phone holds only for bots its user created — so every
     * button on somebody else's bot was inert. [ByBotId] is a one-way digest of that
     * secret: it names the bot, cannot act as it, and the server pairs it with the
     * caller's public key and checks group membership itself.
     */
    sealed interface BotAuth {
        /** The bot's secret. Only for a bot this user OWNS. */
        data class ByToken(val token: String) : BotAuth

        /** The bot's public id, authorised by the caller's own membership. */
        data class ByBotId(val botId: String) : BotAuth
    }

    sealed interface DispatchResult {
        /** Accepted. [callbackQueryId] is present for a `callback_query` kind. */
        data class Ok(val updateId: String?, val callbackQueryId: String?) : DispatchResult

        /** The server said no, or could not be reached. [reason] is for the log only. */
        data class Failed(val reason: String) : DispatchResult

        /**
         * 403 — the server says this user is not a member of any group the bot serves.
         * Distinct from [Failed] because it is the one refusal that is about the USER
         * rather than about the bot or the network, and it deserves its own sentence.
         */
        data object NotAMember : DispatchResult

        /** 404 — the server has never heard of that bot id. */
        data object UnknownBot : DispatchResult

        /** Nothing on this device identifies that bot — see [resolveAuth]. */
        data object NoAuth : DispatchResult
    }

    data class MiniAppSession(val url: String, val initData: String)

    data class BotCommand(val command: String, val description: String)

    // ── Token resolution ─────────────────────────────────────────────────────

    /**
     * How to identify the bot behind [botSenderKey] (`bot:<prefix>`), or null when
     * nothing on this device can.
     *
     * Owned token FIRST, public id second — see [chooseAuth] for why that order and not
     * the other. There is deliberately no third source: deriving or guessing a
     * credential the user was not given is an authorisation decision, and it belongs to
     * the server.
     */
    suspend fun resolveAuth(botSenderKey: String, botName: String?, botId: String?): BotAuth? =
        chooseAuth(ownedTokenFor(botSenderKey, botName), botId)

    /** The token of a bot this user CREATED, or null. Never a token merely received. */
    private suspend fun ownedTokenFor(botSenderKey: String, botName: String?): String? {
        val prefix = botSenderKey.removePrefix("bot:").take(8)
        return try {
            val owned = botDao.getAllBotsList()
            owned.firstOrNull { prefix.isNotEmpty() && it.token.startsWith(prefix) }?.token
                ?: botName?.takeIf { it.isNotBlank() }
                    ?.let { name -> owned.firstOrNull { it.name.equals(name, ignoreCase = true) }?.token }
        } catch (e: Exception) {
            Log.w(TAG, "ownedTokenFor failed: ${e.message}")
            null
        }
    }

    // (the credential preference itself is `chooseAuth`, in the companion object)

    // ── Routes ───────────────────────────────────────────────────────────────

    /**
     * Write the chosen credential onto a request body.
     *
     * `fromPublicKey` is on the body either way — it is the caller's identity, which
     * the dispatch route wants regardless — but it is what MAKES a [BotAuth.ByBotId]
     * call authorise, since the server pairs it with the bot id to check membership.
     */
    private fun JSONObject.putAuth(auth: BotAuth): JSONObject = apply {
        when (auth) {
            is BotAuth.ByToken -> put("token", auth.token)
            is BotAuth.ByBotId -> put("botId", auth.botId)
        }
    }

    /** `kind: "callback_query"` — a tap on an inline button carrying `callback_data`. */
    suspend fun dispatchCallbackQuery(
        auth: BotAuth,
        groupId: String,
        fromPublicKey: String,
        callbackData: String,
        messageId: String
    ): DispatchResult = dispatch(
        JSONObject().apply {
            putAuth(auth)
            put("kind", "callback_query")
            put("groupId", groupId)
            put("fromPublicKey", fromPublicKey)
            put("callbackData", callbackData)
            put("messageId", messageId)
        }
    )

    /** `kind: "command"` — a `/slash args` line routed to the bot. */
    suspend fun dispatchCommand(
        auth: BotAuth,
        groupId: String,
        fromPublicKey: String,
        text: String,
        messageId: String
    ): DispatchResult = dispatch(
        JSONObject().apply {
            putAuth(auth)
            put("kind", "command")
            put("groupId", groupId)
            put("fromPublicKey", fromPublicKey)
            put("text", text)
            put("messageId", messageId)
        }
    )

    private suspend fun dispatch(body: JSONObject): DispatchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${BuildConfig.VPS_URL}$BASE/dispatch")
                .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
                .addHeader("Content-Type", JSON_MEDIA)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // The two refusals the user can act on get their own result, so the
                    // UI can say "you are not in a group this bot serves" instead of a
                    // generic failure the user cannot do anything about.
                    return@withContext when (response.code) {
                        403 -> DispatchResult.NotAMember
                        404 -> DispatchResult.UnknownBot
                        else -> DispatchResult.Failed("HTTP ${response.code}")
                    }
                }
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return@withContext DispatchResult.Failed("unparseable response")
                }
                if (!json.optBoolean("success", false)) {
                    return@withContext DispatchResult.Failed(
                        json.optString("error", "").ifEmpty { "success=false" }
                    )
                }
                // `update_id` is documented without a type; a server that renumbers it
                // as an int must not turn a working button into a parse failure, so it
                // is read as text either way.
                DispatchResult.Ok(
                    updateId = json.opt("update_id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" },
                    callbackQueryId = json.opt("callback_query_id")?.toString()
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispatch failed: ${e.message}")
            DispatchResult.Failed(e.message ?: "network error")
        }
    }

    /**
     * Open a mini-app session. Returns null on any failure — the caller's job is to
     * tell the user the mini-app would not open, not to guess a URL.
     */
    suspend fun createMiniAppSession(
        auth: BotAuth,
        userPublicKey: String,
        groupId: String
    ): MiniAppSession? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                putAuth(auth)
                // The session route names the caller `userPublicKey`, not
                // `fromPublicKey` as dispatch does. Same value, different key — and it
                // is the half that authorises a `botId` call, so it is not optional.
                put("userPublicKey", userPublicKey)
                put("groupId", groupId)
            }
            val request = Request.Builder()
                .url("${BuildConfig.VPS_URL}$BASE/miniapp/session")
                .post(body.toString().toRequestBody(JSON_MEDIA.toMediaType()))
                .addHeader("Content-Type", JSON_MEDIA)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "miniapp/session HTTP ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.optBoolean("success", false)) return@withContext null
                val url = json.optString("url", "")
                val initData = json.optString("initData", "")
                // A mini-app is a WebView with JavaScript ON. Loading a non-TLS or
                // non-http(s) URL there — whatever the server returned — is not a
                // degraded experience, it is the hole.
                if (!url.startsWith("https://")) {
                    Log.w(TAG, "miniapp/session returned a non-https url — refusing to open it")
                    return@withContext null
                }
                MiniAppSession(url = url, initData = initData)
            }
        } catch (e: Exception) {
            Log.w(TAG, "createMiniAppSession failed: ${e.message}")
            null
        }
    }

    /** The bot's advertised command list. Empty on any failure. */
    suspend fun getMyCommands(token: String): List<BotCommand> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${BuildConfig.VPS_URL}$BASE/getMyCommands?token=${android.net.Uri.encode(token)}")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.optBoolean("success", false)) return@withContext emptyList()
                val array = json.optJSONArray("commands") ?: return@withContext emptyList()
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val command = obj.optString("command", "").trim()
                    if (command.isEmpty()) null
                    else BotCommand(command, obj.optString("description", "").trim())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getMyCommands failed: ${e.message}")
            emptyList()
        }
    }
}
