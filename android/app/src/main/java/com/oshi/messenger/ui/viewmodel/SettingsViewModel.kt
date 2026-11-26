package com.oshi.messenger.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.encryption.CryptoManager
// __RATCHET_IS_SHARED_SOURCE_2026_10_28__ see DoubleRatchetAndroidStore.kt
import com.oshi.messenger.network.encryption.resetAllSessions
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.v2.AccountDeletionReceipt
import com.oshi.messenger.network.v2.V2AccountClient
import com.oshi.messenger.service.MultiDeviceSyncManager
import com.oshi.messenger.service.ScreenshotProtectionManager
import com.oshi.messenger.service.SyncStatus
import com.oshi.messenger.util.PrefsWipePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Phases of the GDPR / guideline-5.1.1(v) account deletion flow.
 * Mirrors iOS `AccountDeletionView.Phase` (AccountDeletionView.swift:36).
 */
sealed class AccountDeletionState {
    object Idle : AccountDeletionState()
    object Deleting : AccountDeletionState()
    data class Done(val receipt: AccountDeletionReceipt) : AccountDeletionState()
    data class Failed(val message: String) : AccountDeletionState()
}

/**
 * SettingsViewModel - Handles all settings state and persistence
 * Compatible with iOS SettingsView.swift functionality
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val messageRepository: MessageRepository,
    private val meshManager: MeshNetworkManager,
    private val screenshotProtectionManager: ScreenshotProtectionManager,
    private val database: AppDatabase,
    private val v2AccountClient: V2AccountClient,
    private val multiDeviceSyncManager: MultiDeviceSyncManager,
    // Needed by [clearSessions], which used to be a Log.d stub for want of exactly this.
    private val doubleRatchet: com.oshi.messenger.network.encryption.DoubleRatchet,
    // Encrypted `.oshiexport` export/import (replaces the plaintext JSON export).
    private val messageExportManager: com.oshi.messenger.service.MessageExportManager
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"

        // Security settings keys
        val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val KEY_SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")
        // Auto-lock: seconds to wait after backgrounding before requiring biometric.
        // 0 = disabled. iOS parity: SettingsView.swift @AppStorage("autoLockEnabled").
        val KEY_AUTO_LOCK_TIMEOUT_SECONDS = intPreferencesKey("auto_lock_timeout_seconds")

        // Privacy settings keys
        val KEY_READ_RECEIPTS = booleanPreferencesKey("read_receipts_enabled")
        val KEY_BLOCK_FORWARD_COPY = booleanPreferencesKey("block_forward_copy")
        val KEY_LAST_SEEN = booleanPreferencesKey("last_seen_enabled")
        // `profile_photo_visible` was declared here with NO UI and NO reader —
        // an orphan key. Deleted rather than given a switch: iOS's own
        // `profilePhotoVisible` toggle (SettingsView.swift:983) has no reader
        // either, so building one on Android would not be parity.

        // Notification settings keys.
        // `notification_sound`, `notification_vibrate` and `in_app_sounds` used
        // to live here with switches and zero readers. On Android O+ sound and
        // vibration are owned by the NotificationChannel and channels are
        // IMMUTABLE once created — `.setSound(...)` on the builder is ignored
        // and `enableVibration(true)` on CHANNEL_MESSAGES cannot be revoked from
        // an app-side boolean. The rows now deep-link to the system channel
        // settings (SettingsScreen) instead of flipping a switch that lies.
        // `in_app_sounds` gated nothing on either platform and is gone.
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_NOTIFICATION_PREVIEW = booleanPreferencesKey("notification_preview")

        // Appearance settings keys
        val KEY_THEME = stringPreferencesKey("app_theme")  // "system", "light", "dark"
        // __OLED_BLACK_2026_11_16__ Modifier of the dark theme ("OLED black").
        // Absent = OFF. Applies whenever the resolved theme is dark.
        val KEY_OLED_BLACK = booleanPreferencesKey("oled_black")
        // __GLASS_ALL_THEMES_2026_11_21__ « Effet verre » in every theme.
        // Absent = never touched: each ground's GlassDefaults applies.
        val KEY_GLASS_EFFECT = booleanPreferencesKey("glass_effect")
        val KEY_FONT_SIZE = floatPreferencesKey("font_size")
        val KEY_BUBBLE_STYLE = stringPreferencesKey("bubble_style")  // "Modern", "Classic", "Minimal"

        // Privacy
        val KEY_TYPING_INDICATORS = booleanPreferencesKey("typing_indicators_enabled")

        // Calls
        val KEY_DEFAULT_VOICE_EFFECT = stringPreferencesKey("default_voice_effect")
        val KEY_CALL_QUALITY = stringPreferencesKey("call_quality")  // "Auto", "Low", "High"
        val KEY_CONTACTS_ONLY_CALLS = booleanPreferencesKey("contacts_only_calls")
        val KEY_CALL_PRIVACY_MODE = stringPreferencesKey("call_privacy_mode")  // "relay" or "direct"
        // 🧭 P2P: force TURN relay path (skip direct probing). iOS parity: "oshi.call.forceRelay".
        val KEY_FORCE_RELAY_MODE = booleanPreferencesKey("force_relay_mode")

        // `show_profile_links` lived here with a switch and no reader — the
        // pinned links bar renders unconditionally (ChatScreen.kt:1334). iOS has
        // no such toggle at all, so the switch is removed rather than wired.

        // Steganography
        val KEY_STEGANOGRAPHY_ENABLED = booleanPreferencesKey("steganography_enabled")
        val KEY_STEGANOGRAPHY_AUTO_MODE = booleanPreferencesKey("steganography_auto_mode")
        val KEY_STEGANOGRAPHY_CARRIER_SOURCE = stringPreferencesKey("steganography_carrier_source")
        val KEY_COVERT_CHANNEL_ENABLED = booleanPreferencesKey("covert_channel_enabled")
        /** (N6) Opt-in for the three-taps-on-the-logo wipe. See [com.oshi.messenger.util.PanicGesture.isArmed]. */
        val KEY_PANIC_WIPE_ON_LOGO = booleanPreferencesKey(com.oshi.messenger.util.PanicGesture.PREF_KEY)
        /**
         * __WEAR_BRIDGE_2026_11_17__ Settings → Smartwatch. OFF by default (opt-in):
         * while off the phone answers every watch request "disabled" without
         * reading anything, and publishes nothing to the watch.
         */
        val KEY_SMARTWATCH_ENABLED = booleanPreferencesKey("smartwatch_bridge_enabled")
        val KEY_COVERT_PREFERRED_CHANNEL = stringPreferencesKey("covert_preferred_channel")

        // Map preference
        val KEY_MAP_OPEN_PREFERENCE = stringPreferencesKey("map_open_preference")  // "oshi", "google", "waze"

        // Offline-only routing — when true, MapViewModel.calculateRoute skips
        // the OSRM HTTP fallback and only returns offline routes (or nothing).
        // iOS parity: "settings_routing_offline_only" UserDefault.
        val KEY_ROUTING_OFFLINE_ONLY = booleanPreferencesKey("settings_routing_offline_only")

        // Profile settings keys
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        val KEY_PUBLIC_KEY = stringPreferencesKey("public_key")
        val KEY_PROFILE_IMAGE = stringPreferencesKey("profile_image_uri")
        val KEY_KEY_CREATION_DATE = longPreferencesKey("key_creation_date")
    }

    // =========================================================================
    // STATE FLOWS
    // =========================================================================

    // Profile
    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _publicKey = MutableStateFlow("")
    val publicKey: StateFlow<String> = _publicKey.asStateFlow()

    private val _profileImageUri = MutableStateFlow<Uri?>(null)
    val profileImageUri: StateFlow<Uri?> = _profileImageUri.asStateFlow()

    private val _keyCreationDate = MutableStateFlow(System.currentTimeMillis())
    val keyCreationDate: StateFlow<Long> = _keyCreationDate.asStateFlow()

    // Security
    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _screenshotProtection = MutableStateFlow(true)
    val screenshotProtection: StateFlow<Boolean> = _screenshotProtection.asStateFlow()

    // Off by default: enabling it removes Forward and Copy, actions people
    // expect to find. Opt-in, never a surprise.
    private val _blockForwardCopy = MutableStateFlow(false)
    val blockForwardCopy: StateFlow<Boolean> = _blockForwardCopy.asStateFlow()

    private val _autoLockTimeoutSeconds = MutableStateFlow(0)
    val autoLockTimeoutSeconds: StateFlow<Int> = _autoLockTimeoutSeconds.asStateFlow()

    private val _biometricAvailable = MutableStateFlow(false)
    val biometricAvailable: StateFlow<Boolean> = _biometricAvailable.asStateFlow()

    // Privacy
    private val _readReceipts = MutableStateFlow(true)
    val readReceipts: StateFlow<Boolean> = _readReceipts.asStateFlow()

    private val _lastSeen = MutableStateFlow(true)
    val lastSeen: StateFlow<Boolean> = _lastSeen.asStateFlow()

    // Notifications
    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _notificationPreview = MutableStateFlow(false) // __ONE_BANNER_PER_MESSAGE_2026_09_23__ default OFF
    val notificationPreview: StateFlow<Boolean> = _notificationPreview.asStateFlow()

    // Appearance
    private val _theme = MutableStateFlow("system")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _oledBlack = MutableStateFlow(false)
    val oledBlack: StateFlow<Boolean> = _oledBlack.asStateFlow()

    /** __GLASS_ALL_THEMES_2026_11_21__ null = never touched (per-theme default). */
    private val _glassEffect = MutableStateFlow<Boolean?>(null)
    val glassEffect: StateFlow<Boolean?> = _glassEffect.asStateFlow()

    /**
     * __OLED_SPLASH_2026_11_16__ True once [theme] / [oledBlack] hold the stored
     * values rather than their defaults — MainActivity must not re-apply the
     * window background and splash theme from the defaults on the first frame.
     */
    private val _appearanceLoaded = MutableStateFlow(false)
    val appearanceLoaded: StateFlow<Boolean> = _appearanceLoaded.asStateFlow()

    private val _fontSize = MutableStateFlow(1.0f)
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _bubbleStyle = MutableStateFlow("Modern")
    val bubbleStyle: StateFlow<String> = _bubbleStyle.asStateFlow()

    // Privacy - Typing Indicators
    private val _typingIndicators = MutableStateFlow(true)
    val typingIndicators: StateFlow<Boolean> = _typingIndicators.asStateFlow()

    // Calls
    private val _defaultVoiceEffect = MutableStateFlow("None")
    val defaultVoiceEffect: StateFlow<String> = _defaultVoiceEffect.asStateFlow()

    private val _callQuality = MutableStateFlow("Auto")
    val callQuality: StateFlow<String> = _callQuality.asStateFlow()

    private val _contactsOnlyCalls = MutableStateFlow(false)
    val contactsOnlyCalls: StateFlow<Boolean> = _contactsOnlyCalls.asStateFlow()

    // Call Privacy (relay = IP hidden, direct = allow P2P)
    private val _callPrivacyMode = MutableStateFlow("direct")
    val callPrivacyMode: StateFlow<String> = _callPrivacyMode.asStateFlow()

    // 🧭 Force relay mode — when true, P2P transport skips direct probing and
    // goes straight to TURN allocation. Mirrors iOS "oshi.call.forceRelay".
    private val _forceRelayMode = MutableStateFlow(false)
    val forceRelayMode: StateFlow<Boolean> = _forceRelayMode.asStateFlow()

    // Multi-device sync (server-mediated, iOS MultiDeviceSyncManager)
    val syncEnabled: StateFlow<Boolean> = multiDeviceSyncManager.isSyncEnabled
    val syncStatus: StateFlow<SyncStatus> = multiDeviceSyncManager.syncStatus
    val lastSyncTime: StateFlow<Long> = multiDeviceSyncManager.lastSyncTime
    val thisDeviceName: String get() = multiDeviceSyncManager.thisDeviceName

    // Account deletion (iOS AccountDeletionView)
    private val _accountDeletion = MutableStateFlow<AccountDeletionState>(AccountDeletionState.Idle)
    val accountDeletion: StateFlow<AccountDeletionState> = _accountDeletion.asStateFlow()

    // Steganography
    private val _steganographyEnabled = MutableStateFlow(false)
    val steganographyEnabled: StateFlow<Boolean> = _steganographyEnabled.asStateFlow()

    private val _steganographyAutoMode = MutableStateFlow(false)
    val steganographyAutoMode: StateFlow<Boolean> = _steganographyAutoMode.asStateFlow()

    private val _steganographyCarrierSource = MutableStateFlow("generated")
    val steganographyCarrierSource: StateFlow<String> = _steganographyCarrierSource.asStateFlow()

    // "Privacy relay (beta)" — Oblivious HTTP for /v2/ calls (network/ohttp). OFF by default.
    private val _privacyRelayEnabled = MutableStateFlow(false)
    val privacyRelayEnabled: StateFlow<Boolean> = _privacyRelayEnabled.asStateFlow()
    /** False while the relay URL / pinned gateway key are still build-time placeholders. */
    // __OHTTP_RELAY_ADDRESS_2026_11_10__ available as soon as a relay address exists
    // (entered by the user, or built in); the switch is greyed out only without one.
    private val _privacyRelayUrl = MutableStateFlow("")
    val privacyRelayUrl: StateFlow<String> = _privacyRelayUrl.asStateFlow()
    val privacyRelayAvailable: Boolean
        get() = com.oshi.messenger.network.ohttp.OhttpConfig.isConfigured(
            _privacyRelayUrl.value.ifBlank { com.oshi.messenger.network.ohttp.OhttpConfig.RELAY_URL })

    private val _covertChannelEnabled = MutableStateFlow(false)
    // (N6) Off until the user turns it on — see PanicGesture.isArmed.
    private val _panicWipeOnLogo = MutableStateFlow(com.oshi.messenger.util.PanicGesture.DEFAULT_ARMED)
    val panicWipeOnLogo: StateFlow<Boolean> = _panicWipeOnLogo.asStateFlow()
    private val _smartwatchEnabled = MutableStateFlow(false)
    val smartwatchEnabled: StateFlow<Boolean> = _smartwatchEnabled.asStateFlow()
    val covertChannelEnabled: StateFlow<Boolean> = _covertChannelEnabled.asStateFlow()

    private val _covertPreferredChannel = MutableStateFlow("auto")
    val covertPreferredChannel: StateFlow<String> = _covertPreferredChannel.asStateFlow()

    // Map preference
    private val _mapOpenPreference = MutableStateFlow("oshi")
    val mapOpenPreference: StateFlow<String> = _mapOpenPreference.asStateFlow()

    // Routing — offline only
    private val _routingOfflineOnly = MutableStateFlow(false)
    val routingOfflineOnly: StateFlow<Boolean> = _routingOfflineOnly.asStateFlow()

    // Statistics
    private val _messageCount = MutableStateFlow(0)
    val messageCount: StateFlow<Int> = _messageCount.asStateFlow()

    private val _chatCount = MutableStateFlow(0)
    val chatCount: StateFlow<Int> = _chatCount.asStateFlow()

    val connectedPeers: StateFlow<Int> = meshManager.connectedPeers
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Blocked contacts
    // __BLOCKED_NOTIF_2026_09_23__ every blocked KEY, not only rows that still exist:
    // someone blocked and then deleted must still be listed so they can be unblocked.
    val blockedContacts: StateFlow<List<Contact>> = combine(
        database.contactDao().getBlockedContacts(),
        com.oshi.messenger.service.BlockedKeysStore.get(context).entries
    ) { rows, entries ->
        val merged = com.oshi.messenger.service.BlockedKeys.merge(entries, rows.map { it.publicKey to it.alias })
        merged.map { e ->
            rows.firstOrNull {
                com.oshi.messenger.service.BlockedKeys.normalize(it.publicKey) ==
                    com.oshi.messenger.service.BlockedKeys.normalize(e.publicKey)
            } ?: Contact(publicKey = e.publicKey, alias = e.name ?: (e.publicKey.take(12) + "…"), isBlocked = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Export state
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    // =========================================================================
    // INITIALIZATION
    // =========================================================================

    init {
        // Encrypted exports left in cache by an earlier session, and any plaintext
        // export the pre-2026-09-22 builds left in external storage.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                messageExportManager.sweepStaleExports()
                messageExportManager.purgeLegacyPlaintextExports()
            }
        }
        loadSettings()
        checkBiometricAvailability()
        observeStatistics()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                // Profile
                _displayName.value = preferences[KEY_DISPLAY_NAME] ?: ""
                _publicKey.value = preferences[KEY_PUBLIC_KEY] ?: ""
                preferences[KEY_PROFILE_IMAGE]?.let { uriString ->
                    _profileImageUri.value = resolveProfilePhotoUri(uriString)
                }
                // __KEY_CREATED_SHOWED_TODAY_2026_11_07__ (Anatole N6)
                //
                // This used to be `?: System.currentTimeMillis()`, and NOTHING
                // anywhere wrote the preference — `grep KEY_KEY_CREATION_DATE`
                // returned the declaration and this line. So "Créé le" showed
                // TODAY, moved every day, and did it for every user since the
                // row shipped. In an app whose whole proposition is key custody,
                // a confidently wrong key age is worse than no key age.
                //
                // New identities now stamp it (MainViewModel.createIdentity).
                // An identity older than that fix has no record, so rather than
                // invent one we fall back to when OSHI was first installed on
                // this device — the key cannot predate the app. It is an upper
                // bound, not a guess, and it is stamped ONCE so the date stops
                // moving. A restored backup makes it the reinstall date, which
                // is still a true statement about this device.
                _keyCreationDate.value = preferences[KEY_KEY_CREATION_DATE] ?: backfillKeyCreationDate()

                // Security
                _biometricEnabled.value = preferences[KEY_BIOMETRIC_ENABLED] ?: false
                // Absent = never touched the switch = ON; only an explicit false opts out.
                _screenshotProtection.value = com.oshi.messenger.service.ScreenshotProtectionPolicy
                    .resolveUserChoice(preferences[KEY_SCREENSHOT_PROTECTION])
                _autoLockTimeoutSeconds.value = preferences[KEY_AUTO_LOCK_TIMEOUT_SECONDS] ?: 0

                // Privacy
                _readReceipts.value = preferences[KEY_READ_RECEIPTS] ?: true
                _blockForwardCopy.value = preferences[KEY_BLOCK_FORWARD_COPY] ?: false
                _lastSeen.value = preferences[KEY_LAST_SEEN] ?: true

                // Notifications
                _notificationsEnabled.value = preferences[KEY_NOTIFICATIONS_ENABLED] ?: true
                _notificationPreview.value = preferences[KEY_NOTIFICATION_PREVIEW] ?: false

                // Appearance
                _theme.value = preferences[KEY_THEME] ?: "system"
                _oledBlack.value = preferences[KEY_OLED_BLACK] ?: false
                _glassEffect.value = preferences[KEY_GLASS_EFFECT]
                // __OLED_SPLASH_2026_11_16__ keep the synchronous mirror the cold
                // start reads (ThemeBoot) in step with DataStore.
                val bootTheme = _theme.value
                val bootOled = _oledBlack.value
                val bootGlass = _glassEffect.value
                withContext(Dispatchers.IO) {
                    com.oshi.messenger.ui.theme.ThemeBoot.write(context, bootTheme, bootOled)
                    com.oshi.messenger.ui.theme.ThemeBoot.writeGlass(context, bootGlass)
                }
                _appearanceLoaded.value = true
                _fontSize.value = preferences[KEY_FONT_SIZE] ?: 1.0f
                _bubbleStyle.value = preferences[KEY_BUBBLE_STYLE] ?: "Modern"

                // Privacy - Typing Indicators
                _typingIndicators.value = preferences[KEY_TYPING_INDICATORS] ?: true

                // Calls
                _defaultVoiceEffect.value = preferences[KEY_DEFAULT_VOICE_EFFECT] ?: "None"
                _callQuality.value = preferences[KEY_CALL_QUALITY] ?: "Auto"
                _contactsOnlyCalls.value = preferences[KEY_CONTACTS_ONLY_CALLS] ?: false
                _callPrivacyMode.value = preferences[KEY_CALL_PRIVACY_MODE] ?: "direct"
                _forceRelayMode.value = preferences[KEY_FORCE_RELAY_MODE] ?: false

                // Steganography
                _steganographyEnabled.value = preferences[KEY_STEGANOGRAPHY_ENABLED] ?: false
                _steganographyAutoMode.value = preferences[KEY_STEGANOGRAPHY_AUTO_MODE] ?: false
                _steganographyCarrierSource.value = preferences[KEY_STEGANOGRAPHY_CARRIER_SOURCE] ?: "generated"
                _covertChannelEnabled.value = preferences[KEY_COVERT_CHANNEL_ENABLED] ?: false
                _panicWipeOnLogo.value = com.oshi.messenger.util.PanicGesture.isArmed(preferences[KEY_PANIC_WIPE_ON_LOGO])
                _smartwatchEnabled.value = preferences[KEY_SMARTWATCH_ENABLED] ?: false
                _privacyRelayEnabled.value = preferences[com.oshi.messenger.network.ohttp.OhttpSettings.KEY_ENABLED] ?: false
                _privacyRelayUrl.value = preferences[com.oshi.messenger.network.ohttp.OhttpSettings.KEY_RELAY_URL] ?: ""
                _covertPreferredChannel.value = preferences[KEY_COVERT_PREFERRED_CHANNEL] ?: "auto"

                // Map preference
                _mapOpenPreference.value = preferences[KEY_MAP_OPEN_PREFERENCE] ?: "oshi"

                // Routing offline-only
                _routingOfflineOnly.value = preferences[KEY_ROUTING_OFFLINE_ONLY] ?: false

                // Apply contacts-only calls to UserDefaults (iOS compatibility key)
                context.getSharedPreferences("oshi_settings", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("callsFromContactsOnly", _contactsOnlyCalls.value).apply()

                // Apply screenshot protection state
                if (_screenshotProtection.value) {
                    screenshotProtectionManager.enableProtection()
                } else {
                    screenshotProtectionManager.disableProtection()
                }
            }
        }
    }

    private fun checkBiometricAvailability() {
        // canAuthenticate() is a binder call into the biometric service; keep it
        // off the main thread on the Settings-open path (__SETTINGS_FREEZE_2026_11_09__).
        viewModelScope.launch(Dispatchers.Default) {
            val biometricManager = BiometricManager.from(context)
            _biometricAvailable.value = when (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )) {
                BiometricManager.BIOMETRIC_SUCCESS -> true
                else -> false
            }
        }
    }

    private fun observeStatistics() {
        viewModelScope.launch {
            // Observe conversations count
            messageRepository.getConversations().collect { conversations ->
                _chatCount.value = conversations.size
                _messageCount.value = conversations.sumOf { _: Any ->
                    // Count messages (simplified - just use conversation count * estimated avg)
                    10 as Int // This would need actual message counting from DB
                }
            }
        }

        // __SETTINGS_FREEZE_2026_11_09__ A second collector of
        // messageDao().getUnreadMessages() lived here with an EMPTY body: every
        // Settings open materialised every unread row (inline media included,
        // up to MAX_INLINE_MEDIA_BYTES each) and re-did it on every insert while
        // Settings was on the back stack, to compute nothing. Removed.
    }

    // =========================================================================
    // PROFILE SETTINGS
    // =========================================================================

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            // Keep the same constraints as iOS and upload immediately. The
            // archive is AES-GCM encrypted with a key derived from the private
            // identity key, so no profile name is exposed to the sync service.
            val normalized = name.trim().take(48)
            if (normalized.isEmpty()) return@launch
            context.dataStore.edit { preferences ->
                preferences[KEY_DISPLAY_NAME] = normalized
            }
            _displayName.value = normalized
            multiDeviceSyncManager.syncProfileNow()
            // __SHARED_NICKNAME_2026_09_22__ Share it with contacts: the profile payload
            // now carries `displayName`. Silent on the wire (📸PROFILE_UPDATE📸 is a
            // push-suppressed control payload) and never a chat row on the far side.
            runCatching { messageRepository.broadcastProfileToAllContacts() }
                .onFailure { Log.w(TAG, "Nickname broadcast failed: ${it.message}") }
            Log.d(TAG, "Display name updated")
        }
    }

    /**
     * __PROFILE_PHOTO_URI_DIES_2026_10_27__ Owner report N3, "le probleme de
     * photo de profil sur Android".
     *
     * `ProfileEditScreen` picks with `ActivityResultContracts.GetContent()`,
     * which hands back a `content://` URI carrying a read grant that lives only
     * as long as the activity. That URI string was written straight into
     * DataStore and parsed back on every launch, and
     * `takePersistableUriPermission` is called nowhere in this app — a
     * `GetContent()` URI cannot be persisted anyway, that is `OpenDocument`'s
     * contract. So the avatar rendered once, right after picking, and came back
     * EMPTY after the next cold start, for ever.
     *
     * The cruel part: nothing was actually lost. `setMyProfilePicture` compresses
     * the bytes and keeps them in the "profile" SharedPreferences, and those are
     * what contacts receive — so the owner's photo was visible to everyone
     * except the owner.
     *
     * A photo we own has to live in a file we own. The bytes are copied into
     * app-private storage at pick time and the stable `file://` URI is what gets
     * persisted.
     */
    private fun profilePhotoFile(): File = File(context.filesDir, "profile_photo.jpg")

    /** Last DataStore string we resolved, so the collector migrates once, not per emission. */
    private var resolvedPhotoFor: String? = null
    private var resolvedPhotoUri: Uri? = null

    /**
     * The URI the avatar should actually load, migrating a legacy `content://`
     * value on the way. Returns null when there is nothing left to show, which
     * draws the placeholder instead of a silently broken image.
     */
    private suspend fun resolveProfilePhotoUri(stored: String): Uri? {
        if (stored == resolvedPhotoFor) return resolvedPhotoUri
        resolvedPhotoFor = stored
        val uri = Uri.parse(stored)
        val local = profilePhotoFile()
        resolvedPhotoUri = when {
            // Already ours.
            uri.scheme == "file" && local.exists() -> uri
            else -> withContext(Dispatchers.IO) {
                // The borrowed grant may still be alive in this process; if not,
                // fall back to the compressed bytes the repository kept all along.
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: runCatching {
                    context.getSharedPreferences("profile", Context.MODE_PRIVATE)
                        .getString("profile_picture", null)
                        ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Profile photo unrecoverable; clearing the dead URI")
                    viewModelScope.launch {
                        context.dataStore.edit { it.remove(KEY_PROFILE_IMAGE) }
                    }
                    null
                } else {
                    local.writeBytes(bytes)
                    val migrated = Uri.fromFile(local)
                    Log.d(TAG, "Profile photo migrated to app storage (${bytes.size} bytes)")
                    viewModelScope.launch {
                        context.dataStore.edit { it[KEY_PROFILE_IMAGE] = migrated.toString() }
                    }
                    migrated
                }
            }
        }
        return resolvedPhotoUri
    }

    fun updateProfileImage(uri: Uri?) {
        viewModelScope.launch {
            // Read the picked bytes BEFORE persisting anything. The old order
            // wrote the URI first and read the stream afterwards, so a pick we
            // could not read still left a dead URI in DataStore.
            val bytes = if (uri == null) null else withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull()
            }
            if (uri != null && (bytes == null || bytes.isEmpty())) {
                Log.e(TAG, "Could not read the picked image; profile photo left unchanged")
                return@launch
            }

            val stored: Uri? = withContext(Dispatchers.IO) {
                val local = profilePhotoFile()
                if (bytes == null) {
                    local.delete()
                    null
                } else {
                    local.writeBytes(bytes)
                    Uri.fromFile(local)
                }
            }

            context.dataStore.edit { preferences ->
                if (stored != null) {
                    preferences[KEY_PROFILE_IMAGE] = stored.toString()
                } else {
                    preferences.remove(KEY_PROFILE_IMAGE)
                }
            }
            // Keep the resolver's memo in step so the collector does not re-migrate.
            resolvedPhotoFor = stored?.toString()
            resolvedPhotoUri = stored
            _profileImageUri.value = stored
            Log.d(TAG, "Profile image updated")

            // Broadcast to contacts
            if (uri == null) {
                // __PROFILE_PICTURE_SYNC_2026_09_23__ Removing the photo used to clear only
                // this DataStore key: the bytes the repository SENDS stayed, so contacts kept
                // the old face and every later profile update re-sent it. Clear them and tell
                // contacts — a `profileImageData: null` update is how iOS removes a photo too
                // (`ContactPresenceManager.processProfileUpdate`).
                try {
                    messageRepository.setMyProfilePicture(null)
                    messageRepository.broadcastProfileToAllContacts()
                    multiDeviceSyncManager.syncProfileNow()
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting profile image removal: ${e.message}")
                }
            }
            bytes?.let {
                try {
                    messageRepository.setMyProfilePicture(it)
                    messageRepository.broadcastProfileToAllContacts()
                    multiDeviceSyncManager.syncProfileNow()
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting profile image: ${e.message}")
                }
            }
        }
    }

    // =========================================================================
    // SECURITY SETTINGS
    // =========================================================================

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_BIOMETRIC_ENABLED] = enabled
            }
            _biometricEnabled.value = enabled
            Log.d(TAG, "Biometric enabled: $enabled")
        }
    }

    fun setAutoLockTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_AUTO_LOCK_TIMEOUT_SECONDS] = seconds
            }
            _autoLockTimeoutSeconds.value = seconds
            Log.d(TAG, "Auto-lock timeout: ${seconds}s")
        }
    }

    fun setScreenshotProtection(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_SCREENSHOT_PROTECTION] = enabled
            }
            _screenshotProtection.value = enabled

            if (enabled) {
                screenshotProtectionManager.enableProtection()
            } else {
                screenshotProtectionManager.disableProtection()
            }
            Log.d(TAG, "Screenshot protection: $enabled")
        }
    }

    // =========================================================================
    // PRIVACY SETTINGS
    // =========================================================================

    /** See the Settings footer: this restricts THIS device. It cannot stop the
     *  recipient forwarding, copying or screenshotting what they received. */
    fun setBlockForwardCopy(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_BLOCK_FORWARD_COPY] = enabled
            }
            _blockForwardCopy.value = enabled
            Log.d(TAG, "Block forward/copy: $enabled")
        }
    }

    fun setReadReceipts(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_READ_RECEIPTS] = enabled
            }
            _readReceipts.value = enabled
        }
    }

    fun setLastSeen(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_LAST_SEEN] = enabled
            }
            _lastSeen.value = enabled
        }
    }

    fun unblockContact(publicKey: String) {
        viewModelScope.launch {
            messageRepository.unblockContact(publicKey)
            Log.d(TAG, "Unblocked contact: ${publicKey.take(8)}...")
        }
    }

    // =========================================================================
    // NOTIFICATION SETTINGS
    // =========================================================================

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_NOTIFICATIONS_ENABLED] = enabled
            }
            _notificationsEnabled.value = enabled
        }
    }

    fun setNotificationPreview(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_NOTIFICATION_PREVIEW] = enabled
            }
            _notificationPreview.value = enabled
        }
    }

    // =========================================================================
    // APPEARANCE SETTINGS
    // =========================================================================

    fun setTheme(theme: String) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_THEME] = theme
            }
            _theme.value = theme
            withContext(Dispatchers.IO) {
                com.oshi.messenger.ui.theme.ThemeBoot.write(context, theme, _oledBlack.value)
            }
            Log.d(TAG, "Theme changed to: $theme")
        }
    }

    /**
     * __OLED_BLACK_2026_11_16__ The state flips BEFORE the DataStore write so
     * the whole app re-themes on the same frame as the switch (MainActivity
     * collects [oledBlack]); the write only makes it survive a restart.
     */
    fun setOledBlack(enabled: Boolean) {
        _oledBlack.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_OLED_BLACK] = enabled
            }
            withContext(Dispatchers.IO) {
                com.oshi.messenger.ui.theme.ThemeBoot.write(context, _theme.value, enabled)
            }
        }
    }

    /**
     * __GLASS_ALL_THEMES_2026_11_21__ Same rule as [setOledBlack]: the state
     * flips first so the app re-themes on the switch's frame.
     */
    fun setGlassEffect(enabled: Boolean) {
        _glassEffect.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_GLASS_EFFECT] = enabled
            }
            withContext(Dispatchers.IO) {
                com.oshi.messenger.ui.theme.ThemeBoot.writeGlass(context, enabled)
            }
        }
    }

    fun setFontSize(size: Float) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_FONT_SIZE] = size
            }
            _fontSize.value = size
        }
    }

    fun setBubbleStyle(style: String) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_BUBBLE_STYLE] = style
            }
            _bubbleStyle.value = style
        }
    }

    // =========================================================================
    // PRIVACY - TYPING INDICATORS
    // =========================================================================

    fun setTypingIndicators(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_TYPING_INDICATORS] = enabled
            }
            _typingIndicators.value = enabled
        }
    }

    // =========================================================================
    // CALLS SETTINGS
    // =========================================================================

    fun setDefaultVoiceEffect(effect: String) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_DEFAULT_VOICE_EFFECT] = effect
            }
            _defaultVoiceEffect.value = effect
        }
    }

    fun setCallQuality(quality: String) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_CALL_QUALITY] = quality
            }
            _callQuality.value = quality
        }
    }

    fun setMapOpenPreference(preference: String) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_MAP_OPEN_PREFERENCE] = preference
            }
            _mapOpenPreference.value = preference
        }
    }

    fun setRoutingOfflineOnly(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_ROUTING_OFFLINE_ONLY] = enabled
            }
            _routingOfflineOnly.value = enabled
            // Mirror iOS: also write to plain SharedPreferences so non-VM call
            // sites (e.g. MapViewModel) can read it without a Hilt-injected VM.
            context.getSharedPreferences("oshi_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("settings_routing_offline_only", enabled).apply()
            // The tile stack and the preview renderer read their own key, in their own
            // store, which nothing ever wrote — so the switch gagged routing and nothing
            // else. iOS drives tiles, search and routing off one key.
            context.getSharedPreferences(
                com.oshi.messenger.service.map.OshiOfflineTileProvider.PREF_FILE,
                android.content.Context.MODE_PRIVATE
            ).edit().putBoolean(
                com.oshi.messenger.service.map.OshiOfflineTileProvider.PREF_KEY,
                enabled
            ).apply()
            Log.d(TAG, "Map offline-only: $enabled")
        }
    }

    fun setContactsOnlyCalls(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_CONTACTS_ONLY_CALLS] = enabled
            }
            _contactsOnlyCalls.value = enabled
            // Also store in SharedPreferences for VoIP service access (iOS uses UserDefaults key)
            context.getSharedPreferences("oshi_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("callsFromContactsOnly", enabled).apply()
        }
    }

    fun setCallPrivacyMode(mode: String) {
        val validMode = if (mode == "direct") "direct" else "relay"
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_CALL_PRIVACY_MODE] = validMode
            }
            _callPrivacyMode.value = validMode
            Log.d(TAG, "Call privacy mode set to: $validMode")
        }
    }

    fun setForceRelayMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[KEY_FORCE_RELAY_MODE] = enabled
            }
            _forceRelayMode.value = enabled
            Log.d(TAG, "Force relay mode: $enabled")
        }
    }

    // =========================================================================
    // MULTI-DEVICE SYNC
    // =========================================================================

    fun setSyncEnabled(enabled: Boolean) = multiDeviceSyncManager.setSyncEnabled(enabled)

    fun syncNow() = multiDeviceSyncManager.performServerSync()

    // =========================================================================
    // ACCOUNT DELETION — iOS AccountDeletionView.swift:188-235
    // =========================================================================

    /**
     * Server first, then the device — and the order is NOT interchangeable.
     *
     * The `DELETE /v2/account` request has to be SIGNED by the identity being
     * deleted. Wiping locally first destroys the signing key and leaves the
     * server records permanently unreachable by anyone, including their owner.
     * So a server failure aborts before anything local is touched and the user
     * can retry — exactly what iOS does at AccountDeletionView.swift:188-203.
     * The previous Android code wiped in a `finally`, so one dropped packet
     * orphaned the user's server-side data forever.
     */
    fun deleteAccount() {
        if (_accountDeletion.value is AccountDeletionState.Deleting) return
        _accountDeletion.value = AccountDeletionState.Deleting
        viewModelScope.launch {
            v2AccountClient.deleteAccount()
                .onSuccess { receipt ->
                    wipeEverythingLocal()
                    _accountDeletion.value = AccountDeletionState.Done(receipt)
                }
                .onFailure { e ->
                    Log.w(TAG, "Account deletion aborted — nothing local touched: ${e.message}")
                    _accountDeletion.value =
                        AccountDeletionState.Failed(e.message ?: "Network error")
                }
        }
    }

    fun resetAccountDeletion() {
        _accountDeletion.value = AccountDeletionState.Idle
    }

    /**
     * __PANIC_WIPE_2026_11_03__ A wipe in flight swallows further triggers.
     *
     * There is no confirmation dialog to absorb a shaking hand, so the fourth
     * and fifth taps of a frightened triple-tap arrive as fresh requests, and
     * two wipes racing over one database is a crash instead of a wipe. Not a
     * StateFlow: nothing observes it, and a panic wipe has no UI state.
     */
    @Volatile
    private var panicking: Boolean = false

    /**
     * __PANIC_WIPE_2026_11_03__ Three taps on the wordmark, and this phone
     * stops being evidence.
     *
     * Deliberately NOT [deleteAccount]. That one asks the server first and
     * aborts if the request fails, which is right when someone has decided to
     * leave and wrong when someone is about to have their phone taken: it means
     * a wipe that can be defeated by jamming the signal. This path never
     * touches the network, never asks, and cannot fail into "nothing happened".
     *
     * The order is [PanicWipePolicy.ORDER] and the reasons each step sits where
     * it does are documented there, next to the test that holds them.
     */
    fun panicWipe() {
        if (!com.oshi.messenger.util.PanicWipePolicy.shouldStart(panicking)) return
        panicking = true
        viewModelScope.launch {
            // (N6, Anatole 13/11) OPT-IN. Three taps on a wordmark is a gesture
            // people make by accident — fidgeting, a child, a friend showing the
            // app off — and the price of an accident here is every message and
            // key on the phone, with no confirmation by design. So it does
            // nothing unless the user turned it on in Settings → Security.
            //
            // Read from the STORE, not from `_panicWipeOnLogo`: that flow is
            // filled by an async collect in `init`, and a frightened triple-tap
            // in the first instants after launch must not be dropped because
            // the collect has not landed yet. This is the only entry point to
            // the wipe (the wordmark in ConversationsScreen is its only caller),
            // so gating here covers every trigger.
            val armed = com.oshi.messenger.util.PanicGesture.isArmed(
                runCatching { context.dataStore.data.first()[KEY_PANIC_WIPE_ON_LOGO] }.getOrNull()
            )
            if (!armed) {
                panicking = false
                Log.d(TAG, "panic gesture ignored: not enabled in settings")
                return@launch
            }
            // STOP_RADIOS — first, so a phone that gets no further has at least
            // gone quiet. Every call is independently guarded: a radio that
            // throws on the way down must not stop the database being cleared.
            runCatching { meshManager.stopAll() }
                .onFailure { Log.w(TAG, "panic: mesh stop: ${it.message}") }
            runCatching {
                com.oshi.messenger.service.lora.MeshtasticManager.instanceOrNull?.let { radio ->
                    radio.stopScan()
                    // forget = true: leaving the paired radio behind would
                    // reconnect a wiped phone to the node it was carrying.
                    radio.disconnect(forget = true)
                }
            }.onFailure { Log.w(TAG, "panic: LoRa stop: ${it.message}") }

            // CANCEL_NOTIFICATIONS — the shade is a copy of the message list
            // that clearAllTables() does not reach.
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
            }.onFailure { Log.w(TAG, "panic: notifications: ${it.message}") }

            // WIPE_MEDIA — decrypted bytes, before the keys they no longer need.
            runCatching { withContext(Dispatchers.IO) { wipePanicFiles() } }
                .onFailure { Log.w(TAG, "panic: files: ${it.message}") }

            // WIPE_DATABASE, WIPE_KEYS, WIPE_PREFERENCES, RETURN_TO_ONBOARDING.
            // Clearing DataStore drops `onboarded`, which MainViewModel
            // observes, so the app returns to the first-run screen by itself —
            // the only feedback a panic wipe gives.
            wipeEverythingLocal(bounded = true)
            Log.d(TAG, "panic wipe complete")
        }
    }

    /**
     * Every file this app owns, enumerated rather than named.
     *
     * 🔧 This method named `groups` and `cacheDir` by hand when it was first
     * written on 03/11, and that was wrong within the hour. `filesDir` also
     * holds, at the moment of writing:
     *
     *  - `oshi_mesh_custody.json` — OTHER PEOPLE's messages, held for relay
     *  - `V2PendingEnvelopeStore` — our own undelivered envelopes
     *  - `MediaAttachmentStore(filesDir)` — direct (non-group) attachments
     *  - `ScheduledMessageManager`'s store, `devsync`, `profile_photo.jpg`,
     *    `push_retry_queue.json`, the offline map regions
     *
     * So the panic wipe shipped at 17:00 would have left messages, media and
     * a relay spool on disk while returning the user to the first-run screen —
     * the precise failure shape the tests above exist to catch, reintroduced
     * one layer below them. A hand-written list of directories is the same
     * mistake [PrefsWipePolicy] exists to undo: it had already rotted twice
     * for `shared_prefs`, and it rotted here before the day was out.
     *
     * Enumerate instead, and keep nothing. There is no keep-list: the offline
     * map regions are bulky and annoying to lose, but which regions somebody
     * downloaded is itself a map of where they expected to be — not something
     * to leave on a phone that is being taken.
     */
    private fun wipePanicFiles() {
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.noBackupFilesDir,
            context.externalCacheDir,
        )
        for (root in roots) {
            runCatching { root.listFiles()?.forEach { it.deleteRecursively() } }
                .onFailure { Log.w(TAG, "panic: could not clear ${root.name}: ${it.message}") }
        }
    }

    /**
     * Erase this identity from the device, for real.
     *
     * The old `MainViewModel.wipeLocalIdentity()` cleared the encrypted prefs,
     * the backup blob, the crypto manager and exactly three DataStore keys — it
     * never called `clearAllTables()`, so every message, contact and call record
     * survived "Delete identity". The lesser action (`disconnect()`) wiped more
     * than the greater one.
     */
    private suspend fun wipeEverythingLocal(bounded: Boolean = false) {
        // Push registration first, while the identity that signs it still
        // exists. iOS opens `disconnectWallet()` with the same call and names
        // the symptom: "the VPS still sends push notifications to the old key,
        // causing phantom ringing on this device" (IdentityManager.swift:234-238).
        //
        // __PANIC_WIPE_2026_11_03__ [bounded] is the panic path, and it does not
        // wait at all.
        //
        // This is the one network round trip in the whole wipe, and a wipe that
        // can be stalled by holding a connection open is not a wipe. A timeout
        // around it would be a bound in name only: `unregisterThisDevice` is
        // `suspend` but the request inside it is a blocking `HttpURLConnection`
        // on Dispatchers.IO, and cancelling a coroutine does not interrupt a
        // socket that is already blocked — the timeout would return while the
        // thread stayed stuck. So under panic the attempt is DETACHED: it is
        // launched and abandoned, the destructive steps run immediately, and if
        // it never completes nothing is lost but the de-registration itself.
        // The budget still applies inside, so the abandoned coroutine does not
        // outlive the process it belongs to by long.
        //
        // Deliberate account deletion still waits: there the user is not in a
        // hurry, and phantom ringing on a reused device is the worse outcome.
        val publicKeyForUnregister = _publicKey.value.takeIf { it.isNotEmpty() }
        if (bounded) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(
                        com.oshi.messenger.util.PanicWipePolicy.PUSH_UNREGISTER_BUDGET_MS
                    ) {
                        com.oshi.messenger.service.FCMService
                            .unregisterThisDevice(context, publicKeyForUnregister)
                    }
                }
            }
        } else {
            try {
                com.oshi.messenger.service.FCMService
                    .unregisterThisDevice(context, publicKeyForUnregister)
            } catch (e: Exception) {
                Log.w(TAG, "push unregister during wipe: ${e.message}")
            }
        }
        try {
            meshManager.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "mesh cleanup during deletion: ${e.message}")
        }
        try {
            database.clearAllTables()
        } catch (e: Exception) {
            Log.e(TAG, "clearAllTables during deletion failed: ${e.message}")
        }
        try {
            cryptoManager.clearKeys()
        } catch (e: Exception) {
            Log.w(TAG, "clearKeys during deletion: ${e.message}")
        }
        // Every SharedPreferences file that can resurrect the identity or leak
        // its data. `oshi_identity_backup` is the critical one: it is included in
        // Android Auto Backup and MainViewModel.recoverIdentityFromDevice() will
        // silently re-onboard from it on the next launch if it survives.
        //
        // 🔧 P1 FIX (F-6): this was a hand-written list of eight names. It had
        // already rotted twice — `oshi_ratchet` (Double-Ratchet chain keys) and
        // `v2_prekeys` (X3DH private prekeys) both survived "Delete account",
        // along with `wallpaper`, `groups`, `pinned_messages`, `safety_numbers`,
        // `checkin`, `key_rotation`, `oshi_ai`, `oshi_prefs` and `fcm_prefs`.
        // Now the directory is enumerated and everything we own is erased, so a
        // new prefs file is covered the day it is added. Keep-list and rationale
        // live in [PrefsWipePolicy]; iOS is exhaustive by construction too
        // (AccountDeletionView.swift:225-227).
        wipeOwnedSharedPrefs()
        // __LEAK_FIX_2026_09_25__ The assistant history, the check-in session and the
        // peer nicknames moved out of shared_prefs into sealed files; erase them too.
        runCatching { com.oshi.messenger.data.local.atrest.SealedStringStore.wipeAll(context) }
            .onFailure { Log.w(TAG, "sealed stores wipe: ${it.message}") }
        // Clearing DataStore drops `onboarded`, which MainViewModel observes on
        // every emission (MainViewModel.kt:146) — the app returns to onboarding.
        runCatching { context.dataStore.edit { it.clear() } }
        Log.d(TAG, "Local identity and all local data erased")
    }

    /**
     * Clear every `.xml` in `shared_prefs` this app owns, per [PrefsWipePolicy].
     *
     * `.clear()` through `getSharedPreferences` rather than deleting the file:
     * some of these stores have live instances in this process (the encrypted
     * key store among them) and unlinking the file underneath one leaves the
     * in-memory map intact, which is exactly the "wiped but still there" shape
     * this fix exists to remove.
     */
    private fun wipeOwnedSharedPrefs() {
        val names = runCatching {
            File(context.applicationInfo.dataDir, "shared_prefs")
                .listFiles()
                ?.mapNotNull { PrefsWipePolicy.prefsNameOf(it.name) }
                .orEmpty()
        }.getOrElse { emptyList() }
            .filter { PrefsWipePolicy.shouldWipe(it) }

        for (name in names) {
            runCatching {
                context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                    .edit().clear().apply()
            }.onFailure { Log.w(TAG, "could not clear prefs '$name': ${it.message}") }
        }
        Log.d(TAG, "Cleared ${names.size} preference file(s): ${names.sorted()}")
    }

    // =========================================================================
    // STEGANOGRAPHY SETTINGS (matches iOS SteganographySettingsView)
    // =========================================================================

    fun setSteganographyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_STEGANOGRAPHY_ENABLED] = enabled }
            _steganographyEnabled.value = enabled
        }
    }

    fun setSteganographyAutoMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_STEGANOGRAPHY_AUTO_MODE] = enabled }
            _steganographyAutoMode.value = enabled
        }
    }

    fun setSteganographyCarrierSource(source: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_STEGANOGRAPHY_CARRIER_SOURCE] = source }
            _steganographyCarrierSource.value = source
        }
    }

    // =========================================================================
    // 🛡 __STRICT_MODE_2026_11_22__ STRICT SECURITY MODE
    // Forces existing settings through their own setters (so each one persists and
    // applies exactly as when the person flips it), and restores them when turned
    // off. Rules: security/StrictModePolicy.kt. State: security/StrictModeStore.
    // =========================================================================

    val strictStore: com.oshi.messenger.security.StrictModeStore =
        com.oshi.messenger.security.StrictModeStore.get(context)
    val strictModeActive: StateFlow<Boolean> = strictStore.active
    val strictOptions: StateFlow<com.oshi.messenger.security.StrictOptions> = strictStore.options

    private fun currentGuarded() = com.oshi.messenger.security.StrictGuardedSettings(
        appLock = _biometricEnabled.value,
        screenshotProtection = _screenshotProtection.value,
        incognitoKeyboard = com.oshi.messenger.ui.keyboard.KeyboardPrivacyStore.get(context).isIncognitoEnabled,
        notificationPreview = _notificationPreview.value,
        readReceipts = _readReceipts.value,
        typingIndicators = _typingIndicators.value,
        privacyRelay = _privacyRelayEnabled.value,
        blockForwardCopy = _blockForwardCopy.value,
    )

    private fun applyGuarded(target: com.oshi.messenger.security.StrictGuardedSettings) {
        val now = currentGuarded()
        if (target.appLock != now.appLock) setBiometricEnabled(target.appLock)
        if (target.screenshotProtection != now.screenshotProtection) setScreenshotProtection(target.screenshotProtection)
        if (target.incognitoKeyboard != now.incognitoKeyboard)
            com.oshi.messenger.ui.keyboard.KeyboardPrivacyStore.get(context).setIncognito(target.incognitoKeyboard)
        if (target.notificationPreview != now.notificationPreview) setNotificationPreview(target.notificationPreview)
        if (target.readReceipts != now.readReceipts) setReadReceipts(target.readReceipts)
        if (target.typingIndicators != now.typingIndicators) setTypingIndicators(target.typingIndicators)
        if (target.privacyRelay != now.privacyRelay) setPrivacyRelayEnabled(target.privacyRelay)
        if (target.blockForwardCopy != now.blockForwardCopy) setBlockForwardCopy(target.blockForwardCopy)
    }

    /**
     * Turning OFF is gated by the caller (device authentication when a lock exists);
     * this function only does the switching.
     */
    fun setStrictMode(enabled: Boolean) {
        val options = strictStore.options.value
        val lockAvailable = _biometricAvailable.value
        val relayAvailable = privacyRelayAvailable
        if (enabled) {
            if (!strictStore.isActive) strictStore.activate(currentGuarded())
            val snapshot = strictStore.snapshot() ?: currentGuarded()
            applyGuarded(com.oshi.messenger.security.StrictModePolicy.forced(snapshot, options, lockAvailable, relayAvailable))
            com.oshi.messenger.security.SensitiveClipboard.clearOursNow(context)
        } else {
            val now = currentGuarded()
            val snapshot = strictStore.deactivate() ?: return
            applyGuarded(com.oshi.messenger.security.StrictModePolicy.restore(snapshot, now, options, lockAvailable, relayAvailable))
        }
        screenshotProtectionManager.reapplyAll()
        Log.d(TAG, "Strict security mode: $enabled")
    }

    fun setStrictOptions(options: com.oshi.messenger.security.StrictOptions) {
        val old = strictStore.options.value
        strictStore.setOptions(options)
        if (!strictStore.isActive) return
        val snapshot = strictStore.snapshot() ?: return
        val lockAvailable = _biometricAvailable.value
        val relayAvailable = privacyRelayAvailable
        // Sub-switch flipped while ON: release what the old options forced, then force
        // what the new ones ask for — from the person's own values, never from the
        // forced ones.
        val released = com.oshi.messenger.security.StrictModePolicy.restore(snapshot, currentGuarded(), old, lockAvailable, relayAvailable)
        applyGuarded(
            com.oshi.messenger.security.StrictModePolicy.forced(snapshot, options, lockAvailable, relayAvailable).let { f ->
                // Only the presence fields depend on the options.
                released.copy(
                    appLock = f.appLock, screenshotProtection = f.screenshotProtection,
                    incognitoKeyboard = f.incognitoKeyboard, notificationPreview = f.notificationPreview,
                    privacyRelay = f.privacyRelay, blockForwardCopy = f.blockForwardCopy,
                    readReceipts = if (options.hidePresence) false else released.readReceipts,
                    typingIndicators = if (options.hidePresence) false else released.typingIndicators,
                )
            }
        )
    }

    fun setPrivacyRelayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[com.oshi.messenger.network.ohttp.OhttpSettings.KEY_ENABLED] = enabled }
            _privacyRelayEnabled.value = enabled
        }
    }

    /**
     * Save a relay address. Returns false (nothing saved) when it is not an https address
     * of a host other than OSHI's. Blank clears it — and turns the relay off, so the app
     * never keeps a switch ON that routes nowhere.
     */
    fun setPrivacyRelayUrl(input: String): Boolean {
        val blank = input.isBlank()
        val url = if (blank) "" else com.oshi.messenger.network.ohttp.OhttpSettings.normalizeRelayUrl(input) ?: return false
        _privacyRelayUrl.value = url
        viewModelScope.launch {
            context.dataStore.edit {
                if (blank) it.remove(com.oshi.messenger.network.ohttp.OhttpSettings.KEY_RELAY_URL)
                else it[com.oshi.messenger.network.ohttp.OhttpSettings.KEY_RELAY_URL] = url
                if (blank && com.oshi.messenger.network.ohttp.OhttpConfig.RELAY_URL.isBlank()) {
                    it[com.oshi.messenger.network.ohttp.OhttpSettings.KEY_ENABLED] = false
                }
            }
            if (blank && com.oshi.messenger.network.ohttp.OhttpConfig.RELAY_URL.isBlank()) _privacyRelayEnabled.value = false
        }
        return true
    }

    /** (N6) Arm or disarm the three-taps-on-the-logo wipe. */
    fun setPanicWipeOnLogo(enabled: Boolean) {
        _panicWipeOnLogo.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_PANIC_WIPE_ON_LOGO] = enabled }
        }
    }

    /** __WEAR_BRIDGE_2026_11_17__ Settings → Smartwatch. */
    fun setSmartwatchEnabled(enabled: Boolean) {
        _smartwatchEnabled.value = enabled
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_SMARTWATCH_ENABLED] = enabled }
        }
    }

    fun setCovertChannelEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_COVERT_CHANNEL_ENABLED] = enabled }
            _covertChannelEnabled.value = enabled
        }
    }

    fun setCovertPreferredChannel(channel: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_COVERT_PREFERRED_CHANNEL] = channel }
            _covertPreferredChannel.value = channel
        }
    }

    // =========================================================================
    // DATA MANAGEMENT
    // =========================================================================

    /**
     * SECURITY (2026-09-22): the export is an ENCRYPTED `.oshiexport` sealed with the
     * account key (see MessageExportManager / MessageExportCrypto). The previous
     * implementation wrote every decrypted message as plaintext JSON into external
     * app storage and never deleted it; those leftovers are purged here too.
     */
    fun exportMessages() {
        viewModelScope.launch {
            _isExporting.value = true
            _exportResult.value = null
            try {
                val outcome = messageExportManager.export()
                _exportResult.value = ExportResult.Success(outcome.uri, outcome.mediaSkipped)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed: ${e.message}")
                _exportResult.value = ExportResult.Error(e.message ?: "Export failed")
            } finally {
                _isExporting.value = false
            }
        }
    }

    /**
     * Called when the share sheet returns. The receiving app may still be reading
     * the file (a background upload), so it is removed after a grace period rather
     * than instantly; anything missed is swept on the next Settings open / export.
     */
    fun onExportShareFinished() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(com.oshi.messenger.service.MessageExportManager.STALE_EXPORT_MS)
            runCatching { messageExportManager.sweepStaleExports() }
        }
    }

    fun importMessages(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importResult.value = null
            _importResult.value = try {
                val r = messageExportManager.import(uri)
                ImportResult.Success(r.imported, r.alreadyPresent)
            } catch (e: com.oshi.messenger.service.MessageExportManager.ImportError) {
                Log.w(TAG, "Import refused: ${e.message}")
                ImportResult.Error(e)
            } catch (e: Exception) {
                Log.e(TAG, "Import failed: ${e.message}")
                ImportResult.Error(com.oshi.messenger.service.MessageExportManager.ImportError.Unreadable())
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun clearImportResult() {
        _importResult.value = null
    }

    fun clearExportResult() {
        _exportResult.value = null
    }

    /**
     * 🔥 Clear ratchet sessions — the desync escape hatch.
     *
     * 🔧 P1 FIX (2026-08-13 cross-file integration, item L). This was a `Log.d("Sessions
     * cleared")` stub with a comment saying it "would require access to the double
     * ratchet manager" — so the user tapped it, got a success toast, and NOTHING was
     * cleared. The one situation it exists for (two devices whose ratchets have
     * desynced, where no message decrypts until both sides reset) stayed broken with
     * the app claiming it had been fixed.
     *
     * iOS `MessageManager.clearRatchetSessions()`
     * (`OSHI/MessageManager.swift:1744-1785`) clears, in order: the ratchet session
     * map + its persisted copy, the pending-decryption queue, every `UserDefaults` key
     * whose name contains "ratchet"/"session", and three Keychain items.
     *
     * Ported faithfully minus the parts that do not exist here:
     *  - ratchet map + BOTH persisted stores → [DoubleRatchet.resetAllSessions];
     *  - Android has no pending-decryption queue (`grep -rn "pendingDecryption"` → 0
     *    hits), so there is nothing to drain;
     *  - the `oshi_ratchet` / `oshi_ratchet_encrypted` prefs files ARE the "every key
     *    containing ratchet/session" sweep and the Keychain equivalent, and they are
     *    cleared above.
     *
     * Deliberately NOT ported: iOS also wipes `messages` + `conversations` here
     * (`swift:1749-1751`) — its own alert copy says only "this will clear the pending
     * decryption queue", so the message wipe is undisclosed data loss. Android already
     * offers that explicitly as [clearAllMessages]. Sessions only.
     */
    fun clearSessions() {
        viewModelScope.launch {
            try {
                val dropped = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    doubleRatchet.resetAllSessions(context)
                }
                Log.d(TAG, "🔥 Cleared $dropped ratchet session(s) — peer must send a fresh message to re-establish")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing sessions: ${e.message}")
            }
        }
    }

    fun clearAllMessages() {
        viewModelScope.launch {
            try {
                database.clearAllTables()
                Log.d(TAG, "All messages and contacts cleared from database")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing messages: ${e.message}")
            }
        }
    }

    /**
     * 🔧 P0 FIX (F-2) — Disconnect used to be undone by the next cold launch.
     *
     * The dialog promises "this will clear all your data and you will need to
     * set up your identity again" (strings.xml `settings_disconnect_confirm`).
     * The old body cleared the database, `oshi_secure_keys` and DataStore — but
     * NOT `oshi_identity_backup`, the plain (Android-Auto-Backup-included) file
     * holding the 64-byte recovery blob. `MainViewModel`'s launch path B reads
     * that blob whenever DataStore says "not onboarded" and silently re-onboards
     * (`MainViewModel.kt:191-196` → `recoverIdentityFromDevice()` `:414-421`),
     * so the user came back to the same public key with an empty message store:
     * everything the dialog promised undone, and their history gone anyway.
     *
     * The delete-account path already cleared that file and named this exact
     * hazard in a comment. The lesser action now delegates to the greater one —
     * [wipeEverythingLocal] is a strict superset (push unregister + mesh
     * teardown + `clearAllTables()` + every owned prefs file + DataStore) — so
     * the two exit doors cannot drift apart again.
     *
     * Deliberately NOT calling `v2AccountClient.deleteAccount()`: Disconnect is
     * a local action, the server-side identity is meant to survive it, and iOS
     * draws the same line (`IdentityManager.disconnectWallet()` touches no
     * account route).
     */
    fun disconnect() {
        viewModelScope.launch {
            try {
                wipeEverythingLocal()
                Log.d(TAG, "User fully disconnected - all data wiped")
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect: ${e.message}")
            }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    fun formatPublicKey(key: String): String {
        return if (key.length > 12) {
            "${key.take(8)}...${key.takeLast(6)}"
        } else {
            key
        }
    }

    /**
     * __KEY_CREATED_SHOWED_TODAY_2026_11_07__ One-time seed for an identity that
     * predates the stamping fix. `firstInstallTime` is a real fact about this
     * device and bounds the key from above; `System.currentTimeMillis()` was a
     * fabrication that changed daily. Persisted so the row stops moving.
     */
    private fun backfillKeyCreationDate(): Long {
        val installed = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .firstInstallTime
        } catch (e: Exception) {
            Log.w(TAG, "could not read firstInstallTime: ${e.message}")
            0L
        }
        val seed = if (installed > 0) installed else System.currentTimeMillis()

        // Persisted on a SEPARATE coroutine, never inline. The caller runs
        // inside `dataStore.data.collect`, and `dataStore.edit` from inside a
        // collector of that same flow is a write to the thing being read —
        // DataStore serialises the two and it can deadlock. Launching it also
        // keeps the settings screen rendering immediately rather than waiting
        // on a disk write for a cosmetic row.
        //
        // The guard is re-read inside the edit because by the time it runs the
        // value may already be there (this collector fires more than once).
        viewModelScope.launch {
            try {
                context.dataStore.edit { prefs ->
                    if (prefs[KEY_KEY_CREATION_DATE] == null) prefs[KEY_KEY_CREATION_DATE] = seed
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not persist backfilled key creation date: ${e.message}")
            }
        }
        return seed
    }

    fun formatCreationDate(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }
}

/**
 * Export result sealed class
 */
sealed class ExportResult {
    data class Success(val uri: Uri, val mediaSkipped: Int = 0) : ExportResult()
    data class Error(val message: String) : ExportResult()
}

sealed class ImportResult {
    data class Success(val imported: Int, val alreadyPresent: Int) : ImportResult()
    data class Error(val error: com.oshi.messenger.service.MessageExportManager.ImportError) : ImportResult()
}
