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

    // Cache for per-conversation wallpapers
    private val conversationWallpapers = mutableMapOf<String, ChatWallpaper>()

    /**
     * Get wallpaper for a specific conversation
     * Falls back to global if not set
     */
    fun getWallpaper(conversationId: String): ChatWallpaper {
        // Check cache first
        conversationWallpapers[conversationId]?.let { return it }

        // Load from prefs
        val wallpaper = loadWallpaper("conv_$conversationId") ?: _globalWallpaper.value
        conversationWallpapers[conversationId] = wallpaper
        return wallpaper
    }

    /**
     * Optional callback invoked whenever a wallpaper changes. The MessageRepository
     * wires this to push the change to all active web sessions.
     */
    var onWallpaperChanged: ((conversationId: String?, wallpaper: ChatWallpaper) -> Unit)? = null

    /**
     * Set wallpaper for a specific conversation
     */
    fun setWallpaper(conversationId: String, wallpaper: ChatWallpaper) {
        conversationWallpapers[conversationId] = wallpaper
        saveWallpaper("conv_$conversationId", wallpaper)
        onWallpaperChanged?.invoke(conversationId, wallpaper)
    }

    /**
     * Set global wallpaper (default for all conversations)
     */
    fun setGlobalWallpaper(wallpaper: ChatWallpaper) {
        _globalWallpaper.value = wallpaper
        saveWallpaper("global", wallpaper)
        onWallpaperChanged?.invoke(null, wallpaper)
    }

    /**
     * Reset conversation to use global wallpaper
     */
    fun resetToGlobal(conversationId: String) {
        conversationWallpapers.remove(conversationId)
        // 🧹 REMOVE WHAT saveWallpaper ACTUALLY WROTE.
        //
        // This used to remove `conv_<id>` — a key nothing ever writes. saveWallpaper
        // stores a FAMILY of suffixed keys (`conv_<id>_type`, `_color`, `_image`, …),
        // so the reset cleared the in-memory map and left every stored value on disk:
        // the override came straight back on the next launch.
        //
        // Unreachable today (no caller), which is exactly why it was never noticed —
        // and why it is worth fixing before the first "reset to default" button
        // reaches it.
        val key = "conv_$conversationId"
        prefs.edit().apply {
            listOf("type", "style", "color", "bgcolor", "gradient", "emoji", "image")
                .forEach { remove("${key}_$it") }
            remove(key)   // harmless, and covers any legacy unsuffixed value
        }.apply()
    }

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
