package com.oshi.messenger

import com.oshi.messenger.service.ActiveConversation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ActiveConversation` — the predicate that decides whether an arriving message is
 * marked read, whether a read receipt goes out, and whether a banner is raised.
 *
 * The bug it fixes is "a message landing in an open chat is not marked read". The
 * bug it must NOT introduce is the mirror image: telling the sender ✓✓ Read for a
 * message that arrived while the app was in the user's pocket, or into a DIFFERENT
 * conversation than the one on screen. Every test below is about that second one —
 * the cases where the answer must be **false**.
 *
 * Every fixture is a hand-written literal. Nothing is produced by the code under
 * test, so each assertion can actually fail: drop the `-`/`_` fold and the base64url
 * spelling stops matching; drop the key check in `leaveDirect` and the hand-off test
 * goes red; drop the blank guard and the empty-key test reports "everything is
 * visible"; drop the direct/group exclusivity and both threads read as on screen at
 * once.
 */
class ActiveConversationTest {

    /**
     * base64 of a fixed 32-byte key whose standard encoding contains BOTH `+` and
     * `/`, so its base64url spelling differs in both characters. Same fixture family
     * as `CanonicalIdentityTest`.
     */
    private val standard = "++8//DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734="
    private val urlUnpadded = "--8__DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734"
    private val otherKey = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZQ="

    @Before
    fun reset() = ActiveConversation.clear()

    @After
    fun tearDown() = ActiveConversation.clear()

    // ─── nothing on screen ───────────────────────────────────────────────────

    /**
     * The default. Before any screen resumes — and after the app is backgrounded —
     * NO conversation is visible, so nothing arriving may be marked read.
     */
    @Test
    fun `with no screen resumed nothing is active`() {
        assertFalse(ActiveConversation.isDirectActive(standard))
        assertFalse(ActiveConversation.isGroupActive("group-1"))
        assertNull(ActiveConversation.activeDirectKey)
        assertNull(ActiveConversation.activeGroupId)
    }

    /**
     * A blank key must never be "the thread on screen".
     *
     * `ChatViewModel._publicKey` starts as `""`, and `FCMService` falls back to `""`
     * when a push carries neither `senderKey` nor `senderPublicKey`. If `""` matched
     * `""` this predicate would report "the user is looking at this" for every
     * keyless push — marking read, and suppressing the banner, for a message nobody
     * has seen.
     */
    @Test
    fun `a blank key is never active, in either direction`() {
        ActiveConversation.enterDirect("")
        assertNull(ActiveConversation.activeDirectKey)
        assertFalse(ActiveConversation.isDirectActive(""))

        ActiveConversation.enterDirect(standard)
        assertFalse(ActiveConversation.isDirectActive(""))
        assertFalse(ActiveConversation.isDirectActive("   "))

        ActiveConversation.enterGroup("")
        assertNull(ActiveConversation.activeGroupId)
        assertFalse(ActiveConversation.isGroupActive(""))
    }

    // ─── the thread on screen, and only it ───────────────────────────────────

    @Test
    fun `entering a thread makes exactly that thread active`() {
        ActiveConversation.enterDirect(standard)
        assertTrue(ActiveConversation.isDirectActive(standard))
        assertFalse(ActiveConversation.isDirectActive(otherKey))
    }

    /**
     * The screen is navigated with one spelling of the key; the FCM payload can
     * carry another (base64url, unpadded). Both are the same person, and the gate
     * has to say so — otherwise the open chat still gets a banner and the arriving
     * message still is not marked read, which is the original bug wearing a
     * different hat.
     */
    @Test
    fun `padding and base64url spellings of one key are the same thread`() {
        ActiveConversation.enterDirect(standard)
        assertTrue(ActiveConversation.isDirectActive(urlUnpadded))

        ActiveConversation.clear()
        ActiveConversation.enterDirect(urlUnpadded)
        assertTrue(ActiveConversation.isDirectActive(standard))
    }

    /**
     * ⚠️ The case that is worse than the bug. A message arriving for Bob while the
     * user is reading Alice must NOT be marked read.
     */
    @Test
    fun `another conversation's messages are not visible`() {
        ActiveConversation.enterDirect(standard)
        assertFalse(ActiveConversation.isDirectActive(otherKey))
        assertFalse(ActiveConversation.isGroupActive("group-1"))
    }

    // ─── leaving: backgrounding and hand-off ────────────────────────────────

    /**
     * `ON_PAUSE` — the app went to the background, or the user navigated away. From
     * that moment nothing is visible and arrivals go back to being unread.
     */
    @Test
    fun `leaving the thread clears it`() {
        ActiveConversation.enterDirect(standard)
        ActiveConversation.leaveDirect(standard)
        assertFalse(ActiveConversation.isDirectActive(standard))
        assertNull(ActiveConversation.activeDirectKey)
    }

    /** Leaving is spelling-insensitive too, or a paused screen could fail to clear. */
    @Test
    fun `leaving with the other spelling still clears`() {
        ActiveConversation.enterDirect(standard)
        ActiveConversation.leaveDirect(urlUnpadded)
        assertFalse(ActiveConversation.isDirectActive(standard))
    }

    /**
     * The ordering hazard. Compose can resume the incoming screen before pausing the
     * outgoing one, so chat A's late `ON_PAUSE` arrives after chat B has already
     * claimed the screen. If `leaveDirect` cleared unconditionally, B would be left
     * "not visible" while the user is staring at it — and B's arriving messages
     * would stay unread. That is the original bug, reintroduced through the back
     * door.
     */
    @Test
    fun `a late leave from the previous thread does not evict the new one`() {
        ActiveConversation.enterDirect(standard)
        ActiveConversation.enterDirect(otherKey)   // B resumes
        ActiveConversation.leaveDirect(standard)   // A pauses, late

        assertTrue(ActiveConversation.isDirectActive(otherKey))
        assertFalse(ActiveConversation.isDirectActive(standard))
    }

    @Test
    fun `a late leave from the previous group does not evict the new one`() {
        ActiveConversation.enterGroup("group-a")
        ActiveConversation.enterGroup("group-b")
        ActiveConversation.leaveGroup("group-a")

        assertTrue(ActiveConversation.isGroupActive("group-b"))
        assertFalse(ActiveConversation.isGroupActive("group-a"))
    }

    // ─── groups ─────────────────────────────────────────────────────────────

    @Test
    fun `entering a group makes exactly that group active`() {
        ActiveConversation.enterGroup("group-1")
        assertTrue(ActiveConversation.isGroupActive("group-1"))
        assertFalse(ActiveConversation.isGroupActive("group-2"))
        assertEquals("group-1", ActiveConversation.activeGroupId)
    }

    /** A group id arriving with stray whitespace is still the group on screen. */
    @Test
    fun `group ids are compared trimmed`() {
        ActiveConversation.enterGroup("  group-1  ")
        assertTrue(ActiveConversation.isGroupActive("group-1"))
    }

    /**
     * Exactly one conversation is on screen. Opening a group while a 1:1 was open
     * must retire the 1:1 — otherwise the 1:1 keeps marking its arrivals read from
     * behind the group screen.
     */
    @Test
    fun `only one conversation can be on screen at a time`() {
        ActiveConversation.enterDirect(standard)
        ActiveConversation.enterGroup("group-1")
        assertFalse(ActiveConversation.isDirectActive(standard))
        assertTrue(ActiveConversation.isGroupActive("group-1"))

        ActiveConversation.enterDirect(standard)
        assertFalse(ActiveConversation.isGroupActive("group-1"))
        assertTrue(ActiveConversation.isDirectActive(standard))
    }

    @Test
    fun `clear retires everything`() {
        ActiveConversation.enterDirect(standard)
        ActiveConversation.clear()
        assertFalse(ActiveConversation.isDirectActive(standard))

        ActiveConversation.enterGroup("group-1")
        ActiveConversation.clear()
        assertFalse(ActiveConversation.isGroupActive("group-1"))
    }
}
