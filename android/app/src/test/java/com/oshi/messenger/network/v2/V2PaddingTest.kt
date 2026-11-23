package com.oshi.messenger.network.v2

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * __V2_PADDING_2026_11_09__ Bucketed plaintext padding on the relay path.
 *
 * The SHARED VECTOR in [sharedVector] is asserted byte for byte by iOS
 * `OSHITests/V2PaddingTests.swift` and OSHI-Desktop `V2PaddingDesktopTest`
 * (which compiles this same `V2Padding.kt`). Change it in all three or in none.
 */
class V2PaddingTest {

    @Before fun reset() = V2PaddingPeers.clearForTesting()
    @After fun tearDown() = V2PaddingPeers.clearForTesting()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun id(seed: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { seed.toByte() })

    private fun padded(u: V2Padding.Unpadded): ByteArray? = (u as? V2Padding.Unpadded.Padded)?.bytes
    private fun plain(u: V2Padding.Unpadded): ByteArray? = (u as? V2Padding.Unpadded.Plain)?.bytes

    // ---------------------------------------------------------------- shared vector

    @Test fun sharedVector() {
        val pt = "héllo 👋".toByteArray(Charsets.UTF_8)
        assertEquals("68c3a96c6c6f20f09f918b", hex(pt))
        val p = V2Padding.pad(pt)
        assertEquals(256, p.size)
        assertEquals("ff500168c3a96c6c6f20f09f918b80", hex(p.copyOfRange(0, 15)))
        assertTrue(p.copyOfRange(15, p.size).all { it == 0.toByte() })
        assertEquals("0c052d9de9ec87516729769b8157f701379a272b35818911dae78ad5b4cba84c", sha(p))
        assertArrayEquals(pt, padded(V2Padding.unpad(p)))

        val a253 = V2Padding.pad(ByteArray(253) { 0x61 })
        assertEquals(1024, a253.size)
        assertEquals("d16075e9b0dadb54e365d0163e2409bee1f5b5f54d3a9290ccadca898ceaa438", sha(a253))
        assertEquals(4, V2Padding.CAP_PADDING)
        assertEquals(listOf(256, 1024, 4096, 16384, 65536, 131072), V2Padding.BUCKETS)
    }

    // ---------------------------------------------------------------- round trip / buckets

    @Test fun roundTripAcrossSizes() {
        for (n in listOf(0, 1, 60, 251, 252, 253, 1020, 1021, 4092, 4093, 16380, 65532, 65533, 131068)) {
            val pt = ByteArray(n) { (it * 31 + 7).toByte() }
            val p = V2Padding.pad(pt)
            assertTrue("n=$n → ${p.size}", p.size in V2Padding.BUCKETS)
            assertArrayEquals("n=$n", pt, padded(V2Padding.unpad(p)))
        }
    }

    @Test fun bucketBoundaries() {
        assertEquals(256, V2Padding.paddedLength(0))
        assertEquals(256, V2Padding.paddedLength(252))
        assertEquals(1024, V2Padding.paddedLength(253))
        assertEquals(1024, V2Padding.paddedLength(1020))
        assertEquals(4096, V2Padding.paddedLength(1021))
        assertEquals(131072, V2Padding.paddedLength(131068))
        assertNull(V2Padding.paddedLength(131069))
        val big = ByteArray(131069) { 0x41 }
        assertSame("too large for the last bucket: sent exactly as today", big, V2Padding.pad(big))
        assertArrayEquals(big, plain(V2Padding.unpad(big)))
    }

    /** The privacy property itself, through the real ratchet: a receipt and a sentence look alike. */
    @Test fun receiptAndSentenceShareACiphertextSize() {
        val (alice, bob) = ratchetPair()
        val receipt = "📬DELIVERY_RECEIPT📬${java.util.UUID.randomUUID()}".toByteArray()
        val sentence = "On se retrouve devant la gare à 20 h, je serai un peu en retard.".toByteArray()
        val (h1, c1) = OSHIRatchetV2.encrypt(alice, V2Padding.pad(receipt), ByteArray(0))
        val (_, c2) = OSHIRatchetV2.encrypt(alice, V2Padding.pad(sentence), ByteArray(0))
        assertEquals(c1.size, c2.size)
        assertEquals(256 + 16, c1.size)
        assertArrayEquals(receipt, padded(V2Padding.unpad(OSHIRatchetV2.decrypt(bob, h1, c1, ByteArray(0)))))
    }

    // ---------------------------------------------------------------- flag absent / malformed

    @Test fun unflaggedPlaintextIsUntouched() {
        for (s in listOf("", "ok", "📬DELIVERY_RECEIPT📬abc", "{\"kind\":\"v2file\"}", "text\u0000\u0000")) {
            val b = s.toByteArray()
            assertSame(s, b, plain(V2Padding.unpad(b)))
        }
        val tricky = byteArrayOf(0x41, 0x80.toByte(), 0, 0)
        assertArrayEquals("a trailing 80 00 00 on an unflagged message is NOT padding", tricky, plain(V2Padding.unpad(tricky)))
    }

    @Test fun malformedPaddingIsRejectedWithoutCrashing() {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        for (bad in listOf(b(0xFF), b(0xFF, 0x50), b(0xFF, 0x50, 0x01), b(0xFF, 0x50, 0x02, 0x80), b(0xFF, 0x51, 0x01, 0x80),
                           b(0xFF, 0x50, 0x01, 0x41, 0, 0), b(0xFF, 0x50, 0x01, 0x41, 0x81),
                           b(0xFF, 0x50, 0x01) + ByteArray(300))) {
            assertSame(hex(bad), V2Padding.Unpadded.Malformed, V2Padding.unpad(bad))
        }
        assertArrayEquals(ByteArray(0), padded(V2Padding.unpad(b(0xFF, 0x50, 0x01, 0x80))))
        // Random garbage never throws.
        val rnd = SecureRandom()
        repeat(2000) {
            val g = ByteArray(rnd.nextInt(40)).also(rnd::nextBytes)
            if (g.isNotEmpty()) g[0] = 0xFF.toByte()
            V2Padding.unpad(g)
        }
    }

    // ---------------------------------------------------------------- caps gating

    @Test fun thisBuildAdvertisesPadding() {
        assertTrue((GroupLegacyCompat.MY_CAPS and V2Padding.CAP_PADDING) != 0)
        // __MEDIA_PADDING_2026_11_10__ + CAP_MEDIA_PADDING (8).
        assertEquals(15, GroupLegacyCompat.MY_CAPS)
    }

    @Test fun oldOrUnknownPeerIsNeverPadded() {
        val p = id(1)
        assertFalse("unknown ⇒ no padding", V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteBundle(p, null, null)
        assertFalse("pre-caps bundle", V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteBundle(p, null, 3)
        assertFalse("today's clients (caps 3)", V2PaddingPeers.slotCapable(p, null))
        assertFalse(V2Padding.shouldPad(true, V2PaddingPeers.slotCapable(p, null)))
        V2PaddingPeers.noteBundle(p, null, 7)
        assertTrue(V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteBundle(p, null, 3)
        assertFalse("a later bundle without the bit turns it off", V2PaddingPeers.slotCapable(p, null))
    }

    @Test fun slotsAreIndependentAndSpellingInsensitive() {
        val p = id(2)
        V2PaddingPeers.noteBundle(p, "devNEW", 7)
        assertTrue(V2PaddingPeers.slotCapable(p, "devNEW"))
        assertFalse(V2PaddingPeers.slotCapable(p, "devOLD"))
        assertFalse(V2PaddingPeers.slotCapable(p, null))
        val url = p.replace('+', '-').replace('/', '_').trimEnd('=')
        assertTrue(V2PaddingPeers.slotCapable(url, "devNEW"))
    }

    @Test fun inboundProofAndCounterpartChange() {
        val p = id(3)
        V2PaddingPeers.noteInbound(p, null, padded = false, carriedX3DH = false)
        assertFalse(V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteInbound(p, null, padded = true, carriedX3DH = false)
        assertTrue(V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteInbound(p, null, padded = false, carriedX3DH = false)
        assertTrue("an unpadded message does not disprove it", V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteInbound(p, null, padded = false, carriedX3DH = true)
        assertFalse("a new X3DH session may be another, older install", V2PaddingPeers.slotCapable(p, null))
        V2PaddingPeers.noteDeviceList(p, "d", 3)
        assertFalse(V2PaddingPeers.slotCapable(p, "d"))
        V2PaddingPeers.noteDeviceList(p, "d", 7)
        V2PaddingPeers.noteDeviceList(p, "d", 3)
        assertTrue("device lists only upgrade", V2PaddingPeers.slotCapable(p, "d"))
    }

    @Test fun groupWithOneOldMemberIsNotPadded() {
        val me = id(9); val a = id(4); val b = id(5); val old = id(6)
        V2PaddingPeers.noteBundle(a, null, 7)
        V2PaddingPeers.noteBundle(b, "d1", 7)
        V2PaddingPeers.noteBundle(old, null, 3)
        assertTrue(V2PaddingPeers.groupAllows(listOf(me, a, b), me))
        assertFalse(V2PaddingPeers.groupAllows(listOf(me, a, b, old), me))
        assertFalse("unknown member", V2PaddingPeers.groupAllows(listOf(a, b, id(7)), me))
        assertFalse(V2PaddingPeers.groupAllows(listOf(me), me))
        assertFalse(V2Padding.shouldPad(padGate = false, slotCapable = true))
    }

    @Test fun persistsThroughTheStore() {
        var saved: String? = null
        val store = object : V2PaddingPeers.Store {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        }
        V2PaddingPeers.attach(store)
        V2PaddingPeers.noteBundle(id(8), "x", 7)
        V2PaddingPeers.clearForTesting()
        assertFalse(V2PaddingPeers.slotCapable(id(8), "x"))
        V2PaddingPeers.attach(store)
        assertTrue(V2PaddingPeers.slotCapable(id(8), "x"))
    }

    // ---------------------------------------------------------------- no padding off the relay

    /**
     * Padding is applied ONLY at the v2 router's relay seal; the BLE/Wi-Fi mesh, LoRa/
     * Meshtastic/MeshCore and the text-size budgets never see it. Guarded in the source so a
     * future "pad everywhere" cannot quietly reach a 200-byte LoRa frame.
     */
    @Test fun paddingNeverReachesTheOfflineTransports() {
        val root = File("src/main/java/com/oshi/messenger")
        assertTrue("source tree not found from ${File(".").absolutePath}", root.isDirectory)
        val padders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            if (t.contains("V2Padding.pad(")) padders += f.name
            val n = f.name
            val offline = n.contains("Mesh", ignoreCase = true) || n.contains("Meshtastic") || n.contains("LoRa") ||
                n.contains("MeshCore") || n == "MessageTextSplitter.kt" || n == "MessageSendPolicy.kt" ||
                n == "DoubleRatchet.kt"
            if (offline && n != "DeviceMailboxManager.kt") assertFalse("$n must not pad", t.contains("V2Padding"))
        }
        assertEquals(listOf("V2MessageRouter.kt"), padders.distinct())
        val router = File(root, "network/v2/V2MessageRouter.kt").readText()
        assertEquals("two relay seals: the account path and the per-device path", 2, router.split("V2Padding.pad(").size - 1)
    }

    private fun ratchetPair(): Pair<OSHIRatchetV2.State, OSHIRatchetV2.State> {
        val sk = ByteArray(32) { 0x42 }
        val bobSpk = OSHICryptoV2.generateX25519()
        val alice = OSHIRatchetV2.initAlice(sk, bobSpk.pub)
        val bob = OSHIRatchetV2.initBob(sk, bobSpk)
        return alice to bob
    }
}
