package com.oshi.messenger.service

/**
 * __DEVICE_LINK_AUTHZ_2026_11_20__ Pure decisions for MultiDeviceSyncManager's legacy
 * device-link handshake (no Android, JVM-testable: DeviceLinkAuthzTest).
 */
object DeviceLinkPolicy {
    /**
     * __DEVICE_LINK_AUTHZ_2026_11_20__ Wrong codes tolerated against one pending
     * link code before it is burned. 6 digits = 10^6 codes; without a limit a
     * stranger who can message this phone (any X3DH session) could walk the space
     * in the 5-minute window and get linked, i.e. become a device allowed to write
     * sync data (messages, contact verification/block state, settings).
     */
    const val MAX_LINK_CODE_ATTEMPTS = 5

    /** A 6-digit link code from a CSPRNG (was kotlin.random — predictable). */
    fun newLinkCode(rng: java.security.SecureRandom = java.security.SecureRandom()): String =
        (100000 + rng.nextInt(900000)).toString()

    /** Constant-time code comparison; null/blank pending code never matches. */
    fun linkCodeMatches(offered: String?, pending: String?): Boolean {
        if (offered.isNullOrEmpty() || pending.isNullOrEmpty()) return false
        return java.security.MessageDigest.isEqual(offered.toByteArray(), pending.toByteArray())
    }

    /**
     * __DEVICE_LINK_AUTHZ_2026_11_20__ A `link_accept` is honoured only while THIS
     * device has an outstanding outgoing request to exactly that key. Before, any
     * sender could post `link_accept` and was added as a linked device.
     */
    fun isExpectedLinkAccept(sender: String, pendingTarget: String?, pendingExpiry: Long, now: Long): Boolean =
        !pendingTarget.isNullOrEmpty() && pendingTarget == sender && now <= pendingExpiry

    /** `unlink` may only come from a device that is itself linked. */
    fun mayUnlink(sender: String, linkedKeys: Collection<String>): Boolean = sender in linkedKeys
}
