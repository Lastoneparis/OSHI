package com.oshi.messenger.data.model

import android.content.Context

/**
 * The bio and accent colour THIS user set on their own profile.
 *
 * __PROFILE_BIO_2026_11_02__ (N4) Deliberately plain SharedPreferences, unlike
 * [PeerBioStore] beside it: this is the user's own text about themselves,
 * which they are about to broadcast to every contact anyway. The sealed store
 * exists to protect the map of OTHER people's keys to personal text — the
 * social graph — and that reasoning does not apply to one's own one-line bio.
 */
object OwnProfileExtras {

    private const val PREFS = "oshi_own_profile"
    private const val KEY_BIO = "bio"
    private const val KEY_BIO_UNTIL = "bio_until"
    private const val KEY_COLOR = "accent_color"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bio(context: Context): String? =
        ProfileBioWire.sanitize(prefs(context).getString(KEY_BIO, null))

    /**
     * When the bio stops being shown, or null for permanent.
     *
     * An expiry already in the past reads as null — the bio is simply over, and
     * returning a stale timestamp would have the sender stamp an expiry the
     * receiver would instantly honour by hiding text it had only just been given.
     */
    fun bioUntil(context: Context): Long? =
        prefs(context).getLong(KEY_BIO_UNTIL, 0L).takeIf { it > System.currentTimeMillis() }

    fun accentColor(context: Context): String? =
        ProfileBioWire.sanitizeColor(prefs(context).getString(KEY_COLOR, null))

    /** [until] null = permanent. Sanitised here so nothing unprintable is stored. */
    fun set(context: Context, bio: String?, until: Long?, color: String?) {
        prefs(context).edit().apply {
            val clean = ProfileBioWire.sanitize(bio)
            if (clean == null) remove(KEY_BIO) else putString(KEY_BIO, clean)
            if (clean == null || until == null) remove(KEY_BIO_UNTIL) else putLong(KEY_BIO_UNTIL, until)
            val c = ProfileBioWire.sanitizeColor(color)
            if (c == null) remove(KEY_COLOR) else putString(KEY_COLOR, c)
        }.apply()
    }
}
