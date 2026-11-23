package com.oshi.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (N10, Anatole 13/11) A message notification goes away when IT is opened or
 * ITS conversation is viewed — not because the app was opened.
 *
 * MainActivity used to `cancelAll()` in onCreate AND onResume, so opening OSHI
 * for one chat silently dropped the banners of every other unread chat.
 */
class NotificationClearedPerConversationTest {

    @Test
    fun `opening or resuming the app does not clear the shade`() {
        val activity = SourceText.read("src/main/java/com/oshi/messenger/ui/MainActivity.kt")
        assertFalse(
            "MainActivity must not cancelAll(): that dismisses banners of unopened conversations",
            activity.contains("cancelAll()")
        )
    }

    @Test
    fun `a 1to1 banner goes when its thread is on screen`() {
        val vm = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/ChatViewModel.kt")
        val body = vm.substringAfter("fun onChatVisible(").substringBefore("\n    fun ")
        assertTrue(body.contains("dismissMessageNotification(publicKey)"))
    }

    @Test
    fun `a group banner goes when the group is read, even if already read`() {
        val gm = SourceText.read("src/main/java/com/oshi/messenger/service/GroupManager.kt")
        val body = gm.substringAfter("fun markGroupMessagesAsRead(groupId: String)")
        val cancel = body.indexOf("MessageBanner.groupNotificationId(groupId)")
        val earlyReturn = body.indexOf("if (!anyUnread) return")
        assertTrue("group banner must be cancelled", cancel >= 0)
        assertTrue("…before the already-read early return", earlyReturn > cancel)
    }

    @Test
    fun `tapping a banner still dismisses that banner`() {
        val fcm = SourceText.read("src/main/java/com/oshi/messenger/service/FCMService.kt")
        val repo = SourceText.read("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
        val group = SourceText.read("src/main/java/com/oshi/messenger/service/GroupManager.kt")
        for (src in listOf(fcm, repo, group)) assertTrue(src.contains(".setAutoCancel(true)"))
    }
}
