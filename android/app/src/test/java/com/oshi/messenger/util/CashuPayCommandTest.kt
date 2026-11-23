package com.oshi.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CashuPayCommandTest {

    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** NUT-00's V3 example: 2 + 8 = 10 sat. */
    private val token = "cashuA" + b64url(
        ("""{"token":[{"mint":"https://8333.space:3338","proofs":[""" +
            """{"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",""" +
            """"C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"},""" +
            """{"amount":8,"secret":"fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be",""" +
            """"C":"029e8e5050b890a7d6c0968db16bc1d5d5fa040ea1de284f6ec69d61299f671059","id":"009a1f293253e41e"}""" +
            """]}],"unit":"sat","memo":"Thank you."}""").toByteArray()
    )

    @Test fun `only slash pay is routed`() {
        assertTrue(CashuPayCommand.handles("/pay"))
        assertTrue(CashuPayCommand.handles("  /pay $token "))
        assertFalse(CashuPayCommand.handles("/payment plan"))
        assertFalse(CashuPayCommand.handles("please /pay me"))
    }

    @Test fun `bare slash pay explains itself`() {
        assertEquals(CashuPayCommand.Outcome.Usage, CashuPayCommand.parse("/pay", isGroup = false))
    }

    @Test fun `a one to one chat sends straight away`() {
        val out = CashuPayCommand.parse("/pay $token", isGroup = false)
        assertTrue(out is CashuPayCommand.Outcome.Send)
        assertEquals("10 sat", (out as CashuPayCommand.Outcome.Send).summary)
        assertEquals(10L, out.token.totalAmount)
    }

    @Test fun `a group refuses until the word public is typed`() {
        // The refusal IS the feature: everyone in the thread can spend it.
        val held = CashuPayCommand.parse("/pay $token", isGroup = true)
        assertTrue(held is CashuPayCommand.Outcome.NeedsPublicConfirm)
        assertEquals("10 sat", (held as CashuPayCommand.Outcome.NeedsPublicConfirm).summary)

        val confirmed = CashuPayCommand.parse("/pay $token public", isGroup = true)
        assertTrue(confirmed is CashuPayCommand.Outcome.Send)
    }

    @Test fun `the confirmation word is never mistaken for a token`() {
        assertEquals(CashuPayCommand.Outcome.NotAToken, CashuPayCommand.parse("/pay public", isGroup = true))
        // …and a token is never mistaken for the confirmation word.
        assertTrue(CashuPayCommand.parse("/pay $token PUBLIC", isGroup = true)
            is CashuPayCommand.Outcome.Send)
    }

    @Test fun `nothing that fails to decode is ever sent`() {
        // A typo in a pasted token is money thrown away: the sender believes
        // they paid and the recipient holds a string no wallet will take.
        assertEquals(CashuPayCommand.Outcome.Invalid,
            CashuPayCommand.parse("/pay ${token.dropLast(8)}", isGroup = false))
        assertEquals(CashuPayCommand.Outcome.NotAToken,
            CashuPayCommand.parse("/pay hello", isGroup = false))
        assertEquals(CashuPayCommand.Outcome.NotAToken,
            CashuPayCommand.parse("/pay $token and more", isGroup = false))
    }

    @Test fun `a zero amount token is refused before sending`() {
        val zero = """{"token":[{"mint":"https://m.example","proofs":[
            {"amount":0,"secret":"s","C":"02aa","id":"00aa"}]}],"unit":"sat"}""".replace("\n", "")
        assertEquals(CashuPayCommand.Outcome.Invalid,
            CashuPayCommand.parse("/pay cashuA" + b64url(zero.toByteArray()), isGroup = false))
    }

    // __CASHU_SEND_READY_2026_11_09__ The Send-bitcoin sheet.

    @Test fun `the sheet accepts exactly what slash pay accepts`() {
        val out = CashuPayCommand.parsePasted("  $token\n")
        assertTrue(out is CashuPayCommand.Outcome.Send)
        assertEquals("10 sat", (out as CashuPayCommand.Outcome.Send).summary)
        // A wallet's `cashu:` link is the same money.
        assertTrue(CashuPayCommand.parsePasted("cashu:$token") is CashuPayCommand.Outcome.Send)
    }

    @Test fun `the sheet sends nothing for empty, garbled or prose input`() {
        assertEquals(CashuPayCommand.Outcome.Usage, CashuPayCommand.parsePasted("   "))
        assertEquals(CashuPayCommand.Outcome.NotAToken, CashuPayCommand.parsePasted("hello there"))
        assertEquals(CashuPayCommand.Outcome.Invalid, CashuPayCommand.parsePasted(token.dropLast(40)))
        // Two tokens at once is not one payment.
        assertEquals(CashuPayCommand.Outcome.NotAToken, CashuPayCommand.parsePasted("$token $token"))
    }

    @Test fun `a token is found inside whatever the clipboard holds`() {
        assertEquals(token, CashuPayCommand.firstToken("here you go: $token thanks"))
        assertEquals(null, CashuPayCommand.firstToken("no money here"))
    }
}
