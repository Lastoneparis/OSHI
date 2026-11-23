package com.oshi.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __GROUP_RECEIPTS_ONLY_ARRIVED_BY_RELAY_2026_11_03__ (Anatole, N8 again)
 *
 * "Dans les groupes, la fonction 'info de lecture' ne semble toujours pas
 * fonctionner." — reported after the feature shipped, which is the detail that
 * matters: it was not missing, it was unreachable.
 *
 * Android has FOUR inbound funnels (relay, mesh, V2, cross-platform mesh) plus
 * the group JSON, and each one decides for itself what a control payload means.
 * A 1:1 read receipt was consumed on all of them. A GROUP receipt was consumed
 * on one — the relay — so every receipt that arrived over the mesh or through
 * V2 was parsed by nothing. The sheet stayed empty for exactly the users whose
 * groups do not travel by relay, which on a mesh messenger is the interesting
 * half of them.
 *
 * iOS never had this bug, and the reason is architectural rather than
 * diligence: `MessageManager.receiveMessage` is a single funnel every transport
 * arrives through, so one handler is all it can need.
 *
 * This test pins the count rather than the call sites, so adding a fifth
 * inbound path fails here until that path consumes group receipts too.
 */
class GroupReceiptEveryPathTest {

    private fun repositorySource(): String {
        val f = listOf(
            File("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt"),
            File("app/src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt"),
        ).firstOrNull { it.exists() }
        assertTrue("MessageRepository.kt not found from ${File(".").absolutePath}", f != null)
        return f!!.readText()
    }

    @Test
    fun `every inbound path that consumes a read receipt also consumes a group receipt`() {
        val src = repositorySource()
        // Sites that dispatch a 1:1 read receipt, i.e. the inbound funnels.
        val readSites = Regex("handleReadReceipt\\(decrypted,").findAll(src).count()
        val groupSites = Regex("consumeGroupReceipt\\(decrypted,").findAll(src).count()
        assertTrue("expected several inbound funnels, found $readSites", readSites >= 4)
        assertEquals(
            "a group receipt must be consumed wherever a read receipt is, or " +
                "'info de lecture' silently stays empty on that transport",
            readSites, groupSites
        )
    }

    @Test
    fun `the group receipt handler is shared, not copied`() {
        val src = repositorySource()
        // One definition, many calls. Four inline copies would drift the moment
        // one of them was edited — which is the shape the fix replaced.
        val definitions = Regex("private fun consumeGroupReceipt\\(").findAll(src).count()
        assertEquals(1, definitions)
        assertEquals(
            "GroupReceipts.parse must be reached only through the shared helper",
            1, Regex("GroupReceipts\\.parse\\(").findAll(src).count()
        )
    }

    @Test
    fun `iOS keeps its single inbound funnel`() {
        // If iOS ever grows a second inbound path, it inherits Android's bug,
        // and this is the cheapest place to notice.
        val swift = listOf(
            File("../OSHI/MessageManager.swift"),
            File("../../OSHI/MessageManager.swift"),
        ).firstOrNull { it.exists() }
        assertTrue("OSHI/MessageManager.swift not found", swift != null)
        val text = swift!!.readText()
        assertEquals(
            "iOS consumes group receipts in exactly one place because it has exactly " +
                "one inbound funnel; a second call site means that stopped being true",
            1, Regex("GroupReceipts\\.parse\\(").findAll(text).count()
        )
    }
}
