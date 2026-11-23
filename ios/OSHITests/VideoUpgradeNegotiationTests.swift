//
//  VideoUpgradeNegotiationTests.swift
//  OSHITests
//
//  La négociation audio→vidéo n'avait AUCUN test, sur aucune des deux
//  plateformes: pas un seul test n'envoyait un 0x0E. C'est dans ce vide qu'ont
//  vécu cinq défauts, dont « la personne qui refuse se met à diffuser sa
//  caméra ».
//
//  POURQUOI UN MODÈLE ET PAS `VoiceCallManager`. Le vrai gestionnaire est soudé
//  à CallKit, AVAudioEngine, la poussée VoIP et des sockets UDP vivantes: rien
//  de tout ça ne se pilote sans deux appareils réels. Le fichier
//  `CallScenarioTests` a déjà tranché cette question de la même façon, et sa
//  règle vaut ici: on ne fait JAMAIS passer à vide une assertion qui demande du
//  vrai média.
//
//  Ce que ce modèle reproduit, ce sont les RÈGLES DE DÉCISION — celles que l'on
//  vient de corriger, et qui sont du calcul pur:
//    • un ACCEPT n'est pris en compte que si l'on a une demande en cours;
//    • refuser annule aussi notre propre demande en vol;
//    • le destinataire a un délai, et il REFUSE plutôt que de se taire;
//    • recevoir des trames vidéo prouve que le pair est passé en vidéo, et nous
//      y fait basculer en RÉCEPTION SEULE;
//    • « le pair n'a rien annoncé » (nil) ne se comporte pas comme « le pair a
//      dit non » (false).
//
//  Ce que ce modèle ne prouve PAS, et qu'il ne faut pas lui faire dire: que les
//  octets partent vraiment sur le réseau, que CallKit suit, que la caméra
//  démarre. Ça, il faut deux téléphones.
//

import XCTest
import AVFoundation
@testable import OSHI

/// Le modèle de référence de la négociation, tenu par les tests.
/// Il porte les mêmes drapeaux que `VoiceCallManager`, avec les mêmes noms, pour
/// qu'une divergence saute aux yeux à la relecture.
private struct VideoUpgradeModel {

    enum Code: UInt8 {
        case request = 0x01
        case accept = 0x02
        case decline = 0x03
        case stop = 0x04
        case acceptWatch = 0x05
        case cameraOn = 0x06
        case cameraOff = 0x07
    }

    // L'état, exactement celui du gestionnaire.
    var isCurrentCallVideo = false
    var isAwaitingVideoUpgrade = false
    var pendingVideoUpgradeRequest = false
    var shareOwnVideo = true
    var isRemoteCameraPaused = false
    var declinedReason: String?
    var peerSupportsVideo: Bool?
    var cameraAccessDenied = false

    /// Ce qui est parti sur le fil, pour pouvoir l'affirmer.
    private(set) var sent: [Code] = []
    /// Les délais armés, nommés — on ne teste pas des secondes, on teste qui
    /// surveille quoi.
    private(set) var requesterTimerArmed = false
    private(set) var calleeTimerArmed = false

    // MARK: - Actions locales

    mutating func tapVideo() {
        guard peerSupportsVideo != false else {
            declinedReason = "Their app can't do video calls"
            return
        }
        guard !cameraAccessDenied else {
            declinedReason = "Camera access is off for OSHI"
            return
        }
        declinedReason = nil
        isAwaitingVideoUpgrade = true
        shareOwnVideo = true
        isRemoteCameraPaused = false
        requesterTimerArmed = true
        sent.append(.request)
    }

    mutating func tapAccept(shareCamera: Bool = true) {
        var shareCamera = shareCamera
        if shareCamera && cameraAccessDenied {
            shareCamera = false
            declinedReason = "Camera access is off — joining as watch only"
        }
        requesterTimerArmed = false
        calleeTimerArmed = false
        pendingVideoUpgradeRequest = false
        isAwaitingVideoUpgrade = false
        shareOwnVideo = shareCamera
        isRemoteCameraPaused = false
        isCurrentCallVideo = true
        sent.append(shareCamera ? .accept : .acceptWatch)
    }

    mutating func tapDecline() {
        requesterTimerArmed = false
        calleeTimerArmed = false
        pendingVideoUpgradeRequest = false
        isAwaitingVideoUpgrade = false
        sent.append(.decline)
    }

    /// Le délai du destinataire expire: il refuse pour de bon.
    mutating func calleeTimeoutFires() {
        guard pendingVideoUpgradeRequest else { return }
        tapDecline()
    }

    /// Le délai du demandeur expire.
    mutating func requesterTimeoutFires() {
        guard isAwaitingVideoUpgrade else { return }
        isAwaitingVideoUpgrade = false
        requesterTimerArmed = false
        declinedReason = peerSupportsVideo == nil
            ? "No answer — their version may not support video"
            : "No answer"
    }

    /// La caméra n'a pas démarré.
    mutating func cameraFailed(_ reason: String) {
        shareOwnVideo = false
        declinedReason = reason
        sent.append(.cameraOff)
    }

    // MARK: - Réception

    mutating func receive(_ code: Code) {
        switch code {
        case .request:
            guard !isCurrentCallVideo else { return }
            pendingVideoUpgradeRequest = true
            calleeTimerArmed = true
        case .accept, .acceptWatch:
            // LA règle corrigée: un ACCEPT non sollicité n'allume rien.
            guard isAwaitingVideoUpgrade else { return }
            isAwaitingVideoUpgrade = false
            requesterTimerArmed = false
            isRemoteCameraPaused = (code == .acceptWatch)
            isCurrentCallVideo = true
        case .decline:
            isAwaitingVideoUpgrade = false
            requesterTimerArmed = false
            declinedReason = "Peer declined"
        case .stop:
            isCurrentCallVideo = false
            shareOwnVideo = true
            isRemoteCameraPaused = false
        case .cameraOn:
            isRemoteCameraPaused = false
        case .cameraOff:
            isRemoteCameraPaused = true
        }
    }

    /// Des trames vidéo arrivent alors qu'on se croit en audio.
    mutating func receiveVideoFrame() {
        guard !isCurrentCallVideo else { return }
        requesterTimerArmed = false
        calleeTimerArmed = false
        pendingVideoUpgradeRequest = false
        isAwaitingVideoUpgrade = false
        declinedReason = nil
        shareOwnVideo = false          // réception seule: on ne diffuse pas
        isRemoteCameraPaused = false
        isCurrentCallVideo = true
    }

    /// Fin d'appel: ce que `cleanup()` doit remettre à zéro.
    mutating func cleanup() {
        isCurrentCallVideo = false
        pendingVideoUpgradeRequest = false
        isAwaitingVideoUpgrade = false
        declinedReason = nil
        isRemoteCameraPaused = false
        shareOwnVideo = true
        requesterTimerArmed = false
        calleeTimerArmed = false
    }
}

final class VideoUpgradeNegotiationTests: XCTestCase {

    // MARK: - H2: l'ACCEPT non sollicité

    /// LE défaut signalé: « l'un accepte la vidéo et pas l'autre ».
    /// Les deux appuient sur Vidéo, B refuse — et l'ACCEPT de A arrivait ensuite
    /// allumer la caméra de B, qui venait pourtant de refuser.
    func testDecliningInAGlareDoesNotStartYourCamera() {
        var b = VideoUpgradeModel()
        b.tapVideo()                 // B demande aussi, au même instant
        b.receive(.request)          // la demande de A arrive
        XCTAssertTrue(b.pendingVideoUpgradeRequest)

        b.tapDecline()               // B refuse
        XCTAssertFalse(b.isAwaitingVideoUpgrade,
                       "refuser doit annuler NOTRE demande en vol, sinon leur ACCEPT nous rattrape")

        b.receive(.accept)           // A avait accepté avant de voir le refus
        XCTAssertFalse(b.isCurrentCallVideo,
                       "B vient de refuser: sa caméra ne doit pas s'allumer")
    }

    /// Un ACCEPT en retard, après qu'on a été prévenu « pas de réponse ».
    func testALateAcceptAfterTimeoutIsIgnored() {
        var a = VideoUpgradeModel()
        a.tapVideo()
        a.requesterTimeoutFires()
        XCTAssertNotNil(a.declinedReason)

        a.receive(.accept)
        XCTAssertFalse(a.isCurrentCallVideo,
                       "on nous a dit « pas de réponse »: un ACCEPT en retard ne doit pas nous tirer en vidéo")
    }

    /// Un ACCEPT dupliqué par la retransmission ne doit rien casser.
    func testRetransmittedAcceptsAreHarmless() {
        var a = VideoUpgradeModel()
        a.tapVideo()
        a.receive(.accept)
        XCTAssertTrue(a.isCurrentCallVideo)
        XCTAssertFalse(a.isRemoteCameraPaused)

        a.receive(.accept)           // 2e envoi
        a.receive(.acceptWatch)      // 3e envoi, même si le code diffère
        XCTAssertTrue(a.isCurrentCallVideo)
        XCTAssertFalse(a.isRemoteCameraPaused,
                       "un doublon ne doit pas réécrire l'état négocié")
    }

    /// Le chemin normal continue de marcher — c'est ce qu'on protège.
    func testTheHappyPathStillWorks() {
        var a = VideoUpgradeModel()
        var b = VideoUpgradeModel()
        a.tapVideo()
        b.receive(.request)
        b.tapAccept()
        a.receive(.accept)

        XCTAssertTrue(a.isCurrentCallVideo)
        XCTAssertTrue(b.isCurrentCallVideo)
        XCTAssertTrue(a.shareOwnVideo)
        XCTAssertTrue(b.shareOwnVideo)
    }

    /// « Regarder seulement »: B voit A, A voit un cache pour B.
    func testWatchOnlyLeavesTheRequesterSeeingAPlaceholder() {
        var a = VideoUpgradeModel()
        var b = VideoUpgradeModel()
        a.tapVideo()
        b.receive(.request)
        b.tapAccept(shareCamera: false)
        a.receive(.acceptWatch)

        XCTAssertTrue(a.isCurrentCallVideo)
        XCTAssertTrue(a.isRemoteCameraPaused, "B ne diffuse pas: A doit voir le cache")
        XCTAssertFalse(b.shareOwnVideo)
    }

    // MARK: - H4: le délai du destinataire

    /// La bannière n'est plus éternelle, et le pair est PRÉVENU.
    func testTheCalleeTimesOutAndTellsThePeer() {
        var b = VideoUpgradeModel()
        b.receive(.request)
        XCTAssertTrue(b.calleeTimerArmed, "la demande reçue doit armer un délai")

        b.calleeTimeoutFires()
        XCTAssertFalse(b.pendingVideoUpgradeRequest, "la bannière doit disparaître")
        XCTAssertEqual(b.sent.last, .decline,
                       "se taire laisserait le demandeur ignorer si sa demande est morte en route")
    }

    /// Et ce refus automatique ferme la fenêtre du « accepter trop tard ».
    func testTheCalleeTimeoutClosesTheLateAcceptWindow() {
        var a = VideoUpgradeModel()
        var b = VideoUpgradeModel()
        a.tapVideo()
        b.receive(.request)
        b.calleeTimeoutFires()
        a.receive(.decline)

        XCTAssertFalse(a.isAwaitingVideoUpgrade)
        // B ne peut plus accepter: il n'a plus de demande en attente.
        b.calleeTimeoutFires()
        XCTAssertFalse(b.isCurrentCallVideo)
    }

    // MARK: - H1: le rattrapage par les trames

    /// L'ACCEPT s'est perdu. B diffuse, A est resté en audio — et, avant, rien
    /// ne pouvait jamais les remettre d'accord.
    func testIncomingVideoFramesHealASplitCall() {
        var a = VideoUpgradeModel()
        a.tapVideo()
        a.requesterTimeoutFires()          // l'ACCEPT n'est jamais arrivé
        XCTAssertFalse(a.isCurrentCallVideo)

        a.receiveVideoFrame()              // mais B diffuse bel et bien
        XCTAssertTrue(a.isCurrentCallVideo, "des trames vidéo prouvent que le pair est en vidéo")
        XCTAssertFalse(a.shareOwnVideo,
                       "suivre le pair ne doit jamais vouloir dire diffuser sans l'avoir demandé")
        XCTAssertNil(a.declinedReason, "le message « pas de réponse » n'a plus lieu d'être")
    }

    /// Le rattrapage ne doit rien faire quand on est déjà en vidéo.
    func testFramesDoNotDisturbACallAlreadyInVideo() {
        var a = VideoUpgradeModel()
        a.tapVideo()
        a.receive(.accept)
        XCTAssertTrue(a.shareOwnVideo)

        a.receiveVideoFrame()
        XCTAssertTrue(a.shareOwnVideo, "on ne doit pas couper notre caméra en cours de route")
    }

    // MARK: - H5: la caméra refusée

    /// Demander la vidéo sans pouvoir filmer: on le dit tout de suite.
    func testRequestingWithoutCameraAccessFailsImmediately() {
        var a = VideoUpgradeModel()
        a.cameraAccessDenied = true
        a.tapVideo()

        XCTAssertFalse(a.isAwaitingVideoUpgrade, "pas de rond qui tourne quinze secondes")
        XCTAssertTrue(a.sent.isEmpty, "rien ne part sur le fil")
        XCTAssertNotNil(a.declinedReason, "et on DIT pourquoi")
    }

    /// Accepter sans caméra: on rejoint en réception seule plutôt que de refuser.
    func testAcceptingWithoutCameraJoinsAsWatchOnly() {
        var b = VideoUpgradeModel()
        b.cameraAccessDenied = true
        b.receive(.request)
        b.tapAccept(shareCamera: true)

        XCTAssertTrue(b.isCurrentCallVideo, "on peut toujours les VOIR")
        XCTAssertFalse(b.shareOwnVideo)
        XCTAssertEqual(b.sent.last, .acceptWatch,
                       "le pair doit savoir qu'il n'aura pas nos trames")
        XCTAssertNotNil(b.declinedReason)
    }

    /// Un échec de caméra en cours de route prévient le pair.
    func testACameraFailureTellsThePeer() {
        var b = VideoUpgradeModel()
        b.receive(.request)
        b.tapAccept()
        b.cameraFailed("Camera permission denied")

        XCTAssertEqual(b.sent.last, .cameraOff,
                       "sans ça le pair fixe un cache « en direct » qui n'arrivera jamais")
        XCTAssertNotNil(b.declinedReason)
    }

    /// Et côté pair, ce 0x07 doit bien basculer le cache.
    func testCameraOffFlipsThePeersPlaceholder() {
        var a = VideoUpgradeModel()
        a.tapVideo()
        a.receive(.accept)
        XCTAssertFalse(a.isRemoteCameraPaused)

        a.receive(.cameraOff)
        XCTAssertTrue(a.isRemoteCameraPaused)
        a.receive(.cameraOn)
        XCTAssertFalse(a.isRemoteCameraPaused)
    }

    // MARK: - H7: la capacité vidéo

    /// Un pair qui a dit non: on ne le fait pas attendre quinze secondes.
    func testAPeerWhoDeclaredNoVideoIsRefusedImmediately() {
        var a = VideoUpgradeModel()
        a.peerSupportsVideo = false
        a.tapVideo()

        XCTAssertTrue(a.sent.isEmpty)
        XCTAssertFalse(a.isAwaitingVideoUpgrade)
        XCTAssertNotNil(a.declinedReason)
    }

    /// ⚠️ LE test qui protège la compatibilité: « rien annoncé » n'est PAS
    /// « a dit non ». Les confondre désactiverait la vidéo avec tous les
    /// téléphones pas encore mis à jour.
    func testAPeerWhoDeclaredNothingIsStillAsked() {
        var a = VideoUpgradeModel()
        a.peerSupportsVideo = nil
        a.tapVideo()

        XCTAssertEqual(a.sent, [.request], "un pair silencieux doit être sollicité comme avant")
        XCTAssertTrue(a.isAwaitingVideoUpgrade)
    }

    /// Et l'expiration le dit honnêtement plutôt que « pas de réponse ».
    func testTimeoutBlamesTheVersionWhenNothingWasDeclared() {
        var a = VideoUpgradeModel()
        a.peerSupportsVideo = nil
        a.tapVideo()
        a.requesterTimeoutFires()
        XCTAssertEqual(a.declinedReason, "No answer — their version may not support video")

        var b = VideoUpgradeModel()
        b.peerSupportsVideo = true
        b.tapVideo()
        b.requesterTimeoutFires()
        XCTAssertEqual(b.declinedReason, "No answer")
    }

    // MARK: - H6: la fuite entre appels

    /// « Regarder seulement » puis raccrocher ne doit pas tuer la caméra du
    /// PROCHAIN appel vidéo.
    func testWatchOnlyDoesNotLeakIntoTheNextCall() {
        var m = VideoUpgradeModel()
        m.receive(.request)
        m.tapAccept(shareCamera: false)
        XCTAssertFalse(m.shareOwnVideo)

        m.cleanup()
        XCTAssertTrue(m.shareOwnVideo,
                      "sinon l'appel vidéo suivant part en réception seule et la caméra ne démarre jamais")
    }

    /// La fin d'appel doit désarmer LES DEUX délais.
    func testCleanupDisarmsBothTimers() {
        var m = VideoUpgradeModel()
        m.tapVideo()
        m.receive(.request)
        XCTAssertTrue(m.requesterTimerArmed)
        XCTAssertTrue(m.calleeTimerArmed)

        m.cleanup()
        XCTAssertFalse(m.requesterTimerArmed)
        XCTAssertFalse(m.calleeTimerArmed)
    }

    // MARK: - La descente

    /// Redescendre en audio remet les deux côtés d'accord.
    func testStoppingVideoReturnsBothSidesToVoice() {
        var a = VideoUpgradeModel()
        a.tapVideo()
        a.receive(.acceptWatch)
        XCTAssertTrue(a.isRemoteCameraPaused)

        a.receive(.stop)
        XCTAssertFalse(a.isCurrentCallVideo)
        XCTAssertTrue(a.shareOwnVideo, "prêt pour la prochaine montée")
        XCTAssertFalse(a.isRemoteCameraPaused)
    }

    /// Les opcodes sont un contrat d'interopérabilité: Android lit ces mêmes
    /// octets. Les changer casse les appels entre les deux plateformes.
    func testTheOpcodesAreTheOnesOnTheWire() {
        XCTAssertEqual(VideoUpgradeModel.Code.request.rawValue, 0x01)
        XCTAssertEqual(VideoUpgradeModel.Code.accept.rawValue, 0x02)
        XCTAssertEqual(VideoUpgradeModel.Code.decline.rawValue, 0x03)
        XCTAssertEqual(VideoUpgradeModel.Code.stop.rawValue, 0x04)
        XCTAssertEqual(VideoUpgradeModel.Code.acceptWatch.rawValue, 0x05)
        XCTAssertEqual(VideoUpgradeModel.Code.cameraOn.rawValue, 0x06)
        XCTAssertEqual(VideoUpgradeModel.Code.cameraOff.rawValue, 0x07)
    }
}

/// __B2_VIDEO_UPGRADE_KEEPS_ROUTE_2026_09_23__ Passer en vidéo ne doit pas faire sauter
/// l'appel de l'écouteur au haut-parleur: `.videoChat` implique `.defaultToSpeaker`.
/// Et ne pas réintroduire `.videoChat` sur l'écouteur (écho mesuré sur 6216CD2F).

final class VideoUpgradeKeepsRouteTests: XCTestCase {

    func testUpgradeOnTheEarpieceStaysOffVideoChat() {
        let mode = VoiceCallManager.videoUpgradeAudioMode(speakerOn: false, hasEarpiece: true)
        XCTAssertNotEqual(mode, .videoChat, "l'écouteur ne doit pas basculer au haut-parleur")
        XCTAssertEqual(mode, VoiceCallManager.sessionShape(speaker: false).0)
    }

    func testUpgradeOnTheSpeakerKeepsVideoChat() {
        XCTAssertEqual(VoiceCallManager.videoUpgradeAudioMode(speakerOn: true, hasEarpiece: true), .videoChat)
    }

    func testDeviceWithoutEarpieceIsAlwaysSpeakerShaped() {
        XCTAssertEqual(VoiceCallManager.videoUpgradeAudioMode(speakerOn: false, hasEarpiece: false), .videoChat)
    }
}

// MARK: - __VIDEO_MEDIA_CONTROL_2026_09_23__ / __VIDEO_ABR_2026_09_23__

/// The 9-byte media-channel video controls Android and the desktop send, and what the
/// encoder does with the peer's keyframe requests. Pure types from VideoCallManager.swift.
final class VideoMediaControlTests: XCTestCase {

    private func control(_ type: UInt8, ts: UInt64 = 1_758_000_000_000) -> Data {
        var d = Data([type])
        var be = ts.bigEndian
        d.append(Data(bytes: &be, count: 8))
        return d
    }

    func testDesktopAndAndroidNineByteControlsAreRecognised() {
        XCTAssertEqual(VideoMediaControl.classify(control(0x0B)), .keyframeRequest)
        XCTAssertEqual(VideoMediaControl.classify(control(0x0C)), .cameraPaused)
        XCTAssertEqual(VideoMediaControl.classify(control(0x0D)), .cameraResumed)
    }

    /// A longer 0x0D is the SEALED in-band hang-up; audio and video are never 9 bytes.
    func testAnythingElseIsNotAControl() {
        var sealedEnd = control(0x0D)
        sealedEnd.append(Data(count: 40))
        XCTAssertNil(VideoMediaControl.classify(sealedEnd))
        XCTAssertNil(VideoMediaControl.classify(control(0x05)))   // AAC-ELD header shape
        XCTAssertNil(VideoMediaControl.classify(control(0x0E)))   // sealed upgrade type
        XCTAssertNil(VideoMediaControl.classify(Data([0x0B])))
        XCTAssertNil(VideoMediaControl.classify(Data()))
    }

    /// A slice (non-zero startIndex, as mesh/NotificationCenter hand us) still classifies.
    func testASliceIsClassifiedByItsFirstByte() {
        let buf = Data([0xFF]) + control(0x0B)
        XCTAssertEqual(VideoMediaControl.classify(buf.dropFirst()), .keyframeRequest)
    }

    /// ~2/s: one IDR per 0.5 s however fast the requests come.
    func testPeerRequestsForceAtMostTwoIdrsPerSecond() {
        var g = PeerKeyframeRequests()
        let t0 = Date(timeIntervalSince1970: 1_000)
        g.startedAt = t0
        var idrs = 0
        for i in 0..<32 {  // 32 requests over 2 s (one every 62.5 ms — exact in binary)
            if g.note(at: t0.addingTimeInterval(Double(i) * 0.0625)) { idrs += 1 }
        }
        XCTAssertEqual(g.received, 32)
        XCTAssertEqual(idrs, 4)
    }

    /// The requests are the peer's report on OUR uplink: they lower the bitrate factor,
    /// except during the 3 s decoder warm-up, and the pressure fades after 3 s.
    func testPeerRequestsAreUplinkPressureAfterWarmUp() {
        var g = PeerKeyframeRequests()
        let t0 = Date(timeIntervalSince1970: 1_000)
        g.startedAt = t0
        _ = g.note(at: t0.addingTimeInterval(1))            // warm-up: ignored
        XCTAssertEqual(g.pressureFactor(at: t0.addingTimeInterval(1)), 1.0, accuracy: 1e-9)
        for i in 0..<5 { _ = g.note(at: t0.addingTimeInterval(4 + Double(i) * 0.2)) }
        XCTAssertEqual(g.pressureFactor(at: t0.addingTimeInterval(5)), 0.60, accuracy: 1e-9)
        for i in 0..<20 { _ = g.note(at: t0.addingTimeInterval(5 + Double(i) * 0.05)) }
        XCTAssertEqual(g.pressureFactor(at: t0.addingTimeInterval(6)), 0.35, accuracy: 1e-9, "floor")
        XCTAssertEqual(g.pressureFactor(at: t0.addingTimeInterval(10)), 1.0, accuracy: 1e-9, "faded")
    }
}
