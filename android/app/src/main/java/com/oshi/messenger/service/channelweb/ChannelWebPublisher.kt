package com.oshi.messenger.service.channelweb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.SealedFile
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.GroupSettings
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.CoverSettings
import com.oshi.messenger.network.v2.V2ChannelWebClient
import com.oshi.messenger.network.v2.V2ChannelWebClient.Outcome
import com.oshi.messenger.network.v2.V2GroupHandleClient
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.network.v2.noSilentReplay
import com.oshi.messenger.service.GroupLocalStore
import com.oshi.messenger.service.GroupManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __CHANNEL_WEB_2026_11_26__ Public channel web preview, client side
 * (ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md §9).
 *
 *  - the admin toggle ([enable] / [disable] / [setIndexing]) talks to the server first and
 *    only then flips `settings.webPreview`, which travels to co-admins in the E2E definition;
 *  - every own post / edit / delete in a web-enabled channel is ENQUEUED in a sealed, durable
 *    [ChannelWebOutbox] and sent by one background coroutine with backoff, so the E2E send
 *    never waits on, nor fails because of, the web copy, and a restart loses nothing;
 *  - channel name / description / picture changes re-`PUT` the profile (§9.5), and a group that
 *    stops being a public channel (made private, handle changed, no longer admins-only,
 *    dissolved) gets its web copy purged (§9.6);
 *  - the post menu and deep links resolve a post's public number `n` through the admin map
 *    (§3.6) or, for a reader, the public feed ([ChannelWebMatch]).
 */
@Singleton
class ChannelWebPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groupManager: GroupManager,
    private val cryptoManager: CryptoManager,
    coverSettings: CoverSettings,
    private val signer: V2Signer,
) {
    enum class Result { OK, NOT_ALLOWED, NEEDS_PUBLIC_LINK, NETWORK, REJECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private val store by lazy { GroupLocalStore.get(context) }
    private val imageDir by lazy { File(context.noBackupFilesDir, "channel_web_outbox").apply { mkdirs() } }

    private val http = OkHttpClient.Builder()
        .addInterceptor(coverSettings.interceptor())
        .noSilentReplay()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val client = V2ChannelWebClient(http, ::baseUrl) { m, p, b -> signer.sign(m, p, b, withUserHeader = true) }

    private val outbox = ChannelWebOutbox(
        api = object : ChannelWebOutbox.Api {
            override fun enable(handle: String, body: String) = client.enable(handle, body)
            override fun disable(handle: String) = client.disable(handle)
            override fun upload(handle: String, image: ByteArray) = client.upload(handle, image)
            override fun publish(handle: String, postId: String, body: String) = client.publish(handle, postId, body)
            override fun deletePost(handle: String, postId: String) = client.deletePost(handle, postId)
        },
        images = object : ChannelWebOutbox.ImageFiles {
            override fun read(key: String): ByteArray? = runCatching {
                SealedFile.readBytes(File(imageDir, safeKey(key)), IMAGE_LABEL, AtRestKey)
            }.getOrNull()
            override fun delete(key: String) { runCatching { File(imageDir, safeKey(key)).delete() } }
        },
        persist = { json -> runCatching { store.write(GroupLocalStore.Doc.CHANNEL_WEB, json) }
            .onFailure { Log.w(TAG, "outbox not saved: ${it.message}") } },
    )

    /** Reader side: handle → (messageId → n), learned from the public feed. Memory only. */
    private val readerN = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    /** handle → when the `postId → n` map was last fetched (§3.6), refreshed daily. */
    private val adminSyncedAt = ConcurrentHashMap<String, Long>()
    private fun adminSyncDue(handle: String) =
        System.currentTimeMillis() - (adminSyncedAt[handle] ?: 0L) > ADMIN_SYNC_EVERY_MS

    private fun baseUrl(): String {
        if (BuildConfig.DEBUG) {
            val o = context.getSharedPreferences(V2GroupHandleClient.DEV_PREFS, Context.MODE_PRIVATE)
                .getString(V2GroupHandleClient.DEV_KEY_BASE, null)
            if (!o.isNullOrBlank()) return o.trimEnd('/')
        }
        return BuildConfig.VPS_URL
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called once from `OSHIApplication.onCreate`; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            runCatching { outbox.load(store.read(GroupLocalStore.Doc.CHANNEL_WEB)) }
            outbox.isActive = { gid, handle -> settings(gid)?.let { it.webActive && it.handle == handle } == true }
            groupManager.channelWebHooks = hooks
            observeGroups()
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) { wake.trySend(Unit) }
                    })
            }
            while (true) {
                runCatching { outbox.runDue() }.onFailure { Log.w(TAG, "outbox run: ${it.message}") }
                runCatching { refreshAdminMapsIfDue() }
                val next = outbox.nextDueAt()
                val wait = if (next == null) MAX_SLEEP_MS else (next - System.currentTimeMillis()).coerceIn(0L, MAX_SLEEP_MS)
                if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    private fun kick() { wake.trySend(Unit) }

    private fun me(): String = runCatching { cryptoManager.getPublicKeyString() }.getOrDefault("")

    private fun settings(groupId: String): GroupSettings? = groupManager.settingsOf(groupId)

    private fun iAmAdmin(groupId: String) = groupManager.isAdminOf(groupId, me())

    // ------------------------------------------------------------------ hooks (enqueue only)

    private val hooks = object : GroupManager.ChannelWebHooks {
        override fun onOwnPost(groupId: String, message: GroupMessage) {
            val s = settings(groupId) ?: return
            val handle = s.handle ?: return
            if (!s.webActive || !iAmAdmin(groupId) || message.isDeleted) return
            val plan = ChannelWebContent.plan(message.content, message.mediaType)
                ?: return
            scope.launch {
                val images = if (plan.withImage) listOfNotNull(prepareImage(message)) else emptyList()
                outbox.enqueuePublish(groupId, handle, message.id, plan.text, message.timestamp, images)
                kick()
            }
        }

        override fun onOwnPostEdited(groupId: String, messageId: String, newContent: String) {
            val s = settings(groupId) ?: return
            val handle = s.handle ?: return
            if (!s.webActive) return
            val known = outbox.entry(handle, messageId)
            if (known == null && !outbox.hasPendingPublish(handle, messageId)) return   // never published
            val msg = findMessage(groupId, messageId)
            val plan = ChannelWebContent.plan(newContent, msg?.mediaType)
            if (plan == null) { outbox.enqueueDelete(groupId, handle, messageId); kick(); return }
            scope.launch {
                val images = when {
                    !plan.withImage -> emptyList()
                    known != null && known.mediaIds.isNotEmpty() -> known.mediaIds.map { ChannelWebOutbox.Image(null, it) }
                    known != null && msg != null -> listOfNotNull(prepareImage(msg))
                    else -> emptyList()    // pending publish keeps its own images
                }
                outbox.enqueuePublish(groupId, handle, messageId, plan.text, msg?.timestamp ?: System.currentTimeMillis(), images)
                kick()
            }
        }

        override fun onPostDeletedForEveryone(groupId: String, messageId: String) {
            val s = settings(groupId) ?: return
            val handle = s.handle ?: return
            if (!s.webActive || !iAmAdmin(groupId)) return
            outbox.enqueueDelete(groupId, handle, messageId)
            kick()
        }
    }

    private fun findMessage(groupId: String, messageId: String): GroupMessage? =
        groupManager.groupMessages.value.entries.firstOrNull { it.key.equals(groupId, true) }
            ?.value?.firstOrNull { it.id.equals(messageId, true) }

    private fun localPosts(groupId: String): List<GroupMessage> =
        groupManager.groupMessages.value.entries.firstOrNull { it.key.equals(groupId, true) }?.value.orEmpty()

    /** Spec §3.3 client duties, then sealed to disk so the queue survives a restart. */
    private fun prepareImage(message: GroupMessage): ChannelWebOutbox.Image? {
        val raw = message.mediaData ?: runCatching { groupManager.loadMediaBytes(message.id) }.getOrNull() ?: return null
        val prepared = runCatching { ChannelWebImagePrep.prepare(raw) }.getOrNull() ?: return null
        return writeImage(prepared)?.let { ChannelWebOutbox.Image(it, null) }
    }

    private fun writeImage(bytes: ByteArray): String? {
        val key = UUID.randomUUID().toString()
        return runCatching { SealedFile.writeAtomic(File(imageDir, key), IMAGE_LABEL, AtRestKey, bytes); key }
            .onFailure { Log.w(TAG, "image not sealed: ${it.message}") }.getOrNull()
    }

    private fun safeKey(k: String) = k.filter { it.isLetterOrDigit() || it == '-' }

    // ------------------------------------------------------------------ settings / profile observer

    @OptIn(FlowPreview::class)
    private fun observeGroups() {
        var previous: Map<String, GroupSettings>? = null
        scope.launch {
            combine(groupManager.groups, groupManager.groupSettings) { g, s -> g to s }
                .debounce(1_500L)
                .collect { (groups, all) ->
                    val prev = previous
                    previous = all
                    for ((gid, s) in all) {
                        if (!iAmAdmin(gid)) continue
                        val before = prev?.get(gid)
                        // §9.6: the old web copy goes when its handle stops being this channel's.
                        // NOT on a bare `webPreview` flip received from another admin: the admin
                        // who turned it off already purged, and a build that does not know the
                        // key (iOS today) re-emits settings without it — that must not wipe the web.
                        if (before != null && before.webActive && before.handle != null &&
                            (!s.webEligible || s.handle != before.handle)) {
                            Log.d(TAG, "🌐 purge @${before.handle} (no longer a public channel / handle changed)")
                            outbox.enqueueDisable(gid, before.handle)
                            readerN.remove(before.handle)
                            kick()
                        }
                        if (!s.webActive) continue
                        val handle = s.handle ?: continue
                        val g = groups.firstOrNull { it.id.equals(gid, true) } ?: continue
                        // §9.5 profile: name, description, picture, indexing.
                        val sig = profileSig(g.name, g.description, g.groupImage, s.webIndexing)
                        if (outbox.profileSig(handle) != sig) {
                            val avatar = g.groupImage?.let { runCatching { ChannelWebImagePrep.prepare(it, ChannelWebImagePrep.AVATAR_SIDE) }.getOrNull() }
                                ?.let(::writeImage)
                            outbox.enqueueProfile(gid, handle, title(g.name), description(g.description), s.webIndexing,
                                avatar, clearAvatar = g.groupImage == null)
                            outbox.setProfileSig(handle, sig)
                            kick()
                        }
                        // §9.1 "A new device calls GET …/admin to learn the state."
                        if (adminSyncDue(handle)) scope.launch { syncAdminMap(handle) }
                    }
                }
        }
    }

    private fun title(name: String): String? = name.trim().takeIf { it.isNotEmpty() }?.let { cut(it, 64) }
    private fun description(d: String): String? = d.trim().takeIf { it.isNotEmpty() }?.let { cut(it, 255) }
    private fun cut(s: String, max: Int): String =
        if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

    private fun profileSig(name: String, desc: String, image: ByteArray?, indexing: Boolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(name.toByteArray()); md.update(0); md.update(desc.toByteArray()); md.update(0)
        image?.let { md.update(it) }; md.update(if (indexing) 1 else 0)
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /** §3.6, daily: every web-enabled channel this device administers (the loop wakes ≤ 6 h). */
    private suspend fun refreshAdminMapsIfDue() {
        for ((gid, s) in groupManager.groupSettings.value) {
            val h = s.handle ?: continue
            if (s.webActive && adminSyncDue(h) && iAmAdmin(gid)) syncAdminMap(h)
        }
    }

    private suspend fun syncAdminMap(handle: String): Boolean = withContext(Dispatchers.IO) {
        when (val o = client.adminStatus(handle)) {
            is Outcome.Ok -> {
                val st = V2ChannelWebClient.parseAdmin(o.json) ?: return@withContext false
                if (st.enabled) outbox.mergeAdminMap(handle, st.posts)
                adminSyncedAt[handle] = System.currentTimeMillis()
                true
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------ admin toggle (UI)

    suspend fun enable(groupId: String, indexing: Boolean = false): Result = withContext(Dispatchers.IO) {
        val g = groupManager.getGroupById(groupId) ?: return@withContext Result.NOT_ALLOWED
        val s = settings(groupId) ?: GroupSettings.DEFAULT
        if (!iAmAdmin(groupId) || s.postPolicy != GroupSettings.PostPolicy.ADMINS) return@withContext Result.NOT_ALLOWED
        if (!s.webEligible) return@withContext Result.NEEDS_PUBLIC_LINK
        val handle = s.handle!!
        val body = V2ChannelWebClient.buildEnableBody(title(g.name), description(g.description), indexing)
        when (val o = client.enable(handle, body)) {
            is Outcome.Ok -> Unit
            is Outcome.Retry -> return@withContext Result.NETWORK
            else -> { Log.w(TAG, "enable @$handle refused: $o"); return@withContext Result.REJECTED }
        }
        val r = groupManager.updateSettings(groupId) { it.copy(webPreview = true, webIndexing = indexing) }
        if (r != GroupManager.SettingsResult.OK) return@withContext Result.NOT_ALLOWED
        // The picture follows through the outbox (enable → upload → PUT avatarMediaId).
        g.groupImage?.let { img ->
            ChannelWebImagePrep.prepare(img, ChannelWebImagePrep.AVATAR_SIDE)?.let(::writeImage)?.let { key ->
                outbox.enqueueProfile(groupId, handle, null, null, null, key, clearAvatar = false)
            }
        }
        outbox.setProfileSig(handle, profileSig(g.name, g.description, g.groupImage, indexing))
        adminSyncedAt.remove(handle)
        scope.launch { syncAdminMap(handle) }
        kick()
        Result.OK
    }

    /** Off → `DELETE /v2/c/<handle>` purges every web copy (§3.2). Settings flip only after it. */
    suspend fun disable(groupId: String): Result = withContext(Dispatchers.IO) {
        val s = settings(groupId) ?: return@withContext Result.NOT_ALLOWED
        if (!iAmAdmin(groupId)) return@withContext Result.NOT_ALLOWED
        val handle = s.handle
        if (handle != null) {
            when (client.disable(handle)) {
                is Outcome.Ok, is Outcome.NotFound -> Unit
                is Outcome.Retry -> return@withContext Result.NETWORK
                else -> return@withContext Result.REJECTED
            }
            outbox.forgetHandle(handle)
            readerN.remove(handle)
            adminSyncedAt.remove(handle)
        }
        val r = groupManager.updateSettings(groupId) { it.copy(webPreview = false, webIndexing = false) }
        if (r == GroupManager.SettingsResult.OK) Result.OK else Result.NOT_ALLOWED
    }

    suspend fun setIndexing(groupId: String, on: Boolean): Result = withContext(Dispatchers.IO) {
        val s = settings(groupId) ?: return@withContext Result.NOT_ALLOWED
        if (!iAmAdmin(groupId) || !s.webActive) return@withContext Result.NOT_ALLOWED
        val handle = s.handle!!
        when (client.enable(handle, V2ChannelWebClient.buildEnableBody(null, null, on))) {
            is Outcome.Ok -> Unit
            is Outcome.Retry -> return@withContext Result.NETWORK
            else -> return@withContext Result.REJECTED
        }
        val g = groupManager.getGroupById(groupId)
        if (g != null) outbox.setProfileSig(handle, profileSig(g.name, g.description, g.groupImage, on))
        val r = groupManager.updateSettings(groupId) { it.copy(webIndexing = on) }
        if (r == GroupManager.SettingsResult.OK) Result.OK else Result.NOT_ALLOWED
    }

    // ------------------------------------------------------------------ n lookups (menu, deep links)

    /** The handle this channel's posts are on the web under, or null when the preview is off. */
    fun webHandle(groupId: String): String? = settings(groupId)?.takeIf { it.webActive }?.handle

    /** Fast, no network: n if this device already knows it. */
    fun knownN(groupId: String, messageId: String): Int? {
        val handle = webHandle(groupId) ?: return null
        return outbox.nFor(handle, messageId) ?: readerN[handle]?.get(messageId)
    }

    /**
     * n of [messageId] in this channel, or null when it is not on the web (sent before the
     * preview was on, not a publishable post, or deleted from the web).
     */
    suspend fun resolveN(groupId: String, messageId: String): Int? = withContext(Dispatchers.IO) {
        val handle = webHandle(groupId) ?: return@withContext null
        knownN(groupId, messageId)?.let { return@withContext it }
        if (iAmAdmin(groupId) && syncAdminMap(handle)) outbox.nFor(handle, messageId)?.let { return@withContext it }
        val msg = findMessage(groupId, messageId) ?: return@withContext null
        // Reader: walk the public feed back to the message's time (bounded).
        var before: Int? = null
        repeat(6) {
            val o = client.feed(handle, before, 50) as? Outcome.Ok ?: return@withContext null
            val (page, next) = V2ChannelWebClient.parseFeed(o.json)
            learn(groupId, handle, page)
            readerN[handle]?.get(messageId)?.let { return@withContext it }
            val oldest = page.minOfOrNull { it.createdAt } ?: return@withContext null
            if (next == null || oldest < msg.timestamp - ChannelWebMatch.TEXT_WINDOW_MS) return@withContext null
            before = next
        }
        null
    }

    /** Deep link: which local message is post [n]? null when unknown (the chat opens anyway). */
    suspend fun messageIdForN(groupId: String, n: Int): String? = withContext(Dispatchers.IO) {
        val handle = webHandle(groupId) ?: settings(groupId)?.handle ?: return@withContext null
        outbox.postIdFor(handle, n)?.let { id -> if (findMessage(groupId, id) != null) return@withContext id }
        readerN[handle]?.entries?.firstOrNull { it.value == n }?.key?.let { return@withContext it }
        val o = client.post(handle, n) as? Outcome.Ok ?: return@withContext null
        val p = o.json.optJSONObject("post")?.let(V2ChannelWebClient::parsePublicPost) ?: return@withContext null
        learn(groupId, handle, listOf(p))
        readerN[handle]?.entries?.firstOrNull { it.value == n }?.key
    }

    private fun learn(groupId: String, handle: String, web: List<V2ChannelWebClient.Companion.PublicPost>) {
        val g = groupManager.getGroupById(groupId)
        val admins = g?.adminPublicKeys.orEmpty().ifEmpty { listOfNotNull(g?.creatorPublicKey) }
        val local = localPosts(groupId)
            .filter { !it.isDeleted && (admins.isEmpty() || admins.any { a -> a == it.senderPublicKey }) }
            .map { ChannelWebMatch.Local(it.id, it.timestamp, it.displayContent) }
        val m = ChannelWebMatch.match(local, web)
        if (m.isNotEmpty()) readerN.getOrPut(handle) { ConcurrentHashMap() }.putAll(m)
    }

    companion object {
        private const val TAG = "ChannelWeb"
        private const val IMAGE_LABEL = "groups/channel_web_image"
        private const val MAX_SLEEP_MS = 6 * 3600 * 1000L
        private const val ADMIN_SYNC_EVERY_MS = 24 * 3600 * 1000L
    }
}
