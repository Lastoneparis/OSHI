package com.oshi.messenger

import com.oshi.messenger.service.WallpaperManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __WALLPAPER_PERSIST_2026_11_21__ / __CHAT_BACKDROP_BLUR_2026_11_21__
 * « des fonds d'écran et un effet flou dans les discussions derrière le nom
 * d'un contact ». Why the user thought wallpapers did not exist, as guards:
 * a chat's wallpaper lived in memory only, groups and channels had no way to
 * pick one, and Settings' "Chat Wallpaper" opened a tone sheet.
 */
class ChatWallpaperBlurTest {

    @Test
    fun `conversation keys are hashed, stable and never the id itself`() {
        val id = "BASE64PublicKeyOfSomeContact+/=="
        val h = WallpaperManager.hashId(id)
        assertEquals(32, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(h, WallpaperManager.hashId(id))
        assertNotEquals(h, WallpaperManager.hashId(id.lowercase()))
        assertFalse(h.contains(id))
    }

    @Test
    fun `a chat wallpaper is persisted, chosen or received`() {
        val repo = SourceText.read("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
        val set = SourceText.window(repo, "suspend fun setWallpaper(", 900)
        assertTrue("setWallpaper does not persist", set.contains("persistWallpaper(conversationId, wallpaperData)"))
        val received = SourceText.window(repo, "private suspend fun handleWallpaperUpdate(", 2200)
        assertTrue("a received wallpaper is not persisted", received.contains("persistWallpaper(conversationId, wallpaperData)"))
        val vm = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/ChatViewModel.kt")
        assertTrue("the chat does not read the persisted wallpaper", vm.contains("wallpaperManager.storedWallpaper(pk)"))
    }

    @Test
    fun `groups and channels can pick a wallpaper from the chat`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/GroupChatScreen.kt")
        assertTrue("no menu entry", src.contains("onClick = { showOverflow = false; onWallpaper() }"))
        assertTrue("no picker", src.contains("WallpaperPickerSheet("))
        assertTrue("the wallpaper is not drawn behind the thread", src.contains("ChatWallpaperBackground("))
    }

    @Test
    fun `settings chat wallpaper opens the picker, not only the tone`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/SettingsScreen.kt")
        assertTrue(src.contains("onClick = { showGlobalWallpaperPicker = true }"))
        assertTrue(src.contains("globalMode = true"))
    }

    @Test
    fun `both threads run under frosted bars, edge to edge`() {
        for (f in listOf("ChatScreen.kt", "GroupChatScreen.kt")) {
            val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/$f")
            assertTrue("$f: thread not frosted", src.contains(".frostedBackdropSource("))
            assertTrue("$f: bars not told the frost is live", src.contains("live = liveBackdrop"))
            assertTrue("$f: list not padded under the bars", src.contains("padding.calculateTopPadding()"))
            assertTrue("$f: not drawn behind the status bar", src.contains(".extendUp(statusBarUnderlap)"))
        }
    }
}
