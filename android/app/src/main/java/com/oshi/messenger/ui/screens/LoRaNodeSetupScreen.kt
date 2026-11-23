package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiDropdownMenu
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import com.oshi.messenger.ui.viewmodel.LoRaMeshNodeUi
import com.oshi.messenger.service.lora.MeshtasticManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oshi.messenger.R
import com.oshi.messenger.service.lora.LoRaBluetoothPairing
import com.oshi.messenger.service.lora.LoRaChannel
import com.oshi.messenger.service.lora.LoRaModemPreset
import com.oshi.messenger.service.lora.LoRaRegion
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.viewmodel.LoRaConnState
import com.oshi.messenger.ui.viewmodel.LoRaDiscoveredUi
import com.oshi.messenger.ui.viewmodel.LoRaFormat
import com.oshi.messenger.ui.viewmodel.LoRaSignalWord
import com.oshi.messenger.ui.viewmodel.LoRaViewModel
import kotlinx.coroutines.delay

/**
 * LoRaNodeSetupScreen — the screen that turns a freshly flashed node into a
 * working one. Port of iOS `LoRaNodeSetupView.swift`.
 *
 * It exists for one measured reason. A node straight from the flasher boots with
 * `region=UNSET` and will not transmit:
 *
 *     INFO | Wanted region 0, using UNSET
 *
 * It pairs over Bluetooth, reports "connected", and silently sends nothing.
 * Anyone carrying that node in their pocket would believe they were on the mesh
 * and would not be. So this screen refuses to call a node ready until it has a
 * region, and says exactly that in plain words.
 *
 * Nothing here touches messaging, routing or encryption — it configures a radio.
 *
 * Section order mirrors iOS `:44-65`: status, region (only while it matters),
 * ready, discovery (only while not connected), pairing (only once connected),
 * guide (always — someone who has never held a board cannot guess it needs
 * flashing from a desktop browser first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoRaNodeSetupScreen(
    onBack: () -> Unit,
    /**
     * __LORA_CHANNEL_IS_A_ROOM_2026_11_05__ Open a channel's conversation.
     *
     * The channel list showed the radio's channels and did nothing with them, so
     * the only way into a channel's thread was to wait for somebody else to speak
     * in it and then find the row in the message list. You could not say the first
     * word in a room you were already on.
     *
     * Optional so every other caller and every preview keeps compiling; a null
     * simply leaves the rows inert, as they were.
     */
    onOpenChannel: ((String) -> Unit)? = null,
    viewModel: LoRaViewModel = viewModel(
        factory = LoRaViewModel.Factory(LocalContext.current.applicationContext)
    )
) {
    val connState by viewModel.connState.collectAsState()
    val region by viewModel.region.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val transmitEnabled by viewModel.transmitEnabled.collectAsState()
    val positionBeacon by viewModel.positionBeaconEnabled.collectAsState()
    val bluetoothOn by viewModel.bluetoothOn.collectAsState()
    val permissionDenied by viewModel.permissionDenied.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val status by viewModel.status.collectAsState()
    val myNodeNum by viewModel.myNodeNum.collectAsState()
    val chosenRegion by viewModel.chosenRegion.collectAsState()
    val preset by viewModel.preset.collectAsState()
    val regionSectionOpen by viewModel.regionSectionOpen.collectAsState()
    val connectingId by viewModel.connectingId.collectAsState()
    val connectingName by viewModel.connectingName.collectAsState()
    val pairingMode by viewModel.pairingMode.collectAsState()
    var showChileConfirm by remember { mutableStateOf(false) }
    val channelDraft by viewModel.channelDraft.collectAsState()
    val channelKeyDraft by viewModel.channelKeyDraft.collectAsState()
    val channelKeyInvalid by viewModel.channelKeyInvalid.collectAsState()
    val channelWritten by viewModel.channelWritten.collectAsState()
    val channelName by viewModel.channelName.collectAsState()
    // __PUBLIC_CHANNELS_2026_11_04__
    val nodeChannels by viewModel.nodeChannels.collectAsState()
    /**
     * __LORA_NODE_LIST_2026_11_04__ A clock that ticks every 30 s, so "2 min ago"
     * becomes "3 min ago" without the row rewriting itself every frame.
     *
     * 30 s and not 1 s on purpose: the labels are bucketed to the minute, so a
     * faster tick would redraw the list sixty times for every visible change,
     * and this screen is often open with the phone in someone's pocket.
     */
    /** __LORA_CONFIGURE_DISCLOSURE_2026_11_04__ Collapsed by default. */
    var configureOpen by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val pastedLink by viewModel.pastedLink.collectAsState()
    val pastedChannelSet by viewModel.pastedChannelSet.collectAsState()
    val pastedLinkInvalid by viewModel.pastedLinkInvalid.collectAsState()
    /** The name we just joined, for the confirmation line. Cleared by a new paste. */
    var joinedChannelName by remember { mutableStateOf<String?>(null) }
    // Recomputed when the node's channels change, which is exactly when the
    // shareable link changes; `shareableChannelUrl()` is a plain function, so
    // without `nodeChannels` in the key it would be computed once and go stale.
    val shareUrl = remember(nodeChannels, channelName) { viewModel.shareableChannelUrl() }

    val isReady = connState == LoRaConnState.READY
    val isBusy = connState == LoRaConnState.CONNECTING || connState == LoRaConnState.CONFIGURING
    val needsRegion = viewModel.needsRegion(connState, region)
    val uriHandler = LocalUriHandler.current
    val timeoutMessage = stringResource(R.string.lora_error_timeout)

    // iOS `:68-72`: scan on appear when there is nothing connected, stop on leave.
    DisposableEffect(isReady) {
        if (!isReady) viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    // iOS `:73-90`. Two things happen on a state change:
    //  • the attempt is over either way, so stop pointing at a row;
    //  • a radio that accepts the BLE link but never answers leaves the screen
    //    spinning forever — say so instead of spinning.
    LaunchedEffect(connState) {
        if (connState == LoRaConnState.READY || connState == LoRaConnState.OFF) {
            viewModel.onConnectionAttemptSettled()
        }
        if (connState == LoRaConnState.CONNECTING || connState == LoRaConnState.CONFIGURING) {
            delay(CONNECT_TIMEOUT_MS)
            // Still in the same state 20 s later — LaunchedEffect would have been
            // cancelled otherwise, which is the Compose equivalent of iOS's
            // `guard manager.state == attempt`.
            viewModel.reportConnectTimeout(timeoutMessage)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.lora_back))
                    }
                },
                title = { Text(stringResource(R.string.lora_node_title)) }
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

            // ---------------------------------------------------- Status
            item {
                LoRaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) {
                            if (isBusy) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = if (isReady && needsRegion) Icons.Default.Warning
                                    else Icons.Default.SettingsInputAntenna,
                                    contentDescription = null,
                                    tint = when {
                                        isBusy -> OSHIPurple
                                        !isReady -> MaterialTheme.colorScheme.onSurfaceVariant
                                        needsRegion -> OSHIOrange
                                        else -> OSHIGreen
                                    },
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                statusTitle(connState, needsRegion),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                statusDetail(connState, needsRegion, transmitEnabled, connectingName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isReady) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Divider()
                        LoRaValueRow(
                            stringResource(R.string.lora_node_name),
                            LoRaFormat.text(status.displayName)
                        )
                        LoRaValueRow(
                            stringResource(R.string.lora_node_id),
                            LoRaFormat.nodeId(myNodeNum)
                        )
                        // __LORA_HERO_2026_11_04__ The channel belongs here: "am I
                        // connected, and to WHICH mesh" is one question, and the
                        // answer used to be five sections apart.
                        LoRaValueRow(
                            stringResource(R.string.lora_hero_channel),
                            nodeChannels.firstOrNull { it.isPrimary }?.displayName
                                ?: LoRaChannel.displayName(channelName)
                        )
                        // "3 now, 11 total" rather than "11". A count of every
                        // radio ever heard answers a different question from the
                        // one being asked, which is who is within reach right now.
                        LoRaValueRow(
                            stringResource(R.string.lora_hero_heard),
                            stringResource(
                                R.string.lora_hero_heard_present,
                                nodes.count { LoRaFormat.isPresent(it.lastHeardMillis, nowMillis) },
                                nodes.size,
                            )
                        )
                    }
                }
            }

            // ------------------------------------------------ Radios heard
            // __LORA_NODE_LIST_2026_11_04__ Second, right under the status, because
            // "who can I reach" is the other half of what this screen is opened for.
            if (isReady) {
                item {
                    LoRaNodesSection(
                        nodes = nodes,
                        myNodeNum = myNodeNum,
                        nowMillis = nowMillis,
                    )
                }
            }

            // ---------------------------------------------------- Region
            //
            // Shown while the node has no region (the state this screen exists
            // for) and after the user opens it again to change one. Hidden
            // entirely when the node cannot be configured at all — the UI hides
            // what a node cannot do rather than failing at the tap
            // (LoRaNodeProtocol.swift:49-51).
            if (capabilities.canConfigureRegion && (needsRegion || regionSectionOpen)) {
                item {
                    LoRaSection(
                        header = stringResource(R.string.lora_region_section),
                        footer = stringResource(R.string.lora_region_footer)
                    ) {
                        RegionPickerRow(
                            selected = chosenRegion,
                            onSelect = viewModel::chooseRegion
                        )
                        PresetPickerRow(
                            selected = preset,
                            onSelect = viewModel::choosePreset
                        )
                        FilledTonalButton(
                            onClick = { viewModel.applyRegion() },
                            enabled = chosenRegion != null &&
                                chosenRegion != LoRaRegion.UNSET && isReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.lora_region_apply))
                        }
                        TextButton(
                            onClick = { showChileConfirm = true },
                            enabled = isReady,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.lora_chile_apply))
                        }
                        Text(
                            stringResource(R.string.lora_chile_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------------------------------------------- Ready
            val configured = region
            if (isReady && configured != null && configured != LoRaRegion.UNSET) {
                item {
                    LoRaSection(header = stringResource(R.string.lora_node_section_radio)) {
                        LoRaValueRow(
                            stringResource(R.string.lora_node_region),
                            configured.displayName
                        )
                        if (!transmitEnabled) {
                            WarningLine(stringResource(R.string.lora_status_tx_off))
                        }
                        if (capabilities.canConfigureRegion) {
                            TextButton(onClick = viewModel::openRegionSection) {
                                Text(stringResource(R.string.lora_region_change))
                            }
                        }
                    }
                }
                // __MAP_VISIBILITY_OPT_IN_2026_11_09__ Setup never mentioned the
                // position beacon (its only switch was in LoRa settings), so a
                // freshly paired node was invisible on every map, Meshtastic's
                // and OSHI's. Surfaced here as the SAME explicit opt-in, same
                // stored preference, default OFF: it is a public cleartext
                // location broadcast to every radio in range, never automatic.
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

            // ---------------------------------------------------- Channel
            //
            // __LORA_CHANNEL_2026_10_26__ Which MESH the node is on. Asked for
            // by a user in Chile (Play review, 2026-10-25): the region was
            // settable from here, the channel was not, so being on a country's
            // community mesh meant installing a second Meshtastic client to set
            // it and coming back. Next to region and node name because all
            // three are the same kind of thing - node configuration, written
            // over the same BLE link.
            if (isReady && capabilities.canConfigureRegion) {
                item {
                    ChannelSection(
                        name = channelDraft,
                        key = channelKeyDraft,
                        keyInvalid = channelKeyInvalid,
                        written = channelWritten,
                        lastWritten = channelName,
                        onName = viewModel::editChannelName,
                        onKey = viewModel::editChannelKey,
                        onApply = { viewModel.applyChannel() }
                    )
                }

                // __PUBLIC_CHANNELS_2026_11_04__ The node's own answer, then the
                // two things a user actually does with a public channel: join one
                // somebody shared, and share their own. Gated on the same
                // `canConfigureRegion` as the section above, because all three
                // write over the same BLE admin link.
                item { NodeChannelsSection(channels = nodeChannels, onOpen = onOpenChannel) }
                item {
                    JoinChannelSection(
                        link = pastedLink,
                        invalid = pastedLinkInvalid,
                        parsed = pastedChannelSet,
                        joined = joinedChannelName,
                        onLink = viewModel::onPastedLink,
                        onJoin = {
                            val name = pastedChannelSet?.primary?.displayName
                            if (viewModel.joinPastedChannelSet()) joinedChannelName = name
                        },
                    )
                }
                item { ShareChannelSection(url = shareUrl) }
            }

            // ---------------------------------------------------- Discovery
            if (!isReady) {
                item {
                    LoRaSection(
                        header = stringResource(R.string.lora_discovery_section),
                        footer = stringResource(R.string.lora_discovery_footer)
                    ) {
                        when {
                            permissionDenied ->
                                WarningLine(stringResource(R.string.lora_error_bluetooth_denied))
                            !bluetoothOn ->
                                WarningLine(stringResource(R.string.lora_error_bluetooth_off))
                            discovered.isEmpty() ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        stringResource(R.string.lora_discovery_searching),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            else -> {
                                // __PIN_WITHOUT_A_SCREEN_2026_10_21__ Le moment
                                // EXACT ou on en a besoin. L'option « Sans code »
                                // existe plus bas mais vit sous `if (isReady)`:
                                // elle n'apparaît qu'une fois CONNECTÉ, donc elle
                                // ne peut pas débloquer un premier appairage.
                                // Inévitable — c'est une config du nœud, écrite
                                // par la liaison Bluetooth — mais laisser
                                // l'utilisateur sans indice ne l'est pas.
                                Text(
                                    stringResource(R.string.lora_pairing_noscreen_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                discovered.forEach { found ->
                                DiscoveredNodeRow(
                                    node = found,
                                    spinning = isBusy && connectingId == found.id,
                                    enabled = !isBusy,
                                    onClick = { viewModel.connect(found.id, found.name) }
                                )
                                }
                            }
                        }

                        loRaErrorText(lastError)?.let { WarningLine(it) }
                    }
                }
            }

            // ------------------------------------------- Configure radio
            // __LORA_CONFIGURE_DISCLOSURE_2026_11_04__ Pairing, node name and
            // routing are all real settings and none of them is what this screen
            // is opened for. They used to sit in the same flat stack as the
            // status, which is how a status screen became a ten-section form.
            //
            // Collapsed by DEFAULT and nothing removed — the three sections below
            // are the same composables, one tap away. Progressive disclosure:
            // reveal complex options on request rather than piling them up front.
            if (isReady) {
                item {
                    ConfigureDisclosureRow(
                        open = configureOpen,
                        onToggle = { configureOpen = !configureOpen },
                    )
                }
            }

            if (isReady && configureOpen) {
                // ------------------------------------------------ Pairing
                //
                // A node ships asking for a 6-digit code shown on its own screen.
                // That is the safer default and stays the default — but plenty of
                // boards have no screen to read it off. iOS `:286-309`.
                item {
                    LoRaSection(
                        header = stringResource(R.string.lora_pairing_section),
                        footer = stringResource(R.string.lora_pairing_footer)
                    ) {
                        val mode = pairingMode ?: LoRaBluetoothPairing.RANDOM_PIN
                        PairingPickerRow(selected = mode, onSelect = viewModel::setPairingMode)
                        Text(
                            stringResource(pairingDetailRes(mode)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ------------------------------------------------ Node name
                // __RENAME_FROM_THE_APP_2026_10_21__ `setOwner` existait et
                // fonctionnait, mais rien n'y menait depuis l'app: pour appeler son
                // nœud autrement que « OSHI Mesh 23D8 » il fallait installer un
                // autre client Meshtastic. Parite iOS: `LoRaNodeSetupView`.
                item {
                    NodeNameSection(
                        current = nodes.firstOrNull { it.num == myNodeNum },
                        onSave = { ln, sn -> viewModel.setOwner(ln, sn) }
                    )
                }
            }

            // -------------------------------------------------- Routing
            // __NEVER_OVER_THE_INTERNET_2026_10_20__ On THIS screen, and not in
            // the general settings list, because both switches are meaningless
            // without a paired node and a setting you cannot act on does not
            // belong in a settings list. Parity: iOS `LoRaNodeSetupView`.
            //
            // Inside the disclosure once connected; on its own when not, because
            // then there is no disclosure to put it in and the two switches are
            // still the only thing on this screen a user can act on.
            if (!isReady || configureOpen) {
                item {
                    RoutingSection(radioReady = isReady)
                }
            }

            // ---------------------------------------------------- Guide
            item {
                LoRaSection(
                    header = stringResource(R.string.lora_guide_section),
                    footer = stringResource(R.string.lora_guide_footer)
                ) {
                    GUIDE_STEPS.forEachIndexed { i, res ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(OSHIPurple, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TextButton(onClick = { uriHandler.openUri(FLASHER_URL) }) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.lora_guide_link))
                    }
                }
            }
        }
    }

    if (showChileConfirm) {
        OshiAlertDialog(
            onDismissRequest = { showChileConfirm = false },
            title = { Text(stringResource(R.string.lora_chile_confirm_title)) },
            text = { Text(stringResource(R.string.lora_chile_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.applyChileMeshDefaults()
                    showChileConfirm = false
                }) { Text(stringResource(R.string.lora_chile_confirm_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showChileConfirm = false }) {
                    Text(stringResource(R.string.lora_cancel))
                }
            }
        )
    }
}

private const val FLASHER_URL = "https://oshi-messenger.com/lora/flash/"

/** iOS gives a stuck connect 20 seconds before calling it a failure (`:83`). */
private const val CONNECT_TIMEOUT_MS = 20_000L

private val GUIDE_STEPS = listOf(
    R.string.lora_guide_step1,
    R.string.lora_guide_step2,
    R.string.lora_guide_step3,
    R.string.lora_guide_step4,
    R.string.lora_guide_step5
)

// =============================================================================
// Rows and shared pieces (used by LoRaSettingsScreen too)
// =============================================================================

@Composable
private fun DiscoveredNodeRow(
    node: LoRaDiscoveredUi,
    spinning: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(node.name.ifBlank { stringResource(R.string.lora_node_unnamed) })
            // Words, not dBm. "-73" tells the person holding the phone nothing.
            Text(
                stringResource(signalWordRes(node.rssi)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (spinning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RegionPickerRow(selected: LoRaRegion?, onSelect: (LoRaRegion) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        LoRaPickerRow(
            label = stringResource(R.string.lora_region_picker),
            value = selected?.displayName ?: stringResource(R.string.lora_region_unset),
            onClick = { open = true }
        )
        OshiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // UNSET is deliberately absent: it is a state a node arrives in, not
            // one a user chooses. iOS `LoRaRegion.swift:97-99`.
            LoRaRegion.selectable.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.displayName) },
                    onClick = {
                        onSelect(r)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetPickerRow(selected: LoRaModemPreset, onSelect: (LoRaModemPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        LoRaPickerRow(
            label = stringResource(R.string.lora_preset_picker),
            value = stringResource(presetNameRes(selected)),
            onClick = { open = true }
        )
        OshiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LoRaModemPreset.entries.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(presetNameRes(p)))
                            Text(
                                stringResource(presetDetailRes(p)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onSelect(p)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PairingPickerRow(
    selected: LoRaBluetoothPairing,
    onSelect: (LoRaBluetoothPairing) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        LoRaPickerRow(
            label = stringResource(R.string.lora_pairing_picker),
            value = stringResource(pairingNameRes(selected)),
            onClick = { open = true }
        )
        OshiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LoRaBluetoothPairing.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(stringResource(pairingNameRes(m))) },
                    onClick = {
                        onSelect(m)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun LoRaPickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A label/value line. `value` is expected to already be "—" when unknown. */
@Composable
internal fun LoRaValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NodeNameSection(
    current: com.oshi.messenger.ui.viewmodel.LoRaMeshNodeUi?,
    onSave: (String, String) -> Boolean
) {
    // Pre-remplis une seule fois, quand le nœud se fait connaitre: sinon le
    // NodeInfo qui arrive une seconde plus tard ecraserait ce que l'utilisateur
    // vient de taper.
    var longName by remember(current?.num) { mutableStateOf(current?.longName.orEmpty()) }
    var shortName by remember(current?.num) { mutableStateOf(current?.shortName.orEmpty()) }
    var saved by remember { mutableStateOf(false) }

    LoRaSection(
        header = stringResource(R.string.lora_name_section),
        footer = stringResource(R.string.lora_name_footer)
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = longName,
            onValueChange = { longName = it },
            label = { Text(stringResource(R.string.lora_name_long)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = shortName,
            // Coupe PENDANT la frappe, pas a l'enregistrement: laisser taper
            // cinq caracteres puis en jeter deux en silence est la version
            // desagreable du meme plafond.
            onValueChange = { shortName = LoRaFormat.clampShortName(it) },
            label = { Text(stringResource(R.string.lora_name_short)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            stringResource(R.string.lora_name_short_budget,
                           shortName.toByteArray(Charsets.UTF_8).size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(
            onClick = { saved = onSave(longName, shortName) },
            enabled = longName.isNotBlank()
        ) {
            Text(stringResource(R.string.lora_name_save))
        }
        if (saved) {
            Text(
                stringResource(R.string.lora_name_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The channel section: a name, an optional key, and one button that writes both.
 *
 * Both fields empty is NOT an empty form, it is the stock LongFast channel, so
 * the button stays enabled on an empty name - that is the "put me back on the
 * normal mesh" case. [LoRa channel 2026-10-26]
 */
@Composable
private fun ChannelSection(
    name: String,
    key: String,
    keyInvalid: Boolean,
    written: Boolean,
    lastWritten: String,
    onName: (String) -> Unit,
    onKey: (String) -> Unit,
    onApply: () -> Unit
) {
    LoRaSection(
        header = stringResource(R.string.lora_channel_section),
        footer = stringResource(R.string.lora_channel_footer)
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name,
            // Clamped in the view model WHILE TYPING: the firmware's name field
            // is 11 BYTES, and letting a 12th be typed only to drop it on write
            // is the unpleasant version of the same ceiling.
            onValueChange = onName,
            label = { Text(stringResource(R.string.lora_channel_name)) },
            placeholder = { Text(LoRaChannel.DEFAULT_NAME) },
            supportingText = {
                Text(
                    stringResource(
                        R.string.lora_channel_name_budget,
                        name.toByteArray(Charsets.UTF_8).size,
                        LoRaChannel.NAME_MAX_BYTES
                    )
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = key,
            onValueChange = onKey,
            label = { Text(stringResource(R.string.lora_channel_key)) },
            placeholder = { Text(stringResource(R.string.lora_channel_key_default)) },
            isError = keyInvalid,
            supportingText = {
                if (keyInvalid) {
                    Text(
                        stringResource(R.string.lora_channel_key_invalid),
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(stringResource(R.string.lora_channel_key_hint))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onApply,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lora_channel_apply))
        }
        if (written) {
            Text(
                stringResource(R.string.lora_channel_written, LoRaChannel.displayName(name)),
                style = MaterialTheme.typography.bodySmall,
                color = OSHIGreen,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else if (lastWritten.isNotEmpty()) {
            // "What this phone asked for last time". The node's OWN answer now
            // has a home of its own in `NodeChannelsSection` —
            // __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ — so this line no longer
            // has to stand in for it.
            Text(
                stringResource(R.string.lora_channel_last, lastWritten),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * __PUBLIC_CHANNELS_2026_11_04__ Three sections that together make "see and join
 * public channels" true: what the node is on, joining a shared link, and handing
 * your own mesh to someone else.
 *
 * Why a link and not a name-and-key form (there is already one of those, just
 * above): nobody shares a public channel as a name and a base64 key. Every
 * community mesh, every regional channel, every "join us" post is a
 * `meshtastic.org/e/#` link and a QR code of it. Without the link format, joining
 * the mesh for your city meant reading 32 characters of base64 off a screenshot
 * by hand, or installing the Meshtastic app to import it and coming back.
 */
@Composable
private fun NodeChannelsSection(
    channels: List<com.oshi.messenger.service.lora.LoRaPacketParser.NodeChannel>,
    onOpen: ((String) -> Unit)? = null,
) {
    LoRaSection(
        header = stringResource(R.string.lora_channels_section),
        footer = stringResource(R.string.lora_channels_footer)
    ) {
        if (channels.isEmpty()) {
            // Said plainly rather than left blank: an empty list here means the
            // handshake has not happened, not that the node has no channels, and
            // those read identically if nobody says which it is.
            Text(
                stringResource(R.string.lora_channels_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@LoRaSection
        }
        channels.forEach { channel ->
            // The key the room is filed under, built by the SAME function the
            // receive path uses — so the thread this opens is the thread the
            // broadcasts arrive in, by construction rather than by coincidence.
            val channelKey = com.oshi.messenger.service.lora.LoRaChannelThread.keyFor(
                channel.index,
                channel.name.trim().ifEmpty { null },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onOpen != null) {
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpen(channelKey) }
                                // 48 dp: a row that is now a control has to clear
                                // the touch-target floor, which 6 dp of padding
                                // around one line of text does not.
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 4.dp)
                        } else Modifier
                    )
                    .padding(vertical = 6.dp),
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    Modifier.size(18.dp),
                    tint = if (channel.isPrimary) OSHIGreen
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(channel.displayName, style = MaterialTheme.typography.bodyMedium)
                    // The key's NATURE, not the key. A user in a protest needs to
                    // know "anyone can read this" at a glance, and the public
                    // default key is exactly that despite the padlock other apps
                    // draw next to it.
                    val keyLabel = when {
                        channel.psk.isEmpty() || (channel.psk.size == 1 && channel.psk[0] == 0.toByte()) ->
                            stringResource(R.string.lora_channels_no_key)
                        channel.psk.size == 1 && channel.psk[0] == 1.toByte() ->
                            stringResource(R.string.lora_channels_public_key)
                        else -> stringResource(R.string.lora_channels_private_key)
                    }
                    Text(
                        keyLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(
                        if (channel.isPrimary) R.string.lora_channels_primary
                        else R.string.lora_channels_secondary
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The affordance. A row that is tappable and says nothing about it
                // is the fault moved, not removed — the same reason the group list
                // got a visible button rather than keeping the long-press alone.
                if (onOpen != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinChannelSection(
    link: String,
    invalid: Boolean,
    parsed: com.oshi.messenger.service.lora.LoRaChannelSet.Parsed?,
    joined: String?,
    onLink: (String) -> Unit,
    onJoin: () -> Unit,
) {
    LoRaSection(
        header = stringResource(R.string.lora_join_section),
        footer = stringResource(R.string.lora_join_footer)
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = link,
            onValueChange = onLink,
            label = { Text(stringResource(R.string.lora_join_label)) },
            placeholder = { Text("https://meshtastic.org/e/#") },
            isError = invalid,
            supportingText = {
                when {
                    invalid -> Text(
                        stringResource(R.string.lora_join_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Shown BEFORE the write, because a channel link is opaque:
                    // the user cannot tell what they are about to join by
                    // looking at it, and joining takes the radio off its current
                    // mesh.
                    parsed != null -> {
                        val primary = parsed.primary?.displayName ?: ""
                        val extra = parsed.secondaries.size
                        Text(
                            if (extra == 0)
                                stringResource(R.string.lora_join_preview_one, primary)
                            else
                                stringResource(R.string.lora_join_preview_many, primary, extra)
                        )
                    }
                    else -> Text(stringResource(R.string.lora_join_replaces))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onJoin,
            enabled = parsed != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lora_join_apply))
        }
        if (joined != null) {
            Text(
                stringResource(R.string.lora_join_done, joined),
                style = MaterialTheme.typography.bodySmall,
                color = OSHIGreen,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ShareChannelSection(url: String?) {
    if (url == null) return
    val context = LocalContext.current
    var copied by remember(url) { mutableStateOf(false) }
    LoRaSection(
        header = stringResource(R.string.lora_share_section),
        footer = stringResource(R.string.lora_share_footer)
    ) {
        // The link itself is shown, not hidden behind the button: it contains the
        // channel key, and someone about to hand it out should see what they are
        // handing out.
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("OSHI LoRa channel", url))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lora_share_copy))
        }
        if (copied) {
            Text(
                stringResource(R.string.lora_share_copied),
                style = MaterialTheme.typography.bodySmall,
                color = OSHIGreen,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// __LORA_HERO_2026_11_04__ / __LORA_NODE_LIST_2026_11_04__
//
// WHAT WAS WRONG
//
// This screen was ten stacked form sections — discovery, region, name, channel,
// channels, join, share, pairing, routing, buy — and it read as a settings page
// because that is what it was. Two consequences:
//
//  1. There was NO LIST OF NODES anywhere in the app. The only trace of a mesh
//     was `nodes.size` printed as a number in a value row, plus pins on the map.
//     So "which radios can I hear, how strong, how far, how long ago" had no
//     answer on any screen, even though `LoRaFormat.signalBars` and
//     `estimatedRangeKm` had been sitting there the whole time. The missing
//     piece was only ever the screen.
//  2. The thing a user opens this screen to know — am I connected, and to what —
//     was somewhere in the middle of the stack, below the forms.
//
// WHAT THIS IS
//
// A status card first, then the radios, then everything else behind one
// disclosure. Nothing was deleted: the configuration sections are the same
// composables, moved under `lora_configure_section` and collapsed by default
// (progressive disclosure — complex options revealed on request rather than
// piled up front).
//
// Design notes that are decisions, not taste:
//
//  - Status is a DOT PLUS WORDS, never the dot alone. Colour-only state fails
//    for the ~8% of men with a colour vision deficiency, and this particular
//    state ("can I get a message out of here?") is the one worth being literal
//    about.
//  - Signal is BARS PLUS THE dBm FIGURE, for the same reason in reverse: four
//    bars is a shape, and a shape alone is no better than a colour alone. The
//    figure also carries the a11y label.
//  - Distance is a RANGE and labelled as an estimate when it comes from RSSI.
//    `estimatedRangeKm` returns a band on purpose; collapsing it to one number
//    would be false precision someone might act on in the field.
//  - Figures are tabular so the column does not jitter as values change.
//  - Existing tokens only: `OSHIGreen` for live, `onSurfaceVariant` for idle,
//    `error` for refused. No new palette — this screen lives inside the app's
//    theme and `ChatPaletteContrastTest` guards the one that matters.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Four signal bars, drawn from the bucket `LoRaFormat.signalBars` already returns.
 *
 * `internal` rather than private so the map's node card uses THIS one. A second
 * copy would be a second place for "how many bars is -78 dBm" to drift, which is
 * exactly what `LoRaFormat.signalBars` exists to prevent one level down.
 */
@Composable
internal fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (i <= bars) OSHIGreen
                        // Not invisible: the EMPTY bars are what make it read as
                        // "1 of 4" rather than "one bar, and who knows out of what".
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    )
            )
        }
    }
}

/**
 * The one tap that opens the settings this screen used to lead with.
 *
 * A row rather than a header, with a chevron that turns, because the affordance
 * has to be obvious: a collapsed section nobody can see how to open is worse
 * than a flat list.
 */
@Composable
private fun ConfigureDisclosureRow(open: Boolean, onToggle: () -> Unit) {
    LoRaCard {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .semantics {
                    // Announced as expandable, not as a mystery button.
                    stateDescription = if (open) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.lora_configure_section),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    if (open) R.string.lora_configure_hide else R.string.lora_configure_show
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Default.ChevronRight,
                null,
                Modifier
                    .size(20.dp)
                    .rotate(if (open) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every radio this node has heard, newest first. */
@Composable
private fun LoRaNodesSection(
    nodes: List<LoRaMeshNodeUi>,
    myNodeNum: Long,
    nowMillis: Long,
) {
    LoRaSection(
        header = stringResource(R.string.lora_nodes_section),
        footer = stringResource(R.string.lora_nodes_footer),
    ) {
        if (nodes.isEmpty()) {
            Text(
                stringResource(R.string.lora_nodes_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@LoRaSection
        }
        // Most recently heard first: in the field the useful question is "who is
        // near me now", and a list ordered by node number answers nothing.
        nodes.sortedByDescending { it.lastHeardMillis }.forEachIndexed { index, node ->
            if (index > 0) Divider(Modifier.padding(vertical = 2.dp))
            LoRaNodeRow(node, isMe = node.num == myNodeNum, nowMillis = nowMillis)
        }
    }
}

@Composable
private fun LoRaNodeRow(node: LoRaMeshNodeUi, isMe: Boolean, nowMillis: Long) {
    val name = node.longName.ifEmpty { node.shortName.ifEmpty { node.userId } }
    val bars = node.rssi?.let { LoRaFormat.signalBars(it) } ?: 0
    val signalWord = node.rssi?.let { LoRaFormat.signalWord(it).name } ?: ""

    Row(
        Modifier
            .fillMaxWidth()
            // 48 dp: the Material touch-target floor. The row is not tappable
            // today, but a cramped row is unreadable as well as untappable.
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (isMe) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.lora_nodes_me_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = OSHIGreen,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                nodeDetailLine(node, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (node.rssi != null) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                SignalBars(
                    bars,
                    Modifier.semantics {
                        contentDescription = "Signal $bars of 4, $signalWord"
                    },
                )
                Spacer(Modifier.height(2.dp))
                // The figure beside the bars: a shape alone is no more readable
                // than a colour alone.
                Text(
                    LoRaFormat.dbm(node.rssi),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Direct · 2 min ago · ~0.4–1.2 km · On the map" — only the parts we actually
 * know.
 *
 * Built by joining the pieces that exist rather than printing placeholders: most
 * boards have no GPS (a Heltec V3 has none), so a row that always showed a
 * distance would be showing a guess as a fact.
 */
@Composable
private fun nodeDetailLine(node: LoRaMeshNodeUi, nowMillis: Long): String {
    val parts = mutableListOf<String>()

    node.hopsAway?.let { hops ->
        parts += if (hops == 0) stringResource(R.string.lora_nodes_direct)
                 else stringResource(R.string.lora_nodes_hops, hops)
    }
    LoRaFormat.lastHeardBucket(node.lastHeardMillis, nowMillis)?.let { bucket ->
        parts += when (bucket) {
            LoRaFormat.LastHeard.Now -> stringResource(R.string.lora_nodes_now)
            is LoRaFormat.LastHeard.Minutes -> stringResource(R.string.lora_nodes_minutes, bucket.value)
            is LoRaFormat.LastHeard.Hours -> stringResource(R.string.lora_nodes_hours, bucket.value)
            is LoRaFormat.LastHeard.Days -> stringResource(R.string.lora_nodes_days, bucket.value)
        }
    }
    if (node.hasPosition) {
        parts += stringResource(R.string.lora_nodes_on_map)
    } else {
        // Only when there is no real position. A node that reports where it is
        // does not need its distance guessed from signal strength.
        LoRaFormat.estimatedRangeKm(node.rssi)?.let { (near, far) ->
            parts += stringResource(
                R.string.lora_nodes_range_estimate,
                String.format(java.util.Locale.getDefault(), "%.1f", near),
                String.format(java.util.Locale.getDefault(), "%.1f", far),
            )
        }
    }
    return parts.joinToString("  ·  ")
}

@Composable
private fun RoutingSection(radioReady: Boolean) {
    val context = LocalContext.current
    var preferRadio by remember {
        mutableStateOf(com.oshi.messenger.service.TransportPreferences.preferRadioOverRelay(context))
    }
    // Held in the STORED sense (disabled), like the preference itself, so that
    // reading this function never requires inverting a boolean in your head.
    var internetSendingDisabled by remember {
        mutableStateOf(!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(context))
    }

    LoRaSection(header = stringResource(R.string.lora_routing_section)) {
        RoutingSwitchRow(
            title = stringResource(R.string.lora_routing_prefer_radio),
            note = stringResource(R.string.lora_routing_prefer_radio_note),
            checked = preferRadio,
            onChange = {
                preferRadio = it
                com.oshi.messenger.service.TransportPreferences.setPreferRadioOverRelay(context, it)
            }
        )
        RoutingSwitchRow(
            title = stringResource(R.string.lora_routing_no_internet),
            note = stringResource(R.string.lora_routing_no_internet_note),
            checked = internetSendingDisabled,
            onChange = {
                internetSendingDisabled = it
                com.oshi.messenger.service.TransportPreferences
                    .setInternetAllowedForSending(context, !it)
            }
        )
        // The state someone WILL get into: switch on, no node paired, then
        // wonder why nothing sends. Say it here rather than let them work it
        // out from a column of undelivered messages.
        if (internetSendingDisabled && !radioReady) {
            WarningLine(stringResource(R.string.lora_routing_no_route_warning))
        }
    }
}

@Composable
private fun RoutingSwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun WarningLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = OSHIOrange)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = OSHIOrange)
    }
}

@Composable
internal fun LoRaCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun LoRaSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        LoRaCard(content = content)
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
            )
        }
    }
}

// =============================================================================
// Copy mapping — the enums stay resource-free so the radio layer holds no UI text
// =============================================================================

internal fun signalWordRes(rssi: Int): Int = when (LoRaFormat.signalWord(rssi)) {
    LoRaSignalWord.EXCELLENT -> R.string.lora_signal_excellent
    LoRaSignalWord.GOOD -> R.string.lora_signal_good
    LoRaSignalWord.FAIR -> R.string.lora_signal_fair
    LoRaSignalWord.WEAK -> R.string.lora_signal_weak
}

internal fun presetNameRes(p: LoRaModemPreset): Int = when (p) {
    LoRaModemPreset.LONG_FAST -> R.string.lora_preset_longfast
    LoRaModemPreset.LONG_SLOW -> R.string.lora_preset_longslow
}

internal fun presetDetailRes(p: LoRaModemPreset): Int = when (p) {
    LoRaModemPreset.LONG_FAST -> R.string.lora_preset_longfast_detail
    LoRaModemPreset.LONG_SLOW -> R.string.lora_preset_longslow_detail
}

internal fun pairingNameRes(m: LoRaBluetoothPairing): Int = when (m) {
    LoRaBluetoothPairing.RANDOM_PIN -> R.string.lora_pairing_pin
    LoRaBluetoothPairing.NO_PIN -> R.string.lora_pairing_nopin
}

internal fun pairingDetailRes(m: LoRaBluetoothPairing): Int = when (m) {
    LoRaBluetoothPairing.RANDOM_PIN -> R.string.lora_pairing_pin_detail
    LoRaBluetoothPairing.NO_PIN -> R.string.lora_pairing_nopin_detail
}

@Composable
private fun statusTitle(state: LoRaConnState, needsRegion: Boolean): String = when {
    state == LoRaConnState.CONNECTING -> stringResource(R.string.lora_status_connecting)
    state == LoRaConnState.CONFIGURING -> stringResource(R.string.lora_status_configuring)
    state != LoRaConnState.READY -> stringResource(R.string.lora_status_searching)
    needsRegion -> stringResource(R.string.lora_status_needs_region)
    else -> stringResource(R.string.lora_status_ready)
}

@Composable
private fun statusDetail(
    state: LoRaConnState,
    needsRegion: Boolean,
    transmitEnabled: Boolean,
    connectingName: String?
): String = when {
    state == LoRaConnState.CONNECTING -> stringResource(
        R.string.lora_status_connecting_detail,
        connectingName ?: stringResource(R.string.lora_node_unnamed)
    )
    state == LoRaConnState.CONFIGURING -> stringResource(R.string.lora_status_configuring_detail)
    state != LoRaConnState.READY -> stringResource(R.string.lora_status_searching_detail)
    needsRegion -> stringResource(R.string.lora_status_needs_region_detail)
    !transmitEnabled -> stringResource(R.string.lora_status_tx_off_detail)
    else -> stringResource(R.string.lora_status_ready_detail)
}
