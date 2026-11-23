package com.oshi.messenger.network.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.oshi.messenger.service.LiveLocationService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LocationSharingManager - Manages location sharing functionality
 * Compatible with iOS LocationSharingManager message format
 */
@Singleton
class LocationSharingManager @Inject constructor(
    private val context: Context
) {
    companion object {
        const val MESSAGE_PREFIX = "📍LOCATION📍"
        private const val TAG = "LocationSharingManager"

        // Static storage for received live locations (shared across all instances)
        private val receivedLiveLocationsStatic = mutableMapOf<String, Pair<Double, Double>>()

        /** Called when a live location update is received from a contact */
        fun trackReceivedLiveLocation(sessionId: String, lat: Double, lon: Double) {
            receivedLiveLocationsStatic[sessionId] = Pair(lat, lon)
        }

        /** Get the latest received coordinate for a live session */
        fun getTrackedLiveCoordinate(sessionId: String): Pair<Double, Double>? {
            return receivedLiveLocationsStatic[sessionId]
        }

        /** Clean up when a live session stops */
        fun clearTrackedLiveLocation(sessionId: String) {
            receivedLiveLocationsStatic.remove(sessionId)
        }

        // __NO_BACKGROUND_LOCATION_2026_10_24__ `hasBackgroundLocationPermission`
        // used to live here, as the canonical ACCESS_BACKGROUND_LOCATION check for
        // the armed check-in. OSHI no longer declares that permission (Google Play
        // rejected the app on 2026-10-24 for holding it without a prominent
        // disclosure; see AndroidManifest.xml), so the check could only ever have
        // answered false — a predicate that cannot be true is worse than no
        // predicate, because callers go on branching on it.
        //
        // Both paths that wanted it get the same capability from a location-typed
        // foreground service instead: LiveLocationService for live sharing,
        // CheckInLocationService for the armed check-in. Neither needs more than
        // the while-in-use grant, which `hasLocationPermission` below reports.
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Current location state
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    // Live sharing state
    //
    // __MULTI_LIVE_SHARE_2026_09_22__ Several shares at once, one per conversation
    // (iOS `LocationSharingManager.swift`, same date). The single-share flows below
    // are kept and mirror the MOST RECENT share so nothing that reads them changes
    // meaning; anything that needs one conversation uses [activeLiveShares].
    private val sessions = LiveShareSessions()

    private val _activeLiveShares = MutableStateFlow<List<ActiveLiveShare>>(emptyList())
    /** Every share this device is sending, most recent LAST. */
    val activeLiveShares: StateFlow<List<ActiveLiveShare>> = _activeLiveShares.asStateFlow()

    private val _isLiveSharing = MutableStateFlow(false)
    val isLiveSharing: StateFlow<Boolean> = _isLiveSharing.asStateFlow()

    private val _liveShareEndTime = MutableStateFlow<Long?>(null)
    val liveShareEndTime: StateFlow<Long?> = _liveShareEndTime.asStateFlow()

    private val _liveShareSessionId = MutableStateFlow<String?>(null)
    val liveShareSessionId: StateFlow<String?> = _liveShareSessionId.asStateFlow()

    private val _liveShareConversationId = MutableStateFlow<String?>(null)
    val liveShareConversationId: StateFlow<String?> = _liveShareConversationId.asStateFlow()

    /** ONE location subscription feeds every running share. */
    private var locationCallback: LocationCallback? = null

    /**
     * Per-session hooks. `onStop` fires once when that share ends — manual Stop **or**
     * timer expiry — so the caller can tell the peer the session is over.
     * iOS: `OSHI/LocationSharingManager.swift:39-41`.
     */
    private class SessionHooks(
        val customDuration: Boolean,
        val onUpdate: (LocationMessage) -> Unit,
        val onStop: (() -> Unit)?,
        var endTimer: Timer?
    )
    private val hooks = java.util.concurrent.ConcurrentHashMap<String, SessionHooks>()

    // Permission check
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get current location once
     */
    @SuppressLint("MissingPermission")
    fun getCurrentLocation(callback: (Result<Location>) -> Unit) {
        if (!hasLocationPermission()) {
            callback(Result.failure(SecurityException("Location permission not granted")))
            return
        }

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null
        ).addOnSuccessListener { location ->
            if (location != null) {
                _currentLocation.value = location
                callback(Result.success(location))
            } else {
                // Fallback to last known location if getCurrentLocation returns null
                getLastKnownLocation(callback)
            }
        }.addOnFailureListener { e ->
            // Fallback to last known location on failure (e.g., GPS inactive)
            android.util.Log.w(TAG, "getCurrentLocation failed: ${e.message}, trying last known")
            getLastKnownLocation(callback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(callback: (Result<Location>) -> Unit) {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    _currentLocation.value = location
                    callback(Result.success(location))
                } else {
                    // No cached location — request one via location updates
                    android.util.Log.w(TAG, "No last known location, requesting single update")
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                        .setMaxUpdates(1)
                        .setWaitForAccurateLocation(false)
                        .build()
                    val singleCallback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            fusedLocationClient.removeLocationUpdates(this)
                            val loc = result.lastLocation
                            if (loc != null) {
                                _currentLocation.value = loc
                                callback(Result.success(loc))
                            } else {
                                callback(Result.failure(Exception("Unable to get location")))
                            }
                        }
                    }
                    fusedLocationClient.requestLocationUpdates(
                        request, singleCallback, Looper.getMainLooper()
                    )
                }
            }
            .addOnFailureListener { e ->
                callback(Result.failure(e))
            }
    }

    /**
     * Start live location sharing
     */
    fun startLiveSharing(
        durationMinutes: Int,
        conversationId: String,
        sessionId: String = UUID.randomUUID().toString(),
        onStop: (() -> Unit)? = null,
        onUpdate: (LocationMessage) -> Unit
    ) {
        startSession(conversationId, sessionId, durationMinutes * 60 * 1000L,
            customDuration = false, onStop = onStop, onUpdate = onUpdate)
    }

    /**
     * Start live sharing with custom duration in seconds (for matching sender's time)
     */
    fun startLiveSharingWithCustomDuration(
        durationSeconds: Long,
        conversationId: String,
        sessionId: String = UUID.randomUUID().toString(),
        onStop: (() -> Unit)? = null,
        onUpdate: (LocationMessage) -> Unit
    ) {
        startSession(conversationId, sessionId, durationSeconds * 1000L,
            customDuration = true, onStop = onStop, onUpdate = onUpdate)
    }

    @SuppressLint("MissingPermission")
    private fun startSession(
        conversationId: String,
        sessionId: String,
        durationMillis: Long,
        customDuration: Boolean,
        onStop: (() -> Unit)?,
        onUpdate: (LocationMessage) -> Unit
    ) {
        if (!hasLocationPermission()) {
            android.util.Log.e(TAG, "Location permission not granted")
            return
        }

        // One share per conversation: a new one HERE replaces the old one here.
        // Shares with other conversations keep running.
        sessions.forConversation(conversationId)?.let { stopLiveSharing(sessionId = it.sessionId) }

        android.util.Log.d(TAG, "Starting live location sharing for ${durationMillis / 60_000} minutes " +
            "(${sessions.snapshot().size} other share(s) running)")

        val now = System.currentTimeMillis()
        val endTimer = Timer()
        endTimer.schedule(object : TimerTask() {
            override fun run() {
                stopLiveSharing(sessionId = sessionId)
            }
        }, durationMillis)
        hooks[sessionId] = SessionHooks(customDuration, onUpdate, onStop, endTimer)
        sessions.add(ActiveLiveShare(conversationId, sessionId, now, now + durationMillis))
        publishSessions()

        ensureLocationUpdates()

        // Without a location-typed foreground service Android 10+ throttles us to a few
        // fixes per hour the moment the app leaves the foreground, freezing the peer's
        // dot. iOS's equivalent is `allowsBackgroundLocationUpdates = true`
        // (`OSHI/LocationSharingManager.swift:459-462`). It lives until the LAST share ends.
        LiveLocationService.start(context, sessions.latestEndMillis() ?: 0L)

        android.util.Log.d(TAG, "Live sharing started - session: ${sessionId.take(8)}...")
    }

    @SuppressLint("MissingPermission")
    private fun ensureLocationUpdates() {
        if (locationCallback != null) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L // Update every 10 seconds
        ).apply {
            setMinUpdateIntervalMillis(5_000L) // Min 5 seconds between updates
            setMinUpdateDistanceMeters(10f) // Or if moved 10 meters
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    _currentLocation.value = location
                    for (share in sessions.snapshot()) {
                        val hook = hooks[share.sessionId] ?: continue
                        val remaining = share.remainingSeconds()
                        val updateMessage = if (hook.customDuration) {
                            LocationMessage.liveUpdateWithCustomDuration(
                                location = location,
                                durationSeconds = remaining,
                                sessionId = share.sessionId
                            )
                        } else {
                            LocationMessage.liveUpdate(
                                location = location,
                                durationSeconds = remaining,
                                sessionId = share.sessionId
                            )
                        }
                        hook.onUpdate(updateMessage)
                    }
                }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
    }

    /**
     * Stop EVERY live share. Kept for callers that have no session in hand.
     */
    fun stopLiveSharing() {
        sessions.snapshot().forEach { stopLiveSharing(sessionId = it.sessionId) }
    }

    /** Stop the share running in one conversation, if any. */
    fun stopLiveSharingForConversation(conversationId: String) {
        sessions.forConversation(conversationId)?.let { stopLiveSharing(sessionId = it.sessionId) }
    }

    /**
     * Stop one live share.
     */
    fun stopLiveSharing(sessionId: String) {
        // Nothing to do if that session is not running — prevents a double onStop when
        // both the manual Stop button and the end timer race to call this.
        // iOS: `OSHI/LocationSharingManager.swift:506`.
        sessions.remove(sessionId) ?: return
        android.util.Log.d(TAG, "Stopping live location sharing (session: ${sessionId.take(8)}...)")

        val hook = hooks.remove(sessionId)
        hook?.endTimer?.cancel()
        // Notify the peer that sharing ended (manual OR timer expiry) so their bubble
        // stops showing "live" too.
        hook?.onStop?.invoke()

        publishSessions()

        if (sessions.snapshot().isEmpty()) {
            // Last share gone: give the GPS and the foreground service back.
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
            locationCallback = null
            LiveLocationService.stop(context)
        } else {
            LiveLocationService.start(context, sessions.latestEndMillis() ?: 0L)
        }

        android.util.Log.d(TAG, "Live location sharing STOPPED — ${sessions.snapshot().size} still running")
    }

    /**
     * Extend the most recent live share by additional minutes
     */
    fun extendLiveSharing(additionalMinutes: Int) {
        val latest = sessions.snapshot().lastOrNull()
        if (latest == null) {
            android.util.Log.w(TAG, "Cannot extend - not currently sharing")
            return
        }
        extendLiveSharing(latest.sessionId, additionalMinutes)
    }

    /**
     * Extend one live share by additional minutes
     */
    fun extendLiveSharing(sessionId: String, additionalMinutes: Int) {
        val updated = sessions.extend(sessionId, additionalMinutes * 60 * 1000L)
        if (updated == null) {
            android.util.Log.w(TAG, "Cannot extend - session ${sessionId.take(8)} is not live")
            return
        }
        publishSessions()
        LiveLocationService.start(context, sessions.latestEndMillis() ?: updated.endTimeMillis)

        // Reschedule that session's timer
        hooks[sessionId]?.let { hook ->
            hook.endTimer?.cancel()
            val timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    stopLiveSharing(sessionId = sessionId)
                }
            }, (updated.endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0))
            hook.endTimer = timer
        }

        android.util.Log.d(TAG, "Live sharing extended by $additionalMinutes minutes")
    }

    /** The share running in a conversation, if any. */
    fun liveShareFor(conversationId: String): ActiveLiveShare? = sessions.forConversation(conversationId)

    /**
     * Get remaining time in seconds of the most recent share
     */
    fun getRemainingTimeSeconds(): Long =
        sessions.snapshot().lastOrNull()?.remainingSeconds() ?: 0

    /** Remaining time in seconds of one share (0 when it is not running). */
    fun getRemainingTimeSeconds(sessionId: String): Long =
        sessions.bySession(sessionId)?.remainingSeconds() ?: 0

    /** Mirrors the sessions into the flows, the single-share ones from the most recent. */
    private fun publishSessions() {
        val all = sessions.snapshot()
        val latest = all.lastOrNull()
        _activeLiveShares.value = all
        _isLiveSharing.value = latest != null
        _liveShareEndTime.value = latest?.endTimeMillis
        _liveShareSessionId.value = latest?.sessionId
        _liveShareConversationId.value = latest?.conversationId
    }

    /**
     * Get address for location using Geocoder
     */
    @Suppress("DEPRECATION")
    fun getAddress(location: Location, callback: (String?) -> Unit) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1
                ) { addresses ->
                    val address = addresses.firstOrNull()
                    val addressString = listOfNotNull(
                        address?.thoroughfare,
                        address?.locality,
                        address?.countryName
                    ).joinToString(", ")
                    callback(addressString.ifEmpty { null })
                }
            } else {
                val addresses = geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1
                )
                val address = addresses?.firstOrNull()
                val addressString = listOfNotNull(
                    address?.thoroughfare,
                    address?.locality,
                    address?.countryName
                ).joinToString(", ")
                callback(addressString.ifEmpty { null })
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Geocoding failed: ${e.message}")
            callback(null)
        }
    }

}

/**
 * Location duration options (matching iOS)
 */
enum class LiveDuration(val minutes: Int, val displayName: String) {
    TWO_MINUTES(2, "2 minutes"),
    FIVE_MINUTES(5, "5 minutes"),
    FIFTEEN_MINUTES(15, "15 minutes"),
    THIRTY_MINUTES(30, "30 minutes"),
    ONE_HOUR(60, "1 hour"),
    EIGHT_HOURS(480, "8 hours");

    val seconds: Long get() = minutes * 60L
}

/**
 * Location message data class - compatible with iOS LocationMessage
 */
@Serializable
data class LocationMessage(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Double,
    val isLive: Boolean,
    val expiresAt: Double? = null,
    val accuracy: Double? = null,
    val address: String? = null,
    val sessionId: String? = null,
    val isUpdate: Boolean = false,
    val isStopped: Boolean = false
) {
    companion object {
        const val MESSAGE_PREFIX = "📍LOCATION📍"
        // Apple reference date offset: seconds between Unix epoch (1970) and Jan 1, 2001
        private const val APPLE_EPOCH_OFFSET = 978307200.0
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Convert current time to Apple reference date seconds (matching iOS Date encoding) */
        fun nowAsAppleTimestamp(): Double =
            (System.currentTimeMillis() / 1000.0) - APPLE_EPOCH_OFFSET

        /** Convert Apple reference date seconds to Unix millis */
        fun appleTimestampToUnixMillis(appleTs: Double): Long {
            // Detect old Android format: values > 10 billion are Unix millis
            return if (appleTs > 10_000_000_000.0) {
                appleTs.toLong()
            } else {
                ((appleTs + APPLE_EPOCH_OFFSET) * 1000).toLong()
            }
        }

        /**
         * Create one-time location message
         */
        fun oneTime(location: Location, address: String? = null): LocationMessage {
            return LocationMessage(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = nowAsAppleTimestamp(),
                isLive = false,
                expiresAt = null,
                accuracy = location.accuracy.toDouble(),
                address = address,
                sessionId = null,
                isUpdate = false,
                isStopped = false
            )
        }

        /**
         * Create live location message (initial)
         */
        fun live(
            location: Location,
            duration: LiveDuration,
            address: String? = null,
            sessionId: String? = null
        ): LocationMessage {
            val now = nowAsAppleTimestamp()
            return LocationMessage(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = now,
                isLive = true,
                expiresAt = now + duration.seconds,
                accuracy = location.accuracy.toDouble(),
                address = address,
                sessionId = sessionId ?: UUID.randomUUID().toString(),
                isUpdate = false,
                isStopped = false
            )
        }

        /**
         * Create live location with custom duration in seconds
         */
        fun liveWithCustomDuration(
            location: Location,
            durationSeconds: Long,
            address: String? = null,
            sessionId: String? = null
        ): LocationMessage {
            val now = nowAsAppleTimestamp()
            return LocationMessage(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = now,
                isLive = true,
                expiresAt = now + durationSeconds.toDouble(),
                accuracy = location.accuracy.toDouble(),
                address = address,
                sessionId = sessionId ?: UUID.randomUUID().toString(),
                isUpdate = false,
                isStopped = false
            )
        }

        /**
         * Create live location UPDATE (silent - no notification)
         */
        fun liveUpdate(
            location: Location,
            durationSeconds: Long,
            sessionId: String
        ): LocationMessage {
            val now = nowAsAppleTimestamp()
            return LocationMessage(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = now,
                isLive = true,
                expiresAt = now + durationSeconds.toDouble(),
                accuracy = location.accuracy.toDouble(),
                address = null,
                sessionId = sessionId,
                isUpdate = true,
                isStopped = false
            )
        }

        /**
         * Create live location UPDATE with custom duration
         */
        fun liveUpdateWithCustomDuration(
            location: Location,
            durationSeconds: Long,
            sessionId: String
        ): LocationMessage {
            return liveUpdate(location, durationSeconds, sessionId)
        }

        /**
         * Create STOPPED message - sent when sender manually stops sharing
         */
        fun stopped(sessionId: String, lastLocation: Location? = null): LocationMessage {
            val now = nowAsAppleTimestamp()
            return LocationMessage(
                latitude = lastLocation?.latitude ?: 0.0,
                longitude = lastLocation?.longitude ?: 0.0,
                timestamp = now,
                isLive = true,
                expiresAt = now, // Expired now
                accuracy = lastLocation?.accuracy?.toDouble(),
                address = null,
                sessionId = sessionId,
                isUpdate = true,
                isStopped = true
            )
        }

        /**
         * Whether a raw wire string is a silent live-location ping (update or stop).
         *
         * Port of iOS `LocationMessage.isSilentPushContent(_:)`
         * (`OSHI/LocationSharingManager.swift:848-853`). The static prefix list in
         * `MessageRepository.V2_SILENT_CONTROL_PREFIXES` cannot express this: an
         * update and the initial share carry the same `📍LOCATION📍` prefix and
         * differ only inside the JSON.
         */
        fun isSilentPushContent(content: String): Boolean {
            if (!content.startsWith(MESSAGE_PREFIX)) return false
            return parseQuietly(content)?.isSilentForPush == true
        }

        /**
         * Parse location message from string
         */
        fun fromMessageString(string: String): LocationMessage? {
            val parsed = parseQuietly(string)
            if (parsed == null && string.startsWith(MESSAGE_PREFIX)) {
                android.util.Log.e("LocationMessage", "Failed to parse location payload")
            }
            return parsed
        }

        /** [fromMessageString] without the log line — safe in JVM unit tests. */
        fun parseOrNull(string: String): LocationMessage? = parseQuietly(string)

        /**
         * [fromMessageString] without the log line.
         *
         * The notification decider ([com.oshi.messenger.service.NotificationPresentation])
         * asks this question about every inbound body, and it is deliberately a PURE
         * object exercised in JVM unit tests where `android.util.Log` is the unmocked
         * stub that throws. A malformed — or simply non-location — `📍LOCATION📍` body
         * is an ordinary answer here ("not a silent ping"), not an event worth a log
         * line, so the parse that the decider reaches stays framework-free.
         */
        private fun parseQuietly(string: String): LocationMessage? {
            if (!string.startsWith(MESSAGE_PREFIX)) return null
            val jsonString = string.removePrefix(MESSAGE_PREFIX)
            return try {
                json.decodeFromString<LocationMessage>(jsonString)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Encode to message string
     */
    fun toMessageString(): String {
        return MESSAGE_PREFIX + json.encodeToString(this)
    }

    /**
     * Only the periodic ~10 s pings are silent.
     *
     * A live share produces exactly three things a human cares about — it STARTED,
     * it was EXTENDED, it STOPPED — and roughly one position ping every ten seconds,
     * which is machine traffic. The ping is the only one that must never surface.
     *
     * ⚠️ The subtlety that makes this a one-character-looking rule with a real bug
     * behind it: [stopped] builds the STOP signal with `isUpdate = true` as well
     * (see its factory above — it rides the same in-place-update path on the
     * receiver, `MessageRepository.handleLocationMessage`). So silencing on
     * `isUpdate` alone — which is what this property used to do, as
     * `isUpdate || isStopped`, and the `|| isStopped` term made it explicit — also
     * silences the END of a share. The peer's phone then never learns the dot it is
     * watching has gone dead; it just quietly stops moving.
     *
     * iOS: `isSilentForPush = isUpdate && !isStopped`.
     */
    val isSilentForPush: Boolean
        get() = isUpdate && !isStopped

    /**
     * Check if location is expired
     */
    val isExpired: Boolean
        get() {
            if (isStopped) return true
            val expires = expiresAt ?: return false
            val expiresMillis = appleTimestampToUnixMillis(expires)
            return System.currentTimeMillis() > expiresMillis
        }

    /**
     * Get remaining time in seconds
     *
     * ⏹️ A stopped share has no remaining time, whatever `expiresAt` still says.
     * The STOP payload Android itself builds ([stopped]) sets `expiresAt` to *now*,
     * so the arithmetic below would happen to answer correctly — but an iPhone's
     * STOP keeps the share's ORIGINAL `expiresAt`, and against that payload the
     * gap is still positive and the countdown keeps ticking down to a share that
     * ended minutes ago. [isExpired] already takes `isStopped` first; this reads
     * the same flag so the two can never disagree.
     */
    val remainingTimeSeconds: Long?
        get() {
            if (isStopped) return null
            val expires = expiresAt ?: return null
            val expiresMillis = appleTimestampToUnixMillis(expires)
            val remaining = (expiresMillis - System.currentTimeMillis()) / 1000
            return if (remaining > 0) remaining else null
        }
}
