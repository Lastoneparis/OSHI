package com.oshi.messenger.ui.screens

import android.Manifest
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.oshi.messenger.ui.components.InlineTone
import com.oshi.messenger.ui.components.OshiInlineMessage
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.theme.fittedArtSize
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.viewmodel.MainViewModel
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "KeyBackupScreen"

// Encrypted prefs keys (must match MainViewModel)
private const val PREFS_NAME = "oshi_secure_keys"
private const val KEY_PRIVATE_KEY = "private_key"
private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBackupScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val publicKey by viewModel.publicKey.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(stringResource(R.string.key_backup_export), stringResource(R.string.key_backup_import))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = OSHIPurple
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = if (index == 0) Icons.Default.Upload else Icons.Default.Download,
                                contentDescription = title
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> ExportTab(publicKey = publicKey, context = context, clipboardManager = clipboardManager, viewModel = viewModel)
                1 -> ImportTab(
                    context = context,
                    clipboardManager = clipboardManager,
                    viewModel = viewModel,
                    onRestoreComplete = onBack
                )
            }
        }
    }
}

// =============================================================================
// EXPORT TAB
// =============================================================================

@Composable
private fun ExportTab(
    publicKey: String,
    context: android.content.Context,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    viewModel: MainViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()

    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showQRDialog by remember { mutableStateOf(false) }
    var encryptedKeyBase64 by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var showCopied by remember { mutableStateOf(false) }

    // Recovery key state
    var showRecoveryKey by remember { mutableStateOf(false) }
    var recoveryKeyCopied by remember { mutableStateOf(false) }
    var showRecoveryQRDialog by remember { mutableStateOf(false) }
    val recoveryKey = remember { viewModel.exportRecoveryKey() }

    val isPassphraseValid = passphrase.length >= 8
    val doPassphrasesMatch = passphrase == confirmPassphrase
    val canExport = isPassphraseValid && doPassphrasesMatch && !isExporting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =====================================================================
        // SIMPLE RECOVERY KEY SECTION (iOS-compatible, no passphrase needed)
        // =====================================================================
        if (recoveryKey != null) {
            OshiCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = brandInk(),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.key_backup_recovery_key),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Text(
                        text = stringResource(R.string.key_backup_recovery_key_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    // Show/hide recovery key
                    if (showRecoveryKey) {
                        OshiCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text(
                                text = recoveryKey,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Warning
                    OshiCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = OSHIRed.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = OSHIRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.key_backup_warning_anyone_access),
                                fontSize = 12.sp,
                                color = OSHIRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Reveal / Hide button
                        OutlinedButton(
                            onClick = { showRecoveryKey = !showRecoveryKey },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = if (showRecoveryKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (showRecoveryKey) stringResource(R.string.key_backup_hide) else stringResource(R.string.key_backup_reveal_key),
                                fontSize = 13.sp
                            )
                        }

                        // Copy button
                        OutlinedButton(
                            onClick = {
                                // __CLIPBOARD_HYGIENE_2026_10_01__ the recovery key IS the identity ⇒ sensitive clip, cleared after 60 s.
                                com.oshi.messenger.security.SensitiveClipboard.copy(context, recoveryKey)
                                recoveryKeyCopied = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (recoveryKeyCopied) stringResource(R.string.key_backup_copied_clipboard) else stringResource(R.string.copy),
                                fontSize = 13.sp
                            )
                        }
                    }

                    // Show QR button (full width)
                    Button(
                        onClick = { showRecoveryQRDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.key_backup_show_qr))
                    }
                }
            }

            // Reset "Copied" state after delay
            LaunchedEffect(recoveryKeyCopied) {
                if (recoveryKeyCopied) {
                    kotlinx.coroutines.delay(2000)
                    recoveryKeyCopied = false
                }
            }

            // Recovery key QR code dialog
            if (showRecoveryQRDialog) {
                RecoveryKeyQRDialog(
                    recoveryKey = recoveryKey,
                    onDismiss = { showRecoveryQRDialog = false }
                )
            }

            Divider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Text(
                text = stringResource(R.string.passphrase_encrypted_backup),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Warning card
        OshiCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = OSHIOrange.copy(alpha = 0.1f)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = OSHIOrange,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.key_backup_keep_safe),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.key_backup_keep_safe_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Public key display
        OshiCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.key_backup_your_public_key),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (publicKey.length > 16) {
                        "${publicKey.take(8)}...${publicKey.takeLast(8)}"
                    } else publicKey,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Passphrase input
        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                errorMessage = null
            },
            label = { Text(stringResource(R.string.key_backup_passphrase_label)) },
            placeholder = { Text(stringResource(R.string.key_backup_passphrase_placeholder)) },
            visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            // __ANTI_STALKERWARE_2026_11_20__ a passphrase is a password to the keyboard:
            // no suggestions, no learning, no autocorrect — even when the incognito
            // keyboard toggle is off.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                autoCorrect = false
            ),
            trailingIcon = {
                IconButton(onClick = { showPassphrase = !showPassphrase }) {
                    Icon(
                        imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassphrase) stringResource(R.string.key_backup_hide) else stringResource(R.string.key_backup_reveal_key)
                    )
                }
            },
            isError = passphrase.isNotEmpty() && !isPassphraseValid,
            supportingText = {
                if (passphrase.isNotEmpty() && !isPassphraseValid) {
                    Text(stringResource(R.string.key_backup_passphrase_min))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Confirm passphrase
        OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = {
                confirmPassphrase = it
                errorMessage = null
            },
            label = { Text(stringResource(R.string.key_backup_confirm_passphrase)) },
            placeholder = { Text(stringResource(R.string.key_backup_reenter_passphrase)) },
            visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            // __ANTI_STALKERWARE_2026_11_20__ a passphrase is a password to the keyboard:
            // no suggestions, no learning, no autocorrect — even when the incognito
            // keyboard toggle is off.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                autoCorrect = false
            ),
            isError = confirmPassphrase.isNotEmpty() && !doPassphrasesMatch,
            supportingText = {
                if (confirmPassphrase.isNotEmpty() && !doPassphrasesMatch) {
                    Text(stringResource(R.string.key_backup_passphrase_mismatch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Error message
        errorMessage?.let { error ->
            // Was a bare `Text` in OSHIRed, the SAME shape as the success line
            // below it — the two were distinguished by hue alone, which is not
            // a distinction for a red/green colour-blind user and is not one on
            // a greyscale screenshot either. OshiInlineMessage gives each tone
            // its own glyph, its own container, and an announcement.
            OshiInlineMessage(message = error, tone = InlineTone.Error)
        }

        // Export buttons
        Button(
            onClick = {
                scope.launch {
                    isExporting = true
                    errorMessage = null
                    try {
                        val encrypted = exportEncryptedKey(context, passphrase)
                        encryptedKeyBase64 = encrypted
                        showQRDialog = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Export failed: ${e.message}", e)
                        errorMessage = context.getString(R.string.key_backup_export_failed, e.message)
                    } finally {
                        isExporting = false
                    }
                }
            },
            enabled = canExport,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
        ) {
            if (isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.key_backup_export_qr))
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    isExporting = true
                    errorMessage = null
                    try {
                        val encrypted = exportEncryptedKey(context, passphrase)
                        // __CLIPBOARD_HYGIENE_2026_10_01__ encrypted key backup: hidden preview + 60 s expiry too.
                        com.oshi.messenger.security.SensitiveClipboard.copy(context, encrypted)
                        showCopied = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Export failed: ${e.message}", e)
                        errorMessage = context.getString(R.string.key_backup_export_failed, e.message)
                    } finally {
                        isExporting = false
                    }
                }
            },
            enabled = canExport,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (showCopied) stringResource(R.string.key_backup_copied_clipboard) else stringResource(R.string.key_backup_copy_clipboard))
        }
    }

    // Reset "Copied" state after delay
    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(2000)
            showCopied = false
        }
    }

    // QR Code dialog
    if (showQRDialog && encryptedKeyBase64 != null) {
        QRCodeExportDialog(
            encryptedKey = encryptedKeyBase64!!,
            onDismiss = { showQRDialog = false }
        )
    }
}

// =============================================================================
// IMPORT TAB
// =============================================================================

@Composable
private fun ImportTab(
    context: android.content.Context,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    viewModel: MainViewModel,
    onRestoreComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var encryptedKeyInput by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Recovery key import state
    var recoveryKeyInput by remember { mutableStateOf("") }
    var recoveryKeyError by remember { mutableStateOf<String?>(null) }
    var showRecoveryConfirmDialog by remember { mutableStateOf(false) }

    val publicKey by viewModel.publicKey.collectAsState()
    val hasExistingKey = publicKey.isNotEmpty()
    val canRestore = encryptedKeyInput.isNotBlank() && passphrase.length >= 8 && !isRestoring

    // QR scan launcher — uses zxing-android-embedded ScanContract (same as NewMessageScreen)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (!scanned.isNullOrBlank()) {
            if (scanned.startsWith("oshi-recovery:")) {
                // Recovery key QR code scanned
                recoveryKeyInput = scanned.removePrefix("oshi-recovery:")
                recoveryKeyError = null
                statusMessage = context.getString(R.string.key_backup_recovery_scanned)
            } else {
                // Encrypted key backup QR code
                encryptedKeyInput = if (scanned.startsWith("oshi-backup:")) {
                    scanned.removePrefix("oshi-backup:")
                } else {
                    scanned
                }
                errorMessage = null
                statusMessage = context.getString(R.string.key_backup_qr_scanned)
            }
        } else {
            errorMessage = context.getString(R.string.key_backup_qr_cancelled)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(context.getString(R.string.key_backup_scan_prompt))
                setCameraId(0)
                setBeepEnabled(true)
                setBarcodeImageEnabled(false)
                setOrientationLocked(false)
            }
            scanLauncher.launch(options)
        } else {
            Toast.makeText(context, context.getString(R.string.key_backup_camera_required), Toast.LENGTH_LONG).show()
        }
    }

    val startQRScan: () -> Unit = {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt(context.getString(R.string.key_backup_scan_prompt))
                    setCameraId(0)
                    setBeepEnabled(true)
                    setBarcodeImageEnabled(false)
                    setOrientationLocked(false)
                }
                scanLauncher.launch(options)
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Info card
        OshiCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = OSHIPurple.copy(alpha = 0.1f)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = brandInk(),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.key_backup_import_info),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        // =====================================================================
        // SIMPLE RECOVERY KEY RESTORE (iOS-compatible, no passphrase)
        // =====================================================================
        OshiCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        tint = brandInk(),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.key_backup_recovery_key),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Text(
                    text = stringResource(R.string.key_backup_recovery_paste_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                OutlinedTextField(
                    value = recoveryKeyInput,
                    onValueChange = {
                        recoveryKeyInput = it
                        recoveryKeyError = null
                    },
                    label = { Text(stringResource(R.string.key_backup_recovery_key)) },
                    placeholder = { Text(stringResource(R.string.key_backup_paste_recovery_key)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    isError = recoveryKeyError != null,
                    supportingText = recoveryKeyError?.let { { Text(it, color = OSHIRed) } }
                )

                // Recovery key action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Scan Recovery QR
                    OutlinedButton(
                        onClick = {
                            when {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                                    val options = ScanOptions().apply {
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        setPrompt(context.getString(R.string.key_backup_scan_recovery_prompt))
                                        setCameraId(0)
                                        setBeepEnabled(true)
                                        setBarcodeImageEnabled(false)
                                        setOrientationLocked(false)
                                    }
                                    scanLauncher.launch(options)
                                }
                                else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.key_backup_scan_qr), fontSize = 13.sp)
                    }

                    // Paste from clipboard
                    OutlinedButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                // Strip prefix if present
                                recoveryKeyInput = clipText.removePrefix("oshi-recovery:")
                                recoveryKeyError = null
                                // __CLIPBOARD_HYGIENE_2026_10_01__ pasted into OSHI ⇒ no reason to leave it on the clipboard.
                                com.oshi.messenger.security.SensitiveClipboard.clearIfStillHolds(context, clipText)
                            } else {
                                recoveryKeyError = context.getString(R.string.key_backup_clipboard_empty)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.key_backup_paste), fontSize = 13.sp)
                    }
                }

                // Restore button
                Button(
                    onClick = {
                        // Validate the recovery key first
                        val cleanKey = recoveryKeyInput.trim().removePrefix("oshi-recovery:")
                        try {
                            val decoded = Base64.decode(cleanKey, Base64.NO_WRAP)
                            if (decoded.size != 64) {
                                recoveryKeyError = context.getString(R.string.key_backup_invalid_key_size, decoded.size)
                                return@Button
                            }
                        } catch (e: Exception) {
                            recoveryKeyError = context.getString(R.string.key_backup_invalid_base64)
                            return@Button
                        }

                        if (hasExistingKey) {
                            showRecoveryConfirmDialog = true
                        } else {
                            val success = viewModel.importRecoveryKey(cleanKey)
                            if (success) {
                                statusMessage = context.getString(R.string.key_backup_restored_recovery)
                                onRestoreComplete()
                            } else {
                                recoveryKeyError = context.getString(R.string.recovery_key_restore_failed)
                            }
                        }
                    },
                    enabled = recoveryKeyInput.isNotBlank() && !isRestoring,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OSHIGreen)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.key_backup_restore_recovery_key))
                }
            }
        }

        // Recovery key confirm overwrite dialog
        if (showRecoveryConfirmDialog) {
            OshiAlertDialog(
                onDismissRequest = { showRecoveryConfirmDialog = false },
                icon = {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = OSHIRed,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.key_backup_replace_key_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.key_backup_replace_recovery_warning)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRecoveryConfirmDialog = false
                            val cleanKey = recoveryKeyInput.trim().removePrefix("oshi-recovery:")
                            val success = viewModel.importRecoveryKey(cleanKey)
                            if (success) {
                                statusMessage = context.getString(R.string.key_backup_identity_restored)
                                onRestoreComplete()
                            } else {
                                recoveryKeyError = context.getString(R.string.key_backup_restore_identity_failed)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = OSHIRed)
                    ) {
                        Text(stringResource(R.string.key_backup_replace_key))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRecoveryConfirmDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        Divider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Text(
            text = stringResource(R.string.passphrase_encrypted_backup),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        // Input action buttons row: Scan QR | Paste from Clipboard
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Scan QR Code button
            Button(
                onClick = startQRScan,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.key_backup_scan_qr), fontSize = 13.sp)
            }

            // Paste from clipboard button
            OutlinedButton(
                onClick = {
                    val clipText = clipboardManager.getText()?.text
                    if (!clipText.isNullOrBlank()) {
                        encryptedKeyInput = clipText
                        errorMessage = null
                    } else {
                        errorMessage = context.getString(R.string.key_backup_clipboard_empty)
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.key_backup_paste), fontSize = 13.sp)
            }
        }

        // Encrypted key input
        OutlinedTextField(
            value = encryptedKeyInput,
            onValueChange = {
                encryptedKeyInput = it
                errorMessage = null
            },
            label = { Text(stringResource(R.string.key_backup_encrypted_key_label)) },
            placeholder = { Text(stringResource(R.string.key_backup_paste_base64)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp),
            maxLines = 5,
            shape = RoundedCornerShape(12.dp)
        )

        // Passphrase input
        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                errorMessage = null
            },
            label = { Text(stringResource(R.string.key_backup_passphrase)) },
            placeholder = { Text(stringResource(R.string.key_backup_enter_passphrase)) },
            visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            // __ANTI_STALKERWARE_2026_11_20__ a passphrase is a password to the keyboard:
            // no suggestions, no learning, no autocorrect — even when the incognito
            // keyboard toggle is off.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                autoCorrect = false
            ),
            trailingIcon = {
                IconButton(onClick = { showPassphrase = !showPassphrase }) {
                    Icon(
                        imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (showPassphrase) R.string.accessibility_hide_passphrase
                            else R.string.accessibility_show_passphrase
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Error / status messages
        errorMessage?.let { error ->
            // Was a bare `Text` in OSHIRed, the SAME shape as the success line
            // below it — the two were distinguished by hue alone, which is not
            // a distinction for a red/green colour-blind user and is not one on
            // a greyscale screenshot either. OshiInlineMessage gives each tone
            // its own glyph, its own container, and an announcement.
            OshiInlineMessage(message = error, tone = InlineTone.Error)
        }

        statusMessage?.let { status ->
            OshiInlineMessage(message = status, tone = InlineTone.Success)
        }

        // Restore button
        Button(
            onClick = {
                if (hasExistingKey) {
                    showConfirmDialog = true
                } else {
                    scope.launch {
                        performRestore(
                            context = context,
                            encryptedKeyBase64 = encryptedKeyInput.trim(),
                            passphrase = passphrase,
                            viewModel = viewModel,
                            onProgress = { isRestoring = it },
                            onError = { errorMessage = it },
                            onSuccess = {
                                statusMessage = context.getString(R.string.key_backup_restored_success)
                                onRestoreComplete()
                            }
                        )
                    }
                }
            },
            enabled = canRestore,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
        ) {
            if (isRestoring) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.key_backup_restore_key))
        }
    }

    // Confirm overwrite dialog
    if (showConfirmDialog) {
        OshiAlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = OSHIRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.key_backup_replace_key_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.key_backup_replace_encrypted_warning)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        scope.launch {
                            performRestore(
                                context = context,
                                encryptedKeyBase64 = encryptedKeyInput.trim(),
                                passphrase = passphrase,
                                viewModel = viewModel,
                                onProgress = { isRestoring = it },
                                onError = { errorMessage = it },
                                onSuccess = {
                                    statusMessage = context.getString(R.string.key_backup_key_restored)
                                    onRestoreComplete()
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OSHIRed)
                ) {
                    Text(stringResource(R.string.key_backup_replace_key))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// =============================================================================
// QR CODE EXPORT DIALOG
// =============================================================================

@Composable
private fun QRCodeExportDialog(
    encryptedKey: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(encryptedKey) {
        generateBackupQRCode("oshi-backup:$encryptedKey")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 🔴 SHORT-WINDOW FIX. This dialog held ~590 dp of fixed content in a
        // container that did not scroll, inside `usePlatformDefaultWidth =
        // false` so it takes the whole window. From targetSdk 36 a phone
        // rotates whatever the manifest asks, and at 869 x 400 dp the button
        // below the QR code was off the bottom edge — the user could not
        // dismiss or save the only screen that exports their identity.
        //
        // Two moves, in this order: shrink the ART (a 160 dp QR still scans;
        // a button below the fold does not exist), then scroll what is left.
        val window = rememberWindowSize()
        // 24 dialog pad x2 + 24 card pad x2 + 32 icon + ~26 title + ~40 body
        // + ~48 button + 4 x 16 gaps.
        val qrSize = fittedArtSize(
            windowHeightDp = window.heightDp,
            reservedDp = 306,
            preferred = 260.dp,
            minimum = 160.dp
        )
        OshiCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = brandInk()
                )

                Text(
                    text = stringResource(R.string.key_backup_encrypted_backup_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(R.string.key_backup_qr_scan_desc),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // QR Code
                OshiCard(
                    modifier = Modifier.size(qrSize),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.accessibility_encrypted_qr),
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: Text(
                            text = stringResource(R.string.key_backup_qr_too_large),
                            textAlign = TextAlign.Center,
                            color = OSHIRed,
                            fontSize = 13.sp
                        )
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

// =============================================================================
// RECOVERY KEY QR CODE DIALOG
// =============================================================================

@Composable
private fun RecoveryKeyQRDialog(
    recoveryKey: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(recoveryKey) {
        generateBackupQRCode("oshi-recovery:$recoveryKey")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 🔴 SHORT-WINDOW FIX. This dialog held ~590 dp of fixed content in a
        // container that did not scroll, inside `usePlatformDefaultWidth =
        // false` so it takes the whole window. From targetSdk 36 a phone
        // rotates whatever the manifest asks, and at 869 x 400 dp the button
        // below the QR code was off the bottom edge — the user could not
        // dismiss or save the only screen that exports their identity.
        //
        // Two moves, in this order: shrink the ART (a 160 dp QR still scans;
        // a button below the fold does not exist), then scroll what is left.
        val window = rememberWindowSize()
        // 24 dialog pad x2 + 24 card pad x2 + 32 icon + ~26 title + ~40 body
        // + ~48 button + 4 x 16 gaps.
        val qrSize = fittedArtSize(
            windowHeightDp = window.heightDp,
            reservedDp = 306,
            preferred = 260.dp,
            minimum = 160.dp
        )
        OshiCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = brandInk()
                )

                Text(
                    text = stringResource(R.string.key_backup_recovery_key),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(R.string.key_backup_recovery_qr_desc),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // QR Code
                OshiCard(
                    modifier = Modifier.size(qrSize),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.accessibility_recovery_qr),
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: Text(
                            text = stringResource(R.string.key_backup_qr_too_large_copy),
                            textAlign = TextAlign.Center,
                            color = OSHIRed,
                            fontSize = 13.sp
                        )
                    }
                }

                // Warning
                Text(
                    text = stringResource(R.string.key_backup_anyone_scan_qr),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = OSHIRed,
                    fontWeight = FontWeight.Medium
                )

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

// =============================================================================
// ENCRYPTION / DECRYPTION HELPERS
// =============================================================================

/**
 * Export the private key encrypted with a passphrase-derived AES-256-GCM key.
 * Format: Base64( salt(16) + iv(12) + ciphertext+tag )
 */
private suspend fun exportEncryptedKey(
    context: android.content.Context,
    passphrase: String
): String = withContext(Dispatchers.Default) {
    // Read private key from encrypted prefs
    // SECURITY (CSPN 2026-09): prefer a StrongBox-backed master key, fall back if
    // the device has no StrongBox HSM.
    val masterKey = try {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)
            .build()
    } catch (e: Exception) {
        Log.w(TAG, "StrongBox unavailable for master key, falling back: ${e.message}")
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val privateKeyBase64 = encryptedPrefs.getString(KEY_PRIVATE_KEY, null)
        ?: throw IllegalStateException("No private key found in secure storage")
    val privateKeyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)

    // Also export signing keys if available
    val signingPrivateBase64 = encryptedPrefs.getString(KEY_SIGNING_PRIVATE_KEY, null)
    val signingPublicBase64 = encryptedPrefs.getString(KEY_SIGNING_PUBLIC_KEY, null)

    // Build payload: privateKey + optionally signing keys
    // Format: [1 byte version] + [32 bytes X25519 private key] + [32 bytes Ed25519 signing private] + [32 bytes Ed25519 signing public]
    val payloadBuilder = mutableListOf<Byte>()
    payloadBuilder.add(0x01) // version byte
    payloadBuilder.addAll(privateKeyBytes.toList())

    if (signingPrivateBase64 != null && signingPublicBase64 != null) {
        val signingPrivate = Base64.decode(signingPrivateBase64, Base64.NO_WRAP)
        val signingPublic = Base64.decode(signingPublicBase64, Base64.NO_WRAP)
        if (signingPrivate.size == 32 && signingPublic.size == 32) {
            payloadBuilder.addAll(signingPrivate.toList())
            payloadBuilder.addAll(signingPublic.toList())
        }
    }

    val payload = payloadBuilder.toByteArray()

    // Derive key from passphrase using PBKDF2 (see KEY_BACKUP_PBKDF2_ITERATIONS)
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val derivedKey = deriveKeyBackupKey(passphrase, salt, KEY_BACKUP_PBKDF2_ITERATIONS)

    // Encrypt with AES-256-GCM
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKey = SecretKeySpec(derivedKey, "AES")
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
    val encrypted = cipher.doFinal(payload)

    // Package: salt(16) + iv(12) + encrypted (includes GCM tag)
    val result = salt + iv + encrypted
    Base64.encodeToString(result, Base64.NO_WRAP)
}

/**
 * Decrypt an encrypted key backup and restore it into CryptoManager and encrypted prefs.
 */
private suspend fun performRestore(
    context: android.content.Context,
    encryptedKeyBase64: String,
    passphrase: String,
    viewModel: MainViewModel,
    onProgress: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onSuccess: () -> Unit
) {
    onProgress(true)
    try {
        val restoredKeys = withContext(Dispatchers.Default) {
            decryptKeyBackup(encryptedKeyBase64, passphrase)
        }

        // Store restored keys in encrypted prefs
        // SECURITY (CSPN 2026-09): prefer StrongBox-backed master key with fallback.
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox unavailable for master key, falling back: ${e.message}")
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        val encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Compute public key from the restored private key by loading it into CryptoManager
        // X25519: set the private key, derive the public key
        val privateKeyBytes = restoredKeys.x25519Private

        // Use CryptoManager to derive the public key from the private key
        // We set the key pair - X25519PrivateKeyParameters will derive the public key
        val tempPrivateParams = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(privateKeyBytes, 0)
        val derivedPublicBytes = tempPrivateParams.generatePublicKey().encoded
        val publicKeyBase64 = Base64.encodeToString(derivedPublicBytes, Base64.NO_WRAP)

        // Save to encrypted prefs
        encryptedPrefs.edit().apply {
            putString(KEY_PRIVATE_KEY, Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP))
            if (restoredKeys.signingPrivate != null && restoredKeys.signingPublic != null) {
                putString(KEY_SIGNING_PRIVATE_KEY, Base64.encodeToString(restoredKeys.signingPrivate, Base64.NO_WRAP))
                putString(KEY_SIGNING_PUBLIC_KEY, Base64.encodeToString(restoredKeys.signingPublic, Base64.NO_WRAP))
            }
            apply()
        }

        // Update DataStore with new public key
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("public_key")] = publicKeyBase64
        }

        Log.d(TAG, "Key restored successfully, new public key: ${publicKeyBase64.take(20)}...")

        // Re-register FCM token with new public key — task 1.5.6
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken()
                .addOnCompleteListener { deleteTask ->
                    if (deleteTask.isSuccessful) {
                        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                            .addOnSuccessListener { newToken ->
                                Log.d(TAG, "FCM token refreshed after key restore: ${newToken.take(10)}...")
                            }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token refresh failed (non-fatal): ${e.message}")
        }

        // Trigger full multi-device sync after restore — task 1.5.7
        try {
            viewModel.triggerFullSyncAfterRestore(publicKeyBase64)
        } catch (e: Exception) {
            Log.w(TAG, "Full sync after restore failed (non-fatal): ${e.message}")
        }

        withContext(Dispatchers.Main) {
            onSuccess()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Restore failed: ${e.message}", e)
        withContext(Dispatchers.Main) {
            onError(
                when {
                    e.message?.contains("Tag mismatch", ignoreCase = true) == true ||
                    e.message?.contains("AEADBadTagException", ignoreCase = true) == true ->
                        context.getString(R.string.key_backup_wrong_passphrase)
                    e.message?.contains("Base64", ignoreCase = true) == true ||
                    e.message?.contains("decode", ignoreCase = true) == true ->
                        context.getString(R.string.key_backup_invalid_format)
                    else -> context.getString(R.string.key_backup_restore_failed, e.message ?: "")
                }
            )
        }
    } finally {
        onProgress(false)
    }
}

private data class RestoredKeys(
    val x25519Private: ByteArray,
    val signingPrivate: ByteArray?,
    val signingPublic: ByteArray?
)

/**
 * Decrypt a key backup.
 * Input: Base64( salt(16) + iv(12) + ciphertext+tag )
 * Payload: [1 byte version] + [32 bytes X25519 private] + [optional: 32 bytes signing private + 32 bytes signing public]
 */
/**
 * PBKDF2 work factor for the passphrase-wrapped identity backup.
 *
 * This blob is offline-attackable by design — it leaves the device, so whoever holds it can
 * grind passphrases with no rate limit. 100_000 was about an order of magnitude below current
 * guidance (OWASP puts PBKDF2-HMAC-SHA-256 at 600_000), which is the worst place to be short.
 *
 * Backups written from now on use [KEY_BACKUP_PBKDF2_ITERATIONS]. Backups written before this
 * change still restore, through [decryptWithIterationFallback].
 */
private const val KEY_BACKUP_PBKDF2_ITERATIONS = 600_000
private const val KEY_BACKUP_PBKDF2_ITERATIONS_LEGACY = 100_000

private fun deriveKeyBackupKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return factory.generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)).encoded
}

private fun decryptKeyBackupPayload(key: ByteArray, iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertextWithTag)
}

/**
 * Try the current work factor, then the legacy one, so backups taken before the raise keep
 * restoring. Only an authentication failure triggers the retry: GCM's tag means a wrong key
 * can only fail outright, never half-succeed. Any other exception is a real error and
 * propagates untouched.
 */
private fun decryptWithIterationFallback(
    passphrase: String,
    salt: ByteArray,
    iv: ByteArray,
    ciphertextWithTag: ByteArray,
): ByteArray = try {
    decryptKeyBackupPayload(
        deriveKeyBackupKey(passphrase, salt, KEY_BACKUP_PBKDF2_ITERATIONS), iv, ciphertextWithTag
    )
} catch (_: AEADBadTagException) {
    decryptKeyBackupPayload(
        deriveKeyBackupKey(passphrase, salt, KEY_BACKUP_PBKDF2_ITERATIONS_LEGACY), iv, ciphertextWithTag
    )
}

private fun decryptKeyBackup(encryptedBase64: String, passphrase: String): RestoredKeys {
    // Strip the "oshi-backup:" prefix if present (from QR code scan)
    val cleanBase64 = if (encryptedBase64.startsWith("oshi-backup:")) {
        encryptedBase64.removePrefix("oshi-backup:")
    } else {
        encryptedBase64
    }

    val data = Base64.decode(cleanBase64, Base64.NO_WRAP)
    require(data.size >= 16 + 12 + 33 + 16) { "Backup data too short" } // salt + iv + min payload + GCM tag

    val salt = data.sliceArray(0 until 16)
    val iv = data.sliceArray(16 until 28)
    val ciphertextWithTag = data.sliceArray(28 until data.size)

    // Derive and decrypt. The version byte lives INSIDE the ciphertext, so we cannot read
    // it to pick an iteration count — we try the current count, then the legacy one.
    // GCM's tag makes that unambiguous: a wrong key can only fail, never half-succeed.
    val payload = decryptWithIterationFallback(passphrase, salt, iv, ciphertextWithTag)

    // Parse payload
    require(payload.isNotEmpty()) { "Empty payload after decryption" }
    val version = payload[0].toInt()
    require(version == 1) { "Unsupported backup version: $version" }

    val x25519Private = payload.sliceArray(1 until 33)
    require(x25519Private.size == 32) { "Invalid X25519 private key size" }

    var signingPrivate: ByteArray? = null
    var signingPublic: ByteArray? = null

    if (payload.size >= 97) { // 1 + 32 + 32 + 32
        signingPrivate = payload.sliceArray(33 until 65)
        signingPublic = payload.sliceArray(65 until 97)
    }

    return RestoredKeys(x25519Private, signingPrivate, signingPublic)
}

// =============================================================================
// QR CODE GENERATION
// =============================================================================

private fun generateBackupQRCode(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        // Use higher error correction for backup data
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            com.google.zxing.EncodeHintType.MARGIN to 1
        )
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        Log.e(TAG, "Failed to generate QR code: ${e.message}")
        null
    }
}
