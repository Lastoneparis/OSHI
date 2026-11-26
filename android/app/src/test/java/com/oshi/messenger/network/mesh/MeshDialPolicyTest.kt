package com.oshi.messenger.network.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __MESH_DIAL_LAN_ONLY_2026_11_20__ */
class MeshDialPolicyTest {
    @Test fun lanAddressesAreDialable() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.49.1", "169.254.3.4",
            "fe80::1%wlan0", "fd12:3456::1", "[fe80::abcd]").forEach { assertTrue(it, MeshDialPolicy.isDialable(it)) }
    }

    @Test fun internetAddressesAndHostnamesAreNot() {
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "127.0.0.1", "0.0.0.0", "255.255.255.255", "2001:4860::8888",
            "evil.example.com", "localhost", "192.168.1.300", "", null, "::ffff:8.8.8.8").forEach {
            assertFalse(it.toString(), MeshDialPolicy.isDialable(it))
        }
    }
}
