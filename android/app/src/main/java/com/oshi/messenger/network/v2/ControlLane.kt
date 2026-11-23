package com.oshi.messenger.network.v2

/**
 * __CONTROL_OVER_V2_2026_10_28__ Which lane a CONTROL payload takes — receipts, typing,
 * and anything else that describes a message rather than being one.
 *
 * ## The defect this closes
 *
 * Control payloads were emitted on the LEGACY lane only: `encryptWithDoubleRatchet` straight
 * into `VPSClient.sendMessage`, with no `V2MessageRouter` branch, while the TEXT they
 * describe has had one for months. A peer that speaks V2 and not the legacy lane therefore
 * received every message and never a delivery tick, a read tick, or a typing ping.
 *
 * That peer exists and it is not hypothetical: the desktop client implements V2 and
 * deliberately does not implement the legacy IPFS path (OSHI-Desktop PARITY.md row 0.23,
 * *"ipfs was the old road"*). Its rows 0.18/0.19 recorded the symptom from the other side —
 * *"RECEIVE-FROM-ANDROID IS STRUCTURALLY DEAD … the codecs here are still right and still
 * byte-tested; there is simply nothing to feed them from an Android peer."* The missing
 * half was on this side.
 *
 * ## Why this is a function and not two `if`s at the call sites
 *
 * Because the ORDER is a correctness property, not a style choice, and a rule that lives in
 * one place can be tested once. See [preferV2].
 */
object ControlLane {

    /** Which lane carried it. For logs, and for the test to assert on. */
    enum class Lane { V2, LEGACY }

    /**
     * Try [v2] first; fall back to [legacy] only if it declines or throws.
     *
     * **The order is not negotiable, and "do both to be safe" is the bug.**
     * `encryptWithDoubleRatchet` ADVANCES THE LEGACY RATCHET. Encrypting for the legacy
     * lane and then also sending over V2 spends a ratchet step on a payload that never goes
     * out, and leaves a permanent gap in the receiver's skipped-key store — the same trap
     * `MessageRepository.sendMessage`'s LoRa branch documents and sidesteps by carrying its
     * envelope forward instead of encrypting twice. So [legacy] must not run at all when
     * [v2] succeeds, which is the property the test actually asserts.
     *
     * A throw from [v2] is a fallback, not a failure: the relay being unreachable is the
     * ordinary offline case, and the legacy lane may still deliver over the mesh.
     *
     * @param onV2Error called with the throwable when [v2] threw, for the caller's log.
     */
    suspend fun preferV2(
        v2: suspend () -> Boolean,
        legacy: suspend () -> Unit,
        onV2Error: (Throwable) -> Unit = {},
    ): Lane {
        val sent = try {
            v2()
        } catch (t: Throwable) {
            onV2Error(t)
            false
        }
        if (sent) return Lane.V2
        legacy()
        return Lane.LEGACY
    }
}
