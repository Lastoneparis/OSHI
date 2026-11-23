package com.oshi.messenger.service

import org.junit.Assert.*
import org.junit.Test

class CovertPayloadCodecTest {
    @Test fun `DNS boundary round trips without truncation`() {
        val payload = "a".repeat(31)
        assertEquals(payload, CovertPayloadCodec.decodeDnsName(CovertPayloadCodec.dnsName(payload, "example.com")))
        assertTrue(runCatching { CovertPayloadCodec.dnsName("a".repeat(32), "example.com") }.isFailure)
        assertTrue(runCatching { CovertPayloadCodec.dnsName("é".repeat(16), "example.com") }.isFailure)
        assertEquals("é".repeat(15), CovertPayloadCodec.decodeDnsName(CovertPayloadCodec.dnsName("é".repeat(15), "example.com")))
    }
    @Test fun `DNS rejects invalid hosts and malformed carriers`() {
        for (name in listOf("", "a..com", "-host.com", "a.com/path", "a".repeat(64) + ".com")) {
            assertFalse(name, CovertPayloadCodec.validDnsName(name))
        }
        assertNull(CovertPayloadCodec.decodeDnsName("61.other.example.com"))
        assertNull(CovertPayloadCodec.decodeDnsName("1.covert.example.com"))
        assertNull(CovertPayloadCodec.decodeDnsName("ff.covert.example.com"))
        assertTrue(runCatching { CovertPayloadCodec.dnsName("a".repeat(31), ("a".repeat(63) + ".").repeat(3) + "com") }.isFailure)
    }
    @Test fun `hex decoder rejects odd digits invalid UTF8 and oversized data`() {
        assertEquals("Hello", CovertPayloadCodec.decodeHex("48656c6c6f", 5))
        for (hex in listOf("a", "gg", "c3", "ff", "616263")) assertNull(hex, CovertPayloadCodec.decodeHex(hex, 2))
    }
    @Test fun `image decoder requires every claimed bit to fit`() {
        assertFalse(CovertPayloadCodec.imagePayloadFits(13, 1))
        assertTrue(CovertPayloadCodec.imagePayloadFits(14, 1))
        assertFalse(CovertPayloadCodec.imagePayloadFits(100, 1000))
        assertFalse(CovertPayloadCodec.imagePayloadFits(Long.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(CovertPayloadCodec.imagePayloadFits(100, -1))
    }
}
