//
//  EchoSuppressorGate.swift
//  OSHI — decides, per 20 ms microphone frame, whether what the mic just heard
//  is the loudspeaker leaking back rather than a person talking.
//
//  WHY THIS IS NOT VoiceProcessingIO
//  The textbook answer is Apple's AEC. It is not taken here, and the reason is
//  written at length in VoiceCallManager (`__AEC_STAYS_OFF_2026_08_14__`):
//  VoiceProcessingIO broke ALL call audio in b56–b60 and again on 2026-07-26 and
//  2026-08-14, each time by handing back full-length all-zero buffers. This gate
//  is plain arithmetic on a float buffer — it never touches the audio graph, the
//  session category, or the units, so it cannot reproduce that failure mode.
//
//  WHAT WAS WRONG WITH THE FIRST VERSION — __ECHO_PLAYOUT_ALIGN_2026_08_26__
//  Field report, real call: the person who ANSWERED was on speakerphone, and the
//  CALLER heard his own voice come back with a delay. The answerer heard nothing
//  wrong, and turning his speaker off cured it for both. So the echo was born on
//  the answering handset — its loudspeaker into its own microphone — and the
//  suppressor there did not catch it.
//
//  Two separate defects, and the first one is structural:
//
//  1. THE GATE WAS READING THE WRONG INSTANT. The far end's level was stamped
//     with `Date()` at the moment a decoded buffer was SCHEDULED — but a
//     scheduled buffer is not a played buffer. It sits behind everything already
//     queued on the player node, and is heard `playerQueueDepthMs` later. On a
//     healthy link that is 40–180 ms; on a build without the jitter-buffer decay
//     fix (1.0.39 and earlier) it was measured at 700 ms. The microphone at time
//     T hears what the speaker emits at time T, which corresponds to a buffer
//     scheduled up to 0.7 s EARLIER. So the gate was comparing the mic against a
//     far-end level from the future, with a 150 ms hold window that could not
//     bridge the gap. It opened during silence and — worse — closed during the
//     tail of every sentence, which is exactly the part that echoes.
//
//     Fixed by stamping each far-end buffer with the window in which it will
//     actually be HEARD (`now + queuedAhead`, for its own duration) and looking
//     up, at decision time, what is playing right now.
//
//  2. −12 dB IS NOT ENOUGH FOR A LOUDSPEAKER. The suppression depth was one
//     constant for every route. But the playback stage applies 6.6× pre-gain on
//     the loudspeaker against 1.43× on the earpiece — a deliberate +13.3 dB —
//     and the loudspeaker also couples into the microphone far more strongly
//     than the receiver does. Attenuating the leak by 12 dB still leaves the far
//     end hearing itself at a quarter amplitude, 200–300 ms late, which is
//     audible as an echo rather than as room sound. The depth now follows the
//     route: −24 dB out of the loudspeaker, the proven −12 dB elsewhere.
//
//  WHAT KEEPS THIS SAFE
//  The double-talk guard, unchanged. Speaker-to-mic leakage arrives well below
//  the level that produced it; real speech into the mic is far louder than the
//  echo it competes with. When the local level is within `doubleTalkRatio` of
//  what is playing, this is someone actually talking and the frame passes
//  through untouched. Worst case when it misjudges is one 20 ms frame
//  attenuated — a moment of quiet, never a cut, never silence. And the gain is
//  RAMPED across the frame by the caller (`__ECHO_GAIN_RAMP_2026_08_25__`), so
//  even a misjudged frame cannot click.
//
//  Extracted from VoiceCallManager so the decision can be MEASURED. Timing logic
//  whose only evidence is "the code reads correctly" is timing logic nobody has
//  checked: an off-by-one-buffer error still compiles, still runs, and still
//  sounds like something. EchoSuppressorGateTests drives it with an injected
//  clock and asserts the window it actually opens.
//

import Foundation

struct EchoSuppressorGate {

    /// Which output the far end is currently coming out of. Only the
    /// loudspeaker leaks enough to need the deep attenuation.
    enum Route {
        case loudspeaker
        case earpieceOrHeadset
    }

    // MARK: - Tunables

    /// −12 dB. Deliberately not a mute: half-duplex gating is what makes cheap
    /// speakerphones sound broken when two people talk at once.
    static let earpieceGain: Float = 0.25
    /// −24 dB, for the loudspeaker only. The playback stage already puts 6.6×
    /// into the speaker against 1.43× into the earpiece; a single depth cannot
    /// serve both, and the shallow one is what let the reported echo through.
    static let loudspeakerGain: Float = 0.06
    /// En dessous, ce qui joue est trop faible pour faire écho.
    ///
    /// __ADAPTIVE_COUPLING_2026_08_27__ 300 → 150. À 300, le cas RÉEL mesuré
    /// était rejeté avant même d'être examiné: la lecture de l'iPhone 14 pendant
    /// l'appel qui produisait l'écho était à p25=226, p50=265, p75=339. Le
    /// correspondant était donc jugé INACTIF la plupart du temps, et c'est une
    /// troisième raison — après le seuil d'excès — pour laquelle le suppresseur
    /// n'a touché que 68 trames sur 2119.
    ///
    /// Baisser ce seuil est devenu SANS RISQUE avec la soustraction en
    /// puissance: si le couplage est nul, l'écho prédit l'est aussi et le gain
    /// vaut 1,0. Sur-déclencher ne coûte plus rien — c'est ce qui rend cette
    /// constante peu critique, là où elle était un point de rupture.
    static let farEndFloorRms: Double = 150
    /// Local ≥ this fraction of what is playing ⇒ real speech, not leakage.
    static let doubleTalkRatio: Double = 0.6
    /// Ride through the gaps between words instead of re-opening the mic inside
    /// a sentence, which would let a burst of echo through on every pause.
    /// Measured on the PLAYOUT clock now, so it means what it says.
    static let holdSeconds: TimeInterval = 0.15

    /// __NOISE_FLOOR_CLAMP_2026_08_27__ Plancher absolu sous lequel l'estimation
    /// du bruit de fond ne descend pas, pour que la division reste saine sur un
    /// micro numériquement muet.
    static let minNoiseFloorRms: Double = 20
    /// __WINDOWED_MINIMUM_2026_08_27__ Le suivi est un MINIMUM GLISSANT, pas un
    /// minimum absolu qui remonte lentement.
    ///
    /// MESURÉ, et c'est ce qui rendait le clamp inerte: `echoNoiseFloor=20` sur
    /// LES DEUX téléphones, tout l'appel. 20, c'est `minNoiseFloorRms`, le
    /// plancher absolu. La première version tombait INSTANTANÉMENT sur la trame
    /// la plus basse jamais vue — une trame muette au décrochage suffit — et
    /// remontait de 1 %/s, soit plus de deux minutes pour revenir à 200. Elle
    /// restait donc collée en bas, `bruit/micRms` devenait minuscule, et
    /// `max(routeGain, minuscule)` rendait toujours `routeGain`. Le clamp ne
    /// clampait rien: on était resté en −24 dB à plat, et le soufflement avec.
    ///
    /// Un minimum GLISSANT n'a pas ce défaut: une trame anormalement basse ne
    /// pèse que le temps de la fenêtre, puis elle sort. C'est la construction
    /// classique des estimateurs par statistiques de minimum — des sous-fenêtres
    /// qui tournent, et le minimum des sous-fenêtres.
    static let noiseSubWindowFrames = 25   // 0,5 s à 50 trames/s
    static let noiseSubWindowCount = 6     // 3 s d'historique

    /// __ADAPTIVE_COUPLING_2026_08_27__ Le couplage acoustique micro↔haut-parleur,
    /// ESTIMÉ EN CONTINU. C'est ce qui remplace les seuils que je n'arrivais pas
    /// à régler.
    ///
    /// L'HISTOIRE, parce qu'elle explique la forme du code
    /// Trois réglages successifs ont échoué, et chacun pour une raison
    /// différente: une fenêtre calée sur le mauvais instant, un plancher de bruit
    /// qui se verrouillait, puis un plancher juste appliqué trop littéralement.
    /// Le quatrième a buté sur un mur: les deux cas RÉELS ont exactement le même
    /// rapport au bruit de fond.
    ///
    ///     soufflement (iPhone 17) : plancher 20, micro  55  → 2,75
    ///     fuite       (iPhone 14) : plancher 53, micro 145  → 2,74
    ///
    /// Aucun seuil sur ce rapport ne peut les séparer. Un seuil à 4 désarmait la
    /// fuite (68 trames atténuées sur 2119); un seuil à 2,5 réveillait le
    /// pompage. Il n'existait pas de bonne valeur, parce que la QUESTION était
    /// mauvaise: le niveau du micro ne dit pas s'il entend le haut-parleur.
    ///
    /// CE QUE ÇA MESURE
    /// Pendant que le correspondant parle et que le local se tait, la part du
    /// micro au-dessus de son bruit de fond EST l'écho. Son rapport au niveau
    /// joué est le couplage de cette pièce, avec ce téléphone, sur cette route.
    /// Mesuré sur l'appel du 27 août:
    ///
    ///     iPhone 17, écouteur     : couplage 0,217  (−13 dB)  sur 690 trames
    ///     iPhone 14, haut-parleur : couplage 0,183  (−15 dB)  sur 789 trames
    ///
    /// CE QUE ÇA DONNE, et pourquoi ça résout les deux cas d'un coup
    /// L'écho prédit vaut `niveau joué × couplage`. On le retire EN PUISSANCE:
    /// `résidu = √(micro² − écho²)`. Alors:
    ///
    ///   • pas de couplage ⇒ écho prédit ≈ 0 ⇒ gain 1,0. Le fond n'est PAS touché,
    ///     même quand le haut-parleur hurle. C'est le cas du soufflement, réglé
    ///     sans aucun seuil.
    ///   • couplage fort ⇒ écho prédit ≈ micro ⇒ le résidu tombe au bruit de fond.
    ///     C'est la fuite, retirée jusqu'à la pièce et pas plus bas.
    ///
    /// Le bruit de la pièce SURVIT dans les deux cas, parce qu'on ne retire que
    /// la puissance attribuée à l'écho. C'est ça qui supprime le pompage: pas un
    /// garde, une soustraction juste.
    /// PAS DE VALEUR INITIALE DEVINÉE. L'estimation part de ZÉRO, ce qui veut
    /// dire « je n'ai encore rien observé, donc je n'atténue rien ». C'est le
    /// défaut le plus sûr possible, et il supprime deux constantes de plus qu'il
    /// aurait fallu régler à l'aveugle — exactement ce qui a échoué trois fois.
    ///
    /// La convergence prend environ 1 seconde (constante de temps 1/`couplingRate`
    /// à 50 trames/s): l'écho des premières secondes d'un appel passe, puis
    /// l'estimation attrape l'acoustique de la pièce et ne la lâche plus.
    /// Borne haute: on ne croira jamais que le micro n'est QUE de l'écho. Il
    /// reste toujours de quoi entendre quelqu'un qui se met à parler.
    static let maxCoupling: Double = 0.90
    /// Vitesse d'adaptation par trame. Lent: une estimation qui suivrait la
    /// parole cesserait d'être un couplage.
    static let couplingRate: Double = 0.02
    /// Le micro doit dépasser son plancher d'au moins cette fraction pour que la
    /// trame apprenne quelque chose. En dessous, elle est muette sur le couplage
    /// — et la compter ferait converger l'estimation vers zéro au rythme des
    /// silences du correspondant.
    ///
    /// ⚠️ Ce n'est PAS un seuil de déclenchement déguisé: il ne décide pas s'il
    /// faut atténuer, seulement si la trame a quelque chose à ENSEIGNER. La
    /// décision, elle, reste la soustraction en puissance, qui ne dépend d'aucun
    /// seuil.
    static let informativeExcessRatio: Double = 0.5

    /// 20 ms per slot. 128 slots ≈ 2.5 s of playout timeline — comfortably more
    /// than the deepest queue the player is ever allowed to accumulate, so a
    /// lookup can never fall off the end of the ring while the audio it is
    /// looking for is still pending.
    static let traceCapacity = 128

    // MARK: - State

    private struct Slot {
        var playoutStart: TimeInterval
        var playoutEnd: TimeInterval
        var rms: Double
    }

    private var trace: [Slot]
    private var head: Int = 0
    /// __NOISE_FLOOR_CLAMP_2026_08_27__ Estimation courante du bruit de fond du
    /// micro, en unités Int16. Mise à jour sur la trame BRUTE, donc jamais
    /// polluée par notre propre atténuation.
    private var noiseFloorRms: Double = 0
    /// __ADAPTIVE_COUPLING_2026_08_27__ Couplage courant. `nil` tant qu'aucune
    /// observation n'a eu lieu: on part alors de la valeur initiale de la route.
    private var coupling: Double?
    /// Minima des sous-fenêtres CLOSES, plus celui de la sous-fenêtre ouverte.
    private var noiseSubMins: [Double]
    private var noiseSubIndex = 0
    private var noiseFramesInSub = 0
    private var noiseOpenSubMin = Double.infinity

    init() {
        trace = Array(repeating: Slot(playoutStart: 0, playoutEnd: 0, rms: 0),
                      count: EchoSuppressorGate.traceCapacity)
        noiseSubMins = Array(repeating: .infinity,
                             count: EchoSuppressorGate.noiseSubWindowCount)
    }

    // MARK: - Recording what will be heard

    /// Record a decoded far-end buffer on the PLAYOUT timeline.
    ///
    /// - Parameters:
    ///   - rms: level of the buffer as it will leave the speaker, i.e. AFTER the
    ///     playback pre-gain and saturation. Int16-equivalent units (0…32767),
    ///     the same scale the microphone level is reported in.
    ///   - isConcealed: true for a PLC-synthesised frame. Skipped, as before —
    ///     treating invented audio as far-end speech would hold the gate open
    ///     through silence.
    ///   - queuedAheadSeconds: how much audio is ALREADY pending on the player
    ///     ahead of this buffer. This is the whole point of the type.
    ///   - durationSeconds: how long this buffer plays for.
    ///   - now: caller's clock, injectable for tests.
    mutating func noteFarEndBuffer(rms: Double,
                                   isConcealed: Bool,
                                   queuedAheadSeconds: TimeInterval,
                                   durationSeconds: TimeInterval,
                                   now: TimeInterval) {
        guard !isConcealed else { return }
        guard durationSeconds > 0 else { return }
        let start = now + max(0, queuedAheadSeconds)
        trace[head] = Slot(playoutStart: start,
                           playoutEnd: start + durationSeconds,
                           rms: rms)
        head = (head + 1) % trace.count
    }

    /// Forget the recorded timeline. Called when playback is torn down or the
    /// route changes under us — a queue that no longer exists must not keep
    /// claiming the speaker is busy.
    mutating func reset() {
        for i in trace.indices { trace[i] = Slot(playoutStart: 0, playoutEnd: 0, rms: 0) }
        head = 0
        // Le fond appartient à la pièce et au micro du moment; après un
        // changement de route ou un redémarrage du moteur, ce n'est plus le
        // même micro. Le laisser ferait porter au nouvel appel l'estimation de
        // l'ancien.
        noiseFloorRms = 0
        // Le couplage appartient à cette pièce et à cette route. Après un
        // changement de route ou un redémarrage du moteur, ce n'est plus la même
        // acoustique — le garder ferait porter au nouveau contexte la mesure de
        // l'ancien.
        coupling = nil
        for i in noiseSubMins.indices { noiseSubMins[i] = .infinity }
        noiseSubIndex = 0
        noiseFramesInSub = 0
        noiseOpenSubMin = .infinity
    }

    // MARK: - Reading what IS being heard

    /// Loudest far-end level whose playout window overlaps `[now - hold, now]`.
    /// Zero when nothing is playing — which is also what a fresh gate returns,
    /// since every slot starts at 0 and can never overlap a real clock.
    func farEndLevel(now: TimeInterval) -> Double {
        let windowStart = now - EchoSuppressorGate.holdSeconds
        var loudest: Double = 0
        for slot in trace {
            guard slot.playoutEnd > 0 else { continue }
            // Overlap test against [windowStart, now].
            guard slot.playoutEnd >= windowStart, slot.playoutStart <= now else { continue }
            if slot.rms > loudest { loudest = slot.rms }
        }
        return loudest
    }

    /// The gain this microphone frame should end up at. 1.0 = untouched.
    ///
    /// __NOISE_FLOOR_CLAMP_2026_08_27__ MESURÉ SUR APPAREIL, et c'est une
    /// régression que j'ai introduite. Appel réel du 2026-08-27, haut-parleur:
    /// 477 trames atténuées sur 1175, soit 41 % du temps, à −24 dB. Rapport du
    /// propriétaire: « un petit soufflement derrière ».
    ///
    /// Le niveau d'atténuation n'était pas le problème — c'est de le poser sur
    /// LE BRUIT DE FOND. Faire plonger le fond de 24 dB puis le relâcher, 41 %
    /// du temps, calé sur la parole du correspondant, c'est du POMPAGE: le
    /// souffle de la pièce respire au rythme de l'autre. Une rampe ne le corrige
    /// pas (elle enlève le clic, pas la modulation), et baisser la profondeur
    /// laisserait l'écho revenir.
    ///
    /// Ce qu'on veut retirer, c'est l'écho — pas la pièce. Donc l'atténuation ne
    /// descend jamais SOUS le bruit de fond: le gain est borné par
    /// `bruit / micRms`. Conséquences, et elles tombent toutes du bon côté:
    ///
    ///   • micro très au-dessus du fond (vraie fuite forte) ⇒ borne basse, on
    ///     atténue à fond, jusqu'à la profondeur de la route;
    ///   • micro à peine au-dessus du fond ⇒ il n'y a presque rien à retirer, et
    ///     le gain reste proche de 1: le fond ne bouge pas, donc pas de souffle;
    ///   • micro AU niveau du fond ⇒ gain 1,0, on ne touche à rien.
    ///
    /// La profondeur de route reste le plancher: on ne va jamais plus bas
    /// qu'elle, quoi que dise l'estimation du bruit.
    ///
    /// - Parameters:
    ///   - micRms: level of the raw mic frame, same units as `rms` above.
    ///   - route: where the far end is currently being played.
    ///   - now: caller's clock.
    mutating func targetGain(micRms: Double, route: Route, now: TimeInterval) -> Float {
        updateNoiseFloor(micRms: micRms)

        let farLevel = farEndLevel(now: now)
        // Rien d'assez fort ne joue ⇒ rien ne peut faire écho.
        guard farLevel >= EchoSuppressorGate.farEndFloorRms else { return 1.0 }
        // Le local est du même ordre que ce qui joue ⇒ quelqu'un parle vraiment.
        // Ce garde protège la double-parole ET empêche la parole d'entraîner
        // l'estimation du couplage.
        guard micRms < farLevel * EchoSuppressorGate.doubleTalkRatio else { return 1.0 }

        let routeFloor: Float
        switch route {
        case .loudspeaker:       routeFloor = EchoSuppressorGate.loudspeakerGain
        case .earpieceOrHeadset: routeFloor = EchoSuppressorGate.earpieceGain
        }

        // __ADAPTIVE_COUPLING_2026_08_27__ On est ici parce que le correspondant
        // joue et que le local se tait: la part du micro AU-DESSUS de son bruit de
        // fond est donc de l'écho, et son rapport au niveau joué est le couplage.
        // C'est la seule fenêtre où l'observation est propre, et c'est la seule
        // où on met à jour.
        let excess = max(0, micRms * micRms - noiseFloorRms * noiseFloorRms).squareRoot()

        // Une trame où le micro est À SON PLANCHER ne dit RIEN du couplage: elle
        // est compatible avec « pas de fuite » comme avec « une pause du
        // correspondant ». La prendre pour une observation de couplage nul tire
        // l'estimation vers le bas à chaque silence, et la suppression se met à
        // sous-estimer la fuite d'autant que l'autre fait des pauses — soit tout
        // le temps, dans une conversation.
        //
        // On ne met donc à jour que sur les trames INFORMATIVES: celles où le
        // micro dépasse nettement son propre plancher.
        let informative = excess > noiseFloorRms * EchoSuppressorGate.informativeExcessRatio
        if informative {
            let observed = min(EchoSuppressorGate.maxCoupling, max(0, excess / farLevel))
            let current = coupling ?? 0
            let updated = current + (observed - current) * EchoSuppressorGate.couplingRate
            coupling = min(EchoSuppressorGate.maxCoupling, max(0, updated))
        }

        // L'écho prédit, et sa soustraction EN PUISSANCE. Ce qui reste est ce que
        // le micro entendait qui n'était pas l'écho — c'est-à-dire la pièce, à
        // son niveau naturel. Elle n'est donc jamais écrasée, et c'est ce qui
        // supprime le pompage sans avoir à le garder par un seuil.
        let predictedEcho = farLevel * (coupling ?? 0)
        guard predictedEcho > 0, micRms > 0 else { return 1.0 }
        let residual = max(0, micRms * micRms - predictedEcho * predictedEcho).squareRoot()
        let ratio = Float(residual / micRms)
        // Le plancher de route reste la profondeur maximale: on ne descend jamais
        // plus bas, quoi que dise la prédiction.
        return max(routeFloor, min(1.0, ratio))
    }

    /// Couplage courant, exposé pour DIAG_PATH: une estimation qu'on ne peut pas
    /// lire est une estimation qu'on ne peut pas mettre en cause.
    var currentCoupling: Double { coupling ?? 0 }

    /// Minimum-tracker: descend instantanément, remonte lentement. C'est ce qui
    /// en fait un PLANCHER et non un suiveur de niveau — une estimation qui
    /// remonterait vite épouserait la parole et le clamp ne servirait plus à
    /// rien.
    ///
    /// Alimenté par la trame BRUTE du micro, jamais par notre sortie atténuée:
    /// sinon l'estimation suivrait notre propre gain et la boucle se
    /// refermerait sur elle-même.
    private mutating func updateNoiseFloor(micRms: Double) {
        noiseOpenSubMin = min(noiseOpenSubMin, micRms)
        noiseFramesInSub += 1
        if noiseFramesInSub >= EchoSuppressorGate.noiseSubWindowFrames {
            noiseSubMins[noiseSubIndex] = noiseOpenSubMin
            noiseSubIndex = (noiseSubIndex + 1) % noiseSubMins.count
            noiseOpenSubMin = .infinity
            noiseFramesInSub = 0
        }
        // Minimum sur les sous-fenêtres closes ET sur celle en cours: sans cette
        // dernière, le tout premier demi-seconde d'appel n'aurait aucune
        // estimation du tout.
        var lowest = noiseOpenSubMin
        for v in noiseSubMins where v.isFinite { lowest = min(lowest, v) }
        noiseFloorRms = lowest.isFinite
            ? max(EchoSuppressorGate.minNoiseFloorRms, lowest)
            : max(EchoSuppressorGate.minNoiseFloorRms, micRms)
    }

    /// Exposé pour DIAG_PATH: une estimation de bruit qu'on ne peut pas lire est
    /// une estimation qu'on ne peut pas mettre en cause.
    var currentNoiseFloorRms: Double { noiseFloorRms }
}
