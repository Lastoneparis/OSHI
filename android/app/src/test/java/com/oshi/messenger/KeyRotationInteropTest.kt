package com.oshi.messenger

import com.oshi.messenger.network.encryption.KeyRotationAuthenticator
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Le tag d'autorisation de rotation doit être identique à celui d'iOS, octet
 * pour octet.
 *
 * L'ENJEU EST SYMÉTRIQUE ET PEU INTUITIF. Si les deux calculs divergent, une
 * rotation LÉGITIME venue d'un iPhone est refusée par Android — et refusée avec
 * un message qui dit « tentative de substitution de clé ». On accuserait
 * l'utilisateur d'être attaqué à cause d'une différence d'implémentation.
 *
 * Les vecteurs viennent de `OSHITests/KeyRotationVectorDumpTests.swift`.
 */
class KeyRotationInteropTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun vectors(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("rotation_ios_vectors.json")
        assertNotNull("vecteurs absents — relancer KeyRotationVectorDumpTests", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    /** La chaîne de séparation de domaine, avant tout calcul. */
    @Test
    fun `la chaine info est celle d ios`() {
        assertEquals(vectors().getString("info"), KeyRotationAuthenticator.INFO)
    }

    /**
     * Le message canonique: c'est là que se logent les erreurs d'ordre et de
     * boutisme, et elles sont invisibles dans le tag final.
     */
    @Test
    fun `le message canonique est identique`() {
        val v = vectors()
        assertArrayEquals(
            "le message authentifié diverge d'iOS",
            b64(v.getString("canonicalMessage")),
            KeyRotationAuthenticator.canonicalMessage(
                v.getString("oldPublicKey"), v.getString("newPublicKey"),
                v.getString("timestampMillis").toLong(), v.getInt("rotationNumber")
            )
        )
    }

    @Test
    fun `la cle de mac est identique`() {
        val v = vectors()
        assertArrayEquals(
            b64(v.getString("macKey")),
            KeyRotationAuthenticator.macKey(b64(v.getString("sharedSecret")))
        )
    }

    /** ⚠️ LE test: le tag lui-même. */
    @Test
    fun `le tag est identique a celui d ios`() {
        val v = vectors()
        val tag = KeyRotationAuthenticator.tag(
            b64(v.getString("sharedSecret")),
            v.getString("oldPublicKey"), v.getString("newPublicKey"),
            v.getString("timestampMillis").toLong(), v.getInt("rotationNumber")
        )
        assertArrayEquals("le tag diverge d'iOS", b64(v.getString("tag")), tag)

        // Et la vérification accepte bien ce tag.
        assertTrue(KeyRotationAuthenticator.isValidTag(
            b64(v.getString("tag")), b64(v.getString("sharedSecret")),
            v.getString("oldPublicKey"), v.getString("newPublicKey"),
            v.getString("timestampMillis").toLong(), v.getInt("rotationNumber")
        ))
    }

    /**
     * L'octet nul qui sépare les deux clés. Sans lui, `("ab","c")` et
     * `("a","bc")` donneraient le même message — et un attaquant pourrait
     * déplacer la frontière entre ancienne et nouvelle clé.
     */
    @Test
    fun `le separateur nul empeche l ambiguite`() {
        val v = vectors()
        val ss = b64(v.getString("sharedSecret"))
        val ts = v.getString("timestampMillis").toLong()
        val n = v.getInt("rotationNumber")

        val abc = KeyRotationAuthenticator.tag(ss, "ab", "c", ts, n)
        val a_bc = KeyRotationAuthenticator.tag(ss, "a", "bc", ts, n)

        assertArrayEquals(b64(v.getString("tagAmbiguousAB_C")), abc)
        assertArrayEquals(b64(v.getString("tagAmbiguousA_BC")), a_bc)
        assertFalse("la frontière entre les deux clés n'est pas liée", abc.contentEquals(a_bc))
    }

    /** Changer un seul champ doit changer le tag — sinon il n'est pas lié. */
    @Test
    fun `chaque champ entre dans le tag`() {
        val v = vectors()
        val ss = b64(v.getString("sharedSecret"))
        val old = v.getString("oldPublicKey"); val new = v.getString("newPublicKey")
        val ts = v.getString("timestampMillis").toLong(); val n = v.getInt("rotationNumber")
        val ref = KeyRotationAuthenticator.tag(ss, old, new, ts, n)

        val variantes = mapOf(
            "ancienne clé" to KeyRotationAuthenticator.tag(ss, old + "x", new, ts, n),
            "nouvelle clé" to KeyRotationAuthenticator.tag(ss, old, new + "x", ts, n),
            "horodatage" to KeyRotationAuthenticator.tag(ss, old, new, ts + 1, n),
            "numéro" to KeyRotationAuthenticator.tag(ss, old, new, ts, n + 1),
            "secret" to KeyRotationAuthenticator.tag(
                ss.copyOf().also { it[0] = (it[0] + 1).toByte() }, old, new, ts, n)
        )
        for ((quoi, t) in variantes) {
            assertFalse("$quoi n'entre pas dans le tag", t.contentEquals(ref))
        }
    }

    /** Fraîcheur: le nominal passe, le rejeu et le hors-fenêtre non. */
    @Test
    fun `la fraicheur refuse les rejeux`() {
        val now = 1_757_000_000_000L
        KeyRotationAuthenticator.checkFreshness(now, 7, 6, now)   // ne doit pas lever

        for ((quoi, bloc) in listOf<Pair<String, () -> Unit>>(
            "numéro déjà vu" to { KeyRotationAuthenticator.checkFreshness(now, 6, 6, now) },
            "paquet trop vieux" to {
                KeyRotationAuthenticator.checkFreshness(now - 30L * 24 * 3600 * 1000, 8, 6, now)
            },
            "paquet du futur" to {
                KeyRotationAuthenticator.checkFreshness(now + 30L * 24 * 3600 * 1000, 8, 6, now)
            }
        )) {
            try {
                bloc()
                fail("$quoi aurait dû être refusé")
            } catch (_: KeyRotationAuthenticator.Failure) {
            }
        }
    }
}
