package com.oshi.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __DEVICE_LINK_AUTHZ_2026_11_20__ */
class DeviceLinkAuthzTest {

    private val now = 1_800_000_000_000L

    @Test fun unsolicitedLinkAcceptIsRefused() {
        assertFalse(DeviceLinkPolicy.isExpectedLinkAccept("attackerKey", null, 0L, now))
        assertFalse(DeviceLinkPolicy.isExpectedLinkAccept("attackerKey", "", now + 1000, now))
    }

    @Test fun acceptFromAnotherKeyThanRequestedIsRefused() {
        assertFalse(DeviceLinkPolicy.isExpectedLinkAccept("attackerKey", "myOtherPhone", now + 1000, now))
    }

    @Test fun expiredRequestIsRefused() {
        assertFalse(DeviceLinkPolicy.isExpectedLinkAccept("myOtherPhone", "myOtherPhone", now - 1, now))
    }

    @Test fun expectedAcceptIsHonoured() {
        assertTrue(DeviceLinkPolicy.isExpectedLinkAccept("myOtherPhone", "myOtherPhone", now + 1000, now))
    }

    @Test fun unlinkOnlyFromLinkedDevice() {
        assertFalse(DeviceLinkPolicy.mayUnlink("stranger", listOf("a", "b")))
        assertTrue(DeviceLinkPolicy.mayUnlink("b", listOf("a", "b")))
    }

    @Test fun linkCodeComparisonNeverMatchesMissingCode() {
        assertFalse(DeviceLinkPolicy.linkCodeMatches("123456", null))
        assertFalse(DeviceLinkPolicy.linkCodeMatches("", ""))
        assertFalse(DeviceLinkPolicy.linkCodeMatches("123456", "123457"))
        assertTrue(DeviceLinkPolicy.linkCodeMatches("123456", "123456"))
    }

    @Test fun linkCodesAreSixDigitsAndVary() {
        val codes = (1..200).map { DeviceLinkPolicy.newLinkCode() }
        codes.forEach { assertEquals(6, it.length); assertTrue(it.all(Char::isDigit)); assertTrue(it.toInt() >= 100000) }
        assertTrue(codes.toSet().size > 150)
    }
}
