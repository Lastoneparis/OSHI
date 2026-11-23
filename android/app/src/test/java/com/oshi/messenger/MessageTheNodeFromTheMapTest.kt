package com.oshi.messenger

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__
 *
 * Tapping a LoRa node on the map showed its name, signal, hops and distance and
 * offered no action at all — you could see a radio two streets away and had no
 * way to say anything to it without leaving the map.
 *
 * The two things worth pinning are not "a button exists" but WHICH THREAD it
 * opens and that it is offered for a RELAYED node as well as a direct one:
 *
 *  * the key comes from `conversationKey`, not `fallbackConversationKey`, so a
 *    radio that has proved an OSHI identity gets its contact's ENCRYPTED thread
 *    and only an unproved one lands in the cleartext `lora!` thread. Hard-coding
 *    the fallback would quietly downgrade every proved peer to plaintext;
 *  * nothing gates the button on `hopsAway == 0`. Meshtastic routes a unicast
 *    through the mesh, so a relayed node is reachable exactly like a direct one.
 */
class MessageTheNodeFromTheMapTest {

    private fun swift(name: String): String {
        val f = listOf(File("../OSHI/$name"), File("../../OSHI/$name")).firstOrNull { it.exists() }
        assertTrue("OSHI/$name not found from ${File(".").absolutePath}", f != null)
        return SourceText.stripComments(f!!.readText())
    }

    // ------------------------------------------------------------- Android

    @Test
    fun `the android card offers a way to message the node`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/MapScreen.kt")
        val card = SourceText.between(src, "private fun LoRaNodeMapCard(", "private fun NodeStat")
        assertTrue("no message action on the node card", card.contains("onMessage("))
        assertTrue("the button has no label", card.contains("R.string.lora_node_message"))
    }

    @Test
    fun `the android card opens the proved thread, not always the cleartext one`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/MapScreen.kt")
        val card = SourceText.between(src, "private fun LoRaNodeMapCard(", "private fun NodeStat")
        assertTrue(
            "the card must resolve the thread through conversationKey",
            card.contains("conversationKey("),
        )
        assertFalse(
            "hard-coding the fallback key downgrades every PROVED peer to plaintext",
            card.contains("fallbackConversationKey("),
        )
    }

    @Test
    fun `a relayed node can be messaged too`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/MapScreen.kt")
        val card = SourceText.between(src, "private fun LoRaNodeMapCard(", "private fun NodeStat")
        val button = SourceText.window(card, "onMessage(", 400)
        assertFalse(
            "the message button is gated on hopsAway — Meshtastic routes a unicast through " +
                "the mesh, so a relayed node is reachable exactly like a direct one",
            button.contains("hopsAway"),
        )
    }

    /** A button whose callback is never wired is the bug this project keeps shipping. */
    @Test
    fun `the callback is actually wired to navigation`() {
        val main = SourceText.read("src/main/java/com/oshi/messenger/ui/MainActivity.kt")
        val call = SourceText.window(main, "MapScreen(", 1400)
        assertTrue("MapScreen is constructed without onOpenChat", call.contains("onOpenChat"))
        assertTrue("onOpenChat does not navigate anywhere", call.contains("navigate(\"chat/"))
    }

    // ----------------------------------------------------------------- iOS

    @Test
    fun `the ios card offers the same action through the same resolver`() {
        val map = swift("OfflineMapView.swift")
        val at = map.indexOf("private struct LoRaNodeMapCard")
        assertTrue("LoRaNodeMapCard is gone from OfflineMapView.swift", at >= 0)
        val card = map.substring(at)
        assertTrue("no message action on the iOS node card", card.contains("lora.node.message"))
        assertTrue(
            "the iOS card must resolve the thread through conversationKey",
            card.contains("conversationKey(forNode:"),
        )
        assertFalse(
            "hard-coding the fallback key downgrades every PROVED peer to plaintext",
            card.contains("fallbackConversationKey("),
        )
        assertTrue("the iOS button does not open anything", card.contains("\"OpenChat\""))
    }
}
