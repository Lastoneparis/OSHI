package com.oshi.messenger

import com.oshi.messenger.service.PeerSilencePolicy
import com.oshi.messenger.service.PeerSilencePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The backstop, and the one property that made it not a backstop at all.
 *
 * A live call cannot be exercised on the JVM — there is no peer to stop transmitting —
 * so the arithmetic is the only part of "the peer vanished" that can be pinned
 * anywhere. That is the whole reason [PeerSilencePolicy] is a separate pure object
 * rather than four lines inside `startStatsUpdater`.
 *
 * The headline test is [a_paused_camera_no_longer_buys_the_peer_an_infinite_call]:
 * the rule this replaced read
 *
 * ```
 * timeSinceConnect > 20000 && timeSinceAudio > 45000 && !_isRemoteCameraPaused.value
 * ```
 *
 * and `_isRemoteCameraPaused` is set by the peer's `0x0C videoPaused` and cleared only
 * by their `0x0D videoResumed`. An iPhone that backgrounds mid-video-call sends the
 * pause; one that then dies never sends the resume; and the `&&` turned the only
 * remaining teardown off for the rest of the process's life.
 */
class PeerSilencePolicyTest {

    private fun verdict(
        connectMs: Long,
        silenceMs: Long,
        paused: Boolean = false
    ) = PeerSilencePolicy.verdict(connectMs, silenceMs, paused)

    // =====================================================================
    // 1. The early window belongs to the ring / connect watchdogs
    // =====================================================================

    @Test
    fun `nothing is judged before the call has been up long enough`() {
        // A minute of silence, but only ten seconds connected: not this rule's call.
        assertEquals(Verdict.HEALTHY, verdict(connectMs = 10_000, silenceMs = 60_000))
        assertEquals(Verdict.HEALTHY, verdict(connectMs = 0, silenceMs = 60_000))
        assertEquals(Verdict.HEALTHY, verdict(connectMs = -1, silenceMs = 60_000))
    }

    @Test
    fun `the connected threshold is exclusive at its own boundary`() {
        val at = PeerSilencePolicy.MIN_CONNECTED_MS
        assertEquals(Verdict.HEALTHY, verdict(connectMs = at, silenceMs = 60_000))
        assertEquals(Verdict.LOST, verdict(connectMs = at + 1, silenceMs = 60_000))
    }

    // =====================================================================
    // 2. The two live thresholds
    // =====================================================================

    @Test
    fun `flowing media is healthy`() {
        assertEquals(Verdict.HEALTHY, verdict(connectMs = 5 * 60_000, silenceMs = 0))
        assertEquals(Verdict.HEALTHY, verdict(connectMs = 5 * 60_000, silenceMs = 500))
    }

    @Test
    fun `ten seconds of silence warns, forty five ends`() {
        val up = 5 * 60_000L
        assertEquals(
            Verdict.HEALTHY,
            verdict(connectMs = up, silenceMs = PeerSilencePolicy.RECONNECTING_SILENCE_MS)
        )
        assertEquals(
            Verdict.RECONNECTING,
            verdict(connectMs = up, silenceMs = PeerSilencePolicy.RECONNECTING_SILENCE_MS + 1)
        )
        assertEquals(
            Verdict.RECONNECTING,
            verdict(connectMs = up, silenceMs = PeerSilencePolicy.LOST_SILENCE_MS)
        )
        assertEquals(
            Verdict.LOST,
            verdict(connectMs = up, silenceMs = PeerSilencePolicy.LOST_SILENCE_MS + 1)
        )
    }

    // =====================================================================
    // 3. The bug this object exists for
    // =====================================================================

    @Test
    fun `a paused camera still buys the peer extra rope`() {
        // The lull the exemption defends is real: iOS reconfigures its capture session
        // on the video → voice flip and some builds stall audio while it does. A peer
        // in that state must NOT be hung up on at 45 s.
        val up = 5 * 60_000L
        assertEquals(
            Verdict.LOST,
            verdict(connectMs = up, silenceMs = 46_000, paused = false)
        )
        assertEquals(
            Verdict.RECONNECTING,
            verdict(connectMs = up, silenceMs = 46_000, paused = true)
        )
    }

    @Test
    fun `a paused camera no longer buys the peer an infinite call`() {
        val up = 5 * 60_000L
        // The exact boundary…
        assertEquals(
            Verdict.RECONNECTING,
            verdict(connectMs = up, silenceMs = PeerSilencePolicy.PAUSED_SILENCE_MS, paused = true)
        )
        assertEquals(
            Verdict.LOST,
            verdict(
                connectMs = up,
                silenceMs = PeerSilencePolicy.PAUSED_SILENCE_MS + 1,
                paused = true
            )
        )
        // …and the shape of the old bug: however long you wait, it used to stay up.
        for (minutes in listOf(2L, 10L, 60L, 24L * 60L)) {
            assertEquals(
                "a peer silent for $minutes minute(s) with a paused camera must be LOST",
                Verdict.LOST,
                verdict(connectMs = up, silenceMs = minutes * 60_000L, paused = true)
            )
        }
    }

    @Test
    fun `the paused window is finite and strictly more patient than the normal one`() {
        assertEquals(
            "PAUSED_SILENCE_MS must be a number, not an escape hatch",
            true,
            PeerSilencePolicy.PAUSED_SILENCE_MS > PeerSilencePolicy.LOST_SILENCE_MS &&
                PeerSilencePolicy.PAUSED_SILENCE_MS < Long.MAX_VALUE
        )
    }

    // =====================================================================
    // __KEEPALIVE_IS_NOT_AUDIO_2026_09_22__ — a muted iPhone sends nothing on P2P
    // =====================================================================

    @Test
    fun a_muted_peer_whose_transport_still_pongs_is_not_silent() {
        // 60 s without media (peer muted), last correlated pong 400 ms ago.
        val silence = PeerSilencePolicy.silenceMs(msSinceMedia = 60_000, msSincePeerTransport = 400)
        assertEquals(400L, silence)
        assertEquals(Verdict.HEALTHY, verdict(connectMs = 120_000, silenceMs = silence))
    }

    @Test
    fun without_a_pong_only_media_counts_exactly_as_before() {
        assertEquals(60_000L, PeerSilencePolicy.silenceMs(60_000, null))
        assertEquals(Verdict.LOST, verdict(connectMs = 120_000, silenceMs = PeerSilencePolicy.silenceMs(60_000, null)))
    }

    @Test
    fun a_peer_whose_pongs_stopped_too_is_still_judged_lost() {
        // Peer app died: media AND pongs stopped together.
        val silence = PeerSilencePolicy.silenceMs(msSinceMedia = 50_000, msSincePeerTransport = 49_000)
        assertEquals(Verdict.LOST, verdict(connectMs = 120_000, silenceMs = silence))
    }

    @Test
    fun media_fresher_than_the_last_pong_wins() {
        assertEquals(100L, PeerSilencePolicy.silenceMs(100, 5_000))
        // A negative age (clock step) is ignored rather than trusted.
        assertEquals(100L, PeerSilencePolicy.silenceMs(100, -1))
    }
}
