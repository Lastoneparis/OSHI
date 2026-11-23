//
//  MicLowPassFilter.swift
//  OSHI — 7 kHz low-pass on the microphone capture path.
//
//  MESURÉ, PAS SUPPOSÉ. L'appel du 2026-08-27 a été capturé des deux côtés
//  (`DebugCallAudioRecorder`), et le souffle a une adresse:
//
//      iPhone 17, micro CAPTÉ, dans les silences : 6-16 kHz / 0,3-3,4 kHz = -8,0 dB
//      iPhone 14, micro CAPTÉ, dans les silences :                          -25,8 dB
//      (une voix propre se situe entre -25 et -40 dB)
//
//  Le bruit est présent dans `mic_raw` — AVANT le suppresseur d'écho, AVANT le
//  passe-haut, AVANT le codec. Notre chaîne ne l'ajoute pas (-8,0 → -7,9) et le
//  codec non plus (-7,9 → -8,9 chez le pair). Il entre par le MICROPHONE, il
//  traverse tout intact, et il ressort dans le haut-parleur d'en face.
//
//  Ça a coûté trois correctifs à côté de la plaque. Le suppresseur d'écho a été
//  accusé trois fois, et l'audio l'innocente: corrélation entre le niveau du
//  correspondant et l'atténuation appliquée = -0,10. Il n'y a pas de pompage.
//  Ce n'est pas une modulation, c'est un bruit STATIONNAIRE à large bande.
//
//  POURQUOI OSHI SIFFLE LÀ OÙ LES AUTRES NE SIFFLENT PAS
//  Une app d'appel normale reçoit la réduction de bruit d'Apple GRATUITEMENT,
//  parce qu'elle passe par VoiceProcessingIO. OSHI ne peut pas: VPIO a rendu
//  TOUS les appels muets trois fois (b56-b60, 2026-07-26, 2026-08-14) et reste
//  désarmé. On a toujours su que ça coûtait l'annulation d'écho. Ce que la
//  mesure montre, c'est que ça coûte AUSSI la réduction de bruit — et personne
//  ne l'avait écrit.
//
//  CE QUE FAIT CE FILTRE, ET CE QU'IL NE FAIT PAS
//  Il ne débruite pas. Il BORNE LA BANDE. La parole large bande est définie de
//  50 Hz à 7 kHz — c'est la bande de G.722 et d'AMR-WB, pas un chiffre choisi
//  ici. Au-dessus de 7 kHz il n'y a pas de parole à perdre; il n'y a que du
//  bruit de micro, du souffle de pièce et du bruit de codage.
//
//  MESURÉ SUR LA CAPTURE RÉELLE, avant d'écrire une ligne dans l'app:
//      avant                : -8,0 dB
//      passe-bas 7 kHz x2   : -13,6 dB   bande de parole conservée: +0,00 dB
//  Cinq virgule six décibels de souffle en moins, et pas un dixième de dB perdu
//  entre 300 Hz et 3,4 kHz.
//
//  ET ÇA RAPPORTE UNE SECONDE FOIS. AAC-ELD répartit ses bits par énergie: à
//  48 kbps, chaque bit dépensé pour du bruit à 12 kHz est un bit retiré à la
//  bande qui porte les mots. C'est exactement l'argument du passe-haut de
//  100 Hz, par l'autre bout du spectre. Ensemble ils forment la bande passante
//  de la parole large bande, 100 Hz - 7 kHz.
//
//  Deux biquads en cascade (24 dB/octave): un seul étage laissait encore
//  -11,9 dB à 8 kHz sur la mesure. RBJ cookbook, Q = 1/√2 par étage
//  (Butterworth, sans résonance au coude). Direct Form II transposée, état par
//  canal — même construction que `MicHighPassFilter`, pour qu'un lecteur qui
//  connaît l'un connaisse l'autre.
//

import Foundation

struct MicLowPassFilter {
    private let cutoffHz: Double
    private var stages: [Biquad]
    private var sampleRate: Double = 0
    private var channels: Int = 0

    private struct Biquad {
        var b0: Float = 1, b1: Float = 0, b2: Float = 0, a1: Float = 0, a2: Float = 0
        var state: [(z1: Float, z2: Float)] = []
    }

    /// - Parameter stageCount: 2 = 24 dB/octave. One stage measured only
    ///   −11,9 dB against −13,6 dB for two; the second one is what makes the
    ///   16 kHz end actually go away.
    init(cutoffHz: Double, stageCount: Int = 2) {
        self.cutoffHz = cutoffHz
        self.stages = Array(repeating: Biquad(), count: max(1, stageCount))
    }

    /// Recomputes coefficients when the rate changes, and sizes per-channel state.
    mutating func prepare(sampleRate rate: Double, channels ch: Int) {
        guard rate > 0, ch > 0 else { return }
        if rate != sampleRate {
            sampleRate = rate
            // Un coude au-dessus de Nyquist n'a pas de sens; à 8 kHz de fréquence
            // d'échantillonnage (HFP Bluetooth) la bande est DÉJÀ plus étroite
            // que la cible et le filtre devient un passe-tout.
            let fc = min(cutoffHz, rate * 0.45)
            let w0 = 2.0 * Double.pi * fc / rate
            let cosw = cos(w0)
            let alpha = sin(w0) / (2.0 * (1.0 / 2.0.squareRoot()))
            let b0 = (1.0 - cosw) / 2.0
            let b1 = 1.0 - cosw
            let b2 = (1.0 - cosw) / 2.0
            let a0 = 1.0 + alpha
            let a1 = -2.0 * cosw
            let a2 = 1.0 - alpha
            for i in stages.indices {
                stages[i].b0 = Float(b0 / a0)
                stages[i].b1 = Float(b1 / a0)
                stages[i].b2 = Float(b2 / a0)
                stages[i].a1 = Float(a1 / a0)
                stages[i].a2 = Float(a2 / a0)
            }
        }
        if ch != channels {
            channels = ch
            for i in stages.indices {
                stages[i].state = Array(repeating: (z1: 0, z2: 0), count: ch)
            }
        }
    }

    /// In-place, one channel. Direct Form II transposed.
    mutating func process(_ samples: UnsafeMutablePointer<Float>, frames: Int, channel: Int) {
        guard frames > 0, channel < channels else { return }
        for s in stages.indices {
            var z1 = stages[s].state[channel].z1
            var z2 = stages[s].state[channel].z2
            let b0 = stages[s].b0, b1 = stages[s].b1, b2 = stages[s].b2
            let a1 = stages[s].a1, a2 = stages[s].a2
            for n in 0..<frames {
                let x = samples[n]
                let y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                samples[n] = y
            }
            stages[s].state[channel] = (z1: z1, z2: z2)
        }
    }
}
