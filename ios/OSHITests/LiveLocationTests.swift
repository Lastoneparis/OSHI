//
//  LiveLocationTests.swift
//  OSHITests
//
//  __LIVE_STOP_COUNTDOWN_2026_09_13__ / __LOCATION_PING_SPAM_2026_09_12__
//
//  Deux plaintes distinctes sur le partage de position en direct, et ces tests
//  fixent les deux règles qui y répondent:
//
//    1. « J'arrête le partage avant la fin et le compte à rebours continue. »
//       `remainingTime` ne demandait pas si le partage avait été ARRÊTÉ: elle
//       rendait platement l'écart jusqu'à l'`expiresAt` d'origine. Côté
//       expéditeur, arrêter met `isLiveSharing` à faux, donc `isMyLiveShare`
//       tombe, donc la vue bascule sur cette propriété — et le compte repart
//       vers l'heure de fin initiale comme si rien ne s'était passé.
//
//    2. « Toutes les trente secondes j'ai un ping que tu partages ta loc. »
//       Un partage émet une mise à jour toutes les 10 s. Ces pings doivent être
//       muets. Ce qui doit prévenir, ce sont les trois ÉVÉNEMENTS: le début, la
//       fin, et une prolongation — c'est-à-dire ce que l'utilisateur a demandé
//       (« only when create, stop or renew »).
//
//  Les deux règles vivent dans le modèle, donc elles se testent sans appareil,
//  sans réseau et sans interface.
//

import XCTest
import CoreLocation
@testable import OSHI

final class LiveLocationTests: XCTestCase {

    private let here = CLLocation(latitude: 48.8566, longitude: 2.3522)
    private let session = "session-under-test"

    // MARK: - 1. Le compte à rebours s'arrête quand le partage s'arrête

    /// LA régression. Un partage arrêté n'a plus de temps restant — quelle que
    /// soit l'heure de fin qu'il portait. Avec l'ancienne `remainingTime`, qui ne
    /// regardait que `expiresAt`, ce test échoue.
    func testAStoppedShareHasNoRemainingTime() {
        let stopped = LocationMessage.stopped(sessionId: session, lastLocation: here)
        XCTAssertTrue(stopped.isStopped, "précondition: le message d'arrêt se déclare arrêté")
        XCTAssertNil(
            stopped.remainingTime,
            "un partage arrêté ne doit plus décompter — c'est exactement le "
            + "« le compte continue » signalé par l'utilisateur"
        )
    }

    /// Le garde-fou dans l'autre sens: un partage ACTIF doit bien décompter,
    /// sinon « rendre nil partout » passerait le test précédent.
    func testAnActiveShareStillCountsDown() {
        let live = LocationMessage.live(from: here, duration: .oneHour, sessionId: session)
        XCTAssertFalse(live.isStopped)
        guard let remaining = live.remainingTime else {
            return XCTFail("un partage actif d'une heure doit avoir un temps restant")
        }
        XCTAssertGreaterThan(remaining, 3500, "≈ une heure")
        XCTAssertLessThanOrEqual(remaining, 3600)
    }

    /// Et un partage arrivé à échéance naturellement ne décompte pas non plus.
    func testAnExpiredShareHasNoRemainingTime() {
        let expired = LocationMessage(
            latitude: here.coordinate.latitude, longitude: here.coordinate.longitude,
            timestamp: Date(timeIntervalSinceNow: -7200), isLive: true,
            expiresAt: Date(timeIntervalSinceNow: -60), accuracy: 5, address: nil,
            sessionId: session, isUpdate: false, isStopped: false
        )
        XCTAssertNil(expired.remainingTime)
        XCTAssertTrue(expired.isExpired)
    }

    // MARK: - 2. Qui a le droit de faire sonner le téléphone

    /// Le DÉBUT d'un partage prévient: c'est un événement, il arrive une fois.
    func testStartingAShareNotifies() {
        let start = LocationMessage.live(from: here, duration: .oneHour, sessionId: session)
        XCTAssertFalse(start.isSilentForPush, "le premier partage doit prévenir")
    }

    /// Les pings intermédiaires — un toutes les 10 s — sont muets. C'est la
    /// moitié « anti-spam » de la règle.
    func testPeriodicUpdatesAreSilent() {
        let ping = LocationMessage.liveUpdate(from: here, duration: .oneHour, sessionId: session)
        XCTAssertTrue(ping.isUpdate, "précondition: un ping se déclare update")
        XCTAssertTrue(
            ping.isSilentForPush,
            "les mises à jour périodiques ne doivent jamais faire sonner le téléphone"
        )
    }

    /// La FIN prévient. C'est le piège de la première version du correctif: elle
    /// taisait sur `isUpdate` seul, et `stopped()` est construit avec
    /// `isUpdate: true` — donc la fin du partage devenait muette elle aussi, et
    /// le point de l'autre s'arrêtait simplement de bouger sans rien dire.
    func testStoppingAShareNotifies() {
        let stop = LocationMessage.stopped(sessionId: session, lastLocation: here)
        XCTAssertTrue(stop.isUpdate, "précondition: stopped() est bien un update…")
        XCTAssertTrue(stop.isStopped, "…mais un update ARRÊTÉ")
        XCTAssertFalse(
            stop.isSilentForPush,
            "la fin d'un partage est un événement: elle doit prévenir, même si "
            + "elle voyage comme un update"
        )
    }

    /// La table complète, pour qu'une inversion de signe ne puisse pas se glisser
    /// entre les cas ci-dessus: exactement UN des trois est muet.
    func testExactlyOneOfTheThreeEventsIsSilent() {
        let events: [(String, LocationMessage)] = [
            ("start",  .live(from: here, duration: .oneHour, sessionId: session)),
            ("ping",   .liveUpdate(from: here, duration: .oneHour, sessionId: session)),
            ("stop",   .stopped(sessionId: session, lastLocation: here)),
        ]
        let silent = events.filter { $0.1.isSilentForPush }.map(\.0)
        XCTAssertEqual(silent, ["ping"],
                       "seul le ping périodique est muet; créer et arrêter préviennent")
    }

    // MARK: - 3. La même règle, vue depuis le fil

    /// La décision de sonner est prise sur la CHAÎNE reçue, pas sur un objet
    /// déjà décodé — c'est `isSilentPushContent` qu'appellent les quatre chemins
    /// de notification. Elle doit donner le même verdict que la propriété.
    func testTheWireLevelRuleAgreesWithTheModel() {
        let start = LocationMessage.live(from: here, duration: .oneHour, sessionId: session)
        let ping  = LocationMessage.liveUpdate(from: here, duration: .oneHour, sessionId: session)
        let stop  = LocationMessage.stopped(sessionId: session, lastLocation: here)

        XCTAssertFalse(LocationMessage.isSilentPushContent(start.toMessageString()))
        XCTAssertTrue(LocationMessage.isSilentPushContent(ping.toMessageString()))
        XCTAssertFalse(LocationMessage.isSilentPushContent(stop.toMessageString()))
    }

    /// Un texte ordinaire n'est pas un ping de position. Sans ça, une règle trop
    /// large tairait des messages réels — le pire échec possible pour un
    /// messager.
    func testOrdinaryMessagesAreNeverSilenced() {
        for text in ["Bonjour", "📍 on se voit où ?", "", "{\"type\":\"media\"}"] {
            XCTAssertFalse(
                LocationMessage.isSilentPushContent(text),
                "un message ordinaire doit toujours prévenir: \(text.debugDescription)"
            )
        }
    }

    // MARK: - 4. Le passage sur le fil préserve les deux drapeaux

    /// Tout ce qui précède dépend de `isUpdate` et `isStopped` survivant à
    /// l'encodage: c'est sous cette forme que le destinataire les lit.
    func testTheTwoFlagsSurviveTheWire() {
        let stop = LocationMessage.stopped(sessionId: session, lastLocation: here)
        guard let back = LocationMessage.fromMessageString(stop.toMessageString()) else {
            return XCTFail("le message d'arrêt doit se relire")
        }
        XCTAssertTrue(back.isStopped, "isStopped doit survivre au fil")
        XCTAssertTrue(back.isUpdate)
        XCTAssertEqual(back.sessionId, session, "la session identifie la bulle à réécrire")
        XCTAssertNil(back.remainingTime, "et il reste arrêté après décodage")
    }
}
