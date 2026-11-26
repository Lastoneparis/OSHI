package com.oshi.messenger.data.repository

import com.oshi.messenger.R
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.service.lora.MeshCore
import com.oshi.messenger.service.lora.MeshCoreLink
import com.oshi.messenger.service.lora.MeshCoreSession
import com.oshi.messenger.service.lora.MeshCoreThread
import com.oshi.messenger.ui.screens.MeshCoreUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MESHCORE_INTEROP_2026_11_25__ The whole Android path between an OSHI user and plain MeshCore users, on the
 * JVM: a fake Bluetooth link replays frames captured from REAL MeshCore companion firmware (v1.18.0, two
 * T1000-E radios, test resource meshcore/real-frames-v1.18.0.txt) into [MeshCoreLink] (the code the BLE
 * manager runs), whose events drive [MeshCoreChats] (the code MessageRepository runs) into an in-memory store;
 * what the person sees is then read through [MeshCoreUi] (the decisions ChatScreen draws).
 *
 * This phone holds radio A ("OSHI-TEA-MC"); B ("OSHI-TEB-MC") is the MeshCore user in its contact table.
 * Outgoing traffic is asserted byte for byte as written to the radio; the radio's answers are the captured
 * SENT / SEND_CONFIRMED / OK frames, so a parser drift against the firmware fails here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshCoreEndToEndTest {

    // ─────────────────────────── Fixtures ───────────────────────────

    private val fx: Map<String, ByteArray> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("meshcore/real-frames-v1.18.0.txt")!!.bufferedReader().readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (k, v) = line.split("=", limit = 2)
            k to MeshCore.unhex(v.trim())!!
        }
    }
    private fun f(name: String) = fx[name] ?: error("missing fixture $name")
    private val keyB get() = MeshCore.parseContact(f("contact_B_in_A"))!!.publicKey
    private val threadB get() = MeshCoreThread.contactKey(keyB)
    private val myKey = "OSHI-me"

    // ─────────────────────────── In-memory app ───────────────────────────

    private class Store : MeshCoreChats.Store {
        val messages = LinkedHashMap<String, Message>()
        val contacts = LinkedHashMap<String, String>()
        val notifications = ArrayList<Pair<String, String>>()
        override suspend fun insert(message: Message) { messages[message.id] = message }
        override suspend fun message(id: String) = messages[id]
        override suspend fun update(message: Message) { messages[message.id] = message }
        override suspend fun contactAlias(key: String) = contacts[key]
        override suspend fun insertContact(key: String, alias: String) { contacts[key] = alias }
        override suspend fun renameContact(key: String, alias: String) { contacts[key] = alias }
        override suspend fun isBlocked(key: String) = false
        override suspend fun notify(key: String, preview: String) { notifications += key to preview }
        fun thread(key: String) = messages.values.filter { it.senderPublicKey == key || it.recipientPublicKey == key }
    }

    /**
     * Radio A as the app sees it over Bluetooth: every write is recorded with the virtual time it happened at, and
     * answered (after [LATENCY_MS]) the way the real firmware answered during the capture.
     */
    private inner class FakeRadio(val scope: TestScope) {
        val writes = ArrayList<Pair<Long, ByteArray>>()
        /** What SEND_TXT_MSG answers; null = the firmware's SENT frame with a fresh tag. */
        var dmAnswer: ((ByteArray) -> ByteArray)? = null
        var nextTag = 0x1000L
        /** Tags handed out, in order, so a test can confirm (ACK) them. */
        val tags = ArrayList<Long>()
        /** Queued incoming messages the radio hands over on SYNC_NEXT_MESSAGE. */
        val inbox = ArrayDeque<ByteArray>()
        lateinit var link: MeshCoreLink

        fun write(cmd: ByteArray): Boolean {
            writes += scope.testScheduler.currentTime to cmd
            val answers: List<ByteArray> = when (cmd[0].toInt() and 0xFF) {
                MeshCore.CMD_APP_START -> listOf(f("self_info_A_named"))
                MeshCore.CMD_GET_CHANNEL -> listOf(
                    when (val i = cmd[1].toInt()) {
                        0 -> f("channel_info_public")
                        1 -> f("channel_info_bridge_slot1")
                        2 -> f("channel_info_hashtag_readback")
                        else -> f("channel_info_empty_slot_with_residue").copyOf().also { it[1] = i.toByte() }
                    }
                )
                MeshCore.CMD_GET_CONTACTS -> listOf(f("contacts_start"), f("contact_B_in_A"), f("end_of_contacts"))
                MeshCore.CMD_SYNC_NEXT_MESSAGE -> listOf(inbox.removeFirstOrNull() ?: byteArrayOf(MeshCore.RESP_NO_MORE_MESSAGES.toByte()))
                MeshCore.CMD_SEND_TXT_MSG -> listOf(dmAnswer?.invoke(cmd) ?: sent())
                MeshCore.CMD_SEND_CHANNEL_TXT_MSG -> listOf(byteArrayOf(MeshCore.RESP_OK.toByte()))
                else -> listOf(byteArrayOf(MeshCore.RESP_OK.toByte()))
            }
            scope.launch { delay(LATENCY_MS); answers.forEach { link.onFrame(it) } }
            return true
        }

        /** The captured RESP_SENT (flood, est. timeout 7 s) with a new ACK tag. */
        fun sent(): ByteArray = f("resp_sent_direct_dm1").copyOf().also {
            val t = nextTag++
            tags += t
            for (i in 0 until 4) it[2 + i] = (t shr (8 * i)).toByte()
        }

        /** The captured PUSH_SEND_CONFIRMED carrying [tag]. */
        fun confirm(tag: Long) = f("push_send_confirmed").copyOf().also { for (i in 0 until 4) it[1 + i] = (tag shr (8 * i)).toByte() }

        /** Something arrived over the air: the radio says PUSH_MSG_WAITING and hands it over on SYNC. */
        fun deliver(frame: ByteArray) {
            inbox.addLast(frame)
            link.onFrame(byteArrayOf(MeshCore.PUSH_MSG_WAITING.toByte()))
        }

        fun textWrites() = writes.filter {
            val c = it.second[0].toInt()
            c == MeshCore.CMD_SEND_TXT_MSG || c == MeshCore.CMD_SEND_CHANNEL_TXT_MSG
        }
    }

    private class App(val radio: FakeRadio, val link: MeshCoreLink, val chats: MeshCoreChats, val store: Store)

    /** A connected phone: radio A paired, setup exchange done, collectors running like MessageRepository's. */
    private fun TestScope.connectedApp(): App {
        val store = Store()
        val radio = FakeRadio(this)
        val link = MeshCoreLink(backgroundScope, write = { radio.write(it) }, now = { testScheduler.currentTime + EPOCH_MS }, appName = "OSHI")
        radio.link = link
        val chats = MeshCoreChats(store, radio = { link }, myKey = { myKey }, now = { testScheduler.currentTime + EPOCH_MS }, newId = { "row-" + store.messages.size })
        backgroundScope.launch { link.inboundText.collect { chats.receive(it) } }
        backgroundScope.launch { link.sendOutcomes.collect { chats.apply(it) } }
        runCurrent()
        link.linkUp()
        advanceTimeBy(5_000); runCurrent()
        assertTrue("setup finished on the captured frames", link.isChatReady)
        return App(radio, link, chats, store)
    }

    private fun TestScope.settle(ms: Long = 2_000) { advanceTimeBy(ms); runCurrent() }

    // ─────────────────────────── Connection ───────────────────────────

    @Test
    fun connectingReadsTheRadioAndListsItsPeopleAndChannels() = runTest {
        val app = connectedApp()
        assertEquals("OSHI-TEA-MC", app.link.selfName.value)                     // "MeshCore users see you as …"
        assertEquals(listOf("OSHI-TEB-MC"), MeshCoreThread.chatContacts(app.link.contacts.value).map { it.name })
        // The owner's channels, "#oshi-bridge" hidden; Public first.
        assertEquals(listOf("Public", "#oshi-hw-test"), app.link.channels.value.map { it.name })
        assertEquals(1, app.link.channelIdx.value)
        assertEquals(R.string.meshcore_note_public, MeshCoreUi.threadNote(MeshCoreThread.channelKey("Public"), app.link.channels.value))
        // Setup on the wire: APP_START, 8 × GET_CHANNEL, GET_CONTACTS, SYNC — nothing written to a channel slot.
        val codes = app.radio.writes.map { it.second[0].toInt() }
        assertEquals(MeshCore.CMD_APP_START, codes.first())
        assertEquals(8, codes.count { it == MeshCore.CMD_GET_CHANNEL })
        assertFalse(codes.contains(MeshCore.CMD_SET_CHANNEL))
    }

    // ─────────────────────────── Receiving ───────────────────────────

    @Test
    fun aDirectMessageFromAMeshCoreUserOpensTheirThreadAndNotifies() = runTest {
        val app = connectedApp()
        app.radio.deliver(f("contact_msg_recv_v3"))                              // B → A, v3 layout
        settle()
        val row = app.store.thread(threadB).single()
        assertEquals("v3 format check", row.content)
        assertFalse(row.isFromMe)
        assertEquals(DeliveryMethod.LORA_PLAINTEXT, row.deliveryMethod)          // never drawn as OSHI E2E
        assertEquals("OSHI-TEB-MC", app.store.contacts[threadB])                 // the chat is listed under their name
        // The banner is for THIS thread: its key is what MainActivity opens ("senderKey" → chat/<key>).
        assertEquals(listOf(threadB to "v3 format check"), app.store.notifications)
        assertEquals(R.string.meshcore_note_dm, MeshCoreUi.threadNote(threadB, app.link.channels.value))
    }

    @Test
    fun aRetriedDirectMessageShowsOnceAndAnUnknownSenderStillGetsAThread() = runTest {
        val app = connectedApp()
        // The capture: an ACK lost to a collision, the sender retried, the radio handed over a byte-identical copy.
        app.radio.deliver(f("contact_msg_recv_v0_long_part2_first"))
        settle()
        app.radio.deliver(f("contact_msg_recv_v0_long_part2_retry"))
        settle()
        // That sender (key f00735…) is not in A's table: the thread is keyed on the 6-byte prefix, still answerable.
        val key = "meshcore!f007359830cf"
        assertEquals(1, app.store.thread(key).size)
        assertEquals("MeshCore f00735", app.store.contacts[key])
        assertEquals(1, app.store.notifications.size)
    }

    @Test
    fun channelMessagesLandInTheChannelThreadWithTheSpeakerSplitOff() = runTest {
        val app = connectedApp()
        app.radio.deliver(f("channel_msg_recv_v0_public"))
        settle()
        app.radio.deliver(f("channel_msg_recv_v3_hashtag"))
        settle()
        val pub = app.store.thread("mcchan!Public").single()
        assertEquals("Public", app.store.contacts["mcchan!Public"])
        assertEquals("OSHI-TEB-MC" to "OSHI test", MeshCoreUi.channelSpeaker(pub.content))   // name above, text inside
        val tag = app.store.thread("mcchan!#oshi-hw-test").single()
        assertEquals("OSHI-TEB-MC" to "v3 channel check", MeshCoreUi.channelSpeaker(tag.content))
        assertEquals(listOf("mcchan!Public", "mcchan!#oshi-hw-test"), app.store.notifications.map { it.first })
    }

    // ─────────────────────────── Sending ───────────────────────────

    @Test
    fun aDirectMessageGoesOutByteExactThenTicksSentThenDelivered() = runTest {
        val app = connectedApp()
        val sentAt = testScheduler.currentTime
        val row = app.chats.send(threadB, "OSHI interop test A to B")
        assertFalse(row.isFailed)
        // Before the radio answers: a clock.
        assertEquals(MeshCoreUi.BubbleStatus.SENDING, MeshCoreUi.bubbleStatus(app.store.messages[row.id]!!, app.link.pendingTexts.value))
        runCurrent()
        val written = app.radio.textWrites().single().second
        val ts = (sentAt + EPOCH_MS) / 1000
        assertArrayEquals(MeshCore.sendTextMessage(0, ts, keyB.copyOf(6), "OSHI interop test A to B"), written)
        assertEquals("020000", MeshCore.hex(written.copyOf(3)))                   // SEND_TXT_MSG, plain, attempt 0
        assertEquals("26aa91559d44", MeshCore.hex(written.copyOfRange(7, 13)))     // B's key prefix
        settle(500)
        // The radio took it (RESP_SENT): one tick.
        assertEquals(MeshCoreUi.BubbleStatus.SENT, MeshCoreUi.bubbleStatus(app.store.messages[row.id]!!, app.link.pendingTexts.value))
        // B's radio acknowledged (PUSH_SEND_CONFIRMED with that tag): two ticks.
        app.link.onFrame(app.radio.confirm(app.radio.tags.single()))
        settle(100)
        val after = app.store.messages[row.id]!!
        assertTrue(after.isDelivered)
        assertEquals(MeshCoreUi.BubbleStatus.DELIVERED, MeshCoreUi.bubbleStatus(after, app.link.pendingTexts.value))
        assertEquals(1, app.radio.textWrites().size)                              // delivered: never re-sent
    }

    @Test
    fun noAckFourTimesFailsWithRetryAndTheRetryDelivers() = runTest {
        val app = connectedApp()
        val row = app.chats.send(threadB, "anyone there?")
        settle(5 * 60_000)
        // Four attempts, same timestamp (the recipient's radio dedupes on it), attempt byte 0..3.
        val attempts = app.radio.textWrites().map { it.second }
        assertEquals(listOf(0, 1, 2, 3), attempts.map { it[2].toInt() })
        assertEquals(1, attempts.map { it.copyOfRange(3, 7).toList() }.distinct().size)
        val failed = app.store.messages[row.id]!!
        assertTrue(failed.isFailed)
        assertEquals(MeshCoreUi.BubbleStatus.FAILED, MeshCoreUi.bubbleStatus(failed, app.link.pendingTexts.value))
        assertEquals(R.string.meshcore_failed_dm, MeshCoreUi.failedText(failed, radioConnected = true))
        // Tap to retry = ChatViewModel.retrySendMessage: the row is replaced by a resend under the SAME id and date.
        app.store.messages.remove(row.id)
        val again = app.chats.send(threadB, failed.content, resendOf = failed)
        assertEquals(row.id, again.id)
        assertEquals(failed.timestamp, again.timestamp)
        settle(500)
        app.link.onFrame(app.radio.confirm(app.radio.tags.last()))
        settle(100)
        assertTrue(app.store.messages[row.id]!!.isDelivered)
        assertFalse(app.store.messages[row.id]!!.isFailed)
    }

    @Test
    fun aLongDirectMessageGoesAsNumberedPartsEachAfterTheLastAckPlusTheGap() = runTest {
        val app = connectedApp()
        val text = "This message is long enough that MeshCore cannot carry it in one radio packet, so OSHI numbers " +
            "the parts the way MeshCore apps show them, and waits for each acknowledgement before the next part."
        val count = MeshCoreUi.composerCount(text, isChannel = false, selfName = "OSHI-TEA-MC")
        assertTrue(count.visible && count.over)                                   // the composer said "N messages"
        val row = app.chats.send(threadB, text)
        // B's radio acknowledges each part 1 s after it is sent.
        var confirmed = 0
        repeat(80) {
            settle(250)
            while (confirmed < app.radio.tags.size && testScheduler.currentTime >= app.radio.writes.last().first + 1_000) {
                app.link.onFrame(app.radio.confirm(app.radio.tags[confirmed++]))
            }
        }
        val parts = app.radio.textWrites()
        assertEquals(count.parts, parts.size)
        parts.forEachIndexed { i, (_, cmd) ->
            assertTrue(String(cmd, 13, cmd.size - 13).startsWith("(${i + 1}/${parts.size}) "))
            assertTrue(cmd.size - 13 <= MeshCore.MAX_TEXT_BYTES)
        }
        // Never back to back: each part ≥ 1 s (its ACK) + 1.5 s (the gap) after the previous one.
        parts.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b.first - a.first}", b.first - a.first >= 2_500) }
        assertTrue(app.store.messages[row.id]!!.isDelivered)
    }

    @Test
    fun aChannelMessageIsSentNeverDelivered() = runTest {
        val app = connectedApp()
        val sentAt = testScheduler.currentTime
        val row = app.chats.send("mcchan!Public", "hello Public")
        settle(500)
        val cmd = app.radio.textWrites().single().second
        assertArrayEquals(MeshCore.sendChannelText(0, (sentAt + EPOCH_MS) / 1000, "hello Public"), cmd)
        settle(10 * 60_000)
        val after = app.store.messages[row.id]!!
        assertFalse(after.isDelivered); assertFalse(after.isFailed)
        assertEquals(MeshCoreUi.BubbleStatus.SENT, MeshCoreUi.bubbleStatus(after, app.link.pendingTexts.value))
        assertEquals(1, app.radio.textWrites().size)                              // a broadcast is never retried
    }

    // ─────────────────────────── No radio ───────────────────────────

    @Test
    fun withoutARadioNothingTypedIsLostAndTheThreadSaysWhy() = runTest {
        val app = connectedApp()
        // In flight when the radio goes away (walked out of Bluetooth range): FAILED, tap to retry.
        val inFlight = app.chats.send(threadB, "going out")
        app.link.linkDown()
        settle(1_000)
        assertTrue(app.store.messages[inFlight.id]!!.isFailed)
        // Written while disconnected: stored as not delivered right away, nothing written to any radio.
        val writes = app.radio.writes.size
        val offline = app.chats.send(threadB, "are you there?")
        settle(1_000)
        assertTrue(app.store.messages[offline.id]!!.isFailed)
        assertEquals("are you there?", app.store.messages[offline.id]!!.content)
        assertEquals(writes, app.radio.writes.size)
        assertFalse(app.link.isChatReady)                                         // → "radio not connected · Connect"
        assertEquals(R.string.chat_not_delivered, MeshCoreUi.failedText(app.store.messages[offline.id]!!, radioConnected = false))
        // A channel this radio does not have fails the same visible way.
        app.link.linkUp(); settle(5_000)
        val unknown = app.chats.send("mcchan!#nowhere", "x")
        assertTrue(app.store.messages[unknown.id]!!.isFailed)
    }

    @Test
    fun composerCountWarnsNearOneRadioMessage() {
        assertFalse(MeshCoreUi.composerCount("short", isChannel = false, selfName = null).visible)
        val near = MeshCoreUi.composerCount("x".repeat(120), isChannel = false, selfName = null)
        assertTrue(near.visible); assertFalse(near.over); assertEquals(133, near.limit)
        // A channel message also spends "<our name>: " of the radio's 160 bytes.
        assertEquals(1, MeshCoreUi.composerCount("é".repeat(60), isChannel = true, selfName = "OSHI-TEA-MC").parts)
        assertEquals(2, MeshCoreUi.composerCount("é".repeat(75), isChannel = true, selfName = "OSHI-TEA-MC").parts)
        // More than 20 radio messages: the counter says "too long" and the send is refused (same function as the radio).
        val huge = MeshCoreUi.composerCount("z".repeat(125 * 20 + 1), isChannel = false, selfName = null)
        assertTrue(huge.tooLong && huge.visible)
        assertEquals(20, MeshCoreUi.composerCount("z".repeat(125 * 20), isChannel = false, selfName = null).parts)
        assertNull(MeshCoreUi.errorText(null))
        assertNotNull(MeshCoreUi.errorText(com.oshi.messenger.service.lora.MeshCoreManager.ERROR_PAIRING_FAILED))
    }

    private companion object {
        /** The captured frames are from 2026-11-25; the virtual clock starts there so timestamps look real. */
        const val EPOCH_MS = 1_764_028_800_000L
        const val LATENCY_MS = 40L
    }

    @Suppress("unused") private val keepSessionImport = MeshCoreSession.PART_GAP_MS
}
