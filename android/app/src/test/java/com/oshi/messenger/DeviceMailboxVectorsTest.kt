package com.oshi.messenger

import com.oshi.messenger.network.v2.devicemailbox.DeviceApproval
import com.oshi.messenger.network.v2.devicemailbox.DeviceAuth
import com.oshi.messenger.network.v2.devicemailbox.DeviceHttpOutcome
import com.oshi.messenger.network.v2.devicemailbox.DeviceKeys
import com.oshi.messenger.network.v2.devicemailbox.DeviceListCache
import com.oshi.messenger.network.v2.devicemailbox.DeviceListMismatch
import com.oshi.messenger.network.v2.devicemailbox.DeviceRouting
import com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner
import com.oshi.messenger.network.v2.devicemailbox.SentCopy
import com.oshi.messenger.network.v2.V2Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ `ServerPatches/per_device_mailbox/device_vectors.json`
 * (RFC 8032 seeds): deviceId and the three signatures, byte for byte, before any network code.
 */
class DeviceMailboxVectorsTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val identity = "ERERERERERERERERERERERERERERERERERERERERERE="
    private val dk = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
    private val deviceId = "8a62844af4e6e7850a98c731308278e1"
    private val deviceSeed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    private val deviceDsk = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
    private val approverSeed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"
    private val approverDsk = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
    private val ts = "1790000000000"
    private val registerString = "OSHI-DEVICE-REGISTER/1\nERERERERERERERERERERERERERERERERERERERERERE=\n8a62844af4e6e7850a98c731308278e1\nAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\n11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n1790000000000"
    private val registerSig = "xgbz9LBRI7OTGiujcAlZ/MBCdJeGLJCJct6tB/QIpqSA47aWUJgI493AaNW/RRrmeq8aTDAQZZIzEipZUK8mAw=="
    private val approvalString = "OSHI-DEVICE-APPROVE/1\nERERERERERERERERERERERERERERERERERERERERERE=\n8a62844af4e6e7850a98c731308278e1\n11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n1790000000000"
    private val approvalSig = "hMuiDohMyNnTCCGGbv+9zRl+u1+shr5MKD7wQj6D09gQ7v5nTo/5WOch7doqK6Ezw4xudvd+Sh9y2WZjz6mmAQ=="
    private val reqBody = "{\"upToSeq\":42}"
    private val reqBodyHash = "6848d1f78f9c2479db6416480bbd2a6dbb34bc6cdd1981b721e06a4b949da256"
    private val reqString = "OSHI-DEVICE/1\n8a62844af4e6e7850a98c731308278e1\nPOST\n/v2/messages/ERERERERERERERERERERERERERERERERERERERERERE%3D/ack\n6848d1f78f9c2479db6416480bbd2a6dbb34bc6cdd1981b721e06a4b949da256\n1790000000000"
    private val reqSig = "4d+sTmeDVLZKNQmNdx2bspBhdQ7ENy64Y+VdlLq3vHSZYK10dBbVHDwzxlKzKP6C65AkNLjWOek/tz/Km0DqCw=="

    private val device = DeviceKeys(DeviceAuth.unb64(dk), hex(deviceSeed))
    private val approver = DeviceKeys(ByteArray(32) { 7 }, hex(approverSeed))

    @Test
    fun `deviceId and dsk`() {
        assertEquals(deviceId, DeviceAuth.deviceId(DeviceAuth.unb64(dk)))
        assertEquals(deviceId, device.deviceId)
        assertEquals(deviceDsk, device.dskB64)
        assertEquals(approverDsk, approver.dskB64)
    }

    @Test
    fun `register proof`() {
        assertEquals(registerString, DeviceAuth.registerProofString(identity, deviceId, dk, deviceDsk, ts))
        assertEquals(registerSig, device.sign(registerString))
        val body = device.registerBody(identity, ts.toLong())
        assertEquals(registerSig, body.getString("proof"))
        assertEquals(ts, body.getString("ts"))
        assertEquals(dk, body.getString("dk")); assertEquals(deviceDsk, body.getString("dsk"))
        assertTrue(body.getBoolean("adoptBacklog")); assertFalse(body.has("retireLegacy")); assertFalse(body.has("approval"))
        // A base64url spelling of the identity is normalised before signing.
        val url = device.registerBody(identity.replace('+', '-').replace('/', '_').trimEnd('='), ts.toLong())
        assertEquals(registerSig, url.getString("proof"))
    }

    @Test
    fun `approval by another device`() {
        assertEquals(approvalString, DeviceAuth.approvalString(identity, deviceId, deviceDsk, ts))
        val a = approver.approve(identity, deviceId, deviceDsk, ts.toLong())
        assertEquals(approvalSig, a.sig)
        assertEquals(approver.deviceId, a.by)
        assertEquals(ts, a.ts)
        assertTrue(DeviceAuth.ed25519Verify(approver.dsk, approvalString.toByteArray(), DeviceAuth.unb64(approvalSig)))
        // round trip through the devsync APPROVAL.mailbox shape
        val parsed = DeviceApproval.fromJson(JSONObject(a.toJson().toString()))
        assertEquals(a, parsed)
        assertNull(DeviceApproval.fromJson(JSONObject().put("by", "zz").put("ts", ts).put("sig", "x")))
    }

    @Test
    fun `device request signature over the ack`() {
        val path = "/v2/messages/${V2Signer.encodeIdentity(identity)}/ack"
        assertEquals(reqBodyHash, DeviceAuth.sha256Hex(reqBody.toByteArray()))
        assertEquals(reqString, DeviceAuth.deviceRequestString(deviceId, "POST", path, reqBodyHash, ts))
        val h = device.requestHeaders("POST", path, reqBody.toByteArray(), ts)
        assertEquals(deviceId, h[DeviceKeys.HEADER_DEVICE])
        assertEquals(reqSig, h[DeviceKeys.HEADER_DEVICE_SIG])
    }

    @Test
    fun `decrypt routing never resets on a foreign envelope`() {
        val me = deviceId
        assertEquals(DeviceRouting.Action.DEVICE_COPY, DeviceRouting.classify(me, me, true, false))
        assertEquals(DeviceRouting.Action.ACCOUNT_NORMAL, DeviceRouting.classify(null, me, true, true))
        assertEquals(DeviceRouting.Action.ACCOUNT_QUIET, DeviceRouting.classify(null, me, true, false))
        assertEquals(DeviceRouting.Action.DROP_FOREIGN, DeviceRouting.classify("0".repeat(32), me, true, true))
        // flag off → today's behaviour for account envelopes
        assertEquals(DeviceRouting.Action.ACCOUNT_NORMAL, DeviceRouting.classify(null, me, false, false))
        assertEquals("peer#$me", DeviceRouting.sessionKey("peer", me))
        assertEquals("peer", DeviceRouting.sessionKey("peer", null))
        assertEquals("a/b+c=" to me, DeviceRouting.splitSessionKey("a/b+c=#$me"))
        assertEquals("a/b+c=" to null, DeviceRouting.splitSessionKey("a/b+c="))
    }

    @Test
    fun `sent-copy roundtrip`() {
        val c = SentCopy("PEER=", null, "{\"type\":\"text\",\"content\":\"hi\"}", "M1", "2026-09-23T10:00:00.000Z")
        val p = SentCopy.parse(c.encode())!!
        assertEquals("PEER=", p.peer); assertEquals("M1", p.msgId)
        assertEquals("hi", JSONObject(p.message).getString("content"))
        val plain = SentCopy("PEER=", null, "just text", "M2", "t")
        assertEquals("just text", SentCopy.parse(plain.encode())!!.message)
        val g = SentCopy(null, "G1", "x", "M3", "t")
        assertEquals("G1", SentCopy.parse(g.encode())!!.groupId)
        assertNull(SentCopy.parse("hello".toByteArray()))
        assertNull(SentCopy.parse("{\"kind\":\"other\"}".toByteArray()))
    }

    @Test
    fun `http outcomes and mismatch`() {
        assertEquals(DeviceHttpOutcome.DeviceUnknown, DeviceHttpOutcome.classify(410, "{\"error\":\"device-unknown\"}"))
        assertEquals(DeviceHttpOutcome.MailboxDisabled, DeviceHttpOutcome.classify(409, "{\"error\":\"device-mailbox-disabled\"}"))
        assertEquals(DeviceHttpOutcome.ApprovalRequired, DeviceHttpOutcome.classify(403, "{\"error\":\"approval-required\",\"devices\":1}"))
        val a = "a".repeat(32); val b = "b".repeat(32); val c = "c".repeat(32)
        val body = "{\"error\":\"device-list-mismatch\",\"to\":\"P\",\"missing\":[\"$c\"],\"stale\":[\"$a\"],\"devices\":[\"$b\",\"$c\"]}"
        val m = (DeviceHttpOutcome.classify(409, body) as DeviceHttpOutcome.Mismatch).info
        assertEquals(DeviceListMismatch("P", listOf(c), listOf(a), listOf(b, c)), m)
        val (next, dropped) = FanoutPlanner.applyMismatch(listOf(a, b), m, isSelf = false, myDeviceId = deviceId)
        assertEquals(listOf(b, c), next); assertEquals(listOf(a), dropped)
        // own account: my own id is never a target
        val (selfNext, _) = FanoutPlanner.applyMismatch(emptyList(), m.copy(devices = listOf(deviceId, b)), true, deviceId)
        assertEquals(listOf(b), selfNext)
    }

    @Test
    fun `fanout plan and device list cache`() {
        val a = "a".repeat(32); val b = "b".repeat(32)
        val p = FanoutPlanner.plan("PEER=", "ME==", deviceId, listOf(a), listOf(deviceId, b))
        assertEquals(listOf(a), p.peerDevices); assertEquals(listOf(b), p.selfDevices)
        val self = FanoutPlanner.plan("ME==", "ME==", deviceId, listOf(deviceId, b), listOf(deviceId, b))
        assertEquals(listOf(b), self.peerDevices); assertTrue(self.selfDevices.isEmpty())
        var now = 0L
        val cache = DeviceListCache(ttlMs = 1000, clock = { now })
        cache.put("P", listOf(a)); assertEquals(listOf(a), cache.get("P"))
        now = 1000; assertNull(cache.get("P"))
        assertEquals(listOf(a), DeviceListCache.parse("{\"devices\":[{\"deviceId\":\"$a\",\"caps\":0}],\"enabled\":true}"))
    }

    @Test
    fun `vectors file matches the embedded copy`() {
        val f = listOf("../../ServerPatches/per_device_mailbox/device_vectors.json", "../ServerPatches/per_device_mailbox/device_vectors.json")
            .map { File(it) }.firstOrNull { it.isFile } ?: return
        val v = JSONObject(f.readText())
        assertEquals(identity, v.getString("identity")); assertEquals(dk, v.getString("dk"))
        assertEquals(deviceId, v.getString("deviceId"))
        assertEquals(deviceSeed, v.getJSONObject("device").getString("ed25519Seed"))
        assertEquals(approverSeed, v.getJSONObject("approver").getString("ed25519Seed"))
        assertEquals(registerSig, v.getJSONObject("registerProof").getString("signature"))
        assertEquals(approvalSig, v.getJSONObject("approval").getString("signature"))
        assertEquals(reqString, v.getJSONObject("deviceRequest").getString("string"))
        assertEquals(reqSig, v.getJSONObject("deviceRequest").getString("signature"))
    }
}
