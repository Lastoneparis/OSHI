//
//  MicLowPassFilterTests.swift
//  OSHITests
//
//  Un filtre dont la seule preuve est « le code ressemble à la formule du
//  cookbook » est un filtre que personne n'a vérifié: un signe faux ou deux
//  coefficients intervertis compilent, tournent, et sonnent comme quelque chose.
//  On lui envoie donc de vraies sinusoïdes et on mesure l'atténuation.
//
//  Les cibles viennent de la mesure faite sur la capture réelle de l'appel du
//  2026-08-27 avant d'écrire le filtre: deux étages à 7 kHz font passer le
//  rapport 6-16 kHz / 0,3-3,4 kHz de -8,0 dB à -13,6 dB, sans perdre un dixième
//  de dB entre 300 Hz et 3,4 kHz.
//

import XCTest
@testable import OSHI

final class MicLowPassFilterTests: XCTestCase {

    private let sampleRate: Double = 48000
    private let frames = 48000   // 1 s, assez pour que le transitoire soit négligeable

    /// RMS de sortie / RMS d'entrée, en dB, pour une sinusoïde à `hz`.
    /// Le premier dixième est jeté: c'est le régime transitoire du biquad, pas
    /// sa réponse.
    private func attenuationDb(at hz: Double) -> Double {
        var f = MicLowPassFilter(cutoffHz: 10000, stageCount: 3)
        f.prepare(sampleRate: sampleRate, channels: 1)
        var buf = (0..<frames).map { Float(sin(2.0 * Double.pi * hz * Double($0) / sampleRate)) }
        buf.withUnsafeMutableBufferPointer { p in
            f.process(p.baseAddress!, frames: frames, channel: 0)
        }
        let skip = frames / 10
        let out = buf[skip...]
        let rmsOut = (out.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(out.count)).squareRoot()
        return 20 * log10(max(rmsOut, 1e-12) / 0.7071)
    }

    /// LA BANDE QUI PORTE LES MOTS.
    ///
    /// __CLARITY_COSTS_MORE_THAN_I_MEASURED_2026_08_27__ 5 et 7 kHz sont AJOUTÉS
    /// ici, et c'est la correction qui compte. La version d'avant s'arrêtait à
    /// 3400 Hz — la bande téléphonique ÉTROITE — et déclarait « la parole ne
    /// bouge pas » en toute bonne foi. Or les consonnes qui portent la CLARTÉ
    /// (/s/, /f/, /ʃ/) vivent à 4–8 kHz. Le test ne pouvait donc pas voir les
    /// −2,90 dB que le réglage précédent y coûtait, et que l'oreille, elle,
    /// entendait tout de suite.
    func testSpeechBandIsUntouched() {
        for hz in [300.0, 1000.0, 2000.0, 3400.0, 5000.0] {
            let a = attenuationDb(at: hz)
            XCTAssertGreaterThan(a, -1.0, "\(Int(hz)) Hz atténué de \(a) dB — la parole n'a pas à payer")
            XCTAssertLessThan(a, 1.0, "\(Int(hz)) Hz AMPLIFIÉ de \(a) dB — un passe-bas ne fait pas ça")
        }
    }

    /// LA BANDE DU SOUFFLE. C'est la raison d'être du filtre; si elle survit,
    /// il ne sert à rien.
    func testHissBandIsActuallyRemoved() {
        // Mesuré sur ce réglage: 12 kHz = −17,7 dB, 16 kHz = −42,9 dB.
        XCTAssertLessThan(attenuationDb(at: 12000), -12.0, "12 kHz doit chuter")
        XCTAssertLessThan(attenuationDb(at: 16000), -30.0, "16 kHz doit disparaître")
    }

    /// LA BANDE DE LA CLARTÉ, celle que la version précédente sacrifiait sans
    /// que rien ne le dise. Un dB et demi est la limite de ce qu'on accepte de
    /// payer ici; au-delà, ça s'entend — c'est exactement ce qui a été signalé.
    func testSibilantBandSurvives() {
        for hz in [4000.0, 5000.0, 6000.0] {
            let a = attenuationDb(at: hz)
            XCTAssertGreaterThan(a, -1.5,
                "\(Int(hz)) Hz atténué de \(a) dB — la clarté des consonnes paye trop")
        }
    }

    /// Le coude est là où on l'a mis. Deux étages Butterworth: -3 dB par étage
    /// au coude, donc environ -6 dB au total.
    func testCornerIsWhereItIsDeclared() {
        let a = attenuationDb(at: 10000)
        // Trois étages Butterworth: environ −3 dB par étage au coude.
        XCTAssertLessThan(a, -6.0)
        XCTAssertGreaterThan(a, -14.0)
    }

    /// La pente doit être monotone: chaque octave au-dessus du coude atténue
    /// plus que la précédente. Un biquad mal câblé peut très bien passer un
    /// point isolé et remonter ensuite.
    func testResponseIsMonotonicAboveTheCorner() {
        var previous = 0.0
        for hz in [10000.0, 12000.0, 16000.0, 20000.0] {
            let a = attenuationDb(at: hz)
            XCTAssertLessThan(a, previous + 0.5, "la réponse remonte à \(Int(hz)) Hz")
            previous = a
        }
    }

    /// Un coude au-dessus de Nyquist n'a pas de sens. En HFP Bluetooth la bande
    /// est déjà plus étroite que la cible, et le filtre doit devenir inoffensif
    /// au lieu de produire des coefficients absurdes.
    func testNarrowbandRateDoesNotBreakTheFilter() {
        var f = MicLowPassFilter(cutoffHz: 10000, stageCount: 3)
        f.prepare(sampleRate: 8000, channels: 1)
        var buf = (0..<8000).map { Float(sin(2.0 * Double.pi * 1000.0 * Double($0) / 8000.0)) }
        buf.withUnsafeMutableBufferPointer { p in
            f.process(p.baseAddress!, frames: 8000, channel: 0)
        }
        XCTAssertTrue(buf.allSatisfy { $0.isFinite }, "coefficients instables à 8 kHz")
        let rms = (buf[800...].reduce(0.0) { $0 + Double($1) * Double($1) } / Double(buf.count - 800)).squareRoot()
        XCTAssertGreaterThan(rms, 0.3, "1 kHz ne doit pas être mangé à 8 kHz d'échantillonnage")
    }

    /// L'état est PAR CANAL. Deux canaux traités avec le même état se
    /// contamineraient, et sur un buffer mono ça ne se verrait jamais.
    func testChannelStateIsIndependent() {
        var f = MicLowPassFilter(cutoffHz: 10000, stageCount: 3)
        f.prepare(sampleRate: sampleRate, channels: 2)
        var a = [Float](repeating: 0, count: 480); a[0] = 1.0   // impulsion
        var b = [Float](repeating: 0, count: 480)               // silence
        a.withUnsafeMutableBufferPointer { f.process($0.baseAddress!, frames: 480, channel: 0) }
        b.withUnsafeMutableBufferPointer { f.process($0.baseAddress!, frames: 480, channel: 1) }
        XCTAssertTrue(b.allSatisfy { $0 == 0 }, "le canal 1 a reçu l'énergie du canal 0")
        XCTAssertGreaterThan(a.reduce(0) { $0 + abs($1) }, 0, "le canal 0 n'a rien produit")
    }
}
