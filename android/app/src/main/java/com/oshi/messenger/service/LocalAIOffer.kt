package com.oshi.messenger.service

import java.io.IOException

/**
 * Pure decision logic for the AI Assistant ("Local AI") screen — kept free of Android
 * types so it can be unit-tested on the JVM.
 *
 * The screen must never be a dead end: either the user can chat, or they see Gemini Nano
 * being prepared by the system (with a way out), or they are offered the OSHI offline
 * model download (with a visible reason + retry when a download failed).
 */
object LocalAIOffer {

    enum class Mode {
        /** A chat-capable model is ready (Gemini Nano Prompt API, or the OSHI model on disk). */
        CHAT,
        /** The system says Gemini Nano (Prompt API) is downloadable / downloading. */
        GEMINI_PREPARING,
        /** Offer the OSHI offline model download. */
        OFFER_DOWNLOAD
    }

    /**
     * @param promptModelAvailable Gemini Nano *Prompt API* is AVAILABLE. Proofreading or
     *        Rewriting being available is NOT enough: those can't answer a chat question,
     *        and on several non-Pixel AICore phones (e.g. vivo X300) they are available
     *        while the Prompt API is not.
     */
    fun resolveMode(
        promptModelAvailable: Boolean,
        localModelDownloaded: Boolean,
        geminiState: NLPManager.GeminiNanoState,
        userChoseOfflineModel: Boolean
    ): Mode = when {
        promptModelAvailable || localModelDownloaded -> Mode.CHAT
        !userChoseOfflineModel && (
            geminiState == NLPManager.GeminiNanoState.DOWNLOADABLE ||
                geminiState == NLPManager.GeminiNanoState.DOWNLOADING
            ) -> Mode.GEMINI_PREPARING
        else -> Mode.OFFER_DOWNLOAD
    }

    /** Why the last OSHI model download failed — shown under the download button. */
    enum class DownloadError { NETWORK, STORAGE, SERVER }

    fun classifyDownloadError(e: Throwable): DownloadError {
        val msg = (e.message ?: "").lowercase()
        return when {
            msg.contains("enospc") || msg.contains("no space") || msg.contains("not enough storage") ->
                DownloadError.STORAGE
            msg.startsWith("download failed: http") || msg.contains("empty response body") ->
                DownloadError.SERVER
            e is IOException -> DownloadError.NETWORK
            else -> DownloadError.SERVER
        }
    }

    /** Free space needed before starting a download: the model plus a 200 MB margin. */
    fun hasRoomFor(modelSizeBytes: Long, freeBytes: Long): Boolean =
        freeBytes >= modelSizeBytes + 200L * 1024 * 1024
}
