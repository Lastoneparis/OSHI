package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.network.web.WebConnectionState
import com.oshi.messenger.network.web.WebSession
import com.oshi.messenger.network.web.WebSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WebSessionViewModel @Inject constructor(
    private val webSessionManager: WebSessionManager
) : ViewModel() {

    val connectionState: StateFlow<WebConnectionState> = webSessionManager.connectionState
    val activeSessions: StateFlow<List<WebSession>> = webSessionManager.activeSessions
    val isLoading: StateFlow<Boolean> = webSessionManager.isLoading
    val lastRefresh: StateFlow<Long?> = webSessionManager.lastRefresh

    // ⚰️ `qrBitmap` + `generateQRCode()` DELETED (2026-08-13 cross-file integration,
    // item H). Unreachable — no screen collected the flow — and `generateQRCode()`
    // called `WebSessionManager.generatePairingQR()`, which was a no-op returning null
    // and has been deleted with it. OSHI web pairing is scan-the-BROWSER's-QR
    // ([handleScannedQR] → `validateQRCode`), so the phone never generates one.

    /** Result of the most recent QR scan; consumed by the UI then cleared. */
    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult.asStateFlow()

    data class ScanResult(
        val success: Boolean,
        val message: String,
        // __WEBSESSION_E2E_2026_09_23__ what to tell the user, truthfully, per mode.
        val code: WebSessionManager.ResultCode = if (success) WebSessionManager.ResultCode.LINKED_V1 else WebSessionManager.ResultCode.FAILED,
        val sas: String? = null,
    )

    init {
        // Refresh the session list when the screen first opens
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { webSessionManager.fetchActiveSessions() }
    }

    /** Validate a QR scan result against the relay. */
    fun handleScannedQR(content: String) {
        viewModelScope.launch {
            val r = webSessionManager.validateQRCode(content)
            _scanResult.value = ScanResult(r.success, r.message, r.code, r.sas)
        }
    }

    fun clearScanResult() { _scanResult.value = null }

    fun connectToWeb() {
        webSessionManager.connectToWeb()
    }

    fun terminateSession(sessionId: String) {
        viewModelScope.launch { webSessionManager.revokeSession(sessionId) }
    }

    fun terminateAllSessions() {
        viewModelScope.launch { webSessionManager.revokeAllSessions() }
    }

}
