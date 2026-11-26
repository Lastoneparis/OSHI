package com.oshi.messenger.network.v2

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * __LEGACY_COMPAT_2026_09_23__ Group transition window (owner decision 2026-09-23).
 *
 * Until 2026-10-14 09:00 Europe/Paris, members on OLD builds (iOS ≤ 143, Android ≤ 1.6.25) must
 * keep receiving group messages. Those builds only read the legacy group transport (groupId-keyed
 * envelope on IPFS + `/api/queue`, and `📢GROUP_UPDATE📢` over the legacy 1:1 IPFS path). When the
 * server says `/v2/config.group_legacy_compat = true`, this build ALSO sends that legacy path — to
 * the members it cannot prove are v2-group-capable — on top of the unchanged v2 fan-out.
 *
 * Fail-closed on every axis:
 *  - key missing / config never fetched / parse error ⇒ false (v2 only);
 *  - after [SUNSET_MS] the flag is IGNORED. `/v2/config` is unsigned, so a server (or anyone
 *    who can answer for it) could otherwise re-open the weaker path forever; the compiled-in
 *    sunset caps that downgrade to the agreed window.
 *
 * Capability ("this member reads v2 groups"):
 *  - its prekey bundle `caps` carries [CAP_GROUP_V2] (published by this build and later iOS), or
 *  - we received from it, over the v2 1:1 channel, a full `📢GROUP_UPDATE📢` DEFINITION carrying
 *    `stateVersion` — old builds never sent a group definition over v2 (iOS ≤ 143 only answered
 *    Android sync requests there, with a `created` frame that has no `stateVersion`).
 * Unknown ⇒ NOT capable ⇒ legacy copy too. A new build that also gets the legacy copy dedups it
 * by message id (case-insensitive) in `GroupManager.handleIncomingGroupMessage`.
 */
object GroupLegacyCompat {
    /** 2026-10-14T07:00:00Z = 09:00 Europe/Paris (CEST). */
    const val SUNSET_MS: Long = 1_791_961_200_000L

    /**
     * __RESEND_LIST_ANDROID_2026_10_12__ Prekey-bundle capability bit: "reads a resend list
     * inside a ratchet-heal acknowledgement" (iOS `RatchetHealSignal.Caps.resendList`, same
     * bit). iOS reads it in `peerUnderstandsResendList` BEFORE anything breaks, which is the
     * source that actually works: the acknowledgement-id announcement is only ever learnt on
     * the day the PEER's session breaks, so a device that is always the one breaking never
     * proves itself and its ids expire unused.
     *
     * Announced only because [RatchetHealSignal] is now implemented on both halves — a peer
     * that believes us capable will send us what we said we could read.
     */
    const val CAP_RESEND_LIST: Int = 1 shl 0

    /** Prekey-bundle capability bit: "reads v2 group envelopes and v2 group state". */
    const val CAP_GROUP_V2: Int = 1 shl 1

    /**
     * The caps this build publishes in its bundles.
     * __V2_PADDING_2026_11_09__ + [V2Padding.CAP_PADDING]: this build strips v2 plaintext padding.
     * __MEDIA_PADDING_2026_11_10__ + [V2Padding.CAP_MEDIA_PADDING]: this build reads padded v2
     * media. Changing this value makes [V2MessageRouter] republish the account bundle once
     * (caps drift, zero new one-time pre-keys).
     */
    /**
     * __ADV_CHAT_PRIVACY_2026_11_26__ Prekey-bundle capability bit: "reads 🛡ADV_PRIVACY🛡"
     * (iOS `RatchetHealSignal.Caps.advancedPrivacy`, same value). Desktop does not
     * advertise it, so a desktop peer is never sent the payload.
     */
    const val CAP_ADVANCED_PRIVACY: Int = 1 shl 4

    const val MY_CAPS: Int = CAP_RESEND_LIST or CAP_GROUP_V2 or V2Padding.CAP_PADDING or V2Padding.CAP_MEDIA_PADDING or
        CAP_ADVANCED_PRIVACY

    const val CONFIG_KEY = "group_legacy_compat"

    /** Pure: is the compat window open for this [flag] at [nowMs]? */
    fun isActive(flag: Boolean?, nowMs: Long): Boolean = flag == true && nowMs < SUNSET_MS

    /** Pure: the flag from a `/v2/config` body; anything but a JSON `true` ⇒ false. */
    fun parseFlag(config: JSONObject?): Boolean =
        config != null && config.opt(CONFIG_KEY) == true

    fun hasGroupV2(caps: Int?): Boolean = caps != null && (caps and CAP_GROUP_V2) != 0

    /** __RESEND_LIST_ANDROID_2026_10_12__ Does this peer's bundle announce [CAP_RESEND_LIST]? */
    fun hasResendList(caps: Int?): Boolean = caps != null && (caps and CAP_RESEND_LIST) != 0

    /**
     * Pure: is this decrypted v2 1:1 plaintext a group DEFINITION (proof of a v2-group build)?
     * `📢GROUP_UPDATE📢{…"members":[{…}],…"stateVersion":N…}`.
     */
    fun isV2DefinitionProof(plaintext: String, prefix: String): Boolean {
        if (!plaintext.startsWith(prefix)) return false
        val json = try { JSONObject(plaintext.removePrefix(prefix)) } catch (_: Exception) { return false }
        if (!json.has("stateVersion") || json.isNull("stateVersion")) return false
        val members = json.optJSONArray("members") ?: return false
        return members.length() == 0 || members.optJSONObject(0) != null
    }

    /**
     * Pure: which of [recipients] also need the legacy path. Empty when compat is not active.
     * [canon] canonicalises identities (base64 / base64url spellings).
     */
    fun legacyRecipients(
        recipients: Collection<String>,
        active: Boolean,
        capsOf: (String) -> Int?,
        proven: (String) -> Boolean,
    ): List<String> {
        if (!active) return emptyList()
        return recipients.filter { k ->
            k.isNotBlank() && !k.startsWith("bot:") && !hasGroupV2(capsOf(k)) && !proven(k)
        }.distinct()
    }

    // ------------------------------------------------------------------ __LEAK_FIX_2026_09_25__
    //
    // Audit finding B: while the window is open, EVERY member not proven v2-group-capable got
    // the legacy copy, and the legacy copy is readable by anyone who knows the group id (the
    // key is SHA256(groupId ‖ constant) and the group id travels in invite links). Measured in
    // the shipped code (git 4befbea0 = iOS 1.0.43, 48e60b83^ = Android 1.6.25): every build
    // that advertises `groupEnvelopeV3` in its profile (iOS ≥ 1.0.43, Android ≥ 1.6.1x, all
    // from 2026-09-04) ALSO reads v2 `type:"group"` envelopes (iOS `v2DeliverGroup` since
    // 2026-07-05, Android `dispatchV2GroupText` since 1.6.3) and `📢GROUP_UPDATE📢` over v2 1:1.
    // So a member the v2 send REACHED and that advertises group v3 already has the message:
    // the legacy copy only added a readable duplicate. And when every member that still needs
    // the legacy copy advertises group v3, the copy goes as the per-member wrapped v4 envelope
    // (GroupEnvelopeV3 — no group key), exactly what those builds themselves sent before
    // 48e60b83. The groupId-keyed envelope remains ONLY for a member on a build older than
    // 2026-09-04 (or one we never received a profile from) — and only until [SUNSET_MS].

    /** Pure: which [candidates] still need a legacy copy after the v2 send reached [v2Reached]. */
    fun stillNeedLegacy(
        candidates: Collection<String>,
        v2Reached: Collection<String>,
        readsV2Groups: (String) -> Boolean,
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val reached = v2Reached.mapTo(HashSet()) { canon(it) }
        return candidates.filter { k -> !(canon(k) in reached && readsV2Groups(k)) }.distinct()
    }

    /**
     * __GROUP_FANOUT_2026_11_24__ Pure: a member the v2 send could not reach ONLY because the relay
     * rate-limited us is never handed to the legacy path for it. Legacy is for builds that cannot
     * read v2 groups; a 429 says nothing about the member, and the legacy envelope (groupId-derived
     * key) is weaker than the per-member ratchet it would replace.
     */
    fun withoutRateLimited(legacy: List<String>, rateLimited: Collection<String>): List<String> {
        if (rateLimited.isEmpty() || legacy.isEmpty()) return legacy
        val limited = rateLimited.mapTo(HashSet()) { canon(it) }
        return legacy.filter { canon(it) !in limited }
    }

    enum class LegacyEnvelope { V4_WRAPPED, GROUP_ID_KEY }

    /** Pure: the legacy content envelope for [targets] (all must read v4 for [V4_WRAPPED]). */
    fun legacyEnvelopeFor(targets: Collection<String>, readsV4: (String) -> Boolean): LegacyEnvelope =
        if (targets.isNotEmpty() && targets.all(readsV4)) LegacyEnvelope.V4_WRAPPED else LegacyEnvelope.GROUP_ID_KEY

    // ------------------------------------------------------------------ live state (process-wide)

    private const val PREFS = "oshi_group_legacy_compat"
    private const val K_FLAG = "flag"
    private const val K_CAPS = "peer_caps"
    private const val K_PROOF = "peer_proof"
    private const val MAX_PEERS = 1000

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var serverFlag: Boolean = false
    private val peerCaps = ConcurrentHashMap<String, Int>()
    private val proofs = ConcurrentHashMap.newKeySet<String>()

    /** Idempotent. Loads the persisted flag / caps / proofs (a cold start before the first
     *  `/v2/config` fetch keeps the last server answer; the sunset still applies). */
    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val o = JSONObject(p.getString(K_CAPS, "{}") ?: "{}")
            o.keys().forEach { k -> if (!peerCaps.containsKey(k)) peerCaps[k] = o.optInt(k, 0) }
        }
        runCatching {
            val a = JSONArray(p.getString(K_PROOF, "[]") ?: "[]")
            for (i in 0 until a.length()) proofs.add(a.getString(i))
        }
        serverFlag = p.getBoolean(K_FLAG, false)
        prefs = p
    }

    /** From every successful `/v2/config` read. */
    fun setServerFlag(flag: Boolean) {
        serverFlag = flag
        prefs?.edit()?.putBoolean(K_FLAG, flag)?.apply()
    }

    fun isActiveNow(): Boolean = isActive(serverFlag, System.currentTimeMillis())

    private fun canon(k: String): String =
        com.oshi.messenger.service.GroupUpdateAuthorizer.canonicalIdentity(k)

    fun notePeerCaps(peer: String, caps: Int) {
        val c = canon(peer)
        if (peerCaps.put(c, caps) == caps) return
        trimAndPersist()
    }

    fun noteV2DefinitionFrom(peer: String) {
        if (!proofs.add(canon(peer))) return
        trimAndPersist()
    }

    fun capsOf(peer: String): Int? = peerCaps[canon(peer)]
    fun isProven(peer: String): Boolean = proofs.contains(canon(peer))

    /** Live partition used by GroupManager. */
    fun legacyRecipientsNow(recipients: Collection<String>): List<String> =
        legacyRecipients(recipients, isActiveNow(), ::capsOf, ::isProven)

    @Synchronized
    private fun trimAndPersist() {
        while (peerCaps.size > MAX_PEERS) peerCaps.keys.firstOrNull()?.let { peerCaps.remove(it) } ?: break
        while (proofs.size > MAX_PEERS) proofs.firstOrNull()?.let { proofs.remove(it) } ?: break
        val p = prefs ?: return
        p.edit()
            .putString(K_CAPS, JSONObject(peerCaps as Map<*, *>).toString())
            .putString(K_PROOF, JSONArray(proofs.toList()).toString())
            .apply()
    }

    /** Tests only. */
    internal fun resetForTest() {
        prefs = null; serverFlag = false; peerCaps.clear(); proofs.clear()
    }
}
