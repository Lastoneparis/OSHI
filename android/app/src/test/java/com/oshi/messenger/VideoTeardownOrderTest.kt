package com.oshi.messenger

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "I ended the call on the iPhone and Android kept showing his video for about twenty
 * seconds." — the app's owner, from a real call.
 *
 * The mechanism was an ORDERING, and an ordering is the one thing a unit test can
 * still hold on to when the behaviour itself needs two devices. `endCall()` needs the
 * whole Hilt graph (a `CryptoManager`, a `VPSClient`, a `CrossPlatformMesh`, an
 * `AudioRecord`), so what is pinned here is the sequence of statements in the source —
 * the same technique `MultiDeviceIngestionRoutingTest` uses for the four ingestion
 * chains.
 *
 * **What went wrong.** iOS emits exactly one thing on hang-up, whatever kind of call it
 * is: `createCallPacket(type: .callEnd)` = `[0x04][ts BE8]["hung_up"]`
 * (`OSHI/VoiceCallManager.swift:8109`, opcode `:1907-1911`, reason strings
 * `:1828-1835`). There is no video-stop on hang-up — `0x0F` is sent only by
 * `stopVideoMidCall()` (`:10732-10734`) — and no end-of-stream bit in the H.264 stream
 * (flags layout, `OSHI/VideoCallManager.swift:2293-2294`, bits 3-7 unused). So `0x04`
 * is the *only* teardown trigger Android will ever get, and Android does have a case
 * for it on both channels. It just did not act on it promptly: `videoCallManager
 * .stopVideo()` sat at the tail of `endCall()`'s coroutine, seven steps *after* a
 * blocking `vpsClient.sendCallSignal()` — OkHttp with `connectTimeout = 10 s`,
 * `readTimeout = 30 s` (`VPSClient.kt:99-102`), announcing the end of a call to a peer
 * who had already gone. The picture stayed on screen for as long as that socket took
 * to give up.
 *
 * These tests do not prove the twenty seconds — only a two-device call can do that.
 * They prove the thing that made twenty seconds *possible*, and they fail if it comes
 * back.
 */
class VideoTeardownOrderTest {

    private val managerSource: String by lazy {
        val f = File("src/main/java/com/oshi/messenger/service/EnhancedCallManager.kt")
        assertTrue("not found: ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** The whole file with `//` and `*` comment lines removed — code only. */
    private val managerCode: String by lazy {
        managerSource
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /**
     * The body of `fun endCall(...)`, up to the SIGNAL HANDLING banner that follows it,
     * **with `//` comment lines removed**.
     *
     * Stripping the comments is not cosmetic. The block of prose this fix added quotes
     * `vpsClient.sendCallSignal()` by name to explain why the teardown moved in front
     * of it — and an `indexOf` over the raw text finds that mention, sitting *above*
     * the teardown, and reports the order as still broken. A source-shaped test has to
     * read code, not the discussion of the code.
     */
    private val endCallBody: String by lazy {
        val start = managerSource.indexOf("fun endCall(reason: CallEndReason")
        assertTrue("endCall(reason:) not found", start > 0)
        val end = managerSource.indexOf("private suspend fun handleCallSignal(", start)
        assertTrue("handleCallSignal not found after endCall", end > start)
        managerSource.substring(start, end)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private fun indexIn(haystack: String, needle: String, why: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("$why — not found: $needle", i >= 0)
        return i
    }

    // =====================================================================
    // 1. The user's eyes come before the wire
    // =====================================================================

    @Test
    fun `video is released before any end signal goes out`() {
        val stopVideo = indexIn(
            endCallBody,
            "videoCallManager.stopVideo()",
            "endCall must still stop video"
        )
        val httpSend = indexIn(
            endCallBody,
            "vpsClient.sendCallSignal(",
            "endCall must still announce the end over HTTP"
        )
        assertTrue(
            "videoCallManager.stopVideo() must come BEFORE vpsClient.sendCallSignal(): " +
                "that call is a blocking OkHttp POST (connectTimeout 10 s, readTimeout 30 s, " +
                "VPSClient.kt:99-102) and the peer it is addressed to has already hung up. " +
                "Teardown behind it is the twenty-second freeze.",
            stopVideo < httpSend
        )
    }

    @Test
    fun `the call screen leaves IN_CALL before any end signal goes out`() {
        val ended = indexIn(
            endCallBody,
            "_callState.value = CallState.ENDED",
            "endCall must publish ENDED"
        )
        val httpSend = indexIn(endCallBody, "vpsClient.sendCallSignal(", "HTTP fan-out")
        assertTrue(
            "_callState must reach ENDED before the network fan-out, not after it — " +
                "VideoCallScreen.kt:217-229 dismisses on ENDED, so anything in front of it " +
                "holds the whole call UI up.",
            ended < httpSend
        )
    }

    @Test
    fun `the video teardown is not sequenced inside the fan-out coroutine`() {
        // The fan-out's own internal order still matters (the WebSocket must carry the
        // end signal before disconnectCallWebSocket() closes it), which is why it was
        // left intact — and why the video teardown had to move OUT of it rather than up
        // inside it.
        val stopVideo = indexIn(endCallBody, "videoCallManager.stopVideo()", "teardown")
        val wsSend = indexIn(
            endCallBody,
            "vpsClient.sendCallAudioViaWebSocket(",
            "WebSocket fan-out"
        )
        val wsClose = indexIn(
            endCallBody,
            "vpsClient.disconnectCallWebSocket()",
            "WebSocket teardown"
        )
        assertTrue("video must be released before the WebSocket send too", stopVideo < wsSend)
        assertTrue(
            "the end signal must still be sent before the socket carrying it is closed",
            wsSend < wsClose
        )
    }

    // =====================================================================
    // 2. Both channels still act on 0x04 — the signal IS the trigger
    // =====================================================================

    @Test
    fun `0x04 ends the call on the media plane and on the signal plane`() {
        // Media plane: the legacy bare 0x04 (Android's own endCall "Path 1") still ends
        // the call, but only once the per-call UDP relay attests it is this call's peer
        // for this callId (__BARE_CALLEND_ATTESTED_2026_11_21__). iOS never puts 0x04 on
        // the media path: its WebSocket callEnd is a sealed `call_signal`, which is the
        // signal plane asserted below, and its media-path hang-up is the sealed 0x0D.
        assertTrue(
            "handleAudioData must still act on an attested packetType 0x04",
            Regex(
                """packetType == 0x04\.toByte\(\)[\s\S]{0,1500}?CallSignalGuards\.mayEndFromBareMediaCallEnd[\s\S]{0,1500}?handleCallEnded\(\)"""
            ).containsMatchIn(managerSource)
        )
        // Signal plane: resolveSignalType reads the type off byte 0 of the plaintext.
        assertTrue(
            "resolveSignalType must still map first byte 0x04 to CALL_END",
            Regex("""0x04 -> CallSignalType\.CALL_END""").containsMatchIn(managerSource)
        )
        assertTrue(
            "a CALL_END signal must still reach handleCallEnded()",
            Regex(
                """CallSignalType\.CALL_END ->[\s\S]{0,200}?handleCallEnded\(\)"""
            ).containsMatchIn(managerSource)
        )
    }

    // =====================================================================
    // 3. The timeout is a backstop, and it is bounded
    // =====================================================================

    @Test
    fun `the silence rule is delegated to the pure policy`() {
        assertTrue(
            "startStatsUpdater must route the silence decision through PeerSilencePolicy — " +
                "it is the only part of 'the peer vanished' that a JVM test can reach",
            managerSource.contains("PeerSilencePolicy.verdict(")
        )
    }

    @Test
    fun `the unbounded camera-paused exemption is gone from the source`() {
        assertTrue(
            "the teardown rule must not be conjoined with !_isRemoteCameraPaused again: " +
                "that flag is set by the peer's 0x0C and cleared only by their 0x0D, so a " +
                "peer that pauses and then dies switched the backstop off for ever",
            !Regex("""&&\s*!_isRemoteCameraPaused\.value""").containsMatchIn(managerCode)
        )
    }
}
