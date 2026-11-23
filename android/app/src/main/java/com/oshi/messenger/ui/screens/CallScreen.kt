package com.oshi.messenger.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.service.CallState
import com.oshi.messenger.service.ConnectionType
import com.oshi.messenger.service.audio.NetworkQuality
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.viewmodel.CallViewModel
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIRed
import kotlin.math.sin

@Composable
fun CallScreen(
    publicKey: String,
    isVideoCall: Boolean,
    onEnd: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val callState by viewModel.callState.collectAsState()
    val currentCall by viewModel.currentCall.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeaker by viewModel.isSpeaker.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val connectionQuality by viewModel.connectionQuality.collectAsState()
    val connectionType by viewModel.connectionType.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val isBluetoothOn by viewModel.isBluetoothOn.collectAsState()

    LaunchedEffect(publicKey) {
        viewModel.startCall(publicKey, isVideoCall)
    }

    // __BLOCKED_BY_PEER_2026_09_24__ a start refused before anything was sent (the peer told
    // us it blocks us → "Contact unavailable"): say it and leave, instead of hanging in IDLE.
    val startRefusal by viewModel.startRefusal.collectAsState()
    val refusalContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(startRefusal) {
        startRefusal?.let {
            android.widget.Toast.makeText(refusalContext, refusalContext.getString(it), android.widget.Toast.LENGTH_SHORT).show()
            onEnd()
        }
    }

    LaunchedEffect(callState) {
        if (callState == CallState.IDLE) {
            onEnd()
        }
    }

    // Quality-based background gradient
    val qualityColors = when (connectionQuality) {
        NetworkQuality.EXCELLENT -> listOf(Color(0xFF1B5E20), Color(0xFF2E7D32))
        NetworkQuality.GOOD -> listOf(Color(0xFF33691E), Color(0xFF558B2F))
        NetworkQuality.FAIR -> listOf(Color(0xFFF57F17), Color(0xFFFF8F00))
        NetworkQuality.POOR -> listOf(Color(0xFFE65100), Color(0xFFBF360C))
        NetworkQuality.VERY_POOR -> listOf(Color(0xFFB71C1C), Color(0xFFC62828))
        NetworkQuality.RECONNECTING -> listOf(Color(0xFF4A148C), Color(0xFF6A1B9A))
        NetworkQuality.DISCONNECTED -> listOf(Color(0xFF212121), Color(0xFF424242))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = if (callState == CallState.IN_CALL) qualityColors
                    else listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        // 📐 SHORT WINDOW. `Column(fillMaxSize, SpaceBetween)` with no scroll and
        // ~666 dp of fixed children (64 dp of outer padding, 48 dp of head-room, a
        // 120 dp avatar, a 28 sp name, badges, a waveform, a 56 dp control row and a
        // 72 dp end-call FAB). A landscape phone has ~320-400 dp, so the End Call
        // button and the Accept / Decline row were pushed off the bottom edge with
        // nothing to scroll them back. Same treatment as VoiceCallScreen: shrink the
        // paddings in a short window and let the column scroll.
        val compactHeight = rememberWindowSize().height == HeightClass.Compact
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (compactHeight) 16.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section - caller info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = if (compactHeight) 8.dp else 48.dp)
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val profilePic = currentCall?.peerProfilePicture
                    if (profilePic != null) {
                        val bitmap = remember(profilePic) {
                            android.graphics.BitmapFactory.decodeByteArray(profilePic, 0, profilePic.size)?.asImageBitmap()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = currentCall?.peerName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            com.oshi.messenger.ui.theme.ContactAvatar(
                                publicKey = currentCall?.peerPublicKey.orEmpty(),
                                name = currentCall?.peerName,
                                size = 120.dp,
                            )
                        }
                    } else {
                        // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was
                        // `primaryContainer` (one colour for every caller) or a
                        // white wash in-call; now the caller's own colour.
                        com.oshi.messenger.ui.theme.ContactAvatar(
                            publicKey = currentCall?.peerPublicKey.orEmpty(),
                            name = currentCall?.peerName,
                            size = 120.dp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Name
                Text(
                    text = currentCall?.peerName ?: publicKey.take(8) + "...",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (callState == CallState.IN_CALL) Color.White
                    else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Status
                Text(
                    text = when (callState) {
                        CallState.CONNECTING -> "Connecting..."
                        CallState.RINGING -> if (currentCall?.isOutgoing == true) "Ringing..." else "Incoming call"
                        CallState.IN_CALL -> formatDuration(duration)
                        CallState.ENDED -> "Call ended"
                        else -> ""
                    },
                    fontSize = 16.sp,
                    color = if (callState == CallState.IN_CALL) Color.White.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Latency display (only during active call)
                if (callState == CallState.IN_CALL && latencyMs > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${latencyMs}ms",
                        fontSize = 14.sp,
                        color = when {
                            latencyMs < 100 -> OSHIGreen
                            latencyMs < 200 -> Color(0xFFFFC107)
                            else -> OSHIRed
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Connection type badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            if (callState == CallState.IN_CALL) Color.White.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (callState == CallState.IN_CALL) Color.White
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (connectionType) {
                            ConnectionType.MESH_DIRECT -> "P2P Direct"
                            ConnectionType.MESH -> "Mesh Relay"
                            ConnectionType.VPS -> "Server Relay"
                            ConnectionType.TOR -> "Tor Hidden"
                            ConnectionType.CROSS_PLATFORM -> "Cross-Platform"
                            ConnectionType.RELAY -> "Relay Node"
                        },
                        fontSize = 12.sp,
                        color = if (callState == CallState.IN_CALL) Color.White.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Waveform visualization during active call
                if (callState == CallState.IN_CALL) {
                    Spacer(modifier = Modifier.height(32.dp))
                    WaveformVisualization()
                }
            }

            // Bottom section - controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Control buttons (only show when in call)
                if (callState == CallState.IN_CALL) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Mute button
                        CallControlButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMuted) "Unmute" else "Mute",
                            isActive = isMuted,
                            tintOverride = Color.White,
                            onClick = { viewModel.toggleMute() }
                        )

                        // Speaker button
                        CallControlButton(
                            icon = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                            label = if (isSpeaker) "Speaker On" else "Speaker",
                            isActive = isSpeaker,
                            tintOverride = Color.White,
                            onClick = { viewModel.toggleSpeaker() }
                        )

                        // Bluetooth button
                        CallControlButton(
                            icon = if (isBluetoothOn) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                            label = if (isBluetoothOn) "BT On" else "Bluetooth",
                            isActive = isBluetoothOn,
                            tintOverride = Color.White,
                            onClick = { viewModel.toggleBluetooth() }
                        )

                        // Video toggle (if video call)
                        if (isVideoCall) {
                            CallControlButton(
                                icon = Icons.Default.Videocam,
                                label = stringResource(R.string.call_short_video),
                                isActive = false,
                                tintOverride = Color.White,
                                onClick = { /* Toggle video */ }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }

                // Accept/Decline for incoming calls - 3 buttons matching iOS
                if (callState == CallState.RINGING && currentCall?.isOutgoing == false) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Decline (Red)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FloatingActionButton(
                                onClick = { viewModel.declineCall() },
                                containerColor = OSHIRed,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(
                                    Icons.Default.CallEnd,
                                    contentDescription = stringResource(R.string.accessibility_decline),
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.call_short_decline), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Accept Voice (Green)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FloatingActionButton(
                                onClick = { viewModel.acceptCall() },
                                containerColor = OSHIGreen,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(
                                    Icons.Default.Call,
                                    contentDescription = stringResource(R.string.accessibility_accept_voice),
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.call_short_voice), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Accept Video (Blue)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FloatingActionButton(
                                onClick = { viewModel.acceptCall() },
                                containerColor = Color(0xFF3B82F6),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = stringResource(R.string.accessibility_accept_video),
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.call_short_video), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    // End call button
                    FloatingActionButton(
                        onClick = { viewModel.endCall() },
                        containerColor = OSHIRed,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = stringResource(R.string.accessibility_end_call),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun WaveformVisualization() {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(40.dp)
    ) {
        for (i in 0 until 12) {
            val height = (20 + 20 * sin(phase + i * 0.5f)).dp
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    tintOverride: Color? = null,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isActive)
                    Color.White
                else
                    Color.White.copy(alpha = 0.2f)
            )
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isActive) Color.Black
                else tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = label,
            fontSize = 12.sp,
            color = tintOverride?.copy(alpha = 0.8f) ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}
