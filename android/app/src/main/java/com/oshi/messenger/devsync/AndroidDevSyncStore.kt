package com.oshi.messenger.devsync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.devsync.AndroidDevSyncMapping.ReadMark
import com.oshi.messenger.network.v2.devsync.ApplyResult
import com.oshi.messenger.network.v2.devsync.BytesMediaSource
import com.oshi.messenger.network.v2.devsync.DevSyncCrypto
import com.oshi.messenger.network.v2.devsync.DevSyncStore
import com.oshi.messenger.network.v2.devsync.MediaSource
import com.oshi.messenger.network.v2.devsync.SyncAvatar
import com.oshi.messenger.network.v2.devsync.SyncContact
import com.oshi.messenger.network.v2.devsync.SyncConversation
import com.oshi.messenger.network.v2.devsync.SyncEdited
import com.oshi.messenger.network.v2.devsync.SyncGroup
import com.oshi.messenger.network.v2.devsync.SyncGroupKey
import com.oshi.messenger.network.v2.devsync.SyncJson
import com.oshi.messenger.network.v2.devsync.SyncMedia
import com.oshi.messenger.network.v2.devsync.SyncMessage
import com.oshi.messenger.network.v2.devsync.SyncProfile
import com.oshi.messenger.network.v2.devsync.SyncReadState
import com.oshi.messenger.service.AndroidExportV2
import com.oshi.messenger.service.ExportV2
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.media.MediaAttachmentStore
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * __DEVSYNC_DIRECT_2026_09_22__ [DevSyncStore] over this app's storage:
 *  - 1:1 messages and contacts: Room (the source of truth for those);
 *  - groups and group messages: [GroupManager] (its memory is the source of truth, Room its mirror;
 *    history older than the in-memory window is read from Room);
 *  - media: inline row bytes or [MediaAttachmentStore] (the same two stores the export uses).
 *
 * Row mapping reuses `AndroidExportV2` (the export v2 mapper, design §7). Writes go through the
 * import path: no notification, no receipt, no send. `read` is stored AS RECEIVED.
 *
 * Every method runs on the engine's single sync thread, never on the main thread, so the suspend
 * DAOs are called with runBlocking.
 */
class AndroidDevSyncStore(
    context: Context,
    private val database: AppDatabase,
    private val groupManager: GroupManager,
    private val selfKey: () -> String,
    /** __DEVSYNC_PROFILE_2026_09_23__ adopt a synced profile picture as-is (MessageRepository), no broadcast. */
    private val adoptProfilePicture: (ByteArray?) -> Unit = {},
) : DevSyncStore {

    /**
     * Set by [DevSyncManager] once the engine exists: queues a media fetch (the profile picture
     * travels by reference through MEDIA_GET, like a group picture).
     */
    @Volatile var mediaRequester: ((sha: String, size: Long, conv: String, id: String, ts: Long) -> Unit)? = null

    private val appContext = context.applicationContext

    private val directStore = MediaAttachmentStore(context.filesDir)
    private val groupStore = MediaAttachmentStore(GroupManager.groupMediaRoot(context.filesDir))

    /** (kind, message id) → (plaintext size, sha256): hashing a video once per process is enough. */
    private data class Meta(val size: Long, val sha: String)
    private val metaCache = ConcurrentHashMap<String, Meta>()
    /** sha256 → where the bytes are. */
    private sealed class Loc { data class Direct(val id: String) : Loc(); data class Grp(val id: String) : Loc(); data class Avatar(val gid: String) : Loc(); object Photo : Loc() }
    private val shaIndex = ConcurrentHashMap<String, Loc>()
    /** canonical peer key → every spelling stored in Room (url-safe / unpadded legacy rows). */
    private val spellings = ConcurrentHashMap<String, Set<String>>()

    private fun <T> db(block: suspend () -> T): T = runBlocking { block() }

    /** __BLOCK_SYNC_LWW_2026_09_24__ the durable block list: blockedAt + unblock tombstones. */
    private val blockedKeys = com.oshi.messenger.service.BlockedKeysStore.get(context)

    private fun me() = SyncJson.canonicalKey(selfKey())

    private fun isKey(s: String) = runCatching { Base64.getDecoder().decode(s).size == 32 }.getOrDefault(false)

    // ------------------------------------------------------------------ enumeration

    override fun conversations(): List<SyncConversation> {
        val me = me()
        val raw = db { database.messageDao().distinctSenderKeys() + database.messageDao().distinctRecipientKeys() }
        val byCanon = HashMap<String, MutableSet<String>>()
        for (k in raw) {
            val c = SyncJson.canonicalKey(k)
            if (c == me || !isKey(c)) continue
            byCanon.getOrPut(c) { HashSet() }.add(k)
        }
        spellings.clear()
        spellings.putAll(byCanon)
        val out = byCanon.keys.map { SyncConversation.direct(it) }.toMutableList()
        for (g in groupManager.groups.value) out += SyncConversation.group(g.id, g.name)
        return out
    }

    override fun messages(conversationId: String): List<SyncMessage> = when {
        conversationId.startsWith("direct:") -> directRows(conversationId).map { toSyncDirect(it, conversationId) }
        conversationId.startsWith("group:") -> groupRows(conversationId).map { toSyncGroup(it, conversationId) }
        else -> emptyList()
    }

    private fun peerOf(conversationId: String) = conversationId.removePrefix("direct:")

    private fun directRows(conversationId: String): List<Message> {
        val peer = peerOf(conversationId)
        if (spellings.isEmpty()) conversations()
        val keys = spellings[peer] ?: setOf(peer)
        val seen = HashSet<String>()
        val out = ArrayList<Message>()
        for (k in keys) for (m in db { database.messageDao().getMessagesForContact(k).first() }) {
            if (!seen.add(m.id.lowercase())) continue
            if (m.isDeleted || AndroidExportV2.isControl(m.content)) continue // hidden locally / control payload
            out += m
        }
        return out
    }

    private fun localGroup(conversationId: String): Group? {
        val gid = conversationId.removePrefix("group:")
        return groupManager.groups.value.firstOrNull { it.id.equals(gid, ignoreCase = true) }
    }

    private fun groupRows(conversationId: String): List<GroupMessage> {
        val g = localGroup(conversationId) ?: return emptyList()
        val byId = LinkedHashMap<String, GroupMessage>()
        // Room holds the full history (the in-memory window is the recent part and fresher).
        for (m in db { database.groupDao().getGroupMessages(g.id).first() }) byId[m.id.lowercase()] = m
        for (m in groupManager.devSyncMessagesInMemory(g.id)) byId[m.id.lowercase()] = m
        return byId.values.filter { !AndroidExportV2.isControl(it.content) && it.senderPublicKey != "SYSTEM" }
    }

    override fun messagesByIds(conversationId: String, ids: Collection<String>): List<SyncMessage> {
        val want = ids.map { it.lowercase() }.toSet()
        return messages(conversationId).filter { it.id.lowercase() in want }
    }

    // ------------------------------------------------------------------ mapping

    private fun mediaMeta(kind: String, id: String, inline: ByteArray?, store: MediaAttachmentStore): Meta? {
        val key = "$kind:$id"
        if (inline != null) {
            metaCache[key]?.takeIf { it.size == inline.size.toLong() }?.let { return it }
            return Meta(inline.size.toLong(), sha(inline)).also { metaCache[key] = it }
        }
        val size = store.sizeOf(id)
        if (size < 0) return null
        metaCache[key]?.takeIf { it.size == size }?.let { return it }
        val s = store.openStream(id)?.use { sha(it) } ?: return null
        return Meta(size, s).also { metaCache[key] = it }
    }

    private fun syncMedia(type: com.oshi.messenger.data.model.MediaType?, fileName: String?, viewOnce: Boolean, meta: Meta?): SyncMedia? {
        if (type == null) return null
        val t = AndroidExportV2.typeFor(type)
        val mime = ExportV2.guessMime(t, fileName)
        return when {
            viewOnce -> SyncMedia(t, fileName, mime, omitted = "viewOnce")
            meta == null -> SyncMedia(t, fileName, mime, omitted = "unavailable")
            else -> SyncMedia(t, fileName, mime, size = meta.size, sha256 = meta.sha)
        }
    }

    private fun fromExport(m: ExportV2.Msg, conv: String, media: SyncMedia?) = SyncMessage(
        id = m.id, conversation = conv, outgoing = m.outgoing, senderPublicKey = m.senderPublicKey,
        senderName = m.senderName, timestampMs = m.timestampMs, text = m.text, status = m.status, read = m.read,
        edited = m.editedText?.let { SyncEdited(it, m.editedAtMs) }, deleted = m.deleted, replyToId = m.replyToId,
        viewOnce = m.viewOnce, media = media, reactions = m.reactions,
    )

    private fun toSyncDirect(m: Message, conv: String): SyncMessage {
        // The export mapper splits caption vs document name; a placeholder media object lets it.
        val placeholder = m.mediaType?.let { AndroidExportV2.media(it, null, null, "unavailable") }
        val (_, x) = AndroidExportV2.direct(m, placeholder)
        val meta = if (m.mediaType != null && !m.isViewOnce && !m.isDeletedForEveryone) mediaMeta("d", m.id, m.mediaData, directStore) else null
        meta?.let { shaIndex[it.sha] = Loc.Direct(m.id) }
        return fromExport(x, conv, syncMedia(m.mediaType, x.media?.fileName, m.isViewOnce, meta))
    }

    private fun toSyncGroup(m: GroupMessage, conv: String): SyncMessage {
        val g = localGroup(conv)
        val placeholder = m.mediaType?.let { AndroidExportV2.media(it, m.mediaFileName, null, "unavailable") }
        val (_, x) = AndroidExportV2.group(m, g?.name, placeholder)
        val meta = if (m.mediaType != null && !m.isDeleted) mediaMeta("g", m.id, m.mediaData, groupStore) else null
        meta?.let { shaIndex[it.sha] = Loc.Grp(m.id) }
        return fromExport(x, conv, syncMedia(m.mediaType, x.media?.fileName, false, meta))
    }

    private fun toExport(m: SyncMessage): ExportV2.Msg = ExportV2.Msg(
        id = m.id, conversation = m.conversation, outgoing = m.outgoing, senderPublicKey = m.senderPublicKey,
        senderName = m.senderName, timestampMs = m.timestampMs, text = m.text, status = m.status, read = m.read,
        editedText = m.edited?.text, editedAtMs = m.edited?.atMs, deleted = m.deleted, replyToId = m.replyToId,
        viewOnce = m.viewOnce,
        media = m.media?.let { ExportV2.Media(it.type, it.fileName, it.mime, null, it.omitted ?: "unavailable") },
        reactions = m.reactions,
    )

    // ------------------------------------------------------------------ writes

    override fun apply(conversation: SyncConversation, inserts: List<SyncMessage>, updates: List<SyncMessage>): ApplyResult =
        when (conversation.kind) {
            SyncConversation.KIND_DIRECT -> applyDirect(conversation, inserts, updates)
            SyncConversation.KIND_GROUP -> applyGroup(conversation, inserts, updates)
            else -> ApplyResult()
        }

    private suspend fun findDirect(id: String): Message? {
        val dao = database.messageDao()
        return dao.getMessageById(id) ?: dao.getMessageById(id.lowercase()) ?: dao.getMessageById(id.uppercase())
    }

    private fun applyDirect(c: SyncConversation, inserts: List<SyncMessage>, updates: List<SyncMessage>): ApplyResult = db {
        val dao = database.messageDao()
        val me = me()
        val peer = c.peerPublicKey ?: return@db ApplyResult(failed = inserts.size + updates.size)
        var ins = 0
        var upd = 0
        for (m in inserts) {
            if (findDirect(m.id) != null) continue
            val row = AndroidExportV2.toDirect(toExport(m), peer, me, null)
            // Sync mirrors state (design §8.2): `read` as received, not forced true like an import.
            dao.insert(row.copy(isRead = m.read, isDelivered = !m.outgoing || m.status == "delivered" || m.status == "read"))
            ins++
        }
        for (m in updates) {
            val row = findDirect(m.id) ?: continue
            val deleted = row.isDeletedForEveryone || m.deleted
            val next = row.copy(
                isRead = if (row.isFromMe) row.isRead || m.status == "read" else row.isRead || m.read,
                isDelivered = row.isDelivered || m.status == "delivered" || m.status == "read",
                isFailed = row.isFromMe && m.status == "failed",
                editedContent = if (deleted) null else m.edited?.text ?: row.editedContent,
                editedAt = if (deleted) row.editedAt else m.edited?.atMs ?: row.editedAt,
                isDeletedForEveryone = deleted,
                content = if (deleted) "" else row.content,
                mediaData = if (deleted) null else row.mediaData,
            )
            if (next != row) {
                dao.update(next)
                if (deleted && !row.isDeletedForEveryone) directStore.delete(row.id) // §8.3: deleted erases local media
                upd++
            }
        }
        if (ins > 0 && database.contactDao().getContactByPublicKey(peer) == null) {
            // A conversation only shows once its peer has a contact row (same as the importer).
            database.contactDao().insert(Contact(publicKey = peer, alias = peer.take(8)))
        }
        ApplyResult(inserted = ins, updated = upd)
    }

    private fun applyGroup(c: SyncConversation, inserts: List<SyncMessage>, updates: List<SyncMessage>): ApplyResult {
        val g = localGroup(c.id) ?: return ApplyResult(skippedNoGroup = inserts.size + updates.size)
        val me = me()
        val existing = groupRows(c.id).associateBy { it.id.lowercase() }
        val out = ArrayList<GroupMessage>()
        var ins = 0
        var upd = 0
        for (m in inserts) {
            if (existing.containsKey(m.id.lowercase())) continue
            out += AndroidExportV2.toGroup(toExport(m), g.id, me, null, null).copy(isRead = m.read)
            ins++
        }
        for (m in updates) {
            val row = existing[m.id.lowercase()] ?: continue
            val deleted = row.isDeleted || m.deleted
            val reactions = LinkedHashMap<String, String>()
            if (!deleted) m.reactions.forEach { (emoji, keys) -> keys.forEach { reactions[it] = emoji } }
            val next = row.copy(
                isRead = row.isRead || m.read,
                isDeleted = deleted,
                content = if (deleted) "" else row.content,
                mediaData = if (deleted) null else row.mediaData,
                editedContent = if (deleted) null else m.edited?.text ?: row.editedContent,
                editedAt = if (deleted) row.editedAt else m.edited?.atMs ?: row.editedAt,
                reactions = if (deleted) emptyMap() else row.reactions + reactions,
            )
            if (next != row) {
                out += next
                if (deleted && !row.isDeleted) groupStore.delete(row.id)
                upd++
            }
        }
        if (out.isEmpty()) return ApplyResult()
        return if (groupManager.devSyncUpsertMessages(g.id, out)) ApplyResult(inserted = ins, updated = upd)
        else ApplyResult(skippedNoGroup = inserts.size + updates.size)
    }

    // ------------------------------------------------------------------ media

    override fun openMedia(sha256: String): MediaSource? {
        if (!shaIndex.containsKey(sha256)) {
            // Cold index (fresh process): walk what we hold once.
            for (c in conversations()) messages(c.id)
            groups()
            profile()
        }
        return when (val loc = shaIndex[sha256] ?: return null) {
            is Loc.Direct -> db { findDirect(loc.id) }?.let { m ->
                m.mediaData?.let { BytesMediaSource(it) } ?: storeSource(directStore, m.id)
            }
            is Loc.Grp -> groupMessageById(loc.id)?.let { m ->
                m.mediaData?.let { BytesMediaSource(it) } ?: storeSource(groupStore, m.id)
            }
            is Loc.Avatar -> groupManager.groups.value.firstOrNull { it.id.equals(loc.gid, true) }?.groupImage?.let { BytesMediaSource(it) }
            Loc.Photo -> localPhoto()?.takeIf { sha(it) == sha256 }?.let { BytesMediaSource(it) }
        }
    }

    private fun groupMessageById(id: String): GroupMessage? {
        for (g in groupManager.groups.value) {
            groupManager.devSyncMessagesInMemory(g.id).firstOrNull { it.id.equals(id, true) }?.let { return it }
        }
        return db { database.groupDao().getMessageById(id) ?: database.groupDao().getMessageById(id.lowercase()) ?: database.groupDao().getMessageById(id.uppercase()) }
    }

    private fun storeSource(store: MediaAttachmentStore, id: String): MediaSource? {
        val size = store.sizeOf(id)
        if (size < 0) return null
        return object : MediaSource {
            override val size: Long = size
            override fun open(): InputStream = store.openStream(id) ?: throw java.io.IOException("media gone")
        }
    }

    override fun attachMedia(conversationId: String, messageId: String, sha256: String, file: File): Boolean {
        if (conversationId == PROFILE_CONV && messageId == PROFILE_REF) return adoptPhoto(sha256, file)
        if (messageId == DevSyncStore.AVATAR_REF) {
            val g = localGroup(conversationId) ?: return false
            if (file.length() > MAX_AVATAR_BYTES) return false
            groupManager.devSyncUpsertGroup(g.copy(groupImage = file.readBytes()))
            shaIndex[sha256] = Loc.Avatar(g.id)
            return true
        }
        return if (conversationId.startsWith("direct:")) db {
            val row = findDirect(messageId) ?: return@db false
            if (file.length() <= MAX_INLINE_MEDIA_BYTES) {
                database.messageDao().update(row.copy(mediaData = file.readBytes()))
            } else if (!directStore.save(row.id, file)) return@db false
            metaCache["d:${row.id}"] = Meta(file.length(), sha256)
            shaIndex[sha256] = Loc.Direct(row.id)
            true
        } else {
            val g = localGroup(conversationId) ?: return false
            val row = groupMessageById(messageId) ?: return false
            val next = if (file.length() <= MAX_INLINE_MEDIA_BYTES) row.copy(mediaData = file.readBytes())
            else { if (!groupStore.save(row.id, file)) return false; row }
            groupManager.devSyncUpsertMessages(g.id, listOf(next))
            metaCache["g:${row.id}"] = Meta(file.length(), sha256)
            shaIndex[sha256] = Loc.Grp(row.id)
            true
        }
    }

    // ------------------------------------------------------------------ contacts

    /**
     * __BLOCK_SYNC_LWW_2026_09_24__ CONTACTS with real block timestamps (design §8.5, iOS
     * `V2SyncManager.contacts()`): a block travels as `blocked:true` + the time it was made
     * (BlockedKeysStore), an unblock as `blocked:false` + the unblock time (its tombstone), and a
     * contact never blocked here carries no block field at all (never `false@now`: that would
     * undo a block made earlier on another device). Keys blocked or unblocked without a contact
     * row travel too.
     */
    override fun contacts(): List<SyncContact> {
        val rows = db { database.contactDao().getEveryContact() }
        val seen = HashSet<String>()
        val out = ArrayList<SyncContact>()
        for (c in rows) {
            val state = AndroidDevSyncMapping.blockState(blockedKeys.entry(c.publicKey), blockedKeys.unblockedAt(c.publicKey), c.isBlocked)
            val sc = AndroidDevSyncMapping.syncContact(c, me(), state) ?: continue
            if (seen.add(sc.publicKey)) out += sc
        }
        val loose = blockedKeys.entries.value.map { it.publicKey } + blockedKeys.unblockedKeys()
        for (k in loose) {
            val state = AndroidDevSyncMapping.blockState(blockedKeys.entry(k), blockedKeys.unblockedAt(k), false)
            val sc = AndroidDevSyncMapping.blockOnlyContact(k, me(), state) ?: continue
            if (seen.add(sc.publicKey)) out += sc
        }
        return out
    }

    override fun putContact(contact: SyncContact) = db {
        val dao = database.contactDao()
        val existing = dao.getContactByPublicKey(contact.publicKey)
        val next = AndroidDevSyncMapping.contactRow(existing, contact)
        if (next != null) { if (existing == null) dao.insert(next) else dao.update(next) }
        // __BLOCK_SYNC_LWW_2026_09_24__ the merged block state is applied IN FULL, to the durable
        // list too (every receive/ring gate reads it first): an unblock used to clear only the
        // row, the list kept the key and re-migrated it, so the contact stayed blocked here.
        var changed = existing?.isBlocked != next?.isBlocked && next != null
        when (AndroidDevSyncMapping.blockAction(contact, blockedKeys.entry(contact.publicKey), blockedKeys.unblockedAt(contact.publicKey))) {
            AndroidDevSyncMapping.BlockAction.BLOCK -> {
                blockedKeys.blockAt(contact.publicKey, contact.alias ?: existing?.alias, contact.blockedAtMs ?: 0L)
                changed = true
            }
            AndroidDevSyncMapping.BlockAction.UNBLOCK -> {
                blockedKeys.unblockAt(contact.publicKey, contact.blockedAtMs)
                val n = com.oshi.messenger.service.BlockedKeys.normalize(contact.publicKey)
                dao.getBlockedContacts().first().filter { com.oshi.messenger.service.BlockedKeys.normalize(it.publicKey) == n }
                    .forEach { dao.setBlocked(it.publicKey, false) }
                changed = true
            }
            AndroidDevSyncMapping.BlockAction.NONE -> Unit
        }
        // A block/unblock synced from another device must reach the receive gates now,
        // not at the next restart (they read MessageRepository's cache, not the DAO).
        if (changed) com.oshi.messenger.data.repository.MessageRepository.reloadBlockedCache()
    }

    // ------------------------------------------------------------------ groups (D11)

    override fun groups(): List<SyncGroup> {
        val live = liveGroups()
        val present = live.map { it.groupId }.toSet()
        // __DEVSYNC_LEAVE_2026_09_23__ A group LEFT on this account (here or synced from another
        // device) keeps travelling as {left:true, leftAt, joinedAt} (§8.4 + rejoin rule). Members
        // are empty at epoch 0 (the union keeps the peer's), nameAt is null (never overrides a name),
        // the legacy key is kept (keys are never dropped).
        val left = groupManager.devSyncLeaves().filter { SyncJson.canonicalGroupId(it.groupId) !in present }.map { l ->
            AndroidDevSyncMapping.leftGroup(l.groupId, l.name, l.leftAtMs, l.legacyKey, groupManager.devSyncJoinedAt(l.groupId))
        }
        return live + left
    }

    private fun liveGroups(): List<SyncGroup> = groupManager.groups.value.map { g ->
        val avatar = g.groupImage?.let { img ->
            val s = sha(img)
            shaIndex[s] = Loc.Avatar(g.id)
            SyncAvatar(s, img.size.toLong(), "image/jpeg")
        }
        AndroidDevSyncMapping.syncGroup(g, avatar, groupManager.devSyncJoinedAt(g.id),
            // __GROUP_E2E_V2_2026_09_23__ epoch IS the wire stateVersion (GROUP_E2E_V2_SPEC §5.3).
            epoch = (groupManager.stateVersionOf(g.id) ?: 0).toLong(),
            mutedAtMs = groupManager.mutedAtOf(g.id))
    }

    override fun putGroup(group: SyncGroup) {
        val existing = groupManager.groups.value.firstOrNull { it.id.equals(group.groupId, ignoreCase = true) }
        val legacyKey = AndroidDevSyncMapping.legacyKey(group)
        // __DEVSYNC_LEAVE_2026_09_23__ left on another device: leave here too, silently (no notice
        // to the members, no key rotation: the leaving device already announced it). The merged
        // leftAt is the latest one that counts (§8.4); a legacy leave without a time counts as "now".
        // __DEVSYNC_REJOIN_2026_09_23__ the merged joinedAt is kept; a record that is no longer
        // left because a later join (on another device) beat this device's leave lifts that leave.
        group.joinedAtMs?.let { groupManager.devSyncAdoptJoin(group.groupId, it, group.left) }
        if (group.left) {
            groupManager.devSyncLeaveGroup(group.groupId, group.leftAtMs ?: System.currentTimeMillis(), group.name, legacyKey)
            return
        }
        // Left or deleted here before sync tracked leaves (tombstone only): never resurrect it.
        if (groupManager.devSyncRefusesGroup(group.groupId)) return
        groupManager.devSyncUpsertGroup(AndroidDevSyncMapping.groupRow(group, existing, me()))
        // __GROUP_MUTE_SYNC_2026_09_24__ the merged mute and its time; absent = no information.
        group.muted?.let { groupManager.devSyncApplyMute(group.groupId, it, group.mutedAtMs) }
        // __GROUP_E2E_V2_2026_09_23__ after a merge the local stateVersion = the merged epoch.
        if (group.epoch > 0) groupManager.adoptStateVersion(group.groupId, group.epoch.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    // ------------------------------------------------------------------ profile (§8.6)
    //
    // __DEVSYNC_PROFILE_2026_09_23__ PROFILE = {name, photo:{sha256,size,mime}|null, links, updatedAt}.
    // Last writer wins on updatedAt, set by the device where the user edited it. The app has several
    // edit paths (Settings, Profile edit, onboarding, links editor), so instead of hooking each one a
    // fingerprint of what the app stores is kept: when it moves, that is a local edit and updatedAt =
    // the time it was noticed ([refreshProfileStamp], polled every few seconds by DevSyncManager).
    // The very first snapshot is legacy (updatedAt 0): any stamped value from another device wins.
    // Fields this client does not model travel through unchanged (`extra`).
    //
    // Apply: name → the DataStore key Settings/MessageRepository read + the Profile screen's copy;
    // photo → by reference (MEDIA_GET), adopted byte for byte; links → the links editor's prefs.
    // Nothing is broadcast to contacts from here: the editing device already did.

    private val profilePrefs = appContext.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val linksPrefs = appContext.getSharedPreferences("oshi_profile_links", Context.MODE_PRIVATE)
    private val devsyncPrefs = appContext.getSharedPreferences(DEVSYNC_PREFS, Context.MODE_PRIVATE)

    private fun localName(): String? =
        runCatching { db { appContext.dataStore.data.first()[KEY_DISPLAY_NAME] } }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: profilePrefs.getString("display_name", null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun localPhoto(): ByteArray? = profilePrefs.getString("profile_picture", null)
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    private fun localLinks(): JSONObject? {
        val o = JSONObject()
        for (k in LINK_KEYS) linksPrefs.getString("profile_link_$k", null)?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put(k, it) }
        return o.takeIf { it.length() > 0 }
    }

    private fun profileFingerprint(): String {
        val sha = localPhoto()?.let { sha(it) }.orEmpty()
        return listOf(localName().orEmpty(), sha, localLinks()?.toString().orEmpty()).joinToString("\u0000")
    }

    /** Stamp a local profile edit. Returns true when one was detected (the caller then pushes it). */
    @Synchronized
    fun refreshProfileStamp(): Boolean {
        val fp = profileFingerprint()
        val prev = devsyncPrefs.getString(KEY_PROFILE_FP, null)
        if (prev == fp) return false
        val e = devsyncPrefs.edit().putString(KEY_PROFILE_FP, fp)
        if (prev == null) {
            if (!devsyncPrefs.contains(KEY_PROFILE_AT)) e.putLong(KEY_PROFILE_AT, 0L)
        } else {
            e.putLong(KEY_PROFILE_AT, System.currentTimeMillis()).remove(KEY_PROFILE_PENDING_PHOTO)
        }
        e.commit()
        return prev != null
    }

    /**
     * __DEVSYNC_PROFILE_EVENTS_2026_09_23__ Event-driven profile edits: the name's DataStore key and
     * the `profile` / links SharedPreferences are observed; a change that [refreshProfileStamp]
     * confirms as a LOCAL edit calls [onEdited] (→ `DevSyncEngine.onLocalStateChanged`, debounced
     * 150 ms). The adoption path ([putProfile], [adoptPhoto]) holds this object's monitor while it
     * writes and re-bases the fingerprint before releasing it, so its own writes never count as
     * edits. The DevSyncManager 5 s poll stays as the safety net. Returns the unregister function.
     */
    fun watchProfileChanges(scope: CoroutineScope, onEdited: () -> Unit): () -> Unit {
        val check: () -> Unit = {
            scope.launch { if (runCatching { refreshProfileStamp() }.getOrDefault(false)) onEdited() }
        }
        // Held strongly here: SharedPreferences keeps its listeners in a weak map.
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> check() }
        profilePrefs.registerOnSharedPreferenceChangeListener(listener)
        linksPrefs.registerOnSharedPreferenceChangeListener(listener)
        val job = scope.launch {
            appContext.dataStore.data.map { it[KEY_DISPLAY_NAME] }.distinctUntilChanged().drop(1).collect { check() }
        }
        return {
            profilePrefs.unregisterOnSharedPreferenceChangeListener(listener)
            linksPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            job.cancel()
        }
    }

    @Synchronized
    override fun profile(): SyncProfile? {
        refreshProfileStamp()
        val name = localName()
        val photo = localPhoto()
        val links = localLinks()
        val extra = runCatching { JSONObject(devsyncPrefs.getString(KEY_PROFILE_EXTRA, null) ?: "{}") }.getOrDefault(JSONObject())
        val pending = devsyncPrefs.getString(KEY_PROFILE_PENDING_PHOTO, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val photoRef = photo?.let { p -> sha(p).also { shaIndex[it] = Loc.Photo } to p.size.toLong() }
        return AndroidDevSyncMapping.profileRecord(name, photoRef, pending, links, extra, devsyncPrefs.getLong(KEY_PROFILE_AT, 0L))
    }

    /** Called by the core only when the other device's profile is strictly newer (§8.6). */
    @Synchronized
    override fun putProfile(profile: SyncProfile) {
        val x = profile.extra
        profile.name?.trim()?.take(48)?.takeIf { it.isNotEmpty() }?.let { n ->
            if (n != localName()) {
                runCatching { db { appContext.dataStore.edit { it[KEY_DISPLAY_NAME] = n } } }
                profilePrefs.edit().putString("display_name", n).apply()
            }
        }
        var pending: JSONObject? = null
        if (x.has("photo")) {
            val ph = x.optJSONObject("photo")
            if (ph == null) {
                if (localPhoto() != null) adoptProfilePicture(null)
            } else {
                val want = ph.optString("sha256").lowercase()
                if (want.isNotEmpty() && want != localPhoto()?.let { sha(it) }) {
                    pending = ph
                    mediaRequester?.invoke(want, ph.optLong("size", -1L), PROFILE_CONV, PROFILE_REF, profile.updatedAtMs)
                }
            }
        }
        x.optJSONObject("links")?.let { l ->
            linksPrefs.edit().apply { for (k in LINK_KEYS) putString("profile_link_$k", l.optString(k, "")) }.apply()
        }
        val rest = JSONObject(x.toString()).apply { remove("photo"); remove("links") }
        // What the app now stores is the ADOPTED state, not a local edit.
        devsyncPrefs.edit()
            .putString(KEY_PROFILE_FP, profileFingerprint())
            .putLong(KEY_PROFILE_AT, profile.updatedAtMs)
            .putString(KEY_PROFILE_EXTRA, rest.toString())
            .apply { if (pending != null) putString(KEY_PROFILE_PENDING_PHOTO, pending.toString()) else remove(KEY_PROFILE_PENDING_PHOTO) }
            .commit()
    }

    @Synchronized
    private fun adoptPhoto(sha256: String, file: File): Boolean {
        val pending = devsyncPrefs.getString(KEY_PROFILE_PENDING_PHOTO, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        // Superseded (a newer local edit, or a newer synced picture): drop it.
        if (pending == null || !pending.optString("sha256").equals(sha256, ignoreCase = true)) return true
        if (file.length() > MAX_AVATAR_BYTES) return false
        adoptProfilePicture(file.readBytes())
        shaIndex[sha256.lowercase()] = Loc.Photo
        devsyncPrefs.edit().putString(KEY_PROFILE_FP, profileFingerprint()).remove(KEY_PROFILE_PENDING_PHOTO).commit()
        return true
    }

    // ------------------------------------------------------------------ read state (§8.7)
    //
    // __DEVSYNC_READ_STATE_2026_09_23__ READ_STATE{conv, readUpTo, at}, one per conversation.
    // Export: readUpTo = the newest INCOMING message this device shows as read (or a higher value
    // adopted from another device); `at` moves when readUpTo does. Apply: readUpTo = max of both;
    // incoming rows at or before it become read locally, which clears the unread badges. It never
    // emits a read receipt (the device that read them already did), and opening the conversation
    // afterwards finds nothing unread, so no receipt goes out then either.

    private fun storedReads(): MutableMap<String, ReadMark> {
        val o = runCatching { JSONObject(devsyncPrefs.getString(KEY_READ_STATE, null) ?: "{}") }.getOrDefault(JSONObject())
        val out = HashMap<String, ReadMark>()
        for (k in o.keys()) o.optJSONObject(k)?.let { out[k] = ReadMark(it.optLong("r"), it.optLong("a")) }
        return out
    }

    private fun saveReads(m: Map<String, ReadMark>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONObject().put("r", v.readUpToMs).put("a", v.atMs))
        devsyncPrefs.edit().putString(KEY_READ_STATE, o.toString()).commit()
    }

    /** Newest incoming message shown as read, per sync conversation id. */
    private fun localReadUpTo(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (c in conversations()) {
            val t = when (c.kind) {
                SyncConversation.KIND_DIRECT -> directRows(c.id).filter { !it.isFromMe && it.isRead }.maxOfOrNull { it.timestamp }
                SyncConversation.KIND_GROUP -> groupRows(c.id).filter { !it.isFromMe && it.isRead }.maxOfOrNull { it.timestamp }
                else -> null
            }
            if (t != null && t > 0) out[c.id] = t
        }
        return out
    }

    @Synchronized
    override fun readStates(): List<SyncReadState> {
        // No cache (__DEVSYNC_READ_FRESH_2026_09_23__): the core reads this once per burst of incoming
        // READ_STATE records, and a stale value here delayed a local read by up to the 30 s sweep.
        val stored = storedReads()
        if (AndroidDevSyncMapping.advanceReadMarks(stored, localReadUpTo(), System.currentTimeMillis())) saveReads(stored)
        return stored.map { (conv, m) -> SyncReadState(conv, m.readUpToMs, m.atMs) }
    }

    @Synchronized
    override fun putReadState(state: SyncReadState) {
        val stored = storedReads()
        val prev = stored[state.conv]
        val merged = AndroidDevSyncMapping.mergeReadMark(prev, state)
        if (merged != prev) { stored[state.conv] = merged; saveReads(stored) }
        when {
            state.conv.startsWith("direct:") -> db {
                val dao = database.messageDao()
                for (m in directRows(state.conv)) {
                    if (!m.isFromMe && !m.isRead && m.timestamp <= merged.readUpToMs) dao.markAsReadById(m.id)
                }
            }
            state.conv.startsWith("group:") -> localGroup(state.conv)?.let { g ->
                groupManager.devSyncMarkReadUpTo(g.id, merged.readUpToMs)
                // Rows only in Room (older than the in-memory window) are read there too.
                val stale = db { database.groupDao().getGroupMessages(g.id).first() }
                    .filter { !it.isFromMe && !it.isRead && it.timestamp <= merged.readUpToMs }
                if (stale.isNotEmpty()) groupManager.devSyncUpsertMessages(g.id, stale.map { it.copy(isRead = true) })
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sha(b: ByteArray): String = DevSyncCrypto.hex(DevSyncCrypto.sha256(b))

    private fun sha(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return DevSyncCrypto.hex(md.digest())
    }

    companion object {
        /** Mirror of MessageRepository.MAX_INLINE_MEDIA_BYTES (CursorWindow limit). */
        private const val MAX_INLINE_MEDIA_BYTES = 1_500_000L
        private const val MAX_AVATAR_BYTES = 2L * 1024 * 1024

        /** Media ref of the account's own profile picture (PROFILE.photo), outside any conversation. */
        const val PROFILE_CONV = "profile"
        const val PROFILE_REF = "#photo"
        private const val DEVSYNC_PREFS = "oshi_devsync_store"
        private const val KEY_PROFILE_FP = "profile_fp"
        private const val KEY_PROFILE_AT = "profile_updated_at"
        private const val KEY_PROFILE_EXTRA = "profile_extra"
        private const val KEY_PROFILE_PENDING_PHOTO = "profile_pending_photo"
        private const val KEY_READ_STATE = "read_state"
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val LINK_KEYS = listOf("website", "linkedin", "twitter", "instagram", "github")
    }
}

/**
 * The pure half of [AndroidDevSyncStore] (no Context, no Room): row ⇄ sync-record mapping and the
 * small merges the store applies on top of the core's. Extracted so the JVM tests cover it
 * (AndroidDevSyncMappingTest) without an emulator.
 */
internal object AndroidDevSyncMapping {

    data class ReadMark(val readUpToMs: Long, val atMs: Long)

    private fun isKey(s: String) = runCatching { Base64.getDecoder().decode(s).size == 32 }.getOrDefault(false)

    /**
     * A Room contact as a CONTACTS entry, or null (not a key, or our own). Android keeps no
     * per-field timestamps: legacy data, merged as gap-fill only (design §8.5). The importer's
     * placeholder alias (the key's first 8 chars) is not a name.
     */
    fun syncContact(c: Contact, me: String, block: BlockState = BlockState(c.isBlocked.takeIf { it }, null)): SyncContact? {
        val pk = SyncJson.canonicalKey(c.publicKey)
        if (!isKey(pk) || pk == me) return null
        val alias = c.alias.takeIf { it.isNotBlank() && it != c.publicKey.take(8) && it != pk.take(8) }
        return SyncContact(pk, alias = alias, blocked = block.blocked, blockedAtMs = block.blockedAtMs, verified = c.isVerified.takeIf { it })
    }

    /** __BLOCK_SYNC_LWW_2026_09_24__ the block fields of one key: (blocked, blockedAt). */
    data class BlockState(val blocked: Boolean?, val blockedAtMs: Long?)

    /**
     * From the durable list: blocked → `true` + its block time (0 = a migrated legacy flag →
     * untimestamped); unblocked → `false` + the unblock time; a flagged row the list does not know
     * yet → `true` untimestamped; otherwise nothing (never `false` without a real unblock time).
     */
    fun blockState(entry: com.oshi.messenger.service.BlockedKeys.Entry?, unblockedAt: Long?, rowBlocked: Boolean): BlockState = when {
        entry != null -> BlockState(true, entry.blockedAt.takeIf { it > 0 })
        unblockedAt != null -> BlockState(false, unblockedAt)
        rowBlocked -> BlockState(true, null)
        else -> BlockState(null, null)
    }

    /** A key blocked or unblocked here with no contact row: travels with its block fields only. */
    fun blockOnlyContact(key: String, me: String, block: BlockState): SyncContact? {
        val pk = SyncJson.canonicalKey(key)
        if (!isKey(pk) || pk == me || block.blocked == null) return null
        return SyncContact(pk, blocked = block.blocked, blockedAtMs = block.blockedAtMs)
    }

    enum class BlockAction { BLOCK, UNBLOCK, NONE }

    /**
     * What a MERGED contact record asks of the durable list. `true` → block at the merged time
     * (unless already blocked at exactly that time; an untimestamped merged block never
     * downgrades an existing entry); `false` → unblock every spelling, tombstone at the merged
     * time; absent → nothing.
     */
    fun blockAction(merged: SyncContact, entry: com.oshi.messenger.service.BlockedKeys.Entry?, unblockedAt: Long?): BlockAction = when (merged.blocked) {
        true -> when {
            entry == null -> BlockAction.BLOCK
            merged.blockedAtMs != null && entry.blockedAt != merged.blockedAtMs -> BlockAction.BLOCK
            else -> BlockAction.NONE
        }
        false -> when {
            entry != null -> BlockAction.UNBLOCK
            merged.blockedAtMs != null && unblockedAt != merged.blockedAtMs -> BlockAction.UNBLOCK
            else -> BlockAction.NONE
        }
        null -> BlockAction.NONE
    }

    /** The row to write for a merged contact: a new row, the updated row, or null when nothing changes. */
    fun contactRow(existing: Contact?, contact: SyncContact): Contact? {
        if (existing == null) {
            // __BLOCK_SYNC_LWW_2026_09_24__ a bare unblock (no alias, not blocked) for a key with no
            // row here: nothing to show, so no ghost contact is created for it.
            if (contact.alias == null && contact.blocked == false && contact.verified != true) return null
            return Contact(
                publicKey = contact.publicKey,
                alias = contact.alias ?: contact.publicKey.take(8),
                isBlocked = contact.blocked == true,
                isVerified = contact.verified == true,
            )
        }
        val next = existing.copy(
            alias = contact.alias ?: existing.alias,
            isBlocked = contact.blocked ?: existing.isBlocked,
            isVerified = contact.verified ?: existing.isVerified,
        )
        return next.takeIf { it != existing }
    }

    private fun legacyKeys(key: ByteArray?): List<SyncGroupKey> =
        key?.let { listOf(SyncGroupKey(SyncGroupKey.KIND_LEGACY_AES, 0, Base64.getEncoder().encodeToString(it))) } ?: emptyList()

    /** A live group. Android keeps one updatedAt per group: it stands in for nameAt / avatarAt. */
    fun syncGroup(g: Group, avatar: SyncAvatar?, joinedAtMs: Long?, epoch: Long = 0, mutedAtMs: Long? = null): SyncGroup = SyncGroup(
        groupId = SyncJson.canonicalGroupId(g.id),
        name = g.name,
        nameAtMs = g.updatedAt,
        avatar = avatar,
        avatarAtMs = if (avatar != null) g.updatedAt else null,
        members = g.memberPublicKeys.map(SyncJson::canonicalKey),
        admins = g.adminPublicKeys.map(SyncJson::canonicalKey),
        epoch = epoch,
        joinedAtMs = joinedAtMs,
        keys = legacyKeys(g.encryptionKey),
        updatedAtMs = g.updatedAt,
        description = g.description.ifBlank { null },
        type = g.effectiveGroupType.value,
        creator = SyncJson.canonicalKey(g.creatorPublicKey),
        avatarEmoji = g.avatar,
        pinnedMessageId = g.pinnedMessageId,
        pinnedBy = g.pinnedBy,
        // __GROUP_MUTE_SYNC_2026_09_24__ with its time once toggled (or synced) here; never
        // toggled → a mute untimestamped (gap-fill), no mute absent (never false@now).
        muted = if (mutedAtMs != null) g.isMuted else g.isMuted.takeIf { it },
        mutedAtMs = mutedAtMs,
    )

    /** A group left on this account: no members at epoch 0, no nameAt, the legacy key kept. */
    fun leftGroup(groupId: String, name: String, leftAtMs: Long, legacyKey: ByteArray?, joinedAtMs: Long?): SyncGroup = SyncGroup(
        groupId = SyncJson.canonicalGroupId(groupId),
        name = name,
        left = true,
        leftAtMs = leftAtMs,
        joinedAtMs = joinedAtMs,
        keys = legacyKeys(legacyKey),
        updatedAtMs = leftAtMs,
    )

    fun legacyKey(group: SyncGroup): ByteArray? = group.keys.firstOrNull { it.kind == SyncGroupKey.KIND_LEGACY_AES }?.key
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** The Group row for a merged (not left) group; local-only fields are kept by GroupManager. */
    fun groupRow(group: SyncGroup, existing: Group?, me: String): Group {
        val type = group.type?.let { GroupType.fromValue(it) }
        val base = existing ?: Group(
            id = group.groupId,
            name = group.name,
            creatorPublicKey = group.creator ?: group.admins.firstOrNull() ?: me,
            memberPublicKeys = group.members,
            adminPublicKeys = group.admins,
            createdAt = group.updatedAtMs,
        )
        return base.copy(
            name = group.name,
            description = group.description ?: base.description,
            creatorPublicKey = group.creator ?: base.creatorPublicKey,
            memberPublicKeys = group.members,
            adminPublicKeys = group.admins,
            updatedAt = group.updatedAtMs,
            isPublic = type == GroupType.PUBLIC,
            groupType = type ?: base.groupType,
            encryptionKey = legacyKey(group) ?: base.encryptionKey,
            pinnedMessageId = group.pinnedMessageId,
            pinnedBy = group.pinnedBy,
            avatar = group.avatarEmoji ?: base.avatar,
        )
    }

    /** Local reads only move marks FORWARD (`at` = now when they do). Returns true when [stored] changed. */
    fun advanceReadMarks(stored: MutableMap<String, ReadMark>, localReadUpTo: Map<String, Long>, now: Long): Boolean {
        var dirty = false
        for ((conv, t) in localReadUpTo) {
            val prev = stored[conv]
            if (prev == null || t > prev.readUpToMs) { stored[conv] = ReadMark(t, now); dirty = true }
        }
        return dirty
    }

    /** §8.7: readUpTo = max, at = max. */
    fun mergeReadMark(prev: ReadMark?, state: SyncReadState): ReadMark =
        ReadMark(maxOf(prev?.readUpToMs ?: 0L, state.readUpToMs), maxOf(prev?.atMs ?: 0L, state.atMs))

    /**
     * PROFILE (§8.6). [photo] = (sha256, size) of the local picture; [pending] = a newer device's
     * picture adopted but not fetched yet (announced instead, so this device never reverts it);
     * no picture at all = explicit `photo:null` ("no picture", while absent means "unknown").
     * Null when there is nothing to say.
     */
    fun profileRecord(name: String?, photo: Pair<String, Long>?, pending: JSONObject?, links: JSONObject?, extra: JSONObject, updatedAtMs: Long): SyncProfile? {
        val x = JSONObject(extra.toString())
        when {
            pending != null -> x.put("photo", pending)
            photo != null -> x.put("photo", JSONObject().put("sha256", photo.first).put("size", photo.second).put("mime", "image/jpeg"))
            else -> x.put("photo", JSONObject.NULL)
        }
        if (links != null) x.put("links", links)
        if (name == null && photo == null && pending == null && links == null && x.length() <= 1) return null
        return SyncProfile(name, updatedAtMs, x)
    }
}
