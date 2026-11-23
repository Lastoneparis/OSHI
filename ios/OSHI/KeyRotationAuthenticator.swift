//
//  KeyRotationAuthenticator.swift
//  OSHI — l'autorisation d'une rotation de clé, et rien d'autre.
//
//  CE QUE CE FICHIER REMPLACE. La rotation était cosmétique et forgeable:
//  `signRotation` calculait un `SHA256("old:new")` SANS CLÉ, donc quiconque
//  pouvait envoyer un paquet pouvait aussi en calculer la « signature ». Et
//  `rotateKeys` jetait la clé fraîchement engendrée. Une rotation acceptée sur
//  cette base laissait un attaquant actif substituer sa propre clé — la cible la
//  plus rentable qui soit dans un messager. Les deux plateformes REFUSENT donc
//  aujourd'hui toute rotation entrante, ce qui est sûr et qui rend la
//  fonctionnalité inexistante.
//
//  ── POURQUOI PAS UNE SIGNATURE Ed25519, QUE LA FEUILLE DE ROUTE SUGGÉRAIT ──
//
//  Vérifier une signature Ed25519 demande de connaître la clé de SIGNATURE du
//  pair. Or l'identité d'un contact dans OSHI est sa clé X25519 de CHIFFREMENT;
//  la clé Ed25519 est une paire indépendante, publiée dans le bundle de prékeys
//  et servie par le serveur. La faire venir du serveur au moment de vérifier
//  reviendrait à demander à l'attaquant la clé qui sert à le démasquer — c'est
//  précisément le défaut de liaison que la phase 5b doit fermer.
//
//  ── CE QUE FAIT CE FICHIER À LA PLACE ──────────────────────────────────────
//
//  La seule autorisation qui compte pour remplacer une clé, c'est de PROUVER
//  QU'ON DÉTIENT L'ANCIENNE. On l'établit par un secret ECDH entre l'ancienne
//  identité et celle du destinataire — deux clés que les deux côtés ont déjà, et
//  qu'aucun serveur n'a besoin de fournir:
//
//      ss  = X25519(ancienne privée de l'émetteur, publique du destinataire)
//          = X25519(privée du destinataire, ancienne publique de l'émetteur)
//      mk  = HKDF-SHA256(ss, sel vide, info "OSHI-KeyRotation-v1", 32 octets)
//      msg = ancienne ‖ 0x00 ‖ nouvelle ‖ 0x00 ‖ horodatage(8, BE) ‖ n°(4, BE)
//      tag = HMAC-SHA256(mk, msg)
//
//  Un attaquant qui ne détient pas l'ancienne clé privée ne peut pas produire
//  `tag`. C'est un MAC, donc non transférable: il convainc le destinataire et
//  personne d'autre. Pour un messager c'est une propriété souhaitable, pas une
//  limite — une signature transférable prouverait à un tiers qui a tourné sa clé
//  et quand.
//
//  ── CE QUI EST LIÉ, ET POURQUOI ────────────────────────────────────────────
//
//  Les deux clés sont dans le message, séparées par un octet nul: sans lui,
//  `("ab", "c")` et `("a", "bc")` donneraient le même tag, et un attaquant
//  pourrait déplacer la frontière. L'horodatage et le numéro de rotation y sont
//  aussi, sinon un paquet capté resterait valable pour toujours et pourrait être
//  rejoué pour revenir à une clé précédente.
//

import Foundation
import CryptoKit

enum KeyRotationAuthenticator {

    /// Chaîne de séparation de domaine. Doit être identique sur Android au
    /// caractère près — c'est ce qui rend les deux tags comparables.
    static let info = "OSHI-KeyRotation-v1"

    /// Au-delà, un paquet de rotation est considéré comme rejoué.
    ///
    /// Assez large pour un téléphone resté hors ligne un moment, assez étroit
    /// pour qu'un paquet capté ne serve pas indéfiniment. Le numéro de rotation,
    /// strictement croissant, est la seconde barrière: le temps seul ne suffit
    /// pas, parce que deux appareils ne sont jamais tout à fait à la même heure.
    static let maxAge: TimeInterval = 7 * 24 * 3600

    enum Failure: Error, Equatable {
        case malformedKey
        case noLocalIdentity
        case badTag
        case tooOld
        case replayed
    }

    /// Le message authentifié. Public pour que les tests et Android puissent
    /// vérifier octet pour octet ce qui est lié.
    static func canonicalMessage(oldPublicKey: String,
                                 newPublicKey: String,
                                 timestampMillis: UInt64,
                                 rotationNumber: UInt32) -> Data {
        var d = Data()
        d.append(contentsOf: Array(oldPublicKey.utf8))
        d.append(0x00)
        d.append(contentsOf: Array(newPublicKey.utf8))
        d.append(0x00)
        var ts = timestampMillis.bigEndian
        withUnsafeBytes(of: &ts) { d.append(contentsOf: $0) }
        var n = rotationNumber.bigEndian
        withUnsafeBytes(of: &n) { d.append(contentsOf: $0) }
        return d
    }

    /// La clé de MAC, dérivée du secret ECDH.
    static func macKey(sharedSecret: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: sharedSecret),
                               salt: Data(), info: Data(info.utf8),
                               outputByteCount: 32)
    }

    /// Le tag, à partir d'un secret ECDH déjà calculé.
    ///
    /// Séparé du calcul ECDH pour être testable sur vecteurs: c'est cette
    /// fonction-ci que les deux plateformes doivent faire coïncider.
    static func tag(sharedSecret: Data,
                    oldPublicKey: String,
                    newPublicKey: String,
                    timestampMillis: UInt64,
                    rotationNumber: UInt32) -> Data {
        let msg = canonicalMessage(oldPublicKey: oldPublicKey,
                                   newPublicKey: newPublicKey,
                                   timestampMillis: timestampMillis,
                                   rotationNumber: rotationNumber)
        return Data(HMAC<SHA256>.authenticationCode(for: msg, using: macKey(sharedSecret: sharedSecret)))
    }

    /// Vérifie un tag reçu.
    ///
    /// La comparaison passe par `HMAC.isValidAuthenticationCode`, en temps
    /// constant. Une comparaison naïve `==` sur `Data` sort au premier octet qui
    /// diffère et laisse mesurer combien d'octets sont justes.
    static func isValidTag(_ received: Data,
                           sharedSecret: Data,
                           oldPublicKey: String,
                           newPublicKey: String,
                           timestampMillis: UInt64,
                           rotationNumber: UInt32) -> Bool {
        let msg = canonicalMessage(oldPublicKey: oldPublicKey,
                                   newPublicKey: newPublicKey,
                                   timestampMillis: timestampMillis,
                                   rotationNumber: rotationNumber)
        return HMAC<SHA256>.isValidAuthenticationCode(received,
                                                      authenticating: msg,
                                                      using: macKey(sharedSecret: sharedSecret))
    }

    /// Le secret ECDH entre une clé privée locale et une publique de pair.
    static func sharedSecret(localPrivate: Curve25519.KeyAgreement.PrivateKey,
                             peerPublicBase64: String) throws -> Data {
        guard let raw = Data(base64Encoded: peerPublicBase64),
              let peer = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: raw) else {
            throw Failure.malformedKey
        }
        guard let ss = try? localPrivate.sharedSecretFromKeyAgreement(with: peer) else {
            throw Failure.malformedKey
        }
        return ss.withUnsafeBytes { Data($0) }
    }

    /// Les contrôles qui ne portent pas sur la cryptographie mais sur le temps
    /// et l'ordre. Séparés pour être testés seuls.
    ///
    /// - `lastRotationNumber` est le dernier numéro ACCEPTÉ pour ce contact.
    ///   Exiger strictement plus grand est ce qui empêche de rejouer une
    ///   rotation ancienne pour revenir à une clé qu'on a déjà quittée.
    static func checkFreshness(timestampMillis: UInt64,
                               rotationNumber: UInt32,
                               lastRotationNumber: UInt32,
                               now: Date = Date()) throws {
        let t = Double(timestampMillis) / 1000.0
        let age = now.timeIntervalSince1970 - t
        // Un paquet daté du futur est aussi suspect qu'un paquet trop vieux; on
        // tolère une dérive d'horloge modeste dans les deux sens.
        if age > maxAge || age < -maxAge { throw Failure.tooOld }
        if rotationNumber <= lastRotationNumber { throw Failure.replayed }
    }
}
