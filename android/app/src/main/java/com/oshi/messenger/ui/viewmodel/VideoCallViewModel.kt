package com.oshi.messenger.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.service.*
import com.oshi.messenger.service.audio.CallVoiceEffectProcessor
import com.oshi.messenger.service.audio.NetworkQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VideoCallViewModel - ViewModel for VideoCallScreen
 *
 * Provides state and actions for video calls.
 * Video lifecycle is managed by EnhancedCallManager (starts/stops automatically).
 */
@HiltViewModel
class VideoCallViewModel @Inject constructor(
    private val callManager: EnhancedCallManager,
    private val database: AppDatabase
) : ViewModel() {

    // Call state from EnhancedCallManager
    val callState: StateFlow<CallState> = callManager.callState
    val currentCall: StateFlow<CurrentCall?> = callManager.currentCall
    val isMuted: StateFlow<Boolean> = callManager.isMuted
    val isSpeaker: StateFlow<Boolean> = callManager.isSpeakerOn
    val duration: StateFlow<Long> = callManager.callDuration
    val connectionQuality: StateFlow<NetworkQuality> = callManager.connectionQuality

    // Video state (delegated via EnhancedCallManager → VideoCallManager)
    val localVideoFrame: StateFlow<Bitmap?> = callManager.localVideoFrame
    val remoteVideoFrame: StateFlow<Bitmap?> = callManager.remoteVideoFrame
    val currentQuality: StateFlow<VideoQuality> = callManager.videoQuality
    val blurMode: StateFlow<BlurMode> = callManager.videoBlurMode
    val blurIntensity: StateFlow<Float> = callManager.videoBlurIntensity
    val isSendingVideo: StateFlow<Boolean> = callManager.isSendingVideo
    val isReceivingVideo: StateFlow<Boolean> = callManager.isReceivingVideo
    val isVideoEnabled: StateFlow<Boolean> = callManager.isVideoEnabled
    val outgoingBandwidth: StateFlow<Int> = callManager.videoCallManager.outgoingBandwidth
    val jitterBufferDepth: StateFlow<Int> = callManager.videoCallManager.jitterBufferDepth
    val voiceEffect: StateFlow<CallVoiceEffectProcessor.VoiceEffect> = callManager.currentVoiceEffect

    /** 📹 Local capture failed (permission / no camera / no frames). Consumed by the screen. */
    val videoError: StateFlow<String?> = callManager.videoError

    /**
     * 📹 The peer told us their camera is off — 0x0C `videoPaused` (they
     * backgrounded) or the 0x07 `CAMERA_OFF` upgrade code (they turned it off but
     * are still watching). The manager has tracked this since the 0x0C/0x0D port,
     * but nothing rendered it: the screen showed the generic "Waiting for video…"
     * placeholder, which is what it also shows for a broken connection. iOS
     * distinguishes the two with `cameraPausedOverlay`
     * (VideoCallView.swift:91-93, :1244-1293).
     */
    val isRemoteCameraPaused: StateFlow<Boolean> = callManager.isRemoteCameraPaused

    /**
     * 📶 Measured RX health. `isReceivingVideo` above is a one-way latch, so a peer
     * whose stream dies still reads as "receiving" and the screen holds the last
     * decoded picture forever with nothing said about it. See [RemoteVideoHealth].
     */
    val isRemoteVideoStalled: StateFlow<Boolean> = callManager.videoCallManager.isRemoteVideoStalled
    val remoteVideoFps: StateFlow<Int> = callManager.videoCallManager.remoteVideoFps

    /** Why the last call finished — see [EnhancedCallManager.lastEndReason]. */
    val lastEndReason: StateFlow<CallEndReason?> = callManager.lastEndReason

    fun consumeVideoError() = callManager.consumeVideoError()

    private val _startRefusal = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    /** String res of a start refused before anything was sent ("Contact unavailable"), else null. */
    val startRefusal: StateFlow<Int?> = _startRefusal

    fun startVideoCall(publicKey: String) {
        viewModelScope.launch {
            if (callState.value == CallState.IDLE) {
                val contact = database.contactDao().getContactByPublicKey(publicKey)
                val name = com.oshi.messenger.data.model.PeerNicknameStore.displayName(contact?.alias, publicKey) // __SHARED_NICKNAME_2026_09_22__
                callManager.startCall(publicKey, name, isVideoCall = true).exceptionOrNull()?.let {   // __BLOCKED_BY_PEER_2026_09_24__
                    _startRefusal.value = com.oshi.messenger.service.CallStartRefusal.messageRes(it)
                }
            }
            // Video starts automatically when call connects (in EnhancedCallManager)
        }
    }

    fun acceptCall() {
        viewModelScope.launch {
            callManager.acceptCall()
            // Video starts automatically for video calls
        }
    }

    fun declineCall() {
        viewModelScope.launch {
            callManager.declineCall()
        }
    }

    fun endCall() {
        callManager.endCall()
        // Video stops automatically in endCall()
    }

    /**
     * 📹 "Stop video" — sends 0x0F to peer, both sides revert to voice-only.
     * Audio path is untouched. Use this when the user taps the back/stop-video
     * button in VideoCallScreen so the peer's UI also flips back to voice.
     */
    fun stopVideo() {
        callManager.stopVideo()
    }

    /** True while we are observing peer-accept (used by VoiceCallScreen). */
    val isAwaitingVideoUpgrade: StateFlow<Boolean> = callManager.isAwaitingVideoUpgrade

    fun toggleMute() {
        callManager.toggleMute()
    }

    fun toggleSpeaker() {
        callManager.toggleSpeaker()
    }

    fun toggleSendingVideo() {
        callManager.toggleVideo()
    }

    fun toggleReceivingVideo() {
        callManager.videoCallManager.toggleReceivingVideo()
    }

    fun switchCamera() {
        callManager.switchCamera()
    }

    fun setQuality(quality: VideoQuality) {
        callManager.setVideoQuality(quality)
    }

    fun setBlurMode(mode: BlurMode) {
        callManager.setVideoBlurMode(mode)
    }

    fun setBlurIntensity(intensity: Float) {
        callManager.setVideoBlurIntensity(intensity)
    }

    fun cycleVoiceEffect() {
        callManager.cycleVoiceEffect()
    }

    fun setVoiceEffect(effect: CallVoiceEffectProcessor.VoiceEffect) {
        callManager.setVoiceEffect(effect)
    }
}
