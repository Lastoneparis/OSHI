package com.oshi.messenger.service

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.oshi.messenger.R
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * __ONE_BANNER_PER_MESSAGE_2026_09_23__ ONE banner per message, and it never shows the text.
 *
 * Reported on iOS (same pattern here): a group message produced TWO notifications — the
 * push banner, then a second one posted by the app after decrypting, carrying the group
 * name AND the plaintext. On Android the two paths even used different ids for the same
 * conversation: the FCM path hashed the groupId / sender key *as spelled in the push*
 * (iOS sends upper-case UUIDs, some senders base64url keys), the poll path hashed the
 * local spelling — so the second post did not replace the first, it stacked.
 *
 * Rule (owner's explicit requirement):
 *  - one notification id per CONVERSATION, identical on every path ([groupNotificationId],
 *    [contactNotificationId]) so a later post replaces instead of stacking;
 *  - a path that finds the same conversation's banner posted less than [RECENT_WINDOW_MS]
 *    ago does not re-post (no second sound for the same message arriving twice);
 *  - title = group name (sender alias allowed) or contact name/alias; body = the localized
 *    generic "New message" — the message text only if the user explicitly turned
 *    "notification preview" ON in Settings (default is now OFF).
 */
object MessageBanner {

    const val RECENT_WINDOW_MS = 20_000L

    /** Same canonical form as iOS `MessageBannerPolicy.normalizedKey`. */
    fun normalizeKey(key: String): String =
        key.trim().replace('-', '+').replace('_', '/').trimEnd('=')

    fun groupNotificationId(groupId: String): Int = ("g:" + groupId.trim().uppercase()).hashCode()

    fun contactNotificationId(publicKey: String): Int = ("c:" + normalizeKey(publicKey)).hashCode()

    /** Pure: was a notification with [id] posted within the window? */
    fun postedRecently(id: Int, active: List<Pair<Int, Long>>, now: Long): Boolean =
        active.any { (nid, postTime) -> nid == id && now - postTime in 0 until RECENT_WINDOW_MS }

    fun shownRecently(context: Context, id: Int, now: Long = System.currentTimeMillis()): Boolean = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        postedRecently(id, nm.activeNotifications.map { it.id to it.postTime }, now)
    } catch (_: Exception) {
        false
    }

    /** Message text in the banner only when the user explicitly enabled previews. */
    fun showsMessageText(context: Context): Boolean {
        val hideLegacy = try {
            context.getSharedPreferences("oshi_settings", Context.MODE_PRIVATE)
                .getBoolean("hide_notification_content", false)
        } catch (_: Exception) { false }
        if (hideLegacy) return false
        return try {
            runBlocking {
                context.applicationContext.dataStore.data.first()[booleanPreferencesKey("notification_preview")]
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun genericBody(context: Context): String = context.getString(R.string.notification_new_message)

    /** The body to display: generic unless previews were explicitly enabled. */
    fun body(context: Context, decryptedPreview: String): String =
        if (showsMessageText(context) && decryptedPreview.isNotBlank()) decryptedPreview else genericBody(context)

    /**
     * __NOTIFICATION_HAS_NO_FACE_2026_10_27__ Owner report N7: "afficher la photo
     * de profil ou le contenu du message dans le centre de notification (car cela
     * ne marche pas)".
     *
     * The photo never worked because it was never built. `setLargeIcon` appeared
     * NOWHERE in this app — not on the FCM push path, not on the decrypted 1:1
     * path, not on the group path — so every banner showed the OSHI glyph and
     * nothing else, while the contact's photo sat in the `contacts` table the
     * whole time.
     *
     * (The message TEXT half of that report is a different thing and already
     * works: [body] shows it as soon as "notification preview" is switched on in
     * Settings. It ships OFF by design — see the class note above.)
     *
     * Cropped square from the centre and masked to a circle here rather than left
     * to the platform: the shade rounds a large icon on some versions and not on
     * others, and a face sliced off at the corners looks like a bug.
     *
     * Returns null when there is no usable photo, which is exactly what
     * `setLargeIcon` wants — it then leaves the small icon alone.
     */
    fun avatar(photo: ByteArray?, sizePx: Int = 128): Bitmap? {
        if (photo == null || photo.isEmpty()) return null
        return try {
            val src = BitmapFactory.decodeByteArray(photo, 0, photo.size) ?: return null
            val side = minOf(src.width, src.height)
            if (side <= 0) return null
            val square = Bitmap.createBitmap(
                src, (src.width - side) / 2, (src.height - side) / 2, side, side
            )
            val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val r = sizePx / 2f
            Canvas(out).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            })
            out
        } catch (_: Exception) {
            null   // a corrupt avatar must never cost the user the notification
        }
    }
}
