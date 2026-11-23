package com.oshi.messenger.network.ohttp

import com.oshi.messenger.network.ohttp.Ohttp.hex
import com.oshi.messenger.network.ohttp.Ohttp.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BhttpTest {

    @Test fun varintBoundaries() {
        // RFC 9000 §16 / Appendix A.1 examples and every length boundary.
        val cases = mapOf(
            0L to "00", 37L to "25", 63L to "3f",
            64L to "4040", 15293L to "7bbd", 16383L to "7fff",
            16384L to "80004000", 494878333L to "9d7f3e7d", 1073741823L to "bfffffff",
            1073741824L to "c000000040000000", 151288809941952652L to "c2197c5eff14e88c",
            Bhttp.MAX_VARINT to "ffffffffffffffff",
        )
        for ((v, h) in cases) {
            assertEquals("encode $v", h, toHex(Bhttp.encodeVarint(v)))
            assertEquals("decode $h", v, Bhttp.Reader(hex(h)).varint())
        }
        // Non-minimal encodings are legal to decode.
        assertEquals(37L, Bhttp.Reader(hex("4025")).varint())
        for (bad in listOf(-1L, Bhttp.MAX_VARINT + 1)) {
            try { Bhttp.encodeVarint(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
        try { Bhttp.Reader(hex("80ff")).varint(); fail("truncated varint accepted") } catch (_: Bhttp.FormatException) {}
    }

    @Test fun requestRoundTrip() {
        val body = ByteArray(300) { it.toByte() } // > 63 bytes: 2-byte length
        val r = Bhttp.Request("POST", "https", "oshi-messenger.com", "/v2/messages?after=1",
            listOf("content-type" to "application/json", "x-oshi-signature" to "a+b/c=="), body)
        val bytes = Bhttp.encodeRequest(r)
        assertEquals(0, bytes[0].toInt())
        val back = Bhttp.decodeRequest(bytes)
        assertEquals(r.method, back.method); assertEquals(r.scheme, back.scheme)
        assertEquals(r.authority, back.authority); assertEquals(r.path, back.path)
        assertEquals(r.headers, back.headers); assertArrayEquals(body, back.content)
        assertEquals(emptyList<Pair<String, String>>(), back.trailers)
        // Trailing zero padding is allowed (RFC 9292 §3.8).
        assertEquals(r.path, Bhttp.decodeRequest(bytes + ByteArray(5)).path)
    }

    @Test fun headerNamesAreLowercased() {
        val bytes = Bhttp.encodeRequest(Bhttp.Request("GET", "https", "a", "/", listOf("X-OSHI-User" to "K")))
        assertEquals("x-oshi-user", Bhttp.decodeRequest(bytes).headers.single().first)
    }

    @Test fun exactRequestEncodingForEmptyGet() {
        // RFC 9292 known-length GET with all sections present (empty).
        val bytes = Bhttp.encodeRequest(Bhttp.Request("GET", "https", "example.com", "/"))
        assertEquals("00034745540568747470730b6578616d706c652e636f6d012f" + "000000", toHex(bytes))
    }

    @Test fun responseRoundTripAndInformationalSkipped() {
        val r = Bhttp.Response(404, listOf("content-type" to "application/json"), "{\"e\":1}".toByteArray())
        val enc = Bhttp.encodeResponse(r)
        val back = Bhttp.decodeResponse(enc)
        assertEquals(404, back.status); assertEquals("application/json", back.header("Content-Type"))
        assertEquals("{\"e\":1}", String(back.content))
        // 103 Early Hints (with a header) then the final 200: the 1xx is skipped.
        val hints = hex("01" + "4067" + "0c" + "046c696e6b" + "062f612e637373") // link: /a.css (1+4+1+6 = 12)
        val final = hex("40c8" + "00" + "02" + "6869" + "00")
        val parsed = Bhttp.decodeResponse(hints + final)
        assertEquals(200, parsed.status); assertEquals("hi", String(parsed.content))
    }

    @Test fun truncatedResponseFromRfcVectorDecodes() {
        val r = Bhttp.decodeResponse(hex("0140c8"))
        assertEquals(200, r.status); assertEquals(0, r.content.size)
    }

    @Test fun malformedInputsAreRejected() {
        val bad = listOf(
            "01",                // missing status
            "0140c80510",        // header section length 5 > remaining
            "0140c800000001",    // non-zero trailing byte
            "02",                // indeterminate-length framing: unsupported
        )
        for (h in bad) {
            try { Bhttp.decodeResponse(hex(h)); fail("accepted $h") } catch (_: IllegalArgumentException) {}
        }
        try { Bhttp.decodeRequest(hex("0140c8")); fail("response decoded as request") } catch (_: IllegalArgumentException) {}
        try { Bhttp.decodeResponse(hex("01" + "4258")); fail("status 600 accepted") } catch (_: IllegalArgumentException) {}
    }
}
