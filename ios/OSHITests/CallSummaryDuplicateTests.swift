//
//  CallSummaryDuplicateTests.swift
//  OSHITests
//
//  Régression pour le défaut signalé le 2026-08-21: à la fin d'un appel, le fil
//  affichait DEUX messages — « appel entrant 2:10 » et « appel sortant 2:10 ».
//
//  Mécanisme: `VoiceCallManager` poste `CallEnded` sur les DEUX appareils, chacun
//  avec son propre `wasOutgoing`. `ChatView.handleCallEnded` s'exécutait donc des
//  deux côtés, fabriquait le résumé de SON point de vue, et l'envoyait au pair
//  avec `sendMessage` — qui l'écrit aussi localement. Chacun gardait le sien plus
//  celui d'en face.
//
//  __CALL_SUMMARY_LOCAL_2026_08_22__ Depuis, plus personne n'émet: le résumé d'un
//  appel décroché s'écrit localement dans
//  `MessageManager.handleCallEndedWriteSummary`, et le producteur du format vit à
//  côté du prédicat qui le reconnaît (`OSHIControlPayload`). Les tests ci-dessous
//  vérifient donc les DEUX bouts de la même paire — ce que le producteur écrit,
//  et ce que le filtre reconnaît — plutôt qu'une chaîne recopiée à la main.
//

import XCTest
@testable import OSHI

final class CallSummaryDuplicateTests: XCTestCase {

    // MARK: - Appels DÉCROCHÉS: la copie du pair est un doublon

    func testAnsweredCallSummaryIsRecognisedInBothDirections() {
        // `ChatView.handleCallEnded`: "📱CALL_SUMMARY📱\(icon)\(key)|\(duration)"
        XCTAssertTrue(OSHIControlPayload.isAnsweredCallSummary("📱CALL_SUMMARY📱📞↗️call.outgoing|2:10"),
                      "le résumé sortant du pair doit être reconnu pour être jeté")
        XCTAssertTrue(OSHIControlPayload.isAnsweredCallSummary("📱CALL_SUMMARY📱📞↙️call.incoming|2:10"),
                      "le résumé entrant du pair aussi — c'est LUI que voit l'appelant")
    }

    func testDurationShapesDoNotMatter() {
        for d in ["0:01", "2:10", "59:59", "1:02:03"] {
            XCTAssertTrue(OSHIControlPayload.isAnsweredCallSummary("📱CALL_SUMMARY📱📞↗️call.outgoing|\(d)"),
                          "une durée de \(d) reste une durée")
        }
    }

    // MARK: - Appels NON décrochés: ils doivent PASSER

    /// L'asymétrie est le cœur du correctif. Un appel décroché est forcément vu
    /// des deux côtés. Un appel manqué peut n'avoir été vu que par l'appelant —
    /// l'app d'en face était fermée, ou la notification n'est jamais arrivée.
    /// Son message est alors la SEULE trace que l'appel a existé, et le jeter
    /// effacerait un appel manqué de l'historique de quelqu'un.
    func testMissedAndDeclinedSummariesAreNotDropped() {
        let mustSurvive = [
            "📱CALL_SUMMARY📱📞↗️call.no.answer",   // j'ai appelé, personne n'a répondu
            "📱CALL_SUMMARY📱📞↗️call.declined",    // j'ai appelé, refusé
            "📱CALL_SUMMARY📱📞↙️call.declined",    // on m'a appelé, j'ai refusé
            "📱MISSED_CALL📱❌call.missed",         // appel manqué
        ]
        for payload in mustSurvive {
            XCTAssertFalse(OSHIControlPayload.isAnsweredCallSummary(payload),
                           "« \(payload) » ne porte pas de durée: il n'a pas été décroché, il doit passer")
        }
    }

    // MARK: - Rien d'autre ne doit être attrapé

    func testOrdinaryMessagesAreUntouched() {
        let innocent = [
            "Rappelle-moi à 14h | j'ai un créneau",   // un `|` dans du texte ordinaire
            "📍LOCATION📍48.85|2.35",                 // un AUTRE payload qui contient un `|`
            "",
            "CALL_SUMMARY sans les marqueurs|2:10",
        ]
        for text in innocent {
            XCTAssertFalse(OSHIControlPayload.isAnsweredCallSummary(text),
                           "« \(text) » n'est pas un résumé d'appel décroché")
        }
    }

    /// Le préfixe seul ne suffit pas: c'est la durée qui distingue les deux
    /// familles, et confondre les deux ferait disparaître les appels manqués.
    func testThePrefixAloneIsNotEnough() {
        XCTAssertFalse(OSHIControlPayload.isAnsweredCallSummary(OSHIControlPayload.callSummaryPrefix),
                       "un préfixe nu n'est pas un appel décroché")
    }

    // MARK: - Producteur et filtre, la même paire

    /// Le producteur alimente le prédicat. Recopier la chaîne à la main dans le
    /// test laisserait passer exactement la panne qu'on veut interdire: un
    /// producteur qui change sans que le filtre suive.
    func testWhatWeWriteIsWhatTheFilterRecognises() {
        for outgoing in [true, false] {
            let written = OSHIControlPayload.answeredCallSummary(wasOutgoing: outgoing, duration: "2:10")
            XCTAssertTrue(OSHIControlPayload.isAnsweredCallSummary(written),
                          "ce que nous écrivons doit être reconnu comme résumé d'appel décroché: \(written)")
        }
    }

    /// La direction est celle de l'appareil qui écrit — c'est la raison d'être
    /// de l'écriture locale. Un appel REÇU porte la flèche entrante.
    func testDirectionFollowsTheDeviceThatWrites() {
        XCTAssertEqual(OSHIControlPayload.answeredCallSummary(wasOutgoing: true, duration: "2:10"),
                       "📱CALL_SUMMARY📱📞↗️call.outgoing|2:10")
        XCTAssertEqual(OSHIControlPayload.answeredCallSummary(wasOutgoing: false, duration: "2:10"),
                       "📱CALL_SUMMARY📱📞↙️call.incoming|2:10")
    }

    // MARK: - La durée

    /// `ChatView.formatCallDuration` faisait `Int(duration) / 60` sans jamais
    /// retenir les heures: un appel de deux heures s'affichait « 125:30 ».
    func testDurationTextRollsOverIntoHours() {
        XCTAssertEqual(MessageManager.callDurationText(5), "0:05")
        XCTAssertEqual(MessageManager.callDurationText(130), "2:10")
        XCTAssertEqual(MessageManager.callDurationText(3599), "59:59")
        XCTAssertEqual(MessageManager.callDurationText(7530), "2:05:30")
    }

    /// Une durée négative ne devrait pas exister, mais elle ne doit surtout pas
    /// produire « -1:-30 » dans le fil de quelqu'un.
    func testNegativeDurationIsClamped() {
        XCTAssertEqual(MessageManager.callDurationText(-42), "0:00")
    }
}
