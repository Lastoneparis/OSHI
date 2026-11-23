package com.oshi.messenger.network.v2.devsync

/**
 * Merge rules of docs/OSHI_DEVICE_SYNC_DIRECT.md §8. Every merge is commutative and idempotent, so
 * the order in which sessions happen does not matter. Golden vectors: docs/fixtures/devsync/merge.json.
 */
object SyncMerge {

    private val STATUS_RANK = mapOf("failed" to 0, "pending" to 1, "sent" to 2, "delivered" to 3, "read" to 4)

    /** Highest of pending < sent < delivered < read; `failed` only if neither side has better. */
    fun status(a: String, b: String): String =
        if ((STATUS_RANK[a] ?: 0) >= (STATUS_RANK[b] ?: 0)) a else b

    private fun editedGreaterOrEqual(a: SyncEdited, b: SyncEdited): Boolean {
        val ha = a.atMs != null
        val hb = b.atMs != null
        if (ha != hb) return ha
        if (ha && a.atMs != b.atMs) return a.atMs!! > b.atMs!!
        val ca = CanonicalJson.write(editedObject(a))
        val cb = CanonicalJson.write(editedObject(b))
        return CanonicalJson.UTF8_ORDER.compare(ca, cb) >= 0
    }

    private fun editedObject(e: SyncEdited): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("text", e.text)
        e.atMs?.let { put("at", SyncJson.iso(it)) }
    }

    fun edited(a: SyncEdited?, b: SyncEdited?): SyncEdited? = when {
        a == null -> b
        b == null -> a
        editedGreaterOrEqual(a, b) -> a
        else -> b
    }

    fun reactions(a: Map<String, List<String>>, b: Map<String, List<String>>): Map<String, List<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for (src in listOf(a, b)) for ((emoji, keys) in src) out.getOrPut(emoji) { HashSet() }.addAll(keys)
        val sorted = LinkedHashMap<String, List<String>>()
        for (emoji in out.keys.sortedWith(CanonicalJson.UTF8_ORDER)) {
            val ks = out.getValue(emoji).sortedWith(CanonicalJson.UTF8_ORDER)
            if (ks.isNotEmpty()) sorted[emoji] = ks
        }
        return sorted
    }

    /** Merge a remote copy into the local one. Immutable fields (text, timestamp, sender…) stay local. */
    fun message(local: SyncMessage, remote: SyncMessage): SyncMessage {
        val status = status(local.status, remote.status)
        val read = local.read || remote.read
        if (local.deleted || remote.deleted) {
            return local.copy(status = status, read = read, deleted = true, text = null, edited = null, reactions = emptyMap())
        }
        return local.copy(
            status = status,
            read = read,
            edited = edited(local.edited, remote.edited),
            reactions = reactions(local.reactions, remote.reactions),
        )
    }

    private fun absent(v: Any?): Boolean = v == null || v == "" || v == false

    /**
     * One contact field: later timestamp wins (tie: local); without timestamps, gap-fill only.
     * __BLOCK_SYNC_LWW_2026_09_24__ a local side with neither value nor timestamp also adopts a
     * timestamped "absent" remote value (an unblock = `blocked:false` + `blockedAt`): keeping its
     * timestamp is what stops an OLDER block from a third device re-blocking through the gap-fill.
     */
    fun <T> lwwField(lv: T?, lat: Long?, rv: T?, rat: Long?): Pair<T?, Long?> {
        if (lat != null && rat != null) return if (rat > lat) rv to rat else lv to lat
        if (lat == null && absent(lv) && (!absent(rv) || rat != null)) return rv to rat
        return lv to lat
    }

    fun contact(local: SyncContact, remote: SyncContact): SyncContact {
        val (alias, aliasAt) = lwwField(local.alias, local.aliasAtMs, remote.alias, remote.aliasAtMs)
        val (blocked, blockedAt) = lwwField(local.blocked, local.blockedAtMs, remote.blocked, remote.blockedAtMs)
        val (verified, verifiedAt) = lwwField(local.verified, local.verifiedAtMs, remote.verified, remote.verifiedAtMs)
        return SyncContact(local.publicKey, alias, aliasAt, blocked, blockedAt, verified, verifiedAt)
    }

    fun profile(local: SyncProfile?, remote: SyncProfile?): SyncProfile? = when {
        local == null -> remote
        remote == null -> local
        remote.updatedAtMs > local.updatedAtMs -> remote
        else -> local
    }

    fun readState(local: SyncReadState, remote: SyncReadState): SyncReadState =
        SyncReadState(local.conv, maxOf(local.readUpToMs, remote.readUpToMs), maxOf(local.atMs, remote.atMs))

    /** Later timestamp wins; a timestamped value beats an untimestamped one; ties by canonical-JSON bytes. */
    private fun <T> lww(a: T, aAt: Long?, aCanon: String, b: T, bAt: Long?, bCanon: String): Pair<T, Long?> {
        val ha = aAt != null
        val hb = bAt != null
        val aWins = when {
            ha != hb -> ha
            ha && aAt != bAt -> aAt!! > bAt!!
            else -> CanonicalJson.UTF8_ORDER.compare(aCanon, bCanon) >= 0
        }
        return if (aWins) a to aAt else b to bAt
    }

    /**
     * Design §8.4 (D11). members/admins: higher epoch as a whole, union at equal epoch (absent = 0);
     * name + descriptive extras: later nameAt; avatar: later avatarAt; keys: union by (kind, version),
     * never dropped; joinedAt: max; left: a leave counts only if not older than joinedAt, leftAt =
     * the latest leave that counts ([leftState], __DEVSYNC_REJOIN_2026_09_23__); updatedAt: max.
     * Commutative, idempotent and associative (every part is a max / union).
     */
    fun group(local: SyncGroup?, remote: SyncGroup): SyncGroup {
        if (local == null) return remote.canonical()
        val a = local.canonical()
        val b = remote.canonical()
        var out = a
        if (a.epoch != b.epoch) {
            val w = if (a.epoch > b.epoch) a else b
            out = out.copy(members = w.members, admins = w.admins, epoch = w.epoch, epochAtMs = w.epochAtMs)
        } else {
            out = out.copy(
                members = (a.members + b.members).distinct().sortedWith(CanonicalJson.UTF8_ORDER),
                admins = (a.admins + b.admins).distinct().sortedWith(CanonicalJson.UTF8_ORDER),
                epochAtMs = listOfNotNull(a.epochAtMs, b.epochAtMs).maxOrNull(),
            )
        }
        val (named, nameAt) = lww(a, a.nameAtMs, CanonicalJson.write(a.descriptive()), b, b.nameAtMs, CanonicalJson.write(b.descriptive()))
        out = out.copy(
            name = named.name, nameAtMs = nameAt, description = named.description, type = named.type,
            creator = named.creator, avatarEmoji = named.avatarEmoji, pinnedMessageId = named.pinnedMessageId,
            pinnedBy = named.pinnedBy,
        )
        val (av, avAt) = lww(a.avatar, a.avatarAtMs, CanonicalJson.write(a.avatar?.canonical()), b.avatar, b.avatarAtMs, CanonicalJson.write(b.avatar?.canonical()))
        out = out.copy(avatar = av, avatarAtMs = avAt)
        val byKind = LinkedHashMap<Pair<String, Long>, SyncGroupKey>()
        for (k in a.keys + b.keys) {
            val kv = k.kind to k.version
            val prev = byKind[kv]
            if (prev == null || CanonicalJson.UTF8_ORDER.compare(k.key, prev.key) < 0) byKind[kv] = k
        }
        val keys = byKind.values.sortedWith(compareBy<SyncGroupKey, String>(CanonicalJson.UTF8_ORDER) { it.kind }.thenBy { it.version })
        val joinedAt = listOfNotNull(a.joinedAtMs, b.joinedAtMs).maxOrNull()
        val (left, leftAt) = leftState(listOf(a, b), joinedAt)
        // __GROUP_MUTE_SYNC_2026_09_24__ optional per-group mute: later mutedAt wins, timestamped
        // beats untimestamped, ties by canonical-JSON bytes (true > null > false).
        val (muted, mutedAt) = lww(a.muted, a.mutedAtMs, CanonicalJson.write(a.muted), b.muted, b.mutedAtMs, CanonicalJson.write(b.muted))
        return out.copy(keys = keys, left = left, leftAtMs = leftAt, joinedAtMs = joinedAt, muted = muted, mutedAtMs = mutedAt,
            updatedAtMs = maxOf(a.updatedAtMs, b.updatedAtMs))
    }

    /**
     * __DEVSYNC_REJOIN_2026_09_23__ §8.4 rejoin rule: a leave older than the last join is history, so
     * a rejoin on one device is not undone by another device still holding `left`. A timestamped
     * leave counts when [joinedAt] is null or `leftAt >= joinedAt` (a join in the same millisecond
     * does not beat it); an untimestamped legacy leave counts only while no join is known. Returns
     * (left, the LATEST leave that counts). "Earliest" would not be associative: leave@15 on A,
     * rejoin@20 on C, leave@27 on B would end joined if A met B first.
     */
    fun leftState(groups: List<SyncGroup>, joinedAt: Long?): Pair<Boolean, Long?> {
        var left = false
        var at: Long? = null
        for (g in groups) {
            if (!g.left) continue
            val la = g.leftAtMs
            if (la == null) {
                if (joinedAt == null) left = true
            } else if (joinedAt == null || la >= joinedAt) {
                left = true
                at = maxOf(at ?: la, la)
            }
        }
        return left to at
    }
}
