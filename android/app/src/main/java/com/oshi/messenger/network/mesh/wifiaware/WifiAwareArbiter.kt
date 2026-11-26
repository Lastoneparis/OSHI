package com.oshi.messenger.network.mesh.wifiaware

/**
 * __WIFI_AWARE_2026_11_25__ Every "may Wi-Fi Aware run now?" decision, pure, so the
 * rules that protect the connections the user already has are unit-tested rather
 * than discovered on somebody's phone.
 *
 * The owner's requirement was that Aware must NOT break any current connection.
 * Wi-Fi Aware shares the Wi-Fi chip with infrastructure Wi-Fi, the hotspot
 * (SoftAP) and Wi-Fi Direct (P2P); on many chips Aware and P2P / SoftAP cannot run
 * at the same time, and the framework resolves a conflict by tearing one of them
 * down. So the rules are:
 *
 *  1. Aware never starts while a Wi-Fi Direct GROUP is formed or the hotspot is
 *     on (or turning on). It waits; it does not ask either to stop.
 *  2. Aware that is already running is never stopped by this class because the
 *     other one appeared — the framework will withdraw Aware itself if the chip
 *     cannot hold both, and we then close quietly ([Lifecycle], event
 *     [Lifecycle.Event.UNAVAILABLE]).
 *  3. On API 31+ the chip's free Aware resources are read before starting;
 *     zero publish / subscribe sessions, or no answer at all, means stay off
 *     ("when unsure, stay off").
 *  4. Wi-Fi Direct peer DISCOVERY (a scan this app starts and whose results
 *     nothing reads today) is not started while Aware is running, so Aware's
 *     data paths are never put at risk on a single-radio chip by a scan that
 *     brings nothing. Discovery already running is never stopped.
 *  5. Infrastructure Wi-Fi, Bluetooth and LoRa are never inputs that Aware can
 *     change: nothing in the Aware lane touches them. Aware networks have no
 *     INTERNET capability and only the lane's own sockets are bound to them.
 */
object WifiAwareArbiter {

    /** API 29: `WifiAwareNetworkSpecifier.Builder` + `WifiAwareNetworkInfo` (peer IPv6 + port). */
    const val MIN_SDK = 29
    /** API 31: `WifiAwareManager.getAvailableAwareResources()`. */
    const val SDK_RESOURCES = 31

    /** Upper bound on simultaneous Aware peers, whatever the chip claims. */
    const val MAX_PEERS = 8

    data class Inputs(
        /** `PackageManager.FEATURE_WIFI_AWARE`. */
        val featurePresent: Boolean,
        val sdkInt: Int,
        /** The "Wi-Fi Aware (fast nearby link)" switch. */
        val userEnabled: Boolean,
        /** The mesh is running (MeshPrefs on and `startMesh()` called). */
        val meshActive: Boolean,
        /** NEARBY_WIFI_DEVICES on 33+, ACCESS_FINE_LOCATION below. */
        val permissionsGranted: Boolean,
        /** `WifiAwareManager.isAvailable()`. */
        val awareAvailable: Boolean,
        /** A Wi-Fi Direct group is formed on this phone (any app, including the system). */
        val wifiDirectGroupActive: Boolean,
        /** The hotspot is enabled or enabling. */
        val hotspotActive: Boolean,
        /** `AwareResources` on API 31+, null below 31 or when the call failed. */
        val availablePublishSessions: Int?,
        val availableSubscribeSessions: Int?,
        /** True when this lane is already attached and running. */
        val currentlyRunning: Boolean,
    )

    enum class Reason {
        UNSUPPORTED, USER_DISABLED, MESH_OFF, NO_PERMISSION,
        WIFI_DIRECT_BUSY, HOTSPOT_BUSY, UNAVAILABLE, NO_RESOURCES, UNKNOWN_RESOURCES,
    }

    sealed class Decision {
        object Run : Decision() { override fun toString() = "Run" }
        /** Not allowed now; nothing to retry until an input changes. */
        data class Off(val reason: Reason) : Decision()
        /** Allowed in principle; the radio is busy or unavailable. Re-evaluated on the next broadcast. */
        data class Wait(val reason: Reason) : Decision()
    }

    /** True when this phone can ever run the lane — the settings switch is hidden otherwise. */
    fun isSupported(featurePresent: Boolean, sdkInt: Int): Boolean = featurePresent && sdkInt >= MIN_SDK

    fun decide(i: Inputs): Decision = when {
        !isSupported(i.featurePresent, i.sdkInt) -> Decision.Off(Reason.UNSUPPORTED)
        !i.userEnabled -> Decision.Off(Reason.USER_DISABLED)
        !i.meshActive -> Decision.Off(Reason.MESH_OFF)
        !i.permissionsGranted -> Decision.Off(Reason.NO_PERMISSION)
        // The framework has withdrawn Aware (Wi-Fi off, location off below 13,
        // a higher-priority interface took the chip). Running or not, we stop.
        !i.awareAvailable -> Decision.Wait(Reason.UNAVAILABLE)
        // Rule 2: never tear ourselves down because the other appeared.
        i.currentlyRunning -> Decision.Run
        // Rule 1.
        i.wifiDirectGroupActive -> Decision.Wait(Reason.WIFI_DIRECT_BUSY)
        i.hotspotActive -> Decision.Wait(Reason.HOTSPOT_BUSY)
        // Rule 3.
        i.sdkInt >= SDK_RESOURCES && (i.availablePublishSessions == null || i.availableSubscribeSessions == null) ->
            Decision.Wait(Reason.UNKNOWN_RESOURCES)
        i.sdkInt >= SDK_RESOURCES && (i.availablePublishSessions!! <= 0 || i.availableSubscribeSessions!! <= 0) ->
            Decision.Wait(Reason.NO_RESOURCES)
        else -> Decision.Run
    }

    /**
     * May this phone open one more Aware data path? [availableDataPaths] is
     * `AwareResources.availableDataPathsCount` on API 31+, null below (then only
     * our own [MAX_PEERS] cap applies). Zero free NDPs means the chip would have to
     * drop another path to make room — and that path could be somebody else's.
     */
    fun mayOpenDataPath(sdkInt: Int, availableDataPaths: Int?, openPaths: Int): Boolean {
        if (openPaths >= MAX_PEERS) return false
        if (sdkInt >= SDK_RESOURCES) return (availableDataPaths ?: 0) > 0
        return true
    }

    /**
     * Rule 4. Wi-Fi Direct peer discovery may start only while Aware is not
     * running. (Discovery is a scan, not a session: refusing to START one never
     * disconnects anything; a scan already running is left alone.)
     */
    fun mayStartWifiDirectDiscovery(awareRunning: Boolean): Boolean = !awareRunning

    /**
     * Per-peer form of rule 4, for any future Wi-Fi Direct connect: a peer already
     * reachable over Aware is never also dialled over Wi-Fi Direct.
     */
    fun mayDialOverWifiDirect(peerPublicKey: String, awarePeerKeys: Set<String>): Boolean =
        peerPublicKey !in awarePeerKeys

    /**
     * The responder's server socket listens on the wildcard address (a
     * `ServerSocket` cannot be bound to a `Network`), so it could in principle
     * be reached from the LAN too. Only connections that arrive on an Aware
     * interface (`aware_data0`, …) or from an address an Aware data path told us
     * about are accepted; everything else is closed before a byte is read.
     */
    fun acceptInbound(remoteInterfaceName: String?, remoteAddress: String, expectedPeerAddresses: Set<String>): Boolean =
        (remoteInterfaceName != null && remoteInterfaceName.startsWith("aware")) ||
            normaliseAddress(remoteAddress) in expectedPeerAddresses.map(::normaliseAddress)

    /** Strip a `%scope` suffix and brackets so "fe80::1%aware_data0" matches "fe80::1". */
    fun normaliseAddress(address: String): String =
        address.trim().removePrefix("/").removePrefix("[").substringBefore(']').substringBefore('%').lowercase()

    /** Retry delay after an attach failure: 15 s doubling to 5 min. */
    fun backoffMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 5)
        return (15_000L shl shift).coerceAtMost(300_000L)
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    /**
     * The availability / fallback state machine. Pure reducer: the transport
     * feeds it events and performs the returned [Action]; the action is the
     * only thing that touches the radio.
     */
    object Lifecycle {
        enum class State { OFF, WAITING, ATTACHING, RUNNING }

        enum class Event {
            /** The arbiter said Run. */
            ALLOWED,
            /** The arbiter said Wait (busy radio, no resources). */
            BUSY,
            /** The arbiter said Off (switch off, mesh off, no permission, unsupported). */
            FORBIDDEN,
            ATTACHED,
            ATTACH_FAILED,
            /** ACTION_WIFI_AWARE_STATE_CHANGED with isAvailable == false, or the session was terminated. */
            UNAVAILABLE,
        }

        enum class Action {
            NONE,
            ATTACH,
            /** Close sessions, sockets and data paths quietly; peers fall back to the other lanes. */
            DETACH,
            /** Attach failed: try again after [backoffMs]. */
            SCHEDULE_RETRY,
        }

        data class Step(val state: State, val action: Action)

        fun next(state: State, event: Event): Step = when (event) {
            Event.ALLOWED -> when (state) {
                State.OFF, State.WAITING -> Step(State.ATTACHING, Action.ATTACH)
                State.ATTACHING, State.RUNNING -> Step(state, Action.NONE)
            }
            Event.BUSY -> when (state) {
                // Rule 2 lives in the arbiter (a running lane is never told BUSY
                // for P2P/hotspot); UNAVAILABLE is how the framework withdraws it.
                State.RUNNING -> Step(State.RUNNING, Action.NONE)
                State.ATTACHING -> Step(State.WAITING, Action.DETACH)
                State.OFF, State.WAITING -> Step(State.WAITING, Action.NONE)
            }
            Event.FORBIDDEN -> when (state) {
                State.OFF -> Step(State.OFF, Action.NONE)
                State.WAITING -> Step(State.OFF, Action.NONE)
                State.ATTACHING, State.RUNNING -> Step(State.OFF, Action.DETACH)
            }
            Event.ATTACHED -> when (state) {
                State.ATTACHING -> Step(State.RUNNING, Action.NONE)
                // A late callback for an attach we have since abandoned.
                else -> Step(state, Action.DETACH)
            }
            Event.ATTACH_FAILED -> when (state) {
                State.ATTACHING -> Step(State.WAITING, Action.SCHEDULE_RETRY)
                else -> Step(state, Action.NONE)
            }
            Event.UNAVAILABLE -> when (state) {
                State.RUNNING, State.ATTACHING -> Step(State.WAITING, Action.DETACH)
                else -> Step(state, Action.NONE)
            }
        }
    }
}
