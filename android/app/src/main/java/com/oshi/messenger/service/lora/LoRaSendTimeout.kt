package com.oshi.messenger.service.lora

/**
 * __LORA_SEND_TIMEOUT_2026_11_23__ When an OMP message handed to an OSHI Mesh radio has gone quiet for
 * so long that it must be shown as not delivered.
 *
 * Before this, a message the radio never answered for (Bluetooth dropped mid-handover, the radio
 * rebooted and lost its RAM outbox, a STATUS frame lost) stayed "sent" for ever.
 *
 * Two deadlines, both read from the firmware (`oshi-mesh-firmware`, `src/oshi/OshiOutbox.{h,cpp}`,
 * `src/modules/OshiModule.cpp`), not guessed:
 *
 * 1. **Handover: [noStatusMs] + [perFrameMs] per frame, no status at all.** The firmware emits QUEUED (or
 *    REJECTED/FAILED) the moment the last frame from the phone reassembles (`Outbox::enqueue`), and
 *    keeps STATUS frames for a phone that is momentarily disconnected (`OshiModule::toPhone`). The phone
 *    writes OMP frames 2 s apart (`MeshtasticManager.SEND_SPACING_MS`), so a 44-frame 4 KB text needs
 *    ~90 s just to cross Bluetooth; 3 min on top of that is ample. Silence past it means the frames
 *    never all arrived (link lost, `toRadioChar` gone, radio rebooted).
 *
 * 2. **Progress: [progressMs] after the last QUEUED/SENT.** A QUEUED message can legitimately sit for a
 *    long time: OMP transmits only while `AirTime::isTxAllowedAirUtil()` (half the regional duty cycle,
 *    EU_868 5 % → 2.5 %), measured over a ROLLING 60 x 1-minute window (`airtime.h`,
 *    `utilizationTX[MINUTES_IN_HOUR]`), so a spent budget refills within ~60 min. After the last
 *    fragment (SENT) the radio runs up to 5 repair rounds of 25 s + 3 s per fragment (≤ ~18 min at the
 *    64-fragment maximum), themselves airtime-gated, then ALWAYS ends a unicast as DELIVERED or
 *    IN_CUSTODY (self-custody, `Outbox::onRoundsExhausted`). 120 min covers the hour of airtime plus the
 *    repair rounds with margin for a few messages sharing the budget, and equals
 *    [LoRaAirtimeWait.FORGET_AFTER_MS], so the "waiting for radio airtime" hint and this deadline end
 *    together.
 *
 * IN_CUSTODY, DELIVERED, UPLINKED, FAILED and REJECTED end tracking: custody is a known "held for the
 * destination" state the firmware itself expires after 72 h (`parkedTtlMs`, reported as FAILED), so it
 * never times out here. SENT ends tracking for a BROADCAST, the firmware's terminal state for one
 * (`Outbox::nextFrame` → `finish(e, SENT)`).
 *
 * A timeout is not proof of loss: the caller keeps the msgId → message mapping, so a DELIVERED that
 * arrives later still upgrades the row (`MessageRepository.promoteLoRaAcked`).
 *
 * Pure: the clock is passed in. Thread-safe: every call takes the lock.
 */
class LoRaSendTimeout(
    private val noStatusMs: Long = NO_STATUS_MS,
    private val perFrameMs: Long = PER_FRAME_MS,
    private val progressMs: Long = PROGRESS_MS,
) {
    private class Entry(var deadline: Long, val broadcast: Boolean)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    /** [frames] OMP frames for [messageId] were queued for the radio at [nowMs]. A resend restarts the clock. */
    fun onHandover(messageId: String, frames: Int, broadcast: Boolean, nowMs: Long) = synchronized(lock) {
        entries[messageId] = Entry(nowMs + noStatusMs + perFrameMs * frames.coerceAtLeast(0), broadcast)
        Unit
    }

    /** The radio reported [state] for [messageId]. Ignored for a message this tracker does not hold. */
    fun onStatus(messageId: String, state: OshiMeshProtocol.State, nowMs: Long) = synchronized(lock) {
        val e = entries[messageId] ?: return@synchronized
        when (state) {
            OshiMeshProtocol.State.QUEUED -> e.deadline = nowMs + progressMs
            OshiMeshProtocol.State.SENT ->
                if (e.broadcast) entries.remove(messageId) else e.deadline = nowMs + progressMs
            OshiMeshProtocol.State.DELIVERED,
            OshiMeshProtocol.State.IN_CUSTODY,
            OshiMeshProtocol.State.UPLINKED,
            OshiMeshProtocol.State.FAILED,
            OshiMeshProtocol.State.REJECTED -> entries.remove(messageId)
        }
        Unit
    }

    /** Stop watching [messageId] (it was settled some other way). */
    fun forget(messageId: String) = synchronized(lock) {
        entries.remove(messageId)
        Unit
    }

    fun hasPending(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    fun isTracked(messageId: String): Boolean = synchronized(lock) { entries.containsKey(messageId) }

    /** Messages whose deadline has passed at [nowMs]; each is returned once and no longer tracked. */
    fun expired(nowMs: Long): List<String> = synchronized(lock) {
        val gone = entries.filterValues { nowMs >= it.deadline }.keys.toList()
        gone.forEach { entries.remove(it) }
        gone
    }

    companion object {
        const val NO_STATUS_MS = 3 * 60_000L
        /** Same as `MeshtasticManager.SEND_SPACING_MS`: the phone writes one frame every 2 s. */
        const val PER_FRAME_MS = 2_000L
        const val PROGRESS_MS = 120 * 60_000L
        /** How often the manager checks; a deadline is honoured to within this. */
        const val TICK_MS = 15_000L
    }
}
