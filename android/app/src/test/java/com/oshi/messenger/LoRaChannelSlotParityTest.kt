package com.oshi.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ — the iOS half.
 *
 * The bug was identical on both platforms, which is the whole argument for this
 * file: `MeshPacket.channel` (field 3) was absent from the Android packet
 * builder AND from `MeshtasticManager.swift`, so a message typed into a private
 * LoRa channel's room went out on slot 0 — normally LongFast, normally public —
 * on an iPhone, a Mac and an Android phone alike.
 *
 * Fixing one platform and leaving the other is how a Mac leaks the room an
 * Android phone keeps private, so the Swift side is read and pinned here. Same
 * machinery as [MeshReconnectParityTest]; `../OSHI` is a declared test input
 * (see app/build.gradle.kts), so a Swift-only edit really does re-run this
 * rather than leaving the task UP-TO-DATE and the guard green for ever.
 */
class LoRaChannelSlotParityTest {

    private fun swift(name: String): String {
        val f = listOf(File("../OSHI/$name"), File("../../OSHI/$name")).firstOrNull { it.exists() }
        assertTrue("OSHI/$name not found from ${File(".").absolutePath}", f != null)
        return stripComments(f!!.readText())
    }

    /** A comment can never be the anchor: the explanation would satisfy the guard. */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inBlock -> if (c == '*' && n == '/') { inBlock = false; i++ } else if (c == '\n') out.append('\n')
                c == '/' && n == '*' -> { inBlock = true; i++ }
                c == '/' && n == '/' -> { while (i < src.length && src[i] != '\n') i++; continue }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    @Test
    fun `the swift packet builder emits MeshPacket channel`() {
        val mgr = swift("MeshtasticManager.swift")
        assertTrue(
            "MeshtasticManager.swift no longer writes MeshPacket.channel (field 3) — every " +
                "LoRa channel room is broadcasting on slot 0 again, which is usually public",
            mgr.contains("pkt.varint(3, channel)"),
        )
        assertTrue(
            "slot 0 must stay off the wire so existing frames are byte-identical",
            mgr.contains("if channel != 0"),
        )
        assertTrue(
            "sendText can no longer be told which slot to use",
            Regex("""func sendText\([^)]*channel:\s*Int""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(mgr),
        )
    }

    @Test
    fun `swift can map a room key back to a slot`() {
        val thread = swift("LoRaChannelThread.swift")
        assertTrue(
            "channelIndex(forKey:in:) is gone from the Swift side",
            thread.contains("func channelIndex(forKey"),
        )
    }

    /**
     * The call site, not the declaration — the lesson from `scheduleReconnectSweep`,
     * where a guard on the function name stayed green with the call commented out.
     */
    @Test
    fun `the swift channel send resolves a slot and refuses rather than guessing`() {
        val mm = swift("MessageManager.swift")
        val at = mm.indexOf("private func sendToLoRaChannel(")
        assertTrue("sendToLoRaChannel is gone from MessageManager.swift", at >= 0)
        val body = mm.substring(at, minOf(at + 2600, mm.length))
        assertTrue(
            "sendToLoRaChannel no longer resolves the room's slot — it is broadcasting on 0",
            body.contains("LoRaChannelThread.channelIndex(forKey:"),
        )
        assertTrue(
            "the resolved slot is not being passed to the radio",
            body.contains("channel: slot"),
        )
        assertTrue(
            "an unresolvable channel must be REFUSED, never sent on the primary",
            body.contains("loRaChannelUnknown"),
        )
    }
}
