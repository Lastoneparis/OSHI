package com.oshi.messenger

import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.ThreadOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __THREAD_ORDER_AFTER_RETRY_2026_11_18__ Messages resent after a block / captive
 * portal appear in the order they were WRITTEN, not the order they arrived.
 */
class ThreadOrderAfterRetryTest {

    private fun msg(id: String, ts: Long) =
        Message(id = id, senderPublicKey = "peer", recipientPublicKey = "me", content = id, timestamp = ts, isFromMe = false)

    @Test
    fun parkedMessagesThatArriveLateSitWhereTheyWereWritten() {
        // m1, m2 written during a 10-minute block and parked; m3 written after the
        // path came back went out first. Arrival order at the receiver: m3, m1, m2.
        val arrived = listOf(msg("m3", 3_000), msg("m1", 1_000), msg("m2", 2_000))
        // The thread (newest first, reversed for display) reads m1, m2, m3.
        assertEquals(listOf("m1", "m2", "m3"), ThreadOrder.newestFirst(arrived).reversed().map { it.id })
    }

    @Test
    fun equalDatesKeepTheirOrderAndASortedListIsNotCopied() {
        val rows = listOf(msg("b", 5), msg("a", 5), msg("c", 1))
        assertSame(rows, ThreadOrder.newestFirst(rows))
        assertEquals(listOf("b", "a", "c"), ThreadOrder.newestFirst(listOf(msg("c", 1), msg("b", 5), msg("a", 5))).map { it.id })
    }

    @Test
    fun theReceiverDatesRowsWithTheSendersEnvelopeAndTheThreadSortsByIt() {
        val dao = SourceText.stripComments(SourceText.read("src/main/java/com/oshi/messenger/data/local/AppDatabase.kt"))
        val thread = dao.substring(dao.indexOf("fun getThreadRowsForContact") - 900, dao.indexOf("fun getThreadRowsForContact"))
        assertTrue("1:1 thread ordered by the (sender) date", thread.contains("ORDER BY timestamp DESC"))
        val repo = SourceText.stripComments(SourceText.read("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt"))
        val inbound = repo.substring(repo.indexOf("private suspend fun dispatchV2Inbound("))
        assertTrue("V2 inbound rows carry the envelope date", inbound.contains("timestamp = normalizeTimestamp(tsMs)"))
        // The parked-message retry hands the router the row's own date.
        assertTrue(repo.contains("v2MessageRouter.sendText(recipientPublicKey, v2Bytes, messageId, originalTimestampMs = message.timestamp)"))
        assertTrue("a resend keeps its date", repo.contains("timestamp = resendOf?.timestamp ?: System.currentTimeMillis()"))
    }
}
