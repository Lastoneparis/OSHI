package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oshi.messenger.R
import com.oshi.messenger.service.lora.LoRaRegion
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.viewmodel.LoRaConnState
import com.oshi.messenger.ui.viewmodel.LoRaFirmwareFamilyUi
import com.oshi.messenger.ui.viewmodel.LoRaJammingUi
import com.oshi.messenger.ui.viewmodel.LoRaFormat
import com.oshi.messenger.ui.viewmodel.LoRaMeshNodeUi
import com.oshi.messenger.ui.viewmodel.LoRaNodeHealthUi
import com.oshi.messenger.ui.viewmodel.LoRaRefusal
import com.oshi.messenger.ui.viewmodel.LoRaViewModel
import java.text.DateFormat
import java.util.Date

/**
 * LoRaSettingsScreen — pair and manage a long-range LoRa radio.
 * Port of iOS `MeshtasticSettingsView.swift`.
 *
 * Information architecture follows iOS `:16-202`, in order:
 *   status + forget · detection (only while nothing is paired) · mesh nodes ·
 *   node name · region · pairing · test broadcast · why a message did not go
 *   out · diagnostics · live traffic · last error.
 *
 * Two rules from the iOS screen are load-bearing and are kept here:
 *
 *  • "Why a message did not go out" sits ABOVE the traffic log on purpose: it is
 *    the answer to the question that brings someone to this screen. Four refusal
 *    paths used to end in a log line and nothing else (`:140-145`).
 *  • Distance is only shown when it is genuinely known — the node must have
 *    broadcast a position (bare boards like a Heltec V3 have no GPS and never
 *    will) and this phone must have a fix. Showing "0 km" for a node that never
 *    said where it is would be a made-up number (`:220-225`). Without a position
 *    the row falls back to a signal-derived BAND, marked "~".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoRaSettingsScreen(
    onBack: () -> Unit,
    onOpenNodeSetup: () -> Unit,
    /**
     * This phone's position, when the app already knows it. Null keeps every row
     * on the estimated-range band rather than inventing a distance — see the
     * class note. Wire it to the app's location source at the call site.
     */
    deviceLocation: Pair<Double, Double>? = null,
    /** __MESHCORE_INTEROP_2026_11_25__ Open the thread of a MeshCore user or channel tapped here. */
    onOpenChat: (String) -> Unit = {},
    viewModel: LoRaViewModel = viewModel(
        factory = LoRaViewModel.Factory(LocalContext.current.applicationContext)
    )
) {
    // __LORA_BUY_LINK_2026_11_04__ For the "where do I get one" row below.
    val context = LocalContext.current
    val connState by viewModel.connState.collectAsState()
    val status by viewModel.status.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val region by viewModel.region.collectAsState()
    val transmitEnabled by viewModel.transmitEnabled.collectAsState()
    val mediaOverLoRa by viewModel.mediaOverLoRaEnabled.collectAsState()
    val positionBeacon by viewModel.positionBeaconEnabled.collectAsState()
    val bluetoothOn by viewModel.bluetoothOn.collectAsState()
    val permissionDenied by viewModel.permissionDenied.collectAsState()
    val connectedName by viewModel.connectedName.collectAsState()
    val myNodeNum by viewModel.myNodeNum.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val lastRefusal by viewModel.lastRefusal.collectAsState()
    val traffic by viewModel.traffic.collectAsState()
    val firmwareFamily by viewModel.firmwareFamily.collectAsState()
    val firmwareProbeDone by viewModel.firmwareProbeDone.collectAsState()
    val jamming by viewModel.jamming.collectAsState()
    val jammingOverrideUntil by viewModel.jammingOverrideUntil.collectAsState()

    val isReady = connState == LoRaConnState.READY
    var testText by remember { mutableStateOf("") }
    var showForgetConfirm by remember { mutableStateOf(false) }

    // __MESHCORE_INTEROP_2026_11_25__ One scan finds both firmwares: a radio advertising the MeshCore
    // companion service shows up in "Nearby radios" marked MeshCore and pairs as one (PIN explained first).
    val meshCore = remember { com.oshi.messenger.service.lora.MeshCoreManager.getInstance(context.applicationContext) }
    val meshCoreFound by meshCore.discovered.collectAsState()
    val meshCoreState by meshCore.state.collectAsState()
    val meshCoreName by meshCore.connectedName.collectAsState()
    val meshCoreFree = meshCoreName == null && !meshCore.isPaired
    var meshCorePinFor by remember { mutableStateOf<com.oshi.messenger.service.lora.MeshCoreManager.Device?>(null) }

    // iOS `:204-205`: scan while nothing is paired, stop on leave.
    DisposableEffect(connectedName) {
        if (connectedName == null) {
            viewModel.startScan()
            if (meshCoreFree) meshCore.startScan()
        }
        onDispose {
            viewModel.stopScan()
            if (meshCore.state.value == com.oshi.messenger.service.lora.MeshCoreManager.ConnState.SCANNING) meshCore.stopScan()
        }
    }
    meshCorePinFor?.let { d ->
        MeshCorePinDialog(d.name, onDismiss = { meshCorePinFor = null }, onContinue = {
            meshCorePinFor = null
            viewModel.stopScan()
            meshCore.connect(d.id)
        })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.lora_back))
                    }
                },
                title = { Text(stringResource(R.string.meshtastic_title)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ------------------------------------------- Status / paired radio
            item {
                LoRaSection(footer = stringResource(R.string.meshtastic_footer)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SettingsInputAntenna,
                            null,
                            Modifier.size(28.dp),
                            tint = if (isReady) OSHIGreen
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                status.displayName.ifBlank {
                                    connectedName ?: stringResource(R.string.meshtastic_no_radio)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                statusLine(connState, myNodeNum),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (connState == LoRaConnState.CONNECTING ||
                            connState == LoRaConnState.CONFIGURING
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }

                    // Region UNSET is the state that makes a node look connected
                    // and transmit nothing. Say so where the user is already
                    // looking, and offer the one screen that fixes it.
                    if (viewModel.needsRegion(connState, region)) {
                        Spacer(Modifier.height(10.dp))
                        AttentionBanner(
                            title = stringResource(R.string.lora_status_needs_region),
                            detail = stringResource(R.string.lora_status_needs_region_detail),
                            actionLabel = stringResource(R.string.lora_blocked_fix),
                            onAction = onOpenNodeSetup
                        )
                    } else if (isReady && !transmitEnabled) {
                        Spacer(Modifier.height(10.dp))
                        AttentionBanner(
                            title = stringResource(R.string.lora_status_tx_off),
                            detail = stringResource(R.string.lora_status_tx_off_detail),
                            actionLabel = stringResource(R.string.lora_blocked_fix),
                            onAction = onOpenNodeSetup
                        )
                    }

                    // __ANTI_JAMMING_PHASE1_2026_11_13__ Non-blocking: nothing to fix here, the
                    // app already routes around the radio. Saying so is the whole point.
                    if (isReady && jamming == LoRaJammingUi.PROBABLE_JAMMING) {
                        Spacer(Modifier.height(10.dp))
                        // __ANTI_JAMMING_OVERRIDE_2026_11_13__ The detector can be wrong, and off-grid with no
                        // Bluetooth neighbour LoRa is the only way out: let the person overrule it.
                        val overrideUntil = jammingOverrideUntil
                        AttentionBanner(
                            title = stringResource(R.string.lora_jamming_title),
                            detail = if (overrideUntil != null) {
                                stringResource(R.string.lora_jamming_override_active, timeOf(overrideUntil))
                            } else {
                                stringResource(R.string.lora_jamming_detail)
                            },
                            actionLabel = if (overrideUntil == null) {
                                stringResource(R.string.lora_jamming_send_anyway)
                            } else null,
                            onAction = { viewModel.sendOverLoRaAnyway() }
                        )
                    } else if (isReady && jamming == LoRaJammingUi.CONGESTED) {
                        Spacer(Modifier.height(10.dp))
                        WarningLine(stringResource(R.string.lora_congested))
                    }

                    // A node may be reachable and still have something to say.
                    // `attention` also carries resource keys (MeshtasticManager.kt:1202).
                    loRaErrorText(status.attention)?.let { WarningLine(it) }

                    if (connectedName != null) {
                        Divider(Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { showForgetConfirm = true }) {
                            Icon(Icons.Default.Cancel, null, Modifier.size(18.dp), tint = OSHIRed)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.meshtastic_forget), color = OSHIRed)
                        }
                    } else {
                        Divider(Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = onOpenNodeSetup) {
                            Icon(Icons.Default.Build, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.lora_setup_open))
                        }
                    }
                }
            }

            // ----------------------------------------------- Where to get one
            // __LORA_BUY_LINK_2026_11_04__ The app talks about paired radios
            // throughout and never said where a radio comes from. The page it
            // opens lists the nine boards we have actually verified, each with
            // the vendor's own price and a worldwide search link, so this is a
            // pointer at measured information rather than a shop.
            //
            // "from US$18" is the cheapest VERIFIED board (Heltec V3, read off
            // heltec.org at $17.90), not a marketing floor. If that page stops
            // agreeing, the number here is wrong and should be changed with it.
            item {
                LoRaSection {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://oshi-messenger.com/lora/")
                                        )
                                    )
                                }
                            }
                    ) {
                        Icon(
                            Icons.Default.ShoppingCart,
                            null,
                            Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.lora_buy_node),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.lora_buy_from, "US$18"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.OpenInNew,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ------------------------------------------- Stock Meshtastic radio -> offer OSHI Mesh
            // Only once the probe had its chance: before that MESHTASTIC may be an OSHI Mesh radio still answering.
            if (isReady && firmwareProbeDone && firmwareFamily == LoRaFirmwareFamilyUi.MESHTASTIC) {
                item { OshiMeshUpgradeSection() }
            }

            // ------------------------------------------- MeshCore radio (iOS meshCoreSection)
            item {
                MeshCoreSection(
                    meshtasticReady = isReady, bluetoothOn = bluetoothOn,
                    // Nearby radios (below) lists MeshCore radios while it is shown.
                    ownScanList = connectedName != null || permissionDenied || !bluetoothOn,
                    onOpenChat = onOpenChat,
                )
            }

            // ------------------------------------------- Detection
            if (connectedName == null) {
                item {
                    LoRaSection(header = stringResource(R.string.meshtastic_nearby)) {
                        when {
                            // Permission denial is NOT "Bluetooth off" — iOS `:57-66`.
                            permissionDenied ->
                                WarningLine(stringResource(R.string.meshtastic_bt_denied))
                            !bluetoothOn ->
                                WarningLine(stringResource(R.string.meshtastic_bt_off))
                            else -> {
                                val scanning = connState == LoRaConnState.SCANNING
                                TextButton(
                                    onClick = {
                                        if (scanning) {
                                            viewModel.stopScan()
                                            if (meshCoreState == com.oshi.messenger.service.lora.MeshCoreManager.ConnState.SCANNING) meshCore.stopScan()
                                        } else {
                                            viewModel.startScan()
                                            if (meshCoreFree) meshCore.startScan()
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (scanning) Icons.Default.Stop else Icons.Default.Search,
                                        null,
                                        Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(
                                            if (scanning) R.string.meshtastic_stop_scan
                                            else R.string.meshtastic_scan
                                        )
                                    )
                                }
                                // MeshCore radios found by the same scan, tagged so the two firmwares
                                // are never confused; pairing one explains its PIN first.
                                if (meshCoreFree) meshCoreFound.forEach { d ->
                                    MeshCoreDeviceRow(d) { meshCorePinFor = d }
                                }
                                if (scanning && discovered.isEmpty() && (!meshCoreFree || meshCoreFound.isEmpty())) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            stringResource(R.string.meshtastic_searching),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                discovered.forEach { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.connect(item.id, item.name) }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.name.ifBlank {
                                                    stringResource(R.string.lora_node_unnamed)
                                                }
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.meshtastic_rssi_dbm,
                                                    item.rssi
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            stringResource(R.string.meshtastic_pair),
                                            color = OSHIPurple
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ------------------------------------------- Mesh nodes
            if (isReady) {
                item {
                    LoRaSection(
                        header = stringResource(R.string.meshtastic_nodes_count, nodes.size)
                    ) {
                        if (nodes.isEmpty()) {
                            Text(
                                stringResource(R.string.meshtastic_no_nodes),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        nodes.forEach { node -> MeshNodeRow(node, deviceLocation) }
                    }
                }

                // --------------------------------------- Node name (owner)
                item {
                    OwnerRenameSection(
                        suggested = viewModel.suggestedOwnerName(),
                        currentLong = status.displayName,
                        currentShort = String.format("%04X", myNodeNum and 0xFFFFL),
                        onRename = viewModel::setOwner
                    )
                }

                // --------------------------------------- Region
                //
                // Hidden outright when the node cannot be configured, rather
                // than offering a control that fails at the tap.
                if (capabilities.canConfigureRegion) {
                    item {
                        LoRaSection(header = stringResource(R.string.lora_region_section)) {
                            LoRaValueRow(
                                stringResource(R.string.lora_node_region),
                                region?.let {
                                    if (it == LoRaRegion.UNSET) {
                                        stringResource(R.string.lora_region_unset)
                                    } else {
                                        it.displayName
                                    }
                                } ?: LoRaFormat.DASH
                            )
                            TextButton(onClick = onOpenNodeSetup) {
                                Text(stringResource(R.string.lora_region_change))
                            }
                        }
                    }
                }

                // --------------------------------------- Test broadcast
                item {
                    LoRaSection(
                        header = stringResource(R.string.meshtastic_test),
                        footer = stringResource(R.string.meshtastic_test_warning)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = testText,
                                onValueChange = { testText = it },
                                placeholder = {
                                    Text(stringResource(R.string.meshtastic_test_placeholder))
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    viewModel.sendTestBroadcast(testText)
                                    testText = ""
                                },
                                enabled = isReady && transmitEnabled
                            ) {
                                Icon(Icons.Default.Send, null, tint = OSHIPurple)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------- Media over LoRa (opt-in)
            //
            // Off by default. LoRa carries media terribly — only the smallest
            // images fit and a send takes minutes — so it stays off unless the
            // person deliberately turns it on for a true no-internet situation.
            // When off, the CARRIES_MEDIA refusal above is unchanged.
            if (connectedName != null) {
                item {
                    LoRaSection(
                        header = stringResource(R.string.lora_media_section),
                        footer = stringResource(R.string.lora_media_footer)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.lora_media_toggle),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = mediaOverLoRa,
                                onCheckedChange = { viewModel.setMediaOverLoRa(it) }
                            )
                        }
                    }
                }
            }

            // ------------------------------------- Beacon my position (opt-in)
            //
            // Off by default and persisted off. This is the switch that makes
            // `broadcastPosition` reachable at all — before it, both phones could
            // encode a Position frame and neither ever sent one.
            //
            // The footer has to say what this exposes, not just what it does:
            // the frame goes out in CLEARTEXT to BROADCAST_ADDR on the default
            // LongFast channel, whose key is published in Meshtastic's own
            // source. Every radio in range hears it — tens of kilometres, people
            // the person has never met and cannot list. A switch labelled only
            // "share my position" would be describing a private feature.
            if (connectedName != null) {
                item {
                    LoRaSection(
                        header = stringResource(R.string.lora_position_section),
                        footer = stringResource(R.string.lora_position_footer)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.lora_position_toggle),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = positionBeacon,
                                onCheckedChange = { viewModel.setPositionBeacon(it) }
                            )
                        }
                    }
                }
            }

            // ------------------------------------------- Why a message did not go out
            lastRefusal?.let { refusal ->
                item {
                    LoRaSection(header = stringResource(R.string.lora_blocked_section)) {
                        AttentionBanner(
                            title = refusalTitle(refusal),
                            detail = refusalDetail(refusal),
                            actionLabel = if (refusal.isFixableHere) {
                                stringResource(R.string.lora_blocked_fix)
                            } else {
                                null
                            },
                            onAction = onOpenNodeSetup
                        )
                    }
                }
            }

            // ------------------------------------------- Diagnostics
            //
            // Every unreported value renders "—". A node that has said nothing
            // about its battery must NEVER read "0 %"
            // (LoRaNodeProtocol.swift:108-112).
            if (isReady) {
                item {
                    LoRaSection(header = stringResource(R.string.lora_diagnostics_section)) {
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_health),
                            stringResource(healthRes(status.health))
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_firmware),
                            LoRaFormat.text(status.firmwareVersion)
                        )
                        if (capabilities.canReportBattery) {
                            LoRaValueRow(
                                stringResource(R.string.lora_diag_battery),
                                LoRaFormat.batteryPercent(status.batteryLevel)
                            )
                            LoRaValueRow(
                                stringResource(R.string.lora_diag_charging),
                                status.isCharging?.let {
                                    stringResource(
                                        if (it) R.string.lora_diag_yes else R.string.lora_diag_no
                                    )
                                } ?: LoRaFormat.DASH
                            )
                        }
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_ble_rssi),
                            LoRaFormat.dbm(status.bluetoothRssi)
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_lora_rssi),
                            LoRaFormat.dbm(status.loRaRssi)
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_snr),
                            LoRaFormat.snr(status.loRaSnr)
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_region),
                            LoRaFormat.text(status.region)
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_frequency),
                            LoRaFormat.megahertz(status.frequencyMhz)
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_uptime),
                            LoRaFormat.uptime(status.uptimeSeconds)
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_diag_last_packet),
                            status.lastPacketAt?.let { timeOf(it) } ?: LoRaFormat.DASH
                        )
                    }
                }
            }

            // ------------------------------------------- Live traffic
            if (traffic.isNotEmpty()) {
                item {
                    LoRaSection(header = stringResource(R.string.meshtastic_traffic)) {
                        traffic.take(12).forEach { e ->
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    e.direction,
                                    fontWeight = FontWeight.Bold,
                                    color = if (e.direction == "→") OSHIPurple else OSHIGreen
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(e.summary, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        timeOf(e.atMillis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ------------------------------------------- Last error
            // Resolved INSIDE item{}: LazyListScope is not a composable context.
            if (!lastError.isNullOrBlank()) {
                item {
                    LoRaCard {
                        Text(
                            loRaErrorText(lastError).orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = OSHIRed
                        )
                    }
                }
            }
        }
    }

    if (showForgetConfirm) {
        OshiAlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text(stringResource(R.string.meshtastic_forget)) },
            text = { Text(stringResource(R.string.meshtastic_forget_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disconnect(forget = true)
                    showForgetConfirm = false
                }) {
                    Text(stringResource(R.string.meshtastic_forget), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirm = false }) {
                    Text(stringResource(R.string.lora_cancel))
                }
            }
        )
    }
}

// =============================================================================
// Rows
// =============================================================================

/**
 * Signal AND distance, because dB alone means nothing to most people.
 * iOS `MeshtasticSettingsView.swift:226-249`.
 */
@Composable
private fun MeshNodeRow(node: LoRaMeshNodeUi, deviceLocation: Pair<Double, Double>?) {
    val parts = mutableListOf<String>()

    val lat = node.latitude
    val lon = node.longitude
    val km = if (lat != null && lon != null && deviceLocation != null) {
        LoRaFormat.distanceKm(deviceLocation.first, deviceLocation.second, lat, lon)
    } else {
        null
    }
    if (km != null) {
        // Both ends know where they are: a real distance, no qualifier.
        parts += LoRaFormat.distanceLabel(km)
    } else {
        // No GPS anywhere in the picture — fall back to signal strength, shown
        // as a band with a "~" because it IS a band.
        LoRaFormat.estimatedRangeKm(node.rssi)?.let { (lo, hi) ->
            parts += stringResource(R.string.lora_estimated_range, LoRaFormat.span(lo, hi))
        }
    }
    node.rssi?.let { parts += LoRaFormat.dbm(it) }
    parts += LoRaFormat.snr(node.snr)
    node.hopsAway?.let { hops ->
        parts += if (hops == 0) {
            stringResource(R.string.meshtastic_direct)
        } else {
            stringResource(R.string.meshtastic_hops, hops)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(30.dp)
                .background(OSHIPurple.copy(alpha = 0.15f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                node.shortName.ifBlank { "•" },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(node.longName.ifBlank { node.userId })
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Rename the node. iOS does this from the manager
 * (`renameIfFactoryDefault` / `setOwner`, `MeshtasticManager.swift:690-729`) and
 * never exposed a control; Android does, because a node straight from the
 * flasher calls itself "Meshtastic 23d8" and anyone carrying two of them cannot
 * tell which is theirs.
 */
@Composable
private fun OwnerRenameSection(
    suggested: String,
    currentLong: String,
    currentShort: String,
    onRename: (String, String) -> Boolean
) {
    var longName by remember(currentLong) { mutableStateOf(currentLong) }
    var shortName by remember(currentShort) { mutableStateOf(currentShort) }

    LoRaSection(
        header = stringResource(R.string.lora_owner_section),
        footer = stringResource(R.string.lora_owner_footer)
    ) {
        OutlinedTextField(
            value = longName,
            onValueChange = { longName = it },
            label = { Text(stringResource(R.string.lora_owner_long_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = shortName,
            // 4 characters is what fits on the node's display and in other
            // clients' lists; clamped here so the radio layer never guesses.
            onValueChange = { shortName = it.take(4) },
            label = { Text(stringResource(R.string.lora_owner_short_label)) },
            supportingText = { Text(stringResource(R.string.lora_owner_short_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { longName = suggested }) {
                Text(stringResource(R.string.lora_owner_suggest))
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = { onRename(longName, shortName) },
                enabled = longName.isNotBlank()
            ) {
                Text(stringResource(R.string.lora_owner_apply))
            }
        }
    }
}

@Composable
private fun AttentionBanner(
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OSHIOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Build,
            null,
            Modifier.size(18.dp),
            tint = OSHIOrange
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(actionLabel, color = OSHIOrange)
                }
            }
        }
    }
}

// =============================================================================
// Copy mapping
// =============================================================================

@Composable
private fun statusLine(state: LoRaConnState, myNodeNum: Long): String = when (state) {
    LoRaConnState.OFF -> stringResource(R.string.meshtastic_state_off)
    LoRaConnState.SCANNING -> stringResource(R.string.meshtastic_state_scanning)
    LoRaConnState.CONNECTING -> stringResource(R.string.meshtastic_state_connecting)
    LoRaConnState.CONFIGURING -> stringResource(R.string.meshtastic_state_configuring)
    LoRaConnState.READY ->
        stringResource(R.string.meshtastic_state_ready) + " — " + LoRaFormat.nodeId(myNodeNum)
}

@Composable
private fun refusalTitle(r: LoRaRefusal): String = when (r) {
    is LoRaRefusal.NotConnected -> stringResource(R.string.lora_error_not_connected)
    is LoRaRefusal.RadioMuted -> stringResource(R.string.lora_status_tx_off)
    is LoRaRefusal.NoRegion -> stringResource(R.string.lora_status_needs_region)
    is LoRaRefusal.TooLarge -> stringResource(R.string.lora_blocked_too_large)
    is LoRaRefusal.CarriesMedia -> stringResource(R.string.lora_blocked_media)
}

@Composable
private fun refusalDetail(r: LoRaRefusal): String = when (r) {
    is LoRaRefusal.NotConnected -> stringResource(R.string.lora_status_searching_detail)
    is LoRaRefusal.RadioMuted -> stringResource(R.string.lora_status_tx_off_detail)
    is LoRaRefusal.NoRegion -> stringResource(R.string.lora_status_needs_region_detail)
    is LoRaRefusal.TooLarge -> stringResource(R.string.lora_blocked_too_large_detail, r.bytes)
    is LoRaRefusal.CarriesMedia -> stringResource(R.string.lora_blocked_media_detail)
}

internal fun healthRes(h: LoRaNodeHealthUi): Int = when (h) {
    LoRaNodeHealthUi.DISCONNECTED -> R.string.lora_health_disconnected
    LoRaNodeHealthUi.CONNECTING -> R.string.lora_health_connecting
    LoRaNodeHealthUi.DEGRADED -> R.string.lora_health_degraded
    LoRaNodeHealthUi.HEALTHY -> R.string.lora_health_healthy
}

private fun timeOf(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
