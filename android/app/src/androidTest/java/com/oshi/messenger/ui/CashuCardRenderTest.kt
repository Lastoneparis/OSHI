package com.oshi.messenger.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.oshi.messenger.ui.components.CashuTokenCardsForText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64

/**
 * The card as a user actually sees it, composed on a device.
 *
 * The unit tests prove the token DECODES. They cannot prove the bubble shows
 * the amount, names the mint, or carries the bearer warning — and a payment
 * feature whose warning silently failed to render would be the worst kind of
 * passing test suite.
 */
class CashuCardRenderTest {

    @get:Rule val compose = createComposeRule()

    private fun b64url(bytes: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** NUT-00's V3 example: 2 + 8 = 10 sat, mint 8333.space:3338, memo "Thank you." */
    private val token = "cashuA" + b64url(
        ("""{"token":[{"mint":"https://8333.space:3338","proofs":[""" +
            """{"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",""" +
            """"C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"},""" +
            """{"amount":8,"secret":"fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be",""" +
            """"C":"029e8e5050b890a7d6c0968db16bc1d5d5fa040ea1de284f6ec69d61299f671059","id":"009a1f293253e41e"}""" +
            """]}],"unit":"sat","memo":"Thank you."}""").toByteArray(Charsets.UTF_8)
    )

    @Test
    fun theCardShowsTheAmountTheMintAndTheMemo() {
        compose.setContent { CashuTokenCardsForText(text = "here you go $token") }
        compose.onNodeWithText("  10 sat").assertIsDisplayed()
        // The mint is WHO owes the money; the scheme is stripped for display.
        compose.onNodeWithText("8333.space:3338").assertIsDisplayed()
        compose.onNodeWithText("Thank you.").assertIsDisplayed()
    }

    @Test
    fun theBearerWarningIsOnScreen() {
        compose.setContent { CashuTokenCardsForText(text = token) }
        compose.onNodeWithText("Anyone who gets this token can spend it.").assertIsDisplayed()
        compose.onNodeWithText("Redeem in a wallet").assertIsDisplayed()
    }

    @Test
    fun theCardSaysItHasNotBeenCheckedWithTheMint() {
        // OSHI validates nothing — no DLEQ, no mint call. A figure shown without
        // this line would present an already-spent token as a payment received.
        compose.setContent { CashuTokenCardsForText(text = token) }
        compose.onNodeWithText(
            "OSHI hasn't checked this with the mint — it may already have been spent. " +
                "It is yours only once your wallet redeems it."
        ).assertIsDisplayed()
    }

    @Test
    fun theWebRedemptionIsAnExplicitChoiceBesideCopy() {
        // Sending a bearer token to a third-party site in a URL is a decision,
        // never a fallback to slide into when no wallet is installed.
        compose.setContent { CashuTokenCardsForText(text = token) }
        compose.onNodeWithText("Copy token").assertIsDisplayed()
        compose.onNodeWithText("Redeem on the web").assertIsDisplayed()
    }

    @Test
    fun ordinaryTextDrawsNothingAtAll() {
        // A conversation about cashu must not sprout payment cards.
        compose.setContent {
            CashuTokenCardsForText(text = "have you tried cashuA tokens yet? cashuB is newer")
        }
        val tree = compose.onRoot().printToString()
        assertFalse("a sentence rendered a card", tree.contains("Redeem in a wallet"))
    }

    @Test
    fun theSameTokenQuotedTwiceIsOneCard() {
        compose.setContent { CashuTokenCardsForText(text = "$token and again $token") }
        compose.onAllNodesWithText("Redeem in a wallet").assertCountEquals(1)
    }
}
