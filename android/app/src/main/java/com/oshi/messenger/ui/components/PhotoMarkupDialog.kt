package com.oshi.messenger.ui.components

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassEdge
import com.oshi.messenger.ui.theme.thenIf
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oshi.messenger.R
import java.io.ByteArrayOutputStream

/**
 * __PHOTO_MARKUP_2026_11_08__
 *
 * Draw on a photo before sending it: circle a thing, underline a thing.
 *
 * Strokes are held in IMAGE coordinates from the first touch, never in view
 * coordinates — see [MarkupGeometry] for why that is the whole problem. The
 * preview then projects them back for display, so what is on screen and what
 * gets flattened are the same data rather than two parallel attempts at it.
 */
private data class Stroke(
    val color: Color,
    val widthInImage: Float,
    val points: MutableList<Offset>,
)

/** Pens. Bright on a photograph, and distinguishable from each other when thin. */
private val PEN_COLORS = listOf(
    Color(0xFFFF3B30), // red
    Color(0xFFFFCC00), // yellow
    Color(0xFF34C759), // green
    Color(0xFF0A84FF), // blue
    Color.White,
    Color.Black,
)

@Composable
fun PhotoMarkupDialog(
    imageBytes: ByteArray,
    onCancel: () -> Unit,
    onDone: (ByteArray) -> Unit,
) {
    val source = remember(imageBytes) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    if (source == null) {
        // Undecodable bytes: say nothing clever, just do not open a broken editor.
        onCancel()
        return
    }

    val strokes = remember { mutableStateListOf<Stroke>() }
    var penColor by remember { mutableStateOf(PEN_COLORS.first()) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val penWidthOnScreen = 6f

    // __OLED_GLASS_2026_11_16__ OLED black: the toolbars are glass bars on the
    // black ground (no #1A1A1A slabs) and the chosen pen glows.
    val glass = com.oshi.messenger.ui.theme.glassOn()
    val barFill = if (glass) com.oshi.messenger.ui.theme.GlassTier.Bar.opaque else Color(0xFF1A1A1A)
    val navInset = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()
    val navClearance = if (navInset > 0.dp) navInset else 28.dp
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(source) {
                        detectDragGestures(
                            onDragStart = { p ->
                                MarkupGeometry.toImage(
                                    p.x, p.y, source.width, source.height,
                                    size.width.toFloat(), size.height.toFloat(),
                                )?.let { (ix, iy) ->
                                    strokes.add(
                                        Stroke(
                                            color = penColor,
                                            widthInImage = MarkupGeometry.strokeWidthInImage(
                                                penWidthOnScreen, source.width, source.height,
                                                size.width.toFloat(), size.height.toFloat(),
                                            ),
                                            points = mutableListOf(Offset(ix, iy)),
                                        )
                                    )
                                }
                            },
                            onDrag = { change, _ ->
                                MarkupGeometry.toImage(
                                    change.position.x, change.position.y,
                                    source.width, source.height,
                                    size.width.toFloat(), size.height.toFloat(),
                                )?.let { (ix, iy) ->
                                    strokes.lastOrNull()?.points?.add(Offset(ix, iy))
                                }
                            },
                        )
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val f = MarkupGeometry.fit(source.width, source.height, size.width, size.height)
                    drawImage(
                        image = source.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(f.offsetX.toInt(), f.offsetY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            (source.width * f.scale).toInt(),
                            (source.height * f.scale).toInt(),
                        ),
                    )
                    // Project the image-space strokes back out for the preview.
                    for (s in strokes) {
                        val pts = s.points
                        for (i in 1 until pts.size) {
                            drawLine(
                                color = s.color,
                                start = Offset(f.offsetX + pts[i - 1].x * f.scale, f.offsetY + pts[i - 1].y * f.scale),
                                end = Offset(f.offsetX + pts[i].x * f.scale, f.offsetY + pts[i].y * f.scale),
                                strokeWidth = s.widthInImage * f.scale,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(barFill)
                    .thenIf(glass) { glassEdge(top = true) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (c in PEN_COLORS) {
                    Box(
                        Modifier
                            .size(if (c == penColor) 30.dp else 24.dp)
                            .thenIf(glass && c == penColor) { glassGlow(CircleShape) }
                            .background(c, CircleShape)
                            .border(
                                width = if (c == penColor) 2.dp else 1.dp,
                                color = if (c == penColor) Color.White else Color.White.copy(alpha = 0.4f),
                                shape = CircleShape,
                            )
                            .clickable { penColor = c }
                    )
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) {
                    Text(stringResource(R.string.markup_undo), color = Color.White)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(barFill)
                    // The Cancel / Done row sat under the gesture bar: a full-screen
                    // Dialog window is not handed the navigation-bar insets, so
                    // fall back to a fixed clearance when they read as zero.
                    .padding(bottom = navClearance)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
                TextButton(onClick = {
                    onDone(flatten(source, strokes))
                }) {
                    Text(stringResource(R.string.markup_done), color = Color.White)
                }
            }
        }
    }
}

/**
 * Burn the strokes into a copy of the photo at FULL resolution.
 *
 * PNG, not JPEG: a thin bright line over a photograph is exactly what JPEG
 * ringing destroys, and the point of the mark is that it is legible.
 */
private fun flatten(source: Bitmap, strokes: List<Stroke>): ByteArray {
    val out = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (s in strokes) {
        paint.color = android.graphics.Color.argb(
            (s.color.alpha * 255).toInt(),
            (s.color.red * 255).toInt(),
            (s.color.green * 255).toInt(),
            (s.color.blue * 255).toInt(),
        )
        paint.strokeWidth = s.widthInImage
        val path = android.graphics.Path()
        s.points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        canvas.drawPath(path, paint)
    }
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.PNG, 100, bos)
    return bos.toByteArray()
}
