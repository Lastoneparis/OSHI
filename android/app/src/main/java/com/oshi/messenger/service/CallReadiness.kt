package com.oshi.messenger.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * __CALL_WAKE_2026_11_22__ Will an incoming call be able to ring on THIS phone?
 *
 * Journal 21/11: an Android callee whose FCM call push was accepted by Google showed up
 * 79 s later (the caller had hung up at 25 s), another 26 s later. FCM is not the
 * problem then — the phone is: notifications or the incoming-call category switched off,
 * full-screen alerts refused (Android 14+ only grants USE_FULL_SCREEN_INTENT to calling
 * apps the user allows), the app battery-optimised or put in the RESTRICTED standby
 * bucket (high-priority FCM is then rationed). Nothing told the user. Settings → Calls now
 * says what is wrong and opens the screen that fixes it.
 *
 * [evaluate] is pure (unit-tested); [read] gathers the facts from the system.
 */
object CallReadiness {

    enum class Issue { NOTIFICATIONS_OFF, CALL_CHANNEL_OFF, FULL_SCREEN_DENIED, BACKGROUND_RESTRICTED }

    data class Facts(
        val notificationsEnabled: Boolean,
        /** null = channel not created yet (first launch) — not an issue. */
        val callChannelEnabled: Boolean?,
        /** null below Android 14, where the permission is install-time. */
        val fullScreenAllowed: Boolean?,
        val ignoringBatteryOptimizations: Boolean,
        /** UsageStatsManager bucket, null below Android 9. */
        val standbyBucket: Int?,
    )

    /** UsageStatsManager.STANDBY_BUCKET_RESTRICTED (API 30); literal so the test needs no SDK. */
    const val STANDBY_BUCKET_RESTRICTED = 45

    /**
     * Most severe first. Battery optimisation alone is NOT an issue: Doze still lets a
     * high-priority FCM through, and every phone is optimised by default; the user is only
     * nagged when Android has actually put the app in the restricted bucket.
     */
    fun evaluate(f: Facts): List<Issue> {
        val out = mutableListOf<Issue>()
        if (!f.notificationsEnabled) out += Issue.NOTIFICATIONS_OFF
        if (f.callChannelEnabled == false) out += Issue.CALL_CHANNEL_OFF
        if (f.fullScreenAllowed == false) out += Issue.FULL_SCREEN_DENIED
        if (f.standbyBucket != null && f.standbyBucket >= STANDBY_BUCKET_RESTRICTED && !f.ignoringBatteryOptimizations) {
            out += Issue.BACKGROUND_RESTRICTED
        }
        return out
    }

    fun read(context: Context): Facts {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notificationsEnabled = runCatching { nm?.areNotificationsEnabled() ?: true }.getOrDefault(true)
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { nm?.getNotificationChannel(IncomingCallService.CHANNEL_ID) }.getOrNull()
        } else null
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && channel != null) {
            channel.importance != NotificationManager.IMPORTANCE_NONE
        } else null
        val fullScreen = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { nm?.canUseFullScreenIntent() }.getOrNull()
        } else null
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false }.getOrDefault(false)
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                context.getSystemService(android.app.usage.UsageStatsManager::class.java)?.appStandbyBucket
            }.getOrNull()
        } else null
        return Facts(notificationsEnabled, channelEnabled, fullScreen, ignoring, bucket)
    }

    /** The system screen that fixes [issue]. */
    fun settingsIntent(context: Context, issue: Issue): android.content.Intent {
        val pkg = context.packageName
        return when (issue) {
            Issue.NOTIFICATIONS_OFF ->
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
            Issue.CALL_CHANNEL_OFF ->
                android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, IncomingCallService.CHANNEL_ID)
            Issue.FULL_SCREEN_DENIED ->
                if (Build.VERSION.SDK_INT >= 34) {
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:$pkg"))
                } else appDetails(pkg)
            Issue.BACKGROUND_RESTRICTED -> appDetails(pkg)
        }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun appDetails(pkg: String) =
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg"))
}
