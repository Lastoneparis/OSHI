package com.oshi.messenger.network.v2.devsync

import java.io.File

/**
 * A complete [DevSyncStore] held in memory: the reference behaviour the platform adapters mirror,
 * and the store the JVM tests (Android and Desktop) sync between two engines.
 */
class InMemoryDevSyncStore(
    /** Groups this device "has". Group messages for other groups are skipped (export §6). */
    groups: Collection<SyncGroup> = emptyList(),
) : DevSyncStore {

    private val convs = LinkedHashMap<String, SyncConversation>()
    private val msgs = LinkedHashMap<String, LinkedHashMap<String, SyncMessage>>() // conv -> idKey -> msg
    val media = HashMap<String, ByteArray>()                                          // sha -> bytes
    private val contactMap = LinkedHashMap<String, SyncContact>()
    private val groupMap = LinkedHashMap<String, SyncGroup>().apply { groups.forEach { put(it.groupId, it) } }
    private var profileValue: SyncProfile? = null
    private val readMap = LinkedHashMap<String, SyncReadState>()
    val attached = ArrayList<Triple<String, String, String>>() // conv, id, sha

    @Synchronized
    fun add(conv: SyncConversation, vararg messages: SyncMessage) {
        convs[conv.id] = conv
        val m = msgs.getOrPut(conv.id) { LinkedHashMap() }
        messages.forEach { m[it.idKey] = it.copy(conversation = conv.id) }
    }

    @Synchronized
    fun addMedia(bytes: ByteArray): String {
        val sha = DevSyncCrypto.hex(DevSyncCrypto.sha256(bytes))
        media[sha] = bytes
        return sha
    }

    @Synchronized fun all(): List<SyncMessage> = msgs.values.flatMap { it.values }
    @Synchronized fun get(convId: String, id: String): SyncMessage? = msgs[convId]?.get(SyncJson.asciiLower(id))
    @Synchronized fun count(): Int = msgs.values.sumOf { it.size }

    @Synchronized override fun conversations(): List<SyncConversation> =
        convs.values.filter { (msgs[it.id]?.size ?: 0) > 0 }

    @Synchronized override fun messages(conversationId: String): List<SyncMessage> =
        msgs[conversationId]?.values?.toList() ?: emptyList()

    @Synchronized override fun messagesByIds(conversationId: String, ids: Collection<String>): List<SyncMessage> {
        val m = msgs[conversationId] ?: return emptyList()
        return ids.mapNotNull { m[SyncJson.asciiLower(it)] }
    }

    @Synchronized
    override fun apply(conversation: SyncConversation, inserts: List<SyncMessage>, updates: List<SyncMessage>): ApplyResult {
        if (conversation.kind == SyncConversation.KIND_GROUP && conversation.groupId !in groupMap) {
            return ApplyResult(skippedNoGroup = inserts.size + updates.size)
        }
        convs.putIfAbsent(conversation.id, conversation)
        val m = msgs.getOrPut(conversation.id) { LinkedHashMap() }
        var ins = 0
        for (x in inserts) if (m.putIfAbsent(x.idKey, x) == null) ins++
        var upd = 0
        for (x in updates) if (m.containsKey(x.idKey)) { m[x.idKey] = x; upd++ }
        return ApplyResult(inserted = ins, updated = upd)
    }

    @Synchronized override fun openMedia(sha256: String): MediaSource? = media[sha256]?.let { BytesMediaSource(it) }

    @Synchronized override fun attachMedia(conversationId: String, messageId: String, sha256: String, file: File): Boolean {
        media[sha256] = file.readBytes()
        attached += Triple(conversationId, messageId, sha256)
        return true
    }

    @Synchronized override fun contacts(): List<SyncContact> = contactMap.values.toList()
    @Synchronized override fun putContact(contact: SyncContact) { contactMap[contact.publicKey] = contact }
    @Synchronized override fun groups(): List<SyncGroup> = groupMap.values.toList()
    @Synchronized override fun putGroup(group: SyncGroup) { groupMap[group.groupId] = group }
    @Synchronized override fun profile(): SyncProfile? = profileValue
    @Synchronized override fun putProfile(profile: SyncProfile) { profileValue = profile }
    @Synchronized override fun readStates(): List<SyncReadState> = readMap.values.toList()
    @Synchronized override fun putReadState(state: SyncReadState) { readMap[state.conv] = state }
}
