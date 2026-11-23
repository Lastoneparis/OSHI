package com.oshi.messenger.service.media

import java.io.File
import java.io.RandomAccessFile

/**
 * __CHAT_VIDEO_BITRATE_2026_10_09__ Moves an MP4's `moov` atom to the front of the file
 * — "faststart", the Android equivalent of iOS's
 * `AVAssetWriter.shouldOptimizeForNetworkUse`.
 *
 * ## Why this file has to exist
 *
 * `moov` is the index: track tables, sample sizes, chunk offsets. A decoder cannot
 * produce a single frame without it. `MediaMuxer` writes it LAST, because it only knows
 * the final sample tables once the last sample is written — so a receiver streaming an
 * OSHI video has to hold the entire file before the first frame can be decoded. That is
 * half of "it takes a while when I tap the video", and unlike the bitrate it is not a
 * tuning question: it is where four bytes sit in the file.
 *
 * `AVAssetWriter` has a flag for this. `MediaMuxer` has nothing, at any API level, so
 * the relocation is done here, after the encode.
 *
 * ## What makes it non-trivial
 *
 * Every chunk offset in `stco` / `co64` is an **absolute file offset**. Moving `moov`
 * from the end to the front pushes `mdat` — and therefore every sample in it — forward
 * by exactly `moov`'s own size, so each of those offsets has to be shifted by the same
 * delta. Miss one and the file is structurally valid and silently unplayable, which is
 * why [patchChunkOffsets] is pure, returns the number of entries it touched, and is
 * pinned by a unit test rather than trusted.
 *
 * ## Failure posture
 *
 * Every failure is a no-op: the original file is left exactly as it was and the send
 * proceeds with it. A video that starts a second late is a cosmetic loss; a video the
 * peer cannot open is not. The rewrite is therefore built in a sibling temp file,
 * re-parsed, and only then swapped in.
 *
 * No `android.media` types on purpose: all of this is byte arithmetic, and byte
 * arithmetic belongs where a JVM test can reach it.
 */
object Mp4FastStart {

    /** One top-level box: where it starts, how long it is, what it is. */
    data class Box(val type: String, val offset: Long, val size: Long) {
        val end: Long get() = offset + size
    }

    private const val HEADER_BYTES = 8L
    private const val COPY_BUFFER = 1 shl 20   // 1 MiB

    /** Boxes we recurse into when hunting for sample tables. */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    /**
     * Rewrite [file] with its `moov` atom in front of its media data.
     *
     * @return true when the file was rewritten; false when it was left untouched —
     *   which covers "already faststart", "not an MP4 we recognise", and every error.
     */
    fun relocateMoovInPlace(file: File): Boolean {
        val temp = File(file.parentFile, "${file.name}.fs")
        return try {
            val ok = relocateMoov(file, temp)
            if (!ok) {
                temp.delete()
                return false
            }
            // Re-parse what we just wrote. A rewrite that does not come back as a
            // faststart MP4 of the same length is not one we are going to ship.
            if (!isFastStart(temp) || temp.length() != file.length()) {
                temp.delete()
                return false
            }
            if (!file.delete()) {
                temp.delete()
                return false
            }
            temp.renameTo(file)
        } catch (_: Throwable) {
            try { temp.delete() } catch (_: Throwable) {}
            false
        }
    }

    /** True when [file]'s `moov` already precedes its `mdat` — nothing to do. */
    fun isFastStart(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val boxes = topLevelBoxes(raf) ?: return false
            val moov = boxes.indexOfFirst { it.type == "moov" }
            val mdat = boxes.indexOfFirst { it.type == "mdat" }
            return moov >= 0 && mdat >= 0 && moov < mdat
        }
    }

    /**
     * The actual rewrite: `ftyp`, then the patched `moov`, then everything else in its
     * original order. [output] must not be [input].
     */
    fun relocateMoov(input: File, output: File): Boolean {
        RandomAccessFile(input, "r").use { raf ->
            val boxes = topLevelBoxes(raf) ?: return false
            val moov = boxes.firstOrNull { it.type == "moov" } ?: return false
            val mdat = boxes.firstOrNull { it.type == "mdat" } ?: return false
            // Already in front: leave it alone rather than rewrite it identically.
            if (moov.offset < mdat.offset) return false
            // The uniform `+moov.size` delta is only true for media that sits BETWEEN
            // the insertion point and `moov`. A second `mdat` after `moov` would not
            // move at all, and patching its offsets would break the file — so refuse
            // rather than guess. `MediaMuxer` never produces that shape.
            if (boxes.any { it.type == "mdat" && it.offset > moov.offset }) return false
            // A `moov` that is not the usual tens-to-hundreds of KB is not something to
            // load into a ByteArray on a phone.
            if (moov.size <= HEADER_BYTES || moov.size > 32L * 1024 * 1024) return false

            val moovBytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(moovBytes)
            // Everything between `ftyp` and `moov` slides forward by exactly the size
            // of the block we are inserting in front of it.
            if (patchChunkOffsets(moovBytes, moov.size) < 0) return false

            output.outputStream().buffered().use { out ->
                val buffer = ByteArray(COPY_BUFFER)
                // `ftyp` must stay first — some demuxers refuse a file that does not
                // open with it.
                for (b in boxes) {
                    if (b.type != "ftyp") continue
                    copy(raf, b.offset, b.size, out, buffer)
                }
                out.write(moovBytes)
                for (b in boxes) {
                    if (b.type == "ftyp" || b.type == "moov") continue
                    copy(raf, b.offset, b.size, out, buffer)
                }
            }
            return true
        }
    }

    /**
     * Add [delta] to every chunk offset in every `stco` / `co64` inside [moov].
     *
     * Pure, in place, and the one piece of this file that can be silently wrong — hence
     * the return value.
     *
     * @return the number of offset entries patched, or -1 if [moov] is malformed enough
     *   that the result must not be trusted.
     */
    fun patchChunkOffsets(moov: ByteArray, delta: Long): Int {
        if (moov.size < 8) return -1
        if (readType(moov, 4) != "moov") return -1
        return patchRange(moov, 8, moov.size, delta)
    }

    /** Walks `[from, until)` of [buf] as a sequence of boxes. */
    private fun patchRange(buf: ByteArray, from: Int, until: Int, delta: Long): Int {
        var pos = from
        var patched = 0
        while (pos + 8 <= until) {
            val rawSize = readU32(buf, pos)
            val type = readType(buf, pos + 4)
            var headerSize = 8
            var size: Long = rawSize
            if (rawSize == 1L) {
                if (pos + 16 > until) return -1
                size = readU64(buf, pos + 8)
                headerSize = 16
            } else if (rawSize == 0L) {
                size = (until - pos).toLong()
            }
            if (size < headerSize || pos + size > until) return -1
            val bodyStart = pos + headerSize
            val bodyEnd = (pos + size).toInt()
            when {
                type == "stco" -> {
                    val n = patchStco(buf, bodyStart, bodyEnd, delta, wide = false)
                    if (n < 0) return -1
                    patched += n
                }
                type == "co64" -> {
                    val n = patchStco(buf, bodyStart, bodyEnd, delta, wide = true)
                    if (n < 0) return -1
                    patched += n
                }
                CONTAINERS.contains(type) -> {
                    val n = patchRange(buf, bodyStart, bodyEnd, delta)
                    if (n < 0) return -1
                    patched += n
                }
            }
            pos = bodyEnd
        }
        return patched
    }

    /**
     * `stco`/`co64` body: `version+flags` (4) · `entry_count` (4) · entries.
     *
     * A declared `entry_count` that does not match the box length is a corrupt table,
     * and a corrupt table is not something to half-patch.
     */
    private fun patchStco(buf: ByteArray, from: Int, until: Int, delta: Long, wide: Boolean): Int {
        if (from + 8 > until) return -1
        val count = readU32(buf, from + 4).toInt()
        if (count < 0) return -1
        val width = if (wide) 8 else 4
        if (from + 8 + count.toLong() * width > until.toLong()) return -1
        var p = from + 8
        for (i in 0 until count) {
            if (wide) {
                writeU64(buf, p, readU64(buf, p) + delta)
            } else {
                val shifted = readU32(buf, p) + delta
                // A 32-bit table that can no longer hold its own offsets would wrap
                // silently. `co64` is the only correct answer there, and rewriting the
                // table width is far beyond what a faststart pass should do.
                if (shifted > 0xFFFF_FFFFL) return -1
                writeU32(buf, p, shifted)
            }
            p += width
        }
        return count
    }

    // ── File-level box scan ───────────────────────────────────────────────────

    /**
     * The top-level box list, or null when the file does not parse as a flat sequence
     * of boxes covering exactly its own length.
     */
    fun topLevelBoxes(raf: RandomAccessFile): List<Box>? {
        val length = raf.length()
        if (length < 8) return null
        val boxes = ArrayList<Box>(8)
        var pos = 0L
        val header = ByteArray(16)
        while (pos + 8 <= length) {
            raf.seek(pos)
            raf.readFully(header, 0, 8)
            val rawSize = readU32(header, 0)
            val type = readType(header, 4)
            var size: Long = rawSize
            if (rawSize == 1L) {
                if (pos + 16 > length) return null
                raf.readFully(header, 8, 8)
                size = readU64(header, 8)
            } else if (rawSize == 0L) {
                size = length - pos            // "to end of file"
            }
            if (size < 8 || pos + size > length) return null
            boxes.add(Box(type, pos, size))
            pos += size
        }
        if (pos != length) return null
        return boxes
    }

    private fun copy(
        raf: RandomAccessFile,
        offset: Long,
        size: Long,
        out: java.io.OutputStream,
        buffer: ByteArray,
    ) {
        raf.seek(offset)
        var left = size
        while (left > 0) {
            val n = raf.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n <= 0) throw java.io.EOFException("short read at $offset")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    // ── Big-endian primitives ─────────────────────────────────────────────────

    private fun readType(b: ByteArray, at: Int): String =
        String(b, at, 4, Charsets.ISO_8859_1)

    private fun readU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun writeU32(b: ByteArray, at: Int, v: Long) {
        b[at] = ((v shr 24) and 0xFF).toByte()
        b[at + 1] = ((v shr 16) and 0xFF).toByte()
        b[at + 2] = ((v shr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }

    private fun writeU64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
    }
}
