package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 A MIGRATION MUST DECLARE THE TYPE ROOM VALIDATES, NOT THE ONE THE AUTHOR ASSUMED.
 *
 * `MIGRATION_10_11` added `groups.groupWallpaperData` as **TEXT**, with a comment
 * explaining that a `ByteArray` goes through the base64 `@TypeConverter`. The premise
 * was false: Room's BUILT-IN ByteArray adapter wins over a user converter for entity
 * columns, so the entity's `ByteArray?` is a **BLOB** column — Room's own generated
 * `AppDatabase_Impl` emits BLOB in `createAllTables` and validates BLOB in `TableInfo`.
 *
 * `TableInfo.Column.equals` compares affinity. TEXT != BLOB, so every device whose
 * database passed through that edge failed `onValidateSchema` at open and threw
 * "Migration didn't properly handle groups(...)" — on EVERY launch, with no
 * `fallbackToDestructiveMigration` to catch it. Unopenable app; clear-data the only
 * exit; identity destroyed with it.
 *
 * It was invisible to every existing test because a fresh install takes
 * `createAllTables` (BLOB) and never runs a migration, and because the migration tests
 * assert on the migration's SOURCE TEXT, which cannot know the entity's type.
 *
 * This test closes that gap the only way source-level testing can: it reads the entity
 * declarations, reads the migrations, and insists the two agree.
 */
class MigrationColumnTypeTest {

    private val dbSource: String by lazy {
        File("src/main/java/com/oshi/messenger/data/local/AppDatabase.kt")
            .takeIf { it.exists() }?.readText()
            ?: File("app/src/main/java/com/oshi/messenger/data/local/AppDatabase.kt").readText()
    }

    private val groupSource: String by lazy {
        File("src/main/java/com/oshi/messenger/data/model/Group.kt")
            .takeIf { it.exists() }?.readText()
            ?: File("app/src/main/java/com/oshi/messenger/data/model/Group.kt").readText()
    }

    private val messageSource: String by lazy {
        File("src/main/java/com/oshi/messenger/data/model/Message.kt")
            .takeIf { it.exists() }?.readText()
            ?: File("app/src/main/java/com/oshi/messenger/data/model/Message.kt").readText()
    }

    /** Every `addColumnIfMissing(db, "<table>", "<column>", "<TYPE>")` in the file. */
    private fun declaredColumns(): List<Triple<String, String, String>> =
        Regex("""addColumnIfMissing\(\s*db\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"""")
            .findAll(dbSource)
            .map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toList()

    /** Property names declared `ByteArray?` or `ByteArray` in the entity sources. */
    private fun byteArrayFields(): Set<String> =
        (Regex("""val\s+(\w+)\s*:\s*ByteArray\??""").findAll(groupSource) +
            Regex("""val\s+(\w+)\s*:\s*ByteArray\??""").findAll(messageSource))
            .map { it.groupValues[1] }.toSet()

    @Test
    fun `the regression itself — groupWallpaperData is added as BLOB`() {
        val col = declaredColumns().firstOrNull { it.second == "groupWallpaperData" }
        assertTrue("groupWallpaperData is no longer added by any migration", col != null)
        assertEquals(
            "the entity declares ByteArray?, so Room validates BLOB; TEXT makes the app unopenable",
            "BLOB", col!!.third
        )
    }

    @Test
    fun `no migration adds a ByteArray column as TEXT`() {
        val bytes = byteArrayFields()
        assertTrue("the ByteArray field scan found nothing — the probe itself is broken", bytes.isNotEmpty())
        val wrong = declaredColumns().filter { (_, col, type) ->
            col in bytes && type.uppercase() != "BLOB"
        }
        assertTrue(
            "these columns back a ByteArray entity field but are declared non-BLOB, which " +
                "fails Room's schema validation at open:\n" +
                wrong.joinToString("\n") { "  ${it.first}.${it.second} declared ${it.third}" },
            wrong.isEmpty()
        )
    }

    @Test
    fun `a String field is still allowed to be TEXT — the rule is not just 'everything is BLOB'`() {
        // wallpaperImage is `String?` and correctly TEXT. If this ever flips, the test
        // above has become a rubber stamp rather than a check.
        val col = declaredColumns().firstOrNull { it.second == "wallpaperImage" }
        if (col != null) assertEquals("TEXT", col.third)
    }

    @Test
    fun `the declared database version matches the highest migration edge`() {
        val version = Regex("""version\s*=\s*(\d+)""").find(dbSource)!!.groupValues[1].toInt()
        val highest = Regex("""Migration\((\d+),\s*(\d+)\)""").findAll(dbSource)
            .map { it.groupValues[2].toInt() }.maxOrNull() ?: 0
        assertEquals(
            "a version above the highest edge means Room finds no path and throws at open",
            version, highest
        )
    }

    @Test
    fun `the migration chain is contiguous from 1 to the declared version`() {
        val edges = Regex("""Migration\((\d+),\s*(\d+)\)""").findAll(dbSource)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toSet()
        val version = Regex("""version\s*=\s*(\d+)""").find(dbSource)!!.groupValues[1].toInt()
        val missing = (1 until version).filter { (it to it + 1) !in edges }
        assertTrue("Room needs a CONTIGUOUS chain; missing edges: $missing", missing.isEmpty())
    }
}
