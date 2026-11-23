package com.oshi.messenger

import com.oshi.messenger.service.WelcomeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The invariant behind the 2026-09-16 Play rejection ("broken functionality").
 *
 * WHAT WENT WRONG
 * ---------------
 * The seeded welcome thread offered a reply box. Replying sent to
 * [WelcomeMessage.SYSTEM_SENDER_KEY] — `oshi.welcome`, a LABEL — which
 * `CryptoManager.deriveSharedSecret(String)` handed to `importPublicKey`, which
 * base64-decoded it to 8 bytes and threw
 * `Public key must be 32 bytes, got 8 (input: oshi.welcome...)`. The bubble was
 * stamped "Not delivered", and that exception text was toasted verbatim. Play's
 * reviewer, having no second device, had ONLY this thread — so the whole app
 * read as broken.
 *
 * WHAT THIS PINS
 * --------------
 * That the welcome thread's peer is not, and can never become, a usable X25519
 * key — which is the premise for `ChatViewModel.isSystemThread` hiding the
 * composer and refusing the send.
 *
 * HOW IT CAN FAIL (it is not a mirror of the code under test)
 * -----------------------------------------------------------
 *  - give [WelcomeMessage.SYSTEM_SENDER_KEY] a real 32-byte base64 value and
 *    [systemSenderIsNotAUsableKey] goes red: the guard's premise is gone and
 *    the composer should come back;
 *  - attribute the greeting to a real contact instead and
 *    [greetingComesFromTheSystemSender] goes red.
 *
 * The 8 and the 32 below are literals, not values read back from the code.
 * `java.util.Base64.getMimeDecoder` is used because it skips characters outside
 * the alphabet exactly as `android.util.Base64` did with the `.` in the middle
 * of `oshi.welcome` — which is how "got 8" arose in the reviewer's screenshot.
 */
class WelcomeThreadIsNotAPeerTest {

    private fun decodedSize(s: String): Int =
        java.util.Base64.getMimeDecoder().decode(s).size

    /**
     * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ The premise named above has
     * CHANGED, on purpose, and this test's own "how it can fail" note called the
     * shot: "give SYSTEM_SENDER_KEY a real 32-byte base64 value and this goes
     * red: the guard's premise is gone and the composer should come back."
     *
     * That is exactly what happened. The composer has come back, because the
     * greeting now goes to the author's real account instead of a label — so a
     * new user's only conversation can be answered.
     *
     * What still has to hold is the HISTORICAL fact that made the label fatal:
     * `oshi.welcome` decodes to 8 bytes, X25519 wants 32, and that mismatch is
     * what the Play reviewer photographed on 2026-09-16. It is pinned below
     * against the LEGACY constant, which still exists for the migration — so
     * the lesson survives even though the decision was reversed.
     */
    @Test
    fun systemSenderIsNotAUsableKey() {
        // The legacy label: still exactly as dangerous as it was.
        assertEquals("oshi.welcome", WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY)
        val legacySize = decodedSize(WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY)
        assertEquals("the reviewer's screenshot said 'got 8'", 8, legacySize)
        assertNotEquals("X25519 needs exactly 32 bytes", 32, legacySize)

        // And the sender in use today: a real key, which is why replying works.
        val liveSize = decodedSize(WelcomeMessage.SYSTEM_SENDER_KEY)
        assertEquals(
            "the welcome sender must be a usable X25519 key or every reply throws again",
            32, liveSize,
        )
    }

    @Test
    fun greetingComesFromTheSystemSender() {
        val mine = "eAbMUq0V1sJ7TnQkQ0sQ0Qm0Q0sQ0Qm0Q0sQ0Qm0Q0s="
        val row = WelcomeMessage.greetingRow(mine, "hello", atMillis = 1_700_000_000_000L)
        assertEquals(WelcomeMessage.SYSTEM_SENDER_KEY, row.senderPublicKey)
        assertEquals(mine, row.recipientPublicKey)
    }
}
