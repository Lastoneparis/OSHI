package com.oshi.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔒 __MESH_UNAUTHENTICATED_CONTROL_2026_11_20__ Source guard (same style as
 * MeshMediaEncryptedWireTest): the mesh receive funnel must never hand an
 * unauthenticated frame to the handlers that trust their sender.
 *
 * Proven live on 2026-11-20 against the 1.7.1 release build: one length-prefixed JSON
 * frame `{type:"GROUP_UPDATE", senderPublicKey:<random>, payload:"📢GROUP_UPDATE📢{created…}"}`
 * written to the mesh TCP port (bound on all interfaces right after onboarding) made a
 * group "PENTEST injected group" appear on the phone, with the random key as its admin.
 */
class MeshUnauthenticatedControlTest {
    private val src = File("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt").readText()
    private val funnel = src.substringAfter("private suspend fun handleCrossPlatformMessage(")
        .substringBefore("private suspend fun routeDecryptedMeshMessage(")

    @Test fun plaintextGroupUpdateFrameIsNotRoutedToGroupManager() {
        assertTrue(funnel.contains("Dropped unauthenticated mesh GROUP_UPDATE frame"))
        assertFalse(funnel.contains("groupManager.handleGroupUpdate("))
    }

    @Test fun plaintextContentIsNeverRoutedAsDecrypted() {
        assertFalse(funnel.contains("routeDecryptedMeshMessage(\n                            plaintextContent"))
        assertFalse(Regex("""routeDecryptedMeshMessage\(\s*plaintextContent""").containsMatchIn(funnel))
    }

    @Test fun plaintextMeshMediaIsNotStoredUnderAClaimedSender() {
        assertTrue(funnel.contains("Dropped plaintext (unauthenticated) mesh media frame"))
        assertFalse(funnel.contains("bypassing decryption, routing direct"))
    }
}
