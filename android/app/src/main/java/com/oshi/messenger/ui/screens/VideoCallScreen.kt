package com.oshi.messenger.ui.screens

import androidx.compose.animation.*
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.service.*
import com.oshi.messenger.service.audio.CallVoiceEffectProcessor
import com.oshi.messenger.service.audio.NetworkQuality
import com.oshi.messenger.ui.MainActivity
import com.oshi.messenger.ui.components.VoiceEffectSelector
import com.oshi.messenger.ui.viewmodel.VideoCallViewModel
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIRed
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * VideoCallScreen - iOS-style Video Call UI
 *
 * Features:
 * - Full screen remote video
 * - Draggable PiP local preview
 * - Auto-hiding controls
 * - Quality picker
 * - Blur mode picker
 * - Jitter buffer indicator
 */
@Composable
fun VideoCallScreen(
    publicKey: String,
    isIncoming: Boolean = false,
    onEnd: () -> Unit,
    onBackToVoice: () -> Unit,
    viewModel: VideoCallViewModel = hiltViewModel()
) {
    val callState by viewModel.callState.collectAsState()
    val currentCall by viewModel.currentCall.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val localVideoFrame by viewModel.localVideoFrame.collectAsState()
    val remoteVideoFrame by viewModel.remoteVideoFrame.collectAsState()
    val currentQuality by viewModel.currentQuality.collectAsState()
    val blurMode by viewModel.blurMode.collectAsState()
    val blurIntensity by viewModel.blurIntensity.collectAsState()
    val outgoingBandwidth by viewModel.outgoingBandwidth.collectAsState()
    val jitterBufferDepth by viewModel.jitterBufferDepth.collectAsState()
    val connectionQuality by viewModel.connectionQuality.collectAsState()
    val isSendingVideo by viewModel.isSendingVideo.collectAsState()
    val isReceivingVideo by viewModel.isReceivingVideo.collectAsState()
    val isSpeaker by viewModel.isSpeaker.collectAsState()
    val voiceEffect by viewModel.voiceEffect.collectAsState()
    val videoError by viewModel.videoError.collectAsState()
    val isRemoteCameraPaused by viewModel.isRemoteCameraPaused.collectAsState()
    val isRemoteVideoStalled by viewModel.isRemoteVideoStalled.collectAsState()
    val remoteVideoFps by viewModel.remoteVideoFps.collectAsState()
    val lastEndReason by viewModel.lastEndReason.collectAsState()

    // 📶 Frames ARE arriving, just far too slowly to read as a moving picture —
    // distinct from stalled, and it needs its own sentence. iOS `isRemoteVideoWeak`,
    // OSHI/VideoCallManager.swift:1393-1395.
    val isRemoteVideoWeak = RemoteVideoHealth.isWeak(
        isReceiving = isReceivingVideo,
        isStalled = isRemoteVideoStalled,
        fps = remoteVideoFps
    )

    // PiP state
    val context = LocalContext.current
    val isInPipMode = (context as? MainActivity)?.isInPipMode ?: false

    // __END_CALL_ALWAYS_VISIBLE_2026_09_23__ second exit: ongoing notification with a
    // Hang up action while the call can be ended (see CallHangUpNotification).
    LaunchedEffect(callState, currentCall?.peerName, currentCall?.isOutgoing) {
        val appContext = context.applicationContext
        if (com.oshi.messenger.service.CallHangUpNotification.isHangUpAble(callState, currentCall?.isOutgoing ?: true)) {
            com.oshi.messenger.service.CallHangUpNotification.show(
                appContext, currentCall?.peerName ?: publicKey.take(8), isVideo = true
            )
        } else {
            com.oshi.messenger.service.CallHangUpNotification.cancel(appContext)
        }
    }

    // Listen for PiP action broadcasts (mute / end call)
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                when (intent.action) {
                    "com.oshi.messenger.call.TOGGLE_MUTE" -> viewModel.toggleMute()
                    "com.oshi.messenger.call.END_CALL" -> viewModel.endCall()
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.oshi.messenger.call.TOGGLE_MUTE")
            addAction("com.oshi.messenger.call.END_CALL")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    // Keep MainActivity.pipIsMuted in sync for PiP action icon
    LaunchedEffect(isMuted) {
        (context as? MainActivity)?.pipIsMuted = isMuted
    }

    // UI state
    var showControls by remember { mutableStateOf(true) }
    var showQualityPicker by remember { mutableStateOf(false) }
    var showBlurPicker by remember { mutableStateOf(false) }

    // PiP position — adapts to orientation
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val pipWidth = if (isLandscape) 160f else 120f
    val pipHeight = if (isLandscape) 120f else 160f
    var pipOffset by remember(isLandscape) { mutableStateOf(Offset(screenWidth.value - pipWidth - 20f, 100f)) }

    // 📹 A camera that never started used to be silent — black self-view, no prompt,
    // no error. Say it once, then clear; `cameraFailed` keeps the self-view from
    // spinning forever afterwards.
    var cameraFailed by remember { mutableStateOf(false) }
    LaunchedEffect(videoError) {
        videoError?.let { code ->
            cameraFailed = true
            val msg = when (code) {
                VideoCallManager.ERROR_CAMERA_PERMISSION -> context.getString(R.string.error_camera_permission)
                else -> context.getString(R.string.error_camera_unavailable)
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeVideoError()
        }
    }
    // A frame arriving means the camera recovered — allow the spinner again next time.
    LaunchedEffect(localVideoFrame != null) {
        if (localVideoFrame != null) cameraFailed = false
    }

    // Auto-hide controls timer.
    // 🕶️ Only hide when there is something UNDERNEATH worth uncovering. With no
    // remote picture — not receiving yet, or the peer's camera is off — hiding the
    // controls reveals a placeholder and leaves the user tapping a black screen to
    // get the buttons back. iOS suppresses the timer under exactly these conditions
    // (VideoCallView.swift:426-461).
    //
    // 📶 THIRD condition, previously impossible to write: `isReceivingVideo` LATCHES
    // (VideoCallManager.kt:1601-1602), so a peer whose stream simply died still reads
    // as "receiving" and the controls hid over a frozen frame with the health badge
    // floating on top of nothing. Android now has the measured stall
    // ([RemoteVideoHealth]), so this matches iOS's `hasSomethingToUncover` in full —
    // VideoCallView.swift:426-437, and iOS's `syncControlsToRemoteVideo` (:451-461)
    // is what re-arming on a change of any of the three inputs does here.
    LaunchedEffect(showControls, isReceivingVideo, isRemoteCameraPaused, isRemoteVideoStalled) {
        if (showControls && isReceivingVideo && !isRemoteCameraPaused && !isRemoteVideoStalled) {
            kotlinx.coroutines.delay(5000)
            showControls = false
        }
    }

    // Bring the controls back when the picture underneath them goes away — iOS's
    // `syncControlsToRemoteVideo` does this for the same reason: without it the guard
    // above would only ever be consulted at the moment a tap happened, so a stream
    // that died AFTER the controls hid left the user tapping a frozen frame.
    LaunchedEffect(isRemoteVideoStalled, isRemoteCameraPaused) {
        if (isRemoteVideoStalled || isRemoteCameraPaused) showControls = true
    }

    // Only start an outgoing call — for incoming calls, EnhancedCallManager already set the state
    LaunchedEffect(publicKey, isIncoming) {
        if (!isIncoming) {
            viewModel.startVideoCall(publicKey)
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
    var showEndedSummary by remember { mutableStateOf(false) }
    var summaryDuration by remember { mutableLongStateOf(0L) }
    // 🔚 Was `summaryQuality = currentQuality.name` rendered through
    // `video_call_quality` ("Quality: %s") — i.e. the outgoing VIDEO RESOLUTION
    // PRESET ("HIGH" / "MEDIUM" / "LOW", raw Kotlin enum name, untranslated)
    // presented to the user as the quality of the call that just ended. It said
    // nothing about the call and nothing about why it ended. iOS puts the end reason
    // in this exact slot (VideoCallView.swift:121-133, :253-269).
    var summaryLabel by remember { mutableStateOf(CallEndLabel.ENDED) }

    LaunchedEffect(callState) {
        if (callState != CallState.IDLE && callState != CallState.ENDED) {
            callEverStarted = true
        }
    }

    val callFinished = callEverStarted &&
        (callState == CallState.IDLE || callState == CallState.ENDED)

    // Keyed on `callFinished`, not on `callState`: the manager walks ENDED → IDLE
    // 2 s later, and a `LaunchedEffect(callState)` was cancelled and restarted by
    // that second transition, so the overlay actually stayed up for 3 s + 3 s.
    LaunchedEffect(callFinished) {
        if (!callFinished) return@LaunchedEffect
        summaryDuration = duration
        summaryLabel = CallEndReasonDisplay.labelFor(lastEndReason)
        showEndedSummary = true
        // iOS dwells 1.5 s on the video screen (VideoCallView.swift:131).
        kotlinx.coroutines.delay(1_500L)
        onEnd()
    }

    // 📹 If the peer (or our own stopVideo()) drops back to voice-only,
    // currentCall.isVideoCall flips to false — navigate back to the
    // VoiceCallScreen so both sides match. Audio stays connected.
    LaunchedEffect(currentCall?.isVideoCall) {
        if (callEverStarted && currentCall != null && currentCall?.isVideoCall == false) {
            onBackToVoice()
        }
    }

    // 📐 Short window (landscape phone, split screen). `HeightClass.Compact` is
    // < 480 dp — see ui/theme/WindowSize.kt.
    val compactWindowHeight = rememberWindowSize().height == HeightClass.Compact

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // Remote video (full screen)
        RemoteVideoView(
            bitmap = remoteVideoFrame,
            // 🌍 Was the literal "Video Call" — shown to the user as the peer's NAME.
            contactName = currentCall?.peerName ?: stringResource(R.string.video_call_title),
            peerProfilePicture = currentCall?.peerProfilePicture,
            peerPublicKey = currentCall?.peerPublicKey.orEmpty(),
            isReceiving = isReceivingVideo,
            remoteCameraPaused = isRemoteCameraPaused,
            duration = duration,
            onClick = { showControls = !showControls }
        )

        // 📶 Say what the picture is actually DOING. A frozen frame and a dead call
        // looked identical: the last decoded picture, forever, with no word about it.
        // The reflex is to hang up and call back — which throws away a perfectly good
        // VOICE call, which is why the second line says the audio is fine.
        // iOS `remoteVideoHealthBadge`, VideoCallView.swift:96-99 and :684-711.
        if (!isRemoteCameraPaused && (isRemoteVideoStalled || isRemoteVideoWeak) && !isInPipMode) {
            RemoteVideoHealthBadge(
                stalled = isRemoteVideoStalled,
                // iOS parks it 190 pt above the bottom, clear of its control stack.
                // A short window does not have 190 dp to give away.
                bottomPadding = if (compactWindowHeight) 76.dp else 190.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Local video (PiP) — adapts to phone orientation (hidden in system PiP)
        // 📹 Drawn as soon as we intend to send, not only once a frame exists: with
        // the old `localVideoFrame != null` gate a slow or failed camera presented as
        // "this app has no self-view". The empty box + spinner says "camera starting"
        // (iOS 59972bb did the same in VideoCallView.swift).
        if (isSendingVideo && !isInPipMode) {
            LocalVideoPreview(
                bitmap = localVideoFrame,
                // Spinner only while the camera might still be coming up; a reported
                // failure replaces it with the plain empty box (the Toast says why).
                cameraStarting = localVideoFrame == null && videoError == null && !cameraFailed,
                offset = pipOffset,
                isLandscape = isLandscape,
                onDrag = { delta ->
                    pipOffset = Offset(
                        (pipOffset.x + delta.x).coerceIn(20f, screenWidth.value - pipWidth - 20f),
                        (pipOffset.y + delta.y).coerceIn(80f, screenHeight.value - pipHeight - 60f)
                    )
                }
            )
        }

        // Incoming call: show Accept/Decline buttons (same pattern as VoiceCallScreen)
        val isIncomingRinging = callState == CallState.RINGING && currentCall?.isOutgoing == false

        if (isIncomingRinging && !isInPipMode) {
            // 80 dp of head-room + a 100 dp avatar + name + status + the answer
            // buttons + 60 dp of foot-room is ~386 dp of fixed content in a
            // Column that cannot scroll. A phone in landscape has ~363 dp of
            // usable height, so the Accept and Decline buttons fell off the
            // bottom — an incoming video call that could not be answered.
            // targetSdk 36 makes landscape reachable everywhere, so the two
            // paddings shrink when the window is short.
            //
            // The paddings shrinking is not on its own a guarantee: the avatar, the
            // 24 sp name and the button row are fixed, and a large system font scale
            // or a 300 dp multi-window pane still overflows a Column that cannot
            // scroll. `fillMaxSize().verticalScroll(...)` keeps `SpaceBetween` intact
            // in a tall window — the scroll modifier passes `maxHeight = Infinity`
            // down but preserves the `minHeight` `fillMaxSize` set — and lets the
            // Accept / Decline buttons be reached by scrolling in a short one. Same
            // shape as `IncomingCallOverlay.kt:149-155` and `CallScreen.kt:95-99`.
            val compactHeight = rememberWindowSize().height == HeightClass.Compact
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top: contact info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (compactHeight) 16.dp else 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar — profile picture if available, initials fallback
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        val profilePic = currentCall?.peerProfilePicture
                        val bitmap = remember(profilePic) {
                            profilePic?.let {
                                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = currentCall?.peerName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was a
                            // fixed blue→purple for every caller.
                            com.oshi.messenger.ui.theme.ContactAvatar(
                                publicKey = currentCall?.peerPublicKey.orEmpty(),
                                name = currentCall?.peerName,
                                size = 100.dp,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        // 🌍 Was the literal "Unknown" — the caller's name on the
                        // incoming-video-call screen, in English in all 34 locales.
                        text = currentCall?.peerName
                            ?: stringResource(R.string.notification_unknown_caller),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.call_incoming_video),
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Bottom: Accept / Decline buttons
                Column(
                    modifier = Modifier
                        .padding(bottom = if (compactHeight) 12.dp else 60.dp)
                        .navigationBarsPadding()
                ) {
                    IncomingVideoCallButtons(
                        onAccept = { viewModel.acceptCall() },
                        onDecline = { viewModel.declineCall() }
                    )
                }
            }
        }

        // Controls overlay (only show when call is active, not during incoming ringing or PiP)
        if (!isIncomingRinging && !isInPipMode) {
            // Auto-hiding controls (top bar + effect row)
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Top bar
                    TopControlBar(
                        contactName = currentCall?.peerName ?: "",
                        duration = duration,
                        bandwidth = outgoingBandwidth,
                        connectionQuality = connectionQuality,
                        jitterDepth = jitterBufferDepth,
                        currentQuality = currentQuality,
                        onBack = {
                            // 📹 Tell the peer to drop video too (0x0F) before
                            // we navigate back to the voice-only screen, so both
                            // sides return to voice-only in lockstep.
                            viewModel.stopVideo()
                            onBackToVoice()
                        },
                        onQualityClick = { showQualityPicker = true }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // 🎤 Voice effect chip row — pick a specific effect for OUTBOUND PCM.
                    // Same set + same DSP as iOS so the iPhone receiver hears the effect.
                    // 📐 Dropped in a short window: top bar (~104) + this row (~49) +
                    // the effect row (~69) + the 140 dp reserve is 362 dp of fixed
                    // content, more than a 320 dp landscape window has, so the weight
                    // collapsed and the effect buttons landed ON TOP of the mute /
                    // end-call row. Nothing is lost — the same effect is reachable from
                    // the cycle button in EffectControlsRow below.
                    if (!compactWindowHeight) {
                        VoiceEffectSelector(
                            current = voiceEffect,
                            onSelect = { viewModel.setVoiceEffect(it) },
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Effect controls
                    EffectControlsRow(
                        blurMode = blurMode,
                        currentQuality = currentQuality,
                        isSendingVideo = isSendingVideo,
                        isReceivingVideo = isReceivingVideo,
                        voiceEffect = voiceEffect,
                        onBlurClick = { showBlurPicker = true },
                        onQualityClick = { showQualityPicker = true },
                        onToggleSend = { viewModel.toggleSendingVideo() },
                        onToggleReceive = { viewModel.toggleReceivingVideo() },
                        onSwitchCamera = { viewModel.switchCamera() },
                        onVoiceEffectClick = { viewModel.cycleVoiceEffect() }
                    )

                    // Spacer to reserve space for the always-visible bottom controls.
                    // 📐 The bar's opaque content is the 72 dp end-call FAB plus 40 dp
                    // of bottom padding; 120 dp clears it in a short window while 140
                    // keeps the roomier gap on a normal phone.
                    Spacer(modifier = Modifier.height(if (compactWindowHeight) 120.dp else 140.dp))
                }
            }

            // ALWAYS VISIBLE: End call + mute + speaker (matching iOS — never hidden)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                            )
                        )
                        .padding(bottom = 40.dp, top = 20.dp)
                ) {
                    // Duration display (always visible)
                    if (duration > 0) {
                        Text(
                            text = formatDuration(duration),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    BottomControlBar(
                        isMuted = isMuted,
                        isSpeaker = isSpeaker,
                        onMuteToggle = { viewModel.toggleMute() },
                        onSpeakerToggle = { viewModel.toggleSpeaker() },
                        onEndCall = { viewModel.endCall() }
                    )
                }
            }
        }

        // Quality picker dialog
        if (showQualityPicker) {
            QualityPickerDialog(
                currentQuality = currentQuality,
                onSelectQuality = {
                    viewModel.setQuality(it)
                    showQualityPicker = false
                },
                onDismiss = { showQualityPicker = false }
            )
        }

        // Call ended summary overlay (3.8.2)
        if (showEndedSummary) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = callEndLabelText(summaryLabel),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    // A duration only exists for a call that was actually answered.
                    // iOS shows it on the same condition (VideoCallView.swift:261-266).
                    if (summaryDuration > 0L) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.video_call_duration, formatDuration(summaryDuration)),
                            fontSize = 15.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Blur picker dialog
        if (showBlurPicker) {
            BlurPickerDialog(
                currentMode = blurMode,
                intensity = blurIntensity,
                onSelectMode = {
                    viewModel.setBlurMode(it)
                    showBlurPicker = false
                },
                onIntensityChange = { viewModel.setBlurIntensity(it) },
                onDismiss = { showBlurPicker = false }
            )
        }
    }
}

// ============================================================================
// COMPONENTS
// ============================================================================

/**
 * 📶 What the peer's picture is actually doing, when it is not doing what it looks
 * like it is doing. Port of iOS `remoteVideoHealthBadge`
 * (`OSHI/VideoCallView.swift:684-711`), including its second line: the instinct on
 * seeing a frozen picture is to hang up and call back, and that throws away a
 * working voice call, so the badge says so.
 *
 * Not hit-testable — it floats over the video and must not eat the tap that brings
 * the controls back.
 */
@Composable
private fun RemoteVideoHealthBadge(
    stalled: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(bottom = bottomPadding)
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (stalled) Icons.Default.WifiOff else Icons.Default.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                text = stringResource(
                    if (stalled) R.string.call_video_rx_stalled else R.string.call_video_rx_weak
                ),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = stringResource(R.string.call_video_rx_audio_ok),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun RemoteVideoView(
    bitmap: android.graphics.Bitmap?,
    contactName: String,
    peerProfilePicture: ByteArray? = null,
    peerPublicKey: String = "",
    isReceiving: Boolean,
    remoteCameraPaused: Boolean = false,
    duration: Long,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.accessibility_remote_video),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Placeholder
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = remember(peerProfilePicture) {
                        peerProfilePicture?.let {
                            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = contactName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was a
                        // fixed blue→purple for every caller.
                        com.oshi.messenger.ui.theme.ContactAvatar(
                            publicKey = peerPublicKey,
                            name = contactName,
                            size = 120.dp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = contactName,
                    fontSize = 20.sp,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 📹 "Their camera is off" and "no picture is getting through" look
                // identical to the user unless we say which one it is — and the
                // reflex on the second reading is to hang up a call whose AUDIO is
                // perfectly fine. iOS separates them with `cameraPausedOverlay`
                // (VideoCallView.swift:1244-1293), fed by the same 0x0C / 0x07
                // signals Android has been tracking in `_isRemoteCameraPaused`
                // without ever showing.
                Text(
                    text = when {
                        remoteCameraPaused -> stringResource(R.string.video_call_camera_paused)
                        !isReceiving -> stringResource(R.string.video_call_waiting)
                        else -> stringResource(R.string.video_call_connecting)
                    },
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = formatDuration(duration),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun LocalVideoPreview(
    bitmap: android.graphics.Bitmap?,
    cameraStarting: Boolean = false,
    offset: Offset,
    isLandscape: Boolean = false,
    onDrag: (Offset) -> Unit
) {
    val width = if (isLandscape) 160.dp else 120.dp
    val height = if (isLandscape) 120.dp else 160.dp
    // The bitmap from VideoCallManager is rotated by sensor orientation only (assumes portrait).
    // When the device is rotated to landscape, the system rotates the UI, so we need to
    // counter-rotate the preview content by -90° to keep it upright relative to the user.
    val display = androidx.compose.ui.platform.LocalContext.current.display
    val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
    val previewRotationZ = when (rotation) {
        android.view.Surface.ROTATION_90 -> -90f
        android.view.Surface.ROTATION_270 -> 90f
        android.view.Surface.ROTATION_180 -> 180f
        else -> 0f
    }
    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(width, height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(Offset(dragAmount.x, dragAmount.y))
                }
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.accessibility_local_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = previewRotationZ }
            )
        } else if (cameraStarting) {
            // No frame yet. Icon-only, like iOS — no new string to translate.
            CircularProgressIndicator(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center),
                color = Color.White.copy(alpha = 0.7f),
                strokeWidth = 2.dp
            )
        }

        // Border
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.5f))
        ) {}
    }
}

@Composable
private fun TopControlBar(
    contactName: String,
    duration: Long,
    bandwidth: Int,
    connectionQuality: NetworkQuality,
    jitterDepth: Int,
    currentQuality: VideoQuality,
    onBack: () -> Unit,
    onQualityClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(top = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = stringResource(R.string.accessibility_back_to_voice),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Contact info
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = contactName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = formatDuration(duration),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Quality indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                // 👆 `detectDragGestures` fires on DRAG, never on a tap: it waits for
                // touch slop to be crossed. This chip therefore did nothing when
                // tapped and opened the quality picker only if you smeared a finger
                // across it. Same mistake in three more places in this file.
                .clickable { onQualityClick() }
                .padding(8.dp)
        ) {
            Icon(
                imageVector = when (connectionQuality) {
                    NetworkQuality.EXCELLENT -> Icons.Default.SignalWifi4Bar
                    NetworkQuality.GOOD -> Icons.Default.NetworkWifi3Bar
                    NetworkQuality.FAIR -> Icons.Default.NetworkWifi3Bar
                    NetworkQuality.POOR, NetworkQuality.VERY_POOR -> Icons.Default.SignalWifi0Bar
                    NetworkQuality.RECONNECTING -> Icons.Default.SignalWifi0Bar
                    NetworkQuality.DISCONNECTED -> Icons.Default.WifiOff
                },
                contentDescription = null,
                tint = when (connectionQuality) {
                    NetworkQuality.EXCELLENT, NetworkQuality.GOOD -> OSHIGreen
                    NetworkQuality.FAIR -> Color(0xFFFFEB3B)
                    NetworkQuality.POOR -> Color(0xFFFF9800)
                    NetworkQuality.VERY_POOR -> OSHIRed
                    NetworkQuality.RECONNECTING -> Color(0xFFFF9800)
                    NetworkQuality.DISCONNECTED -> Color.Gray
                },
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "${bandwidth}k",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
            if (jitterDepth > 0) {
                Text(
                    text = "buf:$jitterDepth",
                    fontSize = 10.sp,
                    color = if (jitterDepth < 2) Color(0xFFFF9800) else Color.Green
                )
            }
        }
    }
}

@Composable
private fun EffectControlsRow(
    blurMode: BlurMode,
    currentQuality: VideoQuality,
    isSendingVideo: Boolean,
    isReceivingVideo: Boolean,
    voiceEffect: CallVoiceEffectProcessor.VoiceEffect,
    onBlurClick: () -> Unit,
    onQualityClick: () -> Unit,
    onToggleSend: () -> Unit,
    onToggleReceive: () -> Unit,
    onSwitchCamera: () -> Unit,
    onVoiceEffectClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Blur mode
        EffectButton(
            icon = Icons.Default.BlurOn,
            label = blurMode.displayName,
            onClick = onBlurClick
        )

        // Quality
        EffectButton(
            icon = Icons.Default.Tune,
            label = currentQuality.name,
            onClick = onQualityClick
        )

        // Voice effect
        EffectButton(
            icon = Icons.Default.RecordVoiceOver,
            label = voiceEffect.displayName,
            isActive = voiceEffect != CallVoiceEffectProcessor.VoiceEffect.NORMAL,
            onClick = onVoiceEffectClick
        )

        // Send video toggle
        EffectButton(
            icon = if (isSendingVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
            label = stringResource(if (isSendingVideo) R.string.video_call_camera_on else R.string.video_call_camera_off),
            isActive = !isSendingVideo,
            onClick = onToggleSend
        )

        // Switch camera
        EffectButton(
            icon = Icons.Default.Cameraswitch,
            label = stringResource(R.string.video_call_switch_camera),
            onClick = onSwitchCamera
        )
    }
}

@Composable
private fun EffectButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    // 👆 The wrapper used to carry `detectDragGestures { _, _ -> onClick() }`, which
    // fires once per pointer-move event: dragging a finger across this row toggled
    // the camera or flipped it dozens of times. The IconButton below already handles
    // the tap, so the wrapper needs no gesture at all.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(50.dp)
                .background(
                    if (isActive) Color.White else Color.Black.copy(alpha = 0.5f),
                    CircleShape
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.Black else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun BottomControlBar(
    isMuted: Boolean,
    isSpeaker: Boolean,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onEndCall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // (N12, Anatole 13/11) Same Expressive shapes as the voice call:
        // pill toggles that spring to a rounded square when on, a wide red
        // hang-up pill. No labels here — the video is the content, and this
        // bar already had none.
        com.oshi.messenger.ui.components.ExpressiveCallToggle(
            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = stringResource(if (isMuted) R.string.call_unmute else R.string.call_mute),
            isActive = isMuted,
            onClick = onMuteToggle,
            width = 64.dp,
            height = 56.dp,
            activeContent = Color.Black,
            showLabel = false
        )

        com.oshi.messenger.ui.components.ExpressiveCallPill(
            icon = Icons.Default.CallEnd,
            contentDescription = stringResource(R.string.accessibility_end_call),
            containerColor = OSHIRed,
            onClick = onEndCall,
            width = 112.dp,
            height = 64.dp
        )

        com.oshi.messenger.ui.components.ExpressiveCallToggle(
            icon = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
            label = stringResource(if (isSpeaker) R.string.speaker_off else R.string.speaker_on),
            isActive = isSpeaker,
            onClick = onSpeakerToggle,
            width = 64.dp,
            height = 56.dp,
            activeContent = Color.Black,
            showLabel = false
        )
    }
}

@Composable
private fun QualityPickerDialog(
    currentQuality: VideoQuality,
    onSelectQuality: (VideoQuality) -> Unit,
    onDismiss: () -> Unit
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_quality_title)) },
        text = {
            Column {
                VideoQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 👆 was `detectDragGestures` — a tap on the row label did
                            // nothing; only the radio button itself responded.
                            .clickable { onSelectQuality(quality) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = quality == currentQuality,
                            onClick = { onSelectQuality(quality) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = quality.name,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${quality.resolution.width}x${quality.resolution.height} @ ${quality.frameRate}fps",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun BlurPickerDialog(
    currentMode: BlurMode,
    intensity: Float,
    onSelectMode: (BlurMode) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_background_blur)) },
        text = {
            Column {
                BlurMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 👆 was `detectDragGestures` — see QualityPickerDialog.
                            .clickable { onSelectMode(mode) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = { onSelectMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = mode.displayName,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = when (mode) {
                                    BlurMode.NONE -> stringResource(R.string.blur_none)
                                    BlurMode.BACKGROUND_BLUR -> stringResource(R.string.blur_background)
                                    BlurMode.FULL_BLUR -> stringResource(R.string.blur_full)
                                    BlurMode.PIXELATE -> stringResource(R.string.blur_pixelate)
                                    BlurMode.HEAVY_PIXELATE -> stringResource(R.string.blur_heavy_pixelate)
                                    BlurMode.ULTRA_PIXELATE -> stringResource(R.string.blur_ultra_pixelate)
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Intensity slider (shown when a blur/pixelate effect is active)
                if (currentMode != BlurMode.NONE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.blur_intensity, (intensity * 100).toInt()),
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    Slider(
                        value = intensity,
                        onValueChange = onIntensityChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun IncomingVideoCallButtons(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Decline
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // (N12) Wide pill, as the Phone app draws answer / decline.
            com.oshi.messenger.ui.components.ExpressiveCallPill(
                icon = Icons.Default.CallEnd,
                contentDescription = stringResource(R.string.accessibility_decline),
                containerColor = OSHIRed,
                onClick = onDecline,
                width = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerWidth,
                height = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerHeight
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.call_short_decline),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // Accept
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            com.oshi.messenger.ui.components.ExpressiveCallPill(
                icon = Icons.Default.Videocam,
                contentDescription = stringResource(R.string.accessibility_accept_video),
                containerColor = OSHIGreen,
                onClick = onAccept,
                width = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerWidth,
                height = com.oshi.messenger.ui.components.ExpressiveCallDefaults.AnswerHeight
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.accessibility_accept),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
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
