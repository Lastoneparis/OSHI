package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * __BLOCKED_NOTICE_2026_09_18__ Blocking now SAYS so.
 *
 * The app used to promise the opposite, in 34 languages: *"They won't be notified
 * that they've been blocked."* The owner chose the other answer — tell them — which
 * makes this a rule with two halves that have to agree, on two platforms:
 *
 *  1. the blocker's device emits `🚫BLOCKED🚫` when something addressed to a blocked
 *     key arrives, and
 *  2. the blocked device renders it as a line in its own language.
 *
 * What is asserted here is everything that can be decided without a device: the
 * catalogue entry and how it presents, the three rules that keep the notice from
 * becoming a spam channel or a presence beacon, and that iOS spells the sentinel the
 * same way — a mismatch there is a silent no-op on the wire, which no device test
 * would catch either.
 *
 * The send itself needs the Hilt graph (`V2MessageRouter`, `VPSClient`,
 * `android.util.Base64`), the same wall `V2EnvelopeTimestampWiringTest` documents, so
 * its rules are pinned against the source.
 */
class BlockedNoticeTest {

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
        error("unreachable")
    }

    /** The iOS tree sits beside the Android module in the same checkout. */
    private fun repoFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
        error("unreachable")
    }

    private val repositorySource by lazy {
        moduleFile("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt").readText()
    }

    // ── The catalogue entry ────────────────────────────────────────────────────

    @Test
    fun `the notice is a catalogued control payload, not loose text`() {
        assertTrue(
            "🚫BLOCKED🚫 must be declared in the catalogue — a prefix that lives " +
                "anywhere else is the six-copies bug this file exists to prevent",
            OSHIControlPayload.catalog.any { it.prefix == OSHIControlPayload.BLOCKED_NOTICE },
        )
        assertTrue(OSHIControlPayload.isControl(OSHIControlPayload.BLOCKED_NOTICE))
    }

    @Test
    fun `the notice is shown, not swallowed`() {
        // The whole point of (b) over (a): a silent notice is the behaviour we
        // just replaced.
        assertFalse(
            "a blocked person who is told nothing is exactly the old behaviour",
            OSHIControlPayload.isSilent(OSHIControlPayload.BLOCKED_NOTICE),
        )
        val labels = OSHIControlPayload.Labels(blockedByContact = "No longer deliverable")
        assertEquals(
            "🚫 No longer deliverable",
            OSHIControlPayload.displaySummary(OSHIControlPayload.BLOCKED_NOTICE, labels),
        )
    }

    @Test
    fun `the raw sentinel never reaches a bubble or a conversation row`() {
        val labels = OSHIControlPayload.Labels(blockedByContact = "No longer deliverable")
        for (surface in listOf(
            OSHIControlPayload.previewText(OSHIControlPayload.BLOCKED_NOTICE, labels),
            OSHIControlPayload.listPreview(OSHIControlPayload.BLOCKED_NOTICE, labels),
        )) {
            assertFalse("the sentinel leaked to a surface: $surface", surface.contains("BLOCKED"))
        }
    }

    // ── The three rules ────────────────────────────────────────────────────────

    @Test
    fun `a notice never answers a notice`() {
        // Two people who have blocked each other must not ping one another
        // forever. `suppressesPush` is the screen the sender applies, so the
        // notice being in it is what closes the loop.
        assertTrue(
            "the notice must screen itself out, or a mutual block is a ping-pong",
            OSHIControlPayload.suppressesPush(OSHIControlPayload.BLOCKED_NOTICE),
        )
    }

    @Test
    fun `machinery is never answered`() {
        // A typing indicator or a receipt from a blocked peer is not a person
        // trying to reach anyone, and the periodic profile broadcast is not even
        // an action. Answering any of them would emit notices with nobody asking.
        for (p in listOf(
            "📬DELIVERY_RECEIPT📬abc", "📖READ_RECEIPT📖{}", "⌨️TYPING⌨️", "✍️TYPING✍️",
            "📸PROFILE_UPDATE📸x", "🎨WALLPAPER_UPDATE🎨x", "🔧ACTION🔧{}",
            "{\"type\":\"member_sync\",\"groupId\":\"g1\"}",
        )) {
            assertTrue("$p must not draw a notice", OSHIControlPayload.suppressesPush(p))
        }
        // …while a message a person actually typed must.
        assertFalse(OSHIControlPayload.suppressesPush("hello?"))
    }

    @Test
    fun `the sender screens traffic with the catalogue, not a private list`() {
        val body = noticeBody()
        assertTrue(
            "the screen must be OSHIControlPayload.suppressesPush — a second " +
                "hand-written prefix list is what drifted last time (__PUSH_SPAM_2026_09_17__)",
            body.contains("OSHIControlPayload.suppressesPush(plaintext)"),
        )
    }

    @Test
    fun `the notice is rate limited and the limit survives a restart`() {
        val body = noticeBody()
        assertTrue(
            "without an interval, every message from a blocked spammer draws a " +
                "reply, and those replies tell them when their target is online",
            body.contains("BLOCKED_NOTICE_INTERVAL_MS"),
        )
        assertTrue(
            "an in-memory limit resets on every process death, which on Android is " +
                "every push wake — it has to be persisted",
            body.contains("getSharedPreferences(\"blocked_notice\""),
        )
        assertTrue(
            "the stamp must be written BEFORE the send, or a transport that hangs " +
                "leaves the door open for a second notice",
            body.indexOf("prefs.edit()") < body.indexOf("v2MessageRouter.sendText"),
        )
        assertEquals(
            "one day, and the same number as iOS",
            24L * 60 * 60 * 1000,
            interval(),
        )
    }

    @Test
    fun `the notice goes around the send gate, which would otherwise refuse it`() {
        // `sendTextMessage` refuses every send to a blocked key (that is the other
        // half of this feature). The notice is the one intended exception, so it
        // must reach a transport directly.
        val body = noticeBody()
        assertFalse(
            "sendTextMessage would refuse this — it is addressed to a blocked key",
            body.contains("sendTextMessage("),
        )
        assertTrue(body.contains("v2MessageRouter.sendText("))
    }

    // ── The other half: nothing leaves for a blocked key ───────────────────────

    @Test
    fun `both send paths still refuse a blocked recipient`() {
        val gates = Regex("if \\(isBlockedSender\\(recipientPublicKey\\)\\)")
            .findAll(repositorySource).count()
        assertEquals(
            "text and media must BOTH refuse at the repository — the composer " +
                "banner is a screen, and quick-reply, share, scheduled sends, the " +
                "forward picker and the watch do not go through a screen",
            2, gates,
        )
    }

    // ── Cross-platform ─────────────────────────────────────────────────────────

    @Test
    fun `iOS spells the sentinel exactly the same way`() {
        val swift = repoFile("OSHI/OSHIControlPayload.swift").readText()
        assertTrue(
            "iOS must carry 🚫BLOCKED🚫 in its catalogue — a one-character " +
                "difference is a notice that arrives as unreadable JSON, and no " +
                "device test on either side would show it",
            swift.contains("Entry(\"${OSHIControlPayload.BLOCKED_NOTICE}\", .rendered)"),
        )
        assertTrue(
            "iOS must screen the notice out of its own answers too",
            Regex("renderedButNotWorthWaking[^\\]]*${OSHIControlPayload.BLOCKED_NOTICE}", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(swift),
        )
    }

    @Test
    fun `the app no longer promises that blocking is silent`() {
        val en = moduleFile("src/main/res/values/strings.xml").readText()
        val about = Regex(
            "<string name=\"blocked_contacts_about_desc\">(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(en)?.groupValues?.get(1) ?: fail("blocked_contacts_about_desc is gone").let { "" }
        assertFalse(
            "the description still says they are not notified, while the code now " +
                "notifies them: $about",
            about.contains("not be notified") || about.contains("won't be notified"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The body of `noticeBlockedSender`, braces balanced. */
    private fun noticeBody(): String {
        val start = repositorySource.indexOf("private suspend fun noticeBlockedSender(")
        assertTrue("noticeBlockedSender not found in MessageRepository", start > 0)
        var depth = 0
        var i = repositorySource.indexOf('{', start)
        val open = i
        while (i < repositorySource.length) {
            when (repositorySource[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        return repositorySource.substring(open, minOf(i + 1, repositorySource.length))
    }

    private fun interval(): Long {
        val m = Regex("BLOCKED_NOTICE_INTERVAL_MS = ([^\\n]+)").find(repositorySource)
            ?: fail("no interval constant").let { return -1 }
        // "24L * 60 * 60 * 1000"
        return m.groupValues[1].split("*")
            .map { it.trim().removeSuffix("L").toLong() }
            .reduce(Long::times)
    }
}
