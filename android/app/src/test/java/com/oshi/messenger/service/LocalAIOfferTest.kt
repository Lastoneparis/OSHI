package com.oshi.messenger.service

import com.oshi.messenger.service.LocalAIOffer.DownloadError
import com.oshi.messenger.service.LocalAIOffer.Mode
import com.oshi.messenger.service.NLPManager.GeminiNanoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * The AI Assistant screen must always show something actionable. Field report (vivo X300
 * Ultra, global, Android 16): the tab "did not propose to download anything" — the
 * screen treated Proofreading/Rewriting availability as a chat model and opened an empty
 * chat, or waited forever on a Gemini Nano download that never finished.
 */
class LocalAIOfferTest {

    private fun mode(
        prompt: Boolean = false,
        local: Boolean = false,
        gemini: GeminiNanoState = GeminiNanoState.UNKNOWN,
        userChoseOffline: Boolean = false
    ) = LocalAIOffer.resolveMode(prompt, local, gemini, userChoseOffline)

    @Test
    fun `non-Pixel phone without Prompt API is offered the OSHI download`() {
        // vivo X300 Ultra shape: AICore answers UNAVAILABLE for Prompt, whatever
        // Proofreading/Rewriting say (they are no longer an input here).
        assertEquals(Mode.OFFER_DOWNLOAD, mode(gemini = GeminiNanoState.UNSUPPORTED))
    }

    @Test
    fun `unknown Gemini state still offers the download rather than a blank screen`() {
        assertEquals(Mode.OFFER_DOWNLOAD, mode(gemini = GeminiNanoState.UNKNOWN))
    }

    @Test
    fun `Gemini Nano being prepared shows the preparing view, with an exit to the download`() {
        assertEquals(Mode.GEMINI_PREPARING, mode(gemini = GeminiNanoState.DOWNLOADABLE))
        assertEquals(Mode.GEMINI_PREPARING, mode(gemini = GeminiNanoState.DOWNLOADING))
        assertEquals(
            Mode.OFFER_DOWNLOAD,
            mode(gemini = GeminiNanoState.DOWNLOADING, userChoseOffline = true)
        )
    }

    @Test
    fun `chat opens only when a chat-capable model is ready`() {
        assertEquals(Mode.CHAT, mode(prompt = true, gemini = GeminiNanoState.AVAILABLE))
        assertEquals(Mode.CHAT, mode(local = true, gemini = GeminiNanoState.UNSUPPORTED))
        assertEquals(Mode.CHAT, mode(local = true, gemini = GeminiNanoState.DOWNLOADING))
    }

    @Test
    fun `download failures are classified for the retry message`() {
        assertEquals(DownloadError.NETWORK, LocalAIOffer.classifyDownloadError(UnknownHostException("huggingface.co")))
        assertEquals(DownloadError.NETWORK, LocalAIOffer.classifyDownloadError(IOException("timeout")))
        assertEquals(DownloadError.STORAGE, LocalAIOffer.classifyDownloadError(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(DownloadError.STORAGE, LocalAIOffer.classifyDownloadError(IOException("Not enough storage for x.gguf")))
        assertEquals(DownloadError.SERVER, LocalAIOffer.classifyDownloadError(Exception("Download failed: HTTP 403")))
    }

    @Test
    fun `storage check is sized to the selected model, not a flat 600 MB`() {
        val mb = 1024L * 1024
        val pro = 2_497_280_256L
        assertFalse(LocalAIOffer.hasRoomFor(pro, 1_000 * mb))
        assertTrue(LocalAIOffer.hasRoomFor(pro, pro + 200 * mb))
    }
}
