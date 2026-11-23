package com.oshi.messenger.network.encryption

import android.content.Context
import com.oshi.messenger.network.v2.GroupLegacyCompat

/**
 * What each peer's build can do, learned from the profile payload contacts
 * already exchange (`📸PROFILE_UPDATE📸`).
 *
 * Absent is always read as **cannot**, never as "probably". Both capabilities
 * gate a format the other side must be able to read:
 *
 *  * [supportsRatchetV3] — a v3 session and a v2 one cannot exchange a single
 *    message, because `DoubleRatchet.decrypt` compares `sessionVersion` strictly;
 *  * [supportsGroupV3]   — a client that predates envelope v3 DROPS one rather
 *    than mis-decrypting it.
 *
 * So guessing yes does not degrade the conversation, it ends it. Guessing no
 * only keeps the old behaviour one release longer.
 *
 * Mirrors `PeerCapabilityStore` on iOS.
 */
object PeerCapabilities {

    private const val PREFS = "peer_capabilities_v1"
    private const val KEY_GROUP_V3 = "groupV3"
    private const val KEY_RATCHET_V3 = "ratchetV3"

    /**
     * Per-peer app version, stored one key per peer (`appVersion:<publicKey>`)
     * rather than in a set, because unlike the two booleans this is a VALUE and
     * it changes every time they update.
     */
    private const val KEY_APP_VERSION_PREFIX = "appVersion:"

    /**
     * Which version line [appVersion] belongs to (`"android"` / `"ios"`). Stored
     * beside it because the number is meaningless without it: Android numbers this
     * app 1.6.x and iOS numbers it 1.0.x.
     */
    private const val KEY_APP_PLATFORM_PREFIX = "appPlatform:"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * __TWO_CAPABILITY_STORES_2026_10_19__ Two sources, and the second one is the
     * authoritative one.
     *
     * [KEY_GROUP_V3] is learned from a profile payload, and a peer only sends its
     * profile once the user has messaged it — it carries a photo and a nickname,
     * so withholding it is deliberate. That leaves a brand-new device unreachable
     * in groups until it happens to send a profile, and nothing makes it send one:
     * added to a roster, it stays invisible.
     *
     * The `caps` bitmask on the peer's prekey bundle says the same thing, is
     * published by the peer itself, and leaks nothing. [GroupLegacyCompat] already
     * persists it for every bundle fetched ([V2KeysClient] calls `notePeerCaps`),
     * and already folds it into the legacy-fallback decision — it just never
     * reached this function, which is what the group paths ask.
     */
    fun supportsGroupV3(context: Context, peer: String): Boolean {
        if (peer.isEmpty()) return false
        if (prefs(context).getStringSet(KEY_GROUP_V3, emptySet())!!.contains(peer)) return true
        return GroupLegacyCompat.hasGroupV2(GroupLegacyCompat.capsOf(peer))
    }

    fun supportsRatchetV3(context: Context, peer: String): Boolean =
        peer.isNotEmpty() && prefs(context).getStringSet(KEY_RATCHET_V3, emptySet())!!.contains(peer)

    /**
     * The `appVersion` this peer last reported in its profile payload, or `null`
     * if they have never sent one.
     *
     * `null` is NOT "old". Every build that predates the field omits it, and so
     * does every build on a platform that has not shipped the field yet, so a
     * missing version means "no opinion" and nothing is drawn for that peer.
     */
    fun appVersion(context: Context, peer: String): String? {
        if (peer.isEmpty()) return null
        return prefs(context).getString(KEY_APP_VERSION_PREFIX + peer, null)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The version LINE the peer's [appVersion] belongs to (`"android"` / `"ios"`),
     * or `null` if they never said. `null` must be read as "do not judge this
     * version", never as a default platform.
     */
    fun appPlatform(context: Context, peer: String): String? {
        if (peer.isEmpty()) return null
        return prefs(context).getString(KEY_APP_PLATFORM_PREFIX + peer, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** Remember (or, on `null`/blank, forget) a peer's reported app version. */
    fun recordAppVersion(
        context: Context,
        peer: String,
        version: String?,
        platform: String? = null
    ) {
        if (peer.isEmpty()) return
        val editor = prefs(context).edit()
        val clean = version?.trim()
        val versionKey = KEY_APP_VERSION_PREFIX + peer
        if (clean.isNullOrEmpty()) editor.remove(versionKey) else editor.putString(versionKey, clean)
        // The platform is written in the same transaction as the version it
        // qualifies, so the two can never be stored out of step — a stale platform
        // beside a fresh version from the OTHER platform is precisely the false
        // positive this tag exists to remove.
        val platformKey = KEY_APP_PLATFORM_PREFIX + peer
        val cleanPlatform = platform?.trim()?.lowercase()
        if (cleanPlatform.isNullOrEmpty()) editor.remove(platformKey)
        else editor.putString(platformKey, cleanPlatform)
        editor.apply()
    }

    /**
     * @param appVersion the peer's `versionName`, when they sent one. Defaulted so
     *   existing call sites keep compiling; passing `null` leaves any previously
     *   recorded version alone rather than erasing it, because a payload that
     *   omits the field tells us nothing new.
     */
    fun record(
        context: Context,
        peer: String,
        groupV3: Boolean,
        ratchetV3: Boolean,
        appVersion: String? = null,
        appPlatform: String? = null
    ) {
        if (peer.isEmpty()) return
        if (!appVersion.isNullOrBlank()) recordAppVersion(context, peer, appVersion, appPlatform)
        val p = prefs(context)
        // Copy the returned sets: SharedPreferences documents the instance it
        // hands back as not safe to mutate, and doing so is a classic way to lose
        // the write silently.
        val g = HashSet(p.getStringSet(KEY_GROUP_V3, emptySet())!!)
        val r = HashSet(p.getStringSet(KEY_RATCHET_V3, emptySet())!!)
        if (groupV3) g.add(peer) else g.remove(peer)
        if (ratchetV3) r.add(peer) else r.remove(peer)
        p.edit().putStringSet(KEY_GROUP_V3, g).putStringSet(KEY_RATCHET_V3, r).apply()
    }

    /** Every member must be able to read v3 before a group may use it. */
    fun allSupportGroupV3(context: Context, members: List<String>): Boolean =
        members.isNotEmpty() && members.all { supportsGroupV3(context, it) }

    fun clearForTesting(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
