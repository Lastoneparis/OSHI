package com.oshi.messenger.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.ui.components.FrostedChatBarBackdrop
import com.oshi.messenger.ui.components.GroupHeaderBar
import com.oshi.messenger.ui.theme.GroupAvatar
import com.oshi.messenger.ui.theme.LocalGlassPref
import com.oshi.messenger.ui.theme.OSHITheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * __GROUP_HEADER_ISLAND_2026_11_24__ The group chat's floating header (back,
 * search, name island, avatar). The behaviour test always runs; the PNG shots are
 * opt-in like [DesignShotsTest]: `./gradlew :app:testDebugUnitTest --tests
 * '*GroupHeaderShotsTest' -Pshots=<dir>`.
 *
 * Each shot stacks the header four times — over the plain bar, over a "wallpaper",
 * with a long name, with someone typing — so one picture per theme shows them all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class GroupHeaderShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val wallpaper = Brush.linearGradient(listOf(Color(0xFFE7A0AE), Color(0xFFB79AC8), Color(0xFF8E7BB8)))

    @Composable
    private fun header(title: String, subtitle: String, typing: Boolean = false, onInfo: () -> Unit = {}, onLong: () -> Unit = {}) {
        GroupHeaderBar(
            title = title,
            subtitle = subtitle,
            subtitleIsActivity = typing,
            onBack = {}, onSearch = {}, onInfo = onInfo, onAvatarLongPress = onLong,
            avatar = { size -> GroupAvatar(groupId = title, name = title, size = size) },
        )
    }

    @Composable
    private fun gallery(rtl: Boolean) {
        Column(Modifier.fillMaxSize()) {
            // 1. On the frosted band as it is drawn without a wallpaper.
            Box {
                FrostedChatBarBackdrop(wallpaper = null, scrimColor = MaterialTheme.colorScheme.surface, modifier = Modifier.matchParentSize())
                Column { Spacer(Modifier.height(24.dp)); header("Swiss-Quant.CH 🇨🇭", if (rtl) "۳۰ مشترک" else "30 subscribers") }
            }
            Spacer(Modifier.height(16.dp))
            // 2-4. Over a wallpaper (the band is translucent there).
            Box(Modifier.fillMaxWidth().background(wallpaper).padding(vertical = 12.dp)) {
                Column {
                    header("OSHI Team Chat", if (rtl) "۸ عضو" else "8 members")
                    Spacer(Modifier.height(12.dp))
                    header("Climbing club — Fontainebleau weekend trips & carpooling 🧗", "124 members")
                    Spacer(Modifier.height(12.dp))
                    header("Famille", "Camille is typing…", typing = true)
                    Spacer(Modifier.height(12.dp))
                    header("Q", "1 member")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Group header — ${if (rtl) "RTL" else "LTR"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    private fun shot(name: String, dark: Boolean, oled: Boolean = false, rtl: Boolean = false, glass: Boolean? = null) {
        val dir = System.getProperty("design.shots")
        assumeTrue("design shots are opt-in (-Pshots=<dir>)", dir != null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OSHITheme(darkTheme = dark, oledBlack = oled) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalGlassPref provides (glass ?: LocalGlassPref.current),
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { gallery(rtl) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun headerLight() = shot("group_header_1_light", dark = false)
    @Test fun headerLightGlass() = shot("group_header_2_light_glass", dark = false, glass = true)
    @Test fun headerDark() = shot("group_header_3_dark", dark = true)
    @Test fun headerOled() = shot("group_header_4_oled", dark = true, oled = true)
    @Test fun headerRtl() = shot("group_header_5_rtl_oled", dark = true, oled = true, rtl = true)
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 1.3f)
    fun headerSmallLargeFont() = shot("group_header_6_small_font130_light", dark = false)

    /** Tap on the island or the avatar opens info; long-press on the avatar opens the old menu. */
    @Test fun islandAndAvatarAreWired() {
        var info = 0
        var menu = 0
        rule.setContent {
            OSHITheme(darkTheme = true, oledBlack = true) {
                header("OSHI Team Chat", "8 members", onInfo = { info++ }, onLong = { menu++ })
            }
        }
        rule.onNodeWithTag("group_header_island").assertHasClickAction()
        rule.onNodeWithTag("group_header_island").performTouchInput { click() }
        rule.onNodeWithTag("group_header_avatar").performTouchInput { click() }
        rule.onNodeWithTag("group_header_avatar").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(2, info)
        assertEquals(1, menu)
    }
}
