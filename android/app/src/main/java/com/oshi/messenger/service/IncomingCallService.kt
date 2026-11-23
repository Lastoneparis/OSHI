package com.oshi.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oshi.messenger.service.CallEndReason
import com.oshi.messenger.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

/**
 * IncomingCallService - Foreground service for incoming calls
 * Shows full-screen notification with ringtone and vibration
 * Works even when app is in background
 */
@AndroidEntryPoint
class IncomingCallService : Service() {

    @Inject lateinit var enhancedCallManager: EnhancedCallManager

    /**
     * 🚫 Last line of defence for the ringer. `FCMService` gates before it ever starts
     * this service, but the service is also reachable from other call paths and from a
     * stale intent, and it is the component that actually makes noise — so the block
     * and contacts-only checks are re-applied here. iOS holds the equivalent
     * belt-and-braces at both `VoIPPushManager.swift:1300-1338` (push) and
     * `VoiceCallManager.swift:6265-6284` (signal).
     */
    @Inject lateinit var database: com.oshi.messenger.data.local.AppDatabase

    companion object {
        const val TAG = "IncomingCallService"
        const val CHANNEL_ID = "oshi_incoming_call"
        const val NOTIFICATION_ID = 2001
        const val ACTION_SHOW_INCOMING = "com.oshi.messenger.INCOMING_CALL"
        const val ACTION_ACCEPT = "com.oshi.messenger.ACCEPT_CALL"
        const val ACTION_DECLINE = "com.oshi.messenger.DECLINE_CALL"
        const val ACTION_DISMISS = "com.oshi.messenger.DISMISS_INCOMING"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_KEY = "caller_key"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_IS_VIDEO = "is_video"

        // __STALE_RING_2026_09_23__ 55 s = EnhancedCallManager.CALLEE_RING_TIMEOUT_MS and iOS
        // `calleeRingWatchdog`, so the system ringer and the in-app ring end together.
        private const val RING_TIMEOUT_MS = 55_000L
    }

    private var ringtone: CallSounds.Handle? = null  // __OSHI_CALL_SOUNDS_2026_09_28__
    private var vibrator: Vibrator? = null
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * __ANSWER_FROM_THE_NOTIFICATION_2026_10_28__ (N11, reported by a user:
     * "Impossibilité de décrocher un appel via les notifications")
     *
     * Work that must OUTLIVE this service, on a scope nothing cancels.
     *
     * Accepting and declining both read `scope.launch { … }` followed immediately by
     * `stopSelf()`. `stopSelf()` runs `onDestroy`, which calls `scope.cancel()` — and the
     * coroutine was only QUEUED on the main dispatcher, so it had not started. It was
     * cancelled before `acceptCall()` executed, every single time.
     *
     * The visible result is exactly the report: the ringer stops (`stopRinging()` is
     * synchronous and ran first), the notification goes away, and the call is never
     * answered. Declining had the same defect, so the caller was never told either and
     * sat listening to a ring that had already stopped on this end.
     *
     * A companion-level scope is the fix rather than "await before stopSelf", because the
     * service must still be allowed to die promptly: what matters is that the call
     * manager — a singleton that outlives this service — gets the instruction.
     */
    private val detachedScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var callerName: String = "Unknown"
    private var callerKey: String = ""
    private var callId: String = ""
    private var isVideoCall: Boolean = false

    // 🛟 SINGLE-SURFACE FIX (mirrors iOS double-notification fix): true once this
    // service has raised the full-screen ringer. A second ACTION_SHOW_INCOMING for
    // the SAME call — a duplicate FCM push, an OS intent redelivery, or a WS/mesh
    // path also reaching us — must NOT re-flash the full-screen intent or restart the
    // ringtone. Reset when the ring is torn down (dismiss / accept / decline / destroy).
    private var isShowingIncoming: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_INCOMING -> {
                val incomingCallId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""

                // 🛟 SINGLE-SURFACE FIX: if we are already ringing, a second
                // ACTION_SHOW_INCOMING must not raise a second full-screen / restart the
                // ringtone. Coalesce it onto the surface already up. We must still satisfy
                // the startForegroundService contract with a startForeground() call, but
                // the first ACTION_SHOW_INCOMING already did that, so returning here is safe.
                if (isShowingIncoming) {
                    if (incomingCallId.isEmpty() || incomingCallId == callId) {
                        Log.d(TAG, "🛟 Duplicate incoming for ${incomingCallId.take(8)} — already ringing, coalescing")
                    } else {
                        Log.d(TAG, "🛟 New call ${incomingCallId.take(8)} while ${callId.take(8)} still rings — keeping the first")
                    }
                    return START_NOT_STICKY
                }

                // 🌍 Was the literal "Unknown". `notification_unknown_caller` is the
                // same fallback `FCMService.kt:711` already uses for this exact field.
                callerKey = intent.getStringExtra(EXTRA_CALLER_KEY) ?: ""
                // __LEAK_FIX_2026_09_25__ The name is resolved HERE from the key (contact
                // alias > the peer's shared nickname > key prefix), like
                // `EnhancedCallManager` does for a signalled call. The push no longer
                // carries a name, and a name that did arrive in the extra is ignored
                // whenever there is a key to resolve.
                callerName = resolveCallerNameLocally(callerKey)
                    ?: intent.getStringExtra(EXTRA_CALLER_NAME)
                    ?: getString(com.oshi.messenger.R.string.notification_unknown_caller)
                callId = incomingCallId
                isVideoCall = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                Log.d(TAG, "📞 Incoming call from $callerName (${callerKey.take(8)}...)")

                // 🚫 Do not ring for a blocked caller, and do not ring for an unknown
                // one when the user asked for contacts-only. Checked BEFORE the
                // notification and the ringtone, because a heads-up that is cancelled a
                // moment later has already made the sound.
                // iOS: VoIPPushManager.swift:1300-1310 (blocked), :1325-1338 (contacts-only).
                //
                // Deliberately SYNCHRONOUS: this service is started with
                // `startForegroundService`, so `showIncomingCallNotification()` (which
                // calls `startForeground`) has a hard ~5 s deadline. Deferring the
                // decision into a coroutine would risk
                // `ForegroundServiceDidNotStartInTimeException`. `runBlocking` on
                // Dispatchers.IO runs the two indexed reads off the main thread and
                // blocks for well under a millisecond.
                val rejected = try {
                    runBlocking(Dispatchers.IO) {
                        BlockedKeysStore.get(applicationContext).isBlocked(callerKey) ||
                        CallAdmissionPolicy.isBlocked(database.contactDao(), callerKey) ||
                            CallAdmissionPolicy.rejectsUnknownCaller(
                                applicationContext, database.contactDao(), callerKey
                            )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "admission check failed: ${e.message}")
                    false   // never silence a legitimate call because a read failed
                }
                if (rejected) {
                    Log.w(TAG, "🚫 Incoming call from ${callerKey.take(10)}… refused (blocked or not a contact)")
                    // The platform still demands a startForeground for a service started
                    // with startForegroundService, so satisfy the contract with a SILENT
                    // placeholder and tear it down immediately. Reusing
                    // showIncomingCallNotification() here would flash the full-screen
                    // call intent — the exact surface a blocked caller must never get,
                    // which is why iOS reports the blocked CallKit call with
                    // `displayName: nil` (VoIPPushManager.swift:1304-1308).
                    startForegroundSilentlyThenStop()
                    return START_NOT_STICKY
                }

                showIncomingCallNotification()
                startRinging()
                startTimeout()
                isShowingIncoming = true
            }
            ACTION_ACCEPT -> {
                Log.d(TAG, "✅ Call accepted from notification")
                stopRinging()
                // Bring the app to front so the in-call UI is visible
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("incoming_call", true)
                    putExtra(EXTRA_CALLER_KEY, callerKey)
                    putExtra(EXTRA_CALL_ID, callId)
                    putExtra(EXTRA_CALLER_NAME, callerName)
                    putExtra(EXTRA_IS_VIDEO, isVideoCall)
                }
                // ⚠️ Android 10+ blocks a background activity start, and a service
                // reacting to a notification tap IS background. This may therefore do
                // nothing; it is kept because it works where the exemption applies (and
                // the full-screen intent covers the locked case). It must never be the
                // thing the ANSWER depends on — the call manager's state drives the UI.
                startActivity(activityIntent)
                // __ANSWER_FROM_THE_NOTIFICATION_2026_10_28__ on the detached scope: the
                // service's own is cancelled by the stopSelf() below before this runs.
                detachedScope.launch {
                    try { enhancedCallManager.acceptCall() }
                    catch (e: Exception) { Log.e(TAG, "acceptCall failed: ${e.message}") }
                }
                stopSelf()
            }
            ACTION_DECLINE -> {
                Log.d(TAG, "❌ Call declined from notification")
                stopRinging()
                // __ANSWER_FROM_THE_NOTIFICATION_2026_10_28__ Same defect, same fix:
                // declining never reached the caller either, so they heard a ring this
                // phone had already silenced.
                detachedScope.launch {
                    try { enhancedCallManager.endCall(CallEndReason.DECLINED) }
                    catch (e: Exception) { Log.e(TAG, "endCall failed: ${e.message}") }
                }
                stopSelf()
            }
            ACTION_DISMISS -> {
                // 📱 Lenient callId guard, the same shape as
                // `AnsweredElsewherePacket.dismisses`: refuse only when BOTH ids are
                // known and differ. A dismissal that names no call, or one that arrives
                // before we learned ours, still stops the ringer — a false negative
                // here leaves the phone ringing, which is the worse of the two failures.
                //
                // This arm is reached from `FCMService.kt:797-810` (push) and from
                // `EnhancedCallManager.dismissIncomingCallRinger()` (every local
                // teardown: the caller hung up, a sibling answered over the relay or
                // the mesh, the ring watchdog fired, the user answered or declined in
                // the in-app overlay). Before that second call site existed, all of
                // those left this service ringing for the full RING_TIMEOUT_MS.
                val dismissCallId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!dismissCallId.isNullOrEmpty() && callId.isNotEmpty() && dismissCallId != callId) {
                    Log.d(TAG, "🔕 Dismiss ignored — different call (${dismissCallId.take(8)} vs ${callId.take(8)})")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "🔕 Incoming ringer dismissed")
                stopRinging()
                timeoutJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** __LEAK_FIX_2026_09_25__ Local caller name, or null without a key. Synchronous (startForeground deadline). */
    private fun resolveCallerNameLocally(key: String): String? {
        if (key.isBlank() || key.startsWith("bot:")) return null
        val alias = try {
            // Tolerant of base64 vs base64url / unpadded spellings of the same key.
            val std = key.replace('-', '+').replace('_', '/').trimEnd('=')
            val candidates = linkedSetOf(key, std, std + "=".repeat((4 - std.length % 4) % 4),
                std.replace('+', '-').replace('/', '_'))
            runBlocking(Dispatchers.IO) {
                val dao = database.contactDao()
                candidates.firstNotNullOfOrNull { k -> dao.getContactByPublicKey(k)?.alias?.takeIf { it.isNotBlank() } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "caller lookup failed: ${e.message}"); null
        }
        runCatching { com.oshi.messenger.data.model.PeerNicknameStore.init(applicationContext) }
        return com.oshi.messenger.data.model.PeerNicknameStore.displayName(alias, key)
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowingIncoming = false
        stopRinging()
        timeoutJob?.cancel()
        scope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.oshi.messenger.R.string.channel_incoming_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(com.oshi.messenger.R.string.channel_incoming_calls_desc)
                setShowBadge(true)
                enableVibration(false)  // We handle vibration ourselves
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)  // We handle ringtone ourselves
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Satisfy the `startForegroundService` → `startForeground` contract without showing
     * the caller anything, then shut the service down. Used only on the refusal path.
     */
    private fun startForegroundSilentlyThenStop() {
        try {
            val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(com.oshi.messenger.R.string.app_name))
                .setSmallIcon(com.oshi.messenger.R.drawable.ic_notification_call)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()
            startForeground(NOTIFICATION_ID, placeholder)
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "silent foreground teardown failed: ${e.message}")
        }
        stopSelf()
    }

    private fun showIncomingCallNotification() {
        // 🌍 Was `"Incoming $callType Call"` with `callType = "Video"/"Voice"` — the
        // title of the full-screen incoming-call notification, in English in all 34
        // locales. Both keys already existed and were already translated.
        val title = getString(
            if (isVideoCall) com.oshi.messenger.R.string.incoming_video_call
            else com.oshi.messenger.R.string.incoming_voice_call
        )

        // Full-screen intent → opens app when tapped
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("incoming_call", true)
            putExtra(EXTRA_CALLER_KEY, callerKey)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_IS_VIDEO, isVideoCall)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept action
        val acceptIntent = Intent(this, IncomingCallService::class.java).apply {
            action = ACTION_ACCEPT
        }
        val acceptPending = PendingIntent.getService(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action
        val declineIntent = Intent(this, IncomingCallService::class.java).apply {
            action = ACTION_DECLINE
        }
        val declinePending = PendingIntent.getService(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(callerName)
            .setSmallIcon(com.oshi.messenger.R.drawable.ic_notification_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 🌍 Were the literals "Decline" / "Accept". `call_decline` and
            // `call_accept` already existed and were already translated — these are
            // the only two ways to answer a call from a locked screen.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(com.oshi.messenger.R.string.call_decline),
                declinePending
            )
            .addAction(
                android.R.drawable.ic_menu_call,
                getString(com.oshi.messenger.R.string.call_accept),
                acceptPending
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startRinging() {
        try {
            // __OSHI_CALL_SOUNDS_2026_09_28__ the OSHI ringtone (was the device default);
            // falls back to the device default if it cannot play.
            ringtone?.stop()
            ringtone = CallSounds.startRingtone(this)

            // Vibrate
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 1000, 500, 1000, 500)  // Vibrate pattern
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }

            Log.d(TAG, "🔔 Ringing started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ringing: ${e.message}")
        }
    }

    private fun stopRinging() {
        try {
            ringtone?.stop()
            ringtone = null
            vibrator?.cancel()
            vibrator = null
            Log.d(TAG, "🔕 Ringing stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ringing: ${e.message}")
        }
    }

    private fun startTimeout() {
        timeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            Log.d(TAG, "⏰ Call timeout — no answer")
            stopRinging()
            // Broadcast missed call
            sendBroadcast(Intent("com.oshi.messenger.CALL_MISSED").apply {
                putExtra(EXTRA_CALLER_KEY, callerKey)
                putExtra(EXTRA_CALL_ID, callId)
                setPackage(packageName)
            })
            stopSelf()
        }
    }
}
