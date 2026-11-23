package com.oshi.messenger

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oshi.messenger.network.encryption.DoubleRatchet
import com.oshi.messenger.network.encryption.IOSRatchetHeader
import com.oshi.messenger.network.encryption.IOSRatchetMessage
import com.oshi.messenger.network.encryption.RatchetSecurityMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64 as JvmBase64

/**
 * La FORME de l'en-tête sur le fil, une fois sérialisé.
 *
 * Instrumenté et non JVM parce que `toJSON` passe par `android.util.Base64`,
 * qui n'est qu'une souche sur la JVM et lève. Le dépôt n'utilise pas
 * Robolectric — ses tests unitaires sont du Java pur et tournent en quelques
 * secondes — et l'introduire pour ces quelques assertions coûterait cette
 * propriété. Les OCTETS de l'AAD sont vérifiés côté JVM dans
 * `RatchetAADInteropTest`; ce fichier ne couvre que la sérialisation.
 *
 * ⚠️ CE QUI EST EN JEU. Un en-tête CLASSIQUE ne doit porter aucune des deux
 * nouvelles clés. Écrire `securityMode: null`, ou une chaîne vide, changerait
 * les octets que chaque pair déjà déployé sait lire aujourd'hui — et couperait
 * les conversations EN COURS, pas seulement les nouvelles. « Absent » et
 * « présent et vide » ne sont pas la même chose sur un fil.
 */
@RunWith(AndroidJUnit4::class)
class RatchetHeaderWireTest {

    private val pk = ByteArray(32) { it.toByte() }
    private val ct = ByteArray(1120) { (it % 251).toByte() }

    private fun header(hybrid: Boolean) = IOSRatchetHeader(
        publicKey = pk,
        messageNumber = 7,
        previousChainLength = 3,
        isDHRatchet = true,
        timestamp = 0.0,
        sessionVersion = 3,
        sessionId = "vector-session",
        securityMode = if (hybrid) RatchetSecurityMode.HYBRID_PQ else null,
        pqCiphertext = if (hybrid) ct else null
    )

    @Test
    fun un_en_tete_classique_ne_porte_pas_les_nouvelles_cles() {
        val json = IOSRatchetMessage(header(false), ByteArray(16)).toJSON()
            .getJSONObject("header")

        assertFalse("securityMode ne doit pas apparaître", json.has("securityMode"))
        assertFalse("pqCiphertext ne doit pas apparaître", json.has("pqCiphertext"))
        for (k in listOf("publicKey", "messageNumber", "previousChainLength",
                         "isDHRatchet", "timestamp", "sessionVersion", "sessionId")) {
            assertTrue("$k a disparu de l'en-tête", json.has(k))
        }
        // Et exactement sept clés: en ajouter une changerait ce que lit un pair.
        assertEquals(7, json.length())
    }

    @Test
    fun un_en_tete_hybride_porte_les_deux_cles() {
        val json = IOSRatchetMessage(header(true), ByteArray(16)).toJSON()
            .getJSONObject("header")
        assertEquals(RatchetSecurityMode.HYBRID_PQ, json.getString("securityMode"))
        assertArrayEquals(
            ct,
            android.util.Base64.decode(json.getString("pqCiphertext"), android.util.Base64.NO_WRAP)
        )
    }

    /** Le base64 doit être STANDARD et rembourré, comme Swift l'écrit. */
    @Test
    fun le_base64_est_celui_de_swift() {
        val json = IOSRatchetMessage(header(true), ByteArray(16)).toJSON()
            .getJSONObject("header")
        val s = json.getString("pqCiphertext")
        assertFalse("base64url casserait la lecture côté Swift", s.contains('-') || s.contains('_'))
        assertArrayEquals(ct, JvmBase64.getDecoder().decode(s))
    }

    @Test
    fun l_aller_retour_conserve_le_mode_et_le_chiffre() {
        val original = IOSRatchetMessage(header(true), ByteArray(16) { it.toByte() })
        val back = IOSRatchetMessage.fromJSON(original.toJSON())
        assertEquals(RatchetSecurityMode.HYBRID_PQ, back.header.securityMode)
        assertArrayEquals(original.header.pqCiphertext, back.header.pqCiphertext)
        // Et surtout: la même AAD de part et d'autre de l'aller-retour.
        assertArrayEquals(
            DoubleRatchet.encodeHeaderForAAD(original.header),
            DoubleRatchet.encodeHeaderForAAD(back.header)
        )
    }

    /** Un en-tête d'un pair classique se relit sans mode et sans chiffré. */
    @Test
    fun un_en_tete_d_un_pair_classique_se_relit() {
        val back = IOSRatchetMessage.fromJSON(
            IOSRatchetMessage(header(false), ByteArray(8)).toJSON()
        )
        assertNull(back.header.securityMode)
        assertNull(back.header.pqCiphertext)
        assertEquals(
            RatchetSecurityMode.CLASSICAL,
            RatchetSecurityMode.normalized(back.header.securityMode)
        )
    }
}
