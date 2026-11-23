//
//  ReplyQuoteRenderingTests.swift
//  OSHITests
//
//  __REPLY_QUOTE_NO_RAW_JSON_2026_08_17__ + __BUBBLE_TIME_2026_08_17__
//
//  Deux règles que l'œil ne peut pas vérifier de façon fiable en relisant une
//  capture d'écran, et qui sont exactement celles qui se cassent en silence:
//
//    1. Une citation ne doit JAMAIS afficher de JSON brut. Le catalogue résume
//       tout ce qu'il connaît; ce test couvre ce qu'il ne connaît PAS, parce
//       qu'un futur payload de bot ou un client tiers y retombera.
//    2. L'horodatage révélé doit porter l'heure, y compris pour hier — c'était
//       précisément l'information que la bulle supprimait.
//

import XCTest
@testable import OSHI

final class ReplyQuoteRenderingTests: XCTestCase {

    private func quote(_ originalText: String, mediaType: String? = nil) -> ReplyMessage {
        ReplyMessage(
            originalMessageId: "abc-123",
            originalSenderKey: "U2VuZGVyS2V5QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ==",
            originalText: originalText,
            originalTimestamp: Date(timeIntervalSince1970: 1_700_000_000),
            originalMediaType: mediaType
        )
    }

    // MARK: - Jamais de JSON brut dans une citation

    /// Le cas que le catalogue ne couvre pas: un objet JSON qu'il ne reconnaît
    /// pas retombe sur `?? text`, et la citation afficherait des accolades.
    func testUnrecognisedJSONNeverReachesTheQuote() {
        let body = quote(#"{"type":"bot_payload","v":3,"data":{"x":1}}"#).bodyText
        XCTAssertFalse(body.hasPrefix("{"), "la citation affiche du JSON brut: « \(body) »")
        XCTAssertFalse(body.contains("\"type\""))
        XCTAssertFalse(body.isEmpty, "une citation vide n'apprend rien non plus")
    }

    /// Quand la citation pointe sur un média, l'étiquette du média est meilleure
    /// que « Message chiffré ».
    func testUnrecognisedJSONWithMediaFallsBackToTheMediaLabel() {
        let body = quote(#"{"unknown":true}"#, mediaType: "image").bodyText
        XCTAssertFalse(body.contains("{"))
        XCTAssertTrue(body.contains("📷"), "attendu l'étiquette photo, obtenu « \(body) »")
    }

    /// Les payloads que le catalogue CONNAÎT restent résumés, pas remplacés par
    /// le filet: le filet est un dernier recours, pas un raccourci.
    func testKnownPayloadsAreStillSummarisedByTheCatalog() {
        XCTAssertTrue(quote("📍LOCATION📍{\"lat\":48.8}").bodyText.contains("📍"))
        let media = quote(#"{"type":"media","mediaType":"video","caption":"le sommet"}"#).bodyText
        XCTAssertFalse(media.hasPrefix("{"))
        XCTAssertTrue(media.contains("le sommet"), "la légende est ce que la personne a écrit: « \(media) »")
    }

    /// La prose ordinaire passe intacte — y compris si elle contient une accolade.
    func testProseIsUntouched() {
        XCTAssertEqual(quote("on se retrouve à 18h").bodyText, "on se retrouve à 18h")
        XCTAssertEqual(quote("le code est {A12}").bodyText, "le code est {A12}",
                       "une accolade au milieu d'une phrase n'est pas du JSON")
    }

    /// Une citation reste une ligne d'aperçu: bornée, avec des points de suite.
    func testLongQuoteIsTruncated() {
        let body = quote(String(repeating: "a", count: 400)).bodyText
        XCTAssertLessThanOrEqual(body.count, 104)
        XCTAssertTrue(body.hasSuffix("..."))
    }

    // MARK: - L'heure d'un message d'hier

    /// Le défaut signalé: passé minuit, l'heure d'envoi devenait introuvable.
    /// `OshiRowFormatters` est le formateur réel de l'app.
    func testYesterdayStillCarriesAnHour() {
        let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: Date())!
        let revealed = OshiRowFormatters.string(yesterday, format: "d MMM · HH:mm")
        XCTAssertTrue(revealed.contains(":"), "révélé sans heure: « \(revealed) »")
        XCTAssertTrue(revealed.contains("·"), "la date doit accompagner l'heure hors d'aujourd'hui")
    }

    func testTodayNeedsNoDate() {
        let now = OshiRowFormatters.string(Date(), format: "HH:mm")
        XCTAssertTrue(now.contains(":"))
        XCTAssertFalse(now.contains("·"), "l'en-tête de section donne déjà le jour")
    }

    // MARK: - La même règle dans le fil de GROUPE

    // Les deux tests ci-dessus interrogent `OshiRowFormatters` avec un format
    // qu'ils écrivent eux-mêmes: ils prouvent que le formateur sait produire une
    // heure, pas qu'une bulle la DEMANDE. C'est exactement le trou par lequel le
    // défaut est resté six mois côté groupe — le 1:1 avait été corrigé, la bulle
    // de groupe rendait toujours « Hier » nu, et aucun test n'a bronché.
    //
    // Les fonctions fautives sont `private` dans des `View`: rien ne peut les
    // appeler depuis un test. Ce qui est vérifiable, c'est leur SOURCE. La règle
    // est donc lue sur le fichier, et — parce qu'une règle neuve ne prouve rien
    // tant qu'on ne l'a pas vue échouer — `testTheRuleRejectsTheOldBody` la
    // rejoue sur une copie du corps d'AVANT et exige un refus.

    /// Corps d'une fonction repéré par sa signature, jusqu'à l'accolade appariée.
    private func bodyOfFunction(named signature: String, in source: String) -> String? {
        guard let sig = source.range(of: signature),
              let open = source[sig.upperBound...].firstIndex(of: "{") else { return nil }
        var depth = 0
        var i = open
        while i < source.endIndex {
            if source[i] == "{" { depth += 1 }
            if source[i] == "}" {
                depth -= 1
                if depth == 0 { return String(source[source.index(after: open)..<i]) }
            }
            i = source.index(after: i)
        }
        return nil
    }

    /// Une bulle affiche l'heure TOUS LES JOURS si son libellé ne bifurque pas
    /// sur « hier ». La date, elle, est déjà donnée par l'en-tête de jour.
    private func showsAnHourEveryDay(_ body: String) -> Bool {
        !body.contains("isDateInYesterday") && !body.contains("date_yesterday")
    }

    private func source(of relativePath: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // OSHITests/
            .deletingLastPathComponent()   // racine du projet
        return try String(contentsOf: root.appendingPathComponent(relativePath), encoding: .utf8)
    }

    private func assertBubbleAlwaysShowsAnHour(
        _ signature: String, in relativePath: String,
        file: StaticString = #filePath, line: UInt = #line
    ) throws {
        let text = try source(of: relativePath)
        guard let body = bodyOfFunction(named: signature, in: text) else {
            return XCTFail("« \(signature) » introuvable dans \(relativePath) — " +
                           "renommée? la règle ne surveille alors PLUS RIEN",
                           file: file, line: line)
        }
        XCTAssertTrue(showsAnHourEveryDay(body),
                      "\(relativePath) · \(signature) bifurque encore sur « hier »: " +
                      "passé minuit l'heure d'envoi redevient introuvable, alors que " +
                      "l'en-tête de jour donne déjà la date. Corps: \(body)",
                      file: file, line: line)
        XCTAssertTrue(body.contains("HH:mm"),
                      "\(relativePath) · \(signature) ne rend plus d'heure du tout: \(body)",
                      file: file, line: line)
    }

    /// Le fil de groupe: les quatre variantes de bulle (texte, média, audio,
    /// position) passent toutes par `chatFormatTimestamp`.
    func testGroupBubbleAlwaysShowsAnHour() throws {
        try assertBubbleAlwaysShowsAnHour(
            "private func chatFormatTimestamp(_ date: Date) -> String",
            in: "OSHI/GroupViews.swift")
    }

    /// Le fil 1:1 — la référence dont le groupe avait divergé.
    func testDirectBubbleAlwaysShowsAnHour() throws {
        try assertBubbleAlwaysShowsAnHour(
            "private func formatTimestamp(_ date: Date) -> String",
            in: "OSHI/ChatView.swift")
    }

    /// Le témoin: la règle doit REFUSER le corps d'avant. Sans ça, un test qui
    /// passe ne dit rien — il pourrait passer sur n'importe quoi.
    func testTheRuleRejectsTheOldBody() {
        let before = """
        private func chatFormatTimestamp(_ date: Date) -> String {
            if Calendar.current.isDateInToday(date) {
                return OshiRowFormatters.string(date, format: "HH:mm")
            } else if Calendar.current.isDateInYesterday(date) {
                return "date_yesterday".localized
            } else {
                return OshiRowFormatters.string(date, format: "MMM d")
            }
        }
        """
        let body = bodyOfFunction(
            named: "private func chatFormatTimestamp(_ date: Date) -> String", in: before)
        XCTAssertNotNil(body, "l'extracteur ne sait plus lire un corps de fonction")
        XCTAssertFalse(showsAnHourEveryDay(body ?? ""),
                       "la règle accepte le corps FAUTIF: elle ne surveille rien")
    }

    /// Les LISTES, elles, ont raison de dire « Hier »: aucune n'a d'en-tête de
    /// jour au-dessus. Ce test fige la frontière — pour que le correctif
    /// ci-dessus ne soit pas « généralisé » aux lignes de conversation.
    func testConversationListsStillSayYesterday() throws {
        let groups = try source(of: "OSHI/GroupViews.swift")
        guard let row = bodyOfFunction(
            named: "private func formatTimestamp(_ date: Date) -> String", in: groups) else {
            return XCTFail("la ligne de liste de groupes n'a plus d'horodatage")
        }
        XCTAssertTrue(row.contains("date_yesterday"),
                      "une LIGNE DE LISTE n'a pas d'en-tête de jour: elle doit garder « Hier »")
    }
}
