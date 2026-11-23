//
//  MediaSendOptionsTests.swift
//  OSHITests
//
//  __REVEAL_OPTION_2026_08_22__ Les options d'envoi d'UNE photo.
//
//  L'exigence, telle que Hugo l'a formulée:
//
//    « Deux personnes discutent, l'une dit à l'autre "tu la verras que dans deux
//      jours", et la personne distingue de mieux en mieux la photo reçue, puis
//      totalement au bout de deux jours. Mais toutes les autres photos et
//      messages envoyés pendant ce temps apparaissent normalement. »
//
//  Deux propriétés en découlent, et ce sont elles qui sont testées ici:
//
//   1. UNE SEULE option à la fois. Une photo qui met deux jours à devenir nette
//      ET disparaît à la première ouverture ne serait jamais vue.
//   2. L'option ne DÉBORDE PAS. Elle appartient à cet envoi-là, pas à la
//      conversation. Le piège est déjà écrit dans RevealMode.swift:
//      `RevealModeManager` retient un mode PAR PAIR jusqu'à ce qu'on le change.
//      Il n'est utilisé nulle part, et il ne doit pas l'être.
//

import XCTest
@testable import OSHI

final class MediaSendOptionsTests: XCTestCase {

    // MARK: - Une seule option à la fois

    func testArmingARevealClearsViewOnce() {
        var o = MediaSendOptions.none
        o.setViewOnce(true)
        o.toggleReveal(2 * 3600)
        XCTAssertFalse(o.isViewOnce, "cocher une durée doit décocher « vue unique »")
        XCTAssertEqual(o.revealDuration, 2 * 3600)
    }

    func testArmingViewOnceClearsTheReveal() {
        var o = MediaSendOptions.none
        o.toggleReveal(48 * 3600)
        o.setViewOnce(true)
        XCTAssertNil(o.revealDuration, "cocher « vue unique » doit désarmer la durée")
        XCTAssertTrue(o.isViewOnce)
    }

    /// Les deux ensemble ne doivent JAMAIS être représentables, quel que soit
    /// l'ordre des gestes.
    func testTheTwoOptionsAreNeverBothOn() {
        var o = MediaSendOptions.none
        for step in 0..<24 {
            if step % 2 == 0 { o.setViewOnce(step % 4 == 0) }
            else { o.toggleReveal(RevealMode.presets[step % RevealMode.presets.count]) }
            XCTAssertFalse(o.isViewOnce && o.revealDuration != nil,
                           "étape \(step): « vue unique » et révélation cochées en même temps")
        }
    }

    /// Retoucher la pastille active la désarme: l'option se referme du même geste
    /// qu'elle s'ouvre.
    func testTappingTheSameDurationTwiceDisarmsIt() {
        var o = MediaSendOptions.none
        o.toggleReveal(8 * 3600)
        o.toggleReveal(8 * 3600)
        XCTAssertNil(o.revealDuration)
        XCTAssertTrue(o.isPlain, "on doit revenir exactement à l'envoi normal")
    }

    // MARK: - L'option ne déborde pas sur l'envoi suivant

    /// C'est la seconde moitié de la demande: « toutes les autres photos et
    /// messages envoyés pendant ce temps apparaissent normalement ».
    func testEachSendStartsFromNothing() {
        var first = MediaSendOptions.none
        first.toggleReveal(2 * 24 * 3600)
        XCTAssertNotNil(first.revealDuration)

        // L'envoi suivant est une AUTRE valeur, pas un mode retrouvé quelque part.
        let next = MediaSendOptions.none
        XCTAssertTrue(next.isPlain, "un nouvel envoi part sans aucune option")
        XCTAssertNil(next.revealDuration)
        XCTAssertFalse(next.isViewOnce)
    }

    // MARK: - Les durées proposées

    func testPresetsCoverMinutesToAWeekAndAreOrdered() {
        // Annotation nécessaire: `5 * 60` s'infère en `Int`, et comparer
        // `[TimeInterval]` à `[Int]` fait retomber le littéral sur `Any`.
        let expected: [TimeInterval] = [
            5 * 60, 20 * 60, 60 * 60, 2 * 3600,
            8 * 3600, 24 * 3600, 48 * 3600, 7 * 24 * 3600,
        ]
        XCTAssertEqual(RevealMode.presets, expected)
        XCTAssertEqual(RevealMode.presets, RevealMode.presets.sorted(),
                       "les pastilles se lisent de gauche à droite: elles doivent croître")
    }

    /// 🔴 Le formatteur d'origine passait par `UnitDuration`, qui ne connaît ni le
    /// jour ni la semaine: une semaine s'affichait « 168 h ». Et deux pastilles
    /// portant le même texte seraient indiscernables à l'écran.
    func testEveryDurationGetsItsOwnNonEmptyLabel() {
        let labels = RevealMode.presets.map { RevealDurationFormat.short($0) }
        for (d, label) in zip(RevealMode.presets, labels) {
            XCTAssertFalse(label.isEmpty, "durée \(d) s sans libellé")
        }
        XCTAssertEqual(Set(labels).count, labels.count,
                       "deux durées portent le même libellé: \(labels)")
    }

    /// Un reste négatif — l'horloge a passé la fin — doit se lire comme « c'est
    /// fini », jamais comme « -3 min ».
    func testRemainingNeverGoesNegative() {
        let past = Date().addingTimeInterval(-3600)
        let text = RevealDurationFormat.remaining(until: past)
        XCTAssertFalse(text.contains("-"), "reste négatif affiché: \(text)")
    }
}
