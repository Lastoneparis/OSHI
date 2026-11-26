package com.oshi.messenger.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Telegram-style group settings — `settings` object of the group definition.
 * docs/GROUP_INFO_SETTINGS_SPEC.md §2.1 (iOS and Desktop parse the same JSON).
 *
 * Deliberately NOT a column of the Room `Group` entity: adding a field there needs a Room
 * migration, and a missing one closed the app on launch before. Held by `GroupManager` in a
 * sealed side document keyed by group id.
 *
 * Pure: org.json only, JVM-unit-testable.
 */
data class GroupSettings(
    val visibility: Visibility = Visibility.PRIVATE,
    val handle: String? = null,
    val showPreview: Boolean = false,
    val inviteToken: String? = null,
    val reactions: Reactions = Reactions.ALL,
    val allowedReactions: List<String> = emptyList(),
    val welcome: String = "",
    val postPolicy: PostPolicy = PostPolicy.EVERYONE,
    val adminDMs: Boolean = true,
    val dissolved: Boolean = false,
    /** §2.12 — members may comment under admin posts; meaningful only with [PostPolicy.ADMINS]. */
    val comments: Boolean = false,
    /**
     * __CHANNEL_WEB_2026_11_26__ Public web preview (ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md §9.1):
     * new admin posts are also published in clear at `oshi-messenger.com/c/<handle>`. Only
     * meaningful for a public channel ([webEligible]); travels here so co-admins see it.
     */
    val webPreview: Boolean = false,
    /** Spec §3.1 `searchIndexing`: web pages may be indexed by search engines. */
    val webIndexing: Boolean = false,
) {
    enum class Visibility(val wire: String) { PRIVATE("private"), PUBLIC("public") }
    enum class Reactions(val wire: String) { ALL("all"), SOME("some"), NONE("none") }
    enum class PostPolicy(val wire: String) { EVERYONE("everyone"), ADMINS("admins") }

    val isPublic: Boolean get() = visibility == Visibility.PUBLIC && handle != null

    /** §2.3 — `remove` is always allowed; this decides an `add`. */
    fun isReactionAllowed(emoji: String): Boolean = when (reactions) {
        Reactions.ALL -> true
        Reactions.NONE -> false
        Reactions.SOME -> allowedReactions.contains(emoji)
    }

    /**
     * __INTEROP_2026_11_21__ §2.3 sender side: what the reaction picker may offer. Null = any
     * (the full picker), empty = none (no picker), else exactly the allowed list.
     */
    val pickerReactions: List<String>? get() = when (reactions) {
        Reactions.ALL -> null
        Reactions.NONE -> emptyList()
        Reactions.SOME -> allowedReactions
    }

    /** §2.4 */
    fun mayPost(senderIsAdmin: Boolean): Boolean = postPolicy == PostPolicy.EVERYONE || senderIsAdmin

    /** §2.12.1 — comments bars and member comments exist only in channel mode with comments on. */
    val commentsActive: Boolean get() = postPolicy == PostPolicy.ADMINS && comments && !dissolved

    /** Spec §1: only a channel (admins post) that owns a public handle, never a dissolved group. */
    val webEligible: Boolean get() = isPublic && postPolicy == PostPolicy.ADMINS && !dissolved

    /** The preview is on AND still allowed for this group. */
    val webActive: Boolean get() = webPreview && webEligible

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("visibility", visibility.wire)
        if (visibility == Visibility.PUBLIC && handle != null) put("handle", handle)
        put("showPreview", showPreview)
        inviteToken?.let { put("inviteToken", it) }
        put("reactions", reactions.wire)
        if (allowedReactions.isNotEmpty()) put("allowedReactions", JSONArray(allowedReactions))
        if (welcome.isNotEmpty()) put("welcome", welcome)
        put("postPolicy", postPolicy.wire)
        put("adminDMs", adminDMs)
        put("dissolved", dissolved)
        put("comments", comments)
        // Every subkey emitted, as iOS does (GROUP_INFO_SETTINGS_SPEC §2.13).
        put("webPreview", webPreview)
        put("webIndexing", webPreview && webIndexing)
    }

    companion object {
        const val VERSION = 1
        const val MAX_WELCOME = 1000
        const val MAX_DESCRIPTION = 500
        const val MAX_ALLOWED_REACTIONS = 12
        const val MAX_REACTION_LEN = 16
        val DEFAULT = GroupSettings()

        /** Offered by the editor for "Some reactions" (§2.3). */
        val DEFAULT_REACTION_CHOICES = listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "🙏", "👏", "🎉", "👎")

        private val HANDLE_RE = Regex("^[a-z0-9_]{5,32}$")

        /** Lower-cased valid handle, or null. */
        fun normalizeHandle(raw: String?): String? {
            val h = raw?.trim()?.lowercase() ?: return null
            return h.takeIf { HANDLE_RE.matches(it) }
        }

        private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{16,64}$")

        fun newInviteToken(random: SecureRandom = SecureRandom()): String {
            val b = ByteArray(16).also { random.nextBytes(it) }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        /**
         * Sanitising parse (§2.1): missing subkey ⇒ default, unknown enum ⇒ default, a value of
         * the wrong JSON type ⇒ default, strings truncated, invalid handle ⇒ private.
         */
        fun parse(o: JSONObject): GroupSettings {
            val vis = when (o.opt("visibility")) { "public" -> Visibility.PUBLIC; else -> Visibility.PRIVATE }
            val handle = (o.opt("handle") as? String)?.let { normalizeHandle(it) }
            val reactions = when (o.opt("reactions")) {
                "some" -> Reactions.SOME; "none" -> Reactions.NONE; else -> Reactions.ALL
            }
            val allowed = (o.opt("allowedReactions") as? JSONArray)?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> (arr.opt(i) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_REACTION_LEN } }
            }.orEmpty().distinct().take(MAX_ALLOWED_REACTIONS)
            return GroupSettings(
                visibility = if (vis == Visibility.PUBLIC && handle != null) Visibility.PUBLIC else Visibility.PRIVATE,
                handle = handle.takeIf { vis == Visibility.PUBLIC },
                showPreview = o.opt("showPreview") as? Boolean ?: false,
                inviteToken = (o.opt("inviteToken") as? String)?.takeIf { TOKEN_RE.matches(it) },
                // "some" with nothing allowed behaves as "none" (§2.1).
                reactions = if (reactions == Reactions.SOME && allowed.isEmpty()) Reactions.NONE else reactions,
                allowedReactions = if (reactions == Reactions.SOME) allowed else emptyList(),
                welcome = truncate((o.opt("welcome") as? String).orEmpty(), MAX_WELCOME),
                postPolicy = if (o.opt("postPolicy") == "admins") PostPolicy.ADMINS else PostPolicy.EVERYONE,
                adminDMs = o.opt("adminDMs") as? Boolean ?: true,
                dissolved = o.opt("dissolved") as? Boolean ?: false,
                comments = o.opt("comments") as? Boolean ?: false,
                webPreview = o.opt("webPreview") as? Boolean ?: false,
                webIndexing = (o.opt("webPreview") as? Boolean ?: false) && (o.opt("webIndexing") as? Boolean ?: false),
            )
        }

        fun parseOrNull(o: JSONObject?): GroupSettings? = o?.let { runCatching { parse(it) }.getOrNull() }

        /** Truncate by UTF-16 units without splitting a surrogate pair. */
        fun truncate(s: String, max: Int): String {
            if (s.length <= max) return s
            var end = max
            if (Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end)
        }

        /** §4.1 `groupRef` = hex sha256("oshi-group-handle-v1:" + UPPER(gid)). */
        fun groupRef(groupId: String): String {
            val d = MessageDigest.getInstance("SHA-256")
                .digest(("oshi-group-handle-v1:" + groupId.uppercase()).toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }
        }

        fun publicLink(handle: String): String = "https://oshi-messenger.com/g/$handle"
    }
}

/**
 * Receive-side merge of an incoming `settings` object (spec §2.1 receiver rule).
 *
 * @param senderIsAdmin authorizer verdict for the AUTHENTICATED sender against OUR copy
 *        (`GroupUpdateAuthorizer.mayChangeGroupType`).
 * @return the settings to store (may be [current] unchanged).
 */
object GroupSettingsMerge {
    fun merge(current: GroupSettings?, incoming: GroupSettings?, senderIsAdmin: Boolean): GroupSettings? {
        if (incoming == null) return current                 // absent ⇒ keep (rule 0.3)
        if (!senderIsAdmin) return current                   // not an admin ⇒ ignore the whole object
        val wasDissolved = current?.dissolved == true
        return if (wasDissolved && !incoming.dissolved) incoming.copy(dissolved = true) else incoming
    }
}
