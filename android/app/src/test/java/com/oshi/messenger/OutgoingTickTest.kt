package com.oshi.messenger

import com.oshi.messenger.ui.components.OutgoingTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __COVER_WAITING_CLOCK_2026_11_18__ a parked cover message shows a clock, never "sent". */
class OutgoingTickTest {
    @Test
    fun aMessageParkedForTheDisguisedPathShowsTheClock() {
        assertEquals(OutgoingTick.WAITING, OutgoingTick.of(isRead = false, isDelivered = false, awaitingDisguisedPath = true))
        assertEquals(OutgoingTick.SENT, OutgoingTick.of(isRead = false, isDelivered = false, awaitingDisguisedPath = false))
    }

    @Test
    fun receiptsWinOverAStaleWaitingFlag() {
        assertEquals(OutgoingTick.DELIVERED, OutgoingTick.of(isRead = false, isDelivered = true, awaitingDisguisedPath = true))
        assertEquals(OutgoingTick.READ, OutgoingTick.of(isRead = true, isDelivered = true, awaitingDisguisedPath = true))
    }

    @Test
    fun bothTickSitesUseTheRuleAndTheWaitingLabel() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertEquals(2, Regex("OutgoingTick\\.of\\(").findAll(src).count())
        assertTrue(src.contains("OutgoingTick.WAITING -> Icons.Default.Schedule"))
        assertTrue(src.contains("OutgoingTick.WAITING -> stringResource(R.string.cover_waiting_bubble)"))
    }
}
