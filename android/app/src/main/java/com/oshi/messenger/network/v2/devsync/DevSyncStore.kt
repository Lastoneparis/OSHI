package com.oshi.messenger.network.v2.devsync

import java.io.File
import java.io.InputStream

/**
 * The seam between the sync core and a platform's storage (Android Room, Desktop MessageStore).
 * Everything here is called on the engine's single sync thread; implementations may block.
 *
 * Contract (design §7, §8):
 *  - Only SYNCABLE rows are exposed: no control payloads, no iOS `SYSTEM` rows, no platform-local
 *    rows (welcome thread …). `desktop.local` conversations only on Desktop (the core forwards them
 *    only between two desktops).
 *  - Messages carry media METADATA only (type/fileName/mime/size/sha256/omitted); bytes are read with
 *    [openMedia] and written with [attachMedia].
 *  - [apply] writes through the import path: no notification, no receipt, no send, no control-payload
 *    processing. `read` is stored AS RECEIVED (sync mirrors state, the export forces true).
 *  - Ids are compared case-insensitively.
 */
interface DevSyncStore {

    /** Every conversation that has at least one syncable message. */
    fun conversations(): List<SyncConversation>

    /** Every syncable message of a conversation (the core computes rev / buckets from them). */
    fun messages(conversationId: String): List<SyncMessage>

    /** The messages with these ids (case-insensitive) in that conversation; absent ids are skipped. */
    fun messagesByIds(conversationId: String, ids: Collection<String>): List<SyncMessage>

    /**
     * Insert [inserts] (ids absent locally) and replace [updates] (already merged by the core) in
     * [conversation]. A group conversation whose group does not exist locally is skipped whole.
     */
    fun apply(conversation: SyncConversation, inserts: List<SyncMessage>, updates: List<SyncMessage>): ApplyResult

    /** Plaintext bytes of a media file by sha256 (lowercase hex), or null when not held. */
    fun openMedia(sha256: String): MediaSource?

    /**
     * A fetched, verified media file for message [messageId]; the store copies it in. [messageId] ==
     * [AVATAR_REF] means the picture of the group [conversationId] (`group:<UUID>`).
     */
    fun attachMedia(conversationId: String, messageId: String, sha256: String, file: File): Boolean

    fun contacts(): List<SyncContact> = emptyList()
    fun putContact(contact: SyncContact) {}

    fun groups(): List<SyncGroup> = emptyList()
    /** Insert or replace a group definition (members + keys). */
    fun putGroup(group: SyncGroup) {}

    fun profile(): SyncProfile? = null
    fun putProfile(profile: SyncProfile) {}

    fun readStates(): List<SyncReadState> = emptyList()
    fun putReadState(state: SyncReadState) {}

    companion object {
        /** Media ref id of a group picture (see [attachMedia]). */
        const val AVATAR_REF = "#avatar"
    }
}

data class ApplyResult(val inserted: Int = 0, val updated: Int = 0, val skippedNoGroup: Int = 0, val failed: Int = 0) {
    operator fun plus(o: ApplyResult) =
        ApplyResult(inserted + o.inserted, updated + o.updated, skippedNoGroup + o.skippedNoGroup, failed + o.failed)
}

interface MediaSource {
    val size: Long
    fun open(): InputStream
}

class FileMediaSource(private val file: File) : MediaSource {
    override val size: Long get() = file.length()
    override fun open(): InputStream = file.inputStream()
}

class BytesMediaSource(private val bytes: ByteArray) : MediaSource {
    override val size: Long get() = bytes.size.toLong()
    override fun open(): InputStream = bytes.inputStream()
}

/**
 * Small named JSON blobs the core persists (linked devices, pending media). Android: files in the
 * app's private dir; Desktop: sealed JSON under the data dir.
 */
interface DevSyncStateStore {
    fun read(name: String): String?
    fun write(name: String, json: String)
}

class InMemoryStateStore : DevSyncStateStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun read(name: String): String? = map[name]
    override fun write(name: String, json: String) { map[name] = json }
}

/** A plain directory of `<name>.json` files (tests, and platforms without a sealing layer). */
class FileStateStore(private val dir: File) : DevSyncStateStore {
    init { dir.mkdirs() }
    override fun read(name: String): String? = File(dir, "$name.json").takeIf { it.isFile }?.readText()
    override fun write(name: String, json: String) {
        val tmp = File(dir, "$name.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(File(dir, "$name.json"))) {
            File(dir, "$name.json").writeText(json)
            tmp.delete()
        }
    }
}

/**
 * The per-install device key DK (design §3): a fresh X25519 private key, never exported, never
 * backed up. Android: Keystore-wrapped; Desktop: KeyVault entry.
 */
fun interface DeviceKeyProvider {
    fun loadOrCreate(): ByteArray
}
