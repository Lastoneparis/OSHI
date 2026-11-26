package com.oshi.messenger.service

import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.SealedFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * __GROUP_RESEND_2026_11_25__ Members a group message has NOT reached yet, kept on disk until
 * they are reached, leave the group, or 7 days pass.
 *
 * Before this, a group send that reached part of the group (relay 429 past the wait budget, a
 * network drop, a 5xx, a member whose bundle could not be fetched right now, the app killed in
 * the middle of the fan-out) left the other members without the message for good, and the bubble
 * showed the same tick as a message everybody got.
 *
 * Lifecycle of an [Entry]:
 *  * [begin] BEFORE the fan-out starts: every recipient is pending, due in [GroupResendSchedule]
 *    step 0, so a process killed mid-send resumes on the next launch.
 *  * [settle] after each attempt: reached members and members the server says have NO v2
 *    bundle (they follow the "must update" spec, never this queue) leave the list. Empty list ⇒
 *    the entry is gone and the bubble is a normal Sent. Otherwise the next attempt is scheduled
 *    ([GroupResendSchedule.delayAfter], never earlier than a relay `Retry-After`).
 *  * [dropDeparted]: members no longer in the group are not owed the message.
 *  * after [GroupResendSchedule.MAX_AGE_MS] the entry stays (so the bubble keeps saying who is
 *    missing) but is marked [Entry.gaveUp] and no longer retried automatically; "Retry now"
 *    still works.
 *
 * The entry also keeps what is needed to rebuild the WIRE message from the stored row
 * ([Entry.wireContent] for a reply envelope, the bot author, the comment target, the push
 * targets). Every retry goes through the normal send path: re-encrypted, re-signed, the same
 * message id (receivers and the relay dedupe on it). Sealed at rest with [SealedFile] like the
 * blob resume ledger.
 *
 * Pure JVM (a [File] root, an [AtRestKeyProvider]) so it is unit-tested without a device.
 */
class GroupPendingDeliveryStore(
    root: File,
    private val keys: AtRestKeyProvider = AtRestKey,
    /** Identity comparison (base64 vs base64url spellings). */
    private val canon: (String) -> String = { it },
) {
    data class Entry(
        val msgId: String,
        val groupId: String,
        /** Recipients this message is meant for (members with no v2 bundle are taken out). */
        val total: Int,
        val pending: Set<String>,
        val attempts: Int,
        val firstAt: Long,
        val nextAt: Long,
        val gaveUp: Boolean = false,
        val wireContent: String? = null,
        val botAuthor: String? = null,
        val commentOn: String? = null,
        val wakeOnly: Set<String>? = null,
    ) {
        val delivered: Int get() = (total - pending.size).coerceAtLeast(0)
    }

    private val file = File(root, FILE_NAME)
    private val lock = Any()
    private var cache: LinkedHashMap<String, Entry>? = null

    fun begin(
        msgId: String,
        groupId: String,
        recipients: Collection<String>,
        now: Long = System.currentTimeMillis(),
        wireContent: String? = null,
        botAuthor: String? = null,
        commentOn: String? = null,
        wakeOnly: Set<String>? = null,
    ): Entry? = synchronized(lock) {
        val list = recipients.filter { it.isNotBlank() }.distinctBy(canon)
        if (list.isEmpty()) return null
        val all = load()
        val existing = all[msgId]
        val e = existing?.copy(nextAt = now + GroupResendSchedule.delayAfter(0)) ?: Entry(
            msgId = msgId, groupId = groupId, total = list.size, pending = list.toSet(), attempts = 0,
            firstAt = now, nextAt = now + GroupResendSchedule.delayAfter(0),
            wireContent = wireContent, botAuthor = botAuthor, commentOn = commentOn, wakeOnly = wakeOnly,
        )
        all[msgId] = e
        save(all)
        e
    }

    /**
     * One attempt ended. Returns the entry still pending, or null when nobody is left (removed).
     * [retryAfterMs] = the relay's last `Retry-After`, if it said one.
     */
    fun settle(
        msgId: String,
        reached: Collection<String>,
        noBundle: Collection<String>,
        now: Long = System.currentTimeMillis(),
        retryAfterMs: Long? = null,
    ): Entry? = synchronized(lock) {
        val all = load()
        val e = all[msgId] ?: return null
        val gone = (reached + noBundle).map(canon).toSet()
        val noBundleOwed = noBundle.map(canon).toSet().count { k -> e.pending.any { canon(it) == k } }
        val left = e.pending.filterNot { canon(it) in gone }.toSet()
        if (left.isEmpty()) {
            all.remove(msgId); save(all); return null
        }
        val attempts = e.attempts + 1
        val wait = maxOf(GroupResendSchedule.delayAfter(attempts), retryAfterMs ?: 0L)
        val next = e.copy(
            total = (e.total - noBundleOwed).coerceAtLeast(left.size),
            pending = left,
            attempts = attempts,
            nextAt = now + wait,
            gaveUp = now - e.firstAt >= GroupResendSchedule.MAX_AGE_MS,
        )
        all[msgId] = next
        save(all)
        next
    }

    /** Members who left [groupId] are not owed anything. Returns the msgIds that completed. */
    fun dropDeparted(groupId: String, members: Collection<String>): List<String> = synchronized(lock) {
        val keep = members.map(canon).toSet()
        val all = load()
        val done = ArrayList<String>()
        var changed = false
        for ((id, e) in all.entries.toList()) {
            if (!e.groupId.equals(groupId, ignoreCase = true)) continue
            val left = e.pending.filter { canon(it) in keep }.toSet()
            if (left.size == e.pending.size) continue
            changed = true
            if (left.isEmpty()) { all.remove(id); done += id }
            else all[id] = e.copy(pending = left, total = (e.total - (e.pending.size - left.size)).coerceAtLeast(left.size))
        }
        if (changed) save(all)
        done
    }

    /** Entries whose next attempt is due (not given up). */
    fun due(now: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) {
        load().values.filter { !it.gaveUp && it.nextAt <= now }
    }

    /** "Retry now" / connectivity came back: make [msgId] (or every entry) due now. */
    fun makeDue(msgId: String? = null, now: Long = System.currentTimeMillis(), includeGaveUp: Boolean = msgId != null) =
        synchronized(lock) {
            val all = load()
            var changed = false
            for ((id, e) in all.entries.toList()) {
                if (msgId != null && id != msgId) continue
                if (e.gaveUp && !includeGaveUp) continue
                all[id] = e.copy(nextAt = now, gaveUp = false,
                    firstAt = if (e.gaveUp) now else e.firstAt)
                changed = true
            }
            if (changed) save(all)
        }

    fun get(msgId: String): Entry? = synchronized(lock) { load()[msgId] }
    fun all(): Map<String, Entry> = synchronized(lock) { LinkedHashMap(load()) }
    fun remove(msgId: String) = synchronized(lock) { val all = load(); if (all.remove(msgId) != null) save(all) }
    fun removeGroup(groupId: String) = synchronized(lock) {
        val all = load(); if (all.values.removeAll { it.groupId.equals(groupId, ignoreCase = true) }) save(all)
    }

    // ---- persistence -----------------------------------------------------------------------

    private fun load(): LinkedHashMap<String, Entry> {
        cache?.let { return it }
        val out = LinkedHashMap<String, Entry>()
        try {
            val text = if (file.isFile) SealedFile.readText(file, SEAL_LABEL, keys) else null
            if (text != null) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = Entry(
                        msgId = o.getString("msgId"),
                        groupId = o.getString("groupId"),
                        total = o.getInt("total"),
                        pending = o.getJSONArray("pending").strings().toSet(),
                        attempts = o.optInt("attempts", 0),
                        firstAt = o.getLong("firstAt"),
                        nextAt = o.getLong("nextAt"),
                        gaveUp = o.optBoolean("gaveUp", false),
                        wireContent = o.optStringOrNull("wireContent"),
                        botAuthor = o.optStringOrNull("botAuthor"),
                        commentOn = o.optStringOrNull("commentOn"),
                        wakeOnly = o.optJSONArray("wakeOnly")?.strings()?.toSet(),
                    )
                    out[e.msgId] = e
                }
            }
        } catch (_: Throwable) {
            // A corrupt queue degrades to "nothing pending", never to a crash at launch.
        }
        cache = out
        return out
    }

    private fun save(all: LinkedHashMap<String, Entry>) {
        // Bounded: the oldest entries go first if a pathological number piles up.
        while (all.size > MAX_ENTRIES) all.remove(all.keys.first())
        cache = all
        val arr = JSONArray()
        for (e in all.values) {
            arr.put(JSONObject().apply {
                put("msgId", e.msgId); put("groupId", e.groupId); put("total", e.total)
                put("pending", JSONArray(e.pending.toList())); put("attempts", e.attempts)
                put("firstAt", e.firstAt); put("nextAt", e.nextAt)
                if (e.gaveUp) put("gaveUp", true)
                e.wireContent?.let { put("wireContent", it) }
                e.botAuthor?.let { put("botAuthor", it) }
                e.commentOn?.let { put("commentOn", it) }
                e.wakeOnly?.let { put("wakeOnly", JSONArray(it.toList())) }
            })
        }
        try {
            SealedFile.writeAtomic(file, SEAL_LABEL, keys, arr.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {
        }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    companion object {
        internal const val FILE_NAME = "group_pending_delivery.json"
        internal const val SEAL_LABEL = "group_pending_delivery"
        const val MAX_ENTRIES = 500
    }
}

/** __GROUP_RESEND_2026_11_25__ When the next automatic attempt runs. */
object GroupResendSchedule {
    private const val MIN = 60_000L
    /** Stop retrying automatically after a week (the relay keeps envelopes 30 days). */
    const val MAX_AGE_MS = 7L * 24 * 60 * MIN

    /** 0 = right after the first send (only matters if the app dies mid-send); then 1, 5, 30 min, every 3 h. */
    fun delayAfter(attempts: Int): Long = when {
        attempts <= 0 -> MIN
        attempts == 1 -> MIN
        attempts == 2 -> 5 * MIN
        attempts == 3 -> 30 * MIN
        else -> 180 * MIN
    }
}
