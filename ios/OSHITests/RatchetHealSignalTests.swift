//
//  RatchetHealSignalTests.swift
//  OSHI — l'accusé de réparation doit rester INVISIBLE et NE PAS BOUCLER.
//
//  Les trois défauts qu'un accusé de réparation peut introduire sont tous
//  pires que le bug qu'il corrige, et chacun a son test ici:
//
//    1. Il s'affiche comme un message   → une bulle « __RATCHET_HEAL__v1 ».
//    2. Il produit une bannière          → une notification sans message, le
//                                          symptôme même qu'il vient réparer.
//    3. Il boucle                        → deux appareils cassés en même temps
//                                          se réparent mutuellement sans fin.
//

import XCTest
@testable import OSHI

final class RatchetHealSignalTests: XCTestCase {

    // MARK: 1. Reconnaissance — ni faux positif, ni faux négatif

    func testGeneratedMsgIdIsRecognised() {
        XCTAssertTrue(RatchetHealSignal.isHealMsgId(RatchetHealSignal.makeMsgId()))
    }

    /// Deux accusés ne doivent pas être confondables: le préfixe dit le genre,
    /// il ne remplace pas l'identité.
    func testGeneratedMsgIdsAreUnique() {
        let ids = Set((0..<64).map { _ in RatchetHealSignal.makeMsgId() })
        XCTAssertEqual(ids.count, 64)
    }

    /// LE faux positif qui coûterait cher: un message ordinaire dont la
    /// bannière serait supprimée est un message que l'utilisateur ne voit
    /// jamais arriver.
    func testOrdinaryMsgIdIsNotAHealSignal() {
        XCTAssertFalse(RatchetHealSignal.isHealMsgId(UUID().uuidString))
        XCTAssertFalse(RatchetHealSignal.isHealMsgId(""))
        // Le préfixe est ancré au DÉBUT: le contenir ne suffit pas.
        XCTAssertFalse(RatchetHealSignal.isHealMsgId("x-heal-\(UUID().uuidString)"))
    }

    func testPayloadMarkerIsRecognised() {
        XCTAssertTrue(RatchetHealSignal.isHealPayload(RatchetHealSignal.payloadMarker))
    }

    /// Un ré-encodage qui aurait entouré le marqueur d'un blanc ne doit pas le
    /// faire s'afficher en clair dans une bulle.
    func testPayloadMarkerToleratesSurroundingWhitespace() {
        XCTAssertTrue(RatchetHealSignal.isHealPayload("\n \(RatchetHealSignal.payloadMarker)  \n"))
    }

    /// Le faux positif symétrique: un vrai message effacé du fil.
    func testOrdinaryTextIsNotAHealPayload() {
        XCTAssertFalse(RatchetHealSignal.isHealPayload("salut, tu es là ?"))
        XCTAssertFalse(RatchetHealSignal.isHealPayload(""))
        // Le CITER n'est pas l'ÊTRE — sinon on peut effacer le message de
        // quelqu'un en lui écrivant le marqueur.
        XCTAssertFalse(RatchetHealSignal.isHealPayload("regarde: \(RatchetHealSignal.payloadMarker)"))
    }

    // MARK: 2. Pas de bannière — et les deux cibles doivent rester d'accord

    /// L'extension de notification est une CIBLE SÉPARÉE. Elle ne recopie PAS
    /// le préfixe: `RatchetHealSignal.swift` lui est compilé dedans, ce qui
    /// rend la dérive impossible par construction.
    ///
    /// Ce test garde la seule chose qui reste falsifiable: que l'extension
    /// consulte bien le contrat partagé, et qu'elle SUPPRIME. Une extension qui
    /// importerait le fichier sans jamais l'interroger compilerait très bien et
    /// afficherait une bannière à chaque réparation.
    func testNotificationExtensionSuppressesHealSignals() throws {
        let nse = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("OSHINotificationService")
            .appendingPathComponent("NotificationService.swift")
        let source = try String(contentsOf: nse, encoding: .utf8)

        XCTAssertTrue(source.contains("RatchetHealSignal.isHealMsgId"),
                      "L'extension doit interroger le contrat partagé, sinon l'accusé affiche une bannière sans message.")
        XCTAssertFalse(source.contains("\"heal-\""),
                       "Le préfixe est recopié dans l'extension: il dérivera. Utiliser RatchetHealSignal.")
    }

    // MARK: 3. Pas de boucle

    func testFirstHealIsAlwaysAllowed() {
        XCTAssertTrue(RatchetHealSignal.Policy.maySend(lastSentAt: nil, now: Date()))
    }

    /// Le cas qui produit la boucle: deux appareils cassés en même temps,
    /// chacun adoptant la session neuve de l'autre et cassant celle qu'il
    /// venait de créer.
    func testSecondHealWithinCooldownIsRefused() {
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        let justAfter = t0.addingTimeInterval(RatchetHealSignal.Policy.cooldown - 1)
        XCTAssertFalse(RatchetHealSignal.Policy.maySend(lastSentAt: t0, now: justAfter))
    }

    /// Et le délai doit EXPIRER: une session qui casse à nouveau une heure
    /// plus tard est une vraie panne, pas la boucle.
    func testHealIsAllowedAgainAfterCooldown() {
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertTrue(RatchetHealSignal.Policy.maySend(
            lastSentAt: t0, now: t0.addingTimeInterval(RatchetHealSignal.Policy.cooldown)))
        XCTAssertTrue(RatchetHealSignal.Policy.maySend(
            lastSentAt: t0, now: t0.addingTimeInterval(3600)))
    }

    /// Une horloge qui recule (changement de fuseau, correction NTP) ne doit
    /// pas OUVRIR la vanne: `maySend` compare une durée, et une durée négative
    /// reste inférieure au délai.
    func testClockGoingBackwardsDoesNotUnlockTheCooldown() {
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertFalse(RatchetHealSignal.Policy.maySend(
            lastSentAt: t0, now: t0.addingTimeInterval(-300)))
    }

    // MARK: 4. La grammaire de LIGNES — et sa rétro-compatibilité
    //
    // __RATCHET_RESEND_2026_08_28__ L'accusé porte désormais la liste des
    // enveloppes qu'on n'a pas pu ouvrir. Le reconnaisseur v1 était une
    // ÉGALITÉ: aucun JSON ne pouvait la satisfaire. Toute la question est donc
    // ce qu'un pair resté en v1 voit arriver.

    /// La forme v1 NUE doit continuer d'être reconnue par un binaire v2 —
    /// sinon la mise à jour d'un seul côté fait apparaître une bulle
    /// « __RATCHET_HEAL__v1 » chez l'autre.
    func testBareV1FormIsStillRecognised() {
        XCTAssertTrue(RatchetHealSignal.isHealPayload(RatchetHealSignal.payloadMarker))
        XCTAssertEqual(RatchetHealSignal.parse(RatchetHealSignal.payloadMarker)?.resend, [])
    }

    /// Et SYMÉTRIQUEMENT: sans rien à redemander — le cas de très loin le plus
    /// fréquent — la charge produite est OCTET POUR OCTET celle de la v1, donc
    /// un pair v1 continue de la taire.
    func testEmptyListEncodesToTheExactV1Payload() {
        XCTAssertEqual(RatchetHealSignal.encode(resend: []), RatchetHealSignal.payloadMarker)
    }

    func testResendListSurvivesTheRoundTrip() {
        let payload = RatchetHealSignal.encode(resend: ["a", "b"])
        XCTAssertTrue(RatchetHealSignal.isHealPayload(payload))
        XCTAssertEqual(RatchetHealSignal.parse(payload)?.resend, ["a", "b"])
    }

    /// LE saut de ligne est obligatoire. Un `hasPrefix` nu rouvrirait le trou
    /// que `testOrdinaryTextIsNotAHealPayload` garde: écrire un message
    /// COMMENÇANT par le marqueur ferait disparaître ce message chez l'autre.
    func testMarkerFollowedByProseIsNotAHealPayload() {
        XCTAssertFalse(RatchetHealSignal.isHealPayload("\(RatchetHealSignal.payloadMarker) et la suite"))
        XCTAssertNil(RatchetHealSignal.parse("\(RatchetHealSignal.payloadMarker) et la suite"))
        // Et la propriété d'origine tient toujours.
        XCTAssertFalse(RatchetHealSignal.isHealPayload("regarde: \(RatchetHealSignal.payloadMarker)"))
    }

    /// __HEAL_PARSE_STRICT_2026_08_28__ CE TEST A ÉTÉ RETOURNÉ, et il faut dire
    /// pourquoi: il gardait auparavant le contrat inverse — « un corps illisible
    /// compte quand même comme un accusé » — et ce contrat DÉTRUISAIT DES
    /// MESSAGES.
    ///
    /// Rendre une liste vide au lieu de `nil` faisait prendre le `continue` de
    /// la boucle de réception: le texte disparaissait sans bulle et sans trace.
    /// Il suffisait donc d'écrire un message dont la première ligne est le
    /// marqueur pour l'effacer chez le destinataire — un effacement à distance,
    /// déclenchable par n'importe qui, dans un messager chiffré.
    ///
    /// Le compromis est assumé et documenté dans `parse`: une v3 dont on ne
    /// saurait pas lire le corps s'afficherait en bulle plutôt que d'être
    /// avalée. Afficher quelque chose d'inattendu est un défaut d'apparence;
    /// détruire un message est une perte de données.
    func testUnreadableBodyIsNotAHealSignal() {
        let payload = "\(RatchetHealSignal.payloadMarker)\n{ceci n'est pas du JSON"
        XCTAssertFalse(RatchetHealSignal.isHealPayload(payload))
        XCTAssertNil(RatchetHealSignal.parse(payload))
    }

    /// La porte reste ouverte à une v3: la seule exigence est un corps JSON
    /// portant un champ `v`. Un champ inconnu à côté ne doit pas la refermer —
    /// c'est exactement ce que le bourrage ajoute déjà aujourd'hui.
    func testFutureVersionWithUnknownFieldsIsStillAHealSignal() {
        let payload = "\(RatchetHealSignal.payloadMarker)\n{\"v\":3,\"resend\":[],\"nouveau\":42}"
        XCTAssertTrue(RatchetHealSignal.isHealPayload(payload))
        XCTAssertEqual(RatchetHealSignal.parse(payload)?.resend, [])
    }

    /// Le bourrage anti-fuite doit être INVISIBLE au lecteur: une charge bourrée
    /// se lit exactement comme la même charge nue.
    func testPaddingIsTransparentToTheReader() {
        let ids = (0..<8).map { _ in UUID().uuidString }
        let encoded = RatchetHealSignal.encode(resend: ids)
        XCTAssertEqual(RatchetHealSignal.parse(encoded)?.resend, ids)
    }

    /// LA propriété qui ferme la fuite: la taille ne doit RIEN dire du nombre
    /// d'identifiants. Sans elle, le relais mesurait le chiffré et en déduisait
    /// combien de messages le correspondant n'avait pas pu lire.
    func testPaddedPayloadLengthDoesNotRevealTheCount() {
        let sizes = [1, 4, 16, RatchetHealSignal.Policy.maxResendPerRequest].map { n -> Int in
            RatchetHealSignal.encode(resend: (0..<n).map { _ in UUID().uuidString }).utf8.count
        }
        XCTAssertEqual(Set(sizes).count, 1, "la longueur varie avec le nombre d'ids: \(sizes)")
    }

    /// L'annonce de capacité voyage sur l'IDENTIFIANT, et doit rester sous le
    /// préfixe que l'extension de notification connaît — sinon la réparation
    /// recommence à afficher une bannière sans message.
    func testCapabilityAnnouncementStaysUnderTheSilencedPrefix() {
        let id = RatchetHealSignal.makeCapableMsgId()
        XCTAssertTrue(RatchetHealSignal.announcesListSupport(id))
        XCTAssertTrue(RatchetHealSignal.isHealMsgId(id), "l'extension ne tairait plus la bannière")
        // Un accusé de l'ancienne forme n'annonce rien: c'est ce qui protège 1.0.39.
        XCTAssertFalse(RatchetHealSignal.announcesListSupport(RatchetHealSignal.makeMsgId()))
    }

    /// La liste voyage DANS le chiffré: elle doit tenir sous le plafond de
    /// 256 Ko de chiffré du relais. 32 UUID ≈ 1,2 Ko — la marge est de deux
    /// ordres de grandeur, et ce test est là pour qu'elle le reste.
    func testFullResendListFitsUnderTheRelayCiphertextCap() {
        let ids = (0..<RatchetHealSignal.Policy.maxResendPerRequest).map { _ in UUID().uuidString }
        XCTAssertLessThan(RatchetHealSignal.encode(resend: ids).utf8.count, 256 * 1024)
    }

    /// Le plafond est appliqué à l'ÉMISSION comme à la RÉCEPTION: un pair
    /// bavard ne doit pas pouvoir nous faire produire 10 000 renvois.
    func testResendListIsCappedOnBothSides() {
        let tooMany = (0..<(RatchetHealSignal.Policy.maxResendPerRequest * 4)).map { "id-\($0)" }
        let payload = RatchetHealSignal.encode(resend: tooMany)
        XCTAssertEqual(RatchetHealSignal.parse(payload)?.resend.count,
                       RatchetHealSignal.Policy.maxResendPerRequest)
    }

    // MARK: 5. Les freins du renvoi — la seule chose qui puisse boucler

    /// LE frein qui termine le cycle renvoi → échec → abandon → accusé →
    /// renvoi des MÊMES ids. Le délai de 60 s ne le termine pas, il l'espace.
    func testResendStopsAtTheAttemptCeiling() {
        XCTAssertFalse(RatchetHealSignal.Policy.mayResend(
            attemptsSoFar: RatchetHealSignal.Policy.maxResendAttemptsPerMsgId,
            messageAge: 0, peerBudgetLeft: 100))
        XCTAssertTrue(RatchetHealSignal.Policy.mayResend(
            attemptsSoFar: RatchetHealSignal.Policy.maxResendAttemptsPerMsgId - 1,
            messageAge: 0, peerBudgetLeft: 100))
    }

    func testResendRefusesAMessagePastItsAge() {
        XCTAssertFalse(RatchetHealSignal.Policy.mayResend(
            attemptsSoFar: 0,
            messageAge: RatchetHealSignal.Policy.maxResendAge + 1,
            peerBudgetLeft: 100))
    }

    func testResendRefusesAnExhaustedPeerBudget() {
        XCTAssertFalse(RatchetHealSignal.Policy.mayResend(
            attemptsSoFar: 0, messageAge: 0, peerBudgetLeft: 0))
    }
}

// MARK: - __ONE_BANNER_PER_MESSAGE_2026_09_23__
// (Here because this file is already in the test target — no pbxproj edit.)

final class MessageBannerPolicyTests: XCTestCase {
    typealias P = MessageBannerPolicy
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let gid = "3f2b9c3e-1111-4222-8333-444455556666"

    private func remote(_ info: [AnyHashable: Any], ago: TimeInterval = 1) -> P.Delivered {
        P.Delivered(identifier: UUID().uuidString, userInfo: info, date: now.addingTimeInterval(-ago))
    }
    private func local(_ id: String, _ info: [AnyHashable: Any], ago: TimeInterval = 1) -> P.Delivered {
        var i = info; i[P.localMarkerKey] = true
        return P.Delivered(identifier: id, userInfo: i, date: now.addingTimeInterval(-ago))
    }

    func testGroupKeyIsCaseInsensitiveAndBeatsSender() {
        XCTAssertEqual(P.conversationKey(["groupId": gid, "senderPublicKey": "abc"]),
                       P.conversationKey(["groupId": gid.uppercased()]))
        XCTAssertEqual(P.conversationKey(["senderPublicKey": "ab-c_d=="]), P.conversationKey(["senderKey": "ab+c/d"]))
        XCTAssertNil(P.conversationKey([:]))
    }

    /// The reported case: APNs banner for a group (bot: server messageId ≠ local
    /// id) is already there → the app must NOT post a second, local one.
    func testRemoteGroupBannerWithDifferentMessageIdCoversLocal() {
        let delivered = [remote(["groupId": gid, "messageId": "srv-123", "type": "group_message"])]
        XCTAssertTrue(P.remoteBannerCovers(messageId: UUID().uuidString,
                                           conversationKey: "g:" + gid.uppercased(),
                                           delivered: delivered, now: now))
    }

    func testSameMessageIdCoversRegardlessOfAge() {
        let mid = UUID().uuidString
        let delivered = [remote(["messageId": mid.lowercased(), "senderPublicKey": "k"], ago: 3600)]
        XCTAssertTrue(P.remoteBannerCovers(messageId: mid, conversationKey: "c:other",
                                           delivered: delivered, now: now))
    }

    func testOldRemoteForSameConversationDoesNotCoverANewMessage() {
        let delivered = [remote(["groupId": gid, "messageId": "x"], ago: P.remoteCoversLocalWindow + 5)]
        XCTAssertFalse(P.remoteBannerCovers(messageId: "new", conversationKey: "g:" + gid.uppercased(),
                                            delivered: delivered, now: now))
    }

    func testLocalBannersNeverCountAsRemote() {
        let delivered = [local("m1", ["groupId": gid])]
        XCTAssertFalse(P.remoteBannerCovers(messageId: "m2", conversationKey: "g:" + gid.uppercased(),
                                            delivered: delivered, now: now))
    }

    func testOtherConversationDoesNotCover() {
        let delivered = [remote(["groupId": UUID().uuidString])]
        XCTAssertFalse(P.remoteBannerCovers(messageId: "m", conversationKey: "g:" + gid.uppercased(),
                                            delivered: delivered, now: now))
    }

    /// Reverse race: app posted first → NSE suppresses the remote one.
    func testRecentLocalCoversRemote() {
        let delivered = [local("m1", ["groupId": gid, "messageId": "m1"], ago: 3)]
        XCTAssertTrue(P.localBannerCovers(messageId: "srv-9", conversationKey: P.conversationKey(["groupId": gid]),
                                          delivered: delivered, now: now))
        XCTAssertFalse(P.localBannerCovers(messageId: "srv-9", conversationKey: P.conversationKey(["groupId": gid]),
                                           delivered: [local("m1", ["groupId": gid], ago: P.localCoversRemoteWindow + 1)],
                                           now: now))
        XCTAssertFalse(P.localBannerCovers(messageId: "srv-9", conversationKey: P.conversationKey(["groupId": gid]),
                                           delivered: [remote(["groupId": gid])], now: now))
    }

    func testPreviewDefaultIsNoText() {
        let d = UserDefaults(suiteName: "MessageBannerPolicyTests")!
        d.removePersistentDomain(forName: "MessageBannerPolicyTests")
        XCTAssertFalse(P.showsMessageText(d))
        d.set(true, forKey: P.previewDefaultsKey)
        XCTAssertTrue(P.showsMessageText(d))
        d.removePersistentDomain(forName: "MessageBannerPolicyTests")
    }

    /// __NOTIFICATION_NAMES_HIDDEN_2026_11_20__ "Show Preview" off hides names,
    /// default stays on, and the value reaches the App Group the NSE reads.
    func testShowPreviewToggleControlsNamesAndIsMirroredForTheExtension() {
        let app = UserDefaults(suiteName: "MessageBannerPolicyTests.app")!
        let shared = UserDefaults(suiteName: "MessageBannerPolicyTests.group")!
        app.removePersistentDomain(forName: "MessageBannerPolicyTests.app")
        shared.removePersistentDomain(forName: "MessageBannerPolicyTests.group")
        XCTAssertTrue(P.showsNames(shared), "default unchanged: names shown")
        app.set(false, forKey: P.showNamesKey)
        P.mirrorShowsNames(from: app, to: shared)
        XCTAssertFalse(P.showsNames(shared))
        XCTAssertFalse(P.showsNames(app))
        app.set(true, forKey: P.showNamesKey)
        P.mirrorShowsNames(from: app, to: shared)
        XCTAssertTrue(P.showsNames(shared))
        XCTAssertEqual(P.showNamesKey, "notificationPreview", "must stay the key the Settings toggle writes")
        app.removePersistentDomain(forName: "MessageBannerPolicyTests.app")
        shared.removePersistentDomain(forName: "MessageBannerPolicyTests.group")
    }

    func testGenericBodyKeyIsLocalized() {
        let s = NSLocalizedString(P.genericBodyKey, bundle: Bundle(for: NotificationManager.self), comment: "")
        XCTAssertNotEqual(s, P.genericBodyKey)
    }
}

// MARK: - __BLOCK_MUTE_AUDIT_2026_09_23__
//
// Lives in this file because it is IN the OSHITests target: `BlockedNoticeTests.swift`
// exists on disk but was never added to the target, so its assertions never ran.
// Covers: the block notice (switch, calls, stamp order, rules), group mute/block in
// the Notification Service Extension, and unmute restoring the banner.
final class BlockMuteNoticeAuditTests: XCTestCase {

    private let notice = OSHIControlPayload.blockedNoticePrefix

    func testNoticeIsCataloguedRenderedAndNeverAnswered() {
        XCTAssertEqual(notice, "🚫BLOCKED🚫")
        XCTAssertTrue(OSHIControlPayload.isControl(notice))
        XCTAssertFalse(OSHIControlPayload.isSilent(notice))
        XCTAssertTrue(OSHIControlPayload.suppressesPush(notice), "a notice must never answer a notice")
        for payload in ["📬DELIVERY_RECEIPT📬abc", "📖READ_RECEIPT📖{}", "⌨️TYPING⌨️", "✍️TYPING✍️",
                        "📸PROFILE_UPDATE📸x", "🔧ACTION🔧{}", "{\"type\":\"member_sync\",\"groupId\":\"g1\"}"] {
            XCTAssertTrue(OSHIControlPayload.suppressesPush(payload), "\(payload) must not draw a notice")
        }
        XCTAssertFalse(OSHIControlPayload.suppressesPush("tu es là ?"))
        XCTAssertFalse(OSHIControlPayload.suppressesPush("📞CALL_SIGNAL📞{}"), "a call attempt IS someone trying to reach you")
    }

    func testReceivedNoticeIsNeverAcked() {
        XCTAssertFalse(MessageManager.shouldAckInboundMeshMessage(content: notice, senderPublicKey: "PEER", myPublicKey: "ME"))
    }

    func testTheNoticeSwitchIsOnPerOwnerRequest() {
        XCTAssertTrue(MessageManager.blockedNoticeEnabled)
    }

    func testBlockedCallsAnswerWithTheNoticeNotARing() throws {
        let voip = try Self.repoFile("OSHI/VoIPPushManager.swift")
        let r = try XCTUnwrap(voip.range(of: "why: \"blocked_caller\""))
        // The VoIP push handler must NOT send the notice itself (it may not touch
        // main-thread state — 0xBAADCA11); the call signal path below does.
        XCTAssertFalse(String(voip[r.upperBound...].prefix(900)).contains("noticeBlockedSender("))
        let vcm = try Self.repoFile("OSHI/VoiceCallManager.swift")
        let r2 = try XCTUnwrap(vcm.range(of: "Ignoring call from blocked contact"))
        let after = String(vcm[r2.upperBound...].prefix(900))
        XCTAssertTrue(after.contains("noticeBlockedSender(peerPublicKey"))
        XCTAssertFalse(after.contains("reportNewIncomingCall"))
    }

    func testTheNoticeIsNotStampedBeforeItCanBeSent() throws {
        let mm = try Self.repoFile("OSHI/MessageManager.swift")
        let start = try XCTUnwrap(mm.range(of: "func noticeBlockedSender("))
        let body = String(mm[start.upperBound...].prefix(3000))
        let guardIdx = try XCTUnwrap(body.range(of: "let meshManager = MessageManager.sharedMeshManager"))
        let stampIdx = try XCTUnwrap(body.range(of: "sent[key] = now"))
        XCTAssertLessThan(guardIdx.lowerBound, stampIdx.lowerBound)
        XCTAssertTrue(body.contains("guard MessageManager.blockedNoticeEnabled"))
    }

    func testMutedGroupIsSilencedCaseInsensitively() {
        let gid = "0A1B2C3D-0000-4000-8000-00000000ABCD"
        let snap: [[String: Any]] = [["groupId": gid, "name": "Famille", "muted": "1"],
                                     ["groupId": "11111111-1111-4111-8111-111111111111", "name": "Boulot"]]
        XCTAssertTrue(MessageBannerPolicy.groupIsSilenced(groupId: gid.lowercased(), groupsSnapshot: snap, blockedGroupIds: []))
        XCTAssertFalse(MessageBannerPolicy.groupIsSilenced(groupId: "11111111-1111-4111-8111-111111111111",
                                                           groupsSnapshot: snap, blockedGroupIds: []))
        XCTAssertFalse(MessageBannerPolicy.groupIsSilenced(groupId: "", groupsSnapshot: snap, blockedGroupIds: []))
        XCTAssertFalse(MessageBannerPolicy.groupIsSilenced(groupId: gid, groupsSnapshot: [], blockedGroupIds: []),
                       "missing snapshot (fresh install) must ring, not silence everything")
    }

    func testUnmuteRestoresTheBanner() {
        let gid = "0A1B2C3D-0000-4000-8000-00000000ABCD"
        XCTAssertTrue(MessageBannerPolicy.groupIsSilenced(groupId: gid, groupsSnapshot: [["groupId": gid, "muted": "1"]], blockedGroupIds: []))
        XCTAssertFalse(MessageBannerPolicy.groupIsSilenced(groupId: gid, groupsSnapshot: [["groupId": gid, "name": "F"]], blockedGroupIds: []))
    }

    func testBlockedGroupIsSilencedEvenIfNotMuted() {
        let gid = "0A1B2C3D-0000-4000-8000-00000000ABCD"
        XCTAssertTrue(MessageBannerPolicy.groupIsSilenced(groupId: gid, groupsSnapshot: [], blockedGroupIds: [gid.lowercased()]))
    }

    func testTheExtensionChecksGroupSilenceBeforeDecorating() throws {
        let nse = try Self.repoFile("OSHINotificationService/NotificationService.swift")
        let check = try XCTUnwrap(nse.range(of: "Self.isSilencedGroup(pushGroupId)"))
        let decorate = try XCTUnwrap(nse.range(of: "Self.lookupGroupName(groupId: groupId)"))
        XCTAssertLessThan(check.lowerBound, decorate.lowerBound)
        let blocked = try XCTUnwrap(nse.range(of: "Self.isBlockedSender(senderPublicKey)"))
        XCTAssertLessThan(blocked.lowerBound, decorate.lowerBound)
        let bridge = try Self.repoFile("OSHI/AppGroupBridge.swift")
        XCTAssertTrue(bridge.contains("d[\"muted\"] = \"1\""))
        XCTAssertTrue(bridge.contains("blocked_groups_snapshot.json"))
    }

    func testLocalGroupBannerRespectsBlocks() throws {
        let gm = try Self.repoFile("OSHI/GroupMessaging.swift")
        let r = try XCTUnwrap(gm.range(of: "!group.isMuted,"))
        let cond = String(gm[r.upperBound...].prefix(300))
        XCTAssertTrue(cond.contains("isGroupBlocked(group.id)"))
        XCTAssertTrue(cond.contains("isBlocked(message.senderPublicKey)"))
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
}

// MARK: - __BLOCKED_NOTIF_2026_09_23__ blocked senders never keep a notification

final class BlockedNotificationPolicyTests: XCTestCase {
    typealias P = MessageBannerPolicy
    let key = "eAbMQUdW6UtufTtaK+q/9Zx1abcDEF0123456789ABCdef="

    func testBlockedSenderMatchesAcrossSpellings() {
        let urlSafe = key.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        for wire in [key, urlSafe, String(key.dropLast()), " \(key)\n"] {
            XCTAssertTrue(P.senderIsBlocked(wire, blockedKeys: [key]), wire)
        }
        XCTAssertFalse(P.senderIsBlocked("someoneElse", blockedKeys: [key]))
        XCTAssertFalse(P.senderIsBlocked("", blockedKeys: [key]))
        XCTAssertFalse(P.senderIsBlocked(key, blockedKeys: []))
    }

    func testSenderIsReadFromCallKeysToo() {
        XCTAssertEqual(P.sender(of: ["callerPublicKey": key]), key)
        XCTAssertEqual(P.sender(of: ["senderKey": key]), key)
        XCTAssertNil(P.sender(of: ["messageId": "x"]))
    }

    func testWithdrawsBlockedAndDowngradedButNothingElse() {
        let now = Date()
        let delivered = [
            P.Delivered(identifier: "blockedMsg", userInfo: ["senderPublicKey": key], date: now),
            P.Delivered(identifier: "blockedCall", userInfo: ["callerPublicKey": key], date: now),
            P.Delivered(identifier: "downgraded", userInfo: [P.suppressedMarkerKey: "1"], date: now),
            P.Delivered(identifier: "friend", userInfo: ["senderPublicKey": "friendKey"], date: now),
            P.Delivered(identifier: "noSender", userInfo: ["type": "mail"], date: now),
        ]
        XCTAssertEqual(Set(P.deliveredToWithdraw(delivered, blockedKeys: [key])),
                       ["blockedMsg", "blockedCall", "downgraded"])
        XCTAssertEqual(P.deliveredToWithdraw(delivered, blockedKeys: []), ["downgraded"])
    }

    /// The extension must not rely on an empty content to hide a push: without the
    /// filtering entitlement iOS shows the ORIGINAL alert instead.
    func testExtensionNeverHandsBackBareEmptyContentOutsideTheHelper() throws {
        var dir = URL(fileURLWithPath: #filePath)
        var src: String?
        for _ in 0..<6 {
            let f = dir.appendingPathComponent("OSHINotificationService/NotificationService.swift")
            if let s = try? String(contentsOf: f, encoding: .utf8) { src = s; break }
            dir = dir.deletingLastPathComponent()
        }
        guard let s = src else { throw XCTSkip("NotificationService.swift not found") }
        XCTAssertEqual(s.components(separatedBy: "UNNotificationContent())").count - 1, 1,
                       "only suppressDisplay may hand back an empty content (entitled path)")
        XCTAssertTrue(s.contains("interruptionLevel = .passive"))
    }
}

// MARK: - __PHANTOM_BANNER_2026_10_11__ une bannière sans message derrière
//
// Avis App Store du 10/10 (5★, FR, lino3529): « manque de fluidité à travers
// l'interface et les notifications excessives alors que je n'ai rien reçu ».
//
// Les deux symptômes sortaient du MÊME endroit. `failedDecryptionCount` et
// `maxFailedDecryptions` étaient déclarés, remis à zéro en cas de succès, et
// jamais incrémentés ni lus — donc le plafond n'existait pas et
// `decryptionRetryTimer` rejouait le ratchet sur le thread principal toutes les
// cinq secondes, indéfiniment, dès qu'un message restait indéchiffrable.
// Pendant ce temps la bannière APNs, dessinée par l'extension qui ne déchiffre
// pas, annonçait un message qui n'arrivait jamais.
//
// Ces tests tiennent les deux bouts: la règle de retrait (pure) et le câblage
// (qui est précisément ce qui manquait).

final class PhantomBannerPolicyTests: XCTestCase {
    typealias P = MessageBannerPolicy

    private func delivered(_ id: String, _ info: [AnyHashable: Any]) -> P.Delivered {
        P.Delivered(identifier: id, userInfo: info, date: Date())
    }

    // MARK: la règle de retrait

    func testWithdrawalFindsALocalBannerByItsIdentifier() {
        // Une bannière LOCALE porte le messageId comme identifiant.
        let d = [delivered("MSG-1", [P.localMarkerKey: true, "messageId": "MSG-1"])]
        XCTAssertEqual(P.deliveredMatching(messageIds: ["MSG-1"], delivered: d), ["MSG-1"])
    }

    func testWithdrawalFindsARemoteBannerByItsUserInfoMessageId() {
        // LE cas que `clearNotification(for:)` ne pouvait pas attraper: une
        // bannière APNs a un identifiant attribué par Apple, et ne porte le
        // messageId que dans son userInfo. C'est pourtant ELLE qui sonne pour un
        // message que l'app ne pourra pas afficher.
        let d = [delivered("ABCD-APNS-OPAQUE-ID", ["messageId": "MSG-1", "senderPublicKey": "k"])]
        XCTAssertEqual(P.deliveredMatching(messageIds: ["MSG-1"], delivered: d),
                       ["ABCD-APNS-OPAQUE-ID"])
    }

    func testWithdrawalMatchesMessageIdCaseInsensitively() {
        // UUID Swift en majuscules, Android et le bot en minuscules.
        let d = [delivered("x", ["messageId": "abc-def"])]
        XCTAssertEqual(P.deliveredMatching(messageIds: ["ABC-DEF"], delivered: d), ["x"])
    }

    func testWithdrawalLeavesEveryOtherBannerAlone() {
        let d = [
            delivered("keep-1", ["messageId": "OTHER", "senderPublicKey": "k"]),
            delivered("drop-1", ["messageId": "MSG-1"]),
            delivered("keep-2", ["senderPublicKey": "k"]),          // aucun messageId
        ]
        XCTAssertEqual(P.deliveredMatching(messageIds: ["MSG-1"], delivered: d), ["drop-1"])
    }

    func testWithdrawalOfNothingIsANoOp() {
        let d = [delivered("x", ["messageId": "MSG-1"])]
        XCTAssertTrue(P.deliveredMatching(messageIds: [], delivered: d).isEmpty)
        XCTAssertTrue(P.deliveredMatching(messageIds: ["MSG-1"], delivered: []).isEmpty)
    }

    // MARK: le câblage — c'est lui qui manquait

    func testTheFailedDecryptionCounterIsActuallyIncremented() throws {
        // La régression d'origine: un compteur déclaré, remis à zéro, jamais
        // incrémenté. Un plafond qu'on ne lit jamais ne plafonne rien.
        let mm = try Self.repoFile("OSHI/MessageManager.swift")
        XCTAssertTrue(mm.contains("let failures = (failedDecryptionCount[senderKey] ?? 0) + 1"),
                      "failedDecryptionCount doit être INCRÉMENTÉ, pas seulement remis à zéro")
        XCTAssertTrue(mm.contains("failures == maxFailedDecryptions"),
                      "maxFailedDecryptions doit être LU")
        XCTAssertTrue(mm.contains("nextDecryptionRetry[senderKey] = Date().addingTimeInterval(delay)"))
    }

    func testThePeriodicSweepBacksOffButEventDrivenRetriesDoNot() throws {
        // L'attente ne doit calmer que le balayage périodique. Un réveil APNs,
        // un retour au premier plan ou l'ouverture d'un fil doivent retenter
        // tout de suite, sinon on échange un bug de fluidité contre un bug de
        // latence.
        let mm = try Self.repoFile("OSHI/MessageManager.swift")
        XCTAssertTrue(mm.contains("private func retryAllPendingDecryption(force: Bool = false)"))
        XCTAssertTrue(mm.contains("retryAllPendingDecryption(force: true)"),
                      "le chemin événementiel doit forcer")
        // Le timer périodique, lui, ne force pas.
        let timer = try XCTUnwrap(mm.range(of: "decryptionRetryTimer = Timer.scheduledTimer"))
        let body = String(mm[timer.upperBound...].prefix(260))
        XCTAssertTrue(body.contains("retryAllPendingDecryption()"), "trouvé: \(body)")
        XCTAssertFalse(body.contains("force: true"))
    }

    func testAParkedMessageWithdrawsItsBannerAndIsPublishedToTheExtension() throws {
        let mm = try Self.repoFile("OSHI/MessageManager.swift")
        // Au moment exact où le message est garé.
        let park = try XCTUnwrap(mm.range(of: "private func queueForDecryption"))
        let parkBody = String(mm[park.upperBound...].prefix(2600))
        XCTAssertTrue(parkBody.contains("withdrawPhantomBanners(for: [message])"))
        // Et la liste part dans l'App Group, sinon l'extension re-sonnera.
        XCTAssertTrue(mm.contains("AppGroupBridge.writeUndecryptableSnapshot(ids)"))
        let bridge = try Self.repoFile("OSHI/AppGroupBridge.swift")
        XCTAssertTrue(bridge.contains("undecryptable_snapshot.json"))
    }

    func testTheExtensionSuppressesARepeatPushForAParkedMessage() throws {
        // La PREMIÈRE bannière est inévitable (l'extension ne déchiffre pas).
        // Les suivantes, non: le serveur re-pousse tant que ce n'est pas acquitté.
        let nse = try Self.repoFile("OSHINotificationService/NotificationService.swift")
        let check = try XCTUnwrap(nse.range(of: "Self.isParkedUndecryptable(parkedMsgId)"))
        let decorate = try XCTUnwrap(nse.range(of: "Self.lookupSenderName(senderPublicKey:"))
        XCTAssertLessThan(check.lowerBound, decorate.lowerBound,
                          "la suppression doit précéder la décoration de la bannière")
        XCTAssertTrue(nse.contains("undecryptable_snapshot.json"))
    }

    func testPendingDecryptionIsNotPersistedOnTheMainThread() throws {
        // `FileStorage.save` fait, sous verrou, JSONEncoder + scellement
        // AtRestCipher + écriture atomique. Les appelants sont sur le thread
        // principal (`receiveMessage` y est confiné).
        let mm = try Self.repoFile("OSHI/MessageManager.swift")
        let save = try XCTUnwrap(mm.range(of: "private func savePendingDecryption()"))
        let body = String(mm[save.upperBound...].prefix(1800))
        XCTAssertTrue(body.contains("pendingDecryptionSaveQueue.async"),
                      "l'écriture doit quitter le thread principal")
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
}
