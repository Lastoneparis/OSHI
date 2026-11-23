package com.oshi.messenger.ui.screens

import androidx.compose.foundation.clickable
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.oshi.messenger.R
import com.oshi.messenger.service.lora.MeshCoreManager
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed

/**
 * The MeshCore radio block of the LoRa settings screen — port of `meshCoreSection` in iOS
 * MeshtasticSettingsView.swift: status, scan and pair, forget, and (with both radios ready) the
 * Meshtastic ↔ MeshCore bridge toggle. Reads [MeshCoreManager] directly; it has no view-model state
 * worth a second copy.
 */
@Composable
internal fun MeshCoreSection(meshtasticReady: Boolean, bluetoothOn: Boolean) {
    val context = LocalContext.current
    val meshCore = remember { MeshCoreManager.getInstance(context.applicationContext) }
    val state by meshCore.state.collectAsState()
    val connectedName by meshCore.connectedName.collectAsState()
    val discovered by meshCore.discovered.collectAsState()
    val channelIdx by meshCore.channelIdx.collectAsState()
    val lastError by meshCore.lastError.collectAsState()
    val crossBridge by meshCore.crossBridgeEnabled.collectAsState()
    var showForget by remember { mutableStateOf(false) }

    val ready = state == MeshCoreManager.ConnState.READY && channelIdx != null
    val statusLine = when (state) {
        MeshCoreManager.ConnState.READY ->
            stringResource(if (ready) R.string.meshcore_ready else R.string.meshtastic_state_configuring)
        MeshCoreManager.ConnState.CONNECTING, MeshCoreManager.ConnState.CONFIGURING ->
            stringResource(R.string.meshtastic_state_configuring)
        MeshCoreManager.ConnState.SCANNING -> stringResource(R.string.meshtastic_searching)
        MeshCoreManager.ConnState.OFF ->
            if (meshCore.isPaired) stringResource(R.string.meshtastic_state_connecting) else ""
    }

    Column {
        LoRaSection(
            header = stringResource(R.string.meshcore_title),
            footer = stringResource(R.string.meshcore_footer),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Hub, null, Modifier.size(28.dp),
                    tint = if (ready) OSHIGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        connectedName ?: stringResource(R.string.meshcore_none),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (statusLine.isNotEmpty()) {
                        Text(
                            statusLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state == MeshCoreManager.ConnState.CONNECTING || state == MeshCoreManager.ConnState.CONFIGURING) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (lastError == MeshCoreManager.ERROR_NO_FREE_CHANNEL) {
                WarningLine(stringResource(R.string.meshcore_no_free_channel))
            }
            Divider(Modifier.padding(vertical = 8.dp))
            if (connectedName != null || meshCore.isPaired) {
                TextButton(onClick = { showForget = true }) {
                    Icon(Icons.Default.Cancel, null, Modifier.size(18.dp), tint = OSHIRed)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.meshcore_forget), color = OSHIRed)
                }
            } else if (bluetoothOn) {
                val scanning = state == MeshCoreManager.ConnState.SCANNING
                TextButton(onClick = { if (scanning) meshCore.stopScan() else meshCore.startScan() }) {
                    Icon(if (scanning) Icons.Default.Stop else Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (scanning) R.string.meshtastic_stop_scan else R.string.meshcore_scan))
                }
                if (scanning && discovered.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.meshtastic_searching),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                discovered.forEach { d ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { meshCore.connect(d.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name)
                            Text(
                                stringResource(R.string.meshtastic_rssi_dbm, d.rssi),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(stringResource(R.string.meshtastic_pair), color = OSHIPurple)
                    }
                }
            }
        }

        if (ready && meshtasticReady) {
            Spacer(Modifier.size(16.dp))
            LoRaSection(footer = stringResource(R.string.meshcore_bridge_footer)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.meshcore_bridge), modifier = Modifier.weight(1f))
                    Switch(checked = crossBridge, onCheckedChange = { meshCore.setCrossBridgeEnabled(it) })
                }
            }
        }
    }

    if (showForget) {
        OshiAlertDialog(
            onDismissRequest = { showForget = false },
            title = { Text(stringResource(R.string.meshcore_forget)) },
            confirmButton = {
                TextButton(onClick = { meshCore.forget(); showForget = false }) {
                    Text(stringResource(R.string.meshcore_forget), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForget = false }) { Text(stringResource(R.string.lora_cancel)) }
            },
        )
    }
}
