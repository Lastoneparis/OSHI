package com.oshi.messenger.network.encryption

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * L'identité post-quantique de cet appareil, et ce que l'on sait de celle des
 * pairs.
 *
 * Pendant de `PostQuantumIdentity` et `PostQuantumPeerDirectory` d'iOS.
 *
 * ON PERSISTE LA GRAINE, PAS LA CLÉ ÉTENDUE. 32 octets suffisent: la clé
 * publique de 1216 octets s'en redérive de façon déterministe, des deux côtés.
 * C'est ce qui garantit qu'un appareil ne puisse jamais stocker une moitié
 * publique en désaccord avec sa moitié privée — le cas où l'on publierait une
 * clé vers laquelle les pairs chiffrent et que l'on ne sait pas ouvrir.
 *
 * ── L'ANNONCE EST ACTIVE, APRÈS UN ÉCHANGE RÉEL ENTRE LES DEUX CLIENTS ─────
 *
 * Elle est restée désactivée tant que seule la CRYPTOGRAPHIE était prouvée. Les
 * trois pièces — le KEM, les données additionnelles de l'AEAD, la dérivation de
 * la racine — sont identiques à iOS octet pour octet, contre de vrais vecteurs
 * CryptoKit. Mais ça ne disait rien de la TUYAUTERIE qui les assemble.
 *
 * `HybridExchangeInteropTest` l'a close: un simulateur iOS 26 établit une
 * session hybride et chiffre trois messages; cet appareil reconstruit la même
 * session, les ouvre tous les trois, et répond; iOS ouvre les réponses. Du vrai
 * chiffré, de vrais en-têtes, les deux rôles. Trois tests de plus vérifient ce
 * qui ressemblerait à un succès sans en être un: retirer le chiffré doit faire
 * ÉCHOUER l'ouverture, changer le secret post-quantique doit changer la racine,
 * et le chiffré porté doit rendre le secret dont la racine est faite.
 *
 * Ce qui est en jeu maintenant que c'est actif: les iPhone établissent des
 * sessions hybrides avec cet appareil. Un défaut dans la tuyauterie couperait
 * ces conversations en silence, dans les deux sens — d'où l'exigence d'avoir
 * exercé l'échange avant, et non seulement raisonné dessus.
 */
object PostQuantumIdentity {

    private const val TAG = "PostQuantumIdentity"
    private const val PREFS = "oshi_pq_identity"
    private const val KEY_SEED = "pq_seed_b64"

    /**
     * Activé après l'échange hybride vérifié dans les deux sens entre un
     * simulateur iOS 26 et un émulateur Android (voir l'en-tête de ce fichier).
     *
     * Le remettre à `false` est le repli sûr: les pairs cessent de nous voir
     * comme capables de post-quantique et chaque NOUVELLE session redevient
     * classique, exactement comme avant. Les sessions déjà hybrides ne bougent
     * pas — les défaire serait plus brutal que de les laisser.
     */
    const val ADVERTISE_PUBLIC_KEY = true

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    /**
     * La graine de cet appareil, créée à la première demande.
     *
     * Rend `null` plutôt que de lever si le stockage chiffré est indisponible:
     * l'absence de post-quantique n'est pas une panne, c'est le comportement
     * classique, et faire tomber l'app pour ça serait hors de proportion.
     */
    @Synchronized
    fun seed(context: Context): ByteArray? = try {
        val p = prefs(context)
        val existing = p.getString(KEY_SEED, null)
        if (existing != null) {
            Base64.decode(existing, Base64.NO_WRAP)
        } else {
            val fresh = PostQuantumKEM.generateKeyPair().seed
            p.edit().putString(KEY_SEED, Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
            Log.i(TAG, "identité post-quantique créée")
            fresh
        }
    } catch (t: Throwable) {
        Log.w(TAG, "identité post-quantique indisponible: ${t.message}")
        null
    }

    /** La clé publique de cet appareil, redérivée de la graine. */
    fun publicKey(context: Context): ByteArray? = try {
        seed(context)?.let { PostQuantumKEM.publicKey(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "clé publique post-quantique indérivable: ${t.message}")
        null
    }

    /**
     * Ce que l'on publie dans la charge utile de profil, ou `null`.
     *
     * `null` si [ADVERTISE_PUBLIC_KEY] repasse à `false`, ou si le stockage
     * chiffré refuse — et un pair qui ne reçoit pas ce champ nous traite comme
     * un appareil sans post-quantique, donc la conversation reste classique.
     */
    fun advertisedPublicKeyBase64(context: Context): String? {
        if (!ADVERTISE_PUBLIC_KEY) return null
        return publicKey(context)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }
}

/**
 * Les clés post-quantiques publiées par les pairs.
 *
 * On les enregistre MÊME quand on n'annonce pas la nôtre: c'est sans risque, ça
 * ne change rien au comportement, et ça veut dire que le jour où l'on active
 * l'annonce l'annuaire est déjà rempli plutôt que vide.
 */
object PostQuantumPeerDirectory {

    private const val TAG = "PostQuantumPeerDirectory"
    private const val PREFS = "oshi_pq_peers"

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    /**
     * Enregistre la clé d'un pair.
     *
     * Une longueur inattendue est REFUSÉE ici plutôt que plus tard: une clé mal
     * formée qui atteindrait la négociation ferait échouer l'établissement de la
     * session, alors que l'ignorer laisse simplement la conversation en
     * classique — ce qui est le bon comportement face à un pair qui raconte
     * n'importe quoi.
     */
    fun record(context: Context, publicKey: ByteArray, forPeer: String) {
        if (publicKey.size != PostQuantumKEM.PUBLIC_KEY_BYTE_COUNT) {
            Log.w(TAG, "clé post-quantique de ${publicKey.size} octets ignorée")
            return
        }
        try {
            prefs(context).edit()
                .putString(forPeer, Base64.encodeToString(publicKey, Base64.NO_WRAP))
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "impossible d'enregistrer la clé du pair: ${t.message}")
        }
    }

    /** La clé d'un pair, ou `null` s'il n'en a jamais publié. */
    fun publicKey(context: Context, forPeer: String): ByteArray? = try {
        prefs(context).getString(forPeer, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?.takeIf { it.size == PostQuantumKEM.PUBLIC_KEY_BYTE_COUNT }
    } catch (t: Throwable) {
        Log.w(TAG, "lecture de l'annuaire impossible: ${t.message}")
        null
    }
}
