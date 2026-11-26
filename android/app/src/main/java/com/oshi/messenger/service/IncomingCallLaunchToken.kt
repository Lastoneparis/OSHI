package com.oshi.messenger.service

import android.content.Intent
import java.security.SecureRandom

/**
 * 🔒 __LOCKSCREEN_INTENT_SPOOF_2026_11_20__ Proof that a MainActivity launch carrying
 * `incoming_call=true` was built by THIS process.
 *
 * MainActivity is exported (launcher, share target, deep links). It shows itself over
 * the keyguard when the intent says `incoming_call` (__LOCKED_MEANS_LOCKED_2026_10_28__).
 * A boolean extra is something ANY app can set: another app on the phone (for example
 * through its own full-screen notification or a PendingIntent) could start OSHI with
 * `incoming_call=true` while the phone is locked and the whole app — conversation list,
 * chats — would open over the lock screen for whoever is holding it.
 *
 * Every legitimate sender (IncomingCallService, OshiConnection, FCMService) also puts
 * [EXTRA_TOKEN] = [value], a random secret that lives only in this process' memory. The
 * activity honours `incoming_call` only when the token matches. A call ringing in a dead
 * process cannot exist, so a per-process secret loses nothing.
 */
object IncomingCallLaunchToken {
    const val EXTRA_TOKEN = "com.oshi.messenger.extra.INCOMING_CALL_TOKEN"

    val value: String by lazy {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        b.joinToString("") { "%02x".format(it) }
    }

    /** Pure check, unit-testable. */
    fun matches(offered: String?, expected: String): Boolean =
        !offered.isNullOrEmpty() && java.security.MessageDigest.isEqual(offered.toByteArray(), expected.toByteArray())

    fun stamp(intent: Intent): Intent = intent.putExtra(EXTRA_TOKEN, value)

    fun isGenuine(intent: Intent?): Boolean = matches(intent?.getStringExtra(EXTRA_TOKEN), value)
}
