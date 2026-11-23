package com.oshi.messenger.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

// =============================================================================
// __CLIPBOARD_HYGIENE_2026_10_01__ "What OSHI decrypted should not sit on the
// clipboard forever."
//
// Copying a message, a translation, a recovery key or a bot token used to put the
// plaintext on the system clipboard with no expiry: any keyboard (the default IME
// can always read it), any foreground app later on, and the Android 13+ clipboard
// preview overlay could show it. Every such copy now goes through [copy]:
//   1. ClipDescription EXTRA_IS_SENSITIVE ⇒ Android 13+ hides the preview overlay;
//   2. cleared after 60 s IF the clipboard still holds our text (never wipe
//      something the person copied elsewhere since).
// Android 10+ forbids a background app from reading the clipboard, so when the
// 60 s elapse while OSHI is in the background we cannot check it is still ours —
// the clear is then retried as soon as OSHI regains window focus
// ([onAppFocused], called from MainActivity.onWindowFocusChanged).
// "Still ours" is decided from the clip DESCRIPTION only (system timestamp + label +
// sensitive flag), never by reading the clip: on Android 12+ reading a clip another
// app set shows "OSHI pasted from your clipboard", and a privacy messenger must not
// look like it snoops on a password copied elsewhere. The pending entry (no
// plaintext, no hash) is persisted so a process killed in the background still
// clears on the next launch.
// Clearing the primary clip cannot reach copies a keyboard or the phone already
// kept in its own clipboard history (Gboard, Samsung, SwiftKey) — the explainer
// says so.
// Public keys, addresses, invite links and group ids are made to be shared and are
// NOT routed here: hiding their preview would only make them harder to verify.
// =============================================================================
object SensitiveClipboard {

    /** == ClipDescription.EXTRA_IS_SENSITIVE (API 33); spelled out so it also reads on older SDKs. */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    const val CLEAR_AFTER_MS = 60_000L
    const val LABEL = "OSHI"

    private const val PREFS = "oshi_clipboard_hygiene"
    private const val K_TS = "clip_ts"
    private const val K_LABEL = "clip_label"
    private const val K_COPIED_AT = "copied_at"
    private const val K_CLEAR_AT = "clear_at"

    /**
     * What identifies OUR clip without reading it: the system timestamp of the
     * ClipDescription (set by ClipboardService on every setPrimaryClip, API 26 =
     * minSdk), its label, and the sensitive flag. Wall-clock times so it survives
     * a process death (elapsedRealtime would not survive a reboot either).
     */
    data class Pending(val clipTimestamp: Long, val label: String, val copiedAtWall: Long, val clearAtWall: Long)

    @Volatile private var pending: Pending? = null
    @Volatile private var loaded = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val clearRunnable = Runnable { attemptClear(appContext ?: return@Runnable, focused = false) }
    @Volatile private var appContext: Context? = null

    /** Copies [text] as sensitive and schedules its removal. */
    fun copy(context: Context, text: String, label: String = LABEL) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, text)
        try {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        } catch (_: Exception) {
        }
        try {
            cm.setPrimaryClip(clip)
        } catch (_: Exception) {
            return
        }
        val app = context.applicationContext
        appContext = app
        // We are in the foreground (a copy is a tap), so the description is readable
        // now and carries the timestamp the system just stamped. Description only:
        // no clip read, no toast.
        val ts = try { cm.primaryClipDescription?.timestamp ?: 0L } catch (_: Exception) { 0L }
        val now = System.currentTimeMillis()
        setPending(app, Pending(ts, label, now, now + CLEAR_AFTER_MS))
        schedule(CLEAR_AFTER_MS)
    }

    /** Retry point for a clear that came due while OSHI could not read the clipboard, or before a process death. */
    fun onAppFocused(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (load(app) == null) return
        attemptClear(app, focused = true)
    }

    /**
     * After pasting a recovery key into OSHI, it has no reason to stay on the
     * clipboard: remove it now if it is still exactly what was pasted. (The person
     * just pasted it into OSHI, so this read is the paste they asked for.)
     */
    fun clearIfStillHolds(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val current = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        } catch (_: Exception) {
            null
        } ?: return
        if (current == text) clear(cm)
    }

    /**
     * [focused]: OSHI holds window focus, so a null description really means
     * "empty" rather than "Android 10+ refused a background read".
     */
    private fun attemptClear(context: Context, focused: Boolean) {
        val p = load(context) ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val now = System.currentTimeMillis()
        val desc = try { cm.primaryClipDescription } catch (_: Exception) { null }
        val matches: Boolean? = desc?.let {
            descriptionMatches(
                p,
                currentTimestamp = it.timestamp,
                currentLabel = it.label?.toString(),
                currentSensitive = try { it.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true } catch (_: Exception) { false },
            )
        }
        when (decide(p.copiedAtWall, p.clearAtWall, now, matches, clipboardReadable = desc != null || focused)) {
            Decision.CLEAR -> { clear(cm); setPending(context, null) }
            Decision.FORGET -> setPending(context, null)
            Decision.WAIT -> if (now < p.clearAtWall && now >= p.copiedAtWall) schedule(p.clearAtWall - now)
        }
    }

    enum class Decision { CLEAR, FORGET, WAIT }

    /**
     * Pure rule, unit-tested:
     *  - not due yet ⇒ WAIT (a wall clock set BACK before the copy counts as due,
     *    so a clock change cannot keep the clip alive indefinitely);
     *  - clipboard description unreadable (background on Android 10+) ⇒ WAIT for focus;
     *  - description still ours ⇒ CLEAR;
     *  - anything else (empty, or the person copied something else) ⇒ FORGET.
     * [descriptionMatches] is null when there is no description (empty or unreadable).
     */
    fun decide(
        copiedAtWall: Long,
        clearAtWall: Long,
        nowWall: Long,
        descriptionMatches: Boolean?,
        clipboardReadable: Boolean,
    ): Decision = when {
        nowWall < clearAtWall && nowWall >= copiedAtWall -> Decision.WAIT
        !clipboardReadable -> Decision.WAIT
        descriptionMatches == true -> Decision.CLEAR
        else -> Decision.FORGET
    }

    /**
     * Pure: is the clip described by (timestamp, label, sensitive) the one we set?
     * Our clips always carry the sensitive flag; when the system gave no timestamp
     * at copy time (0), label + flag must do.
     */
    fun descriptionMatches(
        p: Pending,
        currentTimestamp: Long,
        currentLabel: String?,
        currentSensitive: Boolean,
    ): Boolean {
        if (currentLabel != p.label) return false
        if (p.clipTimestamp != 0L && currentTimestamp != p.clipTimestamp) return false
        // Before API 33 the extras are still carried, so the flag is checked on every
        // version; a clip without it is never ours.
        return currentSensitive
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(clearRunnable)
        handler.postDelayed(clearRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun load(context: Context): Pending? {
        if (loaded) return pending
        pending = try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sp.contains(K_CLEAR_AT)) {
                Pending(
                    sp.getLong(K_TS, 0L),
                    sp.getString(K_LABEL, LABEL) ?: LABEL,
                    sp.getLong(K_COPIED_AT, 0L),
                    sp.getLong(K_CLEAR_AT, 0L),
                )
            } else null
        } catch (_: Exception) {
            null
        }
        loaded = true
        return pending
    }

    private fun setPending(context: Context, p: Pending?) {
        pending = p
        loaded = true
        try {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (p == null) {
                e.clear()
            } else {
                e.putLong(K_TS, p.clipTimestamp)
                    .putString(K_LABEL, p.label)
                    .putLong(K_COPIED_AT, p.copiedAtWall)
                    .putLong(K_CLEAR_AT, p.clearAtWall)
            }
            e.apply()
        } catch (_: Exception) {
        }
    }

    private fun clear(cm: ClipboardManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
        }
    }
}
