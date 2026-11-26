package com.oshi.messenger.service.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frames captured from REAL MeshCore companion firmware (v1.18.0, FIRMWARE_VER_CODE 14, MeshCore 75b04ef) on two
 * Seeed T1000-E radios over USB serial, while a host harness drove this very MeshCoreProtocol.kt through app
 * start, channel scan + #oshi-bridge claim, adverts, contacts, DMs with ACKs, a 3-part DM, Public and hashtag
 * channels, the #oshi-bridge datagram path and the v3 formats (2026-11-25). The same file is pinned on iOS
 * (OSHITests/MeshCoreRealFrames.txt); a drift in either parser against the radio now fails a test.
 *
 * A = "OSHI-TEA-MC" (key f007359830cf…), B = "OSHI-TEB-MC" (key 26aa91559d44…).
 */
class MeshCoreRealFramesTest {

    private val fx: Map<String, ByteArray> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("meshcore/real-frames-v1.18.0.txt")!!.bufferedReader().readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (k, v) = line.split("=", limit = 2)
            k to MeshCore.unhex(v.trim())!!
        }
    }

    private fun f(name: String) = fx[name] ?: error("missing fixture $name")
    private val keyA get() = MeshCore.selfPublicKey(f("self_info_A_named"))!!
    private val keyB get() = MeshCore.parseContact(f("contact_B_in_A"))!!.publicKey

    @Test
    fun selfInfo() {
        assertEquals("OSHI-TEA-MC", MeshCore.selfName(f("self_info_A_named")))
        assertEquals("f007359830cf8dc7", MeshCore.hex(keyA).take(16))
        // A fresh radio names itself after its key: the first 4 key bytes, upper-case hex.
        assertEquals("26AA9155", MeshCore.selfName(f("self_info_B_unnamed")))
    }

    @Test
    fun channelInfoIncludingAnEmptySlotWithStaleBytes() {
        val pub = MeshCore.parseChannelInfo(f("channel_info_public"))!!
        assertEquals(MeshCore.ChannelInfo(0, "Public", MeshCore.PUBLIC_CHANNEL_SECRET), pub)
        val bridge = MeshCore.parseChannelInfo(f("channel_info_bridge_slot1"))!!
        assertEquals(MeshCore.ChannelInfo(1, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET), bridge)
        // The firmware strcpy()s "" over a reused buffer: the name field still holds "ublic\0" + key bytes after
        // the NUL. Only reading up to the FIRST NUL sees the slot as empty (and claimable).
        val empty = MeshCore.parseChannelInfo(f("channel_info_empty_slot_with_residue"))!!
        assertEquals(2, empty.idx)
        assertEquals("", empty.name)
        assertArrayEquals(ByteArray(16), empty.secret)
        val tag = MeshCore.parseChannelInfo(f("channel_info_hashtag_readback"))!!
        assertEquals("#oshi-hw-test", tag.name)
        assertArrayEquals(MeshCore.hashtagSecret("#oshi-hw-test"), tag.secret)
        assertTrue(MeshCore.isPublicChannel(tag.name, tag.secret))
    }

    @Test
    fun contactsAndAdverts() {
        val b = MeshCore.parseContact(f("contact_B_in_A"))!!
        assertEquals(MeshCore.CONTACT_FRAME_BYTES, f("contact_B_in_A").size)
        assertEquals("OSHI-TEB-MC", b.name)
        assertEquals(MeshCore.ADV_TYPE_CHAT, b.type)
        assertEquals("OSHI-TEA-MC", MeshCore.parseContact(f("contact_A_in_B"))!!.name)
        // The default firmware (auto-add on) pushes ADVERT (0x80, key only), never NEW_ADVERT: OSHI must fetch the
        // entry with GET_CONTACT_BY_KEY, which is what the session does.
        val s = readySession()
        s.onFrame(f("push_advert_A_heard_by_B"))
        assertArrayEquals(MeshCore.getContactByKey(keyA), s.nextCommand())
        s.onFrame(f("contact_A_in_B"))
        assertEquals("OSHI-TEA-MC", s.contactFor(keyA.copyOf(6))?.name)
    }

    @Test
    fun sentAndConfirmedCarryTheSameAckTag() {
        val sent = MeshCore.parseSent(f("resp_sent_direct_dm1"))!!
        assertTrue(sent.flood)
        assertEquals(sent.ackTag, MeshCore.parseSendConfirmed(f("push_send_confirmed")))
        assertEquals(MeshCore.RESP_ERR, f("err_not_found")[0].toInt())
    }

    @Test
    fun directTextBothFormats() {
        val dm = MeshCore.parseDirectText(f("contact_msg_recv_v0_dm1"))!!
        assertEquals("OSHI interop test A to B", dm.text)
        assertArrayEquals(keyA.copyOf(6), dm.senderPrefix)
        assertEquals("(3/3) boundaries. Ünïcödé ✓ end.", MeshCore.parseDirectText(f("contact_msg_recv_v0_long_part3_utf8"))!!.text)
        val v3 = MeshCore.parseDirectText(f("contact_msg_recv_v3"))!!
        assertEquals("v3 format check", v3.text)
        assertArrayEquals(keyB.copyOf(6), v3.senderPrefix)
    }

    @Test
    fun channelTextBothFormats() {
        val pub = MeshCore.parseChannelText(f("channel_msg_recv_v0_public"))!!
        assertEquals(0, pub.channelIdx)
        assertEquals("OSHI-TEB-MC" to "OSHI test", MeshCore.splitChannelSender(pub.text))
        val tag = MeshCore.parseChannelText(f("channel_msg_recv_v0_hashtag"))!!
        assertEquals(2, tag.channelIdx)
        assertEquals("OSHI-TEA-MC" to "hashtag hello", MeshCore.splitChannelSender(tag.text))
        val v3 = MeshCore.parseChannelText(f("channel_msg_recv_v3_hashtag"))!!
        assertEquals("OSHI-TEB-MC" to "v3 channel check", MeshCore.splitChannelSender(v3.text))
    }

    @Test
    fun bridgeDatagramsReassembleIntoTheOmpFrame() {
        val s = readySession(f("self_info_B_unnamed"))     // B receives what A sent
        assertEquals(1, s.channelIdx)
        assertTrue(s.onFrame(f("channel_data_recv_ob_part0")).none { it is MeshCoreSession.Event.Frame })
        val ev = s.onFrame(f("channel_data_recv_ob_part1")).filterIsInstance<MeshCoreSession.Event.Frame>().single()
        assertEquals(0x5A5A0001u, ev.parsed.msgId)
        assertEquals(0x983507f0u, ev.parsed.origin)       // A's MeshCore-derived id (first 4 key bytes, LE)
        // ...and A itself ignores its own datagrams heard back.
        val a = readySession()
        a.onFrame(f("channel_data_recv_ob_part0"))
        assertTrue(a.onFrame(f("channel_data_recv_ob_part1")).none { it is MeshCoreSession.Event.Frame })
        assertArrayEquals(ByteArray(182) { (it * 7 + 3).toByte() }, ev.parsed.payload)
    }

    @Test
    fun encodersMatchWhatTheRadioAccepted() {
        assertArrayEquals(f("cmd_app_start"), MeshCore.appStart("OSHI-harness"))
        assertArrayEquals(f("cmd_set_channel_bridge"), MeshCore.setChannel(1, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET))
        val retry = f("cmd_send_txt_long_part2_attempt1")
        val ts = MeshCore.parseDirectText(f("contact_msg_recv_v0_long_part2_first"))!!
        assertArrayEquals(retry, MeshCore.sendTextMessage(1, ts.senderTimestamp, keyB.copyOf(6), ts.text))
        val dg = f("cmd_send_channel_data_part0")
        assertEquals(MeshCore.CMD_SEND_CHANNEL_DATA, dg[0].toInt())
        assertEquals(OshiBridgeWire.DATA_TYPE, (dg[3].toInt() and 0xFF) or ((dg[4].toInt() and 0xFF) shl 8))
    }

    /** The bug real radios exposed: an ACK lost to a collision, our retry (same timestamp) surfaced twice by the receiver. */
    @Test
    fun retriedDirectMessageIsSurfacedOnce() {
        val first = f("contact_msg_recv_v0_long_part2_first")
        val retry = f("contact_msg_recv_v0_long_part2_retry")
        assertArrayEquals(first, retry)    // byte-identical on the wire, as captured
        val s = MeshCoreSession()
        assertEquals(1, s.onFrame(first, 1_000).count { it is MeshCoreSession.Event.DirectText })
        assertEquals(0, s.onFrame(retry, 9_000).count { it is MeshCoreSession.Event.DirectText })
        // The same words sent again later (a new timestamp) are a new message.
        val later = first.copyOf().also { it[9] = (it[9] + 1).toByte() }
        assertEquals(1, s.onFrame(later, 10_000).count { it is MeshCoreSession.Event.DirectText })
        // And the copy is forgotten once no sender could still be retrying.
        assertEquals(1, s.onFrame(retry, 10_000 + MeshCoreSession.TEXT_DEDUP_MS + 1).count { it is MeshCoreSession.Event.DirectText })
    }

    @Test
    fun unsolicitedPushesTheSessionDoesNotUseAreHarmless() {
        val s = readySession()
        assertTrue(s.onFrame(f("push_log_rx_data")).isEmpty())
        assertTrue(s.onFrame(f("push_path_updated")).isEmpty())
    }

    @Test
    fun serialFramingOfARealStream() {
        val stream = MeshCore.Serial.encode(f("push_send_confirmed"), MeshCore.Serial.FROM_RADIO) +
            MeshCore.Serial.encode(f("contact_B_in_A"), MeshCore.Serial.FROM_RADIO)
        val one = MeshCore.Serial.decode(stream)!!
        assertArrayEquals(f("push_send_confirmed"), one.payload)
        val two = MeshCore.Serial.decode(stream.copyOfRange(one.consumed, stream.size))!!
        assertArrayEquals(f("contact_B_in_A"), two.payload)
    }

    /** A session taken through the real setup exchange of a radio (radio A by default; bridge already in slot 1). */
    private fun readySession(selfInfo: ByteArray = f("self_info_A_named")): MeshCoreSession {
        val s = MeshCoreSession("OSHI-harness")
        s.start()
        assertNotNull(s.nextCommand())
        s.onFrame(selfInfo)
        for (i in 0 until MeshCore.CHANNEL_SLOTS) {
            val cmd = s.nextCommand()!!
            assertArrayEquals(MeshCore.getChannel(i), cmd)
            val frame = when (i) {
                0 -> f("channel_info_public")
                1 -> f("channel_info_bridge_slot1")
                else -> f("channel_info_empty_slot_with_residue").copyOf().also { it[1] = i.toByte() }
            }
            s.onFrame(frame)
        }
        assertTrue(s.isReady && s.chatReady)
        assertArrayEquals(MeshCore.getContacts(), s.nextCommand())
        s.onFrame(f("contacts_start")); s.onFrame(f("contact_B_in_A")); s.onFrame(f("end_of_contacts"))
        assertArrayEquals(MeshCore.syncNextMessage, s.nextCommand())
        s.onFrame(byteArrayOf(MeshCore.RESP_NO_MORE_MESSAGES.toByte()))
        return s
    }
}
