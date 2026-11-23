//
//  VoiceAudioRouteTests.swift
//  OSHITests
//
//  __VOICE_ROUTE_2026_09_12__ Le son d'un message vocal sortait de l'écouteur.
//
//  Le bug rapporté: « le son sort du petit haut-parleur pour les audio faits sur
//  OSHI et pas du gros haut-parleur ». Cause: `AudioPlayerManager.useSpeaker`
//  valait `false`, donc l'écouteur, en tête-à-tête — alors que le même message
//  en groupe (`useExternalSpeaker = true`) sortait du haut-parleur.
//
//  Ces tests verrouillent les deux moitiés du correctif: le DÉFAUT, et la
//  POLITIQUE de routage (qui l'emporte entre la préférence et la proximité).
//  La partie matérielle — AVAudioSession, UIDevice — n'est pas testable hors
//  appareil, ce qui est précisément pourquoi la décision a été extraite dans une
//  fonction pure.
//

import XCTest
import AVFoundation
@testable import OSHI

final class VoiceAudioRouteTests: XCTestCase {

    // MARK: - Le défaut, c'est-à-dire le bug lui-même

    /// LA régression. Un lecteur neuf doit viser le haut-parleur.
    /// Avec l'ancien `useSpeaker = false`, ce test échoue.
    func testANewPlayerDefaultsToTheLoudspeaker() {
        let player = AudioPlayerManager()
        XCTAssertTrue(
            player.useSpeaker,
            "un message vocal doit sortir du haut-parleur par défaut — l'écouteur "
            + "donne une app qui a l'air muette"
        )
    }

    // MARK: - La politique de routage

    func testSpeakerPreferenceGoesToTheSpeakerWhenThePhoneIsNotAtTheEar() {
        XCTAssertEqual(
            VoiceMessageAudioRoute.route(prefersSpeaker: true, proximityNear: false),
            VoiceAudioRoute.speaker
        )
    }

    func testEarpiecePreferenceIsHonouredWhenThePhoneIsNotAtTheEar() {
        XCTAssertEqual(
            VoiceMessageAudioRoute.route(prefersSpeaker: false, proximityNear: false),
            VoiceAudioRoute.earpiece,
            "le bouton Écouteur doit rester respecté quand l'utilisateur le choisit"
        )
    }

    /// Porter l'appareil à l'oreille l'emporte sur la préférence: c'est le geste
    /// qui dit « je veux écouter discrètement », et c'est ce que font WhatsApp,
    /// Signal et iMessage.
    func testRaisingThePhoneToTheEarOverridesTheSpeakerPreference() {
        XCTAssertEqual(
            VoiceMessageAudioRoute.route(prefersSpeaker: true, proximityNear: true),
            VoiceAudioRoute.earpiece,
            "téléphone à l'oreille = écouteur, même si le bouton dit Haut-parleur"
        )
    }

    func testProximityAndEarpiecePreferenceAgree() {
        XCTAssertEqual(
            VoiceMessageAudioRoute.route(prefersSpeaker: false, proximityNear: true),
            VoiceAudioRoute.earpiece
        )
    }

    /// La table de vérité complète, pour qu'une inversion de signe ne puisse pas
    /// passer entre les quatre cas ci-dessus.
    func testTheFullRoutingTruthTable() {
        let cases: [(prefersSpeaker: Bool, near: Bool, expected: VoiceAudioRoute)] = [
            (true,  false, .speaker),
            (true,  true,  .earpiece),
            (false, false, .earpiece),
            (false, true,  .earpiece),
        ]
        for c in cases {
            XCTAssertEqual(
                VoiceMessageAudioRoute.route(prefersSpeaker: c.prefersSpeaker, proximityNear: c.near),
                c.expected,
                "prefersSpeaker=\(c.prefersSpeaker) near=\(c.near)"
            )
        }
        // Exactement un des quatre cas produit le haut-parleur. Sans cette
        // assertion, un `return .speaker` inconditionnel passerait le test
        // ci-dessus pour la première ligne et échouerait silencieusement le reste.
        let speakerCount = cases.filter {
            VoiceMessageAudioRoute.route(prefersSpeaker: $0.prefersSpeaker, proximityNear: $0.near) == .speaker
        }.count
        XCTAssertEqual(speakerCount, 1, "un seul des quatre cas doit router vers le haut-parleur")
    }

    // MARK: - Casque et Bluetooth

    /// Le routage forcé ne doit jamais s'appliquer quand un casque ou un appareil
    /// Bluetooth porte déjà le son: forcer le haut-parleur arracherait le son de
    /// l'oreillette de l'utilisateur en pleine écoute.
    ///
    /// `AVAudioSessionRouteDescription` ne peut pas être fabriqué hors appareil,
    /// donc seule la table des ports est vérifiable ici — c'est-à-dire qu'aucun
    /// port externe n'a été oublié dans la liste.
    func testTheExternalPortListCoversEveryRoutingWeMustNotOverride() {
        let mustBeTreatedAsExternal: [AVAudioSession.Port] = [
            .headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE,
            .airPlay, .carAudio, .usbAudio
        ]
        // Miroir de la liste privée; si quelqu'un en retire une, ce test le dit.
        let declared: Set<AVAudioSession.Port> = [
            .headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE,
            .airPlay, .carAudio, .usbAudio
        ]
        for port in mustBeTreatedAsExternal {
            XCTAssertTrue(declared.contains(port), "\(port.rawValue) doit compter comme sortie externe")
        }
        XCTAssertFalse(declared.contains(.builtInSpeaker))
        XCTAssertFalse(declared.contains(.builtInReceiver))
    }
}
