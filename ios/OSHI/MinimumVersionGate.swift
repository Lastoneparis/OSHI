//
//  MinimumVersionGate.swift
//  OSHI — le rappel de mise a jour qui NE PEUT PAS murer l'app.
//
//  CE QU'IL FAIT
//  Au lancement et a chaque retour au premier plan, il demande au serveur du
//  projet (`GET /v2/config`, via `V2Client` — meme hote, meme client HTTP que
//  le reste de l'app) une version minimale supportee, et si cette build est en
//  dessous il propose une mise a jour. Rien de plus.
//
//  CE QU'IL NE PEUT PAS FAIRE, ET C'EST STRUCTUREL
//  `MinimumVersionGateState` n'a que deux cas, et `blocksMessaging` rend `false`
//  pour les deux. Il n'existe AUCUNE valeur que ce type puisse prendre qui
//  empeche d'ecrire, d'envoyer ou de lire un message, ni qui touche au chemin
//  maille/hors-ligne. Ce n'est pas « il ne le fait pas aujourd'hui »: le cas
//  n'existe pas. C'est la difference avec `V2FeatureFlag.updateRequired`, qui
//  lui remplace toute l'interface par `UpdateRequiredView` — deux leviers
//  distincts, et celui-ci est le doux.
//
//  ECHOUE OUVERT, TOUJOURS
//  Serveur injoignable, delai depasse, code non-200, corps illisible, champ
//  absent: aucun rappel, en silence. Le seul etat qu'une panne peut produire
//  est « rien a signaler ». Un incident reseau ne doit jamais se transformer en
//  ecran devant quelqu'un qui voulait envoyer un message.
//
//  COMPARAISON NUMERIQUE, PAS LEXICOGRAPHIQUE
//  Tout passe par `AppVersion.compare`. "1.0.9" < "1.0.43" en entiers, et
//  l'inverse en chaines — ce projet a deja livre ce bug une fois.
//
//  CACHE
//  La derniere reponse VALIDE est conservee, donc un lancement hors ligne se
//  comporte comme le dernier lancement en ligne. Un corps illisible ne remplace
//  jamais une bonne reponse, et ne bloque rien non plus.
//

import Foundation
import SwiftUI

// MARK: - L'etat, et ce qu'il ne peut pas dire

/// Ce que le serveur a dit de la version minimale. Deux cas, et aucun des deux
/// n'empeche quoi que ce soit.
enum MinimumVersionGateState: Equatable {
    /// Rien a signaler — y compris tous les cas d'echec.
    case noGate
    /// Cette build est anterieure au minimum annonce. On PROPOSE, on n'impose pas.
    case updateAvailable(minimum: String)

    /// Le coeur du contrat: ce portillon ne bloque jamais la messagerie.
    ///
    /// Ecrit comme une constante et pas comme un `switch`, pour qu'un cas ajoute
    /// plus tard ne puisse pas y glisser un « true » sans qu'on le voie.
    var blocksMessaging: Bool { false }

    var shouldPrompt: Bool {
        if case .updateAvailable = self { return true }
        return false
    }

    var minimum: String? {
        if case .updateAvailable(let minimum) = self { return minimum }
        return nil
    }
}

// MARK: - Le portillon

/// `@MainActor` volontairement: l'etat pilote une vue, et une mise a jour
/// publiee depuis un thread de fond declenche l'avertissement SwiftUI
/// « Publishing changes from background threads ». Rien ici n'est couteux —
/// une requete de 8 s au plus, qui suspend au lieu de bloquer.
@MainActor
final class MinimumVersionGate: ObservableObject {

    static let shared = MinimumVersionGate()

    static let cacheKey = "oshi_min_supported_version"
    static let dismissedKey = "oshi_min_supported_version_dismissed"

    /// Ce que le corps de `/v2/config` nous a appris.
    enum ConfigReading: Equatable {
        /// Corps illisible (pas du JSON, pas un objet, champ du mauvais type).
        /// Se traite EXACTEMENT comme une panne reseau: on garde le cache.
        case unusable
        /// Reponse valide qui ne demande aucune version minimale.
        case noMinimum
        case minimum(String)
    }

    @Published private(set) var state: MinimumVersionGateState = .noGate

    private let defaults: UserDefaults
    private let currentVersion: String
    private let fetch: () async throws -> Data
    private var lastFetchAt: Date?

    /// - Parameters:
    ///   - fetch: la recuperation reseau, injectee pour les tests. Par defaut
    ///     `V2Client.fetchClientConfig()` — l'hote du projet, pas une nouvelle
    ///     adresse: `V2Client.Config.baseURL` porte deja `oshi-messenger.com`,
    ///     et son delai de 8 s borne l'attente.
    init(defaults: UserDefaults = .standard,
         currentVersion: String = AppVersion.current,
         fetch: @escaping () async throws -> Data = { try await V2Client().fetchClientConfig() }) {
        self.defaults = defaults
        self.currentVersion = currentVersion
        self.fetch = fetch
        // Demarre sur la derniere bonne reponse connue: un lancement hors ligne
        // se comporte comme le dernier lancement en ligne.
        self.state = Self.decide(currentVersion: currentVersion,
                                 minimumSupported: defaults.string(forKey: Self.cacheKey))
    }

    // MARK: La decision, pure

    /// Isolee et sans reseau, sans horloge, sans Bundle: c'est la seule partie
    /// dont une erreur se voit par un utilisateur, elle doit se verifier seule.
    static func decide(currentVersion: String, minimumSupported: String?) -> MinimumVersionGateState {
        guard let minimum = minimumSupported?.trimmingCharacters(in: .whitespacesAndNewlines),
              !minimum.isEmpty else { return .noGate }
        // Illisible d'un cote ou de l'autre => on ne sait pas => rien.
        guard let older = AppVersion.isOlder(currentVersion, than: minimum) else { return .noGate }
        return older ? .updateAvailable(minimum: minimum) : .noGate
    }

    /// Lit le corps de `/v2/config` sans jamais lever.
    ///
    /// DEUX CLES, ET LA RAISON EST UN PIEGE REEL: les deux plateformes ne
    /// partagent PAS leur numerotation. iOS est en 1.0.x, Android en 1.6.x
    /// (`MIN_KEY_SCHEDULE_VERSION = "1.6.14"` cote Kotlin). Un unique
    /// `min_supported_version` regle sur la valeur Android dirait a CHAQUE
    /// appareil iOS qu'il est en retard, pour toujours. `ios_min_supported_version`
    /// donne a l'exploitation un levier par plateforme; Android lit
    /// symetriquement `android_min_supported_version` avec le meme repli.
    ///
    /// La cle specifique GAGNE quand elle est presente. Si elle est presente
    /// mais illisible, on ne retombe PAS sur la generique: un reglage iOS
    /// casse ne doit pas se faire remplacer en silence par un reglage prevu
    /// pour l'autre plateforme.
    static func read(_ data: Data) -> ConfigReading {
        struct Body: Decodable {
            let ios_min_supported_version: String?
            let min_supported_version: String?
        }
        guard let body = try? JSONDecoder().decode(Body.self, from: data) else { return .unusable }
        let specific = body.ios_min_supported_version?.trimmingCharacters(in: .whitespacesAndNewlines)
        let shared = body.min_supported_version?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let raw = (specific?.isEmpty == false ? specific : shared), !raw.isEmpty
        else { return .noMinimum }
        // Une valeur presente mais illisible ("latest", "v2") est une erreur
        // d'exploitation, pas une instruction: on la jette plutot que de la
        // mettre en cache et de la re-evaluer a chaque lancement.
        guard AppVersion.components(raw) != nil else { return .unusable }
        return .minimum(raw)
    }

    // MARK: Le cycle de vie

    /// Lancement et retour au premier plan. Etrangle a 60 s pour qu'un
    /// va-et-vient rapide entre apps ne martele pas l'endpoint.
    func refreshIfNeeded(now: Date = Date()) async {
        if let last = lastFetchAt, now.timeIntervalSince(last) < 60 { return }
        lastFetchAt = now
        await refresh()
    }

    func refresh() async {
        let data: Data
        do {
            data = try await fetch()
        } catch {
            // Injoignable, delai depasse, non-200: on garde la derniere bonne
            // reponse et on ne dit rien. Pas de log a chaque retour d'app.
            return
        }
        switch Self.read(data) {
        case .unusable:
            // Corps illisible: traite comme une panne. Il ne cree pas de rappel
            // et il n'efface pas non plus une bonne reponse deja connue.
            return
        case .noMinimum:
            defaults.removeObject(forKey: Self.cacheKey)
            apply(.noGate)
        case .minimum(let minimum):
            defaults.set(minimum, forKey: Self.cacheKey)
            apply(Self.decide(currentVersion: currentVersion, minimumSupported: minimum))
        }
    }

    private func apply(_ next: MinimumVersionGateState) {
        guard state != next else { return }
        state = next
    }

    // MARK: L'ecart

    /// Le rappel est-il a montrer ? Non si l'utilisateur l'a deja ecarte POUR
    /// CE minimum: on ne harcele pas. Un minimum plus recent le remontre une
    /// fois, ce qui est le comportement attendu d'un nouveau fait.
    var shouldShowPrompt: Bool {
        guard let minimum = state.minimum else { return false }
        return defaults.string(forKey: Self.dismissedKey) != minimum
    }

    func dismissPrompt() {
        guard let minimum = state.minimum else { return }
        defaults.set(minimum, forKey: Self.dismissedKey)
        objectWillChange.send()
    }

    func resetForTesting() {
        defaults.removeObject(forKey: Self.cacheKey)
        defaults.removeObject(forKey: Self.dismissedKey)
        lastFetchAt = nil
        state = .noGate
    }
}

// MARK: - Le rappel a l'ecran

/// Fiche App Store d'OSHI. En dur, comme dans `UpdateRequiredView`: une URL
/// pilotee a distance sur un ecran de mise a jour est un moyen d'emmener les
/// gens ailleurs.
private let oshiStoreURL = URL(string: "https://apps.apple.com/app/oshi-mesh/id6753926350")!

/// Banniere haute, visible, et qu'on peut fermer. Elle ne couvre ni le champ de
/// saisie ni la liste des messages, et n'intercepte les touches que la ou elle
/// dessine.
struct UpdateAvailableBanner: View {

    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "arrow.down.circle.fill")
                .font(.title3)
                .foregroundStyle(.white)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedOrEnglish.text("update.prompt.title"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Text(LocalizedOrEnglish.text("update.prompt.body"))
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.9))
                    .fixedSize(horizontal: false, vertical: true)

                Link(destination: oshiStoreURL) {
                    Text(LocalizedOrEnglish.text("update.prompt.action"))
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.white)
                        .foregroundStyle(Theme.brand)
                        .clipShape(Capsule())
                }
                .padding(.top, 2)
            }

            Spacer(minLength: 0)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(6)
            }
            .accessibilityLabel(Text(LocalizedOrEnglish.text("update.prompt.dismiss")))
        }
        .padding(14)
        .background(Theme.brand, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
        .padding(.horizontal, 12)
    }
}

extension View {
    /// A poser a cote de `.rateAppPrompt()` sur la racine de la scene.
    func updateAvailablePrompt() -> some View {
        modifier(UpdateAvailablePromptModifier())
    }
}

private struct UpdateAvailablePromptModifier: ViewModifier {

    @ObservedObject private var gate = MinimumVersionGate.shared
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if gate.shouldShowPrompt {
                    UpdateAvailableBanner { gate.dismissPrompt() }
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .zIndex(1500)
                }
            }
            .animation(.easeInOut(duration: 0.25), value: gate.shouldShowPrompt)
            // Lancement.
            .task { await gate.refreshIfNeeded() }
            // Retour au premier plan.
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active else { return }
                Task { await gate.refreshIfNeeded() }
            }
    }
}
