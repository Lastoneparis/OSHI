package com.oshi.messenger

import com.oshi.messenger.service.NLPManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP-2 — the translate pre-flight decision table.
 *
 * `NLPManager.processTranslateReceived` collapsed every failure into `null`, and the
 * sheet printed one hard-coded "ML translation coming soon" line regardless. The three
 * failures a real device actually hits are different problems:
 *
 *  - **unsupported language** — ML Kit has no model for the pair; retrying never helps;
 *  - **pack not downloaded and offline** — ~30 MB has to come down first, and without a
 *    network `downloadModelIfNeeded` sits on a retrying Task, which is the "spinner that
 *    never resolves";
 *  - **anything else** — a genuine error, worth a retry.
 *
 * The classifier is pure so this runs with no ML Kit, no Play Services and no device.
 */
class TranslatePreflightTest {

    private val EN = "en"
    private val FR = "fr"

    @Test
    fun `both packs present goes ahead`() {
        assertNull(
            NLPManager.classifyTranslationPreflight(FR, EN, modelsPresent = true, online = true)
        )
        // Present locally is enough — being offline must NOT block a pair we already have.
        assertNull(
            NLPManager.classifyTranslationPreflight(FR, EN, modelsPresent = true, online = false)
        )
    }

    @Test
    fun `pack missing but online goes ahead so ML Kit can download`() {
        assertNull(
            NLPManager.classifyTranslationPreflight(FR, EN, modelsPresent = false, online = true)
        )
    }

    @Test
    fun `pack missing and offline is reported, not spun on`() {
        val outcome =
            NLPManager.classifyTranslationPreflight(FR, EN, modelsPresent = false, online = false)
        assertEquals(NLPManager.TranslationOutcome.ModelNotDownloaded, outcome)
    }

    @Test
    fun `an unmapped source language is unsupported`() {
        val outcome =
            NLPManager.classifyTranslationPreflight(null, EN, modelsPresent = true, online = true)
        assertTrue(outcome is NLPManager.TranslationOutcome.UnsupportedLanguage)
        assertNull((outcome as NLPManager.TranslationOutcome.UnsupportedLanguage).sourceCode)
        assertEquals(EN, outcome.targetCode)
    }

    @Test
    fun `an unmapped target language is unsupported`() {
        val outcome =
            NLPManager.classifyTranslationPreflight(FR, null, modelsPresent = true, online = true)
        assertTrue(outcome is NLPManager.TranslationOutcome.UnsupportedLanguage)
    }

    @Test
    fun `unsupported beats the download question`() {
        // A language ML Kit cannot do is unsupported whether or not we have a network:
        // telling the user "connect to the internet" there would be a lie.
        val outcome =
            NLPManager.classifyTranslationPreflight(null, null, modelsPresent = false, online = false)
        assertTrue(outcome is NLPManager.TranslationOutcome.UnsupportedLanguage)
    }
}
