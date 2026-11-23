package com.oshi.messenger.ui.screens

import android.content.Intent
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.components.ChatWallpaperToneSheet
import com.oshi.messenger.ui.components.WallpaperTone
import com.oshi.messenger.ui.components.WallpaperTonePrefs
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.viewmodel.AccountDeletionState
import com.oshi.messenger.ui.viewmodel.ExportResult
import com.oshi.messenger.ui.viewmodel.ImportResult
import com.oshi.messenger.service.MessageExportManager
import com.oshi.messenger.network.v2.MessageExportCrypto
import com.oshi.messenger.ui.viewmodel.MainViewModel
import com.oshi.messenger.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onQRCodeClick: () -> Unit = {},
    onWebSessionClick: () -> Unit = {},
    onBlockedContactsClick: () -> Unit = {},
    /** 🚩 Settings → Privacy → Reported Items. iOS `SettingsView.swift:1010-1017`. */
    onReportedItemsClick: () -> Unit = {},
    onGroupsClick: () -> Unit = {},
    onKeyBackupClick: () -> Unit = {},
    onScheduledCallsClick: () -> Unit = {},
    onScheduledMessagesClick: () -> Unit = {},
    onNearbyClick: () -> Unit = {},
    onPortalClick: () -> Unit = {},
    onProfileLinksClick: () -> Unit = {},
    /** 📡 Opens the Meshtastic LoRa settings screen. iOS `SettingsView.swift:99-105`. */
    onLoRaSettingsClick: () -> Unit = {},
    /** __DEVSYNC_DIRECT_2026_09_22__ Settings → Linked devices (direct own-device sync). */
    onLinkedDevicesClick: () -> Unit = {},
    onLogout: () -> Unit = {},
    /**
     * (N7) Opens straight into the Account category instead of the category
     * list — used by the conversations screen's own-avatar button, which
     * replaced a plain duplicate of the bottom nav's Settings tab.
     */
    openAccountOnStart: Boolean = false,
    mainViewModel: MainViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // Main state
    val publicKey by mainViewModel.publicKey.collectAsState()
    val displayName by mainViewModel.displayName.collectAsState()

    // Settings state
    val profileImageUri by settingsViewModel.profileImageUri.collectAsState()
    val keyCreationDate by settingsViewModel.keyCreationDate.collectAsState()

    // Statistics
    val messageCount by settingsViewModel.messageCount.collectAsState()
    val chatCount by settingsViewModel.chatCount.collectAsState()
    val peersCount by settingsViewModel.connectedPeers.collectAsState()

    // Security
    val biometricEnabled by settingsViewModel.biometricEnabled.collectAsState()
    val biometricAvailable by settingsViewModel.biometricAvailable.collectAsState()
    val screenshotProtection by settingsViewModel.screenshotProtection.collectAsState()
    val autoLockTimeoutSeconds by settingsViewModel.autoLockTimeoutSeconds.collectAsState()
    // __IN_APP_GUIDES_2026_11_02__
    var showGuides by remember { mutableStateOf(false) }
    if (showGuides) {
        com.oshi.messenger.ui.components.GuidesSheet(onDismiss = { showGuides = false })
    }
    var showAutoLockPicker by remember { mutableStateOf(false) }

    // Privacy
    val readReceipts by settingsViewModel.readReceipts.collectAsState()
    val blockForwardCopy by settingsViewModel.blockForwardCopy.collectAsState()
    val privacyRelayEnabled by settingsViewModel.privacyRelayEnabled.collectAsState()
    val panicWipeOnLogo by settingsViewModel.panicWipeOnLogo.collectAsState()
    val lastSeen by settingsViewModel.lastSeen.collectAsState()

    // Notifications
    val notificationsEnabled by settingsViewModel.notificationsEnabled.collectAsState()
    val notificationPreview by settingsViewModel.notificationPreview.collectAsState()

    // Appearance
    val theme by settingsViewModel.theme.collectAsState()
    val oledBlack by settingsViewModel.oledBlack.collectAsState()
    val fontSize by settingsViewModel.fontSize.collectAsState()

    // Export
    val isExporting by settingsViewModel.isExporting.collectAsState()
    val exportResult by settingsViewModel.exportResult.collectAsState()
    val isImporting by settingsViewModel.isImporting.collectAsState()
    val importResult by settingsViewModel.importResult.collectAsState()

    // Privacy - Typing Indicators
    val typingIndicatorsEnabled by settingsViewModel.typingIndicators.collectAsState()

    // Multi-device sync
    val syncEnabled by settingsViewModel.syncEnabled.collectAsState()
    val syncStatus by settingsViewModel.syncStatus.collectAsState()
    val lastSyncTime by settingsViewModel.lastSyncTime.collectAsState()

    // Account deletion
    val accountDeletion by settingsViewModel.accountDeletion.collectAsState()
    var deleteConfirmText by remember { mutableStateOf("") }

    // Calls
    val defaultVoiceEffect by settingsViewModel.defaultVoiceEffect.collectAsState()
    var showVoiceEffectPicker by remember { mutableStateOf(false) }
    val callQuality by settingsViewModel.callQuality.collectAsState()
    var showCallQualityPicker by remember { mutableStateOf(false) }
    val contactsOnlyCalls by settingsViewModel.contactsOnlyCalls.collectAsState()
    val callPrivacyMode by settingsViewModel.callPrivacyMode.collectAsState()
    val forceRelayMode by settingsViewModel.forceRelayMode.collectAsState()

    // Appearance - Bubble Style
    val bubbleStyle by settingsViewModel.bubbleStyle.collectAsState()
    var showBubbleStylePicker by remember { mutableStateOf(false) }
    var showBubbleAppearanceSheet by remember { mutableStateOf(false) }
    var showWallpaperToneSheet by remember { mutableStateOf(false) }

    // Map preference
    val mapOpenPreference by settingsViewModel.mapOpenPreference.collectAsState()
    var showMapPreferencePicker by remember { mutableStateOf(false) }

    // Offline-only routing toggle (mirrors iOS settings_routing_offline_only)
    val routingOfflineOnly by settingsViewModel.routingOfflineOnly.collectAsState()

    // Dialog states
    var showClearSessionsDialog by remember { mutableStateOf(false) }
    var showClearMessagesDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(displayName) }
    var showBackupKeysDialog by remember { mutableStateOf(false) }
    var showIdentityDetailsDialog by remember { mutableStateOf(false) }
    var showSteganographyInfoDialog by remember { mutableStateOf(false) }
    var showRevealPicker by remember { mutableStateOf(false) }
    var showBackupKey by remember { mutableStateOf(false) }
    var showDeleteIdentityDialog by remember { mutableStateOf(false) }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { settingsViewModel.updateProfileImage(it) }
    }

    // Share sheet for the encrypted export: its return is what schedules the
    // temporary file's deletion (MessageExportManager.STALE_EXPORT_MS grace).
    val exportShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { settingsViewModel.onExportShareFinished() }

    // System document picker for importing an `.oshiexport`. The extension has no
    // registered MIME type, so every document is offered; the file's magic header
    // is what decides.
    val importPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { settingsViewModel.importMessages(it) }
    }

    LaunchedEffect(importResult) {
        when (val result = importResult) {
            is ImportResult.Success -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.import_messages_success, result.imported, result.alreadyPresent),
                    Toast.LENGTH_LONG
                ).show()
                settingsViewModel.clearImportResult()
            }
            is ImportResult.Error -> {
                val msg = when (val e = result.error) {
                    is MessageExportManager.ImportError.WrongAccount -> context.getString(R.string.import_error_wrong_account)
                    is MessageExportManager.ImportError.Damaged -> context.getString(R.string.import_error_damaged)
                    is MessageExportManager.ImportError.OtherPlatform -> context.getString(R.string.import_error_other_platform, e.platform)
                    is MessageExportManager.ImportError.NotAnExport -> context.getString(R.string.import_error_not_export)
                    is MessageExportManager.ImportError.UnsupportedVersion -> context.getString(R.string.import_error_version)
                    is MessageExportManager.ImportError.NoAccountKey -> context.getString(R.string.import_error_no_key)
                    is MessageExportManager.ImportError.Unreadable -> context.getString(R.string.import_error_unreadable)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                settingsViewModel.clearImportResult()
            }
            null -> {}
        }
    }

    // Handle export result
    LaunchedEffect(exportResult) {
        when (val result = exportResult) {
            is ExportResult.Success -> {
                // 🔧 P0 FIX (device walkthrough #2, 2026-08-13): `result.uri` is now a
                // FileProvider `content://` Uri (SettingsViewModel.exportMessages), not a
                // raw `file://` one — the latter threw FileUriExposedException here and
                // hard-crashed the app 2/2 times. The grant flag below only means anything
                // for a content:// Uri; with file:// it was decoration on a crash.
                // Encrypted `.oshiexport` (was plaintext JSON until 2026-09-22).
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = MessageExportCrypto.MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, result.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Some targets read the ClipData rather than the extra; giving it the
                    // same Uri makes the read grant apply to both.
                    clipData = android.content.ClipData.newRawUri("", result.uri)
                }
                // Even a correct share can fail (no app can receive JSON). Report it
                // rather than crashing or going silent.
                if (result.mediaSkipped > 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_media_skipped, result.mediaSkipped),
                        Toast.LENGTH_LONG
                    ).show()
                }
                runCatching {
                    exportShareLauncher.launch(
                        Intent.createChooser(intent, context.getString(R.string.export_share))
                    )
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_share_no_app),
                        Toast.LENGTH_LONG
                    ).show()
                }
                settingsViewModel.clearExportResult()
            }
            is ExportResult.Error -> {
                // Was an empty branch with a "// Show error toast/snackbar" TODO, so a
                // failed export looked identical to nothing happening.
                Toast.makeText(
                    context,
                    context.getString(R.string.export_failed_with_reason, result.message),
                    Toast.LENGTH_LONG
                ).show()
                settingsViewModel.clearExportResult()
            }
            null -> {}
        }
    }

    // __SETTINGS_CATEGORIES_2026_10_26__ Settings was ONE scroll holding every
    // section at once: 17 of them, toggles and all, which a user who had just
    // restored onto a new phone described as "defiler infiniment". It is now the
    // shape iOS ships and that they asked for by name: a short list of
    // categories, each opening its own page.
    //
    // `null` IS THE LIST. A value is one category's page, and every section
    // below is gated on it — the sections themselves were not touched, so no
    // row changed behaviour, only where it lives.
    var openCategory by rememberSaveable {
        mutableStateOf<SettingsCategory?>(if (openAccountOnStart) SettingsCategory.ACCOUNT else null)
    }
    val settingsListState = rememberLazyListState()
    // Opening a category must land at ITS top. Without this it inherits the
    // offset the category list was scrolled to and a page can open half-read.
    LaunchedEffect(openCategory) { settingsListState.scrollToItem(0) }
    // System back closes the open category first. Leaving Settings from inside a
    // category would make back skip a level the user can see.
    //
    // __ACCOUNT_IS_ONE_STOP_2026_11_13__ (Anatole N3) Except when Settings was
    // opened STRAIGHT into Account (the avatar on the conversation list): the
    // person never saw the category list, so "back" must take them back where they
    // came from, not through a Settings page they did not ask for.
    val accountIsTheEntry = openAccountOnStart && openCategory == SettingsCategory.ACCOUNT
    BackHandler(enabled = openCategory != null && !accountIsTheEntry) { openCategory = null }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // One button, two meanings: inside a category it returns to the
                    // list, and only from the list does it leave Settings.
                    IconButton(onClick = {
                        if (openCategory != null && !accountIsTheEntry) openCategory = null else onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
                title = {
                    Text(
                        openCategory?.let { stringResource(it.titleRes) }
                            ?: stringResource(R.string.settings_title)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = settingsListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // =========================================================================
            // PROFILE HEADER CARD
            // =========================================================================
            // __ACCOUNT_IS_ONE_STOP_2026_11_13__ (Anatole N3) The card also heads the
            // Account page — photo and name are edited right there, on the page
            // named after them, instead of one level up.
            if (openCategory == null || openCategory == SettingsCategory.ACCOUNT) item {
                ProfileHeaderCard(
                    displayName = displayName.ifEmpty { stringResource(R.string.settings_oshi_user) },
                    publicKey = publicKey,
                    profileImageUri = profileImageUri,
                    creationDate = settingsViewModel.formatCreationDate(keyCreationDate),
                    onEditName = {
                        editedName = displayName
                        showEditNameDialog = true
                    },
                    onEditPhoto = { imagePickerLauncher.launch("image/*") }
                )
            }

            // =========================================================================
            // QUICK STATS CARD
            // =========================================================================
            if (openCategory == null) item {
                QuickStatsCard(
                    messageCount = messageCount,
                    chatCount = chatCount,
                    peersCount = peersCount
                )
            }

            // =========================================================================
            // CATEGORY LIST — the ten rows that replaced the one endless scroll
            // =========================================================================
            if (openCategory == null) item {
                SettingsCategoryList(onOpen = { openCategory = it })
            }

            // =========================================================================
            // ACCOUNT SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.ACCOUNT) item {
                SettingsSection(title = stringResource(R.string.settings_account)) {
                    SettingsItem(
                        icon = Icons.Default.QrCode2,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_my_qr_code),
                        subtitle = stringResource(R.string.settings_share_public_key),
                        onClick = onQRCodeClick
                    )
                    SettingsItem(
                        icon = Icons.Default.Group,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.settings_groups),
                        subtitle = stringResource(R.string.settings_manage_groups),
                        onClick = onGroupsClick
                    )
                    SettingsItem(
                        icon = Icons.Default.Key,
                        iconTint = OSHIOrange,
                        title = stringResource(R.string.settings_backup_keys),
                        subtitle = stringResource(R.string.settings_export_keys),
                        onClick = onKeyBackupClick
                    )
                    SettingsItem(
                        icon = Icons.Default.Info,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.settings_identity_details),
                        subtitle = stringResource(R.string.settings_view_identity),
                        onClick = { showIdentityDetailsDialog = true }
                    )
                    // Guideline 5.1.1(v) wants the words "delete account", not
                    // "delete identity" — iOS gives deletion its own labelled row
                    // for exactly that reason (SettingsView.swift:122-131).
                    SettingsItem(
                        icon = Icons.Default.DeleteForever,
                        iconTint = OSHIRed,
                        title = stringResource(R.string.account_delete_title),
                        subtitle = stringResource(R.string.account_delete_row_subtitle),
                        onClick = {
                            settingsViewModel.resetAccountDeletion()
                            showDeleteIdentityDialog = true
                        }
                    )
                }
            }

            // =========================================================================
            // PROFILE LINKS SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.ACCOUNT) item {
                // The "Show profile links" switch that used to lead this section
                // had no reader — ProfileLinksPinnedBar renders unconditionally
                // (ChatScreen.kt:1334) — and iOS has no such toggle. Removed
                // rather than wired, to match iOS as it ships.
                SettingsSection(title = stringResource(R.string.settings_profile_links)) {
                    SettingsItem(
                        icon = Icons.Default.Edit,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_edit_profile_links),
                        subtitle = stringResource(R.string.settings_edit_profile_links_desc),
                        onClick = onProfileLinksClick
                    )
                }
            }

            // =========================================================================
            // SECURITY SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.PRIVACY) item {
                SettingsSection(title = stringResource(R.string.settings_security)) {
                    SettingsToggleItem(
                        icon = Icons.Default.Fingerprint,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_biometric_lock),
                        subtitle = if (biometricAvailable) stringResource(R.string.settings_biometric_subtitle) else stringResource(R.string.settings_biometric_unavailable),
                        checked = biometricEnabled,
                        enabled = biometricAvailable,
                        onCheckedChange = { settingsViewModel.setBiometricEnabled(it) }
                    )
                    // Auto-lock picker — only meaningful when biometric is enabled (iOS parity)
                    if (biometricEnabled) {
                        SettingsItem(
                            icon = Icons.Default.Lock,
                            iconTint = OSHIPurple,
                            title = stringResource(R.string.settings_auto_lock),
                            subtitle = autoLockLabel(autoLockTimeoutSeconds),
                            onClick = { showAutoLockPicker = true }
                        )
                    }
                    SettingsToggleItem(
                        icon = Icons.Default.ScreenLockPortrait,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.screenshot_protection),
                        subtitle = stringResource(R.string.settings_prevent_screenshots),
                        checked = screenshotProtection,
                        onCheckedChange = { settingsViewModel.setScreenshotProtection(it) }
                    )
                    // (N6, Anatole 13/11) The three-taps-on-the-logo wipe is opt-in.
                    // Off by default; the subtitle says exactly what it does,
                    // because once on it asks for no confirmation.
                    SettingsToggleItem(
                        icon = Icons.Default.DeleteForever,
                        iconTint = OSHIRed,
                        title = stringResource(R.string.settings_panic_wipe_title),
                        subtitle = stringResource(R.string.settings_panic_wipe_subtitle),
                        checked = panicWipeOnLogo,
                        onCheckedChange = { settingsViewModel.setPanicWipeOnLogo(it) }
                    )
                    SettingsItem(
                        icon = Icons.Default.Security,
                        iconTint = OSHIOrange,
                        title = stringResource(R.string.privacy_disguise_title),
                        subtitle = stringResource(R.string.privacy_disguise_subtitle),
                        onClick = { showSteganographyInfoDialog = true }
                    )
                    SettingsItem(
                        icon = Icons.Default.Key,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_key_backup_restore),
                        subtitle = stringResource(R.string.settings_key_backup_restore_desc),
                        onClick = onKeyBackupClick
                    )
                    SettingsItem(
                        icon = Icons.Default.EventAvailable,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.settings_scheduled_calls),
                        subtitle = stringResource(R.string.settings_scheduled_calls_desc),
                        onClick = onScheduledCallsClick
                    )
                    SettingsItem(
                        icon = Icons.Default.Schedule,
                        iconTint = OSHIOrange,
                        title = stringResource(R.string.settings_scheduled_messages),
                        subtitle = stringResource(R.string.settings_scheduled_messages_desc),
                        onClick = onScheduledMessagesClick
                    )
                }
            }

            // =========================================================================
            // PRIVACY SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.PRIVACY) item {
                SettingsSection(title = stringResource(R.string.settings_privacy)) {
                    SettingsToggleItem(
                        icon = Icons.Default.DoneAll,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.settings_read_receipts),
                        subtitle = stringResource(R.string.settings_read_receipts_desc),
                        checked = readReceipts,
                        onCheckedChange = { settingsViewModel.setReadReceipts(it) }
                    )
                    SettingsToggleItem(
                        icon = Icons.Default.AccessTime,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_last_seen),
                        subtitle = stringResource(R.string.settings_last_seen_desc),
                        checked = lastSeen,
                        onCheckedChange = { settingsViewModel.setLastSeen(it) }
                    )
                    SettingsToggleItem(
                        icon = Icons.Default.Keyboard,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.settings_typing_indicators),
                        subtitle = stringResource(R.string.settings_typing_indicators_desc),
                        checked = typingIndicatorsEnabled,
                        onCheckedChange = { settingsViewModel.setTypingIndicators(it) }
                    )
                    // Off by default. The subtitle states the limit plainly:
                    // this restricts this device, not the recipient.
                    SettingsToggleItem(
                        icon = Icons.Default.ContentCopy,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_block_forward_copy),
                        subtitle = stringResource(R.string.settings_block_forward_copy_desc),
                        checked = blockForwardCopy,
                        onCheckedChange = { settingsViewModel.setBlockForwardCopy(it) }
                    )
                    // Oblivious HTTP (network/ohttp). Greyed out until a relay + pinned
                    // gateway key ship in the build; when ON, a relay failure fails the
                    // call — it never silently goes direct.
                    SettingsToggleItem(
                        icon = Icons.Default.Lock,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.settings_privacy_relay),
                        subtitle = stringResource(
                            if (settingsViewModel.privacyRelayAvailable) R.string.settings_privacy_relay_desc
                            else R.string.settings_privacy_relay_not_set
                        ),
                        checked = privacyRelayEnabled && settingsViewModel.privacyRelayAvailable,
                        enabled = settingsViewModel.privacyRelayAvailable,
                        onCheckedChange = { settingsViewModel.setPrivacyRelayEnabled(it) }
                    )
                    // __OHTTP_RELAY_ADDRESS_2026_11_10__ where the relay is. Any OHTTP
                    // relay works: content is sealed to OSHI's pinned gateway key.
                    val relayUrl by settingsViewModel.privacyRelayUrl.collectAsState()
                    var showRelayDialog by remember { mutableStateOf(false) }
                    SettingsItem(
                        icon = Icons.Default.Lock,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.settings_privacy_relay_address),
                        subtitle = relayUrl.ifBlank { stringResource(R.string.settings_privacy_relay_not_set) },
                        onClick = { showRelayDialog = true }
                    )
                    if (showRelayDialog) {
                        var draft by remember { mutableStateOf(relayUrl) }
                        var invalid by remember { mutableStateOf(false) }
                        OshiAlertDialog(
                            onDismissRequest = { showRelayDialog = false },
                            title = { Text(stringResource(R.string.settings_privacy_relay_address)) },
                            text = {
                                Column {
                                    Text(stringResource(R.string.settings_privacy_relay_address_hint))
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = draft,
                                        onValueChange = { draft = it; invalid = false },
                                        singleLine = true,
                                        isError = invalid,
                                        placeholder = { Text("https://") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (invalid) {
                                        Text(
                                            stringResource(R.string.settings_privacy_relay_address_invalid),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (settingsViewModel.setPrivacyRelayUrl(draft)) showRelayDialog = false
                                    else invalid = true
                                }) { Text(stringResource(R.string.save)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRelayDialog = false }) { Text(stringResource(R.string.cancel)) }
                            }
                        )
                    }
                    SettingsItem(
                        icon = Icons.Default.Block,
                        iconTint = OSHIRed,
                        title = stringResource(R.string.settings_blocked_contacts),
                        subtitle = stringResource(R.string.settings_manage_blocked),
                        onClick = onBlockedContactsClick
                    )
                    // Directly under Blocked Contacts, where iOS puts it
                    // (SettingsView.swift:1010-1017). "Report" and "block" are
                    // graded as a pair at store review, and Android shipped
                    // only half of it.
                    SettingsItem(
                        icon = Icons.Default.Flag,
                        iconTint = OSHIOrange,
                        title = stringResource(R.string.settings_reports),
                        subtitle = stringResource(R.string.settings_reports_desc),
                        onClick = onReportedItemsClick
                    )
                }
            }

            // =========================================================================
            // __KEYBOARD_PRIVACY_2026_10_01__ KEYBOARD (privacy)
            // What is typed passes through the keyboard before OSHI encrypts it:
            // incognito toggle, which keyboard is active, and an honest explainer of
            // what an app can and cannot protect.
            // =========================================================================
            if (openCategory == SettingsCategory.PRIVACY) item {
                val keyboardContext = LocalContext.current
                val keyboardStore = remember { com.oshi.messenger.ui.keyboard.KeyboardPrivacyStore.get(keyboardContext) }
                val incognitoKeyboard by keyboardStore.incognito.collectAsState()
                val currentKeyboard by com.oshi.messenger.ui.keyboard.rememberCurrentKeyboard()
                SettingsSection(title = stringResource(R.string.keyboard_privacy_section_title)) {
                    SettingsToggleItem(
                        icon = Icons.Default.Keyboard,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.keyboard_incognito_title),
                        subtitle = stringResource(R.string.keyboard_incognito_subtitle),
                        checked = incognitoKeyboard,
                        onCheckedChange = { keyboardStore.setIncognito(it) }
                    )
                    val kb = currentKeyboard
                    SettingsItem(
                        icon = if (kb == null || kb.isTrusted) Icons.Default.VerifiedUser else Icons.Default.Warning,
                        iconTint = if (kb == null || kb.isTrusted) OSHIGreen else OSHIOrange,
                        title = stringResource(R.string.keyboard_status_title),
                        subtitle = com.oshi.messenger.ui.keyboard.keyboardStatusText(kb),
                        onClick = { com.oshi.messenger.ui.keyboard.KeyboardInspector.showPicker(keyboardContext) }
                    )
                    // __DEVICE_INTEGRITY_2026_10_01__ Red row only when the heuristics
                    // found something; no green "phone is safe" row, because passing
                    // these checks proves nothing (well-hidden root passes them).
                    val integrityVerdict by com.oshi.messenger.security.DeviceIntegrity.verdict.collectAsState()
                    LaunchedEffect(Unit) { com.oshi.messenger.security.DeviceIntegrity.check(keyboardContext) }
                    val integrity = integrityVerdict
                    if (integrity != null && integrity.isFlagged) {
                        SettingsItem(
                            icon = Icons.Default.Warning,
                            iconTint = Color(0xFFEF4444),
                            title = stringResource(R.string.device_integrity_status_title),
                            subtitle = stringResource(
                                R.string.device_integrity_status_flagged,
                                com.oshi.messenger.security.integrityFindingsText(integrity.findings)
                            ),
                            onClick = {}
                        )
                    }
                    Text(
                        text = stringResource(R.string.keyboard_privacy_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
                    )
                    // __CLIPBOARD_HYGIENE_2026_10_01__ + __DEVICE_INTEGRITY_2026_10_01__
                    // what copying does now, and what the root check can and cannot see.
                    Text(
                        text = stringResource(R.string.clipboard_hygiene_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                    Text(
                        text = stringResource(R.string.device_integrity_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }

            // =========================================================================
            // NOTIFICATIONS SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.NOTIFICATIONS) item {
                SettingsSection(title = stringResource(R.string.settings_notifications)) {
                    SettingsToggleItem(
                        icon = Icons.Default.Notifications,
                        iconTint = OSHIOrange,
                        title = stringResource(R.string.settings_notifications),
                        subtitle = stringResource(R.string.settings_enable_push),
                        checked = notificationsEnabled,
                        onCheckedChange = { settingsViewModel.setNotificationsEnabled(it) }
                    )

                    AnimatedVisibility(
                        visible = notificationsEnabled,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column {
                            // Sound + vibration are owned by the Android
                            // NotificationChannel from API 26 up, and channels are
                            // IMMUTABLE once created: `.setSound()` on the builder is
                            // ignored and CHANNEL_MESSAGES' `enableVibration(true)`
                            // cannot be revoked from an app-side boolean. The two
                            // switches that used to sit here therefore did nothing at
                            // all. Send the user to the place that can actually change
                            // it instead of shipping a switch that lies.
                            SettingsItem(
                                icon = Icons.Default.VolumeUp,
                                iconTint = Color(0xFF3B82F6),
                                title = stringResource(R.string.settings_notification_channel_settings),
                                subtitle = stringResource(R.string.settings_notification_channel_settings_desc),
                                onClick = {
                                    val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .putExtra(
                                            android.provider.Settings.EXTRA_CHANNEL_ID,
                                            com.oshi.messenger.service.FCMService.CHANNEL_MESSAGES
                                        )
                                    runCatching { context.startActivity(intent) }
                                }
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Visibility,
                                iconTint = OSHIGreen,
                                title = stringResource(R.string.settings_message_preview),
                                subtitle = stringResource(R.string.settings_message_preview_desc),
                                checked = notificationPreview,
                                onCheckedChange = { settingsViewModel.setNotificationPreview(it) }
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // CALLS SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.CALLS) item {
                SettingsSection(title = stringResource(R.string.settings_calls)) {
                    // Default Voice Effect
                    SettingsItem(
                        icon = Icons.Default.RecordVoiceOver,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_default_voice_effect),
                        subtitle = defaultVoiceEffect,
                        onClick = { showVoiceEffectPicker = true }
                    )
                    // Call Quality
                    SettingsItem(
                        icon = Icons.Default.Tune,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.settings_call_quality),
                        subtitle = callQuality,
                        onClick = { showCallQualityPicker = true }
                    )
                    // Contacts-only calls
                    SettingsToggleItem(
                        icon = Icons.Default.PersonOff,
                        iconTint = OSHIOrange,
                        title = stringResource(R.string.settings_contacts_only_calls),
                        subtitle = stringResource(R.string.settings_contacts_only_calls_desc),
                        checked = contactsOnlyCalls,
                        onCheckedChange = { settingsViewModel.setContactsOnlyCalls(it) }
                    )
                    // 🧭 Always use relay server (P2P force-relay) — iOS parity "oshi.call.forceRelay"
                    SettingsToggleItem(
                        icon = Icons.Default.Shield,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_always_use_relay),
                        subtitle = stringResource(R.string.settings_always_use_relay_desc),
                        checked = forceRelayMode,
                        onCheckedChange = { settingsViewModel.setForceRelayMode(it) }
                    )
                    // __CALL_LOG_AT_REST_2026_09_23__ iOS "Share call diagnostics": a
                    // REDACTED copy of the sealed call log (call-path lines only — the raw
                    // log must never leave the phone). Diagnosing one side of a call
                    // needs the OTHER side's log; this is how to get it.
                    if (remember { com.oshi.messenger.service.diag.CallDiagnosticsShare.available() }) {
                        val diagScope = rememberCoroutineScope()
                        SettingsItem(
                            icon = Icons.Default.Share,
                            iconTint = OSHIPurple,
                            title = stringResource(R.string.settings_calls_diaglog_title),
                            subtitle = stringResource(R.string.settings_calls_diaglog_subtitle),
                            onClick = {
                                diagScope.launch {
                                    val ok = com.oshi.messenger.service.diag.CallDiagnosticsShare.share(
                                        context, context.getString(R.string.settings_calls_diaglog_title)
                                    )
                                    if (!ok) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.export_share_no_app),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // =========================================================================
            // CALL PRIVACY SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.CALLS) item {
                SettingsSection(title = stringResource(R.string.settings_call_privacy)) {
                    // Always Relay (recommended)
                    //
                    // `selectable` on the row + `onClick = null` on the button
                    // is the Material pattern: it leaves ONE node instead of a
                    // labelled row wrapping a nameless 48 x 48 dp radio, and it
                    // gives that node Role.RadioButton so a screen reader says
                    // "selected" rather than "button".
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = callPrivacyMode == "relay",
                                role = Role.RadioButton,
                                onClick = { settingsViewModel.setCallPrivacyMode("relay") }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = callPrivacyMode == "relay",
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = OSHIPurple)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_call_privacy_relay),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.settings_call_privacy_relay_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Allow Direct Calls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = callPrivacyMode == "direct",
                                role = Role.RadioButton,
                                onClick = { settingsViewModel.setCallPrivacyMode("direct") }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = callPrivacyMode == "direct",
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = OSHIPurple)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_call_privacy_direct),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.settings_call_privacy_direct_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // APPEARANCE SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.APPEARANCE) item {
                SettingsSection(title = stringResource(R.string.settings_appearance)) {
                    ThemeSelector(
                        currentTheme = theme,
                        oledBlack = oledBlack,
                        onThemeSelected = { settingsViewModel.setTheme(it) }
                    )

                    // __OLED_BLACK_2026_11_16__ « Noir OLED » — a modifier of the
                    // dark theme, sitting right under the theme picker. Always
                    // switchable (a System user on a light afternoon can arm it for
                    // the evening); the subtitle says it acts only when dark is on.
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsToggleItem(
                        icon = Icons.Default.Contrast,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_theme_oled_black),
                        subtitle = stringResource(R.string.settings_theme_oled_black_desc),
                        checked = oledBlack,
                        // The "only when dark is on" half is the rule; do not ellipsize it.
                        subtitleMaxLines = 3,
                        onCheckedChange = { settingsViewModel.setOledBlack(it) }
                    )

                    // __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes »:
                    // one chat list with All / Unread / Groups / People chips instead
                    // of the Messages and Groups tabs. Per device; applies at once.
                    val appContext = androidx.compose.ui.platform.LocalContext.current
                    val mergedChats by remember(appContext) {
                        com.oshi.messenger.data.model.MergedChatsSetting.state(appContext)
                    }.collectAsState()
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsToggleItem(
                        icon = Icons.Default.Forum,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.chats_merged_setting_title),
                        subtitle = stringResource(R.string.chats_merged_setting_desc),
                        checked = mergedChats,
                        titleMaxLines = 2,
                        subtitleMaxLines = 3,
                        onCheckedChange = { com.oshi.messenger.data.model.MergedChatsSetting.set(appContext, it) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FontSizeSlider(
                        fontSize = fontSize,
                        onFontSizeChange = { settingsViewModel.setFontSize(it) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    BubbleStyleSelector(
                        currentStyle = bubbleStyle,
                        onStyleSelected = { settingsViewModel.setBubbleStyle(it) }
                    )

                    // Accent / silhouette / bold text (F-13). iOS keeps this picker in
                    // Settings (`BubbleStyleSelector`, SettingsView); on Android it landed
                    // in the chat overflow menu only because `SettingsViewModel` was
                    // another team's file that pass. The sheet is self-contained — it owns
                    // its own `BubbleAppearancePrefs` — so one entry point moves it here
                    // without touching the ViewModel at all.
                    SettingsItem(
                        icon = Icons.Default.Palette,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_bubble_appearance),
                        subtitle = stringResource(R.string.settings_bubble_appearance_desc),
                        onClick = { showBubbleAppearanceSheet = true }
                    )

                    // __WALLPAPER_TONE_2026_09_24__ Chat wallpaper tone (light /
                    // dark / automatic), independent of the theme above.
                    SettingsItem(
                        icon = Icons.Default.Wallpaper,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.wallpaper_chat_wallpaper),
                        subtitle = stringResource(
                            when (WallpaperTonePrefs.defaultTone(LocalContext.current)) {
                                WallpaperTone.AUTOMATIC -> R.string.wallpaper_tone_automatic
                                WallpaperTone.LIGHT -> R.string.wallpaper_tone_light
                                WallpaperTone.DARK -> R.string.wallpaper_tone_dark
                            }
                        ),
                        onClick = { showWallpaperToneSheet = true }
                    )

                    // __O_BUBBLE_MASCOT_2026_11_16__ Reactions drawn as the
                    // animated O-bubble mascot (default) or as system emoji.
                    // Display only: the emoji sent is the same either way.
                    val animatedReactions = com.oshi.messenger.ui.components.OshiMascotPrefs
                        .animatedReactions(context)
                    SettingsToggleItem(
                        icon = Icons.Default.EmojiEmotions,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_animated_reactions),
                        subtitle = stringResource(R.string.settings_animated_reactions_desc),
                        checked = animatedReactions,
                        onCheckedChange = {
                            com.oshi.messenger.ui.components.OshiMascotPrefs
                                .setAnimatedReactions(context, it)
                        }
                    )
                }
            }

            // =========================================================================
            // LINKED DEVICES SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.DEVICES) item {
                SettingsSection(title = stringResource(R.string.settings_linked_devices)) {
                    SettingsItem(
                        icon = Icons.Default.Computer,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_oshi_web),
                        subtitle = stringResource(R.string.settings_link_browser),
                        onClick = onWebSessionClick
                    )

                    // __WEAR_BRIDGE_2026_11_17__ Smartwatch (Wear OS). Opt-in: the
                    // watch is a remote view; keys, crypto and sending stay here.
                    val smartwatchEnabled by settingsViewModel.smartwatchEnabled.collectAsState()
                    SettingsToggleItem(
                        icon = Icons.Default.Watch,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_smartwatch_title),
                        subtitle = stringResource(R.string.settings_smartwatch_subtitle),
                        checked = smartwatchEnabled,
                        subtitleMaxLines = 3,
                        onCheckedChange = { settingsViewModel.setSmartwatchEnabled(it) }
                    )

                    // __DEVSYNC_DIRECT_2026_09_22__ Direct device-to-device history sync
                    // (docs/OSHI_DEVICE_SYNC_DIRECT.md): list, approve with the 6-digit code, remove.
                    // Release builds show the row only once the server gate is on (or a local
                    // override is already set): with `devsync_enabled` off it leads to a screen
                    // that can only say "Direct sync is off".
                    val devSync by hiltViewModel<LinkedDevicesViewModel>().state.collectAsState()
                    if (BuildConfig.DEBUG || devSync.serverEnabled || devSync.localOverride != null) {
                    SettingsItem(
                        icon = Icons.Default.Devices,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.devsync_title),
                        subtitle = stringResource(R.string.devsync_settings_subtitle),
                        onClick = onLinkedDevicesClick
                    )
                    }

                    // Multi-device sync — iOS MultiDeviceSyncSettingsView.
                    // There is deliberately NO "registered devices" list: the
                    // /api/sync/devices, /register and /pull routes iOS calls
                    // return 404 on the live server (verified 2026-08-13), so the
                    // list could only ever be empty. What does exist is the
                    // encrypted per-identity archive, which is what this toggle
                    // drives.
                    SettingsToggleItem(
                        icon = Icons.Default.Sync,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.multi_device_title),
                        subtitle = if (syncEnabled) {
                            stringResource(R.string.multi_device_enabled)
                        } else {
                            stringResource(R.string.multi_device_disabled)
                        },
                        checked = syncEnabled,
                        onCheckedChange = { settingsViewModel.setSyncEnabled(it) }
                    )
                    if (syncEnabled) {
                        val statusLabel = when (syncStatus) {
                            is com.oshi.messenger.service.SyncStatus.Syncing ->
                                stringResource(R.string.multi_device_status_syncing)
                            is com.oshi.messenger.service.SyncStatus.Success ->
                                stringResource(R.string.multi_device_status_synced)
                            is com.oshi.messenger.service.SyncStatus.Error ->
                                stringResource(R.string.multi_device_status_error)
                            else -> stringResource(R.string.multi_device_status_idle)
                        }
                        val lastSyncLabel = stringResource(
                            R.string.multi_device_last_sync,
                            if (lastSyncTime > 0L) {
                                settingsViewModel.formatCreationDate(lastSyncTime)
                            } else {
                                stringResource(R.string.multi_device_never)
                            }
                        )
                        SettingsItem(
                            icon = Icons.Default.CloudSync,
                            iconTint = Color(0xFF3B82F6),
                            title = stringResource(R.string.multi_device_sync_now),
                            subtitle = stringResource(R.string.settings_status_dot_separator, statusLabel, lastSyncLabel),
                            onClick = { settingsViewModel.syncNow() }
                        )
                    }
                }
            }

            // =========================================================================
            // LONG-RANGE RADIO (MESHTASTIC LoRa) SECTION
            //
            // Placement matches iOS, which puts "settings.section.longRange"
            // immediately after Web Sessions and before the storage rows
            // (`SettingsView.swift:91-105`). Android's Linked Devices section is
            // its Web Sessions equivalent, so this sits directly after it.
            //
            // Two rows because iOS has two: the radio itself, and the node setup
            // walkthrough. Both go through `loraSettings` here — the settings
            // screen owns the "Set up a LoRa node" affordance and pushes
            // `loraNodeSetup` itself — so a user who has never seen a LoRa node
            // still lands somewhere that explains what one is.
            // =========================================================================
            if (openCategory == SettingsCategory.NETWORK) item {
                SettingsSection(title = stringResource(R.string.meshtastic_title)) {
                    SettingsItem(
                        icon = Icons.Default.SettingsInputAntenna,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.meshtastic_title),
                        subtitle = stringResource(R.string.meshtastic_footer),
                        onClick = onLoRaSettingsClick
                    )
                    SettingsItem(
                        icon = Icons.Default.Router,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.lora_setup_open),
                        subtitle = stringResource(R.string.lora_setup_open_subtitle),
                        onClick = onLoRaSettingsClick
                    )
                }
            }

            // =========================================================================
            // DATA MANAGEMENT SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.DATA) item {
                SettingsSection(title = stringResource(R.string.settings_data_management)) {
                    SettingsItem(
                        icon = Icons.Default.Upload,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.settings_export_messages),
                        subtitle = if (isExporting) stringResource(R.string.settings_exporting) else stringResource(R.string.settings_export_encrypted_desc),
                        enabled = !isExporting,
                        onClick = { settingsViewModel.exportMessages() }
                    )
                    SettingsItem(
                        icon = Icons.Default.Download,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.settings_import_messages),
                        subtitle = if (isImporting) stringResource(R.string.settings_importing) else stringResource(R.string.settings_import_messages_desc),
                        enabled = !isImporting,
                        onClick = { importPickerLauncher.launch(arrayOf("*/*")) }
                    )
                    SettingsItem(
                        icon = Icons.Default.Refresh,
                        iconTint = OSHIOrange,
                        title = stringResource(R.string.settings_clear_sessions),
                        subtitle = stringResource(R.string.settings_clear_sessions_desc),
                        onClick = { showClearSessionsDialog = true }
                    )
                    SettingsItem(
                        icon = Icons.Default.Delete,
                        iconTint = OSHIRed,
                        title = stringResource(R.string.settings_clear_all_messages),
                        subtitle = stringResource(R.string.settings_delete_history),
                        isDestructive = true,
                        onClick = { showClearMessagesDialog = true }
                    )
                }
            }

            // =========================================================================
            // LOCATION SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.DATA) item {
                SettingsSection(title = stringResource(R.string.map_open_preference)) {
                    val mapSubtitle = when (mapOpenPreference) {
                        "google" -> stringResource(R.string.map_option_google)
                        "waze" -> stringResource(R.string.map_option_waze)
                        else -> stringResource(R.string.map_option_oshi)
                    }
                    SettingsItem(
                        icon = Icons.Default.Map,
                        iconTint = OSHIGreen,
                        title = stringResource(R.string.map_open_preference),
                        subtitle = mapSubtitle,
                        onClick = { showMapPreferencePicker = true }
                    )
                    // Says WHAT the choice applies to: opening a place (shared
                    // location, check-in, address in a chat) — not the Map tab,
                    // which is always OSHI's own map. See MapOpener.
                    Text(
                        text = stringResource(R.string.map_open_preference_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    )
                    SettingsToggleItem(
                        icon = Icons.Default.CloudOff,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_routing_offline_title),
                        subtitle = stringResource(R.string.settings_routing_offline_subtitle),
                        checked = routingOfflineOnly,
                        onCheckedChange = { settingsViewModel.setRoutingOfflineOnly(it) }
                    )
                }
            }

            // =========================================================================
            // ADVANCED SECTION
            // =========================================================================
            if (openCategory == SettingsCategory.ADVANCED) item {
                SettingsSection(title = stringResource(R.string.settings_advanced)) {
                    SettingsItem(
                        icon = Icons.Default.SmartToy,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.bot_api_title),
                        subtitle = stringResource(R.string.settings_bot_management_desc),
                        // iOS opens the NATIVE BotManagementView here
                        // (SettingsView.swift:87). This row used to launch a
                        // browser at oshi-messenger.com/bots while the app's own
                        // three bot screens sat unreachable from Settings.
                        onClick = onPortalClick
                    )
                }
            }

            // =========================================================================
            // FEATURES SECTION (Nearby & Portal) — lives under NETWORK
            // =========================================================================
            if (openCategory == SettingsCategory.NETWORK) item {
                // Nearby & Portal
                SettingsSection(title = stringResource(R.string.settings_features)) {
                    // (N22) The only way to make MeshNetworkService's always-on
                    // "Réseau mesh Oshi actif" notification go away is to turn
                    // the mesh itself off — Android requires that notification
                    // for as long as the foreground service it belongs to is
                    // running, so there is no "hide the notification but keep
                    // the mesh" option to offer.
                    var meshEnabled by remember { mutableStateOf(mainViewModel.isMeshEnabled) }
                    SettingsToggleItem(
                        icon = Icons.Default.WifiTethering,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_mesh_networking),
                        subtitle = stringResource(R.string.settings_mesh_networking_toggle_subtitle),
                        checked = meshEnabled,
                        onCheckedChange = {
                            meshEnabled = it
                            mainViewModel.setMeshEnabled(it)
                        }
                    )
                    // __BACKGROUND_PRESENCE_2026_11_10__ Off by default: Android shows
                    // a status-bar icon for as long as OSHI runs in the background.
                    // A phone without Google push keeps running regardless (it has
                    // no other way to receive), so the switch shows on and locked.
                    val hasPush = remember { mainViewModel.hasGooglePush }
                    var keepRunning by remember { mutableStateOf(mainViewModel.keepRunningInBackground) }
                    SettingsToggleItem(
                        icon = Icons.Default.WifiTethering,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.settings_keep_running_background),
                        subtitle = stringResource(
                            if (hasPush) R.string.settings_keep_running_background_desc
                            else R.string.settings_keep_running_background_no_push
                        ),
                        checked = keepRunning || !hasPush,
                        enabled = hasPush,
                        onCheckedChange = {
                            keepRunning = it
                            mainViewModel.setKeepRunningInBackground(it)
                        }
                    )
                    SettingsItem(
                        icon = Icons.Default.WifiTethering,
                        title = stringResource(R.string.peers_nearby),
                        subtitle = if (peersCount > 0) stringResource(R.string.settings_peers_connected, peersCount) else stringResource(R.string.settings_mesh_networking),
                        onClick = onNearbyClick,
                        iconTint = OSHIPurple
                    )
                    SettingsItem(
                        icon = Icons.Default.Apps,
                        title = stringResource(R.string.settings_portal),
                        subtitle = stringResource(R.string.settings_portal_desc),
                        onClick = onPortalClick,
                        iconTint = OSHIPurple
                    )
                }
            }

            if (openCategory == SettingsCategory.ABOUT) item {
                SettingsSection(title = stringResource(R.string.settings_about)) {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        iconTint = Color.Gray,
                        title = stringResource(R.string.settings_version_label),
                        subtitle = BuildConfig.VERSION_NAME
                    )
                    // __IN_APP_GUIDES_2026_11_02__ Above the website link, and
                    // deliberately NOT replacing it: a guide that works offline
                    // belongs in an app whose whole argument is working offline,
                    // and the two questions people actually ask have answers
                    // short enough to read on the screen they are stuck on.
                    SettingsItem(
                        icon = Icons.Default.Help,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.guides_title),
                        subtitle = stringResource(R.string.guide_add_contact_sub),
                        onClick = { showGuides = true }
                    )
                    SettingsItem(
                        icon = Icons.Default.Public,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.settings_help_faq),
                        subtitle = stringResource(R.string.settings_help_faq_desc),
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://oshi-messenger.com"))
                            context.startActivity(intent)
                        }
                    )
                    SettingsItem(
                        icon = Icons.Default.Star,
                        iconTint = Color(0xFFFFD700),
                        title = stringResource(R.string.settings_rate_oshi),
                        subtitle = "",
                        onClick = {
                            com.oshi.messenger.util.AppReviewManager.openPlayStoreReview(context)
                        }
                    )
                    // Legal links. iOS ships both under an explicit LEGAL section
                    // (SettingsView.swift:1096-1107); Android had neither, and
                    // `grep -rni "privacy_policy|terms"` over res/values and this
                    // file returned zero hits. Play expects an in-app privacy
                    // policy link for a messaging app, and it is a GDPR
                    // transparency gap besides.
                    SettingsItem(
                        icon = Icons.Default.PrivacyTip,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.settings_privacy_policy),
                        subtitle = stringResource(R.string.settings_legal),
                        onClick = {
                            openUrl(context, "https://oshi-messenger.com/privacy")
                        }
                    )
                    SettingsItem(
                        icon = Icons.Default.Gavel,
                        iconTint = Color.Gray,
                        title = stringResource(R.string.settings_terms_of_service),
                        subtitle = stringResource(R.string.settings_legal),
                        onClick = {
                            openUrl(context, "https://oshi-messenger.com/terms")
                        }
                    )
                }
            }

            // =========================================================================
            // VERSION INFO
            // =========================================================================
            if (openCategory == null) item {
                VersionInfoCard()
            }

            // =========================================================================
            // DISCONNECT BUTTON
            // =========================================================================
            if (openCategory == null) item {
                DisconnectButton(
                    onClick = { showDisconnectDialog = true }
                )
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // =========================================================================
    // DIALOGS
    // =========================================================================

    // Bubble appearance (accent / silhouette / bold text). The sheet persists to its own
    // `BubbleAppearancePrefs`, so nothing has to be collected or written back here.
    if (showBubbleAppearanceSheet) {
        BubbleAppearanceSheet(
            onDismiss = { showBubbleAppearanceSheet = false },
            onChanged = { }
        )
    }

    if (showWallpaperToneSheet) {
        ChatWallpaperToneSheet(onDismiss = { showWallpaperToneSheet = false })
    }

    // Voice Effect Picker Dialog
    if (showVoiceEffectPicker) {
        val effects = listOf("None", "Robot", "Alien", "Deep", "Chipmunk", "Anonymous", "Echo", "Reverb")
        OshiAlertDialog(
            onDismissRequest = { showVoiceEffectPicker = false },
            title = { Text(stringResource(R.string.settings_default_voice_effect)) },
            text = {
                Column {
                    effects.forEach { effect ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setDefaultVoiceEffect(effect)
                                    showVoiceEffectPicker = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultVoiceEffect == effect,
                                onClick = {
                                    settingsViewModel.setDefaultVoiceEffect(effect)
                                    showVoiceEffectPicker = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = OSHIPurple
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = effect)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoiceEffectPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Call Quality Picker Dialog
    if (showCallQualityPicker) {
        val qualities = listOf(
            "Auto" to stringResource(R.string.settings_call_quality_auto),
            "Low" to stringResource(R.string.settings_call_quality_low),
            "High" to stringResource(R.string.settings_call_quality_high)
        )
        OshiAlertDialog(
            onDismissRequest = { showCallQualityPicker = false },
            title = { Text(stringResource(R.string.settings_call_quality)) },
            text = {
                Column {
                    qualities.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setCallQuality(value)
                                    showCallQualityPicker = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = callQuality == value,
                                onClick = {
                                    settingsViewModel.setCallQuality(value)
                                    showCallQualityPicker = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = OSHIPurple
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCallQualityPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Auto-Lock Timeout Picker Dialog
    if (showAutoLockPicker) {
        val options = listOf(
            0 to stringResource(R.string.settings_auto_lock_disabled),
            30 to stringResource(R.string.settings_auto_lock_30s),
            60 to stringResource(R.string.settings_auto_lock_1m),
            300 to stringResource(R.string.settings_auto_lock_5m),
            900 to stringResource(R.string.settings_auto_lock_15m)
        )
        OshiAlertDialog(
            onDismissRequest = { showAutoLockPicker = false },
            title = { Text(stringResource(R.string.settings_auto_lock)) },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setAutoLockTimeoutSeconds(value)
                                    showAutoLockPicker = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoLockTimeoutSeconds == value,
                                onClick = {
                                    settingsViewModel.setAutoLockTimeoutSeconds(value)
                                    showAutoLockPicker = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = OSHIPurple)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoLockPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Map Preference Picker Dialog
    if (showMapPreferencePicker) {
        val mapOptions = listOf(
            "oshi" to stringResource(R.string.map_option_oshi),
            "google" to stringResource(R.string.map_option_google),
            "waze" to stringResource(R.string.map_option_waze)
        )
        OshiAlertDialog(
            onDismissRequest = { showMapPreferencePicker = false },
            title = { Text(stringResource(R.string.map_open_preference)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.map_open_preference_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    mapOptions.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setMapOpenPreference(value)
                                    showMapPreferencePicker = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = mapOpenPreference == value,
                                onClick = {
                                    settingsViewModel.setMapOpenPreference(value)
                                    showMapPreferencePicker = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = OSHIPurple
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMapPreferencePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Edit Name Dialog
    if (showEditNameDialog) {
        OshiAlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text(stringResource(R.string.settings_edit_name)) },
            text = {
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text(stringResource(R.string.settings_display_name)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.updateDisplayName(editedName)
                        showEditNameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Clear Sessions Dialog
    if (showClearSessionsDialog) {
        OshiAlertDialog(
            onDismissRequest = { showClearSessionsDialog = false },
            title = { Text(stringResource(R.string.settings_clear_sessions)) },
            text = { Text(stringResource(R.string.settings_clear_sessions_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.clearSessions()
                        showClearSessionsDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_clear), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Clear Messages Dialog
    if (showClearMessagesDialog) {
        OshiAlertDialog(
            onDismissRequest = { showClearMessagesDialog = false },
            title = { Text(stringResource(R.string.settings_clear_all_messages)) },
            text = { Text(stringResource(R.string.settings_clear_messages_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.clearAllMessages()
                        showClearMessagesDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_delete_all), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMessagesDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Disconnect Dialog
    if (showDisconnectDialog) {
        OshiAlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.settings_disconnect)) },
            text = { Text(stringResource(R.string.settings_disconnect_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.disconnect()
                        showDisconnectDialog = false
                        onLogout()
                    }
                ) {
                    Text(stringResource(R.string.settings_disconnect), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete identity confirmation — wipes encrypted prefs, the auto-backup blob,
    // and DataStore so the user lands back in onboarding with no recoverable
    // identity. Mirrors iOS "Delete identity" in IdentityVaultManager.
    if (showDeleteIdentityDialog) {
        val confirmWord = stringResource(R.string.account_delete_confirm_word)
        val deleting = accountDeletion is AccountDeletionState.Deleting
        val done = accountDeletion as? AccountDeletionState.Done
        val failed = accountDeletion as? AccountDeletionState.Failed

        OshiAlertDialog(
            // A deletion in flight must not be dismissable — iOS uses
            // `.interactiveDismissDisabled(phase == .deleting)` (swift:125).
            onDismissRequest = { if (!deleting) showDeleteIdentityDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = OSHIRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.account_delete_title))
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        // ---- receipt: counts, not adjectives (swift:143-171) ----
                        done != null -> {
                            val r = done.receipt
                            Text(
                                text = if (r.complete) stringResource(R.string.account_delete_done_complete)
                                else stringResource(R.string.account_delete_done_partial),
                                fontWeight = FontWeight.SemiBold,
                                color = if (r.complete) OSHIGreen else OSHIOrange
                            )
                            Spacer(Modifier.height(8.dp))
                            ReceiptLine(stringResource(R.string.account_delete_receipt_keys), if (r.prekeysErased) "1" else "0")
                            ReceiptLine(stringResource(R.string.account_delete_receipt_sync), "${r.syncItems}")
                            ReceiptLine(stringResource(R.string.account_delete_receipt_queued), "${r.relayEnvelopes}")
                            ReceiptLine(stringResource(R.string.account_delete_receipt_media), "${r.inboundBlobs}")
                            if (r.outboundBlobsLeft > 0) {
                                ReceiptLine(stringResource(R.string.account_delete_receipt_outbound), "${r.outboundBlobsLeft}")
                            }
                            if (r.unreachable.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.account_delete_receipt_unreachable, r.unreachable.joinToString(", ")),
                                    fontSize = 12.sp,
                                    color = OSHIOrange
                                )
                            }
                        }

                        // ---- failure: nothing local was touched, retry is safe ----
                        failed != null -> {
                            Text(
                                stringResource(R.string.account_delete_failed_detail, failed.message),
                                fontSize = 13.sp,
                                color = OSHIOrange
                            )
                        }

                        else -> {
                            Text(stringResource(R.string.account_delete_headline), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.account_delete_body), fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.account_delete_section_removed), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            listOf(
                                R.string.account_delete_item_keys,
                                R.string.account_delete_item_sync,
                                R.string.account_delete_item_queued,
                                R.string.account_delete_item_media,
                                R.string.account_delete_item_push,
                                R.string.account_delete_item_device
                            ).forEach { Text("•  " + stringResource(it), fontSize = 13.sp) }
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.account_delete_section_kept), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text("•  " + stringResource(R.string.account_delete_keeps_recipients), fontSize = 13.sp, color = OSHIOrange)
                            Text("•  " + stringResource(R.string.account_delete_keeps_reports), fontSize = 13.sp, color = OSHIOrange)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.account_delete_section_kept_footer), fontSize = 11.sp)
                            Spacer(Modifier.height(14.dp))
                            // Typed confirmation — a one-tap irreversible action is
                            // the wrong shape (swift:28-47, 100).
                            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                                OutlinedTextField(
                                    value = deleteConfirmText,
                                    onValueChange = { deleteConfirmText = it },
                                    singleLine = true,
                                    enabled = !deleting,
                                    label = { Text(confirmWord) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.account_delete_confirm_footer, confirmWord),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    done != null -> TextButton(onClick = {
                        showDeleteIdentityDialog = false
                        settingsViewModel.resetAccountDeletion()
                        onLogout()
                    }) { Text(stringResource(R.string.account_delete_done_close)) }

                    else -> TextButton(
                        enabled = !deleting &&
                            deleteConfirmText.trim().equals(confirmWord, ignoreCase = true),
                        onClick = {
                            settingsViewModel.deleteAccount()
                        }
                    ) {
                        Text(
                            if (deleting) stringResource(R.string.account_delete_in_progress)
                            else stringResource(R.string.account_delete_action),
                            color = OSHIRed
                        )
                    }
                }
            },
            dismissButton = {
                if (done == null) {
                    TextButton(
                        enabled = !deleting,
                        onClick = {
                            showDeleteIdentityDialog = false
                            deleteConfirmText = ""
                            settingsViewModel.resetAccountDeletion()
                        }
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    // Backup Keys Dialog (matches iOS BackupSeedView)
    if (showBackupKeysDialog) {
        OshiAlertDialog(
            onDismissRequest = { showBackupKeysDialog = false; showBackupKey = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = OSHIOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_backup_identity_key))
                }
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_backup_key_warning),
                        color = OSHIRed,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (showBackupKey) {
                        Text(
                            text = publicKey,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.clipboard_label_oshi_key), publicKey))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_copy_to_clipboard))
                        }
                    } else {
                        TextButton(onClick = { showBackupKey = true }) {
                            Icon(Icons.Default.Visibility, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_reveal_key))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupKeysDialog = false; showBackupKey = false }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }

    // Identity Details Dialog (matches iOS WalletInfoView)
    if (showIdentityDetailsDialog) {
        OshiAlertDialog(
            onDismissRequest = { showIdentityDetailsDialog = false },
            title = { Text(stringResource(R.string.settings_identity_details)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_identity_address), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = publicKey,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_key_created), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(settingsViewModel.formatCreationDate(keyCreationDate))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_encryption_label), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_encryption_type))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.clipboard_label_oshi_address), publicKey))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.settings_copy_address))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIdentityDetailsDialog = false }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }

    if (showRevealPicker) {
        RevealPickerFlow(onClose = { showRevealPicker = false })
    }

    // __PRIVACY_DISGUISE_2026_11_18__ "Privacy disguise" (audit 2026-11-18, findings 2 & 4).
    // The former dialog had an "Image Steganography" switch wired to nothing but a
    // send-path refusal, and promised "All methods use AES-256-GCM. Fully compatible
    // with iOS." for methods that did not exist. It now holds exactly two things, each
    // described by what it really does: the cover transport switch, and the
    // explicit, per-message "Disguise as photo" feature (docs/stego/STEGO-v1.md).
    if (showSteganographyInfoDialog) {
        val covertEnabled by settingsViewModel.covertChannelEnabled.collectAsState()

        OshiAlertDialog(
            onDismissRequest = { showSteganographyInfoDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = OSHIOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.privacy_disguise_title))
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.cover_switch_title), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = covertEnabled,
                            onCheckedChange = { settingsViewModel.setCovertChannelEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = OSHIPurple, checkedTrackColor = OSHIPurple.copy(alpha = 0.5f))
                        )
                    }
                    Text(stringResource(R.string.cover_switch_caption), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // __RESTRICTED_NETWORK_HINT_2026_11_18__ sender switch (default ON).
                    run {
                        val rnContext = androidx.compose.ui.platform.LocalContext.current
                        val rnOn by com.oshi.messenger.util.RestrictedNetworkHintSetting.state(rnContext).collectAsState()
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.rn_hint_setting_title), fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                modifier = Modifier.weight(1f))
                            Switch(
                                checked = rnOn ?: true,
                                onCheckedChange = { com.oshi.messenger.util.RestrictedNetworkHintSetting.set(rnContext, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = OSHIPurple, checkedTrackColor = OSHIPurple.copy(alpha = 0.5f))
                            )
                        }
                        Text(stringResource(R.string.rn_hint_setting_caption), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row {
                        Icon(Icons.Default.Info, contentDescription = null, tint = OSHIOrange, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.cover_not_hidden), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.disguise_photo_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.disguise_photo_explainer), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.disguise_warning_recompress), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.disguise_warning_forensic), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showSteganographyInfoDialog = false; showRevealPicker = true }) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.disguise_reveal_button))
                    }
                    Text(stringResource(R.string.disguise_reveal_hint), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showSteganographyInfoDialog = false }) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }
}

// =============================================================================
// PROFILE HEADER CARD
// =============================================================================

@Composable
private fun ProfileHeaderCard(
    displayName: String,
    publicKey: String,
    profileImageUri: Uri?,
    creationDate: String,
    onEditName: () -> Unit,
    onEditPhoto: () -> Unit
) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile Picture
            Box(
                modifier = Modifier.clickable { onEditPhoto() }
            ) {
                if (profileImageUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(profileImageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(R.string.settings_profile),
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(OSHIPurple, OSHIPurple.copy(alpha = 0.6f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = Color.White
                        )
                    }
                }

                // Edit badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(OSHIPurple),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.settings_edit_photo),
                        modifier = Modifier.size(12.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                // The name + pencil is a hand-rolled clickable, so nothing gave
                // it a height floor: on a 420 dpi phone it measured 92 x 39 dp,
                // under the 48 dp minimum. `defaultMinSize` sits AFTER the
                // `clickable` so the clickable node is the one that grows.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onEditName() }
                        .defaultMinSize(minHeight = Oshi.Touch.minTarget)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.settings_edit_name),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatPublicKey(publicKey),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    color = brandInk()
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.settings_created, creationDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// =============================================================================
// QUICK STATS CARD
// =============================================================================

@Composable
private fun QuickStatsCard(
    messageCount: Int,
    chatCount: Int,
    peersCount: Int
) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                icon = Icons.Default.Message,
                value = messageCount.toString(),
                label = stringResource(R.string.settings_stat_messages),
                color = brandInk()
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(50.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )

            StatItem(
                icon = Icons.Default.People,
                value = chatCount.toString(),
                label = stringResource(R.string.settings_stat_chats),
                color = OSHIGreen
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(50.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )

            StatItem(
                icon = Icons.Default.WifiTethering,
                value = peersCount.toString(),
                label = stringResource(R.string.settings_stat_peers),
                color = OSHIOrange
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = color
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// =============================================================================
// THEME SELECTOR
// =============================================================================

@Composable
private fun ThemeSelector(
    currentTheme: String,
    oledBlack: Boolean = false,
    onThemeSelected: (String) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()

    Column {
        Text(
            text = stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ThemePreviewCard(
                theme = "system",
                label = stringResource(R.string.settings_theme_system),
                icon = Icons.Default.Smartphone,
                isSelected = currentTheme == "system",
                isDarkPreview = isDarkTheme,
                oledPreview = oledBlack,
                modifier = Modifier.weight(1f),
                onClick = { onThemeSelected("system") }
            )

            ThemePreviewCard(
                theme = "light",
                label = stringResource(R.string.settings_theme_light),
                icon = Icons.Default.LightMode,
                isSelected = currentTheme == "light",
                isDarkPreview = false,
                modifier = Modifier.weight(1f),
                onClick = { onThemeSelected("light") }
            )

            ThemePreviewCard(
                theme = "dark",
                label = stringResource(R.string.settings_theme_dark),
                icon = Icons.Default.DarkMode,
                isSelected = currentTheme == "dark",
                isDarkPreview = true,
                oledPreview = oledBlack,
                modifier = Modifier.weight(1f),
                onClick = { onThemeSelected("dark") }
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    theme: String,
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    isDarkPreview: Boolean,
    oledPreview: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // __OLED_BLACK_2026_11_16__ the dark miniatures show the black ground when
    // OLED black is on, so the picker previews what the app will actually do.
    val backgroundColor = when {
        isDarkPreview && oledPreview -> Color.Black
        isDarkPreview -> Color(0xFF2A2A2A)
        else -> Color(0xFFF0F0F0)
    }
    val bubbleColor = when {
        isDarkPreview && oledPreview -> Oshi.Bubble.receivedOled
        isDarkPreview -> Color(0xFF404040)
        else -> Color(0xFFE0E0E0)
    }

    OshiCard(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, brandInk()) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) OSHIPurple.copy(alpha = Oshi.ContainerTintAlpha) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mini phone preview
            Box(
                modifier = Modifier
                    .size(width = 50.dp, height = 70.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(backgroundColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Received bubble
                    Box(
                        modifier = Modifier
                            .width(25.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bubbleColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Sent bubble
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .width(20.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(OSHIPurple)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) brandInk() else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) brandInk() else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

// =============================================================================
// FONT SIZE SLIDER
// =============================================================================

@Composable
private fun FontSizeSlider(
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.settings_font_size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.bodySmall
            )

            Slider(
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueRange = 0.8f..1.4f,
                steps = 5,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = OSHIPurple,
                    activeTrackColor = OSHIPurple
                )
            )

            Text(
                text = "A",
                style = MaterialTheme.typography.titleMedium
            )
        }

        // Preview
        OshiCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = OSHIPurple.copy(alpha = 0.1f)
            )
        ) {
            Text(
                text = stringResource(R.string.settings_preview_text, (fontSize * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (16 * fontSize).sp
                ),
                modifier = Modifier.padding(12.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

// =============================================================================
// BUBBLE STYLE SELECTOR
// =============================================================================

@Composable
private fun BubbleStyleSelector(
    currentStyle: String,
    onStyleSelected: (String) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.settings_bubble_style),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BubbleStyleCard(
                style = "Modern",
                label = stringResource(R.string.settings_bubble_style_modern),
                isSelected = currentStyle == "Modern",
                cornerRadius = 16.dp,
                modifier = Modifier.weight(1f),
                onClick = { onStyleSelected("Modern") }
            )

            BubbleStyleCard(
                style = "Classic",
                label = stringResource(R.string.settings_bubble_style_classic),
                isSelected = currentStyle == "Classic",
                cornerRadius = 8.dp,
                modifier = Modifier.weight(1f),
                onClick = { onStyleSelected("Classic") }
            )

            BubbleStyleCard(
                style = "Minimal",
                label = stringResource(R.string.settings_bubble_style_minimal),
                isSelected = currentStyle == "Minimal",
                cornerRadius = 4.dp,
                modifier = Modifier.weight(1f),
                onClick = { onStyleSelected("Minimal") }
            )
        }
    }
}

@Composable
private fun BubbleStyleCard(
    style: String,
    label: String,
    isSelected: Boolean,
    cornerRadius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Read off the scheme in force, not the phone: a user who forced Dark on a
    // light phone saw light miniatures here. OLED black previews its black ground.
    val isDarkTheme = isDarkScheme()
    val oled = com.oshi.messenger.ui.theme.isOledScheme()
    val backgroundColor = when {
        oled -> Color.Black
        isDarkTheme -> Color(0xFF2A2A2A)
        else -> Color(0xFFF0F0F0)
    }
    val bubbleColor = when {
        oled -> Oshi.Bubble.receivedOled
        isDarkTheme -> Color(0xFF404040)
        else -> Color(0xFFE0E0E0)
    }

    OshiCard(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, brandInk()) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) OSHIPurple.copy(alpha = Oshi.ContainerTintAlpha) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mini bubble preview
            Box(
                modifier = Modifier
                    .size(width = 50.dp, height = 50.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(backgroundColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Received bubble
                    Box(
                        modifier = Modifier
                            .width(25.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(cornerRadius / 4))
                            .background(bubbleColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Sent bubble
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .width(20.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(cornerRadius / 4))
                            .background(OSHIPurple)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) brandInk() else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

// =============================================================================
// VERSION INFO CARD
// =============================================================================

@Composable
private fun VersionInfoCard() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = brandInk()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "OSHI",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://oshi-messenger.com"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        ) {
            Text(
                text = "oshi-messenger.com",
                color = brandInk()
            )
        }
    }
}

// =============================================================================
// DISCONNECT BUTTON
// =============================================================================

@Composable
private fun DisconnectButton(
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OSHIRed.copy(alpha = 0.1f),
            contentColor = OSHIRed
        )
    ) {
        Icon(
            Icons.Default.Logout,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.settings_disconnect),
            fontWeight = FontWeight.Medium
        )
    }
}

// =============================================================================
// SETTINGS SECTION COMPONENT
// =============================================================================

/** One `label … value` line of the account-deletion receipt (iOS `line(_:_:)`, swift:173-179). */
@Composable
private fun ReceiptLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = brandInk(),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OshiCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(4.dp)
            ) {
                content()
            }
        }
    }
}

// =============================================================================
// SETTINGS ITEM COMPONENTS
// =============================================================================

@Composable
private fun SettingsItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint
                )
            }
        },
        headlineContent = {
            Text(
                text = title,
                color = if (isDestructive) OSHIRed else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitleMaxLines: Int = 1,
    /** __MERGED_CHATS_2026_11_18__ 1 everywhere but where a title is a sentence. */
    titleMaxLines: Int = 1,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp)),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint
                )
            }
        },
        headlineContent = {
            Text(
                text = title,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            // The row itself is NOT clickable here — the Switch is the only
            // clickable node, and `Modifier.clickable` does not merge its
            // descendants, so the title and subtitle stay on sibling nodes the
            // switch cannot borrow. On the device that is a 52 x 48 dp
            // clickable with no text and no content-desc: TalkBack announces
            // "on, switch" and never says WHAT is on. Naming it from `title`
            // is what makes the control identifiable.
            Switch(
                modifier = Modifier.semantics { contentDescription = title },
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = OSHIPurple
                )
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

// =============================================================================
// HELPER FUNCTIONS
// =============================================================================

private fun formatPublicKey(key: String): String {
    return if (key.length > 12) {
        "${key.take(8)}...${key.takeLast(6)}"
    } else {
        key
    }
}

@Composable
private fun autoLockLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.settings_auto_lock_disabled)
    30 -> stringResource(R.string.settings_auto_lock_30s)
    60 -> stringResource(R.string.settings_auto_lock_1m)
    300 -> stringResource(R.string.settings_auto_lock_5m)
    900 -> stringResource(R.string.settings_auto_lock_15m)
    else -> "${seconds}s"
}

/**
 * Open an external URL in the browser. `FLAG_ACTIVITY_NEW_TASK` because the
 * caller's context may not be an Activity, and a `try` because a device with no
 * browser must not crash Settings.
 */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

// =============================================================================
// SETTINGS CATEGORIES  __SETTINGS_CATEGORIES_2026_10_26__
// =============================================================================

/**
 * The ten pages Settings is cut into.
 *
 * Every title and subtitle here is a string resource THAT ALREADY SHIPPED, in
 * all 33 locales: the category names are the section headers the sections
 * already carried, and each subtitle is built from two row titles from inside
 * that category. That is deliberate — a category list is the first thing a
 * user reads in Settings, and inventing ten new strings would have shipped it
 * in English to everyone else until the next translation round.
 *
 * Order is the order the sections were already in, so anyone who knew where a
 * setting lived still finds it in the same place, one tap deeper.
 */
private enum class SettingsCategory(
    val titleRes: Int,
    val subtitleRes: Int,
    val secondSubtitleRes: Int?,
    val icon: ImageVector,
    val tint: Color,
) {
    ACCOUNT(R.string.settings_account, R.string.settings_my_qr_code, R.string.settings_profile_links, Icons.Default.Person, OSHIPurple),
    PRIVACY(R.string.settings_privacy, R.string.settings_security, R.string.keyboard_privacy_section_title, Icons.Default.Lock, OSHIGreen),
    NOTIFICATIONS(R.string.settings_notifications, R.string.settings_enable_push, null, Icons.Default.Notifications, OSHIRed),
    CALLS(R.string.settings_calls, R.string.settings_call_quality, R.string.settings_call_privacy, Icons.Default.Call, OSHIPurple),
    APPEARANCE(R.string.settings_appearance, R.string.settings_bubble_appearance, R.string.wallpaper_chat_wallpaper, Icons.Default.Palette, OSHIOrange),
    NETWORK(R.string.meshtastic_title, R.string.peers_nearby, R.string.lora_node_title, Icons.Default.Router, OSHIGreen),
    DEVICES(R.string.settings_linked_devices, R.string.settings_oshi_web, null, Icons.Default.Devices, OSHIPurple),
    DATA(R.string.settings_data_management, R.string.settings_export_messages, R.string.map_open_preference, Icons.Default.Storage, OSHIOrange),
    ADVANCED(R.string.settings_advanced, R.string.bot_api_title, null, Icons.Default.SmartToy, OSHIPurple),
    ABOUT(R.string.settings_about, R.string.settings_help_faq, R.string.settings_privacy_policy, Icons.Default.Info, OSHIPurple),
}

/** The category list. One card, ten rows, no setting in it — only doors. */
@Composable
private fun SettingsCategoryList(onOpen: (SettingsCategory) -> Unit) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            SettingsCategory.entries.forEach { category ->
                val first = stringResource(category.subtitleRes)
                SettingsItem(
                    icon = category.icon,
                    iconTint = category.tint,
                    title = stringResource(category.titleRes),
                    subtitle = category.secondSubtitleRes?.let {
                        stringResource(R.string.settings_status_dot_separator, first, stringResource(it))
                    } ?: first,
                    onClick = { onOpen(category) }
                )
            }
        }
    }
}
