package com.oshi.messenger.network.v2.devsync

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/*
 * __DEVSYNC_DEVICE_AUTH_2026_09_23__ The DEVICE-bound relay upgrade (design §5.2 / §11, Appendix A,
 * server `ServerPatches/devsync_device_auth/`, golden vectors `docs/fixtures/devsync/relay_auth.json`).
 *
 * Before: the upgrade was signed by the ACCOUNT key only and `?device=` was not signed, so two devices
 * of one account connecting in the same millisecond collided on the replay cache (401), and any holder
 * of the account key could connect AS another device id. Now the upgrade proves possession of the
 * device's own Ed25519 key `dsk` (the per-device-mailbox device signing key — ONE scheme for both):
 *
 *   B        = "OSHI-DEVSYNC-UPGRADE/1\n<identity>\n<deviceId>\n<dk>\n<dsk>\n<nonce>"
 *   account  = Ed25519(account key, "GET\n/v2/devsync\n<sha256hex(B)>\n<ts>")        (B = the hashed "body")
 *   device   = Ed25519(dsk, "OSHI-DEVICE/1\n<deviceId>\nGET\n/v2/devsync\n<sha256hex(B)>\n<ts>")
 *
 * `identity` = the normalised account X25519 userKey (standard padded base64), `dk`/`dsk` = standard
 * padded base64 of the raw 32-byte public keys, `deviceId` = H(dk), `nonce` = 16 fresh random bytes as
 * 32 lowercase hex, `ts` = the account signature's `x-oshi-timestamp`. PURE JVM (shared with Desktop).
 */

/** What the relay upgrade needs from this install: the devsync static key (public) and the device signing key. */
interface RelayDeviceCredentials {
    /** X25519 devsync device key `dk`, public half (32 bytes). deviceId = H(dkPub). */
    val dkPub: ByteArray
    /** Ed25519 device signing key `dsk`, public half (32 bytes) — the SAME key the per-device mailbox registers. */
    val dskPub: ByteArray
    /** Ed25519 by the private `dsk` over [message]; 64 raw bytes. */
    fun signWithDsk(message: ByteArray): ByteArray
}

/** Credentials from a raw Ed25519 seed (Desktop KeyVault, tests, vectors). */
class SeedRelayDeviceCredentials(override val dkPub: ByteArray, private val dskSeed: ByteArray) : RelayDeviceCredentials {
    init {
        require(dkPub.size == 32) { "dk must be 32 bytes" }
        require(dskSeed.size == 32) { "dsk seed must be 32 bytes" }
    }
    override val dskPub: ByteArray = Ed25519PrivateKeyParameters(dskSeed, 0).generatePublicKey().encoded
    override fun signWithDsk(message: ByteArray): ByteArray = DevSyncRelayAuth.ed25519Sign(dskSeed, message)
}

object DevSyncRelayAuth {
    const val TAG = "OSHI-DEVSYNC-UPGRADE/1"
    const val HEADER_DEVICE = "x-oshi-device"
    const val HEADER_DEVICE_SIG = "x-oshi-device-signature"
    const val HEADER_DK = "x-oshi-device-dk"
    const val HEADER_DSK = "x-oshi-device-dsk"
    const val HEADER_NONCE = "x-oshi-devsync-nonce"

    private val B64 = Base64.getEncoder()

    fun b64(b: ByteArray): String = B64.encodeToString(b)

    fun bindingString(identity: String, deviceId: String, dk: String, dsk: String, nonceHex: String): String =
        "$TAG\n$identity\n$deviceId\n$dk\n$dsk\n$nonceHex"

    /** The per-device-mailbox request string (`OSHI-DEVICE/1`) with method GET, path /v2/devsync, body = B. */
    fun deviceString(deviceId: String, bindingSha256Hex: String, ts: String): String =
        "OSHI-DEVICE/1\n$deviceId\nGET\n${RelayConnector.PATH}\n$bindingSha256Hex\n$ts"

    fun newNonce(): String = DevSyncCrypto.hex(DevSyncCrypto.randomBytes(16))

    /**
     * The server's `normalizeIdentity`: undo %2B/%2F/%3D, base64url -> standard, re-pad; applied only
     * when the value decodes to 32 bytes (a key), otherwise returned trimmed and untouched.
     */
    fun normalizeIdentity(s: String): String {
        var k = s.trim().replace("%2B", "+", true).replace("%2F", "/", true).replace("%3D", "=", true)
        k = k.replace('-', '+').replace('_', '/').trimEnd('=')
        while (k.length % 4 != 0) k += "="
        return runCatching { Base64.getDecoder().decode(k) }.getOrNull()?.takeIf { it.size == 32 }?.let { b64(it) } ?: s.trim()
    }

    /**
     * All upgrade headers. [accountSign] is the platform's account request signer, called ONCE with
     * `bodyToHash = B`; it must return `x-oshi-user`, `x-oshi-signing-pubkey`, `x-oshi-signature` and
     * `x-oshi-timestamp` (Android `V2Signer.sign("GET", "/v2/devsync", B, withUserHeader = true)`,
     * Desktop `DesktopV2Signer.sign(…)`). [identity] is the account userKey (normalised here).
     */
    fun upgradeHeaders(
        identity: String,
        deviceIdHex: String,
        creds: RelayDeviceCredentials,
        accountSign: (bodyToHash: ByteArray) -> Map<String, String>,
        nonceHex: String = newNonce(),
    ): Map<String, String> {
        require(DevSyncKeys.deviceIdHex(creds.dkPub) == deviceIdHex) { "deviceId is not H(dk)" }
        require(creds.dskPub.size == 32) { "dsk must be 32 bytes" }
        require(nonceHex.length == 32 && nonceHex.all { it in '0'..'9' || it in 'a'..'f' }) { "nonce must be 32 lowercase hex" }
        val id = normalizeIdentity(identity)
        val dk = b64(creds.dkPub)
        val dsk = b64(creds.dskPub)
        val binding = bindingString(id, deviceIdHex, dk, dsk, nonceHex).toByteArray(Charsets.UTF_8)
        val account = accountSign(binding)
        val ts = account["x-oshi-timestamp"] ?: error("account signer returned no x-oshi-timestamp")
        val user = account["x-oshi-user"] ?: error("account signer returned no x-oshi-user")
        require(normalizeIdentity(user) == id) { "x-oshi-user is not the identity inside B" }
        val bh = DevSyncCrypto.hex(DevSyncCrypto.sha256(binding))
        val sig = creds.signWithDsk(deviceString(deviceIdHex, bh, ts).toByteArray(Charsets.UTF_8))
        return LinkedHashMap(account).apply {
            put(HEADER_DEVICE, deviceIdHex)
            put(HEADER_DK, dk)
            put(HEADER_DSK, dsk)
            put(HEADER_NONCE, nonceHex)
            put(HEADER_DEVICE_SIG, b64(sig))
        }
    }

    fun ed25519Public(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    fun ed25519Verify(pub: ByteArray, message: ByteArray, sig: ByteArray): Boolean = try {
        if (pub.size != 32 || sig.size != 64) false else Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(pub, 0))
            update(message, 0, message.size)
            verifySignature(sig)
        }
    } catch (_: Exception) { false }
}
