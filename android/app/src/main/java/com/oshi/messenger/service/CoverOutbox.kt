package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * __COVER_FAIL_CLOSED_2026_11_18__ (audit finding 1)
 *
 * With "Disguise OSHI traffic" ON, a 1:1 message whose V2 (disguised) send fails
 * used to fall through to the LEGACY relay: a plain `POST /api/queue/<recipient>`
 * plus an IPFS upload, in the clear as far as traffic shape goes — exactly the
 * traffic the user had asked to hide, and silently. It now FAILS CLOSED: the
 * message stays on this device, the bubble says "Waiting for disguised
 * connection", and it is retried over the disguised (V2) path only, with
 * backoff. After [GIVE_UP_AFTER_MS] it is marked "Not delivered" (manual retry,
 * which again goes through the same gate).
 *
 * Retries live in memory (the closure holds the already-encoded V2 payload, so a
 * retry does not spend a second ratchet step). Ids are mirrored to
 * SharedPreferences so a process death cannot leave a bubble "waiting" forever:
 * [recoverAfterRestart] turns survivors into ordinary failed rows.
 */
object CoverOutbox {
    private const val TAG = "CoverOutbox"
    private const val PREFS = "oshi_cover_outbox"
    private const val KEY_IDS = "awaiting_ids"
    /** Backoff between disguised retries; the last value repeats. */
    internal val BACKOFF_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000, 600_000)
    const val GIVE_UP_AFTER_MS = 60 * 60_000L

    private val _awaiting = MutableStateFlow<Set<String>>(emptySet())
    /** Message ids currently parked for the disguised path — read by the chat bubble. */
    val awaiting: StateFlow<Set<String>> = _awaiting.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * __COVER_RETRY_KICK_2026_11_18__ Bumped each time a disguised request SUCCEEDS
     * (the V2 pull, see `V2MessageRouter.poll`). A parked message then retries now
     * instead of sleeping out its backoff — after a 10-minute block the next retry
     * used to be up to 10 minutes away although the path had been back for most of
     * them (netem "block10"). Never closer than [KICK_MIN_SPACING_MS] to the
     * previous attempt, so a kick cannot become a retry storm.
     */
    private val wake = MutableStateFlow(0L)
    internal const val KICK_MIN_SPACING_MS = 5_000L

    /** The disguised path just answered: let parked messages retry now. */
    fun kick() { if (jobs.isNotEmpty()) wake.value = wake.value + 1 }

    /** Sleep [ms], or less if [kick] fires (but at least [KICK_MIN_SPACING_MS]). */
    internal suspend fun backoffOrKick(ms: Long) {
        if (ms <= KICK_MIN_SPACING_MS) { delay(ms); return }
        val seen = wake.value              // a kick at ANY point of the sleep counts
        delay(KICK_MIN_SPACING_MS)
        if (wake.value != seen) return
        kotlinx.coroutines.withTimeoutOrNull(ms - KICK_MIN_SPACING_MS) { wake.first { it != seen } }
    }

    /**
     * __COVER_RESUME_AFTER_RESTART_2026_11_18__ What to do, on a cold start, with a
     * message a previous process had parked: send it again over the disguised path
     * (still within the hour, still a plain text, cover still ON) or show it as
     * "Not delivered". Pure, unit-tested. Before, every survivor became a failed row
     * that only a tap could resend — on Android, where the system kills background
     * apps routinely, that was most messages sent while the network was bad.
     */
    fun resumeAfterRestart(coverModeOn: Boolean, isText: Boolean, ageMs: Long): Boolean =
        coverModeOn && isText && ageMs in 0 until GIVE_UP_AFTER_MS

    /**
     * Pure routing rule, unit-tested: may a 1:1 user message that the disguised
     * path did not deliver go out over the LEGACY internet relay?
     */
    fun legacyRelayAllowed(coverModeOn: Boolean): Boolean = !coverModeOn

    /**
     * Process-wide mirror of the "Disguise OSHI traffic" switch, kept current by
     * `MessageRepository` (DataStore collector). Read on the legacy relay's own
     * door, [com.oshi.messenger.network.vps.VPSClient.sendMessage].
     */
    @Volatile var coverModeOn: Boolean = false

    /**
     * The door rule, unit-tested: with cover ON, NOTHING addressed to one person
     * (a 1:1 message, attachment, read receipt, typing ping, reaction, edit,
     * profile update…) may use the undisguised legacy relay. Group fan-out
     * (`conversationId` = the group's id, or "") is not disguised in any mode
     * and is documented as such (docs/stego/PRIVACY_DISGUISE_SCOPE.md).
     *
     * Found on a device on 2026-11-18: with cover ON, merely typing in a 1:1 chat
     * sent three typing pings through plain `POST /api/queue` + IPFS upload.
     */
    fun legacyRelayAllowedFor(coverModeOn: Boolean, recipient: String, conversationId: String): Boolean =
        !(coverModeOn && conversationId == recipient)

    /** Where parked ids survive a process death. */
    interface IdStore { fun read(): Set<String>; fun write(ids: Set<String>) }

    private fun prefsStore(context: Context) = object : IdStore {
        private val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun read(): Set<String> = p.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
        override fun write(ids: Set<String>) { p.edit().putStringSet(KEY_IDS, ids).apply() }
    }

    /**
     * Park [messageId]. [attempt] re-tries the disguised send and returns true on
     * delivery; [onDelivered] / [onGaveUp] update the row.
     */
    fun park(
        context: Context,
        scope: CoroutineScope,
        messageId: String,
        attempt: suspend () -> Boolean,
        onDelivered: suspend () -> Unit,
        onGaveUp: suspend () -> Unit,
    ): Job = parkWith(prefsStore(context), scope, messageId, attempt, onDelivered, onGaveUp)

    internal fun parkWith(
        store: IdStore,
        scope: CoroutineScope,
        messageId: String,
        attempt: suspend () -> Boolean,
        onDelivered: suspend () -> Unit,
        onGaveUp: suspend () -> Unit,
        clock: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { backoffOrKick(it) },
    ): Job {
        add(store, messageId)
        jobs.remove(messageId)?.cancel()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val started = clock()
            var i = 0
            try {
                while (true) {
                    sleep(BACKOFF_MS[minOf(i, BACKOFF_MS.size - 1)]); i++
                    val ok = try { attempt() } catch (e: Exception) {
                        Log.w(TAG, "disguised retry ${messageId.take(8)} threw: ${e.message}"); false
                    }
                    if (ok) { onDelivered(); Log.d(TAG, "✅ ${messageId.take(8)} delivered on disguised retry #$i"); break }
                    if (clock() - started >= GIVE_UP_AFTER_MS) { onGaveUp(); Log.w(TAG, "⏹ ${messageId.take(8)} gave up after $i disguised retries"); break }
                }
            } finally {
                // Only the CURRENT job for this id cleans up: a re-park cancels the
                // old job, whose finally must not erase the new one's entry.
                val me = coroutineContext[Job]
                if (jobs[messageId] === me) { jobs.remove(messageId); remove(store, messageId) }
            }
        }
        jobs[messageId] = job
        job.start()
        return job
    }

    /** User deleted / manually retried the row: stop the background attempts. */
    fun cancel(context: Context, messageId: String) {
        jobs.remove(messageId)?.cancel()
        remove(prefsStore(context), messageId)
    }

    /** Ids parked by a previous process; the caller marks them failed. */
    fun recoverAfterRestart(context: Context): Set<String> = recoverWith(prefsStore(context))

    @Synchronized internal fun recoverWith(store: IdStore): Set<String> {
        val stale = store.read().filterNot { jobs.containsKey(it) }.toSet()
        if (stale.isNotEmpty()) store.write(store.read() - stale)
        return stale
    }

    @Synchronized private fun add(store: IdStore, id: String) {
        _awaiting.value = _awaiting.value + id
        store.write(store.read() + id)
    }

    @Synchronized private fun remove(store: IdStore, id: String) {
        _awaiting.value = _awaiting.value - id
        store.write(store.read() - id)
    }
}
