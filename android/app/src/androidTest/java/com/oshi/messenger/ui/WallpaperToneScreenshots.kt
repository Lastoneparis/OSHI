package com.oshi.messenger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.ChatWallpaperTonePanel
import com.oshi.messenger.ui.components.OshiDynamicStyle
import com.oshi.messenger.ui.components.OshiDynamicWallpaperView
import com.oshi.messenger.ui.components.WallpaperTone
import com.oshi.messenger.ui.components.WallpaperTonePrefs
import com.oshi.messenger.ui.components.WallpaperToneResolver
import com.oshi.messenger.ui.components.WithChatScheme
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHITheme
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.isDarkScheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * __WALLPAPER_TONE_2026_09_24__ Captures of the wallpaper tone: the Settings
 * panel (dark + light) and a chat THREAD on a dark phone with the Light tone,
 * built from the same pieces ChatScreen uses (WithChatScheme +
 * OshiDynamicWallpaperView + Oshi.Bubble colours) with fixture messages.
 * PNGs land in `<external files>/wallpaper-shots/`.
 *
 * Only runs when asked: `-e wallpaperShots true`.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperToneScreenshots {

    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun enabled() = InstrumentationRegistry.getArguments().getString("wallpaperShots") == "true"

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
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "wallpaper-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private data class Msg(val text: String, val mine: Boolean, val time: String)

    private val msgs = listOf(
        Msg("Each user has a unique keypair. Messages are encrypted before they leave the phone.", false, "16:47"),
        Msg("That's impressive. Can I also make voice and video calls?", true, "16:57"),
        Msg("Yes! Voice and video calls are fully encrypted too.", false, "17:07"),
        Msg("I just tried the voice call, the audio quality is excellent!", true, "17:17"),
        Msg("Glad you like it! Works over Bluetooth mesh too.", false, "17:22"),
    )

    @Composable
    private fun Thread(tone: WallpaperTone) {
        val wallpaper = ChatWallpaper.OshiDynamic(OshiDynamicStyle.MESH.ordinal)
        val threadDark = WallpaperToneResolver.resolve(wallpaper, tone, isDarkScheme()).contentDark
        Column(Modifier.fillMaxSize()) {
            // App bar, as ChatScreen draws it (outside the wallpaper box).
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                Text("Alice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            WithChatScheme(dark = threadDark) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    OshiDynamicWallpaperView(OshiDynamicStyle.MESH, Modifier.fillMaxSize(), animated = false)
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val dark = isDarkScheme()
                        msgs.forEach { m ->
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start
                            ) {
                                Box(
                                    Modifier.widthIn(max = 280.dp)
                                        .background(if (m.mine) OSHIPurple else Oshi.Bubble.received(dark), RoundedCornerShape(18.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(m.text, fontSize = 16.sp,
                                        color = if (m.mine) Color.White else MaterialTheme.colorScheme.onSurface)
                                }
                                Text(m.time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
            // Composer bar, also outside the wallpaper box on Android.
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
    fun captureWallpaperTone() {
        assumeTrue(enabled())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = WallpaperTonePrefs.defaultTone(ctx)
        try {
            shoot("android_dark_chat_light_wallpaper", dark = true) { Thread(WallpaperTone.LIGHT) }
            shoot("android_dark_chat_automatic_before", dark = true) { Thread(WallpaperTone.AUTOMATIC) }

            WallpaperTonePrefs.setDefaultTone(ctx, WallpaperTone.LIGHT)
            shoot("android_dark_picker", dark = true) { ChatWallpaperTonePanel(Modifier.fillMaxWidth().padding(top = 16.dp)) }
            WallpaperTonePrefs.setDefaultTone(ctx, WallpaperTone.AUTOMATIC)
            shoot("android_light_picker", dark = false) { ChatWallpaperTonePanel(Modifier.fillMaxWidth().padding(top = 16.dp)) }
        } finally {
            WallpaperTonePrefs.setDefaultTone(ctx, saved)
        }
    }
}
