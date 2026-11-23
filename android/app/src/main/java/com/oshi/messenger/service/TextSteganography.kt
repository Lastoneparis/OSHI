package com.oshi.messenger.service

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TextSteganography — byte-compatible port of `OSHI/TextSteganography.swift`.
 *
 * Hides an encrypted payload inside innocent-looking cover text. On iOS this is
 * used **only** by the covert-relay transport (`CovertChannelManager.swift:806-807`,
 * reached from `MessageManager.swift:4052 sendCovertViaRelay`, itself gated on
 * `covertChannelEnabled`). It is NEVER applied to an ordinary chat message — see the
 * long note in `MessageRepository.sendMessage`.
 *
 * ## What changed and why (2026-08-13 media-steg audit §3, §4)
 *
 * The previous Kotlin implementation could not interoperate with iOS on any of the
 * four methods. Every divergence below is now closed:
 *
 * | # | was (Kotlin)                                          | now (= iOS)                                                    |
 * |---|--------------------------------------------------------|----------------------------------------------------------------|
 * | 1 | payload was `Base64(nonce‖ct)` **as a String**          | raw `nonce(12)‖ct‖tag(16)` **bytes** (`TextSteganography.swift:382-392`) |
 * | 2 | bitstream had no length header                          | `BE32(len) ‖ bits`, MSB-first (`swift:408-425`)                 |
 * | 3 | key = `SHA256(base64(SHA256(secret‖"OSHI_STEGO_V1")) + "TEXT_STEG_V1")` | `SHA256(rawSharedSecret ‖ "TEXT_STEG_V1")` (`swift:383`)        |
 * | 4 | 25-entry homoglyph map + synthesized uppercase           | the 22 explicit entries at `swift:62-85`                       |
 * | 5 | whitespace bit on EVERY space                           | `inWord` gate — one bit per RUN of spaces (`swift:330-352`)     |
 * | 6 | `combined` split the payload half/half                  | zero-width, FALLBACK to homoglyph (`swift:116-122`)            |
 *
 * ## Wire format (all sizes in bytes, ‖ = concatenation)
 *
 * ```
 * encKey    = SHA256( rawSharedSecret ‖ utf8("TEXT_STEG_V1") )      // swift:383
 * encrypted = nonce(12) ‖ AES-256-GCM ciphertext(N) ‖ tag(16)       // swift:387-391
 * bitstream = BE32(encrypted.size) ‖ bits(encrypted)                // swift:410-424, MSB-first
 * ```
 * Decode gates (`swift:428-455`): at least 32 bits; `0 < declaredLen < 100_000`;
 * at least `32 + declaredLen*8` bits; then exactly `declaredLen` bytes are sliced —
 * trailing bits past the declared length are IGNORED, which is what makes padding a
 * partially filled slot safe.
 *
 * ## Deliberately no Android framework types
 *
 * No `android.util.Base64`, no `android.util.Log`: the whole class must run in a plain
 * JVM unit test (this module has no Robolectric) so the byte layout can be asserted
 * against a literal iOS-format fixture.
 */
@Singleton
class TextSteganography @Inject constructor() {

    companion object {
        /** iOS `TextSteganography.swift:383` — the ONLY label in the key derivation. */
        private const val KEY_SALT = "TEXT_STEG_V1"

        private const val GCM_TAG_BITS = 128         // iOS AES.GCM tag = 16 bytes
        private const val GCM_NONCE_LENGTH = 12      // iOS AES.GCM.Nonce() = 12 bytes

        /** iOS `swift:394` — `guard data.count > 28` (12 nonce + 16 tag). */
        private const val MIN_SEALED_LENGTH = 28

        /** iOS `swift:442` — `guard dataLength > 0, dataLength < 100_000`. */
        internal const val MAX_DECLARED_LENGTH = 100_000

        // Zero-width markers — iOS swift:53-56
        internal const val ZW_ZERO = '\u200B'   // ZERO WIDTH SPACE       → bit 0
        internal const val ZW_ONE = '\u200C'    // ZERO WIDTH NON-JOINER  → bit 1
        internal const val ZW_START = '\uFEFF'  // ZERO WIDTH NO-BREAK SP → start marker
        internal const val ZW_SEP = '\u200D'    // ZERO WIDTH JOINER      → end separator

        internal const val NBSP = '\u00A0'

        /**
         * iOS `TextSteganography.swift:62-85` — EXACTLY these 22 entries, with the
         * uppercase forms listed explicitly.
         *
         * The old Kotlin map had 25 entries (24 lowercase + 'A') and synthesized
         * uppercase with `uppercaseChar()`. Both bugs are fatal on the wire: the 13
         * extra lowercase letters shift every subsequent bit index, and
         * `uppercaseChar()` on a Cyrillic/Armenian/Greek codepoint yields characters
         * that iOS's reverse map (`swift:88-94`) never contains.
         */
        internal val HOMOGLYPH_MAP: Map<Char, Char> = linkedMapOf(
            'a' to '\u0430',  // Cyrillic а
            'c' to '\u0441',  // Cyrillic с
            'e' to '\u0435',  // Cyrillic е
            'o' to '\u043E',  // Cyrillic о
            'p' to '\u0440',  // Cyrillic р
            'x' to '\u0445',  // Cyrillic х
            'y' to '\u0443',  // Cyrillic у
            's' to '\u0455',  // Cyrillic ѕ
            'i' to '\u0456',  // Cyrillic і
            'j' to '\u0458',  // Cyrillic ј
            'h' to '\u04BB',  // Cyrillic һ
            'A' to '\u0410',  // Cyrillic А
            'B' to '\u0412',  // Cyrillic В
            'C' to '\u0421',  // Cyrillic С
            'E' to '\u0415',  // Cyrillic Е
            'H' to '\u041D',  // Cyrillic Н
            'K' to '\u041A',  // Cyrillic К
            'M' to '\u041C',  // Cyrillic М
            'O' to '\u041E',  // Cyrillic О
            'P' to '\u0420',  // Cyrillic Р
            'T' to '\u0422',  // Cyrillic Т
            'X' to '\u0425'   // Cyrillic Х
        )

        internal val REVERSE_HOMOGLYPH_MAP: Map<Char, Char> =
            HOMOGLYPH_MAP.entries.associate { (k, v) -> v to k }

        /**
         * iOS `swift:383` — `SHA256(key ‖ utf8("TEXT_STEG_V1"))` over the RAW 32-byte
         * X25519 shared secret (`MessageManager.swift:4044` →
         * `IdentityManager.swift:489-491` returns `sharedSecret.withUnsafeBytes { Data($0) }`).
         * Exposed for the parity test.
         */
        internal fun deriveKeyBytes(secretKey: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(secretKey + KEY_SALT.toByteArray(Charsets.UTF_8))
    }

    /** iOS `TextStegoMethod` — same four cases. */
    enum class Method { ZERO_WIDTH, HOMOGLYPH, WHITESPACE, COMBINED }

    /** iOS `TextSteganography.swift:372-378` — same five raw values. */
    enum class CoverTextStyle { SOCIAL_COMMENT, PRODUCT_REVIEW, NEWS_COMMENT, TECHNICAL_POST, CASUAL_CHAT }

    // =========================================================================
    // PUBLIC API — mirrors iOS embed(data:in:secretKey:method:) / extract(from:secretKey:)
    // =========================================================================

    /**
     * Embed [data] into [coverText], encrypted under the RAW shared secret.
     * iOS: `TextSteganography.swift:100-122`.
     *
     * @param secretKey the raw 32-byte X25519 shared secret — NOT a passphrase, NOT
     *   base64, NOT pre-hashed. Anything else and iOS cannot decrypt.
     * @return the stego text, or null when the cover text cannot hold the payload
     *   (iOS returns nil in the same cases: `swift:275-277`, `swift:325`).
     */
    fun embed(
        data: ByteArray,
        coverText: String,
        secretKey: ByteArray,
        method: Method = Method.ZERO_WIDTH
    ): String? {
        val encrypted = encryptForText(data, secretKey) ?: return null
        return when (method) {
            Method.ZERO_WIDTH -> embedZeroWidth(encrypted, coverText)
            Method.HOMOGLYPH -> embedHomoglyph(encrypted, coverText)
            Method.WHITESPACE -> embedWhitespace(encrypted, coverText)
            // iOS swift:116-122 — zero-width first, FALL BACK to homoglyph. Not a split.
            Method.COMBINED -> embedZeroWidth(encrypted, coverText) ?: embedHomoglyph(encrypted, coverText)
        }
    }

    /**
     * Extract and decrypt a hidden payload.
     * Auto-detect order is iOS's: zeroWidth → homoglyph → whitespace (`swift:162-173`).
     *
     * @return the decrypted plaintext bytes (on iOS this is then JSON-decoded into a
     *   `CovertFragment`, `swift:180`), or null when nothing decodes.
     */
    fun extract(text: String, secretKey: ByteArray, method: Method? = null): ByteArray? {
        val encrypted = when (method) {
            Method.ZERO_WIDTH -> extractZeroWidth(text)
            Method.HOMOGLYPH -> extractHomoglyph(text)
            Method.WHITESPACE -> extractWhitespace(text)
            // Both `combined` and auto-detect try all three in the same order (swift:150-173)
            else -> extractZeroWidth(text) ?: extractHomoglyph(text) ?: extractWhitespace(text)
        } ?: return null
        return decryptFromText(encrypted, secretKey)
    }

    /**
     * Cheap pre-filter: does this text plausibly carry a payload?
     *
     * TIGHTENED (audit §1 row "containsHiddenData"). The old version returned true for
     * ANY character in the reverse homoglyph map or ANY NBSP, so it fired on ordinary
     * Russian/Greek text and on a word-processor NBSP, then burned a failing decrypt on
     * every such message and logged a misleading "detected but extraction failed".
     *
     * Now a carrier must actually parse: the zero-width start marker, or a bit run that
     * yields a valid BE32 length header (`swift:428-444`).
     */
    fun containsHiddenData(text: String): Boolean {
        if (text.indexOf(ZW_START) >= 0 && (text.indexOf(ZW_ZERO) >= 0 || text.indexOf(ZW_ONE) >= 0)) return true
        if (text.any { it in REVERSE_HOMOGLYPH_MAP } && extractHomoglyph(text) != null) return true
        if (text.indexOf(NBSP) >= 0 && extractWhitespace(text) != null) return true
        return false
    }

    /**
     * How many payload bytes fit in [text]. iOS `swift:520-536`.
     * The `- 4` on every branch is the BE32 length header.
     */
    fun capacity(text: String, method: Method = Method.ZERO_WIDTH): Int = when (method) {
        Method.ZERO_WIDTH -> maxOf(0, (text.length - 1) * 8 / 8 - 4)
        Method.HOMOGLYPH -> maxOf(0, text.count { it in HOMOGLYPH_MAP } / 8 - 4)
        Method.WHITESPACE -> maxOf(0, text.count { it == ' ' } / 8 - 4)
        Method.COMBINED -> capacity(text, Method.ZERO_WIDTH) + capacity(text, Method.HOMOGLYPH)
    }

    /** Strip every steganographic artifact. iOS `swift:539-554`. */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch == ZW_ZERO || ch == ZW_ONE || ch == ZW_SEP || ch == ZW_START) continue
            val ascii = REVERSE_HOMOGLYPH_MAP[ch]
            when {
                ascii != null -> sb.append(ascii)
                ch == NBSP -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // =========================================================================
    // ZERO-WIDTH — iOS swift:186-252
    // =========================================================================

    internal fun embedZeroWidth(data: ByteArray, coverText: String): String? {
        val bits = dataToBits(data)
        val cover = coverText.toCharArray()
        if (cover.size <= 1) return null                      // swift:191

        val slots = cover.size - 1                            // swift:194
        val bitsPerSlot = maxOf(1, (bits.size + slots - 1) / slots)   // swift:195

        val sb = StringBuilder()
        sb.append(cover[0])                                   // swift:198
        sb.append(ZW_START)                                   // swift:203
        var bitIndex = 0
        for (i in 1 until cover.size) {                       // swift:205-217
            if (bitIndex < bits.size) {
                val end = minOf(bitIndex + bitsPerSlot, bits.size)
                for (b in bitIndex until end) sb.append(if (bits[b]) ZW_ONE else ZW_ZERO)
                bitIndex = end
            }
            sb.append(cover[i])
        }
        while (bitIndex < bits.size) {                        // swift:219-222
            sb.append(if (bits[bitIndex]) ZW_ONE else ZW_ZERO)
            bitIndex++
        }
        sb.append(ZW_SEP)                                     // swift:225
        return sb.toString()
    }

    internal fun extractZeroWidth(text: String): ByteArray? {
        // swift:231-252 — record between the start marker and the separator, skipping
        // every visible character in between.
        val bits = ArrayList<Boolean>()
        var recording = false
        for (ch in text) {
            if (ch == ZW_START) { recording = true; continue }
            if (ch == ZW_SEP && recording) break
            if (recording) {
                if (ch == ZW_ZERO) bits.add(false)
                else if (ch == ZW_ONE) bits.add(true)
            }
        }
        if (bits.isEmpty()) return null
        return bitsToData(bits)
    }

    // =========================================================================
    // HOMOGLYPH — iOS swift:257-298
    // =========================================================================

    internal fun embedHomoglyph(data: ByteArray, coverText: String): String? {
        val bits = dataToBits(data)
        val chars = coverText.toCharArray()
        var bitIndex = 0
        for (i in chars.indices) {                            // swift:262-273
            if (bitIndex >= bits.size) break
            val glyph = HOMOGLYPH_MAP[chars[i]] ?: continue    // exact-case lookup only
            if (bits[bitIndex]) chars[i] = glyph               // bit 1 → substitute
            bitIndex++                                         // bit 0 → keep ASCII
        }
        // swift:275-277 — cover text too short is a FAILURE, not a silent truncation.
        if (bitIndex < bits.size) return null
        return String(chars)
    }

    internal fun extractHomoglyph(text: String): ByteArray? {
        val bits = ArrayList<Boolean>()
        for (ch in text) {                                    // swift:287-294
            when {
                ch in REVERSE_HOMOGLYPH_MAP -> bits.add(true)
                ch in HOMOGLYPH_MAP -> bits.add(false)
                else -> {} // everything else ignored
            }
        }
        if (bits.isEmpty()) return null
        return bitsToData(bits)
    }

    // =========================================================================
    // WHITESPACE — iOS swift:303-352
    // =========================================================================

    internal fun embedWhitespace(data: ByteArray, coverText: String): String? {
        val bits = dataToBits(data)
        // swift:305 — `components(separatedBy: " ")`; Kotlin's split(" ") matches it,
        // including empty components for runs of spaces.
        val words = coverText.split(" ")
        if (words.size <= 1) return null                      // swift:306

        val sb = StringBuilder(words[0])
        var bitIndex = 0
        for (i in 1 until words.size) {                       // swift:311-322
            if (bitIndex < bits.size) {
                sb.append(if (bits[bitIndex]) NBSP else ' ')
                bitIndex++
            } else {
                sb.append(' ')
            }
            sb.append(words[i])
        }
        if (bitIndex < bits.size) return null                 // swift:325
        return sb.toString()
    }

    internal fun extractWhitespace(text: String): ByteArray? {
        // swift:330-348 — the `inWord` gate. A bit is recorded ONLY for the first
        // space/NBSP that follows a non-space character, so a RUN of spaces (or a
        // leading space) contributes exactly one bit, not one per character. Without
        // this, any double space injects a phantom bit and the whole stream desyncs.
        val bits = ArrayList<Boolean>()
        var inWord = false
        for (ch in text) {
            when (ch) {
                ' ' -> if (inWord) { bits.add(false); inWord = false }
                NBSP -> if (inWord) { bits.add(true); inWord = false }
                else -> inWord = true
            }
        }
        if (bits.isEmpty()) return null
        return bitsToData(bits)
    }

    // =========================================================================
    // ENCRYPTION — iOS swift:382-405
    // =========================================================================

    internal fun encryptForText(data: ByteArray, key: ByteArray): ByteArray? = try {
        val encKey = SecretKeySpec(deriveKeyBytes(key), "AES")
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        // JCE appends the 16-byte tag to the ciphertext, which is exactly iOS's
        // `ciphertext ‖ tag` ordering at swift:389-390.
        nonce + cipher.doFinal(data)
    } catch (e: Throwable) {
        null
    }

    internal fun decryptFromText(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size <= MIN_SEALED_LENGTH) return null       // swift:395
        return try {
            val encKey = SecretKeySpec(deriveKeyBytes(key), "AES")
            val nonce = data.copyOfRange(0, GCM_NONCE_LENGTH)
            val body = data.copyOfRange(GCM_NONCE_LENGTH, data.size)  // ct ‖ tag
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(body)
        } catch (e: Throwable) {
            null
        }
    }

    // =========================================================================
    // BIT CONVERSION — iOS swift:408-458
    // =========================================================================

    /** `BE32(data.size) ‖ bits(data)`, MSB-first within each byte. iOS `swift:408-425`. */
    internal fun dataToBits(data: ByteArray): List<Boolean> {
        val bits = ArrayList<Boolean>(32 + data.size * 8)
        val len = data.size
        val header = byteArrayOf(
            ((len ushr 24) and 0xFF).toByte(),
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
        for (b in header) for (i in 7 downTo 0) bits.add((b.toInt() shr i) and 1 == 1)
        for (b in data) for (i in 7 downTo 0) bits.add((b.toInt() shr i) and 1 == 1)
        return bits
    }

    /** Inverse of [dataToBits] with iOS's exact gates. iOS `swift:427-458`. */
    internal fun bitsToData(bits: List<Boolean>): ByteArray? {
        if (bits.size < 32) return null                       // swift:428
        var declared = 0
        for (i in 0 until 32) {                               // swift:431-440 (big-endian)
            declared = (declared shl 1) or (if (bits[i]) 1 else 0)
        }
        if (declared <= 0 || declared >= MAX_DECLARED_LENGTH) return null   // swift:442
        val totalBits = 32 + declared * 8
        if (bits.size < totalBits) return null                // swift:443-444

        val out = ByteArray(declared)
        for (i in 0 until declared * 8) {                     // swift:448-455
            if (bits[32 + i]) {
                out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
        }
        // Trailing bits past the declared length are ignored — swift:201 of the spec.
        return out
    }

    // =========================================================================
    // COVER TEXT — ported verbatim from iOS swift:462-515
    // =========================================================================

    /**
     * iOS `swift:462-472` + the five generators at `:462-515`: 5 styles × 5 templates,
     * each ~350-450 characters. The previous Kotlin corpus had 4 templates per style at
     * ~150-200 characters — roughly half the embedding capacity and a distinct
     * fingerprint from iOS's traffic.
     */
    fun generateCoverText(style: CoverTextStyle = CoverTextStyle.SOCIAL_COMMENT): String =
        coverTemplates(style).random()

    internal fun coverTemplates(style: CoverTextStyle): List<String> = when (style) {
        // iOS generateSocialComment — swift:463-472
        CoverTextStyle.SOCIAL_COMMENT -> listOf(
            "I really appreciate how this community comes together to share ideas and experiences. The discussions here have been incredibly helpful for understanding different perspectives on current events. It is always refreshing to see people engage in thoughtful conversation rather than just arguing about superficial topics. I hope we can continue to maintain this level of discourse and keep supporting each other through challenging times.",
            "This is exactly what I was looking for today. Sometimes you just need to take a step back and appreciate the small things in life. The weather has been really nice lately and I have been trying to spend more time outdoors. Anyone else feeling like this spring is going to be a good one? I have a feeling that things are starting to look up for a lot of people around here.",
            "Just wanted to share my thoughts on this topic since I have been thinking about it for a while now. There are so many different angles to consider and I think we often oversimplify complex issues. The reality is that most situations have nuances that we tend to overlook when we are caught up in our daily routines. Taking time to reflect on these things can really help us grow as individuals.",
            "Had an amazing experience today that I just had to share with everyone here. It is incredible how a simple act of kindness can completely change your perspective on things. I was at the local market this morning and a complete stranger helped me carry my groceries to the car. It reminded me that there are still so many good people out there who genuinely care about others.",
            "I have been reading a lot lately about different approaches to personal development and wellness. There is so much information available these days that it can be overwhelming to know where to start. What I have found most helpful is focusing on small consistent changes rather than trying to overhaul everything at once. Progress is progress no matter how small it might seem at first."
        )
        // iOS generateProductReview — swift:475-484
        CoverTextStyle.PRODUCT_REVIEW -> listOf(
            "After using this product for about three weeks now I can confidently say it has exceeded my expectations in almost every way. The build quality is solid and it feels premium without being overly expensive. Setup was straightforward and took less than ten minutes. The performance has been consistent and reliable which is exactly what I was looking for. I would definitely recommend this to anyone who needs a dependable solution for everyday use.",
            "I was initially skeptical about this purchase based on some of the mixed reviews I had read online. However after giving it a fair chance I have to say I am pleasantly surprised. The product does exactly what it claims to do and the customer support team was very helpful when I had a question about the settings. Shipping was fast and the packaging was secure. Overall a solid experience from start to finish.",
            "This has quickly become one of my favorite purchases this year. The attention to detail is impressive and you can tell that a lot of thought went into the design. It works seamlessly with my existing setup and has actually improved my workflow quite a bit. The price point is reasonable considering the quality and features you get. I have already recommended it to several friends and family members.",
            "I wanted to wait a full month before writing this review to make sure I had a comprehensive understanding of the product. During that time I have used it daily and I am happy to report that it has held up remarkably well. There are a few minor things I would change but nothing that significantly impacts the overall experience. The battery life is excellent and charges quickly.",
            "Five stars from me without hesitation. I have tried several similar products over the years and this one stands out for its reliability and ease of use. The instruction manual was clear and well written which made getting started a breeze. I particularly appreciate the thoughtful design choices that make everyday tasks more convenient. Great value for the price and I would buy it again in a heartbeat."
        )
        // iOS generateNewsComment — swift:487-496
        CoverTextStyle.NEWS_COMMENT -> listOf(
            "This is a really interesting development that could have significant implications for the industry going forward. I have been following this story closely and it seems like there are multiple factors at play that many people are not considering. The economic aspects alone are quite complex and I think we need to wait for more information before drawing any definitive conclusions about what this means for the average consumer.",
            "Thank you for reporting on this story. It is important that these issues get the attention they deserve. I think the key takeaway here is that we need better transparency and accountability in how these decisions are being made. The public has a right to know what is happening and why. I hope the relevant authorities take appropriate action to address the concerns raised in this article.",
            "I read this article with great interest and I have to say the analysis provided is quite thorough. The data points mentioned are particularly striking and really help put things in perspective. It would be great to see a follow-up piece that explores some of the longer-term implications of these trends. The situation is clearly evolving and I think there is much more to this story than meets the eye.",
            "As someone who has been working in this field for over a decade I can confirm that the trends described in this article are very real and concerning. What many people do not realize is that these changes have been building up gradually over the past several years. The current situation is really just the culmination of a series of policy decisions and market shifts that were predictable in hindsight.",
            "Very well written article that captures the complexity of this issue without oversimplifying it. I appreciate the balanced approach and the inclusion of multiple perspectives. Too often we see reporting that only tells one side of the story. This kind of thoughtful journalism is exactly what we need more of in today is media landscape."
        )
        // iOS generateTechnicalPost — swift:499-508
        CoverTextStyle.TECHNICAL_POST -> listOf(
            "I have been working on optimizing the performance of our application and wanted to share some findings that might be helpful for others facing similar challenges. After extensive profiling we identified several bottlenecks in the data processing pipeline that were causing significant latency issues. By restructuring the caching layer and implementing batch processing for database queries we managed to reduce response times by approximately forty percent.",
            "For anyone struggling with implementing authentication in their mobile application I wanted to document the approach that worked well for us. We ended up using a combination of token-based authentication with refresh tokens and secure storage for sensitive credentials. The key was making sure the token rotation logic was robust enough to handle edge cases like network interruptions and concurrent requests.",
            "Just finished migrating our infrastructure to a new architecture and wanted to share some lessons learned along the way. The biggest challenge was ensuring zero downtime during the transition which required careful planning and extensive testing. We set up a parallel environment and gradually shifted traffic using weighted routing. The entire process took about six weeks from planning to completion.",
            "Here is a comprehensive guide to setting up continuous integration and deployment for your project based on our recent experience. The most important thing we learned is that investing time upfront in writing good tests pays enormous dividends down the line. We now have over ninety percent code coverage and our deployment pipeline catches most issues before they reach production.",
            "I wanted to share our experience with implementing real-time features in our web application. After evaluating several options we decided to go with a combination of server-sent events for push notifications and long-polling as a fallback for older browsers. The implementation was surprisingly straightforward once we had the right architecture in place and the user experience improvement was immediately noticeable."
        )
        // iOS generateCasualChat — swift:511-520
        CoverTextStyle.CASUAL_CHAT -> listOf(
            "Hey how is everything going with you lately? I feel like we have not caught up in ages. Things have been pretty busy on my end with work and everything but I am trying to make more time for the things that matter. Did you end up going on that trip you were planning? I remember you mentioned something about it last time we talked and I have been curious about how it went.",
            "I have been meaning to tell you about this great place I discovered last weekend. It is a small cafe tucked away on a side street that I had never noticed before even though I walk past it almost every day. They have the most amazing pastries and the coffee is really good too. We should definitely check it out together sometime when you are free.",
            "Can you believe how fast this year is going by already? It feels like just yesterday we were making plans for the new year and here we are months later wondering where the time went. I have been trying to be more intentional about how I spend my time and it has made a real difference. Even just taking a few minutes each morning to plan out my day helps a lot.",
            "I just finished watching this incredible series that I think you would really enjoy. It is one of those shows that starts off slowly but gets progressively better with each episode. By the third episode I was completely hooked and ended up binge watching the entire season in one weekend. The storytelling is really well done and the characters are surprisingly complex.",
            "So I finally got around to trying that recipe you recommended and I have to say it turned out way better than I expected. I was a bit nervous about some of the steps since I am not exactly a confident cook but the instructions were really clear and easy to follow. I made a few small modifications based on what I had available and it still turned out great. Thanks for the suggestion."
        )
    }
}
