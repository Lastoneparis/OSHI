package com.oshi.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandCatalogTest {

    @Test fun `a bare slash opens the full list — that is the discovery gesture`() {
        assertEquals("", SlashCommandCatalog.query("/"))
        assertEquals(SlashCommandCatalog.builtIns(), SlashCommandCatalog.matches("/"))
    }

    @Test fun `the menu closes as soon as arguments are being typed`() {
        // After the space the user is writing the token, not the command name.
        assertNull(SlashCommandCatalog.query("/pay cashuAxxx"))
        assertTrue(SlashCommandCatalog.matches("/pay cashuAxxx").isEmpty())
    }

    @Test fun `a slash that is not leading is just text`() {
        assertNull(SlashCommandCatalog.query("hello /pay"))
        assertNull(SlashCommandCatalog.query("and/or"))
        assertTrue(SlashCommandCatalog.matches("hello /pay").isEmpty())
    }

    @Test fun `typing narrows the list, and a miss shows no bar at all`() {
        assertEquals(1, SlashCommandCatalog.matches("/p").size)
        assertEquals("/pay", SlashCommandCatalog.matches("/pa").single().command)
        assertEquals("/pay", SlashCommandCatalog.matches("/PAY").single().command)
        // An empty bar floating over the keyboard is worse than no bar.
        assertTrue(SlashCommandCatalog.matches("/zzz").isEmpty())
    }

    @Test fun `picking a command leaves a trailing space, which also closes the menu`() {
        val entry = SlashCommandCatalog.builtIns().single()
        val completed = SlashCommandCatalog.completion(entry)
        assertEquals("/pay ", completed)
        assertNull(SlashCommandCatalog.query(completed))
    }

    @Test fun `every built-in is a real command the composer routes`() {
        // A menu entry nothing handles is a dead end offered by the app itself.
        SlashCommandCatalog.builtIns().forEach {
            assertTrue(it.command, CashuPayCommand.handles(it.command))
        }
    }
}
