package com.oshi.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.oshi.messenger.R
import com.oshi.messenger.devsync.DevSyncManager
import com.oshi.messenger.devsync.DevSyncUiState
import com.oshi.messenger.network.v2.devsync.DevSyncSession
import com.oshi.messenger.network.v2.devsync.LinkedDevice
import com.oshi.messenger.network.v2.devsync.PairingRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** __DEVSYNC_DIRECT_2026_09_22__ Settings → Linked devices (design §4.3–§4.4). */
@HiltViewModel
class LinkedDevicesViewModel @Inject constructor(private val manager: DevSyncManager) : ViewModel() {
    val state: StateFlow<DevSyncUiState> = manager.state
    fun approve(peerId: String, allow: Boolean) = manager.approve(peerId, allow)
    fun revoke(peerId: String) = manager.revoke(peerId)
    fun syncNow() = manager.syncNow()
    fun setOverride(on: Boolean) = manager.setLocalOverride(if (on) true else null)
    fun refresh() = manager.refresh(force = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    onBack: () -> Unit,
    viewModel: LinkedDevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var toRemove by remember { mutableStateOf<LinkedDevice?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
                },
                title = { Text(stringResource(R.string.devsync_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(if (state.enabled) R.string.devsync_status_on else R.string.devsync_status_off),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Release builds ship with the server gate `devsync_enabled` OFF; the debug
            // override would let a store user force the feature on against it. Shown in
            // release only to someone who already set it, so they can clear it.
            if (com.oshi.messenger.BuildConfig.DEBUG || state.localOverride != null) item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.devsync_debug_override), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.devsync_debug_override_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.localOverride == true, onCheckedChange = { viewModel.setOverride(it) })
                }
            }
            state.deviceId?.let { id ->
                item {
                    Text(stringResource(R.string.devsync_this_device) + ": " + state.deviceName, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.devsync_device_id, id.take(8)), style = MaterialTheme.typography.bodySmall)
                }
            }
            state.lastAlert?.let { alert ->
                item {
                    val (kind, name) = alert.substringBefore(':') to alert.substringAfter(':')
                    val text = when (kind) {
                        "unapproved-device" -> stringResource(R.string.devsync_alert_unapproved, name)
                        "device-linked" -> stringResource(R.string.devsync_alert_linked, name)
                        else -> null
                    }
                    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.pending.isNotEmpty()) {
                item { Text(stringResource(R.string.devsync_pending_title), style = MaterialTheme.typography.titleMedium) }
                items(state.pending, key = { it.peerId }) { req -> PendingCard(req, viewModel) }
            }
            item { Text(stringResource(R.string.devsync_linked_title), style = MaterialTheme.typography.titleMedium) }
            if (state.linked.isEmpty()) {
                item { Text(stringResource(R.string.devsync_none_linked), style = MaterialTheme.typography.bodySmall) }
            }
            items(state.linked, key = { it.deviceId }) { d ->
                val live = state.sessions.firstOrNull { it.peerId == d.deviceId && it.state == DevSyncSession.State.SYNC }
                OshiCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${d.name} · ${d.platform}", fontWeight = FontWeight.SemiBold)
                            val last = d.lastSyncAtMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                                ?: stringResource(R.string.devsync_never)
                            Text(
                                if (live != null) stringResource(R.string.devsync_syncing_now, live.transport)
                                else stringResource(R.string.devsync_last_sync, last),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { toRemove = d }) { Text(stringResource(R.string.devsync_remove)) }
                    }
                }
            }
            item {
                Button(onClick = { viewModel.syncNow() }, enabled = state.enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.devsync_sync_now))
                }
            }
        }
    }

    toRemove?.let { d ->
        OshiAlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text(stringResource(R.string.devsync_remove_confirm_title, d.name)) },
            text = { Text(stringResource(R.string.devsync_remove_confirm_body)) },
            confirmButton = { TextButton(onClick = { viewModel.revoke(d.deviceId); toRemove = null }) { Text(stringResource(R.string.devsync_remove)) } },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text(stringResource(R.string.devsync_cancel)) } },
        )
    }
}

@Composable
private fun PendingCard(req: PairingRequest, viewModel: LinkedDevicesViewModel) {
    OshiCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (req.thisDeviceIsNew) stringResource(R.string.devsync_waiting_body)
                else stringResource(R.string.devsync_pending_body, req.name, req.platform),
                style = MaterialTheme.typography.bodyMedium,
            )
            // §4.3: the same six digits on both screens.
            Text(
                req.code.chunked(3).joinToString(" "),
                fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (req.peerApproved) Text(stringResource(R.string.devsync_peer_approved), style = MaterialTheme.typography.bodySmall)
            Row {
                OutlinedButton(onClick = { viewModel.approve(req.peerId, false) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.devsync_deny))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { viewModel.approve(req.peerId, true) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.devsync_allow))
                }
            }
        }
    }
}
