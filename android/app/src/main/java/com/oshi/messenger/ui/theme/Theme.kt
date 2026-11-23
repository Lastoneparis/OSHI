package com.oshi.messenger.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.oshi.messenger.R

// ─────────────────────────────────────────────────────────────────────────
// Chat bubble style — propagated through the composition tree from
// MainActivity so chat screens can read the user's "Style de bulles"
// preference (Modern / Classic / Minimal) without re-injecting the
// SettingsViewModel everywhere. See bubbleShape() for the per-style
// corner geometry.
// ─────────────────────────────────────────────────────────────────────────

enum class BubbleStyle(val radius: Dp) {
    /** Default WhatsApp-like rounded look with an asymmetric tail corner. */
    Modern(18.dp),
    /** All four corners equally rounded — classic SMS / iMessage. */
    Classic(12.dp),
    /** Tight 4 dp corners — flat, paper-like. */
    Minimal(4.dp);

    /**
     * Shape used for a chat bubble.
     *
     * 🔴 TWO DIVERGENCES FROM iOS, BOTH FIXED 2026-08-23 (F-13's leftovers).
     *
     * **1. The tail was on the TOP corner.** iOS anchors it at the BOTTOM —
     * `bottomTrailingRadius` for outgoing, `bottomLeadingRadius` for incoming
     * (`BubbleStyleManager.swift:371-377`). This is not a coin flip: a bubble is
     * a speech balloon, and a balloon touches down at the speaker, which is
     * below and to their side. Tailed at the top it reads as a card with one
     * clipped corner — which is exactly what a reader who cannot say why the
     * Android build "looks off" is seeing.
     *
     * **2. Every bubble was tailed.** iOS tails only the LAST message of a run
     * from the same sender (`isTail`, `BubbleStyleManager.swift:296-304`), so a
     * burst of five messages reads as one column that touches down once instead
     * of five identical lozenges. [ChatThreadItems.build] computes that flag.
     *
     * The tail radius is iOS's `max(radius * 0.28, 4)` rather than a flat 4 dp:
     * it keeps the tail corner visibly tighter than its three neighbours at
     * every radius, and the floor keeps it from collapsing to a point.
     * Never a hard 90° — a right angle on a coloured bubble reads as a card.
     *
     * [Classic] and [Minimal] stay symmetric at every position. A user who
     * picks "Classic — all four corners equal" has asked for no tail, and
     * giving them one because iOS tails all five of ITS shapes would be porting
     * the mechanism past the point where it still means the same thing.
     */
    fun bubbleShape(isFromMe: Boolean, isTail: Boolean = true): Shape = when (this) {
        Modern -> {
            val r = radius
            val tail = tailRadius(r)
            RoundedCornerShape(
                topStart = r,
                topEnd = r,
                bottomStart = if (!isFromMe && isTail) tail else r,
                bottomEnd = if (isFromMe && isTail) tail else r
            )
        }
        Classic -> RoundedCornerShape(radius)
        Minimal -> RoundedCornerShape(radius)
    }

    companion object {
        /** iOS `max(radius * 0.28, 4)` — `BubbleStyleManager.swift:372`. */
        fun tailRadius(radius: Dp): Dp = maxOf(radius * 0.28f, 4.dp)

        /** Tolerant parser. Unknown / null values fall back to [Modern]. */
        fun fromPref(value: String?): BubbleStyle = when (value?.lowercase()) {
            "classic" -> Classic
            "minimal" -> Minimal
            else -> Modern
        }
    }
}

/**
 * Composition-local exposing the active [BubbleStyle]. Defaults to [BubbleStyle.Modern]
 * so previews and unit tests render correctly without the surrounding
 * MainActivity wiring.
 */
val LocalBubbleStyle = compositionLocalOf { BubbleStyle.Modern }

// OSHI Brand Colors (matching iOS: Color(red: 0.337, green: 0.086, blue: 0.925) = #5616EC)
val OSHIPurple = Color(0xFF5616EC)
val OSHIPurpleDark = Color(0xFF4410D9)
val OSHIPurpleLight = Color(0xFF6F3FFF)
val OSHIBlue = Color(0xFF3B82F6)
val OSHIGreen = Color(0xFF22C55E)
val OSHIOrange = Color(0xFFF97316)
val OSHIRed = Color(0xFFEF4444)

private val DarkColorScheme = darkColorScheme(
    primary = OSHIPurple,
    onPrimary = Color.White,
    primaryContainer = OSHIPurpleDark,
    onPrimaryContainer = Color.White,
    secondary = OSHIBlue,
    onSecondary = Color.White,
    tertiary = OSHIGreen,
    background = Color(0xFF0F0F0F),
    onBackground = Color.White,
    surface = Color(0xFF1A1A1A),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = OSHIRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = OSHIPurple,
    onPrimary = Color.White,
    primaryContainer = OSHIPurpleLight,
    onPrimaryContainer = Color.Black,
    secondary = OSHIBlue,
    onSecondary = Color.White,
    tertiary = OSHIGreen,
    background = Color(0xFFFAFAFA),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF606060),
    error = OSHIRed,
    onError = Color.White
)

// ─────────────────────────────────────────────────────────────────────────
// __OLED_BLACK_2026_11_16__ « Noir OLED » / "OLED black".
//
// A user asked for "darker colours for OLED screens". It is a MODIFIER of the
// dark theme, not a fourth theme: Settings → Appearance keeps System / Light /
// Dark and adds one switch that applies whenever the resolved theme is dark
// (forced Dark, or System while the phone is dark). Same name and same rule on
// iOS and desktop.
//
// What changes, and why each value is what it is:
//
// * `background` and `surface` are #000000. On an OLED panel a black pixel is
//   an unlit pixel; #0F0F0F / #1A1A1A (the regular dark scheme) light every
//   pixel of the screen. Every list, top bar, the bottom bar (tonal 0) and the
//   Scaffold body read one of these two, so this single change is most of it.
// * `surfaceTint` is a NEUTRAL grey instead of the brand purple. Material 3
//   (1.1.x here) lifts a container whose colour IS `colorScheme.surface` by
//   `surfaceTint` at an alpha that grows with tonal elevation
//   (`ColorScheme.surfaceColorAtElevation`). With the brand purple that turned
//   every dialog / sheet / menu a purplish grey. Transparent would make them
//   pure black on pure black — a dialog with no edge. #C4C4C4 is picked so the
//   ladder lands exactly in the near-black band, and only where Material
//   itself says a layer floats: sheet (1 dp) ≈ #0A0A0A, composer (2 dp)
//   ≈ #0E0E0E, menu (3 dp) ≈ #101010, dialog (6 dp) ≈ #151515. Flat surfaces
//   (elevation 0) stay #000000. Held by `OledBlackThemeTest`.
// * `surfaceVariant` (cards, search fields, setting tiles: 100+ call sites)
//   is #141414 — enough to separate a card from the black ground, nowhere near
//   the #2A2A2A grey of the regular dark scheme. `onSurfaceVariant` #B0B0B0 on
//   it is 9.4:1 (WCAG AA wants 4.5:1).
// * Everything else — brand primary, error, the incoming bubble colour etc. —
//   is the regular dark palette; the incoming bubble has its own OLED value in
//   `Oshi.Bubble.received`.
// ─────────────────────────────────────────────────────────────────────────

/** The neutral tint that replaces brand purple as `surfaceTint` in OLED black. */
val OledSurfaceTint = Color(0xFFC4C4C4)

/** Cards / tiles / fields on the black ground. */
val OledSurfaceVariant = Color(0xFF141414)

private val OledColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = OledSurfaceVariant,
    surfaceTint = OledSurfaceTint,
)

/**
 * Pure theme selection — what `MainActivity` resolves from the two
 * preferences. Kept out of Compose so it is unit-testable.
 */
object ThemeSelection {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    /** "dark" / "light" force; anything else (incl. "system", null) follows the phone. */
    fun resolveDark(themePref: String?, systemDark: Boolean): Boolean = when (themePref) {
        DARK -> true
        LIGHT -> false
        else -> systemDark
    }

    /** OLED black only ever modifies a DARK scheme; in light it is inert. */
    fun resolveOled(dark: Boolean, oledBlackPref: Boolean): Boolean = dark && oledBlackPref
}

/**
 * True while the OLED-black modifier is ON and the resolved theme is dark.
 * Provided by [OSHITheme]. Read it through [isOledScheme], which also checks
 * that the scheme in force is dark — a subtree re-themed light by
 * `WithChatScheme` must not get black-ground values.
 */
val LocalOledBlack = staticCompositionLocalOf { false }

/** Is the scheme in force the OLED black one? */
@Composable
@ReadOnlyComposable
fun isOledScheme(): Boolean = LocalOledBlack.current && isDarkScheme()

/**
 * __WALLPAPER_TONE_2026_09_24__ The app's own light / dark scheme, for a
 * subtree that must render in a given scheme regardless of the system — the
 * chat thread over a wallpaper whose tone is pinned (white mesh kept in dark
 * mode ⇒ light-mode bubbles on it). See `WallpaperTone.kt`.
 *
 * [oledBlack] selects the OLED black variant of the dark scheme; it is ignored
 * when [dark] is false.
 */
fun oshiColorScheme(dark: Boolean, oledBlack: Boolean = false): ColorScheme = when {
    dark && oledBlack -> OledColorScheme
    dark -> DarkColorScheme
    else -> LightColorScheme
}

@Composable
fun OSHITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    oledBlack: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val oled = ThemeSelection.resolveOled(darkTheme, oledBlack)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> oshiColorScheme(darkTheme, oled)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // `Window.setStatusBarColor` is a no-op from targetSdk 36: Android
            // 16 enforces edge-to-edge, the bars are transparent, and the
            // system draws its own contrast scrim. It is still called on older
            // devices running this same APK, where it is what keeps the status
            // bar from being a white strip over a dark app.
            //
            // __STATUS_BAR_SAME_COLOUR_2026_11_13__ (Anatole N7) `surface`, not
            // `background`: every top bar and the bottom bar are `surface`
            // (dark #1A1A1A / light white) while `background` is #0F0F0F / #FAFAFA,
            // so the clock strip was one shade off the bar right under it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.surface.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.surface.toArgb()
            }
            // This one still matters everywhere, and matters MORE once the bar
            // is transparent: it decides whether the clock and battery icons
            // are drawn dark (on our light background) or light (on the dark
            // one). Get it wrong on Android 16 and the status bar is invisible.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalOledBlack provides oled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// TYPE
//
// Until now this file ended on `val Typography = Typography()` — the stock
// Material 3 default, which is Roboto at Google's metrics. No font resource
// shipped at all. That is why the app reads as a template: colour and shape
// had been tuned, the letterforms had not, and letterforms are most of what a
// reader actually sees in a messenger.
//
// `res/font/inter_variable.ttf` is Inter as a VARIABLE font: two axes,
// `wght` 100–900 and `opsz` 14–32. One 856 KB file covers every weight the
// app asks for, instead of five static TTFs.
//
// Compose can drive `wght` directly (`FontVariation`, Compose UI 1.5+; the
// BOM here is 2023.10.01 = UI 1.5.4). That matters: without variation
// settings a single-file family gives Compose ONE real weight and it fakes
// the rest by smearing the outlines (`FontSynthesis`), which is what makes
// synthetic bold look muddy at 11 sp. `FontVariation.weight()` instead asks
// the font for the genuine instance the designer drew. The `wght` axis needs
// `Typeface.Builder.setFontVariationSettings`, API 26 — `minSdk` here is 26,
// so it applies on every device this APK installs on, with no fallback path.
//
// `opsz` is deliberately left alone: a `FontFamily` selects by weight and
// style only, so optical size cannot vary per type slot through this API, and
// Inter's default of 14 is right for the sizes below.
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight): Font = Font(
    R.font.inter_variable,
    weight,
    FontStyle.Normal,
    // Named, because `loadingStrategy` sits between `style` and this one and
    // its default (Blocking) is what a bundled resource font wants.
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/**
 * The five weights the codebase actually asks for: `FontWeight.Light` (one
 * call site), `Normal`, `Medium`, `SemiBold` and `Bold`. Declaring exactly
 * these means every `.copy(fontWeight = …)` in the screens resolves to a real
 * drawn instance rather than a synthesised one.
 */
val InterFontFamily = FontFamily(
    inter(FontWeight.Light),     // 300
    inter(FontWeight.Normal),    // 400
    inter(FontWeight.Medium),    // 500
    inter(FontWeight.SemiBold),  // 600
    inter(FontWeight.Bold)       // 700
)

/**
 * OSHI's type scale.
 *
 * Built around the six roles that carry a messenger, mapped onto the M3 slots
 * the screens already reference (counted with
 * `grep -rhoE 'typography\.[A-Za-z]+'`):
 *
 * | role                          | slot          | uses |
 * |-------------------------------|---------------|------|
 * | timestamp / status / metadata | `bodySmall`   |  73  |
 * | badge, read receipt, chip     | `labelSmall`  |  56  |
 * | secondary body, list preview  | `bodyMedium`  |  27  |
 * | section header, small emphasis| `labelMedium` |  20  |
 * | sender name, row title        | `titleMedium` |  17  |
 * | sub-section title             | `titleSmall`  |  12  |
 * | screen title / app bar        | `titleLarge`  |   9  |
 * | chat body                     | `bodyLarge`   |   9  |
 * | button label                  | `labelLarge`  |   3  |
 * | empty-state heading           | `headlineSmall`|  2  |
 *
 * The `headline*`/`display*` slots above `headlineSmall` are never read by
 * OSHI's own code, but Material's components fall back to them and an
 * undeclared slot keeps the M3 default — i.e. Roboto. They are defined so no
 * surface can quietly render in a second typeface, not because a screen wants
 * a 57 sp headline.
 *
 * Three deliberate choices run through the whole scale:
 *
 * 1. **Tracking narrows as size grows.** Inter is drawn for UI, and at 22 sp
 *    and up its default spacing reads loose and web-like. Large titles are
 *    pulled in (−0.4 sp at 22 sp, down to −1.6 sp at 56 sp); small labels are
 *    pushed out (+0.4 / +0.5 sp at 11–12 sp), where letters otherwise collide
 *    and uppercase micro-labels turn into a smudge.
 * 2. **Body breathes, titles do not.** Chat text runs at line height ≈ 1.4×
 *    (16/23) because it wraps to many lines; a screen title never wraps twice,
 *    so titles run ≈ 1.2× and the header stays compact.
 * 3. **SemiBold, not Bold, is the emphasis weight.** 600 separates a sender
 *    name from its message preview without the shouting of 700, and it is
 *    already the weight the screens reach for most (142 `SemiBold` call sites
 *    against 138 `Bold`).
 *
 * Chat body is 16 sp — the readability floor for text a user reads for
 * minutes at a time, and one step above M3's 14 sp `bodyMedium` default.
 */
val Typography = Typography(
    // ── Display. Not used by OSHI; present so nothing falls back to Roboto.
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 62.sp,
        letterSpacing = (-1.6).sp
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.2).sp
    ),
    displaySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.0).sp
    ),

    // ── Headline. `headlineSmall` is the empty-state / dialog heading.
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp
    ),

    // ── Title. Screen titles, app bars, sender names, row headings.
    /** Screen title / `TopAppBar`. Tight tracking is what makes it look set. */
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp
    ),
    /**
     * Sender name and conversation-row title. Same 16 sp as chat body but at
     * 600 and on a tighter 22 sp leading — a name is one line, and the weight
     * alone has to lift it off the 15 sp preview underneath.
     */
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    // ── Body. The text people came to read.
    /** Chat message text. 16 sp floor, ~1.4 leading, neutral tracking. */
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
    ),
    /** Secondary body: message preview in the list, dialog and sheet copy. */
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    /**
     * Timestamps, delivery status, helper text — the app's single most-used
     * slot. 13 sp rather than M3's 12: a timestamp that cannot be read at a
     * glance is furniture, and one extra point costs no layout.
     */
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),

    // ── Label. Buttons, chips, badges, section headers.
    /** Button label. Material reads this slot for every `Button`'s content. */
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    /** Section header and chip text. Wider tracking; these are often caps. */
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    /** Badges, unread counts, read receipts — the smallest thing we ship. */
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
