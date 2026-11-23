package com.oshi.messenger.service.lora

import android.content.Context
import android.util.Log
import com.oshi.messenger.service.CheckInEtaEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The CALLER of [MeshtasticManager.broadcastPosition] — what makes "share my position over
 * LoRa, the way Meshtastic does" a thing a person can actually switch on.
 *
 * Until this existed, both phones could encode and transmit a `Position` frame and neither
 * one ever did: `grep broadcastPosition` found the function, its unit test, and no
 * non-test caller on either platform. A send path with no caller is a capability on paper;
 * the receive half (pins on the map, [LoRaPacketParser]'s portnum-3 case) had nothing of
 * ours to draw.
 *
 * ## Why the policy is a pure function
 *
 * [decide] takes every input as a plain value and returns a [Decision]. It needs no radio,
 * no GPS, no Android framework and no clock, which is the only way the airtime rules below
 * are checkable at all — the alternative is standing outdoors with two radios and a
 * stopwatch for each branch. The loop in [install] does nothing but gather those values and
 * obey the answer.
 *
 * ## The airtime rules, and where they come from
 *
 * LoRa is not a network with spare capacity. One LongFast frame occupies the channel for a
 * good fraction of a second and every node in range hears it, so a beacon that is too eager
 * costs everybody else their messages. Meshtastic's own device defaults are the reference:
 * `position_broadcast_secs` is 900 (15 minutes), and "smart position" suppresses a beacon
 * that has not moved. This mirrors both:
 *
 *  - [INTERVAL_MS] — 15 minutes, the floor between two beacons.
 *  - [MIN_MOVE_M] — under 100 m of movement is not worth a frame…
 *  - [MAX_SILENCE_MS] — …but after an hour a stationary node beacons anyway, because a
 *    neighbour that joined (or restarted) since the last one has no way to learn it is
 *    there. "Smart" suppression with no ceiling makes a parked node invisible.
 *
 * ## What this deliberately does NOT do
 *
 * It does not hold a location subscription and it does not run in a foreground service, so
 * it beacons only while the app's process is alive. A beacon that survives the process
 * needs an FGS of type `location`, a second permission dialog, and a notification the user
 * cannot dismiss — a product decision, not a porting gap. [install] is called from
 * `OSHIApplication`, so "while the app is running" is the honest scope and the switch's
 * subtitle says so.
 *
 * It also never beacons without [MeshtasticManager.positionBeaconEnabled], which is false
 * on a fresh install and persisted. [MeshtasticManager.broadcastPosition]'s own header
 * explains what that switch exposes: a public cleartext broadcast on a channel whose key is
 * published in Meshtastic's source, to every radio in range — tens of kilometres, to people
 * the owner has never met and cannot enumerate.
 */
object LoRaPositionBeacon {

    private const val TAG = "LoRaPositionBeacon"

    /** The floor between two beacons. Meshtastic's `position_broadcast_secs` default. */
    const val INTERVAL_MS = 15 * 60 * 1000L

    /** Below this, a new fix is the same place and does not deserve airtime. */
    const val MIN_MOVE_M = 100.0

    /**
     * __DESKTOP_LORA_WRITE_ONCE_2026_10_29__ Effectively never. A fixed position is written into the node's CONFIGURATION; the
     * firmware then announces on its own schedule (at most every six hours for a pinned
     * node). Re-sending the same coordinates on a timer tells the radio nothing it does not
     * know and writes its FLASH, so the ceiling is a year: a position needs writing when it
     * CHANGES, and the "position changed" path already resets this.
     */
    const val MAX_SILENCE_MS = 365L * 24 * 60 * 60 * 1000L

    /** How often [install]'s loop asks the questions. Not how often it beacons. */
    const val TICK_MS = 60 * 1000L

    /**
     * Why a tick did or did not put a frame on the air.
     *
     * An enum rather than a `Boolean` so the reason survives to the log and could be shown:
     * "my position isn't sharing" has six different causes and five of them are the user's
     * to fix. Same shape as [LoRaSendRefusal], for the same reason.
     */
    enum class Decision {
        /** Beacon now. */
        SEND,

        /** The switch is off. The default, and the only state a fresh install has. */
        DISABLED,

        /** No radio attached, or attached and not finished setting up. */
        NO_RADIO,

        /** `ACCESS_FINE_LOCATION` not granted — nothing to send. */
        NO_PERMISSION,

        /** The location source returned nothing, or a 0/0 that means "no lock". */
        NO_FIX,

        /** Inside [INTERVAL_MS] of the last beacon. */
        TOO_SOON,

        /** Past the interval, but we have moved less than [MIN_MOVE_M] and it is not yet [MAX_SILENCE_MS]. */
        STILL_HERE,
    }

    /**
     * The whole policy, as a value.
     *
     * @param lastSentAtMs 0 when nothing has ever been beaconed from this process.
     * @param lastLat,lastLon the fix the last beacon carried; ignored when [lastSentAtMs] is 0.
     */
    fun decide(
        enabled: Boolean,
        radioReady: Boolean,
        hasPermission: Boolean,
        lat: Double?,
        lon: Double?,
        nowMs: Long,
        lastSentAtMs: Long,
        lastLat: Double,
        lastLon: Double,
        intervalMs: Long = INTERVAL_MS,
        minMoveM: Double = MIN_MOVE_M,
        maxSilenceMs: Long = MAX_SILENCE_MS,
    ): Decision {
        // Order matters: the switch is checked FIRST so that turning it off stops the
        // permission and location questions too. Asking a phone for a fine fix on behalf
        // of a feature the user disabled is the kind of thing a privacy label has to
        // declare.
        if (!enabled) return Decision.DISABLED
        if (!radioReady) return Decision.NO_RADIO
        if (!hasPermission) return Decision.NO_PERMISSION
        if (lat == null || lon == null) return Decision.NO_FIX
        // A receiver with no lock reports 0/0, and so does a mocked provider that was
        // never given a fix. Sending it would put this node in the Gulf of Guinea on every
        // map that heard it — `broadcastPosition` refuses it too, and the receive path
        // guards against it; this is the third gate and the only one that can say why.
        if (lat == 0.0 && lon == 0.0) return Decision.NO_FIX
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return Decision.NO_FIX

        if (lastSentAtMs <= 0L) return Decision.SEND
        val since = nowMs - lastSentAtMs
        // A clock that went backwards (NTP step, timezone-ignorant epoch, a restored
        // backup) must not be able to mute the beacon until the clock catches up.
        if (since < 0L) return Decision.SEND
        if (since >= maxSilenceMs) return Decision.SEND
        if (since < intervalMs) return Decision.TOO_SOON
        val moved = CheckInEtaEstimator.greatCircleMetres(lastLat, lastLon, lat, lon)
        return if (moved < minMoveM) Decision.STILL_HERE else Decision.SEND
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var installed = false

    @Volatile private var lastSentAtMs = 0L
    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0

    /** The last [decide] answer, for the log and for anything that wants to explain itself. */
    @Volatile var lastDecision: Decision = Decision.DISABLED
        private set

    /**
     * Start the loop.
     *
     * @param hasPermission asked every tick rather than once: a person can grant location
     *   while the app is running, and a beacon that needed a relaunch to notice would look
     *   exactly like the switch not working.
     * @param currentFix the app's one location source, passed in rather than reached for.
     *   `MeshtasticManager` is a `Context` singleton and `LocationSharingManager` is an
     *   injected one; building a second fused-location client here would be a second answer
     *   to "where are we". `OSHIApplication` injects the real one and hands it over.
     */
    fun install(
        context: Context,
        hasPermission: () -> Boolean,
        currentFix: (callback: (Double?, Double?) -> Unit) -> Unit,
    ) {
        if (installed) return
        installed = true
        val radio = MeshtasticManager.getInstance(context)
        scope.launch {
            while (true) {
                runCatching { tick(radio, hasPermission, currentFix) }
                    .onFailure { Log.w(TAG, "tick failed: ${it.message}") }
                delay(TICK_MS)
            }
        }
        // Turning the switch ON beacons AT ONCE rather than up to [TICK_MS] later.
        // Without this the switch looks broken for a minute: the person flips it, watches
        // the other phone's map, and nothing appears. The counter is cleared on the same
        // transition so the first beacon of a session is never suppressed as TOO_SOON by a
        // position sent before the switch was last turned off.
        scope.launch {
            radio.positionBeaconEnabled.collect { on ->
                if (!on) return@collect
                lastSentAtMs = 0L
                runCatching { tick(radio, hasPermission, currentFix) }
                    .onFailure { Log.w(TAG, "first tick failed: ${it.message}") }
            }
        }
    }

    /**
     * One pass. Internal so a test can drive it without the loop.
     *
     * The cheap gates are evaluated BEFORE the location source is touched, by calling
     * [decide] with a null fix: that returns [Decision.NO_FIX] only once everything else
     * has passed, which is precisely the point at which asking for a fix is justified.
     */
    internal fun tick(
        radio: MeshtasticManager,
        hasPermission: () -> Boolean,
        currentFix: (callback: (Double?, Double?) -> Unit) -> Unit,
    ) {
        val enabled = radio.positionBeaconEnabled.value
        val gate = decide(
            enabled = enabled,
            radioReady = radio.isReady,
            hasPermission = hasPermission(),
            lat = null, lon = null,
            nowMs = System.currentTimeMillis(),
            lastSentAtMs = lastSentAtMs, lastLat = lastLat, lastLon = lastLon,
        )
        if (gate != Decision.NO_FIX) { lastDecision = gate; return }

        currentFix { lat, lon ->
            val d = decide(
                enabled = enabled,
                radioReady = radio.isReady,
                hasPermission = true,
                lat = lat, lon = lon,
                nowMs = System.currentTimeMillis(),
                lastSentAtMs = lastSentAtMs, lastLat = lastLat, lastLon = lastLon,
            )
            lastDecision = d
            if (d != Decision.SEND) return@currentFix
            // __LORA_FIXED_POSITION_2026_10_28__ The ADMIN path, not `broadcastPosition`.
            // A client POSITION_APP broadcast is accepted by the radio and reaches nobody —
            // measured over the air with two radios; see `setFixedPosition`. The interval
            // below is no longer about airtime: this writes the node's CONFIGURATION, so
            // the floor is what stops a tick rewriting flash.
            if (radio.setFixedPosition(lat!!, lon!!)) {
                lastSentAtMs = System.currentTimeMillis()
                lastLat = lat; lastLon = lon
            }
        }
    }

    /** Forget what was last beaconed. For tests, and for the switch going off and on. */
    internal fun resetForTest() {
        lastSentAtMs = 0L; lastLat = 0.0; lastLon = 0.0; lastDecision = Decision.DISABLED
    }
}
