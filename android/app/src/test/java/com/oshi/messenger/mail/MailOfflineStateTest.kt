package com.oshi.messenger.mail

import com.oshi.messenger.ui.viewmodel.MailRefreshOutcome
import com.oshi.messenger.ui.viewmodel.MailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * __MAIL_OFFLINE_KEEPS_MAILBOX_2026_11_25__ Owner report: "When I don't have network the
 * mail shows a box to input a name even when I have a mailbox."
 */
class MailOfflineStateTest {

    private val account = OshiMailClient.Account(
        address = "hugo@mail.oshi-messenger.com", localpart = "hugo", aliases = emptyList(), aliasLimit = 10,
        storageUsed = 0, storageQuota = 1, messages = 0, sent = 0, sendQuota = 1, sendRemaining = 1,
        driveUsed = 0, driveQuota = 1, driveFiles = 0,
    )

    @Test fun offlineColdStartWithoutCacheIsUnreachableNotTheAddressForm() {
        val s = MailRefreshOutcome.afterFailure(MailViewModel.UiState(), IOException("Unable to resolve host"))
        assertFalse(s.loading)
        assertFalse(s.hasMailbox)
        assertTrue("must show the retry screen, not MailSetup", s.unreachable)
    }

    @Test fun offlineWithRememberedMailboxKeepsShowingIt() {
        val seeded = MailViewModel.UiState(loading = false, hasMailbox = true, account = account)
        val s = MailRefreshOutcome.afterFailure(seeded, IOException("timeout"))
        assertTrue(s.hasMailbox)
        assertEquals(account, s.account)
        assertEquals(MailRefreshOutcome.UNREACHABLE_MESSAGE, s.error)
    }

    @Test fun serverErrorIsNotNoMailbox() {
        val s = MailRefreshOutcome.afterFailure(MailViewModel.UiState(), OshiMailClient.MailException("unavailable", 503))
        assertTrue(s.unreachable)
        assertFalse(s.hasMailbox)
    }

    @Test fun onlyTheServersNoMailboxShowsTheAddressForm() {
        val seeded = MailViewModel.UiState(loading = false, hasMailbox = true, account = account)
        val s = MailRefreshOutcome.afterFailure(seeded, OshiMailClient.MailException("no-mailbox", 404))
        assertFalse(s.hasMailbox)
        assertFalse(s.unreachable)
        assertNull(s.account)
        assertNull(s.error)
    }

    @Test fun clientForgetsTheCacheOnlyOnNoMailbox() {
        val src = File("src/main/java/com/oshi/messenger/mail/OshiMailClient.kt").readText()
        val body = src.substringAfter("suspend fun account(): Account {").substringBefore("fun cachedAccount()")
        assertTrue(body.contains("if (e.code == \"no-mailbox\") forgetCachedAccount()"))
        assertEquals("forgetCachedAccount called once in account()", 1, Regex("forgetCachedAccount\\(\\)").findAll(body).count())
    }
}
