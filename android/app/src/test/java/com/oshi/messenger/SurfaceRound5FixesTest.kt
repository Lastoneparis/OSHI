package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.OSHIControlPayload.Kind
import com.oshi.messenger.network.web.WebSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SURFACE, round 5 — the hand-offs H-1…H-8 of
 * `parity/verify/2026-08-23-surface-handoff.md`, plus the poll-tick write the
 * battery-drain team handed over.
 *
 * ## Why some of these read source text
 *
 * Three of the eight fixes live inside a `@Composable` (`ChatScreen`,
 * `GroupChatScreen`) and two inside a Hilt-injected singleton that needs a real
 * `Context` and a `NotificationManager` (`MessageRepository`, `GroupManager`).
 * None of that is reachable from a JVM unit test. What IS reachable is the
 * decision each of them delegates to — `OSHIControlPayload` and
 * `NotificationPresentation`, both pure — so every one of those fixes is pinned
 * from two directions:
 *
 *  * **behaviour**: the exact string the surface will now show, per payload type,
 *    asserted verbatim (never "the output contains no `{`", which passes for a
 *    preview that prints the sentinel, for one that prints nothing where a real
 *    message belonged, and for one that prints hardcoded English);
 *  * **wiring**: that the call site actually asks that question, and asks it on the
 *    RAW string. A correct helper nobody calls is exactly the shape of this whole
 *    hand-off — `notification_reaction` was a correct key nothing consumed, and
 *    `MessageSanitizer` was a helper four call sites still called.
 *
 * Working directory for a Gradle unit test is the module dir, so paths are
 * relative to `app/` (same convention as `MediaUiRound3Test`).
 */
class SurfaceRound5FixesTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Source access
    // ─────────────────────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val f = File("src/main/java/com/oshi/messenger/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val messageRepository by lazy { source("data/repository/MessageRepository.kt") }
    private val groupManager by lazy { source("service/GroupManager.kt") }
    private val webSessionManager by lazy { source("network/web/WebSessionManager.kt") }
    private val chatScreen by lazy { source("ui/screens/ChatScreen.kt") }
    private val groupChatScreen by lazy { source("ui/screens/GroupChatScreen.kt") }
    private val v2Router by lazy { source("network/v2/V2MessageRouter.kt") }

    /**
     * The body of a Kotlin declaration, by brace balance from the first `{` after
     * [signature]. Blunt but sufficient: it means an assertion about
     * `showMessageNotification` cannot be satisfied by an identical line somewhere
     * else in a 5000-line file.
     */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        val open = text.indexOf('{', start)
        assertTrue("no body for: $signature", open >= 0)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced body for: $signature")
    }

    /** [chars] of source starting at [marker] — for a site whose enclosing declaration is huge. */
    private fun region(text: String, marker: String, chars: Int): String {
        val at = text.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        return text.substring(at, minOf(text.length, at + chars))
    }

    /** Labels whose every value is a unique sentinel, so hardcoded English fails loudly. */
    private val labels = OSHIControlPayload.Labels(
        callOutgoing = "L_OUT",
        callIncoming = "L_IN",
        callMissed = "L_MISS",
        locationLive = "L_LIVE",
        locationShared = "L_LOC",
        scheduledCall = "L_SCHED",
        checkIn = "L_CHECKIN",
        profileUpdated = "L_PROFILE",
        wallpaperChanged = "L_WALL",
        reaction = "L_REACTION",
        keyRotation = "L_KEYROT",
        mediaImage = "L_IMAGE",
        mediaVideo = "L_VIDEO",
        mediaAudio = "L_AUDIO",
        mediaDocument = "L_DOC",
        mediaFile = "L_FILE",
        encryptedMessage = "L_ENC",
        opaqueMessage = "L_OPAQUE"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // H-1 / H-2 — the two LOCAL notification builders
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `H-1 the local 1 to 1 notification asks the catalog on the raw string and can post nothing`() {
        val fn = body(messageRepository, "private suspend fun showMessageNotification(")

        assertTrue(
            "the 1:1 local notification must go through NotificationPresentation",
            fn.contains("NotificationPresentation.decideCapped(")
        )
        assertTrue(
            "it must be asked on the RAW wire body — detection on a sanitised string " +
                "is how a sanitiser gets silently negated",
            fn.contains("raw = preview")
        )
        assertTrue(
            "Suppress must post NOTHING: no banner, no sound, no shade entry",
            Regex("""if \(decision !is NotificationPresentation\.Decision\.Show\) return""")
                .containsMatchIn(fn)
        )
        assertTrue(
            "the fallback body must be the localized resource, not a literal",
            fn.contains("R.string.notification_sent_attachment")
        )
        assertFalse(
            "MessageSanitizer is the retired 4th copy of the catalog; it returns " +
                "💬REPLY💬 and 📢GROUP_UPDATE📢 verbatim",
            fn.contains("MessageSanitizer.sanitizeForDisplay")
        )
        assertFalse(
            "the import must go too, or the next edit reaches for it again",
            messageRepository.contains("import com.oshi.messenger.util.MessageSanitizer")
        )
        assertFalse(
            "an `.ifEmpty` fallback inverts the catalog's only correct answer",
            fn.contains(".ifEmpty {")
        )
    }

    @Test
    fun `H-2 the local group notification suppresses instead of ringing, and localizes`() {
        // `handleIncomingGroupMessage` is ~220 lines; take the notification block
        // itself so the assertions cannot be satisfied from elsewhere in the file.
        val call = region(groupManager, "// Show local notification if group is not muted", 2600)

        assertTrue(
            "the group mute check above it is correct and stays",
            call.contains("!groupForNotif.isMuted")
        )
        assertTrue(
            "the group local notification must go through NotificationPresentation",
            call.contains("NotificationPresentation.decideCapped(")
        )
        assertTrue(
            "asked on the RAW wire body",
            call.contains("raw = messageContent")
        )
        assertTrue(
            "showGroupMessageNotification must only be REACHED on Decision.Show — a " +
                "group key rotation, a roster sync or a receipt batch must not ring anyone",
            Regex(
                """if \(decision is NotificationPresentation\.Decision\.Show\)\s*\{""" +
                    """[^}]*showGroupMessageNotification\("""
            ).containsMatchIn(call)
        )
        assertTrue(call.contains("R.string.notification_sent_attachment"))
        assertFalse(
            "MessageSanitizer must no longer be called from GroupManager",
            groupManager.contains("MessageSanitizer.sanitizeForDisplay")
        )
        assertFalse(
            "and its import must be gone",
            groupManager.contains("import com.oshi.messenger.util.MessageSanitizer")
        )
        assertFalse("no `.ifEmpty` fallback on this path", call.contains(".ifEmpty {"))
    }

    /**
     * The behaviour behind both. `NotificationPresentation` is covered per payload in
     * `SurfacePresentationTest`; what is pinned here is the property the two call
     * sites now depend on and did not before: **a silent payload yields no banner at
     * all**, for EVERY silent catalog entry, by construction rather than by list.
     */
    @Test
    fun `every silent catalog entry decides Suppress, so neither builder can post`() {
        val silent = OSHIControlPayload.catalog.filter { it.kind == Kind.SILENT }
        assertTrue("the catalog must still declare silent entries", silent.size >= 10)
        for (entry in silent) {
            val wire = entry.prefix + "{\"payload\":\"whatever\"}"
            assertEquals(
                "${entry.prefix} must post NOTHING",
                com.oshi.messenger.service.NotificationPresentation.Decision.Suppress,
                com.oshi.messenger.service.NotificationPresentation.decideCapped(wire, labels, "L_ATTACH")
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H-3 — the web-session mirror stops carrying its own catalog copy
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `H-3 the three silent markers the hand-written list missed are now filtered`() {
        // Each of these was mirrored to a linked browser, body and all, and became
        // the conversation's "latest message" in the web sidebar.
        assertTrue(
            "a call signal carries SDP and ICE candidates",
            WebSessionManager.isControlMessage(
                OSHIControlPayload.CALL_SIGNAL + "{\"sdp\":\"v=0 o=- 46117 2 IN IP4 127.0.0.1\"}"
            )
        )
        assertTrue(
            "a group update carries the whole roster and admin set",
            WebSessionManager.isControlMessage(
                OSHIControlPayload.GROUP_UPDATE + "{\"id\":\"g1\",\"members\":[\"a\",\"b\"],\"admins\":[\"a\"]}"
            )
        )
        assertTrue(
            "a session reset is pure protocol",
            WebSessionManager.isControlMessage(OSHIControlPayload.SESSION_RESET)
        )
    }

    @Test
    fun `H-3 every silent catalog entry is filtered, in both emoji spellings`() {
        for (entry in OSHIControlPayload.catalog.filter { it.kind == Kind.SILENT }) {
            val canonical = entry.prefix + "{}"
            assertTrue(
                "${entry.prefix} must never reach a linked browser",
                WebSessionManager.isControlMessage(canonical)
            )
            // The bare-codepoint spelling is the half the hand-written list missed:
            // it built its prefixes by hand and had none of the catalog's U+FE0F
            // handling, which is exactly how the four retired Android lists failed.
            val stripped = entry.prefix.replace("\uFE0F", "") + "{}"
            if (stripped != canonical) {
                assertTrue(
                    "${entry.prefix} without U+FE0F must be filtered too",
                    WebSessionManager.isControlMessage(stripped)
                )
            }
        }
    }

    @Test
    fun `H-3 the sentinel-less group sync JSON is filtered too`() {
        assertTrue(WebSessionManager.isControlMessage("{\"type\":\"sync_request\",\"groupId\":\"g1\"}"))
        assertTrue(WebSessionManager.isControlMessage("{\"type\": \"member_sync\", \"groupId\": \"g1\"}"))
    }

    @Test
    fun `H-3 the profile-update payload the old list filtered is STILL filtered`() {
        // Kind.RENDERED in the catalog (the chat draws "You updated your profile"),
        // but the browser is sent `content` raw and the payload is a picture blob.
        // Collapsing onto isSilent alone would have silently regressed this.
        assertTrue(
            WebSessionManager.isControlMessage(
                OSHIControlPayload.PROFILE_UPDATE + "{\"profilePicture\":\"AAECAwQ=\"}"
            )
        )
    }

    @Test
    fun `H-3 the two web-only markers survive the collapse`() {
        assertTrue(WebSessionManager.isControlMessage("🔄SYNC🔄{}"))
        assertTrue(WebSessionManager.isControlMessage("🔗LINK🔗{}"))
    }

    @Test
    fun `H-3 real user messages still reach the browser`() {
        // The failure mode on the other side of this fix: over-filtering would make a
        // linked browser show an empty conversation.
        assertFalse(
            "a reply envelope is a REAL message the user typed",
            WebSessionManager.isControlMessage(
                OSHIControlPayload.REPLY + "{\"content\":\"see you at 8\",\"replyTo\":{\"id\":\"m1\"}}"
            )
        )
        assertFalse(
            WebSessionManager.isControlMessage(OSHIControlPayload.LOCATION + "{\"latitude\":48.85}")
        )
        assertFalse(
            WebSessionManager.isControlMessage(OSHIControlPayload.CALL_SUMMARY + "📞↗️call.outgoing|1:23")
        )
        assertFalse(WebSessionManager.isControlMessage("we should add a 📍LOCATION📍 marker"))
        assertFalse(WebSessionManager.isControlMessage("hello there"))
    }

    @Test
    fun `H-3 the sixth partial prefix list is gone from the source`() {
        assertFalse(
            "CONTROL_PREFIXES was the sixth partial copy of the catalog",
            webSessionManager.contains("private val CONTROL_PREFIXES = listOf(")
        )
        // Expression body, so read forward from the signature rather than by braces.
        assertTrue(
            "the predicate must delegate to the one catalog",
            region(webSessionManager, "fun isControlMessage(content: String): Boolean =", 260)
                .contains("OSHIControlPayload.isSilent(content)")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H-4 — the clipboard
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * What Copy → paste now produces, per payload type. These are the strings that
     * used to be sentinel + JSON in any other app on the phone.
     */
    @Test
    fun `H-4 the clipboard body per payload type`() {
        val cases = listOf(
            OSHIControlPayload.LOCATION + "{\"latitude\":48.85,\"longitude\":2.35,\"address\":\"12 Rue de Rivoli, Paris, France\"}"
                to "📍 12 Rue de Rivoli",
            OSHIControlPayload.LOCATION + "{\"isLive\":true,\"latitude\":48.85}" to "📍 L_LIVE",
            OSHIControlPayload.CALL_SUMMARY + "📞↗️call.outgoing|1:23" to "📞↗️ L_OUT • 1:23",
            OSHIControlPayload.MISSED_CALL + "❌call.missed" to "❌ L_MISS",
            OSHIControlPayload.REACTION + "{\"emoji\":\"❤️\",\"action\":\"add\"}" to "🔥 L_REACTION",
            OSHIControlPayload.CHECK_IN + "{\"type\":\"arrived\"}" to "🛡️ L_CHECKIN",
            OSHIControlPayload.REPLY + "{\"content\":\"see you at 8\",\"replyTo\":{\"id\":\"m1\"}}" to "see you at 8",
            OSHIControlPayload.FORWARDED + "{\"content\":\"the address is 12 Rivoli\"}" to "the address is 12 Rivoli",
            "just some prose" to "just some prose"
        )
        for ((raw, expected) in cases) {
            assertEquals(
                "clipboard body for ${raw.take(24)}",
                expected,
                OSHIControlPayload.previewText(raw, labels)
            )
        }
    }

    @Test
    fun `H-4 a silent payload copies NOTHING and shows no confirmation`() {
        for (entry in OSHIControlPayload.catalog.filter { it.kind == Kind.SILENT }) {
            assertEquals(
                "${entry.prefix} must yield an empty clipboard body",
                "",
                OSHIControlPayload.previewText(entry.prefix + "{}", labels)
            )
        }
        // …and the call sites must GUARD on that, or "" lands on the clipboard and a
        // "Copied" toast claims it worked. iOS guards for the same reason
        // (ChatView.swift:4547-4553).
        for ((name, text) in listOf("ChatScreen" to chatScreen, "GroupChatScreen" to groupChatScreen)) {
            val onCopy = body(text, "onCopy = {")
            assertTrue(
                "$name.onCopy must route the raw body through the catalog",
                onCopy.contains("OSHIControlPayload.previewText(")
            )
            assertTrue(
                "$name.onCopy must copy nothing when the payload is silent",
                onCopy.contains("if (displayContent.isNotEmpty()) {")
            )
            // What matters is WHAT reaches the clipboard, not which API carries it.
            // This asserted the literal `clipboardManager.setText(AnnotatedString(…))`
            // until __CLIPBOARD_HYGIENE_2026_10_01__ deliberately routed both screens
            // through `SensitiveClipboard.copy`, so a decrypted message is a sensitive
            // clip that clears after 60 s. The behaviour H-4 protects never changed —
            // the catalog's answer is still the thing copied — but the test went red on
            // the spelling and stayed red. A test is an assertion; it goes stale like
            // any other line. So both spellings are accepted and the property is pinned
            // from the other side as well: the RAW wire body must never be copied.
            assertTrue(
                "$name.onCopy must put the CATALOG's answer on the clipboard, not the raw body",
                Regex(
                    """(SensitiveClipboard\.copy\(\s*context\s*,\s*|""" +
                        """clipboardManager\.setText\(AnnotatedString\()(textWithLink|displayContent)"""
                ).containsMatchIn(onCopy)
            )
            assertFalse(
                "$name.onCopy must never copy the raw wire body",
                Regex(
                    """(SensitiveClipboard\.copy\(\s*context\s*,\s*|""" +
                        """clipboardManager\.setText\(AnnotatedString\()raw\b"""
                ).containsMatchIn(onCopy)
            )
        }
        // The 1:1 screen appends a footer; assert the body it wraps is the catalog's.
        assertTrue(
            body(chatScreen, "onCopy = {").contains(
                "val textWithLink = \"\$displayContent\\n\\n--- Sent via OSHI Messenger"
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H-5 — the smart-reply / tone trigger
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `H-5 the smart-reply trigger filters control payloads, on both screens`() {
        val oneToOne = body(chatScreen, "LaunchedEffect(messages.size) {")
        assertTrue(
            "the 1:1 trigger must skip control payloads before handing text to the LLM",
            oneToOne.contains("messages.firstOrNull { !OSHIControlPayload.isControl(it.content) }")
        )
        assertTrue(oneToOne.contains("nlpViewModel.generateSmartReplies(lastMsg.content)"))

        val group = body(groupChatScreen, "// Trigger smart replies on new group message from others")
        assertTrue(
            "the group trigger reads the list in ARRIVAL order, so lastOrNull is the newest",
            group.contains("messages.lastOrNull { !OSHIControlPayload.isControl(it.content) }")
        )
        assertTrue(group.contains("nlpViewModel.generateSmartReplies(lastMsg.content)"))
    }

    @Test
    fun `H-5 the payloads that used to reach the LLM are the ones isControl claims`() {
        // The trigger's whole correctness is this predicate. Asserted per payload so
        // that a catalog entry losing its claim shows up here.
        assertTrue(OSHIControlPayload.isControl(OSHIControlPayload.LOCATION + "{\"latitude\":48.85}"))
        assertTrue(OSHIControlPayload.isControl(OSHIControlPayload.CALL_SUMMARY + "📞↗️call.outgoing|1:23"))
        assertTrue(OSHIControlPayload.isControl(OSHIControlPayload.REACTION + "{\"emoji\":\"❤️\"}"))
        assertTrue(OSHIControlPayload.isControl(OSHIControlPayload.READ_RECEIPT + "abc"))
        // …and prose is not, or the strip would go silent on real conversations.
        assertFalse(OSHIControlPayload.isControl("see you at 8"))
        assertFalse(OSHIControlPayload.isControl("we should add a 📍LOCATION📍 marker"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H-6 — pinned banner, edit banner, translate sheet
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `H-6 the pinned banner reads the catalog, not the retired sanitiser`() {
        assertTrue(
            chatScreen.contains(
                "OSHIControlPayload.previewText(pinMsg.content, controlLabels).take(60)"
            )
        )
        assertFalse(
            "MessageSanitizer.sanitizeForDisplay printed \"📞 Call\" in all 34 locales",
            chatScreen.contains("MessageSanitizer.sanitizeForDisplay(pinMsg.content)")
        )
        // The behaviour the banner now has, verbatim.
        assertEquals(
            "📞↗️ L_OUT • 1:23",
            OSHIControlPayload.previewText(
                OSHIControlPayload.CALL_SUMMARY + "📞↗️call.outgoing|1:23", labels
            ).take(60)
        )
        assertEquals(
            "see you at 8",
            OSHIControlPayload.previewText(
                OSHIControlPayload.REPLY + "{\"content\":\"see you at 8\",\"replyTo\":{\"id\":\"m1\"}}", labels
            ).take(60)
        )
    }

    @Test
    fun `H-6 the edit banner and the translate sheet go through the catalog`() {
        // __SHOW_WHAT_IS_BEING_EDITED_2026_11_06__ The banner now previews
        // `editingOriginal` — the text as it stood when Edit was tapped —
        // instead of `it.editedContent ?: it.content`, which was exactly what
        // the field had been prefilled with and so showed the same sentence
        // twice. The H-6 property this test exists for is unchanged and still
        // asserted: whatever the banner shows goes through the catalog, so a
        // control payload can never render raw there.
        assertTrue(
            "the \"editing message\" banner had no formatting at all",
            chatScreen.contains(".previewText(editingOriginal, controlLabels)")
        )
        assertTrue(
            "the banner must show the text as it stood when Edit was tapped, " +
                "not a copy of the live draft",
            chatScreen.contains("editingOriginal = msg.editedContent ?: msg.content")
        )
        assertTrue(
            "the translate sheet showed AND translated the raw payload",
            body(chatScreen, "onTranslate = {").contains("OSHIControlPayload.previewText(")
        )
    }

    @Test
    fun `H-6 ChatScreen resolves its own localized labels`() {
        // Without this the three H-4/H-6 sites cannot be localized at all — the
        // screen-level `controlLabels` did not exist before this pass (the two that
        // did are inside MessageBubble and ReplyPreviewBanner).
        val decl = Regex("""val controlLabels = remember\(context\) \{ OSHIControlPayload\.labels\(context\) \}""")
        val at = decl.findAll(chatScreen).map { it.range.first }.toList()
        assertTrue("expected a screen-level declaration plus the two bubble ones", at.size >= 3)
        assertTrue(
            "the screen-level one must be in scope at the onCopy / pinned-banner sites",
            at.first() < chatScreen.indexOf("onCopy = {")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H-8 — the Hebrew notification_reaction
    // ─────────────────────────────────────────────────────────────────────────

    private fun reactionLabel(dir: String): String? {
        val f = File("src/main/res/$dir/strings_parity_chat.xml")
        if (!f.exists()) return null
        return Regex("""<string name="notification_reaction">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(f.readText())?.groupValues?.get(1)
    }

    @Test
    fun `H-8 the Hebrew reaction label is real Hebrew and survives the format-specifier guard`() {
        val hebrew = reactionLabel("values-iw")
        assertNotNull("values-iw/strings_parity_chat.xml must still declare the key", hebrew)
        assertFalse(
            "%@ is the Objective-C specifier; getString with no varargs prints it verbatim",
            hebrew!!.contains("%@")
        )
        assertTrue(
            "the replacement must actually be Hebrew, not the English fallback",
            hebrew.any { it in '֐'..'׿' }
        )
        // The whole point: the guard used to swallow this value and hand back English.
        // Now the guard passes it through, so a Hebrew device shows Hebrew.
        val english = OSHIControlPayload.Labels().reaction
        assertEquals(
            "the Hebrew label must now reach the reaction row and banner",
            hebrew,
            OSHIControlPayload.noFormatSpecifiers(hebrew, english)
        )
    }

    @Test
    fun `H-8 no locale ships a format specifier in the argument-free reaction label`() {
        val dirs = File("src/main/res").listFiles()
            ?.filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            ?.map { it.name }
            .orEmpty()
        assertTrue("expected the full locale set", dirs.size >= 30)

        var seen = 0
        for (dir in dirs) {
            val value = reactionLabel(dir) ?: continue
            seen++
            assertEquals(
                "$dir/strings_parity_chat.xml: notification_reaction takes NO arguments",
                value,
                OSHIControlPayload.noFormatSpecifiers(value, "SHOULD_NOT_FALL_BACK")
            )
        }
        assertTrue("expected notification_reaction in 30+ locales, found $seen", seen >= 30)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Battery-drain hand-over — two writes per poll tick on an EMPTY pull
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty relay pull persists neither ledger`() {
        val poll = body(v2Router, "suspend fun poll() = pollMutex.withLock {")
        assertTrue(
            "saveSeenMsgIds/savePendingFiles must be guarded on the pull having " +
                "returned something — only the delivery loop can change either ledger, " +
                "and unguarded this ran two JSON serialisations and two apply() per tick",
            Regex(
                """if \(pull\.messages\.isNotEmpty\(\)\) \{\s*saveSeenMsgIds\(\)\s*savePendingFiles\(\)\s*\}"""
            ).containsMatchIn(poll)
        )
        // The ack still runs unconditionally: a pull that delivered nothing can still
        // need to advance the cursor.
        assertTrue(poll.contains("val ackSeq = if (lowestFailedSeq == Long.MAX_VALUE)"))
        val guardAt = poll.indexOf("if (pull.messages.isNotEmpty()) {")
        val ackAt = poll.indexOf("val ackSeq =")
        assertTrue("the ack must stay outside the guard", guardAt in 0 until ackAt)
    }
}
