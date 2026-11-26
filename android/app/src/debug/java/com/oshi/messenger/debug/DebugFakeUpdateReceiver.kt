package com.oshi.messenger.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.update.FlexibleUpdateManager
import com.oshi.messenger.update.UpdateSnoozeStore

/**
 * DEBUG BUILDS ONLY. __UPDATE_PROMPT_ANDROID_2026_11_21__
 *
 * Drives the in-app update prompt on an emulator with Play's own
 * [FakeAppUpdateManager] — no Play release, no Play Store needed:
 *
 *   adb -s emulator-5554 shell am broadcast -p com.oshi.messenger \
 *       -a com.oshi.messenger.debug.FAKE_UPDATE --es step available
 *
 * steps: `available` (store has versionCode+1 → dialog; `--ez keepSnooze true`
 * keeps an earlier "Later"), `accept` (Play's sheet
 * accepted, download starts), `downloaded` (→ "Update ready · Restart" bar),
 * `installed`, `reset` (back to the real Play manager).
 */
class DebugFakeUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val step = intent.getStringExtra("step") ?: return
        Log.d(TAG, "step=$step")
        when (step) {
            "available" -> {
                val f = FakeAppUpdateManager(context.applicationContext)
                f.setUpdateAvailable(BuildConfig.VERSION_CODE + 1, AppUpdateType.FLEXIBLE)
                fake = f
                FlexibleUpdateManager.managerOverride = f
                FlexibleUpdateManager.resetForTesting()
                // `--ez keepSnooze true` keeps a previous "Later", to check it holds.
                if (!intent.getBooleanExtra("keepSnooze", false)) {
                    UpdateSnoozeStore.fromContext(context).apply {
                        clearSnooze()
                        flexibleStartedVersionCode = null
                    }
                }
                FlexibleUpdateManager.check(context)
            }
            "accept" -> fake?.let {
                // What the activity-result launcher reports when the user accepts
                // Play's consent sheet — the fake has no real sheet to return from.
                FlexibleUpdateManager.onFlowResult(context, Activity.RESULT_OK)
                it.userAcceptsUpdate()
                it.downloadStarts()
            }
            "downloaded" -> fake?.downloadCompletes()
            "installed" -> fake?.installCompletes()
            "recheck" -> FlexibleUpdateManager.check(context)
            "reset" -> {
                fake = null
                FlexibleUpdateManager.managerOverride = null
                FlexibleUpdateManager.resetForTesting()
            }
        }
        fake?.let {
            Log.d(TAG, "fake: confirmationVisible=${it.isConfirmationDialogVisible} splash=${it.isInstallSplashScreenVisible}")
        }
    }

    companion object {
        private const val TAG = "DebugFakeUpdate"
        @Volatile private var fake: FakeAppUpdateManager? = null
    }
}
