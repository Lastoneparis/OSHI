package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The migration chain must be CONTIGUOUS from 1 to the declared database version.
 *
 * Room needs a path from whatever version is installed on the device to the current
 * one. A missing edge used to be invisible: `fallbackToDestructiveMigration()` turned
 * "no path" into a silent `DROP TABLE`, so every message, contact, group and call
 * record below the gap was destroyed on update while the identity (which lives in
 * EncryptedSharedPreferences) survived — so it did not even look like a reset.
 *
 * The builder now uses `fallbackToDestructiveMigrationOnDowngrade()`, which makes a
 * missing edge a launch crash instead. This test makes it a BUILD failure instead of
 * that, and is the reason the next `version =` bump cannot ship alone.
 *
 * Reads the source rather than the classes because `AppDatabase` cannot be loaded in
 * a JVM unit test (Room/Android runtime), and `@Database` has BINARY retention so its
 * `version` is not reflectable anyway.
 */
class RoomMigrationChainTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/oshi/messenger/data/local/AppDatabase.kt")
        assertTrue("AppDatabase.kt not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    private fun declaredVersion(): Int =
        Regex("""version\s*=\s*(\d+)""").find(source)?.groupValues?.get(1)?.toInt()
            ?: error("no `version = N` in AppDatabase.kt")

    /** Edges declared as `object : Migration(a, b)`. */
    private fun declaredEdges(): List<Pair<Int, Int>> =
        Regex("""Migration\((\d+)\s*,\s*(\d+)\)""").findAll(source)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()

    /** Edge names actually handed to `addMigrations` via `ALL_MIGRATIONS`. */
    private fun wiredEdgeNames(): Set<String> {
        val block = Regex("""val ALL_MIGRATIONS = arrayOf\(([^)]*)\)""")
            .find(source)?.groupValues?.get(1) ?: error("ALL_MIGRATIONS not found")
        return Regex("""MIGRATION_\d+_\d+""").findAll(block).map { it.value }.toSet()
    }

    @Test
    fun `every edge from 1 to the current version is declared`() {
        val version = declaredVersion()
        val edges = declaredEdges().toSet()
        val missing = (1 until version).filterNot { (it to it + 1) in edges }
        assertEquals("missing Room migration edge(s) — installs on those versions would be wiped", emptyList<Int>(), missing)
    }

    @Test
    fun `every declared edge is wired into the builder`() {
        val wired = wiredEdgeNames()
        val version = declaredVersion()
        val missing = (1 until version)
            .map { "MIGRATION_${it}_${it + 1}" }
            .filterNot { it in wired }
        assertEquals("edge(s) declared but never passed to addMigrations", emptyList<String>(), missing)
    }

    @Test
    fun `the builder never falls back to a destructive migration on upgrade`() {
        assertTrue(
            "AppDatabase must use fallbackToDestructiveMigrationOnDowngrade()",
            source.contains("fallbackToDestructiveMigrationOnDowngrade()")
        )
        assertTrue(
            "fallbackToDestructiveMigration() wipes user data on any missing upgrade edge",
            !Regex("""\.fallbackToDestructiveMigration\(\)""").containsMatchIn(source)
        )
    }
}
