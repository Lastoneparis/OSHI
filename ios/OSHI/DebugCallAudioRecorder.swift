//
//  DebugCallAudioRecorder.swift
//  OSHI — capture du chemin audio d'un appel, POUR DIAGNOSTIC, en Debug SEULEMENT.
//
//  POURQUOI CE FICHIER EXISTE
//  Le « soufflement » signalé sur les appels haut-parleur a été diagnostiqué
//  TROIS FOIS à partir de percentiles tirés du journal, et corrigé trois fois:
//  une fenêtre calée sur le mauvais instant, puis un plancher de bruit qui
//  tombait à 20 et n'en repartait plus, puis un plancher juste mais appliqué
//  trop littéralement. Chaque fois les chiffres semblaient suffire, et chaque
//  fois il manquait la seule chose qui tranche: le signal lui-même.
//
//  Des percentiles échantillonnés une trame sur cent ne peuvent pas montrer une
//  MODULATION. Le pompage est une enveloppe de gain qui bouge au rythme de la
//  parole d'en face; il faut les échantillons, pas leur résumé.
//
//  CE QUI EST CAPTURÉ, et pourquoi ces trois-là
//    • mic_raw  — la trame micro AVANT toute suppression: la référence.
//    • mic_out  — la même APRÈS suppression et passe-haut: ce que le pair
//                 entend vraiment. `out/raw` trame par trame EST l'enveloppe de
//                 gain, donc le pompage, directement mesurable.
//    • far      — ce qui part vers le haut-parleur, après pré-gain et tanh.
//                 C'est le corrélat: sans lui on ne peut pas montrer que le fond
//                 respire AU RYTHME du correspondant, seulement qu'il respire.
//
//  PCM BRUT, PAS DU WAV
//  Un WAV veut une taille dans son en-tête, donc une finalisation, donc un
//  fichier illisible si l'appel se termine par un plantage ou une coupure — ce
//  qui est précisément le genre d'appel qu'on voudrait examiner. On écrit du PCM
//  16 bits mono little-endian et l'en-tête se pose sur le Mac, après coup. Un
//  fichier tronqué reste lisible jusqu'à son dernier échantillon.
//
//  CE QUI L'EMPÊCHE D'ÊTRE LIVRÉ
//  Tout le type est sous `#if DEBUG`, et chacun de ses points d'entrée aussi —
//  pas seulement le premier. Une capacité d'enregistrement d'appel dont UN SEUL
//  membre échapperait à la garde serait un enregistreur d'appels livré dans un
//  messager chiffré. En Release ce fichier ne compile pas une ligne.
//
//  Et même en Debug elle est bornée: `maxSeconds` coupe l'écriture, parce qu'un
//  diagnostic ne doit pas pouvoir remplir le disque d'un appareil de test.
//

import Foundation
import AVFoundation

#if DEBUG

final class DebugCallAudioRecorder {

    static let shared = DebugCallAudioRecorder()

    /// Active par défaut EN DEBUG, parce qu'un diagnostic qu'il faut d'abord
    /// aller armer dans un réglage inaccessible depuis l'appareil de test ne
    /// sert à rien. L'interrupteur existe pour l'éteindre, pas pour l'allumer.
    ///
    /// Ce n'est pas une décision sur la vie privée: tout le type est sous
    /// `#if DEBUG` et n'existe pas dans un binaire livré.
    static var enabled: Bool {
        !UserDefaults.standard.bool(forKey: "OSHIDebugDisableRecordCallAudio")
    }

    /// Borne dure. 60 s × 48 kHz × 2 octets × 3 flux ≈ 17 Mo — assez pour voir
    /// une modulation, trop peu pour gêner.
    // __ECHO_PROOF_2026_09_22__ 90 s s'arrêtaient AVANT la bascule de route de
    // l'appel 6216CD2F (21h25:57): la moitié qu'il fallait voir. 15 min =
    // ~260 Mo pour les trois pistes, en Debug seulement.
    private let maxSeconds: Double = 900

    enum Track: String, CaseIterable {
        case micRaw = "calldbg_mic_raw"
        case micOut = "calldbg_mic_out"
        case far    = "calldbg_far"
    }

    private let queue = DispatchQueue(label: "oshi.debug.audiorec", qos: .utility)
    private var handles: [Track: FileHandle] = [:]
    private var framesWritten: [Track: Int] = [:]
    private var sampleRates: [Track: Double] = [:]
    private var active = false

    private init() {}

    private static func url(for track: Track) -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return docs.appendingPathComponent("\(track.rawValue).pcm")
    }

    /// Démarre une capture propre. Appelé au début d'un appel; les fichiers du
    /// précédent sont écrasés, sinon on analyserait deux appels collés bout à
    /// bout en croyant n'en lire qu'un.
    func start() {
        guard Self.enabled else { return }
        queue.async {
            self.closeAllLocked()
            let fm = FileManager.default
            for t in Track.allCases {
                let u = Self.url(for: t)
                try? fm.removeItem(at: u)
                fm.createFile(atPath: u.path, contents: nil)
                self.handles[t] = try? FileHandle(forWritingTo: u)
                self.framesWritten[t] = 0
            }
            self.active = true
            CallFileLogger.shared.log("DIAG_AUDIOREC | started | dir=Documents | tracks=\(Track.allCases.map(\.rawValue).joined(separator: ","))")
        }
    }

    func stop() {
        queue.async {
            guard self.active else { return }
            let summary = Track.allCases.map { t in
                let n = self.framesWritten[t] ?? 0
                let sr = self.sampleRates[t] ?? 0
                let secs = sr > 0 ? Double(n) / sr : 0
                return "\(t.rawValue)=\(n)f/\(String(format: "%.1f", secs))s@\(Int(sr))"
            }.joined(separator: " | ")
            CallFileLogger.shared.log("DIAG_AUDIOREC | stopped | \(summary)")
            self.closeAllLocked()
            self.active = false
        }
    }

    private func closeAllLocked() {
        for (_, h) in handles { try? h.close() }
        handles.removeAll()
    }

    /// Écrit une trame. Float32 et Int16 tous les deux, parce que le tap micro et
    /// le tampon de lecture ne garantissent pas le même format et qu'une capture
    /// qui rate la moitié des appareils ne sert à rien.
    func write(_ buffer: AVAudioPCMBuffer, to track: Track) {
        guard Self.enabled else { return }
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return }
        let rate = buffer.format.sampleRate

        var pcm = [Int16](repeating: 0, count: frames)
        if let f = buffer.floatChannelData {
            let ch = f[0]
            for i in 0..<frames {
                let v = max(-1.0, min(1.0, ch[i]))
                pcm[i] = Int16(v * 32767.0)
            }
        } else if let i16 = buffer.int16ChannelData {
            let ch = i16[0]
            for i in 0..<frames { pcm[i] = ch[i] }
        } else {
            return
        }
        let data = pcm.withUnsafeBufferPointer { Data(buffer: $0) }

        queue.async {
            guard self.active, let h = self.handles[track] else { return }
            let written = self.framesWritten[track] ?? 0
            if rate > 0, Double(written) / rate >= self.maxSeconds { return }
            self.sampleRates[track] = rate
            try? h.write(contentsOf: data)
            self.framesWritten[track] = written + frames
        }
    }
}

#endif
