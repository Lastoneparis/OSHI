//
//  PeerVersionCompat.swift
//  OSHI — savoir quelle version parle en face, et comparer deux versions
//  SANS se tromper.
//
//  POURQUOI CE FICHIER EXISTE
//  Le commit 0cfc8c9 a change le calendrier de cles du Double Ratchet: un
//  repondeur v3 demarrait sa chaine d'envoi sur 32 octets nuls, il la derive
//  desormais (`DoubleRatchetSession.responderInitialChain`). C'est une rupture
//  de PROTOCOLE: un telephone a jour qui parle a un telephone en 1.0.43 echoue
//  a dechiffrer sur le chemin « le repondeur parle en premier ». Sur 535
//  appareils connus, 14 seulement sont sur la version courante — le parc est
//  massivement ancien.
//
//  On ne peut pas empecher ca. On peut le DIRE, et c'est tout l'objet de ce
//  fichier: apprendre la version du pair par la charge de profil qu'on echange
//  deja, et la comparer NUMERIQUEMENT.
//
//  LA COMPARAISON DE CHAINES EST UN BUG, PAS UN RACCOURCI
//  "1.0.9" > "1.0.43" en ordre lexicographique, parce que '9' > '4'. Ce projet
//  a deja paye cette erreur. Toute comparaison passe donc par `AppVersion`, qui
//  compare composant par composant, en entiers.
//
//  ET « JE NE SAIS PAS » N'EST PAS « ANCIEN »
//  Une chaine qu'on n'arrive pas a lire rend `nil`, jamais `.orderedAscending`.
//  Un pair muet (ancienne build qui n'envoie pas le champ) n'est pas signale:
//  la banniere ne se declenche que sur une version LUE et strictement plus
//  ancienne. Le prix d'un faux positif — dire a quelqu'un a jour de se mettre a
//  jour — est bien plus eleve que celui d'un silence.
//

import Foundation

// MARK: - Une chaine qui ne peut pas afficher sa propre cle

/// `.localized`, mais qui retombe sur l'anglais au lieu d'afficher la CLE.
///
/// __TROUVE PAR UN TEST, PAS PAR RELECTURE__ `NSLocalizedString` ne retombe pas
/// sur l'anglais quand une cle manque dans la langue de l'appareil: il rend la
/// CLE. Les cles ci-dessous viennent d'etre ajoutees a `en.lproj` et pas encore
/// aux 33 autres `.lproj`, donc sur un telephone francais — le simulateur de ce
/// projet en est un — la banniere affichait litteralement
/// « chat.peer_outdated.message ». La suite de tests serait restee verte: rien
/// ne casse, le texte est simplement absurde a l'ecran.
///
/// Le meme piege attend `update.required.*` (ajoute le 29/08 en `en.lproj`
/// seul) et toute cle neuve tant que la passe de traduction n'est pas faite.
///
/// Volontairement PAS un changement de `String.localized`: ce dernier sert des
/// milliers de chaines deja traduites partout, et le detourner pour ce cas
/// serait un changement global pour un probleme local.
enum LocalizedOrEnglish {
    static func text(_ key: String) -> String {
        let localized = key.localized
        guard localized == key else { return localized }
        guard let path = Bundle.main.path(forResource: "en", ofType: "lproj"),
              let english = Bundle(path: path) else { return localized }
        return english.localizedString(forKey: key, value: key, table: nil)
    }
}

// MARK: - Comparaison numerique de versions

/// Comparaison composant par composant de versions pointees ("1.0.43").
enum AppVersion {

    /// `"1.0.43"` -> `[1, 0, 43]`.
    ///
    /// Rend `nil` pour tout ce qui n'est pas une suite non vide d'entiers
    /// positifs separes par des points: chaine vide, composant vide ("1..3"),
    /// suffixe ("1.0.43-beta"), lettres, nombre trop grand pour un `Int`.
    /// Un `nil` remonte jusqu'a l'appelant, qui doit le lire « je ne sais pas ».
    static func components(_ version: String) -> [Int]? {
        let trimmed = version.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let parts = trimmed.split(separator: ".", omittingEmptySubsequences: false)
        guard !parts.isEmpty else { return nil }
        var out: [Int] = []
        out.reserveCapacity(parts.count)
        for part in parts {
            guard !part.isEmpty,
                  part.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let value = Int(part) else { return nil }
            out.append(value)
        }
        return out
    }

    /// Compare deux versions. `nil` si l'une des deux est illisible.
    ///
    /// Les longueurs differentes sont completees par des zeros, donc
    /// "1.0" == "1.0.0" et "1.0" < "1.0.1".
    static func compare(_ lhs: String, _ rhs: String) -> ComparisonResult? {
        guard let left = components(lhs), let right = components(rhs) else { return nil }
        let width = max(left.count, right.count)
        for index in 0..<width {
            let a = index < left.count ? left[index] : 0
            let b = index < right.count ? right[index] : 0
            if a < b { return .orderedAscending }
            if a > b { return .orderedDescending }
        }
        return .orderedSame
    }

    /// `lhs` est-elle strictement anterieure a `rhs` ?
    /// `nil` = illisible, et l'appelant doit alors ne RIEN faire.
    static func isOlder(_ lhs: String, than rhs: String) -> Bool? {
        guard let result = compare(lhs, rhs) else { return nil }
        return result == .orderedAscending
    }

    /// `CFBundleShortVersionString` de cette build ("1.0.43"). Chaine vide si le
    /// bundle ne le porte pas — ce qui est illisible, donc « je ne sais pas ».
    static var current: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }
}

// MARK: - Le seuil d'interoperabilite du ratchet

/// La version a partir de laquelle le repondeur v3 derive sa chaine initiale.
enum RatchetInterop {

    /// 1.0.43 (build 116) est la derniere version publiee avec l'ancien
    /// calendrier de cles; le nouveau commence donc a la suivante.
    ///
    /// En dur, et volontairement: c'est un fait de PROTOCOLE fige dans le
    /// binaire, pas un reglage. Le piloter depuis le serveur permettrait de
    /// faire afficher « votre correspondant est en retard » a propos de
    /// n'importe qui.
    ///
    /// __CE SEUIL EST UN NUMERO iOS, ET IL N'EN EXISTE PAS DE COMMUN__
    /// Android numerote en 1.6.x (`MIN_KEY_SCHEDULE_VERSION = "1.6.14"`), iOS
    /// en 1.0.x. Aucun nombre n'est simultanement juste sur deux echelles
    /// independantes: comparer le "1.6.13" d'un Android a ce seuil-ci ne veut
    /// rien dire, et comparer le "1.0.43" d'un iPhone a celui de Kotlin
    /// etiquetait CHAQUE iPhone « en retard », pour toujours.
    ///
    /// C'est pourquoi `peerIsOutdated` exige desormais que le pair ait DIT a
    /// quelle echelle appartient son numero, et compare ensuite au plancher DE
    /// CETTE ECHELLE (`minimumVersionByPlatform`). Le tag `appPlatform` voyage
    /// a cote de `appVersion` dans la meme charge de profil, ecrit par le meme
    /// `stamp` cote Kotlin (`ProfileVersionWire.KEY_PLATFORM`) et par le meme
    /// initialiseur ici. Un pair sans tag n'est pas juge: cela couvre Android
    /// 1.6.14, qui a livre `appVersion` seul, et toute build iOS <= 1.0.43.
    ///
    /// __ET LA LIMITE DE FOND DE CETTE BANNIERE__
    /// Les appareils qu'on veut prevenir POUR CETTE RUPTURE-CI sont exactement
    /// ceux qui n'envoient pas `appVersion`: la 1.0.43 ne connait pas le champ.
    /// Ce mecanisme ne sauve donc pas la transition 1.0.43 -> 1.0.44; il rend
    /// la SUIVANTE annoncable. Le dire ici pour que personne ne lise cette
    /// banniere comme une reparation de la rupture qui l'a fait ecrire.
    static let minimumVersion = "1.0.44"

    /// Le seuil ANDROID pour la MEME rupture, sur l'echelle 1.6.x.
    ///
    /// `AppVersions.MIN_KEY_SCHEDULE_VERSION` cote Kotlin. Les deux nombres
    /// designent le meme changement de protocole; ils different parce que les
    /// deux plateformes numerotent l'app differemment, pas parce qu'elles ne
    /// livrent pas la meme chose.
    static let androidMinimumVersion = "1.6.14"

    /// Le plancher a appliquer, PAR PLATEFORME.
    ///
    /// C'est ce que le tag `appPlatform` rend enfin possible: tant que le fil
    /// ne disait pas a quelle echelle appartenait un numero, la seule conduite
    /// honnete etait le silence. Maintenant qu'il le dit, un pair Android reste
    /// en 1.6.13 peut etre prevenu depuis un iPhone, et l'inverse est vrai cote
    /// Kotlin, qui tient la meme table.
    ///
    /// Une plateforme absente de cette table n'est PAS jugee. C'est le defaut
    /// pour tout ce qui viendrait apres — un client tiers, une plateforme
    /// future — et il vaut mieux ne rien dire que de comparer un numero a une
    /// echelle qui n'est pas la sienne.
    static let minimumVersionByPlatform: [String: String] = [
        "ios": minimumVersion,
        "android": androidMinimumVersion,
    ]

    /// L'identifiant de plateforme que cette build ecrit et attend, identique
    /// a `ProfileVersionWire.PLATFORM_IOS` cote Kotlin. En minuscules des deux
    /// cotes, et compare en minuscules a la lecture.
    static let platform = "ios"

    /// Ce pair est-il, de facon CERTAINE, sur une version qui ne parle pas le
    /// nouveau calendrier ?
    ///
    /// DEUX choses doivent etre connues avant de dessiner quoi que ce soit:
    ///
    ///   * le pair a annonce une version — les builds <= 1.0.43 n'envoient pas
    ///     le champ, et un pair muet ne doit pas etre harcele; et
    ///   * le pair a annonce qu'il est un **iOS** — sans quoi son numero
    ///     appartient peut-etre a l'echelle 1.6.x d'Android, ou le seuil
    ///     ci-dessus ne signifie rien.
    ///
    /// `nil` et illisible rendent tous deux `false`, dans les deux champs. On
    /// ne signale que ce qu'on a lu, et le silence reste le bon defaut.
    ///
    /// Pas de valeur par defaut pour `platform`: chaque appelant doit dire ce
    /// qu'il sait de la plateforme du pair, plutot que d'heriter d'un « je ne
    /// sais pas » qu'il n'a pas choisi.
    static func peerIsOutdated(_ peerVersion: String?, platform peerPlatform: String?) -> Bool {
        guard let key = peerPlatform?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              let floor = minimumVersionByPlatform[key] else { return false }
        guard let peerVersion,
              let older = AppVersion.isOlder(peerVersion, than: floor) else { return false }
        return older
    }
}

// MARK: - Ce que les pairs nous ont dit de leur version

/// Version d'app ET plateforme declarees par chaque pair, apprises via
/// `📸PROFILE_UPDATE📸`.
///
/// __LES DEUX VONT ENSEMBLE, DONC ILS SONT STOCKES ENSEMBLE__
/// Un numero sans son echelle ne veut rien dire (voir `RatchetInterop`). Les
/// deux champs vivent donc dans UNE entree de dictionnaire, ecrite en un seul
/// `defaults.set`, exactement comme Kotlin les ecrit dans une seule transaction
/// SharedPreferences. Deux cles separees pourraient etre relues desynchronisees
/// — une version neuve avec l'ancienne plateforme — et la banniere accuserait
/// alors quelqu'un a jour.
///
/// `ObservableObject` parce que la banniere de conversation doit reapparaitre
/// quand la charge de profil arrive — elle arrive apres l'ouverture de l'ecran,
/// pas avant. Les ecritures viennent du pipeline de reception (hors main), donc
/// `objectWillChange` est repousse sur la main queue: publier depuis un thread
/// de fond declenche l'avertissement « Publishing changes from background ».
final class PeerVersionStore: ObservableObject {

    static let shared = PeerVersionStore()

    /// `_v2`: la forme stockee est passee de `[pair: version]` a
    /// `[pair: [cle: valeur]]` pour porter la plateforme. Aucune migration
    /// n'est ecrite parce qu'il n'y a rien a migrer — `_v1` n'a jamais quitte
    /// l'arbre de travail, la 1.0.43 n'ayant jamais connu ce magasin. Une
    /// nouvelle cle plutot que la meme relue en `as?` optimiste: un cast qui
    /// echoue rendrait un magasin vide en silence, ce qui se lit « aucun pair
    /// n'est en retard » — precisement le mauvais defaut.
    private static let storeKey = "peerAppVersions_v2"
    private static let versionField = "v"
    /// __PEER_VERSION_EVIDENCE_2026_10_09__ "We have PROVEN this peer runs
    /// pre-0cfc8c9 code", from a message that only decrypts under the old
    /// zero-seeded responder chain. A separate field from `v` on purpose: we
    /// must not invent a version string the peer never sent, and the two
    /// answers come from different kinds of knowledge — one is a self-report,
    /// this one is an observation.
    private static let evidenceField = "legacyRatchet"
    private static let platformField = "p"

    private let defaults: UserDefaults
    private let lock = NSLock()
    private var entries: [String: [String: String]]

    /// __PEER_VERSION_EVIDENCE_2026_10_09__ One spelling per peer.
    ///
    /// The same identity reaches this store in two encodings: the receive
    /// pipeline records under `MessageManager.canonicalIdentity(...)`, while the
    /// conversation banner asks with the raw `participantPublicKey`. Standard
    /// base64 uses `+` and `/` and is padded; anything that came back through
    /// the relay is base64url and unpadded. Keyed verbatim, those two land in
    /// DIFFERENT dictionary entries — so the banner could ask about a peer whose
    /// version was filed under the other spelling and always get "not
    /// outdated". Same folding as `MessageManager.normalizeAliasKey`, applied on
    /// every read and write so the two can never disagree again.
    ///
    /// Entries written before this existed are simply not found and get
    /// rewritten by the peer's next profile payload.
    private static func normalized(_ key: String) -> String {
        key.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.entries = defaults.dictionary(forKey: Self.storeKey) as? [String: [String: String]] ?? [:]
    }

    /// Enregistre ce que `peer` vient d'annoncer.
    ///
    /// `nil` N'EFFACE RIEN, ni pour la version ni pour la plateforme: un pair a
    /// jour qui envoie une charge de profil tronquee, ou par un chemin plus
    /// ancien de son cote, ne doit pas effacer ce qu'on savait deja. On
    /// n'oublie jamais ce qu'on a lu, on le remplace.
    ///
    /// Une plateforme sans version n'est pas enregistree: un tag seul ne dit
    /// rien, et c'est la meme regle que `stamp` applique a l'emission.
    func record(_ version: String?, platform: String?, for rawPeer: String) {
        let peer = Self.normalized(rawPeer)
        guard !peer.isEmpty else { return }
        guard let version = version?.trimmingCharacters(in: .whitespacesAndNewlines),
              !version.isEmpty else { return }
        let platform = platform?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        lock.lock()
        var entry = entries[peer] ?? [:]
        entry[Self.versionField] = version
        if let platform, !platform.isEmpty { entry[Self.platformField] = platform }
        let changed = entries[peer] != entry
        if changed { entries[peer] = entry }
        let snapshot = entries
        lock.unlock()

        guard changed else { return }
        defaults.set(snapshot, forKey: Self.storeKey)
        DispatchQueue.main.async { [weak self] in self?.objectWillChange.send() }
    }

    func version(for peer: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return entries[Self.normalized(peer)]?[Self.versionField]
    }

    func platform(for peer: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return entries[Self.normalized(peer)]?[Self.platformField]
    }

    /// __PEER_VERSION_EVIDENCE_2026_10_09__ Records that `peer` sent a message
    /// which only decrypts under the responder chain used before commit
    /// 0cfc8c9 — i.e. it is definitely running an old build.
    ///
    /// This is the only way the banner can ever fire for the peers it was
    /// written for. `record(_:platform:for:)` learns the version the peer
    /// ANNOUNCES, but `appVersion`/`appPlatform` shipped in the same release
    /// that fixed the chain, so a 1.0.43 peer announces neither, the guard in
    /// `RatchetInterop.peerIsOutdated` returns false on a nil platform, and the
    /// conversation banner stayed invisible for every genuinely outdated peer.
    func recordLegacyRatchetEvidence(for rawPeer: String) {
        let peer = Self.normalized(rawPeer)
        guard !peer.isEmpty else { return }
        lock.lock()
        var entry = entries[peer] ?? [:]
        let changed = entry[Self.evidenceField] != "1"
        if changed {
            entry[Self.evidenceField] = "1"
            entries[peer] = entry
        }
        let snapshot = entries
        lock.unlock()

        guard changed else { return }
        defaults.set(snapshot, forKey: Self.storeKey)
        DispatchQueue.main.async { [weak self] in self?.objectWillChange.send() }
    }

    /// La question que pose la banniere de conversation.
    func peerIsOutdated(_ peer: String) -> Bool {
        lock.lock()
        let entry = entries[Self.normalized(peer)]
        lock.unlock()
        // Observed evidence outranks a self-report: a peer that cannot speak
        // the current chain is outdated whatever it claims, and an old build
        // claims nothing at all.
        if entry?[Self.evidenceField] == "1" { return true }
        return RatchetInterop.peerIsOutdated(entry?[Self.versionField],
                                            platform: entry?[Self.platformField])
    }

    func removeAllForTesting() {
        lock.lock(); entries.removeAll(); lock.unlock()
        defaults.removeObject(forKey: Self.storeKey)
    }
}
