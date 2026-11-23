package com.oshi.messenger.service

import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message

/**
 * The rows a brand-new identity finds waiting for it: a short greeting, then a
 * contact card for the author so the first message can be sent in one tap
 * instead of having to find and scan a code first.
 *
 * PORT OF `OSHI/WelcomeMessageManager.swift` (168 lines). Everything here is
 * LOCAL — no server call, no network, nothing announced about the new user to
 * anyone, which matters for an app whose whole proposition is that signing up
 * tells nobody you exist.
 *
 * WHY A PURE OBJECT
 * -----------------
 * The JVM unit-test source set has junit + org.json and nothing else
 * (`app/build.gradle.kts:293-296`): no Robolectric, and `isReturnDefaultValues`
 * is not set, so any `android.*` call throws "Method … not mocked". The DECISIONS
 * — which sender key, which direction, which read flag, which ids, which
 * transport — are exactly what a wrong welcome message gets wrong, so they live
 * here where a test can actually reach them. [WelcomeMessageSeeder] is the thin
 * Android shell that reads SharedPreferences, resolves the localized strings and
 * writes to Room.
 *
 * THE FOUR WAYS THIS GOES WRONG IN THE DATABASE
 * ---------------------------------------------
 *  1. WRONG SENDER KEY — the greeting is written by the APP, not by a person.
 *     Attributing it to the author's real key would be a small lie the user
 *     could later discover, and it would collide with the real conversation the
 *     contact card opens. Hence [SYSTEM_SENDER_KEY], distinct from
 *     [AUTHOR_PUBLIC_KEY]. `WelcomeMessageManager.swift:33-36`.
 *  2. WRONG DIRECTION — iOS has no stored direction and derives it by comparing
 *     the sender to self (`ChatView.swift:3717`); Android stores `isFromMe`
 *     explicitly, so it must be set to FALSE or the greeting renders as
 *     something the user wrote to themselves.
 *  3. COUNTED AS UNREAD FOR EVER — `isRead = false` is CORRECT and deliberate
 *     (`WelcomeMessageManager.swift:112`): a badge of 2 is what draws the new
 *     user into the thread. It clears the moment the chat is opened, because
 *     `MessageDao.markAsRead` matches on `senderPublicKey` + `isFromMe = 0`
 *     (`data/local/AppDatabase.kt:499-500`) and these rows satisfy both.
 *  4. RE-INSERTED ON EVERY LAUNCH — guarded twice over: the seeded flag is
 *     written BEFORE the insert ([seededPrefKey]), and the row ids are
 *     DETERMINISTIC ([textMessageId] / [cardMessageId]), so even a double call
 *     REPLACEs the same two primary keys instead of stacking duplicates.
 *
 * ONE THING ANDROID NEEDS THAT iOS DOES NOT
 * -----------------------------------------
 * iOS derives its conversation list from the MESSAGES
 * (`MessageManager.swift:8382-8387`, keyed by the other party). Android derives
 * it from the CONTACTS table — `MessageRepository.getConversations()` starts at
 * `contactDao.getAllContacts()` and `mapNotNull`s, so a message whose sender has
 * no `contacts` row produces NO conversation at all and the greeting is written,
 * correct, and invisible. [contactRow] is that row, and it is not optional.
 */
object WelcomeMessage {

    /**
     * The account new users are offered a conversation with.
     *
     * Public keys are public by definition — this is the same string that would
     * be printed in a QR code on a business card, and it carries no secret.
     * `WelcomeMessageManager.swift:30`.
     */
    const val AUTHOR_PUBLIC_KEY = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="

    /** `WelcomeMessageManager.swift:31`. */
    const val AUTHOR_ALIAS = "Hugo"

    /**
     * Sender of the greeting — the author's REAL key.
     *
     * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ This used to be the label
     * `oshi.welcome`, on the reasoning that attributing an app-written greeting
     * to a person who did not type it is a small lie. iOS held that view too and
     * CHANGED ITS MIND on 2026-10-16; Android never followed, and that is the
     * whole of this bug.
     *
     * The history matters, because the obvious fix here is the one that broke
     * the app before. `oshi.welcome` is not base64: replying fed it to
     * `deriveSharedSecret`, which decoded 8 bytes and threw, every bubble read
     * "Not delivered", and that is literally what Play's reviewer photographed
     * on 2026-09-16 when they rejected the app for broken functionality. The
     * defensive answer was to make the thread READ-ONLY — hide the composer,
     * refuse the send. That stopped the crash and left a new user's only
     * conversation unanswerable, with "add a contact to start a conversation"
     * where a reply should be.
     *
     * Pointing it at the author's real key fixes both: the send path gets a
     * genuine 32-byte key so nothing throws, and the message reaches an account
     * that is read. The honesty concern is answered better than the unreachable
     * sender answered it — [R.string.chat_welcome_creator_notice] sits above the
     * composer and says plainly who is on the other end.
     */
    const val SYSTEM_SENDER_KEY = AUTHOR_PUBLIC_KEY

    /**
     * What the greeting used to be attributed to. Kept ONLY so installs seeded
     * under it can be migrated — [migrateLegacyWelcomeThread] — and so the send
     * guards can still refuse it if a row escapes the migration. Never write it.
     */
    const val LEGACY_SYSTEM_SENDER_KEY = "oshi.welcome"

    /**
     * True when [publicKey] is the welcome thread's author — however it is
     * spelled (URL-safe / unpadded base64 included). The rating policy uses it to
     * tell the welcome reply (moment 1) from a message to a real contact.
     */
    fun isAuthor(publicKey: String): Boolean =
        com.oshi.messenger.data.repository.MessageRepository.canonicalIdentity(publicKey) ==
            com.oshi.messenger.data.repository.MessageRepository.canonicalIdentity(AUTHOR_PUBLIC_KEY)

    /**
     * Seeded once per identity, keyed by public key rather than a global flag,
     * so someone who creates a second identity gets welcomed again — and someone
     * RESTORING an existing identity does not, because they never reach the
     * creation path at all. `WelcomeMessageManager.swift:42-44`.
     *
     * The 24-character prefix is iOS's, kept byte for byte so the two platforms
     * describe the same thing under the same name.
     */
    fun seededPrefKey(publicKey: String): String =
        "oshi.welcome.seeded." + publicKey.take(24)

    /** `WelcomeMessageManager.swift:150`. */
    fun textMessageId(publicKey: String): String =
        "oshi-welcome-text-" + publicKey.take(8)

    /** `WelcomeMessageManager.swift:159`. */
    fun cardMessageId(publicKey: String): String =
        "oshi-welcome-card-" + publicKey.take(8)

    /**
     * `WelcomeMessageManager.swift:56-58`. Three guards, in iOS's order.
     *
     * `isRestoredIdentity` is passed rather than inferred: on Android only
     * `MainViewModel.createIdentity` mints a key pair, but stating the rule here
     * means a future second creation path cannot forget it.
     */
    fun shouldSeed(publicKey: String, isRestoredIdentity: Boolean, alreadySeeded: Boolean): Boolean {
        if (publicKey.isEmpty()) return false
        if (isRestoredIdentity) return false
        if (alreadySeeded) return false
        return true
    }

    /**
     * The pseudo-contact the greeting arrives from.
     *
     * `lastSeen` stays null on purpose: `getConversations` reads it for the green
     * online dot, and a pseudo-key that is never actually online must not claim
     * to be. Nothing polls presence for it — `requestProfileIfNeeded` is only
     * ever called from real inbound handlers.
     */
    fun contactRow(profilePicture: ByteArray?): Contact = Contact(
        publicKey = SYSTEM_SENDER_KEY,
        alias = AUTHOR_ALIAS,
        profilePicture = profilePicture,
        isVerified = false,
        isBlocked = false,
        lastSeen = null,
    )

    /**
     * The greeting. `WelcomeMessageManager.swift:99-119, :148-154`.
     *
     * @param body the localized `R.string.welcome_message_body`, resolved by the
     *        caller — this object never touches a `Context`.
     */
    fun greetingRow(myPublicKey: String, body: String, atMillis: Long): Message = Message(
        id = textMessageId(myPublicKey),
        senderPublicKey = SYSTEM_SENDER_KEY,
        recipientPublicKey = myPublicKey,
        content = body,
        // Locally generated: there is no ciphertext because nothing travelled.
        // `WelcomeMessageManager.swift:104-106`.
        encryptedContent = null,
        timestamp = atMillis,
        isFromMe = false,
        isRead = false,                       // :112 — the badge is the point
        isDelivered = true,                   // :113 — `.delivered`
        deliveryMethod = DeliveryMethod.LOCAL, // :117 — iOS writes nil; LOCAL is that nil
    )

    /**
     * The contact card, one second later so it reads in order and is the last
     * thing on screen — the thing to act on. `WelcomeMessageManager.swift:72-85,
     * :156-167`.
     *
     * `content` carries the localized caption rather than the `"[CONTACT] name"`
     * convention `ChatViewModel.sendContactCard` uses: the bubble ignores
     * `content` entirely once the payload parses (`ChatScreen.kt:3146-3163`), but
     * the CONVERSATION LIST preview does not — `OSHIControlPayload.listPreview`
     * only rewrites the exact `[IMAGE]/[VIDEO]/[AUDIO]/[DOCUMENT]` tokens and
     * returns anything else verbatim, so `"[CONTACT] Hugo"` would appear
     * literally in the chat list. iOS puts the caption here too
     * (`WelcomeMessageManager.swift:160`).
     */
    fun contactCardRow(myPublicKey: String, caption: String, atMillis: Long): Message = Message(
        id = cardMessageId(myPublicKey),
        senderPublicKey = SYSTEM_SENDER_KEY,
        recipientPublicKey = myPublicKey,
        content = caption,
        encryptedContent = null,
        timestamp = atMillis,
        isFromMe = false,
        isRead = false,
        isDelivered = true,
        deliveryMethod = DeliveryMethod.LOCAL,
        mediaType = MediaType.CONTACT,
        mediaData = contactCardJson(atMillis).toByteArray(Charsets.UTF_8),
    )

    /**
     * The card body — the same bytes `MediaManager.createContactCard` builds on iOS
     * (`WelcomeMessageManager.swift:78-79`), through the single writer
     * [com.oshi.messenger.data.model.ContactCardWire.encode].
     *
     * __CONTACT_CARD_COMPAT_2026_09_27__ This used to be
     * `Json.encodeToString(ContactPayload(…))`, which omits the null `timestamp`:
     * 75 bytes, `{"publicKey":"eAbM…E3A=","alias":"Hugo"}`. Fine for our own bubble,
     * but a user who forwarded the card to an iPhone sent exactly those bytes, and
     * iOS's `ContactCard` requires `timestamp` — the receiver got a grey square it
     * could not tap. Rows already stored in that shape are fixed on the way out
     * by `ContactCardWire.canonicalizeForWire`.
     */
    fun contactCardJson(atMillis: Long = System.currentTimeMillis()): String =
        com.oshi.messenger.data.model.ContactCardWire.encode(
            publicKey = AUTHOR_PUBLIC_KEY,
            alias = AUTHOR_ALIAS,
            timestampMillis = atMillis,
        )

    /** The card sorts after the greeting. `WelcomeMessageManager.swift:85` (`+1 s`). */
    const val CARD_OFFSET_MS = 1_000L
}
