package com.oshi.messenger.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────
// Window size classes.
//
// Android 16 (API 36) IGNORES `android:screenOrientation` and
// `resizeableActivity=false` on any display whose smallest width is ≥ 600 dp,
// so from targetSdk 36 onward a tablet or unfolded foldable WILL rotate this
// app into landscape whether the manifest asks for it or not. Every layout
// therefore has to hold up at any width, and the portrait lock we used to
// lean on is gone.
//
// Deliberately hand-rolled rather than pulling in
// `androidx.compose.material3:material3-window-size-class`: the breakpoints
// are the published Material 3 ones, the whole thing is ~30 lines, it is a
// pure function of the configuration (so it unit-tests on the JVM with no
// Compose runtime), and it adds no dependency to a module that already ships
// 82 MB of native AI weights.
//
// Breakpoints (Material 3 / Android large-screen guidelines):
//   width  — Compact < 600 dp ≤ Medium < 840 dp ≤ Expanded
//   height — Compact < 480 dp ≤ Medium < 900 dp ≤ Expanded
// ─────────────────────────────────────────────────────────────────────────

enum class WidthClass { Compact, Medium, Expanded }

enum class HeightClass { Compact, Medium, Expanded }

data class OshiWindowSize(
    val widthDp: Int,
    val heightDp: Int,
    val width: WidthClass,
    val height: HeightClass
) {
    /**
     * A left-hand navigation rail replaces the bottom bar once the window is no
     * longer compact-width — a tablet, an unfolded foldable, a desktop window,
     * or a phone turned landscape (869 dp on a Pixel 6, comfortably Expanded).
     *
     * Width alone, deliberately. An earlier version also raised the rail on any
     * short window, which sounds right for a landscape phone — a bottom bar
     * costs ~80 dp of the ~360 dp of height it has left — but a landscape phone
     * is already caught by the width test, and the only windows the extra
     * clause actually changed were compact-width AND short ones: a split-screen
     * half, ~430 x 400 dp. There an 80 dp rail eats a fifth of the width to save
     * a fifth of the height, which is not a trade worth making, and it
     * contradicts the Material 3 rule that compact width means a bottom bar.
     */
    val useNavigationRail: Boolean
        get() = width != WidthClass.Compact

    /**
     * Cap for text-and-list content. Reading a chat or a settings list across
     * 1280 dp is unusable; past this width the pane is centred and the extra
     * space becomes a gutter. Full-bleed surfaces — the map, turn-by-turn
     * navigation and the call screens — opt out; see `isFullBleedRoute` in
     * MainActivity, which is where the cap is applied, once, for every
     * destination in the graph.
     */
    val contentMaxWidth: Dp
        get() = when (width) {
            WidthClass.Compact -> Dp.Unspecified
            WidthClass.Medium -> 720.dp
            WidthClass.Expanded -> 1040.dp
        }
}

fun widthClassOf(widthDp: Int): WidthClass = when {
    widthDp < 600 -> WidthClass.Compact
    widthDp < 840 -> WidthClass.Medium
    else -> WidthClass.Expanded
}

fun heightClassOf(heightDp: Int): HeightClass = when {
    heightDp < 480 -> HeightClass.Compact
    heightDp < 900 -> HeightClass.Medium
    else -> HeightClass.Expanded
}

fun windowSizeOf(widthDp: Int, heightDp: Int): OshiWindowSize =
    OshiWindowSize(widthDp, heightDp, widthClassOf(widthDp), heightClassOf(heightDp))

// ─────────────────────────────────────────────────────────────────────────
// Dialog / sheet metrics
//
// 🔴 WHY THIS EXISTS. Four surfaces in this app laid a big square of ART — a
// QR code, a wallpaper grid, a map preview — above a primary button, in a
// container with NO scroll, and sized the art in a fixed number:
//
//   • KeyBackupScreen's two QR dialogs are `Dialog(usePlatformDefaultWidth =
//     false)` → `OshiCard(padding 24)` → `Column(padding 24, spacedBy 16)` holding
//     a **260 dp** QR card plus an icon, a title, body copy, a caption and a
//     Button. That is ~590 dp of fixed content. On a landscape phone
//     (869 x 400 dp — and from targetSdk 36 a phone WILL rotate) the Save and
//     Close buttons are below the bottom edge, in a container that does not
//     scroll. The user's own recovery key becomes unexportable, on a screen
//     whose entire purpose is to export it.
//   • ChatWallpaper's sheet: 150 dp preview + tab row + a 200 dp grid + "Done"
//     ≈ 520 dp. The grid scrolls internally, so the swatches are reachable and
//     the button that APPLIES them is not.
//   • LocationSharing's sheet: 180 dp map preview + rows + a 52 dp share
//     button ≈ 440 dp.
//
// None of this reports an error, none of it clips visibly in portrait on the
// developer's own phone, and a screenshot of the top half looks perfect. So the
// arithmetic lives here as a pure function over a height and is asserted in
// `AdaptiveLayoutTest` against real window sizes, in the same shape as iOS's
// `CallControlMetrics.fitting(availableWidth:count:)`
// (`SharedUIComponents.swift:183-223`).
// ─────────────────────────────────────────────────────────────────────────

/**
 * How big a square of art (QR code, preview tile, hero image) may be, given the
 * window it has to share with everything else in its container.
 *
 * ORDER OF CONCESSION, deliberately: give up the ART's size first, because it
 * is the one element that degrades gracefully — a 160 dp QR still scans, a
 * button below the fold does not exist. Never below [minimum]; past that point
 * the caller must scroll instead, which is what [needsScroll] answers.
 *
 * @param windowHeightDp the window, from `OshiWindowSize.heightDp`.
 * @param reservedDp everything else in the container: padding, text, the
 *   button, the gaps. Measure it once and pass it; a guess that is too small is
 *   the bug this function exists to prevent.
 * @param preferred the size to use when there is room — the portrait look,
 *   unchanged.
 * @param minimum the floor. For a QR code this is a scannability limit, not an
 *   aesthetic one.
 */
fun fittedArtSize(
    windowHeightDp: Int,
    reservedDp: Int,
    preferred: Dp,
    minimum: Dp
): Dp {
    require(minimum <= preferred) { "the floor cannot be above the preferred size" }
    val available = windowHeightDp - reservedDp
    if (available >= preferred.value) return preferred
    if (available <= minimum.value) return minimum
    // Round DOWN: rounding up is how a layout that "just fits" in the maths
    // overflows by a pixel on a real display.
    return kotlin.math.floor(available.toDouble()).toInt().dp
}

/**
 * Does a container holding [contentHeightDp] of fixed content need to scroll in
 * this window?
 *
 * The margin is not decoration. A container reports its content height without
 * knowing about the gesture bar, a keyboard, or the caption line that grows to
 * three lines in German — so "it fits exactly" is the state immediately before
 * it does not. 24 dp is one `Space.xl`.
 */
fun needsScroll(windowHeightDp: Int, contentHeightDp: Int, marginDp: Int = 24): Boolean =
    contentHeightDp + marginDp > windowHeightDp

/**
 * Current window size. Recomposes on rotation, fold/unfold and free-form
 * window resize — `LocalConfiguration` is updated for all three, and
 * MainActivity declares `orientation|screenSize|smallestScreenSize|screenLayout`
 * in `configChanges` so the activity is not recreated for any of them.
 */
@Composable
@ReadOnlyComposable
fun rememberWindowSize(): OshiWindowSize {
    val configuration = LocalConfiguration.current
    return windowSizeOf(configuration.screenWidthDp, configuration.screenHeightDp)
}
