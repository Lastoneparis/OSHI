//
//  TextSteganography.swift
//  OSHI - Text-Based Covert Channels
//
//  Hide encrypted data inside innocent-looking text using:
//
//  1. Zero-Width Unicode Characters
//     - U+200B (Zero Width Space)       = bit 0
//     - U+200C (Zero Width Non-Joiner)  = bit 1
//     - U+200D (Zero Width Joiner)      = separator
//     - U+FEFF (Zero Width No-Break Space) = start marker
//     - Invisible to human readers, survives most copy-paste
//
//  2. Homoglyph Substitution
//     - Replace ASCII chars with visually identical Unicode (Cyrillic, Greek, etc.)
//     - 'a' (U+0061) vs 'а' (U+0430 Cyrillic) — looks identical
//     - 'e' (U+0065) vs 'е' (U+0435 Cyrillic) — looks identical
//     - Binary encoding: ASCII = 0, homoglyph = 1
//
//  3. Whitespace Encoding
//     - Normal space (U+0020) = 0
//     - Non-breaking space (U+00A0) = 1
//     - Thin space (U+2009) = separator
//     - Encode bits in word spacing
//
//  4. Cover Text Generation
//     - Generate natural-looking cover text for various contexts
//     - News-style, social media comments, product reviews, etc.
//
//  All methods encrypt data with AES-256-GCM before embedding.
//

import Foundation
import CryptoKit

// MARK: - Text Steganography Engine

final class TextSteganography {
    static let shared = TextSteganography()
    private init() {}

    // MARK: - Encoding Methods

    enum TextStegoMethod: String, CaseIterable, Codable {
        case zeroWidth        // Zero-width Unicode characters
        case homoglyph        // Visual lookalike substitution
        case whitespace       // Space type substitution
        case combined         // All methods layered (maximum capacity)
    }

    // MARK: - Zero-Width Unicode Constants

    private let zwBit0: Character = "\u{200B}"  // Zero Width Space = 0
    private let zwBit1: Character = "\u{200C}"  // Zero Width Non-Joiner = 1
    private let zwSep: Character  = "\u{200D}"  // Zero Width Joiner = separator
    private let zwStart: Character = "\u{FEFF}" // Zero Width No-Break Space = start marker

    // MARK: - Homoglyph Mapping (ASCII → visually identical Unicode)

    /// Maps ASCII characters to their Cyrillic/Greek/Latin-Extended lookalikes
    /// ASCII version = bit 0, homoglyph version = bit 1
    private let homoglyphMap: [Character: Character] = [
        "a": "\u{0430}",  // Cyrillic а
        "c": "\u{0441}",  // Cyrillic с
        "e": "\u{0435}",  // Cyrillic е
        "o": "\u{043E}",  // Cyrillic о
        "p": "\u{0440}",  // Cyrillic р
        "x": "\u{0445}",  // Cyrillic х
        "y": "\u{0443}",  // Cyrillic у
        "s": "\u{0455}",  // Cyrillic ѕ
        "i": "\u{0456}",  // Cyrillic і
        "j": "\u{0458}",  // Cyrillic ј
        "h": "\u{04BB}",  // Cyrillic һ
        "A": "\u{0410}",  // Cyrillic А
        "B": "\u{0412}",  // Cyrillic В
        "C": "\u{0421}",  // Cyrillic С
        "E": "\u{0415}",  // Cyrillic Е
        "H": "\u{041D}",  // Cyrillic Н
        "K": "\u{041A}",  // Cyrillic К
        "M": "\u{041C}",  // Cyrillic М
        "O": "\u{041E}",  // Cyrillic О
        "P": "\u{0420}",  // Cyrillic Р
        "T": "\u{0422}",  // Cyrillic Т
        "X": "\u{0425}",  // Cyrillic Х
    ]

    /// Reverse map for decoding
    private lazy var reverseHomoglyphMap: [Character: Character] = {
        var reverse: [Character: Character] = [:]
        for (ascii, glyph) in homoglyphMap {
            reverse[glyph] = ascii
        }
        return reverse
    }()

    // MARK: - Embed Data in Text

    /// Embed encrypted data into cover text using the best method
    /// Returns stego text that looks normal but contains hidden data
    func embed(
        data: Data,
        in coverText: String,
        secretKey: Data,
        method: TextStegoMethod = .zeroWidth
    ) -> String? {
        // Encrypt data first
        guard let encrypted = encryptForText(data, key: secretKey) else { return nil }

        switch method {
        case .zeroWidth:
            return embedZeroWidth(encrypted, in: coverText)
        case .homoglyph:
            return embedHomoglyph(encrypted, in: coverText)
        case .whitespace:
            return embedWhitespace(encrypted, in: coverText)
        case .combined:
            // Use zero-width as primary, fall back to homoglyph for overflow
            if let result = embedZeroWidth(encrypted, in: coverText) {
                return result
            }
            return embedHomoglyph(encrypted, in: coverText)
        }
    }

    /// Extract hidden data from stego text
    func extract(
        from text: String,
        secretKey: Data,
        method: TextStegoMethod? = nil
    ) throws -> CovertFragment {
        let encrypted: Data

        if let method = method {
            switch method {
            case .zeroWidth:
                guard let data = extractZeroWidth(from: text) else {
                    throw CovertError.channelFailed(.textSteganography, "No zero-width data found")
                }
                encrypted = data
            case .homoglyph:
                guard let data = extractHomoglyph(from: text) else {
                    throw CovertError.channelFailed(.textSteganography, "No homoglyph data found")
                }
                encrypted = data
            case .whitespace:
                guard let data = extractWhitespace(from: text) else {
                    throw CovertError.channelFailed(.textSteganography, "No whitespace data found")
                }
                encrypted = data
            case .combined:
                // Try all methods
                if let data = extractZeroWidth(from: text) {
                    encrypted = data
                } else if let data = extractHomoglyph(from: text) {
                    encrypted = data
                } else if let data = extractWhitespace(from: text) {
                    encrypted = data
                } else {
                    throw CovertError.channelFailed(.textSteganography, "No hidden data found")
                }
            }
        } else {
            // Auto-detect method
            if let data = extractZeroWidth(from: text) {
                encrypted = data
            } else if let data = extractHomoglyph(from: text) {
                encrypted = data
            } else if let data = extractWhitespace(from: text) {
                encrypted = data
            } else {
                throw CovertError.channelFailed(.textSteganography, "No hidden data found")
            }
        }

        // Decrypt
        guard let decrypted = decryptFromText(encrypted, key: secretKey) else {
            throw CovertError.channelFailed(.textSteganography, "Decryption failed")
        }

        return try JSONDecoder().decode(CovertFragment.self, from: decrypted)
    }

    // MARK: - Zero-Width Encoding

    /// Hide data as zero-width characters between visible characters
    private func embedZeroWidth(_ data: Data, in coverText: String) -> String? {
        // Convert data to bit string
        let bits = dataToBits(data)

        // Need enough characters in cover text to embed between
        let coverChars = Array(coverText)
        guard coverChars.count > 1 else { return nil }

        // Calculate bits we can embed (between each pair of characters)
        let slots = coverChars.count - 1
        let bitsPerSlot = max(1, (bits.count + slots - 1) / slots)

        // Build stego text
        var result = String(coverChars[0])
        var bitIndex = 0

        // Insert start marker
        result.append(zwStart)

        for i in 1..<coverChars.count {
            // Embed bits between characters
            if bitIndex < bits.count {
                let end = min(bitIndex + bitsPerSlot, bits.count)
                for b in bitIndex..<end {
                    result.append(bits[b] ? zwBit1 : zwBit0)
                }
                bitIndex = end
            }

            result.append(coverChars[i])
        }

        // Append remaining bits at the end
        while bitIndex < bits.count {
            result.append(bits[bitIndex] ? zwBit1 : zwBit0)
            bitIndex += 1
        }

        // End separator
        result.append(zwSep)

        return result
    }

    /// Extract zero-width hidden data from text
    private func extractZeroWidth(from text: String) -> Data? {
        var bits: [Bool] = []
        var recording = false

        for char in text {
            if char == zwStart {
                recording = true
                continue
            }
            if char == zwSep && recording {
                break
            }
            if recording {
                if char == zwBit0 { bits.append(false) }
                else if char == zwBit1 { bits.append(true) }
                // Skip visible characters
            }
        }

        guard !bits.isEmpty else { return nil }
        return bitsToData(bits)
    }

    // MARK: - Homoglyph Encoding

    /// Encode data by substituting ASCII chars with lookalike Unicode
    private func embedHomoglyph(_ data: Data, in coverText: String) -> String? {
        let bits = dataToBits(data)
        var chars = Array(coverText)
        var bitIndex = 0

        // Find positions where we can substitute
        for i in 0..<chars.count {
            guard bitIndex < bits.count else { break }

            if let homoglyph = homoglyphMap[chars[i]] {
                if bits[bitIndex] {
                    chars[i] = homoglyph  // bit 1: use homoglyph
                }
                // bit 0: keep ASCII (default)
                bitIndex += 1
            }
        }

        guard bitIndex >= bits.count else {
            return nil // Cover text too short for payload
        }

        return String(chars)
    }

    /// Extract homoglyph-encoded data from text
    private func extractHomoglyph(from text: String) -> Data? {
        var bits: [Bool] = []
        let chars = Array(text)

        for char in chars {
            if homoglyphMap.values.contains(char) {
                bits.append(true)  // Homoglyph present = bit 1
            } else if homoglyphMap.keys.contains(char) {
                bits.append(false) // ASCII version = bit 0
            }
            // Other characters are ignored
        }

        guard !bits.isEmpty else { return nil }
        return bitsToData(bits)
    }

    // MARK: - Whitespace Encoding

    /// Encode data in the type of whitespace between words
    private func embedWhitespace(_ data: Data, in coverText: String) -> String? {
        let bits = dataToBits(data)
        let words = coverText.components(separatedBy: " ")
        guard words.count > 1 else { return nil }

        var result = words[0]
        var bitIndex = 0

        for i in 1..<words.count {
            if bitIndex < bits.count {
                if bits[bitIndex] {
                    result.append("\u{00A0}") // Non-breaking space = 1
                } else {
                    result.append(" ") // Normal space = 0
                }
                bitIndex += 1
            } else {
                result.append(" ")
            }
            result.append(words[i])
        }

        guard bitIndex >= bits.count else { return nil }
        return result
    }

    /// Extract whitespace-encoded data
    private func extractWhitespace(from text: String) -> Data? {
        var bits: [Bool] = []
        var inWord = false

        for char in text {
            if char == " " {
                if inWord {
                    bits.append(false) // Normal space = 0
                    inWord = false
                }
            } else if char == "\u{00A0}" {
                if inWord {
                    bits.append(true) // Non-breaking space = 1
                    inWord = false
                }
            } else {
                inWord = true
            }
        }

        guard !bits.isEmpty else { return nil }
        return bitsToData(bits)
    }

    // MARK: - Cover Text Generation

    /// Generate natural-looking cover text for embedding
    func generateCoverText(style: CoverTextStyle = .socialComment, minLength: Int = 200) -> String {
        switch style {
        case .socialComment:
            return generateSocialComment(minLength: minLength)
        case .productReview:
            return generateProductReview(minLength: minLength)
        case .newsComment:
            return generateNewsComment(minLength: minLength)
        case .technicalPost:
            return generateTechnicalPost(minLength: minLength)
        case .casualChat:
            return generateCasualChat(minLength: minLength)
        }
    }

    enum CoverTextStyle: String, CaseIterable {
        case socialComment
        case productReview
        case newsComment
        case technicalPost
        case casualChat
    }

    // MARK: - Text Encryption

    private func encryptForText(_ data: Data, key: Data) -> Data? {
        let encKey = SymmetricKey(data: SHA256.hash(data: key + Data("TEXT_STEG_V1".utf8)))
        let nonce = AES.GCM.Nonce()
        guard 
              let sealed = try? AES.GCM.seal(data, using: encKey, nonce: nonce) else { return nil }
        var result = Data()
        result.append(Data(nonce))       // 12 bytes
        result.append(sealed.ciphertext) // N bytes
        result.append(sealed.tag)        // 16 bytes
        return result
    }

    private func decryptFromText(_ data: Data, key: Data) -> Data? {
        guard data.count > 28 else { return nil }
        let encKey = SymmetricKey(data: SHA256.hash(data: key + Data("TEXT_STEG_V1".utf8)))
        let nonceData = data[data.startIndex..<data.startIndex + 12]
        let ciphertext = data[(data.startIndex + 12)..<(data.endIndex - 16)]
        let tag = data[(data.endIndex - 16)...]
        guard let nonce = try? AES.GCM.Nonce(data: nonceData),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag),
              let plaintext = try? AES.GCM.open(box, using: encKey) else { return nil }
        return plaintext
    }

    // MARK: - Bit Conversion

    private func dataToBits(_ data: Data) -> [Bool] {
        var bits: [Bool] = []
        // Prepend length (4 bytes, big-endian)
        var length = UInt32(data.count).bigEndian
        let lengthData = Data(bytes: &length, count: 4)

        for byte in lengthData {
            for i in stride(from: 7, through: 0, by: -1) {
                bits.append((byte >> i) & 1 == 1)
            }
        }
        for byte in data {
            for i in stride(from: 7, through: 0, by: -1) {
                bits.append((byte >> i) & 1 == 1)
            }
        }
        return bits
    }

    private func bitsToData(_ bits: [Bool]) -> Data? {
        guard bits.count >= 32 else { return nil } // Need at least length header

        // Read length (first 32 bits)
        var lengthBytes = Data(count: 4)
        for i in 0..<32 {
            let byteIdx = i / 8
            let bitPos = 7 - (i % 8)
            if bits[i] {
                lengthBytes[byteIdx] |= UInt8(1 << bitPos)
            }
        }
        let length = lengthBytes.withUnsafeBytes { $0.load(as: UInt32.self) }
        let dataLength = Int(UInt32(bigEndian: length))

        guard dataLength > 0, dataLength < 100_000 else { return nil } // Sanity check
        let totalBits = 32 + dataLength * 8
        guard bits.count >= totalBits else { return nil }

        // Read data
        var result = Data(count: dataLength)
        for i in 0..<(dataLength * 8) {
            let bitIdx = 32 + i
            let byteIdx = i / 8
            let bitPos = 7 - (i % 8)
            if bits[bitIdx] {
                result[byteIdx] |= UInt8(1 << bitPos)
            }
        }

        return result
    }

    // MARK: - Cover Text Generators

    private func generateSocialComment(minLength: Int) -> String {
        let templates = [
            "I really appreciate how this community comes together to share ideas and experiences. The discussions here have been incredibly helpful for understanding different perspectives on current events. It is always refreshing to see people engage in thoughtful conversation rather than just arguing about superficial topics. I hope we can continue to maintain this level of discourse and keep supporting each other through challenging times.",
            "This is exactly what I was looking for today. Sometimes you just need to take a step back and appreciate the small things in life. The weather has been really nice lately and I have been trying to spend more time outdoors. Anyone else feeling like this spring is going to be a good one? I have a feeling that things are starting to look up for a lot of people around here.",
            "Just wanted to share my thoughts on this topic since I have been thinking about it for a while now. There are so many different angles to consider and I think we often oversimplify complex issues. The reality is that most situations have nuances that we tend to overlook when we are caught up in our daily routines. Taking time to reflect on these things can really help us grow as individuals.",
            "Had an amazing experience today that I just had to share with everyone here. It is incredible how a simple act of kindness can completely change your perspective on things. I was at the local market this morning and a complete stranger helped me carry my groceries to the car. It reminded me that there are still so many good people out there who genuinely care about others.",
            "I have been reading a lot lately about different approaches to personal development and wellness. There is so much information available these days that it can be overwhelming to know where to start. What I have found most helpful is focusing on small consistent changes rather than trying to overhaul everything at once. Progress is progress no matter how small it might seem at first."
        ]
        return templates.randomElement()!
    }

    private func generateProductReview(minLength: Int) -> String {
        let templates = [
            "After using this product for about three weeks now I can confidently say it has exceeded my expectations in almost every way. The build quality is solid and it feels premium without being overly expensive. Setup was straightforward and took less than ten minutes. The performance has been consistent and reliable which is exactly what I was looking for. I would definitely recommend this to anyone who needs a dependable solution for everyday use.",
            "I was initially skeptical about this purchase based on some of the mixed reviews I had read online. However after giving it a fair chance I have to say I am pleasantly surprised. The product does exactly what it claims to do and the customer support team was very helpful when I had a question about the settings. Shipping was fast and the packaging was secure. Overall a solid experience from start to finish.",
            "This has quickly become one of my favorite purchases this year. The attention to detail is impressive and you can tell that a lot of thought went into the design. It works seamlessly with my existing setup and has actually improved my workflow quite a bit. The price point is reasonable considering the quality and features you get. I have already recommended it to several friends and family members.",
            "I wanted to wait a full month before writing this review to make sure I had a comprehensive understanding of the product. During that time I have used it daily and I am happy to report that it has held up remarkably well. There are a few minor things I would change but nothing that significantly impacts the overall experience. The battery life is excellent and charges quickly.",
            "Five stars from me without hesitation. I have tried several similar products over the years and this one stands out for its reliability and ease of use. The instruction manual was clear and well written which made getting started a breeze. I particularly appreciate the thoughtful design choices that make everyday tasks more convenient. Great value for the price and I would buy it again in a heartbeat."
        ]
        return templates.randomElement()!
    }

    private func generateNewsComment(minLength: Int) -> String {
        let templates = [
            "This is a really interesting development that could have significant implications for the industry going forward. I have been following this story closely and it seems like there are multiple factors at play that many people are not considering. The economic aspects alone are quite complex and I think we need to wait for more information before drawing any definitive conclusions about what this means for the average consumer.",
            "Thank you for reporting on this story. It is important that these issues get the attention they deserve. I think the key takeaway here is that we need better transparency and accountability in how these decisions are being made. The public has a right to know what is happening and why. I hope the relevant authorities take appropriate action to address the concerns raised in this article.",
            "I read this article with great interest and I have to say the analysis provided is quite thorough. The data points mentioned are particularly striking and really help put things in perspective. It would be great to see a follow-up piece that explores some of the longer-term implications of these trends. The situation is clearly evolving and I think there is much more to this story than meets the eye.",
            "As someone who has been working in this field for over a decade I can confirm that the trends described in this article are very real and concerning. What many people do not realize is that these changes have been building up gradually over the past several years. The current situation is really just the culmination of a series of policy decisions and market shifts that were predictable in hindsight.",
            "Very well written article that captures the complexity of this issue without oversimplifying it. I appreciate the balanced approach and the inclusion of multiple perspectives. Too often we see reporting that only tells one side of the story. This kind of thoughtful journalism is exactly what we need more of in today is media landscape."
        ]
        return templates.randomElement()!
    }

    private func generateTechnicalPost(minLength: Int) -> String {
        let templates = [
            "I have been working on optimizing the performance of our application and wanted to share some findings that might be helpful for others facing similar challenges. After extensive profiling we identified several bottlenecks in the data processing pipeline that were causing significant latency issues. By restructuring the caching layer and implementing batch processing for database queries we managed to reduce response times by approximately forty percent.",
            "For anyone struggling with implementing authentication in their mobile application I wanted to document the approach that worked well for us. We ended up using a combination of token-based authentication with refresh tokens and secure storage for sensitive credentials. The key was making sure the token rotation logic was robust enough to handle edge cases like network interruptions and concurrent requests.",
            "Just finished migrating our infrastructure to a new architecture and wanted to share some lessons learned along the way. The biggest challenge was ensuring zero downtime during the transition which required careful planning and extensive testing. We set up a parallel environment and gradually shifted traffic using weighted routing. The entire process took about six weeks from planning to completion.",
            "Here is a comprehensive guide to setting up continuous integration and deployment for your project based on our recent experience. The most important thing we learned is that investing time upfront in writing good tests pays enormous dividends down the line. We now have over ninety percent code coverage and our deployment pipeline catches most issues before they reach production.",
            "I wanted to share our experience with implementing real-time features in our web application. After evaluating several options we decided to go with a combination of server-sent events for push notifications and long-polling as a fallback for older browsers. The implementation was surprisingly straightforward once we had the right architecture in place and the user experience improvement was immediately noticeable."
        ]
        return templates.randomElement()!
    }

    private func generateCasualChat(minLength: Int) -> String {
        let templates = [
            "Hey how is everything going with you lately? I feel like we have not caught up in ages. Things have been pretty busy on my end with work and everything but I am trying to make more time for the things that matter. Did you end up going on that trip you were planning? I remember you mentioned something about it last time we talked and I have been curious about how it went.",
            "I have been meaning to tell you about this great place I discovered last weekend. It is a small cafe tucked away on a side street that I had never noticed before even though I walk past it almost every day. They have the most amazing pastries and the coffee is really good too. We should definitely check it out together sometime when you are free.",
            "Can you believe how fast this year is going by already? It feels like just yesterday we were making plans for the new year and here we are months later wondering where the time went. I have been trying to be more intentional about how I spend my time and it has made a real difference. Even just taking a few minutes each morning to plan out my day helps a lot.",
            "I just finished watching this incredible series that I think you would really enjoy. It is one of those shows that starts off slowly but gets progressively better with each episode. By the third episode I was completely hooked and ended up binge watching the entire season in one weekend. The storytelling is really well done and the characters are surprisingly complex.",
            "So I finally got around to trying that recipe you recommended and I have to say it turned out way better than I expected. I was a bit nervous about some of the steps since I am not exactly a confident cook but the instructions were really clear and easy to follow. I made a few small modifications based on what I had available and it still turned out great. Thanks for the suggestion."
        ]
        return templates.randomElement()!
    }

    // MARK: - Capacity Analysis

    /// Calculate how many bytes can be hidden in a given cover text
    func capacity(of text: String, method: TextStegoMethod = .zeroWidth) -> Int {
        switch method {
        case .zeroWidth:
            // Can embed between every pair of characters
            return max(0, (text.count - 1) * 8 / 8 - 4) // -4 for length header
        case .homoglyph:
            let substitutable = text.filter { homoglyphMap.keys.contains($0) }.count
            return max(0, substitutable / 8 - 4)
        case .whitespace:
            let spaces = text.filter { $0 == " " }.count
            return max(0, spaces / 8 - 4)
        case .combined:
            // Sum of all methods
            return capacity(of: text, method: .zeroWidth) +
                   capacity(of: text, method: .homoglyph)
        }
    }

    /// Strip all steganographic artifacts from text (clean version)
    func clean(_ text: String) -> String {
        var result = ""
        for char in text {
            // Remove zero-width characters
            if char == zwBit0 || char == zwBit1 || char == zwSep || char == zwStart { continue }
            // Replace homoglyphs with ASCII
            if let ascii = reverseHomoglyphMap[char] {
                result.append(ascii)
            } else if char == "\u{00A0}" {
                result.append(" ") // Replace NBSP with normal space
            } else {
                result.append(char)
            }
        }
        return result
    }
}
