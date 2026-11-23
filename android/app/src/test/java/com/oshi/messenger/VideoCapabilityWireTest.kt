package com.oshi.messenger

import com.oshi.messenger.service.CallOfferCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'octet de capacité vidéo, et l'EMPLACEMENT où il doit tomber.
 *
 * iOS lit ces octets POSITIONNELLEMENT — emplacement 0 = OshiCodec, 1 = PCM
 * 16 kHz, 2 = codec Apple, 3 = vidéo. Android n'en écrivait que deux, donc
 * ajouter la vidéo « à la suite » l'aurait posée dans l'emplacement du codec
 * Apple, et l'iPhone aurait lu une capacité pour une autre.
 *
 * C'est le genre de faute qui ne se voit pas: rien ne plante, l'appel
 * fonctionne, et la vidéo reste simplement indisponible entre les deux
 * plateformes sans que personne sache pourquoi. D'où ces tests sur la
 * disposition elle-même plutôt que sur le seul comportement.
 *
 * ⚠️ LA RÈGLE À NE JAMAIS PERDRE: un octet ABSENT veut dire « ce pair n'a rien
 * annoncé », pas « ce pair a dit non ». Les confondre désactiverait la vidéo
 * avec tous les téléphones pas encore mis à jour.
 */
class VideoCapabilityWireTest {

    private val OSHI = 0x01.toByte()
    private val PCM16 = 0x02.toByte()
    private val APPLE = 0x04.toByte()
    private val VIDEO = 0x08.toByte()

    /** Un callId UUID: que des octets >= 0x2D, ce qui est ce qui permet le découpage. */
    private val callId = "3F2504E0-4F89-11D3-9A0C-0305E82C3301".toByteArray(Charsets.UTF_8)

    private fun offer(vararg caps: Byte): ByteArray = callId + caps

    // ------------------------------------------------------------- l'offre

    @Test
    fun `la video se lit a l emplacement 3`() {
        val p = CallOfferCapabilities.peel(offer(OSHI, PCM16, 0x00, VIDEO), callIdStart = 0)
        assertTrue(p.oshiCodec)
        assertTrue(p.pcm16k)
        assertFalse("0x00 en emplacement 2 = pas de codec Apple", p.appleCodec)
        assertEquals(true, p.video)
        assertEquals("le callId doit rester intact", callId.size, p.callIdEnd)
    }

    /**
     * ⚠️ LE test de compatibilité. Un pair d'avant cette version envoie trois
     * octets; il ne dit rien de la vidéo, et ne rien dire n'est pas dire non.
     */
    @Test
    fun `un pair a trois octets ne dit rien de la video`() {
        val p = CallOfferCapabilities.peel(offer(OSHI, PCM16, APPLE), callIdStart = 0)
        assertTrue(p.appleCodec)
        assertNull("absent veut dire « je ne sais pas », jamais « non »", p.video)
    }

    /** Un pair encore plus ancien: deux octets, comme Android avant ce travail. */
    @Test
    fun `un pair a deux octets ne dit rien non plus`() {
        assertNull(CallOfferCapabilities.peel(offer(OSHI, PCM16), callIdStart = 0).video)
    }

    /** Aucune capacité du tout: le callId ne doit pas être amputé. */
    @Test
    fun `un pair sans capacite laisse le callid entier`() {
        val p = CallOfferCapabilities.peel(callId, callIdStart = 0)
        assertEquals(callId.size, p.callIdEnd)
        assertNull(p.video)
        assertFalse(p.oshiCodec)
    }

    /**
     * Un octet PRÉSENT mais à zéro est une réponse: ce pair sait annoncer la
     * vidéo et annonce qu'il ne la fait pas. Il ne faut pas le confondre avec
     * l'absence.
     */
    @Test
    fun `un zero en emplacement 3 est un vrai non`() {
        val p = CallOfferCapabilities.peel(offer(OSHI, PCM16, 0x00, 0x00), callIdStart = 0)
        assertEquals(false, p.video)
    }

    /**
     * La raison d'être du remplissage: sans l'emplacement Apple à 0x00, la vidéo
     * tomberait en emplacement 2 et serait lue comme une capacité de codec.
     */
    @Test
    fun `sans le remplissage la video serait lue comme le codec apple`() {
        val mauvais = CallOfferCapabilities.peel(offer(OSHI, PCM16, VIDEO), callIdStart = 0)
        assertNull("elle n'atteindrait jamais l'emplacement 3", mauvais.video)
        assertFalse("et 0x08 n'est pas 0x04, donc pas de codec Apple non plus", mauvais.appleCodec)

        val bon = CallOfferCapabilities.peel(offer(OSHI, PCM16, 0x00, VIDEO), callIdStart = 0)
        assertEquals(true, bon.video)
    }

    /** L'invariant du découpage: toute valeur de capacité doit rester < 0x2D. */
    @Test
    fun `les valeurs de capacite restent sous le plancher ascii`() {
        for (flag in listOf(OSHI, PCM16, APPLE, VIDEO)) {
            assertTrue(
                "0x%02X casserait le découpage du callId".format(flag),
                (flag.toInt() and 0xFF) < 0x2D
            )
        }
    }

    // ------------------------------------------------------------ l'accept

    /**
     * La charge utile d'un accept est `[type(1)][horodatage(8)][capacités…]`.
     * iOS retire les neuf premiers octets puis lit les emplacements 0..3, donc
     * la vidéo est à l'indice ABSOLU 12.
     */
    @Test
    fun `dans un accept la video est a l indice 12`() {
        val accept = ByteArray(13)
        accept[9] = OSHI; accept[10] = PCM16; accept[11] = 0x00; accept[12] = VIDEO

        assertEquals(VIDEO, accept[12])
        assertEquals("9 octets d'en-tête + 4 emplacements", 13, accept.size)
        // Ce que fait le lecteur: présent → réponse, absent → inconnu.
        assertEquals(true, if (accept.size > 12) accept[12] == VIDEO else null)
    }

    @Test
    fun `un accept court ne dit rien de la video`() {
        val ancien = ByteArray(11)          // ce qu'Android envoyait avant
        ancien[9] = OSHI; ancien[10] = PCM16
        assertNull(if (ancien.size > 12) ancien[12] == VIDEO else null)
    }
}
