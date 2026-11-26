package com.oshi.messenger.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.oshi.messenger.R
import com.oshi.messenger.security.StrictModeStore
import com.oshi.messenger.security.StrictModeThreats
import com.oshi.messenger.security.StrictThreat
import com.oshi.messenger.security.StrictThreatKind
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.viewmodel.SettingsViewModel

// =============================================================================
// 🛡 __STRICT_MODE_2026_11_22__ Settings › Privacy & Security › Strict security mode.
// One switch, two sub-switches, what this phone exposes right now, and — plainly —
// what the mode does and what it cannot do (spec §5: honesty).
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrictModeScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val active by settingsViewModel.strictModeActive.collectAsState()
    val options by settingsViewModel.strictOptions.collectAsState()
    val lockAvailable by settingsViewModel.biometricAvailable.collectAsState()
    val threats = rememberStrictThreats()

    val unlockTitle = stringResource(R.string.strict_unlock_title)
    val unlockSubtitle = stringResource(R.string.strict_unlock_subtitle)
    fun turnOff() {
        val activity = context as? FragmentActivity
        if (!lockAvailable || activity == null) {
            settingsViewModel.setStrictMode(false)
            return
        }
        // Spec: turning the mode OFF needs the device's own authentication when a
        // lock exists, so someone handed the unlocked phone cannot just flip it.
        val prompt = androidx.biometric.BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    settingsViewModel.setStrictMode(false)
                }
            },
        )
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(unlockTitle)
            .setSubtitle(unlockSubtitle)
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        runCatching { prompt.authenticate(info) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
                title = { Text(stringResource(R.string.strict_title)) },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item { StrictHero(active) }

            item {
                SettingsSection(title = stringResource(R.string.strict_section_switch)) {
                    SettingsToggleItem(
                        icon = Icons.Default.Shield,
                        iconTint = if (active) OSHIGreen else OSHIPurple,
                        title = stringResource(R.string.strict_toggle_title),
                        subtitle = stringResource(if (active) R.string.strict_toggle_on else R.string.strict_toggle_off),
                        checked = active,
                        onCheckedChange = { on -> if (on) settingsViewModel.setStrictMode(true) else turnOff() },
                    )
                    SettingsToggleItem(
                        icon = Icons.Default.ContentPaste,
                        iconTint = OSHIPurple,
                        title = stringResource(R.string.strict_block_paste_title),
                        subtitle = stringResource(R.string.strict_block_paste_subtitle),
                        subtitleMaxLines = 2,
                        checked = options.blockPaste,
                        onCheckedChange = { settingsViewModel.setStrictOptions(options.copy(blockPaste = it)) },
                    )
                    SettingsToggleItem(
                        icon = Icons.Default.DoneAll,
                        iconTint = Color(0xFF3B82F6),
                        title = stringResource(R.string.strict_hide_presence_title),
                        subtitle = stringResource(R.string.strict_hide_presence_subtitle),
                        subtitleMaxLines = 2,
                        titleMaxLines = 2,
                        checked = options.hidePresence,
                        onCheckedChange = { settingsViewModel.setStrictOptions(options.copy(hidePresence = it)) },
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.strict_threats_title)) {
                    StrictThreatList(threats)
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.strict_does_title)) {
                    StrictLine(Icons.Default.ContentCopy, OSHIPurple, R.string.strict_does_copy)
                    StrictLine(Icons.Default.ContentPaste, OSHIPurple, R.string.strict_does_paste)
                    StrictLine(Icons.Default.ScreenLockPortrait, OSHIGreen, R.string.strict_does_screen)
                    StrictLine(Icons.Default.SmartToy, OSHIGreen, R.string.strict_does_agents)
                    StrictLine(Icons.Default.Layers, OSHIGreen, R.string.strict_does_overlays)
                    StrictLine(Icons.Default.Notifications, OSHIOrange, R.string.strict_does_notifications)
                    StrictLine(Icons.Default.Lock, OSHIPurple, R.string.strict_does_lock)
                    StrictLine(Icons.Default.Keyboard, OSHIPurple, R.string.strict_does_keyboard)
                    StrictLine(Icons.Default.LinkOff, OSHIOrange, R.string.strict_does_network)
                    StrictLine(Icons.Default.VisibilityOff, Color(0xFF3B82F6), R.string.strict_does_presence)
                    StrictLine(Icons.Default.Shield, OSHIGreen, R.string.strict_does_relay)
                    StrictLine(Icons.Default.History, OSHIPurple, R.string.strict_does_restore)
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.strict_cannot_title)) {
                    StrictLine(Icons.Default.BugReport, OSHIRed, R.string.strict_cannot_malware)
                    StrictLine(Icons.Default.Keyboard, OSHIRed, R.string.strict_cannot_keyboard)
                    StrictLine(Icons.Default.Hearing, OSHIOrange, R.string.strict_cannot_readers)
                    StrictLine(Icons.Default.PhoneAndroid, OSHIOrange, R.string.strict_cannot_old)
                    StrictLine(Icons.Default.CameraAlt, OSHIRed, R.string.strict_cannot_camera)
                    StrictLine(Icons.Default.People, OSHIOrange, R.string.strict_cannot_recipient)
                }
            }
        }
    }
}

@Composable
private fun StrictHero(active: Boolean) {
    val tint = if (active) OSHIGreen else OSHIPurple
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(if (active) R.string.strict_hero_on else R.string.strict_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.strict_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StrictLine(icon: ImageVector, tint: Color, textRes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun StrictThreatList(threats: List<StrictThreat>) {
    val context = LocalContext.current
    if (threats.isEmpty()) {
        StrictLine(Icons.Default.Shield, OSHIGreen, R.string.strict_threats_none)
    } else {
        threats.forEach { t ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(OSHIOrange.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (t.kind == StrictThreatKind.ACCESSIBILITY_SERVICE) Icons.Default.SmartToy else Icons.Default.Cast,
                        contentDescription = null, tint = OSHIOrange, modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(t.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            if (t.kind == StrictThreatKind.ACCESSIBILITY_SERVICE) R.string.strict_threat_a11y
                            else R.string.strict_threat_remote
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    if (t.kind == StrictThreatKind.ACCESSIBILITY_SERVICE) StrictModeThreats.openAccessibilitySettings(context)
                    else StrictModeThreats.openAppDetails(context, t.packageName)
                }) { Text(stringResource(R.string.strict_threats_open)) }
            }
        }
    }
    Text(
        text = stringResource(R.string.strict_threats_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    )
}

/** Re-checked on every resume: enabling a service means a trip to system Settings. */
@Composable
fun rememberStrictThreats(): List<StrictThreat> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    var threats by remember { mutableStateOf<List<StrictThreat>>(emptyList()) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) { threats = StrictModeThreats.collect(context) }
    return threats
}

/**
 * 🛡 __STRICT_MODE_2026_11_22__ Chat-screen warning (next to the third-party keyboard
 * banner): only while strict mode is on AND something on the phone can read or drive
 * the screen. Dismissed for the current set until it changes or OSHI restarts.
 */
@Composable
fun StrictModeThreatBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val active by remember(context) { StrictModeStore.get(context) }.active.collectAsState()
    if (!active) return
    val threats = rememberStrictThreats()
    val fingerprint = threats.joinToString(";") { it.packageName }
    var dismissed by remember { mutableStateOf(StrictBannerMemory.dismissed) }
    val visible = threats.isNotEmpty() && fingerprint != dismissed
    val amber = Color(0xFFF59E0B)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            color = amber.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(amber.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = amber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.strict_banner_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.strict_banner_body, threats.joinToString(", ") { it.label }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { StrictModeThreats.openAccessibilitySettings(context) }) {
                            Text(stringResource(R.string.strict_threats_open))
                        }
                        TextButton(onClick = {
                            StrictBannerMemory.dismissed = fingerprint
                            dismissed = fingerprint
                        }) { Text(stringResource(R.string.strict_banner_dismiss)) }
                    }
                }
            }
        }
    }
}

/** Process-lifetime: a restart re-arms the warning (strict mode is the opposite of quiet). */
private object StrictBannerMemory {
    @Volatile var dismissed: String? = null
}

/**
 * 🛡 __STRICT_MODE_2026_11_22__ `SelectionContainer` that is not one in strict mode:
 * the text cannot be selected at all, so there is nothing to copy or drag.
 */
@Composable
fun StrictSelectionContainer(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val active by remember(context) { StrictModeStore.get(context) }.active.collectAsState()
    if (active) content() else androidx.compose.foundation.text.selection.SelectionContainer(content = content)
}
