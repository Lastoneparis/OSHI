package com.oshi.messenger

import com.oshi.messenger.service.NLPManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __NLP_DEBUG_FILE_LEAK_2026_09_23__ `NLPManager` appended every debug line — including
 * the first 50 characters the user typed and 80 of the "corrected" text — to
 * `getExternalFilesDir(null)/nlp_debug.txt` in clear, and to `Log.e` (kept by R8).
 *
 * NLPManager itself needs Hilt + ML Kit + a Context (the wall other tests here document),
 * so the "writes no file" rule is pinned against the SOURCE, and the start-up purge of
 * files older builds left is exercised for real on temp directories.
 */
class NlpNoPlaintextFileTest {

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
        error("unreachable")
    }

    private val source by lazy {
        moduleFile("src/main/java/com/oshi/messenger/service/NLPManager.kt").readText()
    }

    @Test fun nlpPathWritesNoFile() {
        // No file-writing API anywhere in the manager: the NLP path has nothing to persist.
        for (api in listOf("appendText(", "writeText(", "writeBytes(", "FileWriter", "FileOutputStream",
            "outputStream(", "openFileOutput", "PrintWriter")) {
            assertFalse("NLPManager must not write files ($api)", source.contains(api))
        }
        // The only mention of external storage is the purge of the legacy file.
        val ext = source.lines().count { l ->
            val t = l.trim()
            !t.startsWith("//") && !t.startsWith("*") && t.contains("getExternalFilesDir(")
        }
        assertEquals("getExternalFilesDir only in legacyDebugFileDirs", 1, ext)
        assertTrue(source.contains("fun legacyDebugFileDirs("))
    }

    @Test fun debugLogIsDebugOnlyLogcat() {
        val body = source.substringAfter("private fun debugLog(msg: String) {").substringBefore("\n    }")
        assertTrue(body, body.contains("BuildConfig.DEBUG"))
        assertFalse("Log.e survives R8 in release", body.contains("Log.e"))
        assertFalse(body.contains("File"))
    }

    @Test fun noTypedTextInLogs() {
        // Lengths and states only: `'${text.take(N)}'` in a log line is the leak shape.
        val leaks = source.lines().filter { l ->
            (l.contains("debugLog(") || l.contains("Log.")) &&
                Regex("""\$\{\w+\.take\(\d+\)\}""").containsMatchIn(l) &&
                !l.contains("senderKey")
        }
        assertEquals("log lines quoting user text: $leaks", emptyList<String>(), leaks)
    }

    @Test fun legacyDebugFilesArePurged() {
        val root = Files.createTempDirectory("nlpPurge").toFile()
        try {
            val ext = File(root, "Android/data/com.oshi.messenger/files").apply { mkdirs() }
            val files = File(root, "files").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            File(ext, NLPManager.LEGACY_DEBUG_FILE).writeText("12:00:00.000 📝 processCorrection: input='mon brouillon secret'\n")
            File(files, NLPManager.LEGACY_DEBUG_FILE).writeText("x")
            val keep = File(ext, "other.txt").apply { writeText("keep") }

            val removed = NLPManager.purgeLegacyDebugFiles(listOf(ext, null, files, cache, ext))
            assertEquals(2, removed)
            assertFalse(File(ext, NLPManager.LEGACY_DEBUG_FILE).exists())
            assertFalse(File(files, NLPManager.LEGACY_DEBUG_FILE).exists())
            assertTrue("only nlp_debug.txt is touched", keep.exists())
            assertEquals(0, NLPManager.purgeLegacyDebugFiles(listOf(ext, files, cache)))
        } finally {
            root.deleteRecursively()
        }
    }
}
