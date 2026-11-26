package com.oshi.messenger.ui.screens

import androidx.compose.animation.*
import com.oshi.messenger.ui.theme.OshiAlertDialog
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
import androidx.compose.ui.draw.clip
import com.oshi.messenger.ui.theme.glassSurface
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.service.*
import com.oshi.messenger.service.audio.NetworkQuality
import com.oshi.messenger.ui.components.VoiceEffectSelector
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.viewmodel.VoiceCallViewModel
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIRed
import kotlin.math.sin

/**
 * VoiceCallScreen - iOS-style Voice Call UI
 *
 * Features:
 * - Dynamic background based on connection quality
 * - Avatar with quality ring animation
 * - Connection type badge
 * - Quality indicator with signal bars
 * - Waveform visualization
 * - Jitter buffer stats
 */
@Composable
fun VoiceCallScreen(
    publicKey: String,
    isIncoming: Boolean = false,
    onEnd: () -> Unit,
    onVideoCall: () -> Unit,
    viewModel: VoiceCallViewModel = hiltViewModel()
) {
    val callState by viewModel.callState.collectAsState()
    val currentCall by viewModel.currentCall.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeaker by viewModel.isSpeaker.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val connectionQuality by viewModel.connectionQuality.collectAsState()
    // __GLASS_ALL_THEMES_2026_11_21__ the black ground is OLED black's; the glass
    // controls on it follow « Effet verre » (oledGlassOn at each control).
    val oledCall = com.oshi.messenger.ui.theme.isOledScheme()
    val connectionType by viewModel.connectionType.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val micLevel by viewModel.micLevel.collectAsState()
    val remoteLevel by viewModel.remoteLevel.collectAsState()
    val lastEndReason by viewModel.lastEndReason.collectAsState()
    val calleeReach by viewModel.calleeReach.collectAsState()  // __CALL_WAKE_2026_11_22__
    val outgoingRingingSince by viewModel.outgoingRingingSince.collectAsState()

    // Video upgrade request from peer — tasks 2.2.3-2.2.5
    val pendingVideoUpgrade by viewModel.pendingVideoUpgrade.collectAsState()
    val pendingVideoUpgradeFrom by viewModel.pendingVideoUpgradeFrom.collectAsState()
    val isAwaitingVideoUpgrade by viewModel.isAwaitingVideoUpgrade.collectAsState()
    val videoUpgradeDeclined by viewModel.videoUpgradeDeclined.collectAsState()
    val voiceEffect by viewModel.voiceEffect.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // __END_CALL_ALWAYS_VISIBLE_2026_09_23__ second exit: an ongoing notification with a
    // Hang up action while the call can be ended (see CallHangUpNotification).
    LaunchedEffect(callState, currentCall?.peerName, currentCall?.isOutgoing) {
        val appContext = context.applicationContext
        if (com.oshi.messenger.service.CallHangUpNotification.isHangUpAble(callState, currentCall?.isOutgoing ?: true)) {
            com.oshi.messenger.service.CallHangUpNotification.show(
                appContext, currentCall?.peerName ?: publicKey.take(8), isVideo = false
            )
        } else {
            com.oshi.messenger.service.CallHangUpNotification.cancel(appContext)
        }
    }

    // 📹 CAMERA for a MID-CALL video upgrade.
    // MainActivity only asks for CAMERA when the call screen is entered AS a video
    // call (MainActivity.kt:2431). A VOICE call upgraded later never went through
    // that, so `VideoCallManager.openCamera()` hit its silent permission return: a
    // video screen, a black self-view, no prompt, no error, no way to retry.
    // iOS prompts inline on the same path (VideoCallManager.swift:687-699).
    var pendingCameraGrant by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingCameraGrant
        pendingCameraGrant = null
        pending?.invoke(granted)
    }
    // `granted` is delivered on BOTH outcomes so the caller can decline the upgrade
    // rather than leaving the peer waiting on a 15 s timeout.
    fun withCameraPermission(onResult: (Boolean) -> Unit) {
        val already = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (already) {
            onResult(true)
        } else {
            pendingCameraGrant = onResult
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Animation states
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Only start an outgoing call — for incoming calls, EnhancedCallManager already set the state
    LaunchedEffect(publicKey, isIncoming) {
        if (!isIncoming) {
            viewModel.startCall(publicKey, false)
        }
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

    // Track whether call has ever left IDLE (prevents onEnd on initial composition)
    var callEverStarted by remember { mutableStateOf(isIncoming) }

    // The peer's name and the final duration have to be captured while they still
    // exist: `endCall` sets `_callState = ENDED` and nulls `_currentCall` on the very
    // next line, so by the time the ended overlay composes there is nothing left to
    // read. iOS holds the same two in `finalDuration` / the retained contact
    // (VoiceCallView.swift:700-708).
    var heldPeerName by remember { mutableStateOf("") }
    var heldDuration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(currentCall?.peerName) {
        currentCall?.peerName?.let { if (it.isNotEmpty()) heldPeerName = it }
    }
    LaunchedEffect(duration) { if (duration > 0L) heldDuration = duration }

    LaunchedEffect(callState) {
        if (callState != CallState.IDLE && callState != CallState.ENDED) {
            callEverStarted = true
        }
    }

    val callFinished = callEverStarted &&
        (callState == CallState.IDLE || callState == CallState.ENDED)

    // 🔚 Say WHY the call ended before leaving the screen.
    //
    // Before this, the screen popped the instant the state reached ENDED. A call
    // that never connected because the network was down, one the peer declined, one
    // that dropped after three minutes and a normal goodbye were all the same
    // thing: the call screen simply disappeared. iOS dwells for two seconds on an
    // overlay carrying the reason and the final duration
    // (VoiceCallView.swift:646-681 rendered, :699-725 driven).
    //
    // Keyed on `callFinished`, NOT on `callState`: the manager walks ENDED → IDLE
    // two seconds later, and a `LaunchedEffect(callState)` would be cancelled and
    // restarted by that second transition, doubling the dwell.
    LaunchedEffect(callFinished) {
        if (!callFinished) return@LaunchedEffect
        kotlinx.coroutines.delay(CALL_ENDED_DWELL_MS)
        onEnd()
    }

    // Caller-side: when our request is accepted by peer, EnhancedCallManager
    // flips currentCall.isVideoCall=true and starts local capture. Navigate
    // the UI over to VideoCallScreen at that point.
    LaunchedEffect(currentCall?.isVideoCall) {
        if (currentCall?.isVideoCall == true) {
            onVideoCall()
        }
    }

    // Caller-side: surface "no answer" / "peer declined" via a Toast.
    LaunchedEffect(videoUpgradeDeclined) {
        videoUpgradeDeclined?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeVideoUpgradeDeclined()
        }
    }

    // Callee-side: incoming 0x0E request → ask the user.
    if (pendingVideoUpgrade) {
        val askerName = pendingVideoUpgradeFrom ?: currentCall?.peerName ?: "Peer"
        OshiAlertDialog(
            onDismissRequest = { viewModel.declineVideoUpgrade() },
            title = { Text(stringResource(R.string.call_video_request)) },
            text = {
                Text(stringResource(R.string.call_video_upgrade_message, askerName))
            },
            // __VUPG_WATCH_ONLY_2026_09_14__ Le troisième choix existe enfin.
            //
            // iOS en propose trois — Accepter et partager / Regarder seulement /
            // Refuser — et le gestionnaire Android savait déjà émettre le 0x05.
            // Seuls manquaient la surcharge du ViewModel et la chaîne. Une
            // personne à qui l'on demande la vidéo dans un lieu où elle ne veut
            // pas se filmer n'avait donc que « tout » ou « rien ».
            //
            // « Regarder seulement » ne demande PAS la caméra: c'est tout
            // l'intérêt, et demander la permission ici serait absurde.
            confirmButton = {
                TextButton(onClick = {
                    // acceptVideoUpgrade() sets currentCall.isVideoCall = true
                    // — the LaunchedEffect above observes that flip and calls
                    // onVideoCall() exactly once. Don't navigate manually here
                    // or we'd attempt a duplicate navigation against a now-gone
                    // source destination (popUpTo with inclusive=true).
                    withCameraPermission { granted ->
                        if (granted) {
                            viewModel.acceptVideoUpgrade()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.error_camera_permission),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            viewModel.declineVideoUpgrade()
                        }
                    }
                }) {
                    Text(stringResource(R.string.call_accept), color = Color(0xFF5616EC))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.acceptVideoUpgrade(shareCamera = false) }) {
                        Text(stringResource(R.string.call_video_watch_only))
                    }
                    TextButton(onClick = { viewModel.declineVideoUpgrade() }) {
                        Text(stringResource(R.string.call_decline))
                    }
                }
            }
        )
    }

    // Caller-side: small "waiting for peer" overlay while we await accept (or 15 s timeout).
    if (isAwaitingVideoUpgrade && !pendingVideoUpgrade) {
        OshiAlertDialog(
            onDismissRequest = { /* keep — only timeout or response can close */ },
            title = { Text(stringResource(R.string.call_video_upgrade_inviting_title)) },
            text = { Text(stringResource(R.string.call_video_upgrade_inviting_message, currentCall?.peerName ?: "peer")) },
            confirmButton = {},
            dismissButton = {}
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // __OLED_GLASS_2026_11_16__ OLED black: no blue/violet wash
                // across the whole screen — a black ground with ONE soft glow
                // behind the avatar, in the same colour the quality would have
                // tinted the screen (brand violet when fine, amber / red when
                // the line is degrading: the warning survives, as light).
                if (oledCall) Modifier
                    .background(Color.Black)
                    .drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(callGlowColor(connectionQuality).copy(alpha = 0.30f), Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.30f),
                                radius = size.width * 0.95f
                            )
                        )
                    }
                else Modifier.background(brush = getBackgroundGradient(connectionQuality))
            )
    ) {
        // ── Call layout ───────────────────────────────────────────────
        // Portrait is one column: who you are talking to on top, the controls
        // under it. That column carries ~550 dp of fixed content, so in
        // landscape — roughly 360 dp of usable height — it ran straight off
        // the bottom edge, and with nothing scrollable the End Call button was
        // unreachable: the only way out of a call was the system back gesture.
        // It was invisible before because the manifest pinned the app to
        // portrait; targetSdk 36 drops that pin on every display 600 dp wide
        // or more, so landscape is now reachable on any tablet or foldable.
        //
        // A short window therefore puts the same two blocks side by side, each
        // scrolling independently, instead of stacking them.
        val compactHeight = rememberWindowSize().height == HeightClass.Compact

        val callerBlock: @Composable () -> Unit = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = if (compactHeight) 0.dp else 48.dp)
            ) {
                // Connection type badge
                AnimatedVisibility(
                    visible = callState == CallState.IN_CALL,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut()
                ) {
                    ConnectionTypeBadge(connectionType)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Avatar with quality ring
                AvatarWithQualityRing(
                    name = currentCall?.peerName ?: "?",
                    profilePicture = currentCall?.peerProfilePicture,
                    peerPublicKey = currentCall?.peerPublicKey ?: publicKey,
                    quality = connectionQuality,
                    isRinging = callState == CallState.RINGING,
                    pulseScale = pulseScale
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Contact name
                Text(
                    text = currentCall?.peerName ?: publicKey.take(8) + "...",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Call status
                CallStatusText(
                    callState = callState,
                    quality = connectionQuality,
                    isOutgoing = currentCall?.isOutgoing ?: true,
                    calleeReach = calleeReach,
                    ringingSince = outgoingRingingSince
                )

                // Duration
                AnimatedVisibility(
                    visible = callState == CallState.IN_CALL,
                    enter = scaleIn() + fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = formatDuration(duration),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                // Quality indicator
                AnimatedVisibility(
                    visible = callState == CallState.IN_CALL,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    QualityIndicator(
                        quality = connectionQuality,
                        latencyMs = latencyMs,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                // Encryption badge
                Spacer(modifier = Modifier.height(16.dp))
                EncryptionBadge()
            }
        }

        val controlsBlock: @Composable ColumnScope.() -> Unit = {
            // Waveform visualization
            AnimatedVisibility(
                visible = callState == CallState.IN_CALL && !isMuted,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                CallVoiceWaveform(
                    micLevel = micLevel,
                    remoteLevel = remoteLevel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 40.dp)
                )
            }

            // 🎤 The classic "talking while on mute" moment. Android hid the waveform
            // while muted and put NOTHING in its place, so the only trace of a muted
            // mic was the fill inversion on one 56 dp button. iOS puts a red capsule
            // exactly where the waveform was — VoiceCallView.swift:140-155,
            // `__MUTE_BANNER_2026_06_19__`.
            AnimatedVisibility(
                visible = callState == CallState.IN_CALL && isMuted,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .background(OSHIRed.copy(alpha = 0.85f), CircleShape)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MicOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.call_muted),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Control buttons.
            //
            // 🎛️ The whole row used to be gated on IN_CALL, so there was no way to
            // mute the mic or flip to speaker while a call you had just placed was
            // still connecting or ringing out — the exact window in which people reach
            // for the speaker button. iOS renders `controlButtonsView` unconditionally
            // and gates only the video button on `.inCall` (VoiceCallView.swift:555-611,
            // :589). Incoming RINGING is deliberately still excluded here: that state
            // shows Accept / Decline, and Android has no mic to mute yet.
            val showCallControls = callState == CallState.IN_CALL ||
                callState == CallState.CONNECTING ||
                (callState == CallState.RINGING && currentCall?.isOutgoing == true)

            if (showCallControls) {
                // 🎤 Voice effect selector — applies effect to OUTBOUND PCM,
                // remote peer hears it without any change on their side.
                if (callState == CallState.IN_CALL) {
                    VoiceEffectSelector(
                        current = voiceEffect,
                        onSelect = { viewModel.setVoiceEffect(it) },
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                CallControlButtons(
                    showVideoButton = callState == CallState.IN_CALL,
                    isMuted = isMuted,
                    isSpeaker = isSpeaker,
                    onMuteToggle = { viewModel.toggleMute() },
                    onSpeakerToggle = { viewModel.toggleSpeaker() },
                    onVideoCall = {
                        // Send 0x0E upgrade REQUEST. Do NOT navigate yet — we
                        // wait for the peer's 0x0E accept (or 15 s timeout)
                        // and the LaunchedEffect on currentCall.isVideoCall
                        // above will navigate to VideoCallScreen on accept.
                        // Ask for CAMERA first: the requester starts its own capture
                        // as soon as the peer accepts, on the same permission-less path.
                        withCameraPermission { granted ->
                            if (granted) {
                                viewModel.requestVideoUpgrade()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.error_camera_permission),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Accept/Decline for incoming calls
            if (callState == CallState.RINGING && currentCall?.isOutgoing == false) {
                IncomingCallButtons(
                    onAccept = { viewModel.acceptCall() },
                    onDecline = { viewModel.declineCall() }
                )
            } else {
                // End call button
                EndCallButton(
                    onClick = { viewModel.endCall() }
                )
            }

            // Was 50 dp to clear the gesture bar; the portrait column now applies
            // the real system-bar insets itself, so only a small margin is left.
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (compactHeight) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    callerBlock()
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    controlsBlock()
                }
            }
        } else {
            // __END_CALL_ALWAYS_VISIBLE_2026_09_23__ Portrait used to be ONE
            // unscrollable column: callerBlock, a weight(1f) spacer, controlsBlock.
            // Once the call is IN_CALL the caller block grows (connection badge, 48 sp
            // timer, quality row) and the controls grow (waveform, voice-effect row),
            // ~950 dp in all — more than an 832 dp-tall phone (1080x2340 @ 450 dpi,
            // e.g. Galaxy A17). A Column does not shrink its children: the spacer went
            // to 0 and the LAST child — the End Call button — was laid out past the
            // bottom edge and clipped. `enableEdgeToEdge()` also put the bottom of the
            // column under the gesture bar. Seen on a real SM-A175F: call screen up,
            // no red button, no way to hang up.
            //
            // Now the controls are measured FIRST (they are not weighted), inside the
            // system-bar insets, so End Call always has its room; the caller block
            // takes whatever height is left and scrolls when that is not enough.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    callerBlock()
                }

                controlsBlock()
            }
        }

        // 🔚 The ended overlay sits on top of the call layout so the avatar and the
        // gradient stay visible underneath, as on iOS (VoiceCallView.swift:646-681).
        if (callFinished) {
            CallEndedOverlay(
                peerName = heldPeerName,
                durationSeconds = heldDuration,
                label = CallEndReasonDisplay.labelFor(lastEndReason)
            )
        }
    }
}

/**
 * How long the ended overlay stays up before the screen pops.
 * iOS: 2.0 s on the voice screen (`VoiceCallView.swift:722`).
 */
private const val CALL_ENDED_DWELL_MS = 2_000L

/**
 * The sentence for a [CallEndLabel]. Kept out of [CallEndReasonDisplay] so the
 * mapping from reason to label stays testable on the JVM, where `R` is a stub.
 *
 * `call_ended`, `call_declined` and `call_no_answer` already existed and were
 * already translated; the other three are new.
 */
@Composable
internal fun callEndLabelText(label: CallEndLabel): String = stringResource(
    when (label) {
        CallEndLabel.ENDED -> R.string.call_ended
        CallEndLabel.DECLINED -> R.string.call_declined
        CallEndLabel.NO_ANSWER -> R.string.call_no_answer
        CallEndLabel.NETWORK_ERROR -> R.string.call_end_network_error
        CallEndLabel.CONNECTION_LOST -> R.string.call_end_connection_lost
        CallEndLabel.ANSWERED_ELSEWHERE -> R.string.call_end_answered_elsewhere
    }
)

@Composable
private fun CallEndedOverlay(
    peerName: String,
    durationSeconds: Long,
    label: CallEndLabel
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = callEndLabelText(label),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (peerName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = peerName,
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            // Only an ANSWERED call has a duration. iOS shows it on the same
            // condition (`VoiceCallView.swift:672-679`).
            if (durationSeconds > 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDuration(durationSeconds),
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ============================================================================
// COMPONENTS
// ============================================================================

/** __OLED_GLASS_2026_11_16__ The single glow colour behind the avatar under OLED black. */
private fun callGlowColor(quality: NetworkQuality): Color = when (quality) {
    NetworkQuality.RECONNECTING -> Color(0xFFFF9800)
    NetworkQuality.POOR, NetworkQuality.VERY_POOR -> Color(0xFFF44336)
    NetworkQuality.FAIR -> Color(0xFFFFC107)
    else -> com.oshi.messenger.ui.theme.OSHIPurpleLight
}

@Composable
private fun getBackgroundGradient(quality: NetworkQuality): Brush {
    val colors = when (quality) {
        NetworkQuality.RECONNECTING -> listOf(
            Color(0xCCFF9800),
            Color(0x99F44336)
        )
        NetworkQuality.POOR, NetworkQuality.VERY_POOR -> listOf(
            Color(0xB3F44336),
            Color(0x99FF9800)
        )
        NetworkQuality.FAIR -> listOf(
            Color(0xB3FFEB3B),
            Color(0x995616EC)
        )
        else -> listOf(
            Color(0xE65616EC),
            Color(0xB32196F3)
        )
    }
    return Brush.linearGradient(colors)
}

@Composable
private fun ConnectionTypeBadge(connectionType: ConnectionType) {
    val (icon, text) = when (connectionType) {
        ConnectionType.MESH_DIRECT -> Icons.Default.Wifi to "P2P Direct"
        ConnectionType.MESH -> Icons.Default.Hub to "Mesh + Server"
        ConnectionType.VPS -> Icons.Default.Cloud to "Secure Server"
        ConnectionType.TOR -> Icons.Default.Security to "Tor Relay"
        ConnectionType.CROSS_PLATFORM -> Icons.Default.Phonelink to "Cross-Platform"
        ConnectionType.RELAY -> Icons.Default.Shuffle to "Relay"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (com.oshi.messenger.ui.theme.oledGlassOn()) Modifier.glassSurface(RoundedCornerShape(16.dp), com.oshi.messenger.ui.theme.glassOpaque(com.oshi.messenger.ui.theme.GlassTier.Chip)) else Modifier.background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(16.dp)))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun AvatarWithQualityRing(
    name: String,
    profilePicture: ByteArray? = null,
    peerPublicKey: String = "",
    quality: NetworkQuality,
    isRinging: Boolean,
    pulseScale: Float
) {
    val ringColor = when (quality) {
        NetworkQuality.EXCELLENT -> OSHIGreen
        NetworkQuality.GOOD -> Color(0xFF8BC34A)
        NetworkQuality.FAIR -> Color(0xFFFFEB3B)
        NetworkQuality.POOR, NetworkQuality.VERY_POOR -> OSHIRed
        NetworkQuality.RECONNECTING -> Color(0xFFFF9800)
        NetworkQuality.DISCONNECTED -> Color.Gray
    }

    Box(contentAlignment = Alignment.Center) {
        // Quality ring
        Box(
            modifier = Modifier
                .size(150.dp)
                .scale(if (quality == NetworkQuality.RECONNECTING) pulseScale else 1f)
                .clip(CircleShape)
                .background(Color.Transparent)
                .then(
                    Modifier.background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, ringColor.copy(alpha = 0.3f))
                        ),
                        shape = CircleShape
                    )
                )
        )

        // Outer ring
        Surface(
            modifier = Modifier.size(150.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(4.dp, ringColor)
        ) {}

        // Ringing pulse animation
        if (isRinging) {
            repeat(3) { index ->
                val scale by rememberInfiniteTransition(label = "ring$index").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, delayMillis = index * 300),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ringScale$index"
                )
                val alpha by rememberInfiniteTransition(label = "ringAlpha$index").animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, delayMillis = index * 300),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ringAlpha$index"
                )
                Surface(
                    modifier = Modifier
                        .size(130.dp)
                        .scale(scale),
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = alpha))
                ) {}
            }
        }

        // Avatar circle
        Surface(
            modifier = Modifier.size(130.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.2f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (quality == NetworkQuality.RECONNECTING) {
                    val rotation by rememberInfiniteTransition(label = "reconnect").animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing)
                        ),
                        label = "rotation"
                    )
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer { rotationZ = rotation }
                    )
                } else if (profilePicture != null) {
                    val bitmap = remember(profilePicture) {
                        android.graphics.BitmapFactory.decodeByteArray(
                            profilePicture, 0, profilePicture.size
                        )?.asImageBitmap()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        com.oshi.messenger.ui.theme.ContactAvatar(peerPublicKey, name, size = 130.dp)
                    }
                } else {
                    // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was a white
                    // wash for every caller; now the caller's own colour.
                    com.oshi.messenger.ui.theme.ContactAvatar(peerPublicKey, name, size = 130.dp)
                }
            }
        }
    }
}

@Composable
private fun CallStatusText(
    callState: CallState,
    quality: NetworkQuality,
    isOutgoing: Boolean,
    calleeReach: com.oshi.messenger.service.CallReach? = null,
    ringingSince: Long = 0L
) {
    // __CALL_WAKE_2026_11_22__ "Ringing…" only once the callee's phone has the call;
    // "Calling…" before, "Not reachable right now" when no push device / socket exists.
    // Re-evaluated every second so the 6 s grace of the unreachable label can elapse.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(callState, isOutgoing) {
        while (callState == CallState.RINGING && isOutgoing) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val outgoingLabel = com.oshi.messenger.service.CallReachLabel.forOutgoing(
        calleeReach, if (ringingSince > 0L) nowMs - ringingSince else 0L)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (quality == NetworkQuality.RECONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // 🌍 These are the words on screen for the whole duration of every call and
        // they were English in all 34 locales. iOS localizes the same set
        // (`call.state.ringing` / `.connecting` / `.on_call`, VoiceCallView.swift:413-430).
        Text(
            text = when {
                quality == NetworkQuality.RECONNECTING -> stringResource(R.string.call_state_reconnecting)
                callState == CallState.IDLE -> stringResource(R.string.call_state_disconnected)
                callState == CallState.RINGING ->
                    if (isOutgoing) when (outgoingLabel) {
                        com.oshi.messenger.service.CallReachLabel.Label.RINGING -> stringResource(R.string.call_state_ringing)
                        com.oshi.messenger.service.CallReachLabel.Label.CALLING -> stringResource(R.string.call_state_calling)
                        com.oshi.messenger.service.CallReachLabel.Label.UNREACHABLE -> stringResource(R.string.call_reach_unreachable)
                    }
                    else stringResource(R.string.call_summary_incoming)
                callState == CallState.CONNECTING -> stringResource(R.string.call_connecting)
                callState == CallState.IN_CALL -> stringResource(R.string.call_state_on_call)
                callState == CallState.ENDED -> stringResource(R.string.call_ended)
                else -> ""
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun QualityIndicator(
    quality: NetworkQuality,
    latencyMs: Long,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .then(if (com.oshi.messenger.ui.theme.oledGlassOn()) Modifier.glassSurface(RoundedCornerShape(12.dp), com.oshi.messenger.ui.theme.glassOpaque(com.oshi.messenger.ui.theme.GlassTier.Chip)) else Modifier.background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(12.dp)))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Signal bars
        SignalBars(quality)

        // Quality text. 🌍 Was `quality.name.replace("_", " ")` — the Kotlin enum
        // name, so every locale read "VERY POOR" / "RECONNECTING" in shouting English.
        Text(
            text = stringResource(
                when (quality) {
                    NetworkQuality.EXCELLENT -> R.string.call_quality_excellent
                    NetworkQuality.GOOD -> R.string.call_quality_good
                    NetworkQuality.FAIR -> R.string.call_quality_fair
                    NetworkQuality.POOR -> R.string.call_quality_poor
                    NetworkQuality.VERY_POOR -> R.string.call_quality_very_poor
                    NetworkQuality.RECONNECTING -> R.string.call_state_reconnecting
                    NetworkQuality.DISCONNECTED -> R.string.call_state_disconnected
                }
            ),
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.8f)
        )

        // 📶 Round-trip time, exactly as iOS renders it: a bare `NNms` next to the
        // quality word, only while it has actually been measured
        // (VoiceCallView.swift:448-456).
        //
        // This replaces two chips that were hardcoded English in all 34 locales —
        // `"Buffer: ${…}ms"` and `"• Loss: …%"`. **They are removed, not translated**,
        // and that is the point: iOS shows neither. The buffer depth is an internal
        // knob nobody outside the jitter buffer can act on, and the loss percentage is
        // already what decides the quality word and the signal bars standing right
        // beside it. What iOS shows and Android never did is the latency, which the
        // manager has measured all along and no ViewModel re-exposed.
        if (latencyMs > 0L) {
            Text(
                text = "•",
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = "${latencyMs}ms",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SignalBars(quality: NetworkQuality) {
    // 📶 iOS gives poor / very poor / reconnecting ONE bar and reserves zero for
    // disconnected (VoiceCallView.swift:466-479). Android's `else -> 0` swept
    // VERY_POOR and RECONNECTING in with it, so a call that was still passing audio
    // showed the same empty meter as a dead one.
    val activeBars = when (quality) {
        NetworkQuality.EXCELLENT -> 4
        NetworkQuality.GOOD -> 3
        NetworkQuality.FAIR -> 2
        NetworkQuality.POOR, NetworkQuality.VERY_POOR, NetworkQuality.RECONNECTING -> 1
        NetworkQuality.DISCONNECTED -> 0
    }

    val barColor = when (quality) {
        NetworkQuality.EXCELLENT, NetworkQuality.GOOD -> OSHIGreen
        NetworkQuality.FAIR -> Color(0xFFFFEB3B)
        else -> OSHIRed
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((6 + index * 4).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (index < activeBars) barColor
                        else Color.White.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

@Composable
private fun EncryptionBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (com.oshi.messenger.ui.theme.oledGlassOn()) Modifier.glassSurface(RoundedCornerShape(16.dp), com.oshi.messenger.ui.theme.glassOpaque(com.oshi.messenger.ui.theme.GlassTier.Chip)) else Modifier.background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(16.dp)))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = OSHIGreen,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.call_end_to_end_encrypted),
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

/**
 * __VOICE_METER_2026_09_22__ Port of iOS `CallVoiceWaveform` (VoiceCallView.swift).
 *
 * The bars follow the VOICE — the louder of my mic and the peer's voice — with a fast
 * attack and a soft release ([LevelSmoother]); green when it is me, white when it is
 * them, flat in silence. Before: a sine wave driven by a 16 ms timer that moved the same
 * whether anyone spoke or not. Frozen wobble when the system's animator scale is 0
 * (Android's "remove animations"); the height still follows the voice.
 */
@Composable
private fun CallVoiceWaveform(
    micLevel: Float,
    remoteLevel: Float,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val reduceMotion = remember {
        try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (_: Exception) { false }
    }
    val currentMic by rememberUpdatedState(micLevel)
    val currentRemote by rememberUpdatedState(remoteLevel)
    val smoother = remember { com.oshi.messenger.service.audio.LevelSmoother() }
    var level by remember { mutableFloatStateOf(0f) }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                val seconds = nanos / 1_000_000_000.0
                val target = maxOf(currentMic, currentRemote)
                level = if (reduceMotion) target else smoother.step(target, seconds)
                t = (seconds % 10_000.0).toFloat()
            }
        }
    }
    val mine = micLevel > remoteLevel
    val speakingColor = if (mine) Color(0xFF34C759).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.85f)
    val barColor = if (level > 0.05f) speakingColor else Color.White.copy(alpha = 0.35f)
    androidx.compose.foundation.Canvas(
        modifier = modifier.clearAndSetSemantics { }
    ) {
        val barCount = 24
        val barW = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val total = barCount * barW + (barCount - 1) * gap
        val startX = (size.width - total) / 2f
        val maxH = size.height
        val minH = 4.dp.toPx()
        for (i in 0 until barCount) {
            val x = (i.toFloat() / (barCount - 1)) * 2f - 1f
            // Bell envelope: the centre carries the voice, the edges follow.
            val envelope = 0.35f + 0.65f * kotlin.math.exp(-x * x * 2.2f)
            val wobble = if (reduceMotion) 1f else
                (0.62 + 0.23 * sin(t * 9.1 + i * 1.7) + 0.15 * sin(t * 15.3 + i * 0.6)).toFloat()
            val h = minH + (maxH - minH) * minOf(1f, level * envelope * wobble * 1.15f)
            val left = startX + i * (barW + gap)
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(left, (maxH - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

@Composable
private fun CallControlButtons(
    showVideoButton: Boolean,
    isMuted: Boolean,
    isSpeaker: Boolean,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onVideoCall: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        CallControlButton(
            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = stringResource(if (isMuted) R.string.call_unmute else R.string.call_mute),
            isActive = isMuted,
            onClick = onMuteToggle
        )

        CallControlButton(
            icon = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
            label = stringResource(if (isSpeaker) R.string.speaker_on else R.string.call_speaker),
            isActive = isSpeaker,
            onClick = onSpeakerToggle
        )

        // Video upgrade only makes sense once there is a call to upgrade —
        // iOS VoiceCallView.swift:589 gates it on `.inCall` the same way.
        if (showVideoButton) {
            CallControlButton(
                icon = Icons.Default.Videocam,
                label = stringResource(R.string.call_video_button),
                isActive = false,
                onClick = onVideoCall
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    // (N12, Anatole 13/11) Google-Phone-style: 80×64 pill that springs to a
    // rounded square when on. Was a 56 dp circle. See ExpressiveCallButtons.kt.
    com.oshi.messenger.ui.components.ExpressiveCallToggle(
        icon = icon,
        label = label,
        isActive = isActive,
        onClick = onClick
    )
}

@Composable
private fun IncomingCallButtons(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        // (N12) Wide pills, as the Phone app draws answer / decline.
        com.oshi.messenger.ui.components.ExpressiveCallPill(
            icon = Icons.Default.CallEnd,
            contentDescription = stringResource(R.string.accessibility_decline),
            containerColor = OSHIRed,
            onClick = onDecline,
            width = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerWidth,
            height = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerHeight
        )

        com.oshi.messenger.ui.components.ExpressiveCallPill(
            icon = Icons.Default.Call,
            contentDescription = stringResource(R.string.accessibility_accept),
            containerColor = OSHIGreen,
            onClick = onAccept,
            width = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerWidth,
            height = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerHeight
        )
    }
}

@Composable
private fun EndCallButton(onClick: () -> Unit) {
    // (N12) 128×72 red pill instead of a 72 dp circle: the hang-up is the
    // largest target on the screen, as in the Phone app.
    com.oshi.messenger.ui.components.ExpressiveCallPill(
        icon = Icons.Default.CallEnd,
        contentDescription = stringResource(R.string.accessibility_end_call),
        containerColor = OSHIRed,
        onClick = onClick
    )
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    // 🌍 `Locale.ROOT`, not the default locale. Without it `String.format` renders
    // Arabic-Indic digits in ar/fa (٠٥:٣٢) while the iPhone next to it shows 05:32 —
    // iOS's `String(format:)` is locale-independent (VoiceCallView.swift:688-692,
    // VideoCallView.swift:1297-1302). A call timer is one of the few places where
    // matching the other handset beats localizing the glyphs.
    return String.format(java.util.Locale.ROOT, "%02d:%02d", mins, secs)
}
