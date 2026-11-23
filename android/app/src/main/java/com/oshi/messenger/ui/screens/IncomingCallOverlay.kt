package com.oshi.messenger.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.service.CallState
import com.oshi.messenger.service.CurrentCall
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIRed
import kotlinx.coroutines.flow.StateFlow

/**
 * IncomingCallOverlay - iOS Compatible Global Incoming Call Overlay
 *
 * Wraps the entire NavHost content. When an incoming call is detected
 * (RINGING + not outgoing), displays a full-screen overlay with:
 * - Pulsing avatar with expanding rings
 * - Caller name + call type
 * - Three buttons: Decline (red), Voice (green), Video (blue)
 * - Haptic feedback on actions
 */
@Composable
fun IncomingCallOverlay(
    callState: StateFlow<CallState>,
    currentCall: StateFlow<CurrentCall?>,
    onAcceptVoice: () -> Unit,
    onAcceptVideo: () -> Unit,
    onDecline: () -> Unit,
    content: @Composable () -> Unit
) {
    val state by callState.collectAsState()
    val call by currentCall.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        content()

        // Show overlay when ringing and NOT outgoing
        if (state == CallState.RINGING && call?.isOutgoing == false) {
            IncomingCallOverlayContent(
                callerName = call?.peerName ?: "Unknown",
                callerProfilePicture = call?.peerProfilePicture,
                callerPublicKey = call?.peerPublicKey.orEmpty(),
                isVideoCall = call?.isVideoCall ?: false,
                onAcceptVoice = onAcceptVoice,
                onAcceptVideo = onAcceptVideo,
                onDecline = onDecline
            )
        }
    }
}

@Composable
private fun IncomingCallOverlayContent(
    callerName: String,
    callerProfilePicture: ByteArray? = null,
    callerPublicKey: String = "",
    isVideoCall: Boolean,
    onAcceptVoice: () -> Unit,
    onAcceptVideo: () -> Unit,
    onDecline: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Pulse animation for avatar rings
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring1"
    )
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring2"
    )
    val ring3Scale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 2.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring3"
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring1alpha"
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring2alpha"
    )
    val ring3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ring3alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // 📐 SHORT WINDOW. This was `Column(fillMaxSize, SpaceBetween)` with
        // 160 dp of vertical padding alone, plus a 120 dp avatar, a 32 sp name and a
        // 70 dp button row — ~489 dp of FIXED content in a container that could not
        // scroll. A landscape phone has ~320–400 dp of height, so Decline / Voice /
        // Video were pushed clean off the bottom edge: an incoming call that cannot
        // be answered OR refused. targetSdk 36 makes landscape reachable everywhere.
        // The padding shrinks when the window is short and the whole column scrolls,
        // so the buttons are always reachable.
        val compactHeight = rememberWindowSize().height == HeightClass.Compact
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compactHeight) 16.dp else 80.dp)
        ) {
            // Top: OSHI branding
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("OSHI", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = OSHIGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.incoming_e2e_encrypted), fontSize = 12.sp, color = OSHIGreen)
                }
            }

            // Middle: Avatar with pulse rings
            Box(contentAlignment = Alignment.Center) {
                // Expanding rings
                Box(modifier = Modifier.size(120.dp).scale(ring3Scale).clip(CircleShape)
                    .background(OSHIGreen.copy(alpha = ring3Alpha)))
                Box(modifier = Modifier.size(120.dp).scale(ring2Scale).clip(CircleShape)
                    .background(OSHIGreen.copy(alpha = ring2Alpha)))
                Box(modifier = Modifier.size(120.dp).scale(ring1Scale).clip(CircleShape)
                    .background(OSHIGreen.copy(alpha = ring1Alpha)))

                // Avatar — profile picture if available, initials fallback
                Box(
                    modifier = Modifier.size(120.dp).clip(CircleShape)
                        .background(Color(0xFF5616EC)),
                    contentAlignment = Alignment.Center
                ) {
                    if (callerProfilePicture != null) {
                        val bitmap = remember(callerProfilePicture) {
                            android.graphics.BitmapFactory.decodeByteArray(
                                callerProfilePicture, 0, callerProfilePicture.size
                            )?.asImageBitmap()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = callerName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            com.oshi.messenger.ui.theme.ContactAvatar(callerPublicKey, callerName, size = 120.dp)
                        }
                    } else {
                        // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was one
                        // brand purple for every caller.
                        com.oshi.messenger.ui.theme.ContactAvatar(callerPublicKey, callerName, size = 120.dp)
                    }
                }
            }

            // Caller info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(callerName, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (isVideoCall) stringResource(R.string.incoming_video_call) else stringResource(R.string.incoming_voice_call),
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            // Buttons: Decline (red), Voice (green), Video (blue)
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                // Decline
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // (N12) Expressive pill; 88 dp wide so three fit a 360 dp screen.
                    com.oshi.messenger.ui.components.ExpressiveCallPill(
                        icon = Icons.Default.CallEnd,
                        contentDescription = stringResource(R.string.accessibility_decline),
                        containerColor = OSHIRed,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDecline()
                        },
                        width = 88.dp,
                        height = 72.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.incoming_decline), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }

                // Accept Voice
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // (N12) Expressive pill; 88 dp wide so three fit a 360 dp screen.
                    com.oshi.messenger.ui.components.ExpressiveCallPill(
                        icon = Icons.Default.Call,
                        contentDescription = stringResource(R.string.accessibility_accept_voice),
                        containerColor = OSHIGreen,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAcceptVoice()
                        },
                        width = 88.dp,
                        height = 72.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.incoming_voice), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }

                // Accept Video
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // (N12) Expressive pill; 88 dp wide so three fit a 360 dp screen.
                    com.oshi.messenger.ui.components.ExpressiveCallPill(
                        icon = Icons.Default.Videocam,
                        contentDescription = stringResource(R.string.accessibility_accept_video),
                        containerColor = Color(0xFF3B82F6),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAcceptVideo()
                        },
                        width = 88.dp,
                        height = 72.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.incoming_video), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
    }
}
