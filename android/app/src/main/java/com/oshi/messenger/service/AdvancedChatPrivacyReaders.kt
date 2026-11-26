package com.oshi.messenger.service

import android.content.Context
import android.content.SharedPreferences
import com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy

/**
 * __ADV_CHAT_PRIVACY_2026_11_26__ Who may receive a `🛡ADV_PRIVACY🛡` payload.
 *
 * A peer qualifies when its prekey bundle advertises
 * [com.oshi.messenger.network.v2.GroupLegacyCompat.CAP_ADVANCED_PRIVACY], or when it
 * has itself sent us one (proof it reads the sentinel). Unknown means no: a false
 * "no" costs one peer not applying a switch it could not have applied anyway; a
 * false "yes" puts raw JSON in an iOS 1.0.43 thread. Mirrors iOS
 * `AdvancedPrivacy.Readers` / `peerReadsNow`.
 *
 * The "has sent one" record is persisted per canonical key, bounded.
 */
object AdvancedChatPrivacyReaders {

    private const val PREFS = "oshi_adv_privacy"
    private const val KEY = "readers_v1"
    private const val CAP = 2000

    @Volatile private var prefs: SharedPreferences? = null
    private val mem = LinkedHashSet<String>()

    private fun canon(k: String) = AdvancedChatPrivacyStore.canonical(k)

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        mem.addAll(p.getStringSet(KEY, emptySet()) ?: emptySet())
    }

    @Synchronized
    fun contains(peer: String): Boolean = peer.isNotBlank() && canon(peer) in mem

    /** [peer] sent us an `🛡ADV_PRIVACY🛡` payload: it reads them. */
    @Synchronized
    fun note(peer: String) {
        if (peer.isBlank()) return
        if (!mem.add(canon(peer))) return
        while (mem.size > CAP) mem.remove(mem.first())
        // Copy: a set handed to SharedPreferences must not be mutated afterwards.
        prefs?.edit()?.putStringSet(KEY, HashSet(mem))?.apply()
    }

    /** The live answer for one peer: bundle caps or proof by payload. */
    fun peerReadsNow(peer: String): Boolean = AdvancedChatPrivacyPolicy.peerReads(
        accountCaps = runCatching { com.oshi.messenger.network.v2.GroupLegacyCompat.capsOf(peer) }.getOrNull(),
        sentUsOne = contains(peer),
    )

    /** Tests only. */
    @Synchronized
    internal fun resetForTesting() {
        mem.clear()
    }
}
