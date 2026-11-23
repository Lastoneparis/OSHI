package com.oshi.messenger

import com.oshi.messenger.service.diag.CallFileLogger
import com.oshi.messenger.service.diag.CallLogRecords
import com.oshi.messenger.service.diag.CallLogRedaction
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64

/**
 * __CALL_LOG_AT_REST_2026_09_23__ The Kotlin twin of iOS `OSHITests/CallLogAtRestTests.swift`:
 * the call log is sealed on disk (OSHILOG1) and the "Share call diagnostics" export is the
 * same redacted `.txt` iOS produces — byte for byte, pinned by fixtures in
 * `src/test/resources/call_log/` that the desktop tests read too.
 *
 * How the fixtures were made (re-run if the iOS side changes):
 *  - `redaction_expected.txt` is the output of the REAL `OSHI/CallFileLogger.swift`
 *    `redact(logs:)` on `redaction_prev.log` + `redaction_current.log`, compiled with
 *    `swiftc` and a two-line KeychainHelper stub.
 *  - `oshilog1_vectors.json` `fixedNonce` was sealed by an independent AES-GCM (Python
 *    `cryptography`) and opened by Swift `CallLogRecords.decode`; `swift` was sealed by
 *    Swift `CallLogRecords.seal` (CryptoKit).
 */
class CallLogAtRestTest {

    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var exportDir: File
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val loggers = mutableListOf<CallFileLogger>()

    @Before fun setUp() {
        root = Files.createTempDirectory("CallLogAtRestTest").toFile()
        dir = File(root, "files/call_logs")
        exportDir = File(root, "cache/call_diagnostics")
    }

    @After fun tearDown() {
        loggers.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    private fun logger(k: () -> ByteArray = { key }) =
        CallFileLogger(dir, exportDir, k).also { loggers += it }

    private val current get() = File(dir, CallFileLogger.CURRENT_LOG_NAME)
    private val previous get() = File(dir, CallFileLogger.PREVIOUS_LOG_NAME)

    private fun ByteArray.contains(needle: String): Boolean {
        val n = needle.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..size - n.size) {
            for (j in n.indices) if (this[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("call_log/$name")?.use { it.readBytes() }
            ?: error("missing test resource call_log/$name")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── File format ──────────────────────────────────────────────────────────

    @Test fun roundTripNeverWritesPlaintext() {
        val l = logger()
        for (i in 0 until 250) l.log("DIAG_JITTER | depth=$i | secret-marker")
        l.flushAndWait()
        for (i in 250 until 500) l.log("DIAG_JITTER | depth=$i | secret-marker")
        l.log("DIAG_MSG_DECRYPT_OK | preview=bonjour-en-clair")
        l.flushAndWait()

        val raw = current.readBytes()
        assertTrue(CallLogRecords.hasMagic(raw))
        assertFalse(raw.contains("secret-marker"))
        assertFalse(raw.contains("bonjour-en-clair"))
        assertFalse(raw.contains("OSHI call log"))

        val decoded = CallLogRecords.decode(raw, key)
        assertTrue("two flushes ⇒ at least two records", decoded.opened > 1)
        assertEquals(0, decoded.rejected)
        assertFalse(decoded.tornTail)
        val lines = String(decoded.plaintext, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        assertTrue(lines.first().startsWith("===== OSHI call log — run started "))
        assertEquals(502, lines.size)
        assertTrue(lines[1].endsWith("] DIAG_JITTER | depth=0 | secret-marker"))
        assertTrue(lines[500].endsWith("] DIAG_JITTER | depth=499 | secret-marker"))
        // Same timestamp shape as iOS ISO8601 + fractional seconds.
        assertTrue(lines[1], Regex("""^\[\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z] """).containsMatchIn(lines[1]))
    }

    @Test fun recordLayoutIsOshilog1() {
        val rec = CallLogRecords.seal("abc".toByteArray(), key)
        val len = (rec[0].toInt() and 0xff shl 24) or (rec[1].toInt() and 0xff shl 16) or
            (rec[2].toInt() and 0xff shl 8) or (rec[3].toInt() and 0xff)
        assertEquals("u32 BE length = nonce 12 + ct 3 + tag 16", 12 + 3 + 16, len)
        assertEquals(4 + len, rec.size)
        assertArrayEquals("OSHILOG1".toByteArray(), CallLogRecords.MAGIC)
    }

    @Test fun eachRecordHasAFreshNonce() {
        val a = CallLogRecords.seal("same".toByteArray(), key)
        val b = CallLogRecords.seal("same".toByteArray(), key)
        assertFalse(a.copyOfRange(4, 16).contentEquals(b.copyOfRange(4, 16)))
    }

    @Test fun fixedNonceVectorIsByteIdentical() {
        val v = JSONObject(String(resource("oshilog1_vectors.json"), Charsets.UTF_8))
        val k = hex(v.getString("keyHex"))
        val fixed = v.getJSONObject("fixedNonce")
        val records = fixed.getJSONArray("records")
        val built = java.io.ByteArrayOutputStream().apply { write(CallLogRecords.MAGIC) }
        for (i in 0 until records.length()) {
            val r = records.getJSONObject(i)
            built.write(CallLogRecords.sealWithNonce(r.getString("plaintext").toByteArray(), k, hex(r.getString("nonceHex"))))
        }
        assertArrayEquals(
            "Kotlin must produce the exact bytes iOS opens",
            Base64.getDecoder().decode(fixed.getString("fileBase64")), built.toByteArray(),
        )
    }

    @Test fun swiftSealedVectorOpens() {
        val v = JSONObject(String(resource("oshilog1_vectors.json"), Charsets.UTF_8))
        val swift = v.getJSONObject("swift")
        val d = CallLogRecords.decode(Base64.getDecoder().decode(swift.getString("fileBase64")), hex(v.getString("keyHex")))
        assertEquals(2, d.opened)
        assertEquals(0, d.rejected)
        assertEquals(swift.getString("plaintext"), String(d.plaintext, Charsets.UTF_8))
    }

    @Test fun tornLastRecordIsSkipped() {
        val l = logger()
        l.log("DIAG_PATH | first"); l.flushAndWait()
        l.log("DIAG_PATH | second"); l.flushAndWait()
        val partial = CallLogRecords.seal("DIAG_PATH | third\n".toByteArray(), key)
        current.appendBytes(partial.copyOf(partial.size / 2))

        val d = CallLogRecords.decode(current.readBytes(), key)
        assertTrue(d.tornTail)
        assertEquals(0, d.rejected)
        val text = String(d.plaintext, Charsets.UTF_8)
        assertTrue(text.contains("DIAG_PATH | first"))
        assertTrue(text.contains("DIAG_PATH | second"))
        assertFalse(text.contains("third"))

        // A 3-byte stub (not even a whole length prefix) is torn too.
        val stub = CallLogRecords.MAGIC + CallLogRecords.seal("ok\n".toByteArray(), key) + byteArrayOf(0, 0, 1)
        val d2 = CallLogRecords.decode(stub, key)
        assertEquals(1, d2.opened)
        assertTrue(d2.tornTail)
    }

    @Test fun failedAuthRecordIsSkippedNotFatal() {
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val file = CallLogRecords.MAGIC + CallLogRecords.seal("a\n".toByteArray(), key) +
            CallLogRecords.seal("forged\n".toByteArray(), other) + CallLogRecords.seal("b\n".toByteArray(), key)
        val d = CallLogRecords.decode(file, key)
        assertEquals(2, d.opened)
        assertEquals(1, d.rejected)
        assertEquals("a\nb\n", String(d.plaintext, Charsets.UTF_8))
    }

    // ── Logger lifecycle ─────────────────────────────────────────────────────

    @Test fun rotationKeepsPreviousRunReadable() {
        val first = logger()
        first.log("DIAG_PATH | run-one")
        first.close()

        val second = logger()
        second.log("DIAG_PATH | run-two")
        val logs = second.decryptedLogs()
        assertEquals(listOf(CallFileLogger.PREVIOUS_LOG_NAME, CallFileLogger.CURRENT_LOG_NAME), logs.map { it.first })
        assertTrue(logs[0].second.contains("run-one"))
        assertFalse(logs[0].second.contains("run-two"))
        assertTrue(logs[1].second.contains("run-two"))
        assertTrue(CallLogRecords.hasMagic(previous.readBytes()))
    }

    @Test fun lostKeyDropsOldLogsSilently() {
        val oldKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val before = logger { oldKey }
        before.log("DIAG_PATH | written-with-the-old-key")
        before.close()

        val after = logger()
        after.log("DIAG_PATH | fresh")
        after.flushAndWait()   // single thread: launch cleanup has run
        assertFalse("an unreadable previous run must be deleted", previous.exists())
        val export = after.redactedExportText()!!
        assertFalse(export.contains("written-with-the-old-key"))
        assertTrue(export.contains("DIAG_PATH | fresh"))
    }

    @Test fun keyUnavailableNeverWritesPlaintextAndRecovers() {
        val available = java.util.concurrent.atomic.AtomicBoolean(false)
        val l = logger { if (available.get()) key else throw IllegalStateException("keystore locked") }
        l.log("DIAG_PATH | before-key")
        l.flushAndWait()
        assertFalse("no key ⇒ nothing on disk, and certainly not in clear", current.exists())

        available.set(true)
        l.log("DIAG_PATH | after-key")
        l.flushAndWait()
        val text = String(CallLogRecords.decode(current.readBytes(), key).plaintext, Charsets.UTF_8)
        assertTrue("buffered lines are kept until the key is back", text.contains("before-key"))
        assertTrue(text.contains("after-key"))
    }

    @Test fun foreignPlaintextFileIsDroppedNotExported() {
        dir.mkdirs()
        current.writeText("[2026-09-20T10:00:00.000Z] DIAG_PATH | not-ours preview=secret\n")
        val l = logger()
        l.log("DIAG_PATH | mine")
        val logs = l.decryptedLogs()
        assertFalse(logs.any { it.second.contains("not-ours") })
        assertFalse(previous.exists())
    }

    @Test fun exportIsRedactedPlaintextAndPurgedNextLaunch() {
        val l = logger()
        l.log("DIAG_JITTER | depth=3")
        l.log("DIAG_MSG_RX_ENTRY | preview=never-exported")
        l.log("🔄 STATE CHANGE: RINGING → IN_CALL")
        val export = l.redactedExportText()!!
        assertEquals(CallLogRedaction.redact(l.decryptedLogs()), export)
        assertTrue(export.startsWith(CallLogRedaction.HEADER + "\n===== ${CallFileLogger.CURRENT_LOG_NAME} =====\n===== OSHI call log — run started "))
        assertTrue(export.endsWith("DIAG_JITTER | depth=3\n🔄 STATE CHANGE: RINGING → IN_CALL\n"))
        assertFalse(export.contains("never-exported"))

        val file = l.exportRedactedCallLog()
        assertNotNull(file)
        assertEquals("txt", file!!.extension)
        assertEquals(export, file.readText())
        l.close()
        logger().decryptedLogs()   // next launch
        assertFalse(file.exists())
    }

    @Test fun nothingToExportReturnsNull() {
        assertNull(CallLogRedaction.redact(listOf("call_debug.log" to "[t] DIAG_MSG_DECRYPT_OK | preview=x\n")))
    }

    @Test fun batchingDoesNotBlockTheCaller() {
        val l = logger()
        val start = System.nanoTime()
        for (i in 0 until 5_000) l.log("DIAG_JITTER | depth=$i")
        assertTrue("5 000 lines must cost the caller appends only", System.nanoTime() - start < 1_000_000_000L)
        l.flushAndWait()
        val d = CallLogRecords.decode(current.readBytes(), key)
        assertEquals(5_001, String(d.plaintext, Charsets.UTF_8).split('\n').count { it.isNotEmpty() })
        assertTrue("lines must be batched, not sealed one by one", d.opened < 5_000 / 10)
    }

    // ── Redaction parity with iOS ────────────────────────────────────────────

    @Test fun redactionMatchesIosFixtureByteForByte() {
        val prev = String(resource("redaction_prev.log"), Charsets.UTF_8)
        val cur = String(resource("redaction_current.log"), Charsets.UTF_8)
        val expected = String(resource("redaction_expected.txt"), Charsets.UTF_8)
        val out = CallLogRedaction.redact(listOf(CallFileLogger.PREVIOUS_LOG_NAME to prev, CallFileLogger.CURRENT_LOG_NAME to cur))
        assertEquals(expected, out)
        assertFalse(out!!.contains("never-exported"))
    }

    @Test fun sealedExportEqualsFixtureToo() {
        // The same inputs, through the encrypted file path, give the same bytes.
        dir.mkdirs()
        val prevPlain = resource("redaction_prev.log")
        previous.writeBytes(CallLogRecords.MAGIC + CallLogRecords.seal(prevPlain, key))
        val curPlain = resource("redaction_current.log")
        current.writeBytes(CallLogRecords.MAGIC + CallLogRecords.seal(curPlain, key))
        val logs = listOf(
            CallFileLogger.PREVIOUS_LOG_NAME to String(CallLogRecords.decode(previous.readBytes(), key).plaintext, Charsets.UTF_8),
            CallFileLogger.CURRENT_LOG_NAME to String(CallLogRecords.decode(current.readBytes(), key).plaintext, Charsets.UTF_8),
        )
        assertEquals(String(resource("redaction_expected.txt"), Charsets.UTF_8), CallLogRedaction.redact(logs))
    }

    @Test fun allowListIsTheIosAllowList() {
        val swift = repoFile("OSHI/CallFileLogger.swift").readText()
        val start = swift.indexOf("static let exportAllowList: [String] = [")
        assertTrue("iOS allow-list not found", start >= 0)
        val end = swift.indexOf("\n    ]", start)
        val body = swift.substring(start, end).lines().joinToString("\n") { line ->
            val c = line.indexOf("//"); if (c >= 0) line.substring(0, c) else line
        }
        val ios = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body.substringAfter("= [")).map { it.groupValues[1] }.toList()
        assertEquals("the export allow-list must be identical on every platform", ios, CallLogRedaction.EXPORT_ALLOW_LIST)
        assertTrue(swift.contains("OSHI call diagnostics — redacted export\\n"))
    }

    /**
     * Cross-check hook: `OSHI_CALLLOG_DUMP=/some/dir ./gradlew testDebugUnitTest --tests '*CallLogAtRestTest*'`
     * leaves a REAL logger file (and its key) there, for the Swift decoder to open.
     * No-op otherwise.
     */
    @Test fun dumpLoggerFileForSwiftCrossCheck() {
        val out = System.getenv("OSHI_CALLLOG_DUMP")?.let(::File) ?: return
        val l = logger()
        l.log("DIAG_PATH | written-by=kotlin-CallFileLogger")
        l.logUrgent("🔄 STATE CHANGE: RINGING → IN_CALL")
        l.close()
        out.mkdirs()
        current.copyTo(File(out, "kotlin_call_debug.log"), overwrite = true)
        File(out, "kotlin_key.hex").writeText(key.joinToString("") { "%02x".format(it) })
    }

    private fun repoFile(relative: String): File {
        var d: File? = File(".").absoluteFile
        while (d != null) {
            val f = File(d, relative)
            if (f.isFile) return f
            d = d.parentFile
        }
        fail("could not locate $relative above ${File(".").absolutePath}")
        error("unreachable")
    }
}
