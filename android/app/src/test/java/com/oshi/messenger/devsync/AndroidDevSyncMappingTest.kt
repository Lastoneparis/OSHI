package com.oshi.messenger.devsync

import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.devsync.AndroidDevSyncMapping.ReadMark
import com.oshi.messenger.network.v2.devsync.SyncAvatar
import com.oshi.messenger.network.v2.devsync.SyncContact
import com.oshi.messenger.network.v2.devsync.SyncGroup
import com.oshi.messenger.network.v2.devsync.SyncGroupKey
import com.oshi.messenger.network.v2.devsync.SyncJson
import com.oshi.messenger.network.v2.devsync.SyncMerge
import com.oshi.messenger.network.v2.devsync.SyncReadState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The pure half of AndroidDevSyncStore ([AndroidDevSyncMapping]): Room rows ⇄ sync records, and the
 * store-side merges, run on the JVM without an emulator. The Context/Room half (DAO calls, prefs)
 * stays a thin shell around these.
 */
class AndroidDevSyncMappingTest {

    private fun key(b: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { b.toByte() })
    private val me = key(1)
    private val bob = key(2)
    private val carol = key(3)
    private val gid = "0f8fad5b-d9cb-469f-a165-70867728950e"   // Android stores lowercase
    private val aes = ByteArray(32) { 7 }

    private fun group(updatedAt: Long = 1_000L) = Group(
        id = gid, name = "Team", description = "", creatorPublicKey = me,
        memberPublicKeys = listOf(me, bob), adminPublicKeys = listOf(me), updatedAt = updatedAt,
        createdAt = 500L, encryptionKey = aes, groupType = GroupType.COLLABORATIVE, avatar = "🔥",
    )

    // ------------------------------------------------------------------ contacts

    @Test
    fun contact_placeholderAliasIsNotAName_selfAndNonKeysAreSkipped() {
        assertNull(AndroidDevSyncMapping.syncContact(Contact(publicKey = me, alias = "Me"), me))
        assertNull(AndroidDevSyncMapping.syncContact(Contact(publicKey = "not-a-key", alias = "x"), me))
        val placeholder = AndroidDevSyncMapping.syncContact(Contact(publicKey = bob, alias = bob.take(8)), me)!!
        assertNull("the importer's 8-char placeholder is not an alias", placeholder.alias)
        val named = AndroidDevSyncMapping.syncContact(Contact(publicKey = bob, alias = "Bob", isBlocked = true), me)!!
        assertEquals("Bob", named.alias)
        assertEquals(true, named.blocked)
        assertNull("false is absent (gap-fill only, §8.5)", named.verified)
    }

    @Test
    fun contactRow_insertsGapFillsAndReportsNoChange() {
        val inserted = AndroidDevSyncMapping.contactRow(null, SyncContact(bob))!!
        assertEquals(bob.take(8), inserted.alias)
        val existing = Contact(publicKey = bob, alias = "Bob")
        assertNull("nothing new ⇒ no write", AndroidDevSyncMapping.contactRow(existing, SyncContact(bob)))
        val blocked = AndroidDevSyncMapping.contactRow(existing, SyncContact(bob, blocked = true))!!
        assertTrue(blocked.isBlocked)
        assertEquals("Bob", blocked.alias)
    }

    // ------------------------------------------------------------------ groups

    @Test
    fun group_exportThenImport_roundTripsWhatAndroidModels() {
        val avatar = SyncAvatar("ab".repeat(32), 10, "image/jpeg")
        val out = AndroidDevSyncMapping.syncGroup(group(), avatar, joinedAtMs = 900L)
        assertEquals(SyncJson.canonicalGroupId(gid), out.groupId)
        assertEquals(gid.uppercase(), out.groupId)
        assertEquals(1_000L, out.nameAtMs)
        assertEquals(1_000L, out.avatarAtMs)
        assertEquals(900L, out.joinedAtMs)
        assertEquals(listOf(SyncGroupKey(SyncGroupKey.KIND_LEGACY_AES, 0, Base64.getEncoder().encodeToString(aes))), out.keys)
        assertEquals("collaborative", out.type)
        assertEquals("🔥", out.avatarEmoji)
        // Survives the wire.
        val back = SyncGroup.fromJson(JSONObject(out.toJson().toString()))!!
        assertEquals(out.canonicalJson(), back.canonicalJson())

        val row = AndroidDevSyncMapping.groupRow(back.copy(members = listOf(me, bob, carol), name = "Team 2"), group(), me)
        assertEquals(gid, row.id) // GroupManager keeps the stored spelling
        assertEquals("Team 2", row.name)
        assertEquals(listOf(me, bob, carol), row.memberPublicKeys)
        assertTrue(row.encryptionKey!!.contentEquals(aes))
        assertEquals(GroupType.COLLABORATIVE, row.groupType)
    }

    @Test
    fun groupRow_forAnUnknownGroup_createsIt_withTheSyncedKey() {
        val g = SyncGroup(groupId = gid.uppercase(), name = "New", members = listOf(me, bob), admins = listOf(bob),
            keys = listOf(SyncGroupKey(SyncGroupKey.KIND_LEGACY_AES, 0, Base64.getEncoder().encodeToString(aes))),
            updatedAtMs = 42L, type = "public")
        val row = AndroidDevSyncMapping.groupRow(g, null, me)
        assertEquals(bob, row.creatorPublicKey) // first admin when no creator
        assertTrue(row.isPublic)
        assertEquals(42L, row.createdAt)
        assertTrue(row.encryptionKey!!.contentEquals(aes))
    }

    @Test
    fun leftGroup_travelsWithItsKeyAndJoinedAt_andNeverOverridesTheName() {
        val l = AndroidDevSyncMapping.leftGroup(gid, "Team", 2_000L, aes, joinedAtMs = 900L)
        assertTrue(l.left)
        assertEquals(2_000L, l.leftAtMs)
        assertNull(l.nameAtMs)
        assertTrue(l.members.isEmpty())
        // Against the live copy on another device: the leave (after the join) wins, the name stays.
        val merged = SyncMerge.group(AndroidDevSyncMapping.syncGroup(group(), null, 900L), l)
        assertTrue(merged.left)
        assertEquals("Team", merged.name)
        assertEquals(1, merged.keys.size)
    }

    @Test
    fun rejoinOnAnotherDevice_beatsThisDevicesOlderLeave_butNotANewerOne() {
        val myLeave = AndroidDevSyncMapping.leftGroup(gid, "Team", 2_000L, aes, joinedAtMs = null)
        val rejoinedElsewhere = AndroidDevSyncMapping.syncGroup(group(updatedAt = 3_000L), null, joinedAtMs = 3_000L)
        val merged = SyncMerge.group(myLeave, rejoinedElsewhere)
        assertFalse("a later join beats the leave (§8.4 rejoin rule)", merged.left)
        assertEquals(3_000L, merged.joinedAtMs)
        assertEquals(listOf(me, bob).sorted(), merged.members.sorted())
        // Commutes.
        assertEquals(merged.canonicalJson(), SyncMerge.group(rejoinedElsewhere, myLeave).canonicalJson())
        // Left again after that rejoin: the leave wins again.
        val leftAgain = AndroidDevSyncMapping.leftGroup(gid, "Team", 4_000L, aes, joinedAtMs = 3_000L)
        val again = SyncMerge.group(merged, leftAgain)
        assertTrue(again.left)
        assertEquals(4_000L, again.leftAtMs)
    }

    // ------------------------------------------------------------------ read state

    @Test
    fun readMarks_onlyMoveForward_andMergeIsMax() {
        val stored = mutableMapOf("direct:x" to ReadMark(100L, 5L))
        assertFalse(AndroidDevSyncMapping.advanceReadMarks(stored, mapOf("direct:x" to 90L), now = 50L))
        assertEquals(ReadMark(100L, 5L), stored["direct:x"])
        assertTrue(AndroidDevSyncMapping.advanceReadMarks(stored, mapOf("direct:x" to 120L, "group:G" to 7L), now = 60L))
        assertEquals(ReadMark(120L, 60L), stored["direct:x"])
        assertEquals(ReadMark(7L, 60L), stored["group:G"])

        assertEquals(ReadMark(120L, 70L), AndroidDevSyncMapping.mergeReadMark(ReadMark(120L, 60L), SyncReadState("direct:x", 110L, 70L)))
        assertEquals(ReadMark(110L, 70L), AndroidDevSyncMapping.mergeReadMark(null, SyncReadState("direct:x", 110L, 70L)))
    }

    // ------------------------------------------------------------------ profile

    @Test
    fun profile_photoIsByReference_explicitNullMeansNoPicture_pendingIsAnnounced() {
        val none = AndroidDevSyncMapping.profileRecord("Hugo", null, null, null, JSONObject(), 10L)!!
        assertTrue("explicit null = no picture", none.extra.has("photo") && none.extra.isNull("photo"))

        val sha = "cd".repeat(32)
        val withPhoto = AndroidDevSyncMapping.profileRecord("Hugo", sha to 1234L, null, null, JSONObject(), 10L)!!
        assertEquals(sha, withPhoto.extra.getJSONObject("photo").getString("sha256"))
        assertEquals(1234L, withPhoto.extra.getJSONObject("photo").getLong("size"))

        val pending = JSONObject().put("sha256", "ef".repeat(32)).put("size", 9).put("mime", "image/jpeg")
        val adopting = AndroidDevSyncMapping.profileRecord("Hugo", sha to 1234L, pending, null, JSONObject(), 20L)!!
        assertEquals("the newer device's picture keeps being announced until fetched", "ef".repeat(32),
            adopting.extra.getJSONObject("photo").getString("sha256"))

        val links = JSONObject().put("github", "hugo")
        val unknownField = JSONObject().put("bio", "hi")
        val full = AndroidDevSyncMapping.profileRecord(null, null, null, links, unknownField, 30L)!!
        assertEquals("hugo", full.extra.getJSONObject("links").getString("github"))
        assertEquals("fields this client does not model travel unchanged", "hi", full.extra.getString("bio"))
        assertFalse("the caller's extra is not mutated", unknownField.has("photo"))

        assertNull("nothing to say", AndroidDevSyncMapping.profileRecord(null, null, null, null, JSONObject(), 0L))
        // Round trip through the wire.
        val wire = com.oshi.messenger.network.v2.devsync.SyncProfile.fromJson(JSONObject(withPhoto.toJson().toString()))!!
        assertEquals(10L, wire.updatedAtMs)
        assertEquals(sha, wire.extra.getJSONObject("photo").getString("sha256"))
    }
}
