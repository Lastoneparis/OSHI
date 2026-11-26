package com.oshi.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** __CONTACT_CARD_ALIAS_2026_11_20__ */
class ContactCardAliasTest {
    private val key = "ABCDEFGHijklmnop"

    @Test fun aCardCannotReuseAnExistingContactsName() {
        assertEquals("ABCDEFGH...", ContactCardAlias.safeAlias("Mom", key, listOf("mom ", "Bob")))
    }

    @Test fun aFreshNameIsKeptCleaned() {
        assertEquals("Alice", ContactCardAlias.safeAlias("  Al‮ice\n", key, listOf("Bob")))
        assertEquals(64, ContactCardAlias.safeAlias("x".repeat(500), key, emptyList()).length)
    }

    @Test fun blankFallsBackToTheKey() {
        assertEquals("ABCDEFGH...", ContactCardAlias.safeAlias(null, key, emptyList()))
        assertEquals("ABCDEFGH...", ContactCardAlias.safeAlias("​\u0000 ", key, emptyList()))
    }
}
