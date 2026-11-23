package com.oshi.messenger

import com.oshi.messenger.network.encryption.RatchetFailurePolicy
import com.oshi.messenger.network.encryption.ReplayedEnvelopeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * __REPLAY_IS_NOT_A_DESYNC_2026_10_14__
 *
 * Reported 14/10 (Huawei nova 5T ↔ iPad 9): "message delivery works one time in
 * two", in ONE direction, groups and read receipts included.
 *
 * The four receive paths in `MessageRepository` (mesh, cross-platform mesh, VPS,
 * LoRa) answered ANY decrypt failure by resetting the ratchet session and
 * retrying. Right for a genuine desync; catastrophic for a REPLAY — a second copy
 * of an envelope we have already read proves the session is HEALTHY. Resetting it
 * meant the peer's next message could not be read, which reset it again, so only
 * a message that happened to open a fresh chain got through.
 *
 * And a second copy is routine here: the mesh floods and retries, and every
 * `CrossPlatformMesh.sendMessage` mints a FRESH frame id, so the duplicate slips
 * past the dedup-by-id and reaches the ratchet. The ratchet refuses it correctly
 * and rolls its own state back — only this layer did damage.
 *
 * `DoubleRatchet` itself cannot run in a JVM unit test (it needs
 * `android.util.Base64`/`Log` and a `CryptoManager`), which is exactly why the
 * decision lives in a pure object instead of being spelled out at four call sites.
 */
class RatchetReplayPolicyTest {

    /// The property that was missing: a replay must never be able to erase a session.
    @Test
    fun aReplayedEnvelopeNeverJustifiesErasingTheSession() {
        assertFalse(
            "a replay destroyed a HEALTHY session: that is the 14/10 bug",
            RatchetFailurePolicy.provesSessionIsBroken(ReplayedEnvelopeException("Invalid message number")),
        )
        assertFalse(RatchetFailurePolicy.provesSessionIsBroken(ReplayedEnvelopeException("Old message")))
    }

    /// And the opposite must hold with it, or one bug is traded for another:
    /// a real desync still has to repair the session.
    @Test
    fun aRealDesyncStillJustifiesTheReset() {
        assertTrue(RatchetFailurePolicy.provesSessionIsBroken(AEADBadTagException("tag mismatch")))
        assertTrue(RatchetFailurePolicy.provesSessionIsBroken(IllegalStateException("Session ID mismatch")))
        assertTrue(RatchetFailurePolicy.provesSessionIsBroken(IllegalStateException("Session version mismatch")))
        assertTrue(RatchetFailurePolicy.provesSessionIsBroken(IllegalStateException("Too many skipped messages: 9999")))
        assertTrue(RatchetFailurePolicy.provesSessionIsBroken(Exception("DoubleRatchet decryption failed")))
    }

    /// The new type must stay an IllegalStateException: every pre-existing broad
    /// `catch` in the receive paths relied on that, and the fix must not change
    /// what they catch — only what they CONCLUDE.
    @Test
    fun theReplayTypeStaysCatchableAsBefore() {
        assertTrue(ReplayedEnvelopeException("x") is IllegalStateException)
    }
}
