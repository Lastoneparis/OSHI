package com.oshi.messenger.ui.screens

import androidx.compose.foundation.layout.*
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.ui.viewmodel.ScheduledCallViewModel
import com.oshi.messenger.service.ScheduledCallManager
import com.oshi.messenger.ui.theme.OSHIGreen
import java.text.SimpleDateFormat
import java.util.*

/**
 * ScheduledCallScreen — view + create scheduled calls (task 3.1.6)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledCallScreen(
    onBack: () -> Unit,
    viewModel: ScheduledCallViewModel = hiltViewModel()
) {
    val calls by viewModel.scheduledCalls.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.key_backup_hide))
                    }
                },
                title = { Text(stringResource(R.string.scheduled_calls_screen_title)) },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.scheduled_calls_schedule))
                    }
                }
            )
        }
    ) { padding ->
        if (calls.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.EventAvailable,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.scheduled_calls_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showCreateDialog = true }) {
                        Text(stringResource(R.string.scheduled_calls_schedule))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(calls, key = { it.id }) { event ->
                    ScheduledCallCard(
                        event = event,
                        onAccept = { viewModel.respond(event.id, accept = true) },
                        onDecline = { viewModel.respond(event.id, accept = false) },
                        onCancel = { viewModel.cancel(event.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateScheduledCallDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { peerKey, time, isVideo ->
                viewModel.create(peerKey, time, isVideo)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun ScheduledCallCard(
    event: ScheduledCallManager.ScheduledCallEvent,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    val statusColor = when (event.status) {
        ScheduledCallManager.CallEventStatus.CONFIRMED -> OSHIGreen
        ScheduledCallManager.CallEventStatus.CANCELLED -> MaterialTheme.colorScheme.error
        ScheduledCallManager.CallEventStatus.COMPLETED -> Color(0xFF9E9E9E)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (event.isVideoCall) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = event.peerPublicKey.take(12) + "...",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = event.status.name,
                        fontSize = 11.sp,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = dateFormat.format(Date(event.scheduledTime)),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action buttons for pending invites
            if (event.status == ScheduledCallManager.CallEventStatus.PENDING &&
                event.myResponse == ScheduledCallManager.ResponseStatus.PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.scheduled_calls_decline))
                    }
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.scheduled_calls_accept))
                    }
                }
            } else if (event.status == ScheduledCallManager.CallEventStatus.PENDING ||
                event.status == ScheduledCallManager.CallEventStatus.CONFIRMED) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun CreateScheduledCallDialog(
    onDismiss: () -> Unit,
    onCreate: (peerKey: String, time: Long, isVideo: Boolean) -> Unit
) {
    var peerKey by remember { mutableStateOf("") }
    var isVideo by remember { mutableStateOf(false) }

    // Simple date/time picker using text inputs for now
    val cal = remember { Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) } }
    var hour by remember { mutableStateOf(cal.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(cal.get(Calendar.MINUTE)) }
    var dayOffset by remember { mutableStateOf(0) } // 0=today, 1=tomorrow, etc.

    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scheduled_calls_schedule_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = peerKey,
                        onValueChange = { peerKey = it },
                        label = { Text(stringResource(R.string.scheduled_calls_peer_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.scheduled_calls_day_offset), fontSize = 13.sp)
                    IconButton(onClick = { if (dayOffset > 0) dayOffset-- }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                    }
                    Text(if (dayOffset == 0) stringResource(R.string.scheduled_calls_today) else stringResource(R.string.scheduled_calls_day_plus, dayOffset), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { dayOffset++ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.scheduled_calls_hour), fontSize = 13.sp)
                    IconButton(onClick = { hour = (hour - 1 + 24) % 24 }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                    }
                    Text(String.format("%02d:%02d", hour, minute), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { hour = (hour + 1) % 24 }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = isVideo, onCheckedChange = { isVideo = it })
                    Text(stringResource(R.string.scheduled_calls_video))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (peerKey.isNotBlank()) {
                        val scheduledCal = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, dayOffset)
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onCreate(peerKey.trim(), scheduledCal.timeInMillis, isVideo)
                    }
                }
            ) {
                Text(stringResource(R.string.scheduled_messages_schedule))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
