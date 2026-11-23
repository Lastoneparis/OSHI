package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v15 → v16 must be incapable of losing a row.
 *
 * `AppDatabase`'s history in this project has destroyed real user data twice: v10 → v11
 * and v11 → v12 wiped messages, contacts and call history, and eight edges were once
 * missing entirely behind `fallbackToDestructiveMigration()`, which turns "no path" into a
 * silent `DROP TABLE`. [RoomMigrationChainTest] now fails the build on a MISSING edge.
 * This test is the other half: it fails the build on an edge that is present but
 * DESTRUCTIVE.
 *
 * Two `ALTER TABLE … ADD COLUMN` on SQLite are a header-only change — they cannot touch
 * an existing row. The way a schema change loses data is the rebuild idiom
 * (`CREATE TABLE _new / INSERT INTO _new SELECT … / DROP TABLE / ALTER TABLE … RENAME`),
 * where a drifted column list silently drops rows or columns. So the assertion is
 * structural and blunt: inside this edge, nothing but `addColumnIfMissing` may appear.
 *
 * Reads the source, like [RoomMigrationChainTest], because `AppDatabase` cannot be loaded
 * in a JVM unit test (Room/Android runtime) and `@Database` has BINARY retention.
 *
 * The behavioural half of the evidence — a v15 row surviving the real statements in real
 * SQLite, byte for byte — is in `parity/verify/2026-08-25-groups-finish.md`.
 */
class GroupEditMigrationTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/oshi/messenger/data/local/AppDatabase.kt")
        assertTrue("AppDatabase.kt not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** The body of `val MIGRATION_15_16 = object : Migration(15, 16) { … }`, statements only. */
    private fun edgeBody(): String {
        val start = source.indexOf("val MIGRATION_15_16 = object : Migration(15, 16)")
        assertTrue("MIGRATION_15_16 is not declared", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return source.substring(open, i + 1) }
            }
            i++
        }
        error("unbalanced braces in MIGRATION_15_16")
    }

    @Test
    fun `the database version is at least 16`() {
        // AT LEAST, not exactly. This used to assert `== 16`, which made any legitimate
        // LATER migration fail a test about an EARLIER one — it broke the moment the
        // v16→v17 rescue edge was added. The property worth pinning is that the 15→16
        // edge shipped with its version bump, and `>=` says that without freezing the
        // schema forever. (The same trap was already fixed once in MediaUiRound3Test.)
        val version = Regex("""version\s*=\s*(\d+)""").find(source)?.groupValues?.get(1)?.toInt()
        assertNotNull("no `version = N` found in AppDatabase.kt", version)
        assertTrue("database version $version is below the 15->16 edge", version!! >= 16)
    }

    @Test
    fun `v15 to v16 adds exactly the two edit columns`() {
        val body = edgeBody()
        assertTrue(
            "editedContent must be added as a nullable TEXT column",
            body.contains("""addColumnIfMissing(db, "group_messages", "editedContent", "TEXT")""")
        )
        assertTrue(
            "editedAt must be added as a nullable INTEGER column",
            body.contains("""addColumnIfMissing(db, "group_messages", "editedAt", "INTEGER")""")
        )
        assertEquals(
            "the edge must add these two columns and nothing else",
            2,
            Regex("""addColumnIfMissing\(""").findAll(body).count()
        )
    }

    @Test
    fun `v15 to v16 contains no statement that could destroy a row`() {
        val body = edgeBody().uppercase()
        for (forbidden in listOf(
            "DROP TABLE", "DROP COLUMN", "DELETE FROM", "TRUNCATE",
            "CREATE TABLE", "INSERT INTO", "UPDATE ", "RENAME TO", "REBUILD"
        )) {
            assertTrue(
                "MIGRATION_15_16 must not contain `$forbidden` — the rebuild idiom is how " +
                    "v10→v11 and v11→v12 wiped user data",
                !body.contains(forbidden)
            )
        }
    }

    @Test
    fun `the new columns are NULLABLE, so existing rows need no backfill`() {
        val body = edgeBody().uppercase()
        // A `NOT NULL` column added without a DEFAULT is an SQLite error on a non-empty
        // table; with one it silently rewrites the meaning of every existing row.
        assertTrue("no NOT NULL on an added column", !body.contains("NOT NULL"))
        assertTrue("no DEFAULT on an added column", !body.contains("DEFAULT"))
    }

    @Test
    fun `the edge is probe-first, so re-running it is a no-op`() {
        // `addColumnIfMissing` reads `PRAGMA table_info` before altering. A raw
        // `ALTER TABLE … ADD COLUMN` on a device that already has the column throws
        // `duplicate column name`, and a throw inside a migration is a launch crash.
        assertTrue(
            "addColumnIfMissing must guard on hasColumn",
            Regex("""fun addColumnIfMissing[\s\S]{0,300}?if \(!hasColumn\(""").containsMatchIn(source)
        )
        assertTrue(
            "hasColumn must probe PRAGMA table_info",
            Regex("""fun hasColumn[\s\S]{0,300}?PRAGMA table_info""").containsMatchIn(source)
        )
    }
}
