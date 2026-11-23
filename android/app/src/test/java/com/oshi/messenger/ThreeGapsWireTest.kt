package com.oshi.messenger

import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.CheckInMessage
import com.oshi.messenger.data.model.CheckInMessageType
import com.oshi.messenger.data.model.ContactCardWire
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.MessageTone
import com.oshi.messenger.service.NLPManager
import com.oshi.messenger.ui.components.OshiContactPickerFilter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three features closed on 2026-08-24, pinned at the seams that actually
 * broke them.
 *
 * Rules this file follows, because breaking either makes the whole file vacuous:
 *
 * 1. **The expected wire is written by hand, never produced by the code under
 *    test.** Every literal below is what an iPhone's `JSONEncoder` emits, read off
 *    `OSHI/MediaManager.swift:867-897` and `:901-917`. A test that built its input
 *    with `ContactCardWire.encode` and then decoded it with `ContactCardWire.decode`
 *    would pass just as happily against an ISO-8601 date that no iPhone accepts.
 * 2. **The output is inspected with a DIFFERENT parser** (`org.json`) than the one
 *    that produced it (`kotlinx.serialization`), so a shape only kotlinx agrees
 *    with cannot slip through.
 */
class ThreeGapsWireTest {

    // =====================================================================
    // 1. Contact sharing — the body an iPhone's decoder accepts
    // =====================================================================

    /**
     * iOS's `ContactCard.timestamp` is a **non-optional `Date`** decoded by a bare
     * `JSONDecoder`, i.e. `.deferredToDate` = a JSON NUMBER of seconds since
     * 2001-01-01. Not a string, not milliseconds, not optional.
     *
     * Hand-computed: 2026-01-01T00:00:00Z is 1767225600 s since 1970, and Apple's
     * reference date is 978307200 s since 1970, so the wire value is
     * 1767225600 - 978307200 = 788918400.
     */
    @Test
    fun `a shared contact carries an apple-epoch timestamp as a json number`() {
        val json = ContactCardWire.encode(
            publicKey = "BASE64PUBKEY",
            alias = "Alice",
            timestampMillis = 1_767_225_600_000L
        )

        val obj = JSONObject(json)
        assertEquals("BASE64PUBKEY", obj.getString("publicKey"))
        assertEquals("Alice", obj.getString("alias"))
        assertEquals(788_918_400.0, obj.getDouble("timestamp"), 0.001)

        // A STRING here is the exact failure the brief warns about: a bare
        // `decode(Date.self)` throws `typeMismatch` on it and the whole card sinks.
        assertFalse(
            "timestamp must not be quoted — iOS decodes it as a number",
            json.contains("\"timestamp\":\"")
        )
        // …and iOS has never heard of avatarUrl, so it must not travel.
        assertFalse(json.contains("avatarUrl"))
    }

    /**
     * The field set is exactly iOS's three keys — no more, no fewer. An absent
     * `timestamp` is `keyNotFound` on the iPhone and a blank
     * `person.crop.circle` placeholder for the user.
     */
    @Test
    fun `the contact card emits exactly the keys iOS declares`() {
        val obj = JSONObject(ContactCardWire.encode("K", "Bob", 1_700_000_000_000L))
        assertEquals(setOf("publicKey", "alias", "timestamp"), obj.keys().asSequence().toSet())
    }

    /**
     * `alias` is `String?` on iOS and its synthesized decoder uses
     * `decodeIfPresent`, so absent == null. Omitting it is correct; emitting
     * `"alias":null` would also decode, but a blank alias must never reach the
     * iPhone as an empty display name.
     */
    @Test
    fun `a blank alias is omitted rather than sent empty`() {
        val obj = JSONObject(ContactCardWire.encode("K", "   ", 1_700_000_000_000L))
        assertFalse(obj.has("alias"))
        assertTrue(obj.has("publicKey"))
        assertTrue(obj.has("timestamp"))
    }

    /**
     * The reverse direction. This literal is what an iPhone puts on the wire; the
     * `timestamp` key is precisely what used to throw under `Json.Default`
     * (`ignoreUnknownKeys = false`) and drop the card into a raw-text bubble.
     */
    @Test
    fun `a card written by an iPhone decodes on Android`() {
        val fromIos = """{"publicKey":"iOSKEY","alias":"Claire","timestamp":788918400}"""
        val card = ContactCardWire.decode(fromIos)
        assertNotNull(card)
        assertEquals("iOSKEY", card!!.publicKey)
        assertEquals("Claire", card.alias)
        assertEquals(788_918_400.0, card.timestamp!!, 0.001)
    }

    /** A key a future iOS build adds must cost a FIELD, never the whole card. */
    @Test
    fun `an unknown key does not sink the whole card`() {
        val future = """{"publicKey":"K","alias":"D","timestamp":1.0,"organisation":"OSHI"}"""
        assertEquals("K", ContactCardWire.decode(future)?.publicKey)
    }

    /** The share-sheet shape, still understood — `exportToVCard` emits it. */
    @Test
    fun `a vCard is still understood`() {
        val vcard = buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:Eve Adams")
            appendLine("NOTE:OSHI Public Key: VCARDKEY")
            appendLine("END:VCARD")
        }
        val card = ContactCardWire.decode(vcard)
        assertEquals("VCARDKEY", card?.publicKey)
        assertEquals("Eve Adams", card?.alias)
    }

    /** Neither JSON nor vCard: the caller must fall back to a text bubble. */
    @Test
    fun `unparseable bytes yield no card`() {
        assertNull(ContactCardWire.decode("just some text"))
        assertNull(ContactCardWire.decode("""{"alias":"no key here"}"""))
    }

    /**
     * The `> 1e10` guard. An older Android build wrote raw Unix millis into this
     * field; read as Apple seconds it would be a date ~300 000 years out.
     */
    @Test
    fun `apple seconds round-trip and legacy unix millis survive`() {
        val millis = 1_766_000_000_000L
        assertEquals(millis, ContactCardWire.appleSecondsToMillis(ContactCardWire.millisToAppleSeconds(millis)))
        assertEquals(millis, ContactCardWire.appleSecondsToMillis(millis.toDouble()))
    }

    /**
     * The group envelope. `mapIOSMediaType` used to return null for "contact", so a
     * group contact card lost its type on the way IN; the sender used to emit null,
     * so it lost its type on the way OUT. Both directions, one raw value —
     * `"contact"`, the case name of iOS's group `MediaType`
     * (`OSHI/GroupMessaging.swift:406-412`).
     */
    @Test
    fun `a group contact card keeps its type in both directions`() {
        assertEquals(MediaType.CONTACT, GroupManager.mapIOSMediaType("contact"))
        assertEquals(MediaType.IMAGE, GroupManager.mapIOSMediaType("photo"))
        assertNull(GroupManager.mapIOSMediaType("sticker"))
    }

    /** The conversation row names the shared person instead of printing a bracket. */
    @Test
    fun `a shared contact names the person on the conversation row`() {
        assertEquals("👤 Alice", OSHIControlPayload.listPreview("[CONTACT] Alice"))
        // Bare marker: iOS draws `"👤 " + "media_file".localized` (GroupViews.swift:523).
        assertEquals("👤 File", OSHIControlPayload.listPreview("[CONTACT]"))
        // Ordinary prose is untouched.
        assertEquals("see you at 6", OSHIControlPayload.listPreview("see you at 6"))
    }

    /** Search over the OSHI picker: alias OR key, case-insensitive (iOS ContactSelectorView:34-41). */
    @Test
    fun `the contact picker searches alias and key`() {
        val list = listOf(
            Contact(publicKey = "AAAkeyOne", alias = "Alice"),
            Contact(publicKey = "BBBkeyTwo", alias = "Bob")
        )
        assertEquals(2, OshiContactPickerFilter.filter(list, "   ").size)
        assertEquals(listOf("Alice"), OshiContactPickerFilter.filter(list, "ali").map { it.alias })
        assertEquals(listOf("Bob"), OshiContactPickerFilter.filter(list, "BBBKEY").map { it.alias })
        assertTrue(OshiContactPickerFilter.filter(list, "zzz").isEmpty())
    }

    // =====================================================================
    // 2. Check-In — what a RECEIVED check-in is allowed to do
    // =====================================================================

    /**
     * Six of the seven types must raise a banner; `update` must not.
     *
     * The `update` silence is iOS's decision, not an omission
     * (`OSHI/NotificationManager.swift:300-306`): a live journey pings its position
     * every five minutes and each ping would buzz the phone. This asserts BOTH
     * halves — a future "let's notify on everything" edit fails here, and so does a
     * regression that silences the emergency.
     */
    @Test
    fun `only a location update stays silent when a check-in arrives`() {
        for (type in CheckInMessageType.entries) {
            val msg = CheckInMessage(type = type, sessionId = "s")
            if (type == CheckInMessageType.update) {
                assertNull("a location update must raise no banner", msg.incomingNotificationBody)
            } else {
                assertNotNull("$type must raise a banner", msg.incomingNotificationBody)
            }
        }
    }

    /**
     * The two types that mean "something may be wrong" get the loudest treatment
     * the channel allows — iOS uses `defaultCritical` for `.emergency`
     * (`OSHI/NotificationManager.swift:390-393`), and on Android the `warning` is
     * the recipient's only signal in the fifteen minutes before the sender's phone
     * escalates by itself.
     */
    @Test
    fun `only a warning or an emergency is urgent`() {
        val urgent = CheckInMessageType.entries.filter { it.isUrgentOnArrival }.toSet()
        assertEquals(setOf(CheckInMessageType.warning, CheckInMessageType.emergency), urgent)
    }

    /**
     * Every type has its own NAME. Two types sharing a resource id would put
     * "Emergency Alert" on an arrival, or vice versa, on the conversation row —
     * and the row is the only place a check-in is summarised.
     */
    @Test
    fun `every check-in type names itself distinctly`() {
        val ids = CheckInMessageType.entries.map { it.titleRes }
        assertEquals(CheckInMessageType.entries.size, ids.toSet().size)
        assertTrue("titles must be real resources", ids.all { it != 0 })
    }

    /**
     * A `started` banner names where the person is going — that is the whole point
     * of it. The destination travels as the format argument so the caller (which
     * has the `Context`) does the substitution.
     */
    @Test
    fun `the started banner carries the destination as its format argument`() {
        val msg = CheckInMessage(
            type = CheckInMessageType.started,
            sessionId = "s",
            destinationAddress = "12 rue de Rivoli"
        )
        assertEquals("12 rue de Rivoli", msg.incomingNotificationBody?.second)
        // …and a check-in with no destination still gets a banner, with an empty arg
        // rather than a crash or a null format.
        assertEquals(
            "",
            CheckInMessage(type = CheckInMessageType.started, sessionId = "s")
                .incomingNotificationBody?.second
        )
    }

    /**
     * The bodies that carry no argument must not be format strings — passing a
     * one-arg resource to a no-arg `getString` leaves a literal `%1$s` on screen,
     * which is exactly the Hebrew-reaction trap `OSHIControlPayload.noFormatSpecifiers`
     * exists for.
     */
    @Test
    fun `only the started banner takes a format argument`() {
        for (type in CheckInMessageType.entries - CheckInMessageType.update) {
            val arg = CheckInMessage(type = type, sessionId = "s").incomingNotificationBody?.second
            if (type == CheckInMessageType.started) {
                assertNotNull("$type must supply an argument", arg)
            } else {
                assertNull("$type must not supply an argument", arg)
            }
        }
    }

    // =====================================================================
    // 3. "Je pense à toi" — the tone bridge
    // =====================================================================

    /**
     * Every one of iOS's five `RelationshipTone`s must reach a writing style, and
     * the four distinguishable ones must reach DIFFERENT template branches —
     * otherwise the tone picker is decoration and every tone writes the same
     * sentence (the exact defect the self-normalizing-bar shape describes).
     *
     * The branch names come from `NLPManager.thinkingOfYouTemplate`.
     */
    @Test
    fun `each relationship tone reaches its own template branch`() {
        fun branchOf(style: NLPManager.ImprovementStyle): String = when (style) {
            NLPManager.ImprovementStyle.SEDUCTIVE, NLPManager.ImprovementStyle.FLIRTY -> "romantic"
            NLPManager.ImprovementStyle.PLAYFUL, NLPManager.ImprovementStyle.CASUAL -> "playful"
            NLPManager.ImprovementStyle.FORMAL, NLPManager.ImprovementStyle.PROFESSIONAL -> "formal"
            else -> "warm"
        }

        assertEquals("romantic", branchOf(MessageTone.ROMANTIC.writingStyle()))
        assertEquals("playful", branchOf(MessageTone.CASUAL.writingStyle()))
        assertEquals("formal", branchOf(MessageTone.FORMAL.writingStyle()))
        assertEquals("warm", branchOf(MessageTone.FRIENDLY.writingStyle()))
        assertEquals("warm", branchOf(MessageTone.FAMILY.writingStyle()))

        // Four distinct branches over five tones, and five distinct STYLES, so the
        // prompt handed to the on-device model differs for every tone even where
        // the templated fallback shares a branch.
        assertEquals(4, MessageTone.entries.map { branchOf(it.writingStyle()) }.toSet().size)
        assertEquals(5, MessageTone.entries.map { it.writingStyle() }.toSet().size)
    }
}
