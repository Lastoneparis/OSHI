package com.oshi.messenger.service.lora

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring that lives inside `MeshtasticManager` / `MessageRepository` (Bluetooth GATT, Room, Context:
 * not constructible in a JVM test). The decisions themselves are unit-tested in `LoRaHeldQueueTest`
 * and `LoRaLongTextTest`; this pins that the real send path USES them, so a refactor cannot quietly
 * put the old behaviour back.
 */
class LoRaSendPathContractTest {

    private fun src(rel: String): String =
        listOf(File("src/main/java/$rel"), File("app/src/main/java/$rel"))
            .first { it.isFile }.readText()

    private val manager by lazy { src("com/oshi/messenger/service/lora/MeshtasticManager.kt") }
    private val repo by lazy { src("com/oshi/messenger/data/repository/MessageRepository.kt") }

    /** The body of `fun <name>(` up to the next top-level member at the same indent. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("missing: $signature", start >= 0)
        val next = Regex("\n    (?:private |internal |override |suspend )*fun ").find(text, start + signature.length)
        return text.substring(start, next?.range?.first ?: text.length)
    }

    /** __ANTI_JAMMING_OVERRIDE_PARITY_2026_11_18__ iOS `relayOMPFrame` guards on `excludesLoRaForJamming`. */
    @Test
    fun `bridged frames honour the send-anyway override like iOS`() {
        val relay = body(manager, "fun relayOmpFrame(")
        assertTrue(relay.contains("isJammed"))
        assertFalse("raw detector verdict ignores the override", relay.contains("channelLooksJammed"))
        listOf(File("../../OSHI/MeshtasticManager.swift"), File("../OSHI/MeshtasticManager.swift"))
            .firstOrNull { it.isFile }?.readText()?.let { swift ->
                val i = swift.indexOf("func relayOMPFrame(")
                assertTrue(i >= 0)
                assertTrue(swift.substring(i, i + 300).contains("excludesLoRaForJamming"))
            }
    }

    /** __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ */
    @Test
    fun `config complete does not flush before the probe, the probe outcome does`() {
        val i = manager.indexOf("is LoRaPacketParser.FromRadio.ConfigComplete ->")
        assertTrue(i >= 0)
        val block = manager.substring(i, manager.indexOf("refreshStatus()", i))
        assertTrue(block.contains("startOshiMeshSetup()"))
        assertFalse(Regex("^\\s*flushStoreAndForward\\(\\)", RegexOption.MULTILINE).containsMatchIn(block))
        assertTrue(body(manager, "private fun ensureOshiChannel(").contains("flushStoreAndForward()"))
        val beacon = manager.substring(manager.indexOf("OshiMeshProtocol.FrameType.BEACON ->"))
            .substringBefore("OshiMeshProtocol.FrameType.STATUS ->")
        assertTrue(beacon.contains("rememberOshiFirmware(true)"))
        assertTrue(beacon.contains("flushStoreAndForward()"))
        // A ready radio whose probe is unanswered holds instead of sending.
        assertTrue(body(manager, "fun queueOrSendSecureMessage(").contains("LoRaRadioGate.holdReason("))
    }

    /** __LORA_HELD_DURABLE_2026_11_18__ */
    @Test
    fun `the held queue is the durable thread-safe one and flush never clears blindly`() {
        assertTrue(manager.contains("private val storeAndForward = LoRaHeldQueue("))
        assertFalse(manager.contains("ArrayDeque<LoRaWireMessage>"))
        assertFalse(manager.contains("storeAndForward.clear()"))
        assertFalse(manager.contains("storeAndForward.removeFirst()"))
        val hold = body(manager, "private fun holdForLater(")
        assertTrue("evictions must be reported", hold.contains("onHeldUndeliverable"))
    }

    /** __NEVER_DROP_HELD_2026_11_18__ audit point 3 */
    @Test
    fun `internet off with the radio skipped keeps the message for the radio or marks it failed`() {
        val i = repo.indexOf("if (!routing.internetAllowedForSending) {")
        assertTrue(i >= 0)
        val branch = repo.substring(i, repo.indexOf("return Result.success(message)", i))
        assertTrue(branch.contains("holdForRadioOrFail("))
        val helper = body(repo, "private suspend fun holdForRadioOrFail(")
        assertTrue(helper.contains("holdForRadio("))
        assertTrue(helper.contains("markSendFailed("))
        assertTrue(repo.contains("radio.heldListener = "))
    }

    /** __LORA_LONG_TEXT_OMP_2026_11_18__ the budget at the send gate reads the remembered firmware. */
    @Test
    fun `send gate passes the remembered oshi firmware to the budget`() {
        assertTrue(repo.contains("MeshtasticManager.lastKnownOshiFirmware(context)"))
        assertTrue(repo.contains("MessageSendPolicy.check(content, loRaBudget, oshiFirmware)"))
    }
}
