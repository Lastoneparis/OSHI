package com.oshi.messenger

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __INK_FROM_THE_SAME_SOURCE_AS_THE_PAINT_2026_11_07__
 *
 * Anatole: "when a message is sent with text it's white on white or black on
 * black."
 *
 * Measuring every shipped pair found all of them above 4.5:1, so the constants
 * were never the fault — two OWNERS for one pair were. The bubble was painted
 * from `BubbleAccent.brush` while its text was inked from
 * `colorScheme.onPrimary`, and the screen was composed from two colour schemes
 * at once (app vs wallpaper tone). Agreement was a coincidence that held.
 *
 * This locks the measurement down so the coincidence becomes a rule.
 */
class BubbleContrastTest {

    private fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    private fun lum(r: Double, g: Double, b: Double) = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    private fun hex(v: Long) = Triple((v shr 16 and 255) / 255.0, (v shr 8 and 255) / 255.0, (v and 255) / 255.0)
    private fun ratio(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
        val la = lum(a.first, a.second, a.third)
        val lb = lum(b.first, b.second, b.third)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val white = Triple(1.0, 1.0, 1.0)
    private val black = Triple(0.0, 0.0, 0.0)

    /** Both gradient stops of every accent, against the white `onAccent` ink. */
    private val accentStops = mapOf(
        "OSHI" to listOf(hex(0x7B3AF5), hex(0x5616EC)),
        "OCEAN" to listOf(Triple(0.10, 0.36, 0.80), Triple(0.04, 0.24, 0.63)),
        "SUNSET" to listOf(Triple(0.82, 0.22, 0.33), Triple(0.72, 0.15, 0.16)),
        "FOREST" to listOf(Triple(0.06, 0.48, 0.36), Triple(0.03, 0.34, 0.29)),
        "GRAPHITE" to listOf(Triple(0.26, 0.27, 0.31), Triple(0.14, 0.15, 0.18)),
        "ROSE" to listOf(Triple(0.76, 0.16, 0.50), Triple(0.60, 0.08, 0.39)),
    )

    /**
     * THE test. Every accent, both gradient stops, against the ink actually
     * used on it. A new accent that reads badly fails here rather than in a
     * screenshot from a user.
     */
    @Test
    fun `every accent carries its own ink at 4,5 to 1`() {
        for ((name, stops) in accentStops) {
            for ((i, stop) in stops.withIndex()) {
                val r = ratio(stop, white)
                assertTrue(
                    "accent $name stop $i reads $r against white onAccent — below 4.5:1",
                    r >= 4.5,
                )
            }
        }
    }

    /** The received bubble, in both schemes, against both inks used on it. */
    @Test
    fun `the received bubble carries its ink in both schemes`() {
        val receivedLight = white
        val receivedDark = hex(0x22252B) // __BUBBLE_MEDIA_2026_11_21__ neutral elevated grey
        val pairs = listOf(
            Triple("light/onSurface", receivedLight, black),
            Triple("light/onSurfaceVariant", receivedLight, hex(0x606060)),
            Triple("dark/onSurface", receivedDark, white),
            Triple("dark/onSurfaceVariant", receivedDark, hex(0xB0B0B0)),
        )
        for ((label, bg, ink) in pairs) {
            val r = ratio(bg, ink)
            assertTrue("received $label reads $r — below 4.5:1", r >= 4.5)
        }
    }

    /**
     * The structural half, and the one that actually bit: the outgoing bubble's
     * ink must be read from the accent that is PAINTED, not from the Material
     * scheme. Two owners for one pair is how "white on white" happens even when
     * every constant measures fine.
     */
    @Test
    fun `outgoing ink is read from the painted accent, not the material scheme`() {
        val chat = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertTrue(
            "the outgoing bubble is no longer painted from the accent — re-check this guard",
            // __BUBBLE_MEDIA_2026_11_21__ painted by the accent's scheme-aware surface.
            chat.contains("appearance.accent.surfaceBrush(darkScheme)"),
        )
        assertTrue(
            "outgoing ink should come from the same accent (accent.ink)",
            chat.contains("appearance.accent.ink(isDarkScheme())"),
        )
        val strayPairing = Regex(
            """color\s*=\s*if\s*\(isFromMe\)\s*\n\s*MaterialTheme\.colorScheme\.onPrimary"""
        )
        assertTrue(
            "an outgoing text colour is back on colorScheme.onPrimary while the bubble " +
                "is painted from the accent — that is the two-owner bug again",
            !strayPairing.containsMatchIn(chat),
        )
    }

    /**
     * Anatole's band: the chat must resolve ONE scheme above the Scaffold and
     * paint from it, instead of letting the bar and the thread disagree.
     */
    @Test
    fun `the chat screen composes from a single resolved scheme`() {
        val chat = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertTrue(
            "the chat no longer hoists one scheme — the band can come back",
            chat.contains("WithChatScheme(dark = chatDark)"),
        )
        assertTrue(
            "the Scaffold must not paint its own container colour behind the frosted bar",
            chat.contains("containerColor = androidx.compose.ui.graphics.Color.Transparent"),
        )
    }

    /**
     * The group thread paints its outgoing bubble from the same accent, so it
     * must ink from the same place. It used a hard-coded white that agreed with
     * `onAccent` by coincidence — the same shape as the 1:1 bug, one screen
     * over, and the reason to check every screen that paints a bubble.
     */
    @Test
    fun `the group thread inks from the painted accent too`() {
        val group = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt")
        assertTrue(
            "the group bubble is no longer accent-painted — re-check this guard",
            group.contains("appearance.accent.surfaceBrush(darkScheme)"),
        )
        assertTrue(
            "group outgoing ink should read the painted accent",
            group.contains("outgoingLook -> accentNow.ink(darkNow)"),
        )
        assertTrue(
            "group outgoing ink is back on a hard-coded white",
            !group.contains("if (isOwnMessage || isBot) Color.White"),
        )
    }
}
