package com.oshi.messenger.ui.components

/**
 * __COVER_WAITING_CLOCK_2026_11_18__ What the status glyph of one of MY bubbles says.
 *
 * A message parked for the disguised path (CoverOutbox, cover mode ON) has NOT left
 * this device, yet it used to wear the single "sent" tick — next to a caption saying
 * it was waiting. It now shows a clock, as iOS does (`clock` SF Symbol), and the
 * caption "Waiting for disguised connection". Read / delivered always win: a receipt
 * that arrives while the id is still in the set (the outbox removes it a moment
 * later) must not flash the clock back.
 */
enum class OutgoingTick {
    WAITING, SENT, DELIVERED, READ;

    companion object {
        fun of(isRead: Boolean, isDelivered: Boolean, awaitingDisguisedPath: Boolean): OutgoingTick = when {
            isRead -> READ
            isDelivered -> DELIVERED
            awaitingDisguisedPath -> WAITING
            else -> SENT
        }
    }
}
