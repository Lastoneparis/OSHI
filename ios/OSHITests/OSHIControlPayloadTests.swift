//
//  OSHIControlPayloadTests.swift
//  OSHITests
//
//  This bug has been "fixed" three times. Each fix added the one reported prefix
//  to the one filter the reporter happened to be looking at, and each time the
//  JSON reappeared somewhere else — because the app held SIX partial copies of
//  the prefix list and the fixes never landed in the same copy twice. The
//  2026-08-10 attempt went into `GroupViews.swift:4328`, which turned out to be
//  the smart-reply PROMPT filter: it renders nothing at all.
//
//  So these tests are not about `OSHIControlPayload`'s internals. They are the
//  thing that makes "one catalog" true instead of aspirational:
//
//    * every prefix known to be on the wire is detected — the list of literals
//      below is written out BY HAND on purpose, so it fails if someone deletes
//      an entry from the catalog, which an `OSHIControlPayload.catalog`-derived
//      loop could never do;
//    * an ordinary message that happens to contain an emoji is NOT a control
//      payload (the naive `contains(emoji)` implementation passes every
//      detection test and eats the user's messages);
//    * a message that mentions a sentinel mid-sentence is NOT a control payload;
//    * a call summary renders as a call row in the two shapes that ship, and
//      never as its wire body;
//    * and the one that is really the reported bug: nothing `previewText` can
//      return may still contain a sentinel or a `{`.
//

import XCTest
@testable import OSHI

final class OSHIControlPayloadTests: XCTestCase {

    /// Every sentinel spelling believed to be on the wire, typed out
    /// independently of the catalog. A regression that removes one from the
    /// catalog must fail here rather than ship.
    private let allPrefixes: [String] = [
        "📞CALL_SIGNAL📞",
        "📱CALL_SUMMARY📱",
        "📬DELIVERY_RECEIPT📬",
        "🔗LINKS_VISIBILITY🔗",
        "📍LOCATION📍",
        "📱MISSED_CALL📱",
        "📸PROFILE_REQUEST📸",
        "📸PROFILE_UPDATE📸",
        "🔥REACTION🔥",
        "📖READ_RECEIPT📖",
        "💬REPLY💬",
        "📅SCHEDULED_CALL📅",
        "🎨WALLPAPER_UPDATE🎨",
        "🛡️CHECK_IN🛡️",
        "🛡️CHECKIN🛡️",
        "⌨️TYPING⌨️",
        "✍️TYPING✍️",
    ]

    // MARK: - Detection

    func testEveryKnownPrefixIsDetected() {
        for prefix in allPrefixes {
            XCTAssertTrue(
                OSHIControlPayload.isControl(prefix + #"{"a":1}"#),
                "\(prefix) is on the wire but the catalog does not know it — every surface will print its JSON"
            )
            XCTAssertEqual(
                OSHIControlPayload.matchedPrefix(prefix + #"{"a":1}"#), prefix,
                "\(prefix) matched a DIFFERENT catalog entry"
            )
        }
    }

    /// The bare-codepoint spellings (no U+FE0F) that older iOS builds and the
    /// Android client emit. `String.hasPrefix` does not consider "🛡" equal to
    /// "🛡️", so a single spelling in the catalog silently misses half the
    /// traffic — and misses it SILENTLY, as raw JSON on someone else's screen.
    func testVariationSelectorStrippedSpellingsAreDetected() {
        let stripped = [
            "\u{1F6E1}CHECK_IN\u{1F6E1}",
            "\u{1F6E1}CHECKIN\u{1F6E1}",
            "\u{2328}TYPING\u{2328}",
            "\u{270D}TYPING\u{270D}",
        ]
        for prefix in stripped {
            XCTAssertTrue(
                OSHIControlPayload.isControl(prefix + #"{"t":"x"}"#),
                "\(prefix.debugDescription) (no U+FE0F) was not detected"
            )
            XCTAssertFalse(
                OSHIControlPayload.previewText(for: prefix + #"{"t":"x"}"#).contains("{"),
                "\(prefix.debugDescription) matched but still leaked its JSON"
            )
        }
    }

    func testCatalogAndHandWrittenListAgree() {
        // Catch a prefix ADDED to the catalog without a test, as well as one
        // removed. The catalog legitimately carries a few entries this list does
        // not (bare-marker protocol strings), so only the emoji sentinels above
        // are required to be present.
        for prefix in allPrefixes {
            XCTAssertTrue(
                OSHIControlPayload.prefixes.contains(prefix),
                "\(prefix) is missing from OSHIControlPayload.catalog"
            )
        }
    }

    // MARK: - Non-detection (the half that keeps user messages intact)

    func testOrdinaryMessageContainingAnEmojiIsNotControl() {
        let realMessages = [
            "📞 call me when you land",
            "📍 I'm here!",
            "🔥🔥🔥",
            "on my way 🛡️",
            "📱 new phone who dis",
            "💬 let's talk tomorrow",
            "📸 look at this photo",
            "hey",
            "",
            "👍",
        ]
        for message in realMessages {
            XCTAssertFalse(
                OSHIControlPayload.isControl(message),
                "\(message.debugDescription) is something a person typed, and it was classified as protocol traffic"
            )
            XCTAssertNil(
                OSHIControlPayload.displaySummary(for: message),
                "\(message.debugDescription) was rewritten instead of shown as typed"
            )
            XCTAssertEqual(
                OSHIControlPayload.previewText(for: message), message,
                "a user's own message must survive previewText byte for byte"
            )
        }
    }

    /// The failure mode of a `contains`-based implementation: prose that quotes a
    /// sentinel is prose. The prefix must be ANCHORED.
    func testPrefixInTheMiddleOfATextIsNotControl() {
        let quoting = [
            "the marker is 📍LOCATION📍 followed by JSON",
            "we should rename 📱CALL_SUMMARY📱 to something shorter",
            "why does 💬REPLY💬 wrap the whole message?",
            " 📖READ_RECEIPT📖",          // leading space — not a prefix
            "see 🛡️CHECK_IN🛡️{\"type\":\"started\"} in the logs",
        ]
        for text in quoting {
            XCTAssertFalse(
                OSHIControlPayload.isControl(text),
                "\(text.debugDescription) merely MENTIONS a sentinel and was swallowed as protocol traffic"
            )
            XCTAssertEqual(OSHIControlPayload.previewText(for: text), text)
        }
    }

    // MARK: - Silent vs rendered

    func testSilentPayloadsAreHiddenNotSummarised() {
        for prefix in ["📞CALL_SIGNAL📞", "📬DELIVERY_RECEIPT📬", "📖READ_RECEIPT📖",
                       "⌨️TYPING⌨️", "✍️TYPING✍️", "🔗LINKS_VISIBILITY🔗",
                       "📸PROFILE_REQUEST📸"] {
            let payload = prefix + #"{"id":"abc"}"#
            XCTAssertTrue(OSHIControlPayload.isSilent(payload), "\(prefix) must never be shown")
            XCTAssertEqual(OSHIControlPayload.displaySummary(for: payload), "",
                           "\(prefix) must summarise to nothing at all")
            XCTAssertEqual(OSHIControlPayload.previewText(for: payload), "")
        }
    }

    // MARK: - « Compté » doit vouloir dire « dessiné »

    /// __COUNTED_IMPLIES_DRAWN_2026_10_20__ Le bug rapporté: un badge de non-lus
    /// devant une conversation VIDE, qui s'effaçait à l'ouverture et revenait la
    /// minute suivante. Cause: le fil jetait les payloads silencieux
    /// (`ChatView.computeFilteredMessages`) pendant que le décompte des non-lus
    /// (`MessageManager.updateConversations`) comptait tout message reçu non lu.
    /// Les deux posent maintenant CETTE question, et ce test est ce qui rend
    /// cette phrase vraie au lieu d'optimiste.
    func testNothingHiddenFromTheThreadCanBeCountedAsUnread() {
        for prefix in allPrefixes {
            let payload = prefix + #"{"id":"abc"}"#
            if OSHIControlPayload.isSilent(payload) {
                XCTAssertTrue(OSHIControlPayload.isHiddenFromThread(payload),
                              "\(prefix) est jeté du fil, il ne doit donc jamais être compté")
            }
        }
        // Les deux marqueurs rendus AILLEURS mais jamais en bulle.
        XCTAssertTrue(OSHIControlPayload.isHiddenFromThread("📸PROFILE_UPDATE📸{}"))
        XCTAssertTrue(OSHIControlPayload.isHiddenFromThread("🎨WALLPAPER_UPDATE🎨{}"))
        // Et l'inverse, qui compte autant: une vraie ligne du fil reste comptable.
        XCTAssertFalse(OSHIControlPayload.isHiddenFromThread("bonjour"))
        XCTAssertFalse(OSHIControlPayload.isHiddenFromThread("📱CALL_SUMMARY📱📞↗️call.outgoing|1:23"))
        XCTAssertFalse(OSHIControlPayload.isHiddenFromThread("📍LOCATION📍{\"latitude\":1}"))
    }

    /// Le coup de pouce de ré-établissement de session envoie la sentinelle NUE,
    /// sans corps JSON. C'est CETTE forme qui était rangée dans le fil et comptée
    /// comme non lue, parce qu'elle n'a pas de gestionnaire à la réception.
    func testBareTypingSentinelIsRecognisedAndHidden() {
        for bare in ["⌨️TYPING⌨️", "✍️TYPING✍️", "⌨TYPING⌨", "✍TYPING✍"] {
            XCTAssertTrue(OSHIControlPayload.isTypingPing(bare), "\(bare) est un ping de frappe")
            XCTAssertTrue(OSHIControlPayload.isHiddenFromThread(bare))
        }
        XCTAssertTrue(OSHIControlPayload.isTypingPing(#"⌨️TYPING⌨️{"isTyping":true}"#))
        // Et de la prose qui parle de frappe n'en est pas un.
        XCTAssertFalse(OSHIControlPayload.isTypingPing("je suis en train d'écrire ⌨️TYPING⌨️"))
        XCTAssertFalse(OSHIControlPayload.isTypingPing("TYPING"))
    }

    func testCallSummaryIsNotSilent() {
        // A call row is user history. Filtering it out with the receipts would
        // delete the call log from the thread — the opposite failure.
        XCTAssertFalse(OSHIControlPayload.isSilent("📱CALL_SUMMARY📱📞↗️call.outgoing|1:23"))
        XCTAssertFalse(OSHIControlPayload.isSilent("📱MISSED_CALL📱❌call.missed"))
        XCTAssertFalse(OSHIControlPayload.isSilent("📍LOCATION📍{\"latitude\":1}"))
    }

    // MARK: - Call rows (the second surface the user reported)

    func testCompletedCallRendersAsACallRowWithDuration() {
        let summary = OSHIControlPayload.previewText(for: "📱CALL_SUMMARY📱📞↗️call.outgoing|1:23")
        XCTAssertFalse(summary.contains("CALL_SUMMARY"), "the sentinel survived into the display text")
        XCTAssertFalse(summary.contains("call.outgoing"), "the raw localization KEY was shown instead of the translation")
        XCTAssertTrue(summary.contains("1:23"), "the duration was dropped: \(summary)")
        XCTAssertTrue(summary.hasPrefix("📞↗️"), "the direction arrow was dropped: \(summary)")
    }

    func testMissedCallRendersAsACallRow() {
        let summary = OSHIControlPayload.previewText(for: "📱MISSED_CALL📱❌call.missed")
        XCTAssertFalse(summary.contains("MISSED_CALL"))
        XCTAssertFalse(summary.contains("call.missed"), "the raw key leaked: \(summary)")
        XCTAssertTrue(summary.hasPrefix("❌"))
        XCTAssertFalse(summary.isEmpty)
    }

    func testCallSummaryWithoutDurationHasNoDanglingSeparator() {
        let summary = OSHIControlPayload.previewText(for: "📱CALL_SUMMARY📱📞↗️call.no.answer")
        XCTAssertFalse(summary.contains("•"), "an absent duration must not leave a bullet: \(summary)")
        XCTAssertFalse(summary.contains("call.no.answer"))
    }

    /// Other clients send the call as JSON rather than key|duration. Both shapes
    /// are on the wire, and the JSON one is what the user reported seeing.
    func testJSONShapedCallSummaryStillRendersAsACallRow() {
        let summary = OSHIControlPayload.previewText(
            for: #"📱CALL_SUMMARY📱{"direction":"outgoing","duration":83}"#
        )
        XCTAssertFalse(summary.contains("{"), "the JSON body reached the screen: \(summary)")
        XCTAssertFalse(summary.contains("direction"))
        XCTAssertTrue(summary.contains("1:23"), "83 seconds should read as 1:23, got: \(summary)")
    }

    func testUnparseableCallBodyStillNeverShowsItsPayload() {
        let summary = OSHIControlPayload.previewText(for: #"📱CALL_SUMMARY📱{"garbage":"#)
        XCTAssertFalse(summary.contains("{"), "a malformed body fell through raw: \(summary)")
        XCTAssertFalse(summary.contains("garbage"))
    }

    func testDurationFormatting() {
        XCTAssertEqual(OSHIControlPayload.formatDuration(0), "0:00")
        XCTAssertEqual(OSHIControlPayload.formatDuration(9), "0:09")
        XCTAssertEqual(OSHIControlPayload.formatDuration(83), "1:23")
        XCTAssertEqual(OSHIControlPayload.formatDuration(3661), "1:01:01")
    }

    // MARK: - Envelopes (the reported swipe-to-reply path)

    func testReplyEnvelopeUnwrapsToTheUserBody() {
        let envelope = #"💬REPLY💬{"content":"see you at 6","replyTo":{"originalMessageId":"abc","originalSenderKey":"k","originalText":"when?","originalTimestamp":0,"originalMediaType":null}}"#
        XCTAssertEqual(OSHIControlPayload.previewText(for: envelope), "see you at 6")
        XCTAssertFalse(OSHIControlPayload.previewText(for: envelope).contains("{"))
    }

    /// Replying to a call summary: the quote must read as a call, not as the
    /// JSON of a JSON. This is exactly what the user saw when swiping.
    func testReplyToACallSummaryQuotesTheCallRow() {
        let envelope = #"💬REPLY💬{"content":"📱CALL_SUMMARY📱📞↙️call.incoming|0:42","replyTo":null}"#
        let shown = OSHIControlPayload.previewText(for: envelope)
        XCTAssertFalse(shown.contains("CALL_SUMMARY"), "nested sentinel survived: \(shown)")
        XCTAssertFalse(shown.contains("{"))
        XCTAssertTrue(shown.contains("0:42"))
    }

    func testMalformedEnvelopeShowsNothingRatherThanItsJSON() {
        let broken = #"💬REPLY💬{"content":"truncated"#
        XCTAssertEqual(OSHIControlPayload.previewText(for: broken), "",
                       "a truncated envelope must collapse to empty, never to visible JSON")
    }

    func testEnvelopesArePreservedForTheBubbleRenderers() {
        // The bubble decodes the envelope itself to draw the quote box above the
        // body; unwrapping it in resolveText would delete that quote box.
        let envelope = #"💬REPLY💬{"content":"hi","replyTo":null}"#
        XCTAssertTrue(OSHIControlPayload.isEnvelope(envelope))
        XCTAssertEqual(OSHIControlPayload.displayTextPreservingEnvelopes(for: envelope), envelope)
        // …but a call summary, which has no quote box, is still summarised.
        let call = "📱CALL_SUMMARY📱📞↗️call.outgoing|1:23"
        XCTAssertNotEqual(OSHIControlPayload.displayTextPreservingEnvelopes(for: call), call)
    }

    // MARK: - The property that actually matters

    /// Whatever a surface does with `previewText`, the output may never contain
    /// a sentinel or a raw JSON brace. This is the single assertion that would
    /// have caught all three previous regressions.
    func testNoPayloadEverRendersAsJSONOrAsASentinel() {
        var samples: [String] = []
        for prefix in allPrefixes {
            samples.append(prefix + #"{"type":"x","value":42,"nested":{"a":[1,2]}}"#)
            samples.append(prefix)                       // bare marker, no body
            samples.append(prefix + "some trailing text")
        }
        samples.append(#"{"type":"member_sync","members":["a"]}"#)
        samples.append(#"{"type":"group_update","name":"x"}"#)
        samples.append(#"{"type":"media","mediaType":"photo","caption":""}"#)
        samples.append("MEDIA|||image|||AAAABBBB")
        samples.append("🔑KEY_ROTATION🔑{\"seq\":3}")
        samples.append("🔧ACTION🔧{\"action\":\"pin\"}")
        samples.append("👍 REACTION👍{\"emoji\":\"👍\"}")
        samples.append("__SESSION_RESET__")

        for sample in samples {
            let shown = OSHIControlPayload.previewText(for: sample)
            XCTAssertFalse(shown.contains("{"),
                           "JSON reached the user for \(sample.prefix(40).debugDescription): \(shown)")
            XCTAssertFalse(shown.contains("\":"),
                           "a JSON key/value pair reached the user for \(sample.prefix(40).debugDescription): \(shown)")
            for prefix in allPrefixes {
                XCTAssertFalse(shown.contains(prefix),
                               "sentinel \(prefix) survived into the display text for \(sample.prefix(40).debugDescription): \(shown)")
            }
        }
    }

    /// The wire splitter must keep reading the same catalog, not a seventh copy.
    /// Splitting a control payload in half produces two messages neither device
    /// can parse.
    func testWireSplitterDerivesFromTheSameCatalog() {
        for prefix in allPrefixes where prefix != OSHIControlPayload.replyPrefix {
            XCTAssertFalse(
                MessageTextSplitter.isUserProse(prefix + #"{"a":1}"#),
                "\(prefix) would be cut in half by the splitter"
            )
        }
        XCTAssertTrue(MessageTextSplitter.isUserProse("just a normal message"))
    }

    // MARK: - Reply quote previews

    /// The reported bug: "a reply to a bot post with a link and a photo says
    /// «Fichier»".
    ///
    /// There are TWO `MediaType` enums in this project and they disagree on how
    /// a picture is spelled — `MediaManager.MediaType.image` for 1:1 media,
    /// `MediaType.photo` for group media (GroupMessaging.swift). Bot posts are
    /// GROUP messages, so every quoted bot photo carried `"photo"`, missed the
    /// `"image"`-only switch, and fell through to the generic file label.
    ///
    /// Asserted against the localized strings rather than raw keys so a missing
    /// translation would also show up here.
    func testAQuotedPhotoReadsAsAPictureInBothMediaTypeSpellings() {
        for spelling in ["image", "photo"] {
            let reply = ReplyMessage(originalMessageId: "m1",
                                     originalSenderKey: "peer",
                                     originalText: "",
                                     originalTimestamp: Date(),
                                     originalMediaType: spelling)
            XCTAssertEqual(reply.mediaLabel, "📷 " + "media_image".localized,
                           "a quoted photo spelled \(spelling) does not read as a photo")
            XCTAssertNotEqual(reply.previewText, "📎 " + "media_file".localized,
                              "a quoted photo spelled \(spelling) still reads as a generic file")
            XCTAssertTrue(reply.quotesAPicture,
                          "no thumbnail would be drawn for a photo spelled \(spelling)")
        }
    }

    /// The other half of the same report: the bot post carried a LINK as well as
    /// the picture, and the quote showed neither. The media branch used to
    /// return early and throw `originalText` away, so a captioned photo lost its
    /// caption entirely.
    func testAQuotedPhotoKeepsItsCaption() {
        let reply = ReplyMessage(originalMessageId: "m2",
                                 originalSenderKey: "peer",
                                 originalText: "https://example.com/article",
                                 originalTimestamp: Date(),
                                 originalMediaType: "photo")
        XCTAssertEqual(reply.bodyText, "https://example.com/article")
        XCTAssertTrue(reply.previewText.contains("https://example.com/article"),
                      "the caption was dropped from the quote: \(reply.previewText)")
    }

    /// A media quote with no caption must still say what it was, and must never
    /// print the label twice — the 1:1 reply paths sanitise through the catalog
    /// BEFORE building the quote, so `originalText` can already be the label.
    func testAQuotedPhotoWithoutACaptionShowsTheLabelExactlyOnce() {
        let label = "📷 " + "media_image".localized
        for text in ["", label] {
            let reply = ReplyMessage(originalMessageId: "m3",
                                     originalSenderKey: "peer",
                                     originalText: text,
                                     originalTimestamp: Date(),
                                     originalMediaType: "image")
            XCTAssertEqual(reply.previewText, label,
                           "duplicated or lost the media label for originalText=\(text.debugDescription)")
        }
    }

    /// A quote is a preview like any other, so it goes through the one catalog:
    /// replying to a call summary or a location must never quote raw JSON.
    func testAQuotedControlPayloadIsNeverRawJSON() {
        for payload in ["📍LOCATION📍{\"latitude\":1.0,\"longitude\":2.0}",
                        "📱CALL_SUMMARY📱{\"duration\":83}"] {
            let reply = ReplyMessage(originalMessageId: "m4",
                                     originalSenderKey: "peer",
                                     originalText: payload,
                                     originalTimestamp: Date(),
                                     originalMediaType: nil)
            XCTAssertFalse(reply.previewText.contains("{"),
                           "raw JSON reached a reply quote: \(reply.previewText)")
        }
    }

    /// Plain text quotes are unchanged by all of the above, and still truncate.
    func testAPlainTextQuoteIsUnchangedAndStillTruncates() {
        let short = ReplyMessage(originalMessageId: "m5", originalSenderKey: "peer",
                                 originalText: "see you at 6", originalTimestamp: Date(),
                                 originalMediaType: nil)
        XCTAssertEqual(short.previewText, "see you at 6")
        XCTAssertNil(short.mediaLabel)
        XCTAssertFalse(short.quotesAPicture)

        let long = ReplyMessage(originalMessageId: "m6", originalSenderKey: "peer",
                                originalText: String(repeating: "a", count: 400),
                                originalTimestamp: Date(), originalMediaType: nil)
        XCTAssertTrue(long.previewText.hasSuffix("..."))
        XCTAssertLessThanOrEqual(long.previewText.count, 103)
    }

    // MARK: - __SILENT_FLAG_WAS_DECORATION_2026_09_02__
    //
    // Le catalogue ci-dessus dit ce qui ne doit pas s'AFFICHER dans un fil. Le
    // problème jumeau est ce qui ne doit pas SONNER: une poussée de réveil.
    //
    // Le défaut signalé — « l'iPhone 14, app ouverte, fait dire à l'iPhone 17
    // qu'il a reçu un message de l'iPhone 14, et rien n'arrive » — n'était pas
    // une liste incomplète. `GroupMessaging.requestMemberSync` DEMANDAIT déjà le
    // silence: titre vide, corps vide, `"silent": "true"`. Le drapeau était écrit
    // à un seul endroit et lu à AUCUN — un `grep` sur tout le dépôt ne rendait
    // que la ligne qui l'écrivait. Un drapeau que personne ne lit est un
    // commentaire, et celui-ci ressemblait à du code.
    //
    // Ces tests-ci gardent le CÂBLAGE, pas le comportement: le comportement
    // (aucune `aps.alert`, `apns-push-type: background`) est prouvé de bout en
    // bout par `ServerVPS/push_silent_wake.test.js`, qui parle au vrai
    // `/send-push`. Ici on vérifie seulement qu'il reste quelqu'un pour lire ce
    // que le client écrit — la marche exacte qui a été ratée.

    private func projectSource(_ relativePath: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent(relativePath), encoding: .utf8)
    }

    /// Le producteur: `requestMemberSync` demande un réveil, pas une bannière —
    /// et le demande par le PARAMÈTRE, pas par une entrée écrite à la main dans
    /// `additionalData` (c'est cette forme-là que personne ne lisait).
    func testMemberSyncAsksForASilentWake() throws {
        // __GROUP_E2E_V2_2026_09_23__ La poussée en clair `group_update` a été retirée:
        // `requestMemberSync` passe par l'état de groupe v2 (`v2SendGroupState`), dont le
        // réveil est demandé en SILENCE par le paramètre. On garde les deux maillons.
        let src = try projectSource("OSHI/GroupMessaging.swift")
        guard let fn = src.range(of: "func requestMemberSync(groupId: UUID)") else {
            return XCTFail("requestMemberSync a disparu — cette règle ne surveille plus rien")
        }
        let body = String(src[fn.lowerBound..<(src.index(fn.lowerBound, offsetBy: 1200, limitedBy: src.endIndex) ?? src.endIndex)])
        XCTAssertTrue(body.contains("v2SendGroupState("),
                      "la sync de membres ne passe plus par l'état de groupe v2")
        XCTAssertFalse(body.contains("wake: false"),
                       "la sync de membres ne réveille plus le destinataire")
        XCTAssertFalse(src.contains("type: \"group_update\""),
                       "la poussée en clair `group_update` est revenue dans GroupMessaging")

        let v2 = try projectSource("OSHI/MessageManager+V2.swift")
        guard let send = v2.range(of: "func v2SendGroupState(") else {
            return XCTFail("v2SendGroupState a disparu")
        }
        let sendBody = String(v2[send.lowerBound..<(v2.index(send.lowerBound, offsetBy: 1500, limitedBy: v2.endIndex) ?? v2.endIndex)])
        XCTAssertTrue(sendBody.contains("silent: true"),
                      "le réveil de l'état de groupe v2 ne demande plus le silence: il sonnera chez tous les membres")
        XCTAssertFalse(sendBody.contains("\"silent\": \"true\""),
                       "le drapeau est de nouveau écrit à la main dans additionalData — c'est la forme que rien ne lisait")
    }

    /// Le paramètre doit réellement poser le drapeau sur le fil.
    func testTheSilentParameterActuallySetsTheWireFlag() throws {
        let src = try projectSource("OSHI/VoIPPushManager.swift")
        XCTAssertTrue(src.contains("silent: Bool = false"),
                      "`requestPushNotification` n'expose plus de paramètre `silent`")
        XCTAssertTrue(src.contains("if silent { data[\"silent\"] = \"true\" }"),
                      "le paramètre `silent` ne pose plus le drapeau sur le fil: il ne fait plus rien")
    }

    /// Les deux LECTEURS. C'est l'assertion qui aurait attrapé le bug: le
    /// serveur (le vrai correctif) et la NSE (le filet, tant qu'un VPS n'est pas
    /// redéployé) doivent chacun consulter le drapeau.
    func testSomeoneActuallyReadsTheSilentFlag() throws {
        let server = try projectSource("ServerVPS/push_service.js")
        XCTAssertTrue(server.contains("String(data.silent) === 'true'"),
                      "/send-push ne lit plus le drapeau: toute poussée de réveil redevient une bannière")
        XCTAssertTrue(server.contains("'background'"),
                      "le chemin silencieux n'envoie plus en apns-push-type: background")

        let nse = try projectSource("OSHINotificationService/NotificationService.swift")
        XCTAssertTrue(nse.contains("userInfo[\"silent\"] as? String) == \"true\""),
                      "la NSE ne filtre plus les réveils: un serveur non redéployé rallume la bannière")
    }

    // MARK: - __PUSH_LISTS_DRIFTED_2026_09_02__ le catalogue décide aussi qui SONNE

    /// La règle qui rend la dérive impossible: tout `.silent` du catalogue doit
    /// avoir un sort décidé côté poussée. Ajouter un payload silencieux sans y
    /// penser fait échouer CE test, au lieu de produire une bannière fantôme
    /// six mois plus tard.
    func testEverySilentPayloadIsAlsoPushSilent() {
        let ringing = "📞CALL_SIGNAL📞"   // la seule exception, et elle est écrite
        for entry in OSHIControlPayload.catalog where entry.kind == .silent {
            if entry.prefix == ringing {
                XCTAssertFalse(OSHIControlPayload.suppressesPush(entry.prefix + "{}"),
                               "un signal d'appel muselé: le téléphone d'en face ne sonnerait plus")
                continue
            }
            XCTAssertTrue(OSHIControlPayload.suppressesPush(entry.prefix + "{}"),
                          "\(entry.prefix) est .silent mais réveillerait quand même le pair — " +
                          "c'est la bannière « nouveau message » sans message")
        }
    }

    /// Les quatre que les deux tableaux supprimés ne portaient PAS. Écrits à la
    /// main: c'est le trou constaté, pas une boucle sur le catalogue qui le
    /// re-déduirait.
    func testThePrefixesTheOldArraysMissedAreCoveredNow() {
        // Les DEUX tableaux supprimés, figés ici tels qu'ils étaient. Ils servent
        // de témoin: sans eux ce test dirait seulement « la nouvelle règle couvre
        // ces trois préfixes », sans montrer que l'ancienne ne les couvrait pas.
        let theOldArrays = [
            "📬DELIVERY_RECEIPT📬", "📖READ_RECEIPT📖",
            "📸PROFILE_UPDATE📸", "📸PROFILE_REQUEST📸",
            "🔗LINKS_VISIBILITY🔗", "🎨WALLPAPER_UPDATE🎨",
            "⌨️TYPING⌨️", "✍️TYPING✍️"
        ]
        for missed in ["🔧ACTION🔧", "📢GROUP_UPDATE📢", "__SESSION_RESET__"] {
            XCTAssertFalse(theOldArrays.contains(where: { missed.hasPrefix($0) }),
                           "témoin faux: \(missed) était déjà couvert, il n'y avait rien à corriger")
            XCTAssertTrue(OSHIControlPayload.suppressesPush(missed + "|1|abc"),
                          "\(missed) manquait aux DEUX tableaux et réveillerait encore")
        }
        // Et rien n'a été perdu au passage: les 8 d'avant sont toujours muselés.
        for kept in theOldArrays {
            XCTAssertTrue(OSHIControlPayload.suppressesPush(kept + "{}"),
                          "\(kept) était muselé avant et ne l'est plus")
        }
    }

    /// Et l'inverse — le vrai risque d'une règle trop large: un message qui
    /// mérite sa notification ne doit pas se faire museler au passage.
    func testRealMessagesStillWakeThePeer() {
        let mustRing = [
            "on se retrouve à 18h",
            "MEDIA|||image|||abc",
            "📱MISSED_CALL📱📞↙️call.no.answer",
            "🛡️CHECK_IN🛡️{\"ok\":true}",
            "📍LOCATION📍{\"isLive\":false}",
            "💬REPLY💬{\"originalText\":\"hi\"}",
            "➡️FORWARDED➡️{\"body\":\"hi\"}",
            "🔥REACTION🔥{\"emoji\":\"👍\"}",
            "📞CALL_SIGNAL📞AAAA"
        ]
        for text in mustRing {
            XCTAssertFalse(OSHIControlPayload.suppressesPush(text),
                           "muselé à tort: « \(text.prefix(24)) »")
        }
    }

    /// L'orthographe sans U+FE0F (clients Android et anciens builds iOS) doit
    /// être muselée elle aussi — sinon la règle ne couvre que la moitié du fil.
    func testStrippedSpellingsAreAlsoPushSilent() {
        XCTAssertTrue(OSHIControlPayload.suppressesPush("⌨TYPING⌨{}"),
                      "la variante sans sélecteur de variation réveille encore le pair")
    }

    /// Les deux appelants consultent bien le catalogue, et n'ont plus de copie
    /// locale de la liste. C'est la forme du bug, pas seulement sa valeur.
    func testNoCallerKeepsItsOwnCopyOfTheList() throws {
        for file in ["OSHI/MessageManager.swift", "OSHI/MessageManager+V2.swift"] {
            let src = try projectSource(file)
            XCTAssertTrue(src.contains("OSHIControlPayload.suppressesPush"),
                          "\(file) ne demande plus au catalogue qui réveiller")
            XCTAssertFalse(src.contains("\"📬DELIVERY_RECEIPT📬\", \"📖READ_RECEIPT📖\",\n"),
                           "\(file) a re-créé sa propre liste de préfixes silencieux")
        }
    }

    // MARK: - 🔒 Aucun nom de groupe dans un titre de poussée

    /// La règle (2026-07-12): le NOM d'un groupe ne part pas dans le titre ni le
    /// corps d'une poussée — il serait lisible par le relais APNs et sur l'écran
    /// verrouillé. Elle vivait dans un commentaire du chemin legacy, et les
    /// chemins écrits APRÈS elle ne l'ont jamais reçue: les deux poussées v2 de
    /// groupe ET la poussée « nouveau membre » postaient toutes le nom.
    ///
    /// Le balayage couvre TOUT `OSHI/`, pas le fichier où le défaut a été
    /// trouvé. Une règle qui ne regarde qu'une surface est exactement ce qui a
    /// laissé les deux autres en place — c'est déjà l'histoire de
    /// `OSHIControlPayload` lui-même.
    func testNoPushAnywhereCarriesAGroupNameInItsTitle() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
        let dir = root.appendingPathComponent("OSHI")
        let files = (FileManager.default.enumerator(atPath: dir.path)?
            .compactMap { $0 as? String }
            .filter { $0.hasSuffix(".swift") }) ?? []
        XCTAssertGreaterThan(files.count, 50, "le balayage n'a presque rien lu — chemin faux?")

        var offenders: [String] = []
        var pushCallsSeen = 0

        for rel in files {
            let text = try String(contentsOf: dir.appendingPathComponent(rel), encoding: .utf8)
            // Sans les commentaires: la note qui explique le correctif CITE la
            // ligne fautive, et une règle qui lit la prose s'accuse elle-même.
            // (Elle l'a fait au premier passage.)
            let code = text.split(separator: "\n", omittingEmptySubsequences: false)
                .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
                .joined(separator: "\n")

            for callName in ["requestPushNotification(", "PushRetryQueue.shared.enqueue("] {
                var idx = code.startIndex
                while let r = code.range(of: callName, range: idx..<code.endIndex) {
                    idx = r.upperBound
                    pushCallsSeen += 1
                    let end = code.index(r.upperBound, offsetBy: 400, limitedBy: code.endIndex) ?? code.endIndex
                    let args = String(code[r.upperBound..<end])
                    // L'argument `title:` de CET appel, jusqu'à la virgule.
                    guard let t = args.range(of: "title:") else { continue }
                    let after = args[t.upperBound...]
                    let title = String(after.prefix(while: { $0 != "," && $0 != "\n" }))
                    if title.contains("roupName") || title.contains("group.name") {
                        offenders.append("\(rel): title:\(title)")
                    }
                }
            }
        }

        XCTAssertGreaterThan(pushCallsSeen, 5,
                             "aucun appel de poussée trouvé — la règle ne surveille rien")
        XCTAssertTrue(offenders.isEmpty,
                      "nom de groupe dans un titre de poussée:\n" + offenders.joined(separator: "\n"))
    }

    /// Le témoin: la règle doit RECONNAÎTRE les trois formes qui existaient
    /// vraiment. Sans ça, un balayage vert ne dit pas s'il sait quoi chercher.
    func testTheGroupNameRuleRecognisesTheShapesThatShipped() {
        let shipped = [
            "                    to: member, type: \"group\", title: groupName,",
            "                        recipientPublicKey: member, type: \"group\", title: groupName,",
            "                    type: \"group_message\",\n                    title: ad.groupName,"
        ]
        for line in shipped {
            guard let t = line.range(of: "title:") else {
                return XCTFail("le témoin ne contient pas de title: — il ne teste rien")
            }
            let title = String(line[t.upperBound...].prefix(while: { $0 != "," && $0 != "\n" }))
            XCTAssertTrue(title.contains("roupName") || title.contains("group.name"),
                          "la règle ne reconnaît plus « \(line.suffix(40)) »")
        }
    }

    /// Le témoin. La règle ci-dessus doit TOMBER quand le lecteur disparaît —
    /// sinon elle passerait aussi sur le code fautif, comme le drapeau lui-même.
    func testTheWiringRuleRejectsAnUnreadFlag() throws {
        let server = try projectSource("ServerVPS/push_service.js")
        let withoutReader = server.replacingOccurrences(
            of: "String(data.silent) === 'true'", with: "false /* reader removed */")
        XCTAssertFalse(withoutReader.contains("String(data.silent) === 'true'"),
                       "le témoin n'a rien retiré: il ne prouve rien")
    }
}
