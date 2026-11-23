package com.oshi.messenger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.service.WallpaperManager
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.ChatWallpaperBackground
import com.oshi.messenger.ui.components.ChatWallpaperTonePanel
import com.oshi.messenger.ui.components.OshiDynamicStyle
import com.oshi.messenger.ui.components.WallpaperTone
import com.oshi.messenger.ui.components.WallpaperTonePrefs
import com.oshi.messenger.ui.components.WallpaperToneResolver
import com.oshi.messenger.ui.components.WithChatScheme
import com.oshi.messenger.ui.screens.MessageBubble
import com.oshi.messenger.ui.theme.OSHITheme
import com.oshi.messenger.ui.theme.isDarkScheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/**
 * __CHAT_POLISH_2026_09_24__ Before/after captures of the chat THREAD rendered
 * with the REAL `MessageBubble` from ChatScreen (fixture messages, no network),
 * over the wallpaper a NEW user gets (`WallpaperManager.globalWallpaper` — flat
 * beige before, Mesh after) and over Mesh explicitly; plus the tone panel.
 * The same file runs on the pre-change tree for the "before" set.
 * PNGs land in `<external files>/design-shots/`. Only runs with `-e designShots true`.
 */
@RunWith(AndroidJUnit4::class)
class ChatDesignShots {

    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun enabled() = InstrumentationRegistry.getArguments().getString("designShots") == "true"

    private var current by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var darkNow by mutableStateOf(false)
    private var hosted = false

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        if (!hosted) {
            rule.setContent {
                OSHITheme(darkTheme = darkNow) {
                    Surface(Modifier.fillMaxSize().testTag("shot"), color = MaterialTheme.colorScheme.background) {
                        current?.invoke()
                    }
                }
            }
            hosted = true
        }
        rule.runOnIdle { darkNow = dark; current = content }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val bmp: Bitmap = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
    }.timeInMillis

    private val me = "me"
    private val alice = "alice"

    private fun msg(id: String, text: String, mine: Boolean, h: Int, m: Int, read: Boolean = true,
                    method: DeliveryMethod = DeliveryMethod.VPS, edited: Boolean = false) = Message(
        id = id,
        senderPublicKey = if (mine) me else alice,
        recipientPublicKey = if (mine) alice else me,
        content = text,
        timestamp = at(h, m),
        isFromMe = mine,
        isRead = read,
        isDelivered = true,
        deliveryMethod = method,
        editedAt = if (edited) at(h, m + 1) else null
    )

    // (message, isTail)
    private val thread = listOf(
        msg("1", "Each user has a unique keypair. Messages are encrypted before they leave the phone.", false, 16, 47) to true,
        msg("2", "That's impressive.", true, 16, 56) to false,
        msg("3", "Can I also make voice and video calls?", true, 16, 57) to true,
        msg("4", "Yes! Voice and video calls are fully encrypted too.", false, 17, 7, method = DeliveryMethod.MESH) to true,
        msg("5", "I just tried the voice call, the audio quality is excellent!", true, 17, 17, edited = true) to true,
        msg("6", "Glad you like it! Works over LoRa radio too, no network needed.", false, 17, 22, method = DeliveryMethod.LORA) to true,
        msg("7", "Sending one now 📡", true, 17, 24, read = false, method = DeliveryMethod.MESH) to true,
    )

    @Composable
    private fun Thread(wallpaper: ChatWallpaper, tone: WallpaperTone) {
        val threadDark = WallpaperToneResolver.resolve(wallpaper, tone, isDarkScheme()).contentDark
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                Text("Alice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            WithChatScheme(dark = threadDark) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ChatWallpaperBackground(wallpaper, Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                        thread.forEach { (m, tail) -> MessageBubble(message = m, isTail = tail) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                Box(Modifier.weight(1f).height(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Test
    fun captureChatDesign() {
        assumeTrue(enabled())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "android"
        val saved = WallpaperTonePrefs.defaultTone(ctx)
        // What a brand-new user's chat shows (no stored global wallpaper).
        ctx.getSharedPreferences("wallpaper", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val newUserDefault = WallpaperManager(ctx).globalWallpaper.value
        val mesh = ChatWallpaper.OshiDynamic(OshiDynamicStyle.MESH.ordinal)
        try {
            shoot("${prefix}_chat_default_light", dark = false) { Thread(newUserDefault, WallpaperTone.AUTOMATIC) }
            shoot("${prefix}_chat_light", dark = false) { Thread(mesh, WallpaperTone.AUTOMATIC) }
            shoot("${prefix}_chat_dark", dark = true) { Thread(mesh, WallpaperTone.AUTOMATIC) }
            shoot("${prefix}_chat_lighttone_in_dark", dark = true) { Thread(mesh, WallpaperTone.LIGHT) }

            WallpaperTonePrefs.setDefaultTone(ctx, WallpaperTone.AUTOMATIC)
            shoot("${prefix}_picker_light", dark = false) { ChatWallpaperTonePanel(Modifier.fillMaxWidth().padding(top = 16.dp)) }
            shoot("${prefix}_picker_dark", dark = true) { ChatWallpaperTonePanel(Modifier.fillMaxWidth().padding(top = 16.dp)) }
            WallpaperTonePrefs.setDefaultTone(ctx, WallpaperTone.LIGHT)
            shoot("${prefix}_picker_lighttone_in_dark", dark = true) { ChatWallpaperTonePanel(Modifier.fillMaxWidth().padding(top = 16.dp)) }
        } finally {
            WallpaperTonePrefs.setDefaultTone(ctx, saved)
        }
    }
}
