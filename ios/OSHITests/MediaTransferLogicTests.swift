//
//  MediaTransferLogicTests.swift
//  OSHITests
//
//  Unit tests for the headlessly-verifiable parts of the CONSOLIDATED media
//  transfer path (see docs/MEDIA_TRANSFER_REFERENCE.md):
//
//    • V2SendResumeStore   — the send-resume ledger: plaintext-hash binding, the
//                            fallback-to-fresh-key path, the per-recipient
//                            `fanoutId` used by the group fan-out, expiry-adjacent
//                            behaviour and the kill switch.
//    • OSHICryptoV2        — the determinism the whole resume story rests on:
//                            reusing (fileKey, fileNonce) on the SAME plaintext
//                            reproduces byte-identical chunks, and a HALF-reused
//                            pair is refused outright.
//    • V2Client            — the missing-set arithmetic that decides "top up three
//                            chunks" vs "re-upload the whole file".
//    • V2FileKeyMessage    — the group-media envelope payload, including that a
//                            payload written before `groupMessage` existed still
//                            decodes.
//    • GroupMessage        — the strip/restore round trip that lets group media
//                            reuse the group TEXT delivery funnel unchanged.
//
//  All deterministic, no network, no device. Nothing here proves a byte moved —
//  see §6 of the reference doc for what only two real phones can establish.
//
//  ⚠️ These tests write the real `oshi_v2_send_resume.json` in the test host's
//  Application Support directory. Every test therefore uses a UUID-unique msgId
//  and removes its own records, so a parallel run cannot see another's state.
//

import XCTest
import CryptoKit
@testable import OSHI

// ============================================================================
// V2SendResumeStore — the ledger
// ============================================================================
final class V2SendResumeStoreTests: XCTestCase {

    private var ids: [String] = []

    /// A unique message id, remembered so tearDown can clean the on-disk ledger.
    private func freshId() -> String {
        let id = "test-\(UUID().uuidString)"
        ids.append(id)
        return id
    }

    /// 🔴 FIX (2026-08-21) — NETTOYER À L'ENTRÉE, PAS SEULEMENT À LA SORTIE.
    ///
    /// Cette classe n'avait qu'un `tearDown`. Or un `tearDown` ne s'exécute pas
    /// quand le processus MEURT — et `oshi_v2_send_resume` vit dans UserDefaults,
    /// qui survit à la mort du processus.
    ///
    /// Constaté le 2026-08-21: sur une machine chargée, un test a dépassé son
    /// délai, XCTest a relancé le runner (« Restarting after unexpected exit,
    /// crash, or test timeout »), et six tests de cette classe ont échoué en
    /// bloc sur `XCTAssertNotNil failed`. Le coupable n'était pas le code testé:
    /// `testDisabledFlag` avait posé la clé à `false` et est mort avant de la
    /// retirer. Au relancement, `V2SendResumeStore.enabled` valait donc `false`,
    /// et un magasin désactivé ne rend évidemment rien.
    ///
    /// Un nettoyage qui n'existe qu'en sortie est indiscernable d'une absence de
    /// nettoyage dès que quelque chose tourne mal — et c'est précisément quand
    /// les choses tournent mal qu'on a besoin qu'il ait eu lieu.
    ///
    /// Le même raisonnement vaut pour le registre sur disque: les ids d'un run
    /// mort ne sont plus dans `ids`, donc plus personne ne les supprimera. On
    /// repart d'un état connu à chaque test.
    override func setUpWithError() throws {
        try super.setUpWithError()
        UserDefaults.standard.removeObject(forKey: V2SendResumeStore.defaultsKey)
        V2SendResumeStore.removeAllForTesting()
        ids.removeAll()

        // 🔴 (2026-08-21) Ce magasin écrit avec `.completeFileProtection`: le
        // fichier est ILLISIBLE tant que la session est verrouillée. Sur une
        // session verrouillée, `save()` échoue, `try?` avale l'erreur, et les six
        // tests de cette classe tombent en bloc sur « rien ne revient » — en
        // accusant le code alors que c'est l'environnement.
        //
        // Constaté: verts à 9 h, rouges à 18 h, sans un changement de ligne entre
        // les deux; le Mac avait verrouillé son écran. Les logs du run le disaient
        // déjà, ailleurs: « bundle publish failed: locked ».
        //
        // On ne saute donc PAS sur la plateforme — ce serait masquer une vraie
        // régression pour toujours — mais sur la CAPACITÉ, mesurée ici et
        // maintenant par une écriture témoin avec la même protection. Si elle
        // passe, les tests tournent.
        let canary = FileManager.default.temporaryDirectory
            .appendingPathComponent("oshi-fileprotection-canary-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: canary) }
        do {
            try Data([0x01]).write(to: canary, options: [.atomic, .completeFileProtection])
        } catch {
            throw XCTSkip("Session verrouillée: `.completeFileProtection` empêche toute écriture, "
                          + "donc le magasin de reprise ne peut pas être exercé ici (\(error)).")
        }
    }

    override func tearDown() {
        for id in ids { V2SendResumeStore.remove(msgId: id) }
        ids.removeAll()
        UserDefaults.standard.removeObject(forKey: V2SendResumeStore.defaultsKey)
        super.tearDown()
    }

    private let key = Data(repeating: 0xAB, count: 32)
    private let nonce = Data(repeating: 0xCD, count: 8)

    // ---- the happy path ---------------------------------------------------

    func testRecordRoundTripsForTheSamePlaintext() {
        let id = freshId()
        let bytes = Data("the quick brown fox".utf8)
        let digest = V2SendResumeStore.digest(bytes)

        V2SendResumeStore.put(msgId: id, recipientKey: "peerA", plaintextSHA256: digest,
                              blobId: "blob-1", fileKey: key, fileNonce: nonce, chunkCount: 3)

        let r = V2SendResumeStore.record(for: id, recipientKey: "peerA", plaintextSHA256: digest)
        XCTAssertNotNil(r, "a record written for these exact bytes must come back")
        XCTAssertEqual(r?.blobId, "blob-1")
        XCTAssertEqual(r?.chunkCount, 3)
        XCTAssertEqual(Data(base64Encoded: r?.fileKeyB64 ?? ""), key)
        XCTAssertEqual(Data(base64Encoded: r?.fileNonceB64 ?? ""), nonce)
    }

    func testDigestIsStableAndPlaintextSensitive() {
        let a = Data("payload".utf8)
        XCTAssertEqual(V2SendResumeStore.digest(a), V2SendResumeStore.digest(a))
        XCTAssertNotEqual(V2SendResumeStore.digest(a), V2SendResumeStore.digest(Data("payloae".utf8)),
                          "a one-byte change must change the binding digest")
        // It really is SHA-256, lowercase hex — the same shape the doc quotes.
        let expected = SHA256.hash(data: a).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(V2SendResumeStore.digest(a), expected)
        XCTAssertEqual(V2SendResumeStore.digest(a).count, 64)
    }

    // ---- THE SAFETY INVARIANT --------------------------------------------
    // Reusing an AES-GCM (key, nonce) across DIFFERENT plaintexts is a total break.
    // The ledger must refuse, and the refusal must look like "no resume".

    func testDifferentPlaintextUnderTheSameMessageIdRefusesKeyMaterial() {
        let id = freshId()
        let original = Data(repeating: 0x01, count: 4096)
        let replacement = Data(repeating: 0x02, count: 4096)

        V2SendResumeStore.put(msgId: id, recipientKey: "peerA",
                              plaintextSHA256: V2SendResumeStore.digest(original),
                              blobId: "blob-1", fileKey: key, fileNonce: nonce, chunkCount: 1)

        let r = V2SendResumeStore.record(for: id, recipientKey: "peerA",
                                         plaintextSHA256: V2SendResumeStore.digest(replacement))
        XCTAssertNil(r, "the digest binding must refuse to hand key material to different bytes")

        // And the poisoned record must be GONE, not left to be re-offered.
        let again = V2SendResumeStore.record(for: id, recipientKey: "peerA",
                                             plaintextSHA256: V2SendResumeStore.digest(original))
        XCTAssertNil(again, "a mismatched record is dropped, so even the original bytes start fresh")
    }

    func testRecipientMismatchYieldsNoResume() {
        let id = freshId()
        let bytes = Data("hello".utf8)
        let digest = V2SendResumeStore.digest(bytes)
        V2SendResumeStore.put(msgId: id, recipientKey: "peerA", plaintextSHA256: digest,
                              blobId: "blob-1", fileKey: key, fileNonce: nonce, chunkCount: 1)

        XCTAssertNil(V2SendResumeStore.record(for: id, recipientKey: "peerB", plaintextSHA256: digest),
                     "a blob reserved for peerA must never be resumed into for peerB")
        // Non-destructive: peerA can still resume.
        XCTAssertNotNil(V2SendResumeStore.record(for: id, recipientKey: "peerA", plaintextSHA256: digest))
    }

    func testMissingRecordYieldsNoResume() {
        XCTAssertNil(V2SendResumeStore.record(for: "test-never-written-\(UUID().uuidString)",
                                              recipientKey: "peerA",
                                              plaintextSHA256: V2SendResumeStore.digest(Data([0x00]))))
    }

    func testRemoveIsIdempotentAndActuallyRemoves() {
        let id = freshId()
        let digest = V2SendResumeStore.digest(Data("x".utf8))
        V2SendResumeStore.put(msgId: id, recipientKey: "peerA", plaintextSHA256: digest,
                              blobId: "blob-1", fileKey: key, fileNonce: nonce, chunkCount: 1)
        V2SendResumeStore.remove(msgId: id)
        V2SendResumeStore.remove(msgId: id)   // must not crash or resurrect
        XCTAssertNil(V2SendResumeStore.record(for: id, recipientKey: "peerA", plaintextSHA256: digest))
    }

    // ---- the kill switch --------------------------------------------------

    func testKillSwitchRestoresStartFromZeroBehaviour() {
        let id = freshId()
        let digest = V2SendResumeStore.digest(Data("x".utf8))
        V2SendResumeStore.put(msgId: id, recipientKey: "peerA", plaintextSHA256: digest,
                              blobId: "blob-1", fileKey: key, fileNonce: nonce, chunkCount: 1)

        UserDefaults.standard.set(false, forKey: V2SendResumeStore.defaultsKey)
        XCTAssertFalse(V2SendResumeStore.enabled)
        XCTAssertNil(V2SendResumeStore.record(for: id, recipientKey: "peerA", plaintextSHA256: digest),
                     "with the flag off every send must start from byte zero")

        // put() is a no-op too, so no key material accumulates while disabled.
        let id2 = freshId()
        V2SendResumeStore.put(msgId: id2, recipientKey: "peerA", plaintextSHA256: digest,
                              blobId: "blob-2", fileKey: key, fileNonce: nonce, chunkCount: 1)
        UserDefaults.standard.removeObject(forKey: V2SendResumeStore.defaultsKey)
        XCTAssertTrue(V2SendResumeStore.enabled, "absent key must default ON")
        XCTAssertNil(V2SendResumeStore.record(for: id2, recipientKey: "peerA", plaintextSHA256: digest),
                     "nothing written while disabled")
    }

    // ---- the GROUP fan-out ledger ----------------------------------------

    func testFanoutIdIsPerRecipientAndCannotCollideWithA1to1Record() {
        let msgId = "m-1"
        let a = V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: "peerA")
        let b = V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: "peerB")
        XCTAssertNotEqual(a, b)
        XCTAssertNotEqual(a, msgId, "the group key must not be the bare 1:1 key")
        XCTAssertTrue(a.hasPrefix(msgId + "#"))
    }

    /// The bookkeeping the group fan-out actually depends on: ONE file key shared
    /// by every member, a DISTINCT blobId per member, and a partial interruption
    /// that leaves some members with a blob and others without.
    func testGroupFanoutKeepsOneKeyAndOneBlobPerMember() {
        let msgId = "test-group-\(UUID().uuidString)"
        let members = ["peerA", "peerB", "peerC"]
        let bytes = Data(repeating: 0x7E, count: 8192)
        let digest = V2SendResumeStore.digest(bytes)
        let ledgerIds = members.map { V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: $0) }
        ids.append(contentsOf: ledgerIds)

        // Reserve for every member — one key, three blobs.
        for (i, member) in members.enumerated() {
            V2SendResumeStore.put(msgId: ledgerIds[i], recipientKey: member, plaintextSHA256: digest,
                                  blobId: "blob-\(member)", fileKey: key, fileNonce: nonce, chunkCount: 4)
        }

        var blobIds: [String] = []
        var keys: Set<String> = []
        for (i, member) in members.enumerated() {
            let r = V2SendResumeStore.record(for: ledgerIds[i], recipientKey: member, plaintextSHA256: digest)
            XCTAssertNotNil(r, "\(member) must have its own resume point")
            blobIds.append(r!.blobId)
            keys.insert(r!.fileKeyB64)
        }
        XCTAssertEqual(Set(blobIds).count, 3, "one blob per member — the server authorises a single recipient")
        XCTAssertEqual(keys.count, 1, "one file key for the whole fan-out — the ciphertext is identical")

        // Interruption: peerB's send completed and its record was dropped. The
        // remaining members must still resume, and peerB must reserve fresh.
        V2SendResumeStore.remove(msgId: ledgerIds[1])
        XCTAssertNotNil(V2SendResumeStore.record(for: ledgerIds[0], recipientKey: "peerA", plaintextSHA256: digest))
        XCTAssertNil(V2SendResumeStore.record(for: ledgerIds[1], recipientKey: "peerB", plaintextSHA256: digest))
        XCTAssertNotNil(V2SendResumeStore.record(for: ledgerIds[2], recipientKey: "peerC", plaintextSHA256: digest))

        // And the "first valid record supplies the key for the whole fan-out"
        // lookup the router performs still finds it.
        var recovered: Data?
        for (i, member) in members.enumerated() {
            guard let r = V2SendResumeStore.record(for: ledgerIds[i], recipientKey: member,
                                                   plaintextSHA256: digest) else { continue }
            if recovered == nil { recovered = Data(base64Encoded: r.fileKeyB64) }
        }
        XCTAssertEqual(recovered, key)
    }

    func testGroupFanoutRefusesToCrossRecipients() {
        let msgId = "test-group-\(UUID().uuidString)"
        let digest = V2SendResumeStore.digest(Data("g".utf8))
        let idA = V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: "peerA")
        ids.append(idA)
        V2SendResumeStore.put(msgId: idA, recipientKey: "peerA", plaintextSHA256: digest,
                              blobId: "blob-A", fileKey: key, fileNonce: nonce, chunkCount: 2)
        // peerB's ledger id has nothing; and peerA's record is not reachable under
        // peerB's identity even if the id were guessed.
        XCTAssertNil(V2SendResumeStore.record(for: V2SendResumeStore.fanoutId(msgId: msgId, recipientKey: "peerB"),
                                              recipientKey: "peerB", plaintextSHA256: digest))
        XCTAssertNil(V2SendResumeStore.record(for: idA, recipientKey: "peerB", plaintextSHA256: digest))
    }
}

// ============================================================================
// OSHICryptoV2 — the determinism resume rests on
// ============================================================================
final class V2ResumeDeterminismTests: XCTestCase {

    /// If this ever stops holding, resume silently produces a blob whose chunks
    /// disagree with the ones already on the server and `commit` never succeeds.
    func testReusedKeyMaterialReproducesByteIdenticalChunks() throws {
        let bytes = Data((0..<(5 * 1024 * 1024)).map { UInt8($0 % 251) })
        let meta = OSHICryptoV2.FileMeta(filename: "clip.mp4", mime: "video/mp4")

        let first = try OSHICryptoV2.encryptFile(bytes: bytes, meta: meta)
        XCTAssertGreaterThan(first.chunks.count, 1, "the fixture must actually span several chunks")

        let second = try OSHICryptoV2.encryptFile(bytes: bytes, meta: meta,
                                                  reuseFileKey: first.fileKey,
                                                  reuseFileNonce: first.fileNonce)
        XCTAssertEqual(first.chunks, second.chunks,
                       "same key + same nonce + same plaintext must reproduce identical ciphertext")
        XCTAssertEqual(first.manifest, second.manifest)
        XCTAssertEqual(first.chunks.count, second.chunks.count)
    }

    func testFreshKeyMaterialProducesDifferentCiphertext() throws {
        let bytes = Data(repeating: 0x11, count: 64 * 1024)
        let a = try OSHICryptoV2.encryptFile(bytes: bytes)
        let b = try OSHICryptoV2.encryptFile(bytes: bytes)
        XCTAssertNotEqual(a.fileKey, b.fileKey)
        XCTAssertNotEqual(a.chunks, b.chunks,
                          "without the ledger, every retry uploads brand-new bytes — that is the bug it fixes")
    }

    func testHalfReusedKeyMaterialIsRefused() {
        let bytes = Data(repeating: 0x22, count: 1024)
        XCTAssertThrowsError(try OSHICryptoV2.encryptFile(bytes: bytes,
                                                          reuseFileKey: Data(repeating: 0xAB, count: 32),
                                                          reuseFileNonce: nil),
                             "a reused key with a fresh nonce must be rejected, not silently accepted")
        XCTAssertThrowsError(try OSHICryptoV2.encryptFile(bytes: bytes,
                                                          reuseFileKey: nil,
                                                          reuseFileNonce: Data(repeating: 0xCD, count: 8)))
    }

    /// The full round trip a group member performs: one key, one ciphertext, and
    /// every member decrypts the SAME bytes out of their OWN blob.
    func testOneKeyDecryptsTheSharedCiphertextForEveryMember() throws {
        let bytes = Data((0..<200_000).map { UInt8($0 % 97) })
        let enc = try OSHICryptoV2.encryptFile(bytes: bytes,
                                               meta: OSHICryptoV2.FileMeta(filename: "p.jpg", mime: "image/jpeg"))
        // Three members, three blobs, one set of bytes copied into each.
        for _ in 0..<3 {
            let (out, info) = try OSHICryptoV2.decryptFile(fileKey: enc.fileKey,
                                                          fileNonce: enc.fileNonce,
                                                          chunks: enc.chunks,
                                                          manifest: enc.manifest)
            XCTAssertEqual(out, bytes)
            XCTAssertEqual(info["filename"] as? String, "p.jpg")
        }
    }
}

// ============================================================================
// V2Client — missing-set arithmetic
// ============================================================================
final class V2MissingSetTests: XCTestCase {

    func testEmptyMissingSetMeansNothingToUpload() {
        XCTAssertEqual(V2Client.chunksToUpload(missing: [], chunkCount: 40), [])
    }

    func testFreshReservationUploadsEverything() {
        let all = Array(0..<40)
        XCTAssertEqual(V2Client.chunksToUpload(missing: all, chunkCount: 40), all)
    }

    /// The point of the whole ledger: a resume tops up a SHORT set, not the file.
    func testResumeUploadsOnlyTheShortMissingSet() {
        XCTAssertEqual(V2Client.chunksToUpload(missing: [37, 38, 39], chunkCount: 40), [37, 38, 39])
    }

    func testOutOfRangeIndicesAreDropped() {
        XCTAssertEqual(V2Client.chunksToUpload(missing: [-1, 0, 3, 40, 999], chunkCount: 4), [0, 3])
    }

    func testOrderIsPreservedAsTheServerReportedIt() {
        XCTAssertEqual(V2Client.chunksToUpload(missing: [5, 1, 3], chunkCount: 8), [5, 1, 3])
    }

    func testResumeIsUsableAcceptsAnInRangeOrEmptySet() {
        XCTAssertTrue(V2Client.resumeIsUsable(missing: [], expectedChunkCount: 10))
        XCTAssertTrue(V2Client.resumeIsUsable(missing: [0, 9], expectedChunkCount: 10))
    }

    func testResumeIsUsableRejectsARecordDescribingDifferentContent() {
        // Blob reserved for 40 chunks, this attempt only has 10 → wrong content.
        XCTAssertFalse(V2Client.resumeIsUsable(missing: [10], expectedChunkCount: 10))
        XCTAssertFalse(V2Client.resumeIsUsable(missing: [0, 1, 39], expectedChunkCount: 10))
        XCTAssertFalse(V2Client.resumeIsUsable(missing: [-1], expectedChunkCount: 10))
    }

    /// Guard the boundary explicitly: index == chunkCount is OUT of range.
    func testChunkCountIsExclusive() {
        XCTAssertEqual(V2Client.chunksToUpload(missing: [9, 10], chunkCount: 10), [9])
        XCTAssertFalse(V2Client.resumeIsUsable(missing: [10], expectedChunkCount: 10))
    }
}

// ============================================================================
// V2FileKeyMessage — the group-media envelope payload
// ============================================================================
final class V2FileKeyMessageTests: XCTestCase {

    private func sample(groupMessage: String?) -> V2FileKeyMessage {
        V2FileKeyMessage(kind: V2FileKeyMessage.kindValue,
                         blobId: "b-1", fileKey: "AAAA", fileNonce: "BBBB",
                         chunkCount: 7, manifest: "CCCC",
                         filename: "photo.jpg", mime: "image/jpeg",
                         mediaType: "photo", isViewOnce: nil,
                         groupMessage: groupMessage)
    }

    func testRoundTripsWithAnEmbeddedGroupMessage() throws {
        let msg = sample(groupMessage: "Z3JvdXA=")
        let decoded = try JSONDecoder().decode(V2FileKeyMessage.self,
                                               from: try JSONEncoder().encode(msg))
        XCTAssertEqual(decoded, msg)
        XCTAssertEqual(decoded.groupMessage, "Z3JvdXA=")
    }

    func testRoundTripsWithoutOne() throws {
        let msg = sample(groupMessage: nil)
        let decoded = try JSONDecoder().decode(V2FileKeyMessage.self,
                                               from: try JSONEncoder().encode(msg))
        XCTAssertNil(decoded.groupMessage, "a 1:1 file must not carry a group payload")
    }

    /// A payload produced BEFORE `groupMessage` existed must still decode — an
    /// in-flight blob key message from an older sender must not become undecodable
    /// (it would surface as `.failed` and the media would be lost).
    func testLegacyPayloadWithoutTheGroupMessageKeyStillDecodes() throws {
        let legacy = """
        {"kind":"v2file","blobId":"b-1","fileKey":"AAAA","fileNonce":"BBBB",\
        "chunkCount":7,"manifest":"CCCC","filename":"photo.jpg","mime":"image/jpeg"}
        """
        let decoded = try JSONDecoder().decode(V2FileKeyMessage.self, from: Data(legacy.utf8))
        XCTAssertEqual(decoded.kind, V2FileKeyMessage.kindValue)
        XCTAssertEqual(decoded.blobId, "b-1")
        XCTAssertNil(decoded.groupMessage)
        XCTAssertNil(decoded.mediaType)
        XCTAssertNil(decoded.isViewOnce)
    }

    func testKindDiscriminatorIsWhatTheParserBranchesOn() {
        XCTAssertEqual(V2FileKeyMessage.kindValue, "v2file")
    }
}

// ============================================================================
// GroupMessage — strip on send, restore on receive
// ============================================================================
final class GroupMediaEnvelopeTests: XCTestCase {

    /// Build the message `sendMessage(to:content:…mediaData:)` builds for media:
    /// the bytes inlined as base64 JSON in `encryptedContent`.
    private func outgoingMediaMessage(bytes: Data, caption: String) -> GroupMessage {
        let json: [String: Any] = [
            "type": "media",
            "mediaType": MediaType.photo.rawValue,
            "content": bytes.base64EncodedString(),
            "size": bytes.count,
            "fileName": "holiday.jpg",
        ]
        let inline = try! JSONSerialization.data(withJSONObject: json)
        return GroupMessage(id: UUID(),
                            groupId: UUID(),
                            senderPublicKey: "senderKeyBase64==",
                            encryptedContent: inline,
                            timestamp: Date(timeIntervalSince1970: 1_785_000_000),
                            isRead: true,
                            messageChainIndex: 4,
                            mediaType: .photo,
                            mediaFileName: "holiday.jpg",
                            plaintextContent: caption,
                            deliveryStatus: .pending)
    }

    /// What the sender ships inside the ratchet payload, and what the receiver
    /// rebuilds. The media must travel ONCE (over the blob store), and everything
    /// the group funnel dedups and renders on must survive.
    func testStripAndRestorePreservesIdentityAndDropsTheInlineCopy() throws {
        let bytes = Data((0..<50_000).map { UInt8($0 % 253) })
        let original = outgoingMediaMessage(bytes: bytes, caption: "on the beach")
        XCTAssertFalse(original.encryptedContent.isEmpty)

        // --- sender side: strip ---
        var stripped = original
        stripped.encryptedContent = Data()
        stripped.mediaData = nil
        let wire = try JSONEncoder().encode(stripped).base64EncodedString()

        // The inline copy really is gone: the base64 of the media must not appear.
        XCTAssertFalse(Data(base64Encoded: wire).flatMap { String(data: $0, encoding: .utf8) }!
                        .contains(bytes.prefix(64).base64EncodedString()),
                       "the media must not ride the relay as well as the blob store")

        // --- receiver side: restore ---
        var rebuilt = try JSONDecoder().decode(GroupMessage.self,
                                               from: Data(base64Encoded: wire)!)
        rebuilt.mediaData = bytes

        XCTAssertEqual(rebuilt.id, original.id, "id-dedup across mesh/relay depends on this")
        XCTAssertEqual(rebuilt.groupId, original.groupId)
        XCTAssertEqual(rebuilt.senderPublicKey, original.senderPublicKey)
        XCTAssertEqual(rebuilt.timestamp.timeIntervalSince1970,
                       original.timestamp.timeIntervalSince1970, accuracy: 0.001)
        XCTAssertEqual(rebuilt.messageChainIndex, 4)
        XCTAssertEqual(rebuilt.mediaType, .photo)
        XCTAssertEqual(rebuilt.mediaFileName, "holiday.jpg")
        XCTAssertEqual(rebuilt.plaintextContent, "on the beach")
        XCTAssertTrue(rebuilt.encryptedContent.isEmpty)
        XCTAssertEqual(rebuilt.mediaData, bytes,
                       "receiveGroupMessage takes the directMedia branch on this field")
    }

    /// `mediaData` is excluded from CodingKeys — the reason the old
    /// `message.mediaData == nil` gate was always true and group media was riding
    /// the relay as text. Pin it so nobody "fixes" it by adding the key back.
    func testMediaDataIsNeverEncoded() throws {
        var m = outgoingMediaMessage(bytes: Data(repeating: 0xFF, count: 1024), caption: "")
        m.mediaData = Data(repeating: 0xFF, count: 1024)
        let json = String(data: try JSONEncoder().encode(m), encoding: .utf8)!
        XCTAssertFalse(json.contains("mediaData"),
                       "mediaData is stored on disk, never serialised into the wire payload")
    }

    func testMimeAndFilenameMappingCoversEveryGroupMediaType() {
        XCTAssertEqual(MessageGroupManager.v2Mime(for: .photo), "image/jpeg")
        XCTAssertEqual(MessageGroupManager.v2Mime(for: .video), "video/mp4")
        XCTAssertEqual(MessageGroupManager.v2Mime(for: .audio), "audio/m4a")
        XCTAssertEqual(MessageGroupManager.v2Mime(for: .contact), "text/vcard")
        XCTAssertEqual(MessageGroupManager.v2Mime(for: .document), "application/octet-stream")

        XCTAssertEqual(MessageGroupManager.v2DefaultFileName(for: .photo), "photo.jpg")
        XCTAssertEqual(MessageGroupManager.v2DefaultFileName(for: .video), "video.mp4")
        XCTAssertEqual(MessageGroupManager.v2DefaultFileName(for: .audio), "audio.m4a")
        XCTAssertEqual(MessageGroupManager.v2DefaultFileName(for: .contact), "contact.vcf")
        XCTAssertEqual(MessageGroupManager.v2DefaultFileName(for: .document), "file.dat")
    }

    /// The group `MediaType` and `MediaManager.MediaType` are DIFFERENT enums with
    /// overlapping raw values (`photo` vs `image`). Bridging them by rawValue is a
    /// silent nil; this documents why the two mapping helpers exist separately.
    func testGroupAndDirectMediaEnumsAreNotRawValueCompatible() {
        XCTAssertNil(MediaManager.MediaType(rawValue: MediaType.photo.rawValue))
        XCTAssertNil(MediaType(rawValue: MediaManager.MediaType.image.rawValue))
    }
}

// ============================================================================
// MediaReconstructionTests — the invariant behind routing media over the
// ENCRYPTED mesh branch (F-mesh#1, 2026-09-10).
//
// The cross-platform bridge used to send media as raw base64 in the clear. The
// fix routes it through the same encrypted path as text (media is already
// inline-encrypted in `encryptedContent`; `meshWireSafe()` keeps that ciphertext
// and strips only the raw sidecar). That is safe ONLY if the receive funnel
// rebuilds the media from the DECRYPTED content — otherwise the recipient gets an
// empty bubble (the documented "shows mesh, never arrives" bug the old guard
// feared). These tests prove `MessageManager.parseAndStoreMedia` does rebuild it,
// for both the iOS wrapper form and the Android `data`-key form, so the ciphertext
// that `meshWireSafe()` preserves is sufficient on its own.
// ============================================================================
final class MediaReconstructionTests: XCTestCase {

    private func emptyMessage() -> SecureMessage {
        SecureMessage(
            id: "recon-\(UUID().uuidString)",
            senderAddress: "0xSENDER",
            recipientAddress: "0xRECIPIENT",
            encryptedContent: EncryptedMessage(ciphertext: "3q2+7w==", signature: "c2ln",
                                               senderPublicKey: "spk",
                                               timestamp: Date(timeIntervalSince1970: 1_700_000_000)),
            timestamp: Date(timeIntervalSince1970: 1_700_000_000),
            isRead: false, deliveryStatus: .delivered)
    }

    /// A random ~4 KB payload, so a bug that returns empty/constant bytes cannot pass.
    private func payload() -> Data {
        var d = Data(count: 4096)
        d.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 4096, $0.baseAddress!) }
        return d
    }

    func testReconstructsIOSInlineImage() {
        let bytes = payload()
        let json = "{\"type\":\"media\",\"mediaType\":\"image\",\"content\":\"\(bytes.base64EncodedString())\",\"size\":\(bytes.count)}"
        XCTAssertTrue(MessageManager.isMediaMessage(json), "iOS wrapper must be recognised as media")
        var m = emptyMessage()
        XCTAssertTrue(MessageManager.parseAndStoreMedia(json, for: &m), "reconstruction must succeed")
        XCTAssertEqual(m.mediaAttachment, bytes, "the media bytes must survive the decrypted-content round trip (no empty bubble)")
    }

    func testReconstructsAndroidDataKeyForm() {
        let bytes = payload()
        let json = "{\"type\":\"IMAGE\",\"data\":\"\(bytes.base64EncodedString())\"}"
        XCTAssertTrue(MessageManager.isMediaMessage(json), "Android direct-typed envelope must be recognised")
        var m = emptyMessage()
        XCTAssertTrue(MessageManager.parseAndStoreMedia(json, for: &m))
        XCTAssertEqual(m.mediaAttachment, bytes, "Android `data`-key media must reconstruct too (cross-platform)")
    }

    func testDocumentWithFileNameReconstructs() {
        let bytes = payload()
        let json = "{\"type\":\"media\",\"mediaType\":\"document\",\"content\":\"\(bytes.base64EncodedString())\",\"fileName\":\"contract.pdf\"}"
        var m = emptyMessage()
        XCTAssertTrue(MessageManager.parseAndStoreMedia(json, for: &m))
        XCTAssertEqual(m.mediaAttachment, bytes)
        XCTAssertEqual(m.mediaFileName, "contract.pdf")
    }

    /// Plain text must NOT be mistaken for media (the guard would misroute it).
    func testPlainTextIsNotMedia() {
        XCTAssertFalse(MessageManager.isMediaMessage("just a normal message"))
        XCTAssertFalse(MessageManager.isMediaMessage("{\"foo\":\"bar\"}"))
    }
}
