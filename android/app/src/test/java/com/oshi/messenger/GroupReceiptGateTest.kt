package com.oshi.messenger

import com.oshi.messenger.network.v2.GroupLegacyCompat
import com.oshi.messenger.network.v2.V2Padding
import com.oshi.messenger.service.GroupReceipts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_RECEIPT_IOS_1043_2026_11_13__ (tester report N11)
 *
 * "On iOS, group messages are not decrypted and show as group receipt."
 *
 * iOS 1.0.43 (git 4befbea0, the build on sale) has no `📑GROUP_RECEIPT📑` in its
 * control catalog, so every receipt Android sent to an iPhone sender landed in
 * the iPhone's 1:1 thread as a raw `📑GROUP_RECEIPT📑{"g":…}` bubble. Android now
 * sends a receipt only to a peer that provably reads the sentinel.
 */
class GroupReceiptGateTest {

    /** Exactly what iOS 1.0.43 publishes in its prekey bundle: `Caps.mine = resendList`. */
    private val ios1043Caps = 1

    private fun reads(
        caps: Int? = null,
        slot: Boolean = false,
        proven: Boolean = false,
        platform: String? = null,
        version: String? = null,
    ) = GroupReceipts.peerReadsReceipts(caps, slot, proven, platform, version)

    @Test
    fun `iOS 1_0_43 never gets a receipt`() {
        assertFalse(reads(caps = ios1043Caps))
        // 1.0.43 sends no appVersion / appPlatform in its profile.
        assertFalse(reads(caps = ios1043Caps, platform = null, version = null))
    }

    @Test
    fun `nothing known means no receipt`() {
        assertFalse(reads())
        assertFalse(reads(caps = 0))
    }

    @Test
    fun `group-v2 alone is not enough - Android 1_6_2x published it long before receipts`() {
        assertFalse(reads(caps = GroupLegacyCompat.CAP_RESEND_LIST or GroupLegacyCompat.CAP_GROUP_V2))
    }

    @Test
    fun `the padding bit proves a build from after receipts`() {
        assertTrue(reads(caps = V2Padding.CAP_PADDING))
        assertTrue(reads(caps = GroupLegacyCompat.MY_CAPS))
    }

    @Test
    fun `this build's own caps qualify, so two current Androids keep exchanging receipts`() {
        assertTrue(
            "MY_CAPS must keep carrying the bit the receipt gate reads",
            (GroupLegacyCompat.MY_CAPS and GroupReceipts.CAP_PADDING_PROXY) != 0
        )
    }

    @Test
    fun `a padded account slot is proof too`() {
        assertTrue(reads(caps = ios1043Caps, slot = true))
    }

    @Test
    fun `a peer that sent us a receipt reads them`() {
        assertTrue(reads(caps = ios1043Caps, proven = true))
    }

    @Test
    fun `Android profile version gate`() {
        assertTrue(reads(platform = "android", version = "1.6.63"))
        assertTrue(reads(platform = "android", version = "1.6.95"))
        assertTrue(reads(platform = "android", version = "1.7.0"))
        assertFalse(reads(platform = "android", version = "1.6.62"))
        assertFalse(reads(platform = "android", version = "1.6.25"))
        assertFalse(reads(platform = "android", version = "garbage"))
    }

    @Test
    fun `an iOS version number is never compared on the Android ladder`() {
        // "1.0.99" < "1.6.63" anyway, but a future iOS "1.7" must not pass by accident:
        // the iOS line is judged by its caps, never by the Android floor.
        assertFalse(reads(platform = "ios", version = "1.7.0"))
        assertFalse(reads(platform = "ios", version = "1.0.43"))
    }

    @Test
    fun `the floor is the first Android release with the sentinel`() {
        assertEquals("1.6.63", GroupReceipts.FIRST_ANDROID_VERSION_READING_RECEIPTS)
    }
}
