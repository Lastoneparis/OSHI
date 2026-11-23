//
//  TimedRevealAuditTests.swift
//  OSHITests
//
//  __REVEAL_AUDIT_2026_08_23__ Audit du moteur de révélation progressive.
//
//  Les trois maillons sont branchés; ce fichier attaque le moteur lui-même —
//  celui qui était écrit depuis le 19/08 et que RIEN n'avait jamais exécuté.
//  Du code jamais appelé n'est pas du code éprouvé: c'est du code dont personne
//  n'a encore trouvé les défauts.
//

import XCTest
@testable import OSHI

final class TimedRevealAuditTests: XCTestCase {

    private func schedule(_ layers: Int, _ seconds: TimeInterval,
                          start: Date = Date(timeIntervalSince1970: 1_000_000)) -> TimedRevealSchedule {
        TimedRevealSchedule(layerCount: layers, totalDuration: seconds, startedAt: start)!
    }

    // MARK: - Les bornes que le décodage contourne

    /// 🔴 `init?` refuse `layerCount < 2`, mais `Codable` est SYNTHÉTISÉ: le
    /// décodage ne passe pas par l'initialiseur et accepte n'importe quoi.
    ///
    /// Ça compte parce que `TimedRevealStore.load()` décode exactement ce
    /// type au lancement. Une entrée corrompue — disque abîmé, migration, ou
    /// simplement une version future — donne `finalIndex = 0`, donc une division
    /// par zéro dans `unlockDate`, donc une Date NaN dans le compte à rebours.
    func testADecodedScheduleCannotBypassItsOwnBounds() throws {
        let corrupt = #"{"layerCount":1,"totalDuration":600,"startedAt":0}"#
        XCTAssertThrowsError(try JSONDecoder().decode(TimedRevealSchedule.self,
                                                      from: Data(corrupt.utf8)),
                             "layerCount=1 n'est pas une révélation et doit être REFUSÉ au décodage")
    }

    func testADecodedScheduleRejectsAnAbsurdDuration() {
        for bad in [#"{"layerCount":4,"totalDuration":0,"startedAt":0}"#,
                    #"{"layerCount":4,"totalDuration":-5,"startedAt":0}"#,
                    #"{"layerCount":4,"totalDuration":9999999,"startedAt":0}"#] {
            XCTAssertThrowsError(try JSONDecoder().decode(TimedRevealSchedule.self, from: Data(bad.utf8)),
                                 "durée absurde acceptée: \(bad)")
        }
    }

    /// Le symptôme que les bornes existent pour éviter: une date impossible.
    func testUnlockDatesAreAlwaysFinite() {
        let s = schedule(2, 60)
        for i in -1...5 {
            XCTAssertTrue(s.unlockDate(forLayer: i).timeIntervalSince1970.isFinite,
                          "date non finie pour la couche \(i)")
        }
    }

    // MARK: - La progression dans le temps

    func testDueLayerWalksFromZeroToTheLastOne() {
        let start = Date(timeIntervalSince1970: 1_000_000)
        let s = schedule(5, 400, start: start)   // une couche toutes les 100 s
        XCTAssertEqual(s.dueLayer(at: start), 0)
        XCTAssertEqual(s.dueLayer(at: start.addingTimeInterval(99)), 0)
        XCTAssertEqual(s.dueLayer(at: start.addingTimeInterval(101)), 1)
        XCTAssertEqual(s.dueLayer(at: start.addingTimeInterval(399)), 3)
        XCTAssertEqual(s.dueLayer(at: start.addingTimeInterval(400)), 4)
        XCTAssertEqual(s.dueLayer(at: start.addingTimeInterval(100_000)), 4,
                       "après la fin, on reste sur la dernière couche")
    }

    /// Une horloge qui recule — changement de fuseau, correction NTP — ne doit
    /// pas faire reculer la révélation ni produire un reste négatif.
    func testAClockThatGoesBackwardsIsHarmless() {
        let start = Date(timeIntervalSince1970: 1_000_000)
        let s = schedule(4, 300, start: start)
        let before = start.addingTimeInterval(-5000)
        XCTAssertEqual(s.dueLayer(at: before), 0)
        XCTAssertGreaterThanOrEqual(s.remaining(at: before), 0)
        XCTAssertEqual(s.progress(at: before), 0, accuracy: 0.0001)
    }

    func testTheAnnouncedDurationIsExactlyWhenItFinishes() {
        let start = Date(timeIntervalSince1970: 2_000_000)
        let s = schedule(6, 48 * 3600, start: start)
        XCTAssertEqual(s.completionDate, start.addingTimeInterval(48 * 3600))
        XCTAssertEqual(s.remaining(at: s.completionDate), 0, accuracy: 0.001)
        XCTAssertEqual(s.dueLayer(at: s.completionDate), s.finalIndex)
    }

    // MARK: - L'encodeur en couches

    /// Une image trop petite ne peut pas être dégradée: elle ne donne qu'une
    /// couche, et ce n'est PAS une révélation. `ChatView` s'appuie là-dessus
    /// pour retomber sur un envoi normal.
    func testATinyImageYieldsASingleLayerSoTheCallerFallsBack() throws {
        let tiny = try makeJPEG(side: 24)
        let layers = TimedRevealEncoder.layers(from: tiny)
        XCTAssertLessThan(layers.count, 2,
                          "24 px ne peut pas produire d'échelle: \(layers.count) couches")
    }

    /// L'échelle doit être strictement croissante en netteté, sinon une couche
    /// « suivante » serait moins bonne que la précédente.
    func testTheLadderIsStrictlyIncreasingAndEndsOnTheOriginal() throws {
        let photo = try makeJPEG(side: 1200)
        let layers = TimedRevealEncoder.layers(from: photo)
        XCTAssertGreaterThanOrEqual(layers.count, 2, "1200 px doit donner une échelle")
        for (a, b) in zip(layers, layers.dropFirst()) {
            XCTAssertLessThan(a.index, b.index, "les rangs doivent croître")
            if let da = a.maxDimension, let db = b.maxDimension {
                XCTAssertLessThan(da, db, "chaque rang doit être PLUS net que le précédent")
            }
        }
        XCTAssertTrue(layers.last!.isFinal, "la dernière couche est l'originale, intacte")
        XCTAssertEqual(layers.last!.jpeg, photo, "et octet pour octet")
    }


    // MARK: - Le cycle complet, simulé

    /// LE test que personne n'avait fait: une image se dénette-t-elle vraiment ?
    ///
    /// On ne peut pas regarder un écran ici, mais on peut vérifier ce qui le
    /// nourrit: à chaque couche appliquée, la présentation doit rendre des
    /// octets STRICTEMENT plus nets, et finir sur l'originale intacte. Si cette
    /// suite est plate, il n'y a pas de révélation — il y a une photo sous un
    /// compte à rebours.
    @MainActor
    func testAPhotoActuallySharpensLayerByLayerUntilItIsTheOriginal() throws {
        let photo = try makeJPEG(side: 1200)
        let layers = TimedRevealEncoder.layers(from: photo)
        XCTAssertGreaterThanOrEqual(layers.count, 3, "il faut une vraie échelle pour ce test")

        let store = TimedRevealStore(defaults: freshDefaults())
        // ⚠️ Une date de 1970 serait ramassée AUSSITÔT par le nouveau
        // ramasse-miettes — à juste titre: une révélation vieille de cinquante
        // ans est perdue. La première version de ce test s'y est fait prendre,
        // et l'échec était le mien, pas celui du code.
        let start = Date()
        let sched = TimedRevealSchedule(layerCount: layers.count,
                                        totalDuration: 400, startedAt: start)!
        let id = "reveal-under-test"
        XCTAssertTrue(store.begin(messageId: id, schedule: sched, layer0: layers[0].jpeg))

        // Couche 0: la plus floue, et la révélation n'est PAS finie.
        var p = try XCTUnwrap(store.presentation(for: id))
        XCTAssertEqual(p.layerIndex, 0)
        XCTAssertFalse(p.isFinished)
        var sizes = [try XCTUnwrap(p.imageData).count]

        // Les couches INTERMÉDIAIRES, dans l'ordre, comme la file les enverra.
        for k in 1..<(layers.count - 1) {
            store.apply(messageId: id, layerIndex: k, bytes: layers[k].jpeg)
            p = try XCTUnwrap(store.presentation(for: id))
            XCTAssertEqual(p.layerIndex, k, "la présentation doit suivre la couche appliquée")
            XCTAssertFalse(p.isFinished, "la couche \(k) n'est pas la dernière")
            sizes.append(try XCTUnwrap(p.imageData).count)
        }

        // Strictement croissant: chaque couche apporte de l'information. Si
        // cette suite était plate, il n'y aurait pas de révélation — il y aurait
        // une photo sous un compte à rebours.
        for (a, b) in zip(sizes, sizes.dropFirst()) {
            XCTAssertLessThan(a, b, "une couche qui n'ajoute rien n'est pas une révélation: \(sizes)")
        }

        // La DERNIÈRE couche a un contrat à part, et il est délibéré: le magasin
        // RELÂCHE sa copie des octets. Le message porte désormais la photo
        // entière, la bulle la rend normalement, et aucune copie en clair ne
        // traîne dans le magasin. Mon premier test l'ignorait et accusait le
        // code d'un défaut qui était le sien.
        let last = layers.count - 1
        store.apply(messageId: id, layerIndex: last, bytes: layers[last].jpeg)
        p = try XCTUnwrap(store.presentation(for: id))
        XCTAssertEqual(p.layerIndex, last)
        XCTAssertTrue(p.isFinished, "après la dernière couche, c'est une photo ordinaire")
        XCTAssertNil(p.imageData,
                     "le magasin doit LÂCHER les octets une fois fini — sinon la photo "
                     + "en clair reste en mémoire pour rien")
        XCTAssertEqual(layers[last].jpeg, photo, "et la dernière couche EST l'originale")
    }

    /// Une couche EN RETARD ne doit pas faire reculer l'image, et l'écran doit
    /// pouvoir le dire — c'est la différence entre « ça arrive » et « c'est figé ».
    @MainActor
    func testALateLayerIsVisibleAsWaitingAndAStaleOneChangesNothing() throws {
        let photo = try makeJPEG(side: 1200)
        let layers = TimedRevealEncoder.layers(from: photo)
        let store = TimedRevealStore(defaults: freshDefaults())
        let start = Date()   // voir la note ci-dessus sur les dates de 1970
        let sched = TimedRevealSchedule(layerCount: layers.count, totalDuration: 400, startedAt: start)!
        _ = store.begin(messageId: "m", schedule: sched, layer0: layers[0].jpeg)
        store.apply(messageId: "m", layerIndex: 2, bytes: layers[2].jpeg)

        // La couche 1 arrive APRÈS la 2: elle est périmée, elle ne doit rien changer.
        store.apply(messageId: "m", layerIndex: 1, bytes: layers[1].jpeg)
        let p = try XCTUnwrap(store.presentation(for: "m"))
        XCTAssertEqual(p.layerIndex, 2, "une couche plus basse ne doit JAMAIS faire reculer l'image")
        XCTAssertEqual(p.imageData, layers[2].jpeg)

        // L'horloge a dépassé la couche 3 alors qu'elle n'est pas là: on attend.
        let late = start.addingTimeInterval(399)
        XCTAssertTrue(p.isWaitingForLate(at: late),
                      "l'écran doit pouvoir dire « en attente », pas tourner indéfiniment")
    }

    /// Une révélation en cours dont plus rien n'arrive ne doit pas être gardée à
    /// vie: sinon le compte à rebours d'une photo qui n'arrivera jamais reste
    /// dans les préférences pour toujours.
    @MainActor
    func testAStalledRevealIsEventuallyForgotten() throws {
        let defaults = freshDefaults()
        let store = TimedRevealStore(defaults: defaults)
        let longAgo = Date().addingTimeInterval(-30 * 24 * 3600)
        let sched = TimedRevealSchedule(layerCount: 4, totalDuration: 3600, startedAt: longAgo)!
        _ = store.begin(messageId: "stalled", schedule: sched, layer0: Data([1, 2, 3]))
        // begin() passe le ramasse-miettes; une entrée d'il y a 30 jours,
        // incomplète, doit avoir disparu.
        _ = store.begin(messageId: "fresh",
                        schedule: TimedRevealSchedule(layerCount: 3, totalDuration: 600,
                                                      startedAt: Date())!,
                        layer0: Data([9]))
        XCTAssertNil(store.presentation(for: "stalled"),
                     "une révélation abandonnée depuis 30 jours doit être oubliée")
        XCTAssertNotNil(store.presentation(for: "fresh"), "et la récente doit rester")
    }

    private func freshDefaults() -> UserDefaults {
        let name = "reveal-test-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    private func makeJPEG(side: Int) throws -> Data {
        let size = CGSize(width: side, height: side)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            // Un dégradé: une image unie se compresse en presque rien et ne
            // permettrait pas de distinguer les couches.
            for x in stride(from: 0, to: side, by: 4) {
                UIColor(hue: CGFloat(x) / CGFloat(side), saturation: 0.9,
                        brightness: 0.9, alpha: 1).setFill()
                ctx.fill(CGRect(x: x, y: 0, width: 4, height: side))
            }
        }
        return try XCTUnwrap(image.jpegData(compressionQuality: 0.9))
    }
}
