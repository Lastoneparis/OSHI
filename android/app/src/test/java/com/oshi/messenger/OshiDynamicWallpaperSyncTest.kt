package com.oshi.messenger

import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.OshiDynamicStyle
import com.oshi.messenger.ui.components.chatWallpaperFromBytes
import com.oshi.messenger.ui.components.toSyncBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🌌 The animated wallpapers have to SURVIVE THE WIRE, in both directions.
 *
 * The bug this pins: iOS's app-wide default is `.oshiDynamic(constellation)` —
 * Mesh (`ChatWallpaperManager.swift:31`) — and Android's decoder had no branch
 * for `{"oshiDynamic":{"_0":n}}`. It fell through to `else -> Default`, so an
 * iPhone on the shipped default handed its Android peer flat beige and nothing
 * reported it. These tests fail on that decoder.
 */
class OshiDynamicWallpaperSyncTest {

    @Test
    fun `an iOS dynamic wallpaper decodes to that exact style`() {
        for (style in OshiDynamicStyle.entries) {
            val wire = """{"oshiDynamic":{"_0":${style.ordinal}}}""".toByteArray()
            val decoded = chatWallpaperFromBytes(wire)
            assertTrue(
                "${style.displayName} must decode as a dynamic wallpaper, got $decoded",
                decoded is ChatWallpaper.OshiDynamic
            )
            assertEquals(style.ordinal, (decoded as ChatWallpaper.OshiDynamic).styleId)
        }
    }

    /** The specific case that was losing iOS's default. */
    @Test
    fun `the iOS default Mesh does not decode to flat beige`() {
        val wire = """{"oshiDynamic":{"_0":${OshiDynamicStyle.MESH.ordinal}}}""".toByteArray()
        val decoded = chatWallpaperFromBytes(wire)
        assertTrue(
            "iOS's shipped default must not arrive as ChatWallpaper.Default",
            decoded !is ChatWallpaper.Default
        )
        assertEquals(
            OshiDynamicStyle.MESH.ordinal,
            (decoded as ChatWallpaper.OshiDynamic).styleId
        )
    }

    @Test
    fun `what Android emits is what Android reads back`() {
        for (style in OshiDynamicStyle.entries) {
            val original = ChatWallpaper.OshiDynamic(style.ordinal)
            assertEquals(original, chatWallpaperFromBytes(original.toSyncBytes()))
        }
    }

    @Test
    fun `Android emits the Swift Codable shape iOS decodes`() {
        val json = String(ChatWallpaper.OshiDynamic(OshiDynamicStyle.WAVES.ordinal).toSyncBytes())
        assertEquals("""{"oshiDynamic":{"_0":5}}""", json)
    }

    /**
     * A style a newer iOS knows and this build does not still means the peer
     * chose a DYNAMIC backdrop. Falling back to `Default` there would reproduce
     * the original bug for every wallpaper added after this build ships.
     */
    @Test
    fun `an unknown style falls back to the dynamic default, not to none`() {
        val decoded = chatWallpaperFromBytes("""{"oshiDynamic":{"_0":99}}""".toByteArray())
        assertTrue(decoded is ChatWallpaper.OshiDynamic)
        assertEquals(
            OshiDynamicStyle.DEFAULT.ordinal,
            (decoded as ChatWallpaper.OshiDynamic).styleId
        )
    }

    @Test
    fun `the catalogue order is the wire order and must not be reshuffled`() {
        assertEquals(
            listOf("Aurora", "Mesh", "Signal", "Contours", "Cipher", "Waves"),
            OshiDynamicStyle.entries.map { it.displayName }
        )
        assertEquals(OshiDynamicStyle.MESH, OshiDynamicStyle.DEFAULT)
    }

    @Test
    fun `every style has a plausible animation period`() {
        for (s in OshiDynamicStyle.entries) {
            assertTrue(
                "${s.displayName} at ${s.periodMs}ms would read as motion, not weather",
                s.periodMs in 20_000..90_000
            )
        }
        assertNotNull(OshiDynamicStyle.fromId(0))
    }
}
