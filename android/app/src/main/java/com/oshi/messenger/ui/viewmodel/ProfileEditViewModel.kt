package com.oshi.messenger.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.encryption.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import android.util.Base64
import javax.inject.Inject

/**
 * ProfileEditViewModel - Manages user profile data
 * Syncs profile picture with contacts via MessageRepository
 */
@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val messageRepository: MessageRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ProfileEditVM"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    private val _displayName = MutableStateFlow(prefs.getString("display_name", "") ?: "")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _profilePicture = MutableStateFlow<ByteArray?>(loadProfilePicture())
    val profilePicture: StateFlow<ByteArray?> = _profilePicture.asStateFlow()

    private val _myPublicKey = MutableStateFlow(cryptoManager.getPublicKeyString())
    val myPublicKey: StateFlow<String> = _myPublicKey.asStateFlow()

    // ⚰️ `telegramLink` / `twitterLink` / `instagramLink` / `websiteLink` and
    // `updateLinks(...)` DELETED (2026-08-13 cross-file integration, item H).
    // Unreferenced since the dead links editor was removed from `ProfileEditScreen`
    // (see the note left at `ProfileEditScreen.kt:75-76`). The `link_*` prefs they
    // wrote are cleaned up once in [purgeDeadLinkPrefs] so the values do not sit on
    // disk forever. Profile links that are actually shared with peers live in
    // `ProfileLinksManager`, not here.

    init {
        purgeDeadLinkPrefs()
    }

    /**
     * One-shot removal of the four orphaned `link_*` keys this ViewModel used to write.
     * Cheap, idempotent, and it means an uninstall is not the only way to get rid of a
     * social handle the user typed into a screen that no longer exists.
     */
    private fun purgeDeadLinkPrefs() {
        try {
            if (prefs.contains("link_telegram") || prefs.contains("link_twitter") ||
                prefs.contains("link_instagram") || prefs.contains("link_website")
            ) {
                prefs.edit()
                    .remove("link_telegram")
                    .remove("link_twitter")
                    .remove("link_instagram")
                    .remove("link_website")
                    .apply()
                Log.d(TAG, "🧹 Removed orphaned link_* profile prefs")
            }
        } catch (e: Exception) {
            Log.w(TAG, "link_* prefs purge failed: ${e.message}")
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            _displayName.value = name
            prefs.edit().putString("display_name", name).apply()
            // __SHARED_NICKNAME_2026_09_22__ This screen wrote ONLY its own "profile"
            // SharedPreferences, which nothing else reads: the nickname never reached
            // the DataStore key Settings and `MessageRepository.sendProfileUpdate`
            // use, so it was neither shown in Settings nor shared with contacts.
            // Mirror it there with the Settings constraints, then tell contacts.
            val normalized = name.trim().take(48)
            if (normalized.isNotEmpty()) {
                runCatching {
                    context.dataStore.edit { p ->
                        p[androidx.datastore.preferences.core.stringPreferencesKey("display_name")] = normalized
                    }
                    messageRepository.broadcastProfileToAllContacts()
                }.onFailure { Log.w(TAG, "Nickname broadcast failed: ${it.message}") }
            }
        }
    }

    fun updateProfilePicture(imageData: ByteArray) {
        viewModelScope.launch {
            _profilePicture.value = imageData
            // setMyProfilePicture compresses, saves to SharedPreferences, and persists
            messageRepository.setMyProfilePicture(imageData)
            // Broadcast to all contacts
            Log.d(TAG, "📸 Broadcasting profile picture to contacts...")
            messageRepository.broadcastProfileToAllContacts()
        }
    }

    fun removeProfilePicture() {
        viewModelScope.launch {
            _profilePicture.value = null
            messageRepository.setMyProfilePicture(null)
            // Broadcast removal to all contacts
            Log.d(TAG, "📸 Broadcasting profile picture removal to contacts...")
            messageRepository.broadcastProfileToAllContacts()
        }
    }

    private fun loadProfilePicture(): ByteArray? {
        val base64 = prefs.getString("profile_picture", null) ?: return null
        return try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
