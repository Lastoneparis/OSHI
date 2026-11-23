package com.oshi.messenger.ui.viewmodel

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * The recipient validator against the platform it actually runs on.
 *
 * Two things here cannot be checked on the desktop JVM:
 *
 *  1. **`android.util.Base64` is a throwing stub in unit tests.** The validator
 *     deliberately uses `java.util.Base64` so it stays unit-testable, but the
 *     canonical spelling it produces has to be byte-identical to what
 *     `CryptoManager.getPublicKeyString()` writes — and that is
 *     `android.util.Base64.encodeToString(bytes, NO_WRAP)`. If the two ever
 *     disagreed (a wrapped line, a URL-safe default) every key the validator
 *     canonicalised would be filed under a spelling nothing else in the app
 *     uses, which is the exact failure this validator exists to prevent.
 *  2. **The strings shown to the user are resources**, and a `Context` is the
 *     only thing that can prove all 34 locales have them.
 */
@RunWith(AndroidJUnit4::class)
class RecipientKeyInstrumentedTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    /** Every message the paste field can show, i.e. `NewMessageScreen.problemMessage`. */
    private val messageForProblem = mapOf(
        RecipientKey.Problem.EMPTY to R.string.new_key_error_empty,
        RecipientKey.Problem.CONTAINS_WHITESPACE to R.string.new_key_error_spaces,
        RecipientKey.Problem.TRAILING_CHARACTERS to R.string.new_key_error_extra_characters,
        RecipientKey.Problem.MISSING_PADDING to R.string.new_key_error_padding,
        RecipientKey.Problem.TOO_SHORT to R.string.new_key_error_too_short,
        RecipientKey.Problem.TOO_LONG to R.string.new_key_error_too_long,
        RecipientKey.Problem.NOT_A_KEY to R.string.new_key_error_not_a_key,
        RecipientKey.Problem.OWN_KEY to R.string.new_key_error_own_key,
    )

    @Test
    fun the_canonical_spelling_is_what_CryptoManager_writes() {
        val random = SecureRandom()
        repeat(200) {
            val raw = ByteArray(RecipientKey.KEY_BYTES).also(random::nextBytes)
            // Exactly how CryptoManager.getPublicKeyString() encodes a key.
            val asTheAppWritesIt = Base64.encodeToString(raw, Base64.NO_WRAP)
            assertEquals(
                "java.util.Base64 and android.util.Base64 disagree on this key",
                asTheAppWritesIt,
                RecipientKey.canonicalize(asTheAppWritesIt)
            )
            // …and every other spelling folds onto it.
            val urlSafe = Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE)
            assertEquals(asTheAppWritesIt, RecipientKey.canonicalize(urlSafe))
        }
    }

    @Test
    fun a_key_the_app_generated_is_a_valid_recipient_for_someone_else() {
        val random = SecureRandom()
        val mine = Base64.encodeToString(
            ByteArray(RecipientKey.KEY_BYTES).also(random::nextBytes), Base64.NO_WRAP
        )
        val theirs = Base64.encodeToString(
            ByteArray(RecipientKey.KEY_BYTES).also(random::nextBytes), Base64.NO_WRAP
        )
        assertEquals(theirs, RecipientKey.canonicalRecipientOrNull(theirs, mine))
        assertNull(RecipientKey.canonicalRecipientOrNull(mine, mine))
    }

    @Test
    fun every_refusal_has_a_message_in_the_default_locale() {
        RecipientKey.Problem.values().forEach { problem ->
            val res = messageForProblem[problem]
            assertNotNull("no string mapped for $problem", res)
            val text = context.getString(res!!)
            assertTrue("$problem resolves to a blank string", text.isNotBlank())
        }
        // A distinct message per refusal is the point — "invalid address format"
        // eight times over would be the bug back again.
        val texts = messageForProblem.values.map { context.getString(it) }
        assertEquals(
            "two refusals share the same message",
            texts.size,
            texts.toSet().size
        )
    }

    @Test
    fun the_three_addresses_a_device_accepted_are_each_refused_with_their_own_message() {
        val mine = "turSEKag/stcMtIZHzaHSxYHSJIVoT1mWvRi7bWqX7w="
        val reported = mapOf(
            "hTiEnKV10M You hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE=" to
                RecipientKey.Problem.CONTAINS_WHITESPACE,
            "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE=f" to
                RecipientKey.Problem.TRAILING_CHARACTERS,
            "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE" to
                RecipientKey.Problem.MISSING_PADDING,
        )
        reported.forEach { (address, expected) ->
            val result = RecipientKey.validate(address, mine)
            assertTrue("$address was accepted", result is RecipientKey.Result.Invalid)
            val problem = (result as RecipientKey.Result.Invalid).problem
            assertEquals(address, expected, problem)
            assertTrue(context.getString(messageForProblem.getValue(problem)).isNotBlank())
        }
    }
}
