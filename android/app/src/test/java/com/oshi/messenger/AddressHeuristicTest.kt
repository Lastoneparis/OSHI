package com.oshi.messenger

import com.oshi.messenger.ui.components.AddressHeuristic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MAP_OPENER_2026_09_23__ The pattern fallback behind tappable addresses in chat.
 * The system TextClassifier found NOTHING in "Meet me at 1600 Amphitheatre Parkway,
 * Mountain View, CA 94043 tomorrow" on the 1.6.28 test emulator, so the address in a
 * message was plain text whatever the map setting said.
 */
class AddressHeuristicTest {

    private fun found(text: String): List<String> =
        AddressHeuristic.find(text).map { text.substring(it.first, it.last + 1) }

    @Test fun usAddressWithCityStateAndZip() {
        assertEquals(
            listOf("1600 Amphitheatre Parkway, Mountain View, CA 94043"),
            found("Meet me at 1600 Amphitheatre Parkway, Mountain View, CA 94043 tomorrow")
        )
    }

    @Test fun frenchAddressWithPostcode() {
        assertEquals(
            listOf("12 rue de Rivoli, 75001 Paris"),
            found("Group hello from Desktop - see you at 12 rue de Rivoli, 75001 Paris")
        )
        assertEquals(listOf("5 avenue des Champs-Élysées"), found("RDV au 5 avenue des Champs-Élysées demain"))
    }

    @Test fun abbreviatedStreetKeepsItsPeriod() {
        assertEquals(listOf("221B Baker St."), found("Come to 221B Baker St."))
        assertEquals(listOf("10 Downing Street"), found("It's at 10 Downing Street."))
    }

    @Test fun typeThenNameThenNumber() {
        assertEquals(listOf("Calle Mayor 10"), found("Estoy en Calle Mayor 10 ahora"))
        assertEquals(listOf("Via Roma 12"), found("ci vediamo in Via Roma 12"))
    }

    @Test fun germanicCompoundStreet() {
        assertEquals(listOf("Hauptstraße 5, 10115 Berlin"), found("Treffpunkt Hauptstraße 5, 10115 Berlin!"))
        assertEquals(listOf("Kalverstraat 1"), found("Zie je bij Kalverstraat 1"))
    }

    @Test fun ordinaryTextWithNumbersIsNotAnAddress() {
        assertTrue(found("I have 3 dogs and 2 cats").isEmpty())
        assertTrue(found("Call me at 5 pm, room 12").isEmpty())
        assertTrue(found("sent via email 3 times").isEmpty())
        assertTrue(found("Version 1.6.28 is out").isEmpty())
        assertTrue(found("no digits here at all, rue de la Paix").isEmpty())
    }
}
