package com.oshi.messenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.oshi.messenger.R
import com.oshi.messenger.ui.components.InlineTone
import com.oshi.messenger.ui.components.OshiInlineMessage
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.viewmodel.MainViewModel
import com.oshi.messenger.ui.viewmodel.SafetyNumberViewModel
import com.oshi.messenger.ui.viewmodel.ScanOutcome

/**
 * Letter spacing for the safety number, as a fraction of the font size.
 *
 * The whole point of this block is that two people read it to each other over a call they
 * already trust, so the digits have to be separable by eye at speed. Expressed in `em`
 * rather than `sp` because the font size itself is computed from the available width —
 * a fixed `sp` spacing would be generous on a tablet and crowding on a small phone.
 */
private const val CODE_LETTER_SPACING_EM = 0.10f

/**
 * Advance width of one monospace glyph, in em. Android's default monospace family measures
 * ~0.60; 0.62 is deliberate headroom so a font substitution on an OEM ROM cannot push a
 * row past the edge of its container and clip digits off the end — a clipped safety number
 * is worse than a small one, because it still looks like a safety number.
 */
private const val CODE_GLYPH_ADVANCE_EM = 0.62f

/**
 * Contact verification — the screen that makes OSHI's end-to-end encryption checkable by
 * the person relying on it.
 *
 * `SafetyNumberManager` and its iOS twin have existed, byte-compatible, with zero UI on
 * either platform: the app could compute a fingerprint nobody could ever see. This is that
 * missing surface.
 *
 * Three ways to establish trust, in descending order of how much the user has to do:
 *
 *  1. **Read the digits aloud.** The 60-digit block is the visual focus for exactly this
 *     reason, and it is why the font is sized to the widest row that fits rather than to a
 *     constant that happens to look right on one device.
 *  2. **Scan their code.** Byte-compares the scanned payload against the payload we expect,
 *     and marks the contact verified on a match in the same step.
 *  3. **Confirm manually**, after doing (1).
 *
 * Not wired into navigation here — the route `"safetyNumber/{theirPublicKey}"` is
 * registered by the owner of `MainActivity`.
 *
 * @param theirPublicKey the contact's base64 public key, exactly as it is displayed and
 *   stored. It must NOT be normalised on the way in: the fingerprint is derived from the
 *   sorted *displayed* base64 strings, so a re-encoded key produces a different, valid
 *   looking, and completely wrong safety number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyNumberScreen(
    theirPublicKey: String,
    onBack: () -> Unit,
    // Pass the activity's instance: a default hiltViewModel() inside a NavHost
    // destination builds a second MainViewModel that re-runs identity restore
    // and initializeServices() on the main thread (__SETTINGS_FREEZE_2026_11_09__).
    mainViewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val viewModel: SafetyNumberViewModel = hiltViewModel()

    val myPublicKey by mainViewModel.publicKey.collectAsState()
    val safetyNumber by viewModel.safetyNumber.collectAsState()
    val isVerified by viewModel.isVerified.collectAsState()
    val keyChanged by viewModel.keyChanged.collectAsState()
    val scanOutcome by viewModel.scanOutcome.collectAsState()

    // `publicKey` starts empty and is filled once DataStore has been read, so this
    // legitimately runs twice. The ViewModel deduplicates.
    LaunchedEffect(myPublicKey, theirPublicKey) {
        viewModel.load(myPublicKey, theirPublicKey)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onCodeScanned(it) }
    }

    val launchScanner: () -> Unit = {
        viewModel.dismissScanOutcome()
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(context.getString(R.string.safety_number_scan))
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            // Both halves are load-bearing: OshiScanActivity is pinned to portrait in the
            // manifest AND locked here, because the lock only freezes whatever orientation
            // the activity started in.
            setOrientationLocked(true)
            setCaptureActivity(com.oshi.messenger.ui.scan.OshiScanActivity::class.java)
        }
        scanLauncher.launch(options)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchScanner()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.error_camera_permission),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val onScanRequested: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val compactHeight = rememberWindowSize().height == HeightClass.Compact
    val qrSize = if (compactHeight) 148.dp else 200.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.safety_number_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val number = safetyNumber
            if (number == null) {
                // Not an empty state: the key is still being read off disk. An empty state
                // drawn here would be indistinguishable from "this contact cannot be
                // verified", which is the one wrong thing this screen could say.
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Column(
                    modifier = Modifier
                        // `align` + `widthIn` before `fillMaxSize`, so the cap governs the
                        // node's own width on a tablet and not merely its children's
                        // constraints.
                        .align(Alignment.TopCenter)
                        .widthIn(max = 520.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // No `navigationBarsPadding()` here: Scaffold's default
                        // `contentWindowInsets` is `systemBars`, so `innerPadding` already
                        // clears the nav bar and adding it again double-pads the bottom.
                        .padding(horizontal = Oshi.Space.lg, vertical = Oshi.Space.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Oshi.Space.lg)
                ) {
                    VerificationStatusChip(isVerified = isVerified)

                    if (keyChanged) {
                        OshiInlineMessage(
                            message = stringResource(R.string.safety_number_changed),
                            tone = InlineTone.Error
                        )
                    }

                    SafetyNumberBlock(rows = number.getCodeRows())

                    Text(
                        text = stringResource(R.string.safety_number_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    scanOutcome?.let { outcome ->
                        OshiInlineMessage(
                            message = when (outcome) {
                                ScanOutcome.Match -> stringResource(R.string.safety_number_match)
                                ScanOutcome.Mismatch -> stringResource(R.string.safety_number_mismatch)
                            },
                            tone = when (outcome) {
                                ScanOutcome.Match -> InlineTone.Success
                                ScanOutcome.Mismatch -> InlineTone.Error
                            }
                        )
                    }

                    SafetyNumberQrCard(qrData = number.qrData, size = qrSize)

                    Button(
                        onClick = onScanRequested,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Oshi.Space.sm))
                        Text(stringResource(R.string.safety_number_scan))
                    }

                    // Offered while unverified, and again once the key has changed —
                    // a contact whose key rotated needs to be re-confirmed, and the
                    // stored "verified" flag alone would hide that.
                    if (!isVerified || keyChanged) {
                        OutlinedButton(
                            onClick = { viewModel.markVerified() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(Oshi.Space.sm))
                            Text(stringResource(R.string.safety_number_verify))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The 60 digits, in the two rows `SafetyNumber.getCodeRows()` produces.
 *
 * Sized by measurement rather than by a constant. A row is 30 digits plus 5 separating
 * spaces; at any fixed `sp` that either overflows a small phone or wastes half a tablet,
 * and this is the one piece of text on the screen that must never be clipped or ellipsised.
 */
@Composable
private fun SafetyNumberBlock(rows: Pair<String, String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = Oshi.Radius.lgShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = Oshi.Space.md,
                vertical = Oshi.Space.xl
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Oshi.Space.md)
        ) {
            Text(
                // Not `.uppercase()`: the no-arg form is Locale.ROOT, which mangles
                // Turkish dotted i, and the locale-aware form mangles a different set.
                // A label style plus tracking reads as a label without touching the text.
                text = stringResource(R.string.encryption_safety_number),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val longestRow = maxOf(rows.first.length, rows.second.length).coerceAtLeast(1)
                val emPerGlyph = CODE_GLYPH_ADVANCE_EM + CODE_LETTER_SPACING_EM
                val codeSize = (maxWidth.value / longestRow / emPerGlyph)
                    .coerceIn(10f, 30f)
                    .sp

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // One label for both rows. `clearAndSetSemantics`, not
                        // `semantics`: the latter adds a node without removing the two
                        // Texts' own, so TalkBack would announce all 60 digits and then
                        // announce them again in halves.
                        .clearAndSetSemantics {
                            contentDescription = "${rows.first} ${rows.second}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Oshi.Space.sm)
                ) {
                    CodeRow(text = rows.first, fontSize = codeSize)
                    CodeRow(text = rows.second, fontSize = codeSize)
                }
            }
        }
    }
}

@Composable
private fun CodeRow(text: String, fontSize: TextUnit) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        letterSpacing = CODE_LETTER_SPACING_EM.em,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The same payload as the digits, for the pair who are in the same room.
 *
 * The bitmap carries its own black-on-white modules and ZXing's four-module quiet zone, so
 * the surrounding container is only ever a frame — it never has to be forced light, and it
 * takes its colour from the scheme like everything else on the screen.
 */
@Composable
private fun SafetyNumberQrCard(qrData: String, size: Dp) {
    val bitmap = remember(qrData) { generateQrBitmapShared(qrData) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Oshi.Space.sm)
    ) {
        Text(
            text = stringResource(R.string.safety_number_show_qr),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = Oshi.Radius.mdShape,
            modifier = Modifier.size(size)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(Oshi.Space.sm),
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_content_description),
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Verified / Not Verified.
 *
 * Carries a glyph as well as a tint, because colour alone is not a distinction (WCAG 1.4.1)
 * and because half of this app's bug reports arrive as greyscale screenshots.
 */
@Composable
private fun VerificationStatusChip(isVerified: Boolean) {
    val accent = if (isVerified) OSHIGreen else OSHIOrange
    val label = if (isVerified) {
        stringResource(R.string.safety_number_verified)
    } else {
        stringResource(R.string.safety_number_not_verified)
    }

    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = Oshi.Radius.pillShape
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = Oshi.Space.lg,
                vertical = Oshi.Space.sm
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isVerified) Icons.Default.CheckCircle else Icons.Default.Shield,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Oshi.Space.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                // Not `accent`: coloured text on a wash of the same colour is the
                // contrast failure the tint was supposed to avoid.
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
