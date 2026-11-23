package com.oshi.messenger.ui.screens

import com.oshi.messenger.ui.theme.glassDialogRim
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oshi.messenger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Caption sheet for GROUP media — the twin of the 1:1 `MediaPreviewDialog`
 * (`ChatScreen.kt`), which is `private` to that file and belongs to another team.
 *
 * `GroupChatViewModel.sendMediaMessage` has taken a caption since the viewmodels pass
 * (audit G-7), and every group call site passed `""` because there was nowhere to type
 * one: the group screen went straight from the picker to the send. The caption becomes
 * the message `content`, which the groups pass puts in the media JSON's `caption` AND in
 * `plaintextContent` — the only key iOS draws a caption from (`GroupViews.swift:2617`).
 *
 * No fixed height: the preview is capped with `heightIn` and the dialog wraps, so on a
 * short or landscape window the buttons stay on screen instead of being pushed past the
 * bottom edge.
 */
@Composable
fun GroupMediaPreviewDialog(
    imageData: ByteArray?,
    videoData: ByteArray?,
    /** __IMAGE_CROP_2026_11_02__ (N13) null hides the crop affordance. */
    onCrop: (() -> Unit)? = null,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var caption by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        // __OLED_GLASS_2026_11_16__ glass dialog under OLED black.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = com.oshi.messenger.ui.theme.glassDialogSurfaceColor(),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .glassDialogRim(RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    imageData?.let { data ->
                        // Decode off the main thread — a full-size JPEG is ≥100 ms.
                        var bitmap by remember(data) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(data) {
                            bitmap = withContext(Dispatchers.Default) {
                                try { BitmapFactory.decodeByteArray(data, 0, data.size) } catch (_: Exception) { null }
                            }
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.chat_photo),
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    // __IMAGE_CROP_2026_11_02__ (N13) the same optional crop the 1:1
                    // preview offers. Images only: cropping a video is a re-encode.
                    if (imageData != null && onCrop != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clickable { onCrop() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Crop,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.crop_edit),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    if (videoData != null) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = stringResource(R.string.chat_video),
                            modifier = Modifier.size(64.dp),
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        placeholder = { Text(stringResource(R.string.caption_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        // Brand #5616EC is ~2:1 on this dark dialog; the brand INK clears AA.
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = com.oshi.messenger.ui.theme.brandInk())
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = { onSend(caption) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_send))
                    }
                }
            }
        }
    }
}
