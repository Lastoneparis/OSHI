package com.oshi.messenger.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProfileLinksManager - Handles incoming iOS profile link visibility messages.
 *
 * iOS sends: "LINKS_VISIBILITY{\"visible\":true,\"links\":{\"websiteURL\":\"...\", ...}}"
 * When visible=false the links bar is hidden.
 *
 * Compatible with iOS ProfileLinksManager.swift.
 */
@Singleton
class ProfileLinksManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ProfileLinksManager"
        const val LINKS_VISIBILITY_PREFIX = "\uD83D\uDD17LINKS_VISIBILITY\uD83D\uDD17"  // LINKS_VISIBILITY
        private const val PREFS_NAME = "profile_links_prefs"
        private const val KEY_CONTACT_LINKS = "contact_links"
        private const val KEY_CONTACT_VISIBLE = "contact_visible"
        private const val KEY_HIDDEN_BY_ME = "hidden_by_me"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // publicKey -> ProfileLinks
    private val _contactLinks = MutableStateFlow<Map<String, ProfileLinks>>(emptyMap())
    val contactLinks: StateFlow<Map<String, ProfileLinks>> = _contactLinks.asStateFlow()

    // publicKey -> visible (as sent by remote peer)
    private val _contactLinksVisible = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val contactLinksVisible: StateFlow<Map<String, Boolean>> = _contactLinksVisible.asStateFlow()

    // publicKey -> hidden locally by the user
    private val _hiddenByMe = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hiddenByMe: StateFlow<Map<String, Boolean>> = _hiddenByMe.asStateFlow()

    // Bump counter to trigger recomposition
    private val _linksVersion = MutableStateFlow(0L)
    val linksVersion: StateFlow<Long> = _linksVersion.asStateFlow()

    init {
        loadFromPrefs()
    }

    /**
     * Check whether the raw message content is a profile links visibility message.
     */
    fun isLinksVisibilityMessage(content: String): Boolean {
        return content.startsWith(LINKS_VISIBILITY_PREFIX)
    }

    /**
     * Process an incoming profile links visibility message from a contact.
     *
     * @param from The sender's public key.
     * @param content The full message content (prefix + JSON).
     */
    fun processIncomingVisibility(from: String, content: String) {
        try {
            val jsonString = content.removePrefix(LINKS_VISIBILITY_PREFIX)
            val json = JSONObject(jsonString)

            val visible = json.optBoolean("visible", false)
            val linksObj = json.optJSONObject("links")

            val links = if (linksObj != null) {
                ProfileLinks(
                    websiteURL = linksObj.optString("websiteURL", "").takeIf { it.isNotEmpty() },
                    linkedinURL = linksObj.optString("linkedinURL", "").takeIf { it.isNotEmpty() },
                    twitterURL = linksObj.optString("twitterURL", "").takeIf { it.isNotEmpty() },
                    instagramURL = linksObj.optString("instagramURL", "").takeIf { it.isNotEmpty() },
                    githubURL = linksObj.optString("githubURL", "").takeIf { it.isNotEmpty() }
                )
            } else {
                ProfileLinks()
            }

            // Update state maps
            val updatedLinks = _contactLinks.value.toMutableMap()
            updatedLinks[from] = links
            _contactLinks.value = updatedLinks

            val updatedVisible = _contactLinksVisible.value.toMutableMap()
            updatedVisible[from] = visible
            _contactLinksVisible.value = updatedVisible

            // If peer says not visible, also clear hiddenByMe so next time it can show
            if (!visible) {
                val updatedHidden = _hiddenByMe.value.toMutableMap()
                updatedHidden.remove(from)
                _hiddenByMe.value = updatedHidden
            }

            _linksVersion.value++
            saveToPrefs()

            Log.d(TAG, "Processed profile links from ${from.take(8)}...: visible=$visible, hasLinks=${links.hasAnyLink}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse links visibility message: ${e.message}")
        }
    }

    /**
     * Get the visible links for a contact, accounting for both the remote visibility flag
     * and the local "hidden by me" state.
     *
     * @return ProfileLinks if links should be shown, null otherwise.
     */
    fun getVisibleLinks(contactKey: String): ProfileLinks? {
        val visible = _contactLinksVisible.value[contactKey] ?: false
        val hiddenLocally = _hiddenByMe.value[contactKey] ?: false
        val links = _contactLinks.value[contactKey]

        if (!visible || hiddenLocally || links == null || !links.hasAnyLink) {
            return null
        }
        return links
    }

    /**
     * Hide the links bar locally for a given conversation. The peer's links
     * remain stored but will not display until the user explicitly shows them again
     * or the peer resends the visibility message.
     */
    fun hideLinksFor(conversationKey: String) {
        val updated = _hiddenByMe.value.toMutableMap()
        updated[conversationKey] = true
        _hiddenByMe.value = updated
        _linksVersion.value++
        saveToPrefs()
        Log.d(TAG, "Hidden links locally for ${conversationKey.take(8)}...")
    }

    /**
     * Show links again that were previously hidden locally.
     */
    fun showLinksFor(conversationKey: String) {
        val updated = _hiddenByMe.value.toMutableMap()
        updated.remove(conversationKey)
        _hiddenByMe.value = updated
        _linksVersion.value++
        saveToPrefs()
        Log.d(TAG, "Showing links again for ${conversationKey.take(8)}...")
    }

    // =========================================================================
    // PERSISTENCE
    // =========================================================================

    private fun saveToPrefs() {
        try {
            val linksJson = JSONObject()
            for ((key, links) in _contactLinks.value) {
                val linkObj = JSONObject()
                links.websiteURL?.let { linkObj.put("websiteURL", it) }
                links.linkedinURL?.let { linkObj.put("linkedinURL", it) }
                links.twitterURL?.let { linkObj.put("twitterURL", it) }
                links.instagramURL?.let { linkObj.put("instagramURL", it) }
                links.githubURL?.let { linkObj.put("githubURL", it) }
                linksJson.put(key, linkObj)
            }

            val visibleJson = JSONObject()
            for ((key, visible) in _contactLinksVisible.value) {
                visibleJson.put(key, visible)
            }

            val hiddenJson = JSONObject()
            for ((key, hidden) in _hiddenByMe.value) {
                hiddenJson.put(key, hidden)
            }

            prefs.edit()
                .putString(KEY_CONTACT_LINKS, linksJson.toString())
                .putString(KEY_CONTACT_VISIBLE, visibleJson.toString())
                .putString(KEY_HIDDEN_BY_ME, hiddenJson.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profile links: ${e.message}")
        }
    }

    private fun loadFromPrefs() {
        try {
            // Load links
            val linksStr = prefs.getString(KEY_CONTACT_LINKS, null)
            if (linksStr != null) {
                val linksJson = JSONObject(linksStr)
                val linksMap = mutableMapOf<String, ProfileLinks>()
                for (key in linksJson.keys()) {
                    val linkObj = linksJson.getJSONObject(key)
                    linksMap[key] = ProfileLinks(
                        websiteURL = linkObj.optString("websiteURL", "").takeIf { it.isNotEmpty() },
                        linkedinURL = linkObj.optString("linkedinURL", "").takeIf { it.isNotEmpty() },
                        twitterURL = linkObj.optString("twitterURL", "").takeIf { it.isNotEmpty() },
                        instagramURL = linkObj.optString("instagramURL", "").takeIf { it.isNotEmpty() },
                        githubURL = linkObj.optString("githubURL", "").takeIf { it.isNotEmpty() }
                    )
                }
                _contactLinks.value = linksMap
            }

            // Load visibility
            val visibleStr = prefs.getString(KEY_CONTACT_VISIBLE, null)
            if (visibleStr != null) {
                val visibleJson = JSONObject(visibleStr)
                val visibleMap = mutableMapOf<String, Boolean>()
                for (key in visibleJson.keys()) {
                    visibleMap[key] = visibleJson.getBoolean(key)
                }
                _contactLinksVisible.value = visibleMap
            }

            // Load hidden by me
            val hiddenStr = prefs.getString(KEY_HIDDEN_BY_ME, null)
            if (hiddenStr != null) {
                val hiddenJson = JSONObject(hiddenStr)
                val hiddenMap = mutableMapOf<String, Boolean>()
                for (key in hiddenJson.keys()) {
                    hiddenMap[key] = hiddenJson.getBoolean(key)
                }
                _hiddenByMe.value = hiddenMap
            }

            Log.d(TAG, "Loaded ${_contactLinks.value.size} profile link entries from prefs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile links: ${e.message}")
        }
    }
}

// =========================================================================
// DATA MODELS
// =========================================================================

data class ProfileLinks(
    val websiteURL: String? = null,
    val linkedinURL: String? = null,
    val twitterURL: String? = null,
    val instagramURL: String? = null,
    val githubURL: String? = null
) {
    val hasAnyLink: Boolean
        get() = listOfNotNull(websiteURL, linkedinURL, twitterURL, instagramURL, githubURL)
            .any { it.isNotEmpty() }

    val filledLinks: List<Pair<LinkType, String>>
        get() {
            val result = mutableListOf<Pair<LinkType, String>>()
            websiteURL?.takeIf { it.isNotEmpty() }?.let { result.add(LinkType.WEBSITE to it) }
            linkedinURL?.takeIf { it.isNotEmpty() }?.let { result.add(LinkType.LINKEDIN to it) }
            twitterURL?.takeIf { it.isNotEmpty() }?.let { result.add(LinkType.TWITTER to it) }
            instagramURL?.takeIf { it.isNotEmpty() }?.let { result.add(LinkType.INSTAGRAM to it) }
            githubURL?.takeIf { it.isNotEmpty() }?.let { result.add(LinkType.GITHUB to it) }
            return result
        }
}

enum class LinkType(val icon: String, val label: String, val urlPrefix: String? = null) {
    WEBSITE("\uD83C\uDF10", "Website"),                                              // 🌐 free-form
    LINKEDIN("\uD83D\uDCBC", "LinkedIn", "https://www.linkedin.com/in/"),            // 💼
    TWITTER("\uD83D\uDC26", "X", "https://x.com/"),                                  // 🐦
    INSTAGRAM("\uD83D\uDCF7", "Instagram", "https://www.instagram.com/"),            // 📷
    GITHUB("\uD83D\uDCBB", "GitHub", "https://github.com/"),                         // 💻
    TELEGRAM("\u2708\uFE0F", "Telegram", "https://t.me/")                            // ✈️
}
