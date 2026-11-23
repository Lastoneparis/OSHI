package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.model.OSHIControlPayload
import org.json.JSONObject

/**
 * Who, in a group, actually received and read one message.
 *
 * __GROUP_RECEIPTS_2026_11_02__ (N8) Port of `OSHI/GroupReceipts.swift`. The
 * wire format is the contract between the two, so the JSON keys and the kind
 * codes here are not free to change on one side.
 *
 * Why it exists at all: there was no per-recipient delivery or read state for
 * group messages on ANY platform. The 1:1 `📖READ_RECEIPT📖` carries no message
 * id, so it means "this conversation is read" and cannot answer a question
 * about one message among many, let alone one member among several.
 *
 * The shape, and the reasons, match iOS exactly:
 *  * the receipt goes to the SENDER ONLY, as an ordinary 1:1 control message —
 *    fanning out would cost N² messages per group message and would tell every
 *    member when every other member read it;
 *  * it names the message, which is the whole difference from the 1:1 receipt;
 *  * two kinds, because "did it arrive" and "did they look" are two questions;
 *  * READ obeys the read-receipts setting, DELIVERY does not — "received" says
 *    a device took bytes, "read" says a person looked.
 */
object GroupReceipts {

    private const val TAG = "GroupReceipts"
    const val PREFIX = OSHIControlPayload.GROUP_RECEIPT

    enum class Kind(val code: String) {
        RECEIVED("d"),
        READ("r");

        companion object {
            fun from(code: String?): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    data class Parsed(val groupId: String, val msgId: String, val kind: Kind, val at: Long)

    /** What one member did with one message. */
    data class MemberState(val receivedAt: Long? = null, val readAt: Long? = null)

    /** One row of the "who has seen this" sheet. */
    data class Row(val memberKey: String, val receivedAt: Long?, val readAt: Long?) {
        val hasRead: Boolean get() = readAt != null
        val hasReceived: Boolean get() = receivedAt != null
    }

    // ---------------------------------------------------------------- wire

    fun encode(groupId: String, msgId: String, kind: Kind, at: Long = System.currentTimeMillis()): String {
        val body = JSONObject()
            .put("g", groupId)
            .put("m", msgId)
            .put("k", kind.code)
            .put("t", at / 1000)
        return PREFIX + body.toString()
    }

    /**
     * null for anything that is not one of ours, or that is malformed.
     *
     * Strict about the message id: it becomes a map key supplied by a remote
     * peer, so an empty or absurd one is refused rather than stored.
     */
    fun parse(text: String?): Parsed? {
        if (text == null || !text.startsWith(PREFIX)) return null
        return try {
            val o = JSONObject(text.removePrefix(PREFIX))
            val g = o.optString("g").takeIf { it.isNotEmpty() } ?: return null
            val m = o.optString("m").takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
            val k = Kind.from(o.optString("k")) ?: return null
            val t = if (o.has("t")) o.optLong("t") * 1000L else System.currentTimeMillis()
            Parsed(g, m, k, t)
        } catch (e: Exception) {
            Log.w(TAG, "unreadable receipt: ${e.message}")
            null
        }
    }

    // ------------------------------------------------- who may receive one

    /**
     * __GROUP_RECEIPT_IOS_1043_2026_11_13__ (tester report N11: "group messages
     * are not decrypted and show as group receipt").
     *
     * A receipt is a 1:1 message addressed to the SENDER of a group message. The
     * iOS build on sale (1.0.43, git 4befbea0, 2026-09-07) predates this sentinel
     * by two months: `📑GROUP_RECEIPT📑` is not in its `OSHIControlPayload.catalog`,
     * so `isSilent` is false, `ChatView.computeFilteredMessages` keeps it, and
     * `displaySummary` returns nil ("ordinary prose"). Every time an iPhone user
     * wrote in a group, each Android member answered with one or two 1:1 bubbles
     * of raw `📑GROUP_RECEIPT📑{"g":"…","m":"…","k":"d","t":…}` in the iPhone's
     * private thread with them, plus an in-app banner of the same text. That is
     * what reads as "an undecrypted group message".
     *
     * No iOS build can reach users (App Store account frozen), so the sender
     * decides: a receipt goes ONLY to a peer that provably reads the sentinel.
     * Unknown is read as "cannot" — the cost of a false "no" is one blank row in
     * the "who read it" sheet; the cost of a false "yes" is junk in someone's chat.
     *
     * Evidence accepted, any one suffices:
     *  1. the peer has itself sent us a group receipt (it obviously reads them);
     *  2. its ACCOUNT bundle `caps` carries [CAP_PADDING_PROXY]. Every build that
     *     publishes that bit (2026-11-09, commit 705a7fd6: iOS, Android 1.6.9x,
     *     desktop) descends from the receipts commits of 2026-11-02 on all three
     *     platforms (b5a5c0ac, 9089fed0, 137072a8). iOS 1.0.43 publishes caps = 1;
     *  3. the account slot of [com.oshi.messenger.network.v2.V2PaddingPeers] says
     *     the peer pads (learnt from its bundle or from a padded message it sent) —
     *     the same proof as 2, from the other source;
     *  4. its profile says platform `android` and a version ≥
     *     [FIRST_ANDROID_VERSION_READING_RECEIPTS] (the first release carrying
     *     9089fed0), for an Android peer whose bundle we fetched before it updated.
     */
    const val FIRST_ANDROID_VERSION_READING_RECEIPTS = "1.6.63"

    /** `V2Padding.CAP_PADDING`, used here as "this build is from 2026-11-09 or later". */
    const val CAP_PADDING_PROXY: Int = com.oshi.messenger.network.v2.V2Padding.CAP_PADDING

    /** Pure decision; see [FIRST_ANDROID_VERSION_READING_RECEIPTS] for the reasons. */
    fun peerReadsReceipts(
        accountCaps: Int?,
        accountSlotPads: Boolean,
        sentUsAReceipt: Boolean,
        platform: String?,
        version: String?,
    ): Boolean {
        if (sentUsAReceipt) return true
        if (accountCaps != null && (accountCaps and CAP_PADDING_PROXY) != 0) return true
        if (accountSlotPads) return true
        if (platform == com.oshi.messenger.network.version.ProfileVersionWire.PLATFORM_ANDROID &&
            version != null &&
            (com.oshi.messenger.network.version.AppVersions
                .compareOrNull(version, FIRST_ANDROID_VERSION_READING_RECEIPTS) ?: -1) >= 0
        ) return true
        return false
    }

    /** The live answer for [peer], gathered from the three stores that know something. */
    fun peerReadsReceiptsNow(context: Context, peer: String): Boolean {
        val canon = com.oshi.messenger.service.GroupUpdateAuthorizer.canonicalIdentity(peer)
        val caps = com.oshi.messenger.network.v2.GroupLegacyCompat.capsOf(peer)
        val slot = runCatching {
            com.oshi.messenger.network.v2.V2PaddingPeers.slotCapable(peer, null)
        }.getOrDefault(false)
        val platform = com.oshi.messenger.network.encryption.PeerCapabilities.appPlatform(context, peer)
            ?: com.oshi.messenger.network.encryption.PeerCapabilities.appPlatform(context, canon)
        val version = com.oshi.messenger.network.encryption.PeerCapabilities.appVersion(context, peer)
            ?: com.oshi.messenger.network.encryption.PeerCapabilities.appVersion(context, canon)
        return peerReadsReceipts(caps, slot, isKnownReader(context, peer), platform, version)
    }

    private const val KEY_READERS = "readers_v1"
    private const val MAX_READERS = 1000

    /** Peers that have sent us a receipt — proof (evidence 1) that they read the sentinel. */
    fun isKnownReader(context: Context, peer: String): Boolean {
        val canon = com.oshi.messenger.service.GroupUpdateAuthorizer.canonicalIdentity(peer)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_READERS, emptySet())!!.contains(canon)
    }

    @Synchronized
    private fun noteReader(context: Context, peer: String) {
        val canon = com.oshi.messenger.service.GroupUpdateAuthorizer.canonicalIdentity(peer)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = p.getStringSet(KEY_READERS, emptySet())!!
        if (canon in cur) return
        // Copy: the returned set must not be mutated (SharedPreferences contract).
        val next = HashSet(cur)
        if (next.size >= MAX_READERS) next.firstOrNull()?.let { next.remove(it) }
        next.add(canon)
        p.edit().putStringSet(KEY_READERS, next).apply()
    }

    // --------------------------------------------------------------- store

    private const val PREFS = "oshi_group_receipts"
    private const val KEY = "table_v1"

    /** How many messages to remember. A map written from the network needs a bound. */
    private const val MAX_MESSAGES = 500

    private fun key(groupId: String, msgId: String) = "$groupId|$msgId"

    private fun load(context: Context): JSONObject =
        try {
            JSONObject(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "{}") ?: "{}"
            )
        } catch (_: Exception) { JSONObject() }

    private fun save(context: Context, table: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, table.toString()).apply()
    }

    @Synchronized
    fun record(context: Context, groupId: String, msgId: String, member: String, kind: Kind, at: Long) {
        // __GROUP_RECEIPT_IOS_1043_2026_11_13__ evidence 1: it sent one, it reads them.
        noteReader(context, member)
        val table = load(context)
        val k = key(groupId, msgId)
        val forMsg = table.optJSONObject(k) ?: JSONObject()
        val st = forMsg.optJSONObject(member) ?: JSONObject()

        when (kind) {
            Kind.RECEIVED -> {
                // Never move an existing stamp later: a duplicate from a retry
                // must not make delivery look more recent than it was.
                val cur = if (st.has("d")) st.optLong("d") else null
                if (cur == null || at < cur) st.put("d", at)
            }
            Kind.READ -> {
                val cur = if (st.has("r")) st.optLong("r") else null
                if (cur == null || at < cur) st.put("r", at)
                // Reading implies receiving. The delivery twin can be lost, and
                // "read but never delivered" is nonsense on a screen.
                if (!st.has("d")) st.put("d", at)
            }
        }
        forMsg.put(member, st)
        table.put(k, forMsg)
        trim(table)
        save(context, table)
    }

    private fun trim(table: JSONObject) {
        if (table.length() <= MAX_MESSAGES) return
        val ranked = table.keys().asSequence().map { k ->
            val forMsg = table.optJSONObject(k)
            var newest = 0L
            forMsg?.keys()?.forEach { m ->
                val st = forMsg.optJSONObject(m) ?: return@forEach
                newest = maxOf(newest, st.optLong("d", 0L), st.optLong("r", 0L))
            }
            k to newest
        }.sortedBy { it.second }.toList()
        for ((k, _) in ranked.take(table.length() - MAX_MESSAGES)) table.remove(k)
    }

    fun states(context: Context, groupId: String, msgId: String): Map<String, MemberState> {
        val forMsg = load(context).optJSONObject(key(groupId, msgId)) ?: return emptyMap()
        val out = mutableMapOf<String, MemberState>()
        forMsg.keys().forEach { m ->
            val st = forMsg.optJSONObject(m) ?: return@forEach
            out[m] = MemberState(
                receivedAt = if (st.has("d")) st.optLong("d") else null,
                readAt = if (st.has("r")) st.optLong("r") else null,
            )
        }
        return out
    }

    @Synchronized
    fun forget(context: Context, groupId: String) {
        val table = load(context)
        val prefix = "$groupId|"
        table.keys().asSequence().filter { it.startsWith(prefix) }.toList().forEach { table.remove(it) }
        save(context, table)
    }

    // ---------------------------------------------------------- presentation

    /**
     * Rows for every member except the sender: read first, then delivered,
     * then nothing known.
     *
     * Members with NO receipt are INCLUDED deliberately. "Nothing is known
     * about this person" is the answer the user most needs, and a list that
     * quietly omitted them would read as everyone having received it.
     */
    fun rows(groupMembers: List<String>, sender: String, states: Map<String, MemberState>): List<Row> =
        groupMembers.filter { it != sender }
            .map { Row(it, states[it]?.receivedAt, states[it]?.readAt) }
            .sortedWith(
                compareByDescending<Row> { it.hasRead }
                    .thenByDescending { it.hasReceived }
                    .thenBy { it.memberKey }
            )
}
