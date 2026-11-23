package com.oshi.messenger

import com.oshi.messenger.network.encryption.PostQuantumKEM
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Rejoue de VRAIS vecteurs X-Wing produits par CryptoKit sur un simulateur
 * iOS 26, et exige que cette implémentation rende exactement les mêmes octets.
 *
 * C'EST LE TEST QUI COMPTE, et la raison tient en une phrase: un test qui
 * comparerait mon Android à ma lecture du brouillon IETF ne prouverait rien,
 * puisque les deux peuvent être faux de la même façon. Comparer à l'implémentation
 * d'Apple, si — c'est elle qui est à l'autre bout des conversations réelles.
 *
 * CE QU'UNE ERREUR D'UN OCTET DONNERAIT. Rien ne planterait. Les deux côtés
 * dériveraient des racines différentes et tous les messages échoueraient au
 * déchiffrement, en silence et dans les deux sens, sur les seules conversations
 * ayant réussi à passer en hybride. L'audit avait nommé ça comme le pire cas; ce
 * fichier est ce qui empêche d'y arriver.
 *
 * Les vecteurs sont produits par `OSHITests/XWingVectorDumpTests.swift` et
 * copiés dans `app/src/test/resources/xwing_ios_vectors.json`.
 */
class XWingInteropTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun vectors(): JSONArray {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("xwing_ios_vectors.json")
        assertNotNull(
            "vecteurs iOS absents — relancer OSHITests/XWingVectorDumpTests puis les copier",
            stream
        )
        val doc = JSONObject(stream!!.bufferedReader().readText())
        // Les tailles viennent du fichier, pas de mes constantes: si Apple
        // changeait de dimension, le test doit s'en apercevoir au lieu de
        // comparer mes suppositions à elles-mêmes.
        assertEquals(PostQuantumKEM.ALGORITHM_IDENTIFIER, doc.getString("algorithm"))
        assertEquals(PostQuantumKEM.PUBLIC_KEY_BYTE_COUNT, doc.getInt("publicKeyByteCount"))
        assertEquals(PostQuantumKEM.CIPHERTEXT_BYTE_COUNT, doc.getInt("ciphertextByteCount"))
        assertEquals(PostQuantumKEM.SHARED_SECRET_BYTE_COUNT, doc.getInt("sharedSecretByteCount"))
        return doc.getJSONArray("vectors")
    }

    /**
     * La même graine de 32 octets doit engendrer la même clé publique de 1216
     * octets des deux côtés.
     *
     * Sans ça, un appareil qui restaure une sauvegarde publierait une clé
     * publique dont il ne possède pas la privée: les pairs chiffreraient vers
     * elle et il ne pourrait rien lire.
     */
    @Test
    fun `une graine ios engendre la meme cle publique ici`() {
        val v = vectors()
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val seed = b64(o.getString("seed"))
            val expected = b64(o.getString("publicKey"))
            val got = PostQuantumKEM.publicKey(seed)
            assertArrayEquals("vecteur $i: clé publique différente d'iOS", expected, got)
        }
    }

    /**
     * Un chiffré produit par iOS doit rendre ICI le secret qu'iOS a obtenu.
     *
     * C'est la direction iPhone → Android: sans elle, Android ne pourrait lire
     * aucun message d'une session hybride ouverte par un iPhone.
     */
    @Test
    fun `un chiffre ios rend le meme secret ici`() {
        val v = vectors()
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val ss = PostQuantumKEM.decapsulate(
                ciphertext = b64(o.getString("ciphertext")),
                seed = b64(o.getString("seed"))
            )
            assertArrayEquals(
                "vecteur $i: secret différent de celui qu'iOS a dérivé",
                b64(o.getString("sharedSecret")), ss
            )
        }
    }

    /**
     * L'autre direction: on encapsule vers la clé publique d'iOS et on écrit le
     * résultat pour que `XWingVectorDumpTests.testAndroidCiphertextDecapsulatesHere`
     * le décapsule.
     *
     * Sans ce sens-là, on n'aurait vérifié qu'une moitié. Un Android capable de
     * LIRE les chiffrés d'iOS mais dont les siens sont illisibles casserait
     * exactement une direction de chaque conversation — le pire des symptômes,
     * parce qu'il ressemble à un problème de réseau.
     */
    @Test
    fun `on produit un chiffre qu ios devra decapsuler`() {
        val v = vectors()
        val out = JSONArray()
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val pk = b64(o.getString("publicKey"))
            val enc = PostQuantumKEM.encapsulate(pk)
            assertEquals(PostQuantumKEM.CIPHERTEXT_BYTE_COUNT, enc.ciphertext.size)
            assertEquals(PostQuantumKEM.SHARED_SECRET_BYTE_COUNT, enc.sharedSecret.size)

            // Contrôle local d'abord: on doit savoir relire notre propre chiffré.
            val back = PostQuantumKEM.decapsulate(enc.ciphertext, b64(o.getString("seed")))
            assertArrayEquals("notre propre chiffré ne se relit pas", enc.sharedSecret, back)

            out.put(JSONObject().apply {
                put("seed", o.getString("seed"))
                put("ciphertext", Base64.getEncoder().encodeToString(enc.ciphertext))
                put("sharedSecret", Base64.getEncoder().encodeToString(enc.sharedSecret))
            })
        }
        val f = File(System.getProperty("java.io.tmpdir"), "oshi_xwing_from_android.json")
        f.writeText(JSONObject().put("vectors", out).toString(2))
        println("XWING_ANDROID_CIPHERTEXTS_WRITTEN ${f.absolutePath}")
    }

    // ------------------------------------------------- garde-fous de forme

    /** Une paire fraîche doit se relire elle-même. */
    @Test
    fun `une paire fraiche fait l aller retour`() {
        val kp = PostQuantumKEM.generateKeyPair()
        assertEquals(PostQuantumKEM.SEED_BYTE_COUNT, kp.seed.size)
        assertEquals(PostQuantumKEM.PUBLIC_KEY_BYTE_COUNT, kp.publicKey.size)
        val enc = PostQuantumKEM.encapsulate(kp.publicKey)
        assertArrayEquals(enc.sharedSecret, PostQuantumKEM.decapsulate(enc.ciphertext, kp.seed))
    }

    /** Deux graines différentes ne doivent pas donner la même clé. */
    @Test
    fun `deux graines donnent deux cles`() {
        val a = PostQuantumKEM.generateKeyPair()
        val b = PostQuantumKEM.generateKeyPair()
        assertTrue(!a.seed.contentEquals(b.seed))
        assertTrue(!a.publicKey.contentEquals(b.publicKey))
    }

    /**
     * Un chiffré décapsulé avec la MAUVAISE graine ne doit pas rendre le bon
     * secret. ML-KEM ne lève pas ici — il rend un secret implicitement rejeté,
     * par conception — donc l'assertion porte sur la différence, pas sur une
     * exception.
     */
    @Test
    fun `une mauvaise graine ne rend pas le bon secret`() {
        val a = PostQuantumKEM.generateKeyPair()
        val b = PostQuantumKEM.generateKeyPair()
        val enc = PostQuantumKEM.encapsulate(a.publicKey)
        val wrong = try {
            PostQuantumKEM.decapsulate(enc.ciphertext, b.seed)
        } catch (e: PostQuantumKEM.DecapsulationFailed) {
            return  // lever est acceptable aussi
        }
        assertTrue(
            "une graine étrangère a rendu le bon secret",
            !wrong.contentEquals(enc.sharedSecret)
        )
    }

    /** Les longueurs absurdes sont refusées AVANT d'atteindre BouncyCastle. */
    @Test
    fun `les longueurs absurdes sont refusees`() {
        try {
            PostQuantumKEM.encapsulate(ByteArray(10))
            fail("une clé publique de 10 octets aurait dû être refusée")
        } catch (_: PostQuantumKEM.MalformedKeyMaterial) {}

        try {
            PostQuantumKEM.decapsulate(ByteArray(10), ByteArray(32))
            fail("un chiffré de 10 octets aurait dû être refusé")
        } catch (_: PostQuantumKEM.DecapsulationFailed) {}
    }
}
