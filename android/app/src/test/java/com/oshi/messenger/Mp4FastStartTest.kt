package com.oshi.messenger

import com.oshi.messenger.service.media.Mp4FastStart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * __CHAT_VIDEO_BITRATE_2026_10_09__ The faststart relocation.
 *
 * iOS gets this from one flag (`AVAssetWriter.shouldOptimizeForNetworkUse`). `MediaMuxer`
 * has no equivalent at any API level, so [Mp4FastStart] does it by hand — and the part
 * that can be silently wrong is the chunk-offset patch: every `stco` entry is an ABSOLUTE
 * file offset, so moving `moov` to the front has to shift all of them by exactly `moov`'s
 * size. Miss one and the file is structurally valid and unplayable, with no error
 * anywhere. That is what these tests are for.
 *
 * The fixtures are hand-built MP4 skeletons rather than a real encode: the box arithmetic
 * is the whole subject, and a real clip would make the expected numbers unreadable.
 */
class Mp4FastStartTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ── Fixture ──────────────────────────────────────────────────────────────

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = 8 + body.size
        return u32(size.toLong()) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun u32(v: Long) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun u64(v: Long) = ByteArray(8) { i -> ((v shr (8 * (7 - i))) and 0xFF).toByte() }

    /** `stco` with [offsets] as 32-bit absolute file offsets. */
    private fun stco(offsets: List<Long>): ByteArray =
        box("stco", u32(0) + u32(offsets.size.toLong()) + offsets.fold(ByteArray(0)) { a, o -> a + u32(o) })

    private fun co64(offsets: List<Long>): ByteArray =
        box("co64", u32(0) + u32(offsets.size.toLong()) + offsets.fold(ByteArray(0)) { a, o -> a + u64(o) })

    /** `moov > trak > mdia > minf > stbl > [tables]`, the real nesting. */
    private fun moov(vararg tables: ByteArray): ByteArray {
        val stbl = box("stbl", tables.fold(ByteArray(0)) { a, t -> a + t })
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        // An `mvhd` sibling, so the walk has to skip a leaf it does not care about.
        val mvhd = box("mvhd", ByteArray(100) { 7 })
        return box("moov", mvhd + trak)
    }

    /** `ftyp` · `mdat` · `moov` — exactly the order `MediaMuxer` writes. */
    private fun muxerShapedFile(mdatPayload: Int, tableBuilder: (List<Long>) -> ByteArray): File {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(8))
        val mdatStart = ftyp.size.toLong()
        // Two chunks: the first sample right after the mdat header, one mid-way.
        val offsets = listOf(mdatStart + 8, mdatStart + 8 + mdatPayload / 2)
        val mdat = box("mdat", ByteArray(mdatPayload) { (it % 251).toByte() })
        val f = folder.newFile("fixture_${mdatPayload}_${System.nanoTime()}.mp4")
        f.writeBytes(ftyp + mdat + moov(tableBuilder(offsets)))
        return f
    }

    // ── The patch itself ─────────────────────────────────────────────────────

    @Test
    fun `every stco entry is shifted by the delta, and the count is reported`() {
        val m = moov(stco(listOf(1_000L, 2_000L, 3_000L)))
        val patched = Mp4FastStart.patchChunkOffsets(m, 512)
        assertEquals("all three entries must be touched — one missed entry is an unplayable file", 3, patched)
        assertEquals(listOf(1_512L, 2_512L, 3_512L), readStco(m))
    }

    @Test
    fun `co64 is patched too, through the same nesting`() {
        val m = moov(co64(listOf(5_000_000_000L, 6_000_000_000L)))
        assertEquals(2, Mp4FastStart.patchChunkOffsets(m, 1_024))
        assertEquals(listOf(5_000_001_024L, 6_000_001_024L), readCo64(m))
    }

    @Test
    fun `a 32-bit table that would overflow is refused rather than wrapped`() {
        // Wrapping here would produce a structurally valid file whose samples point
        // at the wrong place. `co64` is the only correct answer and rewriting the
        // table width is not a faststart pass's job.
        val m = moov(stco(listOf(0xFFFF_FF00L)))
        assertEquals(-1, Mp4FastStart.patchChunkOffsets(m, 4_096))
    }

    @Test
    fun `a buffer that is not a moov is refused`() {
        assertEquals(-1, Mp4FastStart.patchChunkOffsets(box("mdat", ByteArray(16)), 8))
        assertEquals(-1, Mp4FastStart.patchChunkOffsets(ByteArray(4), 8))
    }

    @Test
    fun `an entry count that does not match the box length is refused`() {
        // Claims four entries, carries one. Half-patching a corrupt table is worse
        // than leaving the file alone.
        val lying = box("stco", u32(0) + u32(4) + u32(1_000))
        assertEquals(-1, Mp4FastStart.patchChunkOffsets(moov(lying), 16))
    }

    // ── The file rewrite ─────────────────────────────────────────────────────

    @Test
    fun `a muxer-shaped file is rewritten with moov in front and offsets corrected`() {
        val file = muxerShapedFile(4_096) { stco(it) }
        val before = file.readBytes()
        assertFalse("MediaMuxer writes moov last — that is the premise", Mp4FastStart.isFastStart(file))

        val moovSize = topLevel(file).first { it.type == "moov" }.size
        val originalOffsets = readStco(moovOf(before))

        assertTrue(Mp4FastStart.relocateMoovInPlace(file))

        assertTrue("the whole point", Mp4FastStart.isFastStart(file))
        assertEquals("a reorder must not change the length", before.size.toLong(), file.length())
        assertEquals(
            listOf("ftyp", "moov", "mdat"),
            topLevel(file).map { it.type }
        )
        // Every sample moved forward by exactly the block inserted in front of it.
        val after = file.readBytes()
        assertEquals(
            originalOffsets.map { it + moovSize },
            readStco(moovOf(after))
        )
        // …and each patched offset really does land on the same sample byte.
        for ((i, off) in readStco(moovOf(after)).withIndex()) {
            assertEquals(
                "sample $i does not match the byte it used to point at",
                before[originalOffsets[i].toInt()],
                after[off.toInt()]
            )
        }
        // The media payload itself is copied verbatim.
        assertArrayEquals(
            before.copyOfRange(originalOffsets[0].toInt(), before.size - moovSize.toInt()),
            after.copyOfRange(readStco(moovOf(after))[0].toInt(), after.size)
        )
    }

    @Test
    fun `a file that is already faststart is left exactly as it was`() {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(8))
        val m = moov(stco(listOf(1_000L)))
        val f = folder.newFile("already.mp4")
        f.writeBytes(ftyp + m + box("mdat", ByteArray(512)))
        val before = f.readBytes()

        assertTrue(Mp4FastStart.isFastStart(f))
        assertFalse("nothing to do must mean nothing done", Mp4FastStart.relocateMoovInPlace(f))
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun `a file that is not an MP4 is left alone instead of being mangled`() {
        val f = folder.newFile("notanmp4.bin")
        val junk = ByteArray(300) { (it * 13).toByte() }
        f.writeBytes(junk)
        assertFalse(Mp4FastStart.relocateMoovInPlace(f))
        assertArrayEquals("the original must survive every refusal", junk, f.readBytes())
    }

    @Test
    fun `a truncated file is refused rather than half-copied`() {
        // A box header that claims more bytes than the file holds: an interrupted
        // write, which is exactly the file we must not touch.
        val f = folder.newFile("truncated.mp4")
        f.writeBytes(u32(9_999) + "mdat".toByteArray() + ByteArray(32))
        val before = f.readBytes()
        assertFalse(Mp4FastStart.relocateMoovInPlace(f))
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun `a no-audio no-table moov still relocates`() {
        // Nothing to patch is not a failure: a video track with no chunk table in
        // the shape we understand still benefits from moov being in front.
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(8))
        val f = folder.newFile("notables.mp4")
        f.writeBytes(ftyp + box("mdat", ByteArray(256)) + moov())
        assertTrue(Mp4FastStart.relocateMoovInPlace(f))
        assertTrue(Mp4FastStart.isFastStart(f))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun topLevel(f: File): List<Mp4FastStart.Box> =
        RandomAccessFile(f, "r").use { Mp4FastStart.topLevelBoxes(it)!! }

    /** The `moov` box's bytes, pulled out of a whole-file image. */
    private fun moovOf(bytes: ByteArray): ByteArray {
        var i = 0
        while (i + 8 <= bytes.size) {
            val size = readU32(bytes, i)
            val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
            if (type == "moov") return bytes.copyOfRange(i, (i + size).toInt())
            i += size.toInt()
        }
        error("no moov")
    }

    private fun readStco(moov: ByteArray): List<Long> = readTable(moov, "stco", 4)
    private fun readCo64(moov: ByteArray): List<Long> = readTable(moov, "co64", 8)

    private fun readTable(moov: ByteArray, type: String, width: Int): List<Long> {
        val at = indexOfType(moov, type)
        val count = readU32(moov, at + 12).toInt()
        return (0 until count).map { i ->
            val p = at + 16 + i * width
            if (width == 4) readU32(moov, p) else readU64(moov, p)
        }
    }

    private fun indexOfType(b: ByteArray, type: String): Int {
        val needle = type.toByteArray(Charsets.ISO_8859_1)
        for (i in 4..b.size - 4) {
            if (b[i] == needle[0] && b[i + 1] == needle[1] &&
                b[i + 2] == needle[2] && b[i + 3] == needle[3]
            ) return i - 4
        }
        error("no $type")
    }

    private fun readU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}
