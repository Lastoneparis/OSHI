package com.oshi.messenger

import com.oshi.messenger.service.GroupUpdateAuthorizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MEMBER_SYNC_AUTHZ_2026_11_02__ The group-injection hole, as the messages
 * that would have exploited it.
 *
 * Found auditing OSHI against the 2025–26 messenger vulnerabilities, in the
 * class the NCSC calls "fraudulent integration into groups". Android's
 * `member_sync_request` handler read the requester's key out of the MESSAGE
 * BODY and added it to the group, with no check on the sender at all — while
 * the iOS twin made four. The ratchet authenticated the message perfectly; the
 * authorisation above it was missing.
 */
class MemberSyncAuthzTest {

    private val alice = "QUxJQ0VLRVk="        // a member
    private val bob = "Qk9CS0VZ"              // a member
    private val mallory = "TUFMTE9SWUtFWQ=="  // not a member
    private val victimKey = "VklDVElNS0VZ"    // the key Mallory wants inserted

    private val members = listOf(alice, bob)

    private fun decide(
        sender: String?,
        body: String? = null,
        members: List<String> = this.members,
        evicted: List<String> = emptyList(),
        mayAdd: Boolean = true,
    ) = GroupUpdateAuthorizer.authorizeMemberSyncRequest(sender, body, members, evicted, mayAdd)

    @Test
    fun `the body may not name a third party`() {
        // THE ATTACK: Mallory sends `requesterPublicKey = <someone else>`, and
        // the old code added THAT key to the group. Nothing in the message was
        // forged — the ratchet says it is Mallory — so no amount of encryption
        // would have caught it.
        val d = decide(sender = mallory, body = victimKey)
        assertFalse(d.answer)
        assertFalse(d.addRequester)
        assertEquals("body-names-a-third-party", d.reason)
    }

    @Test
    fun `a non-member cannot pull the roster when we may not add members`() {
        // An admin-only group: Mallory is not a member and this device has no
        // right to add one, so the request produces nothing at all — not even
        // the roster, which is itself the thing worth stealing.
        val d = decide(sender = mallory, mayAdd = false)
        assertFalse(d.answer)
        assertEquals("not-a-member-and-we-may-not-add", d.reason)
    }

    @Test
    fun `an evicted member cannot re-add themselves`() {
        val d = decide(sender = bob, members = listOf(alice), evicted = listOf(bob))
        assertFalse(d.answer)
        assertEquals("evicted", d.reason)
    }

    @Test
    fun `an unauthenticated transport has no say`() {
        for (s in listOf(null, "", "   ")) {
            val d = decide(sender = s, body = alice)
            assertFalse(d.answer)
            assertEquals("unauthenticated", d.reason)
        }
    }

    @Test
    fun `a member asking for the roster is answered, and not re-added`() {
        val d = decide(sender = alice, body = alice)
        assertTrue(d.answer)
        assertFalse(d.addRequester)
        assertEquals("member", d.reason)
    }

    @Test
    fun `the invite case still works, which is why the path exists`() {
        // A collaborative group where any member may add: someone we invited
        // completes the invite by asking for the roster. They are added and the
        // whole group learns about it through the definition broadcast.
        val d = decide(sender = mallory, body = mallory, mayAdd = true)
        assertTrue(d.answer)
        assertTrue(d.addRequester)
        assertEquals("added-by-invite", d.reason)
    }

    @Test
    fun `keys are compared canonically, so base64url does not read as a stranger`() {
        // The same key written base64url vs base64 must not look like two
        // people — that divergence is how a member gets treated as an intruder
        // (and, the other way round, how a check gets bypassed).
        val urlish = alice.replace('+', '-').replace('/', '_').trimEnd('=')
        val d = decide(sender = urlish, body = null)
        assertTrue(d.answer)
        assertFalse(d.addRequester)
        assertEquals("member", d.reason)
    }

    @Test
    fun `a request with no body key speaks for its sender`() {
        val d = decide(sender = bob, body = null)
        assertTrue(d.answer)
        assertEquals("member", d.reason)
    }
}
