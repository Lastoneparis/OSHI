package com.oshi.messenger.network.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Three places where the Android mesh had drifted from `OSHI/CrossPlatformMesh.swift`,
 * each of them a rule the two platforms have to agree on because they are two ends of
 * the same TCP frame.
 *
 * ## 1. The hop limit was taken from the wire (`:1052` on iOS)
 *
 * iOS: `guard message.hopCount < min(message.maxHops, maxHops)` with a local
 * `maxHops = 50`. Android: `if (message.hopCount >= message.maxHops)` — the constant
 * `MAX_HOPS = 50` was declared and never used. `maxHops` is a field of the envelope,
 * so one peer setting it to 9999 turned every Android relay into a re-flooder; the
 * only thing bounding it was the dedup cache, i.e. the size of the network.
 *
 * ## 2. The frame cap disagreed, and the disagreement cost the SESSION (`:685`)
 *
 * iOS reads frames up to `100 * 1024 * 1024`; Android refused anything over 16 MiB
 * **and `break`-ed out of the receive loop**, tearing the connection down. So a large
 * iOS→Android LAN media send both failed and reset the link, and the peer had to be
 * rediscovered. Two separate faults: the number, and the punishment. One peer's bad
 * frame should cost that frame and nothing else.
 *
 * ## 3. `stop()` kept the dedup cache (`:297-301`)
 *
 * iOS drops `seenMessageIds` in `stop()`. Android did not, so a mesh restart still
 * remembered every id of the previous run — and a restart is exactly when a user
 * re-sends the message that did not arrive, which the stale cache then swallowed in
 * silence.
 *
 * The rules are pure functions in the companion object so they can be tested here;
 * the WIRING (the `stop()` call, the receive loop's `continue`) needs the whole Hilt
 * graph plus a live `BluetoothAdapter`, so it is pinned in the source the way
 * `VideoTeardownOrderTest` pins `endCall`'s ordering.
 */
class CrossPlatformMeshParityTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/oshi/messenger/network/mesh/CrossPlatformMesh.kt")
        assertTrue("not found: ${f.absolutePath}", f.exists())
        f.readText()
    }

    private fun bodyOf(startNeedle: String, endNeedle: String): String {
        val start = source.indexOf(startNeedle)
        assertTrue("not found: $startNeedle", start > 0)
        val end = source.indexOf(endNeedle, start + startNeedle.length)
        assertTrue("not found after $startNeedle: $endNeedle", end > start)
        return source.substring(start, end)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    // =====================================================================
    // 1. Hop limit
    // =====================================================================

    /** The reported divergence, exactly as reported. */
    @Test
    fun a_wire_maxHops_of_9999_is_still_dropped_at_fifty() {
        assertFalse(
            "hop 49 of a 9999-hop message is still relayable",
            CrossPlatformMesh.shouldDropForHops(hopCount = 49, maxHops = 9999)
        )
        assertTrue(
            "hop 50 must be dropped whatever the envelope claims",
            CrossPlatformMesh.shouldDropForHops(hopCount = 50, maxHops = 9999)
        )
        assertTrue(
            CrossPlatformMesh.shouldDropForHops(hopCount = 5000, maxHops = Int.MAX_VALUE)
        )
    }

    /** …and a SMALLER wire value still wins: `min()`, not `coerceAtMost` of ours. */
    @Test
    fun a_short_ttl_from_the_wire_is_still_honoured() {
        assertTrue(
            "a maxHops=1 message must not be relayed past its first hop",
            CrossPlatformMesh.shouldDropForHops(hopCount = 1, maxHops = 1)
        )
        assertFalse(CrossPlatformMesh.shouldDropForHops(hopCount = 0, maxHops = 1))
    }

    @Test
    fun ordinary_traffic_is_unaffected() {
        for (hop in 0..49) {
            assertFalse("hop $hop of a normal 50-hop message must relay",
                CrossPlatformMesh.shouldDropForHops(hop, 50))
        }
        assertTrue(CrossPlatformMesh.shouldDropForHops(50, 50))
    }

    @Test
    fun the_relay_guard_calls_the_clamp_and_not_the_wire_value() {
        val body = bodyOf("private fun relayToRecipient(", "private fun ")
        assertTrue(
            "relayToRecipient must clamp via shouldDropForHops",
            body.contains("shouldDropForHops(message.hopCount, message.maxHops)")
        )
        assertFalse(
            "the unclamped wire comparison must not come back",
            body.contains("message.hopCount >= message.maxHops")
        )
    }

    // =====================================================================
    // 2. Frame cap, and what an oversize frame costs
    // =====================================================================

    @Test
    fun the_frame_cap_matches_the_number_ios_writes_with() {
        // OSHI/CrossPlatformMesh.swift:685 — `let maxFrameSize: UInt32 = 100 * 1024 * 1024`
        assertEquals(100 * 1024 * 1024, CrossPlatformMesh.MAX_FRAME_SIZE)
    }

    @Test
    fun a_frame_ios_is_willing_to_send_is_a_frame_we_are_willing_to_read() {
        assertEquals(MeshFrameAction.ACCEPT, CrossPlatformMesh.frameAction(1))
        assertEquals(MeshFrameAction.ACCEPT, CrossPlatformMesh.frameAction(16 * 1024 * 1024 + 1))
        assertEquals(MeshFrameAction.ACCEPT, CrossPlatformMesh.frameAction(64 * 1024 * 1024))
        assertEquals(MeshFrameAction.ACCEPT, CrossPlatformMesh.frameAction(CrossPlatformMesh.MAX_FRAME_SIZE))
    }

    /** The point of the fix: oversize is the FRAME's problem, not the session's. */
    @Test
    fun an_oversize_frame_is_dropped_and_does_not_reset_the_connection() {
        assertEquals(
            "one frame over the cap must not cost the connection",
            MeshFrameAction.DROP,
            CrossPlatformMesh.frameAction(CrossPlatformMesh.MAX_FRAME_SIZE + 1)
        )
        assertEquals(
            MeshFrameAction.DROP,
            CrossPlatformMesh.frameAction(CrossPlatformMesh.MAX_DRAINABLE_FRAME)
        )
    }

    /** A header that cannot be a length at all is still a desync, and still fatal. */
    @Test
    fun a_nonsense_length_still_resets_the_stream() {
        assertEquals(MeshFrameAction.RESET, CrossPlatformMesh.frameAction(0))
        assertEquals(MeshFrameAction.RESET, CrossPlatformMesh.frameAction(-1))          // 0xFFFFFFFF
        assertEquals(MeshFrameAction.RESET, CrossPlatformMesh.frameAction(Int.MIN_VALUE))
        assertEquals(MeshFrameAction.RESET, CrossPlatformMesh.frameAction(Int.MAX_VALUE))
    }

    @Test
    fun the_receive_loop_drains_an_oversize_frame_instead_of_breaking() {
        val body = bodyOf("private fun handleIncomingConnection(", "private fun cleanupDeadConnection(")
        assertTrue("the loop must decide via frameAction", body.contains("when (frameAction(length))"))
        assertTrue("DROP must skip the body", body.contains("input.skipBytes("))
        assertTrue("DROP must keep looping", body.contains("continue"))
        assertFalse(
            "the old 16 MiB cap must not come back",
            body.contains("16 * 1024 * 1024")
        )

        // …and specifically: the DROP arm contains no `break`.
        val dropArm = body.substringAfter("MeshFrameAction.DROP ->").substringBefore("MeshFrameAction.RESET ->")
        assertTrue("DROP arm not found", dropArm.isNotBlank())
        assertFalse("dropping a frame must not break the receive loop", dropArm.contains("break"))
    }

    // =====================================================================
    // 3. stop() forgets
    // =====================================================================

    @Test
    fun stop_clears_the_dedup_cache() {
        val body = bodyOf("fun stop() {", "fun sendMessage(")
        assertTrue(
            "stop() must drop seenMessageIds, as iOS does at CrossPlatformMesh.swift:297-301",
            body.contains("clearSeenMessageIds()")
        )
        // The neighbours it is supposed to sit with, so a future edit that removes the
        // whole reset block fails here too.
        assertTrue(body.contains("sentIdentityTo.clear()"))
    }

    @Test
    fun clearing_the_cache_really_empties_it() {
        // The cache itself is a plain in-memory set; `clearSeenMessageIds` and
        // `seenMessageIdCount` are the only doors into it that do not need a device.
        // Constructing the manager needs Hilt + a BluetoothAdapter, so what is proven
        // here is the collection semantics the `stop()` wiring above depends on:
        // a LinkedHashSet that has been cleared reports zero and re-admits an id it
        // had already seen.
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        assertTrue(seen.add("msg-1"))
        assertFalse("a second delivery of the same id is suppressed", seen.add("msg-1"))
        seen.clear()
        assertEquals(0, seen.size)
        assertTrue("after a restart the same id must be deliverable again", seen.add("msg-1"))
    }

    // =====================================================================
    // 4. The recipient key's SPELLING (__MESH_RECIPIENT_SPELLING_2026_10_12__)
    //
    // Reported 2026-10-12 by a user: "mesh messaging works from iOS to Android
    // but not from Android to iOS". `handleContentMessage` asked "is this frame
    // for me?" by comparing `recipient.trim().lowercase()` with
    // `mine.trim().lowercase()`, plus a first-32-character prefix fallback.
    // Neither half folds base64url into standard base64, and a wrong answer does
    // not park the frame — it goes to `relayToRecipient`, which has no route to
    // our own key and drops it without a trace.
    // =====================================================================

    /** One real X25519 key, in the three spellings it travels in. */
    private val keyStandard = "VFNouvPEe2Ca9UIsXPYcLM/Ti7kEmnrorgDeLDhKnw8="
    private val keyUrlPadded = "VFNouvPEe2Ca9UIsXPYcLM_Ti7kEmnrorgDeLDhKnw8="
    private val keyUrlBare = "VFNouvPEe2Ca9UIsXPYcLM_Ti7kEmnrorgDeLDhKnw8"

    /** A DIFFERENT key, so the fix cannot be "say yes to everything". */
    private val otherKey = "cyNYM+01CFBfwJuryISNkTsWE3jwXBi90bVTx+lZvuc="

    /** The reported failure, reproduced on the rule that caused it. */
    @Test
    fun d_the_old_lowercase_comparison_rejected_our_own_key() {
        // What the code used to compute. Kept as an executable record: if someone
        // reintroduces this comparison, the test below is the one that catches it.
        val theirs = keyUrlPadded.trim().lowercase()
        val mine = keyStandard.trim().lowercase()
        val prefixLen = minOf(mine.length, theirs.length, 32)
        val oldVerdict = theirs == mine ||
            (prefixLen >= 16 && theirs.take(prefixLen) == mine.take(prefixLen))
        assertFalse(
            "the old rule must be shown to fail, otherwise this whole section proves nothing",
            oldVerdict
        )
    }

    @Test
    fun d_a_base64url_recipient_is_still_us() {
        assertTrue(
            "an Android peer addressing us in base64url is addressing US",
            CrossPlatformMesh.isAddressedToUs(keyUrlPadded, keyStandard)
        )
        assertTrue(
            "unpadded base64url is the same key too",
            CrossPlatformMesh.isAddressedToUs(keyUrlBare, keyStandard)
        )
        assertTrue(
            "and the fold works in both directions",
            CrossPlatformMesh.isAddressedToUs(keyStandard, keyUrlBare)
        )
    }

    @Test
    fun d_identical_spellings_still_match() {
        assertTrue(CrossPlatformMesh.isAddressedToUs(keyStandard, keyStandard))
        assertTrue(CrossPlatformMesh.isAddressedToUs(keyUrlBare, keyUrlBare))
    }

    @Test
    fun d_an_empty_recipient_is_a_broadcast() {
        assertTrue(
            "broadcasts (PUBLIC_GROUP_AD, identity gossip) carry no recipient",
            CrossPlatformMesh.isAddressedToUs("", keyStandard)
        )
    }

    @Test
    fun d_someone_elses_message_is_still_relayed() {
        assertFalse(
            "a frame for another peer must keep going to the relay path",
            CrossPlatformMesh.isAddressedToUs(otherKey, keyStandard)
        )
        assertFalse(
            "and its base64url spelling must not match us either",
            CrossPlatformMesh.isAddressedToUs(otherKey.replace('+', '-').replace('/', '_'), keyStandard)
        )
    }

    @Test
    fun d_we_never_claim_a_frame_before_our_identity_is_set() {
        // `myPublicKey` is "" until `start(publicKey:)` runs. Claiming a frame then
        // would hand a stranger's ciphertext to our own decrypt path.
        assertFalse(CrossPlatformMesh.isAddressedToUs(keyStandard, ""))
    }

    @Test
    fun d_normalizer_folds_url_alphabet_and_padding() {
        assertEquals(
            CrossPlatformMesh.normalizeMeshKey(keyStandard),
            CrossPlatformMesh.normalizeMeshKey(keyUrlPadded)
        )
        assertEquals(
            CrossPlatformMesh.normalizeMeshKey(keyUrlBare),
            CrossPlatformMesh.normalizeMeshKey(keyUrlPadded)
        )
        assertFalse(
            "padding is dropped, not kept",
            CrossPlatformMesh.normalizeMeshKey(keyStandard).endsWith("=")
        )
        assertEquals(
            " surrounding whitespace is still trimmed",
            CrossPlatformMesh.normalizeMeshKey(keyStandard),
            CrossPlatformMesh.normalizeMeshKey("  \n" + keyUrlPadded + "\t ")
        )
    }

    /** The WIRING: the receive path must actually consult the rule above. */
    @Test
    fun d_handleContentMessage_uses_the_shared_rule() {
        val body = bodyOf("private fun handleContentMessage(", "private fun handleLocationMessage(")
        assertTrue(
            "the receive side must route its is-this-for-me question through isAddressedToUs",
            body.contains("isAddressedToUs(message.recipientPublicKey, myPublicKey)")
        )
        assertFalse(
            "the lowercase-only comparison must be gone from the receive path",
            body.contains(".lowercase()")
        )
    }

    /** And the send side must keep using the same single normalizer. */
    @Test
    fun d_send_side_lookups_share_one_normalizer() {
        assertTrue(
            "normalizeKey must delegate to normalizeMeshKey so there is ONE rule",
            source.contains("private fun normalizeKey(key: String): String = normalizeMeshKey(key)")
        )
    }

    // ========================================================================
    // __MESH_PARITY_READ_BOTH_SOURCES_2026_10_23__
    //
    // Everything above pins the ANDROID source against constants quoted from iOS
    // in a comment. A comment does not fail when iOS changes. These tests open
    // `OSHI/CrossPlatformMesh.swift` itself, the way `PollParityTest` opens the
    // Swift poll files, so a one-sided edit to either platform fails here
    // instead of silently costing a mesh link nobody can reproduce.
    // ========================================================================

    private val swift: String by lazy {
        val f = listOf(
            File("../OSHI/CrossPlatformMesh.swift"),
            File("../../OSHI/CrossPlatformMesh.swift"),
        ).firstOrNull { it.exists() }
        assertTrue("OSHI/CrossPlatformMesh.swift not found from ${File(".").absolutePath}", f != null)
        f!!.readText()
    }

    /**
     * The announce is the whole handshake: one READ-only characteristic holding
     * this device's LAN address and TCP port. Both sides parse the other's by
     * key, so a renamed or dropped key means the peer is discovered and never
     * dialled.
     */
    @Test
    fun e_announce_carries_the_same_keys_on_both_platforms() {
        for (key in listOf("pk", "name", "port", "platform", "ip")) {
            assertTrue(
                "the Android announce lost \"$key\"",
                Regex("""put\("$key",""").containsMatchIn(source)
            )
            assertTrue(
                "OSHI/CrossPlatformMesh.swift lost \"$key\" from createAdvertisementData",
                Regex(""""$key"\s*:""").containsMatchIn(swift)
            )
        }
        // `platform` is what each side uses to tell an iPhone from an Android
        // phone (`getIOSPeers` / `getAndroidPeers`); the two literals are a pair.
        assertTrue("Android must announce platform \"android\"", source.contains("\"platform\", \"android\""))
        assertTrue("iOS must announce platform \"ios\"", swift.contains("\"platform\": \"ios\""))
    }

    /**
     * The public key is truncated for BLE, and by the SAME amount: a receiver
     * matches the announced prefix against the keys it knows, so 32 against 43
     * would never match.
     */
    @Test
    fun e_announced_public_key_is_truncated_the_same() {
        // Anchored on the "pk" entry of the announce itself. Matching the first
        // truncation in the file finds a log line (`take(12)`), which is how the
        // first version of this test managed to fail while the code was correct.
        // __MESH_WIRE_SPELLING_FOR_OLD_IOS_2026_10_26__ the key is canonicalised
        // before it is truncated (a base64url prefix strands the iPhone's dial),
        // so the announce now reads `canonicalWireKey(myPublicKey).take(32)`.
        // The TRUNCATION is what this test pins, and it is unchanged.
        val android = Regex("""put\("pk",\s*(?:canonicalWireKey\()?myPublicKey\)?\.take\((\d+)\)""")
            .find(source)?.groupValues?.get(1)
        val ios = Regex(""""pk"\s*:\s*String\(myPublicKey\.prefix\((\d+)\)""")
            .find(swift)?.groupValues?.get(1)
        assertEquals("both sides must announce the same length of public key", ios, android)
        assertEquals("32", android)

        // The NSD/Bonjour service NAME carries a shorter prefix, and the two sides
        // also have to agree on that one: each uses it to recognise its OWN record
        // and skip it (`onServiceFound` / `didFind`).
        val andName = Regex("""SERVICE_NAME_PREFIX\$\{myPublicKey\.take\((\d+)\)\}""")
            .find(source)?.groupValues?.get(1)
        val iosName = Regex("""OSHI-\\\(myPublicKey\.prefix\((\d+)\)\)""")
            .find(swift)?.groupValues?.get(1)
        assertEquals("the service-name prefix length must match", iosName, andName)
        assertEquals("8", andName)
    }

    /**
     * Bonjour/NSD. The trailing dot is NOT cosmetic — `NsdManager` will not match
     * an iOS service published as `_oshi-mesh._tcp.` if we ask for
     * `_oshi-mesh._tcp`, and the failure is silent: discovery simply never calls
     * back, on the one transport that works when BLE does not.
     */
    @Test
    fun e_service_type_is_identical_including_the_trailing_dot() {
        val android = Regex("""SERVICE_TYPE\s*=\s*"([^"]+)"""").find(source)?.groupValues?.get(1)
        val ios = Regex("""BONJOUR_TYPE\s*=\s*"([^"]+)"""").find(swift)?.groupValues?.get(1)
        assertEquals("the Bonjour/NSD type must be byte-identical", ios, android)
        assertEquals("_oshi-mesh._tcp.", android)
        assertTrue("the trailing dot must stay", android!!.endsWith("."))
    }

    /** The 16-bit BLE UUIDs, as the two SDKs spell them. */
    @Test
    fun e_ble_uuids_agree() {
        assertTrue(
            "Android service UUID must stay 0541",
            source.contains("UUID.fromString(\"00000541-0000-1000-8000-00805F9B34FB\")")
        )
        assertTrue(
            "Android characteristic UUID must stay 0542",
            source.contains("UUID.fromString(\"00000542-0000-1000-8000-00805F9B34FB\")")
        )
        assertTrue("iOS service UUID must stay 0541", swift.contains("""CBUUID(string: "0541")"""))
        assertTrue("iOS characteristic UUID must stay 0542", swift.contains("""CBUUID(string: "0542")"""))
    }

    /**
     * __ONE_READ_MUST_SEE_ONE_DOCUMENT_2026_10_23__
     *
     * The announce is ~115 bytes and the default ATT MTU carries 22, so a long
     * read is the ordinary case: offset 0, then 22, then 44. Recomputing the
     * document per request splices two different JSONs when the Wi-Fi address or
     * the port changes mid-read, and the reassembled text parses as nothing.
     * Both sides must snapshot at offset 0 and serve the rest from that copy.
     */
    @Test
    fun e_long_reads_are_served_from_one_snapshot_on_both_platforms() {
        val android = bodyOf("override fun onCharacteristicReadRequest(", "private fun createAdvertisementData()")
        assertTrue(
            "Android must snapshot the announce at offset 0",
            android.contains("gattPayloadSnapshot = it") || android.contains("gattPayloadSnapshot =")
        )
        assertTrue(
            "Android must serve continuation reads from the snapshot",
            android.contains("gattPayloadSnapshot")
        )

        val start = swift.indexOf("didReceiveRead request: CBATTRequest")
        assertTrue("iOS didReceiveRead not found", start > 0)
        val ios = swift.substring(start, start + 1600)
        assertTrue(
            "OSHI/CrossPlatformMesh.swift must snapshot the announce at offset 0 — " +
                "recomputing it per read splices two documents into one reply",
            ios.contains("gattPayloadSnapshot")
        )
        assertTrue(
            "iOS must still refuse an offset past the end",
            ios.contains("invalidOffset")
        )
    }


    /**
     * __ANDROID_ANSWERS_READS_IN_BACKGROUND_2026_10_23__
     *
     * THE WIRING. `onCharacteristicReadRequest` computes the answer in OUR
     * process, so a cached (frozen) or killed process leaves the service
     * advertised and the read unanswered — and the advertisement is held by the
     * system Bluetooth stack, so nothing about it looks wrong from outside.
     *
     * Before this, the only caller of `MeshNetworkService` in the entire app was
     * `BootReceiver`: outside of a reboot the service never ran at all. The mesh's
     * start must claim it and its full stop must release it.
     */
    @Test
    fun e_the_mesh_holds_a_foreground_service_for_as_long_as_it_runs() {
        val manager = File("src/main/java/com/oshi/messenger/network/mesh/MeshNetworkManager.kt")
        assertTrue("not found: ${manager.absolutePath}", manager.exists())
        val text = manager.readText()

        val start = text.indexOf("private fun startCrossPlatformMesh()")
        assertTrue("startCrossPlatformMesh not found", start > 0)
        val startBody = text.substring(start, text.indexOf("\n    }", start))
        assertTrue(
            "starting the cross-platform mesh must hold the process up, or BLE reads are " +
                "only answered while the app is on screen",
            startBody.contains("MeshNetworkService.hold(")
        )
        assertTrue(
            "the hold must be gated on the foreground, through the policy",
            startBody.contains("AppPollState.isForeground")
        )

        val stop = text.indexOf("fun stopAll()")
        assertTrue("stopAll not found", stop > 0)
        val stopBody = text.substring(stop, text.indexOf("\n    }", stop))
        assertTrue(
            "a full mesh shutdown must give the process back",
            stopBody.contains("MeshNetworkService.release(")
        )
    }


    /**
     * The transport-level dispatcher. A type one side sends and the other does not
     * name is not an error anywhere: the receiver logs "Unknown message type" and
     * drops the frame, so the feature just does not happen and no test on either
     * platform alone goes red. That is exactly how every Android to iOS group join
     * was discarded until iOS added GROUP_UPDATE / GROUP_MESSAGE / PUBLIC_GROUP_AD.
     *
     * CALL_VIDEO is the one accepted asymmetry, and it is inert: Android's
     * `sendCallVideo` has no callers (mesh calls send CALL_AUDIO; video rides the
     * VPS WebSocket). If it ever gets a caller, add the iOS case FIRST.
     */
    @Test
    fun e_both_dispatchers_accept_the_same_types() {
        val android = Regex(""""([A-Z_]{4,})"""")
            .findAll(bodyOf("when (message.type) {", "else -> Log.d(TAG, \"Unknown message type"))
            .map { it.groupValues[1] }
            .toSet()

        // Comments must go FIRST on the iOS side too. The block above that switch
        // quotes both `default:` and `case "PUBLIC_GROUP_AD"`, so parsing the raw
        // text ended the region early AND invented a type out of prose — which is
        // how the first version of this test reported four iOS cases instead of
        // fourteen.
        val iosSwitch = swift.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        val iosStart = iosSwitch.indexOf("switch message.type {")
        assertTrue("the iOS dispatcher was not found", iosStart > 0)
        val iosEnd = iosSwitch.indexOf("default:", iosStart)
        assertTrue("the iOS dispatcher has no default branch", iosEnd > iosStart)
        val ios = Regex("""case\s+((?:"[A-Z_]{4,}"\s*,?\s*)+)""")
            .findAll(iosSwitch.substring(iosStart, iosEnd))
            .flatMap { Regex(""""([A-Z_]{4,})"""").findAll(it.groupValues[1]) }
            .map { it.groupValues[1] }
            .toSet()

        assertTrue("no types parsed out of the Android dispatcher", android.size >= 10)
        assertTrue("no types parsed out of the iOS dispatcher", ios.size >= 10)

        val knownOneSided = setOf("CALL_VIDEO")
        assertEquals(
            "Android accepts a type iOS drops (add the iOS case before shipping a sender)",
            emptySet<String>(), android - ios - knownOneSided
        )
        assertEquals(
            "iOS accepts a type Android drops — Android would discard those frames in silence",
            emptySet<String>(), ios - android
        )
    }


    /**
     * __A_SPLICED_READ_IS_RECOVERABLE_2026_10_23__
     *
     * Measured on a Galaxy A17 against a peer running a build from before the
     * announce was snapshotted: the read came back as two documents spliced
     * together and `JSONObject` threw. The old code logged it and disconnected,
     * so the peer was dropped — while `nc` to the ip and port inside that very
     * text was succeeding.
     *
     * A splice is a race, not corruption: it needs the peer to recompute its
     * announce between two reads of the same long read. Reading again usually
     * wins. What must NOT happen is salvaging fields out of the broken text —
     * half of one snapshot and half of another is how you dial a port that has
     * just closed.
     */
    @Test
    fun e_a_broken_announce_is_re_read_not_dropped() {
        assertTrue(
            "the parse site must still be the one this test is about",
            source.contains("Parse BLE data error") || source.contains("announce did not parse")
        )
        assertTrue(
            "a failed announce parse must re-read the characteristic",
            source.contains("gatt.readCharacteristic(characteristic)")
        )
        assertTrue(
            "the re-read must be bounded",
            source.contains("ANNOUNCE_READ_MAX_RETRIES")
        )
        val bound = Regex("""ANNOUNCE_READ_MAX_RETRIES\s*=\s*(\d+)""")
            .find(source)?.groupValues?.get(1)?.toInt()
        assertTrue("the bound must be small and present, was $bound", bound != null && bound in 1..3)
        // And the retry state must not leak: every exit from a GATT session
        // goes through safeGattDisconnect, so the cleanup belongs there.
        val disconnect = bodyOf("private fun safeGattDisconnect(", "private fun safeGattClose(")
        assertTrue(
            "safeGattDisconnect must drop the retry count for that device",
            disconnect.contains("announceReadRetries.remove(")
        )
    }

}
