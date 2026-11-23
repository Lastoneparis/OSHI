package com.oshi.messenger.ui.screens

import android.Manifest
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.theme.OSHIBlue
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.viewmodel.MainViewModel

/**
 * The onboarding pages, in the order iOS presents them
 * (`OnboardingView.swift:180-200`).
 *
 * 🔧 P1 FIX (F-7) — Android shipped three pages against iOS's eight, and the
 * two missing SAFETY pages were among them: `GoodConductPage` (the do/don't
 * disclosure Play's User Generated Content policy asks for, and Apple's 1.2)
 * and `DisclaimerPage` (mesh range, notification behaviour, encryption scope,
 * "no security audit yet"). Both are now here, in iOS's relative order:
 * Features → Good Conduct → Disclaimer → the terminal page.
 *
 * An enum rather than an `Int` because the page bound was the literal `2` in
 * four places — the two `currentPage < 2` guards, the button label and the
 * button icon — so adding a page meant finding all four or shipping a "Next"
 * that creates an identity halfway through.
 *
 * 🔧 Round 2 — `SendMessagePage` (OnboardingView.swift:481) and `ConnectivityPage`
 * (:525) are here too, so Android now has every page iOS shows. Order is iOS's
 * relative order with one difference that predates this change: iOS shows the
 * identity page second and ends on a separate `GetStartedPage`, while Android
 * folds the two together and ends on IDENTITY, because on Android the terminal
 * button is what creates the identity. Everything between keeps iOS's sequence:
 * SendMessage → Connectivity → Features → GoodConduct → Disclaimer.
 */
enum class OnboardingPage {
    WELCOME,
    SEND_MESSAGE,
    CONNECTIVITY,
    FEATURES,
    GOOD_CONDUCT,
    DISCLAIMER,

    /** Terminal page: its primary action creates the identity, not "Next". */
    IDENTITY;

    companion object {
        val ORDER: List<OnboardingPage> = values().toList()
    }
}

private val LAST_ONBOARDING_PAGE = OnboardingPage.ORDER.lastIndex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var displayName by remember { mutableStateOf("") }
    var currentPage by remember { mutableStateOf(0) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var deviceRecoveryMessage by remember { mutableStateOf<String?>(null) }

    val isCreatingIdentity by viewModel.isCreatingIdentity.collectAsState()
    val identityCreationComplete by viewModel.identityCreationComplete.collectAsState()
    val creationError by viewModel.creationError.collectAsState()
    val isOnboarded by viewModel.isOnboarded.collectAsState()

    // Navigate after identity creation OR after a successful recovery-key restore
    LaunchedEffect(identityCreationComplete) {
        if (identityCreationComplete) {
            viewModel.resetIdentityCreationState()
            onComplete()
        }
    }
    LaunchedEffect(isOnboarded) {
        if (isOnboarded) {
            onComplete()
        }
    }

    if (showRestoreDialog) {
        RestoreIdentityDialog(
            onDismiss = {
                showRestoreDialog = false
                restoreError = null
            },
            onRestore = { recoveryKey ->
                val ok = viewModel.importRecoveryKey(recoveryKey)
                if (!ok) {
                    restoreError = context.getString(R.string.onboarding_invalid_recovery_key)
                    false
                } else {
                    showRestoreDialog = false
                    true
                }
            },
            error = restoreError
        )
    }

    // 🔴 A MODAL THE USER COULD NOT LEAVE, on the app's first screen.
    //
    // `onDismissRequest = { }` swallows the back gesture and the scrim tap, and
    // the confirm button's body was the comment `/* retry will reset error */`
    // — an empty lambda. The comment is also wrong: the only thing that resets
    // this state is `MainViewModel.resetIdentityCreationState()` (:321-324),
    // which clears `isCreatingIdentity` and `identityCreationComplete` and
    // does NOT touch `_creationError`. Nothing in the app ever writes it back
    // to null after `:313` sets it. So a failed identity creation left the
    // user staring at an OK button that did nothing, on first launch, with no
    // way out but force-quitting — and the retry it names is behind the dialog.
    //
    // Fixed inside this screen rather than in the view model, which belongs to
    // another team this pass: the dialog now shows only while the CURRENT error
    // has not been acknowledged. Clearing the flow-level state as well is in
    // the handoff.
    var acknowledgedCreationError by remember { mutableStateOf<String?>(null) }
    val liveCreationError = creationError?.takeIf { it != acknowledgedCreationError }
    if (liveCreationError != null) {
        OshiAlertDialog(
            onDismissRequest = { acknowledgedCreationError = liveCreationError },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(stringResource(R.string.onboarding_error_create, liveCreationError)) },
            confirmButton = {
                TextButton(onClick = { acknowledgedCreationError = liveCreationError }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // Short window (landscape phone, split screen, a foldable's cover display):
    // the chrome around the scrolling page is trimmed so the page keeps as much
    // of the ~360 dp it has as possible. The buttons never move.
    val windowSize = rememberWindowSize()
    val isShortWindow = windowSize.height == HeightClass.Compact

    Scaffold { padding ->
        // The pages used to sit in a single non-scrolling
        // `Column(fillMaxSize, SpaceBetween)` with the button row as its last
        // child. That holds up in portrait and nowhere else: turn a phone
        // sideways (~360 dp of usable height once the bars are taken) and the
        // Features page alone is taller than the window, so SpaceBetween pushes
        // "Next" off the bottom edge — with no scroll to reach it, onboarding
        // becomes a dead end and the app is unusable. Android 16 makes that
        // reachable on any tablet, since it no longer honours our portrait lock.
        //
        // Now: the page scrolls in a weighted region, the buttons are pinned
        // below it, and the content is capped at a readable 560 dp so it is a
        // centred column on a tablet rather than a strip of text 1200 dp wide.
        // The two new pages (Good Conduct, Disclaimer) are the tallest in the
        // flow — the Disclaimer alone is six cards — so they only hold up
        // BECAUSE of this shape. Do not reintroduce a fixed-height column here.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (OnboardingPage.ORDER[currentPage.coerceIn(0, LAST_ONBOARDING_PAGE)]) {
                OnboardingPage.WELCOME -> WelcomePage()
                OnboardingPage.SEND_MESSAGE -> SendMessagePage()
                OnboardingPage.CONNECTIVITY -> ConnectivityPage()
                OnboardingPage.FEATURES -> FeaturesPage()
                OnboardingPage.GOOD_CONDUCT -> GoodConductPage()
                OnboardingPage.DISCLAIMER -> DisclaimerPage()
                OnboardingPage.IDENTITY -> IdentityPage(
                    displayName = displayName,
                    onDisplayNameChange = { displayName = it },
                    onRestoreClick = { showRestoreDialog = true },
                    onRecoverFromDeviceClick = {
                        val ok = viewModel.recoverIdentityFromDevice()
                        deviceRecoveryMessage =
                            if (ok) context.getString(R.string.onboarding_recover_device_ok)
                            else context.getString(R.string.onboarding_recover_device_none)
                    },
                    deviceRecoveryMessage = deviceRecoveryMessage
                )
            }

        }

            Spacer(modifier = Modifier.height(if (isShortWindow) 8.dp else 24.dp))

            // Navigation buttons — pinned outside the scrolling region so they
            // are reachable at any window height.
            Row(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = if (isShortWindow) 8.dp else 16.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentPage > 0) {
                    OutlinedButton(
                        onClick = { currentPage-- }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_back))
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Button(
                    onClick = {
                        if (currentPage < LAST_ONBOARDING_PAGE) {
                            currentPage++
                        } else if (displayName.isNotBlank() && !isCreatingIdentity) {
                            viewModel.createIdentity(displayName)
                            // Navigation happens via LaunchedEffect when identityCreationComplete becomes true
                        }
                    },
                    enabled = (currentPage < LAST_ONBOARDING_PAGE || displayName.isNotBlank()) && !isCreatingIdentity
                ) {
                    if (isCreatingIdentity) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_creating))
                    } else {
                        Text(if (currentPage < LAST_ONBOARDING_PAGE) stringResource(R.string.onboarding_next) else stringResource(R.string.onboarding_create_identity))
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            if (currentPage < LAST_ONBOARDING_PAGE) Icons.Default.ArrowForward else Icons.Default.Check,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = brandInk()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onboarding_welcome),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_subtitle),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Page 2 — Send Your First Message. Port of iOS `SendMessagePage`
 * (OnboardingView.swift:481-516): the two ways to add someone, as chips rather
 * than a numbered procedure, exactly as iOS reduced it.
 *
 * A plain growing `Column` with no fixed heights — the caller scrolls it in a
 * weighted region with the buttons pinned outside, which is what keeps "Next"
 * reachable on a short or landscape window. See the comment on [OnboardingScreen].
 */
@Composable
private fun SendMessagePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Send,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = brandInk()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_message_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.onboarding_message_description),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // iOS lays the two chips side by side; on a compact width that gives two
        // 2-line labels in ~150 dp each, so they are stacked full-width here.
        MethodChip(
            icon = Icons.Default.QrCodeScanner,
            text = stringResource(R.string.onboarding_method_qr_title)
        )
        Spacer(modifier = Modifier.height(12.dp))
        MethodChip(
            icon = Icons.Default.ContentPaste,
            text = stringResource(R.string.onboarding_method_paste_title)
        )
    }
}

/** iOS `MethodChip` (OnboardingView.swift:503-506) — one icon, one label. */
@Composable
private fun MethodChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = brandInk(),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Page 3 — how OSHI reaches people. Port of iOS `ConnectivityPage`
 * (OnboardingView.swift:525-611): the two modes (online / offline mesh), the
 * three offline ranges, the tip and — the part that matters most — the
 * disclaimer, which states the limits BEFORE a user relies on offline delivery.
 *
 * iOS's animated `RangeDiagram` is deliberately not ported: it is decoration
 * (`accessibilityHidden`), and it is the one element that would need a fixed
 * height on the page that already carries the most text.
 */
@Composable
private fun ConnectivityPage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.onboarding_reach_title),
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.onboarding_reach_subtitle),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        ReachModeRow(
            icon = Icons.Default.Public,
            tint = OSHIBlue,
            title = stringResource(R.string.onboarding_reach_online_title),
            detail = stringResource(R.string.onboarding_reach_online_body)
        )
        ReachModeRow(
            icon = Icons.Default.WifiOff,
            tint = OSHIOrange,
            title = stringResource(R.string.onboarding_reach_offline_title),
            detail = stringResource(R.string.onboarding_reach_offline_body)
        )

        Spacer(modifier = Modifier.height(16.dp))

        RangeRow(
            icon = Icons.Default.Smartphone,
            color = OSHIGreen,
            distance = stringResource(R.string.onboarding_reach_range1_dist),
            label = stringResource(R.string.onboarding_reach_range1_label)
        )
        RangeRow(
            icon = Icons.Default.Hub,
            color = OSHIOrange,
            distance = stringResource(R.string.onboarding_reach_range2_dist),
            label = stringResource(R.string.onboarding_reach_range2_label)
        )
        RangeRow(
            icon = Icons.Default.SettingsInputAntenna,
            color = OSHIRed,
            distance = stringResource(R.string.onboarding_reach_range3_dist),
            label = stringResource(R.string.onboarding_reach_range3_label)
        )

        Spacer(modifier = Modifier.height(16.dp))

        ReachNote(
            icon = Icons.Default.Lightbulb,
            iconTint = OSHIOrange,
            text = stringResource(R.string.onboarding_reach_tip)
        )
        ReachNote(
            icon = Icons.Default.Warning,
            iconTint = OSHIOrange,
            text = stringResource(R.string.onboarding_reach_disclaimer)
        )
    }
}

/** iOS `ReachModeRow` (OnboardingView.swift:614-630). */
@Composable
private fun ReachModeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                text = detail,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** iOS `RangeRow` (OnboardingView.swift:632-652) — icon, distance, plain label. */
@Composable
private fun RangeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    distance: String,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = distance,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(78.dp)
        )
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The tip and the disclaimer, both a tinted card with a leading glyph. */
@Composable
private fun ReachNote(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    text: String
) {
    OshiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeaturesPage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.onboarding_key_features),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        FeatureItem(
            icon = Icons.Default.Security,
            title = stringResource(R.string.onboarding_e2e_title),
            description = stringResource(R.string.onboarding_e2e_desc)
        )

        FeatureItem(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.onboarding_mesh_title),
            description = stringResource(R.string.onboarding_mesh_desc)
        )

        FeatureItem(
            icon = Icons.Default.Call,
            title = stringResource(R.string.onboarding_calls_title),
            description = stringResource(R.string.onboarding_calls_desc)
        )

        FeatureItem(
            icon = Icons.Default.Devices,
            title = stringResource(R.string.onboarding_cross_platform_title),
            description = stringResource(R.string.onboarding_cross_platform_desc)
        )
    }
}

@Composable
private fun FeatureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = brandInk(),
            modifier = Modifier.size(32.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                text = description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityPage(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onRestoreClick: () -> Unit,
    onRecoverFromDeviceClick: () -> Unit,
    deviceRecoveryMessage: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = brandInk()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_create_your_identity),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_choose_display_name),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text(stringResource(R.string.onboarding_display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OshiCard(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = brandInk()
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.onboarding_key_info),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recover identity already on this device (iOS parity).
        // Useful when DataStore was cleared but the encrypted key store survived.
        TextButton(onClick = onRecoverFromDeviceClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PhonelinkLock, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_recover_from_device))
        }
        if (deviceRecoveryMessage != null) {
            Text(
                deviceRecoveryMessage,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }

        // Restore-from-backup escape hatch (iOS parity).
        // Lets a user who already has a 64-byte recovery key paste it in instead of
        // generating a brand-new identity.
        TextButton(onClick = onRestoreClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_restore_from_backup))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreIdentityDialog(
    onDismiss: () -> Unit,
    onRestore: (String) -> Boolean,
    error: String?
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var key by remember { mutableStateOf("") }

    // QR scan launcher — same pattern as KeyBackupScreen / NewMessageScreen.
    // Recovery QRs are emitted with the `oshi-recovery:` prefix; strip it.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { scanned ->
            key = scanned.removePrefix("oshi-recovery:").trim()
        }
    }
    val launchScan: () -> Unit = {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(context.getString(R.string.onboarding_scan_prompt))
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(opts)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchScan() }
    val startScan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            launchScan()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.onboarding_restore_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.onboarding_restore_description),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(R.string.onboarding_recovery_key)) },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            clipboard.getText()?.text?.let { pasted ->
                                key = pasted.trim()
                                // __CLIPBOARD_HYGIENE_2026_10_01__ recovery key pasted into OSHI ⇒ take it off the clipboard.
                                com.oshi.messenger.security.SensitiveClipboard.clearIfStillHolds(context, pasted)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.onboarding_paste_clipboard))
                    }
                    TextButton(
                        onClick = startScan,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.onboarding_scan_qr))
                    }
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRestore(key) },
                enabled = key.isNotBlank()
            ) {
                Text(stringResource(R.string.onboarding_restore_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Page 3 — Good Conduct. Port of iOS `GoodConductPage`
 * (OnboardingView.swift:731-778): the do / don't list a user-generated-content
 * messenger is expected to show before first use. Play's UGC policy asks for
 * the same disclosure Apple's 1.2 does, and Android had no equivalent page.
 *
 * No fixed heights and no `SpaceBetween`: this composable is a plain `Column`
 * that grows, and the caller scrolls it in a weighted region with the buttons
 * pinned outside — see the comment at the top of [OnboardingScreen]. That is
 * what keeps "Next" reachable on a short or landscape window.
 */
@Composable
private fun GoodConductPage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.PanTool,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = OSHIOrange
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_conduct_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.onboarding_conduct_subtitle),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        RuleRow(allowed = true, text = stringResource(R.string.onboarding_rule_respect))
        RuleRow(allowed = true, text = stringResource(R.string.onboarding_rule_privacy))
        RuleRow(allowed = true, text = stringResource(R.string.onboarding_rule_secure))
        RuleRow(allowed = true, text = stringResource(R.string.onboarding_rule_backup))

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        RuleRow(allowed = false, text = stringResource(R.string.onboarding_rule_no_spam))
        RuleRow(allowed = false, text = stringResource(R.string.onboarding_rule_no_abuse))
        RuleRow(allowed = false, text = stringResource(R.string.onboarding_rule_no_illegal))
    }
}

/** iOS `RuleRow` (OnboardingView.swift:731-778) — a green tick or a red cross. */
@Composable
private fun RuleRow(allowed: Boolean, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (allowed) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (allowed) OSHIGreen else OSHIRed,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, fontSize = 15.sp)
    }
}

/**
 * Page 4 — Disclaimer. Port of iOS `DisclaimerPage` + `DisclaimerCard`
 * (OnboardingView.swift:780-914, :916): mesh range, notification behaviour,
 * encryption scope, cloud fallback and the "no audit yet" statement.
 *
 * Every string already existed in `res/values/strings.xml` (and in all 34
 * translations) — the page that renders them was the only missing part.
 */
@Composable
private fun DisclaimerPage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = OSHIOrange
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_disclaimer_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.onboarding_disclaimer_subtitle),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Same six cards, same order, as iOS.
        DisclaimerCard(
            icon = Icons.Default.Security,
            iconTint = OSHIOrange,
            title = stringResource(R.string.onboarding_disclaimer_audit_title),
            description = stringResource(R.string.onboarding_disclaimer_audit_desc)
        )
        DisclaimerCard(
            icon = Icons.Default.VisibilityOff,
            iconTint = OSHIPurple,
            title = stringResource(R.string.onboarding_disclaimer_anonymous_title),
            description = stringResource(R.string.onboarding_disclaimer_anonymous_desc)
        )
        DisclaimerCard(
            icon = Icons.Default.NotificationsOff,
            iconTint = OSHIPurple,
            title = stringResource(R.string.onboarding_disclaimer_notifications_title),
            description = stringResource(R.string.onboarding_disclaimer_notifications_desc)
        )
        DisclaimerCard(
            icon = Icons.Default.SettingsInputAntenna,
            iconTint = OSHIGreen,
            title = stringResource(R.string.onboarding_disclaimer_mesh_title),
            description = stringResource(R.string.onboarding_disclaimer_mesh_desc)
        )
        DisclaimerCard(
            icon = Icons.Default.Cloud,
            iconTint = OSHIBlue,
            title = stringResource(R.string.onboarding_disclaimer_cloud_title),
            description = stringResource(R.string.onboarding_disclaimer_cloud_desc)
        )
        DisclaimerCard(
            icon = Icons.Default.Public,
            iconTint = OSHIRed,
            title = stringResource(R.string.onboarding_disclaimer_critical_title),
            description = stringResource(R.string.onboarding_disclaimer_critical_desc)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_disclaimer_note),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = OSHIOrange,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DisclaimerCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    description: String
) {
    OshiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
