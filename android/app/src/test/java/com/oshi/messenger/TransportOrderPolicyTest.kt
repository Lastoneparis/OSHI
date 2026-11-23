package com.oshi.messenger

import com.oshi.messenger.service.MessageSendPolicy
import com.oshi.messenger.service.MessageSendPolicy.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order messages travel in, and the one promise about it that has to hold.
 *
 * Parity with iOS `OSHITests/TransportPolicyTests.swift`, case for case. The
 * decision is shared; only where the two preferences are READ differs (iOS
 * reads `UserDefaults` inside `order`, here they are fields of
 * [MessageSendPolicy.RoutingConditions]) — see the kdoc on that type.
 *
 * __PREFERENCE_RANK_WAS_A_LIE_2026_10_20__ These exist because on iOS the thing
 * that LOOKED like the routing order was not it: a `preferenceRank` ranked the
 * transports 0…3 and admitted in its own comment that "changing a case here
 * does not change routing". Android never had that constant, but it had the
 * same underlying problem — the ordering judgement lived inline in
 * `MessageRepository.sendTextMessage`, where nothing could test it.
 */
class TransportOrderPolicyTest {

    private fun online(
        media: Boolean = false,
        radio: Boolean = false,
        peerOnMesh: Boolean = false,
        peerOnline: Boolean = true,
        internet: Boolean = true,
        prefer: Boolean = false,
    ) = MessageSendPolicy.RoutingConditions(
        hasPhysicalPath = true,
        peerOnMesh = peerOnMesh,
        radioReady = radio,
        peerLikelyOnline = peerOnline,
        carriesMedia = media,
        internetAllowedForSending = internet,
        preferRadioOverRelay = prefer,
    )

    // =====================================================================
    // (a) The guarantee
    // =====================================================================

    /**
     * THE property. Not "the relay is deprioritised" — the relay is not an
     * option at all. Checked over every combination of the other inputs,
     * because a guarantee that holds in the case you thought of is not one.
     */
    @Test
    fun nothingGoesOverTheInternetWhenTheUserForbadeIt() {
        for (path in listOf(true, false)) {
            for (mesh in listOf(true, false)) {
                for (radio in listOf(true, false)) {
                    for (peerOnline in listOf(true, false)) {
                        for (media in listOf(true, false)) {
                            for (prefer in listOf(true, false)) {
                                val c = MessageSendPolicy.RoutingConditions(
                                    hasPhysicalPath = path,
                                    peerOnMesh = mesh,
                                    radioReady = radio,
                                    peerLikelyOnline = peerOnline,
                                    carriesMedia = media,
                                    internetAllowedForSending = false,
                                    preferRadioOverRelay = prefer,
                                )
                                assertFalse(
                                    "relay offered with internet sending off: $c",
                                    MessageSendPolicy.order(c).contains(Transport.INTERNET_RELAY),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * And the honest consequence: forbidding the internet with no offline route
     * leaves NOWHERE to send. An empty list is the right answer — better than a
     * silent relay send — and the caller keeps the row undelivered rather than
     * marking it failed.
     */
    @Test
    fun forbiddingTheInternetWithNoRadioLeavesNoRouteAtAll() {
        assertEquals(emptyList<Transport>(), MessageSendPolicy.order(online(internet = false)))
    }

    @Test
    fun allowingTheInternetAgainRestoresTheRelay() {
        assertEquals(emptyList<Transport>(), MessageSendPolicy.order(online(internet = false)))
        assertEquals(listOf(Transport.INTERNET_RELAY), MessageSendPolicy.order(online()))
    }

    // =====================================================================
    // (b) The radio, when the user wants it first
    // =====================================================================

    @Test
    fun preferringTheRadioPutsTextOnItBeforeTheRelay() {
        assertEquals(
            listOf(Transport.LORA, Transport.INTERNET_RELAY),
            MessageSendPolicy.order(online(radio = true, prefer = true)),
        )
    }

    /**
     * The limit that keeps the app usable. A LoRa frame carries ~180–240 bytes
     * at seconds per frame, so a photo over the radio is minutes on the air
     * while a working network path sits unused. Preferring the radio is about
     * your messages, not your media.
     */
    @Test
    fun preferringTheRadioStillSendsMediaOverTheNetwork() {
        assertEquals(
            listOf(Transport.INTERNET_RELAY),
            MessageSendPolicy.order(online(media = true, radio = true, prefer = true)),
        )
    }

    /** Off-grid, media has no better option, so the radio carries it. */
    @Test
    fun offGridMediaMayUseTheRadio() {
        val c = MessageSendPolicy.RoutingConditions(
            hasPhysicalPath = false, peerOnMesh = false, radioReady = true,
            peerLikelyOnline = true, carriesMedia = true,
        )
        assertEquals(listOf(Transport.LORA), MessageSendPolicy.order(c))
    }

    @Test
    fun theDefaultOrderIsUnchangedForSomeoneWithoutARadio() {
        assertEquals(listOf(Transport.INTERNET_RELAY), MessageSendPolicy.order(online()))
        assertEquals(
            listOf(Transport.BLUETOOTH_MESH, Transport.INTERNET_RELAY),
            MessageSendPolicy.order(online(peerOnMesh = true)),
        )
    }

    /** The two situations that already used LoRa keep doing so, unasked. */
    @Test
    fun radioStillComesFirstWhenThePeerLooksUnreachable() {
        assertEquals(
            listOf(Transport.LORA, Transport.INTERNET_RELAY),
            MessageSendPolicy.order(online(radio = true, peerOnline = false)),
        )
    }

    @Test
    fun radioStillComesFirstOffGrid() {
        val c = MessageSendPolicy.RoutingConditions(
            hasPhysicalPath = false, peerOnMesh = false, radioReady = true,
            peerLikelyOnline = true, carriesMedia = false,
        )
        assertEquals(listOf(Transport.LORA), MessageSendPolicy.order(c))
    }

    /**
     * With a radio paired and the peer reachable, the radio stays BEHIND the
     * relay unless asked for — a fallback, not a downgrade imposed on everyone
     * who ever paired a node.
     */
    @Test
    fun radioStaysBehindTheRelayWhenNobodyAskedForIt() {
        assertEquals(
            listOf(Transport.INTERNET_RELAY, Transport.LORA),
            MessageSendPolicy.order(online(radio = true)),
        )
    }

    /**
     * The new preference must not disturb the narrower question `lane()`
     * answers, which has its own tests in `LoRaIntegrationPolicyTest`.
     */
    @Test
    fun theOriginalLaneDecisionIsUntouched() {
        assertEquals(
            MessageSendPolicy.Lane.OFF_GRID,
            MessageSendPolicy.lane(hasPhysicalPath = false, radioReady = true, peerLastSeenMs = null, nowMs = 1L),
        )
        assertEquals(
            MessageSendPolicy.Lane.NONE,
            MessageSendPolicy.lane(hasPhysicalPath = true, radioReady = true, peerLastSeenMs = null, nowMs = 1L),
        )
    }

    // =====================================================================
    // (c) The anti-drift property
    // =====================================================================

    /**
     * THE test that justifies the refactor. `radioComesBeforeRelay` is what the
     * send path branches on; `order` is what the policy publishes. If they could
     * disagree we would be back to a documented order that does not match the
     * code — the exact bug this replaced.
     */
    @Test
    fun theBranchTakenAlwaysAgreesWithThePublishedOrder() {
        for (internet in listOf(true, false)) {
            for (prefer in listOf(true, false)) {
                for (path in listOf(true, false)) {
                    for (mesh in listOf(true, false)) {
                        for (radio in listOf(true, false)) {
                            for (peerOnline in listOf(true, false)) {
                                for (media in listOf(true, false)) {
                                    val c = MessageSendPolicy.RoutingConditions(
                                        hasPhysicalPath = path, peerOnMesh = mesh, radioReady = radio,
                                        peerLikelyOnline = peerOnline, carriesMedia = media,
                                        internetAllowedForSending = internet,
                                        preferRadioOverRelay = prefer,
                                    )
                                    val list = MessageSendPolicy.order(c)
                                    val r = list.indexOf(Transport.LORA)
                                    val l = list.indexOf(Transport.INTERNET_RELAY)
                                    val expected = when {
                                        r < 0 -> false
                                        l < 0 -> true
                                        else -> r < l
                                    }
                                    assertEquals(
                                        "branch and published order disagree for $c (list: $list)",
                                        expected,
                                        MessageSendPolicy.radioComesBeforeRelay(c),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A transport is never offered twice, and never offered when its
     * precondition is false. Cheap, but it is what stops a future edit to
     * `order` from handing the send path a list it walks twice.
     */
    @Test
    fun theOrderNeverRepeatsOrInventsATransport() {
        for (internet in listOf(true, false)) {
            for (prefer in listOf(true, false)) {
                for (path in listOf(true, false)) {
                    for (mesh in listOf(true, false)) {
                        for (radio in listOf(true, false)) {
                            val c = MessageSendPolicy.RoutingConditions(
                                hasPhysicalPath = path, peerOnMesh = mesh, radioReady = radio,
                                peerLikelyOnline = false, carriesMedia = false,
                                internetAllowedForSending = internet,
                                preferRadioOverRelay = prefer,
                            )
                            val list = MessageSendPolicy.order(c)
                            assertEquals("a transport appears twice: $list", list.size, list.toSet().size)
                            if (!mesh) assertFalse(list.contains(Transport.BLUETOOTH_MESH))
                            if (!radio) assertFalse(list.contains(Transport.LORA))
                            if (!path) assertFalse(list.contains(Transport.INTERNET_RELAY))
                        }
                    }
                }
            }
        }
    }

    /** Both platforms must agree on the shape of the enum, name for name. */
    @Test
    fun theTransportNamesMatchTheIosEnum() {
        assertTrue(Transport.entries.map { it.name }.containsAll(
            listOf("BLUETOOTH_MESH", "WIFI_MESH", "LORA", "INTERNET_RELAY")))
    }

    // =====================================================================
    // __ANTI_JAMMING_PHASE1_2026_11_13__ A jammed radio is not offered at all
    // =====================================================================

    @Test
    fun aJammedRadioIsLeftOutAndTheRelayCarriesTheMessage() {
        val c = online(radio = true, peerOnline = false).copy(radioJammed = true)
        assertEquals(listOf(Transport.INTERNET_RELAY), MessageSendPolicy.order(c))
        assertFalse(MessageSendPolicy.radioComesBeforeRelay(c))
    }

    @Test
    fun aJammedRadioOffGridLeavesTheMeshOrNothing() {
        val offGrid = MessageSendPolicy.RoutingConditions(
            hasPhysicalPath = false, peerOnMesh = true, radioReady = true,
            peerLikelyOnline = false, carriesMedia = false, radioJammed = true,
        )
        assertEquals(listOf(Transport.BLUETOOTH_MESH), MessageSendPolicy.order(offGrid))
        // Nothing else reachable: an empty list, the message waits (the radio keeps it for later).
        assertEquals(emptyList<Transport>(), MessageSendPolicy.order(offGrid.copy(peerOnMesh = false)))
    }

    @Test
    fun jammingNeverReordersAnythingElse() {
        for (path in listOf(true, false)) for (mesh in listOf(true, false)) for (peerOnline in listOf(true, false))
            for (media in listOf(true, false)) for (prefer in listOf(true, false)) {
                val c = MessageSendPolicy.RoutingConditions(path, mesh, true, peerOnline, media, true, prefer)
                val jammed = MessageSendPolicy.order(c.copy(radioJammed = true))
                assertEquals(MessageSendPolicy.order(c) - Transport.LORA, jammed)
                assertFalse(Transport.LORA in jammed)
            }
    }
}
