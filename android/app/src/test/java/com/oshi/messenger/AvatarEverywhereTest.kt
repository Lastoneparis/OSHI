package com.oshi.messenger

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.theme.AvatarIdentity
import com.oshi.messenger.ui.theme.AvatarNameColors
import com.oshi.messenger.ui.theme.avatarInitials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __ONE_AVATAR_NOT_THREE_2026_11_07__
 *
 * [AvatarIdentity] had exactly one caller — the conversation list row. Every
 * other avatar in the app drew its own colour, usually a flat
 * `MaterialTheme.colorScheme.primaryContainer`, which is ONE colour for
 * everybody. So a contact was teal in the list and the same theme blue as
 * everyone else the moment you opened the conversation: Anatole's N12, and the
 * owner's "someone with no picture shows up blue in the conversation".
 *
 * The initials rule is pinned against the Swift copy's behaviour so the two
 * platforms draw the same letters as well as the same colour.
 */
class AvatarEverywhereTest {

    @Test
    fun `initials follow the name`() {
        assertEquals("AD", avatarInitials("Anatole Dupont", "zzz"))
        assertEquals("RA", avatarInitials("raph", "zzz"))
        assertEquals("R", avatarInitials("raph", "zzz", count = 1))
        assertEquals("SC", avatarInitials("  Sarah   Connor ", "zzz"))
    }

    /** A key is never empty, so this always answers. */
    @Test
    fun `initials fall back to the key`() {
        assertEquals("AB", avatarInitials(null, "abcdef"))
        assertEquals("AB", avatarInitials("", "abcdef"))
        assertEquals("AB", avatarInitials("   ", "abcdef"))
    }

    /**
     * The point of the fix: the COLOUR follows the key, so renaming someone
     * does not recolour them and two screens cannot disagree about one person.
     */
    @Test
    fun `the colour follows the key, not the name`() {
        assertEquals(AvatarIdentity.gradient("KEY-1"), AvatarIdentity.gradient("KEY-1"))
        assertNotEquals(AvatarIdentity.slot("KEY-1"), AvatarIdentity.slot("KEY-2"))
    }

    /** The screens that grew their own colour must not grow one back. */
    @Test
    fun `the chat header does not draw its own avatar colour`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        val header = SourceText.window(src, "val pb = profileBitmap", 1200)
        assertFalse(
            "the 1:1 header is back to a flat primaryContainer — one colour for every contact",
            header.contains("MaterialTheme.colorScheme.primaryContainer"),
        )
        // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ `ContactAvatar` is
        // `DefaultAvatar` keyed on the canonical key — the list's exact colour.
        assertTrue("the 1:1 header no longer uses ContactAvatar", header.contains("ContactAvatar("))
    }

    @Test
    fun `the group header and member rows use the shared avatar`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt")
        assertTrue("GroupChatScreen no longer uses GroupAvatar", src.contains("theme.GroupAvatar("))
        assertTrue("GroupChatScreen member rows no longer use ContactAvatar", src.contains("ContactAvatar("))
        assertFalse(
            "a group avatar is back to BrandColor → BrandColor, i.e. one colour for every group",
            src.contains("listOf(BrandColor, BrandColor.copy(alpha = 0.7f))"),
        )
    }

    // ---------------- __A_NAME_COLOUR_PER_PERSON_2026_11_07__

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val lightBubble = Color(0xF2 / 255f, 0xF2 / 255f, 0xF7 / 255f)
    private val darkBubble = Color(0x2C / 255f, 0x2C / 255f, 0x2E / 255f)

    /**
     * Re-measured, not trusted. These are TEXT colours, so the question is the
     * ratio against the bubble — a different question from the avatar
     * gradients, which are backgrounds measured against white initials.
     */
    @Test
    fun `every name colour is legible on its bubble`() {
        AvatarNameColors.light.forEachIndexed { i, c ->
            val r = contrast(c, lightBubble)
            assertTrue("light name colour $i is only ${"%.2f".format(r)}:1 on a light bubble", r >= 4.5)
        }
        AvatarNameColors.dark.forEachIndexed { i, c ->
            val r = contrast(c, darkBubble)
            assertTrue("dark name colour $i is only ${"%.2f".format(r)}:1 on a dark bubble", r >= 4.5)
        }
    }

    /** A short palette would make `forKey` index out of bounds. */
    @Test
    fun `the name palettes cover every avatar slot`() {
        assertEquals(AvatarIdentity.gradients.size, AvatarNameColors.light.size)
        assertEquals(AvatarIdentity.gradients.size, AvatarNameColors.dark.size)
    }

    /** The name and the avatar must land in the SAME slot for one person. */
    @Test
    fun `a name colour uses the same slot as the avatar`() {
        for (key in listOf("alice", "bob", "a-very-long-base64url_key-123", "")) {
            assertEquals(AvatarIdentity.slot(key), AvatarNameColors.slot(key))
        }
    }

    // Literal parity with iOS/desktop: `AvatarIdentityTest` reads docs/avatar-palette.json.
}
