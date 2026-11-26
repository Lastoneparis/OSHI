package com.oshi.messenger.service.channelweb

import com.oshi.messenger.network.v2.V2ChannelWebClient
import com.oshi.messenger.network.v2.V2ChannelWebClient.Outcome
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * __CHANNEL_WEB_2026_11_26__ The durable outbox of the public channel web preview
 * (PUBLIC_CHANNEL_WEB_SPEC.md §9.2: "Do it from a durable outbox: retry with backoff (re-signed)
 * on network errors / 429 / 5xx").
 *
 * Holds two things, persisted together as one JSON document after every change:
 *  - the jobs still to do (publish/edit, delete, profile, disable), each with its own backoff;
 *  - the `postId → n` map per handle (spec §3.4 "Store n"), plus the media ids of each
 *    published post so an edit of the caption can keep the image without re-uploading it.
 *
 * It never blocks the E2E send: callers only enqueue (memory + one small write), the network
 * happens in [runDue], which the publisher calls from its own background coroutine.
 *
 * Pure (org.json + the client interface), so the JVM tests drive it with a fake server and
 * against the real Node server.
 */
class ChannelWebOutbox(
    private val api: Api,
    private val images: ImageFiles,
    private val persist: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The subset of [V2ChannelWebClient] the outbox uses (tests replace it). */
    interface Api {
        fun enable(handle: String, body: String): Outcome
        fun disable(handle: String): Outcome
        fun upload(handle: String, image: ByteArray): Outcome
        fun publish(handle: String, postId: String, body: String): Outcome
        fun deletePost(handle: String, postId: String): Outcome
    }

    /** Prepared images waiting for upload (sealed files in production). */
    interface ImageFiles {
        fun read(key: String): ByteArray?
        fun delete(key: String)
    }

    /** Asked before each publish: is the preview still on for this group under this handle? */
    var isActive: (groupId: String, handle: String) -> Boolean = { _, _ -> true }

    enum class Kind { PUBLISH, DELETE_POST, PROFILE, DISABLE }

    data class Image(val key: String?, val mediaId: String?)

    data class Job(
        val id: String,
        val kind: Kind,
        val groupId: String,
        val handle: String,
        val postId: String? = null,
        val text: String = "",
        val createdAt: Long = 0L,
        val images: List<Image> = emptyList(),
        /** PROFILE: the §3.1 fields. */
        val title: String? = null,
        val description: String? = null,
        val searchIndexing: Boolean? = null,
        val avatarKey: String? = null,
        val avatarMediaId: String? = null,
        val clearAvatar: Boolean = false,
        val attempts: Int = 0,
        val nextAt: Long = 0L,
        val enqueuedAt: Long = 0L,
        /** Bumped by every coalescing edit, so an in-flight older version never removes it. */
        val rev: Int = 0,
    )

    data class Entry(val n: Int, val mediaIds: List<String>)

    private val lock = Any()
    private val jobs = ArrayList<Job>()
    private val posts = HashMap<String, HashMap<String, Entry>>()
    private val profileSigs = HashMap<String, String>()

    // ------------------------------------------------------------------ queries

    fun pending(): List<Job> = synchronized(lock) { jobs.toList() }

    fun nFor(handle: String, postId: String): Int? = synchronized(lock) {
        posts[handle]?.get(postId)?.n ?: posts[handle]?.entries?.firstOrNull { it.key.equals(postId, true) }?.value?.n
    }

    fun entry(handle: String, postId: String): Entry? = synchronized(lock) { posts[handle]?.get(postId) }

    fun postIdFor(handle: String, n: Int): String? = synchronized(lock) {
        posts[handle]?.entries?.firstOrNull { it.value.n == n }?.key
    }

    fun hasPendingPublish(handle: String, postId: String): Boolean = synchronized(lock) {
        jobs.any { it.kind == Kind.PUBLISH && it.handle == handle && it.postId == postId }
    }

    fun profileSig(handle: String): String? = synchronized(lock) { profileSigs[handle] }

    fun setProfileSig(handle: String, sig: String?) = synchronized(lock) {
        if (sig == null) profileSigs.remove(handle) else profileSigs[handle] = sig
        save()
    }

    /** Next time a job is due, or null when the queue is empty. */
    fun nextDueAt(): Long? = synchronized(lock) { jobs.minOfOrNull { it.nextAt } }

    // ------------------------------------------------------------------ enqueue

    /**
     * A new post, or an edit of a known one (same [postId] ⇒ same `n`, spec §3.4). A pending
     * publish of the same post is replaced in place (it keeps its uploaded media ids when the
     * new version passes none).
     */
    fun enqueuePublish(groupId: String, handle: String, postId: String, text: String, createdAt: Long, images: List<Image>) =
        synchronized(lock) {
            val t = now()
            val i = jobs.indexOfFirst { it.kind == Kind.PUBLISH && it.handle == handle && it.postId == postId }
            if (i >= 0) {
                val old = jobs[i]
                val keep = if (images.isEmpty()) old.images else images
                (old.images - keep.toSet()).forEach { im -> im.key?.let(images()::delete) }
                jobs[i] = old.copy(text = text, images = keep, nextAt = t, attempts = 0, rev = old.rev + 1)
            } else {
                jobs += Job(UUID.randomUUID().toString(), Kind.PUBLISH, groupId, handle, postId, text, createdAt, images,
                    nextAt = t, enqueuedAt = t)
            }
            save()
        }

    private fun images() = images

    /** Delete for everyone (spec §3.5). Drops a pending publish of the same post first. */
    fun enqueueDelete(groupId: String, handle: String, postId: String) = synchronized(lock) {
        val t = now()
        jobs.removeAll { j ->
            val drop = j.kind == Kind.PUBLISH && j.handle == handle && j.postId == postId
            if (drop) j.images.forEach { im -> im.key?.let(images::delete) }
            drop
        }
        if (jobs.none { it.kind == Kind.DELETE_POST && it.handle == handle && it.postId == postId }) {
            jobs += Job(UUID.randomUUID().toString(), Kind.DELETE_POST, groupId, handle, postId, nextAt = t, enqueuedAt = t)
        }
        save()
    }

    /** Channel profile (spec §3.1/§9.5); replaces a pending profile job of the same handle. */
    fun enqueueProfile(
        groupId: String, handle: String, title: String?, description: String?, searchIndexing: Boolean?,
        avatarKey: String?, clearAvatar: Boolean,
    ) = synchronized(lock) {
        val t = now()
        jobs.filter { it.kind == Kind.PROFILE && it.handle == handle }.forEach { old ->
            if (old.avatarKey != null && old.avatarKey != avatarKey) images.delete(old.avatarKey)
        }
        jobs.removeAll { it.kind == Kind.PROFILE && it.handle == handle }
        // A profile must reach the server before the posts queued after it (enable first).
        jobs.add(0, Job(UUID.randomUUID().toString(), Kind.PROFILE, groupId, handle, title = title, description = description,
            searchIndexing = searchIndexing, avatarKey = avatarKey, clearAvatar = clearAvatar, nextAt = t, enqueuedAt = t))
        save()
    }

    /**
     * Turn the web copy of [handle] off (spec §3.2) from the background — used when the group
     * stopped being eligible (made private, no longer a channel, renamed handle, dissolved).
     */
    fun enqueueDisable(groupId: String, handle: String) = synchronized(lock) {
        dropHandleLocked(handle)
        val t = now()
        jobs += Job(UUID.randomUUID().toString(), Kind.DISABLE, groupId, handle, nextAt = t, enqueuedAt = t)
        save()
    }

    /** The preview was turned off by the UI (DELETE already done): forget everything of [handle]. */
    fun forgetHandle(handle: String) = synchronized(lock) {
        dropHandleLocked(handle)
        save()
    }

    private fun dropHandleLocked(handle: String) {
        jobs.removeAll { j ->
            val drop = j.handle == handle
            if (drop) { j.images.forEach { im -> im.key?.let(images::delete) }; j.avatarKey?.let(images::delete) }
            drop
        }
        posts.remove(handle)
        profileSigs.remove(handle)
    }

    /**
     * Spec §3.6: the server's map is the truth for which posts exist and their `n`; local media
     * ids are kept for posts that are on both sides.
     */
    fun mergeAdminMap(handle: String, server: List<Pair<String, Int>>) = synchronized(lock) {
        val local = posts[handle].orEmpty()
        val next = HashMap<String, Entry>()
        for ((id, n) in server) next[id] = Entry(n, local[id]?.takeIf { it.n == n }?.mediaIds.orEmpty())
        posts[handle] = next
        save()
    }

    // ------------------------------------------------------------------ run

    /**
     * Runs every job that is due, one at a time, oldest first (a PROFILE is put in front).
     * Network calls happen outside the lock; results are applied by job id and revision.
     * @return how many jobs were attempted.
     */
    fun runDue(): Int {
        var attempted = 0
        val skip = HashSet<String>()
        while (true) {
            val job = synchronized(lock) {
                val t = now()
                jobs.firstOrNull { it.nextAt <= t && it.id !in skip }
            } ?: break
            skip += job.id
            attempted++
            try {
                runOne(job)
            } catch (e: RuntimeException) {
                reschedule(job, "crash:${e.javaClass.simpleName}")
            }
        }
        return attempted
    }

    private fun runOne(job: Job) {
        when (job.kind) {
            Kind.PUBLISH -> runPublish(job)
            Kind.DELETE_POST -> when (val o = api.deletePost(job.handle, job.postId!!)) {
                is Outcome.Ok, is Outcome.NotFound, Outcome.PreviewOff -> finish(job) { posts[job.handle]?.remove(job.postId) }
                is Outcome.Retry -> reschedule(job, "delete ${o.status} ${o.code}")
                else -> finish(job) { }
            }
            Kind.DISABLE -> when (val o = api.disable(job.handle)) {
                is Outcome.Ok, is Outcome.NotFound -> finish(job) { posts.remove(job.handle) }
                is Outcome.Retry -> reschedule(job, "disable ${o.status} ${o.code}")
                else -> finish(job) { }
            }
            Kind.PROFILE -> runProfile(job)
        }
    }

    private fun runPublish(job0: Job) {
        var job = job0
        if (!isActive(job.groupId, job.handle)) { finish(job) { }; return }
        if (job.createdAt < now() - MAX_AGE_MS) { finish(job) { }; return }   // server would refuse it (§3.4)
        // 1. Upload every image that has no media id yet.
        for ((idx, im) in job.images.withIndex()) {
            if (im.mediaId != null) continue
            val bytes = im.key?.let(images::read)
            if (bytes == null) { job = updateImages(job) { it.filterIndexed { k, _ -> k != idx } } ?: return; return runPublish(job) }
            when (val o = api.upload(job.handle, bytes)) {
                is Outcome.Ok -> {
                    val id = o.json.optString("mediaId").ifEmpty { null }
                    job = updateImages(job) { l -> l.mapIndexed { k, x -> if (k == idx) x.copy(mediaId = id) else x } } ?: return
                    if (id == null) return reschedule(job, "upload without id")
                }
                Outcome.PreviewOff -> return previewOff(job)
                is Outcome.Retry -> if (o.status == 507) {
                    // Server full: publish the text alone rather than never (spec §6).
                    job = updateImages(job) { emptyList() } ?: return
                    return runPublish(job)
                } else return reschedule(job, "upload ${o.status} ${o.code}")
                // 413 / 415 / 400 image errors: this image can never go; keep the rest.
                is Outcome.Rejected, is Outcome.UnknownMedia, is Outcome.NotFound -> {
                    if (o is Outcome.NotFound) return finish(job) { }
                    im.key?.let(images::delete)
                    job = updateImages(job) { it.filterIndexed { k, _ -> k != idx } } ?: return
                    return runPublish(job)
                }
            }
        }
        val mediaIds = job.images.mapNotNull { it.mediaId }
        if (job.text.isBlank() && mediaIds.isEmpty()) { finish(job) { }; return }
        val body = V2ChannelWebClient.buildPublishBody(job.text, mediaIds, job.createdAt)
        when (val o = api.publish(job.handle, job.postId!!, body)) {
            is Outcome.Ok -> {
                val n = o.json.optInt("n", 0)
                finish(job, keepIfRevised = true) {
                    if (n > 0) posts.getOrPut(job.handle) { HashMap() }[job.postId] = Entry(n, mediaIds)
                }
            }
            is Outcome.UnknownMedia -> {
                // Pending image expired (24 h / server restart): forget that id, upload again.
                val gone = o.mediaId
                updateImages(job) { l ->
                    l.mapNotNull { im ->
                        if (gone == null || im.mediaId == gone) { if (im.key != null) im.copy(mediaId = null) else null } else im
                    }
                }?.let { reschedule(it, "unknown-media", soon = true) }
            }
            Outcome.PreviewOff -> previewOff(job)
            is Outcome.Retry -> reschedule(job, "publish ${o.status} ${o.code}")
            is Outcome.NotFound -> finish(job) { }       // handle gone: nothing to publish to
            is Outcome.Rejected -> finish(job) { }       // a bug: retrying cannot fix it
        }
    }

    /** 409: the server copy is off while our settings say on ⇒ enable first, then retry. */
    private fun previewOff(job: Job) {
        if (!isActive(job.groupId, job.handle) || job.attempts >= 3) { finish(job) { }; return }
        synchronized(lock) {
            if (jobs.none { it.kind == Kind.PROFILE && it.handle == job.handle }) {
                val t = now()
                jobs.add(0, Job(UUID.randomUUID().toString(), Kind.PROFILE, job.groupId, job.handle, nextAt = t, enqueuedAt = t))
            }
        }
        reschedule(job, "web-preview-off", soon = true)
    }

    private fun runProfile(job0: Job) {
        var job = job0
        if (!isActive(job.groupId, job.handle)) { finish(job) { }; return }
        val first = V2ChannelWebClient.buildEnableBody(job.title, job.description, job.searchIndexing,
            clearAvatar = job.clearAvatar && job.avatarKey == null)
        when (val o = api.enable(job.handle, first)) {
            is Outcome.Ok -> Unit
            is Outcome.Retry -> return reschedule(job, "enable ${o.status} ${o.code}")
            else -> return finish(job) { }
        }
        val key = job.avatarKey
        if (key != null) {
            val bytes = images.read(key)
            if (bytes != null) {
                val up = api.upload(job.handle, bytes)
                if (up is Outcome.Retry) return reschedule(job, "avatar ${up.status}")
                val id = (up as? Outcome.Ok)?.json?.optString("mediaId")?.ifEmpty { null }
                if (id != null) {
                    job = job.copy(avatarMediaId = id)
                    when (val o = api.enable(job.handle, V2ChannelWebClient.buildEnableBody(null, null, null, avatarMediaId = id))) {
                        is Outcome.Ok -> Unit
                        is Outcome.Retry -> return reschedule(job.copy(avatarMediaId = null), "avatar put ${o.status}")
                        else -> Unit
                    }
                }
            }
        }
        finish(job) { }
    }

    // ------------------------------------------------------------------ helpers

    private fun updateImages(job: Job, f: (List<Image>) -> List<Image>): Job? = synchronized(lock) {
        val i = jobs.indexOfFirst { it.id == job.id }
        if (i < 0) return null
        val cur = jobs[i]
        val next = if (cur.rev == job.rev) cur.copy(images = f(cur.images)) else cur
        jobs[i] = next
        save()
        if (cur.rev == job.rev) next else null
    }

    private fun finish(job: Job, keepIfRevised: Boolean = false, apply: () -> Unit) = synchronized(lock) {
        apply()
        val i = jobs.indexOfFirst { it.id == job.id }
        if (i >= 0) {
            val cur = jobs[i]
            if (keepIfRevised && cur.rev != job.rev) {
                // Edited while this version was in flight: send the new version now, same postId.
                jobs[i] = cur.copy(nextAt = now(), attempts = 0)
            } else {
                jobs.removeAt(i)
                cur.images.forEach { im -> im.key?.let(images::delete) }
                cur.avatarKey?.let(images::delete)
            }
        }
        save()
    }

    private fun reschedule(job: Job, why: String, soon: Boolean = false) = synchronized(lock) {
        val i = jobs.indexOfFirst { it.id == job.id }
        if (i < 0) return@synchronized
        val cur = jobs[i]
        val attempts = cur.attempts + 1
        // Retried long enough: the server would refuse a publish older than 30 days anyway.
        if (now() - cur.enqueuedAt > MAX_AGE_MS) {
            jobs.removeAt(i); cur.images.forEach { im -> im.key?.let(images::delete) }
        } else {
            val delay = if (soon) SOON_MS else backoffMs(attempts)
            jobs[i] = cur.copy(attempts = attempts, nextAt = now() + delay,
                avatarMediaId = if (cur.rev == job.rev) job.avatarMediaId else cur.avatarMediaId)
        }
        lastError = why
        save()
    }

    @Volatile var lastError: String? = null
        private set

    // ------------------------------------------------------------------ persistence

    fun toJson(): String = synchronized(lock) { toJsonLocked() }

    private fun save() = persist(toJsonLocked())

    private fun toJsonLocked(): String = JSONObject().apply {
        put("v", 1)
        put("jobs", JSONArray().apply { jobs.forEach { put(jobJson(it)) } })
        put("posts", JSONObject().apply {
            for ((h, m) in posts) put(h, JSONObject().apply {
                for ((id, e) in m) put(id, JSONObject().put("n", e.n).apply { if (e.mediaIds.isNotEmpty()) put("m", JSONArray(e.mediaIds)) })
            })
        })
        put("profiles", JSONObject(profileSigs as Map<*, *>))
    }.toString()

    /** Replace the in-memory state with a stored document (no-op on garbage). */
    fun load(raw: String?) = synchronized(lock) {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@synchronized
        jobs.clear(); posts.clear(); profileSigs.clear()
        o.optJSONArray("jobs")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::parseJob)?.let(jobs::add) }
        o.optJSONObject("posts")?.let { p ->
            for (h in p.keys()) {
                val m = p.optJSONObject(h) ?: continue
                val map = HashMap<String, Entry>()
                for (id in m.keys()) {
                    val e = m.optJSONObject(id) ?: continue
                    val n = e.optInt("n", 0).takeIf { it > 0 } ?: continue
                    val ids = e.optJSONArray("m")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    map[id] = Entry(n, ids)
                }
                posts[h] = map
            }
        }
        o.optJSONObject("profiles")?.let { p -> for (h in p.keys()) profileSigs[h] = p.optString(h) }
    }

    private fun jobJson(j: Job) = JSONObject().apply {
        put("id", j.id); put("k", j.kind.name); put("g", j.groupId); put("h", j.handle)
        j.postId?.let { put("p", it) }
        if (j.text.isNotEmpty()) put("t", j.text)
        if (j.createdAt != 0L) put("c", j.createdAt)
        if (j.images.isNotEmpty()) put("i", JSONArray().apply {
            j.images.forEach { im -> put(JSONObject().apply { im.key?.let { put("k", it) }; im.mediaId?.let { put("m", it) } }) }
        })
        j.title?.let { put("ti", it) }
        j.description?.let { put("de", it) }
        j.searchIndexing?.let { put("si", it) }
        j.avatarKey?.let { put("ak", it) }
        if (j.clearAvatar) put("ca", true)
        put("a", j.attempts); put("n", j.nextAt); put("e", j.enqueuedAt); put("r", j.rev)
    }

    private fun parseJob(o: JSONObject): Job? {
        val kind = runCatching { Kind.valueOf(o.optString("k")) }.getOrNull() ?: return null
        val images = o.optJSONArray("i")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Image(it.optString("k").ifEmpty { null }, it.optString("m").ifEmpty { null }) }
            }
        }.orEmpty()
        return Job(
            id = o.optString("id").ifEmpty { return null }, kind = kind,
            groupId = o.optString("g"), handle = o.optString("h").ifEmpty { return null },
            postId = o.optString("p").ifEmpty { null }, text = o.optString("t"), createdAt = o.optLong("c", 0L),
            images = images,
            title = if (o.has("ti")) o.optString("ti") else null,
            description = if (o.has("de")) o.optString("de") else null,
            searchIndexing = if (o.has("si")) o.optBoolean("si") else null,
            avatarKey = o.optString("ak").ifEmpty { null }, clearAvatar = o.optBoolean("ca"),
            attempts = o.optInt("a"), nextAt = o.optLong("n"), enqueuedAt = o.optLong("e"), rev = o.optInt("r"),
        )
    }

    companion object {
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000 - 3600_000L
        const val SOON_MS = 2_000L
        private val BACKOFF_S = longArrayOf(5, 15, 30, 60, 120, 300, 600, 1800, 3600, 3 * 3600, 6 * 3600)

        /** Delay before attempt number [attempts] (1-based). Capped at 6 h. */
        fun backoffMs(attempts: Int): Long = BACKOFF_S[(attempts - 1).coerceIn(0, BACKOFF_S.size - 1)] * 1000L
    }
}
