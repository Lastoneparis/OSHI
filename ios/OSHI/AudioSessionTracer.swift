//
//  AudioSessionTracer.swift
//  OSHI — nomme qui prend la session audio, et QUAND.
//
//  SIGNALÉ le 2026-08-27: « quand je joue de la musique et que j'ouvre l'app qui
//  avait été tuée, ça arrête ma musique. »
//
//  POURQUOI UN TRACEUR ET PAS UN CORRECTIF
//  Les suspects évidents sont tous propres. `VoiceCallManager.init` évite
//  explicitement la session — son commentaire dit « Do NOT setup audio session on
//  init - this pauses music/podcasts ». `AudioRecorderManager` ne l'active qu'à
//  l'enregistrement. `SpeechDictationManager.init` n'y touche pas.
//
//  Donc quelqu'un d'autre la prend, et deviner qui a déjà coûté trois correctifs
//  à côté de la plaque aujourd'hui. Une catégorie `.playAndRecord` sans
//  `.mixWithOthers` interrompt les autres applications — mais RIEN dans le
//  journal ne dit qui l'a posée ni depuis où.
//
//  Ce fichier ne corrige rien. Il fait dire à l'app ce qu'elle fait: à chaque
//  changement de catégorie ou d'activité, une ligne avec la CATÉGORIE, les
//  OPTIONS, si une autre app jouait, et la PILE D'APPEL. C'est la technique déjà
//  utilisée pour `VIDEO FLAG CHANGE: STACK:`, et c'est celle qui a fonctionné à
//  chaque fois aujourd'hui.
//
//  Un seul lancement avec de la musique en route suffit à nommer le coupable.
//
//  DEBUG SEULEMENT. Une pile d'appel symbolisée à chaque changement de session
//  n'a rien à faire dans un binaire livré, et `otherAudioPlaying` renseigne sur
//  ce que l'utilisateur écoute.
//

import Foundation
import AVFoundation

#if DEBUG

enum AudioSessionTracer {

    private static var started = false

    /// Appelé une fois au lancement. Idempotent: un second appel ne double pas
    /// les observateurs, sinon chaque changement produirait N lignes et on
    /// perdrait la lisibilité au moment précis où on en a besoin.
    static func start() {
        guard !started else { return }
        started = true

        let s = AVAudioSession.sharedInstance()
        log("launch", detail: "otherAudioPlaying=\(s.isOtherAudioPlaying)")

        NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil
        ) { note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 0
            log("routeChange", detail: "reason=\(reason)")
        }

        NotificationCenter.default.addObserver(
            forName: AVAudioSession.silenceSecondaryAudioHintNotification, object: nil, queue: nil
        ) { note in
            let type = (note.userInfo?[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt) ?? 0
            // C'est LE signal qui compte: iOS nous dit qu'on vient de faire taire
            // l'audio de quelqu'un d'autre (type=1 begin, 0 end).
            log("SILENCED_OTHER_AUDIO", detail: "type=\(type)")
        }

        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: nil
        ) { note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) ?? 0
            log("interruption", detail: "type=\(type)")
        }
    }

    /// Une ligne complète: ce que la session EST, et d'où on vient.
    ///
    /// `.playAndRecord` sans `.mixWithOthers` est ce qui coupe la musique, donc
    /// les options sont imprimées en clair plutôt qu'en masque brut — un
    /// `opts=1` ne dit rien à personne trois semaines plus tard.
    static func log(_ event: String, detail: String = "") {
        let s = AVAudioSession.sharedInstance()
        var opts: [String] = []
        let o = s.categoryOptions
        if o.contains(.mixWithOthers) { opts.append("mixWithOthers") }
        if o.contains(.duckOthers) { opts.append("duckOthers") }
        if o.contains(.defaultToSpeaker) { opts.append("defaultToSpeaker") }
        if o.contains(.allowBluetoothHFP) { opts.append("allowBluetoothHFP") }
        if o.contains(.allowBluetoothA2DP) { opts.append("allowBluetoothA2DP") }
        if o.contains(.interruptSpokenAudioAndMixWithOthers) { opts.append("interruptSpoken") }

        // 6 images suffisent pour nommer l'appelant sans noyer la ligne. La
        // première est ce logger lui-même.
        let stack = Thread.callStackSymbols.dropFirst().prefix(6)
            .map { sym -> String in
                let parts = sym.split(separator: " ", omittingEmptySubsequences: true)
                return parts.count > 3 ? String(parts[3]) : sym
            }
            .joined(separator: " < ")

        let line = "DIAG_AUDIOSESSION | event=\(event) | cat=\(s.category.rawValue) | mode=\(s.mode.rawValue) | opts=[\(opts.joined(separator: ","))] | otherAudio=\(s.isOtherAudioPlaying) | \(detail) | STACK: \(stack)"
        OshiLog.call.info("\(line)")
        CallFileLogger.shared.log(line)
    }
}

#endif
