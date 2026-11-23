package com.oshi.messenger

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.ui.screens.MediaGalleryProjection
import com.oshi.messenger.util.AttachmentFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two fixes from the 2026-08-24 feature check that have pure logic to pin:
 *
 *  * **the group media gallery**, which read the Room `group_messages` table — a
 *    table nothing in the app writes — and was therefore always empty; and
 *  * **the attachment filename**, which never reached the wire, so a document the
 *    sender picked as `Contrat-2026.pdf` arrived as `<messageId>.dat` on an iPhone
 *    AND on another Android.
 *
 * Every input below is a hand-written literal. Nothing here is produced by the code
 * under test, so a test cannot agree with a bug by constructing its own evidence.
 */
class MediaGalleryAndAttachmentNameTest {

    // =====================================================================
    // MediaGalleryProjection
    // =====================================================================

    private fun groupRow(
        id: String,
        type: MediaType?,
        ts: Long,
        data: ByteArray? = byteArrayOf(1, 2, 3),
        deleted: Boolean = false
    ) = GroupMessage(
        id = id,
        groupId = "G1",
        senderPublicKey = "PEER",
        senderName = "Alice",
        content = "",
        timestamp = ts,
        isFromMe = false,
        mediaType = type,
        mediaData = data,
        isDeleted = deleted
    )

    private fun directRow(
        id: String,
        type: MediaType?,
        ts: Long,
        data: ByteArray? = byteArrayOf(9),
        deleted: Boolean = false,
        deletedForEveryone: Boolean = false
    ) = Message(
        id = id,
        senderPublicKey = "PEER",
        recipientPublicKey = "ME",
        content = "",
        timestamp = ts,
        isFromMe = false,
        mediaType = type,
        mediaData = data,
        isDeleted = deleted,
        isDeletedForEveryone = deletedForEveryone
    )

    /**
     * The whole of the group-gallery bug: photos exist in the group's message list,
     * and the grid must show them. Before the fix this list came from a different,
     * always-empty table and the assertion below could only ever have seen `0`.
     */
    @Test
    fun `group photos and videos reach the grid`() {
        val items = MediaGalleryProjection.fromGroupMessages(
            listOf(
                groupRow("a", MediaType.IMAGE, ts = 100),
                groupRow("b", MediaType.VIDEO, ts = 200)
            )
        )
        assertEquals(2, items.size)
        assertEquals(setOf("a", "b"), items.map { it.messageId }.toSet())
    }

    @Test
    fun `newest group media comes first`() {
        val items = MediaGalleryProjection.fromGroupMessages(
            listOf(
                groupRow("old", MediaType.IMAGE, ts = 100),
                groupRow("new", MediaType.IMAGE, ts = 900),
                groupRow("mid", MediaType.IMAGE, ts = 500)
            )
        )
        assertEquals(listOf("new", "mid", "old"), items.map { it.messageId })
    }

    /** A gallery is images and video. Audio, documents and plain text are not media here. */
    @Test
    fun `only images and videos are gallery items`() {
        val items = MediaGalleryProjection.fromGroupMessages(
            listOf(
                groupRow("img", MediaType.IMAGE, ts = 1),
                groupRow("vid", MediaType.VIDEO, ts = 2),
                groupRow("aud", MediaType.AUDIO, ts = 3),
                groupRow("doc", MediaType.DOCUMENT, ts = 4),
                groupRow("txt", null, ts = 5)
            )
        )
        assertEquals(setOf("img", "vid"), items.map { it.messageId }.toSet())
    }

    /** `isVideo` drives the play badge and the viewer's mode — it must follow the type. */
    @Test
    fun `the video flag follows the media type`() {
        val items = MediaGalleryProjection.fromGroupMessages(
            listOf(groupRow("v", MediaType.VIDEO, ts = 1), groupRow("i", MediaType.IMAGE, ts = 2))
        )
        assertTrue(items.first { it.messageId == "v" }.isVideo)
        assertTrue(!items.first { it.messageId == "i" }.isVideo)
    }

    /** A row whose blob was never hydrated has nothing to draw; a cell would be blank. */
    @Test
    fun `a row with no bytes is not an item`() {
        assertTrue(
            MediaGalleryProjection.fromGroupMessages(
                listOf(groupRow("empty", MediaType.IMAGE, ts = 1, data = null))
            ).isEmpty()
        )
    }

    /** Deleting a photo must remove it from the gallery too, not only from the thread. */
    @Test
    fun `deleted media never reappears in the grid`() {
        assertTrue(
            MediaGalleryProjection.fromGroupMessages(
                listOf(groupRow("gone", MediaType.IMAGE, ts = 1, deleted = true))
            ).isEmpty()
        )
        assertTrue(
            MediaGalleryProjection.fromMessages(
                listOf(directRow("gone", MediaType.IMAGE, ts = 1, deleted = true))
            ).isEmpty()
        )
        assertTrue(
            MediaGalleryProjection.fromMessages(
                listOf(directRow("burned", MediaType.IMAGE, ts = 1, deletedForEveryone = true))
            ).isEmpty()
        )
    }

    /** The 1-on-1 branch was never broken; pin it so the shared refactor cannot break it. */
    @Test
    fun `the one-on-one projection still selects and orders media`() {
        val items = MediaGalleryProjection.fromMessages(
            listOf(
                directRow("old", MediaType.IMAGE, ts = 10),
                directRow("doc", MediaType.DOCUMENT, ts = 20),
                directRow("new", MediaType.VIDEO, ts = 30)
            )
        )
        assertEquals(listOf("new", "old"), items.map { it.messageId })
    }

    // =====================================================================
    // AttachmentFileName
    // =====================================================================

    /** The bug itself: the picked name must be what goes on the wire. */
    @Test
    fun `a known filename is what travels`() {
        assertEquals(
            "Contrat-2026.pdf",
            AttachmentFileName.forWire("msg-123", "dat", "Contrat-2026.pdf")
        )
    }

    /**
     * Camera photos and voice notes never had a user-visible name, so the synthetic
     * form must survive — that is the shape every existing message on the wire uses.
     */
    @Test
    fun `with no name the synthetic id form is kept`() {
        assertEquals("msg-123.jpg", AttachmentFileName.forWire("msg-123", "jpg", null))
        assertEquals("msg-123.jpg", AttachmentFileName.forWire("msg-123", "jpg", ""))
        assertEquals("msg-123.jpg", AttachmentFileName.forWire("msg-123", "jpg", "   "))
    }

    /**
     * The receiver hands this string to `java.io.File(oshiDir, name)` on API < 29
     * (`ChatScreen.saveDocumentToDownloads`), so a traversal must not survive — in
     * either direction.
     */
    @Test
    fun `path traversal is reduced to its leaf`() {
        assertEquals("passwd", AttachmentFileName.sanitize("../../../etc/passwd"))
        assertEquals("evil.pdf", AttachmentFileName.sanitize("/absolute/evil.pdf"))
        assertEquals("evil.pdf", AttachmentFileName.sanitize("..\\..\\windows\\evil.pdf"))
    }

    /** A name that is ONLY traversal leaves nothing behind, and must not become "..". */
    @Test
    fun `a name made only of dots and separators is rejected`() {
        assertNull(AttachmentFileName.sanitize(".."))
        assertNull(AttachmentFileName.sanitize("../"))
        assertNull(AttachmentFileName.sanitize("/"))
        assertNull(AttachmentFileName.sanitize("   "))
        assertNull(AttachmentFileName.sanitize(null))
    }

    /** A NUL truncates the name inside the platform layer; C0 controls go with it. */
    @Test
    fun `control characters are stripped`() {
        assertEquals("report.pdf", AttachmentFileName.sanitize("rep\u0000ort.pdf"))
        assertEquals("report.pdf", AttachmentFileName.sanitize("rep\nort.pdf"))
        assertEquals("report.pdf", AttachmentFileName.sanitize("rep\tort.pdf"))
    }

    /** A traversal in the REQUESTED name must not reach the wire either. */
    @Test
    fun `forWire sanitizes before it emits`() {
        assertEquals("passwd", AttachmentFileName.forWire("msg-9", "dat", "../../etc/passwd"))
        assertEquals("msg-9.dat", AttachmentFileName.forWire("msg-9", "dat", "../.."))
    }

    /** Long names are truncated but keep the extension — it picks the icon and MIME. */
    @Test
    fun `an over-long name keeps its extension`() {
        val long = "a".repeat(400) + ".pdf"
        val out = AttachmentFileName.sanitize(long)!!
        assertTrue("was ${out.length}", out.length <= 120)
        assertTrue("lost the extension: $out", out.endsWith(".pdf"))
    }

    /** Ordinary names, including accents and spaces, must pass through untouched. */
    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("Rapport final (2026).pdf", AttachmentFileName.sanitize("Rapport final (2026).pdf"))
        assertEquals("données.csv", AttachmentFileName.sanitize("données.csv"))
    }
}
