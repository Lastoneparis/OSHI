package com.oshi.messenger.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Room upgrade chain, run against real SQLite on a real device.
 *
 * ## Why this could not be a unit test
 *
 * Every other test of this database asserts things *about* the migration SQL.
 * None of it has ever been executed: the JVM has no SQLite, and until this
 * source set existed the app had no instrumented tests at all. This app has
 * destroyed user data through a bad migration twice, and both times the SQL
 * looked right.
 *
 * ## Why not `MigrationTestHelper`
 *
 * `androidx.room:room-testing`'s helper builds the old database from the
 * **exported schema JSON**, and this database is declared `exportSchema = false`
 * (`AppDatabase.kt`), so there are no JSONs to build from. Rather than change
 * another team's annotation, the old database is built here the only other way
 * a v10 database can be built: take the schema the current code creates and undo
 * exactly what the migrations after v10 added.
 *
 * That is not circular. Room bakes the expected schema into the generated
 * `AppDatabase_Impl` and validates the *migrated* database against it on open —
 * so a migration that adds the wrong column type, forgets a column, or is
 * missing from `ALL_MIGRATIONS` makes `openRoom()` throw here, exactly as it
 * would on a user's phone.
 *
 * ## Keeping this test honest
 *
 * [ADDED_SINCE_V10] is the one hand-maintained fact. If a future migration adds
 * a column to `groups` or `group_messages` and it is not listed, the synthetic
 * "v10" database will already contain that column and its `ALTER TABLE … ADD
 * COLUMN` will fail loudly with the column's name — which is the failure you
 * want, not a silent pass.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName = "oshi_migration_under_test.db"

    private companion object {
        /**
         * The version a real user can plausibly still be sitting on, and the one
         * the brief asks about. v10 shipped; v6, v7 and v8 never did.
         */
        const val OLD_VERSION = 10

        /**
         * Every column added to these two tables by an edge above v10, with the
         * edge that adds it. Nothing else changes shape between v10 and now:
         * 11→12 is a data-only `UPDATE`, and no table is created or dropped.
         */
        val ADDED_SINCE_V10: Map<String, List<String>> = linkedMapOf(
            "groups" to listOf(
                "groupWallpaperData",       // 10 → 11
                "groupWallpaperUpdatedAt",  // 10 → 11
                "groupWallpaperUpdatedBy",  // 10 → 11
                "pinnedBy",                 // 12 → 13
                "avatar",                   // 12 → 13
            ),
            "group_messages" to listOf(
                "mediaFileName",            // 13 → 14
                "mediaDurationMs",          // 14 → 15
                "editedContent",            // 15 → 16
                "editedAt",                 // 15 → 16
            ),
        )
    }

    @Before
    fun removeAnyLeftoverDatabase() {
        context.deleteDatabase(dbName)
    }

    @After
    fun removeTheDatabaseWeBuilt() {
        context.deleteDatabase(dbName)
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * The one the brief asks for: open a v10 database with rows in it, migrate to
     * the current version, and assert the rows are still there.
     */
    @Test
    fun a_v10_database_migrates_to_current_and_keeps_every_row() {
        seedThenRewindToV10 { db ->
            db.messageDao().insert(
                Message(
                    id = "m1",
                    senderPublicKey = "peer",
                    recipientPublicKey = "me",
                    content = "written before the upgrade",
                    timestamp = 1_700_000_000_000L,
                    isFromMe = false,
                )
            )
            db.contactDao().insert(Contact(publicKey = "peer", alias = "Ada"))
            db.groupDao().insertGroup(
                Group(
                    id = "g1",
                    name = "Field team",
                    creatorPublicKey = "me",
                    memberPublicKeys = listOf("me", "peer"),
                    adminPublicKeys = listOf("me"),
                    isPublic = true,
                )
            )
            db.groupDao().insertGroupMessage(
                GroupMessage(
                    id = "gm1",
                    groupId = "g1",
                    senderPublicKey = "peer",
                    senderName = "Ada",
                    content = "see you at the ridge",
                    isFromMe = false,
                )
            )
        }

        // The database on disk is now genuinely a v10 one: older user_version,
        // and none of the columns the later edges add.
        assertEquals(OLD_VERSION, userVersion())
        forEachAddedColumn { table, column ->
            assertFalse(
                "`$table`.`$column` exists in the synthetic v$OLD_VERSION database; " +
                    "the rewind did not remove it",
                hasColumn(table, column)
            )
        }

        val db = openRoom()
        try {
            runBlocking {
                assertEquals(
                    "the message did not survive the upgrade",
                    "written before the upgrade",
                    db.messageDao().getMessageById("m1")?.content
                )
                assertEquals(
                    "the contact did not survive the upgrade",
                    "Ada",
                    db.contactDao().getContactByPublicKey("peer")?.alias
                )
                val group = db.groupDao().getGroupById("g1")
                assertEquals("the group did not survive the upgrade", "Field team", group?.name)
                assertEquals(listOf("me", "peer"), group?.memberPublicKeys)
                assertEquals(
                    "the group message did not survive the upgrade",
                    "see you at the ridge",
                    db.groupDao().getMessageById("gm1")?.content
                )
            }
        } finally {
            db.close()
        }

        // …and the chain really ran, rather than Room quietly recreating tables.
        forEachAddedColumn { table, column ->
            assertTrue(
                "`$table`.`$column` is missing after the upgrade — a migration edge " +
                    "did not run",
                hasColumn(table, column)
            )
        }
        assertNotEquals(
            "the database is still on v$OLD_VERSION; nothing migrated",
            OLD_VERSION.toLong(),
            userVersion().toLong()
        )
    }

    /**
     * v11 → v12 is the destructive-looking edge: it exists to remove blobs that
     * crash `SELECT *` with `SQLiteBlobTooBigException`. It must remove the
     * **blob** and keep the message — the previous answer to this crash was to
     * wipe the database.
     */
    @Test
    fun the_oversized_blob_edge_strips_the_blob_and_keeps_the_message() {
        // The converter stores a ByteArray as base64 TEXT, so 1.6 MB of bytes is
        // ~2.13 M characters — over the 2 000 000 the edge tests for.
        val oversized = ByteArray(1_600_000) { 0x41 }
        val small = ByteArray(64) { 0x42 }

        seedThenRewindToV10 { db ->
            db.messageDao().insert(
                Message(
                    id = "big",
                    senderPublicKey = "peer",
                    recipientPublicKey = "me",
                    content = "photo from the ridge",
                    isFromMe = false,
                    mediaType = MediaType.IMAGE,
                    mediaData = oversized,
                )
            )
            db.messageDao().insert(
                Message(
                    id = "small",
                    senderPublicKey = "peer",
                    recipientPublicKey = "me",
                    content = "thumbnail",
                    isFromMe = false,
                    mediaType = MediaType.IMAGE,
                    mediaData = small,
                )
            )
        }

        val db = openRoom()
        try {
            runBlocking {
                val big = db.messageDao().getMessageById("big")
                assertNotNull("the edge deleted the message instead of the blob", big)
                assertEquals("photo from the ridge", big!!.content)
                assertNull("the oversized blob was not stripped", big.mediaData)

                val kept = db.messageDao().getMessageById("small")
                assertNotNull("the edge deleted an unrelated message", kept)
                assertEquals(
                    "a blob under the cap was stripped too",
                    small.toList(),
                    kept!!.mediaData?.toList()
                )
            }
        } finally {
            db.close()
        }
    }

    /**
     * Room turns "no path from the installed version" into a crash at launch —
     * but only if every edge exists. A version bump without its edge is the
     * failure this catches, and it catches it without a device upgrade.
     */
    @Test
    fun the_migration_chain_reaches_the_current_version_without_a_gap() {
        val db = openRoom()
        val current = try {
            // The DAO call is what forces Room to actually open the file.
            runBlocking { db.messageDao().getLatestMessage() }
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }
        assertTrue("the database version went backwards", current >= OLD_VERSION)

        val edges = AppDatabase.ALL_MIGRATIONS.associateBy { it.startVersion }
        var v = 1
        while (v < current) {
            val edge = edges[v]
            assertNotNull("no migration edge starts at v$v (current is v$current)", edge)
            assertEquals(
                "the edge from v$v does not step forward by one version",
                v + 1,
                edge!!.endVersion
            )
            v = edge.endVersion
        }
        assertEquals(current, v)
    }

    // ── Machinery ────────────────────────────────────────────────────────────

    private fun openRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

    /**
     * Write [seed] through the real DAOs at the current schema, then rewind the
     * file on disk to what v10 looked like.
     *
     * Seeding through the DAOs rather than hand-written `INSERT`s is deliberate:
     * a hand-written insert can only test the columns whoever wrote the test
     * remembered.
     */
    private fun seedThenRewindToV10(seed: suspend (AppDatabase) -> Unit) {
        val db = openRoom()
        try {
            runBlocking { seed(db) }
        } finally {
            db.close()
        }
        rewindToV10()
    }

    private fun rewindToV10() {
        val file = context.getDatabasePath(dbName)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // The FTS4 content-sync triggers hang off `group_messages`; dropping
            // that table to rebuild it would take them with it anyway, and Room
            // recreates them when it opens the database.
            namesOf(db, "trigger").forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
            ADDED_SINCE_V10.forEach { (table, dropped) -> stripColumns(db, table, dropped) }
            db.execSQL("DELETE FROM room_master_table")
            db.version = OLD_VERSION
        }
    }

    /** Rebuild [table] without [dropped], preserving the rows and the indices. */
    private fun stripColumns(db: SQLiteDatabase, table: String, dropped: List<String>) {
        var create = requireNotNull(sqlOf(db, "table", table)) { "no CREATE for `$table`" }
        dropped.forEach { column ->
            val before = create
            create = removeColumn(create, column)
            assertNotEquals(
                "`$table` has no column definition for `$column` to remove. If a " +
                    "migration renamed or dropped it, update ADDED_SINCE_V10.",
                before,
                create
            )
        }
        val indices = indexSqlFor(db, table)
        indices.forEach { sql ->
            dropped.forEach { column ->
                assertFalse(
                    "index on `$table` references `$column`, which v$OLD_VERSION " +
                        "did not have: $sql",
                    sql.contains("`$column`")
                )
            }
        }

        val kept = columnsOf(db, table).filterNot { it in dropped }
        assertTrue("`$table` would be left with no columns", kept.isNotEmpty())
        val columnList = kept.joinToString(", ") { "`$it`" }
        val temp = "${table}_rewind_tmp"

        db.execSQL("ALTER TABLE `$table` RENAME TO `$temp`")
        db.execSQL(create)
        db.execSQL("INSERT INTO `$table` ($columnList) SELECT $columnList FROM `$temp`")
        db.execSQL("DROP TABLE `$temp`")
        indices.forEach { db.execSQL(it) }
    }

    /**
     * Delete one column definition out of a Room-generated `CREATE TABLE`.
     *
     * Room emits `` `name` TYPE[ NOT NULL][ DEFAULT x], `` and always closes with
     * `PRIMARY KEY(…)`, so every column definition is followed by a comma. A
     * column that does not match is reported by the caller rather than skipped.
     */
    private fun removeColumn(createSql: String, column: String): String {
        val pattern = Regex(
            "`" + Regex.escape(column) + "`\\s+[A-Za-z]+" +
                "(\\s+NOT\\s+NULL)?(\\s+DEFAULT\\s+[^,)]+)?\\s*,\\s*"
        )
        return pattern.replace(createSql, "")
    }

    private fun sqlOf(db: SQLiteDatabase, type: String, name: String): String? =
        db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?",
            arrayOf(type, name)
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun namesOf(db: SQLiteDatabase, type: String): List<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun indexSqlFor(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? " +
                "AND sql IS NOT NULL",
            arrayOf(table)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun columnsOf(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(idx)) }
        }

    private fun <T> withRawDatabase(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        ).use(block)

    private fun userVersion(): Int = withRawDatabase { it.version }

    private fun hasColumn(table: String, column: String): Boolean =
        withRawDatabase { columnsOf(it, table).contains(column) }

    private inline fun forEachAddedColumn(body: (String, String) -> Unit) {
        ADDED_SINCE_V10.forEach { (table, columns) -> columns.forEach { body(table, it) } }
    }
}
