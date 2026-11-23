package com.oshi.messenger

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.service.CheckInManager
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.media.GifBytes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __GROUP_GIF_2026_11_16__ — a GIF / sticker sent into a GROUP.
 *
 * The group wire has no `gif` media type on any platform (iOS `GroupMessaging.swift`
 * `MediaType { photo, video, audio, document, contact }`, desktop `GroupMessageWire`),
 * so a group GIF travels exactly as iOS's own group composer sends one: `photo`, bytes
 * untouched, name ending `.gif`. Receivers decide "animated" from the bytes
 * (iOS `LazyGroupPhotoView` → `MediaManager.isGIFData`, desktop `GifBubble.isGifFile`,
 * Android `BubbleMedia`'s GIF decoder). These tests pin every piece of that payload.
 */
class GroupGifWireTest {

    private val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x10, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x3B)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    @Test
    fun `the group GroupMessage of a GIF is a photo named gif with no caption`() {
        val json = JSONObject(
            GroupManager.wireGroupMessageJson(
                id = "M1", wireGroupId = "G1", senderPublicKey = "me", appleTimestamp = 1.0,
                encryptedContentB64 = "",                       // v2: the bytes ride the blob
                iosMediaType = "photo",
                mediaFileName = GroupManager.groupGifFileName("oshi_love.gif"),
                body = "", includeBodyCompat = false,
            )
        )
        assertEquals("photo", json.getString("mediaType"))          // a type every shipped decoder knows
        assertEquals("oshi_love.gif", json.getString("mediaFileName"))
        assertFalse("an empty caption must not print under the sticker on iOS", json.has("plaintextContent"))
        assertEquals("", json.getString("encryptedContent"))
        assertFalse(json.has("videoNote"))
    }

    @Test
    fun `every receiver maps the label back to an image`() {
        assertEquals(MediaType.IMAGE, GroupManager.mapIOSMediaType("photo"))
        // And iOS 1:1-style `gif`, should an older peer ever put it on a group row.
        assertEquals(MediaType.IMAGE, GroupManager.mapIOSMediaType("gif"))
    }

    @Test
    fun `the blob MIME says gif only for GIF bytes and stays image star`() {
        assertEquals("image/gif", GroupManager.groupMime("photo", isGif = GifBytes.isGif(gif)))
        assertEquals("image/jpeg", GroupManager.groupMime("photo", isGif = GifBytes.isGif(jpeg)))
        assertEquals("image/jpeg", GroupManager.groupMime("photo"))
        assertTrue(GroupManager.groupMime("photo", true).startsWith("image/"))
        // Only a photo can be a GIF; the flag never relabels another kind.
        assertEquals("video/mp4", GroupManager.groupMime("video", isGif = true))
    }

    @Test
    fun `the file name always ends in gif and carries no path`() {
        assertEquals("cat.gif", GroupManager.groupGifFileName("cat.gif"))
        assertEquals("cat.GIF", GroupManager.groupGifFileName("cat.GIF"))
        assertEquals("fc_hello.gif", GroupManager.groupGifFileName("fc_hello"))
        assertEquals("x.gif", GroupManager.groupGifFileName("/data/user/0/cache/x.gif"))
        assertEquals("y.gif", GroupManager.groupGifFileName("a\\b\\y.gif"))
        assertEquals("animation.gif", GroupManager.groupGifFileName(""))
        assertEquals("animation.gif", GroupManager.groupGifFileName(null))
        assertEquals("animation.gif", GroupManager.groupGifFileName("  "))
    }

    @Test
    fun `the legacy inline copy for old builds carries the untouched bytes`() {
        val b64 = Base64.getEncoder().encodeToString(gif)
        val json = JSONObject(
            GroupManager.buildGroupMediaJson(
                iosMediaType = "photo", contentBase64 = b64, size = gif.size,
                caption = "", fileName = GroupManager.groupGifFileName("s.gif")
            )
        )
        assertEquals("media", json.getString("type"))
        assertEquals("photo", json.getString("mediaType"))
        assertEquals("s.gif", json.getString("fileName"))
        assertFalse(json.has("caption"))
        val back = Base64.getDecoder().decode(json.getString("content"))
        assertTrue("re-encoding would keep one frame", back.contentEquals(gif))
        assertTrue(GifBytes.isGif(back))
    }

    @Test
    fun `the group view model sends the picker's GIF on the group media path untouched`() {
        val vm = moduleFile("src/main/java/com/oshi/messenger/ui/viewmodel/GroupChatViewModel.kt").readText()
        val fn = vm.substringAfter("fun sendGifMessage(").substringBefore("\n    }\n")
        assertTrue(fn.contains("mediaType = MediaType.IMAGE"))
        assertTrue(fn.contains("mediaData = gifData"))
        assertTrue(fn.contains("content = \"\""))
        assertTrue(fn.contains("GroupManager.groupGifFileName(fileName)"))
        // The send path must not recompress: GroupManager.sendGroupMessage has no image codec.
        val gm = moduleFile("src/main/java/com/oshi/messenger/service/GroupManager.kt").readText()
        val send = gm.substringAfter("fun sendGroupMessage(").substringBefore("fun sendBotMessage(")
        assertFalse(send.contains("compress"))
        assertTrue(gm.contains("groupMime(iosMediaType, isGif = com.oshi.messenger.service.media.GifBytes.isGif(bytes))"))
        // And the group screen offers the picker.
        val screen = moduleFile("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt").readText()
        assertTrue(screen.contains("StickerGifPicker(") && screen.contains("viewModel.sendGifMessage(bytes, fileName)"))
    }

    // ── __GROUP_CHECK_IN_2026_11_16__ ──────────────────────────────────

    @Test
    fun `a check-in addressed to a group id goes to the group, a key goes 1 to 1`() {
        assertTrue(CheckInManager.isGroupRecipient("0F8FAD5B-D9CB-469F-A165-70867728950E"))
        assertTrue(CheckInManager.isGroupRecipient("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(CheckInManager.isGroupRecipient("BAsxYwz3rDf0m4oKqVXr0K2r5kQ2V6m3n1p8Zq9yT0o="))
        assertFalse(CheckInManager.isGroupRecipient("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"))
        assertFalse(CheckInManager.isGroupRecipient(""))
        assertFalse(CheckInManager.isGroupRecipient(null))
    }

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        error("not found: $relative")
    }
}
