package com.oshi.messenger.network.v2.devsync

import com.oshi.messenger.network.v2.devsync.SyncJson.optIsoOrNull
import com.oshi.messenger.network.v2.devsync.SyncJson.optStr
import org.json.JSONArray
import org.json.JSONObject

/** HELLO (§6): carried as the payload of Noise messages 2 (responder) and 3 (initiator). */
data class Hello(
    val deviceId: String,
    val name: String,
    val platform: String,
    val app: String = "",
    val caps: List<String> = DEFAULT_CAPS,
    val historySource: Boolean = false,
    val metered: Boolean = false,
    val lowPower: Boolean = false,
    /** `always` / `unmetered` / `never`: what this device lets the PEER send it. */
    val media: String = MEDIA_ALWAYS,
    val proto: Int = 1,
    /**
     * __DEVSYNC_DEVICE_AUTH_2026_09_23__ The sender's public Ed25519 device signing key `dsk` (standard
     * padded base64, 32 bytes): the ONE per-install signing key (per-device mailbox registration and
     * approvals, devsync relay upgrade). Carried so the approving device can sign the mailbox approval
     * for the new device whichever side taps Allow first. Optional; a malformed value decodes as null.
     */
    val dsk: String? = null,
    /**
     * Additive fields another layer carries in HELLO (e.g. the per-device mailbox's public `dsk`,
     * design §15.3). Never overrides a standard field; peers ignore what they do not know.
     */
    val extra: JSONObject? = null,
    /** The HELLO as received (decode only), so a hook can read fields this class does not model. */
    val raw: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("proto", proto).put("deviceId", deviceId).put("name", name).put("platform", platform)
        .put("app", app).put("caps", JSONArray(caps)).put("historySource", historySource)
        .put("net", JSONObject().put("metered", metered).put("lowPower", lowPower))
        .put("media", media)
        .also { o -> if (dsk != null) o.put("dsk", dsk) }
        .also { o ->
            extra?.let { x ->
                val it = x.keys()
                while (it.hasNext()) { val k = it.next(); if (!o.has(k)) o.put(k, x.get(k)) }
            }
        }

    fun encode(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val MEDIA_ALWAYS = "always"
        const val MEDIA_UNMETERED = "unmetered"
        const val MEDIA_NEVER = "never"
        val DEFAULT_CAPS = listOf("text", "media", "contacts", "profile", "read", "groups", "devices")

        /** Strict standard padded base64 of exactly 32 bytes. */
        fun isDsk(s: String): Boolean = s.length == 44 && runCatching {
            val b = java.util.Base64.getDecoder().decode(s)
            b.size == 32 && java.util.Base64.getEncoder().encodeToString(b) == s
        }.getOrDefault(false)

        fun decode(b: ByteArray): Hello? = runCatching {
            val o = JSONObject(String(b, Charsets.UTF_8))
            val caps = o.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val net = o.optJSONObject("net")
            Hello(
                deviceId = o.getString("deviceId").lowercase(java.util.Locale.ROOT),
                name = o.optString("name", "").take(64),
                platform = o.optString("platform", "unknown").take(16),
                app = o.optString("app", "").take(32),
                caps = caps,
                historySource = o.optBoolean("historySource", false),
                metered = net?.optBoolean("metered", false) ?: false,
                lowPower = net?.optBoolean("lowPower", false) ?: false,
                media = o.optString("media", MEDIA_ALWAYS),
                proto = o.optInt("proto", 1),
                dsk = o.optString("dsk", "").takeIf(::isDsk),
                raw = o,
            )
        }.getOrNull()
    }
}

/** One entry of the local linked-device list (§3) and of a DEVICES record (dk = hex of DK_pub). */
data class LinkedDevice(
    val deviceId: String,
    val dk: String,
    val name: String,
    val platform: String,
    val approvedAtMs: Long,
    val approvedBy: String? = null,
    val lastSyncAtMs: Long? = null,
) {
    fun toWire(): JSONObject = JSONObject().put("deviceId", deviceId).put("dk", dk).put("name", name)
        .put("platform", platform).put("approvedAt", SyncJson.iso(approvedAtMs))

    fun toStorage(): JSONObject = toWire().apply {
        approvedBy?.let { put("approvedBy", it) }
        lastSyncAtMs?.let { put("lastSyncAt", SyncJson.iso(it)) }
    }

    /** The dk must hash to the id; an entry that does not is refused. */
    fun isConsistent(): Boolean = runCatching {
        DevSyncKeys.isDeviceIdHex(deviceId) && DevSyncKeys.deviceIdHex(DevSyncCrypto.unhex(dk)) == deviceId
    }.getOrDefault(false)

    companion object {
        fun fromJson(o: JSONObject): LinkedDevice? = runCatching {
            LinkedDevice(
                deviceId = o.getString("deviceId").lowercase(java.util.Locale.ROOT),
                dk = o.getString("dk").lowercase(java.util.Locale.ROOT),
                name = o.optString("name", "").take(64),
                platform = o.optString("platform", "unknown").take(16),
                approvedAtMs = o.optIsoOrNull("approvedAt") ?: 0L,
                approvedBy = o.optStr("approvedBy"),
                lastSyncAtMs = o.optIsoOrNull("lastSyncAt"),
            )
        }.getOrNull()
    }
}

data class DevicesRecord(val linked: List<LinkedDevice>, val revoked: List<String>) {
    fun toJson(): JSONObject = JSONObject()
        .put("linked", JSONArray().apply { linked.forEach { put(it.toWire()) } })
        .put("revoked", JSONArray(revoked))

    companion object {
        fun fromJson(o: JSONObject): DevicesRecord {
            val linked = ArrayList<LinkedDevice>()
            o.optJSONArray("linked")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(LinkedDevice::fromJson)?.let(linked::add) }
            val revoked = ArrayList<String>()
            o.optJSONArray("revoked")?.let { a -> for (i in 0 until a.length()) a.optString(i, "").lowercase(java.util.Locale.ROOT).takeIf(DevSyncKeys::isDeviceIdHex)?.let(revoked::add) }
            return DevicesRecord(linked, revoked)
        }
    }
}

/** CONTACTS entry (§6): per-field timestamps drive the merge (§8.5). */
data class SyncContact(
    val publicKey: String,
    val alias: String? = null,
    val aliasAtMs: Long? = null,
    val blocked: Boolean? = null,
    val blockedAtMs: Long? = null,
    val verified: Boolean? = null,
    val verifiedAtMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("publicKey", publicKey).apply {
        alias?.let { put("alias", it) }
        aliasAtMs?.let { put("aliasAt", SyncJson.iso(it)) }
        blocked?.let { put("blocked", it) }
        blockedAtMs?.let { put("blockedAt", SyncJson.iso(it)) }
        verified?.let { put("verified", it) }
        verifiedAtMs?.let { put("verifiedAt", SyncJson.iso(it)) }
    }

    companion object {
        fun fromJson(o: JSONObject): SyncContact? = runCatching {
            SyncContact(
                publicKey = SyncJson.canonicalKey(o.getString("publicKey")),
                alias = o.optStr("alias"),
                aliasAtMs = o.optIsoOrNull("aliasAt"),
                blocked = if (o.has("blocked") && !o.isNull("blocked")) o.optBoolean("blocked") else null,
                blockedAtMs = o.optIsoOrNull("blockedAt"),
                verified = if (o.has("verified") && !o.isNull("verified")) o.optBoolean("verified") else null,
                verifiedAtMs = o.optIsoOrNull("verifiedAt"),
            )
        }.getOrNull()
    }
}

/** A group secret (design §6 `GROUPS.keys`): e.g. Android `Group.encryptionKey` = `legacy-aes` v0. */
data class SyncGroupKey(val kind: String, val version: Long, val key: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("version", version).put("key", key)
    fun canonical(): Map<String, Any?> = linkedMapOf("key" to key, "kind" to kind, "version" to version)

    companion object {
        const val KIND_LEGACY_AES = "legacy-aes"
        const val KIND_V2_EPOCH = "v2-epoch"
    }
}

/** A group picture by reference; the bytes travel through MEDIA_GET like any media. */
data class SyncAvatar(val sha256: String, val size: Long, val mime: String) {
    fun toJson(): JSONObject = JSONObject().put("sha256", sha256).put("size", size).put("mime", mime)
    fun canonical(): Map<String, Any?> = linkedMapOf("mime" to mime, "sha256" to sha256, "size" to size)
}

/**
 * GROUPS entry (0x1C, design §6 / §8.4, owner decision D11: group members AND group keys sync
 * between the account's own devices):
 *
 *     {groupId, name, nameAt, avatar:{sha256,size,mime}|null, avatarAt, members:[userKey],
 *      admins:[userKey], epoch, epochAt, left, leftAt, joinedAt, keys:[{kind, version, key}], updatedAt}
 *
 * plus optional descriptive extras that travel with the name (`description`, `type`, `creator`,
 * `avatarEmoji`, `pinnedMessageId`, `pinnedBy`). A platform with no epoch writes 0.
 */
data class SyncGroup(
    val groupId: String,
    val name: String,
    val nameAtMs: Long? = null,
    val avatar: SyncAvatar? = null,
    val avatarAtMs: Long? = null,
    val members: List<String> = emptyList(),
    val admins: List<String> = emptyList(),
    val epoch: Long = 0,
    val epochAtMs: Long? = null,
    val left: Boolean = false,
    val leftAtMs: Long? = null,
    /** __DEVSYNC_REJOIN_2026_09_23__ last deliberate join / rejoin (§8.4 rejoin rule); null = unknown. */
    val joinedAtMs: Long? = null,
    val keys: List<SyncGroupKey> = emptyList(),
    val updatedAtMs: Long,
    val description: String? = null,
    val type: String? = null,
    val creator: String? = null,
    val avatarEmoji: String? = null,
    val pinnedMessageId: String? = null,
    val pinnedBy: String? = null,
    /**
     * __GROUP_MUTE_SYNC_2026_09_24__ the account's per-group mute (optional, additive: a peer that
     * predates it sends neither field and ignores them). Later [mutedAtMs] wins ([SyncMerge.group]).
     */
    val muted: Boolean? = null,
    val mutedAtMs: Long? = null,
) {
    val conversationId: String get() = SyncJson.groupConversationId(groupId)

    /**
     * Sorted unique members/admins, keys sorted by (kind, version, key), and a leave older than
     * [joinedAtMs] dropped (§8.4 rejoin rule, [SyncMerge.leftState]): the form merges compare.
     */
    fun canonical(): SyncGroup = SyncMerge.leftState(listOf(this), joinedAtMs).let { (l, lAt) -> copy(left = l, leftAtMs = lAt) }.copy(
        members = members.distinct().sortedWith(CanonicalJson.UTF8_ORDER),
        admins = admins.distinct().sortedWith(CanonicalJson.UTF8_ORDER),
        keys = keys.sortedWith(compareBy<SyncGroupKey, String>(CanonicalJson.UTF8_ORDER) { it.kind }
            .thenBy { it.version }.thenComparator { a, b -> CanonicalJson.UTF8_ORDER.compare(a.key, b.key) }),
    )

    internal fun descriptive(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("name", name)
        description?.let { put("description", it) }
        type?.let { put("type", it) }
        creator?.let { put("creator", it) }
        avatarEmoji?.let { put("avatarEmoji", it) }
        pinnedMessageId?.let { put("pinnedMessageId", it) }
        pinnedBy?.let { put("pinnedBy", it) }
    }

    /** Canonical JSON of the whole group (every field present; the vectors' `canonical`). */
    fun canonicalJson(): String {
        val c = canonical()
        val m = LinkedHashMap<String, Any?>()
        m["groupId"] = c.groupId
        m["members"] = c.members
        m["admins"] = c.admins
        m["epoch"] = c.epoch
        c.epochAtMs?.let { m["epochAt"] = SyncJson.iso(it) }
        m.putAll(c.descriptive())
        c.nameAtMs?.let { m["nameAt"] = SyncJson.iso(it) }
        m["avatar"] = c.avatar?.canonical()
        c.avatarAtMs?.let { m["avatarAt"] = SyncJson.iso(it) }
        m["keys"] = c.keys.map { it.canonical() }
        m["left"] = c.left
        c.leftAtMs?.let { m["leftAt"] = SyncJson.iso(it) }
        c.joinedAtMs?.let { m["joinedAt"] = SyncJson.iso(it) }
        c.muted?.let { m["muted"] = it }
        c.mutedAtMs?.let { m["mutedAt"] = SyncJson.iso(it) }
        m["updatedAt"] = SyncJson.iso(c.updatedAtMs)
        return CanonicalJson.write(m)
    }

    fun toJson(): JSONObject = JSONObject().put("groupId", groupId).put("name", name).apply {
        nameAtMs?.let { put("nameAt", SyncJson.iso(it)) }
        put("avatar", avatar?.toJson() ?: JSONObject.NULL)
        avatarAtMs?.let { put("avatarAt", SyncJson.iso(it)) }
        put("members", JSONArray(members))
        put("admins", JSONArray(admins))
        put("epoch", epoch)
        epochAtMs?.let { put("epochAt", SyncJson.iso(it)) }
        put("left", left)
        leftAtMs?.let { put("leftAt", SyncJson.iso(it)) }
        joinedAtMs?.let { put("joinedAt", SyncJson.iso(it)) }
        put("keys", JSONArray().apply { keys.forEach { put(it.toJson()) } })
        put("updatedAt", SyncJson.iso(updatedAtMs))
        description?.let { put("description", it) }
        type?.let { put("type", it) }
        creator?.let { put("creator", it) }
        avatarEmoji?.let { put("avatarEmoji", it) }
        pinnedMessageId?.let { put("pinnedMessageId", it) }
        pinnedBy?.let { put("pinnedBy", it) }
        muted?.let { put("muted", it) }
        mutedAtMs?.let { put("mutedAt", SyncJson.iso(it)) }
    }

    companion object {
        private fun keysOf(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotEmpty() }?.let(SyncJson::canonicalKey) }

        fun fromJson(o: JSONObject): SyncGroup? = runCatching {
            val keys = ArrayList<SyncGroupKey>()
            o.optJSONArray("keys")?.let { a ->
                for (i in 0 until a.length()) {
                    val k = a.optJSONObject(i) ?: continue
                    val key = k.optStr("key") ?: continue
                    keys += SyncGroupKey(k.optStr("kind") ?: SyncGroupKey.KIND_LEGACY_AES, k.optLong("version", 0), key)
                }
            }
            val av = o.optJSONObject("avatar")?.let { a ->
                val sha = a.optStr("sha256")?.lowercase(java.util.Locale.ROOT) ?: return@let null
                SyncAvatar(sha, a.optLong("size", -1), a.optStr("mime") ?: "image/jpeg")
            }
            SyncGroup(
                groupId = SyncJson.canonicalGroupId(o.getString("groupId")),
                name = o.optString("name", ""),
                nameAtMs = o.optIsoOrNull("nameAt"),
                avatar = av,
                avatarAtMs = o.optIsoOrNull("avatarAt"),
                members = keysOf(o.optJSONArray("members")),
                admins = keysOf(o.optJSONArray("admins")),
                epoch = o.optLong("epoch", 0),
                epochAtMs = o.optIsoOrNull("epochAt"),
                left = o.optBoolean("left", false),
                leftAtMs = o.optIsoOrNull("leftAt"),
                joinedAtMs = o.optIsoOrNull("joinedAt"),
                keys = keys,
                updatedAtMs = o.optIsoOrNull("updatedAt") ?: 0L,
                description = o.optStr("description"),
                type = o.optStr("type"),
                creator = o.optStr("creator")?.let(SyncJson::canonicalKey),
                avatarEmoji = o.optStr("avatarEmoji"),
                pinnedMessageId = o.optStr("pinnedMessageId"),
                pinnedBy = o.optStr("pinnedBy"),
                muted = if (o.has("muted") && !o.isNull("muted")) o.optBoolean("muted") else null,
                mutedAtMs = o.optIsoOrNull("mutedAt"),
            )
        }.getOrNull()
    }
}

/** PROFILE (§6): last writer wins on `updatedAt`. `extra` carries fields this client does not model. */
data class SyncProfile(val name: String?, val updatedAtMs: Long, val extra: JSONObject = JSONObject()) {
    fun toJson(): JSONObject = JSONObject(extra.toString()).apply {
        name?.let { put("name", it) }
        put("updatedAt", SyncJson.iso(updatedAtMs))
    }

    companion object {
        fun fromJson(o: JSONObject): SyncProfile? = runCatching {
            val extra = JSONObject(o.toString()).apply { remove("name"); remove("updatedAt") }
            SyncProfile(o.optStr("name"), SyncJson.parseIso(o.getString("updatedAt")), extra)
        }.getOrNull()
    }
}

/** READ_STATE (§6): `readUpTo` = max of both; never emits receipts. */
data class SyncReadState(val conv: String, val readUpToMs: Long, val atMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("conv", conv).put("readUpTo", SyncJson.iso(readUpToMs)).put("at", SyncJson.iso(atMs))

    companion object {
        fun fromJson(o: JSONObject): SyncReadState? = runCatching {
            SyncReadState(o.getString("conv"), SyncJson.parseIso(o.getString("readUpTo")), SyncJson.parseIso(o.getString("at")))
        }.getOrNull()
    }
}
