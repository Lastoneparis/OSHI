package com.oshi.messenger.service

import com.oshi.messenger.data.local.atrest.AtRestKey
import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.SealedFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk queue of wake pushes that failed to send, retried later.
 *
 * Port of iOS `PushRetryQueue` (`OSHI/PushRetryQueue.swift`). Android's
 * `MessageRepository.pushAfterV2Send` used to log `"⚠️ V2 wake push failed
 * (non-fatal)"` and drop the request: the message itself had been delivered to the
 * relay, but the recipient's phone was never told, so they only saw it the next time
 * they opened the app by hand. One HTTP blip = one missed notification, forever.
 *
 * Three bounds, all of them iOS's, and each one exists to stop a *different* wrong
 * behaviour:
 *
 *  - [MAX_ATTEMPTS] — give up rather than hammer a server that is refusing.
 *  - [BACKOFF_MS] — a per-entry gap, so a drain that runs on a 3 s loop does not
 *    burn all five attempts in fifteen seconds.
 *  - [MAX_AGE_MS] — the important one, and the least obvious. A push carries no
 *    content; it only says "wake up, there is something new". Once the message is
 *    minutes old the push has no job left — the recipient either already has it or
 *    will the moment they open the app. Without this bound `firstQueuedAt` is
 *    written and never read, and a push that failed once fires a banner for a
 *    message that was read an hour ago (`PushRetryQueue.swift:53-64`, the
 *    `__STALE_PUSH_2026_08_18__` note: *"a notification with nothing behind it"*).
 *
 * Takes a plain [File] root and an injectable clock rather than a `Context`, so the
 * whole class is exercisable from a JVM unit test — the same shape as
 * `MediaAttachmentStore` and `V2SendResumeStore`.
 *
 * At rest (2026-09-22): the file holds recipient keys and notification titles/bodies,
 * so it is sealed with [SealedFile] (`OSHISEA1`) under the Keystore-wrapped [AtRestKey].
 * A legacy plaintext queue is still read, and re-written sealed on load.
 */
class PushRetryQueue(
    root: File,
    // Before `nowMs` on purpose: `PushRetryQueue(dir) { clock }` must keep binding the
    // trailing lambda to the clock, not to this (also a fun interface).
    private val keys: AtRestKeyProvider = AtRestKey,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        /** iOS `maxAttempts` (`PushRetryQueue.swift:44`). */
        const val MAX_ATTEMPTS = 5

        /** iOS `backoffSeconds = 30` (`:45`). */
        const val BACKOFF_MS = 30_000L

        /** iOS `maxAgeSeconds = 600` (`:65`). */
        const val MAX_AGE_MS = 600_000L

        /** Bound the file: a long offline stretch must not grow it without limit. */
        const val CAP = 200

        private const val FILE_NAME = "push_retry_queue.json"

        /** [SealedFile] AAD label — binds the ciphertext to this store. */
        internal const val SEAL_LABEL = "push_retry_queue"
    }

    data class Entry(
        val recipientPublicKey: String,
        val type: String,
        val title: String,
        val body: String,
        val data: Map<String, String>,
        val attempts: Int = 0,
        val firstQueuedAtMs: Long = 0L,
        val lastAttemptAtMs: Long? = null,
    ) {
        /**
         * Identity for dedup — iOS `dedupKey` (`PushRetryQueue.swift:39-42`).
         * Same recipient + same messageId means the same push, however many times
         * the send path retried it.
         */
        val dedupKey: String get() = "$recipientPublicKey|$type|${data["messageId"] ?: ""}"
    }

    private val file = File(root, FILE_NAME)
    private val lock = Any()
    private val entries: MutableList<Entry> = mutableListOf()

    /** True while a [drain] is in flight — iOS `isRetrying` (`:66`). */
    @Volatile
    private var draining = false

    init {
        synchronized(lock) {
            entries.addAll(readFromDisk())
            // Migrate a queue left in plaintext by an older build (no-op without a key).
            try { SealedFile.migrate(file, SEAL_LABEL, keys) } catch (_: Throwable) {}
        }
    }

    val pendingCount: Int get() = synchronized(lock) { entries.size }

    /** Snapshot, for tests and diagnostics. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /**
     * Record a push that could not be sent. Idempotent on [Entry.dedupKey]: a
     * message whose push fails twice occupies one slot, not two.
     */
    fun enqueue(
        recipientPublicKey: String,
        type: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ) {
        val entry = Entry(
            recipientPublicKey = recipientPublicKey,
            type = type,
            title = title,
            body = body,
            data = data,
            attempts = 0,
            firstQueuedAtMs = nowMs(),
            lastAttemptAtMs = null,
        )
        synchronized(lock) {
            if (entries.any { it.dedupKey == entry.dedupKey }) return
            entries.add(entry)
            while (entries.size > CAP) entries.removeAt(0)   // oldest first
            writeToDisk(entries)
        }
    }

    /**
     * Try every entry whose backoff has elapsed, dropping the stale and the
     * exhausted. [send] returns true when the push went out.
     *
     * Re-entrant calls are no-ops (iOS `:120-122`), so wiring this into a polling
     * loop is safe. Returns the number of pushes that actually succeeded.
     */
    suspend fun drain(send: suspend (Entry) -> Boolean): Int {
        val due: List<Entry>
        synchronized(lock) {
            if (draining || entries.isEmpty()) return 0
            draining = true
            due = entries.toList()
        }
        var sent = 0
        try {
            val now = nowMs()
            val survivors = mutableListOf<Entry>()
            for (e in due) {
                val last = e.lastAttemptAtMs
                if (last != null && now - last < BACKOFF_MS) {
                    survivors.add(e)                       // not yet due
                    continue
                }
                // Age BEFORE attempts: an old push is dropped even with tries left.
                if (now - e.firstQueuedAtMs > MAX_AGE_MS) continue
                if (e.attempts >= MAX_ATTEMPTS) continue
                val attempted = e.copy(attempts = e.attempts + 1, lastAttemptAtMs = now)
                val ok = try {
                    send(attempted)
                } catch (_: Exception) {
                    false
                }
                if (ok) sent++ else survivors.add(attempted)
            }
            synchronized(lock) {
                // Anything enqueued WHILE this drain ran is not in `due`; keep it.
                val newlyAdded = entries.filter { fresh -> due.none { it.dedupKey == fresh.dedupKey } }
                entries.clear()
                entries.addAll(survivors)
                entries.addAll(newlyAdded)
                writeToDisk(entries)
            }
        } finally {
            draining = false
        }
        return sent
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            try { file.delete() } catch (_: Throwable) {}
        }
    }

    // =====================================================================
    // PERSISTENCE
    // =====================================================================

    private fun readFromDisk(): List<Entry> {
        if (!file.isFile) return emptyList()
        return try {
            val text = SealedFile.readText(file, SEAL_LABEL, keys) ?: return emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val dataObj = o.optJSONObject("data")
                val data = mutableMapOf<String, String>()
                if (dataObj != null) {
                    val keys = dataObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        data[k] = dataObj.optString(k)
                    }
                }
                Entry(
                    recipientPublicKey = o.optString("recipientPublicKey").ifEmpty { return@mapNotNull null },
                    type = o.optString("type", "message"),
                    title = o.optString("title"),
                    body = o.optString("body"),
                    data = data,
                    attempts = o.optInt("attempts", 0),
                    firstQueuedAtMs = o.optLong("firstQueuedAtMs", 0L),
                    lastAttemptAtMs = if (o.has("lastAttemptAtMs") && !o.isNull("lastAttemptAtMs")) {
                        o.optLong("lastAttemptAtMs")
                    } else null,
                )
            }
        } catch (_: Exception) {
            // A corrupt queue is not worth crashing over; it is best-effort state.
            emptyList()
        }
    }

    private fun writeToDisk(list: List<Entry>) {
        try {
            if (list.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (e in list) {
                val dataObj = JSONObject()
                e.data.forEach { (k, v) -> dataObj.put(k, v) }
                arr.put(
                    JSONObject().apply {
                        put("recipientPublicKey", e.recipientPublicKey)
                        put("type", e.type)
                        put("title", e.title)
                        put("body", e.body)
                        put("data", dataObj)
                        put("attempts", e.attempts)
                        put("firstQueuedAtMs", e.firstQueuedAtMs)
                        e.lastAttemptAtMs?.let { put("lastAttemptAtMs", it) }
                    }
                )
            }
            // Temp + fsync + atomic rename (inside SealedFile): a process death
            // mid-write can never leave a truncated file that would read back as an
            // empty queue. Sealed when the at-rest key is available.
            SealedFile.writeAtomic(file, SEAL_LABEL, keys, arr.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {
        }
    }
}
