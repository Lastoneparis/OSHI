package com.oshi.messenger.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.oshi.messenger.ui.components.ChatWallpaper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WallpaperManager - Persists chat wallpaper settings
 * Matches iOS ChatWallpaperManager.swift
 *
 * Stores wallpaper per-conversation or globally
 */
@Singleton
class WallpaperManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)

    // Global default wallpaper
    private val _globalWallpaper = MutableStateFlow<ChatWallpaper>(loadGlobalWallpaper())
    val globalWallpaper: StateFlow<ChatWallpaper> = _globalWallpaper.asStateFlow()

    // __WALLPAPER_PERSIST_2026_11_21__ Per-conversation wallpapers, PERSISTED.
    //
    // Until now the wallpaper a user picked for one chat lived only in
    // `MessageRepository._wallpapers`, an in-memory map nothing ever wrote to
    // disk: it survived until the process died, and the next launch showed the
    // app-wide default again. From the user's side, "wallpapers per chat don't
    // exist on Android" — they did, for a few minutes. The methods below were
    // the intended store (iOS `ChatWallpaperManager`), with no caller.
    //
    // Keys are a hash of the conversation id, never the id itself: these prefs
    // are not encrypted, and a list of contact public keys / group ids in plain
    // text would be a contact list on disk.
    private val conversationWallpapers = mutableMapOf<String, ChatWallpaper?>()

    /** Bumped on every per-conversation change, so UI reading [storedWallpaper] can re-read. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * Get wallpaper for a specific conversation
     * Falls back to global if not set
     */
    fun getWallpaper(conversationId: String): ChatWallpaper =
        storedWallpaper(conversationId) ?: _globalWallpaper.value

    /** The wallpaper chosen for [conversationId] itself, or null when it follows the default. */
    @Synchronized
    fun storedWallpaper(conversationId: String): ChatWallpaper? {
        if (conversationId.isEmpty()) return null
        if (conversationWallpapers.containsKey(conversationId)) return conversationWallpapers[conversationId]
        val wallpaper = loadWallpaper(convKey(conversationId))
        conversationWallpapers[conversationId] = wallpaper
        return wallpaper
    }

    /** True once the user has chosen an app-wide default (Settings, or "apply to all chats"). */
    fun hasChosenGlobal(): Boolean = prefs.contains("global_type")

    /**
     * Optional callback invoked whenever a wallpaper changes. The MessageRepository
     * wires this to push the change to all active web sessions.
     */
    var onWallpaperChanged: ((conversationId: String?, wallpaper: ChatWallpaper) -> Unit)? = null

    /**
     * Set wallpaper for a specific conversation
     */
    fun setWallpaper(conversationId: String, wallpaper: ChatWallpaper) {
        persistConversationWallpaper(conversationId, wallpaper)
        onWallpaperChanged?.invoke(conversationId, wallpaper)
    }

    /**
     * Store [wallpaper] for [conversationId] without telling linked web sessions
     * — for the 1:1 path, whose sync already went through the repository.
     */
    fun persistConversationWallpaper(conversationId: String, wallpaper: ChatWallpaper) {
        if (conversationId.isEmpty()) return
        synchronized(this) {
            conversationWallpapers[conversationId] = wallpaper
            removeKeys(convKey(conversationId))
            saveWallpaper(convKey(conversationId), wallpaper)
        }
        _revision.value++
    }

    /**
     * Set global wallpaper (default for all conversations)
     */
    fun setGlobalWallpaper(wallpaper: ChatWallpaper) {
        removeKeys("global")
        saveWallpaper("global", wallpaper)
        _globalWallpaper.value = wallpaper
        onWallpaperChanged?.invoke(null, wallpaper)
    }

    /**
     * Reset conversation to use global wallpaper
     */
    fun resetToGlobal(conversationId: String) {
        synchronized(this) {
            conversationWallpapers[conversationId] = null
            removeKeys(convKey(conversationId))
        }
        _revision.value++
    }

    /** Forget every per-conversation wallpaper ("use this in every chat"). */
    fun clearAllConversationWallpapers() {
        synchronized(this) {
            conversationWallpapers.clear()
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(CONV_PREFIX) }.forEach { editor.remove(it) }
            editor.apply()
        }
        _revision.value++
    }

    /** saveWallpaper writes a FAMILY of suffixed keys; a type change must not leave the old ones. */
    private fun removeKeys(key: String) {
        prefs.edit().apply {
            listOf("type", "style", "color", "color1", "color2", "gradient_name", "bgcolor", "emoji", "image")
                .forEach { remove("${key}_$it") }
            remove(key)
        }.apply()
    }

    private fun convKey(conversationId: String): String = CONV_PREFIX + hashId(conversationId)

    // =========================================================================
    // PERSISTENCE
    // =========================================================================

    private fun saveWallpaper(key: String, wallpaper: ChatWallpaper) {
        val editor = prefs.edit()

        when (wallpaper) {
            is ChatWallpaper.Default -> {
                editor.putString("${key}_type", "default")
            }
            is ChatWallpaper.OshiDynamic -> {
                editor.putString("${key}_type", "oshiDynamic")
                editor.putInt("${key}_style", wallpaper.styleId)
            }
            is ChatWallpaper.SolidColor -> {
                editor.putString("${key}_type", "solid")
                editor.putInt("${key}_color", wallpaper.color.toArgb())
            }
            is ChatWallpaper.Gradient -> {
                editor.putString("${key}_type", "gradient")
                editor.putInt("${key}_color1", wallpaper.colors[0].toArgb())
                editor.putInt("${key}_color2", wallpaper.colors[1].toArgb())
                editor.putString("${key}_gradient_name", wallpaper.name)
            }
            is ChatWallpaper.EmojiPattern -> {
                editor.putString("${key}_type", "emoji")
                editor.putString("${key}_emoji", wallpaper.emoji)
                editor.putInt("${key}_bgcolor", wallpaper.backgroundColor.toArgb())
            }
            is ChatWallpaper.ImageWallpaper -> {
                editor.putString("${key}_type", "image")
                editor.putString("${key}_image", Base64.encodeToString(wallpaper.imageData, Base64.NO_WRAP))
            }
        }

        editor.apply()
    }

    private fun loadWallpaper(key: String): ChatWallpaper? {
        val type = prefs.getString("${key}_type", null) ?: return null

        return when (type) {
            "default" -> ChatWallpaper.Default
            // Saved above; without this branch the `when (type)` would fall to its
            // else and a chosen animated backdrop would come back as null — a save
            // with no matching load is a silent drop, not persistence.
            "oshiDynamic" -> ChatWallpaper.OshiDynamic(prefs.getInt("${key}_style", 1))
            "solid" -> {
                val color = prefs.getInt("${key}_color", 0xFFE8E0D5.toInt())
                ChatWallpaper.SolidColor(Color(color))
            }
            "gradient" -> {
                val color1 = prefs.getInt("${key}_color1", 0xFFFF6B6B.toInt())
                val color2 = prefs.getInt("${key}_color2", 0xFFFFA500.toInt())
                val name = prefs.getString("${key}_gradient_name", "Custom") ?: "Custom"
                ChatWallpaper.Gradient(listOf(Color(color1), Color(color2)), name)
            }
            "emoji" -> {
                val emoji = prefs.getString("${key}_emoji", "💬") ?: "💬"
                val bgColor = prefs.getInt("${key}_bgcolor", 0xFFF0F0F0.toInt())
                ChatWallpaper.EmojiPattern(emoji, Color(bgColor))
            }
            "image" -> {
                val imageBase64 = prefs.getString("${key}_image", null)
                imageBase64?.let {
                    try {
                        ChatWallpaper.ImageWallpaper(Base64.decode(it, Base64.NO_WRAP))
                    } catch (e: Exception) { null }
                }
            }
            else -> null
        }
    }

    /**
     * __CHAT_POLISH_2026_09_24__ Never set ⇒ Mesh, iOS's shipped default
     * (`ChatWallpaperManager.swift`, `.oshiDynamic(constellation)`), not flat
     * beige. Only the NEVER-SET case changes: a stored choice — including an
     * explicit "Default" (`global_type = default`) — is returned untouched.
     */
    private fun loadGlobalWallpaper(): ChatWallpaper {
        return loadWallpaper("global") ?: FALLBACK_GLOBAL
    }

    companion object {
        private const val CONV_PREFIX = "convh_"

        /** First 128 bits of SHA-256, hex: a stable, non-reversible key for a conversation id. */
        internal fun hashId(id: String): String {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            return d.take(16).joinToString("") { "%02x".format(it) }
        }

        val FALLBACK_GLOBAL: ChatWallpaper =
            ChatWallpaper.OshiDynamic(com.oshi.messenger.ui.components.OshiDynamicStyle.DEFAULT.ordinal)
    }

    /**
     * Get all preset solid colors (matches iOS)
     */
    fun getSolidColorPresets(): List<Color> = listOf(
        Color(0xFFE8E0D5), // Cream (default)
        Color(0xFFE3F2FD), // Light Blue
        Color(0xFFFCE4EC), // Light Pink
        Color(0xFFE8F5E9), // Light Green
        Color(0xFFFFF3E0), // Light Orange
        Color(0xFFF3E5F5), // Light Purple
        Color(0xFF1A237E), // Dark Blue
        Color(0xFF121212), // Near Black
        Color(0xFFFFFFFF), // White
        Color(0xFFECEFF1)  // Light Gray
    )

    /**
     * Get all preset gradients (matches iOS)
     */
    fun getGradientPresets(): List<ChatWallpaper.Gradient> = listOf(
        ChatWallpaper.Gradient(listOf(Color(0xFFFF6B6B), Color(0xFFFFA500)), "Sunset"),
        ChatWallpaper.Gradient(listOf(Color(0xFF4FC3F7), Color(0xFF1565C0)), "Ocean"),
        ChatWallpaper.Gradient(listOf(Color(0xFF81C784), Color(0xFF2E7D32)), "Forest"),
        ChatWallpaper.Gradient(listOf(Color(0xFF303F9F), Color(0xFF1A237E)), "Night Sky"),
        ChatWallpaper.Gradient(listOf(Color(0xFF7B68EE), Color(0xFFFF69B4)), "Aurora"),
        ChatWallpaper.Gradient(listOf(Color(0xFFFF4500), Color(0xFFFF6347)), "Fire")
    )

    /**
     * Get all preset emoji patterns (matches iOS)
     */
    fun getEmojiPresets(): List<String> = listOf(
        "💬", "❤️", "🌸", "✨", "🌊", "🎵"
    )
}

/**
 * __WALLPAPER_PERSIST_2026_11_21__ For composables outside a Hilt ViewModel
 * (Settings) that must reach THE [WallpaperManager] singleton: a second
 * instance would have its own `globalWallpaper` flow and the open chats would
 * not see the change.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface WallpaperManagerEntryPoint {
    fun wallpaperManager(): WallpaperManager
}
