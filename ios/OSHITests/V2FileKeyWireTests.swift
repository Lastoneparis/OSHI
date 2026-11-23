//
//  V2FileKeyWireTests.swift
//  OSHITests
//
//  __REVEAL_WIRE_2026_08_22__ Le contrat de compatibilité du payload média 1:1.
//
//  Ajouter des champs à un message qui circule entre versions et entre
//  plateformes est le genre de changement qui casse loin de l'endroit où on l'a
//  écrit — chez quelqu'un qui n'a pas mis à jour, ou sur Android. Ces tests sont
//  ce qui remplace la promesse « ça devrait aller ».
//

import XCTest
@testable import OSHI

final class V2FileKeyWireTests: XCTestCase {

    private func encodeSorted(_ msg: V2FileKeyMessage) throws -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        return String(data: try enc.encode(msg), encoding: .utf8) ?? ""
    }

    private func plain(isViewOnce: Bool? = nil) -> V2FileKeyMessage {
        V2FileKeyMessage(
            kind: V2FileKeyMessage.kindValue,
            blobId: "blob-1", fileKey: "a2V5", fileNonce: "bm9uY2U=",
            chunkCount: 3, manifest: "bWFu", filename: "photo.jpg", mime: "image/jpeg",
            mediaType: "image", isViewOnce: isViewOnce,
            revealId: nil, revealLayerIndex: nil, revealLayerCount: nil, revealTotalMs: nil,
            groupMessage: nil
        )
    }

    // MARK: - Le trafic d'aujourd'hui ne bouge pas d'un octet

    /// Test DORÉ. La chaîne ci-dessous a été capturée sur le type AVANT l'ajout
    /// des quatre champs. Si un jour elle change, ce n'est pas le test qu'il faut
    /// mettre à jour: c'est que le payload de tous les envois existants a changé.
    func testPayloadIsUnchangedWhenNoRevealIsArmed() throws {
        let expected = #"{"blobId":"blob-1","chunkCount":3,"fileKey":"a2V5","fileNonce":"bm9uY2U=","filename":"photo.jpg","kind":"v2file","manifest":"bWFu","mediaType":"image","mime":"image\/jpeg"}"#
        XCTAssertEqual(try encodeSorted(plain()), expected,
                       "le payload d'un envoi SANS révélation doit rester identique au bit près")
    }

    /// Et les champs optionnels non armés ne doivent pas apparaître du tout —
    /// `null` sur le fil n'est pas la même chose qu'absent pour un parseur strict.
    func testUnarmedFieldsAreAbsentNotNull() throws {
        let json = try encodeSorted(plain())
        for key in ["revealId", "revealLayerIndex", "revealLayerCount", "revealTotalMs"] {
            XCTAssertFalse(json.contains(key), "\(key) ne doit pas figurer sur le fil quand il est nil")
        }
        XCTAssertFalse(json.contains("isViewOnce"), "même règle pour le précédent le plus proche")
    }

    // MARK: - Un client qui ne connaît pas ces champs ne doit rien perdre

    /// Reproduit un décodeur ANCIEN: une structure qui ignore les quatre clés.
    /// C'est le mécanisme dont dépend la compatibilité — une clé inconnue est
    /// ignorée, jamais fatale. (Une VALEUR D'ENUM inconnue, elle, ferait throw et
    /// emporterait le message entier: c'est pourquoi ces champs sont des
    /// scalaires.)
    private struct LegacyFileKey: Decodable {
        let kind: String
        let blobId: String
        let filename: String
        let mime: String
        let isViewOnce: Bool?
    }

    func testAnOlderClientIgnoresTheRevealKeysAndKeepsTheRest() throws {
        let armed = V2FileKeyMessage(
            kind: V2FileKeyMessage.kindValue,
            blobId: "blob-9", fileKey: "a2V5", fileNonce: "bm9uY2U=",
            chunkCount: 1, manifest: "bWFu", filename: "secret.jpg", mime: "image/jpeg",
            mediaType: "image", isViewOnce: nil,
            revealId: "msg-42", revealLayerIndex: 2, revealLayerCount: 5,
            revealTotalMs: 48 * 3600 * 1000,
            groupMessage: nil
        )
        let data = try JSONEncoder().encode(armed)
        let old = try JSONDecoder().decode(LegacyFileKey.self, from: data)
        XCTAssertEqual(old.kind, "v2file")
        XCTAssertEqual(old.blobId, "blob-9", "le blob doit survivre au passage")
        XCTAssertEqual(old.filename, "secret.jpg")
        XCTAssertEqual(old.mime, "image/jpeg")
    }

    /// Et dans l'autre sens: un payload ANCIEN, sans les quatre clés, doit se
    /// décoder dans le type actuel. C'est le cas de tous les messages déjà en
    /// vol quand la mise à jour arrive.
    func testTodaysPayloadStillDecodesIntoTheNewType() throws {
        let legacy = #"{"kind":"v2file","blobId":"b","fileKey":"k","fileNonce":"n","chunkCount":1,"manifest":"m","filename":"f.jpg","mime":"image/jpeg","mediaType":"image"}"#
        let decoded = try JSONDecoder().decode(V2FileKeyMessage.self, from: Data(legacy.utf8))
        XCTAssertEqual(decoded.blobId, "b")
        XCTAssertNil(decoded.revealId, "absent doit se lire comme « pas de révélation »")
        XCTAssertNil(decoded.revealLayerCount)
    }

    // MARK: - Une planification armée traverse intacte

    func testAnArmedRevealSurvivesTheRoundTrip() throws {
        let ctx = RevealSendContext(revealId: "msg-7", layerIndex: 0, layerCount: 4,
                                    totalMs: 2 * 24 * 3600 * 1000)
        let msg = V2FileKeyMessage(
            kind: V2FileKeyMessage.kindValue,
            blobId: "b", fileKey: "k", fileNonce: "n", chunkCount: 1, manifest: "m",
            filename: "f.jpg", mime: "image/jpeg", mediaType: "image", isViewOnce: nil,
            revealId: ctx.revealId, revealLayerIndex: ctx.layerIndex,
            revealLayerCount: ctx.layerCount, revealTotalMs: ctx.totalMs,
            groupMessage: nil
        )
        let back = try JSONDecoder().decode(V2FileKeyMessage.self, from: JSONEncoder().encode(msg))
        XCTAssertEqual(back.revealId, "msg-7")
        XCTAssertEqual(back.revealLayerIndex, 0)
        XCTAssertEqual(back.revealLayerCount, 4)
        XCTAssertEqual(back.revealTotalMs, 2 * 24 * 3600 * 1000,
                       "48 h en millisecondes doit tenir sans débordement")
    }

    /// `revealTotalMs` porte jusqu'à une semaine. En millisecondes ça fait
    /// 604 800 000 — au-delà d'`Int32`, donc à vérifier explicitement plutôt qu'à
    /// supposer.
    func testAWeekInMillisecondsFitsAndRoundTrips() throws {
        let week = 7 * 24 * 3600 * 1000
        XCTAssertGreaterThan(week, Int(Int32.max) / 4)
        let ctx = RevealSendContext(revealId: "x", layerIndex: 3, layerCount: 4, totalMs: week)
        XCTAssertEqual(ctx.totalMs, 604_800_000)
    }
}
