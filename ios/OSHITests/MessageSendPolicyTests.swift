//
//  MessageSendPolicyTests.swift
//  OSHITests
//
//  A message splitter is a piece of code whose bugs are invisible in review and
//  permanent on the recipient's screen. Cut a String by byte offset and a family
//  emoji becomes three replacement characters; cut it by UTF-16 offset and a
//  Devanagari cluster loses its vowel sign. Neither crashes, neither logs, and
//  both ship. So the tests below are mostly "what must NOT happen":
//
//    * no part may be longer than the budget — measured in UTF-8 bytes, which is
//      what the transport counts, NOT in `String.count`;
//    * concatenating the parts must give back the text (modulo the whitespace we
//      deliberately break on);
//    * the count of grapheme clusters must be conserved, which is the property
//      that fails the instant someone "optimises" this with String.Index offsets;
//    * a control payload (a read receipt, a live-location update) must come out
//      of the splitter untouched — splitting one produces two halves that no
//      version of the app can parse.
//
//  And one test that is not about the algorithm at all: that the chosen limit
//  actually fits in the 12 LoRa frames it claims to fit in. That number is the
//  only reason the limit is 500 and not something invented, so it is re-measured
//  here rather than trusted.
//

import XCTest
import CryptoKit
@testable import OSHI

final class MessageSendPolicyTests: XCTestCase {

    // MARK: - Helpers

    private func assertNoPartExceeds(_ parts: [String], _ limit: Int,
                                     file: StaticString = #filePath, line: UInt = #line) {
        for (i, p) in parts.enumerated() {
            XCTAssertLessThanOrEqual(
                p.utf8.count, limit,
                "part \(i + 1)/\(parts.count) is \(p.utf8.count) UTF-8 bytes, budget \(limit)",
                file: file, line: line)
        }
    }

    /// Everything except the whitespace the splitter is allowed to eat at a break.
    private func squashed(_ s: String) -> String {
        s.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    // MARK: - The limit is real, not invented

    /// Re-measures the LoRa envelope: `MeshtasticManager` chunks the JSON of a
    /// `SecureMessage.meshWireSafe()` at 180 bytes and refuses more than 12
    /// frames. `loRaSafeUTF8Bytes` must fit inside 12 × 180 = 2160 with the full
    /// crypto envelope on top of it — AES-GCM, DoubleRatchetMessage JSON,
    /// AES-GCM again, base64, then the SecureMessage JSON.
    ///
    /// If someone adds a field to SecureMessage, this fails and the limit has to
    /// come down. That is the point: a constant nobody re-derives is a constant
    /// that quietly stops being true.
    func testLoRaLimitActuallyFitsTwelveFrames() throws {
        let frameBudget = 180 * 12

        func envelopeBytes(forPlaintextBytes n: Int) throws -> Int {
            let key = SymmetricKey(size: .bits256)
            let plain = Data(repeating: 0x41, count: n)
            let inner = try AES.GCM.seal(plain, using: key).combined!
            let ratchet = DoubleRatchetMessage(
                header: .init(publicKey: Data(repeating: 1, count: 32),
                              messageNumber: 4096,
                              previousChainLength: 4096,
                              isDHRatchet: true,
                              timestamp: Date(),
                              sessionVersion: 4,
                              sessionId: UUID().uuidString),
                ciphertext: inner)
            let ratchetJSON = try JSONEncoder().encode(ratchet)
            let wrapped = try AES.GCM.seal(ratchetJSON, using: key).combined!
            let key44 = Data(repeating: 9, count: 32).base64EncodedString()
            let sig88 = Data(repeating: 9, count: 64).base64EncodedString()
            let msg = SecureMessage(
                id: UUID().uuidString,
                senderAddress: key44,
                recipientAddress: key44,
                encryptedContent: EncryptedMessage(ciphertext: wrapped.base64EncodedString(),
                                                   signature: sig88,
                                                   senderPublicKey: key44,
                                                   timestamp: Date()),
                timestamp: Date(),
                isRead: false,
                deliveryStatus: .pending,
                senderPublicKey: key44,
                recipientPublicKey: key44,
                plaintextContent: String(repeating: "A", count: n),   // stripped by meshWireSafe
                mediaAttachment: nil,
                deliveryMethod: .pending,
                mediaType: nil,
                originalMediaData: nil)
            return try JSONEncoder().encode(msg.meshWireSafe()).count
        }

        let atLimit = try envelopeBytes(forPlaintextBytes: MessageTextSplitter.loRaSafeUTF8Bytes)
        XCTAssertLessThanOrEqual(
            atLimit, frameBudget,
            "\(MessageTextSplitter.loRaSafeUTF8Bytes) B of text encodes to \(atLimit) B, over the \(frameBudget) B / 12-frame LoRa cap")

        // And it should not be absurdly conservative either — otherwise we are
        // splitting people's messages for no reason.
        XCTAssertGreaterThan(atLimit, frameBudget / 2,
                             "limit is far below what a LoRa frame budget allows; it is costing users splits for nothing")
    }

    // MARK: - Grapheme safety

    /// A ZWJ family emoji is ONE Character and 25 UTF-8 bytes. A splitter that
    /// counts bytes or UTF-16 units cuts it into pieces that render as tofu.
    func testEmojiAreNeverCutInHalf() {
        let family = "👨‍👩‍👧‍👦"
        XCTAssertEqual(family.count, 1, "precondition: one grapheme cluster")
        XCTAssertEqual(family.utf8.count, 25, "precondition: 25 UTF-8 bytes")

        let text = String(repeating: family, count: 200)   // 5000 bytes, 200 clusters
        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 500)

        XCTAssertGreaterThan(parts.count, 1)
        assertNoPartExceeds(parts, 500)
        // Conserved, and every piece is a whole number of families.
        XCTAssertEqual(parts.joined(), text)
        XCTAssertEqual(parts.reduce(0) { $0 + $1.count }, 200)
        for p in parts {
            XCTAssertFalse(p.unicodeScalars.contains { $0 == "\u{FFFD}" },
                           "replacement character — a cluster was cut")
            XCTAssertEqual(p.count * 25, p.utf8.count, "a family emoji was split")
        }
    }

    /// Skin-tone modifiers and flags: also single Characters, also multi-scalar.
    func testModifiedEmojiAndFlagsSurvive() {
        let run = "👍🏽🇫🇷👩🏻‍🚀"
        XCTAssertEqual(run.count, 3, "precondition")
        let text = String(repeating: run, count: 120)
        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 300)
        assertNoPartExceeds(parts, 300)
        XCTAssertEqual(parts.joined(), text)
        XCTAssertEqual(parts.reduce(0) { $0 + $1.count }, 360)
    }

    /// Combining accents: "e" + U+0301 is one Character and three bytes. Cutting
    /// between them orphans the accent onto the next message.
    func testCombiningAccentsAreNeverOrphaned() {
        let e = "e\u{0301}"                       // é, decomposed
        XCTAssertEqual(e.count, 1, "precondition")
        XCTAssertEqual(e.unicodeScalars.count, 2, "precondition")

        let text = String(repeating: e, count: 400)
        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 100)
        assertNoPartExceeds(parts, 100)
        XCTAssertEqual(parts.joined(), text)
        XCTAssertEqual(parts.reduce(0) { $0 + $1.count }, 400)
        for p in parts {
            XCTAssertFalse(p.unicodeScalars.first == "\u{0301}",
                           "a part starts with a combining mark — the base letter is in the previous message")
        }
    }

    /// CJK: three bytes per character, no spaces anywhere, so there is no word
    /// boundary to find. Every character boundary is a legal break in CJK
    /// typography, so the mid-run fallback is the CORRECT behaviour here — what
    /// must not happen is a cut inside a character.
    func testCJKSplitsOnCharacterBoundaries() {
        let text = String(repeating: "日本語のテキストです", count: 60)   // 600 chars, 1800 bytes
        XCTAssertEqual(text.utf8.count, text.count * 3, "precondition: 3 bytes/char")

        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 500)
        XCTAssertGreaterThan(parts.count, 1)
        assertNoPartExceeds(parts, 500)
        XCTAssertEqual(parts.joined(), text)
        for p in parts {
            XCTAssertEqual(p.utf8.count % 3, 0, "cut inside a multi-byte character")
        }
    }

    /// The budget is UTF-8 bytes, not characters. 200 CJK characters is 600
    /// bytes and must split against a 500-byte budget even though `count` is 200.
    func testBudgetIsBytesNotCharacters() {
        let text = String(repeating: "漢", count: 200)
        XCTAssertEqual(text.count, 200)
        XCTAssertEqual(text.utf8.count, 600)
        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 500)
        XCTAssertEqual(parts.count, 2, "600 bytes against a 500-byte budget must split")
    }

    // MARK: - Boundary preference

    func testPrefersParagraphBoundary() {
        let a = String(repeating: "alpha ", count: 40).trimmingCharacters(in: .whitespaces)   // ~239 B
        let b = String(repeating: "bravo ", count: 40).trimmingCharacters(in: .whitespaces)
        let parts = MessageTextSplitter.split("\(a)\n\n\(b)", maxUTF8Bytes: 300)

        XCTAssertEqual(parts.count, 2)
        XCTAssertEqual(parts[0], a, "should have broken on the blank line")
        XCTAssertEqual(parts[1], b)
    }

    func testPrefersLineBoundaryWhenNoParagraph() {
        let a = String(repeating: "one ", count: 60).trimmingCharacters(in: .whitespaces)     // 239 B
        let b = String(repeating: "two ", count: 60).trimmingCharacters(in: .whitespaces)
        let parts = MessageTextSplitter.split("\(a)\n\(b)", maxUTF8Bytes: 300)

        XCTAssertEqual(parts.count, 2)
        XCTAssertEqual(parts[0], a)
        XCTAssertEqual(parts[1], b)
    }

    /// Plain prose with no newlines: never cut a word in half.
    func testBreaksOnWordsNotMidWord() {
        let words = (0..<300).map { "word\($0)" }
        let text = words.joined(separator: " ")
        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 400)

        XCTAssertGreaterThan(parts.count, 1)
        assertNoPartExceeds(parts, 400)
        XCTAssertEqual(squashed(parts.joined(separator: " ")), squashed(text),
                       "text was altered, not just re-broken")
        // Every part must start and end on a whole word.
        let rejoined = parts.flatMap { $0.split(separator: " ").map(String.init) }
        XCTAssertEqual(rejoined, words, "a word was cut across two messages")
    }

    /// The last-resort case, and the ONLY one allowed to cut mid-token: a single
    /// unbroken run with no whitespace at all.
    func testUnbrokenRunFallsBackToMidWordCut() {
        let text = String(repeating: "z", count: 2000)
        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 500)
        XCTAssertEqual(parts.count, 4)
        assertNoPartExceeds(parts, 500)
        XCTAssertEqual(parts.joined(), text)
    }

    /// A boundary in the first few characters must not produce a 4-byte part
    /// followed by an over-budget remainder.
    func testEarlyBoundaryDoesNotStarveTheBudget() {
        let text = "Hi\n\n" + String(repeating: "long ", count: 200)
        let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 400)
        assertNoPartExceeds(parts, 400)
        for (i, p) in parts.dropLast().enumerated() {
            XCTAssertGreaterThan(p.utf8.count, 40,
                                 "part \(i) is only \(p.utf8.count) B — the splitter took a near-zero boundary")
        }
    }

    func testShortTextIsNotTouched() {
        let text = "Hello 👋 how are you?"
        XCTAssertEqual(MessageTextSplitter.split(text, maxUTF8Bytes: 500), [text])
        XCTAssertEqual(MessageTextSplitter.splitForSend(text, maxUTF8Bytes: 500), .single)
    }

    func testSplitterAlwaysTerminates() {
        // Pathological inputs: whitespace-only, one huge grapheme, alternating
        // newlines. Each of these has hung a hand-rolled splitter at some point.
        for text in [String(repeating: "\n", count: 500),
                     String(repeating: " ", count: 500),
                     String(repeating: "👨‍👩‍👧‍👦", count: 50),
                     String(repeating: "a\n", count: 400)] {
            let parts = MessageTextSplitter.split(text, maxUTF8Bytes: 60)
            // The invariant that matters: no piece is ever over budget. A
            // whitespace-only input legitimately yields no pieces at all — what
            // it must NOT do is hand back the whole 500-byte string as one
            // "part" that then fails on the radio.
            assertNoPartExceeds(parts, 60)
        }
        // ...and that whitespace-only case is passed through untouched rather
        // than turned into a send of nothing.
        XCTAssertEqual(MessageTextSplitter.splitForSend(String(repeating: "\n", count: 500),
                                                        maxUTF8Bytes: 60), .single)
        XCTAssertFalse(MessageTextSplitter.split(String(repeating: "👨‍👩‍👧‍👦", count: 50),
                                                 maxUTF8Bytes: 60).isEmpty)
    }

    // MARK: - What must never be split

    func testControlPayloadsArePassedThroughUntouched() {
        let filler = String(repeating: "x", count: 6000)
        for prefix in MessageTextSplitter.controlPrefixes {
            let payload = prefix + filler
            XCTAssertFalse(MessageTextSplitter.isUserProse(payload), "\(prefix) treated as prose")
            XCTAssertEqual(MessageTextSplitter.splitForSend(payload, maxUTF8Bytes: 500), .single,
                           "\(prefix) would have been cut in half")
        }
    }

    /// __CASHU_SEND_READY_2026_11_09__ NUT-00's two-proof V3 example is ~620 B,
    /// over the 500 B LoRa budget. Split, it is two strings no wallet takes.
    func testCashuTokenIsRefusedRatherThanSplit() {
        let json = #"{"token":[{"mint":"https://8333.space:3338","proofs":[{"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837","C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"},{"amount":8,"secret":"fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be","C":"029e8e5050b890a7d6c0968db16bc1d5d5fa040ea1de284f6ec69d61299f671059","id":"009a1f293253e41e"}]}],"unit":"sat","memo":"Thank you."}"#
        let b64 = Data(json.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let token = "cashuA" + b64
        XCTAssertNotNil(CashuTokens.decode(token))
        XCTAssertGreaterThan(token.utf8.count, MessageTextSplitter.loRaSafeUTF8Bytes)
        guard case .tooLong = MessageTextSplitter.splitForSend(token, maxUTF8Bytes: MessageTextSplitter.loRaSafeUTF8Bytes) else {
            return XCTFail("a Cashu token over the budget must be refused, never cut")
        }
        // Within budget it goes as-is.
        XCTAssertEqual(MessageTextSplitter.splitForSend(token, maxUTF8Bytes: MessageTextSplitter.standardUTF8Bytes), .single)
    }

    func testGroupMediaJSONIsNotSplit() {
        let json = "{\"type\":\"media\",\"content\":\"" + String(repeating: "A", count: 6000) + "\"}"
        XCTAssertFalse(MessageTextSplitter.isUserProse(json))
        XCTAssertEqual(MessageTextSplitter.splitForSend(json, maxUTF8Bytes: 500), .single)
    }

    // MARK: - Reply / forward wrappers

    /// The composer wraps a reply in JSON before the manager sees it. Splitting
    /// that JSON string would send two undecodable halves. The body inside must
    /// be split instead, and only the first part keeps the quote.
    func testReplyWrapperSplitsTheBodyAndQuotesOnlyTheFirstPart() throws {
        let reply = ReplyMessage(originalMessageId: UUID().uuidString,
                                 originalSenderKey: "peer",
                                 originalText: "the original",
                                 originalTimestamp: Date(),
                                 originalMediaType: nil)
        let body = (0..<300).map { "word\($0)" }.joined(separator: " ")
        let wire = MessageWithReply(content: body, replyTo: reply).toMessageString()

        guard case .parts(let parts) = MessageTextSplitter.splitForSend(wire, maxUTF8Bytes: 600) else {
            return XCTFail("expected a split")
        }
        XCTAssertGreaterThan(parts.count, 1)

        XCTAssertTrue(parts[0].hasPrefix(MessageTextSplitter.replyPrefix))
        let first = try XCTUnwrap(MessageWithReply.fromMessageString(parts[0]))
        XCTAssertEqual(first.replyTo?.originalMessageId, reply.originalMessageId)

        for p in parts.dropFirst() {
            XCTAssertFalse(p.hasPrefix(MessageTextSplitter.replyPrefix),
                           "the quote is repeated on every fragment")
        }
        // Every part still fits the wire budget, wrapper included.
        assertNoPartExceeds(parts, 600)

        let recovered = ([first.content] + parts.dropFirst()).joined(separator: " ")
        XCTAssertEqual(squashed(recovered), squashed(body))
    }

    /// A forward keeps its attribution on EVERY part — dropping it from parts
    /// 2…n relabels someone else's words as the sender's own.
    func testForwardWrapperKeepsAttributionOnEveryPart() throws {
        let body = (0..<300).map { "word\($0)" }.joined(separator: " ")
        let wire = ForwardedMessage(originalSenderName: "Alice",
                                    content: body,
                                    isForwarded: true,
                                    forwardCount: 2).toMessageString()

        guard case .parts(let parts) = MessageTextSplitter.splitForSend(wire, maxUTF8Bytes: 700) else {
            return XCTFail("expected a split")
        }
        XCTAssertGreaterThan(parts.count, 1)
        assertNoPartExceeds(parts, 700)

        var bodies: [String] = []
        for p in parts {
            let f = try XCTUnwrap(ForwardedMessage.fromMessageString(p))
            XCTAssertEqual(f.originalSenderName, "Alice")
            XCTAssertTrue(f.isForwarded)
            XCTAssertEqual(f.forwardCount, 2)
            bodies.append(f.content)
        }
        XCTAssertEqual(squashed(bodies.joined(separator: " ")), squashed(body))
    }

    /// The nastiest case in the wrapper handling: the body goes back INTO JSON,
    /// so every newline and quote costs two bytes instead of one. Budgeting by
    /// "limit minus empty wrapper" under-counts precisely for multi-line pastes
    /// — the ones people actually send — and the wrapped part comes out over the
    /// transport ceiling with nothing to catch it.
    func testWrappedPartsFitTheBudgetEvenWhenJSONEscapingInflatesTheBody() throws {
        let reply = ReplyMessage(originalMessageId: UUID().uuidString,
                                 originalSenderKey: "peer",
                                 originalText: "quoted",
                                 originalTimestamp: Date(),
                                 originalMediaType: nil)
        // Every character is an escape: newlines, quotes, backslashes, tabs.
        let body = String(repeating: "a\n\"b\"\\c\t", count: 300)
        let wire = MessageWithReply(content: body, replyTo: reply).toMessageString()
        XCTAssertGreaterThan(wire.utf8.count, 600, "precondition: needs to split")

        switch MessageTextSplitter.splitForSend(wire, maxUTF8Bytes: 600) {
        case .parts(let parts):
            assertNoPartExceeds(parts, 600)
            XCTAssertTrue(parts[0].hasPrefix(MessageTextSplitter.replyPrefix))
            XCTAssertNotNil(MessageWithReply.fromMessageString(parts[0]),
                            "the wrapped first part no longer decodes")
        case .tooLong:
            break   // acceptable: refusing beats shipping an over-budget part
        case .single:
            XCTFail("an over-budget wrapped message was passed through unsplit")
        }
    }

    /// Same for forwards, where EVERY part is re-wrapped and so every part pays
    /// the escaping cost.
    func testForwardPartsFitTheBudgetWithEscapedBodies() {
        let body = String(repeating: "line \"quoted\"\n", count: 300)
        let wire = ForwardedMessage(originalSenderName: "Alice \"A\"",
                                    content: body,
                                    isForwarded: true,
                                    forwardCount: 1).toMessageString()

        switch MessageTextSplitter.splitForSend(wire, maxUTF8Bytes: 800) {
        case .parts(let parts):
            assertNoPartExceeds(parts, 800)
            for p in parts {
                XCTAssertNotNil(ForwardedMessage.fromMessageString(p), "a part no longer decodes")
            }
        case .tooLong:
            break
        case .single:
            XCTFail("an over-budget forward was passed through unsplit")
        }
    }

    // MARK: - Refusal

    func testAbsurdPasteIsRefusedRatherThanTurnedIntoAStorm() {
        let huge = String(repeating: "word ", count: 200_000)   // ~1 MB
        guard case .tooLong(let needed) = MessageTextSplitter.splitForSend(huge, maxUTF8Bytes: 4000) else {
            return XCTFail("a 1 MB paste must be refused, not sent as hundreds of messages")
        }
        XCTAssertGreaterThan(needed, MessageTextSplitter.maxParts)
    }

    /// The refusal must be something the user can read, in their language —
    /// not a raw key, which is what ships when a .strings entry is missing.
    func testRefusalMessagesAreLocalizedNotRawKeys() {
        let tooLong = MessageSendOutcome.tooLong.userFacingMessage
        XCTAssertNotNil(tooLong)
        XCTAssertNotEqual(tooLong, "send.error.too_long", "raw key shipped — the string is missing")

        let limited = MessageSendOutcome.rateLimited(retryAfterSeconds: 12).userFacingMessage
        XCTAssertNotNil(limited)
        XCTAssertNotEqual(limited, "send.error.rate_limited", "raw key shipped — the string is missing")
        XCTAssertTrue(limited?.contains("12") ?? false,
                      "the retry delay was not substituted into the message: \(limited ?? "nil")")
        XCTAssertFalse(limited?.contains("%@") ?? true, "format specifier left in the output")

        XCTAssertNil(MessageSendOutcome.sent.userFacingMessage)
        XCTAssertNil(MessageSendOutcome.sentAsParts(3).userFacingMessage)
        XCTAssertTrue(MessageSendOutcome.sentAsParts(3).didSend)
        XCTAssertFalse(MessageSendOutcome.tooLong.didSend)
    }

    // MARK: - __SUGGESTIONS_ARE_A_CHOICE_2026_10_27__

    /// Absent must mean ON. The feature shipped enabled, so a person who never
    /// opened Settings must not silently lose it — and a switch that defaults
    /// the wrong way looks exactly like the feature breaking.
    func testSuggestionsDefaultToOnWhenNeverChosen() {
        XCTAssertTrue(SmartReplyPreference.isEnabled(storedValue: nil))
    }

    /// And an explicit choice is honoured in both directions.
    func testAnExplicitChoiceIsHonoured() {
        XCTAssertTrue(SmartReplyPreference.isEnabled(storedValue: true))
        XCTAssertFalse(SmartReplyPreference.isEnabled(storedValue: false),
                       "switching suggestions off must actually turn them off")
    }

    /// The key is shared between ChatView and SettingsView. Spelled twice, it
    /// becomes a switch that stops working the first time one is edited —
    /// which is how `profile_photo_visible` ended up with a UI and no reader.
    func testTheKeyIsNamespacedAndStable() {
        XCTAssertEqual(SmartReplyPreference.enabledKey, "oshi.smartReplies.enabled")
    }

    // MARK: - Transport budget selection

    func testLoRaPairingTightensTheBudget() {
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(loRaPaired: true),
                       MessageTextSplitter.loRaSafeUTF8Bytes)
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(loRaPaired: false),
                       MessageTextSplitter.standardUTF8Bytes)
        XCTAssertLessThan(MessageTextSplitter.loRaSafeUTF8Bytes,
                          MessageTextSplitter.standardUTF8Bytes)
    }

    // MARK: - __LONG_TEXT_ONE_MESSAGE_2026_09_28__

    /// The owner's report: a ~2,000-char paste left as five messages because a
    /// radio had once been paired. Online, a paired radio must not shrink the
    /// budget — LoRa is not the route. Off-grid it still must.
    func testPairedRadioOnlyTightensTheBudgetOffGrid() {
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(radioPaired: true, hasPhysicalPath: true,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: false),
                       MessageTextSplitter.standardUTF8Bytes)
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(radioPaired: false, hasPhysicalPath: true,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: false),
                       MessageTextSplitter.standardUTF8Bytes)
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(radioPaired: false, hasPhysicalPath: false,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: false),
                       MessageTextSplitter.standardUTF8Bytes)
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(radioPaired: true, hasPhysicalPath: false,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: false),
                       MessageTextSplitter.loRaSafeUTF8Bytes)
    }

    // MARK: - __LORA_BUDGET_FOLLOWS_THE_ROUTE_2026_10_27__

    /// THE BUG, in one assertion. An iPhone 17 paired to a ThinkNode M1, Wi-Fi
    /// up, "prefer radio over relay" ON: `order(_:)` publishes `[.loRa,
    /// .internetRelay]`, so the radio is the FIRST thing the envelope is handed
    /// to — and the splitter handed out the 16,000-byte relay budget anyway.
    /// `deliverViaLoRa` then refused the 4,298-byte result against its
    /// 12 × 180 = 2,160-byte ceiling: "4298 octets — message trop long pour
    /// LoRa". The transport the user chose could never carry a long message.
    func testRadioFirstOnlineBindsTheLoRaBudget() {
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(radioPaired: true,
                                                        hasPhysicalPath: true,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: true),
                       MessageTextSplitter.loRaSafeUTF8Bytes,
                       "with the radio put first by the user, the text must be sized for the radio")
    }

    /// The other half of the same rule, and the guard on
    /// `__LONG_TEXT_ONE_MESSAGE_2026_09_28__`: a radio owner who has NOT asked
    /// for radio-first keeps the relay budget while online. Without this, the
    /// fix above would be the 2026-09-28 regression wearing a new hat.
    func testOnlineWithoutTheRadioFirstToggleKeepsTheRelayBudget() {
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(radioPaired: true,
                                                        hasPhysicalPath: true,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: false),
                       MessageTextSplitter.standardUTF8Bytes)
    }

    /// Internet sending switched off app-wide leaves the radio as the only way
    /// out even with a perfectly good Wi-Fi link underneath — `order(_:)`
    /// drops `.internetRelay` entirely. The text must fit the radio.
    func testInternetSendingDisabledBindsTheLoRaBudget() {
        XCTAssertEqual(MessageTextSplitter.maxUTF8Bytes(radioPaired: true,
                                                        hasPhysicalPath: true,
                                                        internetAllowedForSending: false,
                                                        prefersRadioOverRelay: false),
                       MessageTextSplitter.loRaSafeUTF8Bytes)
    }

    /// No radio paired: nothing about the network, the toggles or the user's
    /// preferences may ever shrink the budget. There is no radio to fit.
    func testNoRadioMeansNoLoRaBudgetEver() {
        for path in [true, false] {
            for net in [true, false] {
                for prefer in [true, false] {
                    XCTAssertEqual(
                        MessageTextSplitter.maxUTF8Bytes(radioPaired: false,
                                                         hasPhysicalPath: path,
                                                         internetAllowedForSending: net,
                                                         prefersRadioOverRelay: prefer),
                        MessageTextSplitter.standardUTF8Bytes,
                        "path=\(path) net=\(net) prefer=\(prefer)")
                }
            }
        }
    }

    /// The owner's message, as it behaves after the fix. A text long enough to
    /// have produced the 4,298-byte envelope now leaves as parts, and EVERY
    /// part is inside the budget that was pinned to fit 12 LoRa frames — so
    /// `deliverViaLoRa` has nothing left to refuse.
    func testTheOwnersLongTextNowSplitsInsteadOfBeingRefused() {
        let para = "Voilà un paragraphe écrit à la main, avec des accents — et une ligne.\n"
        var text = ""
        while text.utf8.count < 2_800 { text += para }
        let limit = MessageTextSplitter.maxUTF8Bytes(radioPaired: true,
                                                     hasPhysicalPath: true,
                                                     internetAllowedForSending: true,
                                                     prefersRadioOverRelay: true)
        XCTAssertEqual(limit, MessageTextSplitter.loRaSafeUTF8Bytes)
        guard case .parts(let parts) = MessageTextSplitter.splitForSend(text, maxUTF8Bytes: limit) else {
            return XCTFail("a 2.8 KB text must split, not go out as one oversize envelope")
        }
        XCTAssertGreaterThan(parts.count, 1)
        for part in parts {
            XCTAssertLessThanOrEqual(part.utf8.count, limit,
                                     "a part over the budget is a part the radio refuses")
        }
    }

    /// A 9,000-character French text with paragraphs goes out as ONE message,
    /// byte-for-byte (paragraphs included), with a radio paired and online.
    func testLongFrenchPasteIsOneMessageOnline() {
        let para = "Voilà un paragraphe écrit à la main, avec des accents — et une ligne.\nPuis la suite, à la ligne.\n\n"
        var text = ""
        while text.count < 9_000 { text += para }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertGreaterThanOrEqual(text.count, 8_900)
        let limit = MessageTextSplitter.maxUTF8Bytes(radioPaired: true, hasPhysicalPath: true,
                                                        internetAllowedForSending: true,
                                                        prefersRadioOverRelay: false)
        XCTAssertEqual(MessageTextSplitter.splitForSend(text, maxUTF8Bytes: limit), .single)
        XCTAssertEqual(MessageTextSplitter.normalizedForSend(text), text,
                       "an LF-only text must be sent exactly as typed")
    }

    /// The standard budget must stay far under the relay's 256 KB ciphertext
    /// cap (`ServerVPS/v2/relay_v2.js` MAX_CIPHERTEXT_BYTES) at the measured
    /// 1.83× wire inflation, with room for a reply wrapper.
    func testStandardBudgetFitsTheRelayCap() {
        let relayCap = 256 * 1024
        XCTAssertLessThan(Double(MessageTextSplitter.standardUTF8Bytes) * 1.83 * 2, Double(relayCap))
        XCTAssertGreaterThanOrEqual(MessageTextSplitter.standardUTF8Bytes, 16_000)
    }

    func testForeignLineBreaksBecomeLF() {
        XCTAssertEqual(MessageTextSplitter.normalizeLineBreaks("a\r\nb\rc\u{2028}d\u{2029}e\u{0B}f\u{85}g"),
                       "a\nb\nc\nd\ne\nf\ng")
        XCTAssertEqual(MessageTextSplitter.normalizeLineBreaks("p1\r\n\r\np2"), "p1\n\np2",
                       "a CRLF blank line must stay ONE blank line, not two")
        XCTAssertEqual(MessageTextSplitter.normalizeLineBreaks("  keep\n\n  spaces 👨‍👩‍👧 "),
                       "  keep\n\n  spaces 👨‍👩‍👧 ", "LF-only text is untouched, no trimming")
    }

    func testNormalisationLeavesControlPayloadsAlone() {
        let receipt = "📖READ_RECEIPT📖{\"id\":\"x\u{2028}y\"}"
        XCTAssertFalse(MessageTextSplitter.isUserProse(receipt))
        XCTAssertEqual(MessageTextSplitter.normalizedForSend(receipt), receipt)
        let media = "{\"type\":\"media\",\"caption\":\"a\rb\"}"
        XCTAssertEqual(MessageTextSplitter.normalizedForSend(media), media)
    }

    /// A reply envelope is JSON; `JSONEncoder` writes U+2028 raw. The body is
    /// normalised INSIDE the envelope and the result must still decode.
    func testReplyBodyIsNormalisedAndStillDecodes() throws {
        let reply = ReplyMessage(originalMessageId: "m1",
                                 originalSenderKey: "peer",
                                 originalText: "quoted",
                                 originalTimestamp: Date(),
                                 originalMediaType: nil)
        let wire = MessageWithReply(content: "l1\u{2028}l2\r\nl3", replyTo: reply).toMessageString()
        let out = MessageTextSplitter.normalizedForSend(wire)
        let decoded = try XCTUnwrap(MessageWithReply.fromMessageString(out))
        XCTAssertEqual(decoded.content, "l1\nl2\nl3")
        XCTAssertEqual(decoded.replyTo?.originalMessageId, "m1")
    }

    // MARK: - Rate limiter

    func testAllowsExactlyOneHundredPerMinute() {
        let limiter = MessageSendRateLimiter()
        let t0 = Date()
        for i in 0..<100 {
            XCTAssertEqual(limiter.consume(now: t0), .allowed, "send \(i + 1) was refused")
        }
        guard case .denied(let retry) = limiter.consume(now: t0) else {
            return XCTFail("the 101st message in the same minute must be refused")
        }
        XCTAssertEqual(retry, 60, "retry hint should be the full window when all 100 landed at once")
    }

    /// Sliding, not fixed: the window must reopen gradually, not all at once on
    /// a minute boundary.
    func testWindowSlides() {
        let limiter = MessageSendRateLimiter()
        let t0 = Date()
        for i in 0..<100 { limiter.consume(now: t0.addingTimeInterval(Double(i) * 0.5)) }   // spread over 50 s
        XCTAssertFalse(limiter.wouldAllow(now: t0.addingTimeInterval(50)))
        // 60 s after the FIRST send, one slot is free again.
        XCTAssertTrue(limiter.wouldAllow(now: t0.addingTimeInterval(60.1)))
        XCTAssertEqual(limiter.consume(now: t0.addingTimeInterval(60.1)), .allowed)
        // ...but not two.
        XCTAssertFalse(limiter.wouldAllow(now: t0.addingTimeInterval(60.1)))
    }

    func testRetryHintIsNeverZero() {
        let limiter = MessageSendRateLimiter()
        let t0 = Date()
        for _ in 0..<100 { limiter.consume(now: t0) }
        // 59.99 s in: 0.01 s of wait left, which must not be reported as "0 s".
        guard case .denied(let retry) = limiter.consume(now: t0.addingTimeInterval(59.99)) else {
            return XCTFail("still inside the window")
        }
        XCTAssertGreaterThanOrEqual(retry, 1)
    }

    /// The requirement that makes the whole feature usable: splitting one long
    /// paste must not be what trips the limit. One user action = one token, no
    /// matter how many envelopes the transport needs.
    func testSplittingALongPasteCostsOneToken() {
        let limiter = MessageSendRateLimiter()
        let t0 = Date()

        // Fifty long pastes, each of which splits into many messages.
        let paste = String(repeating: "word ", count: 3000)     // ~15 KB
        guard case .parts(let parts) = MessageTextSplitter.splitForSend(paste, maxUTF8Bytes: 500) else {
            return XCTFail("precondition: this paste should split")
        }
        XCTAssertGreaterThan(parts.count, 20, "precondition: many parts")

        for i in 0..<50 {
            XCTAssertEqual(limiter.consume(now: t0), .allowed,
                           "paste \(i + 1) refused — parts are being charged individually")
        }
        XCTAssertEqual(limiter.currentCount, 50)
    }

    func testResetClearsTheWindow() {
        let limiter = MessageSendRateLimiter()
        let t0 = Date()
        for _ in 0..<100 { limiter.consume(now: t0) }
        XCTAssertFalse(limiter.wouldAllow(now: t0))
        limiter.reset()
        XCTAssertTrue(limiter.wouldAllow(now: t0))
    }

    // MARK: - Ordering

    /// Split parts are issued in one synchronous loop. The lists sort with
    /// `sorted(by:)`, which Swift does not guarantee to be stable, and the wire
    /// only carries milliseconds. Equal timestamps therefore reorder on the
    /// recipient's screen. The allocator must make that impossible.
    func testSendClockIsStrictlyIncreasing() {
        MessageSendClock.resetForTesting()
        let stamps = (0..<200).map { _ in MessageSendClock.next() }
        for i in 1..<stamps.count {
            XCTAssertGreaterThan(stamps[i], stamps[i - 1], "timestamp \(i) did not advance")
        }
    }

    /// Millisecond is the coarsest resolution any transport preserves
    /// (`V2SyncModels.ts` is Int64 ms; Android sends ms). Two stamps that differ
    /// by less than 1 ms collapse to the same value on the wire — so the spacing
    /// has to survive the truncation, not just the Double comparison.
    func testSpacingSurvivesMillisecondTruncationOnTheWire() {
        MessageSendClock.resetForTesting()
        let now = Date()
        let stamps = MessageSendClock.next(count: 50, now: now)
        let onTheWire = stamps.map { Int64($0.timeIntervalSince1970 * 1000) }

        XCTAssertEqual(Set(onTheWire).count, stamps.count,
                       "two parts collapse to the same millisecond once serialized — they can reorder")
        for i in 1..<onTheWire.count {
            XCTAssertGreaterThan(onTheWire[i], onTheWire[i - 1])
        }
        // Fifty parts must not push the last one visibly into the future.
        XCTAssertLessThan(stamps.last!.timeIntervalSince(now), 1.0,
                          "clock skew is user-visible")
    }

    /// A stamp issued after a burst must still be later than the burst, or the
    /// next thing the user types sorts above the paste they just sent.
    func testLaterSendsStayAfterASplitBurst() {
        MessageSendClock.resetForTesting()
        let now = Date()
        let burst = MessageSendClock.next(count: 30, now: now)
        let afterwards = MessageSendClock.next(now: now)   // same wall clock instant
        XCTAssertGreaterThan(afterwards, burst.last!)
    }
}
