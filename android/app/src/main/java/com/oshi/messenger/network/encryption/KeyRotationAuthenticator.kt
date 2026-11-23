package com.oshi.messenger.network.encryption

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * L'autorisation d'une rotation de clé — pendant exact de
 * `OSHI/KeyRotationAuthenticator.swift`.
 *
 * CE QUE CE FICHIER REMPLACE. La rotation était cosmétique et forgeable: la
 * « signature » était un `SHA256("old:new")` SANS CLÉ, donc quiconque pouvait
 * envoyer un paquet pouvait aussi la calculer. Les deux plateformes refusent
 * aujourd'hui toute rotation entrante — sûr, et la fonctionnalité n'existe pas.
 *
 * ── POURQUOI PAS UNE SIGNATURE Ed25519 ─────────────────────────────────────
 *
 * Il faudrait connaître la clé de SIGNATURE du pair. Or l'identité d'un contact
 * est sa clé X25519 de CHIFFREMENT; la clé Ed25519 est une paire indépendante,
 * publiée dans le bundle de prékeys et servie par le SERVEUR. Aller la chercher
 * là au moment de vérifier reviendrait à demander à l'attaquant la clé qui sert
 * à le démasquer.
 *
 * ── CE QUI EST FAIT À LA PLACE ─────────────────────────────────────────────
 *
 * La seule autorisation qui compte pour remplacer une clé est de PROUVER QU'ON
 * DÉTIENT L'ANCIENNE. On l'établit par un secret ECDH entre l'ancienne identité
 * et celle du destinataire — deux clés que les deux côtés ont déjà:
 *
 *     ss  = X25519(ancienne privée émetteur, publique destinataire)
 *         = X25519(privée destinataire, ancienne publique émetteur)
 *     mk  = HKDF-SHA256(ss, sel vide, info "OSHI-KeyRotation-v1", 32 octets)
 *     msg = ancienne ‖ 0x00 ‖ nouvelle ‖ 0x00 ‖ horodatage(8, BE) ‖ n°(4, BE)
 *     tag = HMAC-SHA256(mk, msg)
 *
 * L'octet nul entre les deux clés n'est pas décoratif: sans lui, `("ab","c")` et
 * `("a","bc")` donneraient le même tag et un attaquant pourrait déplacer la
 * frontière. L'horodatage et le numéro y sont aussi, sinon un paquet capté
 * resterait valable pour toujours et pourrait ramener une clé abandonnée.
 */
object KeyRotationAuthenticator {

    /** Doit être identique à iOS au caractère près. */
    const val INFO = "OSHI-KeyRotation-v1"

    /**
     * Au-delà, un paquet est considéré comme rejoué. Assez large pour un
     * téléphone resté hors ligne, assez étroit pour qu'un paquet capté ne serve
     * pas indéfiniment. Le numéro de rotation est la seconde barrière: le temps
     * seul ne suffit pas, deux appareils n'étant jamais à la même heure.
     */
    const val MAX_AGE_MILLIS = 7L * 24 * 3600 * 1000

    sealed class Failure(message: String) : Exception(message) {
        object MalformedKey : Failure("clé illisible")
        object BadTag : Failure("tag invalide")
        object TooOld : Failure("paquet hors de la fenêtre temporelle")
        object Replayed : Failure("numéro de rotation déjà vu")
    }

    /** Le message authentifié. Public pour être vérifiable octet pour octet. */
    fun canonicalMessage(
        oldPublicKey: String,
        newPublicKey: String,
        timestampMillis: Long,
        rotationNumber: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(oldPublicKey.toByteArray(Charsets.UTF_8))
        out.write(0x00)
        out.write(newPublicKey.toByteArray(Charsets.UTF_8))
        out.write(0x00)
        for (i in 7 downTo 0) out.write(((timestampMillis ushr (i * 8)) and 0xFF).toInt())
        for (i in 3 downTo 0) out.write(((rotationNumber ushr (i * 8)) and 0xFF))
        return out.toByteArray()
    }

    /**
     * La clé de MAC. Réutilise le HKDF déjà prouvé identique à CryptoKit sur
     * vecteurs plutôt que d'en écrire un second.
     */
    fun macKey(sharedSecret: ByteArray): ByteArray =
        DoubleRatchet.hkdf32(sharedSecret, INFO)

    fun tag(
        sharedSecret: ByteArray,
        oldPublicKey: String,
        newPublicKey: String,
        timestampMillis: Long,
        rotationNumber: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey(sharedSecret), "HmacSHA256"))
        return mac.doFinal(
            canonicalMessage(oldPublicKey, newPublicKey, timestampMillis, rotationNumber)
        )
    }

    /**
     * Vérifie un tag reçu, en TEMPS CONSTANT.
     *
     * Une comparaison naïve sort au premier octet qui diffère et laisse mesurer
     * combien d'octets sont justes, ce qui suffit à fabriquer un tag valide
     * octet par octet.
     */
    fun isValidTag(
        received: ByteArray,
        sharedSecret: ByteArray,
        oldPublicKey: String,
        newPublicKey: String,
        timestampMillis: Long,
        rotationNumber: Int
    ): Boolean {
        val expected = tag(sharedSecret, oldPublicKey, newPublicKey, timestampMillis, rotationNumber)
        return java.security.MessageDigest.isEqual(expected, received)
    }

    /**
     * Les contrôles de temps et d'ordre, séparés du calcul cryptographique pour
     * être testés seuls.
     *
     * [lastRotationNumber] est le dernier numéro ACCEPTÉ pour ce contact.
     * Exiger strictement plus grand est ce qui empêche de rejouer une rotation
     * ancienne pour revenir à une clé déjà quittée.
     */
    @Throws(Failure::class)
    fun checkFreshness(
        timestampMillis: Long,
        rotationNumber: Int,
        lastRotationNumber: Int,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val age = nowMillis - timestampMillis
        // Un paquet daté du futur est aussi suspect qu'un paquet trop vieux; on
        // tolère une dérive d'horloge modeste dans les deux sens.
        if (age > MAX_AGE_MILLIS || age < -MAX_AGE_MILLIS) throw Failure.TooOld
        if (rotationNumber <= lastRotationNumber) throw Failure.Replayed
    }
}
