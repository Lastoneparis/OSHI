package com.oshi.messenger.network.location

/**
 * One live location share that THIS device is sending.
 *
 * __MULTI_LIVE_SHARE_2026_09_22__ (iOS `LocationSharingManager.ActiveLiveShare`).
 */
data class ActiveLiveShare(
    val conversationId: String,
    val sessionId: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long
) {
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long =
        ((endTimeMillis - now) / 1000).coerceAtLeast(0)
}

/**
 * The bookkeeping behind several simultaneous live shares, kept free of any
 * `android.*` so it is unit-testable on the JVM.
 *
 * Before: ONE share. Starting a second one with another contact silently
 * stopped the first — that contact's bubble flipped to "ended" without anyone
 * asking. Now each conversation owns at most one share, and shares in other
 * conversations keep running. Order is start order, most recent LAST.
 */
class LiveShareSessions {
    private val shares = mutableListOf<ActiveLiveShare>()

    @Synchronized
    fun snapshot(): List<ActiveLiveShare> = shares.toList()

    @Synchronized
    fun forConversation(conversationId: String): ActiveLiveShare? =
        shares.firstOrNull { it.conversationId == conversationId }

    @Synchronized
    fun bySession(sessionId: String): ActiveLiveShare? =
        shares.firstOrNull { it.sessionId == sessionId }

    /** Adds [share]. The caller must have stopped any share of the same conversation first. */
    @Synchronized
    fun add(share: ActiveLiveShare) {
        require(shares.none { it.conversationId == share.conversationId || it.sessionId == share.sessionId }) {
            "a live share for this conversation/session is already running"
        }
        shares.add(share)
    }

    /** Removes and returns the share, or null when it was not running (idempotent stop). */
    @Synchronized
    fun remove(sessionId: String): ActiveLiveShare? {
        val index = shares.indexOfFirst { it.sessionId == sessionId }
        return if (index >= 0) shares.removeAt(index) else null
    }

    /** Pushes the share's end time out by [additionalMillis]; returns the updated share. */
    @Synchronized
    fun extend(sessionId: String, additionalMillis: Long): ActiveLiveShare? {
        val index = shares.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return null
        val updated = shares[index].copy(endTimeMillis = shares[index].endTimeMillis + additionalMillis)
        shares[index] = updated
        return updated
    }

    @Synchronized
    fun expired(now: Long = System.currentTimeMillis()): List<ActiveLiveShare> =
        shares.filter { it.endTimeMillis <= now }

    /** The latest end time across all shares — how long the foreground service must live. */
    @Synchronized
    fun latestEndMillis(): Long? = shares.maxOfOrNull { it.endTimeMillis }
}
