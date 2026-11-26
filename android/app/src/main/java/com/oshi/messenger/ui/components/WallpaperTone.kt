package com.oshi.messenger.ui.components

import android.content.Context
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.BubbleStyle
import com.oshi.messenger.ui.theme.LocalBubbleAppearance
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.oshiColorScheme

/**
 * __WALLPAPER_TONE_2026_09_24__ Wallpaper TONE — light / dark / automatic,
 * chosen independently of the system appearance. Port of iOS
 * `ChatWallpaperAppearance.swift`.
 *
 * The OSHI backdrops draw on a white ground in light mode and a black one in
 * dark mode; the tone pins one of the two. When it does, the THREAD on top
 * (bubbles, text, timestamps, badges) renders in that same scheme, so a white
 * mesh in dark mode carries light-mode bubbles — the palette already tuned
 * for that ground — instead of white text on white.
 *
 * Local preference only: not part of [ChatWallpaper]'s wire format (which the
 * peer and older builds decode), and not synced — no appearance sync exists.
 */
enum class WallpaperTone(val stored: String) {
    AUTOMATIC("automatic"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStored(value: String?): WallpaperTone =
            entries.firstOrNull { it.stored == value } ?: AUTOMATIC
    }
}

/** What a conversation renders with. */
data class WallpaperToneResolution(
    val wallpaperDark: Boolean,
    val contentDark: Boolean,
    val toneApplies: Boolean
)

object WallpaperToneResolver {
    /**
     * The backdrops that have a light and a dark version. On Android that is
     * the drawn OSHI set; the flat Default, colours, gradients, emoji and
     * photos carry their own colours and are left exactly as they were.
     */
    fun followsTone(wallpaper: ChatWallpaper): Boolean = wallpaper is ChatWallpaper.OshiDynamic

    fun resolve(tone: WallpaperTone, systemDark: Boolean): Boolean = when (tone) {
        WallpaperTone.AUTOMATIC -> systemDark
        WallpaperTone.LIGHT -> false
        WallpaperTone.DARK -> true
    }

    /** Pure: setting × system appearance → schemes. Unit-tested. */
    fun resolve(wallpaper: ChatWallpaper, tone: WallpaperTone, systemDark: Boolean): WallpaperToneResolution {
        if (!followsTone(wallpaper)) {
            return WallpaperToneResolution(systemDark, systemDark, toneApplies = false)
        }
        val dark = resolve(tone, systemDark)
        return WallpaperToneResolution(dark, dark, toneApplies = true)
    }
}

/**
 * Persistence. Lives in the existing `wallpaper` prefs file (so account
 * deletion's prefs sweep already covers it). A default tone plus per-chat
 * overrides, mirroring iOS `ChatWallpaperManager.defaultTone` / `tones`.
 */
object WallpaperTonePrefs {
    private const val FILE = "wallpaper"
    private const val KEY_DEFAULT = "tone_default"
    private const val KEY_CONV_PREFIX = "tone_conv_"

    /** Bumped on every write so composables reading a tone recompose. */
    private val version = mutableIntStateOf(0)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun defaultTone(context: Context): WallpaperTone {
        version.intValue
        return WallpaperTone.fromStored(prefs(context).getString(KEY_DEFAULT, null))
    }

    fun setDefaultTone(context: Context, tone: WallpaperTone) {
        prefs(context).edit().putString(KEY_DEFAULT, tone.stored).apply()
        version.intValue++
    }

    /** The chat's own tone, or the default. */
    fun tone(context: Context, conversationId: String): WallpaperTone {
        version.intValue
        val own = prefs(context).getString(KEY_CONV_PREFIX + conversationId, null)
        return if (own != null) WallpaperTone.fromStored(own) else defaultTone(context)
    }

    fun setTone(context: Context, conversationId: String, tone: WallpaperTone) {
        prefs(context).edit().putString(KEY_CONV_PREFIX + conversationId, tone.stored).apply()
        version.intValue++
    }

    fun removeTone(context: Context, conversationId: String) {
        prefs(context).edit().remove(KEY_CONV_PREFIX + conversationId).apply()
        version.intValue++
    }
}

/** Renders [content] in the app's light or dark scheme, whatever the system. */
@Composable
fun WithChatScheme(dark: Boolean, content: @Composable () -> Unit) {
    if (isDarkScheme() == dark) {
        content()
    } else {
        val oled = com.oshi.messenger.ui.theme.LocalOledBlack.current
        val glass = com.oshi.messenger.ui.theme.GlassDefaults.resolve(
            com.oshi.messenger.ui.theme.LocalGlassPref.current,
            com.oshi.messenger.ui.theme.GlassDefaults.styleFor(dark, oled),
        )
        val scheme = oshiColorScheme(dark, oled, glass)
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            // Text without an explicit colour inherits LocalContentColor, which
            // the surrounding Surface/Scaffold set for the OTHER scheme.
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────────

private val BrandGradient = Brush.linearGradient(listOf(Color(0xFF5616EC), Color(0xFFF0478F)))

/** A wallpaper drawn as a tiny conversation: backdrop + one received + one sent bubble. */
@Composable
fun WallpaperMiniChat(
    style: OshiDynamicStyle,
    dark: Boolean,
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    compact: Boolean = true
) {
    WithChatScheme(dark) {
        Box(modifier) {
            OshiDynamicWallpaperView(style = style, modifier = Modifier.fillMaxSize(), animated = animated)
            // __CHAT_POLISH_2026_09_24__ The real bubble surfaces, not Material's
            // surfaceVariant / a flat purple: the tile must show what the chat
            // will actually look like (iOS WallpaperMiniChat does).
            val received = SolidColor(Oshi.Bubble.received(dark, com.oshi.messenger.ui.theme.isOledScheme()))
            val onReceived = MaterialTheme.colorScheme.onSurface
            val sentAccent = LocalBubbleAppearance.current.accent
            val sent = sentAccent.surfaceBrush(dark)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (compact) 8.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp, Alignment.CenterVertically)
            ) {
                MiniBubble(outgoing = false, compact = compact, fill = received, ink = onReceived,
                    rim = Oshi.Bubble.rim(outgoing = false, darkTheme = dark),
                    modifier = Modifier.align(Alignment.Start))
                MiniBubble(outgoing = true, compact = compact, fill = sent, ink = sentAccent.ink(dark),
                    rim = Oshi.Bubble.rimInk(true, dark, sentAccent.isTonal(dark))
                        .copy(alpha = Oshi.Bubble.rimAlpha(true, dark, sentAccent.isTonal(dark))),
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun MiniBubble(outgoing: Boolean, compact: Boolean, fill: Brush, ink: Color, rim: Color, modifier: Modifier) {
    val r = if (compact) 12.dp else 18.dp
    val tail = BubbleStyle.tailRadius(r)
    val shape = RoundedCornerShape(
        topStart = r, topEnd = r,
        bottomStart = if (outgoing) r else tail,
        bottomEnd = if (outgoing) tail else r
    )
    Box(modifier.background(fill, shape).border(Oshi.Bubble.rimWidth, rim, shape).padding(horizontal = if (compact) 7.dp else 12.dp,
        vertical = if (compact) 6.dp else 8.dp)) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(if (outgoing) 30.dp else 38.dp).height(3.dp)
                    .background(ink.copy(alpha = if (outgoing) 0.9f else 0.55f), CircleShape))
                Box(Modifier.width(if (outgoing) 18.dp else 24.dp).height(3.dp)
                    .background(ink.copy(alpha = if (outgoing) 0.9f else 0.55f), CircleShape))
            }
        } else {
            Text(
                stringResource(if (outgoing) R.string.wallpaper_preview_outgoing else R.string.wallpaper_preview_incoming),
                color = ink, fontSize = 13.sp
            )
        }
    }
}

/** Bottom-right triangle: the dark half of the "Automatic" card. */
private object DiagonalHalf : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply {
            moveTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        })
}

@Composable
private fun toneLabel(tone: WallpaperTone): String = stringResource(
    when (tone) {
        WallpaperTone.AUTOMATIC -> R.string.wallpaper_tone_automatic
        WallpaperTone.LIGHT -> R.string.wallpaper_tone_light
        WallpaperTone.DARK -> R.string.wallpaper_tone_dark
    }
)

@Composable
fun toneDetail(tone: WallpaperTone): String = stringResource(
    when (tone) {
        WallpaperTone.AUTOMATIC -> R.string.wallpaper_tone_automatic_detail
        WallpaperTone.LIGHT -> R.string.wallpaper_tone_light_detail
        WallpaperTone.DARK -> R.string.wallpaper_tone_dark_detail
    }
)

/** One tone choice, shown as a mini chat in that tone, with a brand-gradient border when selected. */
@Composable
fun WallpaperToneCard(
    tone: WallpaperTone,
    style: OshiDynamicStyle,
    selected: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
    onClick: () -> Unit
) {
    val label = toneLabel(tone)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = label
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val cardShape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(cardShape)
                .then(
                    if (selected) Modifier.border(3.dp, BrandGradient, cardShape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                )
        ) {
            val inner = Modifier.fillMaxSize().clip(cardShape)
            when (tone) {
                WallpaperTone.LIGHT -> WallpaperMiniChat(style, dark = false, modifier = inner)
                WallpaperTone.DARK -> WallpaperMiniChat(style, dark = true, modifier = inner)
                WallpaperTone.AUTOMATIC -> Box(inner) {
                    WallpaperMiniChat(style, dark = false, modifier = Modifier.fillMaxSize())
                    WallpaperMiniChat(style, dark = true, modifier = Modifier.fillMaxSize().clip(DiagonalHalf))
                }
            }
            // Re-draw the border above the artwork so it is never covered.
            Box(
                Modifier.fillMaxSize().then(
                    if (selected) Modifier.border(3.dp, BrandGradient, cardShape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                )
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            ) {
                Box(
                    Modifier.size(22.dp).background(OSHIPurple, CircleShape).border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (tone) {
                    WallpaperTone.AUTOMATIC -> Icons.Default.Brightness4
                    WallpaperTone.LIGHT -> Icons.Default.LightMode
                    WallpaperTone.DARK -> Icons.Default.DarkMode
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (selected) brandLabelColor() else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) brandLabelColor() else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Brand violet that stays legible on the current surface. */
@Composable
private fun brandLabelColor(): Color = if (isDarkScheme()) Color(0xFFB9A2FF) else OSHIPurple

/** The three tone cards in a row. */
@Composable
fun WallpaperToneRow(
    style: OshiDynamicStyle,
    selected: WallpaperTone,
    cardHeight: Dp = 112.dp,
    onSelect: (WallpaperTone) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        WallpaperTone.entries.forEach { t ->
            WallpaperToneCard(
                tone = t, style = style, selected = selected == t,
                modifier = Modifier.weight(1f), height = cardHeight
            ) { onSelect(t) }
        }
    }
}

/**
 * Settings → Appearance → Chat wallpaper. The app-wide default tone, with a
 * large animated preview that cross-fades on every tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWallpaperToneSheet(onDismiss: () -> Unit) {
    OshiModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        ChatWallpaperTonePanel(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        )
    }
}

/** The sheet's content, on its own so it can also be rendered for capture. */
@Composable
fun ChatWallpaperTonePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tone = WallpaperTonePrefs.defaultTone(context)
    val systemDark = isDarkScheme()
    val previewDark = WallpaperToneResolver.resolve(tone, systemDark)
        Column(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.wallpaper_chat_wallpaper),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Crossfade(targetState = previewDark, animationSpec = tween(350), label = "tonePreview") { dark ->
                WallpaperMiniChat(
                    style = OshiDynamicStyle.DEFAULT,
                    dark = dark,
                    animated = true,
                    compact = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.wallpaper_tone_section).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            WallpaperToneRow(style = OshiDynamicStyle.DEFAULT, selected = tone) {
                WallpaperTonePrefs.setDefaultTone(context, it)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                toneDetail(tone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.wallpaper_tone_more),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
}
