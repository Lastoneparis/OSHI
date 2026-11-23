package com.oshi.messenger

import com.oshi.messenger.data.model.OshiMascot
import com.oshi.messenger.data.model.OshiReaction
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.ui.screens.LaunchMetrics
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ The O-bubble mascot is a RENDERING of
 * standard emoji: these tests pin the emoji → asset mapping (aliases, FE0F,
 * skin tones), that the wire value is still the plain emoji, the settings
 * fallback, and that every file the app loads is really in the APK.
 */
class OshiMascotTest {

    private val assets = File("src/main/assets/OshiMascot")

    // ------------------------------------------------------------ manifest parity

    @Test
    fun `the table matches the shipped manifest exactly`() {
        val manifest = JSONObject(File(assets, "manifest.json").readText())
        val anims = manifest.getJSONArray("animations")
        assertEquals(20, anims.length())
        assertEquals(20, OshiMascot.ALL.size)
        for (i in 0 until anims.length()) {
            val a = anims.getJSONObject(i)
            val m = OshiMascot.named(a.getString("name"))
            assertNotNull("missing ${a.getString("name")}", m)
            m!!
            assertEquals(m.name, a.getString("emoji"), m.emoji)
            val aliases = a.getJSONArray("aliases").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            assertEquals(m.name, aliases, m.aliases)
            assertEquals(m.name, a.getString("label"), m.label)
        }
    }

    @Test
    fun `every asset the app loads is bundled`() {
        for (m in OshiMascot.ALL) {
            for (path in listOf(m.webpAsset, m.gifAsset, m.staticAsset)) {
                val f = File("src/main/assets", path)
                assertTrue("missing $path", f.isFile && f.length() > 0)
            }
            val gif = File("src/main/assets", m.gifAsset).readBytes().copyOf(6)
            assertArrayEquals("${m.gifAsset} is not a GIF89a", "GIF89a".toByteArray(), gif)
        }
        assertTrue(File(assets, "launch/wave_launch.webp").isFile)
        assertTrue(File("src/main/res/drawable-nodpi/mascot_launch_logo.png").isFile)
        assertTrue(File("src/main/res/drawable-nodpi/mascot_launch_neutral.png").isFile)
    }

    // ------------------------------------------------------------ mapping

    @Test
    fun `each canonical emoji maps to its own mascot`() {
        for (m in OshiMascot.ALL) assertSame(m.emoji, m, OshiMascot.forEmoji(m.emoji))
        assertEquals("love", OshiMascot.forEmoji("❤️")?.name)
        assertEquals("laugh", OshiMascot.forEmoji("😂")?.name)
        assertEquals("cry", OshiMascot.forEmoji("😭")?.name)
        assertEquals("peek", OshiMascot.forEmoji("🫣")?.name)
        assertEquals("tearsmile", OshiMascot.forEmoji("🥲")?.name)
        assertEquals("thumbs", OshiMascot.forEmoji("👍")?.name)
        assertEquals("hundred", OshiMascot.forEmoji("💯")?.name)
    }

    @Test
    fun `aliases map to the same mascot`() {
        assertEquals("laugh", OshiMascot.forEmoji("🤣")?.name)
        assertEquals("cry", OshiMascot.forEmoji("😢")?.name)
        assertEquals("love", OshiMascot.forEmoji("💜")?.name)
        assertEquals("love", OshiMascot.forEmoji("🖤")?.name)
        assertEquals("party", OshiMascot.forEmoji("🥳")?.name)
        assertEquals("ok", OshiMascot.forEmoji("👌")?.name)
        assertEquals("ok", OshiMascot.forEmoji("✔️")?.name)
        assertEquals("peek", OshiMascot.forEmoji("🙈")?.name)
        for (m in OshiMascot.ALL) for (a in m.aliases) assertSame(a, m, OshiMascot.forEmoji(a))
    }

    @Test
    fun `variation selector and skin tones are ignored`() {
        assertEquals("love", OshiMascot.forEmoji("❤")?.name)          // bare heart, no FE0F
        assertEquals("love", OshiMascot.forEmoji("❤️")?.name)
        assertEquals("ok", OshiMascot.forEmoji("✔")?.name)            // ✔ without FE0F
        assertEquals("thumbs", OshiMascot.forEmoji("👍🏽")?.name)
        assertEquals("wave", OshiMascot.forEmoji("👋🏿")?.name)
        assertEquals("pray", OshiMascot.forEmoji("🙏🏻")?.name)
    }

    @Test
    fun `unrelated values and sequences draw no mascot`() {
        for (code in listOf(
            null, "", "❌", "🖕", "👎", "👀", "✝️", "☪️", "☸️",
            OshiReaction.OSHI_O, OshiReaction.OSHI_SMILE, // the OSHI marks keep their own drawing
            "❤️‍🔥",                                  // ZWJ heart on fire
            "😂😂", "👍 ", "a",
        )) assertNull("'$code' drew a mascot", OshiMascot.forEmoji(code))
    }

    // ------------------------------------------------------------ wire value

    @Test
    fun `the quick bar sends the standard emoji, never a mascot name`() {
        assertEquals(listOf("⭕", "❤️", "😂", "😢", "👍", "😮", "🔥"), OshiReaction.QUICK_ROW)
        // ❤️ on the wire is exactly U+2764 U+FE0F, the iOS ReactionEmoji.heart raw value.
        assertArrayEquals(
            byteArrayOf(0xE2.toByte(), 0x9D.toByte(), 0xA4.toByte(), 0xEF.toByte(), 0xB8.toByte(), 0x8F.toByte()),
            OshiReaction.QUICK_ROW[1].toByteArray(Charsets.UTF_8)
        )
        assertEquals(ReactionEmojis.HEART, OshiReaction.QUICK_ROW[1])
        for (code in OshiReaction.PICKER_ALL) {
            assertTrue("'$code' is not an emoji", code.none { it.isLetterOrDigit() })
        }
    }

    @Test
    fun `the full picker offers all 20 mascots once, the OSHI marks, and iOS reactions without a mascot`() {
        val all = OshiReaction.PICKER_ALL
        assertEquals(all.size, all.toSet().size)
        assertEquals(listOf(OshiReaction.OSHI_O, OshiReaction.OSHI_SMILE), all.take(2))
        assertEquals(OshiMascot.ALL.map { it.wire }, all.drop(2).take(20))
        assertTrue(all.containsAll(OshiReaction.QUICK_ROW))
        // Every iOS reaction is still reachable, as itself or as its mascot's canonical emoji.
        for (ios in ReactionEmojis.ALL) {
            val m = OshiMascot.forEmoji(ios)
            assertTrue(ios, if (m == null) ios in all else m.wire in all)
        }
        // No mascot is offered twice under an alias (😭 next to 😢).
        assertEquals(20, all.mapNotNull { OshiMascot.forEmoji(it) }.size)
    }

    @Test
    fun `the wire table covers every mascot and each value draws its own mascot`() {
        assertEquals(OshiMascot.ALL.map { it.name }, OshiMascot.WIRE.keys.toList())
        for (m in OshiMascot.ALL) assertSame(m.name, m, OshiMascot.forEmoji(m.wire))
        // Safe mapping (1.6.98): only cry deviates from its manifest canonical emoji.
        for (m in OshiMascot.ALL) if (m.name != "cry") assertEquals(m.name, m.emoji, m.wire)
    }

    // ------------------------------------------- __REACTION_SAFE_MAPPING_2026_11_16__

    @Test
    fun `safe mapping - the wire values, pinned`() {
        assertEquals(
            linkedMapOf(
                "love" to "❤️", "laugh" to "😂", "cry" to "😢", "peek" to "🫣",
                "tearsmile" to "🥲", "thumbs" to "👍", "pray" to "🙏", "fire" to "🔥",
                "wow" to "😮", "angry" to "😡", "party" to "🎉", "wave" to "👋",
                "hearteyes" to "😍", "thinking" to "🤔", "clap" to "👏", "kiss" to "😘",
                "sleep" to "😴", "cool" to "😎", "ok" to "✅", "hundred" to "💯",
            ).toList(),
            OshiMascot.WIRE.toList()
        )
        // The cry mascot SENDS 😢 (iOS 1.0.43 knows it) ...
        assertEquals(ReactionEmojis.SAD, OshiMascot.named("cry")!!.wire)
        // ... and still DRAWS for 😢 and for a received 😭.
        assertEquals("cry", OshiMascot.forEmoji("😢")?.name)
        assertEquals("cry", OshiMascot.forEmoji("😭")?.name)
    }

    @Test
    fun `safe mapping - every quick bar reaction is known to iOS 1_0_43`() {
        assertEquals(listOf("love", "laugh", "cry", "thumbs", "wow", "fire"), OshiMascot.QUICK_NAMES)
        for (code in OshiReaction.QUICK_ROW.drop(1)) {
            assertTrue("'$code' unknown to old iOS", code in ReactionEmojis.ALL)
            assertFalse(code, OshiReaction.mayShowAsThumbsOnOldIos(code))
        }
        assertEquals(OshiReaction.OSHI_O, OshiReaction.QUICK_ROW.first())
    }

    @Test
    fun `safe mapping - exactly the eight others carry the new hint, and still draw as mascots`() {
        val flagged = OshiReaction.PICKER_ALL.filter(OshiReaction::mayShowAsThumbsOnOldIos)
        assertEquals(listOf("🫣", "🥲", "🙏", "😡", "👋", "😍", "😴", "😎"), flagged)
        for (code in flagged) assertNotNull(code, OshiMascot.forEmoji(code))
        // Received 😭 is also drawn as a mascot, though it is never sent.
        assertFalse("😭" in OshiReaction.PICKER_ALL)
        assertFalse(OshiReaction.mayShowAsThumbsOnOldIos(OshiReaction.OSHI_O))
        assertFalse(OshiReaction.mayShowAsThumbsOnOldIos(OshiReaction.OSHI_SMILE))
    }

    @Test
    fun `safe mapping - the new hint is translated in every shipped locale`() {
        val res = File("src/main/res")
        val dirs = res.listFiles()!!.filter { File(it, "strings.xml").exists() }
        assertTrue(dirs.size >= 30)
        for (d in dirs) {
            val xml = File(d, "strings.xml").readText()
            val m = Regex("<string name=\"reactions_new_hint\">([^<]+)</string>").find(xml)
            assertNotNull("${d.name} lacks reactions_new_hint", m)
            assertTrue("${d.name}: hint must show 👍", m!!.groupValues[1].contains("👍"))
        }
    }

    @Test
    fun `the setting off falls back to the system emoji`() {
        assertEquals("love", OshiMascot.rendered("❤️", animatedReactionsEnabled = true)?.name)
        assertNull(OshiMascot.rendered("❤️", animatedReactionsEnabled = false))
        assertNull(OshiMascot.rendered("❌", animatedReactionsEnabled = true))
    }

    // ------------------------------------------------------------ GIF picker

    @Test
    fun `GIF picker search finds mascots by word, emoji or the brand`() {
        assertEquals(OshiMascot.ALL, OshiMascot.search(""))
        assertEquals(OshiMascot.ALL, OshiMascot.search("OSHI"))
        assertEquals(listOf("love"), OshiMascot.search("love").map { it.name })
        assertEquals(listOf("laugh"), OshiMascot.search("🤣").map { it.name })
        assertTrue(OshiMascot.search("zzzz-nothing").isEmpty())
        assertTrue(OshiMascot.ALL.all { it.gifFileName.endsWith(".gif") })
    }

    // ------------------------------------------------------------ launch

    @Test
    fun `the launch intro fits inside the existing minimum launch time`() {
        assertTrue(LaunchMetrics.INTRO_MS <= 900)
        // splash_mascot_logo.xml insets the canvas so it lands at MASCOT_DP inside the 288dp box.
        val xml = File("src/main/res/drawable/splash_mascot_logo.xml").readText()
        val pct = Regex("android:inset=\"([0-9.]+)%\"").find(xml)!!.groupValues[1].toDouble()
        assertEquals(LaunchMetrics.MASCOT_DP.toDouble(), 288 * (1 - 2 * pct / 100), 0.5)
        // ...and the logo (0.41 of the canvas from its centre) stays in the 192dp visible circle.
        assertTrue(0.412 * LaunchMetrics.MASCOT_DP <= 96)
    }

    // __SPLASH_HANDOVER_2026_11_16__ ------------------------------------

    @Test
    fun `the launch screen starts on the ground the starting surface showed`() {
        val tb = com.oshi.messenger.ui.theme.ThemeBoot
        // API 33+: black exactly when a black splash theme was set (OLED in force).
        assertTrue(tb.splashGroundIsBlack(36, "system", true, systemDark = true))
        assertFalse(tb.splashGroundIsBlack(36, "system", true, systemDark = false))
        assertTrue(tb.splashGroundIsBlack(33, "dark", true, systemDark = false))
        assertFalse(tb.splashGroundIsBlack(36, "light", true, systemDark = true))
        assertFalse(tb.splashGroundIsBlack(36, "dark", false, systemDark = true))
        assertFalse(tb.splashGroundIsBlack(36, null, false, systemDark = true))
        // API 26–32 can never show a black starting surface.
        for (sdk in listOf(26, 30, 31, 32)) assertFalse(tb.splashGroundIsBlack(sdk, "dark", true, systemDark = true))
        // ...and every black splash theme maps to a black ground, every other to purple.
        for (theme in listOf(null, "system", "dark", "light")) for (oled in listOf(true, false)) for (night in listOf(true, false)) {
            val choice = tb.splashChoice(theme, oled)
            val splashBlack = choice == com.oshi.messenger.ui.theme.SplashChoice.OledAlways ||
                (choice == com.oshi.messenger.ui.theme.SplashChoice.OledNight && night)
            assertEquals("$theme/$oled/$night", splashBlack, tb.splashGroundIsBlack(36, theme, oled, night))
        }
    }

    @Test
    fun `below API 31 the starting window is the logo on the splash ground, MainActivity only`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val main = manifest.substringAfter("android:name=\".ui.MainActivity\"").substringBefore(">")
        assertTrue(main.contains("@style/Theme.OSHI.Launcher"))
        val base = File("src/main/res/values/themes.xml").readText()
        assertTrue(base.contains("<item name=\"android:windowBackground\">@drawable/launch_window</item>"))
        // API 31+: the system splash is the starting surface, no logo background.
        val v31 = File("src/main/res/values-v31/themes.xml").readText()
        assertTrue(v31.contains("<style name=\"Theme.OSHI.Launcher\" parent=\"Theme.OSHI\" />"))
        val layer = File("src/main/res/drawable/launch_window.xml").readText()
        assertTrue(layer.contains("@color/launch_background"))
        assertTrue(layer.contains("@drawable/mascot_launch_logo"))
        assertTrue(layer.contains("android:width=\"${LaunchMetrics.MASCOT_DP}dp\""))
        assertTrue(layer.contains("android:height=\"${LaunchMetrics.MASCOT_DP}dp\""))
    }
}
