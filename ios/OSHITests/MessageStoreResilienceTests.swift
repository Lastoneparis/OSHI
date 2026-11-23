//
//  MessageStoreResilienceTests.swift
//  OSHITests
//
//  __TOLERANT_MEDIA_TYPE_2026_09_12__ Ce que ces tests protègent: l'historique
//  entier de l'utilisateur.
//
//  `messages.json` est décodé comme UN SEUL tableau. Un champ enum qui refuse une
//  valeur inconnue ne perd donc pas un message — il fait échouer le décodage du
//  TABLEAU. `loadMessages` repart alors sur une liste vide, et le prochain
//  `saveMessages()` réécrit ce vide par-dessus le fichier. Un seul message venu
//  d'un pair plus récent, de l'autre plateforme, ou de la fusion de récupération
//  `archived_messages.json`, et tout l'historique disparaît sans une seule erreur
//  visible.
//
//  Ce piège a déjà été désamorcé deux fois — `deliveryMethod`, puis
//  `deliveryStatus` — et `mediaType` était le dernier trou. Ces tests existent
//  pour qu'il ne se rouvre pas une troisième fois.
//
//  NOTE SUR LA COUVERTURE. Les autres correctifs de la même passe d'audit ont
//  depuis reçu leurs propres fichiers, après avoir élargi `private` → `internal`
//  là où il le fallait (inerte à l'exécution):
//    - `MessageManager.retryFailedMessages()`  → MessageRetryQueueTests
//    - `PushRetryQueue.drain()`                → PushRetryQueueTests
//    - `CrossPlatformMesh.noteSeenMessageId()` → MeshSeenSetTests
//  Reste NON couvert: `ScheduledMessageManager.saveMessages()` (écriture non
//  atomique). Son fichier porte encore une modification non commitée d'une autre
//  session de travail; le correctif y a été posé seul, sans toucher au reste.
//

import XCTest
@testable import OSHI

@MainActor
final class MessageStoreResilienceTests: XCTestCase {

    /// Date fixe: un test qui dépend de l'horloge ment tôt ou tard.
    private let fixedDate = Date(timeIntervalSince1970: 1_700_000_000)

    private func makeMessage(id: String, mediaType: MediaManager.MediaType? = nil) -> SecureMessage {
        SecureMessage(
            id: id,
            senderAddress: "alice",
            recipientAddress: "bob",
            encryptedContent: EncryptedMessage(
                ciphertext: "ct-\(id)",
                signature: "sig-\(id)",
                senderPublicKey: "spk",
                timestamp: fixedDate
            ),
            timestamp: fixedDate,
            isRead: false,
            deliveryStatus: .sent,
            plaintextContent: "body-\(id)",
            mediaType: mediaType
        )
    }

    /// Remplace la valeur de `mediaType` dans le JSON encodé.
    ///
    /// L'assertion sur la présence du motif n'est pas décorative: si le format de
    /// l'encodeur change, le remplacement ne s'appliquerait plus et TOUS les tests
    /// ci-dessous passeraient à vide, en vert, sans rien vérifier.
    private func rewritingMediaType(_ json: Data, from: String, to: String) -> Data {
        let text = String(decoding: json, as: UTF8.self)
        let needle = "\"mediaType\":\"\(from)\""
        XCTAssertTrue(
            text.contains(needle),
            "Le format de l'encodeur a changé: le motif \(needle) est introuvable. "
            + "Sans lui ces tests passeraient à vide."
        )
        return Data(text.replacingOccurrences(of: needle, with: "\"mediaType\":\"\(to)\"").utf8)
    }

    // MARK: - Le message survit à un type de média inconnu

    /// Un `mediaType` que ce build ne connaît pas doit dégrader le CHAMP, pas
    /// emporter le message. L'étiquette ne vaut rien; le message vaut tout.
    func testUnknownMediaTypeDegradesTheFieldAndKeepsTheMessage() throws {
        let encoded = try JSONEncoder().encode([makeMessage(id: "m1", mediaType: .image)])
        let corrupted = rewritingMediaType(encoded, from: "image", to: "hologram")

        let decoded = try JSONDecoder().decode([SecureMessage].self, from: corrupted)

        XCTAssertEqual(decoded.count, 1, "le message doit survivre à une étiquette inconnue")
        XCTAssertNil(decoded[0].mediaType, "le champ inconnu doit tomber à nil")
        XCTAssertEqual(decoded[0].plaintextContent, "body-m1", "le corps doit être intact")
        XCTAssertEqual(decoded[0].id, "m1")
    }

    // MARK: - Le tableau entier survit, c'est tout l'enjeu

    /// LE test de régression. Un seul message porteur d'un type inconnu ne doit
    /// pas empêcher les AUTRES de se décoder: c'est ce décodage tout-ou-rien qui
    /// transformait une étiquette inconnue en perte totale de l'historique.
    func testOneUnknownMediaTypeDoesNotDestroyTheWholeStore() throws {
        let store = [
            makeMessage(id: "m1"),
            makeMessage(id: "m2", mediaType: .image),   // celui qu'on corrompt
            makeMessage(id: "m3")
        ]
        let encoded = try JSONEncoder().encode(store)
        let corrupted = rewritingMediaType(encoded, from: "image", to: "hologram")

        let decoded = try JSONDecoder().decode([SecureMessage].self, from: corrupted)

        XCTAssertEqual(
            decoded.map(\.id), ["m1", "m2", "m3"],
            "les trois messages doivent être là, dans l'ordre — un seul champ inconnu "
            + "ne doit jamais faire échouer le tableau"
        )
        XCTAssertNil(decoded[1].mediaType)
        XCTAssertEqual(decoded[1].plaintextContent, "body-m2")
        // Les voisins ne doivent pas être abîmés au passage.
        XCTAssertEqual(decoded[0].plaintextContent, "body-m1")
        XCTAssertEqual(decoded[2].plaintextContent, "body-m3")
    }

    // MARK: - Mais la tolérance ne doit pas tout avaler

    /// Le correctif ne doit pas être une passoire: une valeur VALIDE doit toujours
    /// se décoder dans le bon cas. Sans ce test, remplacer le champ par un `nil`
    /// systématique passerait les tests précédents.
    func testKnownMediaTypesStillDecodeExactly() throws {
        let all: [MediaManager.MediaType] = [.image, .video, .audio, .document, .contact]
        for kind in all {
            let encoded = try JSONEncoder().encode([makeMessage(id: "k", mediaType: kind)])
            let decoded = try JSONDecoder().decode([SecureMessage].self, from: encoded)
            XCTAssertEqual(decoded.first?.mediaType, kind, "\(kind) doit se décoder tel quel")
        }
    }

    /// Un message SANS média reste un message sans média — le champ absent ne doit
    /// pas se mettre à porter une valeur.
    func testAbsentMediaTypeStaysNil() throws {
        let encoded = try JSONEncoder().encode([makeMessage(id: "plain")])
        let decoded = try JSONDecoder().decode([SecureMessage].self, from: encoded)
        XCTAssertNil(decoded.first?.mediaType)
    }

    // MARK: - L'identité sur laquelle repose la file de rappels push

    /// `PushRetryQueue.drain()` ne réécrit plus la file en bloc: il conserve ce qui
    /// est arrivé pendant son `await`, en filtrant sur `dedupKey`. Ce filtre n'est
    /// correct que si `dedupKey` identifie bien UN message et pas seulement un
    /// destinataire — sinon un nouveau push serait pris pour un doublon et jeté.
    func testPushRetryDedupKeyIdentifiesTheMessageNotJustTheRecipient() {
        func entry(messageId: String, recipient: String = "peer-A") -> PushRetryQueue.Entry {
            PushRetryQueue.Entry(
                recipientPublicKey: recipient,
                type: "message",
                title: "t",
                body: "b",
                additionalData: ["messageId": messageId],
                attempts: 0,
                firstQueuedAt: fixedDate,
                lastAttemptAt: nil
            )
        }

        XCTAssertNotEqual(
            entry(messageId: "m1").dedupKey, entry(messageId: "m2").dedupKey,
            "deux messages distincts vers le même pair doivent avoir des clés distinctes, "
            + "sinon le second push est jeté comme doublon"
        )
        XCTAssertEqual(
            entry(messageId: "m1").dedupKey, entry(messageId: "m1").dedupKey,
            "la clé doit être stable pour un même message"
        )
        XCTAssertNotEqual(
            entry(messageId: "m1", recipient: "peer-A").dedupKey,
            entry(messageId: "m1", recipient: "peer-B").dedupKey,
            "le même message vers deux pairs (envoi de groupe) reste deux pushs"
        )
    }
}
