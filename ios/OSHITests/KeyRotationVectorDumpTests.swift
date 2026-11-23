//
//  KeyRotationVectorDumpTests.swift
//  OSHITests
//
//  Produit les vecteurs du tag d'autorisation de rotation, pour qu'Android soit
//  vérifié contre CE calcul plutôt que contre sa propre relecture de la règle.
//
//  Même raison que pour X-Wing: deux implémentations écrites depuis la même
//  description peuvent être fausses de la même façon. Et l'enjeu est le même —
//  si les tags divergent, une rotation légitime d'un iPhone est REFUSÉE par
//  Android, ce qui ressemble à une attaque et n'en est pas.
//

import XCTest
import CryptoKit
@testable import OSHI

final class KeyRotationVectorDumpTests: XCTestCase {

    /// Des entrées fixes et reconnaissables: un décalage d'un octet dans le
    /// message canonique se verrait immédiatement.
    private let sharedSecret = Data((0..<32).map { UInt8(($0 * 11 + 5) % 256) })
    private let oldKey = "b2xkLWtleS1iYXNlNjQtZXhhbXBsZQ=="
    private let newKey = "bmV3LWtleS1iYXNlNjQtZXhhbXBsZQ=="
    private let ts: UInt64 = 1_757_000_000_000
    private let n: UInt32 = 7

    func testDumpRotationVectorsForAndroid() throws {
        let msg = KeyRotationAuthenticator.canonicalMessage(
            oldPublicKey: oldKey, newPublicKey: newKey,
            timestampMillis: ts, rotationNumber: n)
        let tag = KeyRotationAuthenticator.tag(
            sharedSecret: sharedSecret, oldPublicKey: oldKey, newPublicKey: newKey,
            timestampMillis: ts, rotationNumber: n)
        let mk = KeyRotationAuthenticator.macKey(sharedSecret: sharedSecret)
            .withUnsafeBytes { Data($0) }

        // Une paire d'entrées AMBIGUËS si l'octet nul manquait: ("ab","c") et
        // ("a","bc") produiraient le même message sans séparateur. Les deux tags
        // doivent différer, et Android doit obtenir exactement les mêmes.
        let tagAB_C = KeyRotationAuthenticator.tag(
            sharedSecret: sharedSecret, oldPublicKey: "ab", newPublicKey: "c",
            timestampMillis: ts, rotationNumber: n)
        let tagA_BC = KeyRotationAuthenticator.tag(
            sharedSecret: sharedSecret, oldPublicKey: "a", newPublicKey: "bc",
            timestampMillis: ts, rotationNumber: n)
        XCTAssertNotEqual(tagAB_C, tagA_BC,
                          "le séparateur nul ne joue pas son rôle")

        let doc: [String: Any] = [
            "info": KeyRotationAuthenticator.info,
            "sharedSecret": sharedSecret.base64EncodedString(),
            "oldPublicKey": oldKey,
            "newPublicKey": newKey,
            "timestampMillis": String(ts),
            "rotationNumber": Int(n),
            "canonicalMessage": msg.base64EncodedString(),
            "macKey": mk.base64EncodedString(),
            "tag": tag.base64EncodedString(),
            "tagAmbiguousAB_C": tagAB_C.base64EncodedString(),
            "tagAmbiguousA_BC": tagA_BC.base64EncodedString()
        ]
        let data = try JSONSerialization.data(withJSONObject: doc,
                                              options: [.prettyPrinted, .sortedKeys])
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_rotation_vectors.json")
        try data.write(to: url, options: .atomic)
        print("ROTATION_VECTORS_WRITTEN \(url.path)")
    }

    /// Le bout qui ne peut PAS être mis en vecteur: que les deux côtés d'un
    /// ECDH obtiennent le même secret. On le vérifie ici en local.
    func testBothSidesOfTheExchangeAgree() throws {
        let senderOld = Curve25519.KeyAgreement.PrivateKey()
        let recipient = Curve25519.KeyAgreement.PrivateKey()

        let ssSender = try KeyRotationAuthenticator.sharedSecret(
            localPrivate: senderOld,
            peerPublicBase64: recipient.publicKey.rawRepresentation.base64EncodedString())
        let ssRecipient = try KeyRotationAuthenticator.sharedSecret(
            localPrivate: recipient,
            peerPublicBase64: senderOld.publicKey.rawRepresentation.base64EncodedString())

        XCTAssertEqual(ssSender, ssRecipient,
                       "l'émetteur et le destinataire ne dérivent pas le même secret")
    }

    /// Un tiers qui ne détient pas l'ancienne clé privée ne peut pas produire le
    /// tag. C'est toute la propriété recherchée.
    func testAThirdPartyCannotForgeTheTag() throws {
        let senderOld = Curve25519.KeyAgreement.PrivateKey()
        let recipient = Curve25519.KeyAgreement.PrivateKey()
        let attacker = Curve25519.KeyAgreement.PrivateKey()
        let recipientPub = recipient.publicKey.rawRepresentation.base64EncodedString()

        let legit = KeyRotationAuthenticator.tag(
            sharedSecret: try KeyRotationAuthenticator.sharedSecret(
                localPrivate: senderOld, peerPublicBase64: recipientPub),
            oldPublicKey: oldKey, newPublicKey: newKey,
            timestampMillis: ts, rotationNumber: n)

        let forged = KeyRotationAuthenticator.tag(
            sharedSecret: try KeyRotationAuthenticator.sharedSecret(
                localPrivate: attacker, peerPublicBase64: recipientPub),
            oldPublicKey: oldKey, newPublicKey: newKey,
            timestampMillis: ts, rotationNumber: n)

        XCTAssertNotEqual(legit, forged, "un tiers a produit un tag valide")

        // Et le destinataire refuse bien le faux.
        let ssAtRecipient = try KeyRotationAuthenticator.sharedSecret(
            localPrivate: recipient,
            peerPublicBase64: senderOld.publicKey.rawRepresentation.base64EncodedString())
        XCTAssertTrue(KeyRotationAuthenticator.isValidTag(
            legit, sharedSecret: ssAtRecipient, oldPublicKey: oldKey, newPublicKey: newKey,
            timestampMillis: ts, rotationNumber: n))
        XCTAssertFalse(KeyRotationAuthenticator.isValidTag(
            forged, sharedSecret: ssAtRecipient, oldPublicKey: oldKey, newPublicKey: newKey,
            timestampMillis: ts, rotationNumber: n))
    }

    /// Rejouer un paquet ancien, ou revenir en arrière sur le numéro, doit être
    /// refusé même si le tag est parfaitement valide.
    func testFreshnessRefusesReplays() throws {
        let now = Date(timeIntervalSince1970: 1_757_000_000)
        // Le cas nominal passe.
        XCTAssertNoThrow(try KeyRotationAuthenticator.checkFreshness(
            timestampMillis: 1_757_000_000_000, rotationNumber: 7,
            lastRotationNumber: 6, now: now))
        // Un numéro déjà vu, non.
        XCTAssertThrowsError(try KeyRotationAuthenticator.checkFreshness(
            timestampMillis: 1_757_000_000_000, rotationNumber: 6,
            lastRotationNumber: 6, now: now))
        // Un paquet d'il y a un mois, non.
        XCTAssertThrowsError(try KeyRotationAuthenticator.checkFreshness(
            timestampMillis: 1_757_000_000_000 - 30 * 24 * 3600 * 1000, rotationNumber: 8,
            lastRotationNumber: 6, now: now))
        // Un paquet daté du futur lointain, non plus.
        XCTAssertThrowsError(try KeyRotationAuthenticator.checkFreshness(
            timestampMillis: 1_757_000_000_000 + 30 * 24 * 3600 * 1000, rotationNumber: 8,
            lastRotationNumber: 6, now: now))
    }
}
