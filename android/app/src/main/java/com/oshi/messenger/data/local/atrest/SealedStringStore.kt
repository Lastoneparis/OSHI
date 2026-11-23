package com.oshi.messenger.data.local.atrest

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * __LEAK_FIX_2026_09_25__ One string document (usually JSON) sealed at rest in its own
 * `OSHISEA1` file ([SealedFile]), with a transparent one-way migration out of a legacy
 * plaintext SharedPreferences entry.
 *
 * Several stores kept user content as a plaintext string in a `shared_prefs` XML file
 * (assistant chats, contact aliases, the check-in, the share queue). This moves each of
 * them to `files/<name>` sealed under the at-rest key, without a flag day:
 *
 *  * **read**: the sealed file wins. If there is none, the legacy entry is read, sealed
 *    into the file, re-opened and compared, and only THEN removed from the prefs
 *    (`commit()`). A failure at any step leaves the legacy entry where it was, so the
 *    data is still there on the next launch — nothing the user has can disappear.
 *  * **write**: [SealedFile.writeAtomic] (sealed when the key is available), then the
 *    legacy entry is dropped. Without a key the app's existing fail-safe applies (same
 *    as every other small store, see [AtRestKeyProvider]): the file is written in the
 *    clear rather than losing the write, and it is sealed by the next read that has a
 *    key. A sealed file is never overwritten when the key is missing.
 *
 * The legacy source is abstracted ([Legacy]) so the migration runs in a JVM test.
 */
class SealedStringStore(
    private val file: File,
    private val label: String,
    private val keys: AtRestKeyProvider,
    private val legacy: Legacy? = null,
) {
    /** A legacy plaintext location the value used to live in. */
    interface Legacy {
        fun read(): String?
        /** Remove it durably. Returns false when the removal did not stick. */
        fun clear(): Boolean
    }

    /** The stored value, or null when there is none (or it cannot be opened right now). */
    @Synchronized
    fun read(): String? {
        if (file.isFile) {
            // A file written in the clear during a Keystore outage: seal it now.
            if (SealedFile.isLegacyPlaintext(file)) {
                runCatching { SealedFile.migrate(file, label, keys) }
            }
            val text = try {
                SealedFile.readText(file, label, keys)
            } catch (e: IOException) {
                // Sealed but the key is unavailable (or the file is damaged): report
                // "nothing readable" and leave the file alone — never overwrite it.
                logW("sealed ${file.name} unreadable: ${e.message}")
                return null
            }
            // The file is authoritative; a leftover legacy copy is residue.
            val l = legacy
            if (l != null && text != null) runCatching { if (l.read() != null) l.clear() }
            return text
        }
        val old = legacy?.let { runCatching { it.read() }.getOrNull() } ?: return null
        migrateFromLegacy(old)
        return old
    }

    /** Replace the value. @throws IOException when it could not be stored. */
    @Synchronized
    fun write(value: String) {
        SealedFile.writeAtomic(file, label, keys, value.toByteArray(Charsets.UTF_8))
        val l = legacy
        if (l != null) runCatching { if (l.read() != null) l.clear() }
    }

    /** Delete the value everywhere (file and legacy copy). */
    @Synchronized
    fun clear() {
        synchronized(AtRestFileLocks.forFile(file)) { file.delete() }
        legacy?.let { runCatching { it.clear() } }
    }

    /**
     * Legacy → sealed file, only with a key (moving plaintext from one plaintext place
     * to another buys nothing). The legacy entry is removed only once the sealed file
     * has been read back and matches byte for byte.
     */
    private fun migrateFromLegacy(old: String): Boolean {
        if (AtRestSubKeys.sealed(keys) == null) return false
        return try {
            SealedFile.writeAtomic(file, label, keys, old.toByteArray(Charsets.UTF_8))
            val back = SealedFile.readText(file, label, keys)
            if (back != old || SealedFile.isLegacyPlaintext(file)) {
                logW("migration of ${file.name} did not verify — keeping the legacy copy")
                return false
            }
            legacy?.clear() == true
        } catch (e: Exception) {
            logW("migration of ${file.name} failed: ${e.message} — keeping the legacy copy")
            false
        }
    }

    private fun logW(msg: String) {
        // android.util.Log is a throwing stub in JVM unit tests.
        runCatching { Log.w("SealedStringStore", msg) }
    }

    companion object {
        /**
         * A string entry of a private SharedPreferences file as the legacy source.
         * [deleteFileWhenEmpty]: when the prefs file held nothing else, delete it
         * outright so no stale XML is left behind.
         */
        fun prefsLegacy(
            context: Context,
            prefsName: String,
            key: String,
            deleteFileWhenEmpty: Boolean = true,
        ): Legacy = object : Legacy {
            private val app = context.applicationContext
            override fun read(): String? =
                app.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null)

            override fun clear(): Boolean {
                val prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                if (!prefs.contains(key)) return true
                val ok = prefs.edit().remove(key).commit()
                if (ok && deleteFileWhenEmpty && prefs.all.isEmpty()) {
                    runCatching { app.deleteSharedPreferences(prefsName) }
                }
                return ok
            }
        }

        // The sealed documents that replaced plaintext prefs (__LEAK_FIX_2026_09_25__).
        const val AI_CHATS_FILE = "ai_chats.sealed"
        const val CHECKIN_SESSION_FILE = "checkin_session.sealed"
        const val PEER_NICKNAMES_FILE = "peer_nicknames.sealed"
        /**
         * __PROFILE_BIO_2026_11_02__ (N4) contact public key -> the bio that
         * person published, and when it stops being shown.
         *
         * Sealed for the same reason as the nicknames beside it: a map of
         * public key to personal text IS the user's social graph, and the
         * 2026-09-25 leak fix exists because that was once a readable XML file.
         */
        const val PEER_BIOS_FILE = "peer_bios.sealed"

        /**
         * Every file above. "Delete account" used to erase these contents by clearing
         * `shared_prefs`; now that they live in `files/`, [wipeAll] must run with it.
         */
        val ACCOUNT_FILES =
            listOf(AI_CHATS_FILE, CHECKIN_SESSION_FILE, PEER_NICKNAMES_FILE, PEER_BIOS_FILE)

        /** Delete every [ACCOUNT_FILES] entry. Returns how many were removed. */
        fun wipeAll(context: Context): Int {
            val dir = context.applicationContext.filesDir
            return ACCOUNT_FILES.count { name ->
                val f = File(dir, name)
                synchronized(AtRestFileLocks.forFile(f)) { f.isFile && f.delete() }
            }
        }

        /** The standard location for a sealed string store: `files/<fileName>`. */
        fun inFiles(context: Context, fileName: String, label: String, legacy: Legacy? = null) =
            SealedStringStore(File(context.applicationContext.filesDir, fileName), label, AtRestKey, legacy)
    }
}
