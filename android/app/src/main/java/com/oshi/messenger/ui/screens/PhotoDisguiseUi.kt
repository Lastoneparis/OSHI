package com.oshi.messenger.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.R
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.stego.PhotoDisguise
import com.oshi.messenger.service.stego.StegoV1
import com.oshi.messenger.ui.theme.OshiAlertDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * "Disguise as photo" (STEGO-v1, docs/stego/STEGO-v1.md): the 1:1 composer sheet
 * that makes the PNG, and the reveal dialog used from Settings and from an
 * inbound share. Crypto: the existing identity keys via [CryptoManager].
 */
@HiltViewModel
class PhotoDisguiseViewModel @Inject constructor(
    private val crypto: CryptoManager,
    private val messageRepository: MessageRepository,
) : ViewModel() {

    suspend fun hide(recipientKey: String, cover: StegoV1.RgbImage, payload: StegoV1.Payload): ByteArray =
        withContext(Dispatchers.Default) { PhotoDisguise.hide(crypto, recipientKey, cover, payload) }

    suspend fun reveal(bytes: ByteArray): PhotoDisguise.Outcome = withContext(Dispatchers.Default) {
        val contacts = try { messageRepository.getAllContacts().first() } catch (_: Exception) { emptyList() }
        PhotoDisguise.reveal(crypto, bytes, contacts)
    }

    /** Quick check used on an inbound share: only a Found result interrupts the normal share flow. */
    fun revealIfForMe(context: Context, uri: Uri, onFound: (PhotoDisguise.Outcome.Found) -> Unit) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { PhotoDisguise.readUri(context, uri) } ?: return@launch
            if (!com.oshi.messenger.service.stego.StegoPng.isPng(bytes)) return@launch
            (reveal(bytes) as? PhotoDisguise.Outcome.Found)?.let(onFound)
        }
    }
}

private fun utf8Size(s: String) = s.toByteArray(Charsets.UTF_8).size

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisguiseAsPhotoSheet(
    contactPublicKey: String,
    contactName: String,
    initialText: String,
    onDismiss: () -> Unit,
    viewModel: PhotoDisguiseViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    var file by remember { mutableStateOf<StegoV1.Attachment?>(null) }
    var cover by remember { mutableStateOf<StegoV1.RgbImage?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var usingPhoto by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun setCover(img: StegoV1.RgbImage) {
        cover = img
        val px = IntArray(img.width * img.height) { i ->
            (0xFF shl 24) or ((img.rgb[i * 3].toInt() and 0xFF) shl 16) or ((img.rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (img.rgb[i * 3 + 2].toInt() and 0xFF)
        }
        preview = android.graphics.Bitmap.createBitmap(px, img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888)
    }
    LaunchedEffect(Unit) { setCover(withContext(Dispatchers.Default) { PhotoDisguise.generatedCover() }) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { PhotoDisguise.coverFromUri(context, uri) }?.let { setCover(it); usingPhoto = true }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { PhotoDisguise.readUri(context, uri, StegoV1.MAX_FILE) }
            if (bytes == null) { error = context.getString(R.string.disguise_file_too_large); return@launch }
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
            file = StegoV1.Attachment(name, context.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
            error = null
        }
    }

    // kind+ts+textLen+hasFile = 14; file adds 2+name+2+mime+4+data.
    val needed = 14 + utf8Size(text) + (file?.let { 8 + utf8Size(it.name) + utf8Size(it.mime) + it.data.size } ?: 0)
    val capacity = cover?.let { StegoV1.capacityBytes(it.slots) } ?: 0
    val fits = cover != null && needed <= capacity

    OshiAlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.disguise_photo_title))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.disguise_sheet_for, contactName), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(stringResource(R.string.disguise_message_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("disguise_text"), minLines = 3, maxLines = 8,
                )
                Spacer(Modifier.height(4.dp))
                val f = file
                if (f == null) {
                    TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.disguise_add_file))
                    }
                } else {
                    TextButton(onClick = { file = null }) {
                        Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.disguise_remove_file, "${f.name} (${Formatter.formatShortFileSize(context, f.data.size.toLong())})"))
                    }
                }
                preview?.let {
                    Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)))
                }
                // Stacked, not side by side: two chips do not fit a dialog's width in
                // every language (the second one was squeezed to one letter per line).
                Column {
                    com.oshi.messenger.ui.theme.OshiFilterChip(selected = !usingPhoto, onClick = {
                        scope.launch { setCover(withContext(Dispatchers.Default) { PhotoDisguise.generatedCover() }); usingPhoto = false }
                    }, label = { Text(stringResource(R.string.disguise_cover_generated)) }, leadingIcon = { Icon(Icons.Default.Landscape, null) })
                    com.oshi.messenger.ui.theme.OshiFilterChip(selected = usingPhoto, onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, label = { Text(stringResource(R.string.disguise_cover_photo)) }, leadingIcon = { Icon(Icons.Default.Image, null) })
                }
                Text(
                    stringResource(R.string.disguise_capacity,
                        Formatter.formatShortFileSize(context, needed.toLong()), Formatter.formatShortFileSize(context, capacity.toLong())),
                    fontSize = 12.sp,
                    color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (cover != null && !fits) Text(stringResource(R.string.disguise_too_big), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                WarningRow(stringResource(R.string.disguise_warning_recompress))
                Spacer(Modifier.height(4.dp))
                WarningRow(stringResource(R.string.disguise_warning_forensic))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (working) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.disguise_working), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(enabled = fits && !working && (text.isNotBlank() || file != null), onClick = {
                val c = cover ?: return@Button
                working = true; error = null
                scope.launch {
                    try {
                        val png = viewModel.hide(contactPublicKey, c, StegoV1.Payload(text, System.currentTimeMillis(), file))
                        val intent = withContext(Dispatchers.IO) { PhotoDisguise.shareIntent(context, png) }
                        context.startActivity(Intent.createChooser(intent, null))
                        onDismiss()
                    } catch (e: Exception) {
                        error = context.getString(R.string.disguise_failed)
                    } finally { working = false }
                }
            }) { Text(stringResource(R.string.disguise_create_share)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun WarningRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shows the result of a reveal (a found message, a damaged image, or nothing for you). */
@Composable
fun RevealResultDialog(outcome: PhotoDisguise.Outcome?, checking: Boolean, onOpenChat: ((String) -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reveal_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    checking -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.reveal_checking)) }
                    outcome is PhotoDisguise.Outcome.Found -> {
                        val r = outcome.revealed
                        Text(stringResource(R.string.reveal_from, r.senderName ?: r.senderPublicKey.take(12) + "…"),
                            style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.reveal_hidden_on,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.payload.createdAtMs))),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        if (r.payload.text.isNotEmpty()) Text(r.payload.text, style = MaterialTheme.typography.bodyLarge)
                        // __RESTRICTED_NETWORK_HINT_2026_11_18__ a photo-disguised text came, by
                        // construction, around a block: same chip, from this LOCAL fact (no marker).
                        if (r.payload.text.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            com.oshi.messenger.ui.components.RestrictedNetworkChip()
                        }
                        Row {
                            if (r.payload.text.isNotEmpty()) TextButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("", r.payload.text))
                            }) { Text(stringResource(R.string.reveal_copy)) }
                            r.payload.file?.let { f ->
                                TextButton(onClick = {
                                    val uri = PhotoDisguise.attachmentUri(context, f)
                                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    try { context.startActivity(Intent.createChooser(view, f.name)) } catch (_: Exception) { }
                                }) { Text(stringResource(R.string.reveal_open_file, f.name)) }
                            }
                        }
                    }
                    outcome is PhotoDisguise.Outcome.Damaged -> Text(stringResource(R.string.reveal_damaged, outcome.senderName ?: "?"))
                    else -> Text(stringResource(R.string.reveal_not_found))
                }
            }
        },
        confirmButton = {
            val found = outcome as? PhotoDisguise.Outcome.Found
            if (found != null && onOpenChat != null) {
                TextButton(onClick = { onOpenChat(found.revealed.senderPublicKey); onDismiss() }) { Text(stringResource(R.string.done)) }
            } else TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

/** "Reveal a hidden message": pick a file (never the photo picker, which may re-encode), reveal, show. */
@Composable
fun RevealPickerFlow(onClose: () -> Unit, viewModel: PhotoDisguiseViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<PhotoDisguise.Outcome?>(null) }
    var checking by remember { mutableStateOf(false) }
    var launched by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onClose(); return@rememberLauncherForActivityResult }
        checking = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { PhotoDisguise.readUri(context, uri) }
            outcome = if (bytes == null) PhotoDisguise.Outcome.NotForYou else viewModel.reveal(bytes)
            checking = false
        }
    }
    LaunchedEffect(Unit) { if (!launched) { launched = true; picker.launch(arrayOf("image/*", "application/octet-stream")) } }
    if (checking || outcome != null) RevealResultDialog(outcome, checking, null, onClose)
}
