package com.oshi.messenger.service

import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Recent events" — local, per-device admin log (docs/GROUP_INFO_SETTINGS_SPEC.md §2.8).
 * Nothing here goes on the wire. Built by diffing our copy before/after each applied
 * definition (actor = authenticated sender) and each local change (actor = us).
 *
 * Pure (org.json only). Persistence lives in `GroupManager` (sealed `GroupLocalStore` doc).
 */
object GroupAdminLog {
    const val WINDOW_MS = 48L * 3600 * 1000
    const val MAX_PER_GROUP = 500

    data class Event(
        val at: Long,
        val actor: String,
        val kind: String,
        /** Subject key (member) or new value (name), depending on [kind]. */
        val subject: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("at", at); put("actor", actor); put("kind", kind); subject?.let { put("subject", it) }
        }
        companion object {
            fun fromJson(o: JSONObject): Event? {
                val kind = o.optString("kind").ifEmpty { return null }
                return Event(o.optLong("at"), o.optString("actor"), kind, o.optString("subject").ifEmpty { null })
            }
        }
    }

    // Kinds (spec §2.8)
    const val RENAMED = "renamed"
    const val DESCRIPTION = "description_changed"
    const val PHOTO = "photo_changed"
    const val MEMBER_ADDED = "member_added"
    const val MEMBER_REMOVED = "member_removed"
    const val MEMBER_LEFT = "member_left"
    const val ADMIN_PROMOTED = "admin_promoted"
    const val ADMIN_DEMOTED = "admin_demoted"
    const val VISIBILITY = "visibility_changed"
    const val HANDLE = "handle_changed"
    const val INVITE_RESET = "invite_link_reset"
    const val REACTIONS = "reactions_changed"
    const val WELCOME = "welcome_changed"
    const val POST_POLICY = "post_policy_changed"
    const val ADMIN_DMS = "admin_dms_changed"
    const val COMMENTS = "comments_changed"
    const val JOINED_LINK = "joined_via_link"
    const val DISSOLVED = "dissolved"
    const val TYPE = "type_changed"

    private fun canon(k: String) = GroupUpdateAuthorizer.canonicalIdentity(k)

    fun diff(
        before: Group?, after: Group,
        settingsBefore: GroupSettings?, settingsAfter: GroupSettings?,
        actor: String, at: Long,
    ): List<Event> {
        val out = mutableListOf<Event>()
        if (before != null) {
            if (before.name != after.name) out += Event(at, actor, RENAMED, after.name)
            if (before.description != after.description) out += Event(at, actor, DESCRIPTION)
            if (!(before.groupImage ?: ByteArray(0)).contentEquals(after.groupImage ?: ByteArray(0))) out += Event(at, actor, PHOTO)
            if (before.effectiveGroupType != after.effectiveGroupType) out += Event(at, actor, TYPE, after.effectiveGroupType.value)
            val bm = before.memberPublicKeys.map(::canon).toSet()
            val am = after.memberPublicKeys.map(::canon).toSet()
            after.memberPublicKeys.filter { canon(it) !in bm }.forEach { out += Event(at, actor, MEMBER_ADDED, it) }
            before.memberPublicKeys.filter { canon(it) !in am }.forEach {
                out += Event(at, actor, if (canon(it) == canon(actor)) MEMBER_LEFT else MEMBER_REMOVED, it)
            }
            val ba = before.adminPublicKeys.map(::canon).toSet()
            val aa = after.adminPublicKeys.map(::canon).toSet()
            after.adminPublicKeys.filter { canon(it) !in ba && canon(it) in am }.forEach { out += Event(at, actor, ADMIN_PROMOTED, it) }
            before.adminPublicKeys.filter { canon(it) !in aa && canon(it) in am }.forEach { out += Event(at, actor, ADMIN_DEMOTED, it) }
        }
        val sb = settingsBefore ?: GroupSettings.DEFAULT
        val sa = settingsAfter ?: sb
        if (sb.visibility != sa.visibility) out += Event(at, actor, VISIBILITY, sa.visibility.wire)
        else if (sb.handle != sa.handle && sa.handle != null) out += Event(at, actor, HANDLE, sa.handle)
        if (sb.inviteToken != sa.inviteToken && sa.inviteToken != null) out += Event(at, actor, INVITE_RESET)
        if (sb.reactions != sa.reactions || sb.allowedReactions != sa.allowedReactions) out += Event(at, actor, REACTIONS)
        if (sb.welcome != sa.welcome) out += Event(at, actor, WELCOME)
        if (sb.postPolicy != sa.postPolicy) out += Event(at, actor, POST_POLICY, sa.postPolicy.wire)
        if (sb.adminDMs != sa.adminDMs) out += Event(at, actor, ADMIN_DMS)
        if (sb.comments != sa.comments) out += Event(at, actor, COMMENTS, sa.comments.toString())
        if (!sb.dissolved && sa.dissolved) out += Event(at, actor, DISSOLVED)
        return out
    }

    /** Append, then prune to the 48 h window and the per-group cap. */
    fun append(existing: List<Event>, added: List<Event>, now: Long): List<Event> =
        prune(existing + added, now)

    fun prune(events: List<Event>, now: Long): List<Event> =
        events.filter { now - it.at in 0..WINDOW_MS || it.at > now }.sortedBy { it.at }.takeLast(MAX_PER_GROUP)

    fun encode(byGroup: Map<String, List<Event>>): String = JSONObject().apply {
        byGroup.forEach { (gid, evs) -> if (evs.isNotEmpty()) put(gid, JSONArray(evs.map { it.toJson() })) }
    }.toString()

    fun decode(raw: String?): MutableMap<String, List<Event>> {
        val out = mutableMapOf<String, List<Event>>()
        if (raw.isNullOrEmpty()) return out
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return out
        for (k in o.keys()) {
            val arr = o.optJSONArray(k) ?: continue
            out[k] = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(Event::fromJson) }
        }
        return out
    }
}
