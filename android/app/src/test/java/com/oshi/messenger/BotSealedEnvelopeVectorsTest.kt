package com.oshi.messenger

import com.oshi.messenger.network.bot.BotSealedEnvelope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * __BOT_E2E_2026_09_23__ `ServerPatches/bot_e2e/test/vectors.json` embedded as literals
 * (spec BOT_SEAL_SPEC.md §2): both recipients open the same envelope byte for byte, and
 * every tampering is refused.
 */
class BotSealedEnvelopeVectorsTest {

    private val messageId = "6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f60"
    private val innerJson = "{\"type\":\"bot_message\",\"messageId\":\"6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f60\",\"botToken\":\"abcd1234...\",\"botName\":\"Vector Bot\",\"groupId\":\"3F2504E0-4F89-11D3-9A0C-0305E82C3301\",\"groupName\":\"Vectors\",\"content\":\"héllo 🔐 vectors\",\"timestamp\":\"2026-09-23T12:00:00.000Z\",\"botId\":\"feedfacecafebeef\",\"sealedBy\":\"server\"}"
    private val alicePriv = "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo="
    private val alicePub = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo="
    private val aliceKid = "300c9c9603b92a4b"
    private val bobPriv = "XasIfmJKikt54X+Lg4AO5m87sSkmGLb9HC+LJ/+I4Os="
    private val bobPub = "3p7bfXt9wbTTW2HC7OQ1Nz+DQ8hbeGdNrfx+FG+IK08="
    private val bobKid = "f35e5616160a30bf"
    private val envelope = "bot:6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f60:eyJ0eXBlIjoiYm90X21lc3NhZ2UiLCJ2IjoiYm90LXNlYWwtdjEiLCJtZXNzYWdlSWQiOiI2ZjFjMWIwZS0zZDJhLTRjNTUtOWEzZS0xYjJjM2Q0ZTVmNjAiLCJ0aW1lc3RhbXAiOiIyMDI2LTA5LTIzVDEyOjAwOjAwLjAwMFoiLCJib3ROYW1lIjoiQm90IiwiZTJlIjpmYWxzZSwic2VhbGVkIjp7InYiOiJib3Qtc2VhbC12MSIsImFsZyI6IlgyNTUxOS1IS0RGLVNIQTI1Ni9BRVMtMjU2LUdDTSIsImJvZHkiOiJvS0dpbzZTbHBxZW9xYXFyblRvSVZEV3VJSVZBQitpbldCZWxyUVBOUG5Xd20yQUIrWDFWNXhqT1BHWHdUR1hKeVJNd0REMnNZZVU2SHJHWWFpOGxmVmY5SXg5eU95WnZ4a0xtZzlDSTRGcFdrOVRDMk1DZGQvdWFvOUdvWlltZTU2RDdHWXBFREttRnRHTzZyYWdKeVd6YXdmcnp2aVJxcDk2SVMxQS9FNDZjV3U4ZUErMjUxdHNnZ2dTaE00cGQ0QVN5SWdkdHU3cy9mdGZ1c2ZTY1d2cmoxN1c4L2Y3WW4vUUVIb01nTGZLRldYQW5vQ1lKUlIyOXc1RTVSNUQ5SUZ5TWFPZmhBbFl1OFQxN0ZsVWpweFMwMVpuUloyS25pbzFlMlQzSkZIZXI4L2pPaUtJc2lpKzErRXRLSlZ0TTNCNnRYcTNNWUdWZ09YQk5QWkR5QnFSZ1E5TmFuNWRhVlFrWW9qa1ArYmUxN283Q0JNd2p6QU1aVnZGZWRHODAzZHVnVDVzR2NMK0ExZUZMdVdSazBVVmpFYWlCTHpjT3lZMzFPNnBodXljcEc0ZTM5bmw5VXZYQ0plQmFrSWVXNmpZPSIsIndyYXBzIjp7IjMwMGM5Yzk2MDNiOTJhNGIiOiJjTlI1Tm1DUG9kYVlIazRzMUI4NFlYUll0MytCc2hwMUpKeDJBcDRkN3o2d3NiS3p0TFcydDdpNXVydmx2aWF2cThKdFN1WUFHMXZYVElvUkpPdDU0Y2hjV2JiOG11N2tuUFQ3Mk9GdUlvUURwNkx5YjJLV2Y5cDZYb009IiwiZjM1ZTU2MTYxNjBhMzBiZiI6ImF6N21kR05ZUEw0OXdJL3AwSFpjSm1iL1VoRGRVbnlkaHdYa1NTZklEVlhBd2NMRHhNWEd4OGpKeXN1ZXY1QU1jc3JRVzYvTDVUbFJ4K0FXN3VPRVp4Smw1ZUFyaGQraEg4TE53NWtUZWlBa2YrdE9sSXpqOGQ2S0ZLUT0ifX0sImJvdElkIjoiZmVlZGZhY2VjYWZlYmVlZiIsImdyb3VwSWQiOiIzRjI1MDRFMC00Rjg5LTExRDMtOUEwQy0wMzA1RTgyQzMzMDEiLCJjb250ZW50Ijoi8J+UkiBFbmNyeXB0ZWQgYm90IG1lc3NhZ2UuIFVwZGF0ZSBPU0hJIHRvIHJlYWQgaXQuIiwiYm90VG9rZW4iOiJhYmNkMTIzNC4uLiJ9"

    private fun b64(s: String) = Base64.getDecoder().decode(s)

    /** Parses the queue string the way VPSClient.processBotEnvelope does. */
    private fun outer(): Pair<String, JSONObject> {
        val parts = envelope.split(":", limit = 3)
        return parts[1] to JSONObject(String(b64(parts[2]), Charsets.UTF_8))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block(); fail("expected OpenException")
        } catch (_: BotSealedEnvelope.OpenException) {
        }
    }

    @Test fun kidsMatchVectors() {
        assertEquals(aliceKid, BotSealedEnvelope.kid(b64(alicePub)))
        assertEquals(bobKid, BotSealedEnvelope.kid(b64(bobPub)))
    }

    @Test fun detectsSealedAndLegacy() {
        val (_, o) = outer()
        assertTrue(BotSealedEnvelope.isSealed(o))
        val legacy = JSONObject(innerJson)
        assertFalse(BotSealedEnvelope.isSealed(legacy))
        assertEquals(BotSealedEnvelope.SealKind.NONE, BotSealedEnvelope.sealKind(legacy, null))
    }

    @Test fun bothRecipientsOpen() {
        val (mid, o) = outer()
        val expected = JSONObject(innerJson)
        for ((priv, pub) in listOf(alicePriv to alicePub, bobPriv to bobPub)) {
            // With the public half derived from the private key, and passed explicitly.
            for (inner in listOf(
                BotSealedEnvelope.open(o, mid, b64(priv)),
                BotSealedEnvelope.open(o, mid, b64(priv), b64(pub))
            )) {
                assertEquals(expected.toString(), JSONObject(inner.toString()).toString())
                assertEquals("héllo 🔐 vectors", inner.getString("content"))
                assertEquals(BotSealedEnvelope.SealKind.SERVER, BotSealedEnvelope.sealKind(o, inner))
            }
        }
    }

    @Test fun rejectsTamperedBody() {
        val (mid, o) = outer()
        val sealed = o.getJSONObject("sealed")
        val body = b64(sealed.getString("body"))
        body[20] = (body[20].toInt() xor 1).toByte()
        sealed.put("body", Base64.getEncoder().encodeToString(body))
        assertRejected { BotSealedEnvelope.open(o, mid, b64(alicePriv)) }
        assertRejected { BotSealedEnvelope.open(o, mid, b64(bobPriv)) }
    }

    @Test fun rejectsTamperedWrap() {
        val (mid, o) = outer()
        val wraps = o.getJSONObject("sealed").getJSONObject("wraps")
        val w = b64(wraps.getString(aliceKid))
        w[50] = (w[50].toInt() xor 1).toByte()
        wraps.put(aliceKid, Base64.getEncoder().encodeToString(w))
        assertRejected { BotSealedEnvelope.open(o, mid, b64(alicePriv)) }
        // Bob's wrap is untouched.
        BotSealedEnvelope.open(o, mid, b64(bobPriv))
    }

    @Test fun rejectsWrongMessageId() {
        val (_, o) = outer()
        assertRejected { BotSealedEnvelope.open(o, "6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f61", b64(alicePriv)) }
        // The AAD binds the string as received: case changes are a different AAD.
        assertRejected { BotSealedEnvelope.open(o, messageId.uppercase(), b64(alicePriv)) }
    }

    @Test fun rejectsMissingWrap() {
        val (mid, o) = outer()
        o.getJSONObject("sealed").getJSONObject("wraps").remove(aliceKid)
        assertRejected { BotSealedEnvelope.open(o, mid, b64(alicePriv)) }
        BotSealedEnvelope.open(o, mid, b64(bobPriv))
        // A key that was never sealed to.
        assertRejected { BotSealedEnvelope.open(o, mid, ByteArray(32) { 7 }) }
    }

    @Test fun rejectsOuterGroupIdMismatch() {
        val (mid, o) = outer()
        // Case-insensitive match passes.
        o.put("groupId", "3f2504e0-4f89-11d3-9a0c-0305e82c3301")
        BotSealedEnvelope.open(o, mid, b64(alicePriv))
        o.put("groupId", "00000000-0000-0000-0000-000000000000")
        assertRejected { BotSealedEnvelope.open(o, mid, b64(alicePriv)) }
    }
}
