package com.oshi.messenger.update

import android.content.Context
import android.content.SharedPreferences

/**
 * __UPDATE_PROMPT_ANDROID_2026_11_21__
 *
 * The two durable facts of the non-forced update prompt:
 *
 *  * "Later" for version N until time T ([snooze] / [snoozedVersionCode] / [snoozedUntilMs]);
 *  * "a FLEXIBLE download of version N was started by us" ([flexibleStartedVersionCode]),
 *    which is what stops [ForcedUpdateManager] reading that download as an
 *    interrupted IMMEDIATE update (see [FlexibleUpdatePolicy.forcedPlayState]).
 *
 * Written against [Storage] so the logic is a JVM unit test with no Android;
 * [fromContext] is the SharedPreferences-backed one the app uses.
 */
class UpdateSnoozeStore(private val storage: Storage) {

    /** The few typed reads and writes this needs; `null` = absent. */
    interface Storage {
        fun getInt(key: String): Int?
        fun getLong(key: String): Long?
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun remove(vararg keys: String)
    }

    val snoozedVersionCode: Int? get() = storage.getInt(KEY_SNOOZED_VERSION)
    val snoozedUntilMs: Long? get() = storage.getLong(KEY_SNOOZED_UNTIL)

    /** "Later": hide the dialog for [versionCode] for [FlexibleUpdatePolicy.SNOOZE_MS]. */
    fun snooze(versionCode: Int, nowMs: Long) {
        storage.putInt(KEY_SNOOZED_VERSION, versionCode)
        storage.putLong(KEY_SNOOZED_UNTIL, nowMs + FlexibleUpdatePolicy.SNOOZE_MS)
    }

    fun isSnoozed(availableVersionCode: Int, nowMs: Long): Boolean =
        FlexibleUpdatePolicy.isSnoozed(availableVersionCode, snoozedVersionCode, snoozedUntilMs, nowMs)

    fun clearSnooze() = storage.remove(KEY_SNOOZED_VERSION, KEY_SNOOZED_UNTIL)

    var flexibleStartedVersionCode: Int?
        get() = storage.getInt(KEY_FLEXIBLE_STARTED)
        set(value) {
            if (value == null) storage.remove(KEY_FLEXIBLE_STARTED)
            else storage.putInt(KEY_FLEXIBLE_STARTED, value)
        }

    companion object {
        const val PREFS = "update_prompt"
        const val KEY_SNOOZED_VERSION = "snoozed_version_code"
        const val KEY_SNOOZED_UNTIL = "snoozed_until_ms"
        const val KEY_FLEXIBLE_STARTED = "flexible_started_version_code"

        fun fromContext(context: Context): UpdateSnoozeStore = UpdateSnoozeStore(
            PrefsStorage(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        )
    }

    private class PrefsStorage(private val prefs: SharedPreferences) : Storage {
        override fun getInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null
        override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
        override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
        override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
        override fun remove(vararg keys: String) {
            prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        }
    }
}
