package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.local.atrest.SealedStringStore
import com.oshi.messenger.data.model.AliveCheckInEvent
import com.oshi.messenger.data.model.AliveCheckInInterval
import com.oshi.messenger.data.model.AliveCheckInPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * __ALIVE_CHECK_IN_2026_11_04__ Who has promised to check in, how often, and
 * when they last did.
 *
 * One entry per conversation, holding the TWO directions separately — see
 * [com.oshi.messenger.data.model.AliveCheckInEvent] for why a shared interval
 * would be the wrong shape.
 *
 * Sealed at rest ([SealedStringStore]), like [OfflineOnlyStore] and for the
 * same reason: "these are the people I have agreed to prove I am alive to" is
 * about as sensitive as a contact list gets, and plain `SharedPreferences` ride
 * out in cleartext in every backup.
 *
 * No Room table. The state is a few dozen bytes per conversation and the whole
 * map is read on every list frame, which is exactly what a `StateFlow` over a
 * sealed file does best — and it keeps a new schema version out of a release
 * that already carries a feature. iOS: `OSHI/AliveCheckInStore.swift`.
 */
object AliveCheckInStore {

    private const val TAG = "AliveCheckIn"
    private const val FILE_NAME = "alive_check_ins.json"
    private const val LABEL = "alive check-ins"

    /**
     * One conversation, both directions.
     *
     * @param mineEnabled this device's owner has promised to confirm here.
     * @param mineLastConfirmedAtMillis when they last did. Local clock throughout —
     *   this half never travels, so there is no skew to correct.
     * @param theirsLastEventAtUnixSeconds the newest event applied from the peer,
     *   on the PEER's clock, kept in the peer's own units because its only job is
     *   to be compared against the next payload from the same peer
     *   ([AliveCheckInPolicy.accepts]). Converting it to our clock would make
     *   that comparison meaningless.
     * @param theirsLastConfirmedAtMillis what their newest confirmation vouches
     *   for, after [AliveCheckInPolicy.confirmedAt] has clamped their clock.
     * @param notifiedTheirOverdueForDueAtMillis the deadline whose overdue notice
     *   has already gone out, so a second one cannot.
     * @param notifiedMyDueForDueAtMillis the same, for the reminder we post to
     *   our own owner.
     */
    data class Entry(
        val mineEnabled: Boolean = false,
        val mineIntervalSeconds: Long = AliveCheckInInterval.DEFAULT,
        val mineLastConfirmedAtMillis: Long = 0L,
        val theirsEnabled: Boolean = false,
        val theirsIntervalSeconds: Long = 0L,
        val theirsLastConfirmedAtMillis: Long = 0L,
        val theirsLastEventAtUnixSeconds: Double = 0.0,
        val notifiedTheirOverdueForDueAtMillis: Long = 0L,
        val notifiedMyDueForDueAtMillis: Long = 0L,
    ) {
        val isIdle: Boolean get() = !mineEnabled && !theirsEnabled

        fun myStatus(nowMillis: Long): AliveCheckInPolicy.Status = AliveCheckInPolicy.status(
            enabled = mineEnabled,
            lastConfirmedAtMillis = mineLastConfirmedAtMillis,
            intervalSeconds = mineIntervalSeconds,
            nowMillis = nowMillis,
        )

        fun theirStatus(nowMillis: Long): AliveCheckInPolicy.Status = AliveCheckInPolicy.status(
            enabled = theirsEnabled,
            lastConfirmedAtMillis = theirsLastConfirmedAtMillis,
            intervalSeconds = theirsIntervalSeconds,
            nowMillis = nowMillis,
        )

        /**
         * The one state a conversation ROW shows, out of two directions.
         *
         * Overdue wins over confirmed, in either direction. A row is one dot and
         * one line, so it has to answer "is there anything to look at here?",
         * and a silence on either side is the thing to look at. Showing the
         * peer's green while our own deadline has passed would hide the only
         * half the user can still do something about.
         */
        fun rowStatus(nowMillis: Long): AliveCheckInPolicy.Status {
            val mine = myStatus(nowMillis)
            val theirs = theirStatus(nowMillis)
            return when {
                mine == AliveCheckInPolicy.Status.OVERDUE ||
                    theirs == AliveCheckInPolicy.Status.OVERDUE -> AliveCheckInPolicy.Status.OVERDUE
                mine == AliveCheckInPolicy.Status.CONFIRMED ||
                    theirs == AliveCheckInPolicy.Status.CONFIRMED -> AliveCheckInPolicy.Status.CONFIRMED
                else -> AliveCheckInPolicy.Status.NEUTRAL
            }
        }

        /**
         * The deadline the row counts down to: the nearest one still ahead, or
         * the one most recently missed. Zero when there is nothing to count.
         */
        fun rowDueAtMillis(): Long {
            val mine = if (mineEnabled && mineLastConfirmedAtMillis > 0L && mineIntervalSeconds > 0L) {
                AliveCheckInPolicy.dueAtMillis(mineLastConfirmedAtMillis, mineIntervalSeconds)
            } else 0L
            val theirs = if (theirsEnabled && theirsLastConfirmedAtMillis > 0L && theirsIntervalSeconds > 0L) {
                AliveCheckInPolicy.dueAtMillis(theirsLastConfirmedAtMillis, theirsIntervalSeconds)
            } else 0L
            return when {
                mine > 0L && theirs > 0L -> minOf(mine, theirs)
                else -> maxOf(mine, theirs)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    private var store: SealedStringStore? = null
    private var saveJob: Job? = null

    /**
     * Fold the base64 spellings, exactly as [OfflineOnlyStore] does. The same
     * public key arrives base64url from one transport and standard-base64 from
     * another, and two entries for one person would mean a check-in confirmed
     * on one row and overdue on the other.
     */
    private fun canonical(id: String): String = CallAdmissionPolicy.normalizeKey(id)

    @Synchronized
    fun attach(context: Context) {
        if (store != null) return
        store = SealedStringStore.inFiles(context.applicationContext, FILE_NAME, LABEL)
        load()
    }

    // ── Reading ──

    fun entryFor(conversationId: String): Entry? = _entries.value[canonical(conversationId)]

    fun isConfigured(conversationId: String): Boolean =
        entryFor(conversationId)?.isIdle == false

    /** Every conversation with a live schedule in either direction. */
    fun activeConversationIds(): List<String> =
        _entries.value.filterValues { !it.isIdle }.keys.toList()

    // ── Writing: our own half ──

    /**
     * Start, or re-time, our own commitment. Returns the event to send.
     *
     * Enabling counts as a confirmation at the same instant, deliberately: a
     * schedule that starts with no confirmation behind it starts overdue, and
     * the first thing the other side would see is a red dot caused by the user
     * turning the feature ON.
     */
    fun enableMine(conversationId: String, intervalSeconds: Long, nowMillis: Long = System.currentTimeMillis()): AliveCheckInEvent {
        val interval = AliveCheckInInterval.clamp(intervalSeconds)
        mutate(conversationId) {
            it.copy(
                mineEnabled = true,
                mineIntervalSeconds = interval,
                mineLastConfirmedAtMillis = nowMillis,
                // A new deadline has never been announced, whatever we announced
                // for the old one.
                notifiedMyDueForDueAtMillis = 0L,
            )
        }
        return AliveCheckInEvent.create(AliveCheckInEvent.Kind.ENABLED, interval, nowMillis)
    }

    /** "I'm alive." Returns the event to send, or null when nothing is configured. */
    fun confirmMine(conversationId: String, nowMillis: Long = System.currentTimeMillis()): AliveCheckInEvent? {
        val existing = entryFor(conversationId)
        // Pressing the button is itself a commitment: someone who taps "I'm
        // alive" in a conversation with no schedule means to start one, and
        // refusing them a confirmation because of a flag they never saw would
        // read as a dead button.
        val interval = AliveCheckInInterval.clamp(
            existing?.mineIntervalSeconds ?: AliveCheckInInterval.DEFAULT
        )
        mutate(conversationId) {
            it.copy(
                mineEnabled = true,
                mineIntervalSeconds = interval,
                mineLastConfirmedAtMillis = nowMillis,
                notifiedMyDueForDueAtMillis = 0L,
            )
        }
        return AliveCheckInEvent.create(AliveCheckInEvent.Kind.CONFIRMED, interval, nowMillis)
    }

    /** Stop our own commitment. Returns the event to send. */
    fun disableMine(conversationId: String, nowMillis: Long = System.currentTimeMillis()): AliveCheckInEvent {
        mutate(conversationId) {
            it.copy(
                mineEnabled = false,
                mineLastConfirmedAtMillis = 0L,
                notifiedMyDueForDueAtMillis = 0L,
            )
        }
        return AliveCheckInEvent.create(AliveCheckInEvent.Kind.DISABLED, 0L, nowMillis)
    }

    // ── Writing: the peer's half ──

    /**
     * What happened when a peer's event was applied — the caller needs it to
     * decide which notification to post, and only the store can tell it,
     * because only the store knows the state the event replaced.
     */
    data class Applied(
        val accepted: Boolean,
        val event: AliveCheckInEvent,
        val wasOverdue: Boolean,
        val isNowOverdue: Boolean,
    ) {
        /** They answered after their deadline had already passed. */
        val isRecovery: Boolean get() = accepted && wasOverdue && !isNowOverdue && event.isConfirmation
    }

    /**
     * Apply an event from [conversationId]'s peer.
     *
     * [receivedAtMillis] is ours, and it is what bounds their clock — see
     * [AliveCheckInPolicy.confirmedAt].
     */
    fun applyIncoming(
        event: AliveCheckInEvent,
        conversationId: String,
        receivedAtMillis: Long = System.currentTimeMillis(),
    ): Applied {
        val key = canonical(conversationId)
        val before = _entries.value[key] ?: Entry()
        if (!AliveCheckInPolicy.accepts(event.atUnixSeconds, before.theirsLastEventAtUnixSeconds)) {
            Log.d(TAG, "duplicate or out-of-order event from ${key.take(8)} — ignored")
            return Applied(
                accepted = false,
                event = event,
                wasOverdue = before.theirStatus(receivedAtMillis) == AliveCheckInPolicy.Status.OVERDUE,
                isNowOverdue = before.theirStatus(receivedAtMillis) == AliveCheckInPolicy.Status.OVERDUE,
            )
        }

        val wasOverdue = before.theirStatus(receivedAtMillis) == AliveCheckInPolicy.Status.OVERDUE

        val after = when (event.kind) {
            AliveCheckInEvent.Kind.DISABLED -> before.copy(
                theirsEnabled = false,
                theirsIntervalSeconds = 0L,
                theirsLastConfirmedAtMillis = 0L,
                theirsLastEventAtUnixSeconds = event.atUnixSeconds,
                notifiedTheirOverdueForDueAtMillis = 0L,
            )
            else -> {
                val confirmedAt = AliveCheckInPolicy.confirmedAt(
                    declaredAtMillis = (event.atUnixSeconds * 1000.0).toLong(),
                    receivedAtMillis = receivedAtMillis,
                )
                before.copy(
                    theirsEnabled = true,
                    // A confirmation with no interval on it cannot describe a
                    // deadline. Keep the one we already had rather than fall to
                    // zero, which would read as "nothing is configured" and
                    // silently retire the schedule.
                    theirsIntervalSeconds = if (event.intervalSeconds > 0L) {
                        AliveCheckInInterval.clamp(event.intervalSeconds)
                    } else {
                        before.theirsIntervalSeconds
                    },
                    theirsLastConfirmedAtMillis = confirmedAt,
                    theirsLastEventAtUnixSeconds = event.atUnixSeconds,
                    notifiedTheirOverdueForDueAtMillis = 0L,
                )
            }
        }

        _entries.value = _entries.value + (key to after)
        scheduleSave()

        return Applied(
            accepted = true,
            event = event,
            wasOverdue = wasOverdue,
            isNowOverdue = after.theirStatus(receivedAtMillis) == AliveCheckInPolicy.Status.OVERDUE,
        )
    }

    // ── Writing: notification bookkeeping ──

    /**
     * Claim the right to post ONE overdue notice for [dueAtMillis], or learn
     * that it has already gone out.
     *
     * Claim-and-return rather than ask-then-mark: the watcher can be woken by
     * WorkManager and by the app coming to the foreground at the same moment,
     * and two callers that both read "not yet notified" both notify.
     */
    @Synchronized
    fun claimTheirOverdueNotice(conversationId: String, dueAtMillis: Long): Boolean {
        val key = canonical(conversationId)
        val e = _entries.value[key] ?: return false
        if (!AliveCheckInPolicy.shouldNotifyOverdue(dueAtMillis, e.notifiedTheirOverdueForDueAtMillis)) {
            return false
        }
        _entries.value = _entries.value + (key to e.copy(notifiedTheirOverdueForDueAtMillis = dueAtMillis))
        scheduleSave()
        return true
    }

    @Synchronized
    fun claimMyDueReminder(conversationId: String, dueAtMillis: Long): Boolean {
        val key = canonical(conversationId)
        val e = _entries.value[key] ?: return false
        if (!AliveCheckInPolicy.shouldNotifyOverdue(dueAtMillis, e.notifiedMyDueForDueAtMillis)) {
            return false
        }
        _entries.value = _entries.value + (key to e.copy(notifiedMyDueForDueAtMillis = dueAtMillis))
        scheduleSave()
        return true
    }

    // ── Plumbing ──

    private fun mutate(conversationId: String, transform: (Entry) -> Entry) {
        val key = canonical(conversationId)
        val current = _entries.value[key] ?: Entry()
        _entries.value = _entries.value + (key to transform(current))
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            save()
        }
    }

    private fun save() {
        val s = store ?: return
        try {
            val root = JSONObject()
            for ((id, e) in _entries.value) {
                root.put(
                    id,
                    JSONObject()
                        .put("me", e.mineEnabled)
                        .put("miv", e.mineIntervalSeconds)
                        .put("mat", e.mineLastConfirmedAtMillis)
                        .put("te", e.theirsEnabled)
                        .put("tiv", e.theirsIntervalSeconds)
                        .put("tat", e.theirsLastConfirmedAtMillis)
                        .put("tev", e.theirsLastEventAtUnixSeconds)
                        .put("non", e.notifiedTheirOverdueForDueAtMillis)
                        .put("nmd", e.notifiedMyDueForDueAtMillis)
                )
            }
            s.write(JSONObject().put("entries", root).toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not seal the state: ${e.javaClass.simpleName}")
        }
    }

    private fun load() {
        val raw = store?.read() ?: return
        try {
            val root = JSONObject(raw).optJSONObject("entries") ?: return
            val out = HashMap<String, Entry>()
            for (id in root.keys()) {
                val o = root.optJSONObject(id) ?: continue
                out[id] = Entry(
                    mineEnabled = o.optBoolean("me", false),
                    mineIntervalSeconds = o.optLong("miv", AliveCheckInInterval.DEFAULT),
                    mineLastConfirmedAtMillis = o.optLong("mat", 0L),
                    theirsEnabled = o.optBoolean("te", false),
                    theirsIntervalSeconds = o.optLong("tiv", 0L),
                    theirsLastConfirmedAtMillis = o.optLong("tat", 0L),
                    theirsLastEventAtUnixSeconds = o.optDouble("tev", 0.0),
                    notifiedTheirOverdueForDueAtMillis = o.optLong("non", 0L),
                    notifiedMyDueForDueAtMillis = o.optLong("nmd", 0L),
                )
            }
            _entries.value = out
            Log.d(TAG, "loaded ${out.size} conversation(s)")
        } catch (e: Exception) {
            Log.w(TAG, "could not read the state: ${e.javaClass.simpleName}")
        }
    }

    /** Test seam: drops the in-memory map and detaches the sealed file. */
    @Synchronized
    internal fun resetForTests() {
        saveJob?.cancel()
        _entries.value = emptyMap()
        store = null
    }
}
