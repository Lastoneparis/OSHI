package com.oshi.messenger.mail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __MAIL_UNREAD_REFRESH_2026_11_22__ the mail badge follows the server without the Mail screen. */
class MailUnreadSyncCoreTest {

    private var now = 1_000_000L
    private var statusRoute = true
    private var serverUnread = 0
    private var failNext: Exception? = null
    private var statusCalls = 0
    private var listCalls = 0
    private val published = mutableListOf<Int>()

    private fun core() = MailUnreadSyncCore(
        fetchStatusUnread = {
            statusCalls++
            failNext?.let { failNext = null; throw it }
            if (statusRoute) serverUnread else null
        },
        fetchListUnread = {
            listCalls++
            failNext?.let { failNext = null; throw it }
            serverUnread
        },
        publish = { published += it },
        nowMs = { now },
    )

    @Test fun backoffDoublesFromAMinuteAndCaps() {
        assertEquals(
            listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L),
            (0..5).map { MailUnreadSyncCore.backoffDelayMs(it) },
        )
    }

    @Test fun statusCountIsPublishedWithoutListing() = runBlocking {
        serverUnread = 3
        val c = core()
        assertEquals(MailUnreadSyncCore.Outcome.RAN, c.check(MailUnreadSyncCore.Trigger.LAUNCH))
        assertEquals(listOf(3), published)
        assertEquals(0, listCalls)
    }

    @Test fun missingStatusRouteFallsBackToTheListAndRemembers() = runBlocking {
        statusRoute = false
        serverUnread = 2
        val c = core()
        c.check(MailUnreadSyncCore.Trigger.LAUNCH)
        c.check(MailUnreadSyncCore.Trigger.PUSH)
        assertEquals(1, statusCalls)
        assertEquals(2, listCalls)
        assertEquals(listOf(2, 2), published)
        assertTrue(c.statusRouteMissing)
    }

    @Test fun flightModeFailuresBackOffAndNetworkReturnResets() = runBlocking {
        val c = core()
        failNext = java.io.IOException("offline")
        assertEquals(MailUnreadSyncCore.Outcome.FAILED, c.check(MailUnreadSyncCore.Trigger.FOREGROUND))
        assertEquals(120_000L, c.nextPeriodicDelayMs())
        c.resetBackoff()
        serverUnread = 4
        assertEquals(MailUnreadSyncCore.Outcome.RAN, c.check(MailUnreadSyncCore.Trigger.NETWORK))
        assertEquals(listOf(4), published)
        assertEquals(60_000L, c.nextPeriodicDelayMs())
    }

    @Test fun foregroundIsSpacedButPushIsNot() = runBlocking {
        val c = core()
        c.check(MailUnreadSyncCore.Trigger.LAUNCH)
        now += 2_000
        assertEquals(MailUnreadSyncCore.Outcome.SKIPPED_GATE, c.check(MailUnreadSyncCore.Trigger.FOREGROUND))
        assertEquals(MailUnreadSyncCore.Outcome.RAN, c.check(MailUnreadSyncCore.Trigger.PUSH))
        assertEquals(MailUnreadSyncCore.Outcome.RAN, c.check(MailUnreadSyncCore.Trigger.PERIODIC))
        now += 20_000
        assertEquals(MailUnreadSyncCore.Outcome.RAN, c.check(MailUnreadSyncCore.Trigger.FOREGROUND))
    }

    @Test fun pushDuringACheckWaitsAndRunsAfterIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val c = MailUnreadSyncCore(
            fetchStatusUnread = { if (first) { first = false; gate.await() }; statusCalls++; serverUnread },
            fetchListUnread = { 0 },
            publish = { published += it },
            nowMs = { now },
        )
        serverUnread = 1
        val running = async { c.check(MailUnreadSyncCore.Trigger.LAUNCH) }
        yield()
        serverUnread = 2 // mail lands after the in-flight request read the server
        val push = async { c.check(MailUnreadSyncCore.Trigger.PUSH) }
        yield()
        assertEquals(MailUnreadSyncCore.Outcome.SKIPPED_GATE, c.check(MailUnreadSyncCore.Trigger.FOREGROUND))
        gate.complete(Unit)
        running.await(); push.await()
        assertEquals(2, published.last())
    }

    @Test fun noMailboxPublishesZeroAndQuietsThePeriodicTimer() = runBlocking {
        val c = core()
        failNext = OshiMailClient.MailException("no-mailbox", 404)
        assertEquals(MailUnreadSyncCore.Outcome.NO_MAILBOX, c.check(MailUnreadSyncCore.Trigger.LAUNCH))
        assertEquals(listOf(0), published)
        assertEquals(MailUnreadSyncCore.CAP_MS, c.nextPeriodicDelayMs())
        assertEquals(MailUnreadSyncCore.Outcome.NO_MAILBOX, c.check(MailUnreadSyncCore.Trigger.PERIODIC))
        assertEquals(1, statusCalls)
        serverUnread = 1
        now += 60_000
        assertEquals(MailUnreadSyncCore.Outcome.RAN, c.check(MailUnreadSyncCore.Trigger.FOREGROUND))
        assertFalse(c.noMailbox)
    }
}
