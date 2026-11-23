package com.oshi.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __POLLS_2026_10_23__ Keeps the Android poll aligned with `OSHI/PollViews.swift`
 * and `OSHI/PollMessage.swift`.
 *
 * `PollWireAndTallyTest` proves Android reads and writes the agreed format; this
 * one proves the two FILES still agree about what that format is. The two
 * questions are different: a shared golden payload cannot notice that iOS has
 * raised the option limit to 16 and Android has not, nor that a key was renamed
 * in both encoders at once.
 *
 * It reads source text, because what drifts is a literal — a key name, a bound,
 * a list of durations — and no amount of state testing can see that.
 *
 * Unlike [CrossPlatformMeshParityTest], which quotes the Swift in comments, this
 * one opens the Swift file. The two trees live side by side in one repository;
 * if that stops being true this test says so instead of quietly passing.
 */
class PollParityTest {

    private fun swift(name: String): String {
        val f = listOf(File("../OSHI/$name"), File("../../OSHI/$name"))
            .firstOrNull { it.isFile }
        assertNotNull("iOS source not found beside the Android tree: $name", f)
        return f!!.readText()
    }

    private fun kotlin(path: String): String {
        val f = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
            .firstOrNull { it.isFile }
        assertNotNull("not found on either test working directory: $path", f)
        return f!!.readText()
    }

    private val swiftModel by lazy { swift("PollMessage.swift") }
    private val swiftViews by lazy { swift("PollViews.swift") }
    private val ktModel by lazy { kotlin("com/oshi/messenger/data/model/PollMessage.kt") }
    private val ktViews by lazy { kotlin("com/oshi/messenger/ui/components/PollViews.kt") }
    private val ktStore by lazy { kotlin("com/oshi/messenger/service/PollStore.kt") }

    /**
     * Every wire key, on both sides. A renamed key is a poll that one platform
     * renders and the other shows as a blank card.
     */
    @Test
    fun `the wire keys are the same on both platforms`() {
        // Keys Swift spells as a CodingKey raw value: the literal is in both files.
        val quoted = listOf("\"v\"", "\"q\"", "\"opts\"", "\"multi\"", "\"anon\"",
                            "\"at\"", "\"closes\"", "\"by\"", "\"poll\"")
        for (k in quoted) {
            assertTrue("$k missing from OSHI/PollMessage.swift", swiftModel.contains(k))
            assertTrue("$k missing from PollMessage.kt", ktModel.contains(k))
        }
        // `id` and `picks` carry NO raw value on the Swift side, and a CodingKey
        // without one means the wire key IS the case name. So the literal only
        // exists in Kotlin; what has to hold on Swift is that the case is still
        // spelled that way and still unmapped, because renaming the property
        // there would silently rename the wire key.
        for (bare in listOf("id", "picks")) {
            assertTrue("\"$bare\" missing from PollMessage.kt", ktModel.contains("\"$bare\""))
            assertTrue(
                "OSHI/PollMessage.swift must keep `case $bare` with no raw value, " +
                    "or the wire key changes with the property name",
                Regex("""case\s+$bare\s*$""", RegexOption.MULTILINE)
                    .containsMatchIn(swiftModel),
            )
        }
        // And the one key that must never exist: the voter is the envelope's
        // sender, authenticated by the ratchet.
        assertFalse("a voter key on the wire lets one member vote as another",
                    ktModel.contains("\"voter\""))
        assertFalse("a voter key on the wire lets one member vote as another",
                    swiftModel.contains("\"voter\""))
    }

    /** The limits both platforms enforce, read out of each file. */
    @Test
    fun `the limits are the same on both platforms`() {
        fun n(src: String, pattern: String): String =
            Regex(pattern).find(src)?.groupValues?.get(1)
                ?: error("could not read $pattern")

        assertEquals("question length",
            n(swiftModel, """maxQuestionLength\s*=\s*(\d+)"""),
            n(ktModel, """MAX_QUESTION_LENGTH\s*=\s*(\d+)"""))
        assertEquals("option length",
            n(swiftModel, """maxOptionLength\s*=\s*(\d+)"""),
            n(ktModel, """MAX_OPTION_LENGTH\s*=\s*(\d+)"""))
        assertEquals("minimum options",
            n(swiftModel, """minOptions\s*=\s*(\d+)"""),
            n(ktModel, """MIN_OPTIONS\s*=\s*(\d+)"""))
        assertEquals("maximum options",
            n(swiftModel, """maxOptions\s*=\s*(\d+)"""),
            n(ktModel, """MAX_OPTIONS\s*=\s*(\d+)"""))
        assertEquals("wire version",
            n(swiftModel, """currentVersion\s*=\s*(\d+)"""),
            n(ktModel, """CURRENT_VERSION\s*=\s*(\d+)"""))
    }

    /**
     * The composer offers the same closing times. A seventh option on one side
     * is a poll the other cannot even describe.
     */
    @Test
    fun `the composer offers the same durations`() {
        val wanted = listOf("never", "1h", "6h", "24h", "3d", "7d")
        for (d in wanted) {
            assertTrue("poll.duration.$d missing from OSHI/PollViews.swift",
                       swiftViews.contains("poll.duration.$d"))
            assertTrue("poll_duration_$d missing from PollViews.kt",
                       ktViews.contains("poll_duration_$d"))
        }
        assertEquals("a duration was added on one side only",
            Regex("""poll\.duration\.([a-z0-9]+)""").findAll(swiftViews)
                .map { it.groupValues[1] }.toSet(),
            Regex("""poll_duration_([a-z0-9]+)""").findAll(ktViews)
                .map { it.groupValues[1] }.toSet())
    }

    /** Both bubbles stopped taking a flag neither of them read. */
    @Test
    fun `neither bubble takes a sender flag`() {
        assertFalse("OSHI/PollViews.swift took isFromCurrentUser back",
                    Regex("""let isFromCurrentUser""").containsMatchIn(swiftViews))
        assertFalse("PollViews.kt took isFromMe back",
                    Regex("""^\s*isFromMe:""", RegexOption.MULTILINE).containsMatchIn(ktViews))
    }

    /**
     * The remaining time is said the way iOS says it. This bucketed into the
     * composer's six fixed labels until 2026-10-23, so a poll closing in twenty
     * minutes read "In 1 hour" on Android and "in 20 min" on an iPhone.
     */
    @Test
    fun `the closing time is relative, not bucketed`() {
        assertTrue("PollViews.kt must use getRelativeTimeSpanString, iOS's twin",
                   ktViews.contains("getRelativeTimeSpanString"))
        assertTrue("OSHI/PollViews.swift must use RelativeDateTimeFormatter",
                   swiftViews.contains("RelativeDateTimeFormatter"))
    }

    /**
     * Every path that records an inbound ballot must open the sealed file first.
     * iOS has no equivalent hole — `PollStore.shared` loads in its initialiser —
     * so a missing `attach` here is a vote that survives on one platform and is
     * lost on the other at the next launch.
     */
    @Test
    fun `every inbound ballot path attaches the store before recording`() {
        val callers = mapOf(
            // The DEFINITION, not the first call site: this file calls
            // `recordPollBallot` from three receive paths before defining it.
            "com/oshi/messenger/data/repository/MessageRepository.kt" to
                "private fun recordPollBallot",
            "com/oshi/messenger/service/GroupManager.kt" to "PollVotePayload.isVoteMessage",
        )
        for ((path, marker) in callers) {
            val src = kotlin(path)
            assertTrue("$path no longer records ballots — move this test",
                       src.contains(marker))
            val at = src.indexOf(marker)
            val window = src.substring(at, minOf(src.length, at + 1200))
            assertTrue(
                "$path records a ballot without PollStore.attach: a vote that " +
                    "arrives before the poll is first drawn would be lost on the next launch",
                window.contains("PollStore.attach("),
            )
        }
        // And the store still has the cap that bounds the file.
        assertTrue("the ballot file lost its bound", ktStore.contains("MAX_POLLS_KEPT"))
    }
}
