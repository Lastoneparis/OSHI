//
//  RevealOutboxTests.swift
//  OSHITests
//
//  __REVEAL_OUTBOX_2026_08_22__ La file des couches restant à envoyer.
//
//  Ce que ces tests protègent, c'est la seule raison d'être de cette file: une
//  révélation dure de cinq minutes à UNE SEMAINE. Un minuteur meurt avec le
//  processus. Ce qui doit survivre, c'est une échéance ABSOLUE sur le disque,
//  rejouée au lancement.
//

import XCTest
@testable import OSHI

@MainActor
final class RevealOutboxTests: XCTestCase {

    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("outbox-test-\(UUID().uuidString)", isDirectory: true)
        // La logique de file, sans le chiffrement au repos que l'app injecte au
        // lancement (il exige le trousseau, absent d'un run non signé) — celui-ci
        // est couvert par `PlaintextLeftoversTests`.
        RevealOutbox.seal = nil
        RevealOutbox.open = nil
    }

    override func tearDown() async throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
    }

    private func layers(_ n: Int) -> [Data] {
        (0..<n).map { Data(repeating: UInt8($0), count: 32 + $0) }
    }

    // MARK: - Ce qui part tout de suite, et ce qui attend

    /// La couche 0 n'entre JAMAIS dans la file: elle est partie avec le message
    /// qui crée la bulle. Mettre N couches en file en enverrait une de trop, et
    /// la première arriverait deux fois.
    func testLayerZeroIsNeverQueued() {
        let outbox = RevealOutbox(directory: dir)
        outbox.enqueue(revealId: "m1", peerKey: "peer", filename: "p.jpg", mime: "image/jpeg",
                       layers: layers(4), totalSeconds: 400)
        XCTAssertEqual(outbox.pending.map(\.layerIndex), [1, 2, 3])
    }

    /// Les échéances sont réparties sur la durée, et la DERNIÈRE tombe pile à la
    /// fin — pas avant, sinon la photo est nette avant l'heure annoncée.
    func testDueDatesSpanExactlyTheAnnouncedDuration() {
        let outbox = RevealOutbox(directory: dir)
        let start = Date(timeIntervalSince1970: 1_000_000)
        outbox.enqueue(revealId: "m1", peerKey: "peer", filename: "p.jpg", mime: "image/jpeg",
                       layers: layers(4), totalSeconds: 300, startedAt: start)
        let offsets = outbox.pending.sorted { $0.layerIndex < $1.layerIndex }
            .map { $0.dueAt.timeIntervalSince(start) }
        // Une tolérance ne s'applique pas à un tableau entier: on compare rang
        // par rang, ce qui dit aussi LEQUEL est faux quand ça casse.
        XCTAssertEqual(offsets.count, 3)
        for (i, expected) in [100.0, 200.0, 300.0].enumerated() {
            XCTAssertEqual(offsets[i], expected, accuracy: 0.01, "couche \(i + 1)")
        }
    }

    /// Rien ne part avant l'heure. C'est la promesse faite à l'utilisateur:
    /// « tu la verras dans deux jours ».
    func testNothingIsSentBeforeItsDueDate() {
        let outbox = RevealOutbox(directory: dir)
        let start = Date()
        outbox.enqueue(revealId: "m1", peerKey: "peer", filename: "p.jpg", mime: "image/jpeg",
                       layers: layers(3), totalSeconds: 600, startedAt: start)
        var sent: [Int] = []
        outbox.send = { item, _ in sent.append(item.layerIndex) }
        outbox.drain(now: start.addingTimeInterval(10))
        XCTAssertTrue(sent.isEmpty, "aucune couche n'est échue à t+10 s sur 600 s")
        XCTAssertEqual(outbox.pending.count, 2, "et rien n'a été retiré de la file")
    }

    /// 🔴 L'ordre est ce qui rend la progression MONOTONE. Une couche plus basse
    /// est ignorée à l'arrivée: envoyer la 3 avant la 2 ferait donc perdre la 2
    /// pour toujours, et l'image sauterait une marche.
    func testDueLayersGoOutInOrder() {
        let outbox = RevealOutbox(directory: dir)
        let start = Date()
        outbox.enqueue(revealId: "m1", peerKey: "peer", filename: "p.jpg", mime: "image/jpeg",
                       layers: layers(5), totalSeconds: 100, startedAt: start)
        var sent: [Int] = []
        outbox.send = { item, _ in sent.append(item.layerIndex) }
        outbox.drain(now: start.addingTimeInterval(1000))   // tout est échu d'un coup
        XCTAssertEqual(sent, [1, 2, 3, 4])
    }

    /// Les octets envoyés sont bien ceux de la couche demandée.
    func testTheBytesSentAreTheLayersOwn() {
        let outbox = RevealOutbox(directory: dir)
        let ls = layers(3)
        let start = Date()
        outbox.enqueue(revealId: "m1", peerKey: "peer", filename: "p.jpg", mime: "image/jpeg",
                       layers: ls, totalSeconds: 10, startedAt: start)
        var got: [Int: Data] = [:]
        outbox.send = { item, bytes in got[item.layerIndex] = bytes }
        outbox.drain(now: start.addingTimeInterval(100))
        XCTAssertEqual(got[1], ls[1])
        XCTAssertEqual(got[2], ls[2])
    }

    // MARK: - Survivre à la fermeture de l'app

    /// LE test de cette classe. Une révélation de 48 h traverse forcément une
    /// fermeture: la file doit se retrouver intacte dans une instance NEUVE, et
    /// les échéances doivent être les mêmes dates absolues.
    func testTheQueueSurvivesAFreshInstance() {
        let start = Date(timeIntervalSince1970: 2_000_000)
        do {
            let outbox = RevealOutbox(directory: dir)
            outbox.enqueue(revealId: "m9", peerKey: "peer", filename: "p.jpg", mime: "image/jpeg",
                           layers: layers(4), totalSeconds: 48 * 3600, startedAt: start)
            XCTAssertEqual(outbox.pending.count, 3)
        }
        // Nouvelle instance: c'est ce que fait un relancement de l'app.
        let reborn = RevealOutbox(directory: dir)
        XCTAssertEqual(reborn.pending.count, 3, "la file doit se relire depuis le disque")
        XCTAssertEqual(reborn.pending.map(\.layerIndex).sorted(), [1, 2, 3])
        let last = reborn.pending.max(by: { $0.layerIndex < $1.layerIndex })!
        XCTAssertEqual(last.dueAt.timeIntervalSince(start), 48 * 3600, accuracy: 1,
                       "l'échéance est ABSOLUE — une durée relative repartirait de zéro à chaque lancement")

        var sent: [Int] = []
        reborn.send = { item, _ in sent.append(item.layerIndex) }
        reborn.drain(now: start.addingTimeInterval(48 * 3600 + 1))
        XCTAssertEqual(sent, [1, 2, 3], "et les octets sont toujours là après la renaissance")
    }

    // MARK: - Ne pas garder des octets à vie

    /// Une révélation dont l'app n'est jamais rouverte garderait ses octets pour
    /// toujours. Au-delà de la marge, on libère le disque.
    func testTooOldIsDroppedInsteadOfKeptForever() {
        let outbox = RevealOutbox(directory: dir)
        let longAgo = Date().addingTimeInterval(-30 * 24 * 3600)
        outbox.enqueue(revealId: "old", peerKey: "peer", filename: "p.jpg", mime: "image/jpeg",
                       layers: layers(3), totalSeconds: 600, startedAt: longAgo)
        var sent: [Int] = []
        outbox.send = { item, _ in sent.append(item.layerIndex) }
        outbox.drain()
        XCTAssertTrue(sent.isEmpty, "rien d'aussi vieux ne doit être envoyé")
        XCTAssertTrue(outbox.pending.isEmpty, "et rien ne doit rester sur le disque")
    }

    /// Annuler abandonne tout pour un message — il a été supprimé, ou l'envoi
    /// initial a échoué et il n'y a plus de bulle à enrichir.
    func testCancelDropsEveryLayerOfThatMessageOnly() {
        let outbox = RevealOutbox(directory: dir)
        let start = Date()
        outbox.enqueue(revealId: "a", peerKey: "p", filename: "f", mime: "image/jpeg",
                       layers: layers(3), totalSeconds: 60, startedAt: start)
        outbox.enqueue(revealId: "b", peerKey: "p", filename: "f", mime: "image/jpeg",
                       layers: layers(3), totalSeconds: 60, startedAt: start)
        outbox.cancel(revealId: "a")
        XCTAssertEqual(Set(outbox.pending.map(\.revealId)), ["b"])
    }

    /// Une photo qui ne donne qu'une couche n'est pas une révélation: rien ne
    /// doit entrer en file, sinon on envoie une couche orpheline.
    func testASingleLayerIsNotARevealAndQueuesNothing() {
        let outbox = RevealOutbox(directory: dir)
        outbox.enqueue(revealId: "m", peerKey: "p", filename: "f", mime: "image/jpeg",
                       layers: layers(1), totalSeconds: 60)
        XCTAssertTrue(outbox.pending.isEmpty)
    }
}

/// __PLAINTEXT_LEFTOVERS_2026_09_24__ « Supprimer le clair temporaire après
/// usage, chiffrer ce qui persiste. »
@MainActor
final class PlaintextLeftoversTests: XCTestCase {

    func testRevealLayersAreSealedAtRestAndStillSendClear() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("outbox-seal-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        RevealOutbox.seal = { try MediaCipher.seal($0) }
        RevealOutbox.open = { MediaCipher.isEncrypted($0) ? try MediaCipher.open($0) : $0 }
        let outbox = RevealOutbox(directory: dir)
        let layers = (0..<3).map { Data("layer-\($0)-secret-pixels".utf8) }
        let start = Date()
        outbox.enqueue(revealId: "r", peerKey: "p", filename: "f.jpg", mime: "image/jpeg",
                       layers: layers, totalSeconds: 60, startedAt: start)
        let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        XCTAssertFalse(files.isEmpty)
        for f in files {
            XCTAssertTrue(MediaCipher.isEncrypted(try Data(contentsOf: f)), "\(f.lastPathComponent) en clair")
        }
        var sent: [Data] = []
        outbox.send = { _, bytes in sent.append(bytes) }
        outbox.drain(now: start.addingTimeInterval(120))
        XCTAssertEqual(sent, Array(layers[1...]))
    }

    func testAtRestFileSealsAndMigratesLegacyPlaintext() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("atrest-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let legacy = Data(#"[{"text":"programmé en clair"}]"#.utf8)
        try legacy.write(to: url)
        XCTAssertEqual(try AtRestFile.read(url), legacy)                      // ancien format lisible
        XCTAssertTrue(try Data(contentsOf: url).starts(with: Data("OSHIENC1".utf8)))  // et rechiffré sur place
        XCTAssertEqual(try AtRestFile.read(url), legacy)
        try AtRestFile.write(Data("x".utf8), to: url)
        XCTAssertNotEqual(try Data(contentsOf: url), Data("x".utf8))
    }

    func testShareCopyIsPurgedOnNextShare() throws {
        let a = try MediaPlaintextTemp.writeForShare(Data("a".utf8), fileName: "a.pdf")
        let b = try MediaPlaintextTemp.writeForShare(Data("b".utf8), fileName: "b.pdf")
        XCTAssertFalse(FileManager.default.fileExists(atPath: a.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: b.path))
        XCTAssertEqual(b.lastPathComponent, "b.pdf")
        MediaPlaintextTemp.purgeShares()
        XCTAssertFalse(FileManager.default.fileExists(atPath: b.path))
    }

    /// __GROUP_FILENAME_TRAVERSAL_2026_11_20__ A sender-chosen file name can
    /// never leave the share folder (the group contact-card path used to join
    /// it straight onto tmp/).
    func testSenderFileNameCannotTraverseOutOfTheShareFolder() throws {
        let root = MediaPlaintextTemp.shareDirectory.resolvingSymlinksInPath().path + "/"
        for hostile in ["../../Library/Preferences/x.plist", "../../../Documents/call_debug.log",
                        "..", ".", "", "/etc/passwd", "a/../../b.vcf"] {
            let url = try MediaPlaintextTemp.writeForShare(Data("x".utf8), fileName: hostile)
            XCTAssertTrue(url.resolvingSymlinksInPath().path.hasPrefix(root), "\(hostile) → \(url.path)")
            XCTAssertFalse(url.lastPathComponent.contains("/"))
            XCTAssertNotEqual(url.lastPathComponent, "..")
        }
        MediaPlaintextTemp.purgeShares()
    }

    func testRemovingOneScratchFileKeepsTheOthers() throws {
        let a = MediaPlaintextTemp.scratchURL(ext: "mp4")
        let b = MediaPlaintextTemp.scratchURL(ext: "mp4")
        try Data("a".utf8).write(to: a)
        try Data("b".utf8).write(to: b)
        MediaPlaintextTemp.remove(a)
        XCTAssertFalse(FileManager.default.fileExists(atPath: a.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: b.path))
        MediaPlaintextTemp.remove(b)
        XCTAssertTrue(MediaPlaintextTemp.owns(b))
    }

    func testLinkPreviewDiskEntryHidesTheURLAndIsSealed() throws {
        let marker = UUID().uuidString
        let link = "https://example.org/private/\(marker)?q=secret"
        LinkPreviewCache.shared.store(link, data: LinkPreviewData(url: link, title: "Secret title \(marker)"), image: nil)
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LinkPreviews", isDirectory: true)
        let legacyName = Data(link.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "=", with: "")
        var sealed: Data?
        let deadline = Date().addingTimeInterval(5)
        while sealed == nil, Date() < deadline {
            let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
            XCTAssertFalse(names.contains(legacyName))
            for n in names {
                if let d = try? Data(contentsOf: dir.appendingPathComponent(n)),
                   let plain = try? AtRestCipher.shared.open(d), String(decoding: plain, as: UTF8.self).contains(marker) {
                    XCTAssertEqual(n.count, 64)
                    sealed = d
                }
            }
            if sealed == nil { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
        }
        let d = try XCTUnwrap(sealed)
        XCTAssertTrue(d.starts(with: Data("OSHIENC1".utf8)))
        XCTAssertFalse(String(decoding: d, as: UTF8.self).contains("Secret title"))
    }
}
