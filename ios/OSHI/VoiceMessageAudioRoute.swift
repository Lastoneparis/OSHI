//
//  VoiceMessageAudioRoute.swift
//  OSHI
//
//  __VOICE_ROUTE_2026_09_12__ Où sort le son d'un message vocal.
//
//  LE DÉFAUT ÉTAIT L'ÉCOUTEUR, ET C'EST POURQUOI L'APP AVAIT L'AIR CASSÉE.
//  Quatre chemins de lecture existaient, avec trois comportements différents:
//  `AudioPlayerManager` (1-à-1) et `MediaMessageView` partaient sur l'écouteur
//  (`useSpeaker = false`), `GroupViews` sur le haut-parleur, et
//  `MediaGalleryView` faisait la seule chose correcte. Le même message vocal
//  sortait donc du gros haut-parleur en groupe et du petit écouteur en
//  tête-à-tête. L'utilisateur n'entend rien, conclut que le son est cassé, et
//  ne trouve jamais le bouton Haut-parleur/Écouteur enfoui dans la bulle.
//
//  CE QUE FONT LES AUTRES. WhatsApp, Signal et iMessage partent TOUS du
//  haut-parleur et ne basculent sur l'écouteur que lorsque le capteur de
//  proximité voit le téléphone contre l'oreille. C'est ce que fait ce fichier.
//
//  POURQUOI DEUX CATÉGORIES ET PAS UNE. `.playback` ne sait PAS parler à
//  l'écouteur — c'est une limite d'AVAudioSession, pas un choix. Seul
//  `.playAndRecord` le peut. On ne peut donc pas « juste utiliser .playback »:
//  il faut changer de catégorie selon la route voulue. Conséquence heureuse: en
//  mode haut-parleur, qui est le cas normal, on est en `.playback` et le micro
//  n'est JAMAIS engagé — donc plus d'indicateur orange « micro actif » pendant
//  qu'on ne fait qu'écouter. Dans une app de messagerie privée, ça compte.
//
//  CASQUE ET BLUETOOTH. Si un casque ou un appareil Bluetooth est branché, on ne
//  force rien du tout: l'utilisateur a déjà dit où il voulait le son.
//

import Foundation
import AVFoundation
#if canImport(UIKit)
import UIKit
#endif

/// Où l'on veut que le son sorte.
enum VoiceAudioRoute: Equatable {
    /// Haut-parleur principal (ou casque/Bluetooth si présent).
    case speaker
    /// Écouteur — le petit haut-parleur du haut, pour écouter discrètement.
    case earpiece
}

/// À n'utiliser que depuis le fil principal — comme tout le reste de la
/// configuration audio de l'app, et comme l'exige `UIDevice`. Délibérément PAS
/// `@MainActor`: les quatre appelants sont des vues SwiftUI et des
/// `ObservableObject` non isolés, et les marquer aurait propagé `await` dans
/// toute la couche UI pour un gain nul — la session audio se configure de toute
/// façon de manière synchrone, juste avant que le lecteur ne démarre.
final class VoiceMessageAudioRoute {
    static let shared = VoiceMessageAudioRoute()

    /// Préférence explicite de l'utilisateur (le bouton dans la bulle).
    /// Le défaut est le haut-parleur: c'est tout l'objet de ce fichier.
    private(set) var prefersSpeaker = true

    private var isPlaying = false
    private var proximityObserver: NSObjectProtocol?

    private init() {}

    // MARK: - La décision, isolée pour être testable

    /// La politique de routage, séparée de tout matériel afin d'être testable
    /// sans appareil: une fonction pure, sans AVAudioSession ni UIDevice.
    ///
    /// Le téléphone porté à l'oreille l'emporte TOUJOURS sur la préférence:
    /// quelqu'un qui colle l'appareil à son oreille veut écouter discrètement,
    /// quel que soit le réglage du bouton.
    static func route(prefersSpeaker: Bool, proximityNear: Bool) -> VoiceAudioRoute {
        if proximityNear { return .earpiece }
        return prefersSpeaker ? .speaker : .earpiece
    }

    /// Vrai si un casque filaire ou un appareil Bluetooth porte déjà le son. Dans
    /// ce cas on ne force ni haut-parleur ni écouteur.
    static func isExternalOutputAttached(_ route: AVAudioSessionRouteDescription) -> Bool {
        let external: Set<AVAudioSession.Port> = [
            .headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE,
            .airPlay, .carAudio, .usbAudio
        ]
        return route.outputs.contains { external.contains($0.portType) }
    }

    // MARK: - Cycle de vie de la lecture

    /// À appeler juste avant de démarrer un message vocal.
    func beginPlayback() {
        isPlaying = true
        startProximityMonitoring()
        applyCurrentRoute()
    }

    /// À appeler quand la lecture s'arrête, se termine, ou que la bulle disparaît.
    func endPlayback() {
        isPlaying = false
        stopProximityMonitoring()

        // __VOICE_ROUTE_CALL_GUARD_2026_09_12__ NE JAMAIS désactiver la session
        // pendant un appel. Régression introduite le jour même par ce fichier.
        //
        // `AVAudioSession.sharedInstance()` est PARTAGÉE avec l'appel. Rendre la
        // session ici est correct quand on vient d'écouter un message vocal et
        // que rien d'autre ne joue — mais si un appel est en cours, ce
        // `setActive(false)` coupe l'audio DE L'APPEL. Pire: pendant un appel
        // CallKit, c'est CallKit qui possède la session (voir
        // `callKitOwnsAudioSession`), et la désactiver dans son dos casse le
        // graphe audio sans que rien ne le signale — le correspondant est
        // toujours là, et plus personne ne s'entend.
        //
        // Le scénario n'a rien de théorique: un message vocal qui finit de jouer
        // au moment où un appel arrive, ou qu'on lance depuis la conversation,
        // suffit. On rend donc la session UNIQUEMENT hors appel.
        let callIsLive: Bool = {
            switch VoiceCallManager.shared.callState {
            case .idle, .ended:      return false
            case .connecting, .ringing, .inCall: return true
            }
        }()
        guard !callIsLive else {
            OshiLog.call.info("🔊 VoiceMessageAudioRoute: call in progress — leaving the audio session alone")
            return
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Le bouton Haut-parleur/Écouteur. Applique la route À CHAUD: contrairement
    /// à l'ancien code, on ne redémarre pas la lecture — redémarrer faisait
    /// repartir le message du début, ou sautait à une position approximative.
    func toggleSpeaker() {
        prefersSpeaker.toggle()
        if isPlaying { applyCurrentRoute() }
    }

    func setPrefersSpeaker(_ value: Bool) {
        prefersSpeaker = value
        if isPlaying { applyCurrentRoute() }
    }

    // MARK: - Application

    private var proximityNear: Bool {
        #if canImport(UIKit)
        return UIDevice.current.proximityState
        #else
        return false
        #endif
    }

    private func applyCurrentRoute() {
        apply(Self.route(prefersSpeaker: prefersSpeaker, proximityNear: proximityNear))
    }

    private func apply(_ wanted: VoiceAudioRoute) {
        // __VOICE_ROUTE_CALL_GUARD_2026_09_12__ Même raison qu'`endPlayback`:
        // reconfigurer la catégorie ou réactiver la session pendant un appel
        // arrache l'audio de l'appel, et pendant un appel CallKit cela se fait
        // dans le dos du propriétaire de la session. Un message vocal joué
        // pendant un appel garde donc la route de l'appel; il ne la redéfinit pas.
        switch VoiceCallManager.shared.callState {
        case .connecting, .ringing, .inCall:
            OshiLog.call.info("🔊 VoiceMessageAudioRoute: call in progress — not re-routing the shared session")
            return
        case .idle, .ended:
            break
        }

        let session = AVAudioSession.sharedInstance()
        do {
            // Casque/Bluetooth branché: on ne touche à rien d'autre que la
            // catégorie, sinon on arracherait le son de l'oreillette de l'utilisateur.
            if Self.isExternalOutputAttached(session.currentRoute) {
                try session.setCategory(.playback, mode: .spokenAudio, options: [.allowBluetoothA2DP, .allowAirPlay])
                try session.setActive(true)
                return
            }

            switch wanted {
            case .speaker:
                // `.playback` route vers le haut-parleur principal et n'engage
                // JAMAIS le micro — donc pas d'indicateur orange en lecture.
                try session.setCategory(.playback, mode: .spokenAudio, options: [.allowBluetoothA2DP, .allowAirPlay])
                try session.setActive(true)
            case .earpiece:
                // Seul `.playAndRecord` sait viser l'écouteur. On l'accepte
                // uniquement ici, c'est-à-dire téléphone contre l'oreille ou
                // choix explicite de l'utilisateur.
                try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.allowBluetoothA2DP])
                try session.overrideOutputAudioPort(.none)
                try session.setActive(true)
            }
        } catch {
            OshiLog.call.info("🔊 VoiceMessageAudioRoute: could not apply \(String(describing: wanted)): \(error.localizedDescription)")
        }
    }

    // MARK: - Proximité

    private func startProximityMonitoring() {
        #if canImport(UIKit)
        guard proximityObserver == nil else { return }
        UIDevice.current.isProximityMonitoringEnabled = true
        proximityObserver = NotificationCenter.default.addObserver(
            forName: UIDevice.proximityStateDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            // `queue: .main` garantit déjà le fil principal.
            guard let self, self.isPlaying else { return }
            self.applyCurrentRoute()
        }
        #endif
    }

    private func stopProximityMonitoring() {
        #if canImport(UIKit)
        if let o = proximityObserver {
            NotificationCenter.default.removeObserver(o)
            proximityObserver = nil
        }
        // Laisser la surveillance active éteindrait l'écran dès que l'appareil
        // approche du visage, bien après la fin du message.
        UIDevice.current.isProximityMonitoringEnabled = false
        #endif
    }
}
