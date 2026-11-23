package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La négociation audio→vidéo n'avait AUCUN test, sur aucune des deux
 * plateformes: pas un seul test n'envoyait un 0x0E. C'est dans ce vide qu'ont
 * vécu cinq défauts, dont « la personne qui refuse se met à diffuser sa
 * caméra ».
 *
 * POURQUOI UN MODÈLE ET PAS `EnhancedCallManager`. Le vrai gestionnaire tient
 * des sockets UDP vivantes, Telecom, la caméra et un `CoroutineScope` de
 * service: rien de tout ça ne se pilote depuis un test JVM. Ce que ce modèle
 * reproduit, ce sont les RÈGLES DE DÉCISION — celles que l'on vient de
 * corriger, et qui sont du calcul pur.
 *
 * Le jumeau iOS est `OSHITests/VideoUpgradeNegotiationTests.swift`, avec les
 * mêmes cas dans le même ordre: c'est ce qui rend une divergence entre les deux
 * clients visible à la relecture plutôt qu'en appel réel.
 *
 * Ce que ce modèle ne prouve PAS, et qu'il ne faut pas lui faire dire: que les
 * octets partent vraiment sur le réseau, que Telecom suit, que la caméra
 * démarre. Ça, il faut deux téléphones.
 */
class VideoUpgradeNegotiationTest {

    /** Les opcodes réellement sur le fil — iOS lit exactement ces octets. */
    private enum class Code(val raw: Byte) {
        REQUEST(0x01),
        ACCEPT(0x02),
        DECLINE(0x03),
        STOP(0x04),
        ACCEPT_WATCH(0x05),
        CAMERA_ON(0x06),
        CAMERA_OFF(0x07)
    }

    /**
     * Le modèle de référence, tenu par les tests. Il porte les mêmes drapeaux
     * que `EnhancedCallManager`, avec les mêmes noms, pour qu'une divergence
     * saute aux yeux.
     */
    private class Model {
        var isVideoCall = false
        var isAwaitingVideoUpgrade = false
        var pendingVideoUpgrade = false
        var shareOwnVideo = true
        var isRemoteCameraPaused = false
        var declinedReason: String? = null
        var peerSupportsVideo: Boolean? = null
        var cameraAccessDenied = false

        val sent = mutableListOf<Code>()
        var requesterTimerArmed = false
        var calleeTimerArmed = false

        // --- actions locales ---

        fun tapVideo() {
            if (peerSupportsVideo == false) {
                declinedReason = "Their app can't do video calls"; return
            }
            if (cameraAccessDenied) {
                declinedReason = "Camera access is off for OSHI"; return
            }
            declinedReason = null
            isAwaitingVideoUpgrade = true
            shareOwnVideo = true
            isRemoteCameraPaused = false
            requesterTimerArmed = true
            sent += Code.REQUEST
        }

        fun tapAccept(shareCamera: Boolean = true) {
            var share = shareCamera
            if (share && cameraAccessDenied) {
                share = false
                declinedReason = "Camera access is off — joining as watch only"
            }
            requesterTimerArmed = false
            calleeTimerArmed = false
            pendingVideoUpgrade = false
            isAwaitingVideoUpgrade = false
            shareOwnVideo = share
            isRemoteCameraPaused = false
            isVideoCall = true
            sent += if (share) Code.ACCEPT else Code.ACCEPT_WATCH
        }

        fun tapDecline() {
            requesterTimerArmed = false
            calleeTimerArmed = false
            pendingVideoUpgrade = false
            isAwaitingVideoUpgrade = false
            sent += Code.DECLINE
        }

        fun calleeTimeoutFires() {
            if (pendingVideoUpgrade) tapDecline()
        }

        fun requesterTimeoutFires() {
            if (!isAwaitingVideoUpgrade) return
            isAwaitingVideoUpgrade = false
            requesterTimerArmed = false
            declinedReason = if (peerSupportsVideo == null)
                "No answer — their version may not support video" else "No answer"
        }

        fun cameraFailed(reason: String) {
            shareOwnVideo = false
            declinedReason = reason
            sent += Code.CAMERA_OFF
        }

        // --- réception ---

        fun receive(code: Code) {
            when (code) {
                Code.REQUEST -> {
                    if (isVideoCall) return
                    pendingVideoUpgrade = true
                    calleeTimerArmed = true
                }
                Code.ACCEPT, Code.ACCEPT_WATCH -> {
                    // LA règle corrigée: un ACCEPT non sollicité n'allume rien.
                    if (!isAwaitingVideoUpgrade) return
                    isAwaitingVideoUpgrade = false
                    requesterTimerArmed = false
                    isRemoteCameraPaused = (code == Code.ACCEPT_WATCH)
                    isVideoCall = true
                }
                Code.DECLINE -> {
                    isAwaitingVideoUpgrade = false
                    requesterTimerArmed = false
                    declinedReason = "Peer declined"
                }
                Code.STOP -> {
                    isVideoCall = false
                    shareOwnVideo = true
                    isRemoteCameraPaused = false
                }
                Code.CAMERA_ON -> isRemoteCameraPaused = false
                Code.CAMERA_OFF -> isRemoteCameraPaused = true
            }
        }

        /** Des trames vidéo arrivent alors qu'on se croit en audio. */
        fun receiveVideoFrame() {
            if (isVideoCall) return
            requesterTimerArmed = false
            calleeTimerArmed = false
            pendingVideoUpgrade = false
            isAwaitingVideoUpgrade = false
            declinedReason = null
            shareOwnVideo = false          // réception seule: on ne diffuse pas
            isRemoteCameraPaused = false
            isVideoCall = true
        }

        fun cleanup() {
            isVideoCall = false
            pendingVideoUpgrade = false
            isAwaitingVideoUpgrade = false
            declinedReason = null
            isRemoteCameraPaused = false
            shareOwnVideo = true
            requesterTimerArmed = false
            calleeTimerArmed = false
        }
    }

    // ---------------------------------------------------------------- ACCEPT

    /**
     * LE défaut signalé: « l'un accepte la vidéo et pas l'autre ». Les deux
     * appuient sur Vidéo, B refuse — et l'ACCEPT de A arrivait ensuite allumer
     * la caméra de B, qui venait pourtant de refuser.
     */
    @Test
    fun `refuser dans un croisement n allume pas notre camera`() {
        val b = Model()
        b.tapVideo()
        b.receive(Code.REQUEST)
        assertTrue(b.pendingVideoUpgrade)

        b.tapDecline()
        assertFalse(
            "refuser doit annuler NOTRE demande en vol, sinon leur ACCEPT nous rattrape",
            b.isAwaitingVideoUpgrade
        )

        b.receive(Code.ACCEPT)
        assertFalse("B vient de refuser: sa caméra ne doit pas s'allumer", b.isVideoCall)
    }

    @Test
    fun `un accept en retard apres le delai est ignore`() {
        val a = Model()
        a.tapVideo()
        a.requesterTimeoutFires()
        assertNotNull(a.declinedReason)

        a.receive(Code.ACCEPT)
        assertFalse(
            "on nous a dit « pas de réponse »: un ACCEPT en retard ne doit pas nous tirer en vidéo",
            a.isVideoCall
        )
    }

    @Test
    fun `les accept retransmis sont inoffensifs`() {
        val a = Model()
        a.tapVideo()
        a.receive(Code.ACCEPT)
        assertTrue(a.isVideoCall)
        assertFalse(a.isRemoteCameraPaused)

        a.receive(Code.ACCEPT)        // 2e envoi
        a.receive(Code.ACCEPT_WATCH)  // 3e envoi, même si le code diffère
        assertTrue(a.isVideoCall)
        assertFalse("un doublon ne doit pas réécrire l'état négocié", a.isRemoteCameraPaused)
    }

    @Test
    fun `le chemin normal marche toujours`() {
        val a = Model(); val b = Model()
        a.tapVideo()
        b.receive(Code.REQUEST)
        b.tapAccept()
        a.receive(Code.ACCEPT)

        assertTrue(a.isVideoCall); assertTrue(b.isVideoCall)
        assertTrue(a.shareOwnVideo); assertTrue(b.shareOwnVideo)
    }

    @Test
    fun `regarder seulement laisse le demandeur sur un cache`() {
        val a = Model(); val b = Model()
        a.tapVideo()
        b.receive(Code.REQUEST)
        b.tapAccept(shareCamera = false)
        a.receive(Code.ACCEPT_WATCH)

        assertTrue(a.isVideoCall)
        assertTrue("B ne diffuse pas: A doit voir le cache", a.isRemoteCameraPaused)
        assertFalse(b.shareOwnVideo)
    }

    // ---------------------------------------------------------- délai callee

    @Test
    fun `le destinataire a un delai et previent le pair`() {
        val b = Model()
        b.receive(Code.REQUEST)
        assertTrue("la demande reçue doit armer un délai", b.calleeTimerArmed)

        b.calleeTimeoutFires()
        assertFalse("le dialogue modal doit disparaître", b.pendingVideoUpgrade)
        assertEquals(
            "se taire laisserait le demandeur ignorer si sa demande est morte en route",
            Code.DECLINE, b.sent.last()
        )
    }

    @Test
    fun `le delai du destinataire ferme la fenetre de l accept tardif`() {
        val a = Model(); val b = Model()
        a.tapVideo()
        b.receive(Code.REQUEST)
        b.calleeTimeoutFires()
        a.receive(Code.DECLINE)

        assertFalse(a.isAwaitingVideoUpgrade)
        b.calleeTimeoutFires()
        assertFalse(b.isVideoCall)
    }

    // ------------------------------------------------------------ rattrapage

    /**
     * L'ACCEPT s'est perdu. B diffuse, A est resté en audio — et, avant, rien
     * ne pouvait jamais les remettre d'accord.
     */
    @Test
    fun `des trames video rattrapent un appel coupe en deux`() {
        val a = Model()
        a.tapVideo()
        a.requesterTimeoutFires()
        assertFalse(a.isVideoCall)

        a.receiveVideoFrame()
        assertTrue("des trames vidéo prouvent que le pair est en vidéo", a.isVideoCall)
        assertFalse(
            "suivre le pair ne doit jamais vouloir dire diffuser sans l'avoir demandé",
            a.shareOwnVideo
        )
        assertNull("le message « pas de réponse » n'a plus lieu d'être", a.declinedReason)
    }

    @Test
    fun `les trames ne derangent pas un appel deja en video`() {
        val a = Model()
        a.tapVideo()
        a.receive(Code.ACCEPT)
        assertTrue(a.shareOwnVideo)

        a.receiveVideoFrame()
        assertTrue("on ne doit pas couper notre caméra en cours de route", a.shareOwnVideo)
    }

    // --------------------------------------------------------------- caméra

    @Test
    fun `demander sans acces camera echoue tout de suite`() {
        val a = Model()
        a.cameraAccessDenied = true
        a.tapVideo()

        assertFalse("pas de rond qui tourne quinze secondes", a.isAwaitingVideoUpgrade)
        assertTrue("rien ne part sur le fil", a.sent.isEmpty())
        assertNotNull("et on DIT pourquoi", a.declinedReason)
    }

    @Test
    fun `accepter sans camera rejoint en reception seule`() {
        val b = Model()
        b.cameraAccessDenied = true
        b.receive(Code.REQUEST)
        b.tapAccept(shareCamera = true)

        assertTrue("on peut toujours les VOIR", b.isVideoCall)
        assertFalse(b.shareOwnVideo)
        assertEquals(
            "le pair doit savoir qu'il n'aura pas nos trames",
            Code.ACCEPT_WATCH, b.sent.last()
        )
        assertNotNull(b.declinedReason)
    }

    @Test
    fun `un echec camera previent le pair`() {
        val b = Model()
        b.receive(Code.REQUEST)
        b.tapAccept()
        b.cameraFailed("Camera permission denied")

        assertEquals(
            "sans ça le pair fixe un cache « en direct » qui n'arrivera jamais",
            Code.CAMERA_OFF, b.sent.last()
        )
        assertNotNull(b.declinedReason)
    }

    @Test
    fun `camera off bascule le cache du pair`() {
        val a = Model()
        a.tapVideo()
        a.receive(Code.ACCEPT)
        assertFalse(a.isRemoteCameraPaused)

        a.receive(Code.CAMERA_OFF)
        assertTrue(a.isRemoteCameraPaused)
        a.receive(Code.CAMERA_ON)
        assertFalse(a.isRemoteCameraPaused)
    }

    // ------------------------------------------------------------- capacité

    @Test
    fun `un pair qui a declare ne pas faire la video est refuse tout de suite`() {
        val a = Model()
        a.peerSupportsVideo = false
        a.tapVideo()

        assertTrue(a.sent.isEmpty())
        assertFalse(a.isAwaitingVideoUpgrade)
        assertNotNull(a.declinedReason)
    }

    /**
     * ⚠️ LE test qui protège la compatibilité: « rien annoncé » n'est PAS
     * « a dit non ». Les confondre désactiverait la vidéo avec tous les
     * téléphones pas encore mis à jour.
     */
    @Test
    fun `un pair silencieux est quand meme sollicite`() {
        val a = Model()
        a.peerSupportsVideo = null
        a.tapVideo()

        assertEquals(listOf(Code.REQUEST), a.sent)
        assertTrue(a.isAwaitingVideoUpgrade)
    }

    @Test
    fun `le delai accuse la version quand rien n a ete annonce`() {
        val a = Model()
        a.peerSupportsVideo = null
        a.tapVideo(); a.requesterTimeoutFires()
        assertEquals("No answer — their version may not support video", a.declinedReason)

        val b = Model()
        b.peerSupportsVideo = true
        b.tapVideo(); b.requesterTimeoutFires()
        assertEquals("No answer", b.declinedReason)
    }

    // ------------------------------------------------------------ fuite/fin

    @Test
    fun `regarder seulement ne fuit pas sur l appel suivant`() {
        val m = Model()
        m.receive(Code.REQUEST)
        m.tapAccept(shareCamera = false)
        assertFalse(m.shareOwnVideo)

        m.cleanup()
        assertTrue(
            "sinon l'appel vidéo suivant part en réception seule et la caméra ne démarre jamais",
            m.shareOwnVideo
        )
    }

    @Test
    fun `la fin d appel desarme les deux delais`() {
        val m = Model()
        m.tapVideo()
        m.receive(Code.REQUEST)
        assertTrue(m.requesterTimerArmed)
        assertTrue(m.calleeTimerArmed)

        m.cleanup()
        assertFalse(m.requesterTimerArmed)
        assertFalse(m.calleeTimerArmed)
    }

    @Test
    fun `redescendre en audio remet les deux cotes d accord`() {
        val a = Model()
        a.tapVideo()
        a.receive(Code.ACCEPT_WATCH)
        assertTrue(a.isRemoteCameraPaused)

        a.receive(Code.STOP)
        assertFalse(a.isVideoCall)
        assertTrue("prêt pour la prochaine montée", a.shareOwnVideo)
        assertFalse(a.isRemoteCameraPaused)
    }

    /**
     * Les opcodes sont un contrat d'interopérabilité: iOS lit ces mêmes octets.
     * Les changer casse les appels entre les deux plateformes.
     */
    @Test
    fun `les opcodes sont ceux du fil`() {
        assertEquals(0x01.toByte(), Code.REQUEST.raw)
        assertEquals(0x02.toByte(), Code.ACCEPT.raw)
        assertEquals(0x03.toByte(), Code.DECLINE.raw)
        assertEquals(0x04.toByte(), Code.STOP.raw)
        assertEquals(0x05.toByte(), Code.ACCEPT_WATCH.raw)
        assertEquals(0x06.toByte(), Code.CAMERA_ON.raw)
        assertEquals(0x07.toByte(), Code.CAMERA_OFF.raw)
    }
}
