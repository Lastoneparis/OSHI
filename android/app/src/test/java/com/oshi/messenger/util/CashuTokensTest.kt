package com.oshi.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Golden payloads, not round trips.
 *
 * A decoder tested only against its own encoder passes while both share the
 * same misunderstanding, which is exactly how a token would render with the
 * wrong amount. So the vectors below are built from the values published in
 * NUT-00 (https://github.com/cashubtc/nuts/blob/main/00.md), and each one is
 * anchored to the **serialized prefix the spec itself prints**. If the field
 * order, the JSON whitespace or the CBOR framing drifts, the prefix assertion
 * fails before any decoding is even attempted.
 */
class CashuTokensTest {

    // ── V3 ───────────────────────────────────────────────────────────────────

    /** NUT-00's V3 example, assembled from its published field values. */
    private val v3Json = """
        {"token":[{"mint":"https://8333.space:3338","proofs":[
        {"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",
        "C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"},
        {"amount":8,"secret":"fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be",
        "C":"029e8e5050b890a7d6c0968db16bc1d5d5fa040ea1de284f6ec69d61299f671059","id":"009a1f293253e41e"}
        ]}],"unit":"sat","memo":"Thank you."}
    """.trimIndent().replace("\n", "")

    private fun b64url(bytes: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private val v3Token = "cashuA" + b64url(v3Json.toByteArray(Charsets.UTF_8))

    @Test
    fun `the V3 vector matches the prefix the spec prints`() {
        // From NUT-00's own serialized example. This is the external anchor:
        // it pins "token" first, "mint" first, and whitespace removed.
        assertTrue(
            "V3 serialization drifted from the spec: $v3Token",
            v3Token.startsWith("cashuAeyJ0b2tlbiI6W3sibWludCI6")
        )
    }

    @Test
    fun `V3 decodes to the published amounts, secrets and signatures`() {
        val t = CashuTokens.decode(v3Token)!!
        assertEquals('A', t.version)
        assertEquals("sat", t.unit)
        assertEquals("Thank you.", t.memo)
        assertEquals(listOf("https://8333.space:3338"), t.mints)
        assertEquals(10L, t.totalAmount)
        assertEquals(2, t.proofCount)

        val proofs = t.entries.single().proofs
        assertEquals(2L, proofs[0].amount)
        assertEquals(
            "407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",
            proofs[0].secret
        )
        assertEquals(
            "02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea",
            proofs[0].signature
        )
        assertEquals("009a1f293253e41e", proofs[0].keysetId)
        assertEquals(8L, proofs[1].amount)
        assertEquals(
            "fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be",
            proofs[1].secret
        )
    }

    // ── V4 ───────────────────────────────────────────────────────────────────

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    private fun text(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size < 24) { "only short text strings are hand-encoded here" }
        return "%02x".format(0x60 or b.size) + b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun longText(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size in 24..255)
        return "78%02x".format(b.size) + b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * NUT-00's V4 example, hand-encoded byte by byte from the CBOR spec.
     *
     * The keyset ids, secrets and signatures are the spec's, verbatim. The three
     * `a` amounts are chosen here, because the published excerpt lists the
     * secrets and signatures of that example but not its amounts — so they are
     * this test's own data and are only ever asserted against themselves.
     */
    private val v4Cbor: ByteArray = hex(
        "a3" +
          text("t") + "82" +
            "a2" +
              text("i") + "48" + "00ffd48b8f5ecf80" +
              text("p") + "81" +
                "a3" +
                  text("a") + "01" +
                  text("s") + longText("acc12435e7b8484c3cf1850149218af90f716a52bf4a5ed347e48ecc13f77388") +
                  text("c") + "5821" + "0244538319de485d55bed3b29a642bee5879375ab9e7a620e11e48ba482421f3cf" +
            "a2" +
              text("i") + "48" + "00ad268c4d1f5826" +
              text("p") + "82" +
                "a3" +
                  text("a") + "02" +
                  text("s") + longText("1323d3d4707a58ad2e23ada4e9f1f49f5a5b4ac7b708eb0d61f738f48307e8ee") +
                  text("c") + "5821" + "023456aa110d84b4ac747aebd82c3b005aca50bf457ebd5737a4414fac3ae7d94d" +
                "a3" +
                  text("a") + "01" +
                  text("s") + longText("56bcbcbb7cc6406b3fa5d57d2174f4eff8b4402b176926d3a57d3c3dcbb59d57") +
                  text("c") + "5821" + "0273129c5719e599379a974a626363c333c56cafc0e6d01abe46d5808280789c63" +
          text("m") + text("http://localhost:3338") +
          text("u") + text("sat")
    )

    private val v4Token = "cashuB" + b64url(v4Cbor)

    @Test
    fun `the V4 vector matches the CBOR framing the spec prints`() {
        // NUT-00's own serialized V4 example begins here. It pins the map
        // arity, the "t"-first key order, the 8-byte short keyset id and the
        // array lengths — the four things easiest to get silently wrong.
        assertTrue(
            "V4 CBOR framing drifted from the spec: $v4Token",
            v4Token.startsWith("cashuBo2F0gqJhaUgA_9SLj17PgGFwgaNh")
        )
    }

    @Test
    fun `V4 decodes every keyset, secret and signature`() {
        val t = CashuTokens.decode(v4Token)!!
        assertEquals('B', t.version)
        assertEquals("sat", t.unit)
        assertNull(t.memo)
        assertEquals(listOf("http://localhost:3338"), t.mints)
        assertEquals(3, t.proofCount)
        assertEquals(4L, t.totalAmount)

        val proofs = t.entries.single().proofs
        assertEquals("00ffd48b8f5ecf80", proofs[0].keysetId)
        assertEquals(
            "acc12435e7b8484c3cf1850149218af90f716a52bf4a5ed347e48ecc13f77388",
            proofs[0].secret
        )
        assertEquals(
            "0244538319de485d55bed3b29a642bee5879375ab9e7a620e11e48ba482421f3cf",
            proofs[0].signature
        )
        // The second keyset's proofs keep their own id, not the first one's.
        assertEquals("00ad268c4d1f5826", proofs[1].keysetId)
        assertEquals("00ad268c4d1f5826", proofs[2].keysetId)
        assertEquals(
            "0273129c5719e599379a974a626363c333c56cafc0e6d01abe46d5808280789c63",
            proofs[2].signature
        )
    }

    // ── Finding tokens in a message ──────────────────────────────────────────

    @Test
    fun `a token is found inside ordinary prose`() {
        val body = "here you go $v3Token enjoy"
        val matches = CashuTokens.find(body)
        assertEquals(1, matches.size)
        assertEquals(10L, matches[0].token.totalAmount)
        assertEquals(body.indexOf("cashuA"), matches[0].start)
        assertEquals(body.indexOf("cashuA") + v3Token.length, matches[0].end)
    }

    @Test
    fun `the cashu URI form is accepted and the raw token is kept for redeeming`() {
        val t = CashuTokens.decode("cashu:$v3Token")!!
        assertEquals(10L, t.totalAmount)
        // redeemUri must be handed to a wallet unchanged, prefix included once.
        assertEquals("cashu:$v3Token", t.redeemUri)
    }

    @Test
    fun `talking about cashu is not a token`() {
        // The whole point of refusing to report what cannot be decoded: a bubble
        // must never offer to redeem a sentence.
        assertNull(CashuTokens.decode("cashuA"))
        assertNull(CashuTokens.decode("cashuAnotbase64!!!"))
        assertNull(CashuTokens.decode("cashuC" + b64url("whatever".toByteArray())))
        assertTrue(CashuTokens.find("have you tried cashuA tokens? cashuBsomething").isEmpty())
    }

    @Test
    fun `an empty or proofless token is refused rather than shown as zero`() {
        // A bubble reading "0 sat" would look like a real, worthless payment.
        assertNull(CashuTokens.decode("cashuA" + b64url("""{"token":[]}""".toByteArray())))
        assertNull(
            CashuTokens.decode(
                "cashuA" + b64url("""{"token":[{"mint":"https://m.example","proofs":[]}]}""".toByteArray())
            )
        )
    }

    // ── Bounds: the same ones bitchat's decoder enforces ────────────────────

    @Test
    fun `the cashu URI scheme form and percent encoding are both accepted`() {
        // Copied out of a browser or another app, a token arrives wearing a
        // scheme and sometimes percent-encoded. Refusing those makes a real
        // token fail for a reason the user cannot see.
        assertEquals(10L, CashuTokens.decode("cashu://$v3Token")!!.totalAmount)
        assertEquals(10L, CashuTokens.decode("cashu:$v3Token")!!.totalAmount)
        assertEquals(10L, CashuTokens.decode("cashu%3A$v3Token".replace("cashu%3A", "cashu:"))!!.totalAmount)
        assertEquals("cashuA", CashuTokens.bareToken("cashu://$v3Token")!!.take(6))
        assertNull(CashuTokens.bareToken("not a token at all"))
    }

    @Test
    fun `an absurdly long token is refused before it is decoded`() {
        val huge = "cashuA" + "a".repeat(CashuTokens.MAX_TOKEN_LENGTH + 1)
        assertNull(CashuTokens.bareToken(huge))
        assertNull(CashuTokens.decode(huge))
    }

    @Test
    fun `an amount past every satoshi that will ever exist is refused`() {
        // Not a payment — a bubble rendering it as one would be a lie with a
        // number in it.
        val absurd = """{"token":[{"mint":"https://m.example","proofs":[
            {"amount":9999999999999999,"secret":"s","C":"02aa","id":"00aa"}]}],"unit":"sat"}"""
            .trimIndent().replace("\n", "")
        assertNull(CashuTokens.decode("cashuA" + b64url(absurd.toByteArray())))
    }

    @Test
    fun `strict refuses a zero-amount token, lenient still previews it`() {
        val zero = """{"token":[{"mint":"https://m.example","proofs":[
            {"amount":0,"secret":"s","C":"02aa","id":"00aa"}]}],"unit":"sat"}"""
            .trimIndent().replace("\n", "")
        val t = "cashuA" + b64url(zero.toByteArray())
        // /pay must never put a worthless token on the wire…
        assertNull(CashuTokens.decode(t, strict = true))
        // …but a received one is still shown, so nobody wonders why nothing rendered.
        assertNotNull(CashuTokens.decode(t))
    }

    @Test
    fun `hostile CBOR is refused, not crashed on`() {
        // A map header claiming four billion entries must cost nothing.
        assertNull(CashuTokens.decode("cashuB" + b64url(hex("bbffffffffffffffff"))))
        // Indefinite-length items are not part of the token format.
        assertNull(CashuTokens.decode("cashuB" + b64url(hex("bf61746100ff"))))
        // Trailing bytes after a complete item.
        assertNotNull(CashuTokens.decode(v4Token))
        assertNull(CashuTokens.decode("cashuB" + b64url(v4Cbor + hex("00"))))
    }
}
