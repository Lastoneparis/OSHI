package com.oshi.messenger.service

import com.oshi.messenger.data.model.GroupSettings

/**
 * __GROUP_COMMENTS_2026_11_21__ Telegram-style comments under channel posts
 * (docs/GROUP_INFO_SETTINGS_SPEC.md §2.12). Pure: no Android, JVM-unit-testable.
 *
 * A comment is an ordinary group message of the same group carrying a top-level
 * `commentOn` = the post's id, whose body is always a `💬REPLY💬` envelope (top-level
 * comment: replyTo = the post; reply: replyTo = the comment). Old builds ignore the key
 * and show a reply bubble.
 */
object GroupComments {
    const val FIELD = "commentOn"
    const val MAX_LENGTH = 4096
    const val MAX_ID_LENGTH = 64
    const val SEND_PER_MINUTE = 20
    const val RECEIVE_PER_MINUTE = 30
    const val ORPHANS_PER_GROUP = 50
    const val ORPHAN_TTL_MS = 24 * 3600 * 1000L
    const val WINDOW_MS = 60_000L

    /** `commentOn` from a decoded GroupMessage JSON, or null (absent, blank, not a string, too long). */
    fun read(json: org.json.JSONObject): String? =
        (json.opt(FIELD) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_LENGTH }

    enum class Outcome {
        /** Not a comment for this receiver: apply the ordinary rules (post policy etc.). */
        ORDINARY,
        /** Store as a comment of the post. */
        ACCEPT,
        /** Post not here yet: keep in the orphan buffer. */
        HOLD,
        DROP,
    }

    data class Decision(val outcome: Outcome, val reason: String)

    /** What the receiver knows about the parent, looked up in OUR stored messages. */
    data class Parent(val authorKey: String, val isComment: Boolean, val isDeleted: Boolean)

    /**
     * §2.12.4. [isTextKind] = body is plain text or a reply envelope, no media / location /
     * check-in / poll. [recentFromSender] = comments accepted from this sender in this group
     * in the last minute (receiver flood counter).
     */
    fun decide(
        commentOn: String?,
        settings: GroupSettings?,
        senderIsAdmin: Boolean,
        senderEvicted: Boolean,
        isTextKind: Boolean,
        parent: Parent?,
        parentAuthorIsAdmin: Boolean,
        recentFromSender: Int,
    ): Decision {
        if (commentOn == null) return Decision(Outcome.ORDINARY, "not-a-comment")
        val s = settings ?: GroupSettings.DEFAULT
        if (s.postPolicy != GroupSettings.PostPolicy.ADMINS) return Decision(Outcome.ORDINARY, "not-channel")
        if (!s.comments || s.dissolved) {
            return if (senderIsAdmin && !s.dissolved) Decision(Outcome.ORDINARY, "comments-off-admin")
            else Decision(Outcome.DROP, "comments-off")
        }
        if (!isTextKind) return Decision(Outcome.DROP, "comment-kind")
        if (senderEvicted) return Decision(Outcome.DROP, "banned")
        if (parent == null) return Decision(Outcome.HOLD, "orphan")
        if (parent.isComment || parent.isDeleted) return Decision(Outcome.DROP, "bad-parent")
        if (!parentAuthorIsAdmin) return Decision(Outcome.DROP, "parent-not-admin")
        if (recentFromSender >= RECEIVE_PER_MINUTE) return Decision(Outcome.DROP, "flood")
        return Decision(Outcome.ACCEPT, "comment")
    }

    /**
     * §2.12.4 rule D: a delete-for-everyone of a COMMENT is accepted from its author or a
     * current admin. Anything else keeps the shipped author-only rule.
     */
    fun mayDelete(actorIsAuthor: Boolean, targetIsComment: Boolean, actorIsAdmin: Boolean): Boolean =
        actorIsAuthor || (targetIsComment && actorIsAdmin)

    /** Sliding one-minute counter per key (sender flood control on both sides). */
    class RateWindow(private val windowMs: Long = WINDOW_MS) {
        private val hits = HashMap<String, ArrayDeque<Long>>()

        @Synchronized
        fun count(key: String, now: Long): Int {
            val q = hits[key] ?: return 0
            while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
            return q.size
        }

        @Synchronized
        fun record(key: String, now: Long) {
            count(key, now)
            hits.getOrPut(key) { ArrayDeque() }.addLast(now)
        }

        /** Records and returns true when fewer than [limit] hits are in the window. */
        @Synchronized
        fun tryAcquire(key: String, limit: Int, now: Long): Boolean {
            if (count(key, now) >= limit) return false
            record(key, now)
            return true
        }
    }

    /** A comment held until its post arrives (§2.12.4 "Orphans"). In memory only. */
    data class Orphan(val groupId: String, val postId: String, val rawContent: String, val sender: String, val heldAt: Long)

    class OrphanBuffer(
        private val perGroup: Int = ORPHANS_PER_GROUP,
        private val ttlMs: Long = ORPHAN_TTL_MS,
    ) {
        private val byGroup = HashMap<String, MutableList<Orphan>>()

        private fun key(g: String) = g.lowercase()

        @Synchronized
        fun hold(o: Orphan) {
            val list = byGroup.getOrPut(key(o.groupId)) { mutableListOf() }
            list.removeAll { o.heldAt - it.heldAt > ttlMs }
            list.add(o)
            while (list.size > perGroup) list.removeAt(0)  // oldest first
        }

        /** Removes and returns the live orphans waiting for [postId]. */
        @Synchronized
        fun release(groupId: String, postId: String, now: Long): List<Orphan> {
            val list = byGroup[key(groupId)] ?: return emptyList()
            list.removeAll { now - it.heldAt > ttlMs }
            val out = list.filter { it.postId.equals(postId, ignoreCase = true) }
            list.removeAll { it.postId.equals(postId, ignoreCase = true) }
            return out
        }

        @Synchronized
        fun size(groupId: String): Int = byGroup[key(groupId)]?.size ?: 0
    }

    /** §2.12.2: a comment is text (or a reply envelope around text). [body] is already unwrapped. */
    fun isTextKind(json: org.json.JSONObject, body: String): Boolean {
        if (json.optString("mediaType", "").isNotEmpty() || json.optString("mediaData", "").isNotEmpty()) return false
        val b = body.trimStart()
        return !(b.startsWith("{") || b.startsWith(GroupManager.LOCATION_PREFIX) ||
            b.startsWith(com.oshi.messenger.data.model.CheckInMessage.MESSAGE_PREFIX) ||
            com.oshi.messenger.data.model.PollMessage.isPollMessage(b))
    }

    /**
     * The whole §2.12.4 decision from what GroupManager holds: the decoded GroupMessage
     * [json], its reply-unwrapped [body], the authenticated [sender], our [stored] messages of
     * the group and our copy's admin / eviction view.
     */
    fun evaluate(
        json: org.json.JSONObject,
        body: String,
        sender: String,
        settings: GroupSettings?,
        stored: List<com.oshi.messenger.data.model.GroupMessage>,
        isAdmin: (String) -> Boolean,
        isEvicted: (String) -> Boolean,
        isComment: (String) -> Boolean,
        recentFromSender: Int,
    ): Pair<Decision, com.oshi.messenger.data.model.GroupMessage?> {
        val commentOn = read(json)
        val parentMsg = commentOn?.let { id -> stored.firstOrNull { it.id.equals(id, ignoreCase = true) } }
        val d = decide(
            commentOn = commentOn,
            settings = settings,
            senderIsAdmin = isAdmin(sender),
            senderEvicted = isEvicted(sender),
            isTextKind = isTextKind(json, body),
            parent = parentMsg?.let { Parent(it.senderPublicKey, isComment(it.id), it.isDeleted) },
            parentAuthorIsAdmin = parentMsg?.let { isAdmin(it.senderPublicKey) } ?: false,
            recentFromSender = recentFromSender,
        )
        return d to parentMsg
    }

    /** Truncate a comment body to [MAX_LENGTH] UTF-16 units without splitting a surrogate pair. */
    fun clamp(text: String): String = GroupSettings.truncate(text, MAX_LENGTH)

    /**
     * Thread view model input: [replyToId] of a comment is shown as a quote only when it names
     * another comment (replyTo == commentOn means "top-level").
     */
    fun quoteTarget(replyToId: String?, postId: String): String? =
        replyToId?.takeIf { !it.equals(postId, ignoreCase = true) }

    /**
     * __INTEROP_2026_11_21__ The rows a thread shows: deleted comments are LEFT OUT (the count
     * already excluded them; drawing them left an empty bubble with ticks after an admin's
     * "delete for everyone", seen in the first Android↔Desktop run), and top-level comments
     * quote nothing.
     */
    fun threadRows(
        comments: List<com.oshi.messenger.data.model.GroupMessage>,
        postId: String?,
    ): List<com.oshi.messenger.data.model.GroupMessage> =
        comments.filterNot { it.isDeleted }
            .map { c -> if (postId != null) c.copy(replyToId = quoteTarget(c.replyToId, postId)) else c }
}
