package com.oshi.messenger.update

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.version.MinimumVersionGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * __UPDATE_PROMPT_ANDROID_2026_11_21__
 *
 * Play In-App Updates, FLEXIBLE flow: "a new version is in production → ask once,
 * download in the background, offer a restart". The deciding is all in
 * [FlexibleUpdatePolicy]; this does the impure parts — ask Play, start the flow,
 * listen to the download — and swallows every failure, exactly like
 * [ForcedUpdateManager]: no Play Store, a sideloaded APK, an emulator, a Honor
 * phone without GMS all end in "show nothing", never in a crash or a wall.
 *
 * The UI ([com.oshi.messenger.ui.components.UpdatePromptHost]) observes [facts]
 * and [askedThisStart] and re-applies the policy with what is on screen.
 */
object FlexibleUpdateManager {

    private const val TAG = "FlexibleUpdate"

    private val _facts = MutableStateFlow<FlexibleUpdatePolicy.PlayFacts?>(null)
    /** What Play last said, reduced; `null` = nothing known (or Play unavailable). */
    val facts: StateFlow<FlexibleUpdatePolicy.PlayFacts?> = _facts.asStateFlow()

    private val _askedThisStart = MutableStateFlow(false)
    /** The dialog has already been on screen in this process. */
    val askedThisStart: StateFlow<Boolean> = _askedThisStart.asStateFlow()

    /**
     * Debug builds only (see `DebugFakeUpdateReceiver`): a
     * `FakeAppUpdateManager` to drive the whole flow on an emulator with no
     * Play release. Never set in a release build.
     */
    @Volatile
    var managerOverride: AppUpdateManager? = null
        set(value) {
            field = value
            manager = null
            listenerOn = null
        }

    @Volatile private var manager: AppUpdateManager? = null
    @Volatile private var listenerOn: AppUpdateManager? = null
    @Volatile private var flowInFlight = false

    private var appContext: Context? = null

    private fun managerFor(context: Context): AppUpdateManager {
        appContext = context.applicationContext
        return managerOverride
            ?: manager
            ?: AppUpdateManagerFactory.create(context.applicationContext).also { manager = it }
    }

    private val installListener = InstallStateUpdatedListener { state ->
        val status = state.installStatus()
        Log.d(TAG, "install state $status")
        val current = _facts.value
        when (status) {
            InstallStatus.DOWNLOADED ->
                _facts.value = (current ?: emptyFacts()).copy(install = FlexibleUpdatePolicy.Install.DOWNLOADED)
            InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING ->
                _facts.value = (current ?: emptyFacts()).copy(install = FlexibleUpdatePolicy.Install.IN_PROGRESS)
            InstallStatus.INSTALLED, InstallStatus.FAILED, InstallStatus.CANCELED -> {
                appContext?.let { runCatching { UpdateSnoozeStore.fromContext(it).flexibleStartedVersionCode = null } }
                _facts.value = current?.copy(install = FlexibleUpdatePolicy.Install.IDLE)
            }
            else -> Unit
        }
    }

    private fun emptyFacts() = FlexibleUpdatePolicy.PlayFacts(
        flexibleAvailable = false,
        availableVersionCode = 0,
        install = FlexibleUpdatePolicy.Install.IDLE,
        forced = false,
    )

    /**
     * Ask Play and publish [facts]. Safe on every `onCreate`/`onResume` — this
     * is also what catches "resumed with an update already DOWNLOADED" (by a
     * previous run, or while the app was in the background).
     */
    fun check(context: Context) {
        runCatching {
            val m = managerFor(context)
            if (listenerOn !== m) {
                runCatching { m.registerListener(installListener) }
                listenerOn = m
            }
            val store = UpdateSnoozeStore.fromContext(context)
            m.appUpdateInfo
                .addOnSuccessListener { info -> publish(info, store) }
                .addOnFailureListener { onFailure(it) }
        }.onFailure { onFailure(it) }
    }

    private fun publish(info: AppUpdateInfo, store: UpdateSnoozeStore) {
        val availability = info.updateAvailability()
        val inProgress = availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        val install = when (info.installStatus()) {
            InstallStatus.DOWNLOADED -> FlexibleUpdatePolicy.Install.DOWNLOADED
            InstallStatus.PENDING, InstallStatus.DOWNLOADING, InstallStatus.INSTALLING ->
                FlexibleUpdatePolicy.Install.IN_PROGRESS
            else -> FlexibleUpdatePolicy.Install.IDLE
        }

        // A "started by us" mark outlives its download once the new build is
        // running, or once Play no longer has anything going on.
        val started = store.flexibleStartedVersionCode
        if (started != null && (BuildConfig.VERSION_CODE >= started ||
                (!inProgress && install == FlexibleUpdatePolicy.Install.IDLE))
        ) {
            store.flexibleStartedVersionCode = null
        }

        val forcedState = FlexibleUpdatePolicy.forcedPlayState(
            inProgress = inProgress,
            updateAvailable = availability == UpdateAvailability.UPDATE_AVAILABLE,
            immediateAllowed = runCatching { info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) }.getOrDefault(false),
            flexibleOwnsInProgress = store.flexibleStartedVersionCode != null ||
                install == FlexibleUpdatePolicy.Install.DOWNLOADED,
        )
        val forced = ForcedUpdatePolicy.decide(
            currentVersion = BuildConfig.VERSION_NAME,
            nagFloor = MinimumVersionGate.minimumVersion,
            blockFloor = MinimumVersionGate.blockingVersion,
            play = forcedState,
        ) == ForcedUpdatePolicy.Decision.BLOCK

        val facts = FlexibleUpdatePolicy.PlayFacts(
            flexibleAvailable = availability == UpdateAvailability.UPDATE_AVAILABLE &&
                runCatching { info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) }.getOrDefault(false),
            availableVersionCode = info.availableVersionCode(),
            install = install,
            forced = forced,
        )
        Log.d(TAG, "facts=$facts availability=$availability")
        _facts.value = facts
    }

    /** True while a FLEXIBLE download WE started is in Play's hands. */
    fun flexibleOwnsInProgress(context: Context): Boolean =
        runCatching { UpdateSnoozeStore.fromContext(context).flexibleStartedVersionCode != null }
            .getOrDefault(false)

    /** The dialog is on screen: never again in this process. */
    fun onAskShown() {
        _askedThisStart.value = true
    }

    /** "Later" (or the dialog dismissed): snooze this store version for 3 days. */
    fun onLater(context: Context) {
        val code = _facts.value?.availableVersionCode ?: return
        runCatching { UpdateSnoozeStore.fromContext(context).snooze(code, System.currentTimeMillis()) }
    }

    /**
     * "Update": hand over to Play's FLEXIBLE consent sheet. A fresh
     * [AppUpdateInfo] is fetched first — Play accepts each info for one flow only.
     */
    fun startFlexible(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (flowInFlight) return
        runCatching {
            val m = managerFor(activity)
            flowInFlight = true
            m.appUpdateInfo
                .addOnSuccessListener { info ->
                    val ok = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                    if (!ok) {
                        flowInFlight = false
                        return@addOnSuccessListener
                    }
                    runCatching {
                        m.startUpdateFlowForResult(
                            info,
                            launcher,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                        )
                    }.onFailure {
                        flowInFlight = false
                        onFailure(it)
                    }
                }
                .addOnFailureListener {
                    flowInFlight = false
                    onFailure(it)
                }
        }.onFailure {
            flowInFlight = false
            onFailure(it)
        }
    }

    /**
     * Play's consent sheet returned. OK = download started in the background;
     * CANCELED = the user said no there too, which counts as "Later".
     */
    fun onFlowResult(context: Context, resultCode: Int) {
        flowInFlight = false
        Log.d(TAG, "flexible flow returned $resultCode")
        val code = _facts.value?.availableVersionCode ?: return
        runCatching {
            val store = UpdateSnoozeStore.fromContext(context)
            when (resultCode) {
                Activity.RESULT_OK -> {
                    store.flexibleStartedVersionCode = code
                    _facts.value = _facts.value?.copy(install = FlexibleUpdatePolicy.Install.IN_PROGRESS)
                }
                Activity.RESULT_CANCELED -> store.snooze(code, System.currentTimeMillis())
                else -> Unit // RESULT_IN_APP_UPDATE_FAILED: silent, asked again next start.
            }
        }
    }

    /** "Restart": Play installs the downloaded build and relaunches OSHI. */
    fun completeUpdate(context: Context) {
        runCatching {
            managerFor(context).completeUpdate().addOnFailureListener { onFailure(it) }
        }.onFailure { onFailure(it) }
    }

    private fun onFailure(t: Throwable) {
        Log.d(TAG, "Play update unavailable (${t.javaClass.simpleName}) — showing nothing")
    }

    /** Tests / debug only. */
    fun resetForTesting() {
        _facts.value = null
        _askedThisStart.value = false
        flowInFlight = false
    }
}
