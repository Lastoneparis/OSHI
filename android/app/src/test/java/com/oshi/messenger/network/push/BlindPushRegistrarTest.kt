package com.oshi.messenger.network.push

import com.oshi.messenger.network.push.BlindPushRegistrar.Caps
import com.oshi.messenger.network.push.BlindPushRegistrar.Outcome
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * __BLIND_PUSH_2026_11_09__ The registration decision against a fake push service + gateway.
 * The invariant every case checks: the FCM token reaches the push service ONLY on the
 * legacy path, and the legacy body is exactly what 1.6.88 sends.
 */
class BlindPushRegistrarTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    private val token = "fcm-token-" + "x".repeat(140)
    private val handle = "ph1." + "H".repeat(420)

    /** Programmable routes: path -> response. */
    private val routes = mutableMapOf<String, MockResponse>()
    private val registerReplies = ArrayDeque<MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()

    private class Flag(override var active: Boolean) : BlindPushRegistrar.State

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen.add(request) }
                val p = request.path!!.substringBefore('?')
                if (p == "/push/register-device" && registerReplies.isNotEmpty()) return registerReplies.removeFirst()
                return routes[p] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/push").toString().trimEnd('/')
    private fun bodies(path: String) = seen.filter { it.path == path }.map { it.body.readUtf8() }
    private fun ok(json: String) = MockResponse().setResponseCode(200).setBody(json)
    private fun capsOn(seal: String = "/push-gateway/v1/seal") { routes["/push/capabilities"] = ok("""{"blindPush":{"v":1,"sealUrl":"$seal"}}""") }
    private fun sealOk() { routes["/push-gateway/v1/seal"] = ok("""{"handles":[{"platform":"fcm","kind":"message","handle":"$handle"}]}""") }

    private fun run(flag: Flag) = BlindPushRegistrar.register(client, client, base(), "PK", "dev-1", token, "local_banner", flag)

    // Same fields and values as 1.6.88's body (FCMService.registerTokenWithPushService). Compared
    // as JSON: the JVM test classpath's org.json does not keep insertion order (Android's does).
    private fun assertLegacyBody(b: String) {
        val o = JSONObject(b)
        assertEquals(setOf("publicKey", "fcmToken", "platform", "pushCaps", "deviceId"), o.keys().asSequence().toSet())
        assertEquals("PK", o.getString("publicKey")); assertEquals(token, o.getString("fcmToken"))
        assertEquals("android", o.getString("platform")); assertEquals("local_banner", o.getString("pushCaps"))
        assertEquals("dev-1", o.getString("deviceId"))
    }

    @Test fun `old server (404 on capabilities) - legacy body, byte for byte, no seal`() {
        routes["/push/register-device"] = ok("""{"success":true}""")
        val f = Flag(false)
        assertEquals(Outcome.LEGACY_REGISTERED, run(f))
        val regs = bodies("/push/register-device")
        assertEquals(1, regs.size); assertLegacyBody(regs[0])
        assertTrue(seen.none { it.path!!.startsWith("/push-gateway") })
        assertFalse(f.active)
    }

    @Test fun `blind server - token goes to the gateway only, handle to the push service`() {
        capsOn(); sealOk()
        routes["/push/register-device"] = ok("""{"success":true,"blindPush":{"supported":true,"accepted":true}}""")
        val f = Flag(false)
        assertEquals(Outcome.BLIND_ACCEPTED, run(f))
        assertTrue(f.active)
        val seal = JSONObject(bodies("/push-gateway/v1/seal").single()).getJSONArray("tokens").getJSONObject(0)
        assertEquals(token, seal.getString("token")); assertEquals("fcm", seal.getString("platform")); assertEquals("message", seal.getString("kind"))
        val reg = JSONObject(bodies("/push/register-device").single())
        assertEquals(handle, reg.getString("fcmHandle"))
        assertFalse(reg.has("fcmToken"))
        assertEquals("local_banner", reg.getString("pushCaps")); assertEquals("dev-1", reg.getString("deviceId"))
        // nothing the push service received contains the token
        assertTrue(seen.filter { it.path!!.startsWith("/push/") }.none { it.body.clone().readUtf8().contains(token) })
        // the seal request carries no identity
        assertFalse(bodies("/push-gateway/v1/seal").single().contains("PK"))
    }

    @Test fun `handle registration answered by a server that does not accept it - legacy in the same attempt`() {
        capsOn(); sealOk()
        registerReplies.add(ok("""{"success":true,"deviceId":"dev-1"}"""))              // old server: ignores fcmHandle
        registerReplies.add(ok("""{"success":true,"deviceId":"dev-1"}"""))
        val f = Flag(true)
        assertEquals(Outcome.LEGACY_REGISTERED, run(f))
        val regs = bodies("/push/register-device")
        assertEquals(2, regs.size); assertLegacyBody(regs[1])
        assertFalse(f.active)
    }

    @Test fun `feature switched off between capabilities and register - legacy`() {
        capsOn(); sealOk()
        registerReplies.add(ok("""{"success":false,"blindPush":{"supported":false,"accepted":false}}"""))
        registerReplies.add(ok("""{"success":true}"""))
        assertEquals(Outcome.LEGACY_REGISTERED, run(Flag(true)))
    }

    @Test fun `already blind and the gateway is down - no token sent, retry later`() {
        capsOn(); routes["/push-gateway/v1/seal"] = MockResponse().setResponseCode(502)
        val f = Flag(true)
        assertEquals(Outcome.RETRY_LATER, run(f))
        assertTrue(bodies("/push/register-device").isEmpty())
        assertTrue(f.active)
    }

    @Test fun `already blind and register refused 503 - no token sent, retry later`() {
        capsOn(); sealOk()
        routes["/push/register-device"] = MockResponse().setResponseCode(503).setBody("""{"error":"gateway-unavailable","blindPush":{"supported":true,"accepted":false}}""")
        assertEquals(Outcome.RETRY_LATER, run(Flag(true)))
        assertTrue(bodies("/push/register-device").none { it.contains(token) })
    }

    @Test fun `never blind and the gateway is down - legacy, the phone keeps its pushes`() {
        capsOn(); routes["/push-gateway/v1/seal"] = MockResponse().setResponseCode(500)
        routes["/push/register-device"] = ok("""{"success":true}""")
        assertEquals(Outcome.LEGACY_REGISTERED, run(Flag(false)))
        assertLegacyBody(bodies("/push/register-device").single())
    }

    @Test fun `capabilities unreachable - follows the remembered state`() {
        routes["/push/capabilities"] = MockResponse().setResponseCode(500)
        sealOk(); routes["/push/register-device"] = ok("""{"blindPush":{"supported":true,"accepted":true}}""")
        assertEquals(Outcome.BLIND_ACCEPTED, run(Flag(true)))
        seen.clear(); routes["/push/register-device"] = ok("""{"success":true}""")
        assertEquals(Outcome.LEGACY_REGISTERED, run(Flag(false)))
    }

    @Test fun `server stops advertising - flag cleared, legacy`() {
        routes["/push/capabilities"] = ok("""{"blindPush":null}""")
        routes["/push/register-device"] = ok("""{"success":true}""")
        val f = Flag(true)
        assertEquals(Outcome.LEGACY_REGISTERED, run(f))
        assertFalse(f.active)
    }

    @Test fun `seal URL resolution`() {
        val b = "https://oshi-messenger.com/push"
        assertEquals("https://oshi-messenger.com/push-gateway/v1/seal", BlindPushRegistrar.resolveSealUrl("/push-gateway/v1/seal", b))
        assertEquals("https://gw.example.net/v1/seal", BlindPushRegistrar.resolveSealUrl("https://gw.example.net/v1/seal", b))
        assertNull("no plain http from an https base", BlindPushRegistrar.resolveSealUrl("http://gw.example.net/v1/seal", b))
        assertNull(BlindPushRegistrar.resolveSealUrl("v1/seal", b))
        assertNull(BlindPushRegistrar.resolveSealUrl("", b))
    }

    @Test fun `parsers`() {
        assertEquals(Caps.Unsupported, BlindPushRegistrar.parseCaps(404, null, "https://x/push"))
        assertEquals(Caps.Unknown, BlindPushRegistrar.parseCaps(502, null, "https://x/push"))
        assertEquals(Caps.Unsupported, BlindPushRegistrar.parseCaps(200, """{"blindPush":{"v":2,"sealUrl":"/s"}}""", "https://x/push"))
        assertEquals(Caps.Supported("https://x/s"), BlindPushRegistrar.parseCaps(200, """{"blindPush":{"v":1,"sealUrl":"/s"}}""", "https://x/push"))
        assertNull(BlindPushRegistrar.parseSealResponse("""{"handles":[{"platform":"fcm","handle":"not-a-handle"}]}"""))
        assertNull(BlindPushRegistrar.parseSealResponse("garbage"))
        assertEquals(handle, BlindPushRegistrar.parseSealResponse("""{"handles":[{"platform":"fcm","kind":"message","handle":"$handle"}]}"""))
    }

    /** Source guard: FCMService must not send the token to /unregister-device, and must
     * route every /register-device through the registrar (no second legacy writer). */
    @Test fun `FCMService routes registration through the registrar`() {
        val f = listOf("src/main/java/com/oshi/messenger/service/FCMService.kt", "app/src/main/java/com/oshi/messenger/service/FCMService.kt")
            .map { File(it) }.first { it.exists() }.readText()
        assertFalse("legacy /register-device writer left in FCMService", Regex("""\bREGISTER_DEVICE_ENDPOINT""").containsMatchIn(f))
        assertFalse("token sent on unregister", Regex("""put\("fcmToken", (token|oldToken)\)""").containsMatchIn(f))
        assertTrue(f.contains("BlindPushRegistrar.register("))
    }
}
