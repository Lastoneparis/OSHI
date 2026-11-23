package com.oshi.messenger

import com.oshi.messenger.service.MessageBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __ONE_BANNER_PER_MESSAGE_2026_09_23__ The FCM banner and the decrypted-message banner
 * must land on the SAME notification id for a conversation (replace, never stack), and a
 * banner posted moments ago must not be re-posted.
 */
class MessageBannerTest {

    @Test
    fun groupIdIsCaseInsensitive() {
        // iOS pushes UUIDs upper-case, Android stores them lower-case.
        assertEquals(
            MessageBanner.groupNotificationId("3F2B9C3E-1111-4222-8333-444455556666"),
            MessageBanner.groupNotificationId(" 3f2b9c3e-1111-4222-8333-444455556666 ")
        )
    }

    @Test
    fun contactIdIgnoresBase64Spelling() {
        assertEquals(
            MessageBanner.contactNotificationId("ab+c/d=="),
            MessageBanner.contactNotificationId("ab-c_d")
        )
    }

    @Test
    fun groupAndContactNeverCollideOnTheSameString() {
        assertNotEquals(MessageBanner.groupNotificationId("X"), MessageBanner.contactNotificationId("X"))
    }

    @Test
    fun recentBannerBlocksASecondPost() {
        val id = MessageBanner.groupNotificationId("g1")
        val now = 1_000_000L
        assertTrue(MessageBanner.postedRecently(id, listOf(id to now - 3_000), now))
        assertFalse(MessageBanner.postedRecently(id, listOf(id to now - MessageBanner.RECENT_WINDOW_MS - 1), now))
        assertFalse(MessageBanner.postedRecently(id, listOf((id + 1) to now), now))
    }
}
