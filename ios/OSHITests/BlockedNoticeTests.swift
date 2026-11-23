//
//  BlockedNoticeTests.swift
//  OSHITests
//
//  __BLOCKED_NOTICE_2026_09_18__ Le blocage le DIT, maintenant.
//
//  L'app promettait le contraire, en 34 langues: « ils ne seront pas informés
//  qu'ils ont été bloqués ». Le propriétaire a tranché dans l'autre sens — le
//  dire — ce qui fait une règle à deux moitiés qui doivent s'accorder, sur deux
//  plateformes: l'appareil qui bloque émet `🚫BLOCKED🚫` quand quelque chose
//  adressé à une clé bloquée se présente, et l'appareil bloqué l'affiche dans sa
//  langue.
//
//  Ce qui se décide sans appareil est vérifié ici: l'entrée de catalogue et son
//  rendu, les trois règles qui empêchent l'avis de devenir un canal de spam ou
//  une balise de présence, la porte d'envoi qui refuse tout le reste, et le fait
//  qu'Android épelle la sentinelle à l'identique — une divergence d'un seul
//  caractère est un non-événement silencieux sur le fil, qu'aucun test sur
//  appareil ne rattraperait de part ni d'autre.
//

import XCTest
@testable import OSHI

final class BlockedNoticeTests: XCTestCase {

    private let notice = OSHIControlPayload.blockedNoticePrefix

    // MARK: - L'entrée de catalogue

    func testTheNoticeIsCataloguedAndNotLooseText() {
        XCTAssertEqual(notice, "🚫BLOCKED🚫")
        XCTAssertTrue(
            OSHIControlPayload.catalog.contains { $0.prefix == notice },
            "une sentinelle déclarée ailleurs que dans le catalogue est le bug des six copies que ce fichier existe pour empêcher")
        XCTAssertTrue(OSHIControlPayload.isControl(notice))
    }

    func testTheNoticeIsShownNotSwallowed() {
        // Tout l'intérêt de (b) sur (a): un avis muet, c'est le comportement
        // qu'on vient précisément de remplacer.
        XCTAssertFalse(OSHIControlPayload.isSilent(notice))
        let shown = OSHIControlPayload.previewText(for: notice)
        XCTAssertTrue(shown.hasPrefix("🚫 "), "rendu attendu « 🚫 <libellé> », obtenu « \(shown) »")
        XCTAssertFalse(shown.contains("BLOCKED"), "la sentinelle brute a fui jusqu'à une surface: \(shown)")
        XCTAssertNotEqual(shown, "🚫 chat.blocked_by_contact",
                          "la clé n'est pas traduite — elle manque dans Localizable.strings")
    }

    // MARK: - Les trois règles

    func testANoticeNeverAnswersANotice() {
        // Deux personnes qui se sont bloquées mutuellement ne doivent pas se
        // pinguer sans fin. `suppressesPush` est le tamis qu'applique l'émetteur:
        // que l'avis y figure est ce qui ferme la boucle.
        XCTAssertTrue(OSHIControlPayload.suppressesPush(notice))
    }

    func testMachineryIsNeverAnswered() {
        // Un accusé ou une frappe venant d'un pair bloqué n'est personne qui
        // essaie de joindre quelqu'un, et la diffusion périodique de profil n'est
        // même pas un geste. Répondre à l'un d'eux émettrait des avis sans que
        // personne n'ait rien demandé.
        for payload in ["📬DELIVERY_RECEIPT📬abc", "📖READ_RECEIPT📖{}", "⌨️TYPING⌨️",
                        "✍️TYPING✍️", "📸PROFILE_UPDATE📸x", "🎨WALLPAPER_UPDATE🎨x",
                        "🔧ACTION🔧{}", "{\"type\":\"member_sync\",\"groupId\":\"g1\"}"] {
            XCTAssertTrue(OSHIControlPayload.suppressesPush(payload),
                          "\(payload) ne doit pas attirer d'avis")
        }
        // …alors qu'un message réellement écrit, si.
        XCTAssertFalse(OSHIControlPayload.suppressesPush("tu es là ?"))
    }

    // MARK: - L'autre moitié: plus rien ne part vers une clé bloquée

    func testTheNoticeItselfIsNotUserProseAndSoPassesTheGate() {
        // La porte d'envoi refuse toute PROSE adressée à une clé bloquée. L'avis
        // est l'exception voulue, et il n'a rien à faire d'un drapeau spécial
        // pour ça: il porte une sentinelle du catalogue, donc `isUserProse` le
        // laisse passer tout seul. Si cette assertion tombe, l'avis ne part plus
        // — silencieusement.
        XCTAssertFalse(MessageTextSplitter.isUserProse(notice))
        XCTAssertTrue(MessageTextSplitter.isUserProse("tu es là ?"))
    }

    func testARefusedSendIsSaidNotSwallowed() {
        let outcome = MessageSendOutcome.blocked
        XCTAssertFalse(outcome.didSend)
        XCTAssertNotNil(outcome.userFacingMessage,
                        "un envoi refusé sans un mot, c'est le message perdu en silence que ce champ existe pour empêcher")
        XCTAssertNotEqual(outcome.userFacingMessage, "send.error.blocked",
                          "la clé n'est pas traduite")
    }

    // MARK: - Parité

    func testAndroidSpellsTheSentinelExactlyTheSameWay() throws {
        // Le dépôt Android est à côté de l'arbre iOS dans le même checkout.
        let kotlin = try Self.repoFile(
            "OSHI-Android/app/src/main/java/com/oshi/messenger/data/model/OSHIControlPayload.kt")
        XCTAssertTrue(kotlin.contains("const val BLOCKED_NOTICE = \"\(notice)\""),
                      "Android doit porter 🚫BLOCKED🚫 au caractère près — sinon l'avis arrive chez lui comme du JSON illisible")
        XCTAssertTrue(kotlin.contains("Entry(BLOCKED_NOTICE, Kind.RENDERED)"),
                      "et il doit l'AFFICHER, pas l'avaler")
        XCTAssertTrue(kotlin.contains("WALLPAPER_UPDATE, BLOCKED_NOTICE)"),
                      "et le tamiser de ses propres réponses, comme ici")
    }

    private static func repoFile(_ relative: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try String(contentsOf: candidate, encoding: .utf8)
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("\(relative) introuvable depuis \(#filePath)")
    }
}

// MARK: - __BLOCK_MUTE_DECISIONS_2026_09_24__ owner decisions on block / mute

/// 2) no missed-call trace of a blocked contact · 3) a blocked member is not shown in groups ·
/// 4) block/unblock and group mute sync between the user's own devices (LWW, iOS format).
final class BlockMuteDecisionsTests: XCTestCase {

    private static func randomKey() -> String {
        Data((0..<32).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
    }

    private static func repoFile(_ relative: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try String(contentsOf: candidate, encoding: .utf8)
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("\(relative) not found from \(#filePath)")
    }

    private static func fixturesDir() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("docs/fixtures/devsync")
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent("merge.json").path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("docs/fixtures/devsync not found")
    }

    // MARK: 2b — "this peer told me I am blocked" (same evidence table on Android / Desktop)

    func testEvidenceTableMatchesTheOtherPlatforms() {
        typealias E = BlockedByPeerStore.Evidence
        let table: [(String?, E)] = [
            ("🚫BLOCKED🚫", .blocked),
            ("📬DELIVERY_RECEIPT📬x", .unblocked),
            ("📖READ_RECEIPT📖x", .unblocked),
            ("salut", .unblocked),
            ("⌨️TYPING⌨️", .none),
            ("📸PROFILE_UPDATE📸{}", .none),
            (nil, .none),
        ]
        for (input, expected) in table {
            XCTAssertEqual(BlockedByPeerStore.evidence(of: input), expected, "\(input ?? "nil")")
        }
    }

    func testNoticeRecordsAndARealMessageOrReceiptLiftsIt() {
        let suite = "BlockedByPeer-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        let store = BlockedByPeerStore(defaults: d)
        let peer = Self.randomKey()
        let urlsafe = peer.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))

        store.observeInbound(from: peer, plaintext: "🚫BLOCKED🚫")
        XCTAssertTrue(store.isBlockedBy(urlsafe), "any spelling of the key")
        XCTAssertTrue(BlockedByPeerStore(defaults: d).isBlockedBy(peer), "survives a relaunch")
        store.observeInbound(from: peer, plaintext: "⌨️TYPING⌨️")
        XCTAssertTrue(store.isBlockedBy(peer), "mechanics say nothing")
        store.observeInbound(from: peer, plaintext: "📬DELIVERY_RECEIPT📬abc")
        XCTAssertFalse(store.isBlockedBy(peer), "a device that still blocks us never acks")

        store.observeStoredInbound(from: peer, plaintext: "🚫BLOCKED🚫")
        XCTAssertTrue(store.isBlockedBy(peer))
        store.observeStoredInbound(from: peer, plaintext: nil)   // a media row
        XCTAssertFalse(store.isBlockedBy(peer))
    }

    func testTheCallerDoesNotDialSomeoneWhoBlocksIt() throws {
        let vcm = try Self.repoFile("OSHI/VoiceCallManager.swift")
        let start = try XCTUnwrap(vcm.range(of: "func startCall(to peerPublicKey: String"))
        let refuse = try XCTUnwrap(vcm.range(of: "throw CallError.peerUnavailable", range: start.upperBound..<vcm.endIndex))
        let callKit = try XCTUnwrap(vcm.range(of: "VoIPPushManager.shared.startOutgoingCall", range: start.upperBound..<vcm.endIndex))
        let push = try XCTUnwrap(vcm.range(of: "VoIPPushManager.sendVoIPPush(", range: start.upperBound..<vcm.endIndex))
        XCTAssertLessThan(refuse.lowerBound, callKit.lowerBound, "refused before CallKit starts anything")
        XCTAssertLessThan(refuse.lowerBound, push.lowerBound, "refused before any VoIP push")
        XCTAssertNotEqual(CallError.peerUnavailable.errorDescription, "call.error.unavailable", "translated")
        XCTAssertTrue(vcm.contains("BlockedByPeerStore.shared.clear(peerPublicKey)"), "a peer that calls us no longer blocks us")
        // __ONE_TAP_CALL_2026_09_29__ This asserted the string appeared TWICE,
        // once per call button. That was a proxy for "both the voice and the
        // video button refuse to dial someone who blocked us", and the proxy
        // broke when the code got better: the two buttons were folded into one
        // `startHeaderCall(video:)` behind a shared `callBlockedMessage`, so the
        // string is now correctly present exactly ONCE and both paths are still
        // guarded. Counting occurrences would now fail on a de-duplication and
        // pass on a guard that was bypassed, which is backwards.
        //
        // Assert the invariant instead: the reason exists, and the single entry
        // point consults it and returns BEFORE it can place a call.
        let chat = try Self.repoFile("OSHI/ChatView.swift")
        XCTAssertTrue(chat.contains("if isBlockedByContact { return \"call.error.unavailable\".localized }"),
                      "a peer who blocked us makes the call unavailable")
        let header = try XCTUnwrap(chat.range(of: "private func startHeaderCall(video: Bool)"),
                                   "both call buttons go through startHeaderCall")
        let guardCheck = try XCTUnwrap(chat.range(of: "if let message = callBlockedMessage",
                                                  range: header.upperBound..<chat.endIndex))
        let dial = try XCTUnwrap(chat.range(of: "voiceCallManager.startCall(",
                                            range: header.upperBound..<chat.endIndex))
        XCTAssertLessThan(guardCheck.lowerBound, dial.lowerBound,
                          "the block is checked before the call is placed")
        XCTAssertTrue(chat.contains("onVideoTap: isContactBlocked ? { } : {"),
                      "the video entry point is inert for a blocked contact")
    }

    func testTheBlockedVoIPPushIsKeptOutOfRecentsAndNeverAMissedCall() throws {
        let voip = try Self.repoFile("OSHI/VoIPPushManager.swift")
        let r = try XCTUnwrap(voip.range(of: "why: \"blocked_caller\""))
        let around = String(voip[voip.index(r.lowerBound, offsetBy: -200)..<voip.index(r.upperBound, offsetBy: 120)])
        XCTAssertTrue(around.contains("endReason: .declinedElsewhere"), "never .unanswered (= a MISSED call in Recents)")
        XCTAssertTrue(around.contains("hideFromRecents: true"))
        XCTAssertTrue(voip.contains("config.includesCallsInRecents = true"), "real OSHI calls stay in Recents")
        // A real incoming report and an outgoing start put Recents back on first.
        let real = try XCTUnwrap(voip.range(of: "restoreRecentsForRealCall()   // __BLOCKED_NO_RECENTS_2026_09_24__\n        reportNewIncomingCallFromAnyQueue(uuid, update)"))
        XCTAssertNotNil(real)
        XCTAssertTrue(voip.contains("restoreRecentsForRealCall()   // __BLOCKED_NO_RECENTS_2026_09_24__\n        callController?.request(transaction)"))
    }

    // MARK: 2c — no line in OSHI's own call history

    func testNoCallSummaryIsWrittenForABlockedContact() throws {
        let mm = try Self.repoFile("OSHI/MessageManager.swift")
        let s = try XCTUnwrap(mm.range(of: "func handleCallEndedWriteSummary("))
        let body = String(mm[s.upperBound...].prefix(1400))
        let guardIdx = try XCTUnwrap(body.range(of: "guard !BlockedContactsManager.shared.isBlocked(peer) else { return }"))
        let writeIdx = try XCTUnwrap(body.range(of: "answeredCallSummary("))
        XCTAssertLessThan(guardIdx.lowerBound, writeIdx.lowerBound)
        let chat = try Self.repoFile("OSHI/ChatView.swift")
        let m = try XCTUnwrap(chat.range(of: "private func handleMissedCall("))
        XCTAssertTrue(String(chat[m.upperBound...].prefix(700)).contains("guard !BlockedContactsManager.shared.isBlocked(peerKey) else { return }"))
    }

    // MARK: 3 — a blocked member's group message is dropped, a SYSTEM row is not

    @MainActor
    func testABlockedMembersGroupMessageIsDroppedNotStored() {
        let gm = MessageGroupManager.shared
        let bm = BlockedContactsManager.shared
        let blocked = Self.randomKey(), friend = Self.randomKey()
        let gid = UUID()
        let group = MessageGroup(id: gid, name: "t-block-drop", type: .collaborative, adminPublicKey: friend,
                                 members: [GroupMember(publicKey: blocked, alias: nil, joinedAt: Date(), isAdmin: false),
                                           GroupMember(publicKey: friend, alias: nil, joinedAt: Date(), isAdmin: true)],
                                 createdAt: Date(), lastActivity: Date())
        gm.groups.append(group)
        bm.blockContact(publicKey: blocked)
        defer {
            bm.unblockContact(publicKey: blocked)
            gm.groups.removeAll { $0.id == gid }
            gm.groupMessages.removeValue(forKey: gid)
            gm.saveGroups()
        }
        func msg(_ sender: String, _ text: String, system: GroupSystemMessageType? = nil) -> GroupMessage {
            GroupMessage(id: UUID(), groupId: gid, senderPublicKey: sender, encryptedContent: Data(text.utf8),
                         timestamp: Date(), plaintextContent: text, systemMessageType: system)
        }
        gm.receiveGroupMessage(msg(blocked, "spam"))
        gm.receiveGroupMessage(msg(friend, "hello"))
        gm.receiveGroupMessage(msg("SYSTEM", "x left", system: .memberLeft))
        let rows = gm.groupMessages[gid] ?? []
        XCTAssertFalse(rows.contains { $0.senderPublicKey == blocked }, "not stored → no row, unread, preview or banner")
        XCTAssertTrue(rows.contains { $0.senderPublicKey == friend })
        XCTAssertTrue(rows.contains { $0.senderPublicKey == "SYSTEM" }, "joins/leaves stay visible")
    }

    // MARK: 4 — devsync (golden vectors + the iOS store's export/apply)

    func testDevSyncGoldenVectorsIncludingBlockMuteRecords() throws {
        let r = DevSyncVectorCheck.run(dir: try Self.fixturesDir())
        // The golden-vector files only: `loopback.*` is an in-process two-engine run with real
        // timers (relink / approval timing) — measured flaky under a loaded simulator, and not
        // what this test is about.
        let failures = r.lines.filter { $0.hasPrefix("FAIL") && !$0.hasPrefix("FAIL loopback.") }
        XCTAssertEqual(failures.count, 0, failures.joined(separator: "\n"))
        for name in ["merge.contact.unblock_later_wins", "merge.contact.stale_block_does_not_undo_newer_unblock",
                     "merge.contact.adopted_unblock_then_stale_block_stays_unblocked",
                     "merge.group.mute_later_wins", "merge.group.unmute_later_wins",
                     "record.contact.unblock_is_kept_with_its_timestamp", "record.group.unknown_fields_are_ignored"] {
            XCTAssertTrue(r.lines.contains("PASS \(name)"), "\(name) not exercised")
        }
    }

    @MainActor
    func testUnblockIsExportedWithItsTimeAndASyncedValueIsNotRestamped() throws {
        let bm = BlockedContactsManager.shared
        let pk = DSIds.canonicalKey(Self.randomKey())
        let store = DevSyncIOSStore(state: DSMemoryStateStore())
        defer {
            if bm.isBlocked(pk) { bm.unblockContact(publicKey: pk) }
            var m = UserDefaults.standard.dictionary(forKey: BlockedContactsManager.unblockedAtDefaultsKey) ?? [:]
            m.removeValue(forKey: pk)
            UserDefaults.standard.set(m, forKey: BlockedContactsManager.unblockedAtDefaultsKey)
        }
        _ = store.contacts()                                   // first snapshot (#init)
        XCTAssertNil(store.contacts().first { $0.publicKey == pk }, "never blocked: nothing to say")

        bm.blockContact(publicKey: pk)
        let b = try XCTUnwrap(store.contacts().first { $0.publicKey == pk })
        XCTAssertEqual(b.blocked, true)
        let blockedAt = try XCTUnwrap(b.blockedAtMs)

        bm.unblockContact(publicKey: pk)
        let u = try XCTUnwrap(store.contacts().first { $0.publicKey == pk })
        XCTAssertEqual(u.blocked, false, "an unblock travels as blocked:false…")
        XCTAssertGreaterThan(try XCTUnwrap(u.blockedAtMs), blockedAt, "…with a later blockedAt")
        XCTAssertEqual(u.json()["blocked"] as? Bool, false)
        XCTAssertNotNil(u.json()["blockedAt"] as? String)

        // A synced block keeps the MERGED time; a synced unblock too.
        let t1 = u.blockedAtMs! + 60_000
        store.putContact(SyncContact(publicKey: pk, blocked: true, blockedAtMs: t1))
        XCTAssertTrue(bm.isBlocked(pk))
        XCTAssertEqual(store.contacts().first { $0.publicKey == pk }?.blockedAtMs, t1, "not restamped")
        store.putContact(SyncContact(publicKey: pk, blocked: false, blockedAtMs: t1 + 1))
        XCTAssertFalse(bm.isBlocked(pk))
        let after = try XCTUnwrap(store.contacts().first { $0.publicKey == pk })
        XCTAssertEqual(after.blocked, false)
        XCTAssertEqual(after.blockedAtMs, t1 + 1)
    }

    @MainActor
    func testGroupMuteIsExportedAndAppliedLastWriterWins() throws {
        let gm = MessageGroupManager.shared
        let me = Self.randomKey()
        let gid = UUID()
        let gidS = gid.uuidString.uppercased()
        gm.groups.append(MessageGroup(id: gid, name: "t-mute-sync", type: .collaborative, adminPublicKey: me,
                                      members: [GroupMember(publicKey: me, alias: nil, joinedAt: Date(), isAdmin: true)],
                                      createdAt: Date(), lastActivity: Date()))
        defer {
            gm.groups.removeAll { $0.id == gid }
            gm.saveGroups()
            UserDefaults.standard.removeObject(forKey: "devsync.mutedAt." + gidS)
        }
        let store = DevSyncIOSStore(state: DSMemoryStateStore())
        _ = store.groups()
        let first = try XCTUnwrap(store.groups().first { $0.groupId == gidS })
        XCTAssertNil(first.muted, "a group never muted says nothing (old peers see no change)")
        XCTAssertNil(first.mutedAtMs)

        gm.toggleMuteGroup(gid)
        let muted = try XCTUnwrap(store.groups().first { $0.groupId == gidS })
        XCTAssertEqual(muted.muted, true)
        let at = try XCTUnwrap(muted.mutedAtMs)
        XCTAssertEqual(muted.json()["muted"] as? Bool, true)

        // Another device unmuted later: the merge takes it, the store applies it and keeps its time.
        let remote = SyncGroup(groupId: gidS, name: muted.name, members: muted.members, admins: muted.admins,
                               updatedAtMs: muted.updatedAtMs, muted: false, mutedAtMs: at + 5_000)
        let merged = SyncMerge.group(muted, remote)
        XCTAssertEqual(merged.muted, false)
        store.putGroup(merged)
        XCTAssertFalse(gm.isGroupMuted(gid))
        let back = try XCTUnwrap(store.groups().first { $0.groupId == gidS })
        XCTAssertEqual(back.muted, false)
        XCTAssertEqual(back.mutedAtMs, at + 5_000, "a synced value is not restamped as a local change")

        // An OLDER mute from a stale device loses.
        XCTAssertEqual(SyncMerge.group(back, SyncGroup(groupId: gidS, name: back.name, updatedAtMs: 0, muted: true, mutedAtMs: at)).muted, false)
    }
}
