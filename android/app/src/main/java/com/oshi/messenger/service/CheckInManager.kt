package com.oshi.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.oshi.messenger.R
import com.oshi.messenger.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit

/**
 * Check-In Manager
 * Handles session tracking, geofencing, ETA calculation, and emergency escalation
 * iOS-compatible implementation
 */
class CheckInManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CheckInManager"

        // Configuration
        private const val ARRIVAL_RADIUS_METERS = 100.0  // 100 meters
        private const val WARNING_TIME_AFTER_ETA_MS = 15 * 60 * 1000L  // 15 minutes
        private const val EMERGENCY_TIME_AFTER_WARNING_MS = 15 * 60 * 1000L  // 15 more minutes
        private const val LOCATION_UPDATE_INTERVAL_MS = 30 * 1000L  // 30 seconds

        // Local notifications
        private const val CHECKIN_CHANNEL_ID = "oshi_checkin_safety"
        private const val NOTIF_ID_WARNING = 91_001
        private const val NOTIF_ID_ARRIVED = 91_002
        private const val NOTIF_ID_EMERGENCY = 91_003
        /**
         * Base for notifications about a CONTACT's check-in. Far from the three
         * ids above, which belong to this device's own journey: a contact's
         * arrival must never cancel the user's own "are you okay?" prompt.
         */
        private const val NOTIF_ID_INCOMING_BASE = 92_000_000

        /** Extra set on the launch intent so the UI can jump straight to check-in. */
        const val EXTRA_OPEN_CHECKIN = "oshi.open_checkin"

        private const val PREFS_NAME = "checkin"
        /** __LEAK_FIX_2026_09_25__ sealed home of the active session (was `checkin.xml` / `active_session`). */
        const val CHECKIN_SESSION_FILE = com.oshi.messenger.data.local.atrest.SealedStringStore.CHECKIN_SESSION_FILE
        private const val CHECKIN_SESSION_LABEL = "checkin-session-v1"
        // __NO_BACKGROUND_LOCATION_2026_10_24__ `background_permission_asked` used
        // to live here: the flag that remembered whether the Android 11+ "Allow all
        // the time" dialog had been shown. OSHI no longer declares
        // ACCESS_BACKGROUND_LOCATION (Play rejection of 2026-10-24, see
        // AndroidManifest.xml), so there is no second dialog to remember. The stale
        // key is left in SharedPreferences rather than migrated away; nothing reads
        // it, and clearing it would be a write on every launch for no one.

        // Singleton
        @Volatile
        private var instance: CheckInManager? = null

        fun getInstance(context: Context): CheckInManager {
            return instance ?: synchronized(this) {
                instance ?: CheckInManager(context.applicationContext).also { instance = it }
            }
        }

        private val GROUP_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * __GROUP_CHECK_IN_2026_11_16__ Whether a session's recipient is a GROUP.
         *
         * iOS arms a group check-in with `CheckInSheet(contactPublicKey: group.id.uuidString)`
         * (`GroupViews.swift`), and Android's group sheet does the same, so the recipient is
         * either an OSHI identity (base64 / hex key, never UUID-shaped) or a group UUID. Decided
         * from the SHAPE, not from the loaded group list: after process death
         * `CheckInLocationService` resumes the session before anything has loaded the groups,
         * and the escalation must still reach them rather than a 1:1 send to a key that does
         * not exist.
         */
        fun isGroupRecipient(recipient: String?): Boolean =
            recipient != null && GROUP_ID.matches(recipient.trim())
    }

    // Published state
    private val _activeSession = MutableStateFlow<CheckInSession?>(null)
    val activeSession: StateFlow<CheckInSession?> = _activeSession.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _distanceToDestination = MutableStateFlow<Float?>(null)
    val distanceToDestination: StateFlow<Float?> = _distanceToDestination.asStateFlow()

    private val _showWarningAlert = MutableStateFlow(false)
    val showWarningAlert: StateFlow<Boolean> = _showWarningAlert.asStateFlow()

    // Callbacks — a UI observer, NOT the transport. See [dispatch].
    private var onMessageSend: ((CheckInMessage) -> Unit)? = null

    /**
     * Hilt entry point, mirroring [NotificationActionReceiver.NotificationActionEntryPoint]:
     * this class is a hand-rolled singleton (`getInstance`), not a Hilt one, and must
     * still reach the repository. Resolved lazily so constructing the manager — which
     * happens from `CheckInLocationService.onStartCommand` on a redelivery — never
     * depends on graph readiness.
     */
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface CheckInEntryPoint {
        fun messageRepository(): com.oshi.messenger.data.repository.MessageRepository
        /** __GROUP_CHECK_IN_2026_11_16__ a session armed from a GROUP reports to the group. */
        fun groupManager(): GroupManager
    }

    private val sendScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * The coroutine delivering the most recently dispatched check-in message.
     *
     * Exists for [awaitPendingSend]: when the user answers from the lock screen the
     * process is alive only for as long as `CheckInActionReceiver`'s `goAsync()`
     * keeps it, and a fire-and-forget `launch` would be killed mid-send.
     */
    @Volatile
    private var lastSendJob: Job? = null

    /**
     * Block until the last dispatched message has been sent (or the timeout expires).
     *
     * Called from `CheckInActionReceiver` while it holds `goAsync()`. The timeout is
     * generous but finite: a broadcast receiver that never finishes is an ANR, and a
     * mesh send with no reachable peer can hang far longer than the message is worth.
     */
    suspend fun awaitPendingSend(timeoutMs: Long = 10_000) {
        val job = lastSendJob ?: return
        withTimeoutOrNull(timeoutMs) { job.join() }
            ?: Log.w(TAG, "⏱️ CheckIn: send still in flight after ${timeoutMs}ms")
    }

    /**
     * 🔴 P0 FIX (2026-08-24 feature check) — the alert used to never leave the device.
     *
     * `onMessageSend` was the ONLY thing every check-in message was handed to, and its
     * one production caller passed `{ _ -> showCheckInSheet = false }`
     * (`ui/screens/ChatScreen.kt`). So `started`, `warning`, `arrived`, `cancelled`,
     * `extended` and `emergency` were all built, serialized correctly, and dropped:
     * `CheckInMessage.toMessageString()` had zero production call sites, while
     * [sendEmergencyNotification] told the USER "Emergency Alert Sent". A
     * personal-safety feature that reassures the person in danger and tells nobody
     * else is worse than one that is absent.
     *
     * Three reasons the send lives HERE and not in the screen:
     *  1. the alert fires 15–30 minutes after the sheet is dismissed, by which time a
     *     ViewModel-scoped lambda's scope is cancelled and the send is a no-op;
     *  2. after process death `CheckInLocationService.resumeOrStop()` rebuilds this
     *     singleton and `onMessageSend` is null — the session resumed and could never
     *     have alerted anyone;
     *  3. the session already knows the recipient (`session.contactPublicKey`), so the
     *     screen was never needed to address the message.
     *
     * iOS sends from `ChatView.sendCheckInMessage` (`ChatView.swift:2422-2450`) and
     * gets away with it because its manager is a `@StateObject` on a view that stays
     * alive; the Android equivalent is the singleton.
     *
     * `update` is DELIBERATELY NOT SENT. iOS sends it with `skipLocalMessage: true`
     * (`ChatView.swift:2432-2439`) precisely so the 5-minute position pings do not
     * "flood the chat with Location Update messages"; `MessageRepository.sendTextMessage`
     * has no such flag, so sending it here would insert a fresh bubble every five
     * minutes on BOTH sides. Dropping it costs the peer a moving dot and keeps every
     * message that carries a decision. Restoring it needs a `skipLocalMessage`
     * parameter on the repository's send, which is the tracked follow-up.
     */
    private fun dispatch(message: CheckInMessage) {
        // Keep the UI observer working exactly as before, whatever the transport does.
        onMessageSend?.invoke(message)

        if (message.type == CheckInMessageType.update) {
            Log.d(TAG, "\uD83D\uDEE1\uFE0F CheckIn: update not sent (no skipLocalMessage) — see dispatch() kdoc")
            return
        }
        val recipient = _activeSession.value?.contactPublicKey
        if (recipient.isNullOrBlank()) {
            Log.w(TAG, "\uD83D\uDEE1\uFE0F CheckIn: no session recipient — ${message.type} NOT sent")
            return
        }
        val payload = try {
            message.toMessageString()
        } catch (e: Exception) {
            Log.e(TAG, "\u274C CheckIn: could not serialize ${message.type}: ${e.message}")
            return
        }
        lastSendJob = sendScope.launch {
            try {
                val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    CheckInEntryPoint::class.java
                )
                if (isGroupRecipient(recipient)) {
                    // The group's own E2E fan-out; every member's app (Android, iOS,
                    // desktop) already draws `🛡️CHECK_IN🛡️` as a check-in bubble.
                    entry.groupManager().sendGroupMessage(groupId = recipient, content = payload)
                    Log.d(TAG, "\uD83D\uDEE1\uFE0F CheckIn: sent ${message.type} to group ${recipient.take(8)}\u2026")
                    return@launch
                }
                val repo = entry.messageRepository()
                val result = repo.sendTextMessage(recipient, payload)
                if (result.isSuccess) {
                    Log.d(TAG, "\uD83D\uDEE1\uFE0F CheckIn: sent ${message.type} to ${recipient.take(8)}\u2026")
                } else {
                    Log.e(TAG, "\u274C CheckIn: ${message.type} send FAILED: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "\u274C CheckIn: ${message.type} send threw: ${e.message}")
            }
        }
    }

    // Location
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationCallback: LocationCallback
    private var monitoringJob: Job? = null
    private var deadlineJob: Job? = null

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // __INIT_ORDER_2026_09_28__ Declared BEFORE `init`: Kotlin runs property initializers and init blocks in
    // source order, and `init` reads this store — declared below it, the lazy delegate was
    // still null and the constructor threw a NullPointerException (1.6.32/73).
    private val sessionStore by lazy {
        com.oshi.messenger.data.local.atrest.SealedStringStore.inFiles(
            context,
            fileName = CHECKIN_SESSION_FILE,
            label = CHECKIN_SESSION_LABEL,
            legacy = com.oshi.messenger.data.local.atrest.SealedStringStore.prefsLegacy(
                context, "checkin", "active_session",
            ),
        )
    }

    init {
        // Setup location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    _currentLocation.value = location
                    checkProgress(location)
                }
            }
        }

        // Load persisted session
        loadPersistedSession()
    }

    // MARK: - Session Management

    suspend fun startCheckIn(
        contactPublicKey: String,
        destinationLatitude: Double,
        destinationLongitude: Double,
        destinationAddress: String,
        transportMode: TransportMode,
        estimatedArrivalTimeMs: Long,
        extraTimeMinutes: Int = 0,
        onMessageSend: (CheckInMessage) -> Unit
    ) {
        // Create session
        val session = CheckInSession(
            contactPublicKey = contactPublicKey,
            destinationLatitude = destinationLatitude,
            destinationLongitude = destinationLongitude,
            destinationAddress = destinationAddress,
            transportMode = transportMode,
            estimatedArrivalTime = estimatedArrivalTimeMs,
            extraTimeMinutes = extraTimeMinutes
        )

        _activeSession.value = session
        this.onMessageSend = onMessageSend
        _isMonitoring.value = true

        // Persist session
        persistSession(session)

        // Start monitoring
        startLocationMonitoring()
        startDeadlineMonitoring()
        CheckInDeadlineAlarm.schedule(context, session.effectiveDeadline)

        // Send initial message
        val senderName = getSenderName()
        val message = CheckInMessage.started(session, senderName)
        dispatch(message)

        Log.d(TAG, "\uD83D\uDEE1\uFE0F CheckIn: Started session ${session.id}")
        Log.d(TAG, "   Destination: $destinationAddress")
        Log.d(TAG, "   ETA: ${session.formattedRemainingTime}")
        Log.d(TAG, "   Mode: ${transportMode.name}")
    }

    fun stopCheckIn(reason: CheckInStatus = CheckInStatus.cancelled) {
        val session = _activeSession.value ?: return

        // Stop monitoring
        stopLocationMonitoring()
        stopDeadlineMonitoring()

        // Update status and send message
        when (reason) {
            CheckInStatus.arrived -> {
                val senderName = getSenderName()
                val message = CheckInMessage.arrived(
                    sessionId = session.id,
                    destinationAddress = session.destinationAddress,
                    senderName = senderName
                )
                dispatch(message)
                Log.d(TAG, "\u2705 CheckIn: Arrived safely at ${session.destinationAddress}")
            }
            CheckInStatus.cancelled -> {
                val message = CheckInMessage.cancelled(sessionId = session.id)
                dispatch(message)
                Log.d(TAG, "\uD83D\uDEAB CheckIn: Cancelled by user")
            }
            else -> {}
        }

        // Clear session
        _activeSession.value = null
        _isMonitoring.value = false
        _showWarningAlert.value = false
        clearPersistedSession()
        CheckInDeadlineAlarm.cancel(context)
        // A journey that is over must not leave "Are you okay?" sitting in the
        // shade with live buttons on it.
        androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIF_ID_WARNING)
    }

    fun extendTime(minutes: Int) {
        val session = _activeSession.value ?: return

        session.extend(minutes)
        _activeSession.value = session
        persistSession(session)
        // Every extension restarts the warning-then-escalate cycle (see
        // `CheckInSession.extend`), so the alarms move with it.
        CheckInDeadlineAlarm.schedule(context, session.effectiveDeadline)
        androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIF_ID_WARNING)

        // Send extended message
        val message = CheckInMessage.extended(session.id, session.effectiveDeadline)
        dispatch(message)

        // Reset warning state
        _showWarningAlert.value = false

        // The ongoing notification IS the check-in status; a stale ETA on it
        // would tell the user the opposite of what they just did.
        CheckInLocationService.start(
            context = context,
            destination = session.destinationAddress,
            etaText = session.formattedRemainingTime,
        )

        Log.d(TAG, "\u23F0 CheckIn: Extended by $minutes minutes. New deadline: ${session.formattedRemainingTime}")
    }

    fun confirmSafe() {
        _showWarningAlert.value = false
        extendTime(15)
        Log.d(TAG, "\uD83D\uDC4D CheckIn: User confirmed safe, extended time")
    }

    // ========================================================================
    // THE OFF-RAMP
    // ========================================================================

    /**
     * The single entry point for every "I'm fine" the user can press.
     *
     * \uD83D\uDD34 P0 FIX (2026-08-24 feature check). [stopCheckIn], [confirmSafe] and
     * [extendTime] had **zero call sites**: no notification action, no banner, no
     * dialog. A user who armed a check-in could not end it, so every overdue session
     * ran the full clock to [triggerEmergency] and sent their location and battery
     * level to a contact. The feature could only produce false alarms.
     *
     * Funnelling all four affordances through one method is deliberate: it is the
     * function reachability is argued about, and the notification action, the
     * in-chat banner and the warning dialog are three callers of the same thing
     * rather than three re-implementations that can drift.
     *
     * Note the guard is on `_activeSession.value != null`, **not** on
     * [hasActiveSession]. `hasActiveSession` requires `status == active`, and the
     * exact moment the user most needs this \u2014 after the "Are you okay?" prompt \u2014 the
     * status is `expired`. Checking `hasActiveSession` here would have refused every
     * press of "I'm safe".
     *
     * @return false when there was no session to act on (a stale notification the
     *   user tapped after the session already ended).
     */
    fun applyUserAction(action: CheckInUserAction, extendMinutes: Int = 15): Boolean {
        if (_activeSession.value == null) {
            // Stale button. Take the notification away rather than leaving a control
            // that does nothing.
            androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIF_ID_WARNING)
            CheckInLocationService.stop(context)
            CheckInDeadlineAlarm.cancel(context)
            return false
        }
        when (action) {
            CheckInUserAction.CONFIRM_SAFE -> confirmSafe()
            CheckInUserAction.ARRIVED -> stopCheckIn(CheckInStatus.arrived)
            CheckInUserAction.EXTEND -> extendTime(extendMinutes)
            CheckInUserAction.CANCEL -> stopCheckIn(CheckInStatus.cancelled)
        }
        _showWarningAlert.value = false
        androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIF_ID_WARNING)
        return true
    }

    /**
     * Run the escalation check immediately, outside the minute loop.
     *
     * Called by `CheckInActionReceiver` when the deadline alarm fires, which is the
     * path that works when the app's own process is gone.
     */
    fun runDeadlineCheckNow() = checkDeadline()

    /**
     * A session the user can still act on \u2014 active **or** expired.
     *
     * [hasActiveSession] deliberately keeps its narrower meaning (it gates whether
     * the foreground service should stay up); this one gates the UI, and mirrors
     * iOS's banner condition `status == .active || status == .expired`
     * (`OSHI/CheckInView.swift:540`).
     */
    val hasOpenSession: Boolean
        get() = CheckInServicePolicy.shouldServiceRun(_activeSession.value?.status)

    // MARK: - Location Monitoring

    private fun startLocationMonitoring() {
        // iOS turns background location ON here and ONLY here, for the duration
        // of an armed session (CheckInManager.swift:236-238). Android's
        // equivalent capability is a running foreground service typed
        // `location`, so it is started at exactly the same point in the
        // lifecycle \u2014 and torn down in stopLocationMonitoring below, mirroring
        // the OFF flip at CheckInManager.swift:256.
        // __NO_BACKGROUND_LOCATION_2026_10_24__ No ACCESS_BACKGROUND_LOCATION to
        // check: the service below IS the capability, and it is started from the
        // arming screen, i.e. while OSHI is on screen, so it keeps receiving
        // positions for the whole journey on the while-in-use grant alone. What it
        // does not survive — a swipe-away, an OEM battery saver — is stated on the
        // arming screen and on this service's own notification.
        val session = _activeSession.value
        CheckInLocationService.start(
            context = context,
            destination = session?.destinationAddress ?: "",
            etaText = session?.formattedRemainingTime ?: "",
        )

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LOCATION_UPDATE_INTERVAL_MS
            ).setMinUpdateDistanceMeters(50f).build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                context.mainLooper
            )

            Log.d(TAG, "\uD83D\uDCCD CheckIn: Started location monitoring (foreground service ON)")
        } catch (e: SecurityException) {
            // No location grant at all: the session cannot detect arrival and the
            // emergency alert would carry no position. Do not keep a
            // location-typed service alive on a monitor that is not monitoring.
            Log.e(TAG, "\u274C CheckIn: Location permission denied: ${e.message}")
            CheckInLocationService.stop(context)
        }
    }

    private fun stopLocationMonitoring() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        // Mirrors CheckInManager.swift:256 \u2014 stop touching location the moment
        // the check-in ends, rather than leaving a background-capable service up.
        CheckInLocationService.stop(context)
        Log.d(TAG, "\uD83D\uDCCD CheckIn: Stopped location monitoring (foreground service OFF)")
    }

    // MARK: - Deadline Monitoring

    /**
     * 🔋 DELIBERATELY NOT BACKED OFF (2026-08-23 battery pass).
     *
     * Every other recurring loop in the app now slows down or stops when the app is
     * backgrounded. This one does not, and must not. It is only ever running while a
     * check-in session is actually open ([startDeadlineMonitoring] is called from
     * `startCheckIn` and from session restore, and [stopDeadlineMonitoring] kills it
     * when the session ends), so it costs nothing at rest — and while it IS running,
     * the whole point of the feature is that the alert fires on time when the user has
     * put the phone in their pocket. It also touches no network: it compares a
     * deadline against the clock. Slowing it would trade zero battery for a late
     * personal-safety alarm.
     */
    private fun startDeadlineMonitoring() {
        deadlineJob = scope.launch {
            while (isActive) {
                delay(60_000)  // Check every minute
                checkDeadline()
            }
        }
        Log.d(TAG, "\u23F1\uFE0F CheckIn: Started deadline monitoring")
    }

    private fun stopDeadlineMonitoring() {
        deadlineJob?.cancel()
        deadlineJob = null
        Log.d(TAG, "\u23F1\uFE0F CheckIn: Stopped deadline monitoring")
    }

    // MARK: - Progress Checking

    private fun checkProgress(currentLocation: Location) {
        val session = _activeSession.value ?: return

        // Calculate distance to destination
        val destinationLocation = Location("").apply {
            latitude = session.destinationLatitude
            longitude = session.destinationLongitude
        }
        val distance = currentLocation.distanceTo(destinationLocation)

        // A session with no destination fix is a TIME-ONLY check-in: the user closes
        // it themselves. Without this guard the arrival test measures the distance to
        // Null Island (0, 0) — which every Android session used to be armed at — and
        // would "detect arrival" for anyone within 100 m of a point in the Gulf of
        // Guinea while reporting a meaningless distance to everyone else.
        if (!session.hasDestinationFix) {
            _distanceToDestination.value = null
        } else {
            _distanceToDestination.value = distance
            if (distance <= ARRIVAL_RADIUS_METERS) {
                handleArrival()
            }
        }

        // Send periodic location update (every 5 minutes)
        val timeSinceLastUpdate = System.currentTimeMillis() - session.lastUpdateTime
        if (timeSinceLastUpdate >= 5 * 60 * 1000) {
            val message = CheckInMessage.update(
                sessionId = session.id,
                latitude = currentLocation.latitude,
                longitude = currentLocation.longitude
            )
            dispatch(message)

            session.lastUpdateTime = System.currentTimeMillis()
            _activeSession.value = session
            persistSession(session)
        }
    }

    /**
     * The escalation gate. The *decision* lives in [CheckInEscalationPolicy] \u2014 read
     * its KDoc for why each branch is what it is; this method only carries it out.
     *
     * Two things changed here beyond moving the arithmetic out:
     *
     *  1. the warning stamp is written **only if the notification was delivered**.
     *     It used to be written unconditionally, so on a phone where
     *     `POST_NOTIFICATIONS` was denied [postNotification] dropped the prompt, the
     *     user was never asked anything, and an emergency alert reached their
     *     contact fifteen minutes later regardless;
     *  2. the `warning` message to the contact is sent once (guarded by
     *     `warningSentToContactAt`), so a warning that keeps failing to display
     *     locally does not spam the peer with one bubble a minute.
     */
    private fun checkDeadline() {
        val session = _activeSession.value ?: return
        if (session.status == CheckInStatus.emergency) return

        val now = System.currentTimeMillis()
        when (
            CheckInEscalationPolicy.decide(
                nowMs = now,
                deadlineMs = session.effectiveDeadline,
                warningDeliveredAtMs = session.warningShownAt,
                emergencyAfterWarningMs = EMERGENCY_TIME_AFTER_WARNING_MS,
            )
        ) {
            CheckInEscalationPolicy.Decision.Wait -> Unit

            CheckInEscalationPolicy.Decision.ClockUntrusted -> {
                Log.w(
                    TAG,
                    "\uD83D\uDD70\uFE0F CheckIn: wall clock moved backwards since the warning \u2014 " +
                        "re-warning instead of escalating on arithmetic we cannot trust"
                )
                session.forgetWarningForClockChange()
                _activeSession.value = session
                persistSession(session)
            }

            CheckInEscalationPolicy.Decision.Warn -> {
                val alreadyToldContact = session.warningSentToContactAt != null
                if (!alreadyToldContact) session.markExpired()

                // Stamp the warning ONLY if it actually reached the tray.
                val delivered = sendLocalWarningNotification()
                if (delivered) session.markWarningDelivered()

                _activeSession.value = session
                persistSession(session)
                _showWarningAlert.value = true

                if (!alreadyToldContact) {
                    dispatch(CheckInMessage.warning(session.id))
                }

                if (delivered) {
                    Log.w(TAG, "\u26A0\uFE0F CheckIn: Warning - past ETA, waiting for response")
                } else {
                    // No escalation can follow: the policy refuses to escalate on a
                    // null stamp. That is the deliberate fail-safe direction \u2014 never
                    // alert a third party about someone we could not ask.
                    Log.e(
                        TAG,
                        "\u26A0\uFE0F CheckIn: warning could NOT be delivered (notifications off?) \u2014 " +
                            "will retry; emergency escalation stays disarmed"
                    )
                }
            }

            CheckInEscalationPolicy.Decision.Escalate -> triggerEmergency()
        }
    }

    // MARK: - Arrival Handling

    private fun handleArrival() {
        // Capture the address BEFORE stopping: `stopCheckIn` clears
        // `_activeSession`, and the arrival notification body interpolates the
        // destination ("...you arrived safely at %s"), so reading it afterwards
        // would always render an empty place name.
        val destination = _activeSession.value?.destinationAddress ?: return

        Log.d(TAG, "\uD83C\uDF89 CheckIn: Detected arrival at destination!")

        // Auto-confirm arrival
        stopCheckIn(CheckInStatus.arrived)

        // Send local notification. This runs AFTER `stopCheckIn` has torn the
        // foreground service down (same call stack, same thread, so the process
        // is still very much alive) — matching iOS, which also stops the session
        // before notifying (CheckInManager.swift:382-385). The alert goes out on
        // the separate high-importance `oshi_checkin_safety` channel, not the
        // service's silent ongoing one, so it still buzzes.
        sendArrivalNotification(destination)
    }

    // MARK: - Emergency Escalation

    private fun triggerEmergency() {
        val session = _activeSession.value ?: return

        Log.e(TAG, "\uD83D\uDEA8 CheckIn: EMERGENCY - Sending emergency data to contact")

        // Collect emergency data
        val emergencyData = collectEmergencyData()

        // Update session
        session.markEmergency()
        _activeSession.value = session
        persistSession(session)

        // Send emergency message
        val senderName = getSenderName()
        val message = CheckInMessage.emergency(
            sessionId = session.id,
            emergencyData = emergencyData,
            destinationAddress = session.destinationAddress,
            senderName = senderName
        )
        dispatch(message)

        // Send urgent local notification
        sendEmergencyNotification()

        // Stop monitoring
        stopLocationMonitoring()
        stopDeadlineMonitoring()
        CheckInDeadlineAlarm.cancel(context)
        // The "Are you okay?" prompt has been overtaken by events; its buttons would
        // now act on a session that has already escalated.
        androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIF_ID_WARNING)
        _showWarningAlert.value = false
    }

    // MARK: - Emergency Data Collection

    private fun collectEmergencyData(): EmergencyData {
        val location = _currentLocation.value ?: Location("")
        val batteryLevel = getBatteryLevel()
        val isCharging = isBatteryCharging()
        val networkType = getNetworkType()

        return EmergencyData(
            lastKnownLatitude = location.latitude,
            lastKnownLongitude = location.longitude,
            lastLocationTime = location.time,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkType = networkType,
            deviceName = Build.MODEL,
            lastMovementTime = location.time
        )
    }

    private fun getBatteryLevel(): Double {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level / 100.0
    }

    private fun isBatteryCharging(): Boolean {
        val batteryStatus = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getNetworkType(): String {
        // Simplified network type detection
        return "Cellular"  // Can be enhanced with ConnectivityManager
    }

    // MARK: - Notifications
    //
    // These were three `TODO` stubs that only wrote a Log line. iOS posts three
    // real `UNNotificationRequest`s (CheckInManager.swift:456-500), and the
    // WARNING one is safety-critical: it is the user's ONLY prompt to tap
    // "I'm safe" during the 15 minutes between the missed ETA and the automatic
    // emergency alert. Without it a benign delay escalated, silently, into a
    // false emergency reaching the user's contact \u2014 and when the emergency did
    // fire the user got no confirmation that their location had been shared.
    //
    // Copy and timing are ported from iOS: same three titles/bodies (the keys
    // `checkin.warning.title`, `checkin.arrived.title/body`,
    // `checkin.emergency.title/body`), same trigger points (warning at
    // deadline, arrival on geofence entry, emergency at deadline + 15 min).

    /**
     * Channel is created here rather than in a shared notification helper: no
     * such helper exists in this codebase (each service declares its own via
     * `NotificationManager.createNotificationChannel`), and the check-in channel
     * must be user-separable from Messages/Calls so muting chat cannot mute a
     * safety alert. Creating an existing channel is a documented no-op.
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(CHECKIN_CHANNEL_ID) != null) return
        val channel = android.app.NotificationChannel(
            CHECKIN_CHANNEL_ID,
            context.getString(R.string.checkin_notification_channel_name),
            android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.checkin_notification_channel_description)
            enableVibration(true)
            enableLights(true)
            // iOS uses `UNNotificationSound.defaultCritical` for the emergency
            // notification. Android's nearest equivalent that does not require a
            // special-use permission is bypassing Do Not Disturb on the channel.
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** Tapping any check-in notification opens the app, where `showWarningAlert` drives the in-app "I'm safe" dialog. */
    private fun contentIntent(notificationId: Int): android.app.PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_CHECKIN, true)
        return android.app.PendingIntent.getActivity(
            context, notificationId, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * @param actions buttons rendered on the notification, in order. Android shows
     *   at most three, which is exactly what the warning carries.
     * @return whether the notification was actually handed to the system. **The
     *   return value is load-bearing**: `checkDeadline` starts the fifteen-minute
     *   escalation clock only when the warning was really delivered, so that a phone
     *   with notifications turned off can no longer produce an emergency alert about
     *   a user who was never prompted.
     */
    private fun postNotification(
        notificationId: Int,
        title: String,
        body: String,
        priorityMax: Boolean,
        actions: List<androidx.core.app.NotificationCompat.Action> = emptyList(),
        ongoing: Boolean = false,
    ): Boolean {
        // POST_NOTIFICATIONS is declared in the manifest and runtime-required on
        // Android 13+. Posting without it throws nothing \u2014 the notification is
        // just dropped \u2014 so check and log rather than pretend it was delivered.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "\u26A0\uFE0F POST_NOTIFICATIONS not granted \u2014 check-in alert #$notificationId NOT delivered")
                return false
            }
        }
        try {
            ensureNotificationChannel()
            val builder = androidx.core.app.NotificationCompat.Builder(context, CHECKIN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setPriority(
                    if (priorityMax) androidx.core.app.NotificationCompat.PRIORITY_MAX
                    else androidx.core.app.NotificationCompat.PRIORITY_HIGH
                )
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                // The warning is a QUESTION, not an announcement: it must stay in the
                // shade until the user answers it, or the off-ramp disappears the
                // first time they swipe their notifications away.
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
            actions.forEach { builder.addAction(it) }
            contentIntent(notificationId)?.let { builder.setContentIntent(it) }
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(notificationId, builder.build())
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "\u274C Failed to post check-in notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "\u274C Failed to post check-in notification: ${e.message}")
        }
        return false
    }

    /**
     * The user's only prompt between a missed ETA and an automatic emergency alert \u2014
     * and, until this pass, a notification with **no buttons on it**.
     *
     * The three actions are ported one for one from iOS's `CHECKIN_WARNING`
     * category (`OSHI/NotificationManager.swift:163-183`): "I'm Safe",
     * "I've Arrived", "+15 min", handled at `:1243-1259` by the same three manager
     * calls this one reaches through [CheckInActionReceiver]. Android renders at most
     * three actions, so this is the whole set with nothing dropped.
     *
     * @return whether the prompt reached the tray. See [postNotification].
     */
    private fun sendLocalWarningNotification(): Boolean {
        val delivered = postNotification(
            notificationId = NOTIF_ID_WARNING,
            title = context.getString(R.string.checkin_warning_title),
            body = context.getString(R.string.checkin_warning_body),
            priorityMax = false,
            actions = listOf(
                notificationAction(
                    R.string.checkin_warning_action_safe,
                    CheckInActionReceiver.ACTION_SAFE
                ),
                notificationAction(
                    R.string.checkin_arrived,
                    CheckInActionReceiver.ACTION_ARRIVED
                ),
                notificationAction(
                    R.string.checkin_extend_15,
                    CheckInActionReceiver.ACTION_EXTEND
                ),
            ),
            ongoing = true,
        )
        Log.d(TAG, "\uD83D\uDD14 Check-in WARNING notification delivered=$delivered")
        return delivered
    }

    /**
     * One notification button.
     *
     * `ic_notification` for every action rather than three glyphs: Android has not
     * rendered action icons on phones since API 24, and inventing three drawables
     * that nothing displays is cost with no benefit. The label is what the user
     * reads, and it comes from resources in all 34 locales.
     */
    private fun notificationAction(
        labelRes: Int,
        action: String,
        minutes: Int = 15,
    ): androidx.core.app.NotificationCompat.Action =
        androidx.core.app.NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(labelRes),
            CheckInActionReceiver.actionIntent(context, action, minutes)
        ).build()

    private fun sendArrivalNotification(destinationAddress: String) {
        postNotification(
            notificationId = NOTIF_ID_ARRIVED,
            title = context.getString(R.string.checkin_arrived_title),
            body = context.getString(R.string.checkin_arrived_body, destinationAddress),
            priorityMax = false,
        )
        // A completed journey should not leave the "are you okay?" prompt sitting
        // in the shade.
        androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIF_ID_WARNING)
        Log.d(TAG, "\uD83D\uDD14 Posted check-in ARRIVAL notification")
    }

    private fun sendEmergencyNotification() {
        postNotification(
            notificationId = NOTIF_ID_EMERGENCY,
            title = context.getString(R.string.checkin_emergency_title),
            body = context.getString(R.string.checkin_emergency_body),
            priorityMax = true,
        )
        Log.d(TAG, "\uD83D\uDD14 Posted check-in EMERGENCY notification")
    }

    // MARK: - Persistence
    //
    // __LEAK_FIX_2026_09_25__ The active session (destination ADDRESS, its GPS
    // coordinates, the last known position, the emergency contacts) was a cleartext
    // JSON string in `shared_prefs/checkin.xml`. It now lives sealed at rest in
    // `files/checkin_session.sealed` ([SealedStringStore]); an existing session is
    // migrated on the first load, so a check-in running across the update resumes.


    private fun persistSession(session: CheckInSession) {
        val gson = com.google.gson.Gson()
        try {
            sessionStore.write(gson.toJson(session))
        } catch (e: Exception) {
            Log.e(TAG, "\u274C CheckIn: Failed to persist session: ${e.message}")
        }
    }

    private fun loadPersistedSession() {
        val sessionJson = sessionStore.read() ?: return

        try {
            val gson = com.google.gson.Gson()
            val session = gson.fromJson(sessionJson, CheckInSession::class.java)

            // \uD83D\uDD34 `expired` MUST resume too. It used to be discarded here, and
            // `expired` is precisely the state a session is in between the
            // "Are you okay?" prompt and the emergency alert. So a process kill in
            // that fifteen-minute window threw the session away: the user's
            // "I'm safe" from the lock screen would have found nothing to apply
            // itself to, and a genuine escalation would never have fired either.
            // Both halves of the feature live or die on this branch.
            if (session.status == CheckInStatus.active || session.status == CheckInStatus.expired) {
                _activeSession.value = session
                _isMonitoring.value = true

                // Resume monitoring
                startLocationMonitoring()
                startDeadlineMonitoring()
                CheckInDeadlineAlarm.schedule(context, session.effectiveDeadline)
                if (session.status == CheckInStatus.expired) _showWarningAlert.value = true

                Log.d(TAG, "\uD83D\uDEE1\uFE0F CheckIn: Resumed ${session.status} session ${session.id}")
            } else {
                clearPersistedSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "\u274C CheckIn: Failed to load persisted session: ${e.message}")
            clearPersistedSession()
        }
    }

    private fun clearPersistedSession() {
        sessionStore.clear()
    }

    // MARK: - Helpers

    private fun getSenderName(): String? {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        return prefs.getString("display_name", null)
    }

    val hasActiveSession: Boolean
        get() = _activeSession.value?.status == CheckInStatus.active

    fun getActiveSessionForContact(contactKey: String): CheckInSession? {
        val session = _activeSession.value ?: return null
        return if (session.contactPublicKey == contactKey && session.status == CheckInStatus.active) {
            session
        } else {
            null
        }
    }

    // ========================================================================
    // RECEIVING a contact's check-in
    // ========================================================================

    /**
     * A check-in has arrived from a contact.
     *
     * 🔴 WHAT THIS WAS. A seven-branch `when` in which **every branch was a `Log`
     * line**, three of them carrying a `// Should show notification` comment. So an
     * iPhone user's real **emergency alert** arriving on an Android phone produced
     * one logcat line and nothing else — no notification, no sound, no banner. The
     * person who pressed the button was told "Emergency Alert Sent"; the person who
     * could have helped was told nothing. That is the most serious defect in this
     * pass and it is why the notification is raised HERE, in the singleton, and not
     * in a screen that may not be running.
     *
     * The bubble and the conversation-list row are handled separately, by
     * `MessageRepository.handleCheckInMessage` inserting the row — see the note
     * there. A notification alone would be a banner with nothing behind it.
     *
     * Ported from `OSHI/NotificationManager.swift:519-535` (one body per type),
     * `:390-393` (critical sound for `.emergency`) and `:300-306` (a location
     * `update` raises NO banner — see [CheckInMessage.incomingNotificationBody]).
     */
    fun processIncomingCheckInMessage(senderKey: String, content: String) {
        val message = CheckInMessage.fromMessageString(content) ?: run {
            Log.e(TAG, "\u274C Failed to parse check-in message")
            return
        }

        Log.d(TAG, "\uD83D\uDEE1\uFE0F Received check-in message: ${message.type}")

        val body = message.incomingNotificationBody ?: run {
            // `update` only. iOS silences it for the same reason: a live journey
            // pings every five minutes and each one would buzz the phone.
            Log.d(
                TAG,
                "\uD83D\uDCCD Silent check-in location update from ${senderKey.take(8)}: " +
                    "${message.currentLatitude}, ${message.currentLongitude}"
            )
            return
        }

        if (message.type == CheckInMessageType.emergency) {
            message.emergencyData?.let { data ->
                // __RELEASE_LOG_LEAKS_2026_09_23__ Was Log.e (kept by R8 in release) with
                // the contact's coordinates and device name. Location stays out of logs.
                Log.w(
                    TAG,
                    "\uD83D\uDEA8 EMERGENCY from ${senderKey.take(8)}: " +
                        "battery=${data.formattedBattery}"
                )
            }
        }

        val (bodyRes, arg) = body
        postNotification(
            // Keyed on the SENDER, so two people travelling at once do not
            // overwrite each other's alert, and on the TYPE, so a `warning` and the
            // `emergency` that follows it fifteen minutes later both stay in the
            // shade — the second must not silently replace the first.
            notificationId = incomingNotificationId(senderKey, message.type),
            title = message.senderName?.takeIf { it.isNotBlank() } ?: senderKey.take(8),
            body = if (arg != null) context.getString(bodyRes, arg) else context.getString(bodyRes),
            priorityMax = message.isUrgentOnArrival,
        )
        Log.d(TAG, "\uD83D\uDD14 Posted INCOMING check-in notification (${message.type})")
    }

    /**
     * A stable, collision-resistant id for an inbound check-in notification.
     *
     * Deliberately not `NOTIF_ID_WARNING` and friends: those three belong to THIS
     * device's own journey, and reusing them would let a contact's arrival cancel
     * the user's own "are you okay?" prompt.
     */
    private fun incomingNotificationId(senderKey: String, type: CheckInMessageType): Int =
        NOTIF_ID_INCOMING_BASE + ((senderKey.hashCode() and 0xFFFF) * CheckInMessageType.entries.size) +
            type.ordinal
}
