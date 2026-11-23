package com.oshi.messenger.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.ContextCompat
import com.oshi.messenger.R
import com.oshi.messenger.data.local.AppDatabase
import java.io.ByteArrayOutputStream

/**
 * Writes the welcome conversation for a brand-new identity. The Android shell
 * around [WelcomeMessage]: SharedPreferences, string resources, Room, the app
 * icon. Every DECISION lives in [WelcomeMessage], where a JVM test can reach it.
 *
 * PORT OF `OSHI/WelcomeMessageManager.swift:55-86, :129-146`.
 */
object WelcomeMessageSeeder {

    private const val TAG = "WelcomeSeeder"

    /**
     * Same file the other one-shot migrations use
     * (`MessageRepository`'s `oshi_migrations`), so a "has this device already
     * done X once" flag is in one place rather than a new file per feature.
     */
    private const val PREFS = "oshi_migrations"

    /** Avatar size. Matches `MessageRepository.compressProfileImage`'s 200 px. */
    private const val AVATAR_PX = 200

    fun hasSeeded(context: Context, publicKey: String): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(WelcomeMessage.seededPrefKey(publicKey), false)

    /**
     * Seed the welcome conversation, once per identity, ever.
     *
     * @param isRestoredIdentity true when the user came in through a recovery
     *        key or the Auto-Backup blob. A restore is not a new user — they have
     *        their history, and greeting them again would be noise.
     *        `WelcomeMessageManager.swift:52-57`.
     *
     * Suspending and doing its own IO: the caller is already inside
     * `viewModelScope`, and the two Room writes must not be fired and forgotten
     * or the chat list can render before they land.
     */
    suspend fun seedIfNeeded(
        context: Context,
        publicKey: String,
        isRestoredIdentity: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val app = context.applicationContext

        // __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ Run the migration FIRST,
        // and unconditionally — before the `shouldSeed` early return. Everyone
        // who already has the greeting has `hasSeeded == true`, so putting this
        // after the return would migrate precisely nobody: the users who need it
        // are exactly the users who never reach the seeding path again. iOS
        // learned the same thing the slow way (`__MIGRATION_RAN_TOO_EARLY__`).
        migrateLegacyWelcomeThread(app)

        if (!WelcomeMessage.shouldSeed(publicKey, isRestoredIdentity, hasSeeded(app, publicKey))) return

        // Mark BEFORE inserting. If the insert throws or the app is killed
        // mid-way, a duplicate greeting on every launch would be worse than no
        // greeting at all. `WelcomeMessageManager.swift:60-63`.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WelcomeMessage.seededPrefKey(publicKey), true)
            .apply()

        try {
            val db = AppDatabase.getInstance(app)

            // The contacts row FIRST: `getConversations()` is driven off the
            // contacts table, so inserting the messages first would leave a
            // window in which they exist and no thread shows them.
            db.contactDao().insert(WelcomeMessage.contactRow(appIconPng(app)))

            db.messageDao().insert(
                WelcomeMessage.greetingRow(
                    myPublicKey = publicKey,
                    body = app.getString(R.string.welcome_message_body),
                    atMillis = nowMillis,
                )
            )
            db.messageDao().insert(
                WelcomeMessage.contactCardRow(
                    myPublicKey = publicKey,
                    caption = app.getString(R.string.welcome_message_card_caption),
                    atMillis = nowMillis + WelcomeMessage.CARD_OFFSET_MS,
                )
            )
            Log.d(TAG, "👋 Welcome conversation seeded for ${publicKey.take(8)}…")
        } catch (e: Exception) {
            // The flag stays set. A greeting that failed once is not worth
            // retrying on every launch for ever; the user is not blocked by its
            // absence, and they would be annoyed by its duplication.
            Log.w(TAG, "Welcome seed failed: ${e.message}", e)
        }
    }

    /**
     * Give the greeting a face. `WelcomeMessageManager.swift:121-146`.
     *
     * The sender is `oshi.welcome`, which is not a person and therefore has no
     * profile picture, so without this the first thing a brand-new user sees is a
     * grey initial. The launcher icon is already in the app; reusing it costs
     * nothing and needs no new asset. Returns null rather than throwing — a
     * greeting with an initial is still a greeting.
     */
    private fun appIconPng(context: Context): ByteArray? = try {
        val d: Drawable? = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        when {
            d == null -> null
            // A plain bitmap icon is already the right thing; only an adaptive /
            // vector icon has to be rasterised, and asking a BitmapDrawable to
            // rasterise itself loses nothing but is pointless work.
            d is BitmapDrawable && d.bitmap != null -> d.bitmap.toPng()
            else -> {
                val bmp = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                d.setBounds(0, 0, AVATAR_PX, AVATAR_PX)
                d.draw(canvas)
                bmp.toPng()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "welcome avatar: launcher icon not loadable — greeting keeps its initial", e)
        null
    }

    private fun Bitmap.toPng(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /**
     * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ Move an existing greeting
     * thread off the legacy `oshi.welcome` label and onto the author's real key,
     * so it can finally be replied to.
     *
     * Idempotent and cheap: two indexed UPDATEs that match nothing once done.
     * Failures are swallowed deliberately — a greeting that stays unmigrated is
     * a worse conversation, not a broken app, and this runs on the launch path.
     */
    private suspend fun migrateLegacyWelcomeThread(app: Context) {
        try {
            val db = AppDatabase.getInstance(app)
            val legacy = WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY
            val real = WelcomeMessage.AUTHOR_PUBLIC_KEY
            val sent = db.messageDao().migrateWelcomeSender(legacy, real)
            val received = db.messageDao().migrateWelcomeRecipient(legacy, real)
            if (sent + received > 0) {
                android.util.Log.i(
                    "WelcomeSeeder",
                    "migrated welcome thread: $sent inbound, $received outbound rows",
                )
                // The contact row is keyed by the same string; without this the
                // thread migrates but the chat list still shows the old entry.
                runCatching { db.contactDao().deleteByPublicKey(legacy) }
                runCatching { db.contactDao().insert(WelcomeMessage.contactRow(appIconPng(app))) }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WelcomeSeeder", "welcome migration skipped: ${t.message}")
        }
    }
}
