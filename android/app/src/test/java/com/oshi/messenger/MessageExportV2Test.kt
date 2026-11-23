package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.network.v2.MessageExportCrypto
import com.oshi.messenger.service.AndroidExportV2
import com.oshi.messenger.service.ExportV2
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __EXPORT_V2_INTEROP_2026_09_22__ The platform-neutral export payload (version 2).
 *
 * `docs/fixtures/export_v2_sample.json` is the SAME file OSHI-Desktop's `MessageExportV2Test`
 * and the iOS storage self-test parse. Each platform mapping it to its own model is the proof
 * that an export made anywhere imports everywhere.
 */
class MessageExportV2Test {

    private val katPriv = ByteArray(32) { (it + 1).toByte() }
    private val me = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="
    private val bob = "gbY32PzSxtpjWeaWMROhFw3nleS3JbhNHgtM/Z7FjOk="
    private val carol = "TCbZB0wn2J7eWScMCsFLceBxsVI5UZ91R0svO6Y0gfU="
    private val gid = "5B0E7C1A-3F2D-4E8B-9A61-2C7D4F0B8E13"
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
    )

    private fun fixture(): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "docs/fixtures/export_v2_sample.json")
            if (f.isFile) return f.readText()
            d = d.parentFile
        }
        error("docs/fixtures/export_v2_sample.json not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun sharedFixture_parsesAndMapsToRoomEntities() {
        assertEquals(me, Base64.getEncoder().encodeToString(MessageExportCrypto.publicKeyFor(katPriv)))
        // Sealed and reopened with the account key, as a real import does.
        val sealed = MessageExportCrypto.seal(fixture().toByteArray(Charsets.UTF_8), katPriv)
        val root = JSONObject(String(MessageExportCrypto.open(sealed, katPriv), Charsets.UTF_8))
        val parsed = ExportV2.parse(root)
        assertEquals(8, parsed.payload.messages.size)
        assertEquals(1, parsed.invalid)
        assertEquals(1, parsed.skippedUnknownKind)
        assertEquals(me, parsed.payload.accountPublicKey)
        assertEquals(mapOf(bob to "Bob", carol to "Carol"), parsed.payload.contacts.associate { it.publicKey to it.alias })

        val convs = parsed.payload.conversations.associateBy { it.id }
        val direct = parsed.payload.messages.filter { convs.getValue(it.conversation).kind == ExportV2.KIND_DIRECT }
            .map { AndroidExportV2.toDirect(it, convs.getValue(it.conversation).peerPublicKey!!, me, it.media?.data) }
            .associateBy { it.id }
        val group = parsed.payload.messages.filter { convs.getValue(it.conversation).kind == ExportV2.KIND_GROUP }
            .map { AndroidExportV2.toGroup(it, gid, me, "Carol", it.media?.data) }
            .associateBy { it.id }
        assertEquals(6, direct.size)
        assertEquals(2, group.size)

        val m1 = direct.getValue("6F1C2A3B-0000-4000-8000-000000000001")
        assertTrue(m1.isFromMe)
        assertEquals(me, m1.senderPublicKey)
        assertEquals(bob, m1.recipientPublicKey)
        assertEquals("Hello Bob", m1.content)
        assertEquals(1_789_891_200_000L, m1.timestamp)
        assertTrue(m1.isDelivered)

        val m2 = direct.getValue("6f1c2a3b-0000-4000-8000-000000000002")
        assertFalse(m2.isFromMe)
        assertEquals(bob, m2.senderPublicKey)
        assertEquals(me, m2.recipientPublicKey)
        assertEquals("Hi! été 👋", m2.content)
        assertEquals("6F1C2A3B-0000-4000-8000-000000000001", m2.replyToId)
        assertTrue("imported history must never be unread", m2.isRead)
        assertEquals(1_789_891_290_250L, m2.timestamp)

        val m3 = direct.getValue("6F1C2A3B-0000-4000-8000-000000000003")
        assertEquals(MediaType.IMAGE, m3.mediaType)
        assertArrayEquals(png, m3.mediaData)

        val m4 = direct.getValue("6F1C2A3B-0000-4000-8000-000000000004")
        assertEquals("See you at 9", m4.content)
        assertEquals("See you at 10", m4.editedContent)
        assertEquals(1_789_891_440_000L, m4.editedAt)

        assertTrue(direct.getValue("6F1C2A3B-0000-4000-8000-000000000005").isDeletedForEveryone)

        val m6 = direct.getValue("6F1C2A3B-0000-4000-8000-000000000006")
        assertTrue(m6.isViewOnce)
        assertTrue(m6.viewOnceOpened)
        assertTrue(m6.isFailed)
        assertEquals(MediaType.VIDEO, m6.mediaType)
        assertNull(m6.mediaData)

        val g1 = group.getValue("7A000000-0000-4000-8000-000000000001")
        assertEquals(gid, g1.groupId)
        assertEquals(carol, g1.senderPublicKey)
        assertEquals("Carol", g1.senderName)
        assertEquals("group hello", g1.content)
        assertEquals(mapOf(me to "👍"), g1.reactions)
        val g2 = group.getValue("7A000000-0000-4000-8000-000000000002")
        assertTrue(g2.isFromMe)
        assertEquals(me, g2.senderPublicKey)
        assertEquals("7A000000-0000-4000-8000-000000000001", g2.replyToId)
    }

    @Test
    fun roundTrip_roomEntitiesToV2AndBack() {
        val direct = listOf(
            Message(id = "a1", senderPublicKey = me, recipientPublicKey = bob, content = "hello",
                timestamp = 1_789_891_200_123L, isFromMe = true, isRead = true, isDelivered = true, replyToId = "a0"),
            Message(id = "a2", senderPublicKey = bob, recipientPublicKey = me, content = "[IMAGE]",
                timestamp = 1_789_891_201_000L, isFromMe = false, isRead = true, mediaType = MediaType.IMAGE, mediaData = png),
            Message(id = "a3", senderPublicKey = bob, recipientPublicKey = me, content = "report.pdf",
                timestamp = 1_789_891_202_000L, isFromMe = false, isRead = true, mediaType = MediaType.DOCUMENT, mediaData = png),
            Message(id = "a4", senderPublicKey = me, recipientPublicKey = bob, content = "typo",
                timestamp = 1_789_891_203_000L, isFromMe = true, isDelivered = true,
                editedContent = "fixed", editedAt = 1_789_891_300_000L),
            Message(id = "a5", senderPublicKey = me, recipientPublicKey = bob, content = "lost",
                timestamp = 1_789_891_204_000L, isFromMe = true, isFailed = true),
        )
        val groups = listOf(
            GroupMessage(id = "G1", groupId = gid, senderPublicKey = carol, senderName = "Carol", content = "hey all",
                timestamp = 1_789_891_205_000L, isFromMe = false, isRead = true, reactions = mapOf(me to "❤️")),
            GroupMessage(id = "G2", groupId = gid, senderPublicKey = me, senderName = "Me", content = "",
                timestamp = 1_789_891_206_000L, isFromMe = true, isRead = true,
                mediaType = MediaType.AUDIO, mediaData = png, mediaFileName = "note.m4a", replyToId = "G1"),
        )
        val convs = LinkedHashMap<String, ExportV2.Conversation>()
        val msgs = ArrayList<ExportV2.Msg>()
        for (m in direct) {
            val media = m.mediaType?.let { AndroidExportV2.media(it, null, m.mediaData, null) }
            val (c, x) = AndroidExportV2.direct(m, media)
            convs.putIfAbsent(c.id, c); msgs += x
        }
        for (m in groups) {
            val media = m.mediaType?.let { AndroidExportV2.media(it, m.mediaFileName, m.mediaData, null) }
            val (c, x) = AndroidExportV2.group(m, "Team", media)
            convs.putIfAbsent(c.id, c); msgs += x
        }
        val json = ExportV2.encode(ExportV2.Payload("android", 1_789_900_000_000L, me, listOf(ExportV2.Contact(bob, "Bob")), convs.values.toList(), msgs))
        val root = JSONObject(String(json, Charsets.UTF_8))
        assertEquals(2, root.getInt("version"))
        assertEquals("android", root.getString("platform"))
        val docMsg = root.getJSONArray("messages").getJSONObject(2)
        assertFalse("a document's name is not a caption", docMsg.has("text"))
        assertEquals("report.pdf", docMsg.getJSONObject("media").getString("fileName"))
        assertEquals("application/pdf", docMsg.getJSONObject("media").getString("mime"))
        assertFalse("[IMAGE] is a placeholder, not text", root.getJSONArray("messages").getJSONObject(1).has("text"))

        val parsed = ExportV2.parse(root)
        assertEquals(0, parsed.invalid)
        val pc = parsed.payload.conversations.associateBy { it.id }
        val back = parsed.payload.messages.associateBy { it.id }

        for (o in direct) {
            val v = back.getValue(o.id)
            val b = AndroidExportV2.toDirect(v, pc.getValue(v.conversation).peerPublicKey!!, me, v.media?.data)
            assertEquals(o.id, o.senderPublicKey, b.senderPublicKey)
            assertEquals(o.id, o.recipientPublicKey, b.recipientPublicKey)
            assertEquals(o.id, o.isFromMe, b.isFromMe)
            assertEquals(o.id, o.timestamp, b.timestamp)
            assertEquals(o.id, o.mediaType, b.mediaType)
            assertEquals(o.id, o.replyToId, b.replyToId)
            assertEquals(o.id, o.editedContent, b.editedContent)
            assertEquals(o.id, o.editedAt, b.editedAt)
            assertEquals(o.id, o.isFailed, b.isFailed)
            assertEquals(o.id, o.isDelivered || !o.isFromMe, b.isDelivered)
            if (o.mediaData != null) assertArrayEquals(o.id, o.mediaData, b.mediaData)
            val expectedContent = if (o.content == "[IMAGE]") "" else o.content
            assertEquals(o.id, expectedContent, b.content)
        }
        for (o in groups) {
            val v = back.getValue(o.id)
            val b = AndroidExportV2.toGroup(v, gid, me, null, v.media?.data)
            assertEquals(o.id, o.groupId, b.groupId)
            assertEquals(o.id, o.senderPublicKey, b.senderPublicKey)
            assertEquals(o.id, o.isFromMe, b.isFromMe)
            assertEquals(o.id, o.content, b.content)
            assertEquals(o.id, o.timestamp, b.timestamp)
            assertEquals(o.id, o.mediaType, b.mediaType)
            assertEquals(o.id, o.mediaFileName, b.mediaFileName)
            assertEquals(o.id, o.replyToId, b.replyToId)
            assertEquals(o.id, o.reactions, b.reactions)
            if (o.mediaData != null) assertArrayEquals(o.id, o.mediaData, b.mediaData)
        }
        assertEquals("Carol", AndroidExportV2.toGroup(back.getValue("G1"), gid, me, null, null).senderName)
    }

    @Test
    fun controlPayloadsAreNotConversation() {
        assertTrue(AndroidExportV2.isControl("📬DELIVERY_RECEIPT📬abc"))
        assertTrue(AndroidExportV2.isControl("📸PROFILE_UPDATE📸{}"))
        assertFalse(AndroidExportV2.isControl("hello 📬"))
    }
}
