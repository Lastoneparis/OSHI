package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.network.v2.devicemailbox.DeviceAuth
import com.oshi.messenger.network.v2.devicemailbox.DeviceHttpOutcome
import com.oshi.messenger.network.v2.devicemailbox.DeviceKeys
import com.oshi.messenger.network.v2.devicemailbox.DeviceListCache
import com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner
import com.oshi.messenger.network.v2.devicemailbox.RegisteredDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ The pure device-mailbox core against the REAL server
 * (`ServerPatches/per_device_mailbox/v2/v2_server.js`, run locally with `device_mailbox_enabled:true`,
 * auth mode `enforce`). Opt-in, see [LiveHarness].
 */
class DeviceMailboxLiveServerTest {

    /** An account: X25519 identity (the address) + Ed25519 request-signing key, like CryptoManager. */
    class Account {
        val x = OSHICryptoV2.generateX25519()
        val userKey: String = DeviceAuth.b64(x.pub)
        private val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signingPub: String = DeviceAuth.b64(DeviceAuth.ed25519Public(seed))
        fun sign(msg: ByteArray): String = DeviceAuth.b64(DeviceAuth.ed25519Sign(seed, msg))

        fun headers(method: String, path: String, body: ByteArray, withUser: Boolean = false, device: DeviceKeys? = null): Map<String, String> {
            val ts = nextTs().toString()
            val h = linkedMapOf(
                "x-oshi-signature" to sign(V2Signer.canonicalString(method, path, body, ts).toByteArray()),
                "x-oshi-timestamp" to ts,
                "x-oshi-signing-pubkey" to signingPub,
            )
            if (withUser) h["x-oshi-user"] = userKey
            if (device != null) h.putAll(device.requestHeaders(method, path, body, ts))
            return h
        }

        fun spk(): JSONObject {
            val p = OSHICryptoV2.generateX25519()
            return JSONObject().put("keyId", "spk1").put("key", DeviceAuth.b64(p.pub)).put("signature", sign(p.pub))
        }

        fun bundleBody(deviceId: String? = null): ByteArray {
            val o = JSONObject().put("userKey", userKey).put("identityKey", userKey).put("signingKey", signingPub)
                .put("signedPreKey", spk())
                .put("oneTimePreKeys", JSONArray().apply { repeat(3) { put(JSONObject().put("keyId", "o$it-${deviceId ?: "acct"}").put("key", DeviceAuth.b64(OSHICryptoV2.generateX25519().pub))) } })
            if (deviceId != null) o.put("deviceId", deviceId)
            return o.toString().toByteArray()
        }

        val encoded get() = V2Signer.encodeIdentity(userKey)
    }

    private fun newDevice() = DeviceKeys(OSHICryptoV2.generateX25519().pub, ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun envelope(from: Account, to: Account, msgId: String, toDevice: String? = null, fromDevice: String? = null) = JSONObject().apply {
        put("v", 4); put("msgId", msgId); put("from", from.userKey); put("to", to.userKey); put("type", "1to1")
        put("header", Base64.getEncoder().encodeToString(ByteArray(40))); put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(48) { 1 }))
        put("ts", System.currentTimeMillis())
        toDevice?.let { put("toDevice", it) }; fromDevice?.let { put("fromDevice", it) }
    }

    private fun sendBatch(from: Account, envs: List<JSONObject>): Pair<Int, String> {
        val body = JSONObject().put("envelopes", JSONArray(envs)).toString().toByteArray()
        val r = LiveHarness.post(port, "/v2/messages", body, from.headers("POST", "/v2/messages", body))
        return r.statusCode() to r.body()
    }

    private fun pull(a: Account, device: DeviceKeys?, after: Long = 0): Pair<Int, String> {
        val path = "/v2/messages/${a.encoded}"
        val r = LiveHarness.get(port, "$path?after=$after", a.headers("GET", path, ByteArray(0), device = device))
        return r.statusCode() to r.body()
    }

    private fun ids(body: String): List<String> {
        val arr = JSONObject(body).getJSONArray("messages")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("msgId") + (arr.getJSONObject(it).optString("toDevice").let { d -> if (d.isEmpty()) "" else "@" + d.take(4) }) }
    }

    @Test
    fun `register, approve, per-device bundles, fan-out, device views, revoke, flag off`() {
        val me = Account(); val peer = Account()
        // 0. account bundles (bind userKey → signing key)
        for (a in listOf(me, peer)) {
            val b = a.bundleBody()
            assertEquals(200, LiveHarness.post(port, "/v2/keys/publish", b, a.headers("POST", "/v2/keys/publish", b)).statusCode())
        }
        // 1. first device: no approval needed
        val phone = newDevice(); val mac = newDevice()
        var r = LiveHarness.get(port, "/v2/devices", me.headers("GET", "/v2/devices", ByteArray(0), withUser = true))
        assertEquals(200, r.statusCode()); assertTrue(RegisteredDevice.listFrom(JSONObject(r.body())).isEmpty())
        var body = phone.registerBody(me.userKey, System.currentTimeMillis()).toString().toByteArray()
        r = LiveHarness.post(port, "/v2/devices/register", body, me.headers("POST", "/v2/devices/register", body, withUser = true))
        assertEquals(r.body(), DeviceHttpOutcome.Ok, DeviceHttpOutcome.classify(r.statusCode(), r.body()))

        // 2. second device without approval → approval-required; with the phone's approval → ok
        body = mac.registerBody(me.userKey, System.currentTimeMillis()).toString().toByteArray()
        r = LiveHarness.post(port, "/v2/devices/register", body, me.headers("POST", "/v2/devices/register", body, withUser = true))
        assertEquals(DeviceHttpOutcome.ApprovalRequired, DeviceHttpOutcome.classify(r.statusCode(), r.body()))
        val approval = phone.approve(me.userKey, mac.deviceId, mac.dskB64, System.currentTimeMillis())
        // round trip through the devsync APPROVAL.mailbox JSON
        val carried = com.oshi.messenger.network.v2.devicemailbox.DeviceApproval.fromJson(JSONObject(JSONObject().put("approved", true).put("mailbox", approval.toJson()).toString()).getJSONObject("mailbox"))
        body = mac.registerBody(me.userKey, System.currentTimeMillis(), approval = carried).toString().toByteArray()
        r = LiveHarness.post(port, "/v2/devices/register", body, me.headers("POST", "/v2/devices/register", body, withUser = true))
        assertEquals(r.body(), 200, r.statusCode())
        r = LiveHarness.get(port, "/v2/devices", me.headers("GET", "/v2/devices", ByteArray(0), withUser = true))
        val listed = RegisteredDevice.listFrom(JSONObject(r.body()))
        assertEquals(setOf(phone.deviceId, mac.deviceId), listed.map { it.deviceId }.toSet())
        assertEquals(phone.deviceId, listed.first { it.deviceId == mac.deviceId }.approvedBy)

        // 3. per-device bundles (account- AND device-signed)
        for (d in listOf(phone, mac)) {
            val b = me.bundleBody(d.deviceId)
            r = LiveHarness.post(port, "/v2/keys/publish", b, me.headers("POST", "/v2/keys/publish", b, device = d))
            assertEquals(r.body(), 200, r.statusCode())
        }
        r = LiveHarness.get(port, "/v2/keys/${me.encoded}/devices", peer.headers("GET", "/v2/keys/${me.encoded}/devices", ByteArray(0)))
        val devs = DeviceListCache.parse(r.body())!!
        assertEquals(setOf(phone.deviceId, mac.deviceId), devs.toSet())
        r = LiveHarness.get(port, "/v2/keys/${me.encoded}/count?device=${mac.deviceId}", me.headers("GET", "/v2/keys/${me.encoded}/count", ByteArray(0)))
        assertEquals(3, JSONObject(r.body()).getInt("remaining"))
        r = LiveHarness.get(port, "/v2/keys/${me.encoded}?device=${mac.deviceId}", peer.headers("GET", "/v2/keys/${me.encoded}", ByteArray(0)))
        assertEquals(mac.deviceId, JSONObject(r.body()).getString("deviceId"))

        // 4. a stale list (only the phone) → 409 device-list-mismatch naming the Mac
        var (st, txt) = sendBatch(peer, listOf(envelope(peer, me, "M1", toDevice = phone.deviceId)))
        val mm = (DeviceHttpOutcome.classify(st, txt) as DeviceHttpOutcome.Mismatch).info
        assertEquals(listOf(mac.deviceId), mm.missing)
        val (next, dropped) = FanoutPlanner.applyMismatch(listOf(phone.deviceId), mm, isSelf = false, myDeviceId = "0".repeat(32))
        assertEquals(setOf(phone.deviceId, mac.deviceId), next.toSet()); assertTrue(dropped.isEmpty())
        // the rebuilt batch (one copy per device, same msgId) is accepted
        val plan = FanoutPlanner.plan(me.userKey, peer.userKey, "f".repeat(32), next, emptyList())
        val pair = sendBatch(peer, plan.peerDevices.map { envelope(peer, me, "M1", toDevice = it) })
        assertEquals(pair.second, 200, pair.first)
        // an old sender's account envelope
        assertEquals(200, sendBatch(peer, listOf(envelope(peer, me, "OLD1"))).first)

        // 5. each device sees ITS copy + the account envelope; the legacy view sees only the account one
        val (ps, pb) = pull(me, phone); assertEquals(200, ps)
        assertEquals(setOf("M1@${phone.deviceId.take(4)}", "OLD1"), ids(pb).toSet())
        val (ms, mb) = pull(me, mac); assertEquals(200, ms)
        assertEquals(setOf("M1@${mac.deviceId.take(4)}", "OLD1"), ids(mb).toSet())
        assertEquals(setOf("OLD1"), ids(pull(me, null).second).toSet())

        // 6. a device ack releases that device only
        val ackBody = JSONObject().put("upToSeq", JSONObject(pb).getLong("maxSeq")).toString().toByteArray()
        val ackPath = "/v2/messages/${me.encoded}/ack"
        r = LiveHarness.post(port, ackPath, ackBody, me.headers("POST", ackPath, ackBody, device = phone))
        assertEquals(DeviceHttpOutcome.Ok, DeviceHttpOutcome.classify(r.statusCode(), r.body()))
        assertTrue(ids(pull(me, phone).second).isEmpty())
        assertEquals(2, ids(pull(me, mac).second).size)

        // 7. self-fanout from the Mac to the phone ("sent from my Mac"), minus the sender device
        val self = FanoutPlanner.plan(peer.userKey, me.userKey, mac.deviceId, emptyList(), devs)
        assertEquals(listOf(phone.deviceId), self.selfDevices)
        val selfBatch = listOf(envelope(me, peer, "S1")) + self.selfDevices.map { envelope(me, me, "S1", toDevice = it, fromDevice = mac.deviceId) }
        assertEquals(200, sendBatch(me, selfBatch).first)
        assertEquals(listOf("S1@${phone.deviceId.take(4)}"), ids(pull(me, phone).second))

        // 8. the phone removes the Mac → the Mac gets 410 device-unknown (alert + legacy fallback)
        val unregBody = JSONObject().put("deviceId", mac.deviceId).toString().toByteArray()
        r = LiveHarness.post(port, "/v2/devices/unregister", unregBody, me.headers("POST", "/v2/devices/unregister", unregBody, withUser = true, device = phone))
        assertEquals(r.body(), 200, r.statusCode())
        val (gone, goneBody) = pull(me, mac)
        assertEquals(DeviceHttpOutcome.DeviceUnknown, DeviceHttpOutcome.classify(gone, goneBody))

        // 9. flag off mid-session → device requests get 409 device-mailbox-disabled, legacy still works
        configFile.writeText("{\"v2_enabled\":true,\"rollout_percent\":100,\"device_mailbox_enabled\":false}")
        Thread.sleep(5_600)   // the server caches the config file for 5 s
        val (off, offBody) = pull(me, phone)
        assertEquals(DeviceHttpOutcome.MailboxDisabled, DeviceHttpOutcome.classify(off, offBody))
        assertEquals(200, pull(me, null).first)
        val cfg = JSONObject(LiveHarness.get(port, "/v2/config").body())
        assertEquals(false, cfg.getBoolean("device_mailbox_enabled"))
        configFile.writeText(CONFIG_ON)
    }

    companion object {
        private const val CONFIG_ON = "{\"v2_enabled\":true,\"rollout_percent\":100,\"device_mailbox_enabled\":true}"
        private var server: LiveHarness.Server? = null
        private var port = 0
        private lateinit var configFile: File

        @BeforeClass @JvmStatic
        fun up() {
            LiveHarness.assumeEnabled()
            configFile = File.createTempFile("oshi-v2-config", ".json").apply { writeText(CONFIG_ON); deleteOnExit() }
            port = LiveHarness.freePort()
            val script = File(LiveHarness.repoRoot, "ServerPatches/per_device_mailbox/v2/v2_server.js")
            server = LiveHarness.start(script, mapOf(
                "OSHI_V2_PORT" to port.toString(),
                "OSHI_V2_CONFIG_FILE" to configFile.absolutePath,
                "OSHI_AUTH_MODE" to "enforce",
                "OSHI_QUEUE_PORT" to "1", "OSHI_PUSH_PORT" to "1",
            ), "/health", port)
            val cfg = JSONObject(LiveHarness.get(port, "/v2/config").body())
            check(cfg.getBoolean("device_mailbox_enabled")) { "flag not on: $cfg" }
        }

        @AfterClass @JvmStatic
        fun down() { server?.stop() }
    }
}

private val tsCounter = java.util.concurrent.atomic.AtomicLong(0)

/** Unique, monotonic request timestamps: the server refuses a replayed (identical) signature. */
private fun nextTs(): Long = tsCounter.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
