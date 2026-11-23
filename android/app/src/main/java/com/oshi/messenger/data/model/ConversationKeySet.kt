package com.oshi.messenger.data.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A persisted set of one-to-one conversation keys, observable as a flow.
 *
 * Keys are compared in [normalize]d form — the same case- and
 * base64-spelling-insensitive form the notification ids use
 * (`MessageBanner.normalizeKey`, iOS `MessageBannerPolicy.normalizedKey`) — so a
 * key that reaches a gate from a transport in base64url still matches the one
 * the list stored in standard base64.
 *
 * Storage is injected so the rules can be tested without Android; see
 * [ConversationMutes] and [SavedContactThreads] for the two real instances.
 * Both live in `shared_prefs`, which the account wipe and the panic wipe clear
 * file by file (`PrefsWipePolicy`), like the pinned-conversation set.
 */
class ConversationKeySet(
    load: () -> Set<String>,
    private val persist: (Set<String>) -> Unit,
) {
    private val _keys = MutableStateFlow(load().map(::normalize).filter { it.isNotEmpty() }.toSet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun contains(key: String?): Boolean =
        key != null && key.isNotBlank() && normalize(key) in _keys.value

    /** Add or remove [key]. Returns whether it is in the set afterwards. */
    @Synchronized
    fun set(key: String, member: Boolean): Boolean {
        val k = normalize(key)
        if (k.isEmpty()) return false
        val before = _keys.value
        val after = if (member) before + k else before - k
        if (after != before) {
            _keys.value = after
            runCatching { persist(after) }
        }
        return member
    }

    /** Flip [key]. Returns whether it is in the set afterwards. */
    @Synchronized
    fun toggle(key: String): Boolean = set(key, !contains(key))

    companion object {
        fun normalize(key: String): String =
            key.trim().replace('-', '+').replace('_', '/').trimEnd('=')

        internal fun prefsBacked(context: Context, prefsName: String): ConversationKeySet {
            val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            return ConversationKeySet(
                load = { prefs.getStringSet("keys", null)?.toSet().orEmpty() },
                // A COPY: SharedPreferences must never be handed a set it may
                // later see mutated.
                persist = { prefs.edit().putStringSet("keys", HashSet(it)).apply() },
            )
        }
    }
}

/**
 * __MUTE_A_CONVERSATION_2026_11_13__ (Anatole N8) One-to-one conversations whose
 * new messages must not raise a notification. Groups already had this
 * (`Group.isMuted`); a one-to-one thread had no mute at all on Android.
 *
 * Muting is about RINGING, never about delivery: the gates that read this sit
 * after the poll and the receipt, exactly like the group mute in `FCMService`.
 */
object ConversationMutes {
    private const val PREFS = "oshi_conversation_mutes"
    @Volatile private var instance: ConversationKeySet? = null

    fun get(context: Context): ConversationKeySet = instance ?: synchronized(this) {
        instance ?: ConversationKeySet.prefsBacked(context, PREFS).also { instance = it }
    }
}

/**
 * __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18) Contacts the user
 * deliberately saved, which the conversation list shows even before the first
 * message.
 *
 * The list is built from contact rows but dropped every row without a message,
 * so a contact saved from a QR code was invisible until someone wrote. Showing
 * EVERY message-less contact instead is not an option: "Delete conversation"
 * deletes the messages and keeps the contact, so every thread the user had
 * deleted would come back, and a LoRa channel speaker (a contact row whose only
 * messages live in the channel room) would get an empty thread of their own.
 * So the list shows a message-less contact only when it is in this set: added
 * when the user saves a contact, removed when they delete the conversation.
 */
object SavedContactThreads {
    private const val PREFS = "oshi_saved_contact_threads"
    @Volatile private var instance: ConversationKeySet? = null

    fun get(context: Context): ConversationKeySet = instance ?: synchronized(this) {
        instance ?: ConversationKeySet.prefsBacked(context, PREFS).also { instance = it }
    }

    /**
     * Whether a contact row with no message at all belongs in the list.
     * Pure, so the rule is testable: saved, and not a LoRa interop or channel key.
     */
    fun showsEmptyThread(publicKey: String, saved: Set<String>, isRadioKey: Boolean): Boolean =
        !isRadioKey && ConversationKeySet.normalize(publicKey) in saved
}
