package com.oshi.messenger.util

/**
 * Which `.xml` files in `shared_prefs` an account wipe is allowed to erase.
 *
 * The wipe used to name its eight files by hand. A hand-written list rots, and
 * this one had: `oshi_ratchet` (Double-Ratchet chain keys), `v2_prekeys` (X3DH
 * private prekeys), `wallpaper`, `groups`, `pinned_messages`, `safety_numbers`,
 * `checkin`, `key_rotation`, `oshi_ai`, `oshi_prefs` and `fcm_prefs` all
 * survived "Delete account" — eleven files, two of them private key material.
 *
 * iOS is exhaustive by construction rather than by list
 * (`AccountDeletionView.swift:225-227` iterates
 * `defaults.dictionaryRepresentation().keys`), so this is too: enumerate the
 * directory and erase everything except a short KEEP list of stores we do not
 * own. Adding a new prefs file to the app now gets it wiped automatically.
 *
 * Pure and Android-free so it unit-tests on the JVM.
 */
object PrefsWipePolicy {

    /**
     * Prefixes of preference files owned by someone else. Clearing Firebase's
     * own stores out from under a live process is how you lose FCM delivery for
     * the NEXT identity — the push token is revoked deliberately elsewhere
     * (`FCMService.unregisterThisDevice`), not by deleting Google's bookkeeping.
     * `osmdroid` is map tile-cache configuration and holds nothing personal.
     */
    val KEEP_PREFIXES: List<String> = listOf(
        "com.google.",
        "com.android.",
        "androidx.",
        "android.",
        "WebViewChromium",
        "osmdroid"
    )

    /**
     * 🔴 __DB_KEY_SURVIVES_WIPE_2026_11_10__ Exact names that hold the key of a
     * file which OUTLIVES the wipe, so erasing the key strands the file.
     *
     * `oshi_db_key` is the SQLCipher passphrase of `oshi_database`
     * ([com.oshi.messenger.data.local.DatabasePassphrase.PREFS_NAME]). The wipe
     * empties that database with `clearAllTables()` (which also VACUUMs) but the
     * file stays and stays OPEN, keyed with the old passphrase, while the user
     * re-onboards in the same process. Clearing this store made the next cold
     * start generate a NEW passphrase, open the old file with it and throw
     * `SQLiteNotADatabaseException` on the first query — on EVERY launch, for
     * good. "Delete account" (and the panic wipe since 03/11) therefore ended
     * with an app that could never be opened again (Play Vitals vc118/124,
     * reproduced on the production 1.6.88 build). Nothing personal is in here:
     * the rows it protects are already gone.
     */
    val KEEP_NAMES: Set<String> = setOf("oshi_db_key")

    /** `true` when this preferences file must be erased by an account wipe. */
    fun shouldWipe(prefsName: String): Boolean =
        prefsName.isNotEmpty() &&
            prefsName !in KEEP_NAMES &&
            KEEP_PREFIXES.none { prefsName.startsWith(it) }

    /** Strip the `.xml` suffix `shared_prefs/` filenames carry. */
    fun prefsNameOf(fileName: String): String? =
        if (fileName.endsWith(".xml")) fileName.removeSuffix(".xml") else null
}
