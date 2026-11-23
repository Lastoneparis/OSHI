package com.oshi.messenger.network.ohttp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** __OHTTP_RELAY_ADDRESS_2026_11_10__ what a user may enter as the relay. */
class RelayAddressTest {
    @Test fun `an https relay of another operator is accepted`() {
        assertEquals("https://relay.example.org/ohttp", OhttpSettings.normalizeRelayUrl("  https://relay.example.org/ohttp "))
    }
    @Test fun `cleartext is refused - the relay hop must be encrypted too`() {
        assertNull(OhttpSettings.normalizeRelayUrl("http://relay.example.org/"))
    }
    @Test fun `OSHI itself is refused - the decrypting party cannot also be the relay`() {
        assertNull(OhttpSettings.normalizeRelayUrl("https://oshi-messenger.com/ohttp/gateway"))
        assertNull(OhttpSettings.normalizeRelayUrl("https://relay.oshi-messenger.com/"))
    }
    @Test fun `garbage and bare hosts are refused`() {
        assertNull(OhttpSettings.normalizeRelayUrl(""))
        assertNull(OhttpSettings.normalizeRelayUrl("relay"))
        assertNull(OhttpSettings.normalizeRelayUrl("https://localhost/"))
    }
}
