package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.service.*
import com.oshi.messenger.service.audio.CallVoiceEffectProcessor
import com.oshi.messenger.service.audio.JitterBufferStats
import com.oshi.messenger.service.audio.NetworkQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VoiceCallViewModel - ViewModel for VoiceCallScreen
 *
 * Provides state and actions for voice calls.
 */
@HiltViewModel
class VoiceCallViewModel @Inject constructor(
    private val callManager: EnhancedCallManager,
    private val database: AppDatabase
) : ViewModel() {

    val callState: StateFlow<CallState> = callManager.callState
    val currentCall: StateFlow<CurrentCall?> = callManager.currentCall
    val isMuted: StateFlow<Boolean> = callManager.isMuted
    val isSpeaker: StateFlow<Boolean> = callManager.isSpeakerOn
    val duration: StateFlow<Long> = callManager.callDuration
    val connectionQuality: StateFlow<NetworkQuality> = callManager.connectionQuality
    val connectionType: StateFlow<ConnectionType> = callManager.connectionType
    /** __CALL_WAKE_2026_11_22__ did the callee's phone get the call (outgoing ring only). */
    val calleeReach: StateFlow<com.oshi.messenger.service.CallReach?> = callManager.calleeReach
    val outgoingRingingSince: StateFlow<Long> = callManager.outgoingRingingSince
    val jitterStats: StateFlow<JitterBufferStats?> = callManager.jitterBufferStats

    /**
     * Round-trip time in ms, from the keepalive. The manager has measured it all
     * along; neither call ViewModel re-exposed it, so the number iOS puts on the
     * quality chip on every call (`VoiceCallView.swift:450-456`) was never rendered
     * on Android.
     */
    val latencyMs: StateFlow<Long> = callManager.latencyMs

    /**
     * __VOICE_METER_2026_09_22__ My mic level and the peer's voice level, 0…1 on a dB
     * scale, 20 Hz — the call-screen bars follow these instead of a timer.
     */
    val micLevel: StateFlow<Float> = callManager.micLevel
    val remoteLevel: StateFlow<Float> = callManager.remoteLevel

    /**
     * Why the last call finished, so the screen can say it instead of vanishing.
     * See [EnhancedCallManager.lastEndReason] and [CallEndReasonDisplay].
     */
    val lastEndReason: StateFlow<CallEndReason?> = callManager.lastEndReason

    // Video upgrade from peer (0x0E) — tasks 2.2.3-2.2.5
    val pendingVideoUpgrade: StateFlow<Boolean> = callManager.pendingVideoUpgrade
    val pendingVideoUpgradeFrom: StateFlow<String?> = callManager.pendingVideoUpgradeFrom
    val isAwaitingVideoUpgrade: StateFlow<Boolean> = callManager.isAwaitingVideoUpgrade
    val videoUpgradeDeclined: StateFlow<String?> = callManager.videoUpgradeDeclined

    // 🎤 Voice effect — applied to outbound PCM, mirrors iOS CallVoiceEffect parity
    val voiceEffect: StateFlow<CallVoiceEffectProcessor.VoiceEffect> = callManager.currentVoiceEffect

    fun startCall(publicKey: String, isVideo: Boolean) {
        viewModelScope.launch {
            val contact = database.contactDao().getContactByPublicKey(publicKey)
            val name = com.oshi.messenger.data.model.PeerNicknameStore.displayName(contact?.alias, publicKey) // __SHARED_NICKNAME_2026_09_22__
            // __BLOCKED_BY_PEER_2026_09_24__ the Result was ignored: a refused start left the
            // screen hanging in IDLE with nothing said. The screen shows it and leaves.
            callManager.startCall(publicKey, name, isVideo).exceptionOrNull()?.let {
                _startRefusal.value = com.oshi.messenger.service.CallStartRefusal.messageRes(it)
            }
        }
    }

    private val _startRefusal = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    /** String res of a start refused before anything was sent ("Contact unavailable"), else null. */
    val startRefusal: StateFlow<Int?> = _startRefusal

    fun acceptCall() {
        viewModelScope.launch {
            callManager.acceptCall()
        }
    }

    fun declineCall() {
        viewModelScope.launch {
            callManager.declineCall()
        }
    }

    fun endCall() {
        callManager.endCall()
    }

    fun toggleMute() {
        callManager.toggleMute()
    }

    fun toggleSpeaker() {
        callManager.toggleSpeaker()
    }

    fun requestVideoUpgrade() {
        callManager.requestVideoUpgrade()
    }

    /**
     * [shareCamera] `false` = « regarder seulement »: on accepte la vidéo mais
     * on garde notre caméra éteinte (0x05), et le pair affiche notre cache au
     * lieu d'attendre des trames. iOS propose ce troisième choix depuis le
     * début; côté Android le gestionnaire savait l'émettre mais rien ne pouvait
     * le demander.
     */
    @JvmOverloads
    fun acceptVideoUpgrade(shareCamera: Boolean = true) {
        callManager.acceptVideoUpgrade(shareCamera)
    }

    fun declineVideoUpgrade() {
        callManager.declineVideoUpgrade()
    }

    fun stopVideo() {
        callManager.stopVideo()
    }

    fun consumeVideoUpgradeDeclined() {
        callManager.consumeVideoUpgradeDeclined()
    }

    fun cycleVoiceEffect() {
        callManager.cycleVoiceEffect()
    }

    fun setVoiceEffect(effect: CallVoiceEffectProcessor.VoiceEffect) {
        callManager.setVoiceEffect(effect)
    }
}
