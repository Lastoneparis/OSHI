package com.oshi.messenger.service

/**
 * When has the peer gone quiet for long enough that the call is over?
 *
 * This is the **backstop**, not the primary path. The primary path is the peer's
 * `callEnd` (`0x04`) arriving and being acted on — `EnhancedCallManager.kt`
 * `handleCallEnded()` → `endCall(PEER_ENDED)`. This object only answers the other
 * question: *the peer vanished without saying anything — how long do we wait?*
 *
 * It exists because the rule it replaces had a term that made the backstop
 * **unbounded**:
 *
 * ```
 * if (timeSinceConnect > 20000 && timeSinceAudio > 45000 && !_isRemoteCameraPaused.value)
 * ```
 *
 * `_isRemoteCameraPaused` latches `true` on a `0x0C videoPaused` from the peer and is
 * cleared only by a matching `0x0D videoResumed` (`EnhancedCallManager.kt:3150-3168`).
 * iOS emits `0x0C` when it backgrounds during a video call — so an iPhone that
 * backgrounds and *then* dies (force-quit, battery, Wi-Fi gone) sends the pause, never
 * sends the resume, and the `&& !paused` term switches the only remaining teardown
 * off. Android sits in the call with the peer's last decoded frame on screen, for
 * ever. The `0x0D` that would clear it is precisely the packet a dead peer cannot send.
 *
 * The lull that term was defending is real — iOS reconfigures its capture session on
 * the video→voice flip and some builds stall audio while it does — so the exemption is
 * kept. It is just given an end: [PAUSED_SILENCE_MS] instead of "never".
 *
 * Pure arithmetic, no Android types, so every boundary below is exercised by
 * `PeerSilencePolicyTest` on the JVM — which is the only place it *can* be exercised,
 * since a peer that stops transmitting cannot be staged in a unit test any other way.
 *
 * iOS reference: `VoiceCallManager.swift:3600` (`connectionLossThreshold = 2.5`),
 * `:3794` (`maxDisconnectionTime = 12.0` → auto `endCall(.peerDisconnected)` at
 * `:16510-16518`), `:16389` (15 s post-connect grace before the watchdog arms).
 * Android's figures are deliberately more patient than iOS's; the point of this change
 * is that they are *finite*, not that they match.
 */
object PeerSilencePolicy {

    /**
     * Nothing is judged until the call has been up this long. A call that has just
     * connected has not had time to receive anything yet, and the first seconds are
     * where jitter-buffer fill and P2P cutover live. Unchanged from the inline rule.
     */
    const val MIN_CONNECTED_MS = 20_000L

    /** Silence this long ⇒ say "reconnecting" (beep + banner). Unchanged. */
    const val RECONNECTING_SILENCE_MS = 10_000L

    /** Silence this long ⇒ the peer is gone; end the call. Unchanged. */
    const val LOST_SILENCE_MS = 45_000L

    /**
     * The same question for a peer who told us their camera is paused: twice the rope,
     * and then the same answer. The only property that matters here is that it is a
     * number rather than "never" — see the class comment.
     */
    const val PAUSED_SILENCE_MS = 90_000L

    /**
     * The silence [verdict] should judge: time since the most recent PROOF the peer is
     * still in the call — any packet on the media path, or a P2P pong answering one of
     * OUR pings.
     *
     * Why the pong counts: a MUTED peer sends no media, and since
     * `__KEEPALIVE_IS_NOT_AUDIO_2026_09_22__` the iPhone no longer sends its cleartext
     * keepalive on P2P either (it made the receiver play invented audio). Muting an
     * iPhone for 45 s would otherwise end the call from this side. A pong is not
     * forgeable by a bystander: it echoes the 12-byte nonce our own ping carried, and
     * the peer's transport only answers pings bearing this call's id; it stops the
     * moment the peer's call — and its P2P transport — does.
     *
     * @param msSincePeerTransport null when no correlated pong has ever arrived (relay,
     *   WebSocket or mesh-only call): then only media counts, exactly as before.
     */
    fun silenceMs(msSinceMedia: Long, msSincePeerTransport: Long?): Long {
        if (msSincePeerTransport == null || msSincePeerTransport < 0L) return msSinceMedia
        return minOf(msSinceMedia, msSincePeerTransport)
    }

    enum class Verdict {
        /** Media is flowing, or it is too early to tell. */
        HEALTHY,

        /** Quiet long enough to warn the user, not long enough to give up. */
        RECONNECTING,

        /** The peer is gone. Tear the call down. */
        LOST
    }

    /**
     * @param msSinceConnect how long the call has been connected. `<= 0` means "not
     *   connected yet", which is [Verdict.HEALTHY] — the ring/connect watchdogs own
     *   that phase, not this one.
     * @param msSinceMedia how long since ANY packet from the peer — audio, video,
     *   keepalive (`0x06`), or a control byte. All of them refresh
     *   `lastAudioReceivedTime`, so this really is "nothing at all".
     * @param remoteCameraPaused the peer's last `0x0C`/`0x0D` said their camera is off.
     */
    fun verdict(
        msSinceConnect: Long,
        msSinceMedia: Long,
        remoteCameraPaused: Boolean
    ): Verdict {
        if (msSinceConnect <= MIN_CONNECTED_MS) return Verdict.HEALTHY
        val lostAfter = if (remoteCameraPaused) PAUSED_SILENCE_MS else LOST_SILENCE_MS
        return when {
            msSinceMedia > lostAfter -> Verdict.LOST
            msSinceMedia > RECONNECTING_SILENCE_MS -> Verdict.RECONNECTING
            else -> Verdict.HEALTHY
        }
    }
}
