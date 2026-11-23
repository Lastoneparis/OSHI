package com.oshi.messenger.service.diag

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.io.File

/**
 * __CALL_LOG_AT_REST_2026_09_23__ Android seam of the sealed call log ([CallFileLogger]).
 *
 * Why it exists: every `DIAG_*` line the call stack writes went to logcat only, and R8
 * strips `Log.w/d/i` from release builds (proguard-rules.pro). A release phone therefore
 * kept NO call diagnostics at all, and "send me your log" had nothing to send. The iOS
 * file log (with its redacted share button) is what finally made cross-platform call
 * failures diagnosable from the peer's side; this is the same thing here.
 *
 * `w/d` still write logcat exactly as before (so behaviour in debug and in JVM tests is
 * unchanged), then append to the sealed file. Never put message text, contact names,
 * phone numbers or keys in a line routed here.
 */
object CallDiag {
    const val LOG_DIR = "call_logs"
    const val EXPORT_DIR = "call_diagnostics"

    @Volatile var logger: CallFileLogger? = null
        private set

    /** First lines of `Application.onCreate`. The key is only touched on the logger thread. */
    fun install(context: Context) {
        if (logger != null) return
        val app = context.applicationContext
        logger = CallFileLogger(
            directory = File(app.filesDir, LOG_DIR),
            exportDirectory = File(app.cacheDir, EXPORT_DIR),
            keyProvider = { CallLogKey.key(app) },
        )
        // Backgrounding is where Android may kill us without another chance: seal what
        // is buffered now, not 250 ms later.
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flush()
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() { flush() }
        })
    }

    fun w(tag: String, line: String) {
        Log.w(tag, line)
        logger?.log(line)
    }

    fun d(tag: String, line: String) {
        Log.d(tag, line)
        logger?.log(line)
    }

    /** File only — for high-rate lines whose logcat copy is already emitted elsewhere. */
    fun file(line: String) {
        logger?.log(line)
    }

    /** State changes and call end: sealed immediately, off the caller's thread. */
    fun urgent(tag: String, line: String) {
        Log.w(tag, line)
        logger?.logUrgent(line)
    }

    fun flush() {
        logger?.flush()
    }
}
