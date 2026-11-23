package com.oshi.messenger.ui.screens

import android.content.Intent
import com.oshi.messenger.ui.theme.OshiCard
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRCodeScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val publicKey by viewModel.publicKey.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showCopied by remember { mutableStateOf(false) }

    // Generate QR code.
    //
    // The payload is the RAW public key, exactly what iOS encodes
    // (`QRCodeDisplayView.swift:272`: `filter.message = Data(self.publicKey.utf8)`).
    // Android used to emit `oshi:<key>` and iOS's scanner strips no prefix
    // (`QRScannerView.swift:132-137` hands the string straight through), so an
    // iPhone scanning an Android contact QR ended up with `oshi:<base64>` as the
    // peer address and could never message them. Android's own scanner still
    // accepts both forms (NewMessageScreen.kt:105), so codes already printed or
    // screenshotted keep working.
    val qrBitmap = remember(publicKey) {
        if (publicKey.isNotEmpty()) {
            generateQrBitmapShared(publicKey)
        } else null
    }

    // Compact layout matching iOS QRCodeDisplayView — all visible without scrolling
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Brand gradient, now actually matching iOS.
            //
            // `QRCodeDisplayView.swift:52-59` is a LinearGradient from
            // Color(red: 0.4, green: 0.3, blue: 0.8) = #664CCC to
            // Color(red: 0.6, green: 0.2, blue: 0.7) = #9933B3, running
            // .topLeading → .bottomTrailing.
            //
            // Android had #6650A4 → #9933CC drawn VERTICALLY, under a comment
            // claiming parity. #6650A4 is stock Material Purple40 — visibly less
            // blue than the iOS start stop — and the two apps' hero screen did not
            // match on either colour or direction.
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF664CCC),
                        Color(0xFF9933B3)
                    )
                )
            )
    ) {
        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(4.dp)
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = stringResource(R.string.qr_back),
                tint = Color.White
            )
        }

        // Header + a 260 dp QR card + the key + three buttons is ~600 dp of
        // fixed content. In portrait that spreads out nicely; in a short window
        // (a phone on its side, ~360 dp of height) SpaceBetween pushed the
        // Share and Copy buttons clean off the bottom of a Column that could
        // not scroll. Since targetSdk 36 no longer lets us pin the app to
        // portrait on a 600 dp display, a short window now packs the same
        // content into a scrolling column and shrinks the QR card to fit.
        val compactHeight = rememberWindowSize().height == HeightClass.Compact
        val qrSize = if (compactHeight) 200.dp else 260.dp
        Column(
            modifier = Modifier
                // `align` + `widthIn` must come BEFORE `fillMaxSize`: the other
                // order lets fillMaxSize fix the width to the whole window
                // first, so the cap only clamps the children's constraints
                // while the node still reports full width — the Copy/Share row
                // then stretched the entire way across a tablet.
                .align(Alignment.TopCenter)
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .then(if (compactHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp)
                .padding(top = if (compactHeight) 8.dp else 48.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (compactHeight) {
                Arrangement.spacedBy(12.dp)
            } else {
                Arrangement.SpaceBetween
            }
        ) {
            // Header (compact)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = Color.White.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = displayName.ifEmpty { stringResource(R.string.qr_my_public_key) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.qr_share_to_connect),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            // QR Code card (260dp — closer to iOS 280pt; 200 dp when the
            // window is too short for the full size)
            OshiCard(
                modifier = Modifier.size(qrSize),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_content_description),
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: CircularProgressIndicator(color = Color(0xFF6650A4))
                }
            }

            // Inline truncated key under QR card
            Text(
                text = if (publicKey.length > 16) {
                    "${publicKey.take(8)}...${publicKey.takeLast(8)}"
                } else publicKey,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )

            // Buttons row (horizontal like iOS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Copy button
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(publicKey))
                        showCopied = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, Color.White.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (showCopied) stringResource(R.string.copied) else stringResource(R.string.copy),
                        fontSize = 13.sp
                    )
                }

                // Share button
                Button(
                    onClick = {
                        val addMeText = context.getString(R.string.qr_add_me)
                        val keyLabel = context.getString(R.string.qr_my_public_key_label)
                        // __FIRST_CONTACT_REMOTE_2026_10_10__ The link is built by
                        // `InviteLink` now, not inline here. It was constructed in
                        // two places with the host and the escaping written out by
                        // hand, and a link made on one platform is opened on the
                        // other — so two copies of that format is one drift away
                        // from an invite that silently does not work.
                        val shareText = buildString {
                            appendLine(
                                com.oshi.messenger.util.InviteLink.message(
                                    myPublicKey = publicKey,
                                    opener = addMeText,
                                    hint = "",
                                    myName = displayName
                                ).orEmpty().trimEnd()
                            )
                            appendLine()
                            appendLine(keyLabel)
                            appendLine(publicKey)
                        }
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, shareText)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.qr_share_public_key)))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF6650A4)
                    )
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.share), fontSize = 13.sp)
                }
            }

            // 3-step instructions (matching iOS QRCodeDisplayView lines 186-195)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InstructionRow(step = "1", text = stringResource(R.string.qr_open_on_other_device))
                InstructionRow(step = "2", text = stringResource(R.string.qr_go_to_scan))
                InstructionRow(step = "3", text = stringResource(R.string.qr_scan_to_connect))
            }
        }
    }

    // Reset "Copied" state after delay
    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(2000)
            showCopied = false
        }
    }
}

@Composable
private fun InstructionRow(step: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Step number circle
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        Text(
            text = text,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

/**
 * `internal`, not `private`: [SafetyNumberScreen] renders the safety-number QR with the
 * exact same encoder. A second copy would be a second place for the 512 px size, the
 * RGB_565 config and the swallowed-exception fallback to drift.
 */
internal fun generateQrBitmapShared(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
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
        null
    }
}
