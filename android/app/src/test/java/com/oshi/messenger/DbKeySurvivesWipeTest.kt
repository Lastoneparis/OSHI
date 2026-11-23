package com.oshi.messenger

import com.oshi.messenger.data.local.DatabasePassphrase
import com.oshi.messenger.util.PrefsWipePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DB_KEY_SURVIVES_WIPE_2026_11_10__
 *
 * "Delete account" (and the panic wipe) cleared every prefs file we own,
 * including the SQLCipher passphrase of `oshi_database` — while the encrypted
 * file itself stayed. The next cold start generated a new passphrase, opened
 * the old file with it, and crashed on every launch, for good. Reproduced on
 * the production 1.6.88 build.
 */
class DbKeySurvivesWipeTest {

    @Test
    fun theDatabasePassphraseStoreIsNeverWiped() {
        assertFalse(
            "the DB key must survive a wipe: the database file it opens does",
            PrefsWipePolicy.shouldWipe(DatabasePassphrase.PREFS_NAME),
        )
    }

    @Test
    fun theKeepListNamesTheRealStore() {
        // A literal in the policy and a constant in DatabasePassphrase can drift
        // apart; this fails the day they do.
        assertTrue(DatabasePassphrase.PREFS_NAME in PrefsWipePolicy.KEEP_NAMES)
    }

    @Test
    fun theExactNameDoesNotShieldLookAlikes() {
        // An exact-name keep, not a prefix: a future `oshi_db_key_backup` or
        // identity store must still be erased.
        assertTrue(PrefsWipePolicy.shouldWipe("oshi_db_key_backup"))
        assertTrue(PrefsWipePolicy.shouldWipe("oshi_identity_backup"))
        assertTrue(PrefsWipePolicy.shouldWipe("oshi_files_key"))
    }
}
