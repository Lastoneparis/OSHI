package com.oshi.messenger.ui.viewmodel

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.vps.VPSClient
import com.oshi.messenger.service.CallState
import com.oshi.messenger.service.CurrentCall
import com.oshi.messenger.service.EnhancedCallManager
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.MultiDeviceSyncManager
import com.oshi.messenger.service.WelcomeMessageSeeder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "oshi_settings")

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val vpsClient: VPSClient,
    private val meshManager: MeshNetworkManager,
    private val crossPlatformMesh: CrossPlatformMesh,
    private val messageRepository: MessageRepository,
    private val callManager: EnhancedCallManager,
    private val multiDeviceSyncManager: MultiDeviceSyncManager,
    private val groupManager: GroupManager,
    private val v2AccountClient: com.oshi.messenger.network.v2.V2AccountClient
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_PUBLIC_KEY = stringPreferencesKey("public_key")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        /** Same preference SettingsViewModel reads — the name is the contract. */
        private val KEY_KEY_CREATION_DATE = longPreferencesKey("key_creation_date")
        private val KEY_THEME = stringPreferencesKey("theme")

        // Encrypted prefs keys for private keys
        private const val PREFS_NAME = "oshi_secure_keys"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"

        // Plain SharedPreferences carrying the 64-byte recovery key (base64).
        // This file is INCLUDED in Android Auto Backup (see backup_rules.xml +
        // data_extraction_rules.xml) so that reinstalling on the same Google
        // account recovers the user's identity — same UX as iOS Keychain.
        // EncryptedSharedPreferences is NOT backed up because its master key
        // lives in Android KeyStore which is wiped on uninstall.
        private const val IDENTITY_BACKUP_PREFS = "oshi_identity_backup"
        private const val KEY_RECOVERY_BLOB = "recovery_key_b64"
    }

    // Encrypted SharedPreferences for secure key storage (Android KeyStore backed)
    private val encryptedPrefs by lazy {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            // StrongBoxUnavailableException (or any StrongBox provisioning failure)
            // on devices without a StrongBox HSM — fall back to a TEE-backed key.
            Log.w(TAG, "StrongBox unavailable for master key, falling back: ${e.message}")
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isOnboarded = MutableStateFlow(false)
    val isOnboarded: StateFlow<Boolean> = _isOnboarded.asStateFlow()

    private val _publicKey = MutableStateFlow("")
    val publicKey: StateFlow<String> = _publicKey.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _isCreatingIdentity = MutableStateFlow(false)
    val isCreatingIdentity: StateFlow<Boolean> = _isCreatingIdentity.asStateFlow()

    private val _identityCreationComplete = MutableStateFlow(false)
    val identityCreationComplete: StateFlow<Boolean> = _identityCreationComplete.asStateFlow()

    private val _creationError = MutableStateFlow<String?>(null)
    val creationError: StateFlow<String?> = _creationError.asStateFlow()

    // Backup prompt flag — set after identity creation
    private val _needsBackupPrompt = MutableStateFlow(false)
    val needsBackupPrompt: StateFlow<Boolean> = _needsBackupPrompt.asStateFlow()

    // Total unread message count for badge on bottom navigation
    val totalUnreadCount: StateFlow<Int> = messageRepository.getTotalUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Incoming call state — observed in MainActivity to navigate to call screen
    val incomingCallState: StateFlow<CallState> = callManager.callState
    val incomingCall: StateFlow<CurrentCall?> = callManager.currentCall

    // Connected peers count for Nearby badge
    val connectedPeersCount: StateFlow<Int> = kotlinx.coroutines.flow.combine(
        meshManager.connectedPeers,
        crossPlatformMesh.connectedPeers
    ) { androidPeers, iosPeers ->
        androidPeers.size + iosPeers.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Minimum splash display time (matches iOS ~1.5s)
            val splashMinJob = launch { kotlinx.coroutines.delay(1500L) }

            // We re-enter this collect on every DataStore write. To avoid
            // a feedback loop with recoverIdentityFromDevice() (which writes
            // DataStore from a separate coroutine) we only run the recovery
            // logic on the FIRST emission per process.
            var firstEmission = true
            context.dataStore.data.collect { preferences ->
                _isOnboarded.value = preferences[KEY_ONBOARDED] ?: false
                _publicKey.value = preferences[KEY_PUBLIC_KEY] ?: ""
                _displayName.value = preferences[KEY_DISPLAY_NAME] ?: ""

                if (!firstEmission) {
                    // Subsequent emissions just mirror DataStore changes; the
                    // recovery / re-onboarding decision was made on launch.
                    splashMinJob.join()
                    _isLoading.value = false
                    return@collect
                }
                firstEmission = false

                // Path A — DataStore claims an identity. Try to load it from
                // EncryptedSharedPreferences. If that fails (transient KeyStore
                // error, missing keys after partial wipe) DO NOT clear
                // KEY_ONBOARDED — that's how users used to find themselves
                // back on the onboarding screen mid-day.
                if (_isOnboarded.value && _publicKey.value.isNotEmpty()) {
                    if (restoreKeys()) {
                        initializeServices()
                        splashMinJob.join()
                        _isLoading.value = false
                        return@collect
                    }
                    Log.w(TAG, "loadSettings: restoreKeys() failed — trying Auto-Backup blob before resetting")
                    val blob = runCatching { identityBackupPrefs.getString(KEY_RECOVERY_BLOB, null) }.getOrNull()
                    if (!blob.isNullOrEmpty() && recoverIdentityFromDevice()) {
                        initializeServices()
                        splashMinJob.join()
                        _isLoading.value = false
                        return@collect
                    }
                    Log.w(TAG, "loadSettings: no recoverable identity — resetting onboarding")
                    context.dataStore.edit { prefs ->
                        prefs[KEY_ONBOARDED] = false
                        prefs.remove(KEY_PUBLIC_KEY)
                    }
                    _isOnboarded.value = false
                    _publicKey.value = ""
                    splashMinJob.join()
                    _isLoading.value = false
                    return@collect
                }

                // Path B — DataStore says NOT onboarded. If Auto Backup restored
                // the recovery blob, re-onboard from it silently.
                val blob = runCatching { identityBackupPrefs.getString(KEY_RECOVERY_BLOB, null) }.getOrNull()
                if (!blob.isNullOrEmpty() && recoverIdentityFromDevice()) {
                    initializeServices()
                }

                splashMinJob.join()
                _isLoading.value = false
            }
        }
    }

    /**
     * Restore keys from encrypted storage
     * Returns false if keys are missing or in old format (need regeneration)
     */
    private fun restoreKeys(): Boolean {
        return try {
            val privateKeyBase64 = encryptedPrefs.getString(KEY_PRIVATE_KEY, null)
            val signingPrivateBase64 = encryptedPrefs.getString(KEY_SIGNING_PRIVATE_KEY, null)
            val signingPublicBase64 = encryptedPrefs.getString(KEY_SIGNING_PUBLIC_KEY, null)

            if (privateKeyBase64 == null) {
                Log.w(TAG, "No private key found in secure storage")
                return false
            }

            val privateKeyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val publicKeyBytes = Base64.decode(_publicKey.value, Base64.NO_WRAP)

            // Check if this is new X25519 format (32 bytes) or old P-256 format (longer)
            if (privateKeyBytes.size != 32 || publicKeyBytes.size != 32) {
                Log.w(TAG, "Old key format detected (P-256), needs migration to X25519")
                return false
            }

            // Restore X25519 key agreement keys
            cryptoManager.setKeyPair(privateKeyBytes, publicKeyBytes)
            Log.d(TAG, "✅ X25519 key pair restored from secure storage")

            // Restore Ed25519 signing keys if available
            if (signingPrivateBase64 != null && signingPublicBase64 != null) {
                val signingPrivateBytes = Base64.decode(signingPrivateBase64, Base64.NO_WRAP)
                val signingPublicBytes = Base64.decode(signingPublicBase64, Base64.NO_WRAP)
                if (signingPrivateBytes.size == 32 && signingPublicBytes.size == 32) {
                    cryptoManager.setSigningKeyPair(signingPrivateBytes, signingPublicBytes)
                    Log.d(TAG, "✅ Ed25519 signing key pair restored")
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore keys: ${e.message}")
            false
        }
    }

    fun createIdentity(displayName: String) {
        if (_isCreatingIdentity.value) return // Prevent double-tap

        Log.d(TAG, "createIdentity called with displayName: $displayName")

        viewModelScope.launch {
            _isCreatingIdentity.value = true
            _identityCreationComplete.value = false
            _creationError.value = null

            try {
                Log.d(TAG, "Generating X25519 key pair (iOS compatible)...")
                // Generate key pair - this also generates Ed25519 signing keys
                val (privateKeyBytes, publicKeyBytes) = cryptoManager.generateKeyPair()
                val publicKey = cryptoManager.exportPublicKey()
                Log.d(TAG, "Key pair generated, public key: ${publicKey.take(20)}...")

                // Save private keys to encrypted storage (Android KeyStore backed)
                Log.d(TAG, "Saving keys to encrypted storage...")
                var signingPrivateForBackup: ByteArray? = null
                encryptedPrefs.edit().apply {
                    putString(KEY_PRIVATE_KEY, Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP))
                    // Also save signing keys
                    try {
                        val signingPrivate = cryptoManager.exportSigningPrivateKey()
                        val signingPublic = cryptoManager.getSigningPublicKey()
                        signingPrivateForBackup = signingPrivate
                        putString(KEY_SIGNING_PRIVATE_KEY, Base64.encodeToString(signingPrivate, Base64.NO_WRAP))
                        putString(KEY_SIGNING_PUBLIC_KEY, Base64.encodeToString(signingPublic, Base64.NO_WRAP))
                    } catch (e: Exception) {
                        Log.w(TAG, "Signing keys not available yet: ${e.message}")
                    }
                    apply()
                }
                Log.d(TAG, "Keys saved to encrypted storage")

                // Write the recovery blob to the auto-backup-included prefs file.
                signingPrivateForBackup?.let { writeIdentityBackup(privateKeyBytes, it) }

                Log.d(TAG, "Saving to DataStore...")
                // Save public settings
                context.dataStore.edit { preferences ->
                    preferences[KEY_ONBOARDED] = true
                    preferences[KEY_PUBLIC_KEY] = publicKey
                    preferences[KEY_DISPLAY_NAME] = displayName
                    // __KEY_CREATED_SHOWED_TODAY_2026_11_07__ (Anatole N6) The
                    // "Créé le" row in Settings read `key_creation_date` and
                    // NOTHING had ever written it. The reader fell back to
                    // `System.currentTimeMillis()`, so the row showed TODAY — a
                    // date that moved every day, for every user, since it
                    // shipped. Here is the one moment that knows the answer.
                    preferences[KEY_KEY_CREATION_DATE] = System.currentTimeMillis()
                }
                Log.d(TAG, "Saved to DataStore")

                _publicKey.value = publicKey
                _displayName.value = displayName
                _isOnboarded.value = true

                Log.d(TAG, "Initializing services...")
                initializeServices()
                Log.d(TAG, "Services initialized")

                // 👋 A brand-new identity opens the app to an empty list. Seed the
                // welcome conversation — a greeting plus a contact card for the
                // author — BEFORE signalling completion, so the chat list is never
                // painted empty and then filled in a frame later.
                //
                // Only here: this is the one path that mints a key pair, and a
                // RESTORE (`recoverIdentityFromDevice`, `importRecoveryKey`) is
                // not a new user — they have their history, and greeting them
                // again would be noise. iOS makes the same split with
                // `isRestoredIdentity` (WelcomeMessageManager.swift:52-57,
                // IdentityManager.swift:164-168).
                //
                // Everything it writes is LOCAL: no server call, nothing announced
                // about the new user to anyone.
                WelcomeMessageSeeder.seedIfNeeded(context, publicKey, isRestoredIdentity = false)

                // Signal completion - safe to navigate now
                Log.d(TAG, "Setting identityCreationComplete = true")
                _identityCreationComplete.value = true

                // Prompt user to backup their keys
                _needsBackupPrompt.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error creating identity: ${e.message}", e)
                _creationError.value = e.message ?: "Unknown error"
                // Reset on error
                _isCreatingIdentity.value = false
                _identityCreationComplete.value = false
            }
        }
    }

    fun resetIdentityCreationState() {
        _isCreatingIdentity.value = false
        _identityCreationComplete.value = false
    }

    fun dismissBackupPrompt() {
        _needsBackupPrompt.value = false
    }

    /**
     * Export a simple 64-byte recovery key (32-byte encryption + 32-byte signing private key)
     * combined into a single Base64 string. Matches iOS recovery key format.
     */
    fun exportRecoveryKey(): String? {
        return try {
            val privateKeyBase64 = encryptedPrefs.getString(KEY_PRIVATE_KEY, null) ?: return null
            val signingKeyBase64 = encryptedPrefs.getString(KEY_SIGNING_PRIVATE_KEY, null) ?: return null
            val privateKeyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val signingKeyBytes = Base64.decode(signingKeyBase64, Base64.NO_WRAP)
            if (privateKeyBytes.size != 32 || signingKeyBytes.size != 32) return null
            val combined = privateKeyBytes + signingKeyBytes
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export recovery key: ${e.message}")
            null
        }
    }

    /**
     * Plain SharedPreferences whose contents are included in Auto Backup.
     * We deliberately do NOT use EncryptedSharedPreferences here — its master
     * key lives in Android KeyStore, which is wiped on uninstall, so the
     * encrypted blob would be undecryptable after restore.
     */
    private val identityBackupPrefs by lazy {
        context.getSharedPreferences(IDENTITY_BACKUP_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * SECURITY (CSPN 2026-09): this used to concatenate the raw X25519 + Ed25519
     * 32-byte PRIVATE keys and persist them base64 into a PLAINTEXT SharedPreferences
     * file (`oshi_identity_backup`) that was included in Android Auto Backup. That
     * put the account's master private keys in cleartext, readable off any cloud
     * backup or `adb` pull. The plaintext write has been REMOVED.
     *
     * The private keys now live ONLY in the Android-KeyStore-backed
     * EncryptedSharedPreferences (written immediately before each call to this
     * function) and, when the user opts in, in the passphrase-wrapped backup from
     * KeyBackupScreen. `allowBackup=false` + backup rules exclude every key/DB store.
     *
     * This function is kept as a MIGRATION: it proactively deletes any legacy
     * plaintext recovery blob left on an existing user's device. This is safe
     * because the encrypted store has just been populated with the same identity,
     * so recovery no longer depends on the plaintext blob.
     */
    private fun writeIdentityBackup(encryptionPrivate: ByteArray, signingPrivate: ByteArray) {
        // encryptionPrivate / signingPrivate intentionally unused — see doc above.
        try {
            if (identityBackupPrefs.contains(KEY_RECOVERY_BLOB)) {
                identityBackupPrefs.edit().clear().apply()
                Log.d(TAG, "Removed legacy plaintext identity backup blob")
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearing legacy identity backup failed: ${e.message}")
        }
    }

    // ⚰️ `deleteIdentity()` / `wipeLocalIdentity()` DELETED
    // (2026-08-13 cross-file integration, item H).
    //
    // Zero callers since account deletion moved to
    // `SettingsViewModel.deleteAccount()` → `wipeEverythingLocal()`
    // (`SettingsViewModel.kt:654,684`), and keeping them was worse than dead code —
    // they were a WEAKER wipe wearing the same name. `wipeLocalIdentity()` cleared the
    // encrypted prefs, the Auto-Backup recovery blob, the crypto manager and exactly
    // three DataStore keys. It never called `database.clearAllTables()`, so every
    // message, contact and call record survived "delete identity", and it left
    // `profile` / `oshi_settings` / `oshi_profile_links` / the multi-device prefs on
    // disk. `wipeEverythingLocal()` does all of that plus the mesh teardown.
    //
    // Not forwarded to `SettingsViewModel.deleteAccount()` either: that path only wipes
    // locally AFTER the signed server-side `DELETE /v2/account` succeeds, and exposing a
    // second entry point to it from here would give two screens two different deletion
    // contracts.

    /**
     * Look for a previous identity already stored on this device but not currently
     * marked as onboarded (e.g. user cleared DataStore but EncryptedSharedPreferences
     * still has the keys, or app was force-stopped during the first onboarding write).
     * Falls back to the Auto-Backup recovery blob, which survives uninstall.
     * Returns true if an identity was found and re-activated.
     */
    fun recoverIdentityFromDevice(): Boolean {
        return try {
            // Primary path: encrypted prefs (persists when DataStore was cleared but
            // app wasn't reinstalled).
            var privateKeyBase64 = encryptedPrefs.getString(KEY_PRIVATE_KEY, null)
            var signingPrivateBase64 = encryptedPrefs.getString(KEY_SIGNING_PRIVATE_KEY, null)

            // Fallback path: Auto-Backup recovery blob — survives uninstall when the
            // user is signed into the same Google account.
            if (privateKeyBase64.isNullOrEmpty() || signingPrivateBase64.isNullOrEmpty()) {
                val backupBlob = identityBackupPrefs.getString(KEY_RECOVERY_BLOB, null)
                if (!backupBlob.isNullOrEmpty()) {
                    val combined = Base64.decode(backupBlob, Base64.NO_WRAP)
                    if (combined.size == 64) {
                        privateKeyBase64 = Base64.encodeToString(combined.sliceArray(0..31), Base64.NO_WRAP)
                        signingPrivateBase64 = Base64.encodeToString(combined.sliceArray(32..63), Base64.NO_WRAP)
                        Log.d(TAG, "recoverIdentityFromDevice: using Auto-Backup blob")
                    }
                }
            }
            if (privateKeyBase64.isNullOrEmpty() || signingPrivateBase64.isNullOrEmpty()) {
                Log.d(TAG, "recoverIdentityFromDevice: no stored keys")
                return false
            }
            val encryptionPrivate = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val signingPrivate = Base64.decode(signingPrivateBase64, Base64.NO_WRAP)
            if (encryptionPrivate.size != 32 || signingPrivate.size != 32) {
                Log.w(TAG, "recoverIdentityFromDevice: keys present but wrong size — bailing")
                return false
            }
            // Derive public keys
            val x25519Public = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(encryptionPrivate, 0)
                .generatePublicKey().encoded
            val ed25519Public = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(signingPrivate, 0)
                .generatePublicKey().encoded

            cryptoManager.setKeyPair(encryptionPrivate, x25519Public)
            cryptoManager.setSigningKeyPair(signingPrivate, ed25519Public)

            encryptedPrefs.edit().apply {
                putString(KEY_PRIVATE_KEY, Base64.encodeToString(encryptionPrivate, Base64.NO_WRAP))
                putString(KEY_SIGNING_PRIVATE_KEY, Base64.encodeToString(signingPrivate, Base64.NO_WRAP))
                putString(KEY_SIGNING_PUBLIC_KEY, Base64.encodeToString(ed25519Public, Base64.NO_WRAP))
                apply()
            }
            // Refresh the Auto-Backup blob so future reinstalls keep working.
            writeIdentityBackup(encryptionPrivate, signingPrivate)

            val publicKeyBase64 = Base64.encodeToString(x25519Public, Base64.NO_WRAP)
            viewModelScope.launch {
                context.dataStore.edit { preferences ->
                    preferences[KEY_PUBLIC_KEY] = publicKeyBase64
                    preferences[KEY_ONBOARDED] = true
                }
                _publicKey.value = publicKeyBase64
                _isOnboarded.value = true
                try { triggerFullSyncAfterRestore(publicKeyBase64) } catch (_: Exception) {}
            }
            Log.d(TAG, "recoverIdentityFromDevice: identity reactivated")
            true
        } catch (e: Exception) {
            Log.e(TAG, "recoverIdentityFromDevice failed: ${e.message}", e)
            false
        }
    }

    /**
     * Import a 64-byte recovery key (iOS-compatible format).
     * Restores both X25519 encryption and Ed25519 signing keys, derives public keys,
     * and persists everything into EncryptedSharedPreferences + DataStore.
     */
    fun importRecoveryKey(recoveryKey: String): Boolean {
        return try {
            val bytes = Base64.decode(recoveryKey.trim(), Base64.NO_WRAP)
            if (bytes.size != 64) {
                Log.w(TAG, "Recovery key must be 64 bytes, got ${bytes.size}")
                return false
            }
            val encryptionPrivate = bytes.sliceArray(0..31)
            val signingPrivate = bytes.sliceArray(32..63)

            // Derive X25519 public key from private key
            val x25519PrivateParams = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(encryptionPrivate, 0)
            val x25519PublicBytes = x25519PrivateParams.generatePublicKey().encoded

            // Derive Ed25519 public key from private key
            val ed25519PrivateParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(signingPrivate, 0)
            val ed25519PublicBytes = ed25519PrivateParams.generatePublicKey().encoded

            // Set keys in CryptoManager
            cryptoManager.setKeyPair(encryptionPrivate, x25519PublicBytes)
            cryptoManager.setSigningKeyPair(signingPrivate, ed25519PublicBytes)

            // Persist to EncryptedSharedPreferences
            encryptedPrefs.edit().apply {
                putString(KEY_PRIVATE_KEY, Base64.encodeToString(encryptionPrivate, Base64.NO_WRAP))
                putString(KEY_SIGNING_PRIVATE_KEY, Base64.encodeToString(signingPrivate, Base64.NO_WRAP))
                putString(KEY_SIGNING_PUBLIC_KEY, Base64.encodeToString(ed25519PublicBytes, Base64.NO_WRAP))
                apply()
            }
            // Mirror to the backup-included plain prefs so a future reinstall can recover.
            writeIdentityBackup(encryptionPrivate, signingPrivate)

            // Update DataStore with the derived public key
            val publicKeyBase64 = Base64.encodeToString(x25519PublicBytes, Base64.NO_WRAP)
            viewModelScope.launch {
                context.dataStore.edit { preferences ->
                    preferences[KEY_PUBLIC_KEY] = publicKeyBase64
                    preferences[KEY_ONBOARDED] = true
                }
                _publicKey.value = publicKeyBase64
                _isOnboarded.value = true

                // Re-register FCM and trigger sync
                try {
                    triggerFullSyncAfterRestore(publicKeyBase64)
                } catch (e: Exception) {
                    Log.w(TAG, "Sync after recovery key restore failed: ${e.message}")
                }
            }

            Log.d(TAG, "Recovery key imported successfully, public key: ${Base64.encodeToString(x25519PublicBytes, Base64.NO_WRAP).take(20)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import recovery key: ${e.message}", e)
            false
        }
    }

    /** Re-initialize services and request full sync after key restore — task 1.5.7 */
    /** Push profile links to linked devices AND to all contacts. Called from the
     *  editor's Save button. The contact broadcast is the Android counterpart of
     *  iOS `ProfileLinksManager.saveMyLinks()` invalidating per-contact share
     *  hashes — without it the Android user's links never reach iOS contacts. */
    fun syncProfileLinksNow() {
        multiDeviceSyncManager.syncProfileLinksToLinkedDevices()
        broadcastProfileLinksToContacts()
    }

    private fun broadcastProfileLinksToContacts() {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("oshi_profile_links", Context.MODE_PRIVATE)
                val website = prefs.getString("profile_link_website", "") ?: ""
                val linkedin = prefs.getString("profile_link_linkedin", "") ?: ""
                val twitter = prefs.getString("profile_link_twitter", "") ?: ""
                val instagram = prefs.getString("profile_link_instagram", "") ?: ""
                val github = prefs.getString("profile_link_github", "") ?: ""

                val hasAny = listOf(website, linkedin, twitter, instagram, github)
                    .any { it.isNotBlank() }

                // Build the visibility payload using the iOS-compatible JSON
                // shape — same field names, same prefix, same handler on the
                // iOS side (`processIncomingVisibility`).
                val linksJson = org.json.JSONObject().apply {
                    if (website.isNotBlank()) put("websiteURL", website)
                    if (linkedin.isNotBlank()) put("linkedinURL", linkedin)
                    if (twitter.isNotBlank()) put("twitterURL", twitter)
                    if (instagram.isNotBlank()) put("instagramURL", instagram)
                    if (github.isNotBlank()) put("githubURL", github)
                }
                val payload = org.json.JSONObject().apply {
                    put("visible", hasAny)
                    if (hasAny) put("links", linksJson)
                }
                val message = "${com.oshi.messenger.service.ProfileLinksManager.LINKS_VISIBILITY_PREFIX}$payload"

                val contacts = messageRepository.getAllContactsListSnapshot()
                for (contact in contacts) {
                    try {
                        messageRepository.sendTextMessage(contact, message)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to broadcast links to ${contact.take(8)}…: ${e.message}")
                    }
                }
                Log.d(TAG, "Profile links broadcast to ${contacts.size} contact(s)")
            } catch (e: Exception) {
                Log.e(TAG, "broadcastProfileLinksToContacts failed: ${e.message}")
            }
        }
    }

    fun triggerFullSyncAfterRestore(newPublicKey: String) {
        viewModelScope.launch {
            _publicKey.value = newPublicKey
            try {
                // Re-initialize VPS client with the new key
                vpsClient.initialize(newPublicKey)
                // Re-initialize message repository
                messageRepository.initialize(newPublicKey)
                // Request full sync from the multi-device sync manager
                multiDeviceSyncManager.syncNow()
                Log.d(TAG, "Full sync triggered after key restore for ${newPublicKey.take(20)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Full sync after restore failed: ${e.message}")
            }
        }
    }

    private fun initializeServices() {
        val pk = _publicKey.value
        val name = _displayName.value

        if (pk.isEmpty()) {
            Log.w(TAG, "initializeServices called with empty public key")
            return
        }

        // Initialize all services with individual error handling
        try {
            Log.d(TAG, "Initializing VPS client...")
            vpsClient.initialize(pk)
        } catch (e: Exception) {
            Log.e(TAG, "VPS client init failed: ${e.message}")
        }

        try {
            Log.d(TAG, "Initializing mesh manager...")
            meshManager.initialize(pk, name)
        } catch (e: Exception) {
            Log.e(TAG, "Mesh manager init failed: ${e.message}")
        }

        try {
            Log.d(TAG, "Initializing message repository...")
            messageRepository.initialize(pk)
        } catch (e: Exception) {
            Log.e(TAG, "Message repository init failed: ${e.message}")
        }

        try {
            Log.d(TAG, "Initializing call manager...")
            callManager.initialize(pk, name)
            // Wire call summary callback to send messages on call end (matches iOS format)
            callManager.onCallSummary = { peerPublicKey, content ->
                viewModelScope.launch {
                    try {
                        messageRepository.sendTextMessage(peerPublicKey, content)
                        Log.d(TAG, "📞 Call summary sent to $peerPublicKey")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send call summary: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call manager init failed: ${e.message}")
        }

        // Start mesh networking — unless the user turned it off (N22), in
        // which case respecting that choice across a relaunch is the point:
        // starting it anyway here would silently re-enable it every cold start.
        try {
            if (com.oshi.messenger.network.mesh.MeshPrefs.isEnabled(context)) {
                Log.d(TAG, "Starting mesh network...")
                meshManager.startMesh()
            } else {
                Log.d(TAG, "Mesh networking disabled by user preference — not starting")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mesh start failed: ${e.message}")
        }

        // Start cross-platform mesh (iOS-Android via BLE/mDNS/TCP).
        // If already running with a previous identity, force a teardown + restart
        // so outgoing CrossPlatformMessage.senderPublicKey reflects the new key —
        // otherwise the iPhone keeps attributing posts to the OLD conversation.
        //
        // (N22) This is the lane that actually keeps `MeshNetworkService`'s
        // foreground notification alive (its BLE advertising holds the
        // service up, see MeshNetworkManager.startCrossPlatformMesh) — gating
        // only `startMesh()` above and leaving this one unconditional would
        // have left the "always on" notification exactly as always-on.
        try {
            if (com.oshi.messenger.network.mesh.MeshPrefs.isEnabled(context)) {
                if (crossPlatformMesh.isRunning.value) {
                    Log.d(TAG, "Reconfiguring cross-platform mesh with new identity...")
                    crossPlatformMesh.reconfigureIdentity(pk, name)
                } else {
                    Log.d(TAG, "Starting cross-platform mesh (iOS-Android)...")
                    crossPlatformMesh.start(pk, name)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cross-platform mesh start failed: ${e.message}")
        }

        // Register FCM token now that public key is available.
        // onNewToken may have fired before identity setup, skipping registration.
        try {
            Log.d(TAG, "Registering FCM token with push service...")
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { fcmToken ->
                    viewModelScope.launch {
                        try {
                            com.oshi.messenger.service.FCMService.registerTokenWithPushService(pk, fcmToken, context)
                            Log.d(TAG, "✅ FCM token registered after identity setup")
                        } catch (e: Exception) {
                            Log.w(TAG, "FCM push service registration failed: ${e.message}")
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token retrieval failed: ${e.message}")
        }

        Log.d(TAG, "All services initialization attempted")
    }

    /**
     * (N22) Settings → Network's mesh switch. The only way to make
     * `MeshNetworkService`'s always-on notification go away is to actually
     * stop the mesh it reports on — see [com.oshi.messenger.network.mesh.MeshPrefs].
     */
    /** __BACKGROUND_PRESENCE_2026_11_10__ Settings → Network → keep running in the background. */
    val keepRunningInBackground: Boolean
        get() = com.oshi.messenger.service.BackgroundPresence.userOptIn(context)
    val hasGooglePush: Boolean
        get() = com.oshi.messenger.service.BackgroundPresence.pushAvailable(context)
    fun setKeepRunningInBackground(value: Boolean) =
        com.oshi.messenger.service.BackgroundPresence.setUserOptIn(context, value)

    val isMeshEnabled: Boolean get() = com.oshi.messenger.network.mesh.MeshPrefs.isEnabled(context)

    fun setMeshEnabled(enabled: Boolean) {
        com.oshi.messenger.network.mesh.MeshPrefs.setEnabled(context, enabled)
        if (enabled) {
            val pk = _publicKey.value
            val name = _displayName.value
            if (pk.isNotEmpty()) {
                try {
                    meshManager.startMesh()
                    crossPlatformMesh.start(pk, name)
                } catch (e: Exception) {
                    Log.e(TAG, "Mesh re-enable failed: ${e.message}")
                }
            }
        } else {
            try {
                meshManager.stopAll()
                crossPlatformMesh.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Mesh disable failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't stop mesh on ViewModel clear - let it run in background
        // Mesh should persist across activity/fragment changes
        // Only cleanup when app is fully destroyed via Application.onTerminate()
        Log.d(TAG, "ViewModel cleared - mesh continues running")
    }

    // =========================================================================
    // GROUP INVITE (oshi://group/join?gid=…&name=…&inviter=…)
    // =========================================================================

    /**
     * Join a group via QR / deep-link invite payload. Mirrors iOS
     * `MessageGroupManager.joinGroupFromInvite`.
     *
     * If the group is already local → no-op (success).
     * If we have the group but aren't a member → re-add ourselves (handled by addMember).
     * If we don't have the group yet → create a stub from the invite metadata,
     *   then send a member_sync_request to the inviter so they reply with the
     *   full canonical state (matches existing iOS↔Android sync flow).
     */
    fun joinGroupFromInvite(gid: String, inviterKey: String?, name: String?) {
        viewModelScope.launch {
            try {
                groupManager.joinGroupFromInvite(gid, inviterKey, name)
            } catch (e: Exception) {
                Log.e(TAG, "joinGroupFromInvite failed: ${e.message}")
            }
        }
    }
}
