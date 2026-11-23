package com.oshi.messenger.service

import android.content.Context
import com.oshi.messenger.data.local.ContactDao
import kotlinx.coroutines.flow.first

/**
 * The two admission gates every incoming-call ingress must pass, in one place.
 *
 * ## Why this exists
 *
 * 🔧 P0 FIX (2026-08-13 cross-file integration, item C). `contacts_only_calls` was
 * written by `SettingsViewModel` — to DataStore *and* mirrored into the
 * `oshi_settings` SharedPreferences under the iOS-compatible key
 * `callsFromContactsOnly` (`SettingsViewModel.kt:328,606`) — and then read by
 * NOBODY. A user who turned "calls from contacts only" on across both of their
 * phones was protected on the iPhone and completely unprotected on the Android:
 * any stranger who knew the public key could ring it, and the FCM push alone was
 * enough to light the full-screen incoming-call UI.
 *
 * iOS enforces it in TWO places, and both are mirrored on Android:
 *  - the signal handler, `VoiceCallManager.swift:6281` (predicate
 *    `shouldRejectUnknownCaller` at `:12639-12643`, membership test `isKnownContact`
 *    at `:12629-12637`) → `EnhancedCallManager.handleIncomingCall`;
 *  - the push handler, `VoIPPushManager.swift:1326-1339`, which rejects BEFORE
 *    reporting the call to CallKit → `FCMService`, before it ever starts
 *    `IncomingCallService`.
 *
 * ## Semantics, verbatim from iOS
 *
 * - Default is OFF (`UserDefaults.bool(forKey:)` returns `false` when unset —
 *   `VoiceCallManager.swift:12640`), so the gate is inert until the user opts in.
 * - "Known contact" on iOS means *has an existing conversation*
 *   (`VoiceCallManager.swift:12630-12635`); there is no separate address book. The
 *   Android analogue is a row in the `contacts` table, which
 *   `MessageRepository.ensureContactExists` creates on the first inbound message from
 *   a peer — the same "we have talked before" test.
 * - Matching is base64url-normalized on both sides with a raw-string fallback
 *   (`swift:12631-12634`). Note iOS's own push path (`VoIPPushManager.swift:1329`)
 *   forgets to normalize and can therefore reject a genuine contact; that divergence
 *   is an iOS bug and is deliberately NOT reproduced — both Android ingresses
 *   normalize.
 * - Fail-closed: when the contact set cannot be read, the caller is treated as
 *   unknown, exactly like iOS's `?? false` at `swift:1330` and the `guard ... else
 *   { return false }` at `swift:12630`.
 *
 * Everything decision-shaped here is a pure function so it is unit-testable off-device.
 */
object CallAdmissionPolicy {

    /** SharedPreferences file `SettingsViewModel` mirrors the setting into. */
    const val PREFS_NAME = "oshi_settings"

    /** iOS `UserDefaults` key — `VoiceCallManager.swift:12640`, `VoIPPushManager.swift:1326`. */
    const val KEY_CONTACTS_ONLY_CALLS = "callsFromContactsOnly"

    /**
     * Is "calls from contacts only" turned on?
     * Reads the SharedPreferences mirror, not DataStore, so it is safe to call from a
     * Service on the FCM thread with no coroutine.
     */
    fun isContactsOnlyEnabled(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONTACTS_ONLY_CALLS, false)
    } catch (_: Throwable) {
        false   // a prefs failure must never start silently rejecting every call
    }

    /**
     * Canonical form for key comparison — base64url → base64, padding dropped.
     * Same equivalence `MessageRepository.normalizeBase64Key` uses.
     */
    fun normalizeKey(key: String): String =
        key.trim().replace('-', '+').replace('_', '/').replace("=", "")

    /**
     * Does [callerKey] belong to someone we already have a conversation with?
     * iOS `VoiceCallManager.isKnownContact` (`swift:12629-12637`): normalized compare,
     * with a raw-string fallback for keys stored in an odd spelling.
     */
    fun isKnownContact(callerKey: String, knownKeys: Collection<String>): Boolean {
        if (callerKey.isEmpty()) return false
        val normalized = normalizeKey(callerKey)
        return knownKeys.any { normalizeKey(it) == normalized || it == callerKey }
    }

    /**
     * The gate itself. iOS `shouldRejectUnknownCaller` (`swift:12639-12643`):
     * off → never reject; on → reject anyone we have no conversation with.
     */
    fun shouldRejectUnknownCaller(contactsOnlyEnabled: Boolean, isKnownContact: Boolean): Boolean =
        contactsOnlyEnabled && !isKnownContact

    // =====================================================================
    // DAO-backed helpers for the call managers
    // =====================================================================
    //
    // The call managers (`EnhancedCallManager`, `IncomingCallService`,
    // `ScheduledCallManager`) all already hold an `AppDatabase`, and none of them
    // holds a `MessageRepository`. They go through the DAO here rather than through
    // `MessageRepository.isContactBlocked` purely to avoid adding
    // `EnhancedCallManager → MessageRepository → CallManager` to the Dagger graph for
    // a two-column read. The comparison semantics are identical (normalized key), so
    // the two paths cannot disagree about whether someone is blocked.

    /** 🚫 Normalized block check straight off the contacts table. */
    suspend fun isBlocked(contactDao: ContactDao, publicKey: String): Boolean {
        if (publicKey.isEmpty()) return false
        // __BLOCKED_NOTIF_2026_09_23__ the durable list first: it survives a deleted row.
        if (BlockedKeysStore.current()?.isBlocked(publicKey) == true) return true
        return try {
            if (contactDao.getContactByPublicKey(publicKey)?.isBlocked == true) return true
            // Fall back to a normalized sweep so a differently-padded spelling of the
            // same key cannot walk past the block.
            val normalized = normalizeKey(publicKey)
            contactDao.getBlockedContacts().first()
                .any { normalizeKey(it.publicKey) == normalized }
        } catch (_: Exception) {
            false   // a DB failure must not start dropping legitimate calls
        }
    }

    /**
     * __BLOCKED_NO_TRACE_2026_09_24__ Non-suspending block test for the call-end writers (the
     * in-thread MISSED_CALL / CALL_SUMMARY row is built on a non-suspending path): durable list,
     * then the repository's normalized cache. Owner decision: no call trace for a blocked
     * contact anywhere — the ingress gates already refuse them; this is the belt to those braces.
     */
    fun isBlockedCached(publicKey: String): Boolean {
        if (publicKey.isEmpty()) return false
        if (BlockedKeysStore.current()?.isBlocked(publicKey) == true) return true
        return com.oshi.messenger.data.repository.MessageRepository.isSenderBlockedCached(publicKey)
    }

    /** 🚫 Contacts-only gate. iOS `VoiceCallManager.swift:6281` / `VoIPPushManager.swift:1326-1339`. */
    suspend fun rejectsUnknownCaller(
        context: Context,
        contactDao: ContactDao,
        callerKey: String
    ): Boolean {
        val contactsOnly = isContactsOnlyEnabled(context)
        if (!contactsOnly) return false
        val known = try {
            isKnownContact(callerKey, contactDao.getAllContactsList().map { it.publicKey })
        } catch (_: Exception) {
            false   // fail closed, as iOS does with `?? false` (swift:1330)
        }
        return shouldRejectUnknownCaller(contactsOnly, known)
    }
}
