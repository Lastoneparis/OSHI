package com.oshi.messenger.network.mesh.wifiaware

import com.oshi.messenger.network.mesh.MeshPermissionPolicy
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter.Decision
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter.Lifecycle
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter.Lifecycle.Action
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter.Lifecycle.Event
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter.Lifecycle.State
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __WIFI_AWARE_2026_11_25__ "It must NOT break any current connection" as a
 * decision table: Wi-Fi Aware waits for Wi-Fi Direct and the hotspot, never tears
 * either down, never tears itself down because of them, stays off when unsure,
 * closes quietly when the framework withdraws it, and asks for no new permission.
 */
class WifiAwareArbiterTest {

    private fun ok(sdk: Int = 34) = WifiAwareArbiter.Inputs(
        featurePresent = true, sdkInt = sdk, userEnabled = true, meshActive = true,
        permissionsGranted = true, awareAvailable = true,
        wifiDirectGroupActive = false, hotspotActive = false,
        availablePublishSessions = 1, availableSubscribeSessions = 1,
        currentlyRunning = false,
    )

    // ───────────────────────── decide ─────────────────────────

    @Test fun `runs when everything is clear`() {
        assertEquals(Decision.Run, WifiAwareArbiter.decide(ok()))
    }

    @Test fun `off when the phone cannot, or the user or mesh says no`() {
        assertEquals(Decision.Off(Reason.UNSUPPORTED), WifiAwareArbiter.decide(ok().copy(featurePresent = false)))
        assertEquals(Decision.Off(Reason.UNSUPPORTED), WifiAwareArbiter.decide(ok(sdk = 28)))
        assertEquals(Decision.Off(Reason.USER_DISABLED), WifiAwareArbiter.decide(ok().copy(userEnabled = false)))
        assertEquals(Decision.Off(Reason.MESH_OFF), WifiAwareArbiter.decide(ok().copy(meshActive = false)))
        assertEquals(Decision.Off(Reason.NO_PERMISSION), WifiAwareArbiter.decide(ok().copy(permissionsGranted = false)))
    }

    @Test fun `never starts while Wi-Fi Direct or the hotspot is live`() {
        assertEquals(Decision.Wait(Reason.WIFI_DIRECT_BUSY), WifiAwareArbiter.decide(ok().copy(wifiDirectGroupActive = true)))
        assertEquals(Decision.Wait(Reason.HOTSPOT_BUSY), WifiAwareArbiter.decide(ok().copy(hotspotActive = true)))
    }

    @Test fun `a running lane is not torn down because Wi-Fi Direct or the hotspot appeared`() {
        val running = ok().copy(currentlyRunning = true)
        assertEquals(Decision.Run, WifiAwareArbiter.decide(running.copy(wifiDirectGroupActive = true)))
        assertEquals(Decision.Run, WifiAwareArbiter.decide(running.copy(hotspotActive = true)))
        // Our own sessions consume the resources: no self-eviction either.
        assertEquals(Decision.Run, WifiAwareArbiter.decide(running.copy(availablePublishSessions = 0)))
    }

    @Test fun `but it does close when the framework withdraws Aware`() {
        val r = WifiAwareArbiter.decide(ok().copy(currentlyRunning = true, awareAvailable = false))
        assertEquals(Decision.Wait(Reason.UNAVAILABLE), r)
    }

    @Test fun `on API 31+ no free resources, or no answer, means stay off`() {
        assertEquals(Decision.Wait(Reason.NO_RESOURCES), WifiAwareArbiter.decide(ok().copy(availablePublishSessions = 0)))
        assertEquals(Decision.Wait(Reason.NO_RESOURCES), WifiAwareArbiter.decide(ok().copy(availableSubscribeSessions = 0)))
        assertEquals(Decision.Wait(Reason.UNKNOWN_RESOURCES), WifiAwareArbiter.decide(ok().copy(availablePublishSessions = null)))
        // Below 31 there is no such API: the cap in mayOpenDataPath is the guard.
        assertEquals(Decision.Run, WifiAwareArbiter.decide(ok(sdk = 30).copy(availablePublishSessions = null, availableSubscribeSessions = null)))
    }

    @Test fun `data paths are capped and never taken from another user of the chip`() {
        assertTrue(WifiAwareArbiter.mayOpenDataPath(34, availableDataPaths = 2, openPaths = 0))
        assertFalse("zero free NDPs: the chip would have to drop someone", WifiAwareArbiter.mayOpenDataPath(34, 0, 0))
        assertFalse("unknown on 31+: stay off", WifiAwareArbiter.mayOpenDataPath(34, null, 0))
        assertTrue(WifiAwareArbiter.mayOpenDataPath(30, null, 3))
        assertFalse(WifiAwareArbiter.mayOpenDataPath(30, null, WifiAwareArbiter.MAX_PEERS))
        assertFalse(WifiAwareArbiter.mayOpenDataPath(34, 99, WifiAwareArbiter.MAX_PEERS))
    }

    @Test fun `Wi-Fi Direct discovery yields to a running Aware lane, per peer too`() {
        assertTrue(WifiAwareArbiter.mayStartWifiDirectDiscovery(awareRunning = false))
        assertFalse(WifiAwareArbiter.mayStartWifiDirectDiscovery(awareRunning = true))
        assertFalse(WifiAwareArbiter.mayDialOverWifiDirect("k1", setOf("k1", "k2")))
        assertTrue(WifiAwareArbiter.mayDialOverWifiDirect("k3", setOf("k1", "k2")))
    }

    @Test fun `inbound sockets are accepted only from an Aware interface or a known Aware peer`() {
        assertTrue(WifiAwareArbiter.acceptInbound("aware_data0", "fe80::1%aware_data0", emptySet()))
        assertTrue(WifiAwareArbiter.acceptInbound(null, "/fe80::abcd%12", setOf("FE80::ABCD")))
        assertFalse("a LAN neighbour", WifiAwareArbiter.acceptInbound("wlan0", "192.168.1.20", emptySet()))
        assertFalse(WifiAwareArbiter.acceptInbound(null, "fe80::2", setOf("fe80::1")))
        assertEquals("fe80::1", WifiAwareArbiter.normaliseAddress("[FE80::1%aware_data0]"))
    }

    @Test fun `attach retries back off and are bounded`() {
        assertEquals(15_000L, WifiAwareArbiter.backoffMs(1))
        assertEquals(30_000L, WifiAwareArbiter.backoffMs(2))
        assertEquals(300_000L, WifiAwareArbiter.backoffMs(50))
        assertTrue((1..20).map(WifiAwareArbiter::backoffMs).zipWithNext().all { (a, b) -> b >= a })
    }

    // ───────────────────────── lifecycle / fallback ─────────────────────────

    private fun walk(start: State, vararg events: Event): List<Lifecycle.Step> {
        var s = start
        return events.map { e -> Lifecycle.next(s, e).also { s = it.state } }
    }

    @Test fun `happy path attaches once`() {
        val steps = walk(State.OFF, Event.ALLOWED, Event.ALLOWED, Event.ATTACHED, Event.ALLOWED)
        assertEquals(listOf(Action.ATTACH, Action.NONE, Action.NONE, Action.NONE), steps.map { it.action })
        assertEquals(State.RUNNING, steps.last().state)
    }

    @Test fun `Aware withdrawn while running closes quietly and comes back when available`() {
        val steps = walk(State.RUNNING, Event.UNAVAILABLE, Event.UNAVAILABLE, Event.ALLOWED, Event.ATTACHED)
        assertEquals(listOf(Action.DETACH, Action.NONE, Action.ATTACH, Action.NONE), steps.map { it.action })
        assertEquals(listOf(State.WAITING, State.WAITING, State.ATTACHING, State.RUNNING), steps.map { it.state })
    }

    @Test fun `a failed attach waits and retries, it never loops hot`() {
        val steps = walk(State.OFF, Event.ALLOWED, Event.ATTACH_FAILED, Event.ATTACH_FAILED)
        assertEquals(listOf(Action.ATTACH, Action.SCHEDULE_RETRY, Action.NONE), steps.map { it.action })
        assertEquals(State.WAITING, steps.last().state)
    }

    @Test fun `busy radio before attach completes abandons the attach, never a running lane`() {
        assertEquals(Lifecycle.Step(State.WAITING, Action.DETACH), Lifecycle.next(State.ATTACHING, Event.BUSY))
        assertEquals(Lifecycle.Step(State.RUNNING, Action.NONE), Lifecycle.next(State.RUNNING, Event.BUSY))
        assertEquals(Lifecycle.Step(State.WAITING, Action.NONE), Lifecycle.next(State.OFF, Event.BUSY))
    }

    @Test fun `switch off or mesh off always detaches`() {
        assertEquals(Lifecycle.Step(State.OFF, Action.DETACH), Lifecycle.next(State.RUNNING, Event.FORBIDDEN))
        assertEquals(Lifecycle.Step(State.OFF, Action.DETACH), Lifecycle.next(State.ATTACHING, Event.FORBIDDEN))
        assertEquals(Lifecycle.Step(State.OFF, Action.NONE), Lifecycle.next(State.WAITING, Event.FORBIDDEN))
    }

    @Test fun `a late attach callback for an abandoned attach is closed`() {
        assertEquals(Lifecycle.Step(State.OFF, Action.DETACH), Lifecycle.next(State.OFF, Event.ATTACHED))
        assertEquals(Lifecycle.Step(State.WAITING, Action.DETACH), Lifecycle.next(State.WAITING, Event.ATTACHED))
    }

    @Test fun `every state answers every event`() {
        for (s in State.entries) for (e in Event.entries) Lifecycle.next(s, e)
    }

    // ───────────────────────── no extra prompt ─────────────────────────

    @Test fun `Wi-Fi Aware asks for nothing the mesh does not already list`() {
        for (sdk in 29..36) {
            val aware = MeshPermissionPolicy.wifiAwarePermissions(sdk)
            assertTrue("API $sdk: $aware", MeshPermissionPolicy.allRequestable(sdk).containsAll(aware))
        }
        assertEquals(listOf(MeshPermissionPolicy.NEARBY_WIFI_DEVICES), MeshPermissionPolicy.wifiAwarePermissions(33))
        assertEquals(listOf(MeshPermissionPolicy.ACCESS_FINE_LOCATION), MeshPermissionPolicy.wifiAwarePermissions(32))
    }

    @Test fun `the switch and the badge are translated in all 34 locales`() {
        val res = File("src/main/res")
        assertTrue("run from the app module: ${res.absolutePath}", res.isDirectory)
        val dirs = res.listFiles()!!.filter {
            it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) &&
                !it.name.contains("night") && !Regex("-v\\d+$").containsMatchIn(it.name)
        }.filter { File(it, "strings.xml").exists() }
        assertEquals(34, dirs.size)
        val keys = listOf("settings_wifi_aware_title", "settings_wifi_aware_subtitle", "peers_tag_wifi_aware")
        for (d in dirs) {
            val f = File(d, "strings_wifi_aware.xml")
            assertTrue("missing ${f.path}", f.exists())
            val text = f.readText()
            for (k in keys) assertTrue("${d.name} lacks $k", text.contains("name=\"$k\""))
            assertFalse("${d.name}: unescaped apostrophe", Regex(">[^<]*(?<!\\\\)'[^<]*<").containsMatchIn(text))
        }
    }
}
