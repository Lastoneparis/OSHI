package com.oshi.messenger.update

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.version.MinimumVersionGate

/**
 * __FORCED_UPDATE_ANDROID_2026_10_13__
 *
 * Drives Play's own full-screen IMMEDIATE update flow when — and only when —
 * [ForcedUpdatePolicy] says so.
 *
 * All the deciding happens in [ForcedUpdatePolicy], which is pure. This class
 * exists to do the three things that cannot be pure: ask Play, launch the flow,
 * and swallow every failure.
 *
 * ### What "forced" really reaches
 *
 * Nothing here can affect a phone that is running an older build, because an
 * older build does not contain it. The population this can block is exactly
 * "everyone on the release that first shipped this file, or later". For the
 * users sitting on 1.6.36 today the only lever is the server floor that the
 * dismissible banner already reads, which has been in the field since
 * 2026-09-09. Both levers are needed and they are not substitutes.
 *
 * ### Every failure means "do not block"
 *
 * `AppUpdateManagerFactory.create` works without the Play Store installed, but
 * the task it returns then fails — Honor and Huawei devices without GMS, a
 * sideloaded APK, an emulator, a corporate image. Each one lands in
 * [onFailure] and leaves the app exactly as it was. The app is still a
 * messenger that is supposed to work over Bluetooth with no network at all; a
 * wall raised because Play could not be reached would be a worse bug than the
 * one this is here to fix.
 */
object ForcedUpdateManager {

    private const val TAG = "ForcedUpdate"

    /** Set once a flow has been launched, so a resume does not launch a second. */
    @Volatile
    private var flowInFlight = false

    /**
     * Ask Play, decide, and block if the decision is to block.
     *
     * Safe to call on every `onCreate` and every `onResume`: the Play query is
     * cheap and local (Play Store caches it), and [flowInFlight] stops the
     * duplicate launch that a resume arriving mid-flow would otherwise cause.
     *
     * @param launcher the activity's registered `StartIntentSenderForResult`
     *   launcher. Passed in rather than created here because registration has to
     *   happen before the activity reaches STARTED, which is the activity's job.
     */
    fun check(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        // With no blocking floor published, the ONLY legitimate block left is
        // finishing an immediate update a previous run already began — so take
        // the narrow path that can do nothing else.
        if (MinimumVersionGate.blockingVersion == null) {
            queryAndMaybeResume(activity, launcher)
        } else {
            queryAndDecide(activity, launcher)
        }
    }

    /**
     * The narrow path: ask Play only about an update it has already started.
     * Used when no blocking floor is published, so the only legitimate block is
     * finishing what a previous run began.
     */
    private fun queryAndMaybeResume(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        runCatching {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    // __UPDATE_PROMPT_ANDROID_2026_11_21__ Play reports a FLEXIBLE
                    // download the user accepted with this same value; that one is
                    // not ours to turn into a full-screen wall.
                    if (info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                        !FlexibleUpdateManager.flexibleOwnsInProgress(activity)
                    ) {
                        launchImmediate(manager, info, launcher)
                    }
                }
                .addOnFailureListener { onFailure(it) }
        }.onFailure { onFailure(it) }
    }

    private fun queryAndDecide(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        runCatching {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    // Both halves of "available" matter. `UPDATE_AVAILABLE` says a
                    // newer build exists; `isUpdateTypeAllowed` says THIS device can
                    // actually run the immediate flow — it is false on devices where
                    // Play cannot self-install, and launching anyway throws.
                    // __UPDATE_PROMPT_ANDROID_2026_11_21__ An in-progress FLEXIBLE
                    // download we started is NOT an interrupted immediate update;
                    // see FlexibleUpdatePolicy.forcedPlayState.
                    val state = FlexibleUpdatePolicy.forcedPlayState(
                        inProgress = info.updateAvailability() ==
                            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                        updateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
                        immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                        flexibleOwnsInProgress = FlexibleUpdateManager.flexibleOwnsInProgress(activity),
                    )

                    val decision = ForcedUpdatePolicy.decide(
                        currentVersion = BuildConfig.VERSION_NAME,
                        nagFloor = MinimumVersionGate.minimumVersion,
                        blockFloor = MinimumVersionGate.blockingVersion,
                        play = state,
                    )

                    Log.d(TAG, "decision=$decision play=$state floor=${MinimumVersionGate.blockingVersion}")

                    // OFFER needs nothing from here: the dismissible banner above
                    // the conversation draws itself off MinimumVersionGate.
                    if (decision == ForcedUpdatePolicy.Decision.BLOCK) {
                        launchImmediate(manager, info, launcher)
                    }
                }
                .addOnFailureListener { onFailure(it) }
        }.onFailure { onFailure(it) }
    }

    private fun launchImmediate(
        manager: com.google.android.play.core.appupdate.AppUpdateManager,
        info: com.google.android.play.core.appupdate.AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        if (flowInFlight) return
        flowInFlight = true
        runCatching {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            )
        }.onFailure {
            // Could not even start it. Release the latch so a later resume can
            // try again, and leave the user with a working app.
            flowInFlight = false
            onFailure(it)
        }
    }

    /**
     * The user dismissed or cancelled Play's screen.
     *
     * Deliberately NOT re-launched in a loop. Play's immediate flow is already
     * modal and already restarts the app on success; a relaunch-on-cancel turns
     * a refusal into an app that cannot be opened at all, including by someone
     * who needs it on a mesh with no internet to download an update over.
     * The next cold start asks again, which is enough.
     */
    fun onFlowResult(resultCode: Int) {
        flowInFlight = false
        Log.d(TAG, "immediate update flow returned $resultCode")
    }

    private fun onFailure(t: Throwable) {
        Log.d(TAG, "Play update unavailable (${t.javaClass.simpleName}) — not blocking")
    }

    /** Tests only. */
    fun resetForTesting() {
        flowInFlight = false
    }
}
