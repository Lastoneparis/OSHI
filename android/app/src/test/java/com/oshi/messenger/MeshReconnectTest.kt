package com.oshi.messenger

import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.mesh.MeshReconnectPolicy
import com.oshi.messenger.network.mesh.MeshReconnectPolicy.Attempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__
 *
 * Reported from a user's Android phone with a screenshot: **0 Connecté · 1 À
 * proximité**, an iPhone in the list, "Connecter" beside it, and nothing
 * happening when it was pressed — plus messages no longer going over Bluetooth
 * or Wi-Fi.
 *
 * Three separate holes, each of which alone is enough to produce that
 * screenshot:
 *
 *  1. `PeersViewModel.connectToPeer`'s cross-platform branch was a comment.
 *  2. `CrossPlatformMesh.connectToPeer` was reachable only from two one-shot
 *     discovery callbacks, so a failed dial was a peer's only dial.
 *  3. Every eviction path removed a peer from the connected list without
 *     putting it back on the discovered one, so a dropped link left the peer
 *     in neither — invisible to the UI and to any retry.
 *
 * The first part of this file tests the decision table that now drives the
 * retries. The second pins the three wirings, because they are Compose/Android
 * call sites a JVM test cannot execute.
 */
class MeshReconnectTest {

    // ───────────────────────────── the policy ─────────────────────────────

    private fun decide(
        connected: Boolean = false,
        hasAddress: Boolean = true,
        dialInFlight: Boolean = false,
        failures: Int = 0,
        msSinceLastDial: Long = Long.MAX_VALUE,
        userAsked: Boolean = false,
    ) = MeshReconnectPolicy.decide(
        connected = connected,
        hasAddress = hasAddress,
        dialInFlight = dialInFlight,
        failures = failures,
        msSinceLastDial = msSinceLastDial,
        userAsked = userAsked,
    )

    @Test
    fun aLivePeerIsNotDialledAgain() {
        assertEquals(Attempt.ALREADY_CONNECTED, decide(connected = true))
        // Even when the user presses the button.
        assertEquals(Attempt.ALREADY_CONNECTED, decide(connected = true, userAsked = true))
    }

    @Test
    fun aDialInFlightIsNotDoubled() {
        assertEquals(Attempt.WAIT, decide(dialInFlight = true))
        assertEquals(Attempt.WAIT, decide(dialInFlight = true, userAsked = true))
    }

    @Test
    fun aPeerWithNoAddressCannotBeDialledSoDiscoveryIsAskedAgain() {
        // This is the BLE sighting of an iPhone: a public key and a name, no
        // ip and no port, because iOS does not expose the OSHI GATT service to
        // us. The old code logged "will rely on NSD" and that was the end of it.
        assertEquals(Attempt.REDISCOVER, decide(hasAddress = false))
        assertEquals(Attempt.REDISCOVER, decide(hasAddress = false, userAsked = true))
    }

    @Test
    fun aPressIgnoresTheBackoff() {
        // The user is holding the phone next to the other one. The ladder is not.
        assertEquals(
            Attempt.WAIT,
            decide(failures = 5, msSinceLastDial = 1_000),
        )
        assertEquals(
            Attempt.DIAL,
            decide(failures = 5, msSinceLastDial = 1_000, userAsked = true),
        )
    }

    @Test
    fun theFirstRetryIsImmediateAndTheLadderClimbs() {
        assertEquals(0L, MeshReconnectPolicy.backoffMs(0))
        val ladder = (0..6).map { MeshReconnectPolicy.backoffMs(it) }
        assertEquals(
            "the ladder must never descend: $ladder",
            ladder.sorted(), ladder,
        )
        // It tops out rather than growing without bound, and never becomes
        // "give up": a peer still being announced on the LAN is still worth a try.
        assertEquals(MeshReconnectPolicy.backoffMs(99), MeshReconnectPolicy.backoffMs(5))
        assertTrue("a capped backoff must still fire", MeshReconnectPolicy.backoffMs(99) < Long.MAX_VALUE)
    }

    @Test
    fun theSweepWaitsOutTheBackoffThenDials() {
        val owed = MeshReconnectPolicy.backoffMs(3)
        assertEquals(Attempt.WAIT, decide(failures = 3, msSinceLastDial = owed - 1))
        assertEquals(Attempt.DIAL, decide(failures = 3, msSinceLastDial = owed))
    }

    @Test
    fun aFreshlyDiscoveredPeerIsDialledAtOnce() {
        // failures = 0, so no time need elapse. The sweep must not sit on a
        // peer that has never been tried.
        assertEquals(Attempt.DIAL, decide(failures = 0, msSinceLastDial = 0))
    }

    @Test
    fun theSweepRunsOftenEnoughToHealWhileTheUserWatchesAndNotSoOftenItThrashes() {
        assertTrue(MeshReconnectPolicy.SWEEP_INTERVAL_MS in 5_000..30_000)
        // Restarting NSD browsing re-resolves every service on the network;
        // it must not ride on the sweep's own cadence.
        assertTrue(
            "discovery restarts must be rarer than sweeps",
            MeshReconnectPolicy.REDISCOVER_MIN_INTERVAL_MS > MeshReconnectPolicy.SWEEP_INTERVAL_MS,
        )
    }

    // ───────────────────────── which address we dial ─────────────────────────

    private fun pick(vararg a: String) = CrossPlatformMesh.pickDialAddress(a.toList())

    @Test
    fun aRoutableIPv4BeatsEveryIPv6() {
        // An iPhone publishes its Bonjour service with NetService, which
        // registers A and AAAA. `NsdServiceInfo.getHost()` returns one of them,
        // chosen by the platform.
        assertEquals("192.168.1.18", pick("fe80::18c3:9aff:fe21:1", "192.168.1.18"))
        assertEquals("10.0.1.7", pick("2a01:cb00::1", "10.0.1.7"))
    }

    @Test
    fun anUnscopedLinkLocalIPv6IsNeverDialled() {
        // No zone index means the kernel has no interface to send it on:
        // Socket.connect throws, and before the reconnect sweep existed that
        // throw was the peer's last chance.
        assertEquals(null, pick("fe80::18c3:9aff:fe21:1"))
        assertEquals("fe80::18c3:9aff:fe21:1%wlan0", pick("fe80::18c3:9aff:fe21:1%wlan0"))
    }

    @Test
    fun weNeverDialOurselves() {
        assertEquals(null, pick("127.0.0.1", "::1", "0.0.0.0"))
        assertEquals("192.168.1.18", pick("127.0.0.1", "192.168.1.18"))
    }

    @Test
    fun aLinkLocalIPv4IsALastResortAndNotARefusal() {
        // 169.254.x is what a phone assigns itself when DHCP never answered —
        // `pickLanIPv4` calls it "a last resort, never a choice", and the dial
        // side has to agree: two phones on a Wi-Fi network with no DHCP server
        // both self-assign one and can still reach each other, which for a mesh
        // app is a case that matters.
        assertEquals("169.254.3.4", pick("169.254.3.4"))
        assertEquals("192.168.1.18", pick("169.254.3.4", "192.168.1.18"))
        assertEquals("2a01:cb00::1", pick("169.254.3.4", "2a01:cb00::1"))
    }

    @Test
    fun nothingResolvedMeansNothingDialled() {
        assertEquals(null, CrossPlatformMesh.pickDialAddress(emptyList()))
        assertEquals(null, pick("", "   "))
    }

    // ───────────────────────────── the wiring ─────────────────────────────

    private val viewModel by lazy {
        SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/PeersViewModel.kt")
    }
    private val mesh by lazy {
        SourceText.read("src/main/java/com/oshi/messenger/network/mesh/CrossPlatformMesh.kt")
    }
    private val screen by lazy {
        SourceText.read("src/main/java/com/oshi/messenger/ui/screens/PeersScreen.kt")
    }

    @Test
    fun theConnectButtonReachesTheMesh() {
        val body = SourceText.between(
            viewModel,
            "fun connectToPeer(peer: MeshPeer) {",
            "fun disconnectPeer",
        )
        assertTrue(
            "the cross-platform branch of connectToPeer must call CrossPlatformMesh.connectNow — " +
                "it was two lines of comment and the button ran no code:\n$body",
            body.contains("crossPlatformMesh.connectNow("),
        )
        assertTrue(
            "and it must say what happened: a silent button is indistinguishable from a broken one",
            body.contains("_connectFeedback.emit("),
        )
    }

    @Test
    fun refreshRestartsTheCrossPlatformLaneAndNotOnlyNearbyConnections() {
        val body = SourceText.between(viewModel, "fun refresh() {", "fun showMeshInfo")
        assertTrue(
            "refresh() goes through meshManager.stopMesh(), which deliberately leaves " +
                "CrossPlatformMesh running, so the start() that follows is answered with " +
                "\"already running\". Without restartDiscovery the Refresh button does nothing " +
                "at all for iOS peers:\n$body",
            body.contains("crossPlatformMesh.restartDiscovery("),
        )
    }

    @Test
    fun theScreenShowsTheAnswer() {
        assertTrue(
            "PeersScreen must host a snackbar for connectFeedback",
            screen.contains("viewModel.connectFeedback.collect"),
        )
        assertTrue(
            "and hand it to the Scaffold",
            screen.contains("snackbarHost = { SnackbarHost(snackbarHostState) }"),
        )
    }

    @Test
    fun theMeshSweepsForPeersItHasNotConnected() {
        assertTrue(
            "CrossPlatformMesh.start() must schedule the reconnect sweep, or a failed dial " +
                "is still a peer's only dial",
            mesh.contains("scheduleReconnectSweep(generation)"),
        )
        assertTrue(
            "the sweep must consult the policy rather than inventing its own timing",
            mesh.contains("MeshReconnectPolicy.decide("),
        )
    }

    @Test
    fun everyEvictionHandsThePeerBackToTheSweep() {
        // A peer is removed from the DISCOVERED list when it connects, so an
        // eviction that only removes it from the CONNECTED list leaves it in
        // neither — and NSD will not announce an already-registered service a
        // second time, so nothing re-finds it.
        val leaks = Regex("""currentPeers\.removeAll \{ it\.publicKey == \w+ }""")
            .findAll(mesh).map { it.value }.toList()
        assertEquals(
            "every path that drops a connected peer must go through dropConnectedPeer(); " +
                "found raw removals: $leaks",
            emptyList<String>(), leaks,
        )
        assertTrue(
            "dropConnectedPeer must put the peer back on the discovered list",
            SourceText.between(mesh, "private fun dropConnectedPeer(", "private fun hasLiveConnection(")
                .contains("_crossPlatformPeers.value = discovered"),
        )
    }

    @Test
    fun theResolveListenerPicksItsAddressInsteadOfTakingTheFirstOne() {
        val body = SourceText.between(
            mesh,
            "override fun onServiceResolved(serviceInfo: NsdServiceInfo) {",
            "val peer = CrossPlatformPeer(",
        )
        assertTrue(
            "a resolve must go through dialHostFor/pickDialAddress — serviceInfo.host alone " +
                "can be an unscoped IPv6 link-local, which cannot be dialled at all:\n" + body,
            body.contains("dialHostFor(serviceInfo)"),
        )
        assertTrue(
            "and the picker must read every address the platform offers",
            SourceText.between(mesh, "private fun dialHostFor(", "private fun createResolveListener(")
                .contains("info.hostAddresses"),
        )
    }

    /**
     * __A_SIGHTING_WITH_AN_ADDRESS_WAS_THROWN_AWAY_2026_11_09__ Both sighting paths
     * must fold into the discovered list, not "add if absent": the first,
     * addressless BLE row used to win for good. The merge itself is in
     * CrossPlatformMeshSightingTest.
     */
    @Test
    fun bothSightingPathsMergeInsteadOfKeepingTheFirstRow() {
        val ble = SourceText.between(mesh, "override fun onCharacteristicRead(", "announceReadRetries.remove(gatt.device?.address)")
        val nsd = SourceText.between(mesh, "override fun onServiceResolved(serviceInfo: NsdServiceInfo) {", "connectToPeer(peer)")
        for ((lane, body) in listOf("BLE" to ble, "NSD" to nsd)) {
            assertTrue("$lane sighting must go through mergeSighting:\n$body", body.contains("mergeSighting("))
            assertTrue("$lane sighting still adds only if absent:\n$body", !body.contains("none { it.publicKey == pk }"))
        }
    }

    @Test
    fun aLiveConnectionIsRecognisedAcrossBase64Spellings() {
        val body = SourceText.between(
            mesh,
            "private fun hasLiveConnection(publicKey: String): Boolean {",
            "private fun connectToPeer(",
        )
        assertTrue(
            "a strict containsKey reads \"not connected\" for a peer whose announced key " +
                "is spelled base64url and whose IDENTITY_EXCHANGE key is not:\n$body",
            body.contains("normalizeKey("),
        )
        assertTrue(
            "a closed socket is not a connection — that is the state the sweep exists to leave",
            body.contains("isClosed"),
        )
    }

    @Test
    fun theAutomaticLoopDoesNotBorrowTheUsersPrivileges() {
        // `discoveredPeers` re-emits on every state change. connectToPeer is
        // the user-asked path and ignores backoff by design; routing automatic
        // emissions through it would dial a dead address several times a second.
        // The end anchor is CODE, not a comment: SourceText.read strips comments,
        // so a comment anchor can only ever fail to be found.
        val init = SourceText.between(
            viewModel,
            "discoveredPeers.collect { peers ->",
            "connectedPeers.collect { connected ->",
        )
        assertTrue(
            "the auto-reconnect collector must leave cross-platform peers to the mesh sweep:\n$init",
            init.contains("peer.connectionType != ConnectionType.CROSS_PLATFORM"),
        )
    }
}
