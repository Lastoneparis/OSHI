package com.oshi.messenger.data.model

/**
 * __THREAD_ORDER_AFTER_RETRY_2026_11_18__ Where a message sits in a 1:1 thread.
 *
 * Under a block or a captive portal the sender parks messages (CoverOutbox) and
 * sends them once the path is back — sometimes AFTER a message written later, which
 * went out immediately. The receiver must still show them in the order they were
 * WRITTEN. Two rules, both kept here so they are tested together:
 *
 *  • the sender keeps the original date on every resend (`sendTextMessage(resendOf)`
 *    dates the row `resendOf?.timestamp`); the envelope's `ts` is that date
 *    (`V2MessageRouter.sendText(originalTimestampMs)`, also from the parked retry);
 *  • the receiver dates the row with the SENDER's envelope `ts` (never its own
 *    receive time) and the thread is ordered by that date ([newestFirst], the same
 *    order as `MessageDao.getThreadRowsForContact`'s `ORDER BY timestamp DESC`).
 *
 * Equal dates keep their existing relative order (stable sort), so two rows with
 * the same millisecond never swap between two emissions.
 */
object ThreadOrder {
    fun newestFirst(rows: List<Message>): List<Message> =
        if (isNewestFirst(rows)) rows else rows.sortedByDescending { it.timestamp }

    private fun isNewestFirst(rows: List<Message>): Boolean {
        for (i in 1 until rows.size) if (rows[i - 1].timestamp < rows[i].timestamp) return false
        return true
    }
}
