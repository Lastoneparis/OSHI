package com.oshi.messenger.ui.screens

import android.text.format.DateUtils
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.oshi.messenger.R
import com.oshi.messenger.network.web.WebSession
import com.oshi.messenger.network.web.WebSessionManager
import com.oshi.messenger.ui.viewmodel.WebSessionViewModel
import com.oshi.messenger.ui.theme.OSHIGreen

private val WebBrandColor = Color(0xFF5616EC)
private val WebBrandPurple = Color(0xFF9933FF)
private val WebBrandGradient = Brush.linearGradient(listOf(WebBrandColor, WebBrandPurple))
private val WebActiveGreen = OSHIGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSessionScreen(
    onBack: () -> Unit,
    viewModel: WebSessionViewModel = hiltViewModel()
) {
    val activeSessions by viewModel.activeSessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val lastRefresh by viewModel.lastRefresh.collectAsState()
    val scanResult by viewModel.scanResult.collectAsState()

    var sessionToRevoke by remember { mutableStateOf<WebSession?>(null) }
    var showRevokeAllDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scanPrompt = stringResource(R.string.web_session_scan_prompt)

    // In-process ZXing scanner — same pattern used in GroupsScreen / NewMessageScreen.
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { content ->
            com.oshi.messenger.ui.scan.ScanFeedback.success(context)
            viewModel.handleScannedQR(content)
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Toast scan results then clear so the same result can't fire twice.
    LaunchedEffect(scanResult) {
        scanResult?.let { r ->
            // __WEBSESSION_E2E_2026_09_23__ say what the link actually is: end-to-end (with the code
            // the browser shows) or NOT end-to-end. Never the old blanket "encrypted" claim.
            val text = when (r.code) {
                WebSessionManager.ResultCode.LINKED_E2E ->
                    context.getString(R.string.web_session_link_success_e2e, r.sas?.let(::formatSas) ?: "")
                WebSessionManager.ResultCode.LINKED_V1 -> context.getString(R.string.web_session_link_success_not_e2e)
                WebSessionManager.ResultCode.RELOAD_AND_RESCAN -> context.getString(R.string.web_session_qr_reload)
                WebSessionManager.ResultCode.INVALID_QR -> context.getString(R.string.web_session_qr_invalid)
                // r.message comes from the relay (already localized server-side OR English).
                else -> r.message.ifBlank { context.getString(R.string.web_session_link_failed) }
            }
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearScanResult()
            // Refresh the list — a successful scan should add a new session within ~1s.
            if (r.success) viewModel.refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.web_session_back)
                        )
                    }
                },
                title = { Text(stringResource(R.string.web_session_screen_title)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ───────────── Section 1: OSHI WEB header + "Link a Computer" tile ─────────────
            item {
                SectionHeader(text = stringResource(R.string.web_session_section_oshi_web))
            }
            item {
                LinkComputerCard(
                    onClick = {
                        val options = ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt(scanPrompt)
                            setBeepEnabled(false)
                            setOrientationLocked(true)
                    setCaptureActivity(com.oshi.messenger.ui.scan.OshiScanActivity::class.java)
                            setBarcodeImageEnabled(false)
                        }
                        qrScanLauncher.launch(options)
                    }
                )
            }
            item {
                SectionFooter(text = stringResource(R.string.web_session_footer))
            }

            // ───────────── Section 2: Active Sessions header + list/empty ─────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(
                        text = stringResource(R.string.web_session_active_sessions).uppercase() +
                                " (${activeSessions.size})",
                        modifier = Modifier.weight(1f)
                    )
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            if (activeSessions.isEmpty() && !isLoading) {
                item { EmptySessionsState() }
            } else if (activeSessions.isEmpty() && isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 30.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
            } else {
                items(activeSessions, key = { it.sessionId }) { session ->
                    WebSessionRow(
                        session = session,
                        onRevoke = { sessionToRevoke = session }
                    )
                }
            }

            lastRefresh?.let { ts ->
                item {
                    SectionFooter(
                        text = stringResource(R.string.web_session_last_updated) + " " +
                                relativeTime(ts)
                    )
                }
            }

            // ───────────── Section 3: Revoke All (only when ≥1 session) ─────────────
            if (activeSessions.isNotEmpty()) {
                item {
                    RevokeAllButton(onClick = { showRevokeAllDialog = true })
                }
                item {
                    SectionFooter(text = stringResource(R.string.web_session_revoke_all_footer))
                }
            }

            // ───────────── Section 4: Security info ─────────────
            item {
                SectionHeader(text = stringResource(R.string.web_session_security_section))
            }
            // __WEBSESSION_E2E_2026_09_23__ The old card and footer told EVERY user their web session
            // was end-to-end encrypted — false for all of them before v2 (the relay saw plaintext;
            // C-WEB-1, the claim iOS already removed). Both now state what is true per session.
            val anyPlaintext = activeSessions.any { !it.isEndToEnd }
            item {
                SecurityInfoCard(anyPlaintext = anyPlaintext)
            }

            item {
                E2EFooter(anyPlaintext = anyPlaintext)
            }
        }
    }

    // ── Revoke single session dialog ──
    sessionToRevoke?.let { session ->
        OshiAlertDialog(
            onDismissRequest = { sessionToRevoke = null },
            title = { Text(stringResource(R.string.web_session_revoke_session_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.web_session_revoke_message,
                        session.browserName,
                        session.browserOS
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.terminateSession(session.sessionId)
                    sessionToRevoke = null
                }) {
                    Text(
                        stringResource(R.string.web_session_revoke),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRevoke = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ── Revoke all sessions dialog ──
    if (showRevokeAllDialog) {
        OshiAlertDialog(
            onDismissRequest = { showRevokeAllDialog = false },
            title = { Text(stringResource(R.string.web_session_revoke_all_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.web_session_revoke_all_message,
                        activeSessions.size
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.terminateAllSessions()
                    showRevokeAllDialog = false
                }) {
                    Text(
                        stringResource(R.string.web_session_revoke_all_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section primitives (mimic iOS grouped-List look on Compose)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 0.dp, bottom = 4.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Link Computer (primary entry to the QR scanner)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LinkComputerCard(onClick: () -> Unit) {
    OshiCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(WebBrandGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.web_session_link_computer),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.web_session_scan_qr_subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Active session row
// ─────────────────────────────────────────────────────────────────────────────

private fun browserIconFor(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.contains("chrome") -> Icons.Default.Public
        n.contains("safari") -> Icons.Default.Public
        n.contains("firefox") -> Icons.Default.Public
        n.contains("edge") -> Icons.Default.Public
        n.contains("opera") -> Icons.Default.Public
        n.contains("brave") -> Icons.Default.Public
        else -> Icons.Default.Computer
    }
}

@Composable
private fun WebSessionRow(
    session: WebSession,
    onRevoke: () -> Unit
) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Browser icon in purple-tinted circle (mirrors iOS WebSessionRowView)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(WebBrandColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    browserIconFor(session.browserName),
                    contentDescription = null,
                    tint = WebBrandColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.browserName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (session.isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = WebActiveGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.web_session_badge_active),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = WebActiveGreen,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = session.browserOS,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (session.lastActive > 0L) {
                    Text(
                        text = relativeTime(session.lastActive),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // __WEBSESSION_E2E_2026_09_23__ per-session truth + the code the browser shows.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (session.isEndToEnd) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (session.isEndToEnd) WebActiveGreen else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            session.isEndToEnd && session.sas != null ->
                                stringResource(R.string.web_session_row_e2e_code, formatSas(session.sas))
                            session.isEndToEnd -> stringResource(R.string.web_session_row_e2e)
                            else -> stringResource(R.string.web_session_row_not_e2e)
                        },
                        fontSize = 12.sp,
                        color = if (session.isEndToEnd) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            }

            // No swipe-to-revoke on Compose LazyColumn out of the box — use a trailing icon
            // button instead. Functionally equivalent to iOS swipeActions destructive button.
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.web_session_revoke),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptySessionsState() {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.web_session_no_sessions),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Revoke all
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RevokeAllButton(onClick: () -> Unit) {
    OshiCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Cancel,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.web_session_revoke_all_button),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Security info (3 rows)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SecurityInfoCard(anyPlaintext: Boolean) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecurityInfoRow(
                icon = Icons.Default.Schedule,
                text = stringResource(R.string.web_session_security_expire)
            )
            SecurityInfoRow(
                icon = Icons.Default.Shield,
                text = stringResource(R.string.web_session_security_e2e)
            )
            if (anyPlaintext) {
                SecurityInfoRow(
                    icon = Icons.Default.LockOpen,
                    text = stringResource(R.string.web_session_security_not_e2e)
                )
            }
            SecurityInfoRow(
                icon = Icons.Default.VisibilityOff,
                text = stringResource(R.string.web_session_security_no_contacts)
            )
        }
    }
}

@Composable
private fun SecurityInfoRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = WebActiveGreen,
            modifier = Modifier
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E footer (lock + 1-sentence note)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun E2EFooter(anyPlaintext: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (anyPlaintext) Icons.Default.LockOpen else Icons.Default.Lock,
            contentDescription = null,
            tint = if (anyPlaintext) MaterialTheme.colorScheme.error else WebActiveGreen,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(if (anyPlaintext) R.string.web_session_notice_not_e2e else R.string.web_session_notice_e2e),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** The code exactly as the browser prints it (`web.html`: six digits, no grouping), so they compare at a glance. */
private fun formatSas(sas: String): String = sas

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    return DateUtils.getRelativeTimeSpanString(
        ts,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
}
