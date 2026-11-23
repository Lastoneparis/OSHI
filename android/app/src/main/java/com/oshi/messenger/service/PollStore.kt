package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.local.atrest.SealedStringStore
import com.oshi.messenger.data.model.PollBallot
import com.oshi.messenger.data.model.PollMessage
import com.oshi.messenger.data.model.PollResults
import com.oshi.messenger.data.model.PollVotePayload
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
 * Where the ballots live.
 *
 * A poll's DEFINITION is the message ([PollMessage], parsed from the bubble's
 * own payload every time it draws). Its BALLOTS are here, keyed by poll id,
 * because they arrive later and from other people — writing them back into the
 * message would mean rewriting a message both devices already hold.
 *
 * One ballot per voter per poll. A later ballot replaces an earlier one, and
 * "later" is decided by the ballot's own timestamp, not by arrival order: mesh
 * delivery is not ordered, so a vote relayed through a third phone can land
 * after the correction that superseded it.
 *
 * At rest the file is sealed ([SealedStringStore]): who voted what in a group
 * is user content, and this app stopped writing that class of thing to plain
 * SharedPreferences for a reason.
 *
 * iOS: `OSHI/PollStore.swift`.
 */
object PollStore {

    private const val TAG = "PollStore"
    private const val FILE_NAME = "poll_ballots.json"
    private const val LABEL = "poll ballots"

    /**
     * Storage hygiene, not a product rule: a device that has been in a lot of
     * group polls should not grow this file without bound. Nothing in the
     * product depends on a poll surviving past this point — the bubble still
     * renders, it just shows no tally — so the cap is allowed to forget, and it
     * forgets the least recently touched poll first.
     */
    private const val MAX_POLLS_KEPT = 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** pollId -> voter public key -> their current ballot. */
    private val _ballots = MutableStateFlow<Map<String, Map<String, PollBallot>>>(emptyMap())
    val ballots: StateFlow<Map<String, Map<String, PollBallot>>> = _ballots.asStateFlow()

    private val lastTouched = HashMap<String, Double>()
    private var store: SealedStringStore? = null
    private var saveJob: Job? = null

    /**
     * __ONE_VOTER_IS_ONE_KEY_2026_10_23__ The same identity reaches this device
     * spelled more than one way — base64 and base64url, padded and unpadded —
     * depending on the platform and the transport the envelope came through.
     * Keyed by the raw spelling, one person would hold two ballots and be
     * counted twice, which is a wrong RESULT and not a cosmetic bug.
     *
     * The canonical FORM differs from iOS's (this one drops padding, iOS adds
     * it). That is fine and deliberate: the table is local to one device and is
     * never sent anywhere, so all that matters is that each platform is
     * internally consistent with itself.
     */
    private fun canonical(key: String): String = CallAdmissionPolicy.normalizeKey(key)

    /** Idempotent; safe to call from every view model that shows a poll. */
    @Synchronized
    fun attach(context: Context) {
        if (store != null) return
        store = SealedStringStore.inFiles(context.applicationContext, FILE_NAME, LABEL)
        load()
    }

    // ── Reading ──

    /**
     * Everything the bubble needs, computed against the poll definition it was
     * drawn from. Out-of-range picks are dropped HERE rather than at record
     * time: a ballot can legitimately arrive before the poll message it belongs
     * to (different relay paths), and at that moment there is nothing to
     * validate it against.
     */
    fun results(poll: PollMessage, myPublicKey: String): PollResults {
        val counts = IntArray(poll.options.size)
        val voters = List(poll.options.size) { mutableListOf<String>() }
        var voterCount = 0
        var mine: Set<Int> = emptySet()
        val me = canonical(myPublicKey)

        // Deterministic order so two devices list the same voters in the same
        // order: by ballot time, then by key.
        val ordered = (_ballots.value[poll.id] ?: emptyMap()).entries
            .sortedWith(compareBy({ it.value.atUnix }, { it.key }))

        for ((voter, ballot) in ordered) {
            val unique = ballot.picks.filter { poll.isValidOptionIndex(it) }.distinct().sorted()
            // A single-answer poll keeps the FIRST pick of a ballot that somehow
            // carries several, rather than counting the voter twice.
            val effective = if (poll.allowsMultipleAnswers) unique else unique.take(1)
            if (effective.isEmpty()) continue        // a retraction
            voterCount++
            for (i in effective) {
                counts[i]++
                if (!poll.isAnonymous) voters[i].add(voter)
            }
            if (voter == me) mine = effective.toSet()
        }

        return PollResults(
            counts = counts.toList(),
            voterCount = voterCount,
            votersByOption = if (poll.isAnonymous) List(poll.options.size) { emptyList() }
                             else voters.map { it.toList() },
            myPicks = mine,
        )
    }

    // ── Writing ──

    /**
     * Record a ballot. Returns true when something actually changed, so a
     * caller can avoid redrawing or re-broadcasting for a duplicate — mesh
     * delivers the same vote more than once routinely.
     */
    @Synchronized
    fun record(pollId: String, voter: String, ballot: PollBallot): Boolean {
        if (pollId.isEmpty() || voter.isEmpty()) return false
        val who = canonical(voter)
        val table = HashMap(_ballots.value[pollId] ?: emptyMap())
        val existing = table[who]
        if (existing != null) {
            // Strictly later wins. Equal timestamps keep what we have: two
            // ballots at the same instant from the same voter is a duplicate,
            // and flapping between them would make the bars jitter.
            if (ballot.atUnix <= existing.atUnix) return false
        }
        table[who] = ballot
        val next = HashMap(_ballots.value)
        next[pollId] = table
        lastTouched[pollId] = System.currentTimeMillis() / 1000.0
        enforceCap(next)
        _ballots.value = next
        scheduleSave()
        return true
    }

    fun record(vote: PollVotePayload, from: String): Boolean =
        record(vote.pollId, from, vote.ballot)

    private fun enforceCap(table: HashMap<String, Map<String, PollBallot>>) {
        if (table.size <= MAX_POLLS_KEPT) return
        val victims = lastTouched.entries.sortedBy { it.value }
            .take(table.size - MAX_POLLS_KEPT).map { it.key }
        for (id in victims) {
            table.remove(id)
            lastTouched.remove(id)
        }
    }

    // ── Persistence ──

    /**
     * Coalesced: a group poll can land twenty ballots in a second and each one
     * would otherwise reseal and rewrite the whole file.
     */
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
            val polls = JSONObject()
            for ((pollId, table) in _ballots.value) {
                val byVoter = JSONObject()
                for ((voter, ballot) in table) byVoter.put(voter, ballot.toJson())
                polls.put(pollId, byVoter)
            }
            val touched = JSONObject()
            for ((pollId, at) in lastTouched) touched.put(pollId, at)
            root.put("ballots", polls).put("lastTouched", touched)
            s.write(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not seal the ballots: ${e.javaClass.simpleName}")
        }
    }

    private fun load() {
        val raw = store?.read() ?: return
        try {
            val root = JSONObject(raw)
            val polls = root.optJSONObject("ballots") ?: JSONObject()
            val out = HashMap<String, Map<String, PollBallot>>()
            for (pollId in polls.keys()) {
                val byVoter = polls.optJSONObject(pollId) ?: continue
                val table = HashMap<String, PollBallot>()
                for (voter in byVoter.keys()) {
                    val o = byVoter.optJSONObject(voter) ?: continue
                    table[voter] = PollBallot.fromJson(o)
                }
                out[pollId] = table
            }
            val touched = root.optJSONObject("lastTouched") ?: JSONObject()
            for (pollId in touched.keys()) lastTouched[pollId] = touched.optDouble(pollId, 0.0)
            _ballots.value = out
            Log.d(TAG, "loaded ${out.size} poll(s)")
        } catch (e: Exception) {
            Log.w(TAG, "ballot file unreadable: ${e.javaClass.simpleName}")
        }
    }
}
