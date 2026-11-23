package com.oshi.messenger.service

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.MessageExportCrypto
import com.oshi.messenger.service.media.MediaAttachmentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted message export / import (`.oshiexport`, see [MessageExportCrypto]).
 *
 * SECURITY (2026-09-22). This replaces the old Settings → Export Messages, which
 * wrote EVERY decrypted 1:1 message as plaintext JSON into
 * `getExternalFilesDir(null)/exports/` and never deleted it. The export is now
 * sealed with a key derived from the account's X25519 encryption private key —
 * the one the recovery key restores — so only the same OSHI account can read it,
 * on this device or on another one where that account was restored.
 *
 * Import merges rows straight into Room through the DAOs, deliberately bypassing
 * [com.oshi.messenger.data.repository.MessageRepository]: no notification, no
 * delivery/read receipt, no profile / reaction / control-payload processing.
 * Rows already present (same id) are left untouched.
 */
@Singleton
class MessageExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val cryptoManager: CryptoManager,
) {
    companion object {
        private const val TAG = "MessageExport"
        const val PLATFORM = "android"
        const val EXPORT_DIR = "exports"
        private const val KIND_DIRECT = "direct"
        private const val KIND_GROUP = "group"

        /**
         * Total raw media bytes carried in one export. The container is one GCM
         * message built in memory, so a multi-gigabyte video history cannot go in
         * whole; past this budget messages are exported WITHOUT their media and the
         * count is reported to the user.
         */
        const val MEDIA_BUDGET_BYTES = 48L * 1024 * 1024

        /** Mirror of MessageRepository.MAX_INLINE_MEDIA_BYTES (CursorWindow limit). */
        private const val MAX_INLINE_MEDIA_BYTES = 1_500_000

        /** Refuse to read absurdly large files into memory. */
        private const val MAX_IMPORT_BYTES = 256L * 1024 * 1024

        /** Exports left in cache longer than this are swept. */
        const val STALE_EXPORT_MS = 5 * 60 * 1000L
    }

    data class ExportOutcome(val uri: Uri, val file: File, val messageCount: Int, val mediaSkipped: Int)
    data class ImportOutcome(
        val imported: Int,
        val alreadyPresent: Int,
        val invalid: Int,
        /** __EXPORT_V2_INTEROP_2026_09_22__ group messages whose group is not on this device. */
        val skippedGroups: Int = 0,
        /** Messages of a conversation kind this build does not know (newer writer, desktop-only). */
        val skippedUnknown: Int = 0,
    )

    /** Import refusals the UI maps to a clear message. */
    sealed class ImportError(message: String) : Exception(message) {
        class NoAccountKey : ImportError("no account key on this device")
        class NotAnExport : ImportError("not an OSHI export")
        class UnsupportedVersion : ImportError("export made by a newer OSHI")
        class WrongAccount : ImportError("export belongs to another OSHI account")
        class Damaged : ImportError("export is damaged or has been modified")
        class OtherPlatform(val platform: String) : ImportError("export made by OSHI for $platform")
        class Unreadable : ImportError("file could not be read")
    }

    private val directStore by lazy { MediaAttachmentStore(context.filesDir) }
    private val groupStore by lazy { MediaAttachmentStore(GroupManager.groupMediaRoot(context.filesDir)) }

    private fun exportDir() = File(context.cacheDir, EXPORT_DIR)

    // ------------------------------------------------------------------ export

    suspend fun export(): ExportOutcome = withContext(Dispatchers.IO) {
        sweepStaleExports(0)
        purgeLegacyPlaintextExports()
        val privateKey = try {
            cryptoManager.exportPrivateKeyBytes()
        } catch (e: Exception) {
            throw IllegalStateException("No account key on this device")
        }

        // __EXPORT_V2_INTEROP_2026_09_22__ The platform-neutral VERSION 2 payload
        // (docs/OSHI_EXPORT_FORMAT.md), importable on iOS and Desktop too. Version 1 (Room rows
        // as JSON) is still READ below, never written.
        val selfKey = ExportV2.canonicalKey(cryptoManager.getPublicKeyString())
        val messageDao = database.messageDao()
        val groupDao = database.groupDao()
        val conversations = LinkedHashMap<String, ExportV2.Conversation>()
        val messages = ArrayList<ExportV2.Msg>()
        var count = 0
        var mediaBudget = ExportV2.MEDIA_BUDGET_BYTES
        var mediaSkipped = 0

        fun mediaFor(id: String, inline: ByteArray?, type: MediaType?, viewOnce: Boolean, fileName: String?, store: MediaAttachmentStore): ExportV2.Media? {
            if (type == null) return null
            // View-once media never leaves the device.
            if (viewOnce) return AndroidExportV2.media(type, fileName, null, "viewOnce")
            val bytes = inline ?: runCatching { store.load(id) }.getOrNull()
                ?: return AndroidExportV2.media(type, fileName, null, "unavailable")
            if (bytes.size > mediaBudget) {
                mediaSkipped++
                return AndroidExportV2.media(type, fileName, null, "sizeBudget")
            }
            mediaBudget -= bytes.size
            return AndroidExportV2.media(type, fileName, bytes, null)
        }

        // 1:1 — every non-deleted row, whichever side it is keyed on.
        val keys = (messageDao.distinctSenderKeys() + messageDao.distinctRecipientKeys()).toSet()
        val seen = HashSet<String>()
        val peers = HashSet<String>()
        for (k in keys) {
            for (m in messageDao.getMessagesForContact(k).first()) {
                if (!seen.add(m.id) || AndroidExportV2.isControl(m.content)) continue
                val media = mediaFor(m.id, m.mediaData, m.mediaType, m.isViewOnce, null, directStore)
                val (conv, msg) = AndroidExportV2.direct(m, media)
                if (conv.peerPublicKey == selfKey) continue
                conversations.putIfAbsent(conv.id, conv)
                conv.peerPublicKey?.let { peers.add(it) }
                messages += msg
                count++
            }
        }

        // Groups.
        for (g in groupDao.getAllGroups().first()) {
            for (m in groupDao.getGroupMessages(g.id).first()) {
                if (m.isDeleted || !seen.add(m.id)) continue
                val media = mediaFor(m.id, m.mediaData, m.mediaType, false, m.mediaFileName, groupStore)
                val (conv, msg) = AndroidExportV2.group(m, g.name, media)
                conversations.putIfAbsent(conv.id, conv)
                messages += msg
                count++
            }
        }

        // Aliases of 1:1 peers, so the conversations are named on the other device. No pictures.
        val contacts = database.contactDao().getEveryContact()
            .filter { ExportV2.canonicalKey(it.publicKey) in peers }
            .map { ExportV2.Contact(ExportV2.canonicalKey(it.publicKey), it.alias.ifBlank { null }) }

        val plaintext = ExportV2.encode(ExportV2.Payload(
            platform = PLATFORM,
            exportedAtMs = System.currentTimeMillis(),
            accountPublicKey = selfKey,
            contacts = contacts,
            conversations = conversations.values.toList(),
            messages = messages,
        ))
        val sealed = try {
            MessageExportCrypto.seal(plaintext, privateKey)
        } finally {
            privateKey.fill(0)
            plaintext.fill(0)
        }

        val dir = exportDir().apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val file = File(dir, "OSHI_Messages_$stamp.${MessageExportCrypto.FILE_EXTENSION}")
        file.writeBytes(sealed)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Log.d(TAG, "Exported $count messages (${sealed.size} B, media skipped: $mediaSkipped)")
        ExportOutcome(uri, file, count, mediaSkipped)
    }

    /** Delete exports older than [olderThanMs] from the cache (0 = all). */
    fun sweepStaleExports(olderThanMs: Long = STALE_EXPORT_MS) {
        val now = System.currentTimeMillis()
        exportDir().listFiles()?.forEach { f ->
            if (olderThanMs == 0L || now - f.lastModified() >= olderThanMs) f.delete()
        }
    }

    /**
     * The old export wrote plaintext JSON (and the unused CalendarExportHelper
     * writers plaintext .txt/.csv/.json) into app-specific EXTERNAL storage and
     * never removed them. Delete any that are still there.
     */
    fun purgeLegacyPlaintextExports(): Int {
        val dir = File(context.getExternalFilesDir(null) ?: return 0, "exports")
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("OSHI_Messages_Export_") || f.name.startsWith("OSHI_Chat_")) {
                if (f.delete()) n++
            }
        }
        if (n > 0) Log.i(TAG, "Deleted $n legacy plaintext export file(s)")
        return n
    }

    // ------------------------------------------------------------------ import

    suspend fun import(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > MAX_IMPORT_BYTES) throw ImportError.Unreadable()
                    out.write(buf, 0, r)
                }
                out.toByteArray()
            } ?: throw ImportError.Unreadable()
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError.Unreadable()
        }

        val privateKey = try {
            cryptoManager.exportPrivateKeyBytes()
        } catch (e: Exception) {
            throw ImportError.NoAccountKey()
        }
        val plaintext = try {
            MessageExportCrypto.open(bytes, privateKey)
        } catch (e: MessageExportCrypto.OpenError.NotAnExport) {
            throw ImportError.NotAnExport()
        } catch (e: MessageExportCrypto.OpenError.UnsupportedVersion) {
            throw ImportError.UnsupportedVersion()
        } catch (e: MessageExportCrypto.OpenError.WrongAccount) {
            throw ImportError.WrongAccount()
        } catch (e: MessageExportCrypto.OpenError.Damaged) {
            throw ImportError.Damaged()
        } finally {
            privateKey.fill(0)
        }

        val root = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ImportError.Damaged()
        } finally {
            plaintext.fill(0)
        }
        // __EXPORT_V2_INTEROP_2026_09_22__ v2 from ANY platform; v1 only from Android (a v1
        // payload from iOS/Desktop holds that platform's native objects).
        val version = root.optInt("version", -1)
        if (version == ExportV2.VERSION) {
            val parsed = try { ExportV2.parse(root) } catch (e: Exception) { throw ImportError.Damaged() }
            return@withContext mergeV2(parsed)
        }
        if (version > ExportV2.VERSION) throw ImportError.UnsupportedVersion()
        val platform = root.optString("platform", "")
        if (platform != PLATFORM) throw ImportError.OtherPlatform(platform.ifEmpty { "?" })
        if (version != 1) throw ImportError.UnsupportedVersion()

        mergeInto(root)
    }

    /**
     * Merge a v2 payload straight into Room — same rules as [mergeInto]: no repository, so no
     * notification, no receipt, no profile/reaction/control processing; an id already present
     * (compared case-insensitively — an iPhone upper-cases the UUIDs it round-trips) is left
     * untouched. Group messages are imported only into groups this device holds.
     */
    private suspend fun mergeV2(parsed: ExportV2.Parsed): ImportOutcome {
        val selfKey = ExportV2.canonicalKey(cryptoManager.getPublicKeyString())
        val messageDao = database.messageDao()
        val groupDao = database.groupDao()
        val contactDao = database.contactDao()
        val payload = parsed.payload
        val convs = payload.conversations.associateBy { it.id }
        val aliases = payload.contacts.associate { it.publicKey to it.alias }
        var imported = 0
        var present = 0
        var invalid = parsed.invalid
        var skippedGroups = 0
        val peers = HashSet<String>()
        val groupCache = HashMap<String, com.oshi.messenger.data.model.Group?>()

        suspend fun directExists(id: String) =
            messageDao.getMessageById(id) != null || messageDao.getMessageById(id.lowercase()) != null ||
                messageDao.getMessageById(id.uppercase()) != null

        suspend fun groupMessageExists(id: String) =
            groupDao.getMessageById(id) != null || groupDao.getMessageById(id.lowercase()) != null ||
                groupDao.getMessageById(id.uppercase()) != null

        suspend fun localGroup(gid: String) = groupCache.getOrPut(gid) {
            groupDao.getGroupById(gid) ?: groupDao.getGroupById(GroupManager.canonicalGroupId(gid))
                ?: groupDao.getGroupById(gid.lowercase())
        }

        for (m in payload.messages) {
            try {
                if (AndroidExportV2.isControl(m.text)) { invalid++; continue }
                val c = convs[m.conversation] ?: run { invalid++; null } ?: continue
                val bytes = m.media?.data
                when (c.kind) {
                    ExportV2.KIND_DIRECT -> {
                        val peer = c.peerPublicKey ?: run { invalid++; null } ?: continue
                        if (directExists(m.id)) { present++; continue }
                        var inline = bytes
                        if (bytes != null && bytes.size > MAX_INLINE_MEDIA_BYTES) {
                            inline = null
                            if (!directStore.save(m.id, bytes)) Log.w(TAG, "media for ${m.id.take(8)} not saved")
                        }
                        messageDao.insert(AndroidExportV2.toDirect(m, peer, selfKey, inline))
                        peers.add(peer)
                        imported++
                    }
                    ExportV2.KIND_GROUP -> {
                        val group = localGroup(c.groupId ?: "") ?: run { skippedGroups++; null } ?: continue
                        if (groupMessageExists(m.id)) { present++; continue }
                        var inline = bytes
                        if (bytes != null && bytes.size > MAX_INLINE_MEDIA_BYTES) {
                            inline = null
                            if (!groupStore.save(m.id, bytes)) Log.w(TAG, "group media for ${m.id.take(8)} not saved")
                        }
                        groupDao.insertGroupMessage(
                            AndroidExportV2.toGroup(m, group.id, selfKey, aliases[m.senderPublicKey], inline)
                        )
                        imported++
                    }
                    else -> invalid++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed exported message ${m.id.take(8)}: ${e.message}")
                invalid++
            }
        }

        // A conversation only shows once its peer has a contact row. Create the missing ones
        // from the exported alias; never overwrite an existing contact.
        for (pk in peers) {
            if (contactDao.getContactByPublicKey(pk) == null) {
                val alias = aliases[pk]?.takeIf { it.isNotBlank() } ?: pk.take(8)
                contactDao.insert(Contact(publicKey = pk, alias = alias))
            }
        }

        Log.i(TAG, "Import v2 (${payload.platform}): $imported new, $present present, $invalid invalid, " +
            "$skippedGroups without their group, ${parsed.skippedUnknownKind} unknown kind")
        return ImportOutcome(imported, present, invalid, skippedGroups, parsed.skippedUnknownKind)
    }

    private suspend fun mergeInto(root: JSONObject): ImportOutcome {
        val messageDao = database.messageDao()
        val groupDao = database.groupDao()
        val contactDao = database.contactDao()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        var imported = 0
        var present = 0
        var invalid = 0
        val peers = HashSet<String>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: run { invalid++; null } ?: continue
            try {
                val id = o.getString("id")
                val mediaType = o.optStringOrNull("mediaType")?.let { name ->
                    runCatching { MediaType.valueOf(name) }.getOrNull() ?: MediaType.fromWireLabel(name)
                }
                val media = o.optStringOrNull("mediaData")?.let { Base64.decode(it, Base64.NO_WRAP) }
                val isFromMe = o.getBoolean("isFromMe")
                when (o.optString("kind")) {
                    KIND_DIRECT -> {
                        if (messageDao.getMessageById(id) != null) { present++; continue }
                        var inline = media
                        if (media != null && media.size > MAX_INLINE_MEDIA_BYTES) {
                            inline = null
                            if (!directStore.save(id, media)) Log.w(TAG, "media for ${id.take(8)} not saved")
                        }
                        val sender = o.getString("senderPublicKey")
                        val recipient = o.getString("recipientPublicKey")
                        messageDao.insert(Message(
                            id = id,
                            senderPublicKey = sender,
                            recipientPublicKey = recipient,
                            content = o.optString("content", ""),
                            timestamp = o.getLong("timestamp"),
                            isFromMe = isFromMe,
                            // Imported history is history: never unread, so opening the
                            // chat does not emit read receipts for it.
                            isRead = true,
                            isDelivered = o.optBoolean("isDelivered", isFromMe),
                            deliveryMethod = runCatching {
                                DeliveryMethod.valueOf(o.optString("deliveryMethod"))
                            }.getOrDefault(DeliveryMethod.VPS),
                            mediaType = mediaType,
                            mediaData = inline,
                            replyToId = o.optStringOrNull("replyToId"),
                            editedAt = o.optLongOrNull("editedAt"),
                            editedContent = o.optStringOrNull("editedContent"),
                            isDeletedForEveryone = o.optBoolean("isDeletedForEveryone", false),
                            isViewOnce = o.optBoolean("isViewOnce", false),
                            viewOnceOpened = o.optBoolean("viewOnceOpened", false),
                            isFailed = o.optBoolean("isFailed", false),
                        ))
                        peers.add(if (isFromMe) recipient else sender)
                        imported++
                    }
                    KIND_GROUP -> {
                        if (groupDao.getMessageById(id) != null) { present++; continue }
                        var inline = media
                        if (media != null && media.size > MAX_INLINE_MEDIA_BYTES) {
                            inline = null
                            if (!groupStore.save(id, media)) Log.w(TAG, "group media for ${id.take(8)} not saved")
                        }
                        val reactions = o.optJSONObject("reactions")?.let { r ->
                            r.keys().asSequence().associateWith { r.getString(it) }
                        } ?: emptyMap()
                        groupDao.insertGroupMessage(GroupMessage(
                            id = id,
                            groupId = o.getString("groupId"),
                            senderPublicKey = o.getString("senderPublicKey"),
                            senderName = o.optString("senderName", ""),
                            content = o.optString("content", ""),
                            timestamp = o.getLong("timestamp"),
                            isFromMe = isFromMe,
                            isRead = true,
                            mediaType = mediaType,
                            mediaData = inline,
                            mediaFileName = o.optStringOrNull("mediaFileName"),
                            mediaDurationMs = o.optLongOrNull("mediaDurationMs"),
                            replyToId = o.optStringOrNull("replyToId"),
                            reactions = reactions,
                            editedContent = o.optStringOrNull("editedContent"),
                            editedAt = o.optLongOrNull("editedAt"),
                            botId = o.optStringOrNull("botId"),
                        ))
                        imported++
                    }
                    else -> invalid++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed exported message #$i: ${e.message}")
                invalid++
            }
        }

        // A conversation only shows once its peer has a contact row. Create the
        // missing ones from the exported alias; never overwrite an existing contact.
        val aliases = HashMap<String, String>()
        root.optJSONArray("contacts")?.let { cs ->
            for (i in 0 until cs.length()) {
                val c = cs.optJSONObject(i) ?: continue
                val pk = c.optStringOrNull("publicKey") ?: continue
                aliases[pk] = c.optString("alias", "")
            }
        }
        for (pk in peers) {
            if (contactDao.getContactByPublicKey(pk) == null) {
                val alias = aliases[pk]?.takeIf { it.isNotBlank() } ?: pk.take(8)
                contactDao.insert(Contact(publicKey = pk, alias = alias))
            }
        }

        Log.i(TAG, "Import: $imported new, $present already present, $invalid invalid")
        return ImportOutcome(imported, present, invalid)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
