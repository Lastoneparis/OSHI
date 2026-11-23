package com.oshi.messenger.network.v2

import com.oshi.messenger.network.v2.OSHICryptoV2.Header
import com.oshi.messenger.network.v2.OSHICryptoV2.X25519Pair

/**
 * Signal Double Ratchet state machine, seeded from the X3DH SK, on top of the
 * vector-verified [OSHICryptoV2] primitives. Ports iOS OSHICryptoV2.swift
 * (ratchetInitAlice/Bob, ratchetEncrypt, ratchetDecrypt, dhRatchet, skipMessageKeys).
 *
 * The KDFs / AEAD / header encoding it uses are already proven byte-identical to
 * iOS (V2CryptoVectorTest), so an Android session interoperates with an iOS one.
 * The state-machine logic itself is proven by the Alice<->Bob round-trip test
 * (in-order, out-of-order, and a DH-ratchet reply).
 *
 * MKSKIPPED is a LOCAL store (never transmitted), so its string key format need
 * not match iOS — we use hex(dhPub):n.
 */
object OSHIRatchetV2 {

    private const val MAX_SKIP = 1000

    /** Mutable per-conversation ratchet state. */
    class State(
        var dhs: X25519Pair,          // our current ratchet key pair
        var dhr: ByteArray?,          // their current ratchet public key
        var rk: ByteArray,            // root key
        var cks: ByteArray?,          // sending chain key
        var ckr: ByteArray?,          // receiving chain key
        var ns: Long = 0,             // sending message number
        var nr: Long = 0,             // receiving message number
        var pn: Long = 0,             // previous sending chain length
        val skipped: MutableMap<String, ByteArray> = HashMap(),
    )

    /** Alice = X3DH initiator; performs the first DH ratchet immediately. */
    fun initAlice(sk: ByteArray, bobRatchetPub: ByteArray): State {
        val dhs = OSHICryptoV2.generateX25519()
        val (rk, cks) = OSHICryptoV2.kdfRK(sk, OSHICryptoV2.dh(dhs.priv, bobRatchetPub))
        return State(dhs = dhs, dhr = bobRatchetPub.copyOf(), rk = rk, cks = cks, ckr = null)
    }

    /** Bob = X3DH responder; his initial ratchet pair is his signed-prekey pair. */
    fun initBob(sk: ByteArray, bobRatchetPair: X25519Pair): State =
        State(dhs = bobRatchetPair, dhr = null, rk = sk.copyOf(), cks = null, ckr = null)

    fun encrypt(state: State, plaintext: ByteArray, associatedData: ByteArray): Pair<Header, ByteArray> {
        val cks = state.cks ?: throw IllegalStateException("no sending chain yet")
        val (ck, mk) = OSHICryptoV2.kdfCK(cks)
        state.cks = ck
        val header = Header(dh = state.dhs.pub.copyOf(), pn = state.pn, n = state.ns)
        state.ns += 1
        val (key, nonce) = OSHICryptoV2.deriveMsgKey(mk)
        val aad = associatedData + OSHICryptoV2.encodeHeader(header)
        val ct = OSHICryptoV2.aesGcmSeal(key, nonce, plaintext, aad)
        return header to ct
    }

    /** Commit state only after authentication succeeds, including skipped-key removal.
     * A damaged/replayed carrier must not consume the key needed by a valid retry.
     */
    fun decrypt(state: State, header: Header, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        val working = State(
            dhs = X25519Pair(state.dhs.priv.copyOf(), state.dhs.pub.copyOf()),
            dhr = state.dhr?.copyOf(), rk = state.rk.copyOf(),
            cks = state.cks?.copyOf(), ckr = state.ckr?.copyOf(),
            ns = state.ns, nr = state.nr, pn = state.pn,
            skipped = state.skipped.mapValues { it.value.copyOf() }.toMutableMap(),
        )
        val plaintext = decryptAuthenticated(working, header, ciphertext, associatedData)
        state.dhs = working.dhs; state.dhr = working.dhr; state.rk = working.rk
        state.cks = working.cks; state.ckr = working.ckr
        state.ns = working.ns; state.nr = working.nr; state.pn = working.pn
        state.skipped.clear(); state.skipped.putAll(working.skipped)
        return plaintext
    }

    private fun decryptAuthenticated(state: State, header: Header, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        require(header.dh.size == 32 && header.n in 0..0xffffffffL && header.pn in 0..0xffffffffL)
        trySkipped(state, header, ciphertext, associatedData)?.let { return it }

        if (state.dhr == null || !header.dh.contentEquals(state.dhr)) {
            skipMessageKeys(state, header.pn)   // finish the previous chain
            dhRatchet(state, header)
        }
        skipMessageKeys(state, header.n)        // catch up on the current chain
        val ckr = state.ckr ?: throw IllegalStateException("no receiving chain")
        val (ck, mk) = OSHICryptoV2.kdfCK(ckr)
        state.ckr = ck
        state.nr += 1
        return openWithMk(mk, header, ciphertext, associatedData)
    }

    private fun trySkipped(state: State, header: Header, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
        val k = skKey(header.dh, header.n)
        val mk = state.skipped.remove(k) ?: return null
        return openWithMk(mk, header, ciphertext, ad)
    }

    private fun openWithMk(mk: ByteArray, header: Header, ciphertext: ByteArray, ad: ByteArray): ByteArray {
        val (key, nonce) = OSHICryptoV2.deriveMsgKey(mk)
        val aad = ad + OSHICryptoV2.encodeHeader(header)
        return OSHICryptoV2.aesGcmOpen(key, nonce, ciphertext, aad)
    }

    private fun skipMessageKeys(state: State, until: Long) {
        val ckr = state.ckr ?: return
        if (until - state.nr > MAX_SKIP) throw IllegalStateException("too many skipped messages")
        val dhr = state.dhr ?: return
        var chain = ckr
        while (state.nr < until) {
            check(state.skipped.size < MAX_SKIP) { "too many retained skipped messages" }
            val (ck, mk) = OSHICryptoV2.kdfCK(chain)
            chain = ck
            state.skipped[skKey(dhr, state.nr)] = mk
            state.nr += 1
        }
        state.ckr = chain
    }

    private fun dhRatchet(state: State, header: Header) {
        state.pn = state.ns
        state.ns = 0
        state.nr = 0
        state.dhr = header.dh.copyOf()
        val step1 = OSHICryptoV2.kdfRK(state.rk, OSHICryptoV2.dh(state.dhs.priv, state.dhr!!))
        state.rk = step1.first
        state.ckr = step1.second
        state.dhs = OSHICryptoV2.generateX25519()
        val step2 = OSHICryptoV2.kdfRK(state.rk, OSHICryptoV2.dh(state.dhs.priv, state.dhr!!))
        state.rk = step2.first
        state.cks = step2.second
    }

    private fun skKey(dhPub: ByteArray, n: Long): String =
        dhPub.joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ":" + n
}
