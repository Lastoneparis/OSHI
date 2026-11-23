package com.oshi.messenger

import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.service.GroupUpdateAuthorizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authorization for inbound `📢GROUP_UPDATE📢` payloads — audit G-2.
 *
 * Reference is the Swift source, not the Kotlin: `MessageGroupManager.authorizeGroupUpdate`
 * (GroupMessaging.swift:863-905) and `applyGroupUpdate` (:908-931).
 *
 * Every input here is a hand-written literal. Nothing under test builds its own fixture — the
 * rosters, the sender keys and the expected `reason` tags are all written out by hand, so
 * deleting a guard from `GroupUpdateAuthorizer` makes these fail rather than move together
 * with the code.
 */
class GroupUpdateAuthorizationTest {

    private val adminKey = "AAAAadminkey"
    private val memberKey = "BBBBmemberkey"
    private val strangerKey = "ZZZZstrangerkey"

    private val members = listOf(adminKey, memberKey)
    private val admins = listOf(adminKey)

    // ---------------------------------------------------------------- standing

    @Test
    fun `a sender who is not a member is refused outright`() {
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = members,
            currentAdminKeys = admins,
            currentType = GroupType.COLLABORATIVE,
            sender = strangerKey
        )
        assertFalse("a non-member must not be able to write anything", d.apply)
        assertEquals("sender-not-a-member", d.reason)
        assertTrue(d.keepAdminSet)
        assertTrue(d.keepMembership)
        assertTrue(d.keepName)
    }

    @Test
    fun `a stranger is refused in an admin-only group too`() {
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = members,
            currentAdminKeys = admins,
            currentType = GroupType.ADMIN_ONLY,
            sender = strangerKey
        )
        assertFalse(d.apply)
        assertEquals("sender-not-a-member", d.reason)
    }

    @Test
    fun `a stranger is refused in a public group too`() {
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = members,
            currentAdminKeys = admins,
            currentType = GroupType.PUBLIC,
            sender = strangerKey
        )
        assertFalse(d.apply)
        assertEquals("sender-not-a-member", d.reason)
    }

    // --------------------------------------------------------------- authority

    @Test
    fun `an ordinary member may rename and add but never touch the admin set`() {
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = members,
            currentAdminKeys = admins,
            currentType = GroupType.COLLABORATIVE,
            sender = memberKey
        )
        assertTrue(d.apply)
        assertEquals("member-of-open-group", d.reason)
        assertTrue("the admin set is never taken from a non-admin", d.keepAdminSet)
        assertFalse(d.keepMembership)
        assertFalse(d.keepName)
    }

    @Test
    fun `an admin may take the whole definition`() {
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = members,
            currentAdminKeys = admins,
            currentType = GroupType.COLLABORATIVE,
            sender = adminKey
        )
        assertTrue(d.apply)
        assertEquals("admin", d.reason)
        assertFalse(d.keepAdminSet)
        assertFalse(d.keepMembership)
        assertFalse(d.keepName)
    }

    @Test
    fun `an ordinary member of an admin-only group may change nothing structural`() {
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = members,
            currentAdminKeys = admins,
            currentType = GroupType.ADMIN_ONLY,
            sender = memberKey
        )
        assertTrue("cosmetic fields still apply", d.apply)
        assertEquals("member-of-admin-only-group", d.reason)
        assertTrue(d.keepAdminSet)
        assertTrue(d.keepMembership)
        assertTrue(d.keepName)
    }

    // ------------------------------------------------- unauthenticated transport

    @Test
    fun `a payload with no sender keeps the admin set`() {
        for (sender in listOf(null, "", "   ")) {
            val d = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = members,
                currentAdminKeys = admins,
                currentType = GroupType.COLLABORATIVE,
                sender = sender
            )
            assertTrue(d.apply)
            assertEquals("unauthenticated-transport", d.reason)
            assertTrue("sender=[$sender] must not be able to promote anyone", d.keepAdminSet)
            assertFalse(d.keepMembership)
            assertFalse(d.keepName)
        }
    }

    // -------------------------------------------------------------- replay

    @Test
    fun `an older stateVersion is a replay and is dropped`() {
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = members,
            currentAdminKeys = admins,
            currentType = GroupType.COLLABORATIVE,
            currentStateVersion = 7,
            incomingStateVersion = 6,
            sender = adminKey
        )
        assertFalse(d.apply)
        assertEquals("stale-version", d.reason)
    }

    @Test
    fun `an equal or newer stateVersion passes through to the standing check`() {
        for (incoming in listOf(7, 8)) {
            val d = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = members,
                currentAdminKeys = admins,
                currentType = GroupType.COLLABORATIVE,
                currentStateVersion = 7,
                incomingStateVersion = incoming,
                sender = adminKey
            )
            assertTrue("incoming=$incoming", d.apply)
            assertEquals("admin", d.reason)
        }
    }

    @Test
    fun `a missing version on either side disables the replay check`() {
        assertEquals(
            "admin",
            GroupUpdateAuthorizer.authorize(
                members, admins, GroupType.COLLABORATIVE,
                currentStateVersion = null, incomingStateVersion = 1, sender = adminKey
            ).reason
        )
        assertEquals(
            "admin",
            GroupUpdateAuthorizer.authorize(
                members, admins, GroupType.COLLABORATIVE,
                currentStateVersion = 9, incomingStateVersion = null, sender = adminKey
            ).reason
        )
    }

    // -------------------------------------------------------- key spelling

    @Test
    fun `standing survives a base64url spelling of the same key`() {
        val stored = "abc+def/ghi="
        val onTheWire = "abc-def_ghi"
        val d = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = listOf(stored),
            currentAdminKeys = listOf(stored),
            currentType = GroupType.COLLABORATIVE,
            sender = onTheWire
        )
        assertEquals("admin", d.reason)
    }

    // ------------------------------------------------------ admin re-stamp

    @Test
    fun `re-stamping strips a self-promotion out of an incoming roster`() {
        // The attacker sent a roster that lists themselves as admin. Our copy says otherwise.
        val incomingRoster = listOf(adminKey, memberKey, strangerKey)
        val restamped = GroupUpdateAuthorizer.restampAdminSet(admins, incomingRoster)
        assertEquals(listOf(adminKey), restamped)
        assertFalse(strangerKey in restamped)
    }

    @Test
    fun `re-stamping drops an admin who is no longer in the roster`() {
        val restamped = GroupUpdateAuthorizer.restampAdminSet(
            listOf(adminKey, memberKey), listOf(memberKey, strangerKey)
        )
        assertEquals(listOf(memberKey), restamped)
    }

    @Test
    fun `re-stamping leaves the admin set alone when no roster travelled`() {
        assertEquals(admins, GroupUpdateAuthorizer.restampAdminSet(admins, emptyList()))
    }

    // ------------------------------------------------------- unknown group

    @Test
    fun `a group whose roster does not contain us is never materialised`() {
        assertFalse(GroupUpdateAuthorizer.isSelfInRoster(memberKey, listOf(adminKey, strangerKey)))
        assertFalse(GroupUpdateAuthorizer.isSelfInRoster(memberKey, emptyList()))
        assertFalse("no local key yet ⇒ nothing may be materialised",
            GroupUpdateAuthorizer.isSelfInRoster("", listOf(adminKey, memberKey)))
    }

    @Test
    fun `a group whose roster contains us in any spelling is materialised`() {
        assertTrue(GroupUpdateAuthorizer.isSelfInRoster(memberKey, members))
        assertTrue(GroupUpdateAuthorizer.isSelfInRoster("abc-def_ghi", listOf("abc+def/ghi=")))
    }
}
