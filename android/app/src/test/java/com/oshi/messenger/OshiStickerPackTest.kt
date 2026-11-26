package com.oshi.messenger

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.OshiMascot
import com.oshi.messenger.data.model.OshiStickerPack
import com.oshi.messenger.data.model.StickerPickerTab
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.media.GifBytes
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __OSHI_MASCOT_STICKERS_2026_11_21__ The OSHI sticker tab ([OshiStickerPack]): tab order,
 * pack order, what is loaded for a tap, and that what is sent rides the existing GIF wire
 * every client already shows (1:1 `gif` / `image/gif`, group `photo` + `.gif`).
 */
class OshiStickerPackTest {

    private val assets = File("src/main/assets")
    private val read: (String) -> ByteArray? = { path -> File(assets, path).takeIf { it.isFile }?.readBytes() }

    // ------------------------------------------------------------ tabs

    @Test
    fun `the OSHI tab comes first and the picker opens on it`() {
        assertEquals(listOf(StickerPickerTab.OSHI, StickerPickerTab.GIFS, StickerPickerTab.STICKERS), OshiStickerPack.TABS)
        assertEquals(StickerPickerTab.OSHI, OshiStickerPack.DEFAULT_TAB)
    }

    @Test
    fun `the OSHI tab never spends a GIPHY call`() {
        assertFalse(OshiStickerPack.usesGiphy(StickerPickerTab.OSHI))
        assertTrue(OshiStickerPack.usesGiphy(StickerPickerTab.GIFS))
        assertTrue(OshiStickerPack.usesGiphy(StickerPickerTab.STICKERS))
    }

    // ------------------------------------------------------------ pack loading / order

    @Test
    fun `all 20 mascots are offered in picker order and each once`() {
        val shown = OshiStickerPack.stickers()
        assertEquals(OshiMascot.ALL, shown)
        assertEquals(20, shown.size)
        assertEquals(shown.size, shown.map { it.name }.toSet().size)
        // Every animation in the shipped manifest is reachable from the tab.
        val manifest = JSONObject(File(assets, "OshiMascot/manifest.json").readText()).getJSONArray("animations")
        val names = (0 until manifest.length()).map { manifest.getJSONObject(it).getString("name") }.toSet()
        assertEquals(names, shown.map { it.name }.toSet())
        assertEquals("love", shown.first().name)
    }

    @Test
    fun `search filters the tab by name, label, emoji and finds the whole set for oshi`() {
        assertEquals(listOf("love"), OshiStickerPack.stickers("love").map { it.name })
        assertEquals(listOf("laugh"), OshiStickerPack.stickers("🤣").map { it.name })
        assertEquals(20, OshiStickerPack.stickers("oshi").size)
        assertTrue(OshiStickerPack.stickers("zzzz-nothing").isEmpty())
    }

    @Test
    fun `every sticker loads as the bundled GIF89a with its oshi file name`() {
        for (m in OshiStickerPack.stickers()) {
            val s = OshiStickerPack.sticker(m, read)
            assertNotNull("sticker ${m.name} did not load", s)
            s!!
            assertArrayEquals(m.name, File(assets, m.gifAsset).readBytes(), s.bytes)
            assertEquals("oshi_${m.name}.gif", s.fileName)
            assertTrue(m.name, GifBytes.isGif(s.bytes))
            assertTrue("${m.name} must loop forever", loopsForever(s.bytes))
        }
    }

    @Test
    fun `manifest byte counts match the shipped GIFs`() {
        val anims = JSONObject(File(assets, "OshiMascot/manifest.json").readText()).getJSONArray("animations")
        for (i in 0 until anims.length()) {
            val a = anims.getJSONObject(i)
            val f = File(assets, "OshiMascot/gif/${a.getString("name")}.gif")
            assertEquals(a.getString("name"), a.getJSONObject("bytes").getLong("gif"), f.length())
        }
    }

    @Test
    fun `a missing or non-GIF file is never sent in place of the sticker`() {
        val love = OshiMascot.named("love")!!
        assertNull(OshiStickerPack.sticker(love) { null })
        assertNull(OshiStickerPack.sticker(love) { throw java.io.IOException("gone") })
        // The animated WebP of the same mascot: older iPhones would not show it.
        assertNull(OshiStickerPack.sticker(love) { File(assets, love.webpAsset).readBytes() })
        assertNull(OshiStickerPack.sticker(love) { "GIF87a".toByteArray() + ByteArray(8) })
    }

    @Test
    fun `reduce motion previews the still frame, otherwise the GIF that is sent`() {
        val m = OshiMascot.named("wave")!!
        assertEquals("OshiMascot/static/wave_128.png", OshiStickerPack.previewAsset(m, reduceMotion = true))
        assertEquals("OshiMascot/gif/wave.gif", OshiStickerPack.previewAsset(m, reduceMotion = false))
        assertTrue(File(assets, OshiStickerPack.previewAsset(m, true)).isFile)
    }

    /**
     * iOS 1.0.43 (on sale) draws a received GIF with `UIImage(data:)`: the FIRST frame,
     * still. So each GIF starts on the manifest's `still_frame` pose. This JVM has no
     * image decoder (android.jar), so the pixels are checked by the desktop's
     * `OshiStickerPackTest` (frame 0 vs `static/<name>_128.png`); here: the Android and
     * desktop copies are the SAME bytes, so that check covers what this app sends.
     */
    @Test
    fun `the Android GIFs are byte-identical to the desktop copy`() {
        val desktop = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "OSHI-Desktop/src/main/resources/mascot/gif") }
            .firstOrNull { it.isDirectory }
        org.junit.Assume.assumeTrue("no desktop tree in this checkout", desktop != null)
        for (m in OshiMascot.ALL) {
            assertArrayEquals(m.name, File(assets, m.gifAsset).readBytes(), File(desktop, "${m.name}.gif").readBytes())
        }
    }

    /**
     * The 20 GIFs are the ROTATED set (frame 0 = still pose), verified pixel by pixel when
     * they were made (2026-11-21). Re-copying the original pack (`~/oshi_mascot/pack/gif`,
     * frame 0 = a neutral face on wave/ok/hundred/wow/cool…) would silently bring back
     * blank-looking stickers on iOS 1.0.43; this digest makes that red. If the pack is
     * regenerated on purpose, rotate it again (gifsicle `#k-N` `#0-(k-1)`, k = still_frame),
     * check frame 0, then update the digest.
     */
    @Test
    fun `the shipped GIFs are the set that starts on the still pose`() {
        val all = java.security.MessageDigest.getInstance("SHA-256")
        for (m in OshiMascot.ALL) {
            all.update(java.security.MessageDigest.getInstance("SHA-256").digest(File(assets, m.gifAsset).readBytes()))
        }
        assertEquals(
            "1cfe6279d9d55a29fe430c4466608af6cd554f9df910eb0d98b420bf25ab965b",
            all.digest().joinToString("") { "%02x".format(it) },
        )
    }

    // ------------------------------------------------------------ the send path

    @Test
    fun `a 1to1 sticker rides the existing GIF wire`() {
        val s = OshiStickerPack.sticker(OshiMascot.named("ok")!!, read)!!
        // Same call the picker makes: sendGifMessage(bytes, fileName) → IMAGE, untouched.
        // __GIF_COMPAT_IOS1043_2026_11_21__ labelled `image` (iOS 1.0.43 has no `gif`).
        assertEquals("image", MessageRepository.legacyWireMediaType(MediaType.IMAGE, GifBytes.isGif(s.bytes)))
        val env = JSONObject(
            MessageRepository.legacyMediaEnvelope(
                MediaType.IMAGE.name, null, "R0lG", false, fileName = s.fileName,
                iosMediaType = MessageRepository.legacyWireMediaType(MediaType.IMAGE, true),
            )
        )
        assertEquals("media", env.getString("type"))
        assertEquals("image", env.getString("mediaType"))
        assertEquals("oshi_ok.gif", env.getString("fileName"))
        assertEquals("", env.getString("caption"))
    }

    @Test
    fun `a group sticker is a photo named gif with image gif mime`() {
        val s = OshiStickerPack.sticker(OshiMascot.named("hundred")!!, read)!!
        assertEquals("oshi_hundred.gif", GroupManager.groupGifFileName(s.fileName))
        assertEquals("image/gif", GroupManager.groupMime("photo", isGif = GifBytes.isGif(s.bytes)))
    }

    // ------------------------------------------------------------ helpers

    /** NETSCAPE2.0 application extension with loop count 0. */
    private fun loopsForever(b: ByteArray): Boolean {
        val tag = "NETSCAPE2.0".toByteArray()
        for (i in 0..b.size - tag.size - 5) {
            if ((tag.indices).all { b[i + it] == tag[it] }) {
                val p = i + tag.size // sub-block: 03 01 lo hi 00
                return b[p] == 3.toByte() && b[p + 1] == 1.toByte() && b[p + 2] == 0.toByte() && b[p + 3] == 0.toByte()
            }
        }
        return false
    }
}
