package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.service.EnhancedCallManager
import com.oshi.messenger.service.CallState
import com.oshi.messenger.service.CurrentCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CallViewModel - Legacy ViewModel migrated to use EnhancedCallManager
 *
 * Uses EnhancedCallManager (iOS-compatible G.726 binary protocol)
 * instead of legacy CallManager (JSON protocol).
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callManager: EnhancedCallManager,
    private val database: AppDatabase
) : ViewModel() {

    val callState: StateFlow<CallState> = callManager.callState
    val currentCall: StateFlow<CurrentCall?> = callManager.currentCall
    val isMuted: StateFlow<Boolean> = callManager.isMuted
    val isSpeaker: StateFlow<Boolean> = callManager.isSpeakerOn
    val duration: StateFlow<Long> = callManager.callDuration
    val connectionQuality = callManager.connectionQuality
    val connectionType = callManager.connectionType
    val latencyMs = callManager.latencyMs
    val bitrateLevel = callManager.bitrateLevel
    val callQualityStats = callManager.callQualityStats
    val isBluetoothOn = callManager.isBluetoothOn

    fun startCall(publicKey: String, isVideo: Boolean) {
        viewModelScope.launch {
            val contact = database.contactDao().getContactByPublicKey(publicKey)
            val name = com.oshi.messenger.data.model.PeerNicknameStore.displayName(contact?.alias, publicKey) // __SHARED_NICKNAME_2026_09_22__
            callManager.startCall(publicKey, name, isVideo).exceptionOrNull()?.let {   // __BLOCKED_BY_PEER_2026_09_24__
                _startRefusal.value = com.oshi.messenger.service.CallStartRefusal.messageRes(it)
            }
        }
    }

    private val _startRefusal = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
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

    fun toggleBluetooth() {
        callManager.toggleBluetooth()
    }
}
