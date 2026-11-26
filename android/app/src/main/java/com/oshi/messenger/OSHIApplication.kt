package com.oshi.messenger

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.oshi.messenger.service.AppPollState
import com.oshi.messenger.service.EnhancedCallManager
import com.oshi.messenger.service.OshiConnectionService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * OSHI Application - Main Application Class
 */
@HiltAndroidApp
class OSHIApplication : Application() {

    /**
     * Injected so the process-lifecycle observer below can reach it.
     *
     * `@Singleton`, and the same instance `MainActivity` / `FCMService` /
     * `IncomingCallService` use — so pausing the camera here really does pause the
     * camera of the call the user is on.
     */
    @Inject lateinit var enhancedCallManager: EnhancedCallManager

    /** __DEVSYNC_DIRECT_2026_09_22__ Direct own-device sync (flag-gated, foreground only). */
    @Inject lateinit var devSyncManager: com.oshi.messenger.devsync.DevSyncManager
    // __MAIL_UNREAD_REFRESH_2026_11_22__ unread-mail badge kept true outside the Mail screen.
    @Inject lateinit var mailUnreadSync: com.oshi.messenger.mail.MailUnreadSync
    // __CHANNEL_WEB_2026_11_26__ public channel web preview outbox (drained in the background).
    @Inject lateinit var channelWebPublisher: dagger.Lazy<com.oshi.messenger.service.channelweb.ChannelWebPublisher>

    /**
     * The app's ONE location source, injected so the LoRa position beacon can borrow it.
     *
     * `@Singleton`, so this is the same instance the share-location sheet and live sharing
     * use. The beacon takes a fix-getter rather than this object, and building a second
     * fused-location client inside the radio layer would have been a second answer to
     * "where are we". [LoRa position beacon 2026-10-28]
     */
    @Inject lateinit var locationSharingManager: com.oshi.messenger.network.location.LocationSharingManager

    /**
     * __PER_APP_LANGUAGE_2026_11_20__ Android 8–12: notifications and services resolve
     * strings through the application context, so it carries the in-app language too.
     */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.oshi.messenger.ui.locale.AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.oshi.messenger.ui.locale.AppLanguage.migrateToFramework(this)

        // __RATCHET_IS_SHARED_SOURCE_2026_10_28__ The ratchet logs through a seam now, so
        // that `DoubleRatchet.kt` imports no `android.*` and the desktop client can compile
        // the SAME file instead of running a second implementation of it. The sink defaults
        // to a NO-OP — silence is the right default for a unit test and the wrong one for
        // this app — so installing it here is what keeps `adb logcat -s DoubleRatchet`
        // showing exactly what it showed before. See `RatchetSeams.kt`.
        //
        // Nothing sensitive reaches this: `LOG_SENSITIVE` in DoubleRatchet.kt is the
        // compile-time guard on key material and it stays false in release.
        com.oshi.messenger.network.encryption.RatchetLog.sink =
            com.oshi.messenger.network.encryption.RatchetLog.Sink { level, tag, message, error ->
                when (level) {
                    'E' -> if (error != null) android.util.Log.e(tag, message, error) else android.util.Log.e(tag, message)
                    'W' -> android.util.Log.w(tag, message)
                    else -> android.util.Log.d(tag, message)
                }
            }

        // __CALL_PUSH_AUTH_2026_09_23__ Records the context only; the signing key is
        // resolved on the first call/push request.
        com.oshi.messenger.network.auth.CallPushSigning.init(this)

        // 🔐 Files at rest (2026-09-22). `install` only records the context — the
        // Keystore-wrapped key loads lazily on first use — so it must precede anything
        // that can touch an attachment or a JSON store (FCM, workers, Hilt singletons).
        // The migration pass (scratch sweep + legacy plaintext → sealed) runs on its own
        // low-priority thread and never blocks startup.
        com.oshi.messenger.data.local.atrest.AtRestKey.install(this)
        com.oshi.messenger.data.local.atrest.AtRestMigration.start(this)

        // __CHANNEL_WEB_2026_11_26__ after the first frame (it pulls in GroupManager, which the
        // UI builds anyway); `start` itself only launches a background coroutine.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { channelWebPublisher.get().start() }
                .onFailure { Log.w(TAG, "channel web start failed: ${it.message}") }
        }, 2_000L)

        // __CALL_LOG_AT_REST_2026_09_23__ Sealed call diagnostics (OSHILOG1, same as iOS).
        // Rotation is a rename; the key and every write live on the logger's own thread.
        com.oshi.messenger.service.diag.CallDiag.install(this)

        // __NLP_DEBUG_FILE_LEAK_2026_09_23__ Older builds left typed drafts in clear in
        // `nlp_debug.txt` on external storage. Delete them, off the main thread.
        val appForPurge = this
        Thread({
            runCatching {
                com.oshi.messenger.service.NLPManager.purgeLegacyDebugFiles(
                    com.oshi.messenger.service.NLPManager.legacyDebugFileDirs(appForPurge)
                )
            }
        }, "nlpDebugPurge").apply { priority = Thread.MIN_PRIORITY }.start()

        // __SHARED_NICKNAME_2026_09_22__ Peers' shared nicknames, loaded before the
        // first frame so the conversation list does not flash key prefixes.
        com.oshi.messenger.data.model.PeerNicknameStore.init(this)

        // 📡 LoRa node → identity bindings must be on DISK before anything can
        // learn one.
        //
        // `LoRaNodeIdentityMap.shared` starts as a MEMORY-ONLY map, so until this
        // runs every binding a node proves is lost at the next launch — the exact
        // failure the map exists to prevent, and the thing that routes a later
        // plain-text Meshtastic packet into the right conversation instead of a
        // stray `lora!<nodehex>` thread.
        //
        // It must also run BEFORE any radio traffic, not merely "early":
        // `install` REPLACES `shared` with a map re-read from disk, so calling it
        // late silently discards anything learned into the memory-only default
        // this session (documented at `LoRaNodeIdentityMap.kt:290-297`). First
        // statement of `onCreate` is the only place that is true of — the radio
        // itself cannot exist yet, since nothing has asked for
        // `MeshtasticManager.getInstance`.
        runCatching {
            com.oshi.messenger.service.lora.LoRaNodeIdentityMap.install(this)
        }.onFailure { Log.w(TAG, "LoRa identity map install failed: ${it.message}") }

        // __REACTION_WAS_SILENT_2026_11_04__ The conversation list is the first
        // thing drawn after a cold start, so the last-reaction cache has to be
        // readable before it renders — a preview that vanishes on relaunch
        // reads as a bug rather than as a cache.
        runCatching {
            com.oshi.messenger.service.LastReactionStore.attach(this)
        }.onFailure { Log.w(TAG, "last-reaction store attach failed: ${it.message}") }

        // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Loaded before any banner can be built:
        // the banner builders ask it whether system smart actions are allowed.
        runCatching {
            com.oshi.messenger.service.AdvancedChatPrivacyStore.attach(this)
            com.oshi.messenger.service.AdvancedChatPrivacyReaders.attach(this)
        }.onFailure { Log.w(TAG, "advanced-privacy store attach failed: ${it.message}") }

        // 💓 __ALIVE_CHECK_IN_2026_11_04__ The liveness check-ins, for the same
        // reason as the line above — the conversation list draws their dots and
        // countdowns on the first frame — and then a sweep, which is the part that
        // matters.
        //
        // `evaluate` posts whatever deadline passed while the process was dead and
        // re-arms the watcher. Without it, a phone that was rebooted or had the app
        // force-stopped would lose the pending alarm and tell nobody that a
        // check-in was missed: WorkManager restores its own queue across a reboot,
        // but not across a force-stop, and a safety feature cannot depend on
        // knowing which of the two happened.
        runCatching {
            com.oshi.messenger.service.AliveCheckInStore.attach(this)
            com.oshi.messenger.service.ForwardAffinityStore.initialize(this)
            com.oshi.messenger.service.AliveCheckInWatcher.evaluate(this)
        }.onFailure { Log.w(TAG, "alive check-in sweep failed: ${it.message}") }

        // __WEAR_BRIDGE_2026_11_17__ Publishes the unread COUNT to a paired Wear OS
        // watch — only while Settings → Smartwatch is on (default off). Off, it
        // only watches the switch: no Wearable API call, no repository touched.
        runCatching { com.oshi.messenger.service.wear.WearUnreadPublisher.start(this) }
            .onFailure { Log.w(TAG, "wear unread publisher failed: ${it.message}") }

        // 📡 The CALLER of the LoRa position beacon. Off unless the person turned the
        // switch on in LoRa settings (persisted, default false), so installing it here
        // transmits nothing by itself — see `LoRaPositionBeacon`'s header for the airtime
        // rules and for what the switch exposes.
        //
        // Here rather than in a screen's ViewModel because a beacon that dies when the
        // LoRa settings screen closes is not a beacon. It still only runs while this
        // process is alive; surviving the process needs a foreground service of type
        // `location`, which is a product decision and not a porting gap.
        runCatching {
            com.oshi.messenger.service.lora.LoRaPositionBeacon.install(
                context = this,
                hasPermission = { locationSharingManager.hasLocationPermission() },
                currentFix = { callback ->
                    locationSharingManager.getCurrentLocation { result ->
                        val loc = result.getOrNull()
                        callback(loc?.latitude, loc?.longitude)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "LoRa position beacon install failed: ${it.message}") }

        // osmdroid identifies itself with a single process-wide User-Agent. Setting
        // it here (rather than only in MapScreen) means every map entry point —
        // MapScreen, MapNavigationScreen, any future one — is compliant from the
        // first tile request. A bare package name got the app 403'd by OSM.
        // iOS sets the equivalent headers on its tile session at
        // OSHI/OfflineFirstTileOverlay.swift:167-170.
        runCatching {
            val cfg = org.osmdroid.config.Configuration.getInstance()
            // `load(context, …)` is what resolves osmdroid's storage paths against
            // THIS app's sandbox. Without it `getInstance()` hands back a
            // context-less default whose osmdroidBasePath points at shared external
            // storage — which is why the first version of the cache sweep below
            // reported "removed 0" while 259 placeholder tiles sat in
            // `files/osmdroid/tiles`. Doing it here means every later
            // `Configuration.getInstance()` in the app agrees on one path.
            // Any SharedPreferences works — osmdroid derives the paths from the
            // Context, not from the prefs file — so we use our own rather than
            // pulling in androidx.preference just for its default instance.
            cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
            cfg.userAgentValue = com.oshi.messenger.service.map.OshiTileSource.USER_AGENT
        }

        // One-shot eviction of the OSM "Access blocked" placeholders the previous
        // build cached as if they were map tiles. Without this, pointing at the
        // first-party proxy fixes nothing for any user who already opened the Map
        // tab — verified on device: 259 byte-identical placeholder files were still
        // being served from disk after installing the fixed build. Background
        // thread: it hashes a few hundred small files and must not touch startup.
        Thread {
            runCatching {
                val cfg = org.osmdroid.config.Configuration.getInstance()
                // Sweep the configured cache AND the in-sandbox default. They are
                // normally the same directory after `load()` above, but pinning
                // both means a config quirk can never silently skip the sweep —
                // which is exactly the failure the first attempt hit.
                val roots = listOfNotNull(
                    cfg.osmdroidTileCache,
                    java.io.File(java.io.File(filesDir, "osmdroid"), "tiles")
                )
                com.oshi.messenger.service.map.TileCacheSanitizer
                    .sweepOnce(this, *roots.toTypedArray())
            }
            // The exact OSM "Access blocked" picture the tile proxy served with
            // HTTP 200 from 13/11/2026 (6725 bytes, known sha256). Once per version.
            runCatching {
                com.oshi.messenger.service.map.PoisonedTilePurge
                    .runOncePerVersion(this, BuildConfig.VERSION_CODE)
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()

        // Register PhoneAccount for ConnectionService (native call UI, Bluetooth, call recents)
        OshiConnectionService.registerPhoneAccount(this)

        registerCallLifecycleObserver()
        // __MAIL_UNREAD_REFRESH_2026_11_22__ network-available + launch checks of the mailbox.
        runCatching { mailUnreadSync.install(this) }
            .onFailure { Log.w(TAG, "mailUnreadSync.install failed: ${it.message}") }

        // __DEVSYNC_DIRECT_2026_09_22__ Registers its own process-lifecycle observer; every heavy
        // dependency is lazy, so this constructs nothing else on the main thread.
        runCatching { devSyncManager.install() }
            .onFailure { Log.w(TAG, "devsync install failed: ${it.message}") }
    }

    /**
     * 🔧 P1 FIX (2026-08-13 cross-file integration, item I).
     *
     * `EnhancedCallManager.onAppBackgrounded()` / `onAppForegrounded()`
     * (`EnhancedCallManager.kt:4809,4816`) had ZERO call sites. They are what pause and
     * resume the camera during a video call and what tell the peer about it
     * (`sendVideoPausedSignal` / `sendVideoResumedSignal`), so with nothing invoking
     * them the local camera kept capturing and uploading while the user was in another
     * app — burning battery and bandwidth — and the peer's UI never showed the
     * "camera off" state that iOS shows.
     *
     * `ProcessLifecycleOwner` is the right owner rather than an Activity callback:
     * it fires once for the whole PROCESS, so an Activity recreation (rotation,
     * config change) does not read as background→foreground and does not stutter the
     * camera mid-call. Both manager methods are already no-ops unless a video call is
     * actually in progress, so this is safe on every other path.
     *
     * 🔋 2026-08-23 — this observer now ALSO drives [AppPollState], which is the single
     * reason the app stops waking the radio ~59 times a minute while backgrounded. It
     * was already the right owner and it was already registered; it simply was not
     * told about anything except the camera. `PollScheduler.kt` documents why iOS gets
     * away with 1 s timers (it declares no `UIBackgroundModes`, so the OS suspends the
     * whole process) and why Android, held awake by a `dataSync` foreground service,
     * does not.
     */
    private fun registerCallLifecycleObserver() {
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // 🔋 FIRST, before anything that can throw: the poll schedule is
                    // what decides whether the radio is woken ~59 times a minute or
                    // twice, and it must not be hostage to a camera call failing.
                    runCatching { AppPollState.onForeground() }
                        .onFailure { Log.w(TAG, "AppPollState.onForeground failed: ${it.message}") }
                    runCatching { enhancedCallManager.onAppForegrounded() }
                        .onFailure { Log.w(TAG, "onAppForegrounded failed: ${it.message}") }
                    // __MAIL_UNREAD_REFRESH_2026_11_22__ ask the mailbox on every foreground
                    // and every minute while open, not only when the Mail screen loads.
                    runCatching { mailUnreadSync.onForeground() }
                        .onFailure { Log.w(TAG, "mailUnreadSync.onForeground failed: ${it.message}") }
                }

                override fun onStop(owner: LifecycleOwner) {
                    // Seal buffered call diagnostics now: background is where we get killed.
                    com.oshi.messenger.service.diag.CallDiag.flush()
                    runCatching { AppPollState.onBackground() }
                        .onFailure { Log.w(TAG, "AppPollState.onBackground failed: ${it.message}") }
                    // __BACKGROUND_PRESENCE_2026_11_10__ drop the always-on services
                    // (and the status-bar logo) unless someone needs them.
                    runCatching { com.oshi.messenger.service.BackgroundPresence.onBackground(this@OSHIApplication) }
                        .onFailure { Log.w(TAG, "BackgroundPresence.onBackground failed: ${it.message}") }
                    runCatching { enhancedCallManager.onAppBackgrounded() }
                        .onFailure { Log.w(TAG, "onAppBackgrounded failed: ${it.message}") }
                    runCatching { mailUnreadSync.onBackground() }
                }
            })
            Log.d(TAG, "📱 Process lifecycle observer registered for call camera pause/resume")
        } catch (e: Exception) {
            Log.e(TAG, "Could not register process lifecycle observer: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "OSHIApplication"

        lateinit var instance: OSHIApplication
            private set
    }
}
