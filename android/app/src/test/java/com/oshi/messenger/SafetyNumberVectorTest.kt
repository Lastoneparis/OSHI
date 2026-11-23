package com.oshi.messenger

import com.oshi.messenger.service.SafetyNumberCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety numbers must be byte-identical to iOS `SafetyNumber.generate`
 * (`OSHI/SafetyNumber.swift:21-39`), because iOS's `verify` (`:41-43`) is exact byte
 * equality of the QR payload: anything that differs anywhere can never verify.
 *
 * The expected values below were derived from the SWIFT source (sorted keys, joined
 * with no separator, ONE SHA-256, `byte % 100` over 30 bytes, 12 groups of 5), computed
 * outside this codebase — not read back out of the Kotlin under test.
 */
class SafetyNumberVectorTest {

    private val aliceKey = "dGVzdC1hbGljZS1rZXktMzJieXRlcy1iYXNlNjQ="
    private val bobKey = "dGVzdC1ib2ItcHVibGljLWtleS0zMmJ5dGVzLTE="

    @Test
    fun `matches the iOS vector for a base64 key pair`() {
        assertEquals(
            "490187061099861224338996915126128920988382993901176521575591",
            SafetyNumberCodec.fingerprint(aliceKey, bobKey)
        )
        assertEquals(
            "49018 70610 99861 22433 89969 15126 12892 09883 82993 90117 65215 75591",
            SafetyNumberCodec.displayCode(SafetyNumberCodec.fingerprint(aliceKey, bobKey))
        )
        assertEquals(
            "SAFETY:v1:${aliceKey}${bobKey}",
            SafetyNumberCodec.qrPayload(aliceKey, bobKey)
        )
    }

    /**
     * The all-zero / all-`/` pair is the one that catches a SIGNED-byte modulo: those
     * bytes are 0xFF-heavy, and `byte % 100` on a signed Byte would emit "-…" and
     * desynchronise the whole 60-digit string.
     */
    @Test
    fun `high bytes stay unsigned`() {
        val k1 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val k2 = "/////////////////////////////////////w="
        val fp = SafetyNumberCodec.fingerprint(k1, k2)
        assertEquals("663299970154834468818702686968934008296244198766744727225413", fp)
        assertEquals(60, fp.length)
        assertTrue("every character must be a digit", fp.all { it.isDigit() })
    }

    @Test
    fun `sort order puts the lexicographically smaller key first`() {
        // ASCII: '/'(0x2F) < 'A'(0x41) < 'a'(0x61); the base64 alphabet is ASCII, so
        // Kotlin's String ordering and Swift's agree.
        assertEquals("aaazzz", SafetyNumberCodec.combined("zzz", "aaa"))
        assertEquals(
            "438761365740092220640165584271500724656400668299550810631432",
            SafetyNumberCodec.fingerprint("zzz", "aaa")
        )
    }

    @Test
    fun `both sides of a conversation derive the same number`() {
        assertEquals(
            SafetyNumberCodec.fingerprint(aliceKey, bobKey),
            SafetyNumberCodec.fingerprint(bobKey, aliceKey)
        )
        assertEquals(
            SafetyNumberCodec.qrPayload(aliceKey, bobKey),
            SafetyNumberCodec.qrPayload(bobKey, aliceKey)
        )
    }

    @Test
    fun `the display code is 12 groups of 5`() {
        val groups = SafetyNumberCodec.displayCode(SafetyNumberCodec.fingerprint(aliceKey, bobKey)).split(" ")
        assertEquals(12, groups.size)
        assertTrue(groups.all { it.length == 5 })
    }

    @Test
    fun `verify is byte equality of the scanned payload`() {
        val payload = SafetyNumberCodec.qrPayload(aliceKey, bobKey)
        assertTrue(SafetyNumberCodec.verify(payload, aliceKey, bobKey))
        assertTrue("scanning is order-independent", SafetyNumberCodec.verify(payload, bobKey, aliceKey))
        assertFalse(SafetyNumberCodec.verify(payload, aliceKey, "someone-else"))
        // A payload that carries the fingerprint instead of the keys — the old Android
        // shape — must NOT verify.
        assertFalse(SafetyNumberCodec.verify("$aliceKey:$bobKey:deadbeef", aliceKey, bobKey))
    }
}
