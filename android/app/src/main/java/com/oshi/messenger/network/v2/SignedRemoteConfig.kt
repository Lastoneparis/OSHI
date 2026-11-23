package com.oshi.messenger.network.v2

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * La configuration distante, signée, et impossible à faire reculer — pendant
 * exact de `OSHI/SignedRemoteConfig.swift`.
 *
 * LA MENACE (M1, rétrogradation pilotée par le serveur). `GET /v2/config` décide
 * si ce téléphone parle v2 ou retombe sur l'ancien chemin. Influencer cette
 * réponse EST la rétrogradation: il suffit de répondre `v2_enabled=false` ou
 * `rollout_percent=0` pour renvoyer tout le monde sur le protocole le plus
 * faible, et rien ne prouve aujourd'hui que la réponse vient de l'opérateur.
 *
 * ── ON SIGNE DES OCTETS, PAS « LE JSON » ───────────────────────────────────
 *
 * Signer un objet JSON oblige à s'accorder sur une forme canonique — ordre des
 * clés, espaces, écriture des nombres — et chaque divergence entre les deux
 * plateformes devient un refus de configuration LÉGITIME. L'enveloppe transporte
 * donc la configuration comme un bloc d'octets en base64, et la signature couvre
 * exactement ces octets: ceux que l'on va parser.
 *
 *     enveloppe = { "payload": <base64 du JSON>, "config_version": N,
 *                   "sig": <base64 Ed25519> }
 *     signé     = payload ‖ 0x00 ‖ config_version (8 octets, gros-boutiste)
 *
 * ── LE RETOUR EN ARRIÈRE EST UNE ATTAQUE ──────────────────────────────────
 *
 * Une signature seule ne suffit pas: rejouer une ANCIENNE configuration
 * authentique — celle d'avant l'activation de v2 — rétrograde aussi bien, sans
 * rien forger. D'où `config_version`, strictement croissante et mémorisée.
 */
object SignedRemoteConfig {

    /**
     * La clé publique Ed25519 de l'opérateur, ÉPINGLÉE dans le binaire.
     *
     * Épinglée et non téléchargée: une clé prise sur le même serveur ne
     * prouverait rien de plus que la réponse elle-même.
     *
     * Doit rester identique à `SignedRemoteConfig.operatorPublicKeyBase64` d'iOS.
     *
     * ⚠️ La moitié privée ne vit PAS dans le dépôt: `~/.oshi/`, en 0600. La
     * committer donnerait à quiconque cloné le pouvoir de rétrograder tous les
     * appareils. L'outil qui signe est `tools/config/sign_config.py`.
     */
    const val OPERATOR_PUBLIC_KEY_BASE64 = "gPNDEWgj22dE3ANZ1Sky8WYmoRAkTmIR8cYgOJ6+54U="

    /**
     * ⚠️ NE PASSER À `true` QU'APRÈS que le serveur signe RÉELLEMENT et que des
     * clients vérifiants soient déployés. Avant, toute configuration serait
     * ignorée et chaque appareil retomberait sur son défaut — la rétrogradation
     * même qu'on veut empêcher.
     */
    const val REQUIRE_SIGNATURE = false

    sealed class Rejection(message: String) : Exception(message) {
        /** Pas de signature, et on les exige. */
        object Unsigned : Rejection("configuration non signée")
        /** Signature présente mais invalide. TOUJOURS refusé. */
        object BadSignature : Rejection("signature invalide")
        /** Correctement signée, mais plus ancienne que ce qu'on a déjà vu. */
        class RolledBack(val seen: Long, val offered: Long) :
            Rejection("configuration rejouée: vue=$seen proposée=$offered")
        object Malformed : Rejection("corps illisible")
    }

    data class Accepted(val payload: ByteArray, val version: Long) {
        override fun equals(other: Any?): Boolean =
            other is Accepted && payload.contentEquals(other.payload) && version == other.version
        override fun hashCode(): Int = payload.contentHashCode() * 31 + version.hashCode()
    }

    /** Ce que la signature couvre. Public pour être vérifiable sur vecteurs. */
    fun signedBytes(payload: ByteArray, configVersion: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(payload)
        out.write(0x00)
        for (i in 7 downTo 0) out.write(((configVersion ushr (i * 8)) and 0xFF).toInt())
        return out.toByteArray()
    }

    fun verifySignature(
        payload: ByteArray,
        configVersion: Long,
        signature: ByteArray,
        publicKeyBase64: String
    ): Boolean = try {
        val raw = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        if (raw.size != 32) false else {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(raw, 0))
            val msg = signedBytes(payload, configVersion)
            signer.update(msg, 0, msg.size)
            signer.verifySignature(signature)
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Applique la politique à une réponse `/v2/config`.
     *
     * Lever veut dire « NE CHANGE RIEN », pas « repars de zéro »: l'appelant
     * garde la dernière configuration acceptée. Une réponse refusée ne doit
     * jamais laisser un appareil avec moins qu'avant.
     */
    @Throws(Rejection::class)
    fun accept(
        responseBody: ByteArray,
        lastAcceptedVersion: Long,
        publicKeyBase64: String = OPERATOR_PUBLIC_KEY_BASE64,
        require: Boolean = REQUIRE_SIGNATURE
    ): Accepted {
        val obj = try {
            JSONObject(String(responseBody, Charsets.UTF_8))
        } catch (_: Throwable) {
            throw Rejection.Malformed
        }

        // Forme HÉRITÉE: pas d'enveloppe. C'est ce que le serveur renvoie
        // aujourd'hui, et ça doit continuer de marcher tant que `require` est faux.
        val payloadB64 = obj.optString("payload", "")
        if (payloadB64.isEmpty()) {
            if (require) throw Rejection.Unsigned
            return Accepted(responseBody, lastAcceptedVersion)
        }
        val payload = try {
            Base64.decode(payloadB64, Base64.NO_WRAP)
        } catch (_: Throwable) {
            throw Rejection.Malformed
        }

        val version = obj.optLong("config_version", 0L)

        val sigB64 = obj.optString("sig", "")
        if (sigB64.isEmpty()) {
            // Une enveloppe SANS signature est plus suspecte qu'une réponse
            // héritée: quelqu'un a pris la peine d'en construire une et d'en
            // retirer la preuve. Refusée même quand on n'exige rien.
            throw Rejection.Unsigned
        }
        val sig = try {
            Base64.decode(sigB64, Base64.NO_WRAP)
        } catch (_: Throwable) {
            throw Rejection.Malformed
        }

        if (!verifySignature(payload, version, sig, publicKeyBase64)) throw Rejection.BadSignature

        if (version <= lastAcceptedVersion) {
            throw Rejection.RolledBack(lastAcceptedVersion, version)
        }
        return Accepted(payload, version)
    }
}
