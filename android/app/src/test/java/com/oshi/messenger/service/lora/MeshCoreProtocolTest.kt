package com.oshi.messenger.service.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins OSHI-over-MeshCore to the two things it must match byte for byte: the "OB" envelope of
 * oshi-meshcore-bridge (vectors produced by oshi_bridge/wire.py, the same as
 * OSHITests/MeshCoreRadioTests.swift) and the MeshCore companion protocol
 * (examples/companion_radio/MyMesh.cpp). A drift in either reads as "MeshCore never delivers": the
 * bridge drops a foreign envelope silently and the radio answers ERR to a malformed command.
 */
class MeshCoreProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    @Test
    fun envelopeMatchesBridge() {
        val frame = ByteArray(200) { (it and 0xFF).toByte() }
        val parts = OshiBridgeWire.split(frame, 0x11223344u, 7)!!
        assertEquals(listOf(160, 58), parts.map { it.size })
        assertEquals("4f4210443322110702000102", hex(parts[0].copyOfRange(0, 12)))
        assertEquals("4f4210443322110712979899", hex(parts[1].copyOfRange(0, 12)))

        val joiner = OshiBridgeWire.Joiner()
        val p1 = OshiBridgeWire.parse(parts[1])!!
        val p0 = OshiBridgeWire.parse(parts[0])!!
        assertEquals(0x11223344u, p0.sender)
        assertNull(joiner.push(p1))
        assertArrayEquals(frame, joiner.push(p0))
    }

    @Test
    fun channelSecretMatchesBridge() {
        assertEquals("485a8d4bcd50fd075ea87d7ee19f4135", hex(OshiBridgeWire.CHANNEL_SECRET))
    }

    @Test
    fun envelopeRejectsForeignDatagrams() {
        assertNull(OshiBridgeWire.parse("hello world".toByteArray()))
        assertNull(OshiBridgeWire.parse(bytes(0x4F, 0x42, 0x10, 0, 0, 0, 0, 0, 0x22)))   // part 2 of 2
        assertNull(OshiBridgeWire.parse(bytes(0x4F, 0x42, 0x20, 0, 0, 0, 0, 0, 0x01)))   // version 2
    }

    @Test
    fun companionCommands() {
        assertArrayEquals(bytes(1, 0, 0, 0, 0, 0, 0, 0), MeshCore.appStart().copyOfRange(0, 8))
        assertArrayEquals(bytes(31, 3), MeshCore.getChannel(3))
        val set = MeshCore.setChannel(7, "#oshi-bridge", OshiBridgeWire.CHANNEL_SECRET)
        assertEquals(2 + 32 + 16, set.size)            // the firmware refuses 2+32+32 (256-bit) as unsupported
        assertArrayEquals(bytes(32, 7), set.copyOfRange(0, 2))
        assertArrayEquals("#oshi-bridge".toByteArray(), set.copyOfRange(2, 14))
        assertArrayEquals(ByteArray(20), set.copyOfRange(14, 34))
        assertArrayEquals(
            bytes(62, 7, 0xFF, 0x4F, 0xFF, 0xAA),
            MeshCore.sendChannelData(7, 0xFF4F, bytes(0xAA)),
        )
    }

    @Test
    fun companionReplies() {
        val info = bytes(18, 7) + "#oshi-bridge".toByteArray() + ByteArray(32 - 12) + OshiBridgeWire.CHANNEL_SECRET
        val ch = MeshCore.parseChannelInfo(info)!!
        assertEquals(7, ch.idx)
        assertEquals("#oshi-bridge", ch.name)
        assertArrayEquals(OshiBridgeWire.CHANNEL_SECRET, ch.secret)

        val d = MeshCore.parseChannelData(bytes(27, 0x18, 0, 0, 7, 3, 0x4F, 0xFF, 3, 0x4F, 0x42, 0x10))!!
        assertEquals(7, d.channelIdx)
        assertEquals(0xFF4F, d.dataType)
        assertArrayEquals(bytes(0x4F, 0x42, 0x10), d.payload)
        assertNull(MeshCore.parseChannelData(bytes(27, 0, 0, 0, 7, 3, 0x4F, 0xFF, 9, 1)))   // truncated

        val key = ByteArray(32) { it.toByte() }
        assertArrayEquals(key, MeshCore.selfPublicKey(bytes(5, 1, 22, 22) + key + ByteArray(20)))
    }

    /**
     * End to end on the wire: a message a MeshCore phone sends comes back out of the envelope and the OMP
     * reassembler as the same body, with the phone's id as origin.
     */
    @Test
    fun ompOverEnvelopeRoundTrip() {
        val body = OshiMeshProtocol.encodeBody(ByteArray(900) { 0x41 })
        val frames = OshiMeshProtocol.dataFrames(99u, CrossBridgeDedup.BROADCAST, body, origin = 0xCAFEBABEu)!!
        val joiner = OshiBridgeWire.Joiner()
        val rx = OmpReassembler()
        var out: ByteArray? = null
        frames.forEachIndexed { i, fr ->
            for (dg in OshiBridgeWire.split(fr, 0xCAFEBABEu, i)!!) {
                val joined = joiner.push(OshiBridgeWire.parse(dg)!!) ?: continue
                val f = OshiMeshProtocol.parseData(joined)!!
                assertEquals(0xCAFEBABEu, f.origin)
                rx.accept(f)?.let { out = it }
            }
        }
        assertArrayEquals(body, out)
    }

    @Test
    fun serialFramingRoundTripAndResync() {
        val framed = MeshCore.Serial.encode(bytes(1, 2, 3))
        assertArrayEquals(bytes(0x3C, 3, 0, 1, 2, 3), framed)
        val fromRadio = bytes(0x00, 0x7F) + MeshCore.Serial.encode(bytes(9, 8), MeshCore.Serial.FROM_RADIO) + bytes(0x3E)
        val d = MeshCore.Serial.decode(fromRadio)!!
        assertArrayEquals(bytes(9, 8), d.payload)
        assertEquals(7, d.consumed)
        assertNull(MeshCore.Serial.decode(bytes(0x3E, 5, 0, 1, 2)))   // incomplete: wait for more
    }

    /** The setup conversation: never overwrite a user's channel, claim the first EMPTY slot > 0. */
    @Test
    fun sessionClaimsFirstEmptySlotAndNeverAUserChannel() {
        val s = MeshCoreSession()
        s.start()
        assertArrayEquals(MeshCore.appStart(), s.nextCommand())
        assertNull(s.nextCommand())                                   // one request in flight at a time
        val pub = bytes(0xDB, 0x33, 0x30, 0xA2) + ByteArray(28)
        s.onFrame(bytes(5, 1, 22, 22) + pub + ByteArray(20))
        assertEquals(0xA23033DBu, s.nodeId)
        fun channel(idx: Int, name: String) =
            bytes(18, idx) + name.toByteArray() + ByteArray(32 - name.length) + ByteArray(16) { 1 }
        val names = listOf("Public", "family", "", "", "work", "", "", "")
        for (i in 0 until 8) {
            assertArrayEquals(MeshCore.getChannel(i), s.nextCommand())
            s.onFrame(channel(i, names[i]))
        }
        assertArrayEquals(MeshCore.setChannel(2, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET), s.nextCommand())
        assertFalse(s.isReady)
        val events = s.onFrame(bytes(MeshCore.RESP_OK))
        assertTrue(events.first() is MeshCoreSession.Event.Ready)
        assertTrue(events.any { it is MeshCoreSession.Event.ChatReady })
        assertTrue(s.isReady)
        assertEquals(2, s.channelIdx)
        // Every slot is known: read the contact table first (so a queued DM resolves its sender), then the queue.
        assertArrayEquals(MeshCore.getContacts(), s.nextCommand())
        s.onFrame(bytes(MeshCore.RESP_CONTACTS_START, 0, 0, 0, 0))
        assertNull(s.nextCommand())                                   // the listing is still one request
        s.onFrame(bytes(MeshCore.RESP_END_OF_CONTACTS, 0, 0, 0, 0))
        assertArrayEquals(MeshCore.syncNextMessage, s.nextCommand())
    }

    @Test
    fun sessionReportsNoFreeChannelAndFindsAnExistingOne() {
        val full = MeshCoreSession().apply { start(); nextCommand(); onFrame(bytes(5, 1, 22, 22) + ByteArray(52)) }
        var events: List<MeshCoreSession.Event> = emptyList()
        for (i in 0 until 8) {
            full.nextCommand()
            events = full.onFrame(bytes(18, i) + "ch$i".toByteArray() + ByteArray(29) + ByteArray(16))
        }
        assertTrue(events.any { it is MeshCoreSession.Event.NoFreeChannel })
        assertFalse(full.isReady)
        // ...but a radio with no slot for #oshi-bridge still chats with MeshCore users.
        assertTrue(events.any { it is MeshCoreSession.Event.ChatReady })
        assertTrue(full.chatReady)

        val known = MeshCoreSession().apply { start(); nextCommand(); onFrame(bytes(5, 1, 22, 22) + ByteArray(52)) }
        known.nextCommand()
        known.onFrame(bytes(18, 0) + "Public".toByteArray() + ByteArray(26) + ByteArray(16))
        known.nextCommand()
        val ev = known.onFrame(bytes(18, 1) + OshiBridgeWire.CHANNEL_NAME.toByteArray() + ByteArray(20) + OshiBridgeWire.CHANNEL_SECRET)
        assertTrue(ev.single() is MeshCoreSession.Event.Ready)
        assertEquals(1, known.channelIdx)
    }

    /** A frame another phone put on #oshi-bridge surfaces; our own echo and other channels do not. */
    @Test
    fun sessionReceivesForeignFramesOnly() {
        val s = MeshCoreSession().apply { start(); nextCommand(); onFrame(bytes(5, 1, 22, 22) + bytes(1, 0, 0, 0) + ByteArray(48)) }
        s.nextCommand()
        s.onFrame(bytes(18, 0) + OshiBridgeWire.CHANNEL_NAME.toByteArray() + ByteArray(20) + OshiBridgeWire.CHANNEL_SECRET)
        assertTrue(s.isReady)
        val frame = OshiMeshProtocol.dataFrames(5u, CrossBridgeDedup.BROADCAST, bytes(1, 2, 3), origin = 9u)!![0]
        fun recv(ch: Int, dg: ByteArray) = bytes(27, 0, 0, 0, ch, 0, 0x4F, 0xFF, dg.size) + dg
        val theirs = OshiBridgeWire.split(frame, 9u, 1)!!.single()
        val ours = OshiBridgeWire.split(frame, 1u, 1)!!.single()
        assertTrue(s.onFrame(recv(1, theirs)).isEmpty())            // wrong channel
        assertTrue(s.onFrame(recv(0, ours)).isEmpty())              // our own echo
        val got = s.onFrame(recv(0, theirs)).single() as MeshCoreSession.Event.Frame
        assertEquals(9u, got.parsed.origin)
        assertArrayEquals(frame, got.frame)
        // Outbound: our id as envelope sender, on our slot, as channel data 0xFF4F.
        val out = s.channelDatagrams(frame).single()
        assertArrayEquals(bytes(62, 0, 0xFF, 0x4F, 0xFF, 0x4F, 0x42, 0x10, 1, 0, 0, 0), out.copyOfRange(0, 12))
    }

    @Test
    fun crossBridgeRepeatsEachBroadcastFrameOnce() {
        val d = CrossBridgeDedup()
        val f = OshiMeshProtocol.parseData(OshiMeshProtocol.dataFrames(77u, CrossBridgeDedup.BROADCAST, ByteArray(10), origin = 1u)!![0])!!
        assertTrue(d.firstTime(f, nowMs = 0))
        assertFalse(d.firstTime(f, nowMs = 1_000))
        // The same frame re-stamped by an OSHI Mesh radio (other origin, same msgId) is still a repeat.
        assertFalse(d.firstTime(f.copy(origin = 2u), nowMs = 2_000))
        assertTrue(d.firstTime(f, nowMs = 31 * 60_000L))           // TTL over
        val unicast = OshiMeshProtocol.parseData(OshiMeshProtocol.dataFrames(78u, 5u, ByteArray(10))!![0])!!
        assertFalse(d.firstTime(unicast))
    }

    // ─────────────────────────── MeshCore users (frames built from MyMesh.cpp layouts) ───────────────────────────

    private val alice = ByteArray(32) { (0xA0 + it).toByte() }
    private val bob = ByteArray(32) { (0xB0 + it).toByte() }

    private fun le32(v: Long) = bytes((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())

    /** writeContactRespFrame: code, pub_key 32, type, flags, out_path_len, out_path 64, name 32, last_advert, lat, lon, lastmod. */
    private fun contactFrame(code: Int, key: ByteArray, type: Int, name: String, lastAdvert: Long = 1_700_000_000, lastMod: Long = 1_700_000_100): ByteArray {
        val n = name.toByteArray()
        return bytes(code) + key + bytes(type, 0, 0xFF) + ByteArray(64) + n + ByteArray(32 - n.size) +
            le32(lastAdvert) + le32(48_850_000) + le32(2_350_000) + le32(lastMod)
    }

    private fun channelInfo(idx: Int, name: String, secret: ByteArray): ByteArray {
        val n = name.toByteArray()
        return bytes(18, idx) + n + ByteArray(32 - n.size) + secret
    }

    /** A session through setup: slot 0 Public, 1 #oshi-bridge, 2 #paris, 3 "team" (private), rest empty; Alice + Bob known. */
    private fun chattingSession(): MeshCoreSession {
        val s = MeshCoreSession()
        s.start(); s.nextCommand(0)
        s.onFrame(bytes(5, 1, 22, 22) + ByteArray(32) + ByteArray(22) + "HugoNode".toByteArray())
        assertEquals("HugoNode", s.selfName)
        val slots = listOf(
            channelInfo(0, "Public", MeshCore.PUBLIC_CHANNEL_SECRET),
            channelInfo(1, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET),
            channelInfo(2, "#paris", MeshCore.hashtagSecret("#paris")),
            channelInfo(3, "team", ByteArray(16) { 7 }),
        ) + (4 until 8).map { channelInfo(it, "", ByteArray(16)) }
        for (f in slots) { assertNotNull(s.nextCommand(0)); s.onFrame(f) }
        assertTrue(s.isReady); assertTrue(s.chatReady)
        assertArrayEquals(MeshCore.getContacts(), s.nextCommand(0))
        s.onFrame(bytes(2) + le32(3))
        s.onFrame(contactFrame(3, alice, MeshCore.ADV_TYPE_CHAT, "Alice"))
        s.onFrame(contactFrame(3, bob, MeshCore.ADV_TYPE_CHAT, "Bob"))
        s.onFrame(contactFrame(3, ByteArray(32) { 0x11 }, MeshCore.ADV_TYPE_REPEATER, "Hilltop"))
        val end = s.onFrame(bytes(4) + le32(1_700_000_100))
        assertTrue(end.single() is MeshCoreSession.Event.ContactsChanged)
        assertArrayEquals(MeshCore.syncNextMessage, s.nextCommand(0))
        s.onFrame(bytes(MeshCore.RESP_NO_MORE_MESSAGES))
        return s
    }

    @Test
    fun contactFrameIsParsedFromTheFirmwareLayout() {
        val f = contactFrame(3, alice, MeshCore.ADV_TYPE_CHAT, "Alice")
        assertEquals(MeshCore.CONTACT_FRAME_BYTES, f.size)
        val c = MeshCore.parseContact(f)!!
        assertArrayEquals(alice, c.publicKey)
        assertEquals(MeshCore.ADV_TYPE_CHAT, c.type)
        assertEquals("Alice", c.name)
        assertEquals(1_700_000_000L, c.lastAdvert)
        assertEquals(1_700_000_100L, c.lastMod)
        assertEquals("Bob", MeshCore.parseContact(contactFrame(0x8A, bob, 1, "Bob"))!!.name)   // PUSH_CODE_NEW_ADVERT
        assertNull(MeshCore.parseContact(f.copyOf(147)))
    }

    @Test
    fun directTextOldAndV3Layouts() {
        val prefix = alice.copyOf(6)
        val v2 = bytes(7) + prefix + bytes(0xFF, 0) + le32(1_700_000_123) + "salut".toByteArray()
        val d = MeshCore.parseDirectText(v2)!!
        assertArrayEquals(prefix, d.senderPrefix)
        assertEquals(0xFF, d.pathLen)
        assertEquals(1_700_000_123L, d.senderTimestamp)
        assertEquals("salut", d.text)
        val v3 = bytes(16, 0x28, 0, 0) + prefix + bytes(2, 0) + le32(5) + "é ok".toByteArray()
        assertEquals("é ok", MeshCore.parseDirectText(v3)!!.text)
        // TXT_TYPE_SIGNED_PLAIN (room servers) carries the author's 4-byte prefix before the text.
        val signed = bytes(7) + prefix + bytes(0, 2) + le32(5) + bytes(1, 2, 3, 4) + "post".toByteArray()
        assertEquals("post", MeshCore.parseDirectText(signed)!!.text)
    }

    @Test
    fun channelTextOldAndV3LayoutsAndSenderName() {
        val v2 = bytes(8, 2, 0xFF, 0) + le32(9) + "Alice: hello #paris".toByteArray()
        val t = MeshCore.parseChannelText(v2)!!
        assertEquals(2, t.channelIdx)
        assertEquals("Alice: hello #paris", t.text)
        val v3 = bytes(17, 0xF0, 0, 0, 3, 1, 0) + le32(9) + "Bob: yo".toByteArray()
        assertEquals(3, MeshCore.parseChannelText(v3)!!.channelIdx)
        assertEquals("Alice" to "hello #paris", MeshCore.splitChannelSender(t.text))
        assertEquals(null to "no prefix here", MeshCore.splitChannelSender("no prefix here"))
    }

    @Test
    fun sendCommandsAreByteExact() {
        // CMD_SEND_TXT_MSG: 2, txt_type, attempt, timestamp LE32, pubkey prefix 6, text
        assertArrayEquals(
            bytes(2, 0, 3, 0x78, 0x56, 0x34, 0x12, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5) + "hi".toByteArray(),
            MeshCore.sendTextMessage(3, 0x12345678, alice.copyOf(6), "hi"),
        )
        // CMD_SEND_CHANNEL_TXT_MSG: 3, txt_type, channel idx, timestamp LE32, text
        assertArrayEquals(bytes(3, 0, 2, 1, 0, 0, 0) + "yo".toByteArray(), MeshCore.sendChannelText(2, 1, "yo"))
        assertArrayEquals(bytes(4), MeshCore.getContacts())
        assertArrayEquals(bytes(4, 0x10, 0, 0, 0), MeshCore.getContacts(16))
        // RESP_CODE_SENT / PUSH_CODE_SEND_CONFIRMED
        val sent = MeshCore.parseSent(bytes(6, 1, 0xEF, 0xBE, 0xAD, 0xDE, 0x10, 0x27, 0, 0))!!
        assertTrue(sent.flood)
        assertEquals(0xDEADBEEFL, sent.ackTag)
        assertEquals(10_000L, sent.estTimeoutMs)
        assertEquals(0xDEADBEEFL, MeshCore.parseSendConfirmed(bytes(0x82, 0xEF, 0xBE, 0xAD, 0xDE, 1, 0, 0, 0)))
    }

    @Test
    fun hashtagSecretIsSha256OfTheNameWithItsHash() {
        assertArrayEquals(OshiBridgeWire.CHANNEL_SECRET, MeshCore.hashtagSecret("#oshi-bridge"))
        assertArrayEquals(MeshCore.hashtagSecret("#paris"), MeshCore.hashtagSecret("paris"))
        assertFalse(MeshCore.hashtagSecret("#Paris").contentEquals(MeshCore.hashtagSecret("#paris")))
        assertTrue(MeshCore.isPublicChannel("#paris", MeshCore.hashtagSecret("#paris")))
        assertTrue(MeshCore.isPublicChannel("Public", MeshCore.PUBLIC_CHANNEL_SECRET))
        assertFalse(MeshCore.isPublicChannel("#paris", ByteArray(16) { 7 }))   // a '#' name with a private key
        assertEquals("8b3387e9c5cdea6ac9e5edbaa115cd72", hex(MeshCore.PUBLIC_CHANNEL_SECRET))  // izOH6cXN6mrJ5e26oRXNcg==
        val secret = ByteArray(16) { it.toByte() }
        assertArrayEquals(secret, MeshCore.parseSecret("000102030405060708090a0b0c0d0e0f"))
        assertArrayEquals(secret, MeshCore.parseSecret("AAECAwQFBgcICQoLDA0ODw=="))
        assertArrayEquals(secret, MeshCore.parseSecret("AAECAwQFBgcICQoLDA0ODw"))
        assertNull(MeshCore.parseSecret("abcd"))
    }

    @Test
    fun sessionSurfacesDirectAndChannelTextAndHidesTheBridge() {
        val s = chattingSession()
        assertEquals(listOf("Public", "#paris", "team"), s.userChannels.map { it.name })
        assertEquals(4, s.freeChannelSlot())
        s.onFrame(bytes(0x83))                                                // PUSH_CODE_MSG_WAITING
        assertArrayEquals(MeshCore.syncNextMessage, s.nextCommand(0))
        val dm = s.onFrame(bytes(7) + alice.copyOf(6) + bytes(0xFF, 0) + le32(5) + "salut".toByteArray())
            .single() as MeshCoreSession.Event.DirectText
        assertEquals("Alice", dm.contact!!.name)
        assertEquals("salut", dm.text)
        assertArrayEquals(MeshCore.syncNextMessage, s.nextCommand(0))        // keeps pulling the queue
        val unknown = s.onFrame(bytes(16, 0, 0, 0) + bytes(1, 2, 3, 4, 5, 6) + bytes(0, 0) + le32(5) + "?".toByteArray())
            .single() as MeshCoreSession.Event.DirectText
        assertNull(unknown.contact)
        s.nextCommand(0)
        val ch = s.onFrame(bytes(8, 2, 0, 0) + le32(5) + "Alice: on y va".toByteArray()).single() as MeshCoreSession.Event.ChannelText
        assertEquals("#paris", ch.channel!!.name)
        assertEquals("Alice", ch.senderName)
        assertEquals("on y va", ch.text)
        s.nextCommand(0)
        assertTrue(s.onFrame(bytes(8, 1, 0, 0) + le32(5) + "x: on the bridge".toByteArray()).isEmpty())   // #oshi-bridge: hidden
        s.nextCommand(0)
        // CLI replies are not conversation.
        assertTrue(s.onFrame(bytes(7) + alice.copyOf(6) + bytes(0, 1) + le32(5) + "ok".toByteArray()).isEmpty())
    }

    @Test
    fun directMessageIsRetriedThenDeliveredOnItsAck() {
        val s = chattingSession()
        assertTrue(s.sendDirect("m1", alice.copyOf(6), "hello", nowMs = 1_000_000))
        val cmd0 = s.nextCommand(1_000_000)!!
        assertArrayEquals(MeshCore.sendTextMessage(0, 1_000, alice.copyOf(6), "hello"), cmd0)
        val sent = s.onFrame(bytes(6, 1) + le32(0x1111) + le32(4_000), nowMs = 1_000_000)
        assertEquals(MeshCoreSession.SendState.SENT, (sent.single() as MeshCoreSession.Event.Send).state)
        // No ACK within max(4000 × 1.5, 6000) ms: attempt 1, same timestamp.
        assertTrue(s.onTick(1_005_000).isEmpty())
        assertNull(s.nextCommand(1_005_000))
        s.onTick(1_006_001)
        assertArrayEquals(MeshCore.sendTextMessage(1, 1_000, alice.copyOf(6), "hello"), s.nextCommand(1_006_001))
        s.onFrame(bytes(6, 1) + le32(0x2222) + le32(4_000), nowMs = 1_006_001)
        // An ACK for the FIRST attempt still counts: the recipient got it.
        val done = s.onFrame(bytes(0x82) + le32(0x1111) + le32(900)).single() as MeshCoreSession.Event.Send
        assertEquals("m1", done.messageId)
        assertEquals(MeshCoreSession.SendState.DELIVERED, done.state)
        assertTrue(s.onTick(2_000_000).isEmpty())                            // nothing left to retry
    }

    @Test
    fun directMessageFailsAfterTheFourthUnackedAttempt() {
        val s = chattingSession()
        s.sendDirect("m2", bob.copyOf(6), "anyone?", nowMs = 0)
        var now = 0L
        val states = ArrayList<MeshCoreSession.SendState>()
        for (attempt in 0..3) {
            val cmd = s.nextCommand(now)!!
            assertEquals(attempt, cmd[2].toInt())
            s.onFrame(bytes(6, 0) + le32(100L + attempt) + le32(1_000), nowMs = now).forEach { states += (it as MeshCoreSession.Event.Send).state }
            now += MeshCoreSession.MIN_ACK_WAIT_MS + 1
            s.onTick(now).forEach { states += (it as MeshCoreSession.Event.Send).state }
        }
        assertEquals(listOf(MeshCoreSession.SendState.SENT, MeshCoreSession.SendState.FAILED), states)
        assertNull(s.nextCommand(now))
        // The radio refusing outright (unknown prefix → ERR NOT_FOUND) fails at once.
        s.sendDirect("m3", bytes(9, 9, 9, 9, 9, 9), "x", nowMs = now)
        assertNull(s.nextCommand(now))                                       // the 1.5 s gap after the last attempt
        now += MeshCoreSession.PART_GAP_MS
        s.onTick(now)
        assertNotNull(s.nextCommand(now))
        assertEquals(MeshCoreSession.SendState.FAILED, (s.onFrame(bytes(1, 2), nowMs = now).single() as MeshCoreSession.Event.Send).state)
    }

    @Test
    fun channelMessageIsSentNeverDeliveredAndLongTextIsSplit() {
        val s = chattingSession()
        assertFalse(s.sendChannel("c0", 1, "into the bridge?", nowMs = 0))    // #oshi-bridge is not a chat channel
        val long = "word ".repeat(70).trim()                                    // 349 chars
        assertTrue(s.sendChannel("c1", 2, long, nowMs = 10_000))
        val events = ArrayList<MeshCoreSession.Event>()
        val cmds = ArrayList<ByteArray>()
        val sentAt = ArrayList<Long>()
        var now = 10_000L
        while (now < 60_000) {
            val c = s.nextCommand(now)
            if (c == null) { now += 100; events += s.onTick(now); continue }
            cmds += c; sentAt += now
            events += s.onFrame(bytes(MeshCore.RESP_OK), nowMs = now)
        }
        assertTrue(cmds.size >= 3)
        // One part at a time, 1.5 s apart (iOS textPartGap): back-to-back parts collided on real radios.
        sentAt.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a}", b - a >= MeshCoreSession.PART_GAP_MS) }
        cmds.forEachIndexed { i, c ->
            assertArrayEquals(bytes(3, 0, 2) + le32(10L + i), c.copyOfRange(0, 7))
            val text = String(c, 7, c.size - 7)
            assertTrue(text, text.startsWith("(${i + 1}/${cmds.size}) "))
            // fits the firmware's 160 bytes after "HugoNode: "
            assertTrue(text.toByteArray().size + "HugoNode: ".length <= MeshCore.MAX_TEXT_BYTES)
        }
        assertEquals(listOf(MeshCoreSession.SendState.SENT), events.map { (it as MeshCoreSession.Event.Send).state })
        assertTrue(s.onTick(10_000_000).isEmpty())                            // never retried, never "delivered"
    }

    @Test
    fun textSplittingKeepsEveryPartWithinBothLimits() {
        assertEquals(listOf("short"), MeshCoreText.split("short", MeshCoreText.DM_MAX_BYTES))
        val text = "Ceci est un message assez long pour être découpé en plusieurs morceaux numérotés. ".repeat(6)
        val parts = MeshCoreText.split(text, MeshCoreText.DM_MAX_BYTES)!!
        assertTrue(parts.size > 1)
        parts.forEachIndexed { i, p ->
            assertTrue(p.startsWith("(${i + 1}/${parts.size}) "))
            assertTrue(p.codePointCount(0, p.length) <= MeshCore.RECOMMENDED_TEXT_CHARS)
            assertTrue(p.toByteArray().size <= MeshCoreText.DM_MAX_BYTES)
        }
        assertEquals(text.trim().split(" "), parts.joinToString(" ") { it.substringAfter(") ") }.split(" "))
        // Multi-byte text is cut on code points, never inside one.
        val emoji = "😀".repeat(200)
        MeshCoreText.split(emoji, 100)!!.forEach { p ->
            assertFalse(p.contains('�'))
            assertTrue(p.toByteArray().size <= 100)
        }
        assertTrue(MeshCoreText.channelMaxBytes("HugoNode") > MeshCoreText.channelMaxBytes(null))
    }

    @Test
    fun conversationKeysForMeshCoreUsers() {
        val c = MeshCore.parseContact(contactFrame(3, alice, 1, "Alice"))!!
        val key = MeshCoreThread.contactKey(alice)
        assertEquals("meshcore!" + hex(alice), key)
        assertEquals(key, MeshCoreThread.contactKeyFor(alice.copyOf(6), c))
        assertEquals("meshcore!a0a1a2a3a4a5", MeshCoreThread.contactKeyFor(alice.copyOf(6), null))
        assertArrayEquals(alice.copyOf(6), MeshCoreThread.publicKeyPrefix(key))
        assertArrayEquals(alice.copyOf(6), MeshCoreThread.publicKeyPrefix("meshcore!a0a1a2a3a4a5"))
        assertEquals("Alice", MeshCoreThread.displayName("meshcore!a0a1a2a3a4a5", listOf(c)))
        assertEquals("MeshCore a0a1a2", MeshCoreThread.displayName("meshcore!a0a1a2a3a4a5"))
        assertTrue(MeshCoreThread.isMeshCoreKey(key))
        // Never confused with the Meshtastic interop keys, and those never with these.
        assertFalse(MeshCoreThread.isMeshCoreKey("lora!a1b2c3d4"))
        assertFalse(MeshCoreThread.isMeshCoreKey("lorachan!longfast"))
        assertFalse(LoRaChannelThread.isChannelKey("mcchan!#paris"))
        assertFalse(LoRaNodeIdentityMap.isFallbackConversationKey(key))
        // Channels keep their exact name (a hashtag key is case-sensitive) and never resolve to the bridge.
        val chans = listOf(
            MeshCore.ChannelInfo(0, "Public", MeshCore.PUBLIC_CHANNEL_SECRET),
            MeshCore.ChannelInfo(2, "#paris", MeshCore.hashtagSecret("#paris")),
            MeshCore.ChannelInfo(3, "team", ByteArray(16) { 7 }),
        )
        assertEquals("mcchan!#paris", MeshCoreThread.channelKey("#paris"))
        assertEquals(2, MeshCoreThread.channelIndexFor("mcchan!#paris", chans))
        assertNull(MeshCoreThread.channelIndexFor("mcchan!#Paris", chans))
        assertNull(MeshCoreThread.channelIndexFor("mcchan!#oshi-bridge", chans + MeshCore.ChannelInfo(1, "#oshi-bridge", OshiBridgeWire.CHANNEL_SECRET)))
        assertTrue(MeshCoreThread.isPublicChannelKey("mcchan!#paris", chans))
        assertTrue(MeshCoreThread.isPublicChannelKey("mcchan!Public", chans))
        assertFalse(MeshCoreThread.isPublicChannelKey("mcchan!team", chans))
        assertTrue(MeshCoreThread.isPublicChannelKey("mcchan!#unknown", emptyList()))       // unknown: warn
        assertEquals(listOf("Alice"), MeshCoreThread.chatContacts(listOf(c, c.copy(type = 2, name = "Rptr"))).map { it.name })
    }

    @Test
    fun joiningAChannelUsesAFreeSlotAndNeverTheBridge() {
        val s = chattingSession()
        assertEquals(4, s.freeChannelSlot())
        assertFalse(s.joinChannel(1, "#x", MeshCore.hashtagSecret("#x")))           // the bridge slot
        assertFalse(s.joinChannel(4, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET))
        assertTrue(s.joinChannel(4, "#lyon", MeshCore.hashtagSecret("#lyon")))
        assertArrayEquals(MeshCore.setChannel(4, "#lyon", MeshCore.hashtagSecret("#lyon")), s.nextCommand(0))
        val ev = s.onFrame(bytes(MeshCore.RESP_OK))
        assertTrue(ev.any { it is MeshCoreSession.Event.ChannelJoin && it.ok })
        assertEquals(listOf("Public", "#paris", "team", "#lyon"), s.userChannels.map { it.name })
        assertEquals(5, s.freeChannelSlot())
    }

    @Test
    fun advertsUpdateTheContactTable() {
        val s = chattingSession()
        val carol = ByteArray(32) { (0xC0 + it).toByte() }
        val ev = s.onFrame(contactFrame(0x8A, carol, 1, "Carol"))
        assertTrue(ev.any { it is MeshCoreSession.Event.ContactsChanged })
        assertEquals("Carol", s.contactFor(carol.copyOf(6))!!.name)
        // A known contact re-advertising: only "heard"; an unknown key: ask the radio for it.
        assertTrue(s.onFrame(bytes(0x80) + alice).single() is MeshCoreSession.Event.ContactHeard)
        val dave = ByteArray(32) { 0x0D }
        s.onFrame(bytes(0x80) + dave)
        assertArrayEquals(MeshCore.getContactByKey(dave), s.nextCommand(0))
        s.onFrame(contactFrame(3, dave, 1, "Dave"))
        assertEquals("Dave", s.contactFor(dave.copyOf(6))!!.name)
        s.onFrame(bytes(0x8F) + carol)
        assertNull(s.contactFor(carol.copyOf(6)))
    }

    @Test
    fun aMeshCoreOnlyPhoneIsARadioReadyPhone() {
        assertTrue(LoRaRadios.anyReady(meshtasticReady = false, meshCoreReady = true))
        assertTrue(LoRaRadios.anyReady(meshtasticReady = true, meshCoreReady = false))
        assertFalse(LoRaRadios.anyReady(meshtasticReady = false, meshCoreReady = false))
        assertTrue(LoRaRadios.routeJammed(meshtasticReady = true, meshtasticJammed = true, meshCoreReady = false))
        assertFalse(LoRaRadios.routeJammed(meshtasticReady = true, meshtasticJammed = true, meshCoreReady = true))
        // A MeshCore #oshi-bridge broadcast has no ACK: sent, not delivered.
        assertFalse(LoRaRadios.handOffCountsAsDelivered(viaMeshtastic = false, viaMeshCore = true))
        assertTrue(LoRaRadios.handOffCountsAsDelivered(viaMeshtastic = true, viaMeshCore = true))
    }

    @Test
    fun aLongDirectMessageWaitsForEachAckThenPacesTheNextPart() {
        val s = chattingSession()
        val text = "Ceci est un message assez long pour être découpé en plusieurs morceaux numérotés. ".repeat(4)
        assertTrue(s.sendDirect("long", alice.copyOf(6), text, nowMs = 100_000))
        assertEquals(setOf("long"), s.pendingTextIds)                     // clock: the radio has not taken it
        val p1 = s.nextCommand(100_000)!!
        assertEquals("(1/", String(p1, 13, 3))
        assertNull(s.nextCommand(100_000))                                // one request in flight
        val sent = s.onFrame(bytes(6, 0) + le32(0xA1) + le32(3_000), nowMs = 100_200)
        assertEquals(MeshCoreSession.SendState.SENT, (sent.single() as MeshCoreSession.Event.Send).state)
        assertTrue(s.pendingTextIds.isEmpty())                            // on the air: one tick
        // Part 2 waits for part 1's ACK: nothing goes out meanwhile, whatever the clock says.
        s.onTick(102_000); assertNull(s.nextCommand(102_000))
        assertTrue(s.onFrame(bytes(0x82) + le32(0xA1) + le32(700), nowMs = 103_000).isEmpty())
        s.onTick(104_000); assertNull(s.nextCommand(104_000))             // < 1.5 s after the ACK
        s.onTick(104_500)
        val p2 = s.nextCommand(104_500)!!
        assertEquals("(2/", String(p2, 13, 3))
        assertEquals(le32(101).toList(), p2.copyOfRange(3, 7).toList())  // its own timestamp: base + part index
        s.onFrame(bytes(6, 0) + le32(0xA2) + le32(3_000), nowMs = 104_600)
        var delivered: MeshCoreSession.Event.Send? = null
        var now = 104_600L
        var acks = 0xA2L
        while (delivered == null && now < 200_000) {
            s.onFrame(bytes(0x82) + le32(acks) + le32(700), nowMs = now).filterIsInstance<MeshCoreSession.Event.Send>().firstOrNull()?.let { delivered = it }
            now += MeshCoreSession.PART_GAP_MS
            s.onTick(now)
            val next = s.nextCommand(now) ?: continue
            acks++
            s.onFrame(bytes(6, 0) + le32(acks) + le32(3_000), nowMs = now)
            assertTrue(String(next, 13, next.size - 13).startsWith("("))
        }
        assertEquals(MeshCoreSession.SendState.DELIVERED, delivered!!.state)
    }

    @Test
    fun messagesQueueBehindEachOtherAndALostLinkFailsWhatWaits() {
        val s = chattingSession()
        assertTrue(s.sendChannel("c", 2, "salut le canal", nowMs = 0))
        assertTrue(s.sendDirect("d", bob.copyOf(6), "et toi", nowMs = 0))
        assertEquals(setOf("c", "d"), s.pendingTextIds)
        assertEquals(MeshCore.CMD_SEND_CHANNEL_TXT_MSG, s.nextCommand(0)!![0].toInt())
        s.onFrame(bytes(MeshCore.RESP_OK), nowMs = 100)
        assertNull(s.nextCommand(100))                                    // the DM waits its turn (gap)
        s.onTick(1_600)
        assertEquals(MeshCore.CMD_SEND_TXT_MSG, s.nextCommand(1_600)!![0].toInt())
        // The radio goes away before answering: the DM fails (tap to retry), the channel message stays sent.
        s.reset()
        val failed = s.onTick(2_000).filterIsInstance<MeshCoreSession.Event.Send>()
        assertEquals(listOf("d" to MeshCoreSession.SendState.FAILED), failed.map { it.messageId to it.state })
        assertTrue(s.pendingTextIds.isEmpty())
    }

    @Test
    fun aContactOnlyHeardIsAddedToTheRadioBeforeTheFirstDm() {
        val s = chattingSession()
        val carol = ByteArray(32) { (0xC0 + it).toByte() }
        val advert = contactFrame(0x8A, carol, 1, "Carol")
        s.onFrame(advert)                                                  // auto-add off: heard, not stored
        assertTrue(s.sendDirect("m", carol.copyOf(6), "bonjour", nowMs = 0))
        val add = s.nextCommand(0)!!
        assertEquals(MeshCore.CMD_ADD_UPDATE_CONTACT, add[0].toInt())
        assertArrayEquals(advert.copyOfRange(1, advert.size), add.copyOfRange(1, add.size))
        s.onFrame(bytes(MeshCore.RESP_OK), nowMs = 0)
        assertArrayEquals(MeshCore.sendTextMessage(0, 0, carol.copyOf(6), "bonjour"), s.nextCommand(0))
        s.onFrame(bytes(6, 0) + le32(9) + le32(1_000), nowMs = 0)
        // Once on the radio, the next message goes straight out.
        s.onFrame(bytes(0x82) + le32(9) + le32(1), nowMs = 1_000)
        s.onTick(5_000)
        assertTrue(s.sendDirect("m2", carol.copyOf(6), "encore", nowMs = 5_000))
        assertEquals(MeshCore.CMD_SEND_TXT_MSG, s.nextCommand(5_000)!![0].toInt())
    }

    @Test
    fun announcingThisRadioIsOneFloodAdvert() {
        val s = chattingSession()
        // Too long for 20 radio messages: refused, nothing queued (the composer said "too long").
        assertFalse(s.sendDirect("big", alice.copyOf(6), "z".repeat(125 * 20 + 1), nowMs = 0))
        assertFalse(s.sendChannel("big2", 2, "z".repeat(125 * 20 + 1), nowMs = 0))
        assertTrue(s.sendSelfAdvert())
        assertArrayEquals(bytes(7, 1), s.nextCommand(0))
        assertTrue((s.onFrame(bytes(MeshCore.RESP_OK)).single() as MeshCoreSession.Event.AdvertSent).ok)
        assertFalse(MeshCoreSession().sendSelfAdvert())                    // not connected: nothing queued
    }
}
