//
//  MediaDeliveryAcrossTransportsTests.swift
//  OSHITests
//
//  ARE MESSAGES, MEDIA AND FILES ACTUALLY DELIVERED AND DECRYPTED — on the
//  internet, over the mesh, and over LoRa?
//
//  Text was covered by `TransportRatchetSyncTests`. Media is a DIFFERENT path and
//  had to be checked separately: the bytes never travel inside the ratchet
//  message at all. They are encrypted once under a random file key, uploaded to
//  the blob store, and only the 32-byte FILE KEY rides the ratchet.
//
//  Which is exactly why this file exists now. CV-010 changed how the ratchet
//  derives its chains, so anything carried inside a ratchet message — the file
//  key above all — is at risk of a silent break that no text test would catch: a
//  photo that "sends" and then cannot be opened is far quieter than a message
//  that fails to decrypt.
//
//  Transport by transport, what is actually being asserted:
//
//    * internet / IPFS — full round trip, several sizes, key over a v3 ratchet
//    * mesh            — the group envelope carrying a media payload, including
//                        the per-recipient split
//    * LoRa            — media is REFUSED by design (`queueOrSendSecureMessage`),
//                        and that refusal must stay explicit rather than becoming
//                        a photo that silently never arrives
//
//  Nothing here touches production: no server, no radio, no relay, no blob store.
//

import XCTest
import CryptoKit
@testable import OSHI

final class MediaDeliveryAcrossTransportsTests: XCTestCase {

    private let sharedSecret = Data(repeating: 0x4E, count: 32)
    private let alice = "ALICEmediaALICEmediaALICEmediaALICEmed1a="
    private let bob   = "BOBmediaBOBmediaBOBmediaBOBmediaBOBmed2b="

    /// Two real peers on a ROTATING (v3) session — the one CV-010 introduced,
    /// and therefore the one a media regression would hide in.
    private func v3Pair() -> (a: DoubleRatchetSession, b: DoubleRatchetSession) {
        let m1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m1.resetAllSessions()
        let m2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m2.resetAllSessions()
        return (m1.getOrCreateSession(with: bob, ourPublicKey: alice,
                                      sharedSecret: sharedSecret, ratcheting: true),
                m2.getOrCreateSession(with: alice, ourPublicKey: bob,
                                      sharedSecret: sharedSecret, ratcheting: true))
    }

    private func v2Pair() -> (a: DoubleRatchetSession, b: DoubleRatchetSession) {
        let m1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m1.resetAllSessions()
        let m2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); m2.resetAllSessions()
        return (m1.getOrCreateSession(with: bob, ourPublicKey: alice, sharedSecret: sharedSecret),
                m2.getOrCreateSession(with: alice, ourPublicKey: bob, sharedSecret: sharedSecret))
    }

    /// A byte pattern that compresses badly and detects truncation — a run of
    /// zeros would round-trip even if half the chunks were dropped.
    private func payload(_ n: Int) -> Data {
        var d = Data(count: n)
        for i in 0..<n { d[i] = UInt8((i &* 31 &+ 7) & 0xFF) }
        return d
    }

    // MARK: - The file itself

    /// Every size that exercises a different branch: under one chunk, exactly
    /// one, and several — plus empty, which is the one that usually crashes.
    func testFilesOfEverySizeRoundTripExactly() throws {
        let chunk = 64 * 1024
        for size in [0, 1, 1024, chunk - 1, chunk, chunk + 1, 3 * chunk + 17] {
            let original = payload(size)
            let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: chunk)
            let (back, _) = try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                         fileNonce: enc.fileNonce,
                                                         chunks: enc.chunks,
                                                         manifest: enc.manifest)
            XCTAssertEqual(back, original, "un fichier de \(size) octets n'est pas revenu intact")
        }
    }

    /// The ciphertext must not be the plaintext — the check that catches an
    /// "encryption" path that quietly became a copy.
    func testTheStoredBytesAreNotThePlaintext() throws {
        let original = payload(50_000)
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 16 * 1024)
        let joined = enc.chunks.reduce(Data(), +)
        XCTAssertFalse(joined.contains(original.prefix(64)),
                       "le début du fichier apparaît en clair dans les chunks")
        XCTAssertEqual(enc.fileKey.count, 32)
    }

    /// A corrupted chunk must fail loudly. A media path that returns partial
    /// bytes shows the user a broken image and no error.
    func testACorruptedChunkIsRejectedRatherThanPartiallyDecoded() throws {
        let original = payload(40_000)
        let enc = try OSHICryptoV2.encryptFile(bytes: original, chunkSize: 8 * 1024)
        var chunks = enc.chunks
        XCTAssertGreaterThan(chunks.count, 1)
        chunks[1][chunks[1].startIndex] ^= 0x01

        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: chunks,
                                                          manifest: enc.manifest),
                             "un chunk corrompu a été accepté")
    }

    func testTheWrongFileKeyDecryptsNothing() throws {
        let enc = try OSHICryptoV2.encryptFile(bytes: payload(20_000))
        XCTAssertThrowsError(try OSHICryptoV2.decryptFile(fileKey: Data(repeating: 9, count: 32),
                                                          fileNonce: enc.fileNonce,
                                                          chunks: enc.chunks,
                                                          manifest: enc.manifest))
    }

    // MARK: - Internet: the file key over a ROTATING ratchet

    /// The end-to-end shape of a media send: the bytes go to the blob store, the
    /// FILE KEY travels inside the ratchet message, and the recipient uses it to
    /// open the blob. Here on a v3 session, because that is what CV-010 changed.
    func testAPhotoSentOverAV3SessionIsDecryptedByTheRecipient() throws {
        let (a, b) = v3Pair()
        let photo = payload(120_000)
        let enc = try OSHICryptoV2.encryptFile(bytes: photo, chunkSize: 32 * 1024)

        // What the sender puts on the wire: key material, not bytes.
        let keyPayload = try JSONSerialization.data(withJSONObject: [
            "fileKey": enc.fileKey.base64EncodedString(),
            "fileNonce": enc.fileNonce.base64EncodedString(),
            "manifest": enc.manifest.base64EncodedString()
        ])
        let ratchetMsg = try a.encrypt(keyPayload.base64EncodedString())

        // Receiver: open the ratchet message, recover the key, open the blob.
        let receivedB64 = try b.decrypt(ratchetMsg)
        let received = try XCTUnwrap(Data(base64Encoded: receivedB64))
        let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: received) as? [String: String])

        let (bytes, _) = try OSHICryptoV2.decryptFile(
            fileKey: try XCTUnwrap(Data(base64Encoded: fields["fileKey"]!)),
            fileNonce: try XCTUnwrap(Data(base64Encoded: fields["fileNonce"]!)),
            chunks: enc.chunks,
            manifest: try XCTUnwrap(Data(base64Encoded: fields["manifest"]!)))

        XCTAssertEqual(bytes, photo, "la photo n'est pas déchiffrée intacte à l'arrivée")
    }

    /// And the same on a v2 session, so the fix did not quietly break the format
    /// every existing conversation still uses.
    func testAPhotoSentOverAV2SessionStillWorks() throws {
        let (a, b) = v2Pair()
        let enc = try OSHICryptoV2.encryptFile(bytes: payload(60_000), chunkSize: 16 * 1024)
        let sent = try a.encrypt(enc.fileKey.base64EncodedString())
        XCTAssertEqual(try b.decrypt(sent), enc.fileKey.base64EncodedString())
    }

    /// Several attachments in a row, interleaved with text, out of order — a real
    /// gallery send over a mesh. Each key must come back attached to its own file.
    func testManyAttachmentsInterleavedWithTextAllArriveIntact() throws {
        let (a, b) = v3Pair()
        // Turn the ratchet first so this runs on a rotated chain.
        XCTAssertEqual(try b.decrypt(try a.encrypt("amorce")), "amorce")
        XCTAssertEqual(try a.decrypt(try b.encrypt("réponse")), "réponse")

        var files: [(Data, OSHICryptoV2.EncryptedFile, DoubleRatchetMessage)] = []
        for i in 0..<5 {
            let bytes = payload(10_000 + i * 3_000)
            let enc = try OSHICryptoV2.encryptFile(bytes: bytes, chunkSize: 8 * 1024)
            let msg = try a.encrypt(enc.fileKey.base64EncodedString())
            files.append((bytes, enc, msg))
            _ = try a.encrypt("légende \(i)")   // interleaved text, deliberately not delivered
        }

        // Delivered out of order, as a mesh would.
        for idx in [3, 0, 4, 1, 2] {
            let (bytes, enc, msg) = files[idx]
            let keyB64 = try b.decrypt(msg)
            let (back, _) = try OSHICryptoV2.decryptFile(
                fileKey: try XCTUnwrap(Data(base64Encoded: keyB64)),
                fileNonce: enc.fileNonce, chunks: enc.chunks, manifest: enc.manifest)
            XCTAssertEqual(back, bytes, "la pièce jointe \(idx) est corrompue après réordonnancement")
        }
    }

    // MARK: - Mesh: media inside a group envelope

    /// A group photo on the offline path: the media payload goes inside the v3
    /// group envelope, which is itself opened with a key wrapped per member.
    func testAGroupMediaPayloadIsReadableByEveryMember() throws {
        let members = ["MEM1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=",
                       "MEM2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=",
                       "MEM3aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="]
        var send: [String: DoubleRatchetSession] = [:]
        var recv: [String: DoubleRatchetSession] = [:]
        for m in members {
            let s1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); s1.resetAllSessions()
            let s2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); s2.resetAllSessions()
            send[m] = s1.getOrCreateSession(with: m, ourPublicKey: alice, sharedSecret: sharedSecret)
            recv[m] = s2.getOrCreateSession(with: alice, ourPublicKey: m, sharedSecret: sharedSecret)
        }

        let enc = try OSHICryptoV2.encryptFile(bytes: payload(80_000), chunkSize: 32 * 1024)
        let mediaPayload = try JSONSerialization.data(withJSONObject: [
            "type": "group_message",
            "mediaType": "photo",
            "fileKey": enc.fileKey.base64EncodedString()
        ])

        let sealed = try GroupEnvelopeV3.seal(payload: mediaPayload, members: members) { member, key in
            try JSONEncoder().encode(try send[member]!.encrypt(key))
        }

        for m in members {
            let opened = try GroupEnvelopeV3.open(sealed, asMember: m) { data in
                try recv[m]!.decrypt(try JSONDecoder().decode(DoubleRatchetMessage.self, from: data))
            }
            let fields = try XCTUnwrap(JSONSerialization.jsonObject(with: opened) as? [String: String])
            XCTAssertEqual(fields["fileKey"], enc.fileKey.base64EncodedString(),
                           "le membre \(m.prefix(4)) ne récupère pas la clé du média")
        }
    }

    /// And through the per-recipient split, which is what a larger group over a
    /// constrained transport now produces.
    func testGroupMediaSurvivesThePerRecipientSplit() throws {
        var members: [String] = []
        var send: [String: DoubleRatchetSession] = [:]
        var recv: [String: DoubleRatchetSession] = [:]
        for i in 0..<6 {
            let m = "SPLITMEM\(i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="
            members.append(m)
            let s1 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); s1.resetAllSessions()
            let s2 = DoubleRatchetSessionManager(storageKey: "test-ratchet-\(UUID().uuidString)"); s2.resetAllSessions()
            send[m] = s1.getOrCreateSession(with: m, ourPublicKey: alice, sharedSecret: sharedSecret)
            recv[m] = s2.getOrCreateSession(with: alice, ourPublicKey: m, sharedSecret: sharedSecret)
        }
        let enc = try OSHICryptoV2.encryptFile(bytes: payload(40_000))
        let mediaPayload = Data(enc.fileKey.base64EncodedString().utf8)

        let envelopes = try GroupEnvelopeV3.sealFitting(
            payload: mediaPayload, members: members,
            maxBytes: GroupEnvelopeV3.loRaMessageCeiling,
            encryptWrap: { member, key in try JSONEncoder().encode(try send[member]!.encrypt(key)) })

        XCTAssertEqual(envelopes.count, members.count)
        for (i, m) in members.enumerated() {
            let opened = try GroupEnvelopeV3.open(envelopes[i], asMember: m) { data in
                try recv[m]!.decrypt(try JSONDecoder().decode(DoubleRatchetMessage.self, from: data))
            }
            XCTAssertEqual(opened, mediaPayload, "le média du membre \(i) est illisible après découpe")
        }
    }

    // MARK: - LoRa: text only, and it must SAY so

    /// LoRa carries text and nothing else. That is a deliberate product decision,
    /// not a defect — the radio budget is 12 × 180 bytes — but the refusal has to
    /// stay explicit and reasoned, because "my photo won't send off-grid" is
    /// precisely when a person needs to be told why.
    func testLoRaRefusesMediaWithAStatedReasonRatherThanSilently() {
        // The refusal exists, is distinct from every other reason, and carries a
        // LOCALIZED message — the user has to be told, not just refused.
        let media = MeshtasticManager.SendRefusal.carriesMedia
        XCTAssertNotEqual(media, .notConnected)
        XCTAssertFalse(media.title.isEmpty,
                       "le refus « média » n'a plus de message: l'envoi photo hors-ligne redevient silencieux")
        XCTAssertNotEqual(media.title, "lora.blocked.media",
                          "la clé de traduction n'est pas résolue — l'utilisateur verrait la clé brute")
    }

    /// The text that DOES go by radio must still fit after everything the ratchet
    /// adds — this is the number `MessageTextSplitter` budgets against.
    func testLoRaTextBudgetStillFitsARatchetMessage() throws {
        let (a, _) = v3Pair()
        let maxText = String(repeating: "a", count: MessageTextSplitter.loRaSafeUTF8Bytes)
        let bytes = try JSONEncoder().encode(a.encrypt(maxText)).count
        XCTAssertLessThanOrEqual(bytes, 180 * 12,
                                 "un message texte à la limite du découpeur ne tient plus sur LoRa: \(bytes) octets")
    }
}
