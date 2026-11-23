package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.model.OSHIControlPayload
import org.json.JSONObject

/**
 * __BLOCKED_BY_PEER_2026_09_24__ "This peer told me I am blocked."
 *
 * The caller-side half of "a blocked contact leaves no missed-call trace" (owner decision
 * 2026-09-24). The callee's device answers anything a blocked peer sends with the 🚫BLOCKED🚫
 * notice (`MessageRepository.noticeBlockedSender`, once a day). This device records that here
 * and, from then on, never places a call to that peer: no CALL_REQUEST, no VoIP push, no FCM
 * wake — so the blocked side's iPhone is never woken into the CallKit report PushKit forces
 * (which is what can leave a line in its Recents). The caller sees "Contact unavailable".
 *
 * Lifted by the first sign the peer takes our traffic again: a delivery/read receipt (a device
 * that still blocks us never acks), any stored non-notice 1:1 row from them (text, media, a
 * call summary…), or a call request from them. Mechanics (typing, profile/wallpaper broadcasts,
 * group sync) say nothing. Nothing is told to the server.
 *
 * Same rules and evidence table as iOS (`BlockedByPeerStore` in BlockedContactsManager.swift)
 * and Desktop (`com.oshi.desktop.block.BlockedByPeerStore`).
 */
object BlockedByPeer {
    enum class Evidence { BLOCKED, UNBLOCKED, NONE }

    /** Any inbound 1:1 plaintext from a peer. */
    fun evidence(plaintext: String?): Evidence {
        val pt = plaintext ?: return Evidence.NONE
        if (pt.startsWith(OSHIControlPayload.BLOCKED_NOTICE)) return Evidence.BLOCKED
        if (pt.startsWith(OSHIControlPayload.DELIVERY_RECEIPT) || pt.startsWith(OSHIControlPayload.READ_RECEIPT)) return Evidence.UNBLOCKED
        return if (OSHIControlPayload.suppressesPush(pt)) Evidence.NONE else Evidence.UNBLOCKED
    }

    /**
     * A row about to be STORED in the 1:1 thread (control payloads were consumed before): the
     * notice records; anything else — text, media (null plaintext), a call summary — lifts.
     */
    fun storedEvidence(plaintext: String?): Evidence = when {
        plaintext == null -> Evidence.UNBLOCKED
        plaintext.startsWith(OSHIControlPayload.BLOCKED_NOTICE) -> Evidence.BLOCKED
        OSHIControlPayload.suppressesPush(plaintext) -> Evidence.NONE
        else -> Evidence.UNBLOCKED
    }
}

/** Persisted map normalized key -> when the notice arrived. Pure core: [load]/[persist] are the storage. */
class BlockedByPeerStore(load: () -> String?, private val persist: (String) -> Unit) {
    private val map: MutableMap<String, Long> = decode(load())

    @Synchronized fun isBlockedBy(peer: String): Boolean = map.containsKey(BlockedKeys.normalize(peer))

    fun observeInbound(peer: String, plaintext: String?, now: Long = System.currentTimeMillis()) =
        record(peer, BlockedByPeer.evidence(plaintext), now)

    fun observeStoredInbound(peer: String, plaintext: String?, now: Long = System.currentTimeMillis()) =
        record(peer, BlockedByPeer.storedEvidence(plaintext), now)

    fun clear(peer: String) = record(peer, BlockedByPeer.Evidence.UNBLOCKED, 0L)

    private fun record(peer: String, e: BlockedByPeer.Evidence, now: Long) {
        if (peer.isBlank() || e == BlockedByPeer.Evidence.NONE) return
        val k = BlockedKeys.normalize(peer)
        val snapshot: String
        synchronized(this) {
            val had = map.containsKey(k)
            when (e) {
                BlockedByPeer.Evidence.BLOCKED -> { if (had) return; map[k] = now }
                BlockedByPeer.Evidence.UNBLOCKED -> { if (!had) return; map.remove(k) }
                else -> return
            }
            snapshot = encode(map)
        }
        persist(snapshot)
        runCatching { Log.d("BlockedByPeer", "🚫 blockedByPeer ${if (e == BlockedByPeer.Evidence.BLOCKED) "set" else "cleared"} ${k.take(8)}…") }
    }

    companion object {
        private const val PREFS = "oshi_blocked_by_peer"
        private const val KEY = "peers"
        @Volatile private var instance: BlockedByPeerStore? = null

        fun current(): BlockedByPeerStore? = instance

        fun get(context: Context): BlockedByPeerStore = instance ?: synchronized(this) {
            instance ?: run {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                BlockedByPeerStore({ prefs.getString(KEY, null) }, { prefs.edit().putString(KEY, it).apply() })
            }.also { instance = it }
        }

        fun encode(m: Map<String, Long>): String = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()

        fun decode(json: String?): MutableMap<String, Long> {
            if (json.isNullOrBlank()) return mutableMapOf()
            return try {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
            } catch (_: Exception) { mutableMapOf() }
        }
    }
}

/**
 * __BLOCKED_BY_PEER_2026_09_24__ the distinct failure of `startCall` for a peer that told us it
 * blocks us: nothing was sent. The UI says "Contact unavailable" (`R.string.call_error_unavailable`).
 */
class PeerUnavailableException : Exception("Contact unavailable")

/** What a call screen shows when `startCall` refused before sending anything (null = it did not). */
object CallStartRefusal {
    fun messageRes(failure: Throwable?): Int? = when {
        failure is PeerUnavailableException -> com.oshi.messenger.R.string.call_error_unavailable
        failure?.message == "Contact is blocked" -> com.oshi.messenger.R.string.chat_contact_blocked
        else -> null
    }
}
