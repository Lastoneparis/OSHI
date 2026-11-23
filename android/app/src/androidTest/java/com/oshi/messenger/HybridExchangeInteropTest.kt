package com.oshi.messenger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.encryption.DoubleRatchet
import com.oshi.messenger.network.encryption.IOSRatchetMessage
import com.oshi.messenger.network.encryption.PostQuantumKEM
import com.oshi.messenger.network.encryption.RatchetSecurityMode
import com.oshi.messenger.network.encryption.RatchetSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.File
import java.util.Base64

/**
 * UN ÉCHANGE HYBRIDE RÉEL entre les deux clients: on ouvre ici des messages
 * chiffrés par un vrai iPhone (simulateur iOS 26), et on répond.
 *
 * POURQUOI CE TEST EXISTE ALORS QUE TROIS AUTRES PASSENT DÉJÀ. Le KEM, les
 * données additionnelles et la dérivation de racine sont prouvés exacts
 * SÉPARÉMENT. Ce test vérifie la TUYAUTERIE qui les assemble: la session
 * porte-t-elle le mode et le chiffré, l'en-tête les écrit-il, l'AAD les
 * reprend-elle, et le message s'ouvre-t-il de l'autre côté. C'est précisément
 * la partie que je ne pouvais pas prouver jusqu'ici, et la raison pour laquelle
 * l'annonce de la clé publique est restée désactivée.
 *
 * POURQUOI ÇA NE DEMANDE PAS DEUX TÉLÉPHONES EN RÉSEAU. Le répondeur dérive sa
 * clé de ratchet du secret partagé lui-même
 * (`derivedResponderRatchetPrivateKey`), donc à partir des mêmes entrées fixes
 * les deux plateformes construisent la MÊME session. Ce qui circule ensuite est
 * exactement ce qui circulerait entre deux appareils — mêmes octets, même
 * en-tête, même AAD.
 *
 * Instrumenté et non JVM parce que la sérialisation passe par
 * `android.util.Base64`, qui n'est qu'une souche sur la JVM.
 */
@RunWith(AndroidJUnit4::class)
class HybridExchangeInteropTest {

    private val crypto = CryptoManager()
    private val ratchet = DoubleRatchet(crypto)

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun fixture(): JSONObject {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return JSONObject(
            ctx.assets.open("hybrid_exchange_ios.json").bufferedReader().readText()
        )
    }

    /**
     * Reconstruit la session hybride à partir des mêmes entrées fixes qu'iOS.
     *
     * [asInitiator] décide du rôle: `false` nous met en RÉPONDEUR, ce qu'iOS
     * attend quand c'est lui qui a ouvert la conversation.
     */
    private fun session(f: JSONObject, asInitiator: Boolean): RatchetSession {
        val ikm = DoubleRatchet.sessionIkm(
            b64(f.getString("sharedSecret")), b64(f.getString("pqSecret"))
        )
        val (root, _) = DoubleRatchet.deriveRootAndChain(
            b64(f.getString("sharedSecret")), b64(f.getString("pqSecret"))
        )
        val responderPriv = DoubleRatchet.derivedResponderRatchetPrivateKey(ikm)
        val responderPub = X25519PrivateKeyParameters(responderPriv, 0)
            .generatePublicKey().encoded
        val responderChain = DoubleRatchet.responderInitialChain(ikm)

        return if (asInitiator) {
            val (ephPriv, ephPub) = crypto.generateEphemeralKeyPair()
            val dh = crypto.deriveSharedSecretWithKeys(ephPriv, responderPub)
            RatchetSession(
                rootKey = root,
                sendingChainKey = DoubleRatchet.hkdf32(dh, "ChainKey"),
                receivingChainKey = responderChain,
                ourRatchetPrivateKey = ephPriv,
                ourRatchetPublicKey = ephPub,
                theirRatchetPublicKey = responderPub,
                sessionId = f.getString("sessionId"),
                isInitiator = true,
                sessionVersion = DoubleRatchet.RATCHETING_SESSION_VERSION,
                securityMode = RatchetSecurityMode.HYBRID_PQ,
                pqCiphertext = b64(f.getString("pqCiphertext"))
            )
        } else {
            RatchetSession(
                rootKey = root,
                sendingChainKey = responderChain,
                receivingChainKey = ByteArray(32),
                ourRatchetPrivateKey = responderPriv,
                ourRatchetPublicKey = responderPub,
                theirRatchetPublicKey = null,
                sessionId = f.getString("sessionId"),
                isInitiator = false,
                sessionVersion = DoubleRatchet.RATCHETING_SESSION_VERSION,
                securityMode = RatchetSecurityMode.HYBRID_PQ,
                pqCiphertext = b64(f.getString("pqCiphertext"))
            )
        }
    }

    /**
     * ⚠️ LE test: des messages chiffrés par iOS en mode hybride, ouverts ici.
     *
     * Trois textes, dont un avec accents et un emoji — l'UTF-8 fait partie du
     * contrat au même titre que les clés.
     */
    @Test
    fun des_messages_hybrides_d_ios_s_ouvrent_ici() {
        val f = fixture()
        val s = session(f, asInitiator = false)
        val expected = f.getJSONArray("plaintexts")
        val wire = f.getJSONArray("messages")
        assertEquals(expected.length(), wire.length())

        for (i in 0 until wire.length()) {
            val msg = IOSRatchetMessage.fromJSON(
                JSONObject(String(b64(wire.getString(i)), Charsets.UTF_8))
            )
            assertEquals(
                "iOS n'a pas marqué le message comme hybride",
                RatchetSecurityMode.HYBRID_PQ, msg.header.securityMode
            )
            assertEquals(
                PostQuantumKEM.CIPHERTEXT_BYTE_COUNT,
                msg.header.pqCiphertext?.size
            )
            assertEquals(
                "message $i illisible",
                expected.getString(i), ratchet.decrypt(s, msg)
            )
        }
    }

    /**
     * Et l'autre direction: on chiffre, et iOS devra ouvrir.
     *
     * Une direction qui marche pendant que l'autre échoue est le symptôme le
     * plus trompeur qui soit — il ressemble à un problème de réseau.
     */
    @Test
    fun on_produit_des_messages_qu_ios_devra_ouvrir() {
        val f = fixture()
        val s = session(f, asInitiator = false)
        val textes = listOf(
            "réponse depuis Android",
            "et une seconde, même chaîne",
            "accents: çöğ — emoji 🛡️"
        )
        val out = JSONArray()
        for (t in textes) {
            val msg = ratchet.encrypt(s, t)
            assertEquals(RatchetSecurityMode.HYBRID_PQ, msg.header.securityMode)
            out.put(Base64.getEncoder().encodeToString(
                msg.toJSON().toString().toByteArray(Charsets.UTF_8)
            ))
        }
        val doc = JSONObject().apply {
            put("sharedSecret", f.getString("sharedSecret"))
            put("pqSecret", f.getString("pqSecret"))
            put("pqCiphertext", f.getString("pqCiphertext"))
            put("sessionId", f.getString("sessionId"))
            put("plaintexts", JSONArray(textes))
            put("messages", out)
        }
        // Écrit dans le stockage de l'app, récupéré ensuite par `adb pull`.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.getExternalFilesDir(null), "oshi_hybrid_replies_from_android.json")
        file.writeText(doc.toString(2))
        android.util.Log.w("HybridExchange", "HYBRID_REPLIES_WRITTEN ${file.absolutePath}")
        assertTrue(file.exists())
    }

    /**
     * Le mode DOIT changer le résultat. Sans ce test, une implémentation qui
     * ignore la moitié post-quantique passerait les deux précédents: elle
     * dériverait une racine classique et, comme iOS ferait pareil, tout
     * marcherait — en n'ayant rien apporté.
     */
    @Test
    fun la_moitie_post_quantique_change_vraiment_la_racine() {
        val f = fixture()
        val ss = b64(f.getString("sharedSecret"))
        val pq = b64(f.getString("pqSecret"))
        assertNotEquals(
            "le secret post-quantique est ignoré: l'hybride ne protège de rien",
            Base64.getEncoder().encodeToString(DoubleRatchet.deriveRootAndChain(ss, null).first),
            Base64.getEncoder().encodeToString(DoubleRatchet.deriveRootAndChain(ss, pq).first)
        )
    }

    /**
     * Une rétrogradation doit échouer, pas réussir plus faiblement.
     *
     * On retire `pqCiphertext` d'un message authentique d'iOS: l'AAD change,
     * donc l'ouverture doit échouer. C'est toute la raison d'épingler le mode
     * dans la session plutôt que de le lire message par message.
     */
    @Test
    fun retirer_le_chiffre_fait_echouer_l_ouverture() {
        val f = fixture()
        val s = session(f, asInitiator = false)
        val original = IOSRatchetMessage.fromJSON(
            JSONObject(String(b64(f.getJSONArray("messages").getString(0)), Charsets.UTF_8))
        )
        val strippé = IOSRatchetMessage(
            original.header.copy(securityMode = null, pqCiphertext = null),
            original.ciphertext
        )
        var refusé = false
        try {
            ratchet.decrypt(s, strippé)
        } catch (t: Throwable) {
            refusé = true
        }
        assertTrue("une rétrogradation est passée inaperçue", refusé)
    }

    /** Et le chiffré transporté est bien celui dont notre graine rend le secret. */
    @Test
    fun le_chiffre_porte_bien_le_secret_annonce() {
        val f = fixture()
        val ss = PostQuantumKEM.decapsulate(
            b64(f.getString("pqCiphertext")), b64(f.getString("pqSeed"))
        )
        assertArrayEquals(
            "le chiffré de l'en-tête ne rend pas le secret utilisé pour la racine",
            b64(f.getString("pqSecret")), ss
        )
    }
}
