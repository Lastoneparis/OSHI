package com.oshi.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * __RESEND_TIMESTAMP_2026_09_17__ The envelope's `ts` is the ONLY clock the recipient
 * has for a message: iOS reads it straight into the bubble's date
 * (`MessageManager+V2.swift:1256` — `Date(timeIntervalSince1970: Double(env.ts) / 1000)`).
 * When Android stamped it inside the encrypt instead of carrying the message's own
 * date, a text arrived dated at the moment the send happened to reach the wire —
 * after the contact upsert, the local insert, the off-grid probe, a prekey fetch for a
 * new peer, a photo's compression, a blob upload, or (worst) a retry hours later. The
 * sender's thread kept the compose time; the recipient's did not. Reported from
 * production 2026-09-17: messages from Android landing at the BOTTOM of an iPhone
 * thread, newer than texts that were genuinely written after them.
 *
 * What this test pins is the WIRING, because the wiring is where the defect was: the
 * parameter existed nowhere and no call site passed anything. `V2MessageRouter.sendText`
 * and `sendFile` cannot be called from a JVM test — they need the Hilt graph, and
 * `V2Envelope` needs `android.util.Base64` — which is the same wall
 * `V2SendResumeWiringTest` documents for `sendFile`. So the contract is asserted
 * against the source: every v2 send that carries a USER-VISIBLE message must hand the
 * router that message's own timestamp.
 *
 * This is deliberately not a test of `originalTimestampMs ?: now`. Re-deriving that
 * expression in a test would mirror the implementation and could never fail — the bug
 * was never in the expression, it was in nobody supplying the left-hand side.
 *
 * It goes red on the code as it shipped in 1.6.19 (versionCode 59) and green on the fix.
 */
class V2EnvelopeTimestampWiringTest {

    private fun moduleFile(relative: String): File {
        // Gradle runs unit tests with the module directory as the working directory,
        // but walk upward anyway so the test survives being run from the repo root.
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

    private val repositorySource by lazy {
        moduleFile("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt").readText()
    }

    private val routerSource by lazy {
        moduleFile("src/main/java/com/oshi/messenger/network/v2/V2MessageRouter.kt").readText()
    }

    private val chatViewModelSource by lazy {
        moduleFile("src/main/java/com/oshi/messenger/ui/viewmodel/ChatViewModel.kt").readText()
    }

    /** Body of every `<receiver>.<name>(` call in [source], parens balanced. */
    private fun callsTo(source: String, name: String): List<String> {
        val receiver = if (name == "sendMessage") "vpsClient" else "v2MessageRouter"
        val calls = mutableListOf<String>()
        val marker = "$receiver.$name("
        var from = 0
        while (true) {
            val start = source.indexOf(marker, from)
            if (start < 0) return calls
            var depth = 0
            var i = start + marker.length - 1
            while (i < source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            calls += source.substring(start, minOf(i + 1, source.length))
            from = i + 1
        }
    }

    @Test
    fun `the router offers the message's own date for text`() {
        val decl = routerSource.indexOf("suspend fun sendText(")
        assertTrue("V2MessageRouter.sendText( not found", decl > 0)
        val body = routerSource.substring(decl, routerSource.indexOf("): Boolean", decl) + 10)
        assertTrue(
            "V2MessageRouter.sendText must accept originalTimestampMs — without it the " +
                "caller has no way to say when the message was actually written",
            body.contains("originalTimestampMs"),
        )
        assertTrue(
            "the envelope must prefer originalTimestampMs over the clock",
            routerSource.contains("ts = originalTimestampMs ?: System.currentTimeMillis()"),
        )
    }

    /**
     * __ATTACHMENT_DATED_AT_UPLOAD_2026_09_17__ An attachment is dated when its upload
     * FINISHES, not when it was picked — decided by the product owner, and it is what
     * iOS does (`OSHI/V2MessageRouter.swift:911`, whose `sendFile` takes no
     * `originalTimestamp`). The reason is not tidiness: a video that took ten minutes
     * to upload, dated ten minutes ago, lands ABOVE everything sent while it was
     * uploading — buried in messages the recipient has already read, where they will
     * never notice it arrived.
     *
     * So `sendFile` must NOT grow the knob back, and the media call sites must not
     * pass one. This is asserted because the opposite is the intuitive change to make.
     */
    @Test
    fun `an attachment is dated when its upload finishes, like iOS`() {
        val decl = routerSource.indexOf("suspend fun sendFile(")
        assertTrue("V2MessageRouter.sendFile( not found", decl > 0)
        val body = routerSource.substring(decl, routerSource.indexOf("): Boolean", decl) + 10)
        assertFalse(
            "sendFile must NOT take originalTimestampMs — an attachment is dated at " +
                "upload completion, matching iOS",
            body.contains("originalTimestampMs"),
        )
        val fileSends = callsTo(repositorySource, "sendFile")
        assertTrue("expected the blob send site", fileSends.isNotEmpty())
        fileSends.forEach { call ->
            assertFalse(
                "the blob send must not carry the row's timestamp:\n$call",
                call.contains("originalTimestampMs"),
            )
        }
    }

    @Test
    fun `every user-visible v2 send carries the row's timestamp`() {
        // TEXT only. Media is dated at upload completion on purpose — see
        // `an attachment is dated when its upload finishes, like iOS`.
        //
        // __BLOCKED_NOTICE_2026_09_18__ And USER-visible only, which the name of
        // this test already said. The blocked-notice send is not a message anyone
        // wrote: it is emitted by the receiving device the moment it drops
        // something, so "the row's timestamp" has no meaning for it — there is no
        // row. It is excluded by the payload it carries rather than by its call
        // site, so moving the sender does not quietly re-open the hole.
        //
        // __SHARED_NICKNAME_2026_09_22__ Same for the profile update/request control
        // payloads, which now go V2-first: they are minted with a fresh
        // `UUID.randomUUID()` id precisely because no chat row exists for them, so
        // there is no row timestamp to carry. A user message always sends under its
        // row's `messageId`, so a random id at the call site is the signature of a
        // row-less control send.
        //
        // __V2_CAPTION_2026_09_27__ The caption follow-up belongs to its ATTACHMENT, which
        // is dated at upload completion: it must land right after the photo it repeats
        // the caption of, not at the minute the photo was picked.
        val sends = callsTo(repositorySource, "sendText")
            .filter {
                "iosInline" !in it && "BLOCKED_NOTICE" !in it && "UUID.randomUUID()" !in it &&
                    "captionFollowUpId" !in it
            }
        assertTrue("expected the v2 text send; found ${sends.size}", sends.isNotEmpty())
        sends.forEachIndexed { index, call ->
            assertTrue(
                "v2 send #${index + 1} in MessageRepository does not pass the message's own " +
                    "timestamp, so the recipient will date it at the instant the send reached " +
                    "the wire:\n$call",
                // Widened 2026-10-28 from the literal `= message.timestamp`, which was a
                // proxy for "a real timestamp" and fitted only the call sites that existed
                // when this was written. The live-location UPDATE has no `message` val —
                // its row is `existing` and its instant is `updatedAtMs`, shared with the
                // local `updateContent` so both ends date the update identically.
                //
                // The teeth are unchanged. The shipped 1.6.19 bug passed NOTHING, which
                // still fails the first clause; and the obvious cheat — handing the router
                // the wire clock it would have used anyway — fails the second.
                call.contains("originalTimestampMs =") &&
                    !call.contains("originalTimestampMs = System.currentTimeMillis()"),
            )
        }
    }

    /**
     * The legacy IPFS/VPS fallback carries its OWN date field, read by iOS with
     * `Date(timeIntervalSince1970:)` (`MessageManager.swift:4184`), and it had the
     * same defect. It matters more than the v2 path, not less: it only runs once the
     * v2 attempt has already spent a prekey fetch and a relay timeout, so a stamp
     * taken at upload time is the end of the longest send the app can make.
     *
     * Note the epoch. Almost every other timestamp Android puts on the wire is
     * Apple-reference seconds (`- 978307200`), and this one must NOT be: iOS reads it
     * as Unix seconds. Getting that wrong dates the message in 1995.
     */
    @Test
    fun `the legacy fallback carries the row's date, in Unix seconds`() {
        val client = moduleFile(
            "src/main/java/com/oshi/messenger/network/vps/VPSClient.kt"
        ).readText()
        assertTrue(
            "VPSClient.sendMessage must accept originalTimestampMs",
            client.contains("originalTimestampMs: Long? = null"),
        )
        assertTrue(
            "the IPFS envelope must prefer the message's own date over the clock",
            client.contains(
                "put(\"timestamp\", (originalTimestampMs ?: System.currentTimeMillis()) / 1000.0)"
            ),
        )
        assertTrue(
            "this envelope is Unix seconds — the Apple-epoch offset must not appear on it",
            "put(\"timestamp\", (originalTimestampMs ?: System.currentTimeMillis()) / 1000.0 - 978307200"
                !in client,
        )
        // Text and location only: the two media fallbacks deliberately omit it.
        val sends = callsTo(repositorySource, "sendMessage")
            .filter { "originalTimestampMs" in it }
        assertTrue("expected the legacy text sends; found ${sends.size}", sends.size >= 3)
        sends.forEachIndexed { i, call ->
            assertTrue(
                "legacy send #${i + 1} does not pass the row's timestamp:\n$call",
                call.contains("originalTimestampMs = message.timestamp"),
            )
        }
    }

    @Test
    fun `a retry re-sends the same message rather than writing a new one`() {
        val start = chatViewModelSource.indexOf("fun retrySendMessage(")
        assertTrue("retrySendMessage not found", start > 0)
        val body = chatViewModelSource.substring(start, start + 900)
        assertTrue(
            "retrySendMessage must pass resendOf, or the retry mints a new id and a new " +
                "timestamp and the message is re-dated to the moment it finally got through",
            body.contains("resendOf = message"),
        )
        assertTrue(
            "sendTextMessage must keep the resent message's id",
            repositorySource.contains("resendOf?.id ?: UUID.randomUUID().toString()"),
        )
        assertTrue(
            "sendTextMessage must keep the resent message's date",
            repositorySource.contains("resendOf?.timestamp ?: System.currentTimeMillis()"),
        )
    }
}
