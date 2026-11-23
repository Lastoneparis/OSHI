package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.ReportDeliveryState
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.ui.screens.reportOutcomeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * __BLOCK_MUTE_AUDIT_2026_09_23__ The owner's three safety features, end to end in source:
 * block (no ring, no banner, blocked peer told — also on CALLS now), group mute (the push
 * path compared ids case-sensitively, so an iPhone's upper-case id rang through mute), and
 * report (the toast claimed "sent" before the POST; groups could not be reported at all).
 */
class BlockMuteReportAuditTest {

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, "app/$relative").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        fail("could not locate $relative"); error("unreachable")
    }

    private fun src(path: String) = moduleFile("src/main/java/com/oshi/messenger/$path").readText()

    // ── block ──────────────────────────────────────────────────────────────────

    @Test
    fun `the notice switch is on per the owner's request`() {
        assertTrue(MessageRepository.BLOCKED_NOTICE_ENABLED)
        val repo = src("data/repository/MessageRepository.kt")
        val body = repo.substringAfter("private suspend fun noticeBlockedSender(").take(400)
        assertTrue("the switch must gate the send", body.contains("if (!BLOCKED_NOTICE_ENABLED) return"))
    }

    @Test
    fun `both ring paths answer a blocked caller with the notice, not a ring`() {
        for (path in listOf("service/EnhancedCallManager.kt", "service/CallManager.kt")) {
            val s = src(path)
            val branch = s.substringAfter("Blocked caller — dropping CALL_REQUEST silently").take(400)
            assertTrue("$path: blocked caller not told", branch.contains("MessageRepository.noticeBlockedCaller(signal.senderPublicKey)"))
            assertTrue("$path: blocked caller must still return before ringing", branch.contains("return"))
        }
    }

    @Test
    fun `a received notice draws neither a receipt nor a banner`() {
        val repo = src("data/repository/MessageRepository.kt")
        val tail = repo.substringAfter("requestProfileIfNeeded(senderPublicKey)\n").take(700)
        val gate = tail.indexOf("!= OSHIControlPayload.BLOCKED_NOTICE")
        assertTrue(gate >= 0)
        assertTrue(gate < tail.indexOf("sendDeliveryReceipt(") && gate < tail.indexOf("showMessageNotification("))
        assertEquals(OSHIControlPayload.BLOCKED_NOTICE, OSHIControlPayload.matchedPrefix(OSHIControlPayload.BLOCKED_NOTICE))
    }

    @Test
    fun `a blocked group member is shown but never rings locally`() {
        val gm = src("service/GroupManager.kt")
        val cond = gm.substringAfter("__BLOCKED_MEMBER_BANNER_2026_09_23__").take(600)
        assertTrue(cond.contains("MessageRepository.isSenderBlockedCached(senderPublicKey)"))
    }

    @Test
    fun `blocks synced from another device reach the receive gates without a restart`() {
        assertTrue(src("devsync/AndroidDevSyncStore.kt").contains("MessageRepository.reloadBlockedCache()"))
        val legacy = src("service/MultiDeviceSyncManager.kt")
        assertTrue(legacy.contains("MessageRepository.reloadBlockedCache()"))
        // __BLOCK_SYNC_LWW_2026_09_24__ an absent isBlocked still never unblocks, and a present one
        // only wins over the time the block was made/lifted HERE (not the last import).
        assertTrue("an absent isBlocked must not unblock",
            legacy.contains("val applyBlock = contactJson.has(\"isBlocked\") && incomingTs > localBlockTs"))
        assertTrue(legacy.contains("isBlocked = if (applyBlock) isBlocked else existing.isBlocked"))
    }

    @Test
    fun `the non-suspending cache check is false for an unknown or empty key`() {
        assertFalse(MessageRepository.isSenderBlockedCached(""))
        assertFalse(MessageRepository.isSenderBlockedCached("nobody-blocked-this"))
    }

    // ── group mute ─────────────────────────────────────────────────────────────

    @Test
    fun `the push path compares group ids case-insensitively`() {
        val fcm = src("service/FCMService.kt")
        val fn = fcm.substringAfter("private fun isGroupMuted(groupId: String): Boolean").take(900)
        assertTrue(fn.contains("equals(groupId.trim(), ignoreCase = true)"))
        assertFalse(fn.contains("optString(\"id\") == groupId"))
    }

    // ── report ─────────────────────────────────────────────────────────────────

    @Test
    fun `the report toast says what really happened`() {
        assertEquals(R.string.report_sent_confirmation, reportOutcomeText(ReportDeliveryState.SENT))
        assertEquals(R.string.report_queued_confirmation, reportOutcomeText(ReportDeliveryState.PENDING))
        assertEquals(R.string.report_rejected_confirmation, reportOutcomeText(ReportDeliveryState.REJECTED))
        val chat = src("ui/screens/ChatScreen.kt")
        val submit = chat.substringAfter("ReportContactDialog(\n            alreadyReported = viewModel.hasReportedContact()").take(600)
        assertFalse("a toast before the outcome is a claim, not a result",
            submit.contains("R.string.report_sent_confirmation"))
    }

    @Test
    fun `a group can be reported, with the same privacy contract`() {
        val screen = src("ui/screens/GroupChatScreen.kt")
        assertTrue(screen.contains("R.string.chat_report_group"))
        assertTrue(screen.contains("viewModel.reportGroup(reason, details)"))
        val vm = src("ui/viewmodel/GroupChatViewModel.kt")
        val fn = vm.substringAfter("fun reportGroup(").take(900)
        assertTrue(fn.contains("ReportSubject.GROUP"))
        assertFalse("no message content in a report", fn.contains("_messages"))
    }
}
