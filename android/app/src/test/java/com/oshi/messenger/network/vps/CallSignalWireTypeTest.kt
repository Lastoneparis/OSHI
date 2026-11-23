package com.oshi.messenger.network.vps

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * __CALL_VIDEO_SIGNAL_2026_09_23__ The envelope `type` Android puts on /signal.
 *
 * The call server's end-of-call logic (summary, callEnd push, relay-token revocation, TTL)
 * and iOS's cross-call guard only know camelCase `callEnd` / `callDeclined`; Android used
 * to send `call_end` / `call_reject`. Receivers route on the decrypted byte 0 either way.
 */
class CallSignalWireTypeTest {

    @Test
    fun `terminal types go out in the server's camelCase`() {
        assertEquals("callEnd", CallSignal.wireTypeOf(CallSignalType.CALL_END))
        assertEquals("callDeclined", CallSignal.wireTypeOf(CallSignalType.CALL_REJECT))
    }

    @Test
    fun `every other type keeps its shipped spelling`() {
        assertEquals("call_request", CallSignal.wireTypeOf(CallSignalType.CALL_REQUEST))
        assertEquals("call_accept", CallSignal.wireTypeOf(CallSignalType.CALL_ACCEPT))
        assertEquals("ice_candidate", CallSignal.wireTypeOf(CallSignalType.ICE_CANDIDATE))
    }

    @Test
    fun `a keyframe request is typed explicitly`() {
        assertEquals("keyframeRequest", CallSignal.WIRE_TYPE_KEYFRAME_REQUEST)
    }

    /** What a SHIPPED Android receiver (1.6.26) does with the new spellings: CALL_REQUEST,
     *  which `resolveSignalType` then resolves from the decrypted byte 0. */
    @Test
    fun `an old receiver reads the new spellings as a type to resolve from the payload`() {
        for (t in listOf("callEnd", "callDeclined", "keyframeRequest")) {
            val parsed = runCatching { CallSignalType.valueOf(t.uppercase()) }.getOrNull()
            assertEquals(null, parsed)
        }
    }
}
