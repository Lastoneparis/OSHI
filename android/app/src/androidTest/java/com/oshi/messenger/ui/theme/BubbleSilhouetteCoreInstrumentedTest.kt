package com.oshi.messenger.ui.theme

import android.graphics.Region
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * 🫧 THE CONTRACT [BubbleSilhouette.coreInsets] EXISTS TO KEEP.
 *
 * A silhouette is drawn AROUND a reading core: `padding(coreInsets)` runs before
 * `background`, so the text occupies `size - coreInsets` and the decoration is
 * whatever the shape adds outside it. If any corner of that core falls OUTSIDE the
 * outline, characters sit on the chat background with no bubble behind them.
 *
 * That is not hypothetical. `BalloonShape` was `addOval(0, 0, w, bodyBottom)`, and an
 * ellipse contains a rectangle inset only by a FRACTION of its own size while
 * `coreInsets` is a fixed dp — so at a realistic one-line bubble the core poked out at
 * all four corners. This test fails on that version and passes on the current one.
 *
 * The sizes matter as much as the shapes. Bubbles are capped at 300.dp wide
 * (`ChatScreen.widthIn(max = 300.dp)`) but grow arbitrarily TALL, so the tall cases are
 * where a purely height-derived reserve slides out from under a fixed dp inset.
 */
@RunWith(AndroidJUnit4::class)
class BubbleSilhouetteCoreInstrumentedTest {

    private val density = Density(3f)

    /** width x height in dp: one line, a short word, two lines, a long paragraph. */
    private val cases = listOf(
        300f to 44f,
        120f to 40f,
        300f to 88f,
        300f to 320f,
        160f to 240f
    )

    private fun regionOf(shape: BubbleSilhouette, w: Float, h: Float): Region {
        val outline = shape.shape.createOutline(Size(w, h), LayoutDirection.Ltr, density)
        val path = when (outline) {
            is Outline.Generic -> outline.path.asAndroidPath()
            is Outline.Rounded -> androidx.compose.ui.graphics.Path()
                .apply { addRoundRect(outline.roundRect) }.asAndroidPath()
            is Outline.Rectangle -> androidx.compose.ui.graphics.Path()
                .apply { addRect(outline.rect) }.asAndroidPath()
        }
        return Region().apply {
            setPath(path, Region(0, 0, w.roundToInt() + 2, h.roundToInt() + 2))
        }
    }

    @Test
    fun everySilhouetteContainsItsOwnReadingCore() {
        val failures = mutableListOf<String>()
        for (silhouette in BubbleSilhouette.entries) {
            val ins = silhouette.coreInsets
            for ((wDp, hDp) in cases) {
                val w = wDp * density.density
                val h = hDp * density.density
                val l = with(density) { ins.calculateLeftPadding(LayoutDirection.Ltr).toPx() }
                val t = with(density) { ins.calculateTopPadding().toPx() }
                val r = with(density) { ins.calculateRightPadding(LayoutDirection.Ltr).toPx() }
                val b = with(density) { ins.calculateBottomPadding().toPx() }
                if (w - l - r < 8f || h - t - b < 8f) continue

                val region = regionOf(silhouette, w, h)
                val x0 = (l + 1f).roundToInt()
                val y0 = (t + 1f).roundToInt()
                val x1 = (w - r - 1f).roundToInt()
                val y1 = (h - b - 1f).roundToInt()
                for ((px, py) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
                    if (!region.contains(px, py)) {
                        failures += "${silhouette.name} at ${wDp}x${hDp}dp: " +
                            "core corner ($px,$py) is OUTSIDE the silhouette"
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
