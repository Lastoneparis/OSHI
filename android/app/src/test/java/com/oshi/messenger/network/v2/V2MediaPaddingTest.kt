package com.oshi.messenger.network.v2

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * __MEDIA_PADDING_2026_11_10__ Padmé padding of v2 media blobs
 * (docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md).
 *
 * The VECTORS here ([padmeVectors], [byteExactSmallFile], [byteExactTwoChunkFile]) are the
 * spec's, asserted byte for byte by OSHI-Desktop `V2MediaPaddingDesktopTest` (which compiles
 * this same crypto) and by iOS. Change them in all three or in none.
 */
class V2MediaPaddingTest {

    @Before fun reset() = V2PaddingPeers.clearForTesting()
    @After fun tearDown() = V2PaddingPeers.clearForTesting()

    private val key = ByteArray(32) { it.toByte() }                       // 0x00..0x1f
    private val nonce = ByteArray(8) { (0xa0 + it).toByte() }             // 0xa0..0xa7

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun id(seed: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { seed.toByte() })
    private fun bytes(n: Int, salt: Int = 7) = ByteArray(n) { i -> ((i * 31 + salt) and 0xFF).toByte() }

    private fun stream(enc: OSHICryptoV2.EncryptedFile, expected: Int? = enc.chunks.size): Pair<Long, ByteArray> {
        val out = ByteArrayOutputStream()
        val n = OSHICryptoV2Streaming.decryptToStream(enc.fileKey, enc.fileNonce, enc.manifest, out, expected) { i -> enc.chunks.getOrNull(i) }
        return n to out.toByteArray()
    }

    // ---------------------------------------------------------------- spec vectors

    @Test fun padmeVectors() {
        val v = linkedMapOf(
            0L to 4096L, 1L to 4096L, 4096L to 4096L, 4097L to 4352L, 10000L to 10240L,
            65537L to 67584L, 100000L to 100352L, 1000000L to 1015808L, 1048576L to 1048576L,
            2097153L to 2162688L, 5243003L to 5373952L, 100000000L to 100663296L,
            209715199L to 209715200L, 209715200L to 209715200L,
        )
        for ((l, p) in v) assertEquals("L=$l", p, OSHICryptoV2.mediaPaddedSize(l))
        // Never shrinks, never crosses the ceiling for L under it.
        assertEquals(209715201L, OSHICryptoV2.mediaPaddedSize(209715201L))
        for (l in listOf(4097L, 77777L, 3145733L, 123456789L)) {
            val p = OSHICryptoV2.mediaPaddedSize(l)
            assertTrue(p >= l && p <= OSHICryptoV2.MEDIA_PAD_MAX && p - l <= l / 8 + 1)
        }
    }

    @Test fun byteExactSmallFile() {
        val data = "héllo 👋".toByteArray(Charsets.UTF_8)
        assertEquals(11, data.size)
        val enc = OSHICryptoV2.encryptFile(data, "a.txt", "text/plain", reuseFileKey = key, reuseFileNonce = nonce, pad = true)
        assertEquals(
            "{\"filename\":\"a.txt\",\"mime\":\"text/plain\",\"size\":11,\"chunkCount\":1,\"chunkSize\":2097152,\"paddedSize\":4096}",
            String(enc.infoJson, Charsets.UTF_8),
        )
        assertEquals(1, enc.chunks.size)
        assertEquals(4112, enc.chunks[0].size)
        assertEquals("467e96605b69cdd2047136dd6640282f4f7e54efc66c61bd1a95e6f4b244502e", sha(enc.chunks[0]))
        assertEquals(119, enc.manifest.size)
        assertEquals("f66c94d1f2742ffc6742d4b4d97df162d7f89f4dea408c042d729be1cd3bed5d", sha(enc.manifest))
        assertArrayEquals(data, OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest))
        val (n, out) = stream(enc)
        assertEquals(11L, n)
        assertArrayEquals(data, out)
    }

    @Test fun byteExactTwoChunkFile() {
        val data = ByteArray(3145733) { 'a'.code.toByte() }
        val enc = OSHICryptoV2.encryptFile(data, "a.txt", "text/plain", reuseFileKey = key, reuseFileNonce = nonce, pad = true)
        assertEquals(3211264L, OSHICryptoV2.mediaPaddedSize(data.size.toLong()))
        assertEquals(listOf(2097168, 1114128), enc.chunks.map { it.size })
        assertEquals("a04c905db45c6098e7175d2ceea2b5f3793481f39900fcd011d7b9fe7f099df6", sha(enc.chunks[0] + enc.chunks[1]))
        assertTrue(String(enc.infoJson, Charsets.UTF_8).endsWith(",\"chunkCount\":2,\"chunkSize\":2097152,\"paddedSize\":3211264}"))
        assertEquals(2, OSHICryptoV2.mediaChunkCount(data.size.toLong(), padded = true))
        assertArrayEquals(data, OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest))
        val (n, out) = stream(enc)
        assertEquals(data.size.toLong(), n)
        assertArrayEquals(data, out)
    }

    /** pad = false is byte-identical to every earlier build: no `paddedSize`, same chunks. */
    @Test fun unpaddedIsUnchanged() {
        val data = "héllo 👋".toByteArray(Charsets.UTF_8)
        val enc = OSHICryptoV2.encryptFile(data, "a.txt", "text/plain", reuseFileKey = key, reuseFileNonce = nonce)
        assertEquals(
            "{\"filename\":\"a.txt\",\"mime\":\"text/plain\",\"size\":11,\"chunkCount\":1,\"chunkSize\":2097152}",
            String(enc.infoJson, Charsets.UTF_8),
        )
        assertArrayEquals(OSHICryptoV2.aesGcmSeal(key, OSHICryptoV2.chunkNonce(nonce, 0), data, ByteArray(0)), enc.chunks[0])
        assertEquals(1, OSHICryptoV2.mediaChunkCount(11, padded = false))
    }

    // ---------------------------------------------------------------- round trips

    @Test fun roundTripPaddedAndUnpadded() {
        for (chunkSize in listOf(OSHICryptoV2.DEFAULT_CHUNK_SIZE, 1000, 4096)) {
            for (n in listOf(1, 11, 999, 1000, 1001, 4095, 4096, 4097, 10000, 65537)) {
                val data = bytes(n, n)
                for (pad in listOf(false, true)) {
                    val enc = OSHICryptoV2.encryptFile(data, "f.bin", "application/octet-stream", chunkSize = chunkSize, pad = pad)
                    val sealed = enc.chunks.sumOf { it.size - 16 }.toLong()
                    assertEquals("n=$n pad=$pad cs=$chunkSize", if (pad) OSHICryptoV2.mediaPaddedSize(n.toLong()) else n.toLong(), sealed)
                    assertEquals(OSHICryptoV2.mediaChunkCount(n.toLong(), pad, chunkSize), enc.chunks.size)
                    assertArrayEquals(data, OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest))
                    val (w, out) = stream(enc)
                    assertEquals(n.toLong(), w)
                    assertArrayEquals("streaming stops writing at size", data, out)
                    val m = OSHICryptoV2Streaming.openManifest(enc.fileKey, enc.fileNonce, enc.manifest)
                    assertEquals(n.toLong(), m.size)
                    assertEquals(if (pad) OSHICryptoV2.mediaPaddedSize(n.toLong()) else null, m.paddedSize)
                }
            }
        }
    }

    /** A manifest written by an older build (no `paddedSize`) still opens, both readers. */
    @Test fun oldFormatStillDecrypts() {
        val data = bytes(5000)
        val chunks = listOf(OSHICryptoV2.aesGcmSeal(key, OSHICryptoV2.chunkNonce(nonce, 0), data, ByteArray(0)))
        val info = "{\"filename\":\"x.jpg\",\"mime\":\"image/jpeg\",\"size\":5000,\"chunkCount\":1,\"chunkSize\":2097152}"
        val manifest = OSHICryptoV2.aesGcmSeal(key, OSHICryptoV2.chunkNonce(nonce, OSHICryptoV2.MANIFEST_COUNTER), info.toByteArray(), ByteArray(0))
        assertArrayEquals(data, OSHICryptoV2.decryptFile(key, nonce, chunks, manifest))
        val enc = OSHICryptoV2.EncryptedFile(key, nonce, chunks, manifest, info.toByteArray())
        assertArrayEquals(data, stream(enc).second)
        assertEquals(null, OSHICryptoV2Streaming.openManifest(key, nonce, manifest).paddedSize)
    }

    // ---------------------------------------------------------------- rejections

    /** Seal [sealed] as chunks under a hand-written manifest. */
    private fun forge(sealed: ByteArray, size: Int, paddedSize: Int, chunkSize: Int = OSHICryptoV2.DEFAULT_CHUNK_SIZE): OSHICryptoV2.EncryptedFile {
        val chunks = ArrayList<ByteArray>()
        var off = 0; var i = 0L
        while (off < sealed.size) {
            val end = minOf(off + chunkSize, sealed.size)
            chunks += OSHICryptoV2.aesGcmSeal(key, OSHICryptoV2.chunkNonce(nonce, i++), sealed.copyOfRange(off, end), ByteArray(0))
            off = end
        }
        val info = OSHICryptoV2.manifestJson("f", "application/octet-stream", size, chunks.size, chunkSize, paddedSize)
        val manifest = OSHICryptoV2.aesGcmSeal(key, OSHICryptoV2.chunkNonce(nonce, OSHICryptoV2.MANIFEST_COUNTER), info, ByteArray(0))
        return OSHICryptoV2.EncryptedFile(key, nonce, chunks, manifest, info)
    }

    private fun assertBothReject(what: String, enc: OSHICryptoV2.EncryptedFile) {
        try { OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest); fail("decryptFile accepted $what") } catch (_: IllegalArgumentException) {}
        try { stream(enc); fail("decryptToStream accepted $what") } catch (_: IllegalArgumentException) {}
    }

    @Test fun nonZeroPaddingIsRejected() {
        val data = bytes(100)
        for (where in listOf(100, 2000, 4095)) {
            val sealed = data + ByteArray(3996)
            sealed[where] = 1
            assertBothReject("a non-zero byte at $where", forge(sealed, 100, 4096))
        }
        // Across chunks: the bad byte in the SECOND chunk, after the streaming reader stopped writing.
        val sealed = data + ByteArray(3996)
        sealed[3000] = 0x55
        assertBothReject("a non-zero byte in a later chunk", forge(sealed, 100, 4096, chunkSize = 1024))
    }

    @Test fun paddedSizeBelowSizeIsRejected() {
        val data = bytes(5000)
        assertBothReject("paddedSize < size", forge(data, 5000, 4096))
    }

    @Test fun reassembledLengthMustEqualPaddedSize() {
        val data = bytes(100)
        assertBothReject("short of paddedSize", forge(data + ByteArray(1000), 100, 4096))
        assertBothReject("beyond paddedSize", forge(data + ByteArray(5000), 100, 4096))
    }

    @Test fun streamingWritesNothingPastSizeEvenOnFailure() {
        val data = bytes(100)
        val sealed = data + ByteArray(3996)
        sealed[4000] = 9
        val enc = forge(sealed, 100, 4096, chunkSize = 64)
        val out = ByteArrayOutputStream()
        try {
            OSHICryptoV2Streaming.decryptToStream(enc.fileKey, enc.fileNonce, enc.manifest, out, enc.chunks.size) { enc.chunks[it] }
            fail()
        } catch (_: IllegalArgumentException) {}
        assertTrue(out.size() <= 100)
    }

    // ---------------------------------------------------------------- gating

    @Test fun thisBuildAdvertisesMediaPadding() {
        assertEquals(8, V2Padding.CAP_MEDIA_PADDING)
        assertTrue((GroupLegacyCompat.MY_CAPS and V2Padding.CAP_MEDIA_PADDING) != 0)
    }

    @Test fun unknownOrOldPeerIsNeverPadded() {
        val p = id(1)
        assertFalse("unknown ⇒ unpadded", V2PaddingPeers.mediaAllows(p, emptyList()))
        V2PaddingPeers.noteBundle(p, null, null)
        assertFalse("pre-caps bundle", V2PaddingPeers.mediaAllows(p, emptyList()))
        V2PaddingPeers.noteBundle(p, null, 7)
        assertFalse("message padding is not media padding", V2PaddingPeers.mediaAllows(p, emptyList()))
        assertTrue(V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteBundle(p, null, 15)
        assertTrue("account capable, no devices", V2PaddingPeers.mediaAllows(p, emptyList()))
        assertFalse("device list could not be fetched ⇒ unknown", V2PaddingPeers.mediaAllows(p, null))
        V2PaddingPeers.noteBundle(p, null, 7)
        assertFalse("a bundle is exact: downgrade", V2PaddingPeers.mediaAllows(p, emptyList()))
    }

    /** EVERY delivery path — the account slot AND each device — must be confirmed (intersection). */
    @Test fun everyDeliveryPathMustAdvertise() {
        val p = id(2)
        V2PaddingPeers.noteBundle(p, "d1", 15)
        V2PaddingPeers.noteBundle(p, "d2", 15)
        assertFalse("devices capable but account slot unknown ⇒ unpadded", V2PaddingPeers.mediaAllows(p, listOf("d1", "d2")))
        V2PaddingPeers.noteBundle(p, null, 7)
        assertFalse("devices capable but account slot old ⇒ unpadded", V2PaddingPeers.mediaAllows(p, listOf("d1", "d2")))
        V2PaddingPeers.noteBundle(p, null, 15)
        assertTrue("all capable ⇒ padded", V2PaddingPeers.mediaAllows(p, listOf("d1", "d2")))
        assertFalse("d3 unknown", V2PaddingPeers.mediaAllows(p, listOf("d1", "d2", "d3")))
        V2PaddingPeers.noteBundle(p, "d3", 7)
        assertFalse("account capable but one device old ⇒ unpadded", V2PaddingPeers.mediaAllows(p, listOf("d1", "d2", "d3")))
        // A device list is upgrade-only.
        V2PaddingPeers.noteDeviceList(p, "d3", 15)
        assertTrue(V2PaddingPeers.mediaAllows(p, listOf("d1", "d2", "d3")))
        V2PaddingPeers.noteDeviceList(p, "d3", 7)
        assertTrue(V2PaddingPeers.mediaAllows(p, listOf("d3")))
    }

    @Test fun inboundEvidence() {
        val p = id(3)
        V2PaddingPeers.noteInboundPaddedMedia(p, "d1")
        assertTrue("a padded file proves the sender reads them", V2PaddingPeers.mediaSlotCapable(p, "d1"))
        V2PaddingPeers.noteInbound(p, "d1", padded = true, carriedX3DH = false)
        assertTrue(V2PaddingPeers.mediaSlotCapable(p, "d1"))
        V2PaddingPeers.noteInbound(p, "d1", padded = true, carriedX3DH = true)
        assertFalse("a new X3DH may be another install: back to unknown", V2PaddingPeers.mediaSlotCapable(p, "d1"))
        assertTrue("message padding still learned", V2PaddingPeers.slotCapable(p, "d1"))
        // Identity spelling is normalised like the message-padding slots.
        V2PaddingPeers.noteBundle(p.replace('+', '-').replace('/', '_').trimEnd('='), null, 8)
        assertTrue(V2PaddingPeers.mediaSlotCapable(p, null))
    }

    @Test fun groupNeedsEveryOtherMember() {
        val me = id(9)
        assertFalse("nobody else", V2PaddingPeers.groupMediaAllows(mapOf(me to true), me))
        assertTrue(V2PaddingPeers.groupMediaAllows(mapOf(me to false, id(1) to true, id(2) to true), me))
        assertFalse(V2PaddingPeers.groupMediaAllows(mapOf(id(1) to true, id(2) to false), me))
    }

    @Test fun mediaSlotsPersistBesideMessageSlots() {
        var saved: String? = null
        val store = object : V2PaddingPeers.Store {
            override fun read(): String? = saved
            override fun write(json: String) { saved = json }
        }
        V2PaddingPeers.attach(store)
        V2PaddingPeers.noteBundle(id(4), "d", 15)
        V2PaddingPeers.noteBundle(id(5), null, 4)
        val json = org.json.JSONObject(saved!!)
        assertTrue(json.keys().asSequence().any { it.startsWith("m!") })
        V2PaddingPeers.clearForTesting()
        V2PaddingPeers.attach(store)
        assertTrue(V2PaddingPeers.mediaSlotCapable(id(4), "d"))
        assertTrue(V2PaddingPeers.slotCapable(id(4), "d"))
        assertTrue(V2PaddingPeers.slotCapable(id(5), null))
        assertFalse(V2PaddingPeers.mediaSlotCapable(id(5), null))
        assertFalse("a prefixed key is never an identity", V2PaddingPeers.identityAdvertises("m!" + id(4)))
    }

    // ---------------------------------------------------------------- relay blob path only

    /**
     * Media padding is decided in ONE place per platform (the v2 router's blob sends). The
     * BLE/Wi-Fi mesh, LoRa/Meshtastic/MeshCore and legacy IPFS paths never seal a padded file.
     */
    @Test fun mediaPaddingNeverReachesOtherPaths() {
        val root = File("src/main/java/com/oshi/messenger")
        assertTrue("source tree not found from ${File(".").absolutePath}", root.isDirectory)
        val padders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            if (Regex("""encryptFile\([^)]*pad\s*=""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(t)) padders += f.name
            val n = f.name
            val offline = n.contains("Mesh", ignoreCase = true) || n.contains("LoRa") || n.contains("Ipfs", ignoreCase = true) ||
                n.contains("IPFS")
            if (offline && n != "DeviceMailboxManager.kt") {
                assertFalse("$n must not pad media", t.contains("CAP_MEDIA_PADDING") || t.contains("mediaPaddedSize") || t.contains("pad = padMedia"))
            }
        }
        assertEquals(listOf("V2MessageRouter.kt"), padders.distinct())
    }


    // __ACCOUNT_CAPS_IN_DEVICE_LIST_2026_11_10__ measured on production: two current desktops
    // in device mode never padded, because the account slot was never learned.
    @Test fun deviceListWithAccountCapsLetsDeviceModePad() {
        val peer = "cGVlci1rZXktZm9yLWFjY291bnQtY2Fwcy10ZXN0LTAwMDA="
        val d1 = "fba5d5d2e72c9a59999f70841f9f2077"; val d2 = "1aeb0157356201744ad0223961ca4622"
        // Old server: no accountCaps -> account slot unknown -> never padded.
        V2PaddingPeers.learnFromDeviceList(peer, """{"devices":[{"deviceId":"$d1","caps":15}],"enabled":true}""")
        org.junit.Assert.assertTrue(V2PaddingPeers.mediaSlotCapable(peer, d1))
        org.junit.Assert.assertFalse(V2PaddingPeers.mediaAllows(peer, listOf(d1)))
        // New server, current account install: padded.
        V2PaddingPeers.learnFromDeviceList(peer, """{"devices":[{"deviceId":"$d1","caps":15}],"enabled":true,"accountCaps":15}""")
        org.junit.Assert.assertTrue(V2PaddingPeers.mediaAllows(peer, listOf(d1)))
        // A second, OLD device in the list: unpadded.
        V2PaddingPeers.learnFromDeviceList(peer, """{"devices":[{"deviceId":"$d1","caps":15},{"deviceId":"$d2","caps":7}],"enabled":true,"accountCaps":15}""")
        org.junit.Assert.assertFalse(V2PaddingPeers.mediaAllows(peer, listOf(d1, d2)))
        // The account install went back to an old build: exact, so it turns padding OFF.
        V2PaddingPeers.learnFromDeviceList(peer, """{"devices":[{"deviceId":"$d1","caps":15}],"enabled":true,"accountCaps":7}""")
        org.junit.Assert.assertFalse(V2PaddingPeers.mediaAllows(peer, listOf(d1)))
        // Device mode off on the server: account slot alone decides (empty device list).
        V2PaddingPeers.learnFromDeviceList(peer, """{"devices":[],"enabled":false,"accountCaps":15}""")
        org.junit.Assert.assertTrue(V2PaddingPeers.mediaAllows(peer, emptyList()))
        // Garbage never throws and teaches nothing.
        V2PaddingPeers.learnFromDeviceList(peer, "not json"); V2PaddingPeers.learnFromDeviceList(peer, null)
        V2PaddingPeers.learnFromDeviceList(peer, """{"accountCaps":null}""")
        org.junit.Assert.assertTrue(V2PaddingPeers.mediaAllows(peer, emptyList()))
    }
}
