package com.oshi.messenger.service

import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.MediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the welcome conversation actually WRITES.
 *
 * A greeting inserted as a fake inbound row can look perfect on screen and be
 * wrong in the database: the wrong sender key, the wrong direction, unread for
 * ever, or re-inserted on every launch. Each group below pins one of those, plus
 * the byte-level agreement with iOS's `WelcomeMessageManager.swift` that makes
 * the two platforms say the same thing about the same identity.
 *
 * Everything under test is pure — no `Context`, no Room, no `android.*` — which
 * is the only reason these decisions live in [WelcomeMessage] at all. The JVM
 * test classpath is junit + org.json and nothing else
 * (`app/build.gradle.kts:293-296`).
 */
class WelcomeMessageTest {

    private val myKey = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/AbCd="
    private val now = 1_770_000_000_000L
    private val body = "Welcome to OSHI."
    private val caption = "Tap to start a conversation with me"

    // =====================================================================
    // 1. Identity constants — copied from iOS, not re-derived
    // =====================================================================

    /** `WelcomeMessageManager.swift:30-31`. A different key greets a stranger. */
    @Test
    fun `author identity matches the shipping iOS constants`() {
        assertEquals("eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A=", WelcomeMessage.AUTHOR_PUBLIC_KEY)
        assertEquals("Hugo", WelcomeMessage.AUTHOR_ALIAS)
    }

    /**
     * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ REVERSED, deliberately.
     *
     * This test used to assert the opposite — that the system sender must NOT
     * be the author's key — on the reasoning that attributing an app-written
     * greeting to a person who never typed it is a small lie. That reasoning
     * was ours and iOS's both, and iOS abandoned it on 2026-10-16 after seeing
     * what it cost: the greeting is a new user's ONLY conversation, and under
     * the old rule it could not be answered. Android kept the old rule and
     * showed "add a contact to start a conversation" where a reply belongs.
     *
     * The honesty concern is now answered where it should be — in words, above
     * the composer (`chat_welcome_creator_notice`) — rather than by making the
     * sender unreachable.
     */
    fun `system sender IS the author key, so the greeting can be answered`() {
        assertEquals(WelcomeMessage.AUTHOR_PUBLIC_KEY, WelcomeMessage.SYSTEM_SENDER_KEY)
        assertEquals(
            "the legacy label must be kept so existing threads can be migrated",
            "oshi.welcome",
            WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY,
        )
    }

    /**
     * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ ALSO REVERSED, and this is
     * the safety-critical half.
     *
     * The old rule wanted a sender that does NOT decode as base64, so no
     * normalization pass could rewrite it. The new rule needs the opposite and
     * needs it strictly: a sender that decodes to exactly 32 bytes, because
     * `deriveSharedSecret` decodes it to build the key. `oshi.welcome` decoded
     * to 8 bytes and THREW — which is why every reply read "Not delivered" and
     * why Play rejected the app on 2026-09-16 for broken functionality.
     *
     * So this is the test that stands between us and repeating a store
     * rejection. The LEGACY label keeps its old property, asserted below.
     */
    @Test
    fun `system sender decodes to a real 32 byte key`() {
        val decoded = runCatching {
            java.util.Base64.getDecoder().decode(WelcomeMessage.SYSTEM_SENDER_KEY)
        }
        assertTrue("the welcome sender must be decodable", decoded.isSuccess)
        assertEquals(
            "a sender that is not 32 bytes throws in deriveSharedSecret",
            32,
            decoded.getOrThrow().size,
        )
    }

    /** The legacy label keeps the property it was chosen for. */
    @Test
    fun `the legacy label is still not base64`() {
        val decoded = runCatching {
            java.util.Base64.getDecoder().decode(WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY)
        }
        assertTrue("oshi.welcome must not decode as base64", decoded.isFailure)
    }

    // =====================================================================
    // 2. The four database mistakes
    // =====================================================================

    /** Mistake 1 — wrong sender. Both rows come FROM the pseudo-key, TO the user. */
    @Test
    fun `both rows are addressed from the system sender to the new user`() {
        for (m in listOf(
            WelcomeMessage.greetingRow(myKey, body, now),
            WelcomeMessage.contactCardRow(myKey, caption, now),
        )) {
            assertEquals(WelcomeMessage.SYSTEM_SENDER_KEY, m.senderPublicKey)
            assertEquals(myKey, m.recipientPublicKey)
        }
    }

    /**
     * Mistake 2 — wrong direction. iOS has no stored flag and derives direction
     * from the sender (`ChatView.swift:3717`); Android stores it, so it must be
     * set. `isFromMe = true` would draw the greeting as something the user wrote
     * to themselves, right-aligned, and would also exclude it from the unread
     * query (`AppDatabase.kt:484-485` filters `isFromMe = 0`).
     */
    @Test
    fun `both rows are inbound`() {
        assertFalse(WelcomeMessage.greetingRow(myKey, body, now).isFromMe)
        assertFalse(WelcomeMessage.contactCardRow(myKey, caption, now).isFromMe)
    }

    /**
     * Mistake 3 — unread for ever. `isRead = false` is deliberate
     * (`WelcomeMessageManager.swift:112`): the badge of 2 is what draws the new
     * user in. It is not a leak because `MessageDao.markAsRead` matches on
     * `senderPublicKey = :pk AND isFromMe = 0` (`AppDatabase.kt:499-500`), and
     * these rows satisfy both — so opening the chat clears it.
     */
    @Test
    fun `rows are unread but clearable by the markAsRead predicate`() {
        val rows = listOf(
            WelcomeMessage.greetingRow(myKey, body, now),
            WelcomeMessage.contactCardRow(myKey, caption, now),
        )
        assertTrue(rows.all { !it.isRead })
        // The exact predicate of `UPDATE messages SET isRead = 1
        // WHERE senderPublicKey = :publicKey AND isFromMe = 0`.
        assertTrue(rows.all { it.senderPublicKey == WelcomeMessage.SYSTEM_SENDER_KEY && !it.isFromMe })
        // And the unread query that feeds the badge.
        assertTrue(rows.all { !it.isRead && !it.isFromMe && !it.isDeleted })
    }

    /**
     * Mistake 4 — re-inserted on every launch. The ids are DETERMINISTIC, so a
     * second insert REPLACEs the same primary keys
     * (`@Insert(onConflict = REPLACE)`, `AppDatabase.kt:490-491`) instead of
     * stacking a second copy. That is the belt under the seeded-flag braces.
     */
    @Test
    fun `ids are deterministic per identity, so a re-insert replaces`() {
        assertEquals(
            WelcomeMessage.greetingRow(myKey, body, now).id,
            WelcomeMessage.greetingRow(myKey, "different text", now + 5_000).id,
        )
        assertEquals("oshi-welcome-text-" + myKey.take(8), WelcomeMessage.textMessageId(myKey))
        assertEquals("oshi-welcome-card-" + myKey.take(8), WelcomeMessage.cardMessageId(myKey))
        // The two rows must not collide with each other.
        assertNotEquals(WelcomeMessage.textMessageId(myKey), WelcomeMessage.cardMessageId(myKey))
    }

    // =====================================================================
    // 3. Transport honesty
    // =====================================================================

    /**
     * Nothing travelled, so nothing may claim a transport. iOS writes
     * `deliveryMethod: nil` (`WelcomeMessageManager.swift:117`) and draws no
     * badge. Android's column is non-null with a DEFAULT of `VPS`, so leaving it
     * alone would put a blue "Via Internet" pill on a message that never touched
     * a network — the default is the bug, which is why this is asserted rather
     * than assumed.
     */
    @Test
    fun `neither row claims a network transport`() {
        assertEquals(DeliveryMethod.LOCAL, WelcomeMessage.greetingRow(myKey, body, now).deliveryMethod)
        assertEquals(DeliveryMethod.LOCAL, WelcomeMessage.contactCardRow(myKey, caption, now).deliveryMethod)
        assertNotEquals(DeliveryMethod.VPS, WelcomeMessage.greetingRow(myKey, body, now).deliveryMethod)
    }

    /** No ciphertext, because nothing was encrypted. `WelcomeMessageManager.swift:104-106`. */
    @Test
    fun `rows carry no ciphertext and are already delivered`() {
        val m = WelcomeMessage.greetingRow(myKey, body, now)
        assertNull(m.encryptedContent)
        assertTrue(m.isDelivered)     // iOS `.delivered`, :113
        assertFalse(m.isFailed)
    }

    // =====================================================================
    // 4. Ordering and content
    // =====================================================================

    /** `WelcomeMessageManager.swift:73-85` — the card is last, the thing to act on. */
    @Test
    fun `the card sorts one second after the greeting`() {
        assertEquals(1_000L, WelcomeMessage.CARD_OFFSET_MS)
        val text = WelcomeMessage.greetingRow(myKey, body, now)
        val card = WelcomeMessage.contactCardRow(myKey, caption, now + WelcomeMessage.CARD_OFFSET_MS)
        assertTrue(card.timestamp > text.timestamp)
    }

    /**
     * The greeting carries the localized body verbatim. Any wrapping, prefixing
     * or truncation here would show up as a mangled first impression in 34
     * languages at once.
     */
    @Test
    fun `the greeting body is passed through unchanged`() {
        val long = "Welcome to OSHI.\n\nSecond paragraph — with an em dash.\n\nThird?"
        assertEquals(long, WelcomeMessage.greetingRow(myKey, long, now).content)
    }

    /**
     * The card's `content` must NOT use the `"[CONTACT] name"` convention
     * `ChatViewModel.sendContactCard` writes. The bubble ignores `content` once
     * the payload parses (`ChatScreen.kt:3146-3163`), but the conversation-list
     * preview does not: `OSHIControlPayload.listPreview` only rewrites the exact
     * `[IMAGE]/[VIDEO]/[AUDIO]/[DOCUMENT]` tokens and returns anything else
     * verbatim, so the bracket form would be shown to the user literally.
     */
    @Test
    fun `the card caption is human readable, not a bracket token`() {
        val m = WelcomeMessage.contactCardRow(myKey, caption, now)
        assertEquals(caption, m.content)
        assertFalse(m.content.startsWith("["))
    }

    // =====================================================================
    // 5. The contact card payload
    // =====================================================================

    /**
     * `ChatScreen.kt:3118-3125` decodes `mediaData` with
     * `ContactPayload.serializer()`. Wrong shape → the bubble falls through to
     * rendering the raw caption, and the one tap that starts the real
     * conversation is gone.
     */
    @Test
    fun `the card payload is ContactPayload JSON carrying the author key`() {
        val m = WelcomeMessage.contactCardRow(myKey, caption, now)
        assertEquals(MediaType.CONTACT, m.mediaType)
        val json = JSONObject(String(m.mediaData!!, Charsets.UTF_8))
        assertEquals(WelcomeMessage.AUTHOR_PUBLIC_KEY, json.getString("publicKey"))
        assertEquals(WelcomeMessage.AUTHOR_ALIAS, json.getString("alias"))
    }

    /** The greeting is a plain text row — no media type, no blob. */
    @Test
    fun `the greeting carries no media`() {
        val m = WelcomeMessage.greetingRow(myKey, body, now)
        assertNull(m.mediaType)
        assertNull(m.mediaData)
    }

    // =====================================================================
    // 6. The contacts row Android needs and iOS does not
    // =====================================================================

    /**
     * `MessageRepository.getConversations()` starts at
     * `contactDao.getAllContacts()` and `mapNotNull`s — a message whose sender
     * has no contacts row produces NO conversation. Without this the greeting is
     * written, correct, and invisible. iOS has no equivalent because its list is
     * derived from the messages (`MessageManager.swift:8382-8387`).
     */
    @Test
    fun `a contacts row exists under the same key the messages are sent from`() {
        val c = WelcomeMessage.contactRow(null)
        assertEquals(WelcomeMessage.SYSTEM_SENDER_KEY, c.publicKey)
        assertEquals(WelcomeMessage.greetingRow(myKey, body, now).senderPublicKey, c.publicKey)
        assertEquals(WelcomeMessage.AUTHOR_ALIAS, c.alias)
    }

    /**
     * `lastSeen` drives the green online dot
     * (`MessageRepository.kt`, `now - lastSeen < 5 min`). A pseudo-key that is
     * never online must not claim to be, and it must not arrive blocked.
     */
    @Test
    fun `the pseudo contact is never online and never blocked`() {
        val c = WelcomeMessage.contactRow(null)
        assertNull(c.lastSeen)
        assertFalse(c.isBlocked)
        assertFalse(c.isVerified)
    }

    // =====================================================================
    // 7. The seed-once guard
    // =====================================================================

    /** `WelcomeMessageManager.swift:42-44` — per identity, not a global flag. */
    @Test
    fun `the seeded flag is keyed per identity`() {
        assertEquals("oshi.welcome.seeded." + myKey.take(24), WelcomeMessage.seededPrefKey(myKey))
        assertNotEquals(
            WelcomeMessage.seededPrefKey(myKey),
            WelcomeMessage.seededPrefKey("ZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZz="),
        )
    }

    /** `WelcomeMessageManager.swift:56-58`, all three guards. */
    @Test
    fun `shouldSeed refuses an empty key, a restore, and a second run`() {
        assertTrue(WelcomeMessage.shouldSeed(myKey, isRestoredIdentity = false, alreadySeeded = false))
        assertFalse(WelcomeMessage.shouldSeed("", isRestoredIdentity = false, alreadySeeded = false))
        assertFalse(WelcomeMessage.shouldSeed(myKey, isRestoredIdentity = true, alreadySeeded = false))
        assertFalse(WelcomeMessage.shouldSeed(myKey, isRestoredIdentity = false, alreadySeeded = true))
    }

    /**
     * A restore is the case that would otherwise duplicate a greeting the user
     * already read on their old device — and there is nothing on a fresh install
     * to tell us they did, because the flag lives in SharedPreferences, not in
     * the identity. So the flag alone is NOT enough: the restore guard is
     * load-bearing on its own.
     */
    @Test
    fun `a restored identity is refused even with no flag set`() {
        assertFalse(WelcomeMessage.shouldSeed(myKey, isRestoredIdentity = true, alreadySeeded = false))
    }
}
