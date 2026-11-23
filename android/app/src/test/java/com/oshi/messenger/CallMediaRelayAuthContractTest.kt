package com.oshi.messenger

import com.oshi.messenger.network.auth.CallPushAuth
import com.oshi.messenger.service.CallKeyEncoding
import com.oshi.messenger.service.RelayAuth
import com.oshi.messenger.service.RelayToken
import com.oshi.messenger.service.UDPRelayClient
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * __CALL_MEDIA_AUTH_2026_09_23__ docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md §7 T1–T3, byte for byte,
 * through the production builders (RelayAuth.relayTokenBody, UDPRelayClient.buildRegisterFrame).
 */
class CallMediaRelayAuthContractTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val identityRaw = ByteArray(32) { 0xAB.toByte() }
    private val peerRaw = ByteArray(32) { 0xCD.toByte() }
    private val identity = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s"
    private val peer = "zc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0"
    private val callId = "00000000-0000-4000-8000-000000000001"

    private val token = RelayToken(
        tokenId = hex("000102030405060708090a0b0c0d0e0f"),
        macKey = ByteArray(32) { 0x11 },
        expiresAtMs = Long.MAX_VALUE, identity = identity, peer = peer, callId = callId,
    )

    private val t2 = "042b7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63302b713675727136757271367572713675727136757271367572713675727136757271367572713675727136732430303030303030302d303030302d343030302d383030302d3030303030303030303030314f524131000102030405060708090a0b0c0d0e0f0000000000000001efcd414ee50798f79de486aaad32cdee"
    private val t3 = "052b7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63302b713675727136757271367572713675727136757271367572713675727136757271367572713675727136732430303030303030302d303030302d343030302d383030302d3030303030303030303030314f524131000102030405060708090a0b0c0d0e0f000000000000000218b0e57904a1ab0168acbcc9faf527f3"

    @Test
    fun `key spelling is one helper - std base64, base64url and hex agree`() {
        val std = Base64.getEncoder().encodeToString(identityRaw)
        assertEquals(identity, CallKeyEncoding.toBase64url(std))
        assertEquals(identity, CallKeyEncoding.toBase64url(identity))
        assertEquals(identity, CallKeyEncoding.toBase64url(identityRaw.hex()))
        assertEquals(peer, CallKeyEncoding.toBase64url(Base64.getEncoder().encodeToString(peerRaw)))
    }

    @Test
    fun `T1 relay-token body, hash and signature`() {
        val body = RelayAuth.relayTokenBody(identity, peer, callId)
        assertEquals(
            """{"identity":"$identity","peer":"$peer","callId":"$callId"}""",
            String(body, Charsets.UTF_8),
        )
        assertEquals(
            "d7d96a32479808485bfa046c6f90958321a4efa2df1e1e995f321f0826ea9fa0",
            MessageDigest.getInstance("SHA-256").digest(body).hex(),
        )
        assertEquals(
            "POST\n/api/call/relay-token\nd7d96a32479808485bfa046c6f90958321a4efa2df1e1e995f321f0826ea9fa0\n1790000000000",
            CallPushAuth.canonical("POST", "/api/call/relay-token", body, "1790000000000"),
        )
        val priv = Ed25519PrivateKeyParameters(hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"), 0)
        val sign: (ByteArray) -> ByteArray = { msg ->
            Ed25519Signer().run { init(true, priv); update(msg, 0, msg.size); generateSignature() }
        }
        val h = CallPushAuth.headers("POST", "/api/call/relay-token", body, 1790000000000L, priv.generatePublicKey().encoded, sign)
        assertEquals(
            "MMiJUgXMwpDFQR7ZDZRWFZ4TathkQYx2kBOSE8l67niXpMK9nASLpki4SBcIFA4ta9NYyIpenRqvfzxs9GZYCA==",
            h[CallPushAuth.HEADER_SIGNATURE],
        )
        assertTrue(CallPushAuth.covers("/api/call/relay-token"))
    }

    @Test
    fun `T2 then T3 - authenticated register counter 1 then 2 from the real frame builder`() {
        val client = UDPRelayClient()
        // Keys handed in standard base64, as EnhancedCallManager stores them.
        client.primeForTest(
            Base64.getEncoder().encodeToString(identityRaw),
            Base64.getEncoder().encodeToString(peerRaw),
            callId, token,
        )
        val first = client.buildRegisterFrame()!!
        assertEquals(170, first.size)
        assertEquals(t2, first.hex())
        // T3 is the 0x05 keepalive with counter 2: the 2nd trailer the client emits carries
        // counter 2 and differs from T3 only by the type byte (and hence the mac).
        val second = client.buildRegisterFrame()!!
        assertEquals("0000000000000002", second.copyOfRange(146, 154).hex())
        val t3Header = second.copyOfRange(0, 126).also { it[0] = 0x05 }
        assertEquals(t3, RelayAuth.sealRegister(t3Header, token.tokenId, token.macKey, 2).hex())
    }

    @Test
    fun `new token restarts the counter at 1, no token-less register once a token was held`() {
        val client = UDPRelayClient()
        client.primeForTest(identity, peer, callId, token)
        client.buildRegisterFrame(); client.buildRegisterFrame()
        val fresh = RelayToken(ByteArray(16) { 7 }, ByteArray(32) { 9 }, Long.MAX_VALUE, identity, peer, callId)
        client.primeForTest(identity, peer, callId, fresh)
        assertEquals("0000000000000001", client.buildRegisterFrame()!!.copyOfRange(146, 154).hex())
    }

    @Test
    fun `without any token a legacy register is the bare header`() {
        val client = UDPRelayClient()
        client.primeForTest(identity, peer, callId, null)
        assertEquals(126, client.buildRegisterFrame()!!.size)
    }

    @Test
    fun `ack is exactly 04 01 and nacks are 04 00 code`() {
        assertTrue(RelayAuth.isAck(byteArrayOf(4, 1)))
        assertFalse(RelayAuth.isAck(byteArrayOf(4, 0, 1)))
        assertNull(RelayAuth.nackCode(byteArrayOf(4, 1)))
        for (c in 1..6) assertEquals(c, RelayAuth.nackCode(byteArrayOf(4, 0, c.toByte())))
    }

    @Test
    fun `token response parses and never prints the mac key`() {
        val body = """{"tokenId":"AAECAwQFBgcICQoLDA0ODw","macKey":"ERERERERERERERERERERERERERERERERERERERERERE","expiresAt":1790014400000,"ttlMs":14400000,"udpPort":8089,"mode":"dual","v":1}"""
        val t = RelayToken.fromResponse(body, identity, peer, callId)
        assertNotNull(t)
        assertArrayEquals(token.tokenId, t!!.tokenId)
        assertArrayEquals(token.macKey, t.macKey)
        assertEquals(1790014400000L, t.expiresAtMs)
        assertFalse(t.toString().contains("ERERER"))
        assertFalse(t.needsRefresh(1790014400000L - 11 * 60_000L))
        assertTrue(t.needsRefresh(1790014400000L - 9 * 60_000L))
        assertNull(RelayToken.fromResponse("{\"tokenId\":\"AA\"}", identity, peer, callId))
    }
}
