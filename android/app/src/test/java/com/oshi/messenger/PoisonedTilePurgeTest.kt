package com.oshi.messenger

import com.oshi.messenger.service.map.PoisonedTilePurge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The 13/11/2026 tile-proxy incident: the matcher must hit the exact
 * "Access blocked" picture and nothing else, including other 6725-byte files.
 *
 * Fixture `tiles/osm-access-blocked-6725.png` is the real picture captured from
 * the proxy during the incident.
 */
class PoisonedTilePurgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `constants are the reported picture`() {
        assertEquals(6725L, PoisonedTilePurge.POISON_SIZE)
        assertEquals(
            "109722facb0459991ce2c9983b3ae40232e84be7273aaa454071b50049e3d88b",
            PoisonedTilePurge.POISON_SHA256
        )
    }

    private fun blocked(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("tiles/osm-access-blocked-6725.png")!!.use { it.readBytes() }

    @Test
    fun `the captured picture is poisoned`() {
        val b = blocked()
        assertEquals(6725, b.size)
        assertTrue(PoisonedTilePurge.isPoisoned(b))
    }

    @Test
    fun `one flipped byte is kept`() {
        val b = blocked().also { it[100] = (it[100] + 1).toByte() }
        assertFalse(PoisonedTilePurge.isPoisoned(b))
    }

    @Test
    fun `purge deletes the picture in osmdroid and region folders, keeps the rest`() {
        val osm = tmp.newFolder("osmdroid", "tiles")
        val regions = tmp.newFolder("Maps", "regions")
        val p1 = File(osm, "Mapnik/12/5/6.png.tile").apply { parentFile!!.mkdirs(); writeBytes(blocked()) }
        val p2 = File(regions, "fr/tiles/12/5/6.png").apply { parentFile!!.mkdirs(); writeBytes(blocked()) }
        val lookalike = File(osm, "Mapnik/12/5/7.png.tile").apply { writeBytes(ByteArray(6725) { 3 }) }
        val real = File(osm, "Mapnik/12/5/8.png.tile").apply { writeBytes(ByteArray(20_000) { 9 }) }
        assertEquals(2, PoisonedTilePurge.purgeFiles(listOf(osm, regions, osm)))
        assertFalse(p1.exists())
        assertFalse(p2.exists())
        assertTrue(lookalike.exists())
        assertTrue(real.exists())
    }

    @Test
    fun `a different 6725-byte tile is not poisoned`() {
        val bytes = ByteArray(6725) { (it * 31).toByte() }
        assertFalse(sha(bytes) == PoisonedTilePurge.POISON_SHA256)
        assertFalse(PoisonedTilePurge.isPoisoned(bytes))
    }

    @Test
    fun `other sizes are never poisoned`() {
        assertFalse(PoisonedTilePurge.isPoisoned(ByteArray(0)))
        assertFalse(PoisonedTilePurge.isPoisoned(ByteArray(6724)))
        assertFalse(PoisonedTilePurge.isPoisoned(ByteArray(6726)))
    }

    @Test
    fun `purge keeps a 6725-byte file with another hash and every other tile`() {
        val root = tmp.newFolder("tiles")
        val same = File(root, "Mapnik/13/1/2.png.tile").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(6725) { 7 }) }
        val other = File(root, "Mapnik/13/1/3.png.tile").apply { writeBytes(ByteArray(1234) { 1 }) }
        assertEquals(0, PoisonedTilePurge.purgeFiles(listOf(root, File(root, "missing"))))
        assertTrue(same.exists())
        assertTrue(other.exists())
        assertFalse(PoisonedTilePurge.isPoisonedFile(same))
        assertFalse(PoisonedTilePurge.isPoisonedFile(File(root, "nope")))
    }
}
