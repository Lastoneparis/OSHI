package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.network.encryption.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The safety-number algorithm, byte-for-byte iOS `SafetyNumber.generate`
 * (`OSHI/SafetyNumber.swift:21-39`) and `verify` (`:41-43`).
 *
 * Android had TWO implementations and neither matched iOS: this file hashed
 * `"0:k1:k2"` with SHA-512 iterated 5200 times and remapped hex chars to digits, and
 * `CryptoManager.generateSafetyNumber` did a third thing. iOS's verify is EXACT BYTE
 * EQUALITY of the QR payload, so nothing either produced could ever verify against an
 * iPhone.
 *
 *   1. keys      = sort([ourKey, theirKey])       lexicographic, on the base64 STRING
 *   2. combined  = keys[0] + keys[1]              no separator, no version tag
 *   3. hash      = SHA-256(UTF-8(combined))       ONE pass
 *   4. digits    = "%02d" of (byte % 100) over hash[0..29]      → 60 digits
 *   5. display   = 12 groups of 5, single-space separated
 *   6. qr        = UTF-8("SAFETY:v1:" + combined)
 *
 * Kept free of Android APIs so it is unit-testable against iOS-derived vectors.
 */
object SafetyNumberCodec {

    const val QR_PREFIX = "SAFETY:v1:"

    /** The 60-digit numeric fingerprint, unformatted. */
    fun fingerprint(ourKey: String, theirKey: String): String {
        val combined = combined(ourKey, theirKey)
        val hash = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
        // `and 0xFF` is load-bearing: Kotlin's Byte is SIGNED, so `byte % 100` on a
        // negative byte yields a negative remainder and "%02d" emits e.g. "-7" — three
        // characters, which desynchronises the whole 60-digit string. Swift's UInt8 is
        // unsigned and never hits it.
        return hash.take(30).joinToString("") { "%02d".format((it.toInt() and 0xFF) % 100) }
    }

    /** The sorted, separator-less pre-image. Sorts the DISPLAYED base64, never a normalised form. */
    fun combined(ourKey: String, theirKey: String): String {
        val keys = listOf(ourKey, theirKey).sorted()
        return keys[0] + keys[1]
    }

    fun displayCode(fingerprint: String): String = fingerprint.chunked(5).joinToString(" ")

    /** What the QR encodes: the COMBINED KEYS, not the fingerprint. */
    fun qrPayload(ourKey: String, theirKey: String): String = QR_PREFIX + combined(ourKey, theirKey)

    /** iOS compares the scanned BYTES; it never re-derives (`OSHI/SafetyNumber.swift:41-43`). */
    fun verify(scannedPayload: String, ourKey: String, theirKey: String): Boolean =
        scannedPayload == qrPayload(ourKey, theirKey)
}

/**
 * SafetyNumberManager - Compatible with iOS SafetyNumber.swift
 * Generates and verifies safety numbers for contact verification
 *
 * NOTE: neither platform has a screen for this. `SafetyNumberView`
 * (`OSHI/SafetyNumber.swift:57-252`) has zero references anywhere in the iOS tree, and
 * this manager is referenced only by its DI provider. The algorithm is ported so the
 * two agree whenever a screen appears; building one is a product decision on both sides.
 */
@Singleton
class SafetyNumberManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val database: AppDatabase
) {
    /** Fire-and-forget writer for the contact row; verification must not block the UI. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    companion object {
        private const val TAG = "SafetyNumberManager"
    }

    // Storage for verified contacts
    private val prefs = context.getSharedPreferences("safety_numbers", Context.MODE_PRIVATE)

    /**
     * Generate safety number between two users
     * This creates a shared safety number that both parties can verify
     */
    fun generateSafetyNumber(myPublicKey: String, theirPublicKey: String): SafetyNumber {
        val fingerprint = SafetyNumberCodec.fingerprint(myPublicKey, theirPublicKey)
        return SafetyNumber(
            displayCode = SafetyNumberCodec.displayCode(fingerprint),
            fingerprint = fingerprint,
            qrData = SafetyNumberCodec.qrPayload(myPublicKey, theirPublicKey),
            myPublicKey = myPublicKey,
            theirPublicKey = theirPublicKey
        )
    }

    /**
     * Verify a scanned safety number matches
     */
    fun verifySafetyNumber(
        scannedQRData: String,
        expectedMyPublicKey: String,
        expectedTheirPublicKey: String
    ): VerificationResult {
        // Byte equality against the expected payload — never a re-derivation. Splitting
        // on ':' into three parts could not work at all: an iOS code IS
        // "SAFETY:v1:<combined>", which already contains two colons, so every real scan
        // parsed into 4 parts and was rejected as "Invalid QR code format".
        return if (SafetyNumberCodec.verify(scannedQRData, expectedMyPublicKey, expectedTheirPublicKey)) {
            VerificationResult(true, "Safety number verified successfully")
        } else {
            VerificationResult(false, "Safety number mismatch - possible MITM attack!")
        }
    }

    /**
     * Mark a contact as verified
     */
    fun markContactVerified(theirPublicKey: String, fingerprint: String) {
        // Two stores, deliberately, because they answer different questions.
        //
        // Prefs keep the FINGERPRINT: that is what lets `hasKeyChanged` notice later
        // that the person we verified is no longer the person we are talking to.
        prefs.edit().putString("verified_$theirPublicKey", fingerprint).apply()

        // The contact row keeps the BOOLEAN, because that is what the interface reads.
        // Until this existed the two were disconnected: scanning a code wrote prefs
        // only, `Contact.isVerified` stayed false, and every badge in the app kept
        // showing the contact as unverified straight after verifying them.
        ioScope.launch {
            runCatching { database.contactDao().setVerified(theirPublicKey, true) }
                .onFailure { Log.w(TAG, "Verified in prefs but contact row not updated: ${it.message}") }
        }
        Log.d(TAG, "Marked contact as verified: ${theirPublicKey.take(16)}...")
    }

    /**
     * Check if contact is verified
     */
    fun isContactVerified(theirPublicKey: String): Boolean {
        return prefs.contains("verified_$theirPublicKey")
    }

    /**
     * Check if contact's key has changed since verification
     */
    fun hasKeyChanged(theirPublicKey: String, currentFingerprint: String): Boolean {
        val storedFingerprint = prefs.getString("verified_$theirPublicKey", null) ?: return false
        return storedFingerprint != currentFingerprint
    }

    /**
     * Clear verification for a contact
     */
    fun clearVerification(theirPublicKey: String) {
        prefs.edit().remove("verified_$theirPublicKey").apply()
    }

    // ------------------------------------------------------------------ proactive detection
    //
    // [hasKeyChanged] above is correct and has been for as long as it has existed. Its only
    // caller outside this class was `SafetyNumberViewModel.load`, i.e. the safety-number
    // screen, so a key substitution was detected only by a user who already suspected one.
    // Everything below is the OCCASION for that check rather than a second copy of it: the
    // inbound message path calls [noteObservedIdentity], and what it finds arrives here.
    //
    // See [KeyChangeWatch] for why the baseline lives under its own prefs key instead of
    // `verified_` (short version: `isContactVerified` is `contains("verified_…")`, so seeding
    // that key would forge a verification badge for every contact who ever sent a message).

    /** A contact is not who they were. Held until [acknowledgeKeyChange]. */
    data class KeyChangeAlert(
        val theirPublicKey: String,
        val previousFingerprint: String,
        val currentFingerprint: String,
        /**
         * True when the previous fingerprint is one the user confirmed in person. Same event,
         * very different sentence: "their key changed" versus "the person you verified is not
         * the person you are talking to".
         */
        val wasVerified: Boolean
    )

    private val _keyChanges = MutableStateFlow<List<KeyChangeAlert>>(emptyList())

    /** What the UI observes. Empty is the normal state and must stay silent. */
    val keyChanges: StateFlow<List<KeyChangeAlert>> = _keyChanges.asStateFlow()

    /**
     * Called on the inbound path with the key a message actually arrived under.
     *
     * Cheap enough to sit on every message: one `SharedPreferences` read and one SHA-256 over
     * ~88 bytes, and it writes only on a first sight or an actual change.
     *
     * Returns the outcome so a caller can log it; the alert is published on [keyChanges]
     * either way.
     */
    fun noteObservedIdentity(myPublicKey: String, theirPublicKey: String): KeyChangeWatch.Outcome {
        if (myPublicKey.isBlank() || theirPublicKey.isBlank()) return KeyChangeWatch.Outcome.FirstSight
        val fingerprint = SafetyNumberCodec.fingerprint(myPublicKey, theirPublicKey)
        val known = KeyChangeWatch.knownFingerprint(prefs, theirPublicKey)
        val outcome = KeyChangeWatch.classify(theirPublicKey, fingerprint, known)
        when (outcome) {
            is KeyChangeWatch.Outcome.FirstSight ->
                KeyChangeWatch.remember(prefs, theirPublicKey, fingerprint)

            is KeyChangeWatch.Outcome.Unchanged -> Unit

            is KeyChangeWatch.Outcome.Changed -> {
                val wasVerified = prefs.getString("verified_$theirPublicKey", null) == outcome.previous
                Log.w(TAG, "🚨 Identity key changed for ${theirPublicKey.take(16)}… (verified=$wasVerified)")
                _keyChanges.update { current ->
                    // One alert per contact: a second change before the first is
                    // acknowledged replaces it rather than stacking, so the list cannot grow
                    // without bound on a peer that rotates every message.
                    current.filterNot { it.theirPublicKey == theirPublicKey } + KeyChangeAlert(
                        theirPublicKey = theirPublicKey,
                        previousFingerprint = outcome.previous,
                        currentFingerprint = outcome.current,
                        wasVerified = wasVerified
                    )
                }
                // Adopt the new key as the baseline: the alarm fires once and is
                // acknowledged, rather than on every message forever. The alert above
                // outlives this write.
                KeyChangeWatch.remember(prefs, theirPublicKey, fingerprint)
            }
        }
        return outcome
    }

    /** The user has been told. Does not clear the baseline — that was already adopted. */
    fun acknowledgeKeyChange(theirPublicKey: String) {
        _keyChanges.update { it.filterNot { alert -> alert.theirPublicKey == theirPublicKey } }
    }

    /**
     * Get all verified contacts
     */
    fun getVerifiedContacts(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith("verified_") }
            .map { it.removePrefix("verified_") }
    }

}

/**
 * Safety number data class
 */
data class SafetyNumber(
    val displayCode: String,      // "12345 67890 12345..."
    val fingerprint: String,       // Raw fingerprint hash
    val qrData: String,           // Data for QR code
    val myPublicKey: String,
    val theirPublicKey: String
) {
    /**
     * Get display code as list of groups
     */
    fun getCodeGroups(): List<String> = displayCode.split(" ")

    /**
     * Get code as two rows for display
     */
    fun getCodeRows(): Pair<String, String> {
        val groups = getCodeGroups()
        val midpoint = groups.size / 2
        return Pair(
            groups.take(midpoint).joinToString(" "),
            groups.drop(midpoint).joinToString(" ")
        )
    }
}

/**
 * Verification result
 */
data class VerificationResult(
    val isVerified: Boolean,
    val message: String
)
