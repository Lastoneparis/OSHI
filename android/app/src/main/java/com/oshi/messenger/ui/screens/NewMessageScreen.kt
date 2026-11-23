package com.oshi.messenger.ui.screens

import android.Manifest
import com.oshi.messenger.ui.theme.OshiDropdownMenu
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.viewmodel.NewMessageViewModel
import com.oshi.messenger.ui.viewmodel.RecipientKey
import com.oshi.messenger.ui.theme.OSHIGreen

/**
 * NewMessageScreen - QR code front-and-center design
 *
 * Shows the user's QR code immediately when opening.
 * Scan QR and Paste Key are action buttons below.
 */

// Brand color matching iOS
private val BrandColor = OSHIPurple

/**
 * Normalise whatever a contact QR / deep link produced into a bare public key.
 *
 * Deliberately tolerant. OSHI now emits the RAW key, byte-for-byte what iOS
 * encodes (`QRCodeDisplayView.swift:272`), because iOS's scanner strips no
 * prefix (`QRScannerView.swift:132-137`) and an `oshi:`-prefixed Android QR
 * therefore gave an iPhone `oshi:<base64>` as the peer address. Codes generated
 * by earlier Android builds still carry that prefix and must keep working, and
 * both link forms are accepted too.
 */
internal fun normalizeScannedContactPayload(raw: String): String {
    val httpsPrefix = "https://oshi-messenger.org/add?key="
    val deepLinkPrefix = "oshi://add?key="
    return when {
        raw.startsWith(deepLinkPrefix) ->
            runCatching { java.net.URLDecoder.decode(raw.removePrefix(deepLinkPrefix), "UTF-8") }
                .getOrDefault(raw)
        raw.startsWith(httpsPrefix) ->
            runCatching { java.net.URLDecoder.decode(raw.removePrefix(httpsPrefix), "UTF-8") }
                .getOrDefault(raw)
        // Legacy Android-generated contact QR.
        raw.startsWith("oshi:") && !raw.startsWith("oshi://") -> raw.removePrefix("oshi:")
        else -> raw
    }.trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    onBack: () -> Unit,
    onContactSelected: (String) -> Unit,
    /**
     * Called once the composed message has been handed to the repository.
     * iOS shows a success alert and clears the form (`NewMessageView.swift:148`);
     * on Android we confirm with a toast and let the host leave the screen.
     */
    onSent: () -> Unit = {},
    /** Open straight on the camera (used by the empty state's "Scan a friend's code"). */
    autoStartScan: Boolean = false,
    viewModel: NewMessageViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var recipientAddress by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    // Not a boolean: the screen has to be able to say WHY a paste was refused.
    var recipientCheck by remember {
        mutableStateOf<RecipientKey.Result>(RecipientKey.Result.Invalid(RecipientKey.Problem.EMPTY))
    }
    // The CANONICAL key (standard base64, padded), not the raw paste — see
    // RecipientKey. Null means "not a recipient we can send to", which is also
    // the single gate on the composer and the send button below.
    val validRecipient = (recipientCheck as? RecipientKey.Result.Valid)?.canonicalKey
    var isViewOnceEnabled by remember { mutableStateOf(false) }
    var showPasteField by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    val myPublicKey by viewModel.myPublicKey.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val nearbyPeers by viewModel.nearbyPeers.collectAsState()
    val sendState by viewModel.sendState.collectAsState()

    // Attachment pickers — iOS offers exactly these two entries in its paperclip
    // menu (`NewMessageView.swift:498-511`: "attach.single" / "attach.multiple").
    val singleImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { attachments = attachments + it } }
    val multiImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) attachments = attachments + uris }

    // Keep screen bright while showing QR code
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: -1f
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply {
            screenBrightness = 1.0f
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = originalBrightness
            }
        }
    }

    // QR Scanner launcher
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scannedContent ->
            val scanned = normalizeScannedContactPayload(scannedContent)
            // Same rule as the pasted field, and for the same reason: a QR that
            // does not carry a 32-byte key used to be announced as "Contact
            // added" and then failed at send.
            val publicKey = RecipientKey.canonicalRecipientOrNull(scanned, myPublicKey)

            if (publicKey != null) {
                // Rising two-note chime + haptic, replacing ZXing's flat beep.
                // Only on a code we could actually use — a confirmation sound for
                // an unusable QR is worse than silence.
                com.oshi.messenger.ui.scan.ScanFeedback.success(context)
                // The toast below says "Contact added!" — it now is (N18).
                viewModel.saveScannedContact(publicKey)
                Toast.makeText(context, context.getString(R.string.new_contact_added), Toast.LENGTH_SHORT).show()
                onContactSelected(publicKey)
            } else {
                Toast.makeText(context, context.getString(R.string.new_invalid_qr), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(context.getString(R.string.new_scan_prompt))
                setCameraId(0)
                setBeepEnabled(false)
                setBarcodeImageEnabled(false)
                setOrientationLocked(true)
                    setCaptureActivity(com.oshi.messenger.ui.scan.OshiScanActivity::class.java)
            }
            scanLauncher.launch(options)
        } else {
            Toast.makeText(context, context.getString(R.string.new_camera_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    val startScanning: () -> Unit = {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(context.getString(R.string.new_scan_prompt))
                    setCameraId(0)
                    setBeepEnabled(false)
                    setBarcodeImageEnabled(false)
                    setOrientationLocked(true)
                    setCaptureActivity(com.oshi.messenger.ui.scan.OshiScanActivity::class.java)
                }
                scanLauncher.launch(options)
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    LaunchedEffect(recipientAddress, myPublicKey) {
        recipientCheck = viewModel.checkRecipient(recipientAddress)
    }

    // "Scan a friend's code" deep-links here with ?scan=true; open the camera once.
    var scanRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStartScan) {
        if (autoStartScan && !scanRequested) {
            scanRequested = true
            startScanning()
        }
    }

    // One-shot send result. Sent → toast + hand back to the host; Failed → toast.
    LaunchedEffect(sendState) {
        when (val s = sendState) {
            is NewMessageViewModel.SendState.Sent -> {
                Toast.makeText(context, context.getString(R.string.new_message_sent), Toast.LENGTH_SHORT).show()
                viewModel.acknowledgeSendState()
                messageText = ""
                attachments = emptyList()
                isViewOnceEnabled = false
                onSent()
            }
            is NewMessageViewModel.SendState.Failed -> {
                Toast.makeText(context, context.getString(R.string.new_message_send_failed), Toast.LENGTH_LONG).show()
                viewModel.acknowledgeSendState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                },
                title = { Text(stringResource(R.string.new_message_title)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── MY QR CODE — always visible ──
            val qrBitmap = remember(myPublicKey) {
                if (myPublicKey.isNotEmpty()) generateQRCode(myPublicKey) else null
            }

            // QR Card
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp
            ) {
                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_my_code),
                        modifier = Modifier
                            .size(240.dp)
                            .padding(14.dp),
                        contentScale = ContentScale.Fit
                    )
                } ?: Box(
                    modifier = Modifier.size(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandColor)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Truncated key
            Text(
                text = formatAddress(myPublicKey),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Copy / Share buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { copyToClipboard(context, myPublicKey) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy, null,
                        modifier = Modifier.size(16.dp), tint = BrandColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.copy), color = BrandColor, fontSize = 13.sp)
                }

                Button(
                    onClick = { shareKey(context, myPublicKey) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.share), fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Action buttons: Scan QR / Paste Key ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Scan QR button
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { startScanning() }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner, null,
                            modifier = Modifier.size(28.dp), tint = BrandColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.new_tab_scan),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Paste Key button
                Surface(
                    color = if (showPasteField) BrandColor.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showPasteField = !showPasteField }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ContentPaste, null,
                            modifier = Modifier.size(28.dp), tint = BrandColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.new_tab_paste),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // ── Paste Key field (expandable) ──
            if (showPasteField) {
                Spacer(modifier = Modifier.height(12.dp))
                PasteKeyContent(
                    recipientAddress = recipientAddress,
                    onAddressChange = { recipientAddress = it },
                    recipientCheck = recipientCheck,
                    context = context
                )
            }

            // Recipient Preview
            if (validRecipient != null) {
                Spacer(modifier = Modifier.height(16.dp))
                RecipientPreview(address = validRecipient)
            }

            // Message Input & Send
            if (validRecipient != null) {
                Spacer(modifier = Modifier.height(16.dp))
                MessageInputSection(
                    messageText = messageText,
                    onMessageChange = { messageText = it },
                    isViewOnceEnabled = isViewOnceEnabled,
                    onViewOnceChange = { isViewOnceEnabled = it },
                    attachments = attachments,
                    onAttachSingle = { singleImagePicker.launch("image/*") },
                    onAttachMultiple = { multiImagePicker.launch("image/*") },
                    onRemoveAttachment = { uri -> attachments = attachments - uri },
                    onClearAttachments = { attachments = emptyList() }
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 🔧 The Send button used to call `onContactSelected(recipientAddress)`,
                // i.e. it navigated to the chat and DROPPED everything the user had
                // typed — the screen called "New Message" could not send a message.
                // It now goes through the same repository paths the chat uses.
                // iOS's `canSend` is text-or-attachment, never both required
                // (`NewMessageView.swift:646-648`).
                SendButton(
                    enabled = sendState !is NewMessageViewModel.SendState.Sending &&
                        (messageText.isNotBlank() || attachments.isNotEmpty()),
                    isSending = sendState is NewMessageViewModel.SendState.Sending,
                    onClick = {
                        viewModel.sendFirstMessage(
                            recipientPublicKey = validRecipient,
                            text = messageText,
                            attachments = attachments,
                            isViewOnce = isViewOnceEnabled
                        )
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Secondary path: skip the inline composer and open the full chat.
                // __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (Anatole N18) This screen is
                // what "New contact" in the New menu opens. A pasted key the user chose
                // to open is a contact they meant to keep: save it, as the QR path does,
                // so it is in the list on the way back even if no message was sent.
                TextButton(
                    onClick = {
                        viewModel.saveScannedContact(validRecipient)
                        onContactSelected(validRecipient)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.new_open_conversation), color = BrandColor)
                }
            }

            // Nearby peers
            if (nearbyPeers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                NearbyPeersSection(
                    peers = nearbyPeers,
                    onPeerSelected = { onContactSelected(it.publicKey) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3-step instructions at bottom
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QRInstructionStep(step = "1", text = stringResource(R.string.qr_open_on_other_device))
                QRInstructionStep(step = "2", text = stringResource(R.string.qr_go_to_scan))
                QRInstructionStep(step = "3", text = stringResource(R.string.qr_scan_to_connect))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}


@Composable
private fun QRInstructionStep(step: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = BrandColor.copy(alpha = 0.15f),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColor
                )
            }
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The message under the field for one refusal.
 *
 * An exhaustive `when` on purpose and with no `else`: adding a
 * [RecipientKey.Problem] without a string for it must not compile, because the
 * whole point of the rewrite is that the user is told what is wrong.
 */
@androidx.annotation.StringRes
private fun problemMessage(problem: RecipientKey.Problem): Int = when (problem) {
    RecipientKey.Problem.EMPTY -> R.string.new_key_error_empty
    RecipientKey.Problem.CONTAINS_WHITESPACE -> R.string.new_key_error_spaces
    RecipientKey.Problem.TRAILING_CHARACTERS -> R.string.new_key_error_extra_characters
    RecipientKey.Problem.MISSING_PADDING -> R.string.new_key_error_padding
    RecipientKey.Problem.TOO_SHORT -> R.string.new_key_error_too_short
    RecipientKey.Problem.TOO_LONG -> R.string.new_key_error_too_long
    RecipientKey.Problem.NOT_A_KEY -> R.string.new_key_error_not_a_key
    RecipientKey.Problem.OWN_KEY -> R.string.new_key_error_own_key
}

@Composable
private fun PasteKeyContent(
    recipientAddress: String,
    onAddressChange: (String) -> Unit,
    recipientCheck: RecipientKey.Result,
    context: Context
) {
    val isValidAddress = recipientCheck is RecipientKey.Result.Valid
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = BrandColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.new_paste_recipient_key),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Input field
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = recipientAddress,
                        onValueChange = onAddressChange,
                        placeholder = {
                            Text(
                                stringResource(R.string.new_paste_placeholder),
                                fontSize = 14.sp
                            )
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    )

                    // Paste button
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
                                onAddressChange(text.toString().trim())
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.new_tab_paste),
                            tint = BrandColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Clear button
                    if (recipientAddress.isNotEmpty()) {
                        IconButton(onClick = { onAddressChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Validation indicator
            if (recipientAddress.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isValidAddress) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (isValidAddress) OSHIGreen else Color(0xFFFF9800),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        // "Invalid address format" told a first-time user nothing.
                        // Each refusal now names the thing that is wrong with what
                        // they actually pasted.
                        text = when (recipientCheck) {
                            is RecipientKey.Result.Valid ->
                                stringResource(R.string.new_paste_valid)
                            is RecipientKey.Result.Invalid ->
                                stringResource(problemMessage(recipientCheck.problem))
                        },
                        fontSize = 12.sp,
                        color = if (isValidAddress) OSHIGreen else Color(0xFFFF9800)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipientPreview(address: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = BrandColor.copy(alpha = 0.15f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = BrandColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.new_recipient_title),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatAddress(address),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }

            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = OSHIGreen
            )
        }
    }
}

@Composable
private fun MessageInputSection(
    messageText: String,
    onMessageChange: (String) -> Unit,
    isViewOnceEnabled: Boolean,
    onViewOnceChange: (Boolean) -> Unit,
    attachments: List<android.net.Uri>,
    onAttachSingle: () -> Unit,
    onAttachMultiple: () -> Unit,
    onRemoveAttachment: (android.net.Uri) -> Unit,
    onClearAttachments: () -> Unit
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Message,
                    contentDescription = null,
                    tint = BrandColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.new_composer_message),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                // Attach menu — was an empty lambda, so the paperclip was decoration.
                // iOS: NewMessageView.swift:498-511.
                Box {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.chat_attach),
                            tint = BrandColor
                        )
                    }
                    OshiDropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_composer_attach_single)) },
                            leadingIcon = { Icon(Icons.Default.Photo, contentDescription = null) },
                            onClick = {
                                attachMenuOpen = false
                                onAttachSingle()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_composer_attach_multiple)) },
                            leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                attachMenuOpen = false
                                onAttachMultiple()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = messageText,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Attachment strip — iOS `mediaPreviews` (NewMessageView.swift:535).
            if (attachments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.new_composer_attachments,
                            attachments.size,
                            attachments.size
                        ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearAttachments) {
                        Text(
                            text = stringResource(R.string.new_composer_clear_all),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(attachments) { uri ->
                        Box {
                            coil.compose.AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(70.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = stringResource(R.string.new_composer_remove_attachment),
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .clickable { onRemoveAttachment(uri) }
                            )
                        }
                    }
                }
            }

            // View Once Toggle
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isViewOnceEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    tint = if (isViewOnceEnabled) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.new_composer_view_once),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.new_composer_view_once_desc),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isViewOnceEnabled,
                    onCheckedChange = onViewOnceChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFFFF9800)
                    )
                )
            }
        }
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    isSending: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandColor,
            disabledContainerColor = Color.Gray
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        if (isSending) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.new_composer_send),
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun NearbyPeersSection(
    peers: List<com.oshi.messenger.network.mesh.MeshPeer>,
    onPeerSelected: (com.oshi.messenger.network.mesh.MeshPeer) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.peers_nearby),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = BrandColor
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(peers) { peer ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.clickable { onPeerSelected(peer) }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was
                        // one brand tint for every nearby person.
                        com.oshi.messenger.ui.theme.ContactAvatar(
                            publicKey = peer.publicKey,
                            name = peer.displayName,
                            size = 48.dp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = peer.displayName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(OSHIGreen, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.new_message_online),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper functions

private fun generateQRCode(text: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun formatAddress(address: String): String {
    return if (address.length > 16) {
        "${address.take(8)}...${address.takeLast(8)}"
    } else {
        address
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // Localised: the clip label is surfaced by the system clipboard UI.
    val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label_oshi_key), text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, context.getString(R.string.new_copied_to_clipboard), Toast.LENGTH_SHORT).show()
}

private fun shareKey(context: Context, publicKey: String) {
    val encodedKey = java.net.URLEncoder.encode(publicKey, "UTF-8")
    val deepLinkURL = "https://oshi-messenger.org/add?key=$encodedKey"

    val shareText = """
${context.getString(R.string.new_share_add_me)}

$deepLinkURL

${context.getString(R.string.new_share_public_key)}
$publicKey
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.new_share_via)))
}
