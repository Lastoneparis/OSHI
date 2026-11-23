package com.oshi.messenger.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.vps.VPSClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KeyRotationManager - Compatible with iOS KeyRotation.swift
 * Handles periodic key rotation for enhanced security
 */
@Singleton
class KeyRotationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val vpsClient: VPSClient
) {
    companion object {
        private const val TAG = "KeyRotationManager"
        private const val KEY_ROTATION_PREFIX = "\uD83D\uDD11KEY_ROTATION\uD83D\uDD11"  // 🔑KEY_ROTATION🔑
        private const val ROTATION_INTERVAL_DAYS = 30
        private const val MAX_PENDING_ROTATIONS = 5
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // SECURITY (CSPN 2026-09): the rotation store holds our own NEW private key
    // during an in-flight rotation (PendingKeyRotation.newPrivateKey). It used to
    // be a PLAIN SharedPreferences file ("key_rotation"), leaking that private key
    // in cleartext. It is now an Android-KeyStore-backed EncryptedSharedPreferences,
    // with a one-time migration of any legacy plaintext file.
    private val prefs: SharedPreferences = buildEncryptedRotationPrefs(context)

    // State
    private val _rotationStatus = MutableStateFlow<RotationStatus>(RotationStatus.Idle)
    val rotationStatus: StateFlow<RotationStatus> = _rotationStatus.asStateFlow()

    // SECURITY (CSPN 2026-09): contacts whose advertised identity-key rotation could
    // NOT be cryptographically verified (unsigned/invalid). The UI must surface these
    // as a safety-number change and require manual re-verification — never auto-trust.
    private val _safetyNumberChanges = MutableStateFlow<Set<String>>(emptySet())
    val safetyNumberChanges: StateFlow<Set<String>> = _safetyNumberChanges.asStateFlow()

    private val _pendingRotations = MutableStateFlow<Map<String, PendingKeyRotation>>(emptyMap())
    val pendingRotations: StateFlow<Map<String, PendingKeyRotation>> = _pendingRotations.asStateFlow()

    // Callbacks
    private val rotationCallbacks = mutableListOf<(String, String) -> Unit>()  // oldKey, newKey

    init {
        loadPendingRotations()
        checkRotationNeeded()
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Check if key rotation is needed
     */
    fun isRotationNeeded(): Boolean {
        val lastRotation = prefs.getLong("last_rotation_time", 0)
        val daysSinceRotation = (System.currentTimeMillis() - lastRotation) / (24 * 60 * 60 * 1000)
        return daysSinceRotation >= ROTATION_INTERVAL_DAYS
    }

    /**
     * Get days until next recommended rotation
     */
    fun getDaysUntilRotation(): Int {
        val lastRotation = prefs.getLong("last_rotation_time", System.currentTimeMillis())
        val daysSinceRotation = ((System.currentTimeMillis() - lastRotation) / (24 * 60 * 60 * 1000)).toInt()
        return maxOf(0, ROTATION_INTERVAL_DAYS - daysSinceRotation)
    }

    /**
     * Initiate key rotation
     * This generates new keys and notifies all contacts
     */
    fun initiateKeyRotation(contactPublicKeys: List<String>) {
        if (_rotationStatus.value == RotationStatus.InProgress) {
            Log.w(TAG, "Key rotation already in progress")
            return
        }

        scope.launch {
            _rotationStatus.value = RotationStatus.InProgress

            try {
                val oldPublicKey = cryptoManager.getPublicKeyString()

                // Generate new key pair
                val newKeyPair = generateNewKeyPair()
                val newPublicKey = newKeyPair.publicKeyString

                Log.d(TAG, "Initiating key rotation: ${oldPublicKey.take(16)}... -> ${newPublicKey.take(16)}...")

                // Notify all contacts about the key change
                notifyContactsOfRotation(contactPublicKeys, oldPublicKey, newPublicKey)

                // Store pending rotation
                val pending = PendingKeyRotation(
                    oldPublicKey = oldPublicKey,
                    newPublicKey = newPublicKey,
                    newPrivateKey = newKeyPair.privateKeyBytes,
                    initiatedAt = System.currentTimeMillis(),
                    notifiedContacts = contactPublicKeys.toMutableList(),
                    confirmedContacts = mutableListOf()
                )

                val currentPending = _pendingRotations.value.toMutableMap()
                currentPending[newPublicKey] = pending
                _pendingRotations.value = currentPending
                savePendingRotations()

                _rotationStatus.value = RotationStatus.WaitingForConfirmations(
                    pending = contactPublicKeys.size,
                    confirmed = 0
                )

            } catch (e: Exception) {
                Log.e(TAG, "Key rotation failed: ${e.message}")
                _rotationStatus.value = RotationStatus.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Handle incoming key rotation notification
     */
    fun handleKeyRotationNotification(content: String, senderPublicKey: String) {
        if (!content.startsWith(KEY_ROTATION_PREFIX)) return

        try {
            val jsonStr = content.removePrefix(KEY_ROTATION_PREFIX)
            val json = JSONObject(jsonStr)

            val action = json.getString("action")

            when (action) {
                "notify" -> handleRotationNotify(json, senderPublicKey)
                "confirm" -> handleRotationConfirm(json, senderPublicKey)
                "complete" -> handleRotationComplete(json, senderPublicKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse key rotation: ${e.message}")
        }
    }

    /**
     * Complete pending rotation after all confirmations received
     */
    fun completeRotation(newPublicKey: String) {
        val pending = _pendingRotations.value[newPublicKey] ?: return

        scope.launch {
            try {
                // Apply new keys
                cryptoManager.applyNewKeyPair(pending.newPublicKey, pending.newPrivateKey)

                // Update rotation time
                prefs.edit().putLong("last_rotation_time", System.currentTimeMillis()).apply()

                // Notify contacts of completion
                notifyContactsOfCompletion(pending.notifiedContacts, pending.oldPublicKey, newPublicKey)

                // Clear pending
                val currentPending = _pendingRotations.value.toMutableMap()
                currentPending.remove(newPublicKey)
                _pendingRotations.value = currentPending
                savePendingRotations()

                // Notify callbacks
                rotationCallbacks.forEach { it(pending.oldPublicKey, newPublicKey) }

                _rotationStatus.value = RotationStatus.Completed
                Log.d(TAG, "Key rotation completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete rotation: ${e.message}")
                _rotationStatus.value = RotationStatus.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Cancel pending rotation
     */
    fun cancelRotation(newPublicKey: String) {
        val currentPending = _pendingRotations.value.toMutableMap()
        currentPending.remove(newPublicKey)
        _pendingRotations.value = currentPending
        savePendingRotations()
        _rotationStatus.value = RotationStatus.Idle
    }

    /**
     * Add callback for rotation completion
     */
    fun addRotationCallback(callback: (String, String) -> Unit) {
        rotationCallbacks.add(callback)
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Generate new X25519 key pair (iOS compatible)
     */
    private fun generateNewKeyPair(): KeyPairData {
        val keyPairGenerator = X25519KeyPairGenerator()
        keyPairGenerator.init(X25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKey = keyPair.private as X25519PrivateKeyParameters
        val publicKey = keyPair.public as X25519PublicKeyParameters

        return KeyPairData(
            publicKeyString = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP),
            privateKeyBytes = privateKey.encoded
        )
    }

    private suspend fun notifyContactsOfRotation(
        contacts: List<String>,
        oldPublicKey: String,
        newPublicKey: String
    ) {
        val json = JSONObject().apply {
            put("action", "notify")
            put("oldPublicKey", oldPublicKey)
            put("newPublicKey", newPublicKey)
            put("timestamp", System.currentTimeMillis())
        }

        val content = "$KEY_ROTATION_PREFIX${json}"

        contacts.forEach { contactKey ->
            try {
                val encrypted = cryptoManager.encryptMessage(content, contactKey)
                vpsClient.sendMessage(contactKey, encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify contact: ${e.message}")
            }
        }
    }

    private fun handleRotationNotify(json: JSONObject, senderPublicKey: String) {
        val oldPublicKey = json.getString("oldPublicKey")
        val newPublicKey = json.getString("newPublicKey")

        // SECURITY (CSPN 2026-09): a key-rotation notification advertises a NEW
        // identity/encryption key for a contact. Auto-storing and auto-confirming
        // it (the previous behaviour) let anyone who could deliver us a message
        // silently redirect that contact's future traffic to an attacker-controlled
        // key — a classic MITM. We now FAIL CLOSED: the rotation is only accepted
        // if it carries an Ed25519 signature by the peer's OLD identity key over the
        // (oldPublicKey -> newPublicKey) transition. Absent or invalid signature =>
        // REJECT, and flag a safety-number change for manual re-verification.
        if (!verifyRotationSignature(json, oldPublicKey, newPublicKey)) {
            Log.w(
                TAG,
                "REJECTED unverified key rotation from a contact " +
                    "(no valid old-identity signature) — flagging safety-number change"
            )
            _safetyNumberChanges.value = _safetyNumberChanges.value + senderPublicKey
            // Deliberately: no contact_rotations write, no auto-confirmation.
            return
        }

        Log.d(TAG, "Accepted SIGNED key rotation from a contact")

        // Store the verified pending key change for this contact
        val contactRotations = prefs.getStringSet("contact_rotations", mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()
        contactRotations.add("$senderPublicKey:$newPublicKey")
        prefs.edit().putStringSet("contact_rotations", contactRotations).apply()

        // Send confirmation only after cryptographic verification
        scope.launch {
            sendRotationConfirmation(senderPublicKey, newPublicKey)
        }
    }

    /**
     * SECURITY (CSPN 2026-09): verify that a rotation notification is signed by the
     * peer's OLD Ed25519 identity key over the "oldPublicKey:newPublicKey" transition.
     *
     * Expected (future) wire fields:
     *   • "signature"       — base64 Ed25519 signature over UTF-8 "old:new".
     *   • "oldSigningKey"   — base64 raw 32-byte Ed25519 public key that produced it.
     *
     * The current wire format carries NEITHER, so this returns false and the caller
     * fails closed (rejects + flags a safety-number change) — never silently accepts.
     *
     * TODO: once a trusted contact store is wired in, additionally pin "oldSigningKey"
     * against the Ed25519 identity we already hold for this contact, so a peer cannot
     * present an attacker-chosen old key that self-signs the transition.
     */
    private fun verifyRotationSignature(
        json: JSONObject,
        oldPublicKey: String,
        newPublicKey: String
    ): Boolean {
        val signatureB64 = json.optString("signature", "")
        val oldSigningKeyB64 = json.optString("oldSigningKey", "")
        if (signatureB64.isBlank() || oldSigningKeyB64.isBlank()) {
            return false
        }
        return try {
            val signature = Base64.decode(signatureB64, Base64.NO_WRAP)
            val oldSigningKey = Base64.decode(oldSigningKeyB64, Base64.NO_WRAP)
            val signedData = "$oldPublicKey:$newPublicKey".toByteArray(Charsets.UTF_8)
            cryptoManager.verify(signedData, signature, oldSigningKey)
        } catch (e: Exception) {
            Log.w(TAG, "Rotation signature verification errored — treating as invalid")
            false
        }
    }

    private suspend fun sendRotationConfirmation(contactKey: String, newPublicKey: String) {
        val json = JSONObject().apply {
            put("action", "confirm")
            put("newPublicKey", newPublicKey)
        }

        val content = "$KEY_ROTATION_PREFIX${json}"
        val encrypted = cryptoManager.encryptMessage(content, contactKey)
        vpsClient.sendMessage(contactKey, encrypted)
    }

    private fun handleRotationConfirm(json: JSONObject, senderPublicKey: String) {
        val newPublicKey = json.getString("newPublicKey")

        val pending = _pendingRotations.value[newPublicKey] ?: return

        if (!pending.confirmedContacts.contains(senderPublicKey)) {
            pending.confirmedContacts.add(senderPublicKey)

            val currentPending = _pendingRotations.value.toMutableMap()
            currentPending[newPublicKey] = pending
            _pendingRotations.value = currentPending
            savePendingRotations()

            _rotationStatus.value = RotationStatus.WaitingForConfirmations(
                pending = pending.notifiedContacts.size,
                confirmed = pending.confirmedContacts.size
            )

            Log.d(TAG, "Received confirmation from $senderPublicKey (${pending.confirmedContacts.size}/${pending.notifiedContacts.size})")

            // Auto-complete if all confirmed
            if (pending.confirmedContacts.size >= pending.notifiedContacts.size) {
                completeRotation(newPublicKey)
            }
        }
    }

    private suspend fun notifyContactsOfCompletion(
        contacts: List<String>,
        oldPublicKey: String,
        newPublicKey: String
    ) {
        val json = JSONObject().apply {
            put("action", "complete")
            put("oldPublicKey", oldPublicKey)
            put("newPublicKey", newPublicKey)
        }

        val content = "$KEY_ROTATION_PREFIX${json}"

        contacts.forEach { contactKey ->
            try {
                val encrypted = cryptoManager.encryptMessage(content, contactKey)
                vpsClient.sendMessage(contactKey, encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify completion: ${e.message}")
            }
        }
    }

    private fun handleRotationComplete(json: JSONObject, senderPublicKey: String) {
        val oldPublicKey = json.getString("oldPublicKey")
        val newPublicKey = json.getString("newPublicKey")

        Log.d(TAG, "Contact $senderPublicKey completed key rotation")

        // Update contact's public key in our records
        // This would typically update the contact database
        rotationCallbacks.forEach { it(oldPublicKey, newPublicKey) }
    }

    private fun checkRotationNeeded() {
        scope.launch {
            if (isRotationNeeded()) {
                _rotationStatus.value = RotationStatus.RotationRecommended(getDaysUntilRotation())
            }
        }
    }

    private fun loadPendingRotations() {
        try {
            val pendingJson = prefs.getString("pending_rotations", null) ?: return
            val json = JSONObject(pendingJson)

            val pending = mutableMapOf<String, PendingKeyRotation>()
            json.keys().forEach { key ->
                val rotationJson = json.getJSONObject(key)
                pending[key] = PendingKeyRotation(
                    oldPublicKey = rotationJson.getString("oldPublicKey"),
                    newPublicKey = rotationJson.getString("newPublicKey"),
                    newPrivateKey = android.util.Base64.decode(
                        rotationJson.getString("newPrivateKey"),
                        android.util.Base64.NO_WRAP
                    ),
                    initiatedAt = rotationJson.getLong("initiatedAt"),
                    notifiedContacts = rotationJson.getJSONArray("notifiedContacts").let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toMutableList()
                    },
                    confirmedContacts = rotationJson.getJSONArray("confirmedContacts").let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toMutableList()
                    }
                )
            }

            _pendingRotations.value = pending
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending rotations: ${e.message}")
        }
    }

    private fun savePendingRotations() {
        try {
            val json = JSONObject()
            _pendingRotations.value.forEach { (key, rotation) ->
                json.put(key, JSONObject().apply {
                    put("oldPublicKey", rotation.oldPublicKey)
                    put("newPublicKey", rotation.newPublicKey)
                    put("newPrivateKey", android.util.Base64.encodeToString(
                        rotation.newPrivateKey,
                        android.util.Base64.NO_WRAP
                    ))
                    put("initiatedAt", rotation.initiatedAt)
                    put("notifiedContacts", org.json.JSONArray(rotation.notifiedContacts))
                    put("confirmedContacts", org.json.JSONArray(rotation.confirmedContacts))
                })
            }
            prefs.edit().putString("pending_rotations", json.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pending rotations: ${e.message}")
        }
    }

    /**
     * SECURITY (CSPN 2026-09): build the KeyStore-backed EncryptedSharedPreferences
     * used for the rotation store (holds our own in-flight new private key), and
     * migrate any legacy PLAINTEXT "key_rotation" file into it once, then delete it.
     */
    private fun buildEncryptedRotationPrefs(context: Context): SharedPreferences {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox unavailable for rotation master key, falling back: ${e.message}")
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }

        val encrypted = EncryptedSharedPreferences.create(
            context,
            "key_rotation_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        migrateLegacyPlaintextRotationPrefs(context, encrypted)
        return encrypted
    }

    private fun migrateLegacyPlaintextRotationPrefs(
        context: Context,
        encrypted: SharedPreferences
    ) {
        try {
            val legacy = context.getSharedPreferences("key_rotation", Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return

            Log.d(TAG, "Migrating legacy plaintext key_rotation store into encrypted store")
            encrypted.edit().apply {
                legacy.getString("pending_rotations", null)?.let { putString("pending_rotations", it) }
                legacy.getStringSet("contact_rotations", null)?.let { putStringSet("contact_rotations", it) }
                if (legacy.contains("last_rotation_time")) {
                    putLong("last_rotation_time", legacy.getLong("last_rotation_time", 0))
                }
                apply()
            }
            // Wipe the plaintext file — it held our in-flight new private key.
            legacy.edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "Legacy rotation prefs migration failed: ${e.message}")
        }
    }

    fun cleanup() {
        scope.cancel()
        rotationCallbacks.clear()
    }
}

/**
 * Key rotation status
 */
sealed class RotationStatus {
    object Idle : RotationStatus()
    data class RotationRecommended(val daysOverdue: Int) : RotationStatus()
    object InProgress : RotationStatus()
    data class WaitingForConfirmations(val pending: Int, val confirmed: Int) : RotationStatus()
    object Completed : RotationStatus()
    data class Failed(val error: String) : RotationStatus()
}

/**
 * Pending key rotation data
 */
data class PendingKeyRotation(
    val oldPublicKey: String,
    val newPublicKey: String,
    val newPrivateKey: ByteArray,
    val initiatedAt: Long,
    val notifiedContacts: MutableList<String>,
    val confirmedContacts: MutableList<String>
)

/**
 * Key pair data
 */
private data class KeyPairData(
    val publicKeyString: String,
    val privateKeyBytes: ByteArray
)
