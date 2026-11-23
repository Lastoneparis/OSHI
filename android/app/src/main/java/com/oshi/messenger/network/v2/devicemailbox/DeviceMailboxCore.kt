package com.oshi.messenger.network.v2.devicemailbox

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/*
 * __PER_DEVICE_MAILBOX_2026_09_23__ Per-device mailboxes: the pure, platform-free half.
 *
 * Spec: ServerPatches/per_device_mailbox/CLIENT_SPEC.md (design docs/OSHI_DEVICE_SYNC_DIRECT.md §15,
 * Appendix B). Golden vectors: ServerPatches/per_device_mailbox/device_vectors.json, reproduced by
 * `DeviceMailboxVectorsTest`.
 *
 * PURE JVM (BouncyCastle + org.json + java.util), no android.* — so the Desktop client can compile
 * this directory from the Android tree exactly like the devsync package (add a "devicemailbox" include glob to
 * OSHI-Desktop/build.gradle.kts `sharedV2Sources`). The HTTP, Keystore and poll-loop seams live in
 * `network/v2/V2DeviceClient.kt` and `DeviceMailboxManager.kt` (Android only).
 */

/** Canonical strings, ids and Ed25519 for the device registry (CLIENT_SPEC §2). */
object DeviceAuth {

    private val ID_PREFIX = "OSHI-DEVSYNC-id".toByteArray(Charsets.UTF_8)

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').let {
        var t = it; while (t.length % 4 != 0) t += "="; t
    })

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x.toInt() and 0xFF))
        return sb.toString()
    }

    fun sha256Hex(body: ByteArray): String = hex(sha256(body))

    /** `hex(SHA-256("OSHI-DEVSYNC-id" ‖ dk_raw))[0..32]` — the SAME id devsync routes on. */
    fun deviceId(dkPub: ByteArray): String {
        require(dkPub.size == 32) { "dk must be 32 bytes" }
        return hex(sha256(ID_PREFIX, dkPub)).substring(0, 32)
    }

    fun isDeviceId(s: String?): Boolean = s != null && s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * The account X25519 userKey in NORMALISED spelling (standard base64, `+/`, padded). The server
     * compares the proof strings byte for byte, so a base64url identity here is a 401.
     */
    fun normalizeIdentity(identity: String): String = runCatching { b64(unb64(identity.trim())) }.getOrDefault(identity.trim())

    fun registerProofString(identity: String, deviceId: String, dk: String, dsk: String, ts: String): String =
        "OSHI-DEVICE-REGISTER/1\n$identity\n$deviceId\n$dk\n$dsk\n$ts"

    fun approvalString(identity: String, newDeviceId: String, newDsk: String, ts: String): String =
        "OSHI-DEVICE-APPROVE/1\n$identity\n$newDeviceId\n$newDsk\n$ts"

    /** `<path>` is the wire path, query excluded, percent-encoding as sent (same as the account signature). */
    fun deviceRequestString(deviceId: String, method: String, path: String, bodySha256Hex: String, ts: String): String =
        "OSHI-DEVICE/1\n$deviceId\n$method\n$path\n$bodySha256Hex\n$ts"

    fun ed25519Public(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    fun ed25519Verify(pub: ByteArray, message: ByteArray, sig: ByteArray): Boolean = try {
        if (pub.size != 32 || sig.size != 64) false else {
            val v = Ed25519Signer()
            v.init(false, Ed25519PublicKeyParameters(pub, 0))
            v.update(message, 0, message.size)
            v.verifySignature(sig)
        }
    } catch (_: Exception) { false }
}

/** `{by, ts, sig}` — the approval an already-registered device signs, carried as devsync `APPROVAL.mailbox`. */
data class DeviceApproval(val by: String, val ts: String, val sig: String) {
    fun toJson(): JSONObject = JSONObject().put("by", by).put("ts", ts).put("sig", sig)

    companion object {
        /** The devsync `APPROVAL` field name (design §4.3 / CLIENT_SPEC §3.2). */
        const val FIELD = "mailbox"
        /** The server honours an approval for 15 min. */
        const val MAX_AGE_MS = 15 * 60 * 1000L

        fun fromJson(o: JSONObject?): DeviceApproval? {
            if (o == null) return null
            val by = o.optString("by"); val sig = o.optString("sig")
            val ts = o.opt("ts")?.toString().orEmpty()
            if (!DeviceAuth.isDeviceId(by) || sig.isEmpty() || ts.isEmpty() || ts.toLongOrNull() == null) return null
            return DeviceApproval(by, ts, sig)
        }
    }
}

/**
 * The per-install device credentials: `dk` (X25519 devsync static key, public half only here) and the
 * Ed25519 device signing key `dsk` (seed). Everything that needs the private `dsk` goes through this.
 */
class DeviceKeys(val dkPub: ByteArray, private val dskSeed: ByteArray) {
    init {
        require(dkPub.size == 32) { "dk must be 32 bytes" }
        require(dskSeed.size == 32) { "dsk seed must be 32 bytes" }
    }

    val deviceId: String = DeviceAuth.deviceId(dkPub)
    val dsk: ByteArray = DeviceAuth.ed25519Public(dskSeed)
    val dkB64: String get() = DeviceAuth.b64(dkPub)
    val dskB64: String get() = DeviceAuth.b64(dsk)

    fun sign(message: String): String = DeviceAuth.b64(DeviceAuth.ed25519Sign(dskSeed, message.toByteArray(Charsets.UTF_8)))

    /** The two extra headers of a device-mode request. [ts] MUST be the account signature's `x-oshi-timestamp`. */
    fun requestHeaders(method: String, path: String, body: ByteArray, ts: String): Map<String, String> = linkedMapOf(
        HEADER_DEVICE to deviceId,
        HEADER_DEVICE_SIG to sign(DeviceAuth.deviceRequestString(deviceId, method, path, DeviceAuth.sha256Hex(body), ts)),
    )

    /** Body of `POST /v2/devices/register` (§3.2 step 3). */
    fun registerBody(
        identity: String,
        ts: Long,
        approval: DeviceApproval? = null,
        adoptBacklog: Boolean = true,
        retireLegacy: Boolean = false,
    ): JSONObject {
        val id = DeviceAuth.normalizeIdentity(identity)
        val tsS = ts.toString()
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("dk", dkB64)
            put("dsk", dskB64)
            put("ts", tsS)
            put("proof", sign(DeviceAuth.registerProofString(id, deviceId, dkB64, dskB64, tsS)))
            approval?.let { put("approval", it.toJson()) }
            put("adoptBacklog", adoptBacklog)
            if (retireLegacy) put("retireLegacy", true)
        }
    }

    /** Approve ANOTHER device of this account (this device must itself be registered). */
    fun approve(identity: String, newDeviceId: String, newDskB64: String, ts: Long): DeviceApproval {
        require(DeviceAuth.isDeviceId(newDeviceId)) { "bad deviceId" }
        val tsS = ts.toString()
        return DeviceApproval(deviceId, tsS, sign(DeviceAuth.approvalString(DeviceAuth.normalizeIdentity(identity), newDeviceId, newDskB64, tsS)))
    }

    companion object {
        const val HEADER_DEVICE = "x-oshi-device"
        const val HEADER_DEVICE_SIG = "x-oshi-device-signature"
    }
}

/** One entry of `GET /v2/devices`. Names/platforms are NOT on the server (they come from devsync). */
data class RegisteredDevice(
    val deviceId: String,
    val dk: String,
    val dsk: String,
    val registeredAt: Long,
    val lastSeenAt: Long,
    val approvedBy: String?,
    val hasBundle: Boolean,
) {
    companion object {
        fun listFrom(json: JSONObject): List<RegisteredDevice> {
            val arr = json.optJSONArray("devices") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("deviceId")
                if (!DeviceAuth.isDeviceId(id)) return@mapNotNull null
                RegisteredDevice(
                    deviceId = id, dk = o.optString("dk"), dsk = o.optString("dsk"),
                    registeredAt = o.optLong("registeredAt"), lastSeenAt = o.optLong("lastSeenAt"),
                    approvedBy = o.optString("approvedBy").takeIf { DeviceAuth.isDeviceId(it) },
                    hasBundle = o.optBoolean("hasBundle", false),
                )
            }
        }
    }
}

/** `409 device-list-mismatch {to, missing, stale, devices}` (§3.5 step 5). */
data class DeviceListMismatch(val to: String, val missing: List<String>, val stale: List<String>, val devices: List<String>) {
    companion object {
        fun parse(body: String?): DeviceListMismatch? {
            val o = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
            if (o.optString("error") != "device-list-mismatch") return null
            fun ids(k: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(DeviceAuth::isDeviceId) } ?: emptyList()
            return DeviceListMismatch(o.optString("to"), ids("missing"), ids("stale"), ids("devices"))
        }
    }
}

/** What a device-route HTTP answer means for the client (§3.2 errors, §3.3 responses). */
sealed interface DeviceHttpOutcome {
    data object Ok : DeviceHttpOutcome
    /** `410 device-unknown`: removed by another device, reset, or 30 days silent. Alert the user. */
    data object DeviceUnknown : DeviceHttpOutcome
    /** `409 device-mailbox-disabled`: the flag is off server-side → legacy path (§3.1). */
    data object MailboxDisabled : DeviceHttpOutcome
    data object ApprovalRequired : DeviceHttpOutcome
    data object BadApproval : DeviceHttpOutcome
    data object Conflict : DeviceHttpOutcome
    data object TooManyDevices : DeviceHttpOutcome
    data class Mismatch(val info: DeviceListMismatch) : DeviceHttpOutcome
    data class Failed(val status: Int, val error: String?) : DeviceHttpOutcome

    companion object {
        fun classify(status: Int, body: String?): DeviceHttpOutcome {
            if (status in 200..299) return Ok
            val err = runCatching { JSONObject(body ?: "{}").optString("error") }.getOrNull()
            return when {
                status == 410 && err == "device-unknown" -> DeviceUnknown
                status == 410 -> DeviceUnknown
                status == 409 && err == "device-mailbox-disabled" -> MailboxDisabled
                status == 409 && err == "device-list-mismatch" -> DeviceListMismatch.parse(body)?.let { Mismatch(it) } ?: Failed(status, err)
                status == 409 && err == "device-conflict" -> Conflict
                status == 403 && err == "approval-required" -> ApprovalRequired
                status == 403 && err == "bad-approval" -> BadApproval
                status == 429 && err == "too-many-devices" -> TooManyDevices
                else -> Failed(status, err)
            }
        }
    }
}

/**
 * The decrypt rules of §3.4 as a pure decision. The ONE rule that must never be missed: an envelope
 * not addressed to this device NEVER triggers a session reset, a heal signal or a "could not
 * decrypt" row — otherwise multi-device turns into a reset storm.
 */
object DeviceRouting {

    enum class Action {
        /** `toDevice == me`: session `(from, fromDevice?)`; failures follow the normal retry/heal rules. */
        DEVICE_COPY,
        /** No `toDevice`, and this device holds the account bundle (or device mode is off): today's path. */
        ACCOUNT_NORMAL,
        /** No `toDevice`, NOT the holder: try quietly with `(from, nil)`; on failure ack and drop, nothing else. */
        ACCOUNT_QUIET,
        /** `toDevice` ≠ me: cannot happen (the server filters); ack and drop. */
        DROP_FOREIGN,
    }

    fun classify(toDevice: String?, myDeviceId: String?, deviceMode: Boolean, holdsAccountBundle: Boolean): Action = when {
        !deviceMode || myDeviceId == null -> if (toDevice == null) Action.ACCOUNT_NORMAL else Action.DROP_FOREIGN
        toDevice == null -> if (holdsAccountBundle) Action.ACCOUNT_NORMAL else Action.ACCOUNT_QUIET
        toDevice == myDeviceId -> Action.DEVICE_COPY
        else -> Action.DROP_FOREIGN
    }

    /** Session store key for `(peerUserKey, peerDeviceId?)`. The legacy session keeps its old key. */
    fun sessionKey(peerUserKey: String, peerDeviceId: String?): String =
        if (peerDeviceId == null) peerUserKey else "$peerUserKey#$peerDeviceId"

    /** Inverse of [sessionKey]. */
    fun splitSessionKey(key: String): Pair<String, String?> {
        val i = key.lastIndexOf('#')
        if (i < 0) return key to null
        val dev = key.substring(i + 1)
        return if (DeviceAuth.isDeviceId(dev)) key.substring(0, i) to dev else key to null
    }
}

/**
 * The "sent from another of my devices" plaintext (§3.6):
 * `{"kind":"sent-copy","v":1,"conversation":{…},"message":<payload>,"msgId":…,"sentAt":<ISO>}`.
 */
data class SentCopy(
    val peer: String?,
    val groupId: String?,
    /** The exact payload sent to the peer: a JSON object's text, or a plain string. */
    val message: String,
    val msgId: String,
    val sentAt: String,
) {
    fun encode(): ByteArray {
        val conv = if (groupId != null) JSONObject().put("type", "group").put("groupId", groupId)
        else JSONObject().put("type", "1to1").put("peer", peer)
        val msg: Any = message.trim().let { t ->
            if (t.startsWith("{")) runCatching { JSONObject(t) }.getOrNull() ?: message else message
        }
        return JSONObject().put("kind", KIND).put("v", 1).put("conversation", conv)
            .put("message", msg).put("msgId", msgId).put("sentAt", sentAt)
            .toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val KIND = "sent-copy"

        /** Null unless [plaintext] is a well-formed sent-copy. Cheap prefix check first. */
        fun parse(plaintext: ByteArray): SentCopy? {
            if (plaintext.isEmpty() || plaintext[0] != '{'.code.toByte()) return null
            val o = runCatching { JSONObject(String(plaintext, Charsets.UTF_8)) }.getOrNull() ?: return null
            if (o.optString("kind") != KIND) return null
            val conv = o.optJSONObject("conversation") ?: return null
            val msgId = o.optString("msgId").takeIf { it.isNotEmpty() } ?: return null
            val message = when (val m = o.opt("message")) {
                is JSONObject -> m.toString()
                is String -> m
                else -> return null
            }
            return when (conv.optString("type")) {
                "1to1" -> SentCopy(conv.optString("peer").takeIf { it.isNotEmpty() } ?: return null, null, message, msgId, o.optString("sentAt"))
                "group" -> SentCopy(null, conv.optString("groupId").takeIf { it.isNotEmpty() } ?: return null, message, msgId, o.optString("sentAt"))
                else -> null
            }
        }
    }
}

/** Per-peer device lists (`GET /v2/keys/<peer>/devices`), cached 10 min (§3.5 step 1). */
class DeviceListCache(private val ttlMs: Long = 10 * 60 * 1000L, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(val devices: List<String>, val at: Long)
    private val map = HashMap<String, Entry>()

    @Synchronized fun get(peer: String): List<String>? = map[peer]?.takeIf { clock() - it.at < ttlMs }?.devices
    @Synchronized fun put(peer: String, devices: List<String>) { map[peer] = Entry(devices.distinct(), clock()) }
    @Synchronized fun invalidate(peer: String) { map.remove(peer) }
    @Synchronized fun clear() = map.clear()

    companion object {
        /** `{devices:[{deviceId, caps}], enabled}` → ids. */
        fun parse(body: String?): List<String>? {
            val o = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
            val arr: JSONArray = o.optJSONArray("devices") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val e = arr.opt(i)
                val id = when (e) { is JSONObject -> e.optString("deviceId"); is String -> e; else -> null }
                id?.takeIf(DeviceAuth::isDeviceId)
            }
        }
    }
}

/**
 * __LEGACY_READER_2026_09_28__ Does the peer's live install read the LEGACY (account) view?
 *
 * The relay shows an install that is not registered in device mode ONLY the account envelopes
 * (`relay_v2.js visibleTo`: `deviceId == null` ⇒ `isAccountItem(item)`); a per-device copy is
 * invisible to it. The sender, though, picks per-device copies from `GET /v2/keys/<peer>/devices`,
 * which lists every device that ever published a bundle — including a phone that was reinstalled
 * (its old deviceId stays bundled for 30 days, and the new install cannot register without an
 * approval from it), or a Mac registered next to an iPhone still on a build without device mode.
 * In both cases every message from a device-mode sender went to a device nobody reads: the
 * sender's relay accepted it (✓), the peer never saw it, and the peer's own messages — account
 * envelopes — still arrived. "I receive, but what I send never arrives."
 *
 * The evidence is on the wire: an install in device mode always writes with `fromDevice`
 * (per-device copies carry it, `sendFannedOut`/iOS `sendFanout`), and a device copy always
 * carries `toDevice`. An envelope with NEITHER was written by an install on the account path,
 * i.e. one that also READS the account view. Until that peer writes a device copy again, we
 * address it with the account envelope, which every view of that account receives (its
 * registered devices are owed it too: the holder decrypts, the others drop it quietly, §3.4).
 */
object PeerReadView {
    /** True when the envelope came from an install that is not in device mode. */
    fun isLegacyWriter(toDevice: String?, fromDevice: String?): Boolean =
        toDevice == null && fromDevice == null
}

/**
 * Who gets a copy of one outgoing message (§3.5 + §3.6), and how a `409 device-list-mismatch`
 * changes that. Pure: the router encrypts, this decides.
 */
object FanoutPlanner {

    data class Plan(
        /** Per-device copies for the peer; empty → one account envelope (the peer has no registered device). */
        val peerDevices: List<String>,
        /** Self-fanout copies: every OTHER device of my own account. */
        val selfDevices: List<String>,
    )

    fun plan(peer: String, me: String, myDeviceId: String, peerDevices: List<String>, ownDevices: List<String>): Plan {
        val self = ownDevices.filter { it != myDeviceId }.distinct()
        // Writing to myself (a note-to-self): the peer IS my account, so the self copies are the peer copies.
        if (DeviceAuth.normalizeIdentity(peer) == DeviceAuth.normalizeIdentity(me)) return Plan(self, emptyList())
        return Plan(peerDevices.distinct(), self)
    }

    /**
     * Apply a mismatch for [target] (the peer, or me for the self copies): the server's `devices` is the
     * truth; for my own account, drop my own id. Returns the new list plus the ids whose sessions must go.
     */
    fun applyMismatch(current: List<String>, m: DeviceListMismatch, isSelf: Boolean, myDeviceId: String): Pair<List<String>, List<String>> {
        val next = m.devices.filter { !isSelf || it != myDeviceId }.distinct()
        val dropped = (m.stale + current.filter { it !in next }).distinct()
        return next to dropped
    }

    /** At most 2 retries after a mismatch; then the account envelope for that peer (§3.5 step 5). */
    const val MAX_MISMATCH_RETRIES = 2
}
