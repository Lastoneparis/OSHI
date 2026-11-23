package com.oshi.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * __PUSH_PLAINTEXT_PREVIEW_2026_09_02__
 *
 * Une poussée RÉVEILLE l'appareil d'en face. Elle ne raconte pas ce qui est arrivé,
 * parce que tout ce qu'elle transporte passe par un relais tiers — le serveur de
 * poussée, puis APNs ou FCM — et finit sur un écran verrouillé.
 *
 * `GroupManager.deliverGroupEnvelope` y envoyait, pour CHAQUE message de groupe:
 * le nom du groupe trois fois (`title`, `body`, `data.groupName`), le nom affiché de
 * l'expéditeur, et **les 100 premiers caractères du message en clair** (`data.preview`).
 * Son commentaire disait « matches iOS broadcastGroupMessageViaRelay » — le chemin iOS
 * en question envoie justement un `"OSHI"` générique, et la NSE iOS écrit
 * « no plaintext in the push — the server never sees content ».
 *
 * Le chemin 1:1 d'Android, dans le MÊME dépôt, n'a jamais envoyé autre chose que
 * `senderPublicKey` + `messageId` + des clés de localisation. C'est le chemin de groupe
 * qui était le dissident, pas la règle qui était nouvelle.
 *
 * Ce test lit la SOURCE plutôt que d'appeler la fonction: `deliverGroupEnvelope` est
 * privée, suspend, et tire un `Cipher`, IPFS, le mesh et le réseau derrière elle. Ce qui
 * est vérifiable sans tout ça, c'est ce que le payload CONTIENT — et c'est exactement là
 * qu'était le défaut.
 */
class PushCarriesNoPlaintextTest {

    private fun source(relative: String): String {
        // Le module de test tourne depuis `app/`; la source vit sous `src/main/java`.
        val f = File("src/main/java/com/oshi/messenger/$relative")
        assertTrue("source introuvable: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    /** Sans les commentaires: la note qui explique le correctif CITE les champs fautifs. */
    private fun code(relative: String): String =
        source(relative).lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joined()

    private fun Sequence<String>.joined() = joinToString("\n")

    /** Le bloc d'arguments d'un appel `sendPushNotification(` — accolades appariées. */
    private fun pushCalls(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = text.indexOf("sendPushNotification(")
        while (i >= 0) {
            val open = text.indexOf('(', i + "sendPushNotification".length)
            var depth = 0
            var j = open
            while (j < text.length) {
                if (text[j] == '(') depth++
                if (text[j] == ')') { depth--; if (depth == 0) break }
                j++
            }
            out.add(text.substring(open, minOf(j + 1, text.length)))
            i = text.indexOf("sendPushNotification(", j)
        }
        return out
    }

    @Test
    fun `no android push carries message plaintext or display names`() {
        val files = listOf("service/GroupManager.kt", "network/vps/VPSClient.kt")
        val banned = listOf("\"preview\"", "\"senderName\"", "\"groupName\"", "push.preview", "push.senderName")
        var callsSeen = 0

        for (rel in files) {
            for (call in pushCalls(code(rel))) {
                callsSeen++
                for (b in banned) {
                    assertFalse(
                        "$rel: une poussée transporte $b — contenu en clair chez le relais:\n$call",
                        call.contains(b)
                    )
                }
                assertFalse(
                    "$rel: le nom du groupe part dans le titre ou le corps:\n$call",
                    call.contains("group.name")
                )
            }
        }
        assertTrue("aucun appel de poussée trouvé — la règle ne surveille rien", callsSeen >= 2)
    }

    /**
     * Le témoin: la règle doit REJETER le payload tel qu'il était livré. Sans ça, un vert
     * ne dirait pas si elle sait quoi chercher.
     */
    @Test
    fun `the rule rejects the payload that shipped`() {
        val before = """
            vpsClient.sendPushNotification(
                recipientPublicKey = memberKey,
                type = "group_message",
                title = group.name,
                body = "New message in ${'$'}{group.name}",
                data = mapOf(
                    "type" to "group_message",
                    "groupId" to group.id,
                    "groupName" to group.name,
                    "senderName" to push.senderName,
                    "preview" to push.preview,
                    "messageId" to messageId
                )
            )
        """.trimIndent()
        val call = pushCalls(before).firstOrNull()
        assertNotNull("l'extracteur ne sait plus lire un appel", call)
        assertTrue("la règle ne voit plus l'aperçu en clair", call!!.contains("\"preview\""))
        assertTrue("la règle ne voit plus le nom d'expéditeur", call.contains("\"senderName\""))
        assertTrue("la règle ne voit plus le nom de groupe", call.contains("group.name"))
    }

    /**
     * Et ce qui doit RESTER: la poussée garde de quoi router et de quoi appliquer le
     * garde « expéditeur bloqué » de `FCMService` — qui lit `senderKey`/`senderPublicKey`
     * et n'en recevait AUCUN sur un message de groupe, donc ne pouvait jamais mordre.
     */
    @Test
    fun `the group push still carries routing ids and the sender key`() {
        val call = pushCalls(code("service/GroupManager.kt"))
            .firstOrNull { it.contains("\"group_message\"") }
        assertNotNull("la poussée de groupe a disparu", call)
        for (needed in listOf("\"groupId\"", "\"messageId\"", "\"senderPublicKey\"", "\"senderKey\"")) {
            assertTrue("la poussée de groupe ne porte plus $needed:\n$call", call!!.contains(needed))
        }
    }
}
