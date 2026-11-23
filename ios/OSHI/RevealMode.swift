//
//  RevealMode.swift
//  OSHI
//
//  The sender's choice for the timed photo reveal: OFF by default, and an
//  explicit mode you turn on for the one photo you want to tease.
//
//  THE CONSTRAINT THIS FILE EXISTS TO HONOUR
//  -----------------------------------------
//  Hugo, in his words: "this should be a specific mode to activate but by default
//  still send the picture in one tap normally."
//
//  So the ordinary path must not grow a step. Sending a photo is one tap, exactly
//  as before; nothing here is on the way. The reveal is a mode you deliberately
//  arm, and it announces itself while it is armed.
//
//  WHY IT DISARMS AFTER EVERY SEND
//  -------------------------------
//  A sticky mode is the dangerous version of this feature. Arm it once for a
//  flirt, forget, and the next photo — a boarding pass, a receipt, a photo of a
//  whiteboard someone needs NOW — silently arrives as a blur that clears in two
//  hours. The failure is invisible to the sender: their own copy looks normal.
//
//  So `armed` returns to `.off` the moment a send consumes it. The cost is real
//  and worth naming: sending three teasing photos in a row means arming it three
//  times. That is the right trade — the mistake it prevents is silent and lands
//  on the RECIPIENT, while the annoyance it causes is visible and lands on the
//  person who chose the mode.
//
//  NO NEW LOCALIZED STRINGS
//  ------------------------
//  The durations are rendered by `MeasurementFormatter`, which already speaks
//  every language the system does — "20 min", "2 h", "2 Std.", "2時間". Inventing
//  `reveal.duration.20min` in 34 files would be strictly worse: a hand-written
//  translation that drifts, for something the OS already gets right. The only
//  word needed is "off", and `common.off` already exists in all 34.
//

import Foundation
import SwiftUI

// MARK: - The mode

/// How a photo should reach the recipient.
enum RevealMode: Equatable, Hashable {
    /// The normal path. One tap, full photo, nothing added.
    case off
    /// The photo becomes legible over `duration`, starting when it is sent.
    case timed(duration: TimeInterval)

    /// The presets offered as one-tap chips.
    ///
    /// Deliberately four, and deliberately not a free-form picker. A duration
    /// wheel turns a playful gesture into data entry, and nobody teasing someone
    /// wants to scroll to 00:20:00. The range covers "look in a minute" to "wait
    /// all afternoon"; anything longer is better served by simply not sending it
    /// yet.
    /// __REVEAL_LONG_2026_08_22__ Hugo a demandé d'aller bien au-delà: 8 h, 24 h,
    /// 48 h et une semaine. Le commentaire d'origine défendait l'inverse — « plus
    /// long, autant ne pas l'envoyer tout de suite » — c'était un avis produit, et
    /// c'est son app.
    ///
    /// Le planificateur suit sans rien changer: `TimedRevealSchedule` ne connaît
    /// qu'une `totalDuration` et une fraction écoulée, il n'a aucun plafond.
    ///
    /// ⚠️ Ce que ça déplace vraiment, c'est la DURÉE DE VIE. Une révélation de deux
    /// heures survit à peu près à tout; une d'une semaine traverse forcément des
    /// arrêts de l'app, des redémarrages, des changements d'heure. La planification
    /// doit donc être reconstruite depuis une date de départ persistée, jamais
    /// depuis un minuteur en mémoire.
    static let presets: [TimeInterval] = [
        5 * 60, 20 * 60, 60 * 60, 2 * 3600,
        8 * 3600, 24 * 3600, 48 * 3600, 7 * 24 * 3600,
    ]

    var duration: TimeInterval? {
        if case .timed(let d) = self { return d }
        return nil
    }

    var isArmed: Bool { self != .off }
}

// MARK: - Formatting

enum RevealDurationFormat {
    /// « 5 min », « 2 h », « 2 j », « 1 sem » — dans la langue du système.
    ///
    /// __REVEAL_LONG_2026_08_22__ C'était un `MeasurementFormatter` sur
    /// `UnitDuration`, qui ne connaît ni le jour ni la semaine: avec les nouvelles
    /// durées, une semaine se serait affichée « 168 h ». `DateComponentsFormatter`
    /// sait dire jours et semaines, et les localise lui-même — toujours aucune
    /// chaîne à traduire.
    ///
    /// `maximumUnitCount = 1` est délibéré: on veut « 2 j », pas
    /// « 1 j 23 h 59 min ». Sur un compte à rebours c'est aussi ce qu'on lit le
    /// mieux de loin.
    static func short(_ seconds: TimeInterval) -> String {
        let value = max(0, seconds)
        let f = DateComponentsFormatter()
        f.unitsStyle = .abbreviated
        f.maximumUnitCount = 1
        f.allowedUnits = [.weekOfMonth, .day, .hour, .minute]
        if let text = f.string(from: value), !text.isEmpty { return text }

        // Repli: `DateComponentsFormatter` rend un optionnel, et une chaîne vide
        // pour une durée sous la minute. Ne jamais afficher de vide.
        let m = MeasurementFormatter()
        m.unitOptions = .providedUnit
        m.unitStyle = .short
        m.numberFormatter.maximumFractionDigits = 0
        return m.string(from: Measurement(value: value / 60, unit: UnitDuration.minutes))
    }

    /// The label under a still-revealing photo: how much longer to wait.
    ///
    /// Clamped at zero — a negative remainder formatted as "-3 min" reads as a
    /// bug, and a clock that has passed simply means the photo is clear.
    static func remaining(until endsAt: Date, now: Date = Date()) -> String {
        short(max(0, endsAt.timeIntervalSince(now)))
    }
}

// MARK: - Options d'envoi d'UNE pièce jointe

/// __REVEAL_OPTION_2026_08_22__ Ce qu'on a coché pour CETTE photo-là.
///
/// Exigence de Hugo, et c'est une exigence de correction, pas de confort:
/// « la révélation progressive doit se limiter à la photo envoyée. Les messages
/// et photos envoyés normalement après ou pendant une photo en progressif
/// doivent être reçus parfaitement clairs. »
///
/// D'où une VALEUR attachée à un envoi, et non un mode armé quelque part. Le
/// piège existe déjà dans ce fichier: `RevealModeManager` retient un mode PAR
/// PAIR jusqu'à ce qu'on le change — donc la photo suivante en hériterait sans
/// que personne ne l'ait demandé. Il n'est utilisé nulle part, et cette
/// structure est ce qui doit être utilisé à sa place.
///
/// L'EXCLUSION est portée ici, pas dans la vue: une photo qui met vingt minutes
/// à devenir nette ET disparaît à la première ouverture ne serait jamais vue. Un
/// écran peut oublier d'appliquer une règle; un type qui n'expose que des
/// mutations correctes ne le peut pas.
struct MediaSendOptions: Equatable {
    private(set) var isViewOnce: Bool = false
    private(set) var revealDuration: TimeInterval? = nil

    /// L'état par défaut: aucune option. C'est celui de CHAQUE nouvel envoi.
    static let none = MediaSendOptions()

    var isPlain: Bool { self == .none }

    mutating func setViewOnce(_ on: Bool) {
        isViewOnce = on
        if on { revealDuration = nil }
    }

    /// Retoucher la durée active la désarme: l'option se referme du même geste
    /// qu'elle s'ouvre.
    mutating func toggleReveal(_ duration: TimeInterval) {
        revealDuration = (revealDuration == duration) ? nil : duration
        if revealDuration != nil { isViewOnce = false }
    }
}

// MARK: - Arming

/// Holds the mode armed for the NEXT photo, per conversation.
///
/// Per conversation, not global: arming a reveal for one person must not arm it
/// for the next chat you open. Keyed by the peer's public key, which is the only
/// identifier every surface already has.
@MainActor
final class RevealModeManager: ObservableObject {
    static let shared = RevealModeManager()

    @Published private var armedByPeer: [String: RevealMode] = [:]

    private init() {}

    /// Deliberately NOT persisted. A mode that survives a relaunch is a mode the
    /// sender has forgotten about, which is exactly the failure this design is
    /// built to avoid.
    func mode(for peerKey: String) -> RevealMode {
        armedByPeer[peerKey] ?? .off
    }

    func arm(_ mode: RevealMode, for peerKey: String) {
        if mode == .off {
            armedByPeer.removeValue(forKey: peerKey)
        } else {
            armedByPeer[peerKey] = mode
        }
    }

    /// Reads the armed mode AND disarms it, in one step.
    ///
    /// One call, so no send path can read the mode and then forget to clear it —
    /// which would leave it armed for the next photo. See the file header.
    func consume(for peerKey: String) -> RevealMode {
        let mode = armedByPeer[peerKey] ?? .off
        armedByPeer.removeValue(forKey: peerKey)
        return mode
    }
}

// MARK: - The control

/// The chip row shown when the sender opens the reveal control.
///
/// Not on the normal path: this appears only after the timer button is tapped, so
/// the one-tap photo send is untouched.
struct RevealDurationPicker: View {
    let peerKey: String
    @ObservedObject private var manager = RevealModeManager.shared
    @Environment(\.dismiss) private var dismiss

    private var current: RevealMode { manager.mode(for: peerKey) }

    var body: some View {
        HStack(spacing: 8) {
            chip(title: "common.off".localized, isOn: !current.isArmed) {
                manager.arm(.off, for: peerKey)
            }
            ForEach(RevealMode.presets, id: \.self) { seconds in
                chip(title: RevealDurationFormat.short(seconds),
                     isOn: current.duration == seconds) {
                    manager.arm(.timed(duration: seconds), for: peerKey)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private func chip(title: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(isOn ? .semibold : .regular))
                .foregroundColor(isOn ? .white : .primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    Capsule(style: .continuous)
                        .fill(isOn ? AnyShapeStyle(Theme.brand)
                                   : AnyShapeStyle(Color.primary.opacity(0.07)))
                )
                // The chip is 34pt tall on screen; the finger gets 44.
                .contentShape(Rectangle())
                .frame(minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
    }
}

/// The armed-state indicator.
///
/// A mode you cannot see is a mode you will forget, and forgetting this one sends
/// someone a photo they cannot read. So while it is armed the composer carries a
/// visible badge with the duration in it — not an icon alone, the actual "20 min",
/// because the icon says "something is on" and the number says WHAT.
struct RevealArmedBadge: View {
    let duration: TimeInterval

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "hourglass")
                .font(.caption2.weight(.bold))
            Text(RevealDurationFormat.short(duration))
                .font(.caption2.weight(.semibold))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(Capsule(style: .continuous).fill(Theme.brand))
        .accessibilityLabel(RevealDurationFormat.short(duration))
    }
}
