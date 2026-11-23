package com.oshi.messenger.util

/**
 * Sanitizes message content for display in notifications, previews, and UI.
 * Strips all protocol prefixes and raw JSON to prevent leaking internal formats.
 */
object MessageSanitizer {

    /**
     * Sanitize raw message content for display in notifications and previews.
     * Returns a human-readable string, never raw JSON or protocol prefixes.
     */
    fun sanitizeForDisplay(content: String): String {
        if (content.isBlank()) return ""

        return when {
            // Reply prefix: extract just the reply text
            content.startsWith("↩️REPLY↩️") -> {
                try {
                    val json = content.removePrefix("↩️REPLY↩️")
                    val jsonObj = org.json.JSONObject(json)
                    jsonObj.optString("replyContent", "Message")
                } catch (_: Exception) { "Message" }
            }

            // Forwarded prefix: extract original content
            content.startsWith("➡️FORWARDED➡️") -> {
                try {
                    val json = content.removePrefix("➡️FORWARDED➡️")
                    val jsonObj = org.json.JSONObject(json)
                    val inner = jsonObj.optString("content", "")
                    if (inner.isNotEmpty()) "➡️ $inner" else "➡️ Forwarded message"
                } catch (_: Exception) { "➡️ Forwarded message" }
            }

            // Check-In
            content.startsWith("\uD83D\uDEE1\uFE0FCHECK_IN\uD83D\uDEE1\uFE0F") ||
            content.startsWith("🛡️CHECK_IN🛡️") -> "🛡️ Check-In"

            // Location
            content.startsWith("📍LOCATION📍") -> "📍 Location shared"

            // Profile/wallpaper/key system messages
            content.startsWith("📸PROFILE_UPDATE📸") -> "Profile updated"
            content.startsWith("📸PROFILE_REQUEST📸") -> "Profile request"
            content.startsWith("🎨WALLPAPER_UPDATE🎨") -> "Wallpaper changed"
            content.startsWith("🔑KEY_ROTATION🔑") -> "Encryption keys rotated"

            // Call signals and summaries
            content.startsWith("📞CALL_SIGNAL📞") -> "📞 Connecting..."
            content.startsWith("📱CALL_SUMMARY📱") -> "📞 Call"
            content.startsWith("📱MISSED_CALL📱") -> "📞 Missed Call"

            // Scheduled call
            content.startsWith("\uD83D\uDCC5SCHEDULED_CALL\uD83D\uDCC5") ||
            content.startsWith("📅SCHEDULED_CALL📅") -> "📅 Scheduled Call"

            // Reactions
            content.startsWith("🔥REACTION🔥") || content.startsWith("👍 REACTION👍") -> "Reaction"

            // Typing indicator
            content.startsWith("⌨️TYPING⌨️") -> ""
            content.startsWith("✍️TYPING✍️") -> ""

            // Delivery/read receipts — protocol messages, never display
            content.startsWith("📬DELIVERY_RECEIPT📬") -> ""
            content.startsWith("📖READ_RECEIPT📖") -> ""

            // Edit/Delete actions
            content.startsWith("✏️EDIT✏️") -> "Message edited"
            content.startsWith("🗑️DELETE🗑️") -> "Message deleted"

            // iOS unified ACTION prefix
            content.startsWith("⚡ACTION⚡") || content.startsWith("🔧ACTION🔧") -> "Action"

            // Group message prefix
            content.startsWith("👥GROUP_MESSAGE👥") -> "New message"

            // Media
            content.startsWith("MEDIA|||") -> {
                val parts = content.split("|||")
                if (parts.size >= 2) {
                    when (parts[1].lowercase()) {
                        "image" -> "📷 Photo"
                        "video" -> "🎥 Video"
                        "audio" -> "🎤 Voice message"
                        "document" -> "📄 Document"
                        else -> "📎 File"
                    }
                } else "📎 File"
            }

            // Audio/voice message
            content.startsWith("[AUDIO]") -> "🎤 Voice message"

            // LINKS_VISIBILITY prefix (iOS profile links visibility)
            content.startsWith("\uD83D\uDD17LINKS_VISIBILITY\uD83D\uDD17") -> "Profile links updated"

            // LINKS_ prefix (iOS profile links - legacy)
            content.startsWith("LINKS_") -> "Profile links updated"

            // Raw encrypted data detection
            content.startsWith("[Encrypted") || content.contains("ratchet sync") -> "🔒 Encrypted message"
            content.startsWith("{") && (content.contains("ciphertext") || content.contains("\"header\"")) -> "🔒 Encrypted message"

            // iOS system JSON events — group/profile protocol messages
            content.contains("links_visibility") -> ""
            content.contains("\"groupId\"") && content.contains("\"type\"") -> ""
            content.contains("\"encryptedContent\"") || content.contains("\"senderPublicKey\"") -> ""

            // Raw JSON detection (safety net)
            content.startsWith("{") && content.trimEnd().endsWith("}") -> ""

            // Normal text - return as-is
            else -> content
        }
    }
}
