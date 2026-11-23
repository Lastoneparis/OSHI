package com.oshi.messenger.service.lora

/**
 * __LORA_AIRTIME_HINT_2026_11_18__ Which messages an OSHI Mesh radio has accepted (OMP STATUS
 * QUEUED) but not yet put on the air for a while.
 *
 * The firmware enforces the regional duty cycle (EU_868: an hourly airtime budget). Once a few long
 * messages have spent it, the radio HOLDS the next one until the budget refills — correct and legal,
 * but the phone only ever sees QUEUED, so the bubble looked stuck for minutes with no explanation
 * (bench 18/11: `statuses=['QUEUED']` for 300 s after three 2,000 B messages in 12 min).
 *
 * Fed by `MeshtasticManager.handleOmp`; read by the chat bubble, which then says
 * "Waiting for radio airtime (legal duty-cycle limit)". Not thread-confined: every call takes the lock.
 */
class LoRaAirtimeWait(
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val forgetAfterMs: Long = FORGET_AFTER_MS,
) {
    private val lock = Any()
    private val queuedAt = HashMap<String, Long>()

    /** The radio said QUEUED for [messageId]. The first QUEUED counts; repeats do not reset the clock. */
    fun onQueued(messageId: String, nowMs: Long) = synchronized(lock) {
        queuedAt.putIfAbsent(messageId, nowMs)
        Unit
    }

    /** Any later state (SENT, DELIVERED, IN_CUSTODY, FAILED, REJECTED, UPLINKED): it is not waiting any more. */
    fun onProgress(messageId: String) = synchronized(lock) {
        queuedAt.remove(messageId)
        Unit
    }

    fun hasPending(): Boolean = synchronized(lock) { queuedAt.isNotEmpty() }

    /**
     * Messages QUEUED for at least the threshold. Entries older than [forgetAfterMs] are dropped:
     * the hourly budget has long refilled by then, and a status we never heard (radio out of range)
     * must not leave a hint on a bubble for ever.
     */
    fun waiting(nowMs: Long): Set<String> = synchronized(lock) {
        queuedAt.entries.removeAll { nowMs - it.value > forgetAfterMs }
        queuedAt.filterValues { nowMs - it >= thresholdMs }.keys.toSet()
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 60_000L
        const val FORGET_AFTER_MS = 2 * 60 * 60_000L
    }
}
