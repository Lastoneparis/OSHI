package com.oshi.messenger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.network.v2.SignedRemoteConfig
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * La configuration signée doit être vérifiée IDENTIQUEMENT sur les deux clients.
 *
 * L'ENJEU EST SYMÉTRIQUE. Si les deux vérifications divergent, une configuration
 * LÉGITIME de l'opérateur est refusée par l'un des deux clients — qui retombe
 * alors sur son défaut, c'est-à-dire exactement la rétrogradation que tout ceci
 * existe pour empêcher. Se tromper « en sécurité » n'est pas plus sûr ici.
 *
 * Instrumenté et non JVM: la vérification passe par `android.util.Base64`, une
 * souche qui lève sous la JVM.
 *
 * Les vecteurs viennent de `OSHITests/SignedRemoteConfigTests.swift`.
 */
@RunWith(AndroidJUnit4::class)
class SignedConfigInteropTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun vectors(): JSONObject {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return JSONObject(
            ctx.assets.open("config_ios_vectors.json").bufferedReader().readText()
        )
    }

    private fun envelope(payloadB64: String, version: Long, sigB64: String?): ByteArray {
        val o = JSONObject().put("payload", payloadB64).put("config_version", version)
        if (sigB64 != null) o.put("sig", sigB64)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** Les octets signés: c'est là que se logent les erreurs de boutisme. */
    @Test
    fun les_octets_signes_sont_identiques() {
        val v = vectors()
        assertArrayEquals(
            "les octets couverts par la signature divergent d'iOS",
            b64(v.getString("signedBytes")),
            SignedRemoteConfig.signedBytes(
                b64(v.getString("payload")), v.getLong("configVersion")
            )
        )
    }

    /** ⚠️ Une signature produite par iOS doit être acceptée ICI. */
    @Test
    fun une_signature_ios_est_acceptee_ici() {
        val v = vectors()
        assertTrue(
            "une configuration légitime signée par iOS est refusée ici",
            SignedRemoteConfig.verifySignature(
                b64(v.getString("payload")), v.getLong("configVersion"),
                b64(v.getString("signature")), v.getString("publicKey")
            )
        )
    }

    /** Et la politique complète l'accepte, avec la bonne version. */
    @Test
    fun la_politique_accepte_une_enveloppe_signee_plus_recente() {
        val v = vectors()
        val r = SignedRemoteConfig.accept(
            envelope(v.getString("payload"), v.getLong("configVersion"), v.getString("signature")),
            lastAcceptedVersion = 4L,
            publicKeyBase64 = v.getString("publicKey")
        )
        assertArrayEquals(b64(v.getString("payload")), r.payload)
        assertEquals(v.getLong("configVersion"), r.version)
    }

    /** ⚠️ Le retour en arrière: une configuration AUTHENTIQUE mais plus vieille. */
    @Test
    fun une_configuration_authentique_plus_ancienne_est_refusee() {
        val v = vectors()
        try {
            SignedRemoteConfig.accept(
                envelope(v.getString("payload"), v.getLong("configVersion"), v.getString("signature")),
                lastAcceptedVersion = 99L,
                publicKeyBase64 = v.getString("publicKey")
            )
            fail("un rejeu de configuration est passé")
        } catch (e: SignedRemoteConfig.Rejection.RolledBack) {
            assertEquals(99L, e.seen)
        }
    }

    /** La version est COUVERTE: on ne remonte pas le numéro d'une vieille config. */
    @Test
    fun remonter_le_numero_invalide_la_signature() {
        val v = vectors()
        try {
            SignedRemoteConfig.accept(
                envelope(v.getString("payload"), 99L, v.getString("signature")),
                lastAcceptedVersion = 0L,
                publicKeyBase64 = v.getString("publicKey")
            )
            fail("une version falsifiée est passée")
        } catch (_: SignedRemoteConfig.Rejection.BadSignature) {
        }
    }

    /** La charge utile aussi: on ne glisse pas une autre config sous la signature. */
    @Test
    fun changer_la_charge_utile_invalide_la_signature() {
        val v = vectors()
        val autre = Base64.getEncoder().encodeToString(
            """{"v2_enabled":false,"rollout_percent":0,"min_build":0}""".toByteArray()
        )
        try {
            SignedRemoteConfig.accept(
                envelope(autre, v.getLong("configVersion"), v.getString("signature")),
                lastAcceptedVersion = 0L,
                publicKeyBase64 = v.getString("publicKey")
            )
            fail("une charge utile substituée est passée")
        } catch (_: SignedRemoteConfig.Rejection.BadSignature) {
        }
    }

    /** Une enveloppe dont on a RETIRÉ la signature est refusée sans condition. */
    @Test
    fun une_signature_retiree_est_refusee_meme_sans_exigence() {
        val v = vectors()
        try {
            SignedRemoteConfig.accept(
                envelope(v.getString("payload"), v.getLong("configVersion"), null),
                lastAcceptedVersion = 0L,
                publicKeyBase64 = v.getString("publicKey"),
                require = false
            )
            fail("une enveloppe sans signature est passée")
        } catch (_: SignedRemoteConfig.Rejection.Unsigned) {
        }
    }

    /**
     * La forme HÉRITÉE passe tant qu'on n'exige rien — sinon livrer ce code
     * ferait ignorer la configuration vivante et rétrograderait tout le monde.
     */
    @Test
    fun la_forme_heritee_passe_encore() {
        val v = vectors()
        val brut = b64(v.getString("payload"))
        val r = SignedRemoteConfig.accept(brut, lastAcceptedVersion = 2L,
            publicKeyBase64 = v.getString("publicKey"), require = false)
        assertArrayEquals(brut, r.payload)
        assertEquals("une réponse héritée ne doit pas faire avancer la version", 2L, r.version)
    }

    /** Et elle est refusée dès qu'on exige des signatures. */
    @Test
    fun la_forme_heritee_est_refusee_quand_on_exige() {
        val v = vectors()
        try {
            SignedRemoteConfig.accept(b64(v.getString("payload")), 0L,
                v.getString("publicKey"), require = true)
            fail("une réponse héritée est passée alors qu'on exigeait une signature")
        } catch (_: SignedRemoteConfig.Rejection.Unsigned) {
        }
    }

    /**
     * ⚠️ LE test de bout en bout: une enveloppe produite par l'OUTIL RÉEL
     * (`tools/config/sign_config.py`), vérifiée avec la clé RÉELLEMENT épinglée
     * dans ce binaire.
     *
     * Les autres tests utilisent une clé de test; celui-ci prouve que la chaîne
     * complète — génération de la paire, signature côté opérateur, épinglage
     * dans le client — se referme. Si l'outil et le client divergeaient, toute
     * configuration légitime serait refusée et chaque appareil retomberait sur
     * son défaut, c'est-à-dire la rétrogradation qu'on veut empêcher.
     */
    @Test
    fun une_enveloppe_du_vrai_signataire_est_acceptee() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val body = ctx.assets.open("config_real_envelope.json").readBytes()
        val r = SignedRemoteConfig.accept(body, lastAcceptedVersion = 0L)
        assertEquals(3L, r.version)
        assertTrue(
            "la charge utile ne contient pas la configuration attendue",
            String(r.payload, Charsets.UTF_8).contains("v2_enabled")
        )
    }

    /** Un corps illisible n'est pas une configuration. */
    @Test
    fun un_corps_illisible_est_refuse() {
        try {
            SignedRemoteConfig.accept("pas du json".toByteArray(), 0L, "", require = false)
            fail("du texte quelconque est passé")
        } catch (_: SignedRemoteConfig.Rejection) {
        }
    }
}
