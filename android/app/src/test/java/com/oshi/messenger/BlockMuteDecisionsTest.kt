package com.oshi.messenger

import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.devsync.AndroidDevSyncMapping
import com.oshi.messenger.network.v2.devsync.DevSyncVectorCheck
import com.oshi.messenger.network.v2.devsync.SyncContact
import com.oshi.messenger.network.v2.devsync.SyncGroup
import com.oshi.messenger.network.v2.devsync.SyncMerge
import com.oshi.messenger.service.BlockedByPeer
import com.oshi.messenger.service.BlockedByPeerStore
import com.oshi.messenger.service.BlockedKeys
import com.oshi.messenger.service.BlockedMemberGate
import com.oshi.messenger.service.CallStartRefusal
import com.oshi.messenger.service.PeerUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * __BLOCK_MUTE_DECISIONS_2026_09_24__ owner decisions on block / mute (iOS twin:
 * OSHITests/BlockedNoticeTests.swift `BlockMuteDecisionsTests`, Desktop: BlockedByPeerTest).
 * 2) no missed-call trace of a blocked contact · 3) a blocked member is not shown in groups ·
 * 4) block/unblock and group mute sync between the user's own devices (LWW, iOS format).
 */
class BlockMuteDecisionsTest {

    private fun key(b: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { b.toByte() })
    private val me = key(1)
    private val bob = key(2)

    private fun src(rel: String): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, rel)
            if (f.isFile) return f.readText()
            d = d.parentFile
        }
        error("$rel not found")
    }

    // ---------------------------------------------------------------- 2b "peer X blocks me"

    @Test
    fun evidenceTable_isTheSameAsIosAndDesktop() {
        val table = listOf(
            "🚫BLOCKED🚫" to BlockedByPeer.Evidence.BLOCKED,
            "📬DELIVERY_RECEIPT📬x" to BlockedByPeer.Evidence.UNBLOCKED,
            "📖READ_RECEIPT📖x" to BlockedByPeer.Evidence.UNBLOCKED,
            "salut" to BlockedByPeer.Evidence.UNBLOCKED,
            "⌨️TYPING⌨️" to BlockedByPeer.Evidence.NONE,
            "📸PROFILE_UPDATE📸{}" to BlockedByPeer.Evidence.NONE,
            null to BlockedByPeer.Evidence.NONE,
        )
        for ((input, expected) in table) assertEquals("$input", expected, BlockedByPeer.evidence(input))
    }

    @Test
    fun noticeRecords_realMessageOrReceiptLifts_persisted_anySpelling() {
        var saved: String? = null
        val store = BlockedByPeerStore({ saved }, { saved = it })
        val urlsafe = bob.replace('+', '-').replace('/', '_').trimEnd('=')
        store.observeInbound(bob, "🚫BLOCKED🚫")
        assertTrue(store.isBlockedBy(urlsafe))
        assertTrue("survives a restart", BlockedByPeerStore({ saved }, { saved = it }).isBlockedBy(bob))
        store.observeInbound(bob, "⌨️TYPING⌨️")
        assertTrue("mechanics say nothing", store.isBlockedBy(bob))
        store.observeInbound(bob, "📖READ_RECEIPT📖x")
        assertFalse("a device that still blocks us never acks", store.isBlockedBy(bob))
        store.observeStoredInbound(bob, "🚫BLOCKED🚫")
        assertTrue(store.isBlockedBy(bob))
        store.observeStoredInbound(bob, null)   // a media row
        assertFalse(store.isBlockedBy(bob))
    }

    @Test
    fun callerNeverDialsSomeoneWhoBlocksIt_refusalBeforeAnyPushOrSignal() {
        for (path in listOf("app/src/main/java/com/oshi/messenger/service/EnhancedCallManager.kt",
                            "app/src/main/java/com/oshi/messenger/service/CallManager.kt")) {
            val s = src("OSHI-Android/$path")
            val start = s.indexOf("suspend fun startCall(")
            val refuse = s.indexOf("PeerUnavailableException()", start)
            assertTrue(path, refuse > start)
            for (send in listOf("sendVoIPPush(", "sendFcmCallWake(", "sendCallSignal(")) {
                val i = s.indexOf(send, start)
                if (i >= 0) assertTrue("$path: $send before the refusal", refuse < i)
            }
            assertTrue("$path: a caller that rings us no longer blocks us", s.contains("BlockedByPeerStore.get(context).clear(signal.senderPublicKey)"))
        }
        assertEquals(R.string.call_error_unavailable, CallStartRefusal.messageRes(PeerUnavailableException()))
        assertNull(CallStartRefusal.messageRes(null))
        val chat = src("OSHI-Android/app/src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertTrue(chat.contains("R.string.call_error_unavailable"))
    }

    @Test
    fun unavailableStringExistsInEveryLocale() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        var res: File? = null
        while (d != null && res == null) {
            listOf(File(d, "OSHI-Android/app/src/main/res"), File(d, "app/src/main/res"), File(d, "src/main/res"))
                .firstOrNull { it.isDirectory }?.let { res = it }
            d = d.parentFile
        }
        val dirs = res!!.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").isFile && it.name != "values-v31" }
        assertTrue(dirs.size >= 34)
        for (v in dirs) assertTrue(v.name, File(v, "strings.xml").readText().contains("name=\"call_error_unavailable\""))
    }

    // ---------------------------------------------------------------- 2c no call trace

    @Test
    fun noCallHistoryRowOrSummaryForABlockedContact() {
        val ecm = src("OSHI-Android/app/src/main/java/com/oshi/messenger/service/EnhancedCallManager.kt")
        val h = ecm.indexOf("private suspend fun saveCallHistory(")
        assertTrue(ecm.substring(h, h + 500).contains("CallAdmissionPolicy.isBlocked("))
        val sm = ecm.indexOf("private fun sendCallSummaryMessage(")
        assertTrue(ecm.substring(sm, sm + 500).contains("CallAdmissionPolicy.isBlockedCached("))
        val cm = src("OSHI-Android/app/src/main/java/com/oshi/messenger/service/CallManager.kt")
        val h2 = cm.indexOf("private suspend fun saveCallHistory(")
        assertTrue(cm.substring(h2, h2 + 500).contains("CallAdmissionPolicy.isBlocked("))
    }

    // ---------------------------------------------------------------- 3 groups

    @Test
    fun blockedMemberContentIsDropped_systemBotsAndCommandsAreNot() {
        val blocked: (String) -> Boolean = { it == bob }
        assertTrue(BlockedMemberGate.drops(bob, "", blocked))
        assertFalse(BlockedMemberGate.drops(me, "", blocked))
        assertFalse(BlockedMemberGate.drops("system", "", { true }))
        assertFalse(BlockedMemberGate.drops("SYSTEM", "", { true }))
        assertFalse(BlockedMemberGate.drops("bot:abc", "", { true }))
        assertFalse("an edit/delete command is not content", BlockedMemberGate.drops(bob, "messageDeleted", blocked))
        val gm = src("OSHI-Android/app/src/main/java/com/oshi/messenger/service/GroupManager.kt")
        val gate = gm.indexOf("BlockedMemberGate.drops(")
        val store = gm.indexOf("addMessageToGroup(", gate)
        assertTrue("dropped before it is stored", gate in 1 until store)
    }

    // ---------------------------------------------------------------- 4 devsync

    @Test
    fun blockState_exportsUnblockWithItsTime_neverFalseAtNow() {
        val e = BlockedKeys.Entry(bob, "Bob", 5_000L)
        assertEquals(AndroidDevSyncMapping.BlockState(true, 5_000L), AndroidDevSyncMapping.blockState(e, null, false))
        assertEquals("migrated legacy flag = untimestamped", AndroidDevSyncMapping.BlockState(true, null),
            AndroidDevSyncMapping.blockState(BlockedKeys.Entry(bob, null, 0L), null, true))
        assertEquals(AndroidDevSyncMapping.BlockState(false, 7_000L), AndroidDevSyncMapping.blockState(null, 7_000L, false))
        assertEquals("never blocked: nothing to say", AndroidDevSyncMapping.BlockState(null, null), AndroidDevSyncMapping.blockState(null, null, false))
        val out = AndroidDevSyncMapping.syncContact(Contact(publicKey = bob, alias = "Bob"), me, AndroidDevSyncMapping.BlockState(false, 7_000L))!!
        assertEquals(false, out.blocked)
        assertEquals(7_000L, out.blockedAtMs)
        assertEquals(false, out.toJson().getBoolean("blocked"))
        assertEquals("1970-01-01T00:00:07.000Z", out.toJson().getString("blockedAt"))
        // A key blocked with no contact row still travels.
        assertEquals(true, AndroidDevSyncMapping.blockOnlyContact(bob, me, AndroidDevSyncMapping.BlockState(true, 5_000L))!!.blocked)
    }

    @Test
    fun blockAction_appliesTheMergedRecordInFull() {
        val entry = BlockedKeys.Entry(bob, null, 5_000L)
        fun c(b: Boolean?, at: Long?) = SyncContact(bob, blocked = b, blockedAtMs = at)
        assertEquals(AndroidDevSyncMapping.BlockAction.UNBLOCK, AndroidDevSyncMapping.blockAction(c(false, 9_000L), entry, null))
        assertEquals(AndroidDevSyncMapping.BlockAction.NONE, AndroidDevSyncMapping.blockAction(c(true, 5_000L), entry, null))
        assertEquals(AndroidDevSyncMapping.BlockAction.BLOCK, AndroidDevSyncMapping.blockAction(c(true, 9_000L), null, 7_000L))
        assertEquals("record the synced unblock time", AndroidDevSyncMapping.BlockAction.UNBLOCK, AndroidDevSyncMapping.blockAction(c(false, 9_000L), null, null))
        assertEquals(AndroidDevSyncMapping.BlockAction.NONE, AndroidDevSyncMapping.blockAction(c(null, null), entry, null))
        // The stale-record bug: Android unblocked at 9 000, a device still says blocked@5 000.
        val merged = SyncMerge.contact(SyncContact(bob, blocked = false, blockedAtMs = 9_000L), c(true, 5_000L))
        assertEquals(AndroidDevSyncMapping.BlockAction.NONE, AndroidDevSyncMapping.blockAction(merged, null, 9_000L))
    }

    private val gid = "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D"
    private fun group(muted: Boolean) = Group(id = gid, name = "Team", creatorPublicKey = me, memberPublicKeys = listOf(me, bob),
        adminPublicKeys = listOf(me), updatedAt = 1_000L, groupType = GroupType.COLLABORATIVE, isMuted = muted)

    @Test
    fun groupMute_exportedWithItsTime_mergedLastWriterWins() {
        assertNull("never toggled, not muted: absent", AndroidDevSyncMapping.syncGroup(group(false), null, null).muted)
        assertEquals("legacy mute: untimestamped", true, AndroidDevSyncMapping.syncGroup(group(true), null, null).muted)
        val mine = AndroidDevSyncMapping.syncGroup(group(true), null, null, mutedAtMs = 2_000L)
        assertEquals(true, mine.toJson().getBoolean("muted"))
        val unmutedLater = mine.copy(muted = false, mutedAtMs = 3_000L)
        assertEquals(false, SyncMerge.group(mine, unmutedLater).muted)
        assertEquals(false, SyncMerge.group(unmutedLater, mine).muted)
        assertEquals("an old peer without the fields keeps the mute", true,
            SyncMerge.group(mine, mine.copy(muted = null, mutedAtMs = null)).muted)
        assertEquals(true, SyncGroup.fromJson(mine.toJson())!!.muted)
    }

    @Test
    fun muteToggleIsNotConflatedAway() {
        assertNotEquals(group(false), group(true))
    }

    @Test
    fun goldenVectors_includingBlockMuteRecords() {
        val r = DevSyncVectorCheck.runAll()
        assertEquals(r.toString(), 0, r.failures)
    }
}
