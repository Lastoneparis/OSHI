package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.service.ScheduledCallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ScheduledCallViewModel — wraps ScheduledCallManager for UI (task 3.1.6)
 */
@HiltViewModel
class ScheduledCallViewModel @Inject constructor(
    private val scheduledCallManager: ScheduledCallManager,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    val scheduledCalls: StateFlow<List<ScheduledCallManager.ScheduledCallEvent>> =
        scheduledCallManager.scheduledCalls

    fun create(peerPublicKey: String, scheduledTime: Long, isVideo: Boolean) {
        scheduledCallManager.createScheduledCall(
            peerPublicKey = peerPublicKey,
            scheduledTime = scheduledTime,
            isVideoCall = isVideo,
            myPublicKey = cryptoManager.getPublicKeyString()
        )
    }

    fun respond(eventId: String, accept: Boolean) {
        scheduledCallManager.respondToScheduledCall(eventId, accept)
    }

    fun cancel(eventId: String) {
        scheduledCallManager.cancelScheduledCall(eventId)
    }
}
