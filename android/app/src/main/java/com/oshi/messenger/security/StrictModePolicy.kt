package com.oshi.messenger.security

// =============================================================================
// 🛡 __STRICT_MODE_2026_11_22__ "Strict security mode" — the pure half (no Android).
//
// Owner's request (22/11): "a mode in Settings, like security: when activated, no
// copy/paste, no possibility for an AI agent to take control, and any other
// security." Shared spec with iOS/macOS and desktop (STRICT_MODE_SPEC.md).
//
// The mode does NOT duplicate settings that already exist. It FORCES them while it
// is on (screenshot protection, incognito keyboard, notification privacy, app lock,
// block forward/copy, read receipts, typing indicators, privacy relay) and puts back
// what the person had chosen when it is turned off. Everything that decides a value
// lives here so a JVM test asserts it (StrictModePolicyTest).
// =============================================================================

/** The existing settings strict mode takes over, as one comparable value. */
data class StrictGuardedSettings(
    val appLock: Boolean,
    val screenshotProtection: Boolean,
    val incognitoKeyboard: Boolean,
    val notificationPreview: Boolean,
    val readReceipts: Boolean,
    val typingIndicators: Boolean,
    val privacyRelay: Boolean,
    val blockForwardCopy: Boolean,
)

/** The two sub-switches of the mode (both ON by default, per spec). */
data class StrictOptions(
    /** Block paste INTO the composer too (copy OUT is always blocked). */
    val blockPaste: Boolean = true,
    /** Read receipts and typing indicators off while strict. */
    val hidePresence: Boolean = true,
)

object StrictModePolicy {

    /**
     * `MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT` (hidden constant, Android 12+): set by
     * the input system on every touch an AccessibilityService injected with
     * `dispatchGesture`. Stable value in AOSP since API 31.
     */
    const val FLAG_IS_ACCESSIBILITY_EVENT = 0x800

    /**
     * What the guarded settings become while strict mode is on.
     * [lockAvailable]: the phone has a screen lock / biometrics, so the app lock can
     * be turned on (otherwise it is left as is: forcing a lock that cannot be opened
     * would strand the person). [relayAvailable]: a privacy relay is configured.
     */
    fun forced(
        before: StrictGuardedSettings,
        options: StrictOptions,
        lockAvailable: Boolean,
        relayAvailable: Boolean,
    ): StrictGuardedSettings = before.copy(
        appLock = before.appLock || lockAvailable,
        screenshotProtection = true,
        incognitoKeyboard = true,
        notificationPreview = false,
        readReceipts = if (options.hidePresence) false else before.readReceipts,
        typingIndicators = if (options.hidePresence) false else before.typingIndicators,
        privacyRelay = before.privacyRelay || relayAvailable,
        blockForwardCopy = true,
    )

    /**
     * What the settings go back to when strict mode is turned off. A field strict
     * mode changed returns to the [snapshot] taken when it was turned on; a field it
     * did not touch keeps whatever it is [now] (the person may have changed it while
     * the mode was on — that choice is theirs and is kept).
     */
    fun restore(
        snapshot: StrictGuardedSettings,
        now: StrictGuardedSettings,
        options: StrictOptions,
        lockAvailable: Boolean,
        relayAvailable: Boolean,
    ): StrictGuardedSettings {
        val f = forced(snapshot, options, lockAvailable, relayAvailable)
        fun pick(forcedV: Boolean, snapV: Boolean, nowV: Boolean) = if (forcedV != snapV) snapV else nowV
        return StrictGuardedSettings(
            appLock = pick(f.appLock, snapshot.appLock, now.appLock),
            screenshotProtection = pick(f.screenshotProtection, snapshot.screenshotProtection, now.screenshotProtection),
            incognitoKeyboard = pick(f.incognitoKeyboard, snapshot.incognitoKeyboard, now.incognitoKeyboard),
            notificationPreview = pick(f.notificationPreview, snapshot.notificationPreview, now.notificationPreview),
            readReceipts = pick(f.readReceipts, snapshot.readReceipts, now.readReceipts),
            typingIndicators = pick(f.typingIndicators, snapshot.typingIndicators, now.typingIndicators),
            privacyRelay = pick(f.privacyRelay, snapshot.privacyRelay, now.privacyRelay),
            blockForwardCopy = pick(f.blockForwardCopy, snapshot.blockForwardCopy, now.blockForwardCopy),
        )
    }

    /**
     * App lock on return to the foreground. Normal rule: lock enabled, a timeout set
     * (0 = never) and that much time elapsed. Strict: lock enabled ⇒ lock on EVERY
     * return, whatever the timeout.
     */
    fun shouldAutoLock(appLock: Boolean, timeoutSec: Int, elapsedMs: Long, strict: Boolean): Boolean = when {
        !appLock -> false
        strict -> true
        else -> timeoutSec > 0 && elapsedMs >= timeoutSec * 1000L
    }

    /** May text be copied out of OSHI (selection toolbar, clipboard, IME copy/cut)? */
    fun copyAllowed(strict: Boolean): Boolean = !strict

    /** May clipboard content be pasted into OSHI? */
    fun pasteAllowed(strict: Boolean, options: StrictOptions): Boolean = !(strict && options.blockPaste)

    /** Network extras that reach a third party: link previews and GIPHY search. */
    fun thirdPartyFetchAllowed(strict: Boolean): Boolean = !strict

    /** Saving / sharing a decrypted photo or video out of OSHI. */
    fun mediaExportAllowed(strict: Boolean): Boolean = !strict

    /**
     * Drop a touch that an AccessibilityService injected (`dispatchGesture`) when a
     * service that is NOT a declared assistive tool is enabled. With only real tools
     * (TalkBack, Switch Access, Voice Access) enabled, injected gestures are theirs
     * and pass, so a person relying on them is not locked out.
     */
    fun dropInjectedTouch(strict: Boolean, motionFlags: Int, nonToolServiceEnabled: Boolean): Boolean =
        strict && nonToolServiceEnabled && (motionFlags and FLAG_IS_ACCESSIBILITY_EVENT) != 0

    /**
     * A field is shown greyed "forced by strict mode" while the mode holds it at a
     * value different from what the person would otherwise have chosen.
     */
    fun isLocked(strict: Boolean, field: String, options: StrictOptions): Boolean {
        if (!strict) return false
        return when (field) {
            FIELD_READ_RECEIPTS, FIELD_TYPING -> options.hidePresence
            else -> field in ALWAYS_FORCED
        }
    }

    const val FIELD_APP_LOCK = "app_lock"
    const val FIELD_SCREENSHOT = "screenshot"
    const val FIELD_INCOGNITO = "incognito"
    const val FIELD_NOTIFICATION_PREVIEW = "notification_preview"
    const val FIELD_READ_RECEIPTS = "read_receipts"
    const val FIELD_TYPING = "typing"
    const val FIELD_PRIVACY_RELAY = "privacy_relay"
    const val FIELD_BLOCK_FORWARD_COPY = "block_forward_copy"
    private val ALWAYS_FORCED = setOf(
        FIELD_SCREENSHOT, FIELD_INCOGNITO, FIELD_NOTIFICATION_PREVIEW, FIELD_BLOCK_FORWARD_COPY,
    )
}

// -----------------------------------------------------------------------------
// Detection: what on this phone can read or drive OSHI. Pure classification;
// the Android half (StrictModeThreats.collect) gathers the raw facts.
// -----------------------------------------------------------------------------

enum class StrictThreatKind { ACCESSIBILITY_SERVICE, REMOTE_CONTROL_APP }

data class StrictThreat(val packageName: String, val label: String, val kind: StrictThreatKind)

/** One enabled accessibility service, as the system reports it. */
data class EnabledA11yService(
    val packageName: String,
    val label: String,
    val isAccessibilityTool: Boolean,
)

object StrictThreatPolicy {

    /**
     * Remote-control, screen-sharing and phone-mirroring apps whose job is to let
     * someone (or something) else see and drive the screen. Package names as
     * published on Google Play / their vendors. Installed is enough to list them:
     * each of these can be armed in seconds once installed.
     * Must stay in sync with the `<queries>` block of AndroidManifest.xml (package
     * visibility, Android 11+): a name missing there is invisible to OSHI.
     */
    val REMOTE_CONTROL_PACKAGES: Map<String, String> = linkedMapOf(
        "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
        "com.teamviewer.host.market" to "TeamViewer Host",
        "com.anydesk.anydeskandroid" to "AnyDesk",
        "com.carriez.flutter_hbb" to "RustDesk",
        "com.sand.airdroid" to "AirDroid",
        "com.sand.airdroidbiz" to "AirDroid Business",
        "com.sand.aircast" to "AirDroid Cast",
        "com.splashtop.sos" to "Splashtop SOS",
        "com.zoho.assist.agent" to "Zoho Assist",
        "net.christianbeier.droidvnc_ng" to "droidVNC-NG",
        "com.koushikdutta.vysor" to "Vysor",
        "com.apowersoft.mirror" to "ApowerMirror",
        "com.microsoft.appmanager" to "Link to Windows",
        "com.logmein.rescuemobile" to "LogMeIn Rescue",
    )

    fun evaluate(
        a11y: List<EnabledA11yService>,
        installedPackages: Set<String>,
        ownPackage: String,
    ): List<StrictThreat> {
        val services = a11y.asSequence()
            .filter { it.packageName != ownPackage && !it.isAccessibilityTool }
            .distinctBy { it.packageName }
            .map { StrictThreat(it.packageName, it.label, StrictThreatKind.ACCESSIBILITY_SERVICE) }
            .sortedBy { it.label.lowercase() }
            .toList()
        val remote = REMOTE_CONTROL_PACKAGES
            .filterKeys { it in installedPackages && it != ownPackage }
            .map { (pkg, label) -> StrictThreat(pkg, label, StrictThreatKind.REMOTE_CONTROL_APP) }
        return services + remote
    }

    /** At least one enabled service is not a declared assistive tool. */
    fun hasNonToolService(a11y: List<EnabledA11yService>, ownPackage: String): Boolean =
        a11y.any { it.packageName != ownPackage && !it.isAccessibilityTool }
}
