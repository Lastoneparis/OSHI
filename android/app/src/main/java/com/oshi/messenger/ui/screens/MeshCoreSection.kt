package com.oshi.messenger.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.service.lora.MeshCore
import com.oshi.messenger.service.lora.MeshCoreLink
import com.oshi.messenger.service.lora.MeshCoreManager
import com.oshi.messenger.service.lora.MeshCoreThread
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.theme.OshiAlertDialog

/** Everything the MeshCore block of the radio settings shows: a plain value, so it renders in a test. */
internal data class MeshCoreSectionUi(
    val state: MeshCoreManager.ConnState = MeshCoreManager.ConnState.OFF,
    val radioName: String? = null,
    val paired: Boolean = false,
    val awaitingPin: Boolean = false,
    val error: String? = null,
    val bluetoothOn: Boolean = true,
    /** The "Nearby radios" list is hidden (a Meshtastic radio is connected): this block lists MeshCore radios itself. */
    val ownScanList: Boolean = false,
    val discovered: List<MeshCoreManager.Device> = emptyList(),
    val chatReady: Boolean = false,
    val bridgeReady: Boolean = false,
    val selfName: String? = null,
    val channels: List<MeshCore.ChannelInfo> = emptyList(),
    val contacts: List<MeshCore.Contact> = emptyList(),
    val heardAt: Map<String, Long> = emptyMap(),
    val now: Long = System.currentTimeMillis(),
    val meshtasticReady: Boolean = false,
    val crossBridge: Boolean = true,
)

internal class MeshCoreSectionActions(
    val scan: () -> Unit = {},
    val stopScan: () -> Unit = {},
    val connect: (MeshCoreManager.Device) -> Unit = {},
    val forget: () -> Unit = {},
    /** Open (creating if needed) the thread [key] named [name]. */
    val openChat: (key: String, name: String) -> Unit = { _, _ -> },
    /** Hashtag (secret null) or private channel; returns an error to show, or null once queued. */
    val join: (name: String, secret: ByteArray?) -> String? = { _, _ -> null },
    val refreshContacts: () -> Unit = {},
    val announce: () -> Unit = {},
    val setCrossBridge: (Boolean) -> Unit = {},
)

/**
 * The MeshCore radio block of the LoRa settings screen — port of `meshCoreSection` in iOS
 * MeshtasticSettingsView.swift, grown into the MeshCore user journey: connect (with the PIN explained before
 * Android asks for it), see who your radio has heard, tap a person or channel to open the conversation, join
 * a channel, announce yourself so MeshCore users can write to you.
 */
@Composable
internal fun MeshCoreSection(
    meshtasticReady: Boolean,
    bluetoothOn: Boolean,
    /** True when the "Nearby radios" list (which shows MeshCore radios too) is hidden. */
    ownScanList: Boolean = true,
    onOpenChat: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val meshCore = remember { MeshCoreManager.getInstance(context.applicationContext) }
    val state by meshCore.state.collectAsState()
    val connectedName by meshCore.connectedName.collectAsState()
    val discovered by meshCore.discovered.collectAsState()
    val channelIdx by meshCore.channelIdx.collectAsState()
    val lastError by meshCore.lastError.collectAsState()
    val crossBridge by meshCore.crossBridgeEnabled.collectAsState()
    val chatReady by meshCore.chatReady.collectAsState()
    val channels by meshCore.channels.collectAsState()
    val contacts by meshCore.contacts.collectAsState()
    val heardAt by meshCore.heardAt.collectAsState()
    val selfName by meshCore.selfName.collectAsState()
    val awaitingPin by meshCore.awaitingPin.collectAsState()
    var pinFor by remember { mutableStateOf<MeshCoreManager.Device?>(null) }
    /** A channel just asked for: opened as soon as the radio accepts it. */
    var joining by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(meshCore) {
        meshCore.joinResults.collect { (idx, ok) ->
            val name = joining
            joining = null
            if (!ok) {
                android.widget.Toast.makeText(context, context.getString(R.string.meshcore_join_refused), android.widget.Toast.LENGTH_SHORT).show()
            } else if (name != null && meshCore.channels.value.any { it.idx == idx && it.name == name }) {
                val key = MeshCoreThread.channelKey(name)
                meshCore.requestConversation(key, name)
                onOpenChat(key)
            }
        }
    }
    LaunchedEffect(meshCore) {
        meshCore.advertResults.collect { ok ->
            android.widget.Toast.makeText(
                context, context.getString(if (ok) R.string.meshcore_announce_done else R.string.meshcore_announce_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    val ui = MeshCoreSectionUi(
        state = state, radioName = connectedName ?: meshCore.pairedName, paired = meshCore.isPaired, awaitingPin = awaitingPin,
        error = lastError, bluetoothOn = bluetoothOn, ownScanList = ownScanList, discovered = discovered,
        chatReady = chatReady, bridgeReady = state == MeshCoreManager.ConnState.READY && channelIdx != null,
        selfName = selfName, channels = channels, contacts = contacts, heardAt = heardAt,
        meshtasticReady = meshtasticReady, crossBridge = crossBridge,
    )
    MeshCoreSectionContent(
        ui,
        MeshCoreSectionActions(
            scan = { meshCore.startScan() },
            stopScan = { meshCore.stopScan() },
            connect = { pinFor = it },
            forget = { meshCore.forget() },
            openChat = { key, name -> meshCore.requestConversation(key, name); onOpenChat(key) },
            join = { name, secret ->
                val result = if (secret == null) meshCore.joinHashtagChannel(name) else meshCore.joinPrivateChannel(name, secret)
                when (result) {
                    MeshCoreLink.JoinResult.QUEUED -> {
                        val full = if (secret == null) "#" + name.trim().removePrefix("#") else name.trim()
                        // Already on the radio: no answer will come, open it now.
                        if (meshCore.channels.value.any { it.name == full }) {
                            meshCore.requestConversation(MeshCoreThread.channelKey(full), full)
                            onOpenChat(MeshCoreThread.channelKey(full))
                        } else joining = full
                        null
                    }
                    MeshCoreLink.JoinResult.NO_FREE_SLOT -> context.getString(R.string.meshcore_join_no_slot)
                    MeshCoreLink.JoinResult.NOT_READY -> context.getString(R.string.meshcore_error_not_connected)
                    MeshCoreLink.JoinResult.INVALID -> context.getString(R.string.meshcore_join_bad_name)
                }
            },
            refreshContacts = { meshCore.refreshContacts() },
            announce = { meshCore.sendSelfAdvert() },
            setCrossBridge = { meshCore.setCrossBridgeEnabled(it) },
        ),
    )

    pinFor?.let { d ->
        MeshCorePinDialog(d.name, onDismiss = { pinFor = null }, onContinue = { pinFor = null; meshCore.connect(d.id) })
    }
}

@Composable
internal fun MeshCoreSectionContent(ui: MeshCoreSectionUi, actions: MeshCoreSectionActions) {
    var showForget by remember { mutableStateOf(false) }
    /** null = no dialog; true = hashtag channel; false = private channel. */
    var joinHashtag by remember { mutableStateOf<Boolean?>(null) }
    val connected = ui.state == MeshCoreManager.ConnState.READY && ui.chatReady
    val busy = ui.awaitingPin || ui.state == MeshCoreManager.ConnState.CONNECTING ||
        ui.state == MeshCoreManager.ConnState.CONFIGURING || (ui.state == MeshCoreManager.ConnState.READY && !ui.chatReady)

    Column {
        LoRaSection(
            header = stringResource(R.string.meshcore_title),
            footer = stringResource(R.string.meshcore_footer_users),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Hub, null, Modifier.size(28.dp), tint = if (connected) OSHIGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.radioName ?: stringResource(R.string.meshcore_none),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // "Connection lost, reconnecting" below already says what a status line would.
                    val status = MeshCoreUi.statusText(ui.state, ui.awaitingPin, ui.chatReady, ui.paired)
                        .takeIf { ui.error != MeshCoreManager.ERROR_CONNECTION_LOST }
                    when {
                        connected -> Text(
                            if (ui.selfName != null) stringResource(R.string.meshcore_seen_as, ui.selfName) else stringResource(R.string.meshcore_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = OSHIGreen,
                        )
                        status != null -> Text(
                            stringResource(status),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ui.awaitingPin) meshCoreAccent() else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (ui.awaitingPin) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            MeshCoreUi.errorText(ui.error)?.let { WarningLine(stringResource(it)) }
            Divider(Modifier.padding(vertical = 8.dp))
            when {
                ui.radioName != null || ui.paired -> TextButton(onClick = { showForget = true }) {
                    Icon(Icons.Default.Cancel, null, Modifier.size(18.dp), tint = OSHIRed)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.meshcore_forget), color = OSHIRed)
                }
                !ui.bluetoothOn -> WarningLine(stringResource(R.string.meshtastic_bt_off))
                // MeshCore radios are found by the "Nearby radios" scan below and marked "MeshCore".
                !ui.ownScanList -> Text(
                    stringResource(R.string.meshcore_scan_below),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> MeshCoreScanList(ui, actions)
            }
        }

        // __MESHCORE_INTEROP_2026_11_25__ MeshCore users and channels on this radio. Tapping one opens its thread;
        // text there goes through this radio, MeshCore-encrypted, NOT OSHI end-to-end encrypted.
        if (connected) {
            Spacer(Modifier.size(16.dp))
            LoRaSection(
                header = stringResource(R.string.meshcore_contacts_title),
                footer = stringResource(R.string.meshcore_contacts_footer),
            ) {
                val people = MeshCoreThread.chatContacts(ui.contacts)
                if (people.isEmpty()) {
                    Text(
                        stringResource(R.string.meshcore_no_contacts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                people.forEach { c ->
                    val key = MeshCoreThread.contactKey(c.publicKey)
                    val name = c.name.ifBlank { MeshCoreThread.displayName(key) }
                    // Heard this session, else the contact's own advert clock when it looks real (radios without
                    // a set clock advertise 1970 or the future): never a bare key.
                    val advertMs = c.lastAdvert * 1000
                    val heard = ui.heardAt[c.publicKeyHex]
                        ?: advertMs.takeIf { it in (ui.now - 30L * 24 * 3_600_000)..(ui.now + 3_600_000) }
                    MeshCoreRow(
                        initials = name,
                        title = name,
                        subtitle = heard?.let {
                            stringResource(
                                R.string.meshcore_heard,
                                // Under a minute reads "1 minute ago", never "0 minutes ago".
                                DateUtils.getRelativeTimeSpanString(minOf(it, ui.now - 60_000), ui.now, DateUtils.MINUTE_IN_MILLIS).toString(),
                            )
                        } ?: stringResource(R.string.meshcore_contact_kind),
                        online = heard != null && ui.now - heard < 30 * 60_000L,
                        onClick = { actions.openChat(key, name) },
                    )
                }
                Divider(Modifier.padding(vertical = 4.dp))
                Row {
                    TextButton(onClick = actions.announce, colors = ButtonDefaults.textButtonColors(contentColor = meshCoreAccent())) {
                        Icon(Icons.Default.Campaign, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.meshcore_announce))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = actions.refreshContacts, colors = ButtonDefaults.textButtonColors(contentColor = meshCoreAccent())) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.meshcore_refresh_contacts), Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.size(16.dp))
            LoRaSection(
                header = stringResource(R.string.meshcore_channels_title),
                footer = stringResource(R.string.meshcore_channels_footer),
            ) {
                ui.channels.forEach { ch ->
                    val public = MeshCore.isPublicChannel(ch.name, ch.secret)
                    MeshCoreRow(
                        icon = if (public) Icons.Default.Public else Icons.Default.Lock,
                        title = ch.name,
                        subtitle = stringResource(if (public) R.string.meshcore_channel_public else R.string.meshcore_channel_private),
                        onClick = { actions.openChat(MeshCoreThread.channelKey(ch.name), ch.name) },
                    )
                }
                Divider(Modifier.padding(vertical = 4.dp))
                TextButton(onClick = { joinHashtag = true }, colors = ButtonDefaults.textButtonColors(contentColor = meshCoreAccent())) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.meshcore_join_hashtag))
                }
                TextButton(onClick = { joinHashtag = false }, colors = ButtonDefaults.textButtonColors(contentColor = meshCoreAccent())) {
                    Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.meshcore_join_private))
                }
            }
        }

        if (ui.bridgeReady && ui.meshtasticReady) {
            Spacer(Modifier.size(16.dp))
            LoRaSection(footer = stringResource(R.string.meshcore_bridge_footer)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.meshcore_bridge), modifier = Modifier.weight(1f))
                    Switch(checked = ui.crossBridge, onCheckedChange = actions.setCrossBridge)
                }
            }
        }
    }

    joinHashtag?.let { hashtag ->
        JoinChannelDialog(hashtag = hashtag, onDismiss = { joinHashtag = null }, onJoin = { name, secret ->
            actions.join(name, secret).also { if (it == null) joinHashtag = null }
        })
    }

    if (showForget) {
        OshiAlertDialog(
            onDismissRequest = { showForget = false },
            title = { Text(stringResource(R.string.meshcore_forget)) },
            confirmButton = {
                TextButton(onClick = { actions.forget(); showForget = false }) {
                    Text(stringResource(R.string.meshcore_forget), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForget = false }) { Text(stringResource(R.string.lora_cancel)) }
            },
        )
    }
}

@Composable
private fun MeshCoreScanList(ui: MeshCoreSectionUi, actions: MeshCoreSectionActions) {
    val scanning = ui.state == MeshCoreManager.ConnState.SCANNING
    TextButton(onClick = { if (scanning) actions.stopScan() else actions.scan() }, colors = ButtonDefaults.textButtonColors(contentColor = meshCoreAccent())) {
        Icon(if (scanning) Icons.Default.Stop else Icons.Default.Search, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (scanning) R.string.meshtastic_stop_scan else R.string.meshcore_scan))
    }
    if (scanning && ui.discovered.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.meshtastic_searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ui.discovered.forEach { d -> MeshCoreDeviceRow(d) { actions.connect(d) } }
}

/** One radio found by a scan, marked "MeshCore" so it is never mistaken for a Meshtastic one. */
@Composable
internal fun MeshCoreDeviceRow(d: MeshCoreManager.Device, onPair: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPair).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                FirmwareTag("MeshCore")
            }
            Text(
                stringResource(R.string.meshtastic_rssi_dbm, d.rssi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.meshtastic_pair), color = meshCoreAccent(), fontWeight = FontWeight.SemiBold)
    }
}

/** The firmware a scanned radio runs, detected from the Bluetooth service it advertises. */
@Composable
internal fun FirmwareTag(text: String) {
    val accent = meshCoreAccent()
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = accent,
        modifier = Modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun MeshCoreRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    initials: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    online: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = meshCoreAccent()
        Box(
            Modifier.size(36.dp).background(accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = accent)
            else Text((initials ?: title).take(2).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (online) Box(
                Modifier.align(Alignment.BottomEnd).size(10.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp)
                    .background(OSHIGreen, CircleShape)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Said BEFORE Android's own pairing prompt asks for a PIN nobody told the person about. */
@Composable
internal fun MeshCorePinDialog(radioName: String, onDismiss: () -> Unit, onContinue: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Hub, null, tint = meshCoreAccent()) },
        title = { Text(stringResource(R.string.meshcore_pin_title, radioName)) },
        text = { MeshCorePinExplainer() },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.meshcore_pin_continue), fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lora_cancel)) } },
    )
}

@Composable
internal fun MeshCorePinExplainer() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.meshcore_pin_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.meshcore_pin_default),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Name (and, for a private channel, the 16-byte secret) of a channel to put in a free slot. [onJoin] returns an
 * error to show, or null when the radio took the request.
 */
@Composable
private fun JoinChannelDialog(hashtag: Boolean, onDismiss: () -> Unit, onJoin: (String, ByteArray?) -> String?) {
    var name by remember { mutableStateOf(if (hashtag) "#" else "") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val badSecret = stringResource(R.string.meshcore_join_bad_secret)
    val badName = stringResource(R.string.meshcore_join_bad_name)
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (hashtag) R.string.meshcore_join_hashtag else R.string.meshcore_join_private)) },
        text = {
            JoinChannelFields(hashtag, name, { name = it; error = null }, secret, { secret = it; error = null }, error)
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                if (n.isEmpty() || n == "#") { error = badName; return@TextButton }
                if (hashtag) { error = onJoin(n, null); return@TextButton }
                val key = MeshCore.parseSecret(secret) ?: run { error = badSecret; return@TextButton }
                error = onJoin(n, key)
            }) { Text(stringResource(R.string.meshcore_join), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lora_cancel)) } },
    )
}

@Composable
internal fun JoinChannelFields(
    hashtag: Boolean,
    name: String,
    onName: (String) -> Unit,
    secret: String,
    onSecret: (String) -> Unit,
    error: String?,
) {
    Column {
        Text(
            stringResource(if (hashtag) R.string.meshcore_hashtag_hint else R.string.meshcore_private_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = name, onValueChange = onName, singleLine = true,
            label = { Text(stringResource(R.string.meshcore_channel_name)) },
            placeholder = { Text(if (hashtag) "#paris" else "") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!hashtag) {
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = secret, onValueChange = onSecret, singleLine = true,
                label = { Text(stringResource(R.string.meshcore_channel_secret)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { WarningLine(it) }
    }
}
