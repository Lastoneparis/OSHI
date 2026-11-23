package com.oshi.messenger

import android.content.ClipDescription
import com.oshi.messenger.security.SensitiveClipboard
import com.oshi.messenger.security.SensitiveClipboard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CLIPBOARD_HYGIENE_2026_10_01__ The pure half of SensitiveClipboard (no
 * Robolectric in this project, and android.jar's ClipData/PersistableBundle are
 * throwing stubs): the extra key it sets, the 60 s window, the "only clear what
 * is still ours" rule, and the description-only ownership check (no clip read ⇒
 * no Android 12+ "pasted from your clipboard" toast).
 */
class SensitiveClipboardTest {

    private val copiedAt = 1_000L
    private val due = copiedAt + SensitiveClipboard.CLEAR_AFTER_MS
    private val ours = SensitiveClipboard.Pending(clipTimestamp = 42_000L, label = "OSHI", copiedAtWall = copiedAt, clearAtWall = due)

    @Test
    fun `sensitive extra is the platform key`() {
        // Compile-time constant from android.jar (API 33), inlined — so this runs on the JVM.
        assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, SensitiveClipboard.EXTRA_IS_SENSITIVE)
        assertEquals("android.content.extra.IS_SENSITIVE", SensitiveClipboard.EXTRA_IS_SENSITIVE)
    }

    @Test
    fun `clears after sixty seconds`() {
        assertEquals(60_000L, SensitiveClipboard.CLEAR_AFTER_MS)
    }

    @Test
    fun `waits until due`() {
        assertEquals(Decision.WAIT, SensitiveClipboard.decide(copiedAt, due, due - 1, true, clipboardReadable = true))
    }

    @Test
    fun `clears when the clip is still ours`() {
        assertEquals(Decision.CLEAR, SensitiveClipboard.decide(copiedAt, due, due, true, clipboardReadable = true))
        // Process killed in the background, relaunched an hour later: still cleared.
        assertEquals(Decision.CLEAR, SensitiveClipboard.decide(copiedAt, due, due + 3_600_000, true, clipboardReadable = true))
    }

    @Test
    fun `clock set back before the copy counts as due`() {
        assertEquals(Decision.CLEAR, SensitiveClipboard.decide(copiedAt, due, copiedAt - 10_000, true, clipboardReadable = true))
    }

    @Test
    fun `never wipes something the person copied since`() {
        assertEquals(Decision.FORGET, SensitiveClipboard.decide(copiedAt, due, due, false, clipboardReadable = true))
    }

    @Test
    fun `empty clipboard while focused is forgotten`() {
        assertEquals(Decision.FORGET, SensitiveClipboard.decide(copiedAt, due, due, null, clipboardReadable = true))
    }

    @Test
    fun `background read refused waits for focus instead of clearing blindly`() {
        assertEquals(Decision.WAIT, SensitiveClipboard.decide(copiedAt, due, due, null, clipboardReadable = false))
    }

    @Test
    fun `description of our own clip matches`() {
        assertTrue(SensitiveClipboard.descriptionMatches(ours, 42_000L, "OSHI", currentSensitive = true))
    }

    @Test
    fun `a later copy elsewhere does not match - different timestamp`() {
        // e.g. a password copied in another app: the description alone tells us, no clip read (no Android 12+ toast).
        assertFalse(SensitiveClipboard.descriptionMatches(ours, 43_000L, "OSHI", currentSensitive = true))
    }

    @Test
    fun `different label or missing sensitive flag does not match`() {
        assertFalse(SensitiveClipboard.descriptionMatches(ours, 42_000L, "Password", currentSensitive = true))
        assertFalse(SensitiveClipboard.descriptionMatches(ours, 42_000L, null, currentSensitive = true))
        assertFalse(SensitiveClipboard.descriptionMatches(ours, 42_000L, "OSHI", currentSensitive = false))
    }

    @Test
    fun `no timestamp recorded falls back to label and flag`() {
        val noTs = ours.copy(clipTimestamp = 0L)
        assertTrue(SensitiveClipboard.descriptionMatches(noTs, 99L, "OSHI", currentSensitive = true))
        assertFalse(SensitiveClipboard.descriptionMatches(noTs, 99L, "OSHI", currentSensitive = false))
    }
}
