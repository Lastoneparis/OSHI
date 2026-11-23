package com.oshi.messenger.network.encryption

import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.pqc.crypto.xwing.XWingKEMExtractor
import org.bouncycastle.pqc.crypto.xwing.XWingKEMGenerator
import org.bouncycastle.pqc.crypto.xwing.XWingKeyGenerationParameters
import org.bouncycastle.pqc.crypto.xwing.XWingKeyPairGenerator
import org.bouncycastle.pqc.crypto.xwing.XWingPrivateKeyParameters
import org.bouncycastle.pqc.crypto.xwing.XWingPublicKeyParameters
import java.security.SecureRandom

/**
 * X-Wing (ML-KEM-768 + X25519) — le pendant Android de `OSHI/PostQuantumKEM.swift`.
 *
 * POURQUOI CE FICHIER EXISTE. Toute clé asymétrique d'OSHI est en Curve25519, et
 * Shor casse X25519 comme Ed25519. La couche symétrique, elle, va bien:
 * AES-256-GCM avec des clés HKDF-SHA256 de 32 octets garde ~128 bits face à
 * Grover. L'exposition est donc entièrement dans la façon dont la racine est
 * convenue.
 *
 * Et ça compte plus ici qu'ailleurs: OSHI épingle du chiffré sur IPFS. Une app
 * de transport oblige l'attaquant à être sur le fil au bon moment; un stockage
 * public adressé par contenu laisse n'importe qui archiver aujourd'hui et lire
 * le jour où une machine quantique existe. C'est « récolter maintenant,
 * déchiffrer plus tard », et c'est la raison de faire ça AVANT.
 *
 * ⚠️ LE RISQUE PRINCIPAL N'EST PAS CRYPTOGRAPHIQUE, IL EST D'INTEROPÉRABILITÉ.
 * Si Android annonce une clé PQ sans produire exactement les mêmes octets
 * qu'iOS, rien ne plante: les deux côtés dérivent des racines différentes et
 * TOUT échoue au déchiffrement, en silence et dans les deux sens. C'est
 * précisément ce que l'audit avait signalé comme pire cas.
 *
 * D'où deux décisions:
 *
 *  1. ON N'ÉCRIT PAS LE COMBINATEUR À LA MAIN. BouncyCastle 1.81 implémente
 *     X-Wing (`org.bouncycastle.pqc.crypto.xwing`). Réimplémenter SHA3-256 sur
 *     l'étiquette, les deux secrets et les deux clés à partir du brouillon IETF
 *     aurait été une deuxième chance de se tromper — et une faute d'un octet y
 *     est invisible.
 *  2. ON SE VÉRIFIE CONTRE APPLE, PAS CONTRE LA SPÉCIFICATION. `XWingInteropTest`
 *     rejoue de VRAIS vecteurs produits par CryptoKit sur un simulateur iOS 26.
 *     Un test qui comparerait cette implémentation à ma lecture du brouillon ne
 *     prouverait rien: les deux peuvent être fausses de la même façon.
 *
 * HYBRIDE UNIQUEMENT, JAMAIS PQ SEUL. X-Wing est déjà lui-même un hybride, et
 * par-dessus on continue de mélanger le secret classique X25519 d'OSHI dans la
 * racine. Une cassure de ML-KEM ne peut donc pas rendre la session plus faible
 * qu'aujourd'hui. Une primitive qui a quelques années n'a pas le droit d'être la
 * seule chose qui tient la porte.
 *
 * ÉCHOUER FERMÉ. Si une session a été établie en hybride et que la moitié PQ
 * casse ensuite — chiffré manquant, mauvaise longueur, décapsulation en erreur —
 * on lève. On ne repasse jamais en classique en silence, parce que « retomber
 * discrètement » est exactement la rétrogradation qu'un attaquant provoquerait.
 */
object PostQuantumKEM {

    // PAS DE JOURNALISATION ICI, ET PAS D'IMPORT `android.*`. Ce fichier est compilé TEL
    // QUEL par le client Desktop (Windows/Linux) via `kotlin.srcDir` — voir le
    // `sourceSets` de OSHI-Desktop/build.gradle.kts. Une seule implémentation partagée est
    // ce qui garantit que les trois plateformes produisent les mêmes octets; une copie
    // aurait dérivé, et la dérive est silencieuse (les deux côtés échouent au
    // déchiffrement, jamais à la compilation). Un `android.util.Log` suffisait à rendre le
    // partage impossible: il a été retiré, l'échec est porté par l'exception.

    /**
     * Écrit dans l'en-tête du fil pour qu'un changement d'algorithme soit un
     * changement de valeur et non une devinette sur ce que les octets voulaient
     * dire. Doit rester identique à `PostQuantumKEM.algorithmIdentifier` d'iOS.
     */
    const val ALGORITHM_IDENTIFIER = "xwing-mlkem768-x25519"

    /**
     * Tailles fixées par le brouillon X-Wing, et confirmées contre CryptoKit.
     * Elles servent à rejeter l'absurde AVANT d'atteindre BouncyCastle: un
     * contrôle de longueur est total et ne coûte rien, et il empêche un pair mal
     * formé de se transformer en erreur opaque qu'il faudrait ensuite
     * interpréter.
     *
     * 1216 = 1184 (clé publique ML-KEM-768) + 32 (X25519)
     * 1120 = 1088 (chiffré ML-KEM-768)      + 32 (clé éphémère X25519)
     */
    const val PUBLIC_KEY_BYTE_COUNT = 1216
    const val CIPHERTEXT_BYTE_COUNT = 1120
    const val SHARED_SECRET_BYTE_COUNT = 32

    /**
     * La graine persistée: 32 octets plutôt que la clé étendue.
     *
     * C'est aussi ce qu'iOS persiste (`seedRepresentation`), et c'est ce qui
     * garantit qu'un appareil ne puisse jamais stocker une moitié publique en
     * désaccord avec sa moitié privée — la publique se redérive de la graine.
     */
    const val SEED_BYTE_COUNT = 32

    /** Android n'a aucun plancher d'OS ici, contrairement à iOS qui exige 26. */
    const val IS_SUPPORTED_BY_PLATFORM = true

    class MalformedKeyMaterial(message: String) : Exception(message)
    class DecapsulationFailed(message: String) : Exception(message)

    data class KeyPair(val seed: ByteArray, val publicKey: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is KeyPair && seed.contentEquals(other.seed) &&
                publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = seed.contentHashCode() * 31 + publicKey.contentHashCode()
    }

    // ------------------------------------------------------------ génération

    fun generateKeyPair(): KeyPair {
        val seed = ByteArray(SEED_BYTE_COUNT).also { SecureRandom().nextBytes(it) }
        return KeyPair(seed, publicKey(seed))
    }

    /**
     * La clé publique qu'engendre une graine.
     *
     * Déterministe, et c'est la moitié du contrat qu'iOS impose: le même 32
     * octets doit donner les mêmes 1216 des deux côtés, sinon un appareil qui
     * restaure une sauvegarde publierait une clé dont il n'a pas la privée.
     */
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == SEED_BYTE_COUNT) {
            "graine de ${seed.size} octets, attendu $SEED_BYTE_COUNT"
        }
        val gen = XWingKeyPairGenerator()
        gen.init(XWingKeyGenerationParameters(FixedSeedRandom(seed)))
        val pair = gen.generateKeyPair()
        val pk = (pair.public as XWingPublicKeyParameters).encoded
        check(pk.size == PUBLIC_KEY_BYTE_COUNT) {
            "clé publique de ${pk.size} octets, attendu $PUBLIC_KEY_BYTE_COUNT"
        }
        return pk
    }

    private fun privateKey(seed: ByteArray): XWingPrivateKeyParameters {
        val gen = XWingKeyPairGenerator()
        gen.init(XWingKeyGenerationParameters(FixedSeedRandom(seed)))
        return gen.generateKeyPair().private as XWingPrivateKeyParameters
    }

    // --------------------------------------------------------- encapsulation

    data class Encapsulation(val ciphertext: ByteArray, val sharedSecret: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Encapsulation && ciphertext.contentEquals(other.ciphertext) &&
                sharedSecret.contentEquals(other.sharedSecret)
        override fun hashCode(): Int =
            ciphertext.contentHashCode() * 31 + sharedSecret.contentHashCode()
    }

    /**
     * La moitié de l'initiateur: produire un secret partagé plus le chiffré dont
     * le pair a besoin pour dériver le même secret.
     *
     * Contrairement à Diffie-Hellman — où les deux côtés calculent le secret à
     * partir de clés qu'ils détiennent déjà — un KEM est unidirectionnel. Ce
     * seul fait est ce qui force le chiffré sur le fil, et la raison pour
     * laquelle ceci ne pouvait pas être glissé dans le calcul du secret partagé
     * sans changer l'en-tête.
     */
    fun encapsulate(publicKey: ByteArray): Encapsulation {
        if (publicKey.size != PUBLIC_KEY_BYTE_COUNT) {
            throw MalformedKeyMaterial(
                "clé publique de ${publicKey.size} octets, attendu $PUBLIC_KEY_BYTE_COUNT"
            )
        }
        val pk: XWingPublicKeyParameters = try {
            XWingPublicKeyParameters(publicKey)
        } catch (t: Throwable) {
            throw MalformedKeyMaterial("clé publique illisible: ${t.message}")
        }
        val gen = XWingKEMGenerator(SecureRandom())
        val enc = gen.generateEncapsulated(pk as AsymmetricKeyParameter)
        val ct = enc.encapsulation
        val ss = enc.secret
        check(ct.size == CIPHERTEXT_BYTE_COUNT) { "chiffré de ${ct.size} octets" }
        check(ss.size == SHARED_SECRET_BYTE_COUNT) { "secret de ${ss.size} octets" }
        return Encapsulation(ct, ss)
    }

    /**
     * La moitié du répondeur.
     *
     * Toute défaillance se réduit ici à [DecapsulationFailed], volontairement:
     * la seule réaction correcte de l'appelant est de refuser la session, et une
     * erreur plus détaillée serait un petit oracle pour celui qui nous a envoyé
     * ces octets.
     */
    fun decapsulate(ciphertext: ByteArray, seed: ByteArray): ByteArray {
        if (ciphertext.size != CIPHERTEXT_BYTE_COUNT) {
            throw DecapsulationFailed("chiffré de ${ciphertext.size} octets")
        }
        if (seed.size != SEED_BYTE_COUNT) {
            throw DecapsulationFailed("graine de ${seed.size} octets")
        }
        return try {
            val ss = XWingKEMExtractor(privateKey(seed)).extractSecret(ciphertext)
            check(ss.size == SHARED_SECRET_BYTE_COUNT)
            ss
        } catch (t: Throwable) {
            throw DecapsulationFailed("décapsulation impossible")
        }
    }

    /**
     * Un `SecureRandom` qui rend la graine TELLE QUELLE.
     *
     * ⚠️ NE PAS Y ÉTENDRE LA GRAINE. C'est l'erreur commise ici au premier essai:
     * on y passait SHAKE-256(graine), et les clés publiques ne correspondaient
     * plus à celles d'iOS. BouncyCastle demande à ce générateur les 32 octets
     * BRUTS, puis fait lui-même l'expansion du brouillon X-Wing —
     * `SHAKE256(graine) → 96 octets`, dont les 64 premiers deviennent (d, z)
     * pour ML-KEM et les 32 derniers la clé privée X25519. Étendre ici la faisait
     * une seconde fois.
     *
     * Vérifié en désassemblant `XWingKeyPairGenerator`, pas en le supposant.
     *
     * C'est ce qui rend `graine → clé publique` déterministe et reproductible
     * d'un appareil à l'autre: sans ça il faudrait persister la clé privée
     * étendue entière, et surtout on ne pourrait pas rejouer les vecteurs
     * d'Apple.
     */
    private class FixedSeedRandom(private val seed: ByteArray) : SecureRandom() {
        private var used = false

        override fun nextBytes(bytes: ByteArray) {
            if (used || bytes.size != seed.size) {
                // La génération de clé ne demande QUE la graine, une seule fois.
                // Tout le reste serait de l'aléa non reproductible, donc une
                // paire que la même graine ne réengendrerait pas — exactement ce
                // que cette classe existe pour empêcher.
                throw IllegalStateException(
                    "aléa inattendu: ${bytes.size} octets (déjà servi=$used)"
                )
            }
            System.arraycopy(seed, 0, bytes, 0, seed.size)
            used = true
        }
    }
}
