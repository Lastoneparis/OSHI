//
//  RatchetHealSignal.swift
//  OSHI — la réparation d'un ratchet cassé voyage dans le CANAL DES MESSAGES.
//
//  SIGNALÉ, deux fois, par deux contacts différents: « je reçois la
//  notification mais pas le message. » Et: « j'ai réinitialisé de mon côté,
//  ils ont ouvert l'app, toujours rien. »
//
//  CE QUI ÉTAIT DÉJÀ LÀ, ET CE QUI MANQUAIT
//  `__V2_RATCHET_DEAD_END_2026_08_16__` fait déjà la moitié du travail: après
//  8 échecs, le récepteur efface SA session. Et `decrypt1to1` sait déjà adopter
//  une session neuve à partir d'un en-tête X3DH entrant (RATCHET-H1). Les deux
//  moitiés de la guérison existent depuis des mois.
//
//  Ce qui manquait, c'est le fil entre elles. Effacer ma session ne suffit pas:
//  le pair, lui, croit la sienne bonne, donc il continue d'écrire avec — et un
//  en-tête X3DH n'est attaché QUE par un expéditeur qui n'a plus de session.
//  Le pair détient le remède et n'a aucun moyen d'apprendre qu'on en a besoin.
//
//  L'ancien fil passait par Pinata/IPFS, un canal SÉPARÉ de celui des messages.
//  Un canal séparé peut tomber pendant que les messages passent — et c'est
//  exactement ce qui a été observé.
//
//  CE QUE FONT SIGNAL ET WHATSAPP
//  Ils n'envoient jamais de « reset ». Le récepteur, qui est le seul à SAVOIR,
//  renvoie un accusé de reprise (`<receipt type="retry">` chez WhatsApp) sur le
//  canal NORMAL, et l'expéditeur réémet avec une session neuve. La réparation
//  emprunte le chemin des messages: si les messages arrivent, la réparation
//  arrive.
//
//  ET ICI ELLE SE TRANSPORTE ELLE-MÊME
//  L'accusé part APRÈS `deleteSession`, donc `loadOrEstablish` ne trouve rien,
//  donc il refait X3DH, donc l'enveloppe PORTE l'en-tête. Le pair l'adopte en
//  la déchiffrant. L'accusé n'est pas une demande de réparation: il EST la
//  réparation. C'est le `PreKeySignalMessage` de Signal, avec les pièces
//  qu'OSHI possède déjà.
//
//  POURQUOI UN PRÉFIXE D'IDENTIFIANT, ET PAS SEULEMENT UN MARQUEUR DE CONTENU
//  Le relais pousse une notification pour toute enveloppe, et la NSE NE
//  DÉCHIFFRE PAS (choix délibéré: déchiffrer dans l'extension désynchronise le
//  ratchet). Elle ne peut donc pas voir le marqueur de contenu. Sans un signe
//  en clair, cet accusé afficherait une bannière sans message — c'est-à-dire
//  précisément le bug qu'il vient réparer.
//
//  D'où deux marques, une par lecteur:
//    • `payloadMarker` — CHIFFRÉ. Ce que lit l'app pour ne pas créer de bulle.
//    • `msgIdPrefix`   — EN CLAIR. Ce que lit la NSE pour taire la bannière.
//
//  CE QUE LE PRÉFIXE RÉVÈLE AU RELAIS, ET POURQUOI C'EST ACCEPTABLE
//  Il apprend « ces deux-là refont leurs clés ». Il connaissait déjà
//  l'expéditeur, le destinataire et l'instant; le contenu reste scellé. On
//  échange ce demi-bit de métadonnée contre la suppression d'une notification
//  fantôme — le symptôme même que l'utilisateur a signalé.
//

import Foundation
import CryptoKit

enum RatchetHealSignal {

    /// Contenu chiffré de l'accusé. Il n'est jamais affiché: sa valeur est
    /// l'en-tête X3DH que son enveloppe transporte, pas son texte.
    static let payloadMarker = "__RATCHET_HEAL__v1"

    /// Marque EN CLAIR sur l'identifiant d'enveloppe, seule chose que la NSE
    /// puisse lire sans déchiffrer.
    static let msgIdPrefix = "heal-"

    /// Identifiant d'accusé. Le suffixe reste un UUID: le préfixe dit le genre,
    /// il ne doit pas rendre deux accusés confondables.
    static func makeMsgId() -> String { msgIdPrefix + UUID().uuidString }

    /// Lu par la NSE, sur `userInfo["messageId"]`.
    static func isHealMsgId(_ msgId: String) -> Bool { msgId.hasPrefix(msgIdPrefix) }

    // MARK: - Grammaire de LIGNES (v2)
    //
    // POURQUOI LE CONTENU DOIT PORTER UNE LISTE
    // Effacer la session et refaire X3DH répare le CANAL, pas les messages
    // déjà perdus. MESURÉ sur les deux téléphones de l'utilisateur: 176
    // enveloppes abandonnées après les 8 tentatives — 176 messages annoncés
    // par une notification puis détruits. Le pair est le seul à les avoir
    // encore; il faut donc pouvoir les LUI NOMMER.
    //
    // POURQUOI DANS LE CHIFFRÉ, ET PAS DANS UN CHAMP D'ENVELOPPE
    // Le relais jette tout champ d'enveloppe qu'il ne connaît pas
    // (`ServerVPS/v2/relay_v2.js`, construction explicite de l'objet stocké).
    // Une liste posée à côté du chiffré n'arriverait jamais — et, si elle
    // arrivait, elle dirait au serveur QUELS messages ont échoué.
    //
    // POURQUOI UNE PREMIÈRE LIGNE INCHANGÉE, OCTET POUR OCTET
    // 1. Le reconnaisseur v1 est une ÉGALITÉ (`trimmed == payloadMarker`):
    //    `encode(resend: [])` rend donc EXACTEMENT le marqueur nu, et un pair
    //    resté en v1 continue de taire l'accusé tant qu'il n'y a rien à
    //    redemander — c'est le cas de très loin le plus fréquent.
    // 2. Le préfixe non-JSON en tête fait échouer immédiatement le
    //    `JSONDecoder().decode(V2FileKeyMessage.self)` que `parse(_:env:)`
    //    tente EN PREMIER côté routeur: l'accusé ne peut pas être pris pour
    //    une clé de fichier.
    //
    // POURQUOI UN SAUT DE LIGNE OBLIGATOIRE, ET PAS UN `hasPrefix` NU
    // `RatchetHealSignalTests` garde déjà la propriété « citer le marqueur
    // n'est pas l'être »: sans le `\n` exigé, il suffirait d'ÉCRIRE un
    // message commençant par le marqueur pour le faire disparaître chez
    // l'autre.

    /// Ce qu'un accusé demande. Une liste vide est la forme normale: l'accusé
    /// vaut d'abord par l'en-tête X3DH que son enveloppe transporte.
    struct Heal: Equatable, Sendable {
        var resend: [String]
    }

    /// Corps JSON des lignes qui suivent le marqueur. `resend` est optionnel
    /// pour qu'un corps d'une version future, amputé de ce champ, se décode
    /// quand même en liste vide au lieu de faire échouer la reconnaissance.
    private struct Body: Codable {
        let v: Int
        let resend: [String]?
    }

    /// Construit la charge. Sans rien à redemander, elle est BYTE POUR BYTE
    /// celle de la v1 — un pair qui n'a jamais entendu parler de la liste ne
    /// voit aucune différence.
    /// Identifiant d'accuse annoncant que l'EMETTEUR sait lire la forme
    /// enrichie. Le suffixe reste sous `msgIdPrefix`, donc l'extension de
    /// notification continue de taire la banniere sans rien savoir de tout ca.
    ///
    /// __HEAL_V1_SAFETY_2026_08_28__ POURQUOI UNE ANNONCE, ET PAS UN SIMPLE
    /// ENVOI. Le reconnaisseur de la v1 est une EGALITE stricte
    /// (`trimmed == payloadMarker`). Aucune charge portant une liste ne peut
    /// donc la satisfaire: un pair en 1.0.39 recevant la forme enrichie ne la
    /// reconnait pas, la laisse descendre jusqu'a l'affichage, et voit du JSON
    /// brut dans sa conversation. Et rien dans le protocole ne dit quelle
    /// version tourne en face — `capabilities()` ne repond que « v2 dispo ».
    ///
    /// D'ou l'annonce, qui voyage la ou la v1 ne regarde jamais: l'IDENTIFIANT
    /// d'enveloppe. Une v1 l'ignore entierement; une version recente y lit que
    /// le pair sait lire une liste, et ne joint la sienne qu'ensuite. Le premier
    /// accuse entre deux appareils ne rattrape donc rien — il guerit seulement
    /// la session — et les suivants rattrapent. C'est le prix d'une mise a jour
    /// de protocole sans negociation de version, et il se paie une fois.
    static let capableMsgIdPrefix = msgIdPrefix + "v2-"

    static func makeCapableMsgId() -> String { capableMsgIdPrefix + UUID().uuidString }

    /// Vrai si CET accuse annonce que son emetteur sait lire une liste.
    static func announcesListSupport(_ msgId: String) -> Bool {
        msgId.hasPrefix(capableMsgIdPrefix)
    }

    static func encode(resend: [String]) -> String {
        let ids = Array(resend.prefix(Policy.maxResendPerRequest))
        guard !ids.isEmpty else { return payloadMarker }
        guard let data = try? JSONEncoder().encode(Body(v: 2, resend: ids)),
              let json = String(data: data, encoding: .utf8) else {
            // Un encodeur qui échoue ne doit pas coûter la réparation: la
            // forme nue guérit encore la session, elle ne rattrape juste rien.
            return payloadMarker
        }
        // __HEAL_LENGTH_LEAK_2026_08_28__ La charge est BOURREE a une taille
        // fixe.
        //
        // AES-GCM n'ajoute aucun bourrage: |chiffre| = |clair| + 16. L'enveloppe
        // etant identifiable en clair par son prefixe `heal-`, le relais n'avait
        // qu'a mesurer le ciphertext pour deduire N — la grammaire est fixe et
        // chaque identifiant coute 38 octets. Il apprenait donc COMBIEN de
        // messages un correspondant n'avait pas pu lire, c'est-a-dire l'ampleur
        // de la panne et le volume echange pendant celle-ci.
        //
        // Le bourrage est un commentaire JSON impossible — on ne peut pas en
        // mettre — donc un champ ignore par le decodeur (`Body` ne declare que
        // `v` et `resend`, les cles inconnues sont jetees). Toute charge
        // enrichie fait desormais exactement la meme taille, quel que soit N.
        let target = padTargetBytes
        var out = payloadMarker + "\n" + json
        if out.utf8.count < target {
            // -13 pour `,"p":"…"}` et le `}` qu'on remplace.
            let room = target - out.utf8.count - 12
            if room > 0 {
                let pad = String(repeating: "0", count: room)
                out = String(out.dropLast()) + ",\"p\":\"" + pad + "\"}"
            }
        }
        return out
    }

    /// Taille visee pour toute charge enrichie. `maxResendPerRequest` (32)
    /// identifiants coutent au pire 32 x 39 + l'ossature ≈ 1300 octets; on vise
    /// au-dessus pour que le maximum tienne sans depasser, et donc pour que
    /// TOUTES les charges enrichies se ressemblent.
    static let padTargetBytes = 1500

    /// Lu par l'app, après déchiffrement. Tolère les espaces de bord: un
    /// marqueur qu'un ré-encodage aurait entouré d'un blanc doit rester un
    /// marqueur, sinon il s'affiche en clair dans une bulle.
    ///
    /// Rend `nil` UNIQUEMENT quand ce n'est pas un accusé. Un corps illisible
    /// — une v3 qu'on ne sait pas lire — rend une liste vide, jamais `nil`:
    /// l'accusé garde toute sa valeur (l'en-tête X3DH a déjà été adopté par
    /// `decrypt1to1` avant qu'on regarde ce texte), et surtout il ne doit
    /// jamais finir en bulle.
    static func parse(_ text: String) -> Heal? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed == payloadMarker { return Heal(resend: []) }
        guard trimmed.hasPrefix(payloadMarker + "\n") else { return nil }
        let rest = String(trimmed.dropFirst(payloadMarker.count + 1))

        // __HEAL_PARSE_STRICT_2026_08_28__ Un corps illisible rend `nil`, PAS
        // une liste vide.
        //
        // La version precedente rendait `Heal(resend: [])`, et le `continue` de
        // la boucle de reception faisait alors DISPARAITRE le texte sans bulle
        // ni trace. Consequence: il suffisait d'ecrire un message dont la
        // premiere ligne est le marqueur pour que le message SUIVANT du meme
        // envoi soit detruit chez le destinataire — un effacement a distance,
        // declenchable par n'importe qui, dans un messager.
        //
        // Le compromis assume: une v3 future dont on ne saurait pas lire le
        // corps s'afficherait en bulle au lieu d'etre avalee. Afficher quelque
        // chose d'inattendu est un defaut d'apparence; detruire un message est
        // une perte de donnee. On choisit le premier.
        //
        // La porte reste ouverte pour cette v3: il suffit que son corps soit du
        // JSON portant un champ `v`. C'est la seule chose exigee ici.
        guard let data = rest.data(using: .utf8),
              let body = try? JSONDecoder().decode(Body.self, from: data) else {
            return nil
        }
        return Heal(resend: Array((body.resend ?? []).prefix(Policy.maxResendPerRequest)))
    }

    static func isHealPayload(_ text: String) -> Bool { parse(text) != nil }

    // MARK: - Capacites annoncees dans le bundle de prekeys

    /// __CLIENT_CAPS_2026_08_31__ Ce que cette build sait faire, annonce dans le
    /// bundle de prekeys.
    ///
    /// POURQUOI CE CHEMIN, ET PAS LA POIGNEE DE MAIN PAR ACCUSE
    /// La premiere version apprenait la capacite d'un pair UNIQUEMENT en
    /// recevant un accuse de sa part (`announcesListSupport` sur l'identifiant).
    /// Verifie: `markPeerUnderstandsResendList` n'avait qu'UN appelant, dans la
    /// branche de reception d'accuse. Consequence, et elle est fatale au
    /// rattrapage: pour redemander mes messages perdus, je dois savoir que tu es
    /// capable — mais je ne l'apprends que le jour ou TA session casse. Si c'est
    /// toujours mon cote qui casse, la liste ne part JAMAIS et mes identifiants
    /// expirent au bout de 7 jours sans avoir servi.
    ///
    /// Le bundle de prekeys est le SEUL endroit que les deux cotes consultent
    /// avant de s'ecrire. La capacite y voyage donc, et le probleme dispara it
    /// par construction: pas de poignee de main, pas d'ordre a respecter, pas de
    /// direction privilegiee.
    ///
    /// Un ENTIER de drapeaux, pas un numero de version: on veut tester « sait-il
    /// lire une liste », pas comparer 1.41 a 1.39. Une capacite retiree plus
    /// tard laisse son bit libre plutot que de decaler tout le reste.
    enum Caps {
        /// Sait lire une liste de renvoi dans un accuse de reparation.
        static let resendList: Int = 1 << 0

        /// __LEGACY_COMPAT_2026_09_23__ Lit les groupes v2 (docs/GROUP_E2E_V2_SPEC.md):
        /// contenu `type:"group"` + état `📢GROUP_UPDATE📢` en 1:1 v2. Un pair qui
        /// l'annonce ne reçoit PAS la copie legacy pendant la transition
        /// (`GroupLegacyCompat`). Même bit sur Android et Desktop.
        static let groupV2: Int = 1 << 1

        /// __V2_PADDING_2026_11_09__ Sait retirer le bourrage des clairs v2
        /// (`V2Padding`, V2MessageRouter.swift). Un pair qui l'annonce peut
        /// recevoir des messages relais bourres a taille fixe. Meme bit sur
        /// Android (`V2Padding.CAP_PADDING`) et Desktop. Litteral ici parce que ce
        /// fichier est compile dans la NSE, qui ne compile pas `V2Padding`.
        static let padding: Int = 1 << 2

        /// __MEDIA_PADDING_2026_11_10__ Sait lire un blob media v2 BOURRE
        /// (manifeste avec `paddedSize`, OSHICryptoV2.decryptFile*). Un fichier
        /// n'est bourre que si TOUS les appareils destinataires l'annoncent
        /// (`V2MediaPaddingPeers`). Meme bit sur Android et Desktop
        /// (`CAP_MEDIA_PADDING`, docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md §3).
        static let mediaPadding: Int = 1 << 3

        /// Ce que CETTE build annonce. Toute capacite ajoutee ici doit etre
        /// reellement implementee: un pair qui nous croit capable nous enverra
        /// ce que nous aurons annonce savoir lire.
        static let mine: Int = resendList | groupV2 | padding | mediaPadding

        static func has(_ caps: Int, _ flag: Int) -> Bool { (caps & flag) != 0 }

        /// Capacites lues dans le bundle de prekeys d'un pair.
        ///
        /// POURQUOI ICI ET PAS DANS `V2SyncState`: `V2MessageRouter` est compile
        /// dans TROIS cibles (OSHI, OSHIShare, la NSE), alors que `V2SyncState`
        /// vit dans `MessageManager+V2.swift`, qui n'appartient qu'a l'app. Y
        /// mettre ce magasin faisait echouer la compilation des extensions.
        /// Ce fichier-ci, lui, est deja compile partout.
        ///
        /// Persiste, parce qu'un bundle n'est relu qu'a un nouvel etablissement
        /// de session — parfois des semaines plus tard.
        private static let storeKey = "oshi_v2_peer_caps"
        private static let storeCap = 500

        static func remember(peer: String, caps: Int) {
            var m = (UserDefaults.standard.dictionary(forKey: storeKey) as? [String: Int]) ?? [:]
            guard m[peer] != caps else { return }
            m[peer] = caps
            if m.count > storeCap { m = Dictionary(uniqueKeysWithValues: Array(m).suffix(storeCap)) }
            UserDefaults.standard.set(m, forKey: storeKey)
        }

        static func of(peer: String) -> Int {
            ((UserDefaults.standard.dictionary(forKey: storeKey) as? [String: Int]) ?? [:])[peer] ?? 0
        }
    }

    // MARK: - Enveloppe de RENVOI
    //
    // __RESEND_ENVELOPE_2026_08_28__ Un message renvoyé voyage dans SA PROPRE
    // enveloppe, avec un identifiant de relais NEUF, et porte son identifiant
    // applicatif d'origine À L'INTÉRIEUR du chiffré.
    //
    // POURQUOI, ET CE QUE ÇA CORRIGE
    // La première forme réémettait sous le msgId D'ORIGINE. Or `msgId` est un
    // champ EN CLAIR de l'enveloppe relais, conservé 30 jours: le relais voyait
    // donc deux enveloppes A→B portant le même identifiant à des semaines
    // d'intervalle, et pouvait les apparier — donc dater la panne, la relier au
    // pic d'accusés, et compter les messages concernés. Le contenu restait
    // scellé; la structure de la conversation, non.
    //
    // Un identifiant neuf casse l'appariement. Mais le destinataire a besoin de
    // l'identifiant d'ORIGINE, sinon un même message redemandé deux fois
    // formerait deux bulles: `receiveMessage` déduplique sur cet id. D'où le
    // transport à l'intérieur du chiffré, que le relais ne lit pas.
    //
    // POURQUOI CETTE FORME EST SÛRE ICI, alors qu'un accusé enrichi ne l'est pas
    // Un renvoi n'est déclenché QUE par la réception d'une liste, et une liste
    // ne parvient QUE d'un pair ayant annoncé savoir la lire. Le destinataire
    // d'un renvoi est donc prouvé capable — ce qui n'est jamais garanti pour un
    // accusé, dont le premier part forcément vers un pair inconnu.
    enum Resent {
        static let marker = "__RATCHET_RESENT__v1"

        private struct Body: Codable {
            let id: String
            let b: String
        }

        static func encode(originalId: String, body: String) -> String? {
            guard let data = try? JSONEncoder().encode(Body(id: originalId, b: body)),
                  let json = String(data: data, encoding: .utf8) else { return nil }
            return marker + "\n" + json
        }

        /// Rend `nil` dès que ce n'est pas un renvoi lisible — jamais une valeur
        /// de repli. Même raison qu'au-dessus: une forme douteuse doit
        /// s'AFFICHER, pas disparaître. Un corps illisible avalé en silence
        /// serait un effacement à distance.
        static func parse(_ text: String) -> (id: String, body: String)? {
            guard text.hasPrefix(marker + "\n") else { return nil }
            let rest = String(text.dropFirst(marker.count + 1))
            guard let data = rest.data(using: .utf8),
                  let b = try? JSONDecoder().decode(Body.self, from: data),
                  !b.id.isEmpty else { return nil }
            return (b.id, b.b)
        }
    }

    /// Décide si l'on a le droit d'émettre un accusé vers ce pair MAINTENANT.
    ///
    /// POURQUOI UNE LIMITE, ET PAS UN SIMPLE ENVOI
    /// Deux appareils dont les sessions cassent en même temps s'enverraient des
    /// accusés en boucle: chacun adopte la session neuve de l'autre, écrase
    /// celle qu'il venait de créer, et casse à nouveau. Le délai brise la
    /// boucle en laissant à l'un des deux le temps d'aboutir.
    ///
    /// La structure est PURE et testable: `V2SyncState` garde les dates, mais
    /// la décision — la seule chose qui puisse boucler — se vérifie sans
    /// horloge ni réseau.
    struct Policy {
        /// Mesuré sur le trajet réel: un aller-retour relais + pull tient
        /// largement sous la minute. En dessous, on ré-émettrait avant d'avoir
        /// pu constater que ça a marché.
        static let cooldown: TimeInterval = 60

        /// - Parameter lastSentAt: dernier envoi vers CE pair, `nil` si jamais.
        static func maySend(lastSentAt: Date?, now: Date) -> Bool {
            guard let lastSentAt else { return true }
            return now.timeIntervalSince(lastSentAt) >= cooldown
        }

        // MARK: Départage — qui émet TOUT DE SUITE
        //
        // Le délai brise une boucle DÉCALÉE, pas une boucle SYMÉTRIQUE. A et B
        // cassent ensemble, chacun efface et envoie son accusé: A reçoit celui
        // de B, sa session ne l'ouvre pas, l'en-tête est là, donc
        // `establishResponderSession` REMPLACE la session de A par celle de B;
        // symétriquement chez B. Les deux écrivent alors sur une session que
        // l'autre n'a plus. Le délai ne fait que répéter ça toutes les 60 s.
        //
        // Tant que la réaction demandait 8 tics, la simultanéité était rare;
        // dès qu'elle est immédiate, les deux côtés répondent au MÊME lot
        // cassé dans la MÊME seconde. Il faut donc DÉSIGNER un côté, et le
        // faire sans échanger un octet: une comparaison des deux identités.
        //
        // Le côté non désigné n'est pas passif: il ADOPTE l'en-tête X3DH
        // entrant dès qu'il en reçoit un (RATCHET-H1, inchangé). Il ne fait
        // qu'attendre un délai complet avant d'émettre le sien à son tour, au
        // cas où l'initiateur ne serait jamais venu.

        /// Vrai pour EXACTEMENT un des deux côtés de toute paire, sans
        /// horloge, sans réseau et sans négociation.
        static func isHealInitiator(me: String, peer: String) -> Bool { me < peer }

        // MARK: Renvoi des messages perdus
        //
        // La seule chose qui puisse boucler doit se vérifier sans horloge ni
        // réseau — même argument que `maySend`. Le cycle à casser est:
        // renvoi → échec → abandon → `deleteSession` → accusé → renvoi des
        // MÊMES ids. Le délai de 60 s ne le coupe PAS, il ne fait que
        // l'espacer; seul un compteur par msgId le termine.

        /// 32 UUID ≈ 1,2 Ko de JSON, très loin du plafond de 256 Ko de
        /// chiffré du relais. La borne est là pour le carnet local et pour la
        /// quantité de travail qu'un pair peut nous demander d'un coup.
        static let maxResendPerRequest = 32

        /// Au-delà, redemander n'a plus de sens: le message a fini par être
        /// lu ailleurs, ou il n'intéresse plus personne.
        static let maxResendAge: TimeInterval = 7 * 24 * 3600

        /// LE frein qui termine le cycle. 2 et non 1: le premier renvoi peut
        /// légitimement se perdre avec la session qu'on vient de refaire.
        static let maxResendAttemptsPerMsgId = 2

        /// Plafond de débit par pair, indépendant du précédent: il borne ce
        /// qu'un pair bavard peut nous faire produire même avec des ids tous
        /// différents.
        static let maxResendsPerPeerPerHour = 64

        /// - Parameter attemptsSoFar: renvois DÉJÀ faits pour ce msgId.
        /// - Parameter messageAge: âge du message stocké localement.
        /// - Parameter peerBudgetLeft: renvois restants vers ce pair sur l'heure.
        static func mayResend(attemptsSoFar: Int,
                              messageAge: TimeInterval,
                              peerBudgetLeft: Int) -> Bool {
            attemptsSoFar < maxResendAttemptsPerMsgId
                && messageAge <= maxResendAge
                && peerBudgetLeft > 0
        }
    }
}

// MARK: - __ONE_BANNER_PER_MESSAGE_2026_09_23__

/// UNE bannière par message, qui ne montre JAMAIS le texte.
///
/// SIGNALÉ: « pour un message de groupe j'ai DEUX notifications: la bannière
/// APNs au nom du groupe, et une seconde avec le nom du groupe ET le texte en
/// clair. » La seconde était la notification LOCALE que l'app pose après s'être
/// réveillée (content-available), avoir déchiffré, puis appelé
/// `UNUserNotificationCenter.add`. Le balayage qui devait retirer la bannière
/// APNs comparait `messageId` — or celui du push n'est pas celui du message
/// (repli serveur = `ipfsHash`/`fallback_…`, bot = id serveur), et une bannière
/// portant un AUTRE messageId n'était jamais rattrapée par le repli `groupId`.
/// Et quand la notif locale partait AVANT que l'extension ait rendu la bannière
/// distante, rien ne pouvait plus la retirer.
///
/// RÈGLE (demande explicite du propriétaire):
///  • une seule bannière par message;
///  • titre = nom du groupe (sous-titre = alias de l'expéditeur) ou nom/alias du
///    contact; corps = « Nouveau message chiffré » localisé, jamais le texte;
///  • la bannière distante (APNs) est LA bannière. L'app ne pose une locale que
///    si aucune distante ne couvre déjà cette conversation (maillage, sondage
///    sans push), et l'extension tait la distante si une locale vient d'être
///    posée pour la même conversation.
///
/// Vit dans ce fichier parce qu'il est DÉJÀ compilé dans l'app, l'extension de
/// notification et les tests — ajouter un fichier imposerait de toucher le
/// pbxproj. Foundation seulement.
enum MessageBannerPolicy {

    /// Marqueur posé dans `userInfo` de toute bannière de message LOCALE.
    static let localMarkerKey = "oshiLocalBanner"

    /// Clé de localisation du corps générique — la même que le `loc-key` que le
    /// serveur met dans l'alerte APNs, donc les deux chemins affichent la même
    /// phrase dans la langue du téléphone.
    static let genericBodyKey = "notification.push.body.encrypted"

    /// Côté app: une bannière distante de la même conversation livrée dans cette
    /// fenêtre couvre ce message (le réveil + la récupération réseau arrivent
    /// après la bannière, rarement plus de quelques secondes).
    static let remoteCoversLocalWindow: TimeInterval = 30

    /// Côté extension: une locale livrée dans cette fenêtre pour la même
    /// conversation rend la distante redondante. Plus court: au-delà, c'est très
    /// probablement un message SUIVANT qui mérite son propre son.
    static let localCoversRemoteWindow: TimeInterval = 15

    /// Types de push qui portent un message (et donc la règle « pas de texte »).
    static let messageTypes: Set<String> = ["message", "group_message", "group", "bot", "bot_message"]

    /// Réglage caché historique (aucune UI ne l'écrit). Respecté s'il a été posé
    /// EXPLICITEMENT à true; le défaut est désormais « pas de texte ».
    static let previewDefaultsKey = "showMessagePreviews"

    /// __NOTIFICATION_NAMES_HIDDEN_2026_11_20__ Settings › Notifications ›
    /// "Show Preview". The toggle has existed for months and NOTHING read it:
    /// switched off, the lock screen still named the contact and the group on
    /// every banner — on a seized or borrowed phone, that IS the social graph.
    /// Off now means: no contact name, no group name, generic title. Message
    /// text is never shown either way (see `showsMessageText`).
    ///
    /// The extension runs in another process, so the value is mirrored into the
    /// App Group; the app's own `UserDefaults.standard` copy stays the source
    /// the toggle writes. Default ON: unchanged for everyone who never touched it.
    static let showNamesKey = "notificationPreview"
    static let appGroupSuite = "group.Moriceau-lab.Genesis"

    static func showsNames(_ defaults: UserDefaults? = UserDefaults(suiteName: appGroupSuite)) -> Bool {
        (defaults?.object(forKey: showNamesKey) as? Bool) ?? true
    }

    /// Copies the app's setting into the App Group so the extension sees it.
    static func mirrorShowsNames(from app: UserDefaults = .standard,
                                 to shared: UserDefaults? = UserDefaults(suiteName: appGroupSuite)) {
        let value = (app.object(forKey: showNamesKey) as? Bool) ?? true
        shared?.set(value, forKey: showNamesKey)
    }

    static func showsMessageText(_ defaults: UserDefaults = .standard) -> Bool {
        (defaults.object(forKey: previewDefaultsKey) as? Bool) ?? false
    }

    /// Canonique base64 (base64url replié, padding et espaces retirés).
    static func normalizedKey(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    /// Clé de conversation d'une bannière: `g:<GROUPID>` pour un groupe (UUID en
    /// majuscules — Android et le bot l'écrivent en minuscules), `c:<clé>` pour
    /// un 1:1. nil si la bannière ne désigne aucune conversation.
    static func conversationKey(_ userInfo: [AnyHashable: Any]) -> String? {
        if let gid = (userInfo["groupId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !gid.isEmpty {
            return "g:" + gid.uppercased()
        }
        for k in ["senderPublicKey", "senderKey", "senderAddress"] {
            if let pk = userInfo[k] as? String, !pk.isEmpty {
                return "c:" + normalizedKey(pk)
            }
        }
        return nil
    }

    static func isLocal(_ userInfo: [AnyHashable: Any]) -> Bool {
        if let b = userInfo[localMarkerKey] as? Bool { return b }
        if let s = userInfo[localMarkerKey] as? String { return s == "1" || s == "true" }
        return false
    }

    /// Une bannière déjà dans le centre de notifications, réduite à ce que la
    /// règle regarde (testable sans UserNotifications).
    struct Delivered {
        let identifier: String
        let userInfo: [AnyHashable: Any]
        let date: Date
    }

    /// App: une bannière DISTANTE couvre-t-elle déjà ce message ?
    /// Même `messageId` → oui, quel que soit l'âge. Sinon même conversation
    /// livrée il y a moins de `remoteCoversLocalWindow`.
    static func remoteBannerCovers(messageId: String,
                                   conversationKey: String?,
                                   delivered: [Delivered],
                                   now: Date = Date()) -> Bool {
        delivered.contains { d in
            guard !isLocal(d.userInfo) else { return false }
            if !messageId.isEmpty,
               let mid = d.userInfo["messageId"] as? String,
               mid.caseInsensitiveCompare(messageId) == .orderedSame {
                return true
            }
            guard let conversationKey, self.conversationKey(d.userInfo) == conversationKey else { return false }
            return now.timeIntervalSince(d.date) <= remoteCoversLocalWindow
        }
    }

    /// Extension: une bannière LOCALE couvre-t-elle déjà ce push ?
    static func localBannerCovers(messageId: String,
                                  conversationKey: String?,
                                  delivered: [Delivered],
                                  now: Date = Date()) -> Bool {
        delivered.contains { d in
            guard isLocal(d.userInfo) else { return false }
            if !messageId.isEmpty, d.identifier.caseInsensitiveCompare(messageId) == .orderedSame {
                return true
            }
            if !messageId.isEmpty,
               let mid = d.userInfo["messageId"] as? String,
               mid.caseInsensitiveCompare(messageId) == .orderedSame {
                return true
            }
            guard let conversationKey, self.conversationKey(d.userInfo) == conversationKey else { return false }
            return now.timeIntervalSince(d.date) <= localCoversRemoteWindow
        }
    }

    /// __GROUP_MUTE_BLOCK_IN_NSE_2026_09_23__ Faut-il taire une bannière de
    /// groupe ? Oui si le groupe est MUET (`"muted": "1"` dans
    /// `groups_snapshot.json`) ou BLOQUÉ (`blocked_groups_snapshot.json`).
    ///
    /// WHY: muter un groupe ne faisait taire que la notification LOCALE
    /// (`GroupMessaging`, `!group.isMuted`). La bannière APNs, elle, est rendue
    /// par l'extension — processus séparé qui ne lisait que `{groupId, name}` —
    /// donc app en arrière-plan ou tuée, un groupe muet sonnait quand même. Même
    /// trou pour un groupe bloqué. Les messages continuent d'arriver: seule la
    /// présentation est tue. UUID comparés sans casse (Android/bot en minuscules).
    static func groupIsSilenced(groupId: String,
                                groupsSnapshot: [[String: Any]],
                                blockedGroupIds: [String]) -> Bool {
        let wanted = groupId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !wanted.isEmpty else { return false }
        if blockedGroupIds.contains(where: { $0.caseInsensitiveCompare(wanted) == .orderedSame }) {
            return true
        }
        return groupsSnapshot.contains { g in
            guard let id = g["groupId"] as? String,
                  id.caseInsensitiveCompare(wanted) == .orderedSame else { return false }
            return (g["muted"] as? String) == "1"
        }
    }

    // MARK: __BLOCKED_NOTIF_2026_09_23__

    /// Posé dans `userInfo` d'une notification que l'extension a dû DÉGRADER au lieu
    /// de la supprimer (voir `NotificationService.suppressDisplay`). L'app et
    /// l'extension retirent toute notification qui le porte.
    static let suppressedMarkerKey = "oshiSuppressed"

    /// Clés `userInfo` qui peuvent désigner l'émetteur d'une notification.
    static let senderUserInfoKeys = ["senderPublicKey", "senderKey", "senderAddress",
                                     "callerPublicKey", "callerKey"]

    /// L'émetteur est-il dans la liste de blocage ? Les deux côtés canonisés
    /// (base64url, padding, espaces) — une clé écrite autrement sur le fil que dans
    /// la liste doit quand même correspondre.
    static func senderIsBlocked(_ sender: String, blockedKeys: [String]) -> Bool {
        let wire = normalizedKey(sender)
        guard !wire.isEmpty else { return false }
        return blockedKeys.contains { normalizedKey($0) == wire }
    }

    /// Premier émetteur trouvé dans `userInfo` (nil si aucun).
    static func sender(of userInfo: [AnyHashable: Any]) -> String? {
        for k in senderUserInfoKeys {
            if let pk = userInfo[k] as? String, !pk.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return pk
            }
        }
        return nil
    }

    // MARK: __PHANTOM_BANNER_2026_10_11__

    /// Identifiants des bannières livrées qui annoncent un de ces `messageIds`.
    ///
    /// Les DEUX orthographes comptent, et c'est tout l'intérêt de la fonction:
    /// une bannière LOCALE porte le messageId comme identifiant, alors qu'une
    /// bannière APNs a un identifiant attribué par Apple et ne porte le
    /// messageId que dans son `userInfo`. `clearNotification(for:)` ne comparait
    /// que l'identifiant, donc il ne retirait jamais une bannière distante — or
    /// c'est précisément la distante qui sonne pour un message que l'app ne
    /// pourra pas afficher.
    static func deliveredMatching(messageIds: Set<String>, delivered: [Delivered]) -> [String] {
        guard !messageIds.isEmpty else { return [] }
        let wanted = Set(messageIds.map { $0.lowercased() })
        return delivered.compactMap { d in
            if wanted.contains(d.identifier.lowercased()) { return d.identifier }
            if let mid = d.userInfo["messageId"] as? String,
               wanted.contains(mid.lowercased()) { return d.identifier }
            return nil
        }
    }

    /// Notifications déjà livrées à RETIRER: toute notification dégradée
    /// (`suppressedMarkerKey`) et toute notification dont l'émetteur est bloqué —
    /// message, appel manqué, réaction, quel que soit le chemin qui l'a posée.
    static func deliveredToWithdraw(_ delivered: [Delivered], blockedKeys: [String]) -> [String] {
        delivered.compactMap { d in
            if let m = d.userInfo[suppressedMarkerKey] as? String, m == "1" { return d.identifier }
            for k in senderUserInfoKeys {
                if let pk = d.userInfo[k] as? String, senderIsBlocked(pk, blockedKeys: blockedKeys) {
                    return d.identifier
                }
            }
            return nil
        }
    }
}

// MARK: - __BOT_E2E_2026_09_23__ bot-seal-v1

/// Messages de bot scellés: le serveur ne garde plus que du chiffré.
///
/// Spec normative: `ServerPatches/bot_e2e/BOT_SEAL_SPEC.md` (§2 ouverture),
/// référence `ServerPatches/bot_e2e/patched/bot_seal.js`, vecteurs
/// `ServerPatches/bot_e2e/test/vectors.json` (couverts par
/// `BotSealedEnvelopeTests`).
///
/// L'enveloppe `bot:<messageId>:<base64(outer)>` ne change pas de forme. Le
/// JSON externe ne porte que des ids, une date, l'id public du bot, un texte de
/// remplacement pour les anciens builds et `sealed` — scellé à la clé X25519
/// d'identité de chaque membre (celle dont la moitié publique est `publicKey`).
/// Ce build ne montre JAMAIS le texte de remplacement: soit il ouvre, soit il
/// jette (et acquitte).
///
/// Vit dans ce fichier parce qu'il est déjà compilé dans l'app, la NSE et les
/// tests (pas de nouveau fichier ⇒ pas de pbxproj).
enum BotSealedEnvelope {

    static let version = "bot-seal-v1"
    /// En-tête annoncé sur `GET /api/pending/<key>` (spec §3).
    static let capsHeaderName = "X-OSHI-Caps"
    static let capsHeaderValue = version
    static let wrapLength = 32 + 12 + 32 + 16

    /// Ce que l'UI a le droit d'affirmer (spec §3), du plus au moins fort.
    enum SealKind: String {
        /// Scellé par le bot lui-même: le serveur n'a jamais vu le clair.
        case botE2E
        /// Scellé par le serveur OSHI à l'arrivée: l'opérateur du bot l'a écrit, il le lit.
        case serverSealed
        /// Ancien format en clair (historique / vieux serveur).
        case legacyPlain
    }

    /// Raisons d'échec — JAMAIS de contenu dedans (elles finissent dans les logs).
    enum OpenError: Error, Equatable {
        case notSealed
        case unsupportedVersion
        case noWrapForThisKey
        case badWrap
        case wrapAuthFailed
        case badBody
        case bodyAuthFailed
        case badInnerJSON
        case messageIdMismatch
        case groupIdMismatch
        case typeMismatch
    }

    /// Détection (spec §1): `v == "bot-seal-v1"` ET `sealed` est un objet.
    static func isSealed(_ json: [String: Any]) -> Bool {
        (json["v"] as? String) == version && json["sealed"] is [String: Any]
    }

    /// `kid = hex(SHA-256(pub))[0..16]`, minuscules.
    static func kid(forPublicKey raw: Data) -> String {
        SHA256.hash(data: raw).map { String(format: "%02x", $0) }.joined().prefix(16).description
    }

    /// Ouvre une enveloppe externe scellée et rend le JSON interne (format bot
    /// historique + `sealedBy`), prêt pour le traitement habituel.
    ///
    /// - Parameter messageId: l'id de la chaîne de queue (`bot:<id>:…`). L'AAD
    ///   utilise le `messageId` EXTERNE tel que reçu (§2.6); s'il est absent,
    ///   celui-ci; s'ils diffèrent (casse ignorée) → rejet.
    static func open(outer json: [String: Any],
                     messageId: String,
                     privateKey: Curve25519.KeyAgreement.PrivateKey) throws -> [String: Any] {
        guard let sealed = json["sealed"] as? [String: Any], (json["v"] as? String) == version else {
            throw OpenError.notSealed
        }
        if let sv = sealed["v"] as? String, sv != version { throw OpenError.unsupportedVersion }

        let outerId: String
        if let oid = json["messageId"] as? String, !oid.isEmpty {
            if !messageId.isEmpty, oid.caseInsensitiveCompare(messageId) != .orderedSame {
                throw OpenError.messageIdMismatch
            }
            outerId = oid
        } else {
            outerId = messageId
        }
        guard !outerId.isEmpty else { throw OpenError.messageIdMismatch }

        // 1-2. kid → wrap
        let pub = privateKey.publicKey.rawRepresentation
        let myKid = kid(forPublicKey: pub)
        guard let wraps = sealed["wraps"] as? [String: Any],
              let wb64 = wraps[myKid] as? String else { throw OpenError.noWrapForThisKey }
        guard let w = Data(base64Encoded: wb64), w.count == wrapLength else { throw OpenError.badWrap }
        let wb = [UInt8](w)
        let epkRaw = Data(wb[0..<32])

        // 3. k = HKDF-SHA256(X25519(priv, epk), salt = epk || pub, info = "bot-seal-v1")
        let k: SymmetricKey
        do {
            let epk = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: epkRaw)
            let shared = try privateKey.sharedSecretFromKeyAgreement(with: epk)
            k = shared.hkdfDerivedSymmetricKey(using: SHA256.self,
                                               salt: epkRaw + pub,
                                               sharedInfo: Data(version.utf8),
                                               outputByteCount: 32)
        } catch {
            throw OpenError.badWrap
        }

        // 4. K = AES-GCM-open(k, iv, ct, tag, aad = "bot-seal-v1|wrap|<messageId>|<kid>")
        let contentKeyBytes: Data
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: Data(wb[32..<44])),
                                            ciphertext: Data(wb[44..<76]),
                                            tag: Data(wb[76..<92]))
            contentKeyBytes = try AES.GCM.open(box, using: k,
                                               authenticating: Data("\(version)|wrap|\(outerId)|\(myKid)".utf8))
        } catch {
            throw OpenError.wrapAuthFailed
        }
        guard contentKeyBytes.count == 32 else { throw OpenError.wrapAuthFailed }

        // 5. corps: iv(12) || ct || tag(16)
        guard let bodyB64 = sealed["body"] as? String,
              let bodyData = Data(base64Encoded: bodyB64), bodyData.count >= 28 else {
            throw OpenError.badBody
        }
        let b = [UInt8](bodyData)
        let plaintext: Data
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: Data(b[0..<12])),
                                            ciphertext: Data(b[12..<(b.count - 16)]),
                                            tag: Data(b[(b.count - 16)...]))
            plaintext = try AES.GCM.open(box, using: SymmetricKey(data: contentKeyBytes),
                                         authenticating: Data("\(version)|body|\(outerId)".utf8))
        } catch {
            throw OpenError.bodyAuthFailed
        }
        guard var inner = (try? JSONSerialization.jsonObject(with: plaintext)) as? [String: Any] else {
            throw OpenError.badInnerJSON
        }

        // 7. liaisons interne ↔ externe
        guard let innerId = inner["messageId"] as? String,
              innerId.caseInsensitiveCompare(outerId) == .orderedSame else {
            throw OpenError.messageIdMismatch
        }
        let outerType = (json["type"] as? String) ?? "bot_message"
        if let innerType = inner["type"] as? String {
            guard innerType == outerType else { throw OpenError.typeMismatch }
        } else {
            // Un `bot_callback_answer` sans `type` interne serait lu comme un message.
            inner["type"] = outerType
        }
        if outerType != "bot_callback_answer" {
            guard let og = json["groupId"] as? String,
                  let ig = inner["groupId"] as? String,
                  og.caseInsensitiveCompare(ig) == .orderedSame else {
                throw OpenError.groupIdMismatch
            }
        }
        return inner
    }

    /// Étiquette honnête (spec §3). `inner` = le JSON ouvert, nil pour une
    /// enveloppe historique. « bot » exige les DEUX marques (interne authentifiée
    /// + externe) — on ne sur-affirme jamais.
    static func label(inner: [String: Any]?, outer: [String: Any]) -> SealKind {
        guard isSealed(outer) else { return .legacyPlain }
        let e2e = (outer["e2e"] as? Bool) ?? ((outer["e2e"] as? NSNumber)?.boolValue ?? false)
        if e2e, (inner?["sealedBy"] as? String) == "bot" { return .botE2E }
        return .serverSealed
    }

    // MARK: Dernier genre vu par groupe (pour l'étiquette de l'UI)

    static let kindByGroupDefaultsKey = "oshi.botSealKindByGroup.v1"

    static func recordKind(_ kind: SealKind, groupId: UUID, defaults: UserDefaults = .standard) {
        var map = (defaults.dictionary(forKey: kindByGroupDefaultsKey) as? [String: String]) ?? [:]
        let key = groupId.uuidString.uppercased()
        guard map[key] != kind.rawValue else { return }
        map[key] = kind.rawValue
        defaults.set(map, forKey: kindByGroupDefaultsKey)
    }

    /// nil = aucun message de bot reçu par le serveur depuis ce build (bot
    /// local, ou historique): l'UI n'affirme rien.
    static func lastKind(groupId: UUID, defaults: UserDefaults = .standard) -> SealKind? {
        guard let map = defaults.dictionary(forKey: kindByGroupDefaultsKey) as? [String: String],
              let raw = map[groupId.uuidString.uppercased()] else { return nil }
        return SealKind(rawValue: raw)
    }

    /// Clés `Localizable.strings`: (court, sous la bulle) et (phrase complète de la spec).
    static func captionKeys(_ kind: SealKind) -> (short: String, full: String) {
        switch kind {
        case .serverSealed: return ("bot.seal.server.short", "bot.seal.server.full")
        case .botE2E:       return ("bot.seal.bot.short", "bot.seal.bot.full")
        case .legacyPlain:  return ("bot.seal.legacy.short", "bot.seal.legacy.full")
        }
    }
}
