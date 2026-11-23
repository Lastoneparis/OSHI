package com.oshi.messenger

import com.oshi.messenger.network.encryption.DoubleRatchet
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

/**
 * La racine et la chaîne initiale d'une session doivent être identiques à celles
 * d'iOS, dans les deux modes.
 *
 * C'est la dernière des trois pièces à prouver. Le KEM peut être exact
 * (`XWingInteropTest`) et l'AAD exacte (`RatchetAADInteropTest`): si les deux
 * côtés ne dérivent pas la même racine de la même matière, aucun message ne
 * passe — et, comme toujours ici, sans qu'aucune erreur de format ne soit
 * signalée.
 *
 * LES CHAÎNES INFO SONT DANS LE FICHIER DE VECTEURS, et ce test compare la
 * CONSTANTE DE PRODUCTION à celle qu'iOS a écrite. Changer l'une des deux fait
 * donc tomber le test, au lieu de laisser les deux clients diverger en silence.
 */
class RatchetDerivationInteropTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun vectors(): JSONObject {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("derivation_ios_vectors.json")
        assertNotNull("vecteurs absents — relancer XWingVectorDumpTests", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    /** Les chaînes info elles-mêmes, avant tout calcul. */
    @Test
    fun `les chaines info sont celles d ios`() {
        val v = vectors()
        assertEquals(v.getString("infoRootClassical"), DoubleRatchet.INFO_ROOT_KEY)
        assertEquals(v.getString("infoChainClassical"), DoubleRatchet.INFO_INITIAL_CHAIN)
        assertEquals(v.getString("infoRootHybrid"), DoubleRatchet.INFO_ROOT_KEY_HYBRID)
        assertEquals(v.getString("infoChainHybrid"), DoubleRatchet.INFO_INITIAL_CHAIN_HYBRID)
    }

    /**
     * ⚠️ Le chemin CLASSIQUE, d'abord. Il doit rester exactement ce qu'il est:
     * c'est ce que dérive chaque session déjà en cours.
     */
    @Test
    fun `la derivation classique est inchangee`() {
        val v = vectors()
        val (root, chain) = DoubleRatchet.deriveRootAndChain(
            b64(v.getString("sharedSecret")), null
        )
        assertArrayEquals("racine classique divergente", b64(v.getString("rootClassical")), root)
        assertArrayEquals("chaîne classique divergente", b64(v.getString("chainClassical")), chain)
    }

    @Test
    fun `la derivation hybride correspond a ios`() {
        val v = vectors()
        val (root, chain) = DoubleRatchet.deriveRootAndChain(
            b64(v.getString("sharedSecret")), b64(v.getString("pqSecret"))
        )
        assertArrayEquals("racine hybride divergente", b64(v.getString("rootHybrid")), root)
        assertArrayEquals("chaîne hybride divergente", b64(v.getString("chainHybrid")), chain)
    }

    /**
     * L'ORDRE de concaténation fait partie du fil. L'inverser donnerait deux
     * racines différentes et aucun message ne passerait.
     */
    @Test
    fun `l ordre de concatenation compte`() {
        val v = vectors()
        val ss = b64(v.getString("sharedSecret"))
        val pq = b64(v.getString("pqSecret"))
        assertArrayEquals(ss + pq, DoubleRatchet.sessionIkm(ss, pq))

        // Et la preuve que ce n'est pas symétrique: l'ordre inverse donne autre
        // chose que la racine qu'iOS a calculée.
        val inverse = DoubleRatchet.hkdf32(pq + ss, DoubleRatchet.INFO_ROOT_KEY_HYBRID)
        assertFalse(
            "inverser l'ordre passerait inaperçu",
            inverse.contentEquals(b64(v.getString("rootHybrid")))
        )
    }

    /**
     * Séparation de domaine: la même matière ne doit jamais produire la même clé
     * selon le mode, sinon les deux pourraient silencieusement se confondre.
     */
    @Test
    fun `les deux modes ne se confondent pas`() {
        val v = vectors()
        val ss = b64(v.getString("sharedSecret"))
        val pq = b64(v.getString("pqSecret"))
        val classique = DoubleRatchet.deriveRootAndChain(ss, null)
        val hybride = DoubleRatchet.deriveRootAndChain(ss, pq)
        assertFalse(classique.first.contentEquals(hybride.first))
        assertFalse(classique.second.contentEquals(hybride.second))
        // Et racine ≠ chaîne dans un même mode.
        assertFalse(hybride.first.contentEquals(hybride.second))
    }

    /**
     * Un secret post-quantique différent doit donner une racine différente —
     * c'est-à-dire que la moitié PQ entre RÉELLEMENT dans la dérivation et n'est
     * pas silencieusement ignorée. Sans ce test, une implémentation qui jette
     * `pqSecret` passerait tous les autres.
     */
    @Test
    fun `le secret post quantique entre vraiment dans la racine`() {
        val v = vectors()
        val ss = b64(v.getString("sharedSecret"))
        val pq = b64(v.getString("pqSecret"))
        val autre = pq.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(
            "changer le secret PQ n'a rien changé: il est ignoré",
            DoubleRatchet.deriveRootAndChain(ss, pq).first
                .contentEquals(DoubleRatchet.deriveRootAndChain(ss, autre).first)
        )
    }
}
