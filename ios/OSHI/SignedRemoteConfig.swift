//
//  SignedRemoteConfig.swift
//  OSHI — la configuration distante, signée, et impossible à faire reculer.
//
//  LA MENACE (M1, rétrogradation pilotée par le serveur). `GET /v2/config`
//  décide si ce téléphone parle v2 ou retombe sur l'ancien chemin. Autrement
//  dit, influencer cette réponse EST la rétrogradation: il suffit de répondre
//  `v2_enabled=false` ou `rollout_percent=0` pour renvoyer tout le monde sur le
//  protocole le plus faible, et aujourd'hui rien ne prouve que la réponse vient
//  bien de l'opérateur.
//
//  ── POURQUOI ON NE SIGNE PAS « LE JSON » MAIS DES OCTETS ───────────────────
//
//  Signer un objet JSON oblige à se mettre d'accord sur une forme canonique:
//  ordre des clés, espaces, encodage des nombres. C'est une source d'erreurs
//  sans fin, et chaque divergence entre les deux plateformes devient un refus de
//  configuration légitime. L'enveloppe transporte donc la configuration comme un
//  BLOC D'OCTETS en base64, et la signature couvre exactement ces octets — ceux
//  que l'on va parser, pas une re-sérialisation qui pourrait en différer.
//
//      enveloppe = { "payload": <base64 du JSON de config>,
//                    "config_version": N,
//                    "sig": <base64 Ed25519> }
//      signé     = payload_octets ‖ 0x00 ‖ config_version (8 octets, gros-boutiste)
//
//  ── LE RETOUR EN ARRIÈRE EST UNE ATTAQUE, PAS UNE MALADRESSE ───────────────
//
//  Une signature seule ne suffit pas: un attaquant qui rejoue une ANCIENNE
//  configuration authentique — celle d'avant l'activation de v2 — obtient la
//  même rétrogradation sans rien forger. D'où `config_version`, strictement
//  croissante et mémorisée: une configuration correctement signée mais plus
//  ancienne que la dernière vue est refusée.
//
//  ── POURQUOI L'EXIGENCE EST DÉSACTIVÉE AU DÉPART ──────────────────────────
//
//  Le serveur ne signe encore rien. Exiger une signature aujourd'hui ferait
//  ignorer la configuration vivante par tous les clients, qui retomberaient sur
//  leur valeur par défaut — c'est-à-dire exactement la rétrogradation que ce
//  fichier existe pour empêcher. La feuille de route le dit dans cet ordre:
//  livrer les clients qui VÉRIFIENT avant que le serveur n'émette quoi que ce
//  soit.
//
//  ⚠️ Mais une signature PRÉSENTE ET FAUSSE est refusée dès maintenant, dans
//  tous les cas. Ça ne coûte aucune compatibilité et ça ferme la porte à qui
//  tenterait de fabriquer une enveloppe.
//

import Foundation
import CryptoKit

enum SignedRemoteConfig {

    /// La clé publique Ed25519 de l'opérateur, épinglée dans le binaire.
    ///
    /// Épinglée et non téléchargée: une clé que l'on irait chercher sur le même
    /// serveur ne prouverait rien de plus que la réponse elle-même.
    ///
    /// ⚠️ LA MOITIÉ PRIVÉE NE VIT PAS DANS LE DÉPÔT: `~/.oshi/`, en 0600. La
    /// committer donnerait à quiconque cloné le pouvoir de rétrograder tous les
    /// appareils — l'attaque même que cette signature ferme. Et la régénérer
    /// casserait toutes les installations déjà déployées, qui épinglent
    /// celle-ci. L'outil qui signe est `tools/config/sign_config.py`.
    static let operatorPublicKeyBase64 = "gPNDEWgj22dE3ANZ1Sky8WYmoRAkTmIR8cYgOJ6+54U="

    /// ⚠️ NE PASSER À `true` QU'APRÈS que le serveur signe RÉELLEMENT, et que
    /// des clients vérifiants soient déployés. Avant cela, toute configuration
    /// serait ignorée et chaque appareil retomberait sur son défaut — la
    /// rétrogradation même qu'on veut empêcher.
    static let requireSignature = false

    /// Où l'on mémorise la plus haute version acceptée.
    static let lastVersionKey = "oshi_config_last_version"

    enum Rejection: Error, Equatable {
        /// L'enveloppe ne porte pas de signature, et on les exige.
        case unsigned
        /// Signature présente mais invalide. Toujours refusé.
        case badSignature
        /// Correctement signée, mais plus ancienne que ce qu'on a déjà vu.
        case rolledBack(seen: UInt64, offered: UInt64)
        case malformed
    }

    /// Ce que la signature couvre. Public pour être vérifiable sur vecteurs.
    static func signedBytes(payload: Data, configVersion: UInt64) -> Data {
        var d = Data()
        d.append(payload)
        d.append(0x00)
        var v = configVersion.bigEndian
        withUnsafeBytes(of: &v) { d.append(contentsOf: $0) }
        return d
    }

    static func verifySignature(payload: Data,
                                configVersion: UInt64,
                                signature: Data,
                                publicKeyBase64: String) -> Bool {
        guard let raw = Data(base64Encoded: publicKeyBase64),
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw) else {
            return false
        }
        return key.isValidSignature(signature,
                                    for: signedBytes(payload: payload, configVersion: configVersion))
    }

    /// Extrait la configuration d'une réponse `/v2/config`, en appliquant la
    /// politique.
    ///
    /// - Returns: les octets de configuration à parser.
    /// - Throws: [Rejection] quand la réponse ne doit PAS être obéie. L'appelant
    ///   garde alors ce qu'il avait — refuser veut dire « ne change rien », pas
    ///   « repars de zéro ».
    static func accept(responseBody: Data,
                       lastAcceptedVersion: UInt64,
                       publicKeyBase64: String = operatorPublicKeyBase64,
                       require: Bool = requireSignature) throws -> (payload: Data, version: UInt64) {
        guard let obj = try? JSONSerialization.jsonObject(with: responseBody) as? [String: Any] else {
            throw Rejection.malformed
        }

        // Forme héritée: pas d'enveloppe. C'est ce que le serveur renvoie
        // aujourd'hui, et ça doit continuer de fonctionner tant que
        // `require` est faux.
        guard let payloadB64 = obj["payload"] as? String,
              let payload = Data(base64Encoded: payloadB64) else {
            if require { throw Rejection.unsigned }
            return (responseBody, lastAcceptedVersion)
        }

        let version = UInt64((obj["config_version"] as? Int) ?? 0)

        guard let sigB64 = obj["sig"] as? String, let sig = Data(base64Encoded: sigB64) else {
            // Une enveloppe SANS signature est plus suspecte qu'une réponse
            // héritée: quelqu'un a pris la peine d'en construire une et a retiré
            // la preuve. On la refuse même quand on n'exige rien.
            throw Rejection.unsigned
        }

        guard verifySignature(payload: payload, configVersion: version,
                              signature: sig, publicKeyBase64: publicKeyBase64) else {
            throw Rejection.badSignature
        }

        // Rejouer une ancienne configuration authentique donnerait la même
        // rétrogradation sans rien forger.
        guard version > lastAcceptedVersion else {
            throw Rejection.rolledBack(seen: lastAcceptedVersion, offered: version)
        }
        return (payload, version)
    }
}
