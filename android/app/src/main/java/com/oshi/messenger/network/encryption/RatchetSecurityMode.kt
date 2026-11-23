package com.oshi.messenger.network.encryption

/**
 * Le mode de sécurité d'une session de ratchet, tel qu'il circule sur le fil.
 *
 * Pendant de `RatchetSecurityMode` d'iOS (`OSHI/PostQuantumKEM.swift`). Ce sont
 * des chaînes et non un `enum` Kotlin parce qu'elles sont sérialisées
 * telles quelles dans l'en-tête JSON et mélangées dans les données
 * additionnelles de l'AEAD: ce qui compte ici est la valeur exacte, pas le type.
 *
 * ⚠️ L'ÉPINGLAGE EST TOUT L'INTÉRÊT. Si le mode pouvait varier d'un message à
 * l'autre, un attaquant qui retire le chiffré KEM d'un message rétrograderait la
 * session en classique — précisément ce que l'hybride existe pour empêcher. Le
 * mode est donc enregistré dans la session, persisté avec elle, et mélangé dans
 * l'AAD, de sorte qu'un chiffré retiré produise un échec d'authentification
 * plutôt qu'une session plus faible et plus discrète.
 */
object RatchetSecurityMode {

    /** X25519 seul. Ce que sont toutes les sessions en production aujourd'hui. */
    const val CLASSICAL = "classical"

    /** X25519 **et** X-Wing, tous deux mélangés dans la clé racine. */
    const val HYBRID_PQ = "hybridPQ"

    /**
     * Un en-tête sans mode vient d'un pair d'avant le post-quantique. C'est
     * classique, pas une erreur — et ça doit le rester pour toujours: la moitié
     * du réseau ne se mettra jamais à jour.
     */
    fun normalized(raw: String?): String = when (raw) {
        HYBRID_PQ -> HYBRID_PQ
        else -> CLASSICAL
    }

    fun isHybrid(raw: String?): Boolean = normalized(raw) == HYBRID_PQ
}

/**
 * Quand une session a le droit d'utiliser la moitié post-quantique.
 *
 * `DISABLED` est l'interrupteur d'arrêt, pas le défaut: il rend chaque chemin
 * post-quantique inatteignable et ramène toute NOUVELLE session au comportement
 * classique, sans nouveau binaire. Les sessions déjà établies ne bougent pas,
 * dans un sens comme dans l'autre.
 */
object PostQuantumPolicy {
    /** Identique octet pour octet à la version d'avant l'hybride. */
    const val DISABLED = "disabled"

    /**
     * **Défaut de livraison.** Utiliser l'hybride dès que les DEUX côtés le
     * peuvent, rester silencieusement classique sinon. Ne peut jamais casser un
     * pair qui n'a pas le post-quantique.
     */
    const val HYBRID_WHEN_AVAILABLE = "hybridWhenAvailable"

    /**
     * Refuser d'établir une session tant que l'hybride n'est pas disponible.
     * Pas pour une diffusion générale — ça partitionne la maille — mais c'est ce
     * qui permet de tester le chemin « échouer fermé ».
     */
    const val REQUIRED = "required"
}

/**
 * Décide du mode d'UNE nouvelle session.
 *
 * Sorti du ratchet pour être testable seul: c'est une fonction pure de la
 * politique, du support local et de ce que l'on sait du pair — sans clé et sans
 * entrée-sortie.
 */
object PostQuantumNegotiator {

    /**
     * @param policy la préférence de l'appareil
     * @param peerPublicKey la clé X-Wing du pair, ou `null` s'il n'en a jamais publié
     * @param localSupported si cet appareil sait faire X-Wing (toujours vrai sur
     *   Android; sur iOS cela dépend de la version du système)
     * @return le mode à employer, ou `null` quand la politique exige l'hybride
     *   et qu'il est impossible — auquel cas l'appelant doit REFUSER la session
     *   plutôt que de retomber en classique.
     */
    fun mode(policy: String, peerPublicKey: ByteArray?, localSupported: Boolean): String? {
        val canHybrid = localSupported &&
            peerPublicKey != null &&
            peerPublicKey.size == PostQuantumKEM.PUBLIC_KEY_BYTE_COUNT
        return when (policy) {
            PostQuantumPolicy.DISABLED -> RatchetSecurityMode.CLASSICAL
            PostQuantumPolicy.REQUIRED ->
                if (canHybrid) RatchetSecurityMode.HYBRID_PQ else null
            else ->
                if (canHybrid) RatchetSecurityMode.HYBRID_PQ
                else RatchetSecurityMode.CLASSICAL
        }
    }
}
