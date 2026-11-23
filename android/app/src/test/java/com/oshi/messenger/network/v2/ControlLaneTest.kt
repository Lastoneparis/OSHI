package com.oshi.messenger.network.v2

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receipts and typing take the V2 lane when there is one, and the legacy encrypt does not
 * happen when they do.
 *
 * The second half is the one that matters and it is not a tidiness point.
 * `encryptWithDoubleRatchet` ADVANCES THE LEGACY RATCHET. "Encrypt first, then try V2" —
 * the obvious way to write this — spends a ratchet step on a payload that never leaves and
 * leaves a permanent gap in the receiver's skipped-key store, for every receipt, forever.
 * `MessageRepository.sendMessage`'s LoRa branch documents the same trap and sidesteps it by
 * carrying its envelope forward rather than encrypting twice.
 *
 * Nothing here is mocked, because this module has no mocking library and because the rule
 * is small enough not to need one: [ControlLane.preferV2] takes the two lanes as functions,
 * so the test supplies two counters and asserts which ran.
 */
class ControlLaneTest {

    @Test
    fun `a peer reachable over V2 never touches the legacy lane`() = runBlocking {
        var v2Calls = 0
        var legacyCalls = 0
        val lane = ControlLane.preferV2(
            v2 = { v2Calls++; true },
            legacy = { legacyCalls++ },
        )
        assertEquals(ControlLane.Lane.V2, lane)
        assertEquals(1, v2Calls)
        assertEquals(
            "the legacy encrypt ADVANCES THE RATCHET — running it for a payload that went " +
                "out over V2 burns a step and gaps the peer's skipped-key store",
            0,
            legacyCalls,
        )
    }

    @Test
    fun `a peer with no V2 path falls back exactly once`() = runBlocking {
        var legacyCalls = 0
        val lane = ControlLane.preferV2(v2 = { false }, legacy = { legacyCalls++ })
        assertEquals(ControlLane.Lane.LEGACY, lane)
        assertEquals("the fallback must send once, not zero times and not twice", 1, legacyCalls)
    }

    @Test
    fun `a throwing V2 lane is a fallback, not a lost receipt`() = runBlocking {
        // The relay being unreachable is the ordinary offline case, and the legacy lane may
        // still deliver over the mesh. An exception here must not swallow the payload.
        var legacyCalls = 0
        var reported: Throwable? = null
        val lane = ControlLane.preferV2(
            v2 = { throw java.io.IOException("relay unreachable") },
            legacy = { legacyCalls++ },
            onV2Error = { reported = it },
        )
        assertEquals(ControlLane.Lane.LEGACY, lane)
        assertEquals(1, legacyCalls)
        assertTrue("the caller must be able to log what happened", reported is java.io.IOException)
    }

    @Test
    fun `V2 is attempted before the legacy lane, always`() = runBlocking {
        // Order, not just counts: a version that ran legacy first and V2 second would pass
        // both tests above and still burn the ratchet step this exists to save.
        val order = mutableListOf<String>()
        ControlLane.preferV2(v2 = { order += "v2"; false }, legacy = { order += "legacy" })
        assertEquals(listOf("v2", "legacy"), order)
    }

    @Test
    fun `nothing is reported to the error hook on an ordinary decline`() = runBlocking {
        var reported = false
        ControlLane.preferV2(v2 = { false }, legacy = {}, onV2Error = { reported = true })
        assertFalse("a peer with no V2 bundle is not an error to log", reported)
    }
}
