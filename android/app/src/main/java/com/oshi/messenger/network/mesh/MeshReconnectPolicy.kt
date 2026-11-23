package com.oshi.messenger.network.mesh

/**
 * When a peer we can SEE but are not TALKING to should be dialled again.
 *
 * Pure Kotlin, no `android.*`, for the same reason as [BleRadioPolicy]: every
 * branch below has to be reachable from a JVM unit test instead of needing two
 * phones in one room.
 *
 * ───────────────────── the defect this file exists for ─────────────────────
 *
 * Reported from a user's Android phone on 2026-11-07, with a screenshot of the
 * Nearby Peers screen: **0 Connecté · 1 À proximité**, one iPhone named in the
 * list, a "Connecter" button beside it — and, in the user's words, messages
 * over Bluetooth and Wi-Fi no longer going through and the button doing
 * nothing when pressed.
 *
 * The button did nothing because it was wired to nothing:
 * `PeersViewModel.connectToPeer` branched on the connection type and the
 * cross-platform branch held two lines of comment saying the connection was
 * automatic. It was automatic — exactly once:
 *
 *   * `CrossPlatformMesh.connectToPeer` is called from the NSD resolve callback
 *     and from the BLE characteristic read, and from nowhere else.
 *   * NSD fires `onServiceFound` once per service. A peer already registered
 *     when we started browsing is never announced again, so a resolve that
 *     ends in a failed `Socket.connect` is the ONLY attempt that peer will ever
 *     get.
 *   * A peer read over BLE from an iPhone usually carries no ip/port at all
 *     (iOS does not expose the OSHI GATT service to us), and the code logged
 *     "will rely on NSD" and dropped it.
 *   * Nothing swept the discovered list afterwards. There was no occurrence of
 *     the word "reconnect" anywhere in the mesh package.
 *
 * And the three controls on that screen that look like they would retry all
 * return without touching this lane: Refresh and the Scanner/Visible toggles go
 * through `MeshNetworkManager.stopMesh()`, which documents that it deliberately
 * leaves `CrossPlatformMesh` running, so the `start()` that follows logs
 * "already running" and returns. So a single failed dial — an iPhone whose app
 * was backgrounded for a second, a Wi-Fi blip, a router that took a moment —
 * left the peer stranded at "À proximité" until the app was killed and
 * relaunched, with every affordance for fixing it inert.
 *
 * This object is the decision half of the fix. [decide] is called for each
 * discovered peer by a 15-second sweep, and once more with `userAsked = true`
 * when the user presses Connecter.
 */
object MeshReconnectPolicy {

    /** What to do about one discovered-but-unconnected peer, right now. */
    enum class Attempt {
        /** There is a live socket for this key. Nothing to do. */
        ALREADY_CONNECTED,

        /** Open a TCP connection to the peer's announced ip/port. */
        DIAL,

        /**
         * We know the peer exists but hold no address for it — a BLE sighting
         * whose announce carried no ip/port. Only a fresh NSD/BLE round can
         * supply one; dialling is not an option.
         */
        REDISCOVER,

        /** A dial is already in flight, or the backoff has not elapsed. */
        WAIT,
    }

    /** How often the automatic sweep runs. */
    const val SWEEP_INTERVAL_MS = 15_000L

    /**
     * Floor on automatic discovery restarts. Restarting NSD browsing is not
     * free (it re-enumerates the whole service type and re-resolves every
     * peer), and the condition that triggers it — a peer seen over BLE with no
     * address — can persist for as long as that iPhone is nearby.
     */
    const val REDISCOVER_MIN_INTERVAL_MS = 60_000L

    /**
     * Backoff ladder, indexed by consecutive failures. The first retry is
     * deliberately quick: the common cause is a peer that was mid-handshake or
     * briefly backgrounded, and three seconds is the difference between a mesh
     * that heals while the user is still looking at the screen and one that
     * looks dead.
     *
     * It tops out rather than giving up. A peer still on the discovered list is
     * still being announced on the LAN; a mesh that stops trying is the bug
     * this file is named after.
     */
    internal val BACKOFF_MS = longArrayOf(0L, 3_000L, 10_000L, 30_000L, 60_000L, 120_000L)

    /** Delay owed before the next dial after [failures] consecutive failures. */
    fun backoffMs(failures: Int): Long =
        BACKOFF_MS[failures.coerceIn(0, BACKOFF_MS.size - 1)]

    /**
     * @param connected       a live socket already exists for this peer's key
     * @param hasAddress      the peer announced both an ip and a port
     * @param dialInFlight    a connect attempt for this key has not returned yet
     * @param failures        consecutive failed dials for this key
     * @param msSinceLastDial elapsed time since the last dial was started
     * @param userAsked       the user pressed Connecter on this very peer
     */
    fun decide(
        connected: Boolean,
        hasAddress: Boolean,
        dialInFlight: Boolean,
        failures: Int,
        msSinceLastDial: Long,
        userAsked: Boolean,
    ): Attempt = when {
        connected -> Attempt.ALREADY_CONNECTED
        dialInFlight -> Attempt.WAIT
        !hasAddress -> Attempt.REDISCOVER
        // A press is a fact the backoff does not have: the user is holding the
        // phone next to the other one and knows it is there. Honouring a
        // two-minute ladder at that moment is what makes a button feel dead.
        userAsked -> Attempt.DIAL
        msSinceLastDial >= backoffMs(failures) -> Attempt.DIAL
        else -> Attempt.WAIT
    }
}
