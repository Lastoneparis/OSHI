package com.oshi.messenger

import com.oshi.messenger.service.GroupUpdateAuthorizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __EVICTION_TOMBSTONE_WAS_DEVICE_LOCAL_2026_11_04__ (Anatole) "le nombre de personnes
 * dans le groupe bouge sans cesse sans que personne ne touche à rien", and its twin
 * report, "des personnes disparaissent sans raison".
 *
 * Both are one defect seen from two ends. Removing a member was recorded NOWHERE on
 * Android, so:
 *
 *  - the evicted member's app kept announcing itself with `member_added`, which
 *    `handleMemberAdded` accepts without any standing check (a self-announced join is how
 *    a genuine new member gets in at all) — so they came straight back;
 *  - every OTHER member's device held a roster written before the eviction and pushed it
 *    back as the current truth — so they came back there too, even on iOS, which has had
 *    the tombstone since September but was being handed definitions that omitted it.
 *
 * The asymmetry is the whole design and each test below pins one half of it: the list only
 * ever GROWS from the wire, and it only ever shrinks from a deliberate local re-add.
 *
 * Keys are written out by hand. Nothing here builds a fixture from the code under test.
 */
class GroupEvictionTombstoneTest {

    private val admin = "AAAAadminkey"
    private val member = "BBBBmemberkey"
    private val evicted = "CCCCevictedkey"

    // ------------------------------------------------------------------ union

    @Test
    fun `an incoming definition that forgets an eviction cannot undo it`() {
        // The case that bit Anatole: a member's device still holds the pre-eviction
        // definition. Honouring it as written re-opens the door.
        val union = GroupUpdateAuthorizer.unionEvicted(listOf(evicted), emptyList())
        assertEquals(listOf(evicted), union)
    }

    @Test
    fun `an eviction we have not seen yet is adopted from the wire`() {
        // The other direction, and the reason the field has to travel at all: the admin
        // who performed the eviction is the only device that knows about it.
        val union = GroupUpdateAuthorizer.unionEvicted(emptyList(), listOf(evicted))
        assertEquals(listOf(evicted), union)
    }

    @Test
    fun `a key present on both sides is not duplicated`() {
        val union = GroupUpdateAuthorizer.unionEvicted(listOf(evicted), listOf(evicted))
        assertEquals(1, union.size)
    }

    @Test
    fun `the same identity in two base64 spellings counts once`() {
        // The real cross-platform skew, and the one `canonicalIdentity` exists for: the
        // same key arrives base64url-and-padded from one platform and standard-base64
        // from the other. A tombstone that misses on that is no tombstone.
        val union = GroupUpdateAuthorizer.unionEvicted(
            listOf("ab-cd_ef=="), listOf("ab+cd/ef")
        )
        assertEquals("one identity, not two encodings", 1, union.size)
    }

    @Test
    fun `case is NOT ignored, because base64 is case-significant`() {
        // Stated as a test because the tempting "be tolerant" fix here is to lowercase,
        // and that would merge two genuinely different public keys into one tombstone —
        // silently evicting someone who was never evicted.
        val union = GroupUpdateAuthorizer.unionEvicted(
            listOf(evicted.lowercase()), listOf(evicted.uppercase())
        )
        assertEquals("two different keys must stay two", 2, union.size)
    }

    @Test
    fun `a null incoming list is not a reason to clear ours`() {
        // An Android build from before this change sends no `evictedMemberKeys` at all.
        // Reading that absence as "nobody is evicted" would make every old client in the
        // group a door back in.
        assertEquals(listOf(evicted), GroupUpdateAuthorizer.unionEvicted(listOf(evicted), null))
    }

    // ----------------------------------------------------------------- roster

    @Test
    fun `an evicted key is dropped from a roster we accept`() {
        val roster = listOf(admin, member, evicted)
        assertEquals(
            listOf(admin, member),
            GroupUpdateAuthorizer.withoutEvicted(roster, listOf(evicted))
        )
    }

    @Test
    fun `filtering is by identity, not by raw string`() {
        // Same skew as above, on the roster side: the definition lists the key in one
        // encoding and our tombstone holds the other.
        val roster = listOf(admin, "ab-cd_ef==")
        assertEquals(
            listOf(admin),
            GroupUpdateAuthorizer.withoutEvicted(roster, listOf("ab+cd/ef"))
        )
    }

    @Test
    fun `an empty tombstone list leaves the roster exactly as it was`() {
        val roster = listOf(admin, member)
        assertEquals(roster, GroupUpdateAuthorizer.withoutEvicted(roster, emptyList()))
    }

    @Test
    fun `everyone being evicted is not special-cased into everyone being kept`() {
        // Guards the tempting `if (result.isEmpty()) return memberKeys` shortcut: an empty
        // roster is a visible, reportable state, but silently restoring the evicted is not.
        assertTrue(
            GroupUpdateAuthorizer.withoutEvicted(listOf(evicted), listOf(evicted)).isEmpty()
        )
    }

    // ------------------------------------------- the sync-request rule, now live

    @Test
    fun `an evicted member asking for the roster is refused`() {
        // This rule existed before today but was handed `emptyList()` by its only caller,
        // because the Android `Group` had no field to pass. The note above it said it was
        // "the weaker of the four and not the one that mattered" — it was the one.
        val d = GroupUpdateAuthorizer.authorizeMemberSyncRequest(
            sender = evicted,
            requesterFromBody = evicted,
            currentMemberKeys = listOf(admin, member),
            evictedMemberKeys = listOf(evicted),
            weMayAddMembers = true,
        )
        assertEquals(false, d.answer)
        assertEquals(false, d.addRequester)
        assertEquals("evicted", d.reason)
    }

    @Test
    fun `an evicted member is refused even where we would otherwise add them`() {
        // `weMayAddMembers = true` plus a non-member requester is the "added-by-invite"
        // path. The tombstone has to be checked BEFORE that, or eviction means nothing on
        // any device that happens to hold add rights.
        val d = GroupUpdateAuthorizer.authorizeMemberSyncRequest(
            sender = evicted,
            requesterFromBody = null,
            currentMemberKeys = listOf(admin, member),
            evictedMemberKeys = listOf(evicted),
            weMayAddMembers = true,
        )
        assertEquals("added-by-invite must not outrank a tombstone", false, d.answer)
    }

    @Test
    fun `an ordinary member is still answered`() {
        val d = GroupUpdateAuthorizer.authorizeMemberSyncRequest(
            sender = member,
            requesterFromBody = member,
            currentMemberKeys = listOf(admin, member),
            evictedMemberKeys = listOf(evicted),
            weMayAddMembers = true,
        )
        assertEquals(true, d.answer)
        assertEquals("member", d.reason)
    }

    // ------------------------------------------------ the wiring, read at source

    /**
     * The three sites below are plumbing in a class that needs a `Context`, a crypto
     * manager and a transport to instantiate, so they are pinned at source. What breaks
     * here is a field not being passed, which no amount of state testing can see — the
     * same reason [GroupChatSheetReachabilityTest] reads source for a missing modifier.
     */
    private fun groupManagerSource(): String {
        val f = listOf(
            File("src/main/java/com/oshi/messenger/service/GroupManager.kt"),
            File("app/src/main/java/com/oshi/messenger/service/GroupManager.kt"),
        ).firstOrNull { it.isFile }
        assertNotNull("GroupManager.kt not found from either test working directory", f)
        return f!!.readText()
    }

    @Test
    fun `the tombstone rides the outbound definition`() {
        // Without this line the list stays on the device that performed the eviction and
        // every other member keeps pushing the evicted key back — the oscillation itself.
        assertTrue(
            "broadcastFullGroupUpdate must encode evictedMemberKeys",
            groupManagerSource().contains("evictedMemberKeys = group.evictedMemberKeys")
        )
    }

    @Test
    fun `the tombstone is read back off the inbound definition`() {
        val src = groupManagerSource()
        assertTrue(
            "the incoming definition's evictedMemberKeys must be parsed",
            src.contains("groupJson.optJSONArray(\"evictedMemberKeys\")")
        )
        assertTrue(
            "and handed to the apply path",
            src.contains("incomingEvictedKeys =")
        )
    }

    @Test
    fun `a self-announced join is filtered against the tombstone`() {
        // `handleMemberAdded` accepts a self-announced join with no standing check, on
        // purpose. That makes this filter the only thing standing between an evicted
        // member and the roster.
        assertTrue(
            "handleMemberAdded must filter the announced keys against the tombstone",
            groupManagerSource().contains(
                "GroupUpdateAuthorizer.withoutEvicted(announced, group.evictedMemberKeys)"
            )
        )
    }
}
