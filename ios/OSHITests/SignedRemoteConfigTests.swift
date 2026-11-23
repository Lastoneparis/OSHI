//
//  SignedRemoteConfigTests.swift
//  OSHITests
//
//  La configuration distante décide si l'appareil parle v2 ou retombe sur
//  l'ancien chemin. Influencer cette réponse EST la rétrogradation (M1), donc ce
//  sont ces règles-ci qui la ferment — pas la cryptographie, qui n'est qu'un
//  moyen.
//

import XCTest
import CryptoKit
@testable import OSHI

final class SignedRemoteConfigTests: XCTestCase {

    private let payload = Data(#"{"v2_enabled":true,"rollout_percent":100,"min_build":0}"#.utf8)
    private lazy var key = Curve25519.Signing.PrivateKey()
    private var pubB64: String { key.publicKey.rawRepresentation.base64EncodedString() }

    private func envelope(version: UInt64, signWith: Curve25519.Signing.PrivateKey?,
                          payload: Data? = nil) -> Data {
        let p = payload ?? self.payload
        var obj: [String: Any] = [
            "payload": p.base64EncodedString(),
            "config_version": Int(version)
        ]
        if let k = signWith {
            let sig = try! k.signature(for: SignedRemoteConfig.signedBytes(payload: p,
                                                                          configVersion: version))
            obj["sig"] = sig.base64EncodedString()
        }
        return try! JSONSerialization.data(withJSONObject: obj)
    }

    /// Le cas nominal: signée, plus récente, acceptée.
    func testAProperlySignedNewerConfigIsAccepted() throws {
        let r = try SignedRemoteConfig.accept(responseBody: envelope(version: 5, signWith: key),
                                              lastAcceptedVersion: 4,
                                              publicKeyBase64: pubB64)
        XCTAssertEqual(r.payload, payload)
        XCTAssertEqual(r.version, 5)
    }

    /// ⚠️ Une signature FAUSSE est refusée même quand on n'exige rien. C'est
    /// gratuit en compatibilité et ça ferme la fabrication d'enveloppes.
    func testAForgedSignatureIsAlwaysRejected() {
        let attacker = Curve25519.Signing.PrivateKey()
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: envelope(version: 9, signWith: attacker),
                                          lastAcceptedVersion: 0,
                                          publicKeyBase64: pubB64, require: false)
        ) { XCTAssertEqual($0 as? SignedRemoteConfig.Rejection, .badSignature) }
    }

    /// Une enveloppe dont on a RETIRÉ la signature: quelqu'un a pris la peine
    /// d'en construire une et d'en enlever la preuve. Refusée sans condition.
    func testAStrippedSignatureIsRejectedEvenWhenNotRequired() {
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: envelope(version: 9, signWith: nil),
                                          lastAcceptedVersion: 0,
                                          publicKeyBase64: pubB64, require: false)
        ) { XCTAssertEqual($0 as? SignedRemoteConfig.Rejection, .unsigned) }
    }

    /// ⚠️ LE test du retour en arrière. Une ANCIENNE configuration authentique
    /// — celle d'avant l'activation de v2 — rétrograde aussi bien qu'une
    /// contrefaçon, et sans rien forger.
    func testAnOlderAuthenticConfigIsRefused() {
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: envelope(version: 3, signWith: key),
                                          lastAcceptedVersion: 7,
                                          publicKeyBase64: pubB64)
        ) {
            XCTAssertEqual($0 as? SignedRemoteConfig.Rejection, .rolledBack(seen: 7, offered: 3))
        }
    }

    /// Et rejouer la MÊME version ne passe pas non plus: l'ordre doit être
    /// strict, sinon une version peut être réécrite après coup.
    func testTheSameVersionIsRefused() {
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: envelope(version: 7, signWith: key),
                                          lastAcceptedVersion: 7,
                                          publicKeyBase64: pubB64)
        )
    }

    /// La forme HÉRITÉE — ce que le serveur renvoie aujourd'hui — passe tant
    /// qu'on n'exige pas de signature. Sans ça, livrer ce code ignorerait la
    /// configuration vivante et rétrograderait tout le monde.
    func testTheLegacyUnenvelopedResponseStillWorks() throws {
        let r = try SignedRemoteConfig.accept(responseBody: payload,
                                              lastAcceptedVersion: 2,
                                              publicKeyBase64: pubB64, require: false)
        XCTAssertEqual(r.payload, payload)
        XCTAssertEqual(r.version, 2, "une réponse héritée ne doit pas faire avancer la version")
    }

    /// Et la même réponse héritée est refusée dès qu'on exige des signatures.
    func testTheLegacyResponseIsRefusedOnceSignaturesAreRequired() {
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: payload,
                                          lastAcceptedVersion: 0,
                                          publicKeyBase64: pubB64, require: true)
        ) { XCTAssertEqual($0 as? SignedRemoteConfig.Rejection, .unsigned) }
    }

    /// La signature couvre la VERSION, pas seulement la charge utile: sinon on
    /// pourrait relever le numéro d'une vieille configuration pour la faire
    /// passer pour neuve.
    func testTheVersionIsCoveredBySignature() {
        let good = envelope(version: 5, signWith: key)
        var obj = try! JSONSerialization.jsonObject(with: good) as! [String: Any]
        obj["config_version"] = 99            // on remonte le numéro, on garde le tag
        let tampered = try! JSONSerialization.data(withJSONObject: obj)
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: tampered, lastAcceptedVersion: 0,
                                          publicKeyBase64: pubB64)
        ) { XCTAssertEqual($0 as? SignedRemoteConfig.Rejection, .badSignature) }
    }

    /// Et elle couvre la charge utile: on ne peut pas glisser une autre config
    /// sous une signature valide.
    func testThePayloadIsCoveredBySignature() {
        let good = envelope(version: 5, signWith: key)
        var obj = try! JSONSerialization.jsonObject(with: good) as! [String: Any]
        obj["payload"] = Data(#"{"v2_enabled":false,"rollout_percent":0,"min_build":0}"#.utf8)
            .base64EncodedString()
        let tampered = try! JSONSerialization.data(withJSONObject: obj)
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: tampered, lastAcceptedVersion: 0,
                                          publicKeyBase64: pubB64)
        ) { XCTAssertEqual($0 as? SignedRemoteConfig.Rejection, .badSignature) }
    }

    /// Écrit les vecteurs pour Android: une CLÉ PRIVÉE de test, pour qu'il
    /// puisse fabriquer ses propres enveloppes et qu'on vérifie que les deux
    /// plateformes acceptent et refusent exactement les mêmes.
    ///
    /// La clé est engendrée ici et ne sert qu'à ça: ce n'est pas celle de
    /// l'opérateur, qui n'existe pas encore.
    func testDumpConfigVectorsForAndroid() throws {
        let k = Curve25519.Signing.PrivateKey()
        let pub = k.publicKey.rawRepresentation.base64EncodedString()
        let version: UInt64 = 5
        let signed = SignedRemoteConfig.signedBytes(payload: payload, configVersion: version)
        let sig = try k.signature(for: signed)

        let doc: [String: Any] = [
            "publicKey": pub,
            "privateKey": k.rawRepresentation.base64EncodedString(),
            "payload": payload.base64EncodedString(),
            "configVersion": Int(version),
            "signedBytes": signed.base64EncodedString(),
            "signature": sig.base64EncodedString()
        ]
        let data = try JSONSerialization.data(withJSONObject: doc,
                                              options: [.prettyPrinted, .sortedKeys])
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_config_vectors.json")
        try data.write(to: url, options: .atomic)
        print("CONFIG_VECTORS_WRITTEN \(url.path)")
    }

    /// ⚠️ LE test de bout en bout: une enveloppe produite par l'OUTIL RÉEL
    /// (`tools/config/sign_config.py`), vérifiée avec la clé RÉELLEMENT épinglée
    /// dans ce binaire.
    ///
    /// Les autres tests utilisent une clé engendrée sur place; celui-ci prouve
    /// que la chaîne complète se referme — paire de l'opérateur, signature,
    /// épinglage. Si l'outil et le client divergeaient, TOUTE configuration
    /// légitime serait refusée et chaque appareil retomberait sur son défaut,
    /// c'est-à-dire la rétrogradation qu'on veut empêcher.
    func testAnEnvelopeFromTheRealSignerIsAccepted() throws {
        let body = Data(#"""
        {"payload": "eyJ2Ml9lbmFibGVkIjp0cnVlLCJyb2xsb3V0X3BlcmNlbnQiOjEwMCwibWluX2J1aWxkIjowfQo=", "config_version": 3, "sig": "jvjNCycrjDXX4cF4zhz+SqV1ICLgxSQXEq+ft2aQNjWF0PVPxBE7JXqxf5/qIkLwbGO4ZDtMBLOpEaFh+3w9Cg=="}
        """#.utf8)
        let r = try SignedRemoteConfig.accept(responseBody: body, lastAcceptedVersion: 0)
        XCTAssertEqual(r.version, 3)
        XCTAssertTrue(String(data: r.payload, encoding: .utf8)!.contains("v2_enabled"))
    }

    /// Un corps illisible n'est pas une configuration.
    func testGarbageIsRejected() {
        XCTAssertThrowsError(
            try SignedRemoteConfig.accept(responseBody: Data("pas du json".utf8),
                                          lastAcceptedVersion: 0, publicKeyBase64: pubB64)
        ) { XCTAssertEqual($0 as? SignedRemoteConfig.Rejection, .malformed) }
    }
}
