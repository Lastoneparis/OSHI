package com.oshi.messenger.service

/**
 * How often the app is allowed to wake the radio, as a function of what the app is
 * actually doing.
 *
 * ## Why this exists
 *
 * The Android port copied iOS's poll *intervals* without copying iOS's *execution
 * environment*, and that single mismatch is the whole "phone gets hot" defect.
 *
 * On iOS the intervals are aggressive — 1 s for the call-signal poll
 * (`VoiceCallManager.swift:4783`), 1 s for the message/IPFS poll
 * (`MessageManager.swift:1914`), 30 s for multi-device sync
 * (`MultiDeviceSyncManager.swift:118`). They are free anyway, because **iOS declares
 * no `UIBackgroundModes` at all** (verified: the key is absent from `OSHI/Info.plist`).
 * The moment the app leaves the screen the process is suspended and every one of
 * those `Timer`s stops dead. iOS's steady-state background cost is exactly zero
 * wakeups, and it learns about anything new from a push: a PushKit VoIP push for a
 * call — which iOS then uses to *suppress* the VPS poll for that caller
 * (`VoiceCallManager.swift:4760-4778`) — and APNs for messages. Coming back to the
 * foreground is a *burst*, not a standing loop: `SecureWeb3MessengerApp.swift:290-330`
 * fires `checkPendingIPFSMessages()` at 0 / 0.3 / 0.8 / 1.5 s and
 * `MultiDeviceSyncManager.performFullSync()` at 1.0 s, then goes quiet again.
 *
 * Where iOS *does* keep a poll alive across states, it switches the interval on app
 * state explicitly — `CovertChannelReceiver.swift:25-26` declares
 * `pollIntervalActive = 15` / `pollIntervalBackground = 60` and `:100` picks between
 * them with `active ? pollIntervalActive : pollIntervalBackground`.
 *
 * Android has none of that for free. A `dataSync` foreground service keeps the
 * process alive precisely so the loops keep running, so iOS's "free" 1–5 s timers
 * became ~59 network wakeups a minute, forever, screen off included. This object is
 * the missing half of the port: it makes the interval a function of foreground /
 * background and of whether anything is actually happening, the same way iOS's own
 * `setActivePolling` does — and it is deliberately *not* a kill switch, because two
 * things must keep running no matter what (see [callDelayMs]).
 *
 * Pure arithmetic, no Android types, so every rule below is exercised by
 * `PollBackoffTest` on the JVM.
 */
object PollBackoff {

    // ── Message / V2 relay poll ────────────────────────────────────────────────

    /** Foreground and something happened recently. Unchanged from the old constant. */
    const val MESSAGE_BASE_MS = 5_000L

    /** Foreground, quiet for [IDLE_STEP_1_MS]. */
    const val MESSAGE_IDLE_1_MS = 10_000L

    /** Foreground, quiet for [IDLE_STEP_2_MS]. */
    const val MESSAGE_IDLE_2_MS = 20_000L

    /** Foreground, quiet for [IDLE_STEP_3_MS] or more. */
    const val MESSAGE_IDLE_3_MS = 30_000L

    /**
     * Backgrounded. iOS's value here is "never" (suspended process); this is a
     * backstop for the case where FCM does not arrive, which on Android is a real
     * possibility rather than a theoretical one. FCM is still the primary wake path:
     * `FCMService.kt:528` calls `VPSClient.triggerImmediatePoll()`, which both fires a
     * poll immediately and resets the idle escalation.
     */
    const val MESSAGE_BACKGROUND_MS = 120_000L

    // ── Call-signal poll ───────────────────────────────────────────────────────

    /**
     * A call is ringing, connecting or in progress. Unchanged from the old flat
     * constant, and NEVER suspended — not on background, not on screen-off. iOS polls
     * this at 1 s while a call is live; 3 s is what Android already shipped and this
     * change does not make call latency worse than it was.
     */
    const val CALL_ACTIVE_MS = 3_000L

    /**
     * No call, app on screen. iOS's idle equivalent is 1 s, but iOS pays nothing for
     * it (the process dies on background anyway) and it has PushKit as the real
     * incoming-call path. Android has the FCM `"call"` branch
     * (`FCMService.kt:579-623` → `IncomingCallService`) as its PushKit equivalent, so
     * this poll is the *fallback* for a push that did not arrive, not the primary
     * path. 10 s of extra worst-case ring latency on a failed push, in exchange for
     * cutting 20 requests/min to 6.
     */
    const val CALL_IDLE_FOREGROUND_MS = 10_000L

    /**
     * No call, app backgrounded. Still not zero — this is the one loop that must
     * survive backgrounding, because the failure mode of getting it wrong is a missed
     * call. Strictly better than iOS, which polls not at all in this state.
     */
    const val CALL_IDLE_BACKGROUND_MS = 60_000L

    // ── Idle escalation ladder ────────────────────────────────────────────────

    const val IDLE_STEP_1_MS = 60_000L        // 1 minute
    const val IDLE_STEP_2_MS = 300_000L       // 5 minutes
    const val IDLE_STEP_3_MS = 900_000L       // 15 minutes

    /**
     * Applied to the 30 s housekeeping loops (multi-device sync, public-group mesh
     * broadcast) when the app is backgrounded. Not suspended outright: multi-device
     * sync is how a second device catches up, and a user who backgrounds the app for
     * an hour still expects their other phone to be current.
     */
    const val BACKGROUND_AUX_MULTIPLIER = 4L

    /**
     * Delay before the next coalesced message + V2 relay poll.
     *
     * `callActive` pins it at the base interval: call setup rides the same VPS host,
     * and a call is by definition "something happening".
     */
    fun messageDelayMs(foreground: Boolean, callActive: Boolean, idleMs: Long): Long = when {
        callActive -> MESSAGE_BASE_MS
        !foreground -> MESSAGE_BACKGROUND_MS
        idleMs < IDLE_STEP_1_MS -> MESSAGE_BASE_MS
        idleMs < IDLE_STEP_2_MS -> MESSAGE_IDLE_1_MS
        idleMs < IDLE_STEP_3_MS -> MESSAGE_IDLE_2_MS
        else -> MESSAGE_IDLE_3_MS
    }

    /**
     * Delay before the next call-signal poll. Never returns anything that would stop
     * the loop, and never returns more than [CALL_ACTIVE_MS] while a call is live —
     * those two properties are asserted directly in `PollBackoffTest`.
     */
    fun callDelayMs(foreground: Boolean, callActive: Boolean): Long = when {
        callActive -> CALL_ACTIVE_MS
        foreground -> CALL_IDLE_FOREGROUND_MS
        else -> CALL_IDLE_BACKGROUND_MS
    }

    /**
     * Cover-mode timing jitter: `delayMs` scaled by a uniform factor in
     * [1 - [COVER_JITTER_FRACTION], 1 + [COVER_JITTER_FRACTION]]. Mean preserved, so
     * battery and latency are unchanged on average; what goes away is the exact
     * 5 / 10 / 20 / 30 / 120 s period, which a passive observer sees as the
     * inter-request gap without decrypting anything. Applied ONLY to the message
     * poll that carries the V2 pull, and only while the cover transport is on.
     */
    const val COVER_JITTER_FRACTION = 0.5

    fun coverJitteredMs(delayMs: Long, random: java.util.Random): Long {
        val factor = 1.0 - COVER_JITTER_FRACTION + 2 * COVER_JITTER_FRACTION * random.nextDouble()
        return (delayMs * factor).toLong().coerceAtLeast(1L)
    }

    /** Delay for a 30 s housekeeping loop. */
    fun auxDelayMs(foreground: Boolean, baseMs: Long): Long =
        if (foreground) baseMs else baseMs * BACKGROUND_AUX_MULTIPLIER
}

/**
 * Process-wide answer to "is the app on screen, and is anything happening".
 *
 * A plain object rather than an injected `@Singleton` on purpose: the readers are
 * `VPSClient`, `MessageRepository`, `MultiDeviceSyncManager` and `GroupManager`, all
 * constructed at different points by Hilt, and the writer is
 * `OSHIApplication`'s `ProcessLifecycleOwner` observer, which is not in the graph at
 * all. Every field is `@Volatile`; nothing here blocks.
 *
 * [clock] is injectable so the escalation ladder can be tested without sleeping.
 */
object AppPollState {

    /** How long after the last call signal we keep treating a call as live. */
    const val CALL_HOT_WINDOW_MS = 60_000L

    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var foreground: Boolean = true

    @Volatile
    private var lastActivityMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastCallSignalMs: Long = 0L

    @Volatile
    private var callSessionOpen: Boolean = false

    val isForeground: Boolean get() = foreground

    /**
     * True while a call is plausibly live. Two independent triggers, because either
     * one alone has a hole:
     *
     *  - [setCallSessionOpen] is driven by the call WebSocket, which only exists once
     *    a call is actually being set up — it does not cover the *ringing* phase of an
     *    incoming call whose push was lost.
     *  - [noteCallSignal] fires on any signal the poll itself observed, which covers
     *    exactly that case: the first offer seen on a slow idle tick immediately drags
     *    the loop back to [PollBackoff.CALL_ACTIVE_MS] for the next
     *    [CALL_HOT_WINDOW_MS], so every subsequent signal of that call is at 3 s.
     */
    fun isCallActive(): Boolean =
        callSessionOpen || (clock() - lastCallSignalMs) < CALL_HOT_WINDOW_MS

    fun onForeground() {
        foreground = true
        noteActivity()
    }

    fun onBackground() {
        foreground = false
    }

    /** Something real happened (message in, user poked the app, push arrived). */
    fun noteActivity() {
        lastActivityMs = clock()
    }

    fun noteCallSignal() {
        lastCallSignalMs = clock()
    }

    fun setCallSessionOpen(open: Boolean) {
        callSessionOpen = open
        if (open) noteCallSignal()
    }

    fun idleForMs(): Long = clock() - lastActivityMs

    /**
     * Set by `CoverSettings.mode()` each time a V2 request evaluates the cover
     * switch. While true the message poll is jittered ([PollBackoff.coverJitteredMs]).
     */
    @Volatile
    var coverTimingJitter: Boolean = false

    private val jitterRandom = java.security.SecureRandom()

    fun messagePollDelayMs(): Long {
        val base = PollBackoff.messageDelayMs(foreground, isCallActive(), idleForMs())
        return if (coverTimingJitter) PollBackoff.coverJitteredMs(base, jitterRandom) else base
    }

    fun callPollDelayMs(): Long =
        PollBackoff.callDelayMs(foreground, isCallActive())

    fun auxSyncDelayMs(baseMs: Long): Long =
        PollBackoff.auxDelayMs(foreground, baseMs)

    /** Test hook only. */
    fun resetForTest(now: Long = System.currentTimeMillis()) {
        clock = { now }
        foreground = true
        lastActivityMs = now
        lastCallSignalMs = 0L
        callSessionOpen = false
        coverTimingJitter = false
    }
}
