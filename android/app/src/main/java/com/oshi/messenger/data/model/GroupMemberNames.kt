package com.oshi.messenger.data.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * __GROUP_MEMBER_NAMES_2026_09_23__ Display names for the members of a group (group info
 * screen). A Desktop member used to show as `abc123...wxyz` even when we knew their name.
 *
 * Order: the alias the USER typed for that contact > the member's own shared nickname
 * (📸PROFILE_UPDATE📸, [PeerNicknameStore]) > the `alias` the group definition carries for that
 * member (GROUP_E2E_V2_SPEC §5.1, the sender's local name for them — [GroupMemberAliasStore]) >
 * null (caller shows the formatted address). Automatic placeholder aliases (`key.take(8)+"..."`)
 * never win: they ARE the address.
 */
object GroupMemberNames {

    fun resolve(
        publicKey: String,
        contactAliases: Map<String, String?>,
        nicknames: Map<String, String>,
        definitionAliases: Map<String, String>,
    ): String? {
        val nk = PeerNickname.normalizeKey(publicKey)
        val alias = contactAliases[publicKey]
            ?: contactAliases.entries.firstOrNull { PeerNickname.normalizeKey(it.key) == nk }?.value
        PeerNickname.userAlias(alias, publicKey)?.let { return it }
        PeerNickname.sanitize(PeerNickname.lookup(nicknames, publicKey))?.let { return it }
        val defAlias = definitionAliases[nk]
        return PeerNickname.sanitize(defAlias)?.takeIf { !PeerNickname.isAutoAlias(it, publicKey) }
    }

    /** `{publicKey, alias?}` entries of a definition's `members` array → normalized key → alias. */
    fun aliasesFromMembersJson(membersJson: String?): Map<String, String> {
        val arr = runCatching { JSONArray(membersJson ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pk = o.optString("publicKey", "").takeIf { it.isNotEmpty() } ?: continue
            val alias = PeerNickname.sanitize(o.optString("alias", "")) ?: continue
            if (PeerNickname.isAutoAlias(alias, pk)) continue
            out[PeerNickname.normalizeKey(pk)] = alias
        }
        return out
    }
}

/**
 * Per-group member aliases learned from incoming definitions. Persisted (SharedPreferences,
 * JSON per group) and observable. Only a FALLBACK: see [GroupMemberNames.resolve].
 */
object GroupMemberAliasStore {
    private const val PREFS = "group_member_aliases"

    private val _aliases = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    /** groupId (lower-case) → normalized member key → alias. */
    val aliases: StateFlow<Map<String, Map<String, String>>> = _aliases.asStateFlow()

    @Volatile private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        val out = HashMap<String, Map<String, String>>()
        for ((gid, v) in app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) {
            val raw = v as? String ?: continue
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            out[gid] = o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotEmpty() }
        }
        _aliases.value = out
    }

    fun forGroup(groupId: String): Map<String, String> = _aliases.value[groupId.lowercase()].orEmpty()

    /** Merge what a definition said (new aliases win; members it does not name are kept). */
    @Synchronized
    fun record(context: Context, groupId: String, incoming: Map<String, String>) {
        if (incoming.isEmpty()) return
        init(context)
        val gid = groupId.lowercase()
        val before = _aliases.value[gid].orEmpty()
        val after = before + incoming
        if (after == before) return
        (appContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(gid, JSONObject(after as Map<*, *>).toString()).apply()
        _aliases.value = _aliases.value + (gid to after)
    }
}
