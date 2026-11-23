package com.oshi.messenger.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoverCodecTest {
    @Test
    fun eventCarrierRoundTripsTheSignedV2RequestRecord() {
        val request = JSONObject()
            .put("v", 1)
            .put("method", "POST")
            .put("path", "/v2/messages")
            .put("headers", JSONObject().put("x-oshi-signature", "signature"))
            .put("body", "cGF5bG9hZA==")

        val carrier = CoverCodec.encode(request, "event")
        val decoded = CoverCodec.decode(carrier.body, carrier.type)

        assertEquals("POST", decoded.getString("method"))
        assertEquals("/v2/messages", decoded.getString("path"))
        assertEquals("cGF5bG9hZA==", decoded.getString("body"))
    }

    @Test
    fun profileRotationOnlyEmitsAcceptedStrictSchemas() {
        val request = JSONObject().put("v", 1).put("body", "cGF5bG9hZA==")
        var sawEvent = false
        var sawBatch = false
        repeat(64) {
            val carrier = CoverCodec.encode(request, "event")
            val json = JSONObject(String(carrier.body, Charsets.UTF_8))
            sawEvent = sawEvent || json.has("events")
            sawBatch = sawBatch || json.has("batch")
            assertEquals("cGF5bG9hZA==", CoverCodec.decode(carrier.body, carrier.type).getString("body"))
        }
        assertTrue("event profile was never selected", sawEvent)
        assertTrue("batch profile was never selected", sawBatch)
    }

    @Test
    fun framingUsesCoarseSharedBuckets() {
        val small = CoverCodec.frame(JSONObject().put("v", 1).put("body", "x"))
        val medium = CoverCodec.frame(JSONObject().put("v", 1).put("body", "x".repeat(5_000)))
        assertEquals(4 * 1024, small.size)
        assertEquals(16 * 1024, medium.size)
        assertEquals(1, CoverCodec.unframe(small).getInt("v"))
    }

    @Test
    fun finalCarrierRetainsTheCoarseLengthClass() {
        // Since 2026-11-13 the frame bucket may be spread one step up and the
        // inner member order varies, but within one (profile, bucket) class the
        // TLS-visible body length must still not depend on the payload length.
        val shortRequest = JSONObject().put("v", 1).put("body", "x")
        val longRequest = JSONObject().put("v", 1).put("body", "x".repeat(3_000))
        val lengthsByClass = mutableMapOf<String, MutableSet<Int>>()
        repeat(256) {
            for (request in listOf(shortRequest, longRequest)) {
                val carrier = CoverCodec.encode(request, "event")
                val root = JSONObject(String(carrier.body, Charsets.UTF_8))
                val profile = if (root.has("events")) "event" else "batch"
                val data = if (root.has("events")) root.getJSONArray("events").getJSONObject(0).getJSONObject("params").getString("data")
                    else root.getJSONArray("batch").getJSONObject(0).getString("payload")
                val bucket = java.util.Base64.getDecoder().decode(data).size
                lengthsByClass.getOrPut("$profile/$bucket") { mutableSetOf() }.add(carrier.body.size)
            }
        }
        assertEquals(setOf("event/4096", "batch/4096", "event/16384", "batch/16384"), lengthsByClass.keys)
        lengthsByClass.forEach { (cls, sizes) ->
            assertEquals("$cls leaked a within-bucket message size", 1, sizes.size)
        }
    }

    @Test
    fun endpointRotationOnlyUsesTheStrictRelayAliasSet() {
        assertEquals(setOf("/v2/cover", "/v2/collect", "/v2/metrics"), CoverCodec.ENDPOINT_PATHS.toSet())
        val seen = mutableSetOf<String>()
        repeat(64) { seen += CoverCodec.selectEndpointPath() }
        assertEquals(CoverCodec.ENDPOINT_PATHS.toSet(), seen)
    }

    @Test
    fun rejectsFramesOutsideTheSharedPaddingBuckets() {
        try {
            CoverCodec.unframe(ByteArray(4 * 1024 + 1))
            fail("non-bucket frame must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsMalformedBase64Carrier() {
        val body = JSONObject().put("events", org.json.JSONArray().put(
            JSONObject().put("name", "page_view").put("params", JSONObject().put("data", "%"))
        )).toString().toByteArray(Charsets.UTF_8)
        try {
            CoverCodec.decode(body, "application/json")
            fail("malformed Base64 must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsUnknownOuterCarrierFields() {
        val carrier = CoverCodec.encode(JSONObject().put("v", 1).put("body", "cGF5bG9hZA=="), "event")
        val outer = JSONObject(String(carrier.body, Charsets.UTF_8)).put("unreviewedExtension", "reject-me")
        try {
            CoverCodec.decode(outer.toString().toByteArray(Charsets.UTF_8), carrier.type)
            fail("unknown outer field must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
