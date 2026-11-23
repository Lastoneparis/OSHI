package com.oshi.messenger

import com.oshi.messenger.network.encryption.IOSRatchetHeader
import com.oshi.messenger.network.encryption.RatchetSecurityMode
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Les données additionnelles de l'AEAD doivent être identiques à celles d'iOS,
 * octet pour octet, dans les DEUX modes.
 *
 * POURQUOI C'EST LA PIÈCE LA PLUS DANGEREUSE DE TOUT LE PORTAGE. L'AAD n'est pas
 * chiffrée: elle est AUTHENTIFIÉE. Si les deux côtés ne la composent pas de la
 * même façon, chacun chiffre correctement et aucun ne parvient à ouvrir ce que
 * l'autre envoie. Rien ne plante, rien ne journalise une erreur de format — tout
 * échoue simplement à l'authentification.
 *
 * Et il y a pire que de casser l'hybride: casser le CLASSIQUE. Ces 59 octets
 * sont ce que chaque pair déjà déployé calcule aujourd'hui. Y ajouter quoi que
 * ce soit — même un champ vide, même une clé JSON à `null` — couperait toutes
 * les conversations en cours, pas seulement les nouvelles.
 *
 * Les vecteurs viennent de la VRAIE fonction d'iOS (`encodeHeaderForAAD`), pas
 * de ma lecture de sa disposition.
 *
 * ⚠️ CE QUI N'EST PAS ICI. Les assertions sur la FORME JSON de l'en-tête vivent
 * dans `androidTest/RatchetHeaderWireTest`, pas ici: `toJSON` passe par
 * `android.util.Base64`, qui n'est qu'une souche sur la JVM et lève. Ce dépôt
 * n'utilise pas Robolectric — ses 1659 tests sont du Java pur et s'exécutent en
 * quelques secondes — et introduire Robolectric pour quatre assertions coûterait
 * cette propriété-là. Le partage est donc: les OCTETS de l'AAD ici, la forme du
 * JSON sur un appareil.
 */
class RatchetAADInteropTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun vectors(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("aad_ios_vectors.json")
        assertNotNull("vecteurs d'AAD absents — relancer XWingVectorDumpTests", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    private fun headerFrom(v: JSONObject, hybrid: Boolean) = IOSRatchetHeader(
        publicKey = b64(v.getString("publicKey")),
        messageNumber = v.getInt("messageNumber"),
        previousChainLength = v.getInt("previousChainLength"),
        isDHRatchet = v.getBoolean("isDHRatchet"),
        // L'horodatage n'entre PAS dans l'AAD des deux côtés — s'il y entrait,
        // ce test échouerait, ce qui est exactement ce qu'on veut savoir.
        timestamp = 0.0,
        sessionVersion = v.getInt("sessionVersion"),
        sessionId = v.getString("sessionId"),
        securityMode = if (hybrid) RatchetSecurityMode.HYBRID_PQ else null,
        pqCiphertext = if (hybrid) b64(v.getString("pqCiphertext")) else null
    )

    /**
     * ⚠️ LE test de non-régression. Le chemin classique doit rester exactement ce
     * qu'il est: 59 octets, les mêmes qu'iOS produit aujourd'hui.
     */
    @Test
    fun `l aad classique est inchangee`() {
        val v = vectors()
        val expected = b64(v.getString("aadClassical"))
        val got = com.oshi.messenger.network.encryption.DoubleRatchet
            .encodeHeaderForAAD(headerFrom(v, hybrid = false))
        assertEquals("la longueur de l'AAD classique a changé", expected.size, got.size)
        assertArrayEquals("l'AAD classique diverge d'iOS", expected, got)
    }

    /** Et l'hybride: le même préfixe, puis « hybridPQ », puis le chiffré. */
    @Test
    fun `l aad hybride correspond a ios`() {
        val v = vectors()
        val expected = b64(v.getString("aadHybrid"))
        val got = com.oshi.messenger.network.encryption.DoubleRatchet
            .encodeHeaderForAAD(headerFrom(v, hybrid = true))
        assertArrayEquals("l'AAD hybride diverge d'iOS", expected, got)
    }

    /**
     * La structure, énoncée explicitement: l'hybride PROLONGE le classique, il
     * ne le réécrit pas. C'est ce qui garantit qu'ajouter le post-quantique ne
     * peut pas modifier ce qu'un pair classique calcule.
     */
    @Test
    fun `l hybride prolonge le classique`() {
        val v = vectors()
        val classical = b64(v.getString("aadClassical"))
        val hybrid = b64(v.getString("aadHybrid"))
        val ct = b64(v.getString("pqCiphertext"))

        assertArrayEquals(
            "l'hybride doit commencer par exactement l'AAD classique",
            classical, hybrid.copyOfRange(0, classical.size)
        )
        val tail = hybrid.copyOfRange(classical.size, hybrid.size)
        assertArrayEquals(
            "la queue doit être « hybridPQ » puis le chiffré",
            RatchetSecurityMode.HYBRID_PQ.toByteArray(Charsets.UTF_8) + ct, tail
        )
    }

    /**
     * Retirer le chiffré doit CHANGER l'AAD.
     *
     * C'est la propriété entière de l'épinglage: un attaquant qui enlève
     * `pqCiphertext` pour rétrograder la session obtient un échec
     * d'authentification, pas une session plus faible et silencieuse.
     */
    @Test
    fun `retirer le chiffre change l aad`() {
        val v = vectors()
        val avec = com.oshi.messenger.network.encryption.DoubleRatchet
            .encodeHeaderForAAD(headerFrom(v, hybrid = true))
        val sans = com.oshi.messenger.network.encryption.DoubleRatchet
            .encodeHeaderForAAD(headerFrom(v, hybrid = false))
        assertFalse("une rétrogradation passerait inaperçue", avec.contentEquals(sans))
    }
}
