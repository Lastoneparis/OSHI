package com.oshi.messenger.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oshi.messenger.R
import kotlinx.coroutines.launch

/**
 * Crop a picked photo before it is sent or made a profile picture.
 *
 * __IMAGE_CROP_2026_11_02__ (N13) "Choosing a profile photo or sending a photo
 * can be cropped on iOS; it would be welcome on Android."
 *
 * NO NEW DEPENDENCY, and no `com.android.camera.action.CROP` intent either.
 * That intent is the usual shortcut and it is a trap: it is not part of the
 * platform, which app answers it (if any) varies by vendor, several return a
 * thumbnail instead of the crop, and on a device where nothing handles it the
 * feature simply does not exist. A crop window is a rectangle and a
 * `Bitmap.createBitmap` call; drawing it ourselves means it behaves the same on
 * every phone.
 *
 * The arithmetic is in [ImageCropGeometry], under unit test. This file is the
 * part you can see, and it deliberately holds no mapping logic of its own: the
 * same `display` box is what the image is DRAWN into and what the crop is
 * measured against, so the picture on screen and the pixels cut out cannot
 * disagree.
 *
 * @param aspect width / height to lock the window to, or null for a free crop.
 *               Profile photos pass 1: they are shown in a circle, so a free
 *               crop would just be cropped again, off-centre, by the UI.
 */
@Composable
fun ImageCropDialog(
    source: Bitmap,
    aspect: Float? = null,
    onCancel: () -> Unit,
    onCropped: (Bitmap) -> Unit,
) {
    // Rotation produces a new bitmap rather than a drawing transform: with the
    // bitmap always "as shown", the crop maths never has to know about it.
    var working by remember(source) { mutableStateOf(source) }
    var image by remember(working) { mutableStateOf<ImageBitmap>(working.asImageBitmap()) }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var crop by remember { mutableStateOf<ImageCropGeometry.Box?>(null) }
    var handle by remember { mutableStateOf(ImageCropGeometry.Handle.BODY) }

    val display = remember(viewSize, working) {
        ImageCropGeometry.fit(viewSize.width.toFloat(), viewSize.height.toFloat(),
                              working.width, working.height)
    }
    // Reset the window whenever the surface or the bitmap changes (first layout,
    // rotation, a configuration change) — a window measured against a different
    // display box is the one way the crop could land on the wrong pixels.
    LaunchedEffect(display) {
        if (display.width > 0f) crop = ImageCropGeometry.initialCrop(display, aspect)
    }

    val slop = with(LocalDensity.current) { 32.dp.toPx() }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel),
                        tint = Color.White)
                }
                Text(
                    stringResource(R.string.crop_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = {
                    val rotated = runCatching {
                        Bitmap.createBitmap(working, 0, 0, working.width, working.height,
                                            Matrix().apply { postRotate(90f) }, true)
                    }.getOrNull()
                    if (rotated != null) {
                        working = rotated
                        image = rotated.asImageBitmap()
                    }
                }) {
                    Icon(Icons.Default.Rotate90DegreesCcw,
                        contentDescription = stringResource(R.string.crop_rotate), tint = Color.White)
                }
                IconButton(onClick = {
                    val box = crop
                    val out = if (box == null || display.width <= 0f) working else {
                        val s = ImageCropGeometry.toSource(box, display, working.width, working.height)
                        runCatching {
                            Bitmap.createBitmap(working, s.x, s.y, s.width, s.height)
                        }.getOrDefault(working)
                    }
                    onCropped(out)
                }) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.done),
                        tint = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(display, aspect) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                crop?.let { handle = ImageCropGeometry.handleAt(it, pos.x, pos.y, slop) }
                            },
                            onDrag = { _, drag ->
                                val box = crop ?: return@detectDragGestures
                                crop = ImageCropGeometry.resize(box, handle, drag.x, drag.y, display, aspect)
                            },
                        )
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (display.width <= 0f) return@Canvas
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(working.width, working.height),
                        dstOffset = IntOffset(display.left.toInt(), display.top.toInt()),
                        dstSize = IntSize(display.width.toInt(), display.height.toInt()),
                    )
                    val box = crop ?: return@Canvas

                    // Dim everything outside the window — four bands, so the
                    // kept area is never drawn over and its colours stay true.
                    val shade = Color.Black.copy(alpha = 0.6f)
                    drawRect(shade, Offset(0f, 0f), Size(size.width, box.top))
                    drawRect(shade, Offset(0f, box.bottom), Size(size.width, size.height - box.bottom))
                    drawRect(shade, Offset(0f, box.top), Size(box.left, box.height))
                    drawRect(shade, Offset(box.right, box.top), Size(size.width - box.right, box.height))

                    drawRect(
                        color = Color.White,
                        topLeft = Offset(box.left, box.top),
                        size = Size(box.width, box.height),
                        style = Stroke(width = 2f),
                    )
                    // Thirds, the standard composition aid.
                    for (i in 1..2) {
                        val x = box.left + box.width * i / 3f
                        val y = box.top + box.height * i / 3f
                        drawLine(Color.White.copy(alpha = 0.35f),
                            Offset(x, box.top), Offset(x, box.bottom), strokeWidth = 1f)
                        drawLine(Color.White.copy(alpha = 0.35f),
                            Offset(box.left, y), Offset(box.right, y), strokeWidth = 1f)
                    }
                    // Corner grips: the only hint that the window can be resized.
                    val arm = 28f
                    val grip = Stroke(width = 6f)
                    listOf(
                        Triple(box.left, box.top, 1f to 1f),
                        Triple(box.right, box.top, -1f to 1f),
                        Triple(box.left, box.bottom, 1f to -1f),
                        Triple(box.right, box.bottom, -1f to -1f),
                    ).forEach { (cx, cy, dir) ->
                        drawLine(Color.White, Offset(cx, cy), Offset(cx + arm * dir.first, cy),
                            strokeWidth = grip.width)
                        drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + arm * dir.second),
                            strokeWidth = grip.width)
                    }
                }
            }
        }
    }
}

/**
 * [ImageCropDialog] for a photo that is already JPEG bytes — the shape the chat
 * media preview holds its pick in.
 *
 * Decode and re-encode both run off the main thread: a 1920 px photo costs tens
 * of milliseconds each way, and this is the same work that used to be done in a
 * picker callback and froze the UI (`decodeSampledImageBytes`'s note).
 *
 * Returns the ORIGINAL bytes if the decode fails, so a picture that cannot be
 * read still sends exactly as it would have without this screen.
 */
@Composable
fun ImageCropForBytes(
    bytes: ByteArray,
    onCancel: () -> Unit,
    onCropped: (ByteArray) -> Unit,
) {
    var decoded by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(bytes) { mutableStateOf(false) }

    LaunchedEffect(bytes) {
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val decoded = runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            // UPRIGHT FIRST, and this path needs it MORE than the profile one:
            // a picked photo at or under 1920 px is passed along as the ORIGINAL
            // FILE (`decodeSampledImageBytes` returns its bytes untouched), so
            // these bytes still carry the camera's EXIF orientation that
            // `decodeByteArray` ignores. Cropping that without rotating would
            // cut out the wrong part of the picture AND re-encode it with no
            // EXIF at all, making the mistake permanent.
            decoded?.let { ImageOrientation.applied(it, ImageOrientation.orientationOf(bytes)) }
        }
        if (bmp == null) failed = true else decoded = bmp
    }
    LaunchedEffect(failed) { if (failed) onCropped(bytes) }

    val scope = rememberCoroutineScope()
    decoded?.let { bmp ->
        ImageCropDialog(
            source = bmp,
            aspect = null,
            onCancel = onCancel,
            onCropped = { cropped ->
                scope.launch {
                    val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching {
                            java.io.ByteArrayOutputStream().also {
                                cropped.compress(Bitmap.CompressFormat.JPEG, 90, it)
                            }.toByteArray()
                        }.getOrNull()
                    }
                    onCropped(out ?: bytes)
                }
            },
        )
    }
}
