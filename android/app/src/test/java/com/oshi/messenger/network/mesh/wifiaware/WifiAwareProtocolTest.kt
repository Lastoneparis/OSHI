package com.oshi.messenger.network.mesh.wifiaware

import com.oshi.messenger.network.mesh.wifiaware.WifiAwareProtocol.Discovery
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareProtocol.Framing
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareProtocol.Handshake
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

/**
 * __WIFI_AWARE_2026_11_25__ The wire of the Wi-Fi Aware lane: discovery hello,
 * socket framing, envelope framing over a real socket, and the mandatory
 * proof-of-possession handshake (including the impostor and reflection cases
 * that would otherwise let a nearby phone swallow a contact's messages).
 */
class WifiAwareProtocolTest {

    // ───────────────────────── discovery hello ─────────────────────────

    private val tokenA = ByteArray(8) { (it + 1).toByte() }
    private val tokenB = ByteArray(8) { (0xF0 + it).toByte() }
    private val keyA = "A".repeat(44)

    @Test fun `announce round-trips and fits the smallest Aware SSI budget`() {
        val hint = Discovery.identityHint(keyA, tokenA)
        val bytes = Discovery.encodeAnnounce(tokenA, hint)
        assertEquals(Discovery.ANNOUNCE_LENGTH, bytes.size)
        assertTrue("must fit 255 bytes, the minimum every chip accepts", bytes.size <= 255)
        val m = Discovery.decode(bytes)!!
        assertEquals(Discovery.TYPE_ANNOUNCE, m.type)
        assertArrayEquals(tokenA, m.token)
        assertArrayEquals(hint, m.hint)
        assertNull(m.salt)
    }

    @Test fun `hello carries a salt, ready does not`() {
        val hint = Discovery.identityHint(keyA, tokenA)
        val salt = ByteArray(8) { 7 }
        val hello = Discovery.decode(Discovery.encodeHello(tokenA, hint, salt))!!
        assertEquals(Discovery.TYPE_HELLO, hello.type)
        assertArrayEquals(salt, hello.salt)
        val ready = Discovery.decode(Discovery.encodeReady(tokenA, hint))!!
        assertEquals(Discovery.TYPE_READY, ready.type)
        assertNull(ready.salt)
    }

    @Test fun `anything not ours is ignored, never trusted`() {
        val good = Discovery.encodeAnnounce(tokenA, Discovery.identityHint(keyA, tokenA))
        assertNull(Discovery.decode(null))
        assertNull(Discovery.decode(ByteArray(0)))
        assertNull(Discovery.decode(good.copyOf(good.size - 1)))           // truncated
        assertNull(Discovery.decode(good + 0))                              // trailing byte
        assertNull(Discovery.decode(good.clone().also { it[0] = 'X'.code.toByte() })) // other app
        assertNull(Discovery.decode(good.clone().also { it[2] = 9 }))        // future version
        assertNull(Discovery.decode(good.clone().also { it[3] = 77 }))       // unknown type
        // A HELLO without its salt is malformed.
        val helloNoSalt = good.clone().also { it[3] = Discovery.TYPE_HELLO }
        assertNull(Discovery.decode(helloNoSalt))
    }

    @Test fun `the hint is not the key and changes with the token`() {
        val h1 = Discovery.identityHint(keyA, tokenA)
        val h2 = Discovery.identityHint(keyA, tokenB)
        assertEquals(WifiAwareProtocol.HINT_LENGTH, h1.size)
        assertFalse("a rotating token must give an unlinkable hint", h1.contentEquals(h2))
        assertTrue(Discovery.hintMatches(keyA, tokenA, h1))
        assertFalse(Discovery.hintMatches("B".repeat(44), tokenA, h1))
        // Nothing of the key itself is on the air.
        val announce = Discovery.encodeAnnounce(tokenA, h1)
        assertFalse(String(announce, Charsets.ISO_8859_1).contains("AAAA"))
    }

    @Test fun `exactly one side of a pair initiates`() {
        assertTrue(Discovery.shouldInitiate(tokenA, tokenB))
        assertFalse(Discovery.shouldInitiate(tokenB, tokenA))
        assertFalse("equal tokens (or our own publication) initiate nothing", Discovery.shouldInitiate(tokenA, tokenA.clone()))
        // Unsigned comparison: 0x80 is bigger than 0x7F, not negative.
        val lo = ByteArray(8) { 0x7F }
        val hi = ByteArray(8) { 0x80.toByte() }
        assertTrue(Discovery.shouldInitiate(lo, hi))
        assertFalse(Discovery.shouldInitiate(hi, lo))
    }

    @Test fun `both sides derive the same valid passphrase`() {
        val salt = ByteArray(8) { 3 }
        val initiatorSide = Discovery.passphrase(tokenA, tokenB, salt)
        val responderSide = Discovery.passphrase(tokenA, tokenB, salt)
        assertEquals(initiatorSide, responderSide)
        assertTrue("Aware accepts 8..63 characters", initiatorSide.length in 8..63)
        assertTrue(initiatorSide.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(initiatorSide, Discovery.passphrase(tokenA, tokenB, ByteArray(8) { 4 }))
        assertNotEquals(initiatorSide, Discovery.passphrase(tokenB, tokenA, salt))
    }

    // ───────────────────────── framing ─────────────────────────

    @Test fun `frames round-trip back to back`() {
        val out = ByteArrayOutputStream()
        Framing.write(out, Framing.TYPE_PING, ByteArray(0))
        Framing.write(out, Framing.TYPE_ENVELOPE, "héllo".toByteArray())
        Framing.write(out, Framing.TYPE_ACK, Framing.encodeSeq(42))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(Framing.Frame(Framing.TYPE_PING, ByteArray(0)), Framing.read(input))
        assertEquals(Framing.Frame(Framing.TYPE_ENVELOPE, "héllo".toByteArray()), Framing.read(input))
        assertEquals(42L, Framing.decodeSeq(Framing.read(input)!!.payload))
        assertNull("clean end of stream between frames", Framing.read(input))
    }

    @Test fun `a broken stream is an error, never a resync`() {
        val frame = Framing.encode(Framing.TYPE_ENVELOPE, ByteArray(100) { 1 })
        expectFramingError(frame.copyOf(3))                 // inside the header
        expectFramingError(frame.copyOf(50))                // inside the payload
        expectFramingError(byteArrayOf(99, 0, 0, 0, 0))     // unknown type
        expectFramingError(byteArrayOf(Framing.TYPE_ENVELOPE, 0x7F, 0x7F, 0x7F, 0x7F)) // hostile length
        expectFramingError(byteArrayOf(Framing.TYPE_ENVELOPE, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) // negative
    }

    @Test fun `handshake frames have their own small ceiling`() {
        val big = Framing.encode(Framing.TYPE_IDENT, ByteArray(Framing.MAX_HANDSHAKE_PAYLOAD + 1))
        try {
            Framing.read(ByteArrayInputStream(big), Framing.MAX_HANDSHAKE_PAYLOAD)
            fail("oversized pre-auth frame accepted")
        } catch (e: Framing.FramingException) { /* expected */ }
    }

    @Test fun `sequence numbers round-trip across the whole range`() {
        for (v in listOf(0L, 1L, 255L, 256L, Long.MAX_VALUE, -1L)) {
            assertEquals(v, Framing.decodeSeq(Framing.encodeSeq(v)))
        }
        assertNull(Framing.decodeSeq(ByteArray(7)))
        val (seq, json) = Framing.decodeEnvelope(Framing.encodeEnvelope(9, "{}".toByteArray()))!!
        assertEquals(9L, seq)
        assertEquals("{}", String(json))
        assertNull(Framing.decodeEnvelope(ByteArray(3)))
    }

    @Test fun `envelopes cross a real TCP socket intact, in order, and are acked`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val bodies = listOf(
            """{"id":"m1","type":"TEXT_MESSAGE","payload":"AAAA"}""".toByteArray(),
            ByteArray(3 * 1024 * 1024) { (it % 251).toByte() },   // a media-sized envelope
            """{"id":"m3","type":"ACK"}""".toByteArray(),
        )
        val received = mutableListOf<Pair<Long, ByteArray>>()
        val peer = thread {
            server.accept().use { s ->
                val input = s.getInputStream().buffered()
                val output = s.getOutputStream().buffered()
                while (true) {
                    val f = Framing.read(input) ?: break
                    if (f.type == Framing.TYPE_BYE) break
                    val env = Framing.decodeEnvelope(f.payload)!!
                    received += env
                    Framing.write(output, Framing.TYPE_ACK, Framing.encodeSeq(env.first))
                }
            }
        }
        val acked = mutableListOf<Long>()
        Socket(server.inetAddress, server.localPort).use { s ->
            val output = s.getOutputStream().buffered()
            val input = s.getInputStream().buffered()
            bodies.forEachIndexed { i, b -> Framing.write(output, Framing.TYPE_ENVELOPE, Framing.encodeEnvelope(i + 1L, b)) }
            repeat(bodies.size) { acked += Framing.decodeSeq(Framing.read(input)!!.payload)!! }
            Framing.write(output, Framing.TYPE_BYE, ByteArray(0))
        }
        peer.join(5_000)
        server.close()
        assertEquals(listOf(1L, 2L, 3L), received.map { it.first })
        bodies.forEachIndexed { i, b -> assertArrayEquals(b, received[i].second) }
        assertEquals(listOf(1L, 2L, 3L), acked)
    }

    private fun expectFramingError(bytes: ByteArray) {
        try {
            Framing.read(ByteArrayInputStream(bytes))
            fail("accepted a broken frame: ${bytes.toList()}")
        } catch (e: Framing.FramingException) { /* expected */ }
    }

    // ───────────────────────── handshake ─────────────────────────

    private class Identity {
        val pair = OSHICryptoV2.generateX25519()
        val key: String = Base64.getEncoder().encodeToString(pair.pub)
        fun secret(peerKey: String): ByteArray? =
            try { OSHICryptoV2.dh(pair.priv, Base64.getDecoder().decode(peerKey)) } catch (e: Exception) { null }
    }

    private fun nonce(b: Int) = ByteArray(WifiAwareProtocol.NONCE_LENGTH) { (b + it).toByte() }

    /** Run two handshakes against each other, frame by frame, as the socket would. */
    private fun run(a: Handshake, b: Handshake): Pair<Handshake.Result?, Handshake.Result?> {
        val toB = ArrayDeque(listOf(a.start()))
        val toA = ArrayDeque(listOf(b.start()))
        var ra: Handshake.Result? = null
        var rb: Handshake.Result? = null
        repeat(10) {
            if (rb !is Handshake.Result.Verified && rb !is Handshake.Result.Failed) toB.removeFirstOrNull()?.let { f ->
                rb = b.onFrame(f).also { r -> (r as? Handshake.Result.Continue)?.send?.let(toA::addAll) }
            }
            if (ra !is Handshake.Result.Verified && ra !is Handshake.Result.Failed) toA.removeFirstOrNull()?.let { f ->
                ra = a.onFrame(f).also { r -> (r as? Handshake.Result.Continue)?.send?.let(toB::addAll) }
            }
        }
        return ra to rb
    }

    @Test fun `two honest phones verify each other`() {
        val alice = Identity(); val bob = Identity()
        val (ra, rb) = run(
            Handshake(alice.key, "Alice", nonce(1), alice::secret),
            Handshake(bob.key, "Bob", nonce(2), bob::secret),
        )
        val va = ra as Handshake.Result.Verified
        val vb = rb as Handshake.Result.Verified
        assertEquals(bob.key, va.peerPublicKey)
        assertEquals("Bob", va.peerName)
        assertEquals(alice.key, vb.peerPublicKey)
        assertEquals("Alice", vb.peerName)
    }

    @Test fun `an impostor announcing a contact's key is refused`() {
        val alice = Identity(); val bob = Identity(); val mallory = Identity()
        // Mallory claims Bob's key but only holds her own private key.
        val (ra, _) = run(
            Handshake(alice.key, "Alice", nonce(1), alice::secret),
            Handshake(bob.key, "Bob", nonce(2)) { peer -> mallory.secret(peer) },
        )
        assertTrue("got $ra", ra is Handshake.Result.Failed)
    }

    @Test fun `a reflected nonce cannot be turned into a proof for someone else's key`() {
        val alice = Identity(); val bob = Identity()
        val aliceNonce = nonce(5)
        // Mallory opens a SECOND connection to Alice, claiming Bob's key and
        // sending Alice's own challenge from the first connection as her nonce.
        val second = Handshake(alice.key, "Alice", nonce(9), alice::secret)
        second.start()
        val mal = WifiAwareProtocol.encodeIdent(WifiAwareProtocol.Ident(bob.key, "Bob", aliceNonce))
        val answer = second.onFrame(Framing.Frame(Framing.TYPE_IDENT, mal)) as Handshake.Result.Continue
        val reflectedTag = answer.send.single().payload
        // ...and replays Alice's answer to Alice on the first connection.
        val first = Handshake(alice.key, "Alice", aliceNonce, alice::secret)
        first.start()
        first.onFrame(Framing.Frame(Framing.TYPE_IDENT,
            WifiAwareProtocol.encodeIdent(WifiAwareProtocol.Ident(bob.key, "Bob", nonce(40)))))
        val r = first.onFrame(Framing.Frame(Framing.TYPE_PROOF, reflectedTag))
        assertTrue("reflection must fail, got $r", r is Handshake.Result.Failed)
    }

    @Test fun `protocol violations fail the handshake`() {
        val alice = Identity(); val bob = Identity()
        fun fresh() = Handshake(alice.key, "Alice", nonce(1), alice::secret).also { it.start() }
        val bobIdent = Framing.Frame(Framing.TYPE_IDENT,
            WifiAwareProtocol.encodeIdent(WifiAwareProtocol.Ident(bob.key, "Bob", nonce(2))))

        assertTrue(fresh().onFrame(Framing.Frame(Framing.TYPE_PROOF, ByteArray(32))) is Handshake.Result.Failed)
        assertTrue(fresh().onFrame(Framing.Frame(Framing.TYPE_ENVELOPE, ByteArray(8))) is Handshake.Result.Failed)
        assertTrue(fresh().onFrame(Framing.Frame(Framing.TYPE_IDENT, byteArrayOf(1, 2, 3))) is Handshake.Result.Failed)
        // Ourselves.
        val selfIdent = Framing.Frame(Framing.TYPE_IDENT,
            WifiAwareProtocol.encodeIdent(WifiAwareProtocol.Ident(alice.key, "Alice", nonce(3))))
        assertTrue(fresh().onFrame(selfIdent) is Handshake.Result.Failed)
        // Our own nonce echoed back.
        val echo = Framing.Frame(Framing.TYPE_IDENT,
            WifiAwareProtocol.encodeIdent(WifiAwareProtocol.Ident(bob.key, "Bob", nonce(1))))
        assertTrue(fresh().onFrame(echo) is Handshake.Result.Failed)
        // A key we cannot compute a secret for.
        val noSecret = Handshake(alice.key, "Alice", nonce(1)) { null }
        assertTrue(noSecret.onFrame(bobIdent) is Handshake.Result.Failed)
        // Two IDENTs.
        val twice = fresh()
        assertTrue(twice.onFrame(bobIdent) is Handshake.Result.Continue)
        assertTrue(twice.onFrame(bobIdent) is Handshake.Result.Failed)
        // Wrong tag length.
        val shortTag = fresh()
        shortTag.onFrame(bobIdent)
        assertTrue(shortTag.onFrame(Framing.Frame(Framing.TYPE_PROOF, ByteArray(31))) is Handshake.Result.Failed)
    }

    @Test fun `ident round-trips and rejects trailing junk`() {
        val ident = WifiAwareProtocol.Ident("K".repeat(44), "Zoé 🌍", nonce(7))
        val bytes = WifiAwareProtocol.encodeIdent(ident)
        assertEquals(ident, WifiAwareProtocol.decodeIdent(bytes))
        assertNull(WifiAwareProtocol.decodeIdent(bytes + 0))
        assertNull(WifiAwareProtocol.decodeIdent(bytes.copyOf(bytes.size - 1)))
        assertNotNull(WifiAwareProtocol.decodeIdent(WifiAwareProtocol.encodeIdent(ident.copy(displayName = ""))))
    }
}
