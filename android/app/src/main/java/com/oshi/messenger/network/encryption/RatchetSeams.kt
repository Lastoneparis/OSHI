package com.oshi.messenger.network.encryption

/**
 * The two platform seams the Double Ratchet needs — base64 and a log sink — with no
 * `android.*` anywhere, so the ratchet itself can be COMPILED BY THE DESKTOP CLIENT
 * instead of reimplemented there.
 *
 * __RATCHET_IS_SHARED_SOURCE_2026_10_28__ `OSHI-Desktop/PARITY.md` row 0.16 recorded the
 * legacy ratchet as unportable: *"`DoubleRatchet.kt` (855 lines) and `CryptoManager.kt`
 * (681) import `android.util.Base64`, `android.util.Log`, `android.content.Context` and
 * `EncryptedSharedPreferences` … So making a desktop talk to a PHONE over the mesh means
 * porting a SECOND ratchet — exactly the 'third independent implementation' PLAN.md §1
 * argues against, and this time with no vectors published to check it against."*
 *
 * That was a true description of the file and the wrong conclusion about the protocol. The
 * coupling was four imports' worth, and none of it was in the cryptography: base64, a
 * logger, and a persistence shell around the already-pure `toJSON`/`fromJSON`. Removing it
 * means the phone and the desktop run the SAME ratchet — not two implementations that must
 * be proven equal, which is a problem nobody has to have.
 *
 * This is the pattern `build.gradle.kts` already describes for `CallRatingPolicy` (shared,
 * pure) versus `CallRatingManager` (Context + prefs + Log, each client's own): share the
 * rules, keep the seams local. See `DoubleRatchetAndroidStore.kt` for Android's half.
 */

/**
 * Base64 in the one spelling this protocol uses.
 *
 * Deliberately mirrors `android.util.Base64`'s signature, flag argument included, so the
 * change in `DoubleRatchet.kt` is one token per call site — `Base64.` to `RatchetBase64.` —
 * and a reviewer can see at a glance that no call's SHAPE changed. Every one of the 24 call
 * sites passed [NO_WRAP] and nothing else.
 */
object RatchetBase64 {

    /** Mirrors `android.util.Base64.NO_WRAP`. Kept so call sites read unchanged. */
    const val NO_WRAP = 2

    /**
     * Standard base64, padded, no line breaks — byte-identical to Android's
     * `encodeToString(data, NO_WRAP)` and to what Swift's `Data.base64EncodedString()`
     * produces, which is what makes an iOS header parse here at all.
     */
    @JvmStatic
    fun encodeToString(data: ByteArray, flags: Int = NO_WRAP): String =
        java.util.Base64.getEncoder().encodeToString(data)

    /**
     * The MIME decoder, not the strict one, and the choice matters.
     *
     * `java.util.Base64.getDecoder()` throws on any character outside the alphabet —
     * including the newline a wrapped encoder emits — whereas `android.util.Base64.decode`
     * skips them. Using the strict decoder would make this REJECT input the shipped Android
     * build accepts today, which on this lane means a peer's message silently failing to
     * decrypt. The MIME decoder accepts a superset, so no currently-working peer can break;
     * anything genuinely malformed still fails, one step later, at the AEAD tag.
     */
    @JvmStatic
    fun decode(encoded: String, flags: Int = NO_WRAP): ByteArray =
        java.util.Base64.getMimeDecoder().decode(encoded)
}

/**
 * Where the ratchet's diagnostics go.
 *
 * Android installs `android.util.Log`; the desktop installs its own logger; a unit test
 * installs nothing and gets silence, which is why the default is a no-op rather than
 * `println`. The signature mirrors `android.util.Log` for the same one-token reason as
 * [RatchetBase64].
 *
 * **This sink must never be given key material.** `DoubleRatchet.kt`'s `LOG_SENSITIVE`
 * compile-time guard is what enforces that and it stays false in release; moving the sink
 * behind an interface does not relax it, and a sink that a client could point at a file
 * makes the guard matter MORE, not less.
 */
object RatchetLog {

    /** One sink, installed once by the client. Volatile: installed on a different thread. */
    fun interface Sink {
        fun log(level: Char, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    @JvmStatic
    var sink: Sink? = null

    @JvmStatic fun d(tag: String, message: String) { sink?.log('D', tag, message, null) }
    @JvmStatic fun w(tag: String, message: String) { sink?.log('W', tag, message, null) }
    @JvmStatic fun e(tag: String, message: String) { sink?.log('E', tag, message, null) }
    @JvmStatic fun e(tag: String, message: String, error: Throwable?) { sink?.log('E', tag, message, error) }
}
