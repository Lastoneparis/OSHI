package com.oshi.messenger.network.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** __COVER_DNS_FALLBACK_2026_11_19__ */
class CoverDnsTest {
    private val real = InetAddress.getByAddress("oshi-messenger.com", byteArrayOf(45, 67, 216.toByte(), 197.toByte()))
    private val poison = InetAddress.getByAddress("oshi-messenger.com", byteArrayOf(10, 10, 34, 34))   // Iran's block answer
    private var dohCalls = 0
    private var now = 1_000_000L
    private fun dns(system: (String) -> List<InetAddress>, doh: List<InetAddress> = listOf(real)) =
        CoverDns(system, { dohCalls++; doh }, { now })

    @Test fun honestResolverIsUsedAndDohNeverCalled() {
        assertEquals(listOf(real), dns({ listOf(real) }).lookup("oshi-messenger.com")); assertEquals(0, dohCalls)
    }
    @Test fun iranStylePoisonFallsBackToDoh() {
        assertEquals(listOf(real), dns({ listOf(poison) }).lookup("oshi-messenger.com")); assertEquals(1, dohCalls)
    }
    @Test fun nxdomainFallsBackToDoh() {
        assertEquals(listOf(real), dns({ throw UnknownHostException("nx") }).lookup("oshi-messenger.com"))
    }
    @Test fun mixedAnswerKeepsOnlyRoutableAddresses() {
        assertEquals(listOf(real), dns({ listOf(poison, real) }).lookup("oshi-messenger.com")); assertEquals(0, dohCalls)
    }
    @Test fun dohFailureReturnsTheSystemOutcomeUnchanged() {
        assertEquals(listOf(poison), dns({ listOf(poison) }, emptyList()).lookup("oshi-messenger.com"))
        try { dns({ throw UnknownHostException("nx") }, emptyList()).lookup("oshi-messenger.com"); fail() } catch (_: UnknownHostException) {}
    }
    @Test fun localNamesAreNeverSecondGuessed() {
        val lo = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        assertEquals(listOf(lo), dns({ listOf(lo) }).lookup("localhost")); assertEquals(0, dohCalls)
        assertTrue(CoverDns.isLocalName("relay.test")); assertFalse(CoverDns.isLocalName("oshi-messenger.com"))
    }
    @Test fun nonRoutableRanges() {
        for (ip in listOf("10.10.34.35", "192.168.1.1", "172.16.0.1", "127.0.0.1", "0.0.0.0", "169.254.1.1", "100.64.0.1", "::1", "fd00::1", "fe80::1"))
            assertTrue(ip, CoverDns.isNonRoutable(InetAddress.getByName(ip)))
        for (ip in listOf("45.67.216.197", "1.1.1.1", "100.128.0.1", "2a02:c207:2284:3891::1"))
            assertFalse(ip, CoverDns.isNonRoutable(InetAddress.getByName(ip)))
    }
    @Test fun parsesCloudflareAndGoogleJson() {
        val cf = """{"Status":0,"TC":false,"Question":[{"name":"oshi-messenger.com","type":1}],"Answer":[{"name":"oshi-messenger.com","type":5,"TTL":60,"data":"x.example."},{"name":"oshi-messenger.com","type":1,"TTL":300,"data":"45.67.216.197"}]}"""
        assertEquals(listOf(real.hostAddress), CoverDns.parseDnsJson("oshi-messenger.com", cf).map { it.hostAddress })
        assertEquals("oshi-messenger.com", CoverDns.parseDnsJson("oshi-messenger.com", cf)[0].hostName)
        assertTrue(CoverDns.parseDnsJson("x", """{"Status":3}""").isEmpty())
        assertTrue(CoverDns.parseDnsJson("x", """{"Status":0,"Answer":[{"type":1,"data":"evil.example"}]}""").isEmpty())
        val v6 = """{"Status":0,"Answer":[{"type":28,"data":"2a02:c207:2284:3891::1"}]}"""
        assertEquals(1, CoverDns.parseDnsJson("oshi-messenger.com", v6).size)
    }
    @Test fun dohAnswerIsReusedThenRefreshed() {
        val d = dns({ listOf(poison) })
        repeat(3) { assertEquals(listOf(real), d.lookup("oshi-messenger.com")) }
        assertEquals(1, dohCalls)
        now += CoverDns.POSITIVE_TTL_MS + 1
        d.lookup("oshi-messenger.com"); assertEquals(2, dohCalls)
    }
    @Test fun deadDohPathCostsOneAttemptPerMinute() {
        val d = dns({ throw UnknownHostException("nx") }, emptyList())
        repeat(5) { try { d.lookup("oshi-messenger.com"); fail() } catch (_: UnknownHostException) {} }
        assertEquals(1, dohCalls)
        now += CoverDns.NEGATIVE_TTL_MS + 1
        try { d.lookup("oshi-messenger.com"); fail() } catch (_: UnknownHostException) {}
        assertEquals(2, dohCalls)
    }
}
