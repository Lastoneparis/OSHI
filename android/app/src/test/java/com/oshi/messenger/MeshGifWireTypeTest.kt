package com.oshi.messenger

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.service.media.GifBytes
import com.oshi.messenger.util.AttachmentFileName
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __GIF_WIRE_TYPE_2026_09_13__ — the LAN-mesh leg.
 *
 * A GIF handed to an iPhone over the local mesh arrived as a frozen first frame and
 * saved itself as `.jpg`. The bytes on the wire were the animated GIF all along
 * (compression is gif-aware and leaves them intact, `MessageRepository.kt:2566-2568`);
 * what lied was the LABEL. Android has two media send paths, and only one of them
 * sniffed:
 *
 *  * the V2 blob/inline path asks `GifBytes.isGif(compressedData)` and emits
 *    `gif` / `image/gif` / `.gif`;
 *  * the CrossPlatformMesh path read the [MediaType] enum only — which has no GIF
 *    member and never will, because adding one costs a Room migration — and so
 *    emitted `image` and `.jpg`.
 *
 * iOS renders this envelope STRICTLY by its label (`handleCrossPlatformMediaMessage`
 * reads `mediaType`), so the label is what has to be right on the wire. The receiving
 * side on iOS now sniffs as well, but builds already in the field do not, and they are
 * exactly the builds this fix is for.
 */
class MeshGifWireTypeTest {

    /** `GIF89a` + a plausible screen descriptor. */
    private val gifBytes = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
        0x40, 0x00, 0x40, 0x00, 0x00.toByte(), 0x00, 0x00
    )

    /** JPEG SOI + JFIF. Anything but a GIF. */
    private val jpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00
    )

    // =====================================================================
    // The rule the mesh path now applies
    // =====================================================================

    @Test
    fun a_gif_on_the_mesh_path_is_labelled_gif_not_image() {
        val isGif = GifBytes.isGif(gifBytes)
        assertTrue("GIF89a header must sniff as a GIF", isGif)
        assertEquals("gif", MessageRepository.crossPlatformMeshWireType(MediaType.IMAGE, isGif))
    }

    @Test
    fun a_gif_on_the_mesh_path_gets_a_gif_extension_not_jpg() {
        val isGif = GifBytes.isGif(gifBytes)
        assertEquals("gif", MessageRepository.crossPlatformMeshExtension(MediaType.IMAGE, isGif))
        // …and the synthetic name the envelope actually carries when the sender had
        // no user-chosen filename, which is the camera / picker / share-sheet case.
        val name = AttachmentFileName.forWire("9f3c1b2a", "gif", null)
        assertEquals("9f3c1b2a.gif", name)
    }

    /** The whole envelope, as the iPhone parses it. */
    @Test
    fun the_mesh_envelope_for_a_gif_says_gif_in_both_places() {
        val isGif = GifBytes.isGif(gifBytes)
        val ext = MessageRepository.crossPlatformMeshExtension(MediaType.IMAGE, isGif)
        val wire = MessageRepository.crossPlatformMediaEnvelope(
            dataBase64 = "R0lGODlh",
            iosMediaType = MessageRepository.crossPlatformMeshWireType(MediaType.IMAGE, isGif),
            fileName = AttachmentFileName.forWire("msg-1", ext, null),
            size = gifBytes.size,
            caption = null,
            isViewOnce = false,
        )
        val json = JSONObject(wire)
        assertEquals("gif", json.getString("mediaType"))
        assertEquals("msg-1.gif", json.getString("fileName"))
    }

    /**
     * A user-chosen name wins, exactly as it does on every other leg — the sniff only
     * decides the SYNTHETIC extension and the label.
     */
    @Test
    fun a_real_filename_still_wins_over_the_synthesised_one() {
        val isGif = GifBytes.isGif(gifBytes)
        val ext = MessageRepository.crossPlatformMeshExtension(MediaType.IMAGE, isGif)
        assertEquals("cat.gif", AttachmentFileName.forWire("msg-1", ext, "cat.gif"))
    }

    // =====================================================================
    // …and everything that is NOT a GIF is byte-for-byte what already shipped
    // =====================================================================

    @Test
    fun a_photo_is_untouched_by_the_sniff() {
        val isGif = GifBytes.isGif(jpegBytes)
        assertEquals("image", MessageRepository.crossPlatformMeshWireType(MediaType.IMAGE, isGif))
        assertEquals("jpg", MessageRepository.crossPlatformMeshExtension(MediaType.IMAGE, isGif))
    }

    @Test
    fun the_other_media_types_keep_their_labels_and_this_paths_own_extensions() {
        // NB `bin`, not `wireFileName`'s `dat`: this envelope has always spelled a
        // document that way and 📄 M-6 deliberately kept it.
        assertEquals("video", MessageRepository.crossPlatformMeshWireType(MediaType.VIDEO, false))
        assertEquals("mp4", MessageRepository.crossPlatformMeshExtension(MediaType.VIDEO, false))
        assertEquals("audio", MessageRepository.crossPlatformMeshWireType(MediaType.AUDIO, false))
        assertEquals("m4a", MessageRepository.crossPlatformMeshExtension(MediaType.AUDIO, false))
        assertEquals("document", MessageRepository.crossPlatformMeshWireType(MediaType.DOCUMENT, false))
        assertEquals("bin", MessageRepository.crossPlatformMeshExtension(MediaType.DOCUMENT, false))
        assertEquals("contact", MessageRepository.crossPlatformMeshWireType(MediaType.CONTACT, false))
        assertEquals("bin", MessageRepository.crossPlatformMeshExtension(MediaType.CONTACT, false))
    }

    @Test
    fun the_sniff_beats_the_enum_even_when_the_enum_is_wrong() {
        // A share-sheet GIF that arrived labelled DOCUMENT is still a GIF.
        assertEquals("gif", MessageRepository.crossPlatformMeshWireType(MediaType.DOCUMENT, true))
        assertEquals("gif", MessageRepository.crossPlatformMeshExtension(MediaType.DOCUMENT, true))
    }

    // =====================================================================
    // The wiring — `sendMediaMessage` needs the whole Hilt graph AND
    // `android.util.Base64`, so the call site itself is pinned in the source,
    // the technique `VideoTeardownOrderTest` uses for the same reason.
    // =====================================================================

    /**
     * __LEAK_FIX_2026_09_25__ The mesh branch no longer builds this plaintext envelope
     * at all — it sends the ratchet ciphertext (see `MeshMediaEncryptedWireTest`). The
     * label rules above stay pinned because the envelope is still the reference shape
     * of the raw frames older peers send; what must hold now is that the branch never
     * builds it again.
     */
    @Test
    fun the_cross_platform_mesh_branch_no_longer_builds_the_plaintext_envelope() {
        val f = File("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
        assertTrue("not found: ${f.absolutePath}", f.exists())
        val src = f.readText()

        val start = src.indexOf("CROSS-PLATFORM MESH MEDIA")
        assertTrue("cross-platform mesh media branch not found", start > 0)
        val end = src.indexOf("vpsClient.sendMessage(", start)
        assertTrue("branch end not found", end > start)
        val branch = src.substring(start, end)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "the mesh branch must not build the plaintext media envelope",
            !branch.contains("crossPlatformMediaEnvelope(")
        )
        assertTrue(
            "the mesh branch must not send a raw MEDIA_MESSAGE frame",
            !branch.contains("\"MEDIA_MESSAGE\"")
        )
    }

    /**
     * The compression arm must stay gif-aware. Re-encoding a GIF to JPEG keeps one
     * frame, and no amount of correct labelling brings the animation back.
     */
    @Test
    fun compression_still_leaves_a_gif_alone() {
        val f = File("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
        val src = f.readText()
        assertTrue(
            "the IMAGE compression arm must still short-circuit on GifBytes.isGif",
            src.contains("MediaType.IMAGE -> if (GifBytes.isGif(mediaData))")
        )
    }
}
