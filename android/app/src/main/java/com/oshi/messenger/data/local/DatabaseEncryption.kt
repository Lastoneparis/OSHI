package com.oshi.messenger.data.local

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * SQLCipher wiring for `oshi_database`: builds the Room open-helper factory and
 * performs the ONE-TIME, defensive plaintext -> encrypted migration.
 *
 * ── DATA SAFETY IS THE FIRST PRINCIPLE ─────────────────────────────────────
 * An unsafe migration is worse than a plaintext DB. Every path here is written
 * so that on ANY failure the ORIGINAL plaintext database is left untouched and
 * the app falls back to opening it unencrypted. We only delete plaintext after
 * the encrypted copy is proven openable with the passphrase AND row counts of
 * every table match.
 *
 * ── ACTIVÉ (ENABLED = true), APRÈS EXÉCUTION RÉELLE SUR APPAREIL ───────────
 * Le drapeau est resté à `false` tant que la migration ne pouvait pas être
 * PROUVÉE sûre: la bibliothèque native de SQLCipher ne tourne pas sous
 * l'environnement JVM/Robolectric de cette machine, et le plan de test était
 * manuel — sept étapes à refaire au doigt, donc jamais rejouées.
 *
 * `DatabaseMigrationInstrumentedTest` exécute maintenant ces étapes sur
 * appareil, automatiquement, injection de faute comprise: 250 messages et 7
 * conversations migrés, comptes identiques avant et après, fichier illisible en
 * clair, version de schéma conservée, pas de re-migration au second lancement,
 * et une tentative interrompue qui laisse l'original intact.
 *
 * ⚠️ CE QUE CETTE EXÉCUTION A TROUVÉ, et que la relecture n'avait pas vu: la
 * migration NE FONCTIONNAIT PAS. Trois défauts se cachaient derrière le drapeau
 * désactivé — la bibliothèque native n'était jamais chargée, `rawExecSQL` ne
 * faisait pas avancer le `SELECT` qui déclenche l'export, et `ATTACH` n'arrivait
 * pas à créer le fichier cible. Aucun ne levait d'exception: la migration
 * rendait simplement `false` et l'application restait en clair. Activer le
 * drapeau tel quel aurait donné un chiffrement au repos qui n'existe pas — le
 * pire résultat possible pour un contrôle que l'on déclare mis en œuvre.
 *
 * La règle qui prime reste la même: une migration ratée est PIRE qu'une base en
 * clair. Chaque chemin ci-dessous laisse l'original intact en cas d'échec, et la
 * suppression du clair n'a lieu qu'après vérification des comptes.
 */
object DatabaseEncryption {
    private const val TAG = "DatabaseEncryption"

    const val DB_NAME = "oshi_database"

    /**
     * Master switch. DEFAULT-OFF: while false, [openHelperFactory] returns null
     * and Room opens the plaintext database exactly as before — zero behavioural
     * change, zero migration, zero data-loss surface. See the class KDoc for why.
     */
    const val ENABLED = true

    /**
     * SQLCipher 4.x (`net.zetetic:sqlcipher-android`) NE CHARGE PAS sa
     * bibliothèque native tout seul: il faut `System.loadLibrary("sqlcipher")`
     * avant le premier appel, sinon chaque ouverture lève
     * `UnsatisfiedLinkError: No implementation found for … nativeOpen`.
     *
     * ⚠️ CE CHARGEMENT MANQUAIT, et le drapeau désactivé le cachait. Basculer
     * [ENABLED] à `true` tel quel aurait été pire que de ne rien chiffrer:
     * `isPlaintextDatabase` attrape tout `Throwable` et rend `false`, donc une
     * base EN CLAIR aurait été prise pour « déjà chiffrée », la migration sautée,
     * puis Room aurait tenté de l'ouvrir avec une clé. Trouvé en exerçant
     * réellement la migration sur appareil — pas en relisant le code.
     *
     * Rend `false` plutôt que de lever: une bibliothèque absente n'est pas une
     * raison de faire tomber l'application, c'est une raison de rester en clair.
     */
    private val nativeAvailable: Boolean by lazy {
        try {
            System.loadLibrary("sqlcipher")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "bibliothèque SQLCipher indisponible; la base reste en clair", t)
            false
        }
    }

    /**
     * À appeler avant toute utilisation directe de `SQLiteDatabase`, y compris
     * depuis les tests: le chargement est idempotent et ne coûte rien après la
     * première fois.
     */
    internal fun ensureNativeLoaded(): Boolean = nativeAvailable

    /**
     * The Room open-helper factory to install, or null to keep Room on its
     * default (plaintext) helper.
     *
     * Contract for the caller (AppDatabase.getInstance): call this BEFORE
     * Room opens the database. A null return means "open plaintext" and is the
     * safe fallback for every failure — disabled flag, unavailable KeyStore, or
     * a migration that did not complete cleanly.
     */
    fun openHelperFactory(context: Context): SupportSQLiteOpenHelper.Factory? {
        if (!ENABLED) return null

        // Sans la bibliothèque native, TOUT échouerait en aval — y compris la
        // détection « cette base est-elle en clair ? », dont l'échec ressemble
        // à « elle est déjà chiffrée ». On reste en clair, ce qui est le repli
        // sûr, plutôt que de rendre la base inouvrable.
        if (!nativeAvailable) return null

        val dbFile = context.getDatabasePath(DB_NAME)
        val passphrase = DatabasePassphrase.getOrCreate(context)
        if (passphrase == null) {
            // KeyStore refused. Do NOT encrypt — an unopenable DB (no key) would
            // strand the user. Stay plaintext.
            //
            // 🔴 __DB_KEYSTORE_HICCUP_2026_11_11__ ...but NEVER hand Room an
            // ENCRYPTED file without its key. Room's plaintext helper reads it as
            // "file is not a database", reports corruption, and Android's handler
            // DELETES the file: every contact and every message, silently, while the
            // app opens normally on an empty list. Reproduced on the Play build of
            // 1.6.93 by making the passphrase store unreadable for one launch. The
            // file is moved aside instead, and [restoreSetAside] puts it back on the
            // next launch that can read the key.
            if (dbFile.exists() && dbFile.length() > 0 && !hasPlaintextHeader(dbFile)) {
                if (!moveSet(dbFile, KEYLESS_PREFIX)) {
                    // Opening it keyless would destroy it. A failed launch is
                    // recoverable; a deleted database is not.
                    throw IllegalStateException("Encrypted database locked (Keystore unavailable) and could not be set aside")
                }
                Log.e(TAG, "Keystore unavailable: encrypted DB set aside (kept), this launch starts on an empty interim database.")
            } else {
                Log.w(TAG, "No DB passphrase available; keeping database plaintext.")
            }
            return null
        }

        // A database set aside by an earlier keyless launch comes back first,
        // with whatever that launch received merged into it.
        restoreSetAside(dbFile, passphrase)

        if (dbFile.exists() && !isPlaintextDatabase(dbFile)) {
            // __DB_KEY_SURVIVES_WIPE_2026_11_10__ An encrypted file our key cannot
            // open is a crash on EVERY launch: Room throws on the first query and
            // the user can never get back in. See [quarantineIfKeyCannotOpen].
            quarantineIfKeyCannotOpen(dbFile, passphrase)
        }
        if (dbFile.exists() && isPlaintextDatabase(dbFile)) {
            val migrated = try {
                migratePlaintextToEncrypted(dbFile, passphrase)
            } catch (e: Throwable) {
                Log.e(TAG, "Plaintext->encrypted migration threw; leaving plaintext intact.", e)
                false
            }
            if (!migrated) {
                // Migration did not complete. Plaintext file is intact (we never
                // delete it before verification). Fall back to opening plaintext.
                return null
            }
        }
        // Either: fresh install (no file) -> encrypted DB created on open;
        // already-encrypted file -> opened with the key; just-migrated -> opened.
        return SupportOpenHelperFactory(passphrase.encodeToByteArray())
    }

    /**
     * True if [file] can be read as an UNENCRYPTED SQLite database. Used to
     * decide whether a migration is needed: an already-encrypted DB (or a wrong
     * key) makes the first query throw, and we then skip migration.
     */
    /**
     * `internal` plutôt que `private` UNIQUEMENT pour que le test instrumenté
     * puisse exercer la migration pour de vrai. Le plan de test documenté plus
     * bas est manuel; l'automatiser demande d'appeler ces deux fonctions, et un
     * test qui les réimplémenterait ne prouverait rien de ce qui est livré.
     * Aucun appelant de production hors de cet objet ne doit s'en servir.
     */
    internal fun isPlaintextDatabase(file: File): Boolean {
        var db: SQLiteDatabase? = null
        return try {
            // Empty key => SQLCipher opens the file as plaintext SQLite.
            db = SQLiteDatabase.openDatabase(
                file.absolutePath, "", null,
                SQLiteDatabase.OPEN_READONLY, null,
            )
            db.rawQuery("SELECT count(*) FROM sqlite_master", arrayOf<String>()).use { it.moveToFirst() }
            true
        } catch (e: Throwable) {
            false
        } finally {
            try {
                db?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Migrate the plaintext [dbFile] to an encrypted database keyed by
     * [passphrase], using SQLCipher's `sqlcipher_export`.
     *
     * Steps, all defensive:
     *   1. Export plaintext -> a SIBLING temp file (never overwrites the original).
     *   2. Carry the Room schema version (PRAGMA user_version) to the encrypted DB.
     *   3. VERIFY: encrypted DB opens with the key and every table's row count
     *      matches the plaintext DB.
     *   4. Only then swap: delete plaintext + its -wal/-shm/-journal, rename temp
     *      into place.
     * On any failure we delete the temp file and return false, leaving the
     * original plaintext database exactly as it was.
     *
     * @return true only if the encrypted DB is in place and verified.
     */
    internal fun migratePlaintextToEncrypted(dbFile: File, passphrase: String): Boolean {
        val tempFile = File(dbFile.parentFile, "$DB_NAME.encrypting.tmp")
        // Clean any leftover from a previously-interrupted attempt.
        deleteQuietly(tempFile)

        val plaintextCounts: Map<String, Long>
        val schemaVersion: Int

        // ---- Phase 1: export plaintext -> encrypted temp, read counts + version.
        //
        // ⚠️ LE SENS DE L'EXPORT COMPTE, et l'inverse échoue en silence.
        //
        // La version précédente ouvrait la base EN CLAIR puis y attachait la
        // cible chiffrée (`ATTACH … KEY`). Mesuré sur appareil: `ATTACH` n'arrive
        // pas à CRÉER ce fichier — `SQLITE_CANTOPEN (14)` — alors que
        // `openOrCreateDatabase` y parvient dans le même dossier. Pire, avec
        // `rawExecSQL` rien ne levait: le temporaire n'existait simplement pas,
        // la vérification échouait ensuite, la migration rendait `false`, et
        // l'application restait silencieusement en clair.
        //
        // On CRÉE donc d'abord la base chiffrée, puis on y attache la base en
        // clair et on exporte depuis elle. `ATTACH` n'a alors plus rien à créer.
        //
        // Et l'export passe par un CURSEUR: `rawExecSQL` ne fait pas avancer un
        // `SELECT`, donc `sqlcipher_export` n'était jamais évalué.
        val plaintextCounts2: Map<String, Long>
        var enc0: SQLiteDatabase? = null
        try {
            enc0 = SQLiteDatabase.openOrCreateDatabase(tempFile.absolutePath, passphrase, null, null)
            enc0.rawExecSQL(
                "ATTACH DATABASE ${sqlLiteral(dbFile.absolutePath)} AS plaintext KEY ''"
            )
            enc0.rawQuery("SELECT sqlcipher_export('main', 'plaintext')", arrayOf<String>())
                .use { it.moveToFirst() }
            // La version de schéma de Room doit traverser, sinon Room croirait
            // devoir re-migrer la base.
            schemaVersion = enc0.rawQuery("PRAGMA plaintext.user_version", arrayOf<String>())
                .use { it.moveToFirst(); it.getInt(0) }
            plaintextCounts2 = tableRowCounts(enc0)
            enc0.rawExecSQL("DETACH DATABASE plaintext")
            enc0.version = schemaVersion
        } catch (e: Throwable) {
            Log.e(TAG, "Export phase failed; plaintext untouched.", e)
            try {
                enc0?.rawExecSQL("DETACH DATABASE plaintext")
            } catch (_: Throwable) {
            }
            try {
                enc0?.close()
            } catch (_: Throwable) {
            }
            deleteQuietly(tempFile)
            return false
        } finally {
            try {
                enc0?.close()
            } catch (_: Throwable) {
            }
        }
        plaintextCounts = plaintextCounts2

        // ---- Phase 2: set schema version on the encrypted copy + verify counts.
        var enc: SQLiteDatabase? = null
        val encryptedCounts: Map<String, Long>
        try {
            enc = SQLiteDatabase.openDatabase(
                tempFile.absolutePath, passphrase, null,
                SQLiteDatabase.OPEN_READWRITE, null,
            )
            // La version a déjà été posée en phase 1; on la RELIT pour vérifier
            // qu'elle a bien traversé plutôt que de la reposer à l'aveugle.
            if (enc.version != schemaVersion) {
                Log.e(TAG, "Schema version did not survive: ${enc.version} != $schemaVersion")
                throw IllegalStateException("schema version mismatch")
            }
            encryptedCounts = tableRowCounts(enc)
        } catch (e: Throwable) {
            Log.e(TAG, "Encrypted verify-open failed; discarding temp, plaintext untouched.", e)
            try {
                enc?.close()
            } catch (_: Throwable) {
            }
            deleteQuietly(tempFile)
            return false
        } finally {
            try {
                enc?.close()
            } catch (_: Throwable) {
            }
        }

        if (encryptedCounts != plaintextCounts) {
            Log.e(
                TAG,
                "Row-count mismatch after export (plaintext=$plaintextCounts encrypted=$encryptedCounts); " +
                    "discarding temp, plaintext untouched.",
            )
            deleteQuietly(tempFile)
            return false
        }

        // ---- Phase 3: atomic-ish swap. Encrypted copy is proven good.
        val parent = dbFile.parentFile
        val walFile = File(parent, "$DB_NAME-wal")
        val shmFile = File(parent, "$DB_NAME-shm")
        val journalFile = File(parent, "$DB_NAME-journal")
        // Delete the plaintext WAL/SHM/journal FIRST: they belong to the plaintext
        // file and would corrupt reads of the encrypted file left at the same path.
        deleteQuietly(walFile)
        deleteQuietly(shmFile)
        deleteQuietly(journalFile)

        if (!dbFile.delete()) {
            Log.e(TAG, "Could not remove plaintext DB before swap; discarding temp, plaintext untouched.")
            deleteQuietly(tempFile)
            return false
        }
        if (!tempFile.renameTo(dbFile)) {
            // Worst spot: plaintext removed but temp not in place. Try a copy.
            Log.e(TAG, "rename temp->db failed; attempting copy.")
            return try {
                tempFile.copyTo(dbFile, overwrite = true)
                deleteQuietly(tempFile)
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Copy of encrypted temp into place failed.", e)
                false
            }
        }
        Log.i(TAG, "Database migrated to SQLCipher (${plaintextCounts.values.sum()} rows across ${plaintextCounts.size} tables).")
        return true
    }

    /** Prefix of a database moved aside because no key we hold can open it. */
    internal const val QUARANTINE_PREFIX = "$DB_NAME.unreadable"

    /**
     * 🔴 __DB_KEY_SURVIVES_WIPE_2026_11_10__ — the way out for phones already stuck.
     *
     * "Delete account" erased the passphrase store while the encrypted file
     * stayed (fixed in [com.oshi.messenger.util.PrefsWipePolicy.KEEP_NAMES]).
     * The next start generated a NEW passphrase and handed it to Room, and the
     * first query threw `SQLiteNotADatabaseException` — before any screen, on
     * every launch, with no exit short of clearing app data (Play Vitals
     * vc118/124; reproduced on production 1.6.88). The old key is gone for good,
     * so the rows in that file are unrecoverable by ANY build; the only choice
     * left is between a dead app and a working one.
     *
     * So: when the file is not plaintext (the caller checked) AND our key is
     * refused with SQLCipher's specific "not a database" error, the file is
     * RENAMED aside (never deleted here) with its -wal/-shm/-journal, and Room
     * creates a fresh encrypted database. Anything else — I/O error, locked
     * file, a missing native library — leaves the file exactly where it is:
     * only the one error that proves "wrong key" moves it. One quarantined copy
     * is kept; an older one is replaced, so repeated incidents do not pile up.
     *
     * @return true if the file was moved aside.
     */
    internal fun quarantineIfKeyCannotOpen(dbFile: File, passphrase: String): Boolean {
        var db: SQLiteDatabase? = null
        val wrongKey = try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, passphrase, null,
                SQLiteDatabase.OPEN_READONLY, null,
            )
            db.rawQuery("SELECT count(*) FROM sqlite_master", arrayOf<String>()).use { it.moveToFirst() }
            false
        } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Key check on existing DB failed for another reason; leaving it in place.", e)
            false
        } finally {
            try {
                db?.close()
            } catch (_: Throwable) {
            }
        }
        if (!wrongKey) return false

        // 🔴 __DB_KEYSTORE_HICCUP_2026_11_11__ This used to delete the PREVIOUS
        // quarantine first. If the key is lost twice, the first copy is the one
        // that held the user's history and the second an empty database — so the
        // real data was the one thrown away. Copies are now pruned by [moveSet],
        // which always keeps the OLDEST.
        val moved = moveSet(dbFile, QUARANTINE_PREFIX)
        if (moved) {
            Log.e(TAG, "Encrypted DB unreadable with the stored key (key lost); moved aside, starting fresh.")
        } else {
            Log.e(TAG, "Encrypted DB unreadable with the stored key and could not be moved aside.")
        }
        return moved
    }

    /** Prefix of an encrypted database set aside because the Keystore refused at launch. */
    internal const val KEYLESS_PREFIX = "$DB_NAME.keyless"

    /** Prefix of an interim database merged back into a restored one. */
    internal const val INTERIM_PREFIX = "$DB_NAME.interim"

    /** Prefix of a database Android reported corrupt; kept, never deleted by us. */
    internal const val CORRUPT_PREFIX = "$DB_NAME.corrupt"

    private val SIDECARS = listOf("", "-wal", "-shm", "-journal")
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * True when [file] starts with the plaintext SQLite magic. Deterministic,
     * unlike [isPlaintextDatabase], which reports ANY open failure as "encrypted".
     */
    internal fun hasPlaintextHeader(file: File): Boolean = try {
        val head = ByteArray(SQLITE_MAGIC.size)
        val n = file.inputStream().use { it.read(head) }
        n == head.size && head.contentEquals(SQLITE_MAGIC)
    } catch (_: Throwable) {
        false
    }

    /**
     * Rename [dbFile] and its -wal/-shm/-journal to `<prefix>.<stamp><suffix>`.
     * The main file failing to move returns false (sidecars of a moved main file
     * that cannot move are deleted: read against a NEW file they would corrupt it).
     *
     * Keeps at most [keep] sets under [prefix], and always the OLDEST one: when a
     * failure repeats, the first set aside is the one that holds the history.
     */
    internal fun moveSet(dbFile: File, prefix: String, keep: Int = 3): Boolean {
        val parent = dbFile.parentFile ?: return false
        val stamp = System.currentTimeMillis()
        if (!File(parent, DB_NAME).renameTo(File(parent, "$prefix.$stamp"))) return false
        for (suffix in SIDECARS.drop(1)) {
            val f = File(parent, DB_NAME + suffix)
            if (f.exists() && !f.renameTo(File(parent, "$prefix.$stamp$suffix"))) deleteQuietly(f)
        }
        val stamps = setStamps(parent, prefix)
        if (stamps.size > keep) {
            // Oldest kept, newest kept, drop the ones in between.
            stamps.drop(1).dropLast(keep - 1).forEach { old -> deleteSet(parent, "$prefix.$old") }
        }
        return true
    }

    private fun setStamps(parent: File, prefix: String): List<Long> =
        parent.listFiles().orEmpty()
            .mapNotNull { f -> f.name.removePrefix("$prefix.").takeIf { f.name.startsWith("$prefix.") }?.toLongOrNull() }
            .sorted()

    private fun deleteSet(parent: File, base: String) {
        for (suffix in SIDECARS) deleteQuietly(File(parent, base + suffix))
    }

    private fun renameSet(parent: File, fromBase: String, toBase: String): Boolean {
        if (!File(parent, fromBase).renameTo(File(parent, toBase))) return false
        for (suffix in SIDECARS.drop(1)) {
            val f = File(parent, fromBase + suffix)
            if (f.exists() && !f.renameTo(File(parent, toBase + suffix))) deleteQuietly(f)
        }
        return true
    }

    private fun opensWith(file: File, passphrase: String): Boolean {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(file.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null)
            db.rawQuery("SELECT count(*) FROM sqlite_master", arrayOf<String>()).use { it.moveToFirst() }
            true
        } catch (_: Throwable) {
            false
        } finally {
            try { db?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 🔴 __DB_KEYSTORE_HICCUP_2026_11_11__ Put back the database an earlier
     * launch set aside because the Keystore refused, once the key opens it.
     *
     * The launch that set it aside ran on an empty interim database and may have
     * received messages (the server deletes what it delivered), so the interim
     * rows are merged in, never dropped. A set-aside file the key does not open is
     * left where it is — never deleted here.
     *
     * @return true if a database was restored.
     */
    internal fun restoreSetAside(dbFile: File, passphrase: String): Boolean {
        val parent = dbFile.parentFile ?: return false
        // The OLDEST holds the history; later ones can only be interim databases.
        val stamp = setStamps(parent, KEYLESS_PREFIX).firstOrNull() ?: return false
        val aside = File(parent, "$KEYLESS_PREFIX.$stamp")
        if (!opensWith(aside, passphrase)) {
            Log.e(TAG, "Set-aside DB does not open with the current key; left in place.")
            return false
        }
        val interimBase = "$INTERIM_PREFIX.$stamp"
        val hadInterim = dbFile.exists()
        if (hadInterim && !renameSet(parent, DB_NAME, interimBase)) return false
        if (!renameSet(parent, aside.name, DB_NAME)) {
            if (hadInterim) renameSet(parent, interimBase, DB_NAME)
            return false
        }
        Log.w(TAG, "Set-aside DB restored after a Keystore refusal.")
        if (hadInterim) {
            val interim = File(parent, interimBase)
            val merged = try {
                mergeInterim(dbFile, interim, passphrase)
            } catch (e: Throwable) {
                Log.e(TAG, "Interim merge failed; interim kept.", e)
                false
            }
            // The interim holds plaintext messages: delete it once merged.
            if (merged) deleteSet(parent, interimBase)
        }
        return true
    }

    /**
     * Copy every row of [interim] that [dbFile] does not already have
     * (`INSERT OR IGNORE`, by primary key). Columns are named, not `*`: a
     * database that grew through ALTER TABLE migrations and one Room created
     * fresh at the same version can order the same columns differently.
     */
    internal fun mergeInterim(dbFile: File, interim: File, passphrase: String): Boolean {
        val interimKey = if (hasPlaintextHeader(interim)) "" else passphrase
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READWRITE, null)
        try {
            val mainVersion = db.version
            db.execSQL("ATTACH DATABASE ${sqlLiteral(interim.absolutePath)} AS interim KEY ${sqlLiteral(interimKey)}")
            try {
                val interimVersion = db.rawQuery("PRAGMA interim.user_version", arrayOf<String>()).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
                if (interimVersion != mainVersion) {
                    Log.e(TAG, "Interim schema v$interimVersion != v$mainVersion; not merged, kept.")
                    return false
                }
                val tables = mutableListOf<String>()
                db.rawQuery(
                    "SELECT name FROM interim.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                        "AND name NOT IN ('room_master_table','android_metadata')",
                    arrayOf<String>(),
                ).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
                var copied = 0
                db.beginTransaction()
                try {
                    for (t in tables) {
                        val q = "\"" + t.replace("\"", "\"\"") + "\""
                        val mainCols = columnsOf(db, "main", q)
                        val cols = columnsOf(db, "interim", q).filter { it in mainCols }
                        if (cols.isEmpty()) continue
                        val list = cols.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
                        db.execSQL("INSERT OR IGNORE INTO main.$q ($list) SELECT $list FROM interim.$q")
                        copied++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                Log.w(TAG, "Interim database merged ($copied tables).")
                return true
            } finally {
                try { db.execSQL("DETACH DATABASE interim") } catch (_: Throwable) {}
            }
        } finally {
            db.close()
        }
    }

    private fun columnsOf(db: SQLiteDatabase, schema: String, quotedTable: String): List<String> {
        val cols = mutableListOf<String>()
        db.rawQuery("PRAGMA $schema.table_info($quotedTable)", arrayOf<String>()).use { c ->
            val i = c.getColumnIndex("name")
            while (c.moveToNext()) cols.add(c.getString(i))
        }
        return cols
    }

    /**
     * 🔴 __DB_KEYSTORE_HICCUP_2026_11_11__ What Room's helpers do on a corruption
     * report is DELETE the database file (`SupportSQLiteOpenHelper.Callback
     * .onCorruption`). For a messenger that is the whole history, gone without a
     * word. Every helper we build goes through this wrapper, which moves the file
     * aside instead; the open then continues on a fresh file exactly as before.
     */
    class KeepOnCorruptionFactory(
        private val delegate: SupportSQLiteOpenHelper.Factory,
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val inner = configuration.callback
            val wrapped = object : SupportSQLiteOpenHelper.Callback(inner.version) {
                override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) = inner.onConfigure(db)
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = inner.onCreate(db)
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    inner.onUpgrade(db, oldVersion, newVersion)
                override fun onDowngrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    inner.onDowngrade(db, oldVersion, newVersion)
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = inner.onOpen(db)
                override fun onCorruption(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    val path = db.path
                    Log.e(TAG, "Corruption reported on $path — moving it aside, NOT deleting it.")
                    try { db.close() } catch (_: Throwable) {}
                    if (path == null || path == ":memory:") return
                    val f = File(path)
                    if (f.name == DB_NAME && f.exists()) moveSet(f, CORRUPT_PREFIX, keep = 2)
                }
            }
            return delegate.create(
                SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name)
                    .callback(wrapped)
                    .noBackupDirectory(configuration.useNoBackupDirectory)
                    .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                    .build()
            )
        }
    }

    /** name -> row count for every user + Room-internal table (excludes sqlite_% internals). */
    private fun tableRowCounts(db: SQLiteDatabase): Map<String, Long> {
        val names = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            arrayOf<String>(),
        ).use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        val counts = linkedMapOf<String, Long>()
        for (name in names.sorted()) {
            // Table names come from sqlite_master (not user input); quote defensively.
            db.rawQuery("SELECT count(*) FROM \"${name.replace("\"", "\"\"")}\"", arrayOf<String>()).use { c ->
                counts[name] = if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
        return counts
    }

    /**
     * Un littéral de chaîne SQLite: entre apostrophes, chaque apostrophe
     * interne doublée. C'est la seule échappée dont SQLite ait besoin.
     */
    private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun deleteQuietly(f: File) {
        try {
            if (f.exists()) f.delete()
        } catch (_: Throwable) {
        }
    }
}

/**
 * MIGRATION_TEST_PLAN (on-device / instrumented — SQLCipher native cannot run
 * under the JVM Robolectric setup on the build host):
 *
 *  1. Install the LAST plaintext build; use the app so `oshi_database` holds
 *     real rows (send/receive messages, create chats). Note counts via
 *     `adb shell run-as com.oshi.messenger sqlite3 databases/oshi_database
 *     "SELECT count(*) FROM messages;"` (debug build).
 *  2. Build this version with ENABLED=true; install as an UPGRADE (do not
 *     uninstall — that wipes the KeyStore and the DB).
 *  3. Launch. Confirm the app opens, chats + messages are all present, counts
 *     match step 1.
 *  4. Verify at rest: `adb shell run-as com.oshi.messenger sqlite3
 *     databases/oshi_database "SELECT count(*) FROM messages;"` now FAILS
 *     ("file is not a database") — the file is encrypted. `head -c 16` of the
 *     file is NOT the ASCII "SQLite format 3\0" magic.
 *  5. Kill/relaunch: opens cleanly (already-encrypted path, no re-migration).
 *  6. Fresh install (no prior DB): app works; DB is encrypted from creation.
 *  7. Fault injection: interrupt between phases (kill mid-migration); relaunch
 *     must still open the plaintext DB with all data (temp discarded on next run).
 */
