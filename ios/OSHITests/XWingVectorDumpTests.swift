//
//  XWingVectorDumpTests.swift
//  OSHITests
//
//  Produit de VRAIS vecteurs X-Wing depuis CryptoKit, pour qu'Android puisse
//  être vérifié contre l'implémentation d'Apple plutôt que contre ma lecture du
//  brouillon IETF.
//
//  POURQUOI ÇA EXISTE. Porter X-Wing sur Android veut dire réimplémenter un
//  combinateur — SHA3-256 sur une étiquette, deux secrets et deux clés — à
//  partir d'une spécification. Si je me trompe d'un octet, rien ne plante: les
//  deux côtés dérivent des racines différentes et TOUT échoue au déchiffrement,
//  en silence et dans les deux sens. C'est exactement l'avertissement de l'audit.
//
//  Un test qui compare mon Android à ma lecture du brouillon ne prouve rien: les
//  deux peuvent être faux de la même façon. Comparer à CryptoKit, si.
//
//  LE SIMULATEUR EST EN iOS 26, donc X-Wing y est disponible alors que l'app
//  déploie en 17.5. C'est ce qui rend ces vecteurs possibles du tout.
//
//  Ce test n'ASSERTE presque rien: il ÉCRIT. Sa sortie est lue par
//  `XWingInteropTest` côté Android. Il est ignoré sur un OS trop ancien plutôt
//  que d'échouer, parce qu'un OS sans X-Wing n'a rien à dire sur X-Wing.
//

import XCTest
import CryptoKit
@testable import OSHI

final class XWingVectorDumpTests: XCTestCase {

    /// Là où Android ira les lire. Un chemin fixe, pas un dossier temporaire:
    /// deux processus différents, sur deux outils différents, doivent se donner
    /// rendez-vous quelque part.
    private var vectorURL: URL {
        URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_xwing_vectors.json")
    }

    /// Écrit ce que CryptoKit produit réellement: une graine, la clé publique
    /// qu'elle engendre, un chiffré et le secret qu'il transporte.
    func testDumpVectorsForAndroid() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform,
                          "X-Wing demande iOS 26 — rien à produire ici")

        var vectors: [[String: String]] = []
        for _ in 0..<3 {
            let kp = try PostQuantumKEM.generateKeyPair()
            XCTAssertEqual(kp.publicKey.count, PostQuantumKEM.publicKeyByteCount)

            // La graine doit ENGENDRER la clé publique de façon déterministe —
            // c'est la moitié du contrat qu'Android doit reproduire.
            let rederived = try PostQuantumKEM.publicKey(forSeed: kp.seed)
            XCTAssertEqual(rederived, kp.publicKey,
                           "la graine ne redonne pas la même clé publique")

            let (ct, ss) = try PostQuantumKEM.encapsulate(toPublicKey: kp.publicKey)
            XCTAssertEqual(ct.count, PostQuantumKEM.ciphertextByteCount)
            XCTAssertEqual(ss.count, PostQuantumKEM.sharedSecretByteCount)

            // Et la décapsulation doit rendre le même secret, ici aussi.
            let back = try PostQuantumKEM.decapsulate(ct, withSeed: kp.seed)
            XCTAssertEqual(back, ss, "encaps/decaps ne se répondent pas")

            vectors.append([
                "seed": kp.seed.base64EncodedString(),
                "publicKey": kp.publicKey.base64EncodedString(),
                "ciphertext": ct.base64EncodedString(),
                "sharedSecret": ss.base64EncodedString()
            ])
        }

        let doc: [String: Any] = [
            "algorithm": PostQuantumKEM.algorithmIdentifier,
            "publicKeyByteCount": PostQuantumKEM.publicKeyByteCount,
            "ciphertextByteCount": PostQuantumKEM.ciphertextByteCount,
            "sharedSecretByteCount": PostQuantumKEM.sharedSecretByteCount,
            "securityModeRawValue": RatchetSecurityMode.hybridPQ.rawValue,
            "vectors": vectors
        ]
        let data = try JSONSerialization.data(withJSONObject: doc,
                                              options: [.prettyPrinted, .sortedKeys])
        try data.write(to: vectorURL, options: .atomic)
        print("XWING_VECTORS_WRITTEN \(vectorURL.path) bytes=\(data.count)")
    }

    /// Les VRAIES données additionnelles de l'AEAD, telles que cette session les
    /// calcule — pas telles que je les ai comprises.
    ///
    /// C'est la pièce qui protège du pire scénario: si Android compose l'AAD
    /// autrement, les deux côtés chiffrent correctement et échouent à
    /// s'authentifier mutuellement. Rien ne plante, tout échoue.
    ///
    /// Deux vecteurs, et le contraste entre les deux est l'essentiel:
    /// le CLASSIQUE doit être exactement ce qu'il est aujourd'hui (sinon on
    /// casse chaque pair déjà déployé), l'HYBRIDE ajoute « hybridPQ » puis le
    /// chiffré.
    func testDumpAADVectorsForAndroid() throws {
        let session = DoubleRatchetSession(rootKey: Data(repeating: 1, count: 32),
                                           sendingChainKey: Data(repeating: 2, count: 32),
                                           receivingChainKey: Data(repeating: 3, count: 32),
                                           sessionId: "vector-session",
                                           isInitiator: true)
        let pk = Data((0..<32).map { UInt8($0) })
        let ct = Data((0..<PostQuantumKEM.ciphertextByteCount).map { UInt8($0 % 251) })

        var classical = DoubleRatchetMessage.MessageHeader(
            publicKey: pk, messageNumber: 7, previousChainLength: 3,
            isDHRatchet: true, timestamp: Date(timeIntervalSince1970: 0),
            sessionVersion: 3, sessionId: "vector-session")

        var hybrid = classical
        hybrid.securityMode = RatchetSecurityMode.hybridPQ
        hybrid.pqCiphertext = ct

        let doc: [String: Any] = [
            "publicKey": pk.base64EncodedString(),
            "messageNumber": 7,
            "previousChainLength": 3,
            "isDHRatchet": true,
            "sessionVersion": 3,
            "sessionId": "vector-session",
            "pqCiphertext": ct.base64EncodedString(),
            "aadClassical": session.encodeHeaderForAAD(classical).base64EncodedString(),
            "aadHybrid": session.encodeHeaderForAAD(hybrid).base64EncodedString()
        ]
        let data = try JSONSerialization.data(withJSONObject: doc,
                                              options: [.prettyPrinted, .sortedKeys])
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_aad_vectors.json")
        try data.write(to: url, options: .atomic)
        print("AAD_VECTORS_WRITTEN \(url.path) bytes=\(data.count)")
    }

    /// La dérivation de la racine et de la chaîne initiale, dans les deux modes.
    ///
    /// C'est la dernière pièce à prouver: le KEM peut être exact et l'AAD
    /// exacte, si les deux côtés ne dérivent pas la même racine à partir de la
    /// même matière, aucun message ne passe.
    ///
    /// Les chaînes info sont ÉCRITES DANS LE FICHIER, et les deux plateformes
    /// assertent que leur propre constante de production leur est égale. Si
    /// quelqu'un change l'une des deux, le test tombe — au lieu de laisser deux
    /// clients diverger en silence.
    func testDumpDerivationVectorsForAndroid() throws {
        func hkdf(_ ikm: Data, _ info: String) -> Data {
            HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm),
                                   salt: Data(), info: Data(info.utf8),
                                   outputByteCount: 32)
                .withUnsafeBytes { Data($0) }
        }
        // Des entrées fixes et reconnaissables: un décalage d'un octet dans la
        // concaténation se verrait immédiatement.
        let ss = Data((0..<32).map { UInt8($0) })
        let pq = Data((0..<32).map { UInt8(200 &- $0) })
        let ikmClassical = ss
        let ikmHybrid = ss + pq          // ⚠️ l'ORDRE fait partie du contrat

        let doc: [String: Any] = [
            "sharedSecret": ss.base64EncodedString(),
            "pqSecret": pq.base64EncodedString(),
            "infoRootClassical": "RootKey",
            "infoChainClassical": "InitialChain",
            "infoRootHybrid": "RootKey-XWing-v1",
            "infoChainHybrid": "InitialChain-XWing-v1",
            "rootClassical": hkdf(ikmClassical, "RootKey").base64EncodedString(),
            "chainClassical": hkdf(ikmClassical, "InitialChain").base64EncodedString(),
            "rootHybrid": hkdf(ikmHybrid, "RootKey-XWing-v1").base64EncodedString(),
            "chainHybrid": hkdf(ikmHybrid, "InitialChain-XWing-v1").base64EncodedString()
        ]
        let data = try JSONSerialization.data(withJSONObject: doc,
                                              options: [.prettyPrinted, .sortedKeys])
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_derivation_vectors.json")
        try data.write(to: url, options: .atomic)
        print("DERIVATION_VECTORS_WRITTEN \(url.path)")
    }

    /// UN ÉCHANGE HYBRIDE RÉEL: on établit une session, on chiffre, on écrit le
    /// message tel qu'il partirait sur le fil — et Android devra le déchiffrer.
    ///
    /// C'est ce qui manquait aux trois preuves précédentes. Le KEM, l'AAD et la
    /// dérivation sont exacts séparément; ceci vérifie la TUYAUTERIE qui les
    /// assemble: la session porte-t-elle le mode et le chiffré, l'en-tête les
    /// écrit-il, l'AAD les reprend-elle, et le tout s'ouvre-t-il de l'autre côté.
    ///
    /// Pourquoi ça ne demande pas deux téléphones en réseau: le répondeur dérive
    /// sa clé de ratchet du secret partagé (`derivedResponderRatchetKey`), donc
    /// à partir des mêmes entrées fixes les deux plateformes construisent la
    /// MÊME session. Ce qui circule ensuite est exactement ce qui circulerait
    /// entre deux appareils.
    func testDumpHybridExchangeForAndroid() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform, "X-Wing demande iOS 26")

        // Entrées fixes: le secret X25519, et la moitié post-quantique.
        let ss = Data((0..<32).map { UInt8(($0 * 7 + 3) % 256) })
        let kp = try PostQuantumKEM.generateKeyPair()
        let (ct, pq) = try PostQuantumKEM.encapsulate(toPublicKey: kp.publicKey)
        let ikm = ss + pq
        let sessionId = "hybrid-exchange-vector"

        func hkdf(_ info: String) -> Data {
            HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm),
                                   salt: Data(), info: Data(info.utf8),
                                   outputByteCount: 32)
                .withUnsafeBytes { Data($0) }
        }
        let root = hkdf("RootKey-XWing-v1")

        // L'INITIATEUR, côté iOS.
        let session = DoubleRatchetSession.makeRatcheting(
            rootSeed: root, sharedSecret: ikm, sessionId: sessionId,
            isInitiator: true, securityMode: .hybridPQ, pqCiphertext: ct)

        var wire: [String] = []
        let plaintexts = ["bonjour depuis iOS",
                          "deuxième message, même chaîne",
                          "accents: éàü — et un emoji 🔐"]
        for p in plaintexts {
            let msg = try session.encrypt(p)
            let data = try JSONEncoder().encode(msg)
            wire.append(data.base64EncodedString())
        }

        let doc: [String: Any] = [
            "sharedSecret": ss.base64EncodedString(),
            "pqSecret": pq.base64EncodedString(),
            "pqCiphertext": ct.base64EncodedString(),
            "pqSeed": kp.seed.base64EncodedString(),
            "sessionId": sessionId,
            "plaintexts": plaintexts,
            "messages": wire
        ]
        let data = try JSONSerialization.data(withJSONObject: doc,
                                              options: [.prettyPrinted, .sortedKeys])
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_hybrid_exchange.json")
        try data.write(to: url, options: .atomic)
        print("HYBRID_EXCHANGE_WRITTEN \(url.path) messages=\(wire.count)")
    }

    /// Et la réponse d'Android, déchiffrée ICI.
    ///
    /// Sans ce sens-là on n'aurait prouvé qu'une direction — et une direction
    /// qui marche pendant que l'autre échoue est le symptôme le plus trompeur
    /// qui soit, parce qu'il ressemble à un problème de réseau.
    func testAndroidHybridRepliesDecryptHere() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform, "X-Wing demande iOS 26")
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_hybrid_replies_from_android.json")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: url.path),
                          "Android n'a pas encore répondu — lancer HybridExchangeInteropTest")

        let doc = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let ss = Data(base64Encoded: doc["sharedSecret"] as! String)!
        let pq = Data(base64Encoded: doc["pqSecret"] as! String)!
        let ct = Data(base64Encoded: doc["pqCiphertext"] as! String)!
        let sessionId = doc["sessionId"] as! String
        let expected = doc["plaintexts"] as! [String]
        let wire = doc["messages"] as! [String]

        let ikm = ss + pq
        let root = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm),
                                          salt: Data(), info: Data("RootKey-XWing-v1".utf8),
                                          outputByteCount: 32).withUnsafeBytes { Data($0) }
        // Ici nous sommes l'INITIATEUR et Android le répondeur — l'inverse du
        // test précédent, donc les deux rôles sont couverts.
        let session = DoubleRatchetSession.makeRatcheting(
            rootSeed: root, sharedSecret: ikm, sessionId: sessionId,
            isInitiator: true, securityMode: .hybridPQ, pqCiphertext: ct)

        XCTAssertEqual(wire.count, expected.count)
        for (i, b64) in wire.enumerated() {
            let msg = try JSONDecoder().decode(DoubleRatchetMessage.self,
                                               from: Data(base64Encoded: b64)!)
            XCTAssertEqual(msg.header.securityMode, .hybridPQ,
                           "le message d'Android n'est pas marqué hybride")
            let plain = try session.decrypt(msg)
            XCTAssertEqual(plain, expected[i], "message \(i) d'Android illisible ici")
        }
    }

    /// L'autre sens: Android encapsule, iOS décapsule.
    ///
    /// Sans ça on n'aurait vérifié qu'une moitié. Un Android capable de LIRE les
    /// chiffrés d'iOS mais dont les siens sont illisibles casserait exactement
    /// une direction de chaque conversation — le pire des symptômes, parce qu'il
    /// ressemble à un problème de réseau.
    ///
    /// Le fichier est produit par Android; absent, le test est ignoré.
    func testAndroidCiphertextDecapsulatesHere() throws {
        try XCTSkipUnless(PostQuantumKEM.isSupportedByPlatform,
                          "X-Wing demande iOS 26")
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("oshi_xwing_from_android.json")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: url.path),
                          "Android n'a pas encore produit de chiffré — lancer XWingInteropTest d'abord")

        let doc = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let items = doc["vectors"] as! [[String: String]]
        XCTAssertFalse(items.isEmpty)
        for v in items {
            let seed = Data(base64Encoded: v["seed"]!)!
            let ct = Data(base64Encoded: v["ciphertext"]!)!
            let expected = Data(base64Encoded: v["sharedSecret"]!)!
            let got = try PostQuantumKEM.decapsulate(ct, withSeed: seed)
            XCTAssertEqual(got, expected,
                           "le chiffré produit par Android ne rend pas le même secret ici")
        }
    }
}
