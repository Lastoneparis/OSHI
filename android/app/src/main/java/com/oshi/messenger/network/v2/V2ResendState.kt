package com.oshi.messenger.network.v2

import org.json.JSONObject

/**
 * __RESEND_LIST_ANDROID_2026_10_12__ The two ledgers the resend half of the ratchet heal
 * needs, plus the pure decision that gates serving a request. Port of iOS `V2SyncState`
 * (`MessageManager+V2.swift:1084-1194`) and `MessageManager.v2ResendDecision` (`:2433`).
 *
 * WHY THIS FILE EXISTS
 * Deleting the session and re-running X3DH repairs the CHANNEL, not the messages already
 * lost. After [V2RetryBudget.MAX_RETRIES] failed attempts `V2MessageRouter.poll` gives up
 * on an envelope and lets the ack pass, so the relay drops it: the peer is then the only
 * one who still has that message, and the only way to get it back is to NAME it to them.
 * Android had neither ledger, so it never asked and never served — see
 * [RatchetHealSignal] for what that looked like to users.
 *
 * Both stores take a [Store] seam so the counting logic is unit-testable on the JVM with
 * no SharedPreferences, exactly like [V2RetryBudget].
 */
object V2ResendState {

    /** Persistence seam: one JSON document, read and written whole. */
    interface Store {
        fun read(): String?
        fun write(json: String)
    }
}

/**
 * Envelopes we gave up on, per peer: `peer -> (msgId -> envelope timestamp, Unix ms)`.
 *
 * The timestamp is the ENVELOPE's, not the moment we gave up: it is what decides whether
 * re-asking is still worth it, and it is the only date we have for a message we never read.
 */
class V2UnreadableStore(private val store: V2ResendState.Store) {

    private fun load(): LinkedHashMap<String, LinkedHashMap<String, Long>> {
        val raw = store.read() ?: return LinkedHashMap()
        return try {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, LinkedHashMap<String, Long>>(o.length())
            for (peer in o.keys()) {
                val ids = o.optJSONObject(peer) ?: continue
                val m = LinkedHashMap<String, Long>(ids.length())
                for (id in ids.keys()) m[id] = ids.optLong(id, 0L)
                out[peer] = m
            }
            out
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private fun save(m: Map<String, Map<String, Long>>) {
        val o = JSONObject()
        for ((peer, ids) in m) {
            val inner = JSONObject()
            for ((id, ts) in ids) inner.put(id, ts)
            o.put(peer, inner)
        }
        store.write(o.toString())
    }

    /**
     * Record an envelope from [peer] that will never be decrypted. Idempotent.
     *
     * [envelopeTs] is normalised the same way `MessageRepository.normalizeTimestamp` does it,
     * and that is load-bearing, not tidying: an envelope's `ts` reaches us in SECONDS from
     * some senders. Stored raw, a seconds value reads as 1970 against [drain]'s cutoff, every
     * id is judged stale and the list this whole mechanism exists to send is always empty —
     * a silent, total failure. A missing or absurd stamp falls back to now, which errs
     * towards asking rather than towards losing the message.
     */
    fun mark(peer: String, msgId: String, envelopeTs: Long, nowMs: Long = System.currentTimeMillis()) {
        val ts = normalizeMs(envelopeTs, nowMs)
        val m = load()
        val forPeer = m[peer] ?: LinkedHashMap()
        if (forPeer[msgId] == ts) return
        forPeer[msgId] = ts
        if (forPeer.size > PER_PEER_CAP) {
            // Keep the most RECENT: those are the ones whose resend still has value.
            val keep = forPeer.entries.sortedByDescending { it.value }.take(PER_PEER_CAP)
            forPeer.clear()
            keep.forEach { forPeer[it.key] = it.value }
        }
        m[peer] = forPeer
        if (m.size > PEER_CAP) {
            val keep = m.entries.toList().takeLast(PEER_CAP)
            m.clear()
            keep.forEach { m[it.key] = it.value }
        }
        save(m)
    }

    /**
     * The ids to re-ask [peer] for: most RECENT first, capped, and purged of anything past
     * the resend age.
     *
     * Does NOT empty the ledger: an id is forgotten only after an acknowledgement has
     * actually left ([clear]). Draining here would mean a failed send takes the list with
     * it, and the user loses the messages AND the trigger.
     */
    fun drain(peer: String, nowMs: Long): List<String> {
        val forPeer = load()[peer] ?: return emptyList()
        val cutoff = nowMs - RatchetHealSignal.Policy.MAX_RESEND_AGE_MS
        return forPeer.entries
            .filter { it.value >= cutoff }
            .sortedByDescending { it.value }
            .take(RatchetHealSignal.MAX_RESEND_PER_REQUEST)
            .map { it.key }
    }

    /**
     * Forget [msgId] wherever it is. Called where [V2RetryBudget.clear] is called — a
     * message that finally decoded must never be re-asked for — and after a successful
     * acknowledgement.
     */
    fun clear(msgId: String) {
        val m = load()
        var touched = false
        val empties = ArrayList<String>()
        for ((peer, ids) in m) {
            if (ids.remove(msgId) != null) {
                touched = true
                if (ids.isEmpty()) empties.add(peer)
            }
        }
        if (!touched) return
        empties.forEach { m.remove(it) }
        save(m)
    }

    companion object {
        /** Seconds or milliseconds in, milliseconds out. */
        fun normalizeMs(ts: Long, nowMs: Long): Long = when {
            ts <= 0L -> nowMs
            ts > 1_000_000_000_000L -> ts
            else -> ts * 1000
        }

        /** iOS `unreadablePerPeerCap`. */
        const val PER_PEER_CAP = 64

        /** A contact book, not a database — iOS `seenCap`. */
        const val PEER_CAP = 500
    }
}

/**
 * The sender-side brakes: attempts per msgId (persisted — a counter that dies with the
 * process cannot end a cycle that survives it) and a rolling hourly budget per peer.
 */
class V2ResendBudget(private val store: V2ResendState.Store) {

    private fun load(): JSONObject = try {
        JSONObject(store.read() ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    /** Resends already made for [msgId]. */
    fun attempts(msgId: String): Int = load().optJSONObject(ATTEMPTS)?.optInt(msgId, 0) ?: 0

    fun noteResent(msgId: String) {
        val root = load()
        val a = root.optJSONObject(ATTEMPTS) ?: JSONObject()
        a.put(msgId, a.optInt(msgId, 0) + 1)
        // Bounded: SharedPreferences, not a database.
        if (a.length() > ATTEMPTS_CAP) {
            val keys = a.keys().asSequence().toList()
            keys.take(a.length() - ATTEMPTS_CAP).forEach { a.remove(it) }
        }
        root.put(ATTEMPTS, a)
        store.write(root.toString())
    }

    /** Resends left towards [peer] inside the current hour. */
    fun peerBudgetLeft(peer: String, nowMs: Long): Int {
        val max = RatchetHealSignal.Policy.MAX_RESENDS_PER_PEER_PER_HOUR
        val w = load().optJSONObject(WINDOWS)?.optJSONArray(peer) ?: return max
        if (w.length() != 2) return max
        if (nowMs - w.optLong(0) >= 3_600_000L) return max
        return maxOf(0, max - w.optInt(1))
    }

    fun noteSpent(peer: String, nowMs: Long) {
        val root = load()
        val windows = root.optJSONObject(WINDOWS) ?: JSONObject()
        val w = windows.optJSONArray(peer)
        if (w != null && w.length() == 2 && nowMs - w.optLong(0) < 3_600_000L) {
            windows.put(peer, org.json.JSONArray().put(w.optLong(0)).put(w.optInt(1) + 1))
        } else {
            windows.put(peer, org.json.JSONArray().put(nowMs).put(1))
        }
        root.put(WINDOWS, windows)
        store.write(root.toString())
    }

    private companion object {
        const val ATTEMPTS = "a"
        const val WINDOWS = "w"
        const val ATTEMPTS_CAP = 500
    }
}

/** Why a requested id will or will not be re-sent. The reason is logged, never shown. */
enum class V2ResendDecision { SEND, NOTFOUND, NOTMINE, DELETED, MEDIA, AGE, BUDGET }

/**
 * Pure: may we re-send the message a peer named?
 *
 * 🔴 THE OWNERSHIP TEST IS A SECURITY TEST, NOT AN OPTIMISATION. Without it a peer who
 * GUESSES or REPLAYS an id gets a message intended for someone else re-sent to them. The
 * ids travel inside the ciphertext, but they are still a value the peer chooses.
 *
 * Media is refused: the bytes live in the blob store under a reservation that is long gone,
 * and `mediaType` — not the presence of bytes — is the test, because a consumed view-once
 * row has been purged for good.
 */
object V2ResendPolicy {
    fun decide(
        found: Boolean,
        isFromMe: Boolean,
        senderMatchesMe: Boolean,
        recipientMatchesPeer: Boolean,
        isDeletedForEveryone: Boolean,
        hasMedia: Boolean,
        messageAgeMs: Long,
        attemptsSoFar: Int,
        peerBudgetLeft: Int,
    ): V2ResendDecision {
        if (!found) return V2ResendDecision.NOTFOUND
        if (!isFromMe || !senderMatchesMe || !recipientMatchesPeer) return V2ResendDecision.NOTMINE
        if (isDeletedForEveryone) return V2ResendDecision.DELETED
        if (hasMedia) return V2ResendDecision.MEDIA
        if (messageAgeMs > RatchetHealSignal.Policy.MAX_RESEND_AGE_MS) return V2ResendDecision.AGE
        if (!RatchetHealSignal.Policy.mayResend(attemptsSoFar, messageAgeMs, peerBudgetLeft)) {
            return V2ResendDecision.BUDGET
        }
        return V2ResendDecision.SEND
    }
}
