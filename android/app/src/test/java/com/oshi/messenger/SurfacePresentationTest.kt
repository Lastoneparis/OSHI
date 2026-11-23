package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.OSHIControlPayload.Kind
import com.oshi.messenger.service.NotificationPresentation
import com.oshi.messenger.service.NotificationPresentation.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE surface × payload table, as executable assertions.
 *
 * The question every user-facing surface has to answer is the same one: *if this
 * message is a location payload, a reaction, a receipt, a call summary, a group
 * update, a reveal envelope or a `v2file` key message, what does the human see?*
 * This file answers it once, for the two functions every such surface now goes
 * through — `OSHIControlPayload.listPreview` (list rows) and
 * `NotificationPresentation.decide` (banners).
 *
 * ## What these assertions deliberately are NOT
 *
 * "the preview contains no `{`" is a weak test: it passes for a preview that
 * prints the sentinel, for one that prints an empty string where a real message
 * belonged, and for one that prints a hardcoded English label. So every case
 * below asserts the **exact rendered string**, against labels whose values are
 * distinguishable sentinels (see [labels]) so that a branch which quietly stops
 * consulting the localized label — the 34-language failure this pass exists to
 * close — fails here rather than shipping.
 */
class SurfacePresentationTest {

    /**
     * Labels whose every value is unique and unmistakable. If a summary branch
     * hardcodes English instead of reading its label, the assertion prints the
     * English and the diff names the branch.
     */
    private val labels = OSHIControlPayload.Labels(
        callOutgoing = "L_OUT",
        callIncoming = "L_IN",
        callMissed = "L_MISSED",
        callNoAnswer = "L_NOANSWER",
        callDeclined = "L_DECLINED",
        callDefault = "L_CALL",
        locationLive = "L_LIVE",
        locationShared = "L_LOC",
        scheduledCall = "L_SCHED",
        checkIn = "L_CHECKIN",
        profileUpdated = "L_PROFILE",
        wallpaperChanged = "L_WALLPAPER",
        reaction = "L_REACTION",
        keyRotation = "L_KEYROT",
        mediaImage = "L_PHOTO",
        mediaVideo = "L_VIDEO",
        mediaAudio = "L_AUDIO",
        mediaDocument = "L_DOC",
        mediaFile = "L_FILE",
        connecting = "L_CONNECTING",
        encryptedMessage = "L_ENCRYPTED",
        opaqueMessage = "L_OPAQUE"
    )

    private val attachment = "L_ATTACHMENT"

    private fun row(raw: String) = OSHIControlPayload.listPreview(raw, labels)

    private fun banner(raw: String) =
        NotificationPresentation.decide(raw, labels, attachment)

    // =====================================================================
    // The payload types named in the brief, one row of the table each.
    // =====================================================================

    @Test
    fun locationPayload_rowAndBannerShowTheAddress_neverTheJson() {
        val raw = "📍LOCATION📍{\"latitude\":48.85,\"longitude\":2.35," +
            "\"address\":\"12 Rue de Rivoli, Paris, France\"}"

        assertEquals("📍 12 Rue de Rivoli", row(raw))
        assertEquals(Decision.Show("📍 12 Rue de Rivoli"), banner(raw))
    }

    @Test
    fun liveLocationPayload_saysItIsLive() {
        val raw = "📍LOCATION📍{\"latitude\":48.85,\"longitude\":2.35,\"isLive\":true}"

        assertEquals("📍 L_LIVE", row(raw))
        assertEquals(Decision.Show("📍 L_LIVE"), banner(raw))
    }

    @Test
    fun locationPayloadWithNoAddress_fallsBackToTheLabel() {
        val raw = "📍LOCATION📍{\"latitude\":48.85,\"longitude\":2.35}"

        assertEquals("📍 L_LOC", row(raw))
    }

    @Test
    fun reaction_rowShowsTheLabel_bannerShowsTheLabel() {
        // 🔥REACTION🔥 is RENDERED, not SILENT: iOS puts a reaction in the list.
        val raw = "🔥REACTION🔥{\"emoji\":\"❤️\",\"action\":\"add\",\"messageId\":\"m1\"}"

        assertEquals("🔥 L_REACTION", row(raw))
        assertEquals(Decision.Show("🔥 L_REACTION"), banner(raw))
    }

    @Test
    fun legacyReactionSpelling_rendersIdentically() {
        assertEquals("🔥 L_REACTION", row("👍 REACTION👍{\"emoji\":\"👍\"}"))
    }

    @Test
    fun deliveryReceipt_rowIsEmpty_andNoBannerIsPostedAtAll() {
        val raw = "📬DELIVERY_RECEIPT📬abc-123"

        assertEquals("", row(raw))
        // THE regression. This used to be `Show("New message")`, because
        // `MessageSanitizer.sanitizeForDisplay(raw).ifEmpty { "New message" }`
        // inverted the sanitiser's one correct answer.
        assertEquals(Decision.Suppress, banner(raw))
    }

    @Test
    fun readReceipt_postsNoBanner() {
        assertEquals(Decision.Suppress, banner("📖READ_RECEIPT📖abc-123"))
    }

    @Test
    fun typingPing_bothSpellings_postNoBanner() {
        assertEquals(Decision.Suppress, banner("⌨️TYPING⌨️{\"isTyping\":true}"))
        assertEquals(Decision.Suppress, banner("✍️TYPING✍️{\"isTyping\":true}"))
    }

    @Test
    fun answeredCallSummary_showsDirectionAndDuration_localized() {
        val raw = OSHIControlPayload.answeredCallSummary(wasOutgoing = true, duration = "1:23")

        assertEquals("📞↗️ L_OUT • 1:23", row(raw))
        assertEquals(Decision.Show("📞↗️ L_OUT • 1:23"), banner(raw))
    }

    @Test
    fun callSummaryAsJson_fromAnotherClient_alsoRenders() {
        val raw = "📱CALL_SUMMARY📱{\"direction\":\"incoming\",\"duration\":83}"

        assertEquals("📞↙️ L_IN • 1:23", row(raw))
    }

    @Test
    fun missedCall_showsTheMissedLabel() {
        val raw = "📱MISSED_CALL📱❌call.missed"

        assertEquals("❌ L_MISSED", row(raw))
        assertEquals(Decision.Show("❌ L_MISSED"), banner(raw))
    }

    @Test
    fun groupUpdate_isSilent_soTheGroupRosterNeverRingsAnyone() {
        val raw = "📢GROUP_UPDATE📢{\"id\":\"g1\",\"name\":\"Trip\",\"type\":\"collaborative\"," +
            "\"adminPublicKey\":\"AAA\",\"members\":[]}"

        assertEquals("", row(raw))
        // MessageSanitizer had no 📢GROUP_UPDATE📢 branch and, because the string
        // opens with an emoji rather than `{`, none of its JSON catch-alls fired
        // either — it returned the whole roster verbatim onto the lock screen.
        assertEquals(Decision.Suppress, banner(raw))
    }

    @Test
    fun bareGroupSyncJson_carriesNoSentinel_andIsStillSilent() {
        val raw = "{\"type\":\"sync_request\",\"groupId\":\"g1\"}"

        assertEquals("", row(raw))
        assertEquals(Decision.Suppress, banner(raw))
    }

    @Test
    fun replyEnvelope_showsTheBodyTheUserTyped_neverTheEnvelope() {
        val raw = "💬REPLY💬{\"content\":\"see you at 8\",\"replyTo\":" +
            "{\"messageId\":\"m1\",\"content\":\"when?\",\"senderName\":\"Ana\"}}"

        // MessageSanitizer has only the dead Android-legacy ↩️REPLY↩️ branch, so
        // this exact string reached the banner VERBATIM.
        assertEquals("see you at 8", row(raw))
        assertEquals(Decision.Show("see you at 8"), banner(raw))
    }

    @Test
    fun replyToACallSummary_quotesTheCall_notAJsonOfAJson() {
        val inner = OSHIControlPayload.answeredCallSummary(wasOutgoing = false, duration = "0:42")
        val raw = "💬REPLY💬{\"content\":${quote(inner)},\"replyTo\":{\"messageId\":\"m1\"}}"

        assertEquals("📞↙️ L_IN • 0:42", row(raw))
    }

    @Test
    fun forwardEnvelope_showsTheForwardedBody() {
        val raw = "➡️FORWARDED➡️{\"originalSenderName\":\"Ana\",\"content\":\"the address is 12 Rivoli\"," +
            "\"isForwarded\":true,\"forwardCount\":1}"

        assertEquals("the address is 12 Rivoli", row(raw))
        assertEquals(Decision.Show("the address is 12 Rivoli"), banner(raw))
    }

    @Test
    fun envelopeWithUnreadableBody_showsNothingRatherThanBrokenJson_andStillBanners() {
        val raw = "💬REPLY💬{not json at all"

        // Row: nothing. Never the broken JSON.
        assertEquals("", row(raw))
        // Banner: a real message DID arrive and we cannot read it — falling back
        // beats dropping it, which is why `decide` does not treat "" as Suppress
        // on its own.
        assertEquals(Decision.Show(attachment), banner(raw))
    }

    @Test
    fun v2FileKeyMessage_showsItsMediaType_neverTheKeyMaterial() {
        // `V2MessageRouter.kt:506-518`: if `V2FileKeyMessage.parse` returns null —
        // one required field moved, one truncated manifest — the router falls
        // through to "treat the plaintext as UTF-8 text" and this whole object,
        // fileKey included, becomes `Message.content`.
        val raw = "{\"kind\":\"v2file\",\"blobId\":\"b1\",\"fileKey\":\"AAAAAAAAAAAAAAAAAAAAAA==\"," +
            "\"fileNonce\":\"AAAAAAAAAAA=\",\"chunkCount\":3,\"manifest\":\"AAAA\"," +
            "\"filename\":\"holiday.jpg\",\"mime\":\"image/jpeg\",\"mediaType\":\"image\"}"

        assertEquals("📷 L_PHOTO", row(raw))
        assertEquals(Decision.Show("📷 L_PHOTO"), banner(raw))
        assertFalse("key material must not reach the surface", row(raw).contains("fileKey"))
    }

    @Test
    fun progressiveRevealEnvelope_isTheSameV2FilePayload_plusFourScalars() {
        val raw = "{\"kind\":\"v2file\",\"blobId\":\"b1\",\"fileKey\":\"AAAA\",\"fileNonce\":\"AA\"," +
            "\"chunkCount\":1,\"manifest\":\"AA\",\"filename\":\"v.mp4\",\"mime\":\"video/mp4\"," +
            "\"mediaType\":\"video\",\"revealId\":\"m1\",\"revealLayerIndex\":2," +
            "\"revealLayerCount\":5,\"revealTotalMs\":604800000}"

        assertEquals("🎥 L_VIDEO", row(raw))
    }

    @Test
    fun v2FileWithNoMediaType_stillNamesItselfAFile() {
        val raw = "{\"kind\":\"v2file\",\"blobId\":\"b1\",\"filename\":\"notes.bin\",\"mime\":\"application/octet-stream\"}"

        assertEquals("📎 L_FILE", row(raw))
    }

    @Test
    fun checkIn_scheduledCall_profile_wallpaper_keyRotation_allUseTheirLabel() {
        assertEquals("🛡️ L_CHECKIN", row("🛡️CHECK_IN🛡️{\"type\":\"arrived\"}"))
        assertEquals("🛡️ L_CHECKIN", row("🛡️CHECKIN🛡️{\"type\":\"arrived\"}"))
        assertEquals("📅 L_SCHED", row("📅SCHEDULED_CALL📅{\"at\":1}"))
        assertEquals("📸 L_PROFILE", row("📸PROFILE_UPDATE📸data"))
        assertEquals("🎨 L_WALLPAPER", row("🎨WALLPAPER_UPDATE🎨data"))
        assertEquals("🔑 L_KEYROT", row("🔑KEY_ROTATION🔑{\"newKey\":\"x\"}"))
    }

    @Test
    fun mediaMarker_andGroupMediaJson_nameTheirType() {
        assertEquals("📷 L_PHOTO", row("MEDIA|||image|||<base64>"))
        assertEquals("🎤 L_AUDIO", row("MEDIA|||audio|||<base64>"))
        assertEquals(
            "📷 L_PHOTO on the beach",
            row("{\"type\":\"media\",\"mediaType\":\"photo\",\"caption\":\"on the beach\"}")
        )
    }

    @Test
    fun ordinaryProse_isNeverRewritten_onEitherSurface() {
        val raw = "we should add a 📍LOCATION📍 marker to the protocol"

        // Merely CONTAINING a sentinel is prose; only a prefix is a payload.
        assertEquals(raw, row(raw))
        assertEquals(Decision.Show(raw), banner(raw))
    }

    @Test
    fun shortUserJson_isStillTheUsersText() {
        // A person typing `{"a":1}` must see `{"a":1}` — the nets are for machine
        // payloads, and they are gated on length and shape for exactly this reason.
        assertEquals("{\"a\":1}", row("{\"a\":1}"))
    }

    // =====================================================================
    // The nets that used to be English literals inside ConversationsScreen
    // =====================================================================

    @Test
    fun ciphertextThatReachedARow_isNamed_notPrinted() {
        val raw = "{\"ciphertext\":\"9f8a7b6c5d4e3f2a1b0c\",\"header\":{\"n\":4,\"pn\":0}}"

        assertEquals("🔒 L_ENCRYPTED", row(raw))
        assertEquals(Decision.Show("🔒 L_ENCRYPTED"), banner(raw))
    }

    @Test
    fun bareEncryptedMarker_isNamed() {
        assertEquals("🔒 L_ENCRYPTED", row("[Encrypted message]"))
    }

    @Test
    fun driftedMediaEnvelope_isNamedByItsType() {
        assertEquals("🎥 L_VIDEO", row("{\"mediaType\":\"video\",\"data\":\"AAAA\",\"w\":1920}"))
    }

    @Test
    fun anyOtherOpaquePayload_isNamed_notPrinted() {
        val raw = "{\"schemaVersion\":9,\"payloadWeHaveNeverSeen\":\"aGVsbG8gd29ybGQ=\",\"n\":7}"

        assertEquals("💬 L_OPAQUE", row(raw))
        assertEquals(Decision.Show("💬 L_OPAQUE"), banner(raw))
    }

    @Test
    fun bracketPlaceholders_becomeMediaLabels() {
        assertEquals("📷 L_PHOTO", row("[IMAGE]"))
        assertEquals("🎥 L_VIDEO", row("[VIDEO]"))
        assertEquals("🎤 L_AUDIO", row("[AUDIO]"))
        assertEquals("📄 L_DOC", row("[DOCUMENT]"))
    }

    // =====================================================================
    // Catalog-driven: a marker added tomorrow is covered by construction
    // =====================================================================

    @Test
    fun everySilentCatalogEntry_postsNoBanner() {
        val missed = OSHIControlPayload.catalog
            .filter { it.kind == Kind.SILENT }
            .filter { NotificationPresentation.decide(it.prefix + "body", labels, attachment) != Decision.Suppress }
            .map { it.prefix }

        assertEquals("silent payloads that would still ring the phone", emptyList<String>(), missed)
    }

    @Test
    fun noSurfaceEverEmitsASentinelOrAnOpeningBrace_forAnyCatalogEntry() {
        // Secondary net only — the exact-text assertions above are the real test.
        // This one catches a NEW catalog entry whose summary branch was forgotten.
        val leaks = mutableListOf<String>()
        for (entry in OSHIControlPayload.catalog) {
            val raw = entry.prefix + "{\"content\":\"x\",\"secret\":\"s3cr3t\"}"
            val rendered = row(raw)
            if (rendered.contains(entry.prefix) || rendered.contains("s3cr3t")) {
                leaks += "${entry.prefix} -> $rendered"
            }
            when (val d = banner(raw)) {
                is Decision.Show ->
                    if (d.body.contains(entry.prefix) || d.body.contains("s3cr3t")) {
                        leaks += "banner ${entry.prefix} -> ${d.body}"
                    }
                is Decision.Suppress -> Unit
            }
        }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test
    fun everyLabelIsReachable_soNoBranchCanQuietlyHardcodeEnglish() {
        // Each label below is produced by at least one payload shape. A branch that
        // stops consulting its label (the 34-language bug) drops out of this set.
        val produced = setOf(
            row("📍LOCATION📍{\"isLive\":true}"),
            row("📍LOCATION📍{}"),
            row("📅SCHEDULED_CALL📅x"),
            row("🛡️CHECK_IN🛡️x"),
            row("📸PROFILE_UPDATE📸x"),
            row("🎨WALLPAPER_UPDATE🎨x"),
            row("🔥REACTION🔥x"),
            row("🔑KEY_ROTATION🔑x"),
            row("MEDIA|||image|||x"),
            row("MEDIA|||video|||x"),
            row("MEDIA|||audio|||x"),
            row("MEDIA|||document|||x"),
            row("MEDIA|||weird|||x"),
            row("📱CALL_SUMMARY📱📞↗️call.outgoing|1:00"),
            row("📱CALL_SUMMARY📱📞↙️call.incoming|1:00"),
            row("📱MISSED_CALL📱❌call.missed"),
            row("📱MISSED_CALL📱❌call.no.answer"),
            row("📱MISSED_CALL📱❌call.declined"),
            row("📱CALL_SUMMARY📱📞call.contact.default"),
            row("[Encrypted]"),
            row("{\"schemaVersion\":9,\"unknownThing\":\"aGVsbG8gd29ybGQ=\",\"n\":7}")
        ).joinToString(" ")

        val unreached = listOf(
            "L_LIVE", "L_LOC", "L_SCHED", "L_CHECKIN", "L_PROFILE", "L_WALLPAPER",
            "L_REACTION", "L_KEYROT", "L_PHOTO", "L_VIDEO", "L_AUDIO", "L_DOC",
            "L_FILE", "L_OUT", "L_IN", "L_MISSED", "L_NOANSWER", "L_DECLINED",
            "L_CALL", "L_ENCRYPTED", "L_OPAQUE"
        ).filterNot { produced.contains(it) }

        assertEquals("labels no payload shape can reach", emptyList<String>(), unreached)
    }

    // =====================================================================
    // NotificationPresentation's own contract
    // =====================================================================

    @Test
    fun blankBody_showsTheLocalizedAttachmentFallback_notAnEnglishLiteral() {
        assertEquals(Decision.Show(attachment), banner(""))
        assertEquals(Decision.Show(attachment), banner("   "))
    }

    @Test
    fun cappedBannerElidesAtFiftyCharacters() {
        val long = "x".repeat(120)
        val decision = NotificationPresentation.decideCapped(long, labels, attachment)

        assertEquals(Decision.Show("x".repeat(50) + "…"), decision)
    }

    @Test
    fun cappedBannerLeavesAShortBodyAlone() {
        assertEquals(
            Decision.Show("hello"),
            NotificationPresentation.decideCapped("hello", labels, attachment)
        )
    }

    @Test
    fun cappingNeverResurrectsASuppressedPayload() {
        assertEquals(
            Decision.Suppress,
            NotificationPresentation.decideCapped("📖READ_RECEIPT📖" + "x".repeat(200), labels, attachment)
        )
    }

    @Test
    fun defaultLabelsAreAllNonEmpty_soAContextFreeCallerNeverShowsABlank() {
        val d = OSHIControlPayload.Labels()
        val fields = listOf(
            d.callOutgoing, d.callIncoming, d.callMissed, d.callNoAnswer, d.callDeclined,
            d.callDefault, d.locationLive, d.locationShared, d.scheduledCall, d.checkIn,
            d.profileUpdated, d.wallpaperChanged, d.reaction, d.keyRotation, d.mediaImage,
            d.mediaVideo, d.mediaAudio, d.mediaDocument, d.mediaFile, d.connecting,
            d.encryptedMessage, d.opaqueMessage
        )
        assertTrue(fields.all { it.isNotBlank() })
    }

    // =====================================================================
    // A mistranslated label must not print its format specifier at the user
    // =====================================================================

    @Test
    fun theActualHebrewReactionString_isRejectedInFavourOfTheDefault() {
        // What `values-iw/strings_parity_chat.xml:7` USED to hold. Two `%@`
        // substitutions in a label that takes no arguments — lifted from a
        // different, parameterised iOS key. `getString` with no varargs prints it
        // as-is, so wiring `notification_reaction` (nothing consumed it before this
        // pass) would have put `🔥 %@ הגיב/ה %@ להודעתך` on every Hebrew reaction row.
        //
        // The resource itself was fixed in round 5 (hand-off H-8), so this is now a
        // regression test for the GUARD, not for the resource: `SurfaceRound5FixesTest`
        // reads the live XML and asserts the real Hebrew now passes through. The
        // guard stays because it is what makes wiring a translated key safe at all.
        val hebrew = "%@ הגיב/ה %@ להודעתך"

        assertEquals(
            "Reacted to a message",
            OSHIControlPayload.noFormatSpecifiers(hebrew, "Reacted to a message")
        )
    }

    @Test
    fun theRealTranslationsAreAllKept() {
        // values-fr / -de / -es / -ja / -ru, verbatim. None carries a specifier,
        // so the guard must be invisible to them — a guard that also swallowed
        // good translations would silently re-English 33 locales.
        for (good in listOf(
            "A réagi à un message",
            "Hat auf eine Nachricht reagiert",
            "Reaccionó a un mensaje",
            "メッセージにリアクションしました",
            "Отреагировал на сообщение"
        )) {
            assertEquals(good, OSHIControlPayload.noFormatSpecifiers(good, "FALLBACK"))
        }
    }

    @Test
    fun everyFormatSpecifierSpellingIsCaught() {
        for (bad in listOf("%@ reacted", "%s reacted", "%1\$s reacted %2\$s", "%d new")) {
            assertEquals(
                "should have been rejected: $bad",
                "FALLBACK",
                OSHIControlPayload.noFormatSpecifiers(bad, "FALLBACK")
            )
        }
    }

    @Test
    fun aBarePercentSignIsNotAFormatSpecifier() {
        // "100% agree" is ordinary copy and must survive.
        assertEquals("100% agree", OSHIControlPayload.noFormatSpecifiers("100% agree", "FALLBACK"))
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
