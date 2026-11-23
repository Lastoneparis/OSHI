package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __SHARED_NICKNAME_2026_09_22__ Both phones read a profile update's capability flags as
 * "absent = cannot" and REMOVE the capability (iOS `PeerCapabilityStore.record(…: false)`,
 * Android `PeerCapabilities.record(optBoolean(…, false))`). So an Android build must never
 * emit a profile update that drops a flag it advertises elsewhere. Pinned on the source:
 * exactly ONE emitter builds `📸PROFILE_UPDATE📸`, and inside it both flags are written
 * unconditionally (not behind an `if`), and `lastSeen` is always written.
 *
 * `pqPublicKey` may legitimately be absent (keystore refusal); that is safe because both
 * receivers only RECORD it when present and never erase on absence.
 */
class ProfileCapabilityStabilityTest {

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, "app/$relative").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("not found: $relative")
    }

    private val repo by lazy {
        moduleFile("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt").readText()
    }

    private fun emitterBody(): String {
        val start = repo.indexOf("private suspend fun sendProfileUpdate(")
        assertTrue("sendProfileUpdate not found", start >= 0)
        val end = repo.indexOf("val content = \"📸PROFILE_UPDATE📸", start)
        assertTrue("payload assembly not found inside sendProfileUpdate", end > start)
        return repo.substring(start, end)
    }

    @Test
    fun `there is exactly one profile-update emitter`() {
        val builders = Regex("\"📸PROFILE_UPDATE📸\\$\\{").findAll(repo).count()
        assertEquals(1, builders)
        val tree = moduleFile("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
            .parentFile.parentFile.parentFile   // …/com/oshi/messenger
        val others = tree.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "MessageRepository.kt" }
            .filter { it.readText().contains("put(\"type\", \"profile_update\")") }.toList()
        assertTrue("another profile_update builder appeared: $others", others.isEmpty())
    }

    @Test
    fun `capability flags and lastSeen are written unconditionally`() {
        val body = emitterBody()
        for (line in listOf("put(\"groupEnvelopeV3\", true)", "put(\"ratchetV3\", true)", "\"lastSeen\",")) {
            val i = body.indexOf(line)
            assertTrue("missing: $line", i >= 0)
            // The nearest preceding line must not open a conditional that guards it.
            val prev = body.substring(0, i).trimEnd().lines().last().trim()
            assertTrue("`$line` looks conditional (after `$prev`)", !prev.startsWith("if (") && !prev.endsWith("{") || prev.endsWith("apply {") || prev.endsWith("put("))
        }
    }
}
