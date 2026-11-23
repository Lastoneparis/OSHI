package com.oshi.messenger.ui.viewmodel

import java.util.Base64

/**
 * What a pasted recipient address has to be before the New Message screen may
 * call it valid.
 *
 * ## Why this exists
 *
 * The rule this replaces was `trimmed.length >= 20 && trimmed != myPublicKey`
 * — a port of iOS `NewMessageView.validateAddress()`
 * (`NewMessageView.swift:650-653`). It is the first thing a new user does, and
 * it showed a green "Valid address" for strings that cannot possibly be a key,
 * e.g. a key with an autocomplete word and two spaces in the middle of it, a
 * key with one stray character typed after the `=`, and a key whose padding had
 * been eaten somewhere in the copy chain. Each was accepted, opened a
 * conversation, and then failed at send with nothing on screen to explain it.
 *
 * An OSHI public key is not "20-odd characters". It is the **raw 32-byte X25519
 * public key**, base64-encoded — `CryptoManager.getPublicKeyString()` on this
 * side, `encryptionKey.publicKey.rawRepresentation.base64EncodedString()` on iOS
 * (`IdentityManager.swift:296`). 32 bytes is what actually makes a key a key:
 * `CryptoManager.importPublicKey` rejects anything else
 * (`CryptoManager.kt:213`), and that rejection is the "fails later at send" the
 * old validator was hiding.
 *
 * ## Two spellings of the same key
 *
 * A key reaches this app in more than one spelling. The VPS endpoints build
 * paths out of base64url (`VPSClient.publicKeyToBase64url`), iOS's VoIP push
 * delivers the caller's key as base64url while the contact photo was stored
 * under standard base64 (`IncomingCallOverlay.swift:37-57` — the avatar went
 * missing on the ringing screen for exactly this reason), and
 * `MultiDeviceSyncManager` strips the padding for its URL form. So both
 * spellings are *accepted* here — and then immediately **canonicalised** to the
 * one spelling the rest of the app stores and compares on: standard base64,
 * padded, 44 characters. Handing the raw paste onwards is what files a
 * conversation under a key nobody else uses and sends messages to nobody.
 *
 * Everything here is pure Kotlin (`java.util.Base64`, available from API 26 =
 * this app's `minSdk`), so it is unit-testable on the JVM with no Android
 * runtime and no Hilt.
 */
object RecipientKey {

    /** A raw X25519 public key. `CryptoManager.KEY_SIZE`. */
    const val KEY_BYTES = 32

    /** 32 bytes of base64 payload: `ceil(32 / 3) * 4 - 1` = 43 characters. */
    const val BODY_LENGTH = 43

    /** …plus the single `=` that closes it: the canonical spelling is 44 long. */
    const val CANONICAL_LENGTH = 44

    /**
     * Why an address was refused. One case per thing a user can actually do to a
     * key, because "Invalid address format" tells a first-time user nothing.
     */
    enum class Problem {
        /** Nothing (or only whitespace) was entered. */
        EMPTY,

        /**
         * Whitespace *inside* the key. Leading and trailing whitespace is
         * trimmed and tolerated — a paste picks up a newline — but a space in
         * the middle means something else was selected along with the key.
         */
        CONTAINS_WHITESPACE,

        /** Something follows the `=` that ends the key. */
        TRAILING_CHARACTERS,

        /**
         * Standard-alphabet spelling whose `=` has been stripped. Every producer
         * of a standard-base64 key in OSHI and on iOS pads it, so an unpadded
         * one did not come from a key producer — it lost characters on the way.
         * (Unpadded *base64url* is a real spelling and is accepted; see
         * [validate].)
         */
        MISSING_PADDING,

        /** Fewer characters than a 32-byte key can be written in. */
        TOO_SHORT,

        /** More characters than a 32-byte key can be written in. */
        TOO_LONG,

        /**
         * The right length, but not a key: a character outside the alphabet,
         * both alphabets mixed together, or a non-canonical encoding that would
         * give the same 32 bytes a second spelling.
         */
        NOT_A_KEY,

        /**
         * Our own key. Messaging yourself creates a conversation whose peer is
         * you, which no send path handles. Compared on the **canonical** form,
         * so pasting your own key in its base64url spelling is caught too — the
         * old `trimmed != myPublicKey` string compare was not.
         */
        OWN_KEY,
    }

    sealed interface Result {
        /**
         * @property canonicalKey standard base64, padded — the spelling
         *   `CryptoManager` and every store in the app key on. Send this, not
         *   the raw paste.
         */
        data class Valid(val canonicalKey: String) : Result

        data class Invalid(val problem: Problem) : Result
    }

    private const val STANDARD_ONLY = "+/"
    private const val URL_ONLY = "-_"

    private fun Char.isBase64Body(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this in STANDARD_ONLY || this in URL_ONLY

    /**
     * Rewrite [key] into the one spelling the app stores, or return null if it
     * is not a 32-byte key in any spelling.
     *
     * Deliberately the same tolerance as `CryptoManager.importPublicKey`
     * (`CryptoManager.kt:202-217`): base64url is folded to standard and the
     * padding is restored. The difference is that this one *also* refuses a
     * non-canonical encoding, so a key can never acquire a second spelling that
     * decodes to the same bytes.
     */
    fun canonicalize(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        val padCount = trimmed.takeLastWhile { it == '=' }.length
        val body = trimmed.dropLast(padCount)
        if (body.length != BODY_LENGTH || padCount > 1) return null
        if (!body.all { it.isBase64Body() }) return null
        val standard = body.replace('-', '+').replace('_', '/') + "="
        val decoded = try {
            Base64.getDecoder().decode(standard)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (decoded.size != KEY_BYTES) return null
        // Re-encode and insist on a round trip. base64's decoder ignores the
        // two spare bits in the 43rd character, so `…AE` and `…AF` decode to
        // the same 32 bytes — accepting both would hand the same key two
        // spellings, which is the bug this whole file exists to prevent.
        val canonical = Base64.getEncoder().encodeToString(decoded)
        return if (canonical == standard) canonical else null
    }

    /**
     * Classify [address] as a recipient for the user whose own key is
     * [myPublicKey].
     *
     * Accepted spellings, all of which must decode to exactly [KEY_BYTES]:
     *  - standard base64, padded — 44 chars, `A–Z a–z 0–9 + /`, one `=`.
     *    This is what `CryptoManager.getPublicKeyString()` and iOS emit.
     *  - base64url, padded or unpadded — `-` and `_` in place of `+` and `/`.
     *    Unpadded is base64url's ordinary spelling (it is what
     *    `VPSClient.publicKeyToBase64url` and iOS's `urlEncodedKey` produce),
     *    so refusing it would refuse a key the app itself hands out.
     *
     * Refused, among others: whitespace inside the key, anything after the `=`,
     * a **standard**-alphabet key with its padding stripped (no producer in
     * OSHI emits one, so it is a mangled paste), the two alphabets mixed, any
     * length that is not 32 bytes, and our own key in either spelling.
     */
    fun validate(address: String, myPublicKey: String): Result {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Problem.EMPTY)
        if (trimmed.any { it.isWhitespace() }) {
            return Result.Invalid(Problem.CONTAINS_WHITESPACE)
        }

        val padCount = trimmed.takeLastWhile { it == '=' }.length
        val body = trimmed.dropLast(padCount)
        // A '=' anywhere but the very end means the key ended and the user (or
        // an autocomplete) kept typing.
        if (body.contains('=')) return Result.Invalid(Problem.TRAILING_CHARACTERS)

        when {
            body.length < BODY_LENGTH -> return Result.Invalid(Problem.TOO_SHORT)
            body.length > BODY_LENGTH -> return Result.Invalid(Problem.TOO_LONG)
            padCount > 1 -> return Result.Invalid(Problem.TOO_LONG)
        }

        if (!body.all { it.isBase64Body() }) return Result.Invalid(Problem.NOT_A_KEY)

        val hasStandardOnly = body.any { it in STANDARD_ONLY }
        val hasUrlOnly = body.any { it in URL_ONLY }
        if (hasStandardOnly && hasUrlOnly) return Result.Invalid(Problem.NOT_A_KEY)
        // Standard alphabet, right length, no '=' — a padded key that lost its
        // tail. base64url is allowed to omit it; standard base64 never does in
        // this codebase.
        if (padCount == 0 && hasStandardOnly) return Result.Invalid(Problem.MISSING_PADDING)

        val canonical = canonicalize(trimmed) ?: return Result.Invalid(Problem.NOT_A_KEY)

        val mine = canonicalize(myPublicKey)
        val isMine = if (mine != null) canonical == mine else trimmed == myPublicKey.trim()
        if (isMine) return Result.Invalid(Problem.OWN_KEY)

        return Result.Valid(canonical)
    }

    /** The canonical key we should actually send to, or null if [address] is not one. */
    fun canonicalRecipientOrNull(address: String, myPublicKey: String): String? =
        (validate(address, myPublicKey) as? Result.Valid)?.canonicalKey
}
