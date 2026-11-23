package com.oshi.messenger.network.v2

import android.util.Base64
import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.encryption.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A peer's X3DH prekey bundle after signature-verification + TOFU. */
data class FetchedBundle(
    val identityKey: ByteArray,   // peer X25519 identity (== their address)
    val signingKey: ByteArray,    // peer Ed25519 signing pub
    val signedPreKeyId: String,
    val signedPreKey: ByteArray,  // peer X25519 SPK pub (signature verified)
    val oneTimePreKeyId: String?, // null when the server's OPK pool is drained
    val oneTimePreKey: ByteArray?,
)

/**
 * OSHI V2 prekey endpoints (/v2/keys), byte-compatible with iOS V2Client.
 * Publishing a bundle is what makes iOS treat this Android install as v2-capable
 * (it fetches our bundle and routes V2 instead of the legacy IPFS path).
 */
@Singleton
class V2KeysClient @Inject constructor(
    private val coverSettings: CoverSettings,
    private val signer: V2Signer,
    private val cryptoManager: CryptoManager,
    private val prekeyStore: V2PrekeyStore,
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(coverSettings.interceptor())
        .noSilentReplay()   // __SIGNED_NO_SILENT_REPLAY_2026_11_18__ (CoverInterceptor.kt)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** Publish (or republish) our bundle. Returns the server's remaining OPK count, or null on failure. */
    suspend fun publish(oneTimeCount: Int = 20): Int? = withContext(Dispatchers.IO) {
        try {
            val userKey = cryptoManager.getPublicKeyString()          // b64 X25519 identity
            val signingKey = b64(cryptoManager.getSigningPublicKey())  // b64 Ed25519
            val spk = prekeyStore.currentSignedPreKey()
            val spkSig = b64(cryptoManager.sign(spk.pair.pub))         // Ed25519 over raw SPK pub

            val body = JSONObject().apply {
                put("userKey", userKey)
                put("identityKey", userKey)   // == identity (X3DH IK is the legacy X25519 identity)
                put("signingKey", signingKey)
                // __LEGACY_COMPAT_2026_09_23__ advertise "reads v2 groups" so iOS/Android senders in
                // the compat window skip the legacy group copy for us (server stores 0..0xFFFF).
                put("caps", GroupLegacyCompat.MY_CAPS)
                put("signedPreKey", JSONObject().apply {
                    put("keyId", spk.keyId)
                    put("key", b64(spk.pair.pub))
                    put("signature", spkSig)
                })
                put("oneTimePreKeys", JSONArray().apply {
                    for ((id, pub) in prekeyStore.generateOneTimePreKeys(oneTimeCount)) {
                        put(JSONObject().apply { put("keyId", id); put("key", b64(pub)) })
                    }
                })
            }
            // __MAILBOX_BOOTSTRAP_2026_09_23__ Android's org.json writes every "/" as "\\/". The live
            // /v2/keys route hashes JSON.stringify(parsed body) instead of the bytes received, so
            // an escaped slash made EVERY Android publish fail verification: stored, but graced
            // ("invalid-signature-grace") and never bound userKey→signingKey — a fresh Android
            // identity stayed "unbound" on every route. Send the canonical spelling (base64 and
            // UUIDs only here: no backslash can precede a slash legitimately).
            val bodyBytes = body.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8)
            val path = "/v2/keys/publish"
            val headers = signer.sign("POST", path, bodyBytes)  // hash = the exact bytes sent
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$path")
                .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) {
                    Log.w("V2KeysClient", "publish HTTP ${resp.code}"); return@withContext null
                }
                JSONObject(resp.body?.string().orEmpty()).optInt("remaining", oneTimeCount)
            }
        } catch (e: Exception) {
            Log.e("V2KeysClient", "publish failed: ${e.message}", e); null
        }
    }

    /**
     * The three answers `/v2/keys/<peer>` can give, kept apart on purpose.
     *
     * __NO_BUNDLE_IS_NOT_A_TIMEOUT_2026_11_02__ [fetchBundle] collapses all of
     * them to `null`, and every caller that asked "can this peer receive v2?"
     * read that `null` as **"they are on a build without v2 — tell the user to
     * make them update"**. A 502 from the relay, a socket timeout and a phone
     * in a lift all answer `null` too.
     *
     * That is not hypothetical here. In production, every `/v2` route was measured on
     * 2026-10-29 at 7-20 s per request with ~23 % outright timeouts, so in a
     * group of five the odds of naming at least one innocent member on any
     * given send were better than even — which is exactly the report that
     * started this: "a message appears saying Person 1, 2, 3, 4, 5 must
     * update, naming everyone in the group, when everyone HAS updated".
     *
     * [Missing] is the ONLY answer that may be shown to a user as "they must
     * update". [Unknown] means "ask again later" and must stay silent.
     */
    sealed interface BundleFetch {
        data class Found(val bundle: FetchedBundle) : BundleFetch
        /** The server answered, and it has no bundle for this key (404). */
        data object Missing : BundleFetch
        /** Transient: no network, a 5xx, a timeout, or a malformed body. */
        data object Unknown : BundleFetch
    }

    /**
     * Fetch a peer's bundle, keeping "the server says there is none" apart from
     * "we could not ask". Verifies the SPK signature + TOFU (identity==address).
     *
     * A bundle that fails verification is [BundleFetch.Unknown], not
     * [BundleFetch.Missing]: the peer may well be on a current build and the
     * server may be the thing at fault, and the one behaviour we must never
     * produce is accusing them of running an old app.
     */
    suspend fun fetchBundleOutcome(peerUserKey: String): BundleFetch = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/keys/${signer.encodeIdentity(peerUserKey)}"
            val headers = signer.sign("GET", path, ByteArray(0))
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext BundleFetch.Missing
                if (resp.code !in 200..299) {
                    Log.w("V2KeysClient", "fetchBundle HTTP ${resp.code} — transient, not 'no bundle'")
                    return@withContext BundleFetch.Unknown
                }
                val parsed = parseBundle(resp.body?.string().orEmpty(), peerUserKey)
                if (parsed == null) BundleFetch.Unknown else BundleFetch.Found(parsed)
            }
        } catch (e: Exception) {
            Log.w("V2KeysClient", "fetchBundle failed (transient): ${e.message}")
            BundleFetch.Unknown
        }
    }

    /**
     * Fetch a peer's bundle. Verifies the SPK signature + TOFU (identity==address).
     *
     * Returns `null` for "no bundle" AND for every transient failure, which is
     * fine for the send paths that just need a bundle or a fallback. Anything
     * that turns the answer into a sentence shown to a user must call
     * [fetchBundleOutcome] instead — see __NO_BUNDLE_IS_NOT_A_TIMEOUT_2026_11_02__.
     */
    suspend fun fetchBundle(peerUserKey: String): FetchedBundle? =
        (fetchBundleOutcome(peerUserKey) as? BundleFetch.Found)?.bundle

    /**
     * The verification half, shared by both entry points: SPK signature, then
     * TOFU. `null` means "this body is not a bundle we may use" — never
     * "the peer has none".
     */
    private fun parseBundle(body: String, peerUserKey: String): FetchedBundle? = try {
        val json = JSONObject(body)
        val identityKey = unb64(json.getString("identityKey"))
        val signingKey = unb64(json.getString("signingKey"))
        val spk = json.getJSONObject("signedPreKey")
        val spkPub = unb64(spk.getString("key"))
        val spkSig = unb64(spk.getString("signature"))

        // (a) SPK signature must verify against the peer's signing key.
        if (!cryptoManager.verify(spkPub, spkSig, signingKey)) {
            Log.w("V2KeysClient", "SPK signature invalid for $peerUserKey"); null
        } else if (b64(identityKey) != peerUserKey) {
            // (b) TOFU: the address IS the X25519 identity — a mismatch is MITM.
            Log.w("V2KeysClient", "identityKey != address — possible key substitution"); null
        } else {
            val opk = json.optJSONObject("oneTimePreKey")
            // __LEGACY_COMPAT_2026_09_23__ remember the peer's caps (absent ⇒ 0 = old build).
            GroupLegacyCompat.notePeerCaps(peerUserKey, json.optInt("caps", 0))
            // __V2_PADDING_2026_11_09__ the ACCOUNT session slot this bundle builds (absent ⇒ not capable).
            V2PaddingPeers.noteBundle(peerUserKey, null, if (json.has("caps")) json.optInt("caps", 0) else null)
            FetchedBundle(
                identityKey = identityKey,
                signingKey = signingKey,
                signedPreKeyId = spk.optString("keyId"),
                signedPreKey = spkPub,
                oneTimePreKeyId = opk?.optString("keyId"),
                oneTimePreKey = opk?.getString("key")?.let { unb64(it) },
            )
        }
    } catch (e: Exception) {
        Log.w("V2KeysClient", "bundle body unreadable: ${e.message}"); null
    }

    /** How many one-time prekeys the server still holds for us. Refill when low. */
    suspend fun remainingCount(): Int? = withContext(Dispatchers.IO) {
        try {
            val userKey = cryptoManager.getPublicKeyString()
            val path = "/v2/keys/${signer.encodeIdentity(userKey)}/count"
            val headers = signer.sign("GET", path, ByteArray(0))
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) null
                else JSONObject(resp.body?.string().orEmpty()).optInt("remaining", 0)
            }
        } catch (e: Exception) { null }
    }
}
