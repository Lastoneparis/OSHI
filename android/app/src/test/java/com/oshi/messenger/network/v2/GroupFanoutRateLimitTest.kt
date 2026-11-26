package com.oshi.messenger.network.v2

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __GROUP_FANOUT_2026_11_24__ A relay 429 is waited out (Retry-After), never turned into the
 * account path or the legacy group path. Reproduced on a local copy of the production relay:
 * the per-sender bucket (60 tokens, 10/s) refuses a member send once it is empty, and the old
 * client gave up after 1 s + 2 s.
 */
class GroupFanoutRateLimitTest {

    private data class A(val status: Int, val retryAfter: String? = null)

    private fun run(vararg answers: A): Triple<A, Int, List<Long>> = runBlocking {
        val sleeps = ArrayList<Long>()
        var i = 0
        val last = RateLimitBackoff.retrying(
            call = { answers[minOf(i++, answers.size - 1)] },
            status = { it.status }, retryAfter = { it.retryAfter }, sleep = { sleeps += it },
        )
        Triple(last, i, sleeps)
    }

    @Test fun honoursRetryAfterSeconds() {
        val (last, calls, sleeps) = run(A(429, "3"), A(429, "1"), A(200))
        assertEquals(200, last.status)
        assertEquals(3, calls)
        assertEquals(listOf(3000L, 1000L), sleeps)
    }

    @Test fun backsOffWhenTheRelayGivesNoRetryAfter() {
        // relays before ServerPatches/group_fanout_2026_11_24 send a bare 429
        val (last, _, sleeps) = run(A(429), A(429), A(429), A(200))
        assertEquals(200, last.status)
        assertEquals(listOf(1000L, 2000L, 4000L), sleeps)
    }

    @Test fun moreThanTheOldTwoRetriesOn429() {
        // the old loop stopped after 3 attempts; a drained bucket needs more
        val (last, calls, _) = run(A(429, "1"), A(429, "1"), A(429, "1"), A(429, "1"), A(200))
        assertEquals(200, last.status)
        assertEquals(5, calls)
    }

    @Test fun capsEachWaitAndTheTotal() {
        assertEquals(RateLimitBackoff.MAX_SINGLE_WAIT_MS, RateLimitBackoff.nextDelayMs(429, "999", 0, 0, 0))
        assertEquals(1000L, RateLimitBackoff.nextDelayMs(429, "0", 0, 0, 0))
        assertNull(RateLimitBackoff.nextDelayMs(429, "30", 3, 0, RateLimitBackoff.MAX_RATE_LIMIT_WAIT_MS - 1000))
        val (last, _, sleeps) = run(A(429, "30"))
        assertEquals(429, last.status)
        assertTrue(sleeps.sum() <= RateLimitBackoff.MAX_RATE_LIMIT_WAIT_MS)
        assertEquals(4, sleeps.size)    // 4 x 30 s = the 120 s budget
    }

    @Test fun serverErrorsKeepThePreviousBudget() {
        val (last, calls, sleeps) = run(A(503), A(503), A(503), A(200))
        assertEquals(503, last.status)
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 2000L), sleeps)
        val (net, _, _) = run(A(-1), A(200))
        assertEquals(200, net.status)
    }

    @Test fun otherClientErrorsAreNotRetried() {
        for (s in listOf(400, 401, 403, 409, 413)) {
            val (last, calls, _) = run(A(s), A(200))
            assertEquals(s, last.status); assertEquals(1, calls)
        }
    }

    @Test fun httpDateRetryAfterFallsBackToBackoff() {
        assertNull(RateLimitBackoff.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(2000L, RateLimitBackoff.nextDelayMs(429, "Wed, 21 Oct 2015 07:28:00 GMT", 1, 0, 0))
    }

    @Test fun rateLimitedMembersNeverGetTheLegacyCopy() {
        val legacy = listOf("A=", "B=", "C=")
        assertEquals(listOf("A=", "C="), GroupLegacyCompat.withoutRateLimited(legacy, setOf("B=")))
        assertEquals(legacy, GroupLegacyCompat.withoutRateLimited(legacy, emptySet()))
    }

    // Source guards: the two fallbacks that used to follow a 429 stay closed.
    private val router = File("src/main/java/com/oshi/messenger/network/v2/V2MessageRouter.kt").readText()

    @Test fun aRateLimitedDeviceBatchDoesNotTakeTheAccountPath() {
        val refused = router.substringAfter("is V2DeviceClient.BatchResult.Refused -> {").substringBefore("Log.w(TAG, \"device batch refused")
        assertTrue(refused.contains("if (r.status == 429)") && refused.contains("return false"))
        assertTrue(router.contains("if (lastSendRateLimited) return false"))
    }

    @Test fun groupFanOutsReportRateLimitedSeparately() {
        assertEquals(2, Regex("""GroupSendResult\(handled, unreachable, rateLimited\)""").findAll(router).count())
        val gm = File("src/main/java/com/oshi/messenger/service/GroupManager.kt").readText()
        assertTrue(gm.contains("compat.withoutRateLimited("))
    }
}
