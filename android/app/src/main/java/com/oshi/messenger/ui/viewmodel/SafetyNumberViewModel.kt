package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.service.SafetyNumber
import com.oshi.messenger.service.SafetyNumberManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What a scan of the other party's code turned out to be.
 *
 * Deliberately NOT [com.oshi.messenger.service.VerificationResult]: that type carries a
 * `message` field holding a hardcoded English sentence ("Safety number mismatch - possible
 * MITM attack!"). Surfacing it would put untranslated English on the one screen whose whole
 * job is to be trusted, in all 33 locales. Only the boolean crosses into the UI; the words
 * come from `safety_number_match` / `safety_number_mismatch`.
 */
enum class ScanOutcome { Match, Mismatch }

/**
 * Backing state for `SafetyNumberScreen`.
 *
 * [SafetyNumberManager] is the only thing that computes or stores anything here — this
 * class does no crypto of its own, so Android and iOS cannot drift apart the way the two
 * pre-existing Android safety-number implementations did.
 *
 * Every call into the manager touches `SharedPreferences` (a disk read) or SHA-256, so
 * both are done off the main thread; the screen only ever reads the flows.
 */
@HiltViewModel
class SafetyNumberViewModel @Inject constructor(
    private val safetyNumberManager: SafetyNumberManager
) : ViewModel() {

    private val _safetyNumber = MutableStateFlow<SafetyNumber?>(null)
    val safetyNumber: StateFlow<SafetyNumber?> = _safetyNumber.asStateFlow()

    private val _isVerified = MutableStateFlow(false)
    val isVerified: StateFlow<Boolean> = _isVerified.asStateFlow()

    /**
     * True when this contact was verified once and their key has since changed — the
     * single most important thing this screen can tell anyone, and the reason
     * `safety_number_changed` exists.
     */
    private val _keyChanged = MutableStateFlow(false)
    val keyChanged: StateFlow<Boolean> = _keyChanged.asStateFlow()

    private val _scanOutcome = MutableStateFlow<ScanOutcome?>(null)
    val scanOutcome: StateFlow<ScanOutcome?> = _scanOutcome.asStateFlow()

    /**
     * Guards against the recomposition-driven reload: the screen calls [load] from a
     * `LaunchedEffect` keyed on the two keys, and the caller's own public key arrives
     * asynchronously (it is read from DataStore), so the effect legitimately re-runs.
     * Without this the safety number would be recomputed on every arrival.
     */
    private var loadedFor: Pair<String, String>? = null

    /**
     * @param myPublicKey `MainViewModel.publicKey`, which starts empty and is filled in
     *   once DataStore has been read. An empty key is not an error state, just "not yet".
     */
    fun load(myPublicKey: String, theirPublicKey: String) {
        if (myPublicKey.isEmpty() || theirPublicKey.isEmpty()) return
        val pair = myPublicKey to theirPublicKey
        if (loadedFor == pair) return
        loadedFor = pair

        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val number = safetyNumberManager.generateSafetyNumber(myPublicKey, theirPublicKey)
                Triple(
                    number,
                    safetyNumberManager.isContactVerified(theirPublicKey),
                    safetyNumberManager.hasKeyChanged(theirPublicKey, number.fingerprint)
                )
            }
            _safetyNumber.value = loaded.first
            _isVerified.value = loaded.second
            _keyChanged.value = loaded.third
        }
    }

    /**
     * Hand the raw contents of a scanned QR to the manager.
     *
     * The comparison is byte equality against the payload we expect, done by
     * [SafetyNumberManager.verifySafetyNumber] — nothing is parsed or re-derived here, so
     * an arbitrary non-OSHI QR simply reads as a mismatch rather than crashing or being
     * quietly accepted.
     *
     * A match marks the contact verified in the same step. Making the user scan and *then*
     * press a second button is how a half-verified contact happens.
     */
    fun onCodeScanned(scannedQRData: String) {
        val number = _safetyNumber.value ?: return
        viewModelScope.launch {
            val matched = withContext(Dispatchers.IO) {
                safetyNumberManager.verifySafetyNumber(
                    scannedQRData = scannedQRData,
                    expectedMyPublicKey = number.myPublicKey,
                    expectedTheirPublicKey = number.theirPublicKey
                ).isVerified
            }
            _scanOutcome.value = if (matched) ScanOutcome.Match else ScanOutcome.Mismatch
            if (matched) {
                persistVerification(number)
            }
        }
    }

    /** Manual confirmation, for the pair who read the digits to each other out loud. */
    fun markVerified() {
        val number = _safetyNumber.value ?: return
        viewModelScope.launch { persistVerification(number) }
    }

    fun dismissScanOutcome() {
        _scanOutcome.value = null
    }

    private suspend fun persistVerification(number: SafetyNumber) {
        withContext(Dispatchers.IO) {
            safetyNumberManager.markContactVerified(number.theirPublicKey, number.fingerprint)
        }
        _isVerified.value = true
        // The stored fingerprint is now the current one by construction, so any
        // "their key changed" warning this screen was showing is resolved.
        _keyChanged.value = false
    }
}
