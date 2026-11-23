//
//  GroupMentionTests.swift
//  OSHITests
//
//  `@name` mentions share the `@` sigil with `@TICKER`, so the resolution order
//  is the whole design: a token matching a group member is a MENTION, and only
//  what is left over goes to the ticker parser.
//
//  A person always wins. Someone called "Max" turned into a link to a stock
//  chart is a far worse failure than a ticker rendering as plain text.
//

import XCTest
@testable import OSHI

final class GroupMentionTests: XCTestCase {

    private let members = [
        "hugo": "KEY_HUGO",
        "jean": "KEY_JEAN",
        "jean-luc": "KEY_JEANLUC",
        "Chloé": "KEY_CHLOE",
        "AAPL": "KEY_PERSON_NAMED_AAPL",
    ]

    private func names(_ s: String) -> [String] {
        GroupMentions.matches(in: s, members: members).map(\.name)
    }

    private func keys(_ s: String) -> [String] {
        GroupMentions.matches(in: s, members: members).map(\.publicKey)
    }

    // MARK: Resolution

    func testSimpleMention() {
        XCTAssertEqual(keys("hey @hugo look"), ["KEY_HUGO"])
    }

    func testMentionAtStartAndEnd() {
        XCTAssertEqual(keys("@hugo"), ["KEY_HUGO"])
        XCTAssertEqual(keys("ping @hugo"), ["KEY_HUGO"])
    }

    func testSeveralMentions() {
        XCTAssertEqual(keys("@hugo and @jean"), ["KEY_HUGO", "KEY_JEAN"])
    }

    func testCaseInsensitive() {
        XCTAssertEqual(keys("@HUGO"), ["KEY_HUGO"])
        XCTAssertEqual(keys("@Hugo"), ["KEY_HUGO"])
    }

    /// A mention that fails because of an accent is a mention that silently
    /// notifies nobody.
    func testAccentInsensitive() {
        XCTAssertEqual(keys("@chloe"), ["KEY_CHLOE"])
        XCTAssertEqual(keys("@Chloé"), ["KEY_CHLOE"])
    }

    /// FOUND BY THE FIRST RUN OF THIS FILE. Two members whose names differ only
    /// by case or accent fold to the same token, and a dictionary would keep
    /// whichever was written last — so `@jean` would notify one of them at
    /// random. Notifying the wrong person in a group is a real harm and an
    /// invisible one, so an ambiguous name resolves to NOBODY.
    func testAmbiguousNamesResolveToNobodyRatherThanGuessing() {
        let colliding = ["Jean": "KEY_A", "Jeán": "KEY_B", "hugo": "KEY_HUGO"]
        XCTAssertEqual(GroupMentions.matches(in: "@jean ping", members: colliding).count, 0,
                       "an ambiguous mention was resolved by guessing")
        // Unambiguous members in the same group are unaffected.
        XCTAssertEqual(GroupMentions.matches(in: "@hugo ping", members: colliding)
                        .map(\.publicKey), ["KEY_HUGO"])
    }

    /// Longest name first, or "@jean-luc" is eaten by the member called "jean".
    func testLongestNameWins() {
        XCTAssertEqual(keys("@jean-luc are you there"), ["KEY_JEANLUC"])
    }

    /// Trailing prose must not be swallowed into the name.
    func testPunctuationAfterAMentionIsNotPartOfIt() {
        XCTAssertEqual(keys("@hugo, come here"), ["KEY_HUGO"])
        XCTAssertEqual(keys("@hugo?"), ["KEY_HUGO"])
    }

    // MARK: What must NOT resolve

    func testUnknownNameIsNotAMention() {
        XCTAssertEqual(keys("@nobody here"), [])
    }

    func testEmailAddressIsNotAMention() {
        XCTAssertEqual(keys("write to hugo@jean.com"), [])
    }

    func testInsideAURLIsNotAMention() {
        XCTAssertEqual(keys("https://x.com/i/@hugo/status"), [])
    }

    func testEmptyMemberListResolvesNothing() {
        XCTAssertEqual(GroupMentions.matches(in: "@hugo", members: [:]).count, 0)
    }

    // MARK: The collision with tickers — the point of this file

    /// A member named AAPL must be a PERSON, not a stock link. This is the
    /// ordering rule stated as a test.
    func testAMemberWhoseNameLooksLikeATickerIsStillAPerson() {
        XCTAssertEqual(keys("@AAPL what do you think"), ["KEY_PERSON_NAMED_AAPL"])
    }

    /// And a ticker nobody is named after stays available to the ticker parser:
    /// mentions must not claim it.
    func testATickerThatMatchesNoMemberIsNotAMention() {
        XCTAssertEqual(keys("buy @TSLA today"), [])
        XCTAssertEqual(TickerLinks.matches(in: "buy @TSLA today").map(\.symbol), ["TSLA"])
    }

    // MARK: The notify decision

    func testMentionsPredicateAgreesWithMatches() {
        XCTAssertTrue(GroupMentions.mentions("KEY_HUGO", in: "@hugo ping", members: members))
        XCTAssertFalse(GroupMentions.mentions("KEY_JEAN", in: "@hugo ping", members: members))
        XCTAssertFalse(GroupMentions.mentions("KEY_HUGO", in: "no mention", members: members))
    }
}

// MARK: - Rendering on the receiving device
//
// A mention travels as a KEY, not as text, so the sender's private nickname for
// someone is never imposed on everyone else's copy of the thread. Each device
// renders the name IT knows.

final class MentionRenderTests: XCTestCase {

    private let key = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: "contactAliases")
    }
    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: "contactAliases")
        super.tearDown()
    }

    private func setLocalAlias(_ name: String) {
        let data = try! JSONEncoder().encode([key: name])
        UserDefaults.standard.set(data, forKey: "contactAliases")
    }

    /// The group's own member alias wins: it is the name that group agreed on.
    func testGroupAliasWins() {
        setLocalAlias("my private nickname")
        XCTAssertEqual(GroupMentions.displayName(for: key, groupAlias: "Hugo"), "Hugo")
    }

    /// No group alias — fall back to what THIS device calls them.
    func testLocalAliasUsedWhenNoGroupAlias() {
        setLocalAlias("Hugo")
        XCTAssertEqual(GroupMentions.displayName(for: key), "Hugo")
    }

    /// THE case the owner asked about: a reader who never named this person.
    /// Five characters, not the whole key — a raw base64 blob mid-sentence is
    /// unreadable and pushes the rest of the line off screen.
    func testFirstFiveCharactersWhenNobodyNamedThem() {
        XCTAssertEqual(GroupMentions.displayName(for: key), "eAbMQ")
        XCTAssertEqual(GroupMentions.displayName(for: key).count, 5)
    }

    func testEmptyAliasesAreTreatedAsAbsent() {
        setLocalAlias("")
        XCTAssertEqual(GroupMentions.displayName(for: key), "eAbMQ")
        XCTAssertEqual(GroupMentions.displayName(for: key, groupAlias: ""), "eAbMQ")
    }

    // MARK: The wire form

    func testWireTokenCarriesTheKeyNotTheName() {
        let token = GroupMentions.wireToken(for: key)
        XCTAssertTrue(token.contains(key))
        XCTAssertEqual(GroupMentions.wireMentions(in: "hey \(token) look"), [key])
    }

    func testSeveralWireMentions() {
        let k2 = "ZZZZQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="
        let text = "\(GroupMentions.wireToken(for: key)) and \(GroupMentions.wireToken(for: k2))"
        XCTAssertEqual(GroupMentions.wireMentions(in: text), [key, k2])
    }

    /// The same message renders DIFFERENTLY on two devices — that is the point.
    func testRenderUsesTheNameThisDeviceKnows() {
        let wire = "hey \(GroupMentions.wireToken(for: key)) look"

        // Device that named them.
        setLocalAlias("Hugo")
        XCTAssertEqual(GroupMentions.render(wire), "hey @Hugo look")

        // Device that did not.
        UserDefaults.standard.removeObject(forKey: "contactAliases")
        XCTAssertEqual(GroupMentions.render(wire), "hey @eAbMQ look")

        // Device where the GROUP has a name for them.
        XCTAssertEqual(GroupMentions.render(wire, groupAliases: [key: "Boss"]),
                       "hey @Boss look")
    }

    func testTextWithoutMentionsIsUntouched() {
        let plain = "no mentions here at all"
        XCTAssertEqual(GroupMentions.render(plain), plain)
    }

    /// A malformed token must be left alone rather than half-rewritten.
    func testMalformedTokenIsLeftAsIs() {
        XCTAssertEqual(GroupMentions.render("@[short]"), "@[short]")
        XCTAssertEqual(GroupMentions.wireMentions(in: "@[short]"), [])
    }
}
