//
//  V2LiveMediaRoundTripTests.swift
//  OSHITests
//
//  LIVE end-to-end proof that v2 MEDIA RECEPTION works against the production
//  server (https://oshi-messenger.com), using the app's own code throughout:
//  `V2Client` for the transport, `OSHICryptoV2` for X3DH / Double Ratchet / file
//  crypto, `V2FileKeyMessage` for the wire payload. Nothing here re-implements
//  the protocol — a reimplementation would only prove my copy agrees with itself.
//
//  WHY IT EXISTS
//  -------------
//  Until 2026-08-12 the published prekey bundle carried the X25519 key-agreement
//  key in the `signingKey` field while the signed pre-key was signed with a
//  separate Ed25519 key. `verifyPrekey` could therefore never succeed, every
//  `establishInitiatorSession` threw, and every media send silently fell back to
//  legacy IPFS. So the v2 media send AND receive paths had effectively never run
//  in production. This test walks the whole thing for real:
//
//    Alice: publish bundle -> Bob fetches it -> verify the signed pre-key
//           -> X3DH -> ratchet -> encryptFile -> reserve/PUT/commit the blob
//           -> send the file-key envelope through the relay
//    Bob:   pull -> X3DH responder -> ratchet decrypt -> V2FileKeyMessage
//           -> downloadBlob (parallel, index-ordered) -> decryptFile
//           -> byte-compare against Alice's original file
//
//  ⚠️ Hits the REAL production server and needs internet. Gated behind the same
//  env var as TwoUserServerDeliveryTests so offline/CI runs SKIP rather than
//  fail:
//    OSHI_LIVE_SERVER_TESTS=1 xcodebuild test -project OSHI.xcodeproj  //      -scheme Genesis -destination 'id=<booted-sim-udid>'  //      -only-testing:OSHITests/V2LiveMediaRoundTripTests
//  and CHECK the "Executed N tests" line — a filter matching nothing still
//  prints "** TEST SUCCEEDED **" while executing 0 tests.
//
//  Identities are EPHEMERAL and in-memory (`V2EphemeralCrypto`, defined in
//  V2MediaReceiveTests.swift): the tests never touch the Keychain, never read
//  the device's real identity, and never publish under it.
//

import XCTest
import CryptoKit
@testable import OSHI

final class V2LiveMediaRoundTripTests: XCTestCase {

    private func requireLiveServer() throws {
        guard ProcessInfo.processInfo.environment["OSHI_LIVE_SERVER_TESTS"] == "1" else {
            throw XCTSkip("Set OSHI_LIVE_SERVER_TESTS=1 to run live-server tests")
        }
    }

    // MARK: - An ephemeral v2 peer

    /// One party: an X25519 identity (which IS the routing address / `userKey`),
    /// a separate Ed25519 signing key (which authenticates HTTP requests AND
    /// signs the pre-key), a signed pre-key and a one-time pre-key pool — exactly
    /// the material `V2MessageRouter.ensureOwnKeyMaterial` builds.
    private struct Peer {
        let crypto: V2EphemeralCrypto
        let client: V2Client
        let ik: OSHICryptoV2.X25519Pair
        let signing: OSHICryptoV2.Ed25519Pair
        let spk: OSHICryptoV2.X25519Pair
        let spkId: String
        let spkSignature: Data
        let opks: [(id: String, pair: OSHICryptoV2.X25519Pair)]

        var userKey: String { crypto.userKey }

        init() throws {
            let idPriv = Curve25519.KeyAgreement.PrivateKey()
            let sgPriv = Curve25519.Signing.PrivateKey()
            self.crypto = V2EphemeralCrypto(identity: idPriv, signing: sgPriv)
            self.client = V2Client(config: V2Client.Config(baseURL: URL(string: "https://oshi-messenger.com")!,
                                                           requestTimeout: 60,
                                                           maxRetryAttempts: 3),
                                   crypto: crypto,
                                   session: .shared)
            self.ik = OSHICryptoV2.X25519Pair(priv: idPriv.rawRepresentation,
                                              pub: idPriv.publicKey.rawRepresentation)
            self.signing = OSHICryptoV2.Ed25519Pair(priv: sgPriv.rawRepresentation,
                                                    pub: sgPriv.publicKey.rawRepresentation)
            self.spk = OSHICryptoV2.generateX25519()
            self.spkId = UUID().uuidString
            // Signed with the ED25519 key, and the bundle below publishes that
            // SAME key as `signingKey`. Publishing anything else is the bug this
            // whole file exists to catch.
            self.spkSignature = try OSHICryptoV2.signPrekey(signing, spk.pub)
            self.opks = (0..<3).map { _ in (UUID().uuidString, OSHICryptoV2.generateX25519()) }
        }

        var bundle: V2.PreKeyBundleUpload {
            V2.PreKeyBundleUpload(
                identityKey: ik.pub.base64EncodedString(),
                signingKey: signing.pub.base64EncodedString(),
                signedPreKey: V2.SignedPreKey(keyId: spkId,
                                              key: spk.pub.base64EncodedString(),
                                              signature: spkSignature.base64EncodedString()),
                // __NO_SILENT_ZERO_2026_10_19__ a rendu `caps` OBLIGATOIRE et ce
                // site d'appel a été oublié: le test target ne compilait plus
                // depuis, donc AUCUN test iOS ne pouvait tourner. Le faux pair
                // annonce ce qu'annonce l'app, sinon il se ferait traiter en
                // client legacy par le code qu'il est censé exercer.
                oneTimePreKeys: opks.map { V2.OneTimePreKey(keyId: $0.id, key: $0.pair.pub.base64EncodedString()) },
                caps: RatchetHealSignal.Caps.mine)
        }

        func register() async throws {
            _ = try await client.publishBundle(bundle)
        }

        func opk(id: String?) -> OSHICryptoV2.X25519Pair? {
            guard let id else { return nil }
            return opks.first(where: { $0.id == id })?.pair
        }
    }

    // MARK: - Header codec (mirrors V2MessageRouter's private pair)

    private func encodeHeaderB64(_ h: OSHICryptoV2.Header) -> String {
        OSHICryptoV2.encodeHeader(h).base64EncodedString()
    }

    private func decodeHeader(_ b64: String) throws -> OSHICryptoV2.Header {
        let data = try XCTUnwrap(Data(base64Encoded: b64))
        XCTAssertGreaterThanOrEqual(data.count, 40, "ratchet header is too short")
        let bytes = [UInt8](data)
        func u32(_ o: Int) -> UInt32 {
            (UInt32(bytes[o]) << 24) | (UInt32(bytes[o + 1]) << 16)
                | (UInt32(bytes[o + 2]) << 8) | UInt32(bytes[o + 3])
        }
        return OSHICryptoV2.Header(dh: Data(bytes[0..<32]), pn: u32(32), n: u32(36))
    }

    // MARK: - 1. The bundle the server hands out actually verifies

    /// The direct regression test for commit 8f83eec. Bob publishes a bundle;
    /// Alice fetches it from the LIVE server and verifies the signed pre-key with
    /// the `signingKey` the bundle carries. Before the fix this returned false for
    /// every peer, which is why no v2 session — and therefore no v2 media — ever
    /// existed in production.
    func testPublishedBundleVerifiesItsOwnSignedPreKey() async throws {
        try requireLiveServer()
        let bob = try Peer()
        try await bob.register()

        let alice = try Peer()
        let fetched = try await alice.client.fetchBundle(userKey: bob.userKey)

        let signingPub = try XCTUnwrap(Data(base64Encoded: fetched.signingKey))
        let spkPub = try XCTUnwrap(Data(base64Encoded: fetched.signedPreKey.key))
        let sig = try XCTUnwrap(Data(base64Encoded: fetched.signedPreKey.signature))

        XCTAssertTrue(OSHICryptoV2.verifyPrekey(signingPub, spkPub, sig),
                      "the signed pre-key does not verify against the bundle's own signingKey —  establishInitiatorSession would throw badSignedPreKeySignature for every peer, exactly as before 8f83eec")

        // TOFU: the routing address IS the X25519 identity key.
        XCTAssertEqual(fetched.identityKey, bob.userKey,
                       "bundle identityKey != the peer we asked for — the TOFU check would reject this")

        // Non-vacuity: the BROKEN shape (publishing the X25519 identity key as
        // `signingKey`, which is what shipped) must NOT verify. Without this the
        // assertion above could pass for the wrong reason.
        XCTAssertFalse(OSHICryptoV2.verifyPrekey(bob.ik.pub, spkPub, sig),
                       "the X25519 identity key verified an Ed25519 signature — the check is meaningless")
    }

    // MARK: - 2. The whole media round trip, for real

    /// Alice sends a genuinely multi-chunk file to Bob through the production
    /// blob store + relay; Bob pulls it, opens the ratchet message, downloads the
    /// blob with the parallel index-ordered `downloadBlob`, and decrypts it back
    /// to Alice's exact bytes.
    // MARK: - Guérison d'un ratchet cassé, entre deux parties, contre le relais

    /// PREUVE que la guérison fonctionne — et pas seulement que ses marqueurs
    /// sont bien formés.
    ///
    /// Tout ce qui a été livré sur le ratchet ces deux jours passait 736 tests
    /// sans qu'aucun ne prouve la seule chose qui compte: qu'une session cassée
    /// se répare et que les messages repartent. Ces tests vérifiaient des
    /// formats, des délais, des marqueurs. Celui-ci vérifie le mécanisme.
    ///
    /// Il suit exactement la panne signalée par Yassine et Zak:
    ///   1. la conversation marche;
    ///   2. la session du RÉCEPTEUR diverge — un pas de ratchet perdu, un second
    ///      appareil qui l'a consommé, une restauration;
    ///   3. il ne peut plus rien ouvrir: « je reçois la notification, pas le
    ///      message »;
    ///   4. il refait X3DH et émet l'accusé, qui PORTE donc son en-tête;
    ///   5. l'autre côté l'adopte en le déchiffrant;
    ///   6. le message suivant passe. Sans que personne n'ait rien fait.
    ///
    /// Ce qu'il ne couvre PAS, et il faut le dire: il travaille au niveau du
    /// protocole (`OSHICryptoV2` + le relais), pas à travers `V2MessageRouter`.
    /// Le routeur lit l'identité de l'appareil dans le trousseau à une clé fixe
    /// (`loadLegacyIdentityPair`, "privateKey"), donc deux routeurs du même
    /// processus partageraient une seule identité et la conversation à deux
    /// serait impossible à monter. Les branches propres au routeur restent
    /// couvertes par leurs tests unitaires.
    func testABrokenRatchetHealsAndMessagesFlowAgain() async throws {
        try requireLiveServer()

        let alice = try Peer()
        let bob = try Peer()
        try await alice.register()
        try await bob.register()

        // ---- 1. Une conversation qui marche ----
        let bBundle = try await alice.client.fetchBundle(userKey: bob.userKey)
        let bSpk = try XCTUnwrap(Data(base64Encoded: bBundle.signedPreKey.key))
        let aEph = OSHICryptoV2.generateX25519()
        let sk1 = try OSHICryptoV2.x3dhInitiator(
            ik: alice.ik, ek: aEph,
            theirIkPub: try XCTUnwrap(Data(base64Encoded: bBundle.identityKey)),
            theirSpkPub: bSpk,
            theirOpkPub: bBundle.oneTimePreKey.flatMap { Data(base64Encoded: $0.key) })
        let aliceState = try OSHICryptoV2.ratchetInitAlice(SK: sk1, bobRatchetPub: bSpk)

        let (h1, c1) = try OSHICryptoV2.ratchetEncrypt(aliceState, plaintext: Data("bonjour".utf8),
                                                       associatedData: Data())
        let id1 = UUID().uuidString
        _ = try await alice.client.sendEnvelope(V2.Envelope(
            msgId: id1, from: alice.userKey, to: bob.userKey, type: .oneToOne,
            x3dh: V2.X3DHHeader(identityKey: alice.ik.pub.base64EncodedString(),
                                ephemeralKey: aEph.pub.base64EncodedString(),
                                signedPreKeyId: bBundle.signedPreKey.keyId,
                                oneTimePreKeyId: bBundle.oneTimePreKey?.keyId),
            header: encodeHeaderB64(h1), ciphertext: c1.base64EncodedString()))

        let p1 = try await bob.client.pullMessages(userKey: bob.userKey)
        let in1 = try XCTUnwrap(p1.messages.first(where: { $0.msgId == id1 }))
        let x1 = try XCTUnwrap(in1.x3dh)
        let bobSK = try OSHICryptoV2.x3dhResponder(
            ik: bob.ik, spk: bob.spk, opk: bob.opk(id: x1.oneTimePreKeyId),
            theirIkPub: try XCTUnwrap(Data(base64Encoded: x1.identityKey)),
            theirEkPub: try XCTUnwrap(Data(base64Encoded: x1.ephemeralKey)))
        let bobState = OSHICryptoV2.ratchetInitBob(SK: bobSK, bobRatchetPair: bob.spk)
        let got1 = try OSHICryptoV2.ratchetDecrypt(
            bobState, header: try decodeHeader(in1.header),
            ciphertext: try XCTUnwrap(Data(base64Encoded: in1.ciphertext)), associatedData: Data())
        XCTAssertEqual(String(data: got1, encoding: .utf8), "bonjour",
                       "la conversation doit d'abord marcher, sinon on ne prouve rien en la cassant")

        // ---- 2. La session de Bob DIVERGE ----
        // Un secret partagé FAUX produit exactement l'état d'un ratchet
        // désynchronisé: les clés de chaîne ne correspondent plus.
        let wrongSK = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        let divergedState = OSHICryptoV2.ratchetInitBob(SK: wrongSK, bobRatchetPair: bob.spk)

        // ---- 3. Le symptôme: Alice écrit, Bob ne peut plus ouvrir ----
        let (h2, c2) = try OSHICryptoV2.ratchetEncrypt(aliceState, plaintext: Data("tu me reçois ?".utf8),
                                                       associatedData: Data())
        let id2 = UUID().uuidString
        _ = try await alice.client.sendEnvelope(V2.Envelope(
            msgId: id2, from: alice.userKey, to: bob.userKey, type: .oneToOne,
            header: encodeHeaderB64(h2), ciphertext: c2.base64EncodedString()))

        let p2 = try await bob.client.pullMessages(userKey: bob.userKey)
        let in2 = try XCTUnwrap(p2.messages.first(where: { $0.msgId == id2 }))
        XCTAssertNil(in2.x3dh, "un message de suite ne porte PAS d'en-tête X3DH — c'est tout le problème")
        XCTAssertThrowsError(try OSHICryptoV2.ratchetDecrypt(
            divergedState, header: try decodeHeader(in2.header),
            ciphertext: try XCTUnwrap(Data(base64Encoded: in2.ciphertext)), associatedData: Data()),
            "la session divergée doit refuser le message — sinon il n'y a rien à réparer")

        // ---- 4. Bob refait X3DH et émet l'accusé ----
        // L'ordre porte tout: c'est parce que Bob n'a plus de session que son
        // enveloppe PORTE un en-tête X3DH. Un accusé émis sur une session
        // vivante repartirait sur le ratchet cassé et ne réparerait rien.
        let aBundle = try await bob.client.fetchBundle(userKey: alice.userKey)
        let aSpk = try XCTUnwrap(Data(base64Encoded: aBundle.signedPreKey.key))
        XCTAssertTrue(OSHICryptoV2.verifyPrekey(
            try XCTUnwrap(Data(base64Encoded: aBundle.signingKey)), aSpk,
            try XCTUnwrap(Data(base64Encoded: aBundle.signedPreKey.signature))))
        let bEph = OSHICryptoV2.generateX25519()
        let sk2 = try OSHICryptoV2.x3dhInitiator(
            ik: bob.ik, ek: bEph,
            theirIkPub: try XCTUnwrap(Data(base64Encoded: aBundle.identityKey)),
            theirSpkPub: aSpk,
            theirOpkPub: aBundle.oneTimePreKey.flatMap { Data(base64Encoded: $0.key) })
        let bobHealState = try OSHICryptoV2.ratchetInitAlice(SK: sk2, bobRatchetPub: aSpk)

        let healPayload = RatchetHealSignal.encode(resend: [])
        let (h3, c3) = try OSHICryptoV2.ratchetEncrypt(bobHealState, plaintext: Data(healPayload.utf8),
                                                       associatedData: Data())
        let healId = RatchetHealSignal.makeCapableMsgId()
        _ = try await bob.client.sendEnvelope(V2.Envelope(
            msgId: healId, from: bob.userKey, to: alice.userKey, type: .oneToOne,
            x3dh: V2.X3DHHeader(identityKey: bob.ik.pub.base64EncodedString(),
                                ephemeralKey: bEph.pub.base64EncodedString(),
                                signedPreKeyId: aBundle.signedPreKey.keyId,
                                oneTimePreKeyId: aBundle.oneTimePreKey?.keyId),
            header: encodeHeaderB64(h3), ciphertext: c3.base64EncodedString()))

        // ---- 5. Alice l'ouvre: l'en-tête X3DH APPORTE la réparation ----
        let p3 = try await alice.client.pullMessages(userKey: alice.userKey)
        let in3 = try XCTUnwrap(p3.messages.first(where: { $0.msgId == healId }),
                                "l'accusé n'est pas arrivé chez Alice")
        let x3 = try XCTUnwrap(in3.x3dh, "l'accusé DOIT porter un en-tête X3DH, sinon il ne répare rien")
        let aliceSK2 = try OSHICryptoV2.x3dhResponder(
            ik: alice.ik, spk: alice.spk, opk: alice.opk(id: x3.oneTimePreKeyId),
            theirIkPub: try XCTUnwrap(Data(base64Encoded: x3.identityKey)),
            theirEkPub: try XCTUnwrap(Data(base64Encoded: x3.ephemeralKey)))
        let aliceHealState = OSHICryptoV2.ratchetInitBob(SK: aliceSK2, bobRatchetPair: alice.spk)
        let healText = String(data: try OSHICryptoV2.ratchetDecrypt(
            aliceHealState, header: try decodeHeader(in3.header),
            ciphertext: try XCTUnwrap(Data(base64Encoded: in3.ciphertext)),
            associatedData: Data()), encoding: .utf8)
        XCTAssertNotNil(healText)
        XCTAssertTrue(RatchetHealSignal.isHealPayload(try XCTUnwrap(healText)),
                      "Alice doit reconnaître l'accusé, sinon il s'affiche en bulle")
        XCTAssertTrue(RatchetHealSignal.announcesListSupport(in3.msgId),
                      "l'identifiant doit annoncer la capacité, sinon aucune liste ne partira jamais")

        // ---- 6. LA propriété: le message suivant passe ----
        let (h4, c4) = try OSHICryptoV2.ratchetEncrypt(aliceHealState,
                                                       plaintext: Data("et maintenant ?".utf8),
                                                       associatedData: Data())
        let id4 = UUID().uuidString
        _ = try await alice.client.sendEnvelope(V2.Envelope(
            msgId: id4, from: alice.userKey, to: bob.userKey, type: .oneToOne,
            header: encodeHeaderB64(h4), ciphertext: c4.base64EncodedString()))

        let p4 = try await bob.client.pullMessages(userKey: bob.userKey)
        let in4 = try XCTUnwrap(p4.messages.first(where: { $0.msgId == id4 }))
        let got4 = try OSHICryptoV2.ratchetDecrypt(
            bobHealState, header: try decodeHeader(in4.header),
            ciphertext: try XCTUnwrap(Data(base64Encoded: in4.ciphertext)), associatedData: Data())
        XCTAssertEqual(String(data: got4, encoding: .utf8), "et maintenant ?",
                       "GUÉRISON: la conversation doit repartir sans intervention humaine")
    }

    func testMediaRoundTripsThroughTheLiveServerByteExact() async throws {
        try requireLiveServer()

        let alice = try Peer()
        let bob = try Peer()
        try await alice.register()
        try await bob.register()

        // ---- Alice: X3DH initiator against Bob's real, server-served bundle ----
        let bundle = try await alice.client.fetchBundle(userKey: bob.userKey)
        let theirIk = try XCTUnwrap(Data(base64Encoded: bundle.identityKey))
        let theirSpk = try XCTUnwrap(Data(base64Encoded: bundle.signedPreKey.key))
        let theirSpkSig = try XCTUnwrap(Data(base64Encoded: bundle.signedPreKey.signature))
        let theirSigning = try XCTUnwrap(Data(base64Encoded: bundle.signingKey))
        XCTAssertTrue(OSHICryptoV2.verifyPrekey(theirSigning, theirSpk, theirSpkSig),
                      "Bob's signed pre-key did not verify — no session can be established")
        let theirOpk = bundle.oneTimePreKey.flatMap { Data(base64Encoded: $0.key) }

        let ephemeral = OSHICryptoV2.generateX25519()
        let sk = try OSHICryptoV2.x3dhInitiator(ik: alice.ik,
                                                ek: ephemeral,
                                                theirIkPub: theirIk,
                                                theirSpkPub: theirSpk,
                                                theirOpkPub: theirOpk)
        let aliceState = try OSHICryptoV2.ratchetInitAlice(SK: sk, bobRatchetPub: theirSpk)

        // ---- Alice: encrypt + upload a multi-chunk file ----
        // 5 MB at the shipping 2 MiB chunk size = 3 chunks, so the receiver's
        // bounded-parallel download really has more than one request in flight.
        let original = Data((0..<(5 * 1024 * 1024)).map { _ in UInt8.random(in: 0...255) })
        let filename = "live-\(UUID().uuidString.prefix(8)).jpg"
        let enc = try OSHICryptoV2.encryptFile(
            bytes: original,
            meta: OSHICryptoV2.FileMeta(filename: filename, mime: "image/jpeg"))
        XCTAssertGreaterThan(enc.chunks.count, 1, "the test file did not split into multiple chunks")

        let blobId = try await alice.client.uploadBlob(recipientKey: bob.userKey,
                                                       chunks: enc.chunks,
                                                       useBackground: false)

        // ---- Alice: send the file-key message through the ratchet + relay ----
        let keyMsg = V2FileKeyMessage(
            kind: V2FileKeyMessage.kindValue,
            blobId: blobId,
            fileKey: enc.fileKey.base64EncodedString(),
            fileNonce: enc.fileNonce.base64EncodedString(),
            chunkCount: enc.chunks.count,
            manifest: enc.manifest.base64EncodedString(),
            filename: filename,
            mime: "image/jpeg",
            mediaType: MediaManager.MediaType.image.rawValue,
            isViewOnce: true,
            groupMessage: nil)
        let payload = try JSONEncoder().encode(keyMsg)
        let (hdr, ct) = try OSHICryptoV2.ratchetEncrypt(aliceState, plaintext: payload, associatedData: Data())
        let msgId = UUID().uuidString
        let envelope = V2.Envelope(msgId: msgId,
                                   from: alice.userKey,
                                   to: bob.userKey,
                                   type: .oneToOne,
                                   x3dh: V2.X3DHHeader(identityKey: alice.ik.pub.base64EncodedString(),
                                                       ephemeralKey: ephemeral.pub.base64EncodedString(),
                                                       signedPreKeyId: bundle.signedPreKey.keyId,
                                                       oneTimePreKeyId: bundle.oneTimePreKey?.keyId),
                                   header: encodeHeaderB64(hdr),
                                   ciphertext: ct.base64EncodedString())
        let accepted = try await alice.client.sendEnvelope(envelope)
        XCTAssertTrue(accepted.contains(msgId), "the relay did not accept the file-key envelope")

        // ---- Bob: pull + open the ratchet message ----
        let pull = try await bob.client.pullMessages(userKey: bob.userKey)
        let inbound = try XCTUnwrap(pull.messages.first(where: { $0.msgId == msgId }),
                                    "Bob's pull did not contain the message Alice just sent")
        let x3dh = try XCTUnwrap(inbound.x3dh, "the X3DH header did not survive the relay")
        XCTAssertEqual(x3dh.identityKey, alice.userKey,
                       "the X3DH identity key is not the sender's routing address — the TOFU gate would reject it")

        let bobSK = try OSHICryptoV2.x3dhResponder(ik: bob.ik,
                                                   spk: bob.spk,
                                                   opk: bob.opk(id: x3dh.oneTimePreKeyId),
                                                   theirIkPub: try XCTUnwrap(Data(base64Encoded: x3dh.identityKey)),
                                                   theirEkPub: try XCTUnwrap(Data(base64Encoded: x3dh.ephemeralKey)))
        let bobState = OSHICryptoV2.ratchetInitBob(SK: bobSK, bobRatchetPair: bob.spk)
        let plaintext = try OSHICryptoV2.ratchetDecrypt(
            bobState,
            header: try decodeHeader(inbound.header),
            ciphertext: try XCTUnwrap(Data(base64Encoded: inbound.ciphertext)),
            associatedData: Data())

        let received = try JSONDecoder().decode(V2FileKeyMessage.self, from: plaintext)
        XCTAssertEqual(received.kind, V2FileKeyMessage.kindValue)
        XCTAssertEqual(received.blobId, blobId)
        XCTAssertEqual(received.chunkCount, enc.chunks.count)
        XCTAssertEqual(received.filename, filename)
        XCTAssertEqual(received.mediaType, MediaManager.MediaType.image.rawValue,
                       "mediaType was lost — the bubble would render as a generic file")
        XCTAssertEqual(received.isViewOnce, true, "isViewOnce was lost — ephemeral media would persist")

        // ---- Bob: download the blob + decrypt ----
        let fileKey = try XCTUnwrap(Data(base64Encoded: received.fileKey))
        let fileNonce = try XCTUnwrap(Data(base64Encoded: received.fileNonce))
        let manifest = try XCTUnwrap(Data(base64Encoded: received.manifest))
        let chunks = try await bob.client.downloadBlob(blobId: received.blobId,
                                                       chunkCount: received.chunkCount)
        XCTAssertEqual(chunks.count, enc.chunks.count)
        XCTAssertEqual(chunks, enc.chunks,
                       "a chunk came back from the blob store altered or out of order")

        let (out, info) = try OSHICryptoV2.decryptFile(fileKey: fileKey,
                                                       fileNonce: fileNonce,
                                                       chunks: chunks,
                                                       manifest: manifest)
        XCTAssertEqual(SHA256.hash(data: out).map { $0 }, SHA256.hash(data: original).map { $0 },
                       "the media did not survive the live round trip byte-exact")
        XCTAssertEqual(info["filename"] as? String, filename)
        XCTAssertEqual((info["size"] as? NSNumber)?.intValue, original.count)

        // ---- Cleanup: ack the relay, drop the blob ----
        try? await bob.client.ack(userKey: bob.userKey, upToSeq: pull.maxSeq)
        try? await bob.client.deleteBlob(blobId: blobId)
    }

    // MARK: - 3. The path the APP actually uses to receive media

    /// `MessageManager+V2` does NOT call `downloadBlob`; it calls
    /// `downloadAndDecryptFileToDisk`, which stages chunks through a BACKGROUND
    /// URLSession and stream-decrypts them. That is the code real users' media
    /// travels through, so it gets its own live proof — kept separate from the
    /// test above because a background session can be deferred by the OS and a
    /// stall here must not cost us the primary evidence.
    func testProductionReceivePathDownloadsAndDecryptsToDisk() async throws {
        try requireLiveServer()

        let alice = try Peer()
        let bob = try Peer()
        try await alice.register()
        try await bob.register()

        let original = Data((0..<(3 * 1024 * 1024)).map { _ in UInt8.random(in: 0...255) })
        let enc = try OSHICryptoV2.encryptFile(
            bytes: original,
            meta: OSHICryptoV2.FileMeta(filename: "disk.bin", mime: "application/octet-stream"))
        XCTAssertGreaterThan(enc.chunks.count, 1, "the test file did not split into multiple chunks")

        let blobId = try await alice.client.uploadBlob(recipientKey: bob.userKey,
                                                       chunks: enc.chunks,
                                                       useBackground: false)

        let outURL = try await bob.client.downloadAndDecryptFileToDisk(
            blobId: blobId,
            chunkCount: enc.chunks.count,
            fileKeyB64: enc.fileKey.base64EncodedString(),
            fileNonceB64: enc.fileNonce.base64EncodedString(),
            manifestB64: enc.manifest.base64EncodedString())

        let back = try Data(contentsOf: outURL)
        XCTAssertEqual(SHA256.hash(data: back).map { $0 }, SHA256.hash(data: original).map { $0 },
                       "the production receive path did not reproduce the sender's bytes")

        try? FileManager.default.removeItem(at: outURL)
        try? await bob.client.deleteBlob(blobId: blobId)
    }
}
