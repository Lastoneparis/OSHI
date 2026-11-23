package com.oshi.messenger.service.diag

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * __CALL_LOG_AT_REST_2026_09_23__ The call diagnostics log, sealed at rest — the Kotlin
 * twin of iOS `OSHI/CallFileLogger.swift`.
 *
 * PURE JVM ON PURPOSE (no `android.*` import): the desktop build compiles this very file
 * out of the Android tree (OSHI-Desktop/build.gradle.kts, shared sources), so the phone
 * and the desktop cannot drift on the file format or on the export redaction. The
 * platform seams — where the key lives, where the file lives, when to flush — are in
 * `CallDiag` (Android) and `com.oshi.desktop.diag.DesktopCallLog` (desktop).
 *
 * File format "OSHILOG1", byte-identical to iOS so one tool reads all three:
 *
 *     "OSHILOG1" (8) | record | record | …
 *     record = u32 BE length L | AES-256-GCM combined (nonce 12 ‖ ct ‖ tag 16)
 *
 * The 8-byte header is the AAD of every record. One record per FLUSH, not per line:
 * lines are buffered and sealed every 250 ms or 4 KB, so a DIAG line from the audio
 * thread costs a StringBuilder append under a short lock — never a key lookup, an AES
 * pass or a syscall on the caller's thread. A torn last record (crash mid-write) is
 * skipped by the reader; a failed write is truncated back to the last record boundary.
 */
class CallFileLogger(
    directory: File,
    private val exportDirectory: File,
    /** 32 raw key bytes. Throws when the key is not reachable right now (lines are kept). */
    private val keyProvider: () -> ByteArray,
    /** Called once when a log file is created (desktop: owner-only permissions). */
    private val onFileCreated: (File) -> Unit = {},
) {
    companion object {
        const val CURRENT_LOG_NAME = "call_debug.log"
        const val PREVIOUS_LOG_NAME = "call_debug.prev.log"
        const val EXPORT_FILE_NAME = "oshi-call-diagnostics.txt"

        const val FLUSH_INTERVAL_MS = 250L
        const val FLUSH_THRESHOLD_CHARS = 4096
        /** Key unreachable for a while: keep lines (never in clear) up to this bound, then drop. */
        const val MAX_PENDING_CHARS = 1 shl 20

        /** Same shape as iOS `ISO8601DateFormatter` + fractional seconds: `2026-09-20T10:00:01.000Z`. */
        private val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

        fun stamp(instant: Instant = Instant.now()): String = STAMP.format(instant)
    }

    private val fileURL = File(directory, CURRENT_LOG_NAME)
    private val prevURL = File(directory, PREVIOUS_LOG_NAME)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "callFileLogger").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    // Guarded by `lock`: log() runs on audio, network and main threads and must only
    // ever pay for an append.
    private val lock = Any()
    private var pending = StringBuilder()
    private var flushScheduled = false
    private var urgentFlushScheduled = false

    // Confined to `executor`.
    private var channel: FileChannel? = null
    private var committedSize = 0L

    init {
        // Rotation, not truncation (iOS 2026-07-26): the run a relaunch interrupted is
        // exactly the one someone wants to read. Rename is format-agnostic and cheap, so it
        // stays synchronous; everything that needs the key is queued BEFORE the first line.
        directory.mkdirs()
        if (fileURL.exists()) {
            prevURL.delete()
            fileURL.renameTo(prevURL)
        }
        executor.execute {
            purgeExports()
            securePreviousRun()
        }
        enqueue("===== OSHI call log — run started ${stamp()} (previous run: $PREVIOUS_LOG_NAME) =====\n")
    }

    // ── Writing ────────────────────────────────────────────────────────────────

    fun log(message: String) {
        enqueue("[${stamp()}] $message\n")
    }

    /** For critical events (state changes, call end): sealed now, off the caller's thread. */
    fun logUrgent(message: String) {
        enqueue("[${stamp()}] $message\n", schedule = false)
        flush()
    }

    /** Seals whatever is buffered, without waiting. Call on call end / background. */
    fun flush() {
        runCatching { executor.execute { drain() } }
    }

    /** Seals whatever is buffered and waits for the write. Never from a UI or audio thread. */
    fun flushAndWait() {
        onQueue { drain() }
    }

    private fun enqueue(line: String, schedule: Boolean = true) {
        var tick = false
        var now = false
        synchronized(lock) {
            pending.append(line)
            if (schedule) {
                if (!flushScheduled) { flushScheduled = true; tick = true }
                if (pending.length >= FLUSH_THRESHOLD_CHARS && !urgentFlushScheduled) {
                    urgentFlushScheduled = true; now = true
                }
            }
        }
        runCatching {
            if (now) executor.execute { drain() }
            else if (tick) executor.schedule({ drain() }, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Seals the buffer as ONE record and appends it. Runs on `executor` only. */
    private fun drain() {
        val chunk: String
        synchronized(lock) {
            chunk = pending.toString()
            pending = StringBuilder()
            flushScheduled = false
            urgentFlushScheduled = false
        }
        if (chunk.isEmpty()) return
        try {
            append(CallLogRecords.seal(chunk.toByteArray(Charsets.UTF_8), keyProvider()))
        } catch (e: Exception) {
            // Key or file unreachable: keep the lines for the next flush — never fall
            // back to writing them in clear.
            synchronized(lock) {
                if (chunk.length + pending.length <= MAX_PENDING_CHARS) pending.insert(0, chunk)
            }
        }
    }

    private fun append(record: ByteArray) {
        if (channel == null) {
            if (!fileURL.exists()) {
                fileURL.writeBytes(CallLogRecords.MAGIC)
                onFileCreated(fileURL)
            }
            val ch = RandomAccessFile(fileURL, "rw").channel
            committedSize = ch.size()
            ch.position(committedSize)
            channel = ch
        }
        val ch = channel!!
        try {
            val buf = java.nio.ByteBuffer.wrap(record)
            while (buf.hasRemaining()) ch.write(buf)
            committedSize += record.size
        } catch (e: Exception) {
            // A partial write would shift every later record off its boundary: cut back
            // to the last whole record before anything else is appended.
            runCatching { ch.truncate(committedSize) }
            runCatching { ch.close() }
            channel = null
            throw e
        }
    }

    // ── Reading & export ───────────────────────────────────────────────────────

    /** Cheap: a stat per file, no decryption. */
    val hasDiagnostics: Boolean
        get() = fileURL.exists() || prevURL.exists() || synchronized(lock) { pending.isNotEmpty() }

    /** Decrypted content of both runs, previous first. Never write the result to disk. */
    fun decryptedLogs(): List<Pair<String, String>> = onQueue {
        drain()
        listOf(prevURL, fileURL).mapNotNull { f -> readPlaintext(f)?.let { f.name to it } }
    }

    /** The redacted export as text, decrypted from both runs (previous first). */
    fun redactedExportText(): String? = CallLogRedaction.redact(decryptedLogs())

    /**
     * Decrypts, redacts (the SAME allow-list as iOS) and writes the only plaintext this
     * logger ever puts on disk, in [exportDirectory] — which the next launch, or the next
     * export, deletes. Blocks on the logger thread and decrypts the whole log: call it OFF
     * the main thread.
     */
    fun exportRedactedCallLog(): File? {
        val text = redactedExportText() ?: return null
        exportDirectory.deleteRecursively()
        if (!exportDirectory.mkdirs() && !exportDirectory.isDirectory) return null
        val dest = File(exportDirectory, EXPORT_FILE_NAME)
        return runCatching { dest.writeText(text, Charsets.UTF_8); dest }.getOrNull()
    }

    /**
     * Stop the logger thread after draining, and RELEASE THE FILE.
     *
     * __CLOSE_RELEASES_THE_HANDLE_2026_10_30__ This drained the queue and shut the executor
     * down but left `channel` open, so the process kept a handle on `call_debug.log` after
     * the logger said it had stopped. `deleteLog` has always closed it; `close` never did.
     *
     * On POSIX that is invisible: a file may be renamed or unlinked while somebody holds it
     * open, so the next run's rotation (`fileURL.renameTo(prevURL)` in `init`) worked anyway
     * and nothing on macOS, Linux or Android ever complained. On Windows a held file cannot
     * be renamed — `renameTo` just returns false — so the previous run was silently never
     * kept, and `call_debug.prev.log` never appeared. Found on `windows-latest`, where
     * `DesktopCallLogTest` asked for the previous run and got NoSuchElementException.
     *
     * The channel is closed ON THE WRITER THREAD and before `shutdown()`, so it cannot race
     * a write that is still queued behind the flush.
     */
    fun close() {
        runCatching { flushAndWait() }
        runCatching { onQueue { channel?.close(); channel = null } }
        executor.shutdown()
    }

    private fun <T> onQueue(block: () -> T): T = executor.submit(Callable { block() }).get()

    /**
     * Decrypted text of one log file, or null. A file NO record of opens was written under
     * a key this device no longer has: it can never be read again, so it is dropped
     * silently and logging starts fresh. Anything that is not OSHILOG1 was not written by
     * us (these platforms never wrote a plaintext call log) and is dropped too.
     */
    private fun readPlaintext(f: File): String? {
        if (!f.isFile) return null
        val data = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (!CallLogRecords.hasMagic(data)) {
            deleteLog(f)
            return null
        }
        val key = runCatching { keyProvider() }.getOrNull() ?: return null
        val decoded = CallLogRecords.decode(data, key)
        if (decoded.isUnreadable) {
            deleteLog(f)
            return null
        }
        return String(decoded.plaintext, Charsets.UTF_8)
    }

    private fun deleteLog(f: File) {
        if (f == fileURL) {
            runCatching { channel?.close() }
            channel = null
        }
        f.delete()
    }

    /** Key-loss cleanup of the previous run (a locked key store gives no verdict). */
    private fun securePreviousRun() {
        if (!prevURL.isFile) return
        val data = runCatching { prevURL.readBytes() }.getOrNull() ?: return
        if (!CallLogRecords.hasMagic(data)) { prevURL.delete(); return }
        val key = runCatching { keyProvider() }.getOrNull() ?: return
        if (CallLogRecords.decode(data, key).isUnreadable) prevURL.delete()
    }

    /** The last export is only needed while the share target copies it; it dies next launch. */
    private fun purgeExports() {
        exportDirectory.deleteRecursively()
    }
}

/** "OSHILOG1" | (u32 BE length | AES-GCM combined)* — see the file header. */
object CallLogRecords {
    val MAGIC: ByteArray = "OSHILOG1".toByteArray(Charsets.US_ASCII)
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    /** nonce + tag: a record can never be shorter than this. */
    const val MIN_RECORD_LENGTH = NONCE_BYTES + TAG_BITS / 8
    /** Far above any flush; a larger prefix is garbage. */
    const val MAX_RECORD_LENGTH = 16 shl 20

    private val random = SecureRandom()

    class Decoded {
        var plaintext: ByteArray = ByteArray(0)
        var opened = 0
        var rejected = 0
        /** Trailing bytes too short to be a whole record (crash mid-write). */
        var tornTail = false
        /** Records exist and none opens: wrong key, not a torn write. */
        val isUnreadable: Boolean get() = opened == 0 && rejected > 0
    }

    fun hasMagic(file: ByteArray): Boolean =
        file.size >= MAGIC.size && MAGIC.indices.all { file[it] == MAGIC[it] }

    /** Seals one record with a fresh random nonce. */
    fun seal(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return sealWithNonce(plaintext, key, nonce)
    }

    /** Deterministic variant — test vectors only; a reused nonce breaks GCM. */
    internal fun sealWithNonce(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32) { "call log key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        val ctAndTag = cipher.doFinal(plaintext)
        val length = NONCE_BYTES + ctAndTag.size
        val out = ByteArray(4 + length)
        out[0] = (length ushr 24).toByte(); out[1] = (length ushr 16).toByte()
        out[2] = (length ushr 8).toByte(); out[3] = length.toByte()
        System.arraycopy(nonce, 0, out, 4, NONCE_BYTES)
        System.arraycopy(ctAndTag, 0, out, 4 + NONCE_BYTES, ctAndTag.size)
        return out
    }

    /**
     * Opens every whole record in order. A record that fails authentication is skipped
     * (its length still locates the next); a torn tail ends the walk.
     */
    fun decode(file: ByteArray, key: ByteArray): Decoded {
        val result = Decoded()
        if (!hasMagic(file)) return result
        val out = java.io.ByteArrayOutputStream()
        val keySpec = SecretKeySpec(key, "AES")
        var i = MAGIC.size
        while (i < file.size) {
            if (file.size - i < 4) { result.tornTail = true; break }
            val length = (file[i].toInt() and 0xff shl 24) or (file[i + 1].toInt() and 0xff shl 16) or
                (file[i + 2].toInt() and 0xff shl 8) or (file[i + 3].toInt() and 0xff)
            val start = i + 4
            // `length` is read as a signed Int: a garbage prefix ≥ 2^31 is negative and
            // fails the lower bound, like Swift's upper bound would.
            if (length < MIN_RECORD_LENGTH || length > MAX_RECORD_LENGTH || file.size - start < length) {
                result.tornTail = true
                break
            }
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, file, start, NONCE_BYTES))
                cipher.updateAAD(MAGIC)
                out.write(cipher.doFinal(file, start + NONCE_BYTES, length - NONCE_BYTES))
                result.opened++
            } catch (e: Exception) {
                result.rejected++
            }
            i = start + length
        }
        result.plaintext = out.toByteArray()
        return result
    }
}

/**
 * The export's allow-list redaction — a line-for-line port of iOS
 * `CallFileLogger.exportAllowList` / `redact(logs:)`. The shared fixture
 * `call_log/redaction_*` (test resources) pins the output of BOTH implementations.
 *
 * An ALLOW-list, not a block-list: a new diagnostic is excluded until someone adds it
 * deliberately, which fails safe. `DIAG_MSG_RX_ENTRY` / `DIAG_MSG_DECRYPT_OK` carry
 * `preview=` with decrypted text and must NEVER be added here.
 */
object CallLogRedaction {
    val EXPORT_ALLOW_LIST: List<String> = listOf(
        "DIAG_PATH", "DIAG_JITTER", "DIAG_JITTER_JUMP", "DIAG_PLAYBACK_RATE",
        "DIAG_SNAP", "DIAG_CODEC", "DIAG_ENGINE", "DIAG_AUDIO_RX_PCM",
        "DIAG_AUDIO_TX_PCM", "DIAG_AUDIO_TRACK_CFG", "DIAG_RX_AUDIO_FIRST",
        "DIAG_TX_AUDIO_FIRST", "DIAG_TX_PRIMARY", "DIAG_ICE_TX", "DIAG_ICE_RX",
        "DIAG_NET_PATH", "DIAG_TRANSPORT_BADGE", "DIAG_FRAME_CONTRACT",
        "DIAG_ACCEPT", "DIAG_DECRYPT_FAIL", "🧭 P2P", "🔄 STATE CHANGE",
        // __EXPORT_P2P_STATE_2026_10_09__ Added on iOS the same day: the line
        // that says whether the direct path ever came up and why it did not.
        // `CallLogAtRestTest.allowListIsTheIosAllowList` compares the two lists
        // as ORDERED lists, so the position matters as much as the entry.
        "DIAG_P2P_STATE",
        "===== OSHI call log",
        "✅ CALL ACCEPTED", "📞 acceptCall:", "⚠️ acceptCall:",
        "⚠️ handleCallKitAnswer:", "📞 CallKit", "❌ CALL", "📞 CALL ENDED",
        "📞 AUTO-ACCEPT", "⚠️ startCall:", "DIAG_CALLSIG", "DIAG_PUSH_RECV",
        "DIAG_VIDEO", "📹",
        "DIAG_V2_", "DIAG_AUTH_FAIL",
        // __EXPORT_AUDIO_ENGINE_PARITY_2026_10_09__ These nine were already on
        // iOS at HEAD and Android had never followed, so the parity test above
        // was failing before today's work — the drift, not the test, was the
        // bug. Closed here because that test compares identical ordered lists,
        // so a partial port cannot be green. They are audio-engine lifecycle
        // lines: no addresses, no identifiers, no message content.
        "🎙️", "⏱️", "🔁 Audio engine watchdog", "🔧 CallKit audio",
        "✅ AUDIO ENGINE", "❌ AUDIO ENGINE", "✅ Audio engine",
        "❌ Audio engine", "⚠️ startAudioEngine",
    )

    const val HEADER = "OSHI call diagnostics — redacted export\n" +
        "Only call-path lines are included. Message content, contacts and\n" +
        "conversation data are deliberately NOT in this file.\n\n"

    fun redact(logs: List<Pair<String, String>>): String? {
        val out = StringBuilder(HEADER)
        var kept = 0
        for ((name, text) in logs) {
            out.append("\n===== ").append(name).append(" =====\n")
            for (line in swiftLines(text)) {
                // Timestamps are bracketed at the head, so match on what follows.
                val close = line.indexOf(']')
                val body = if (close < 0) "" else swiftTrimWhitespaces(line.substring(close + 1))
                val candidate = if (body.isEmpty()) line else body
                if (EXPORT_ALLOW_LIST.any { candidate.startsWith(it) }) {
                    out.append(candidate).append('\n')
                    kept++
                }
            }
        }
        return if (kept > 0) out.toString() else null
    }

    /**
     * Swift's `split(separator: "\n", omittingEmptySubsequences: true)` on a String works
     * on grapheme clusters, and "\r\n" is ONE cluster — so a CRLF is not a separator
     * there. Reproduced so both platforms cut a pasted CRLF log the same way.
     */
    internal fun swiftLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '\n' && (i == 0 || text[i - 1] != '\r')) {
                if (i > start) lines.add(text.substring(start, i))
                start = i + 1
            }
        }
        if (start < text.length) lines.add(text.substring(start))
        return lines
    }

    /** `CharacterSet.whitespaces` = Unicode Zs + TAB (NOT newlines, unlike Kotlin's trim()). */
    internal fun swiftTrimWhitespaces(s: String): String {
        fun ws(c: Char) = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
        var a = 0
        var b = s.length
        while (a < b && ws(s[a])) a++
        while (b > a && ws(s[b - 1])) b--
        return s.substring(a, b)
    }
}
