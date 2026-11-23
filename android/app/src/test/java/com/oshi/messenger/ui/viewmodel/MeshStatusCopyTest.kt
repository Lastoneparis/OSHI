package com.oshi.messenger.ui.viewmodel

import com.oshi.messenger.R
import com.oshi.messenger.network.mesh.BleRadioPolicy
import com.oshi.messenger.network.mesh.MeshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the Nearby Peers status card says, and whether it is true.
 *
 * The card is the only explanation a user ever gets for a mesh that is not
 * working. On the owner's own Galaxy A17 it read "Searching…" while every one
 * of BLUETOOTH_SCAN / _ADVERTISE / _CONNECT was denied and the app had never
 * once asked for them. Everything asserted here is a sentence that used to be
 * wrong, or an English literal in an app that ships in 34 locales.
 */
class MeshStatusCopyTest {

    /**
     * The Kotlin with every comment removed.
     *
     * The leak checks below are about CODE. The very sentences they forbid are
     * quoted in the KDoc above them — deliberately, since a comment naming the
     * exact string that used to ship is how the next person knows what was
     * wrong. A grep that cannot tell prose from a string literal would force
     * that documentation out of the file.
     */
    private fun codeOnly(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    private fun copy(
        status: MeshStatus,
        state: MeshState = MeshState.IDLE,
        connectedTotal: Int = 0,
        connectedCrossPlatform: Int = 0,
        connectedNearby: Int = 0,
        crossPlatformRunning: Boolean = true,
        bluetoothEnabled: Boolean = true,
        advertising: Boolean = true,
        scanning: Boolean = true,
        fault: BleRadioPolicy.RadioFault = BleRadioPolicy.RadioFault.NONE,
    ) = meshStatusCopy(
        status, state, connectedTotal, connectedCrossPlatform, connectedNearby,
        crossPlatformRunning, bluetoothEnabled, advertising, scanning, fault,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Nothing on this card is an English literal any more
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every status maps to a real string resource`() {
        for (status in MeshStatus.entries) {
            val c = copy(status)
            assertNotEquals("no title resource for $status", 0, c.titleRes)
            assertNotEquals("no subtitle resource for $status", 0, c.subtitleRes)
        }
    }

    @Test
    fun `PeersViewModel builds no user-facing sentence in Kotlin`() {
        // The previous pass shipped "Not visible to nearby devices — Bluetooth
        // permission needed" and four siblings as string literals inside the
        // ViewModel. Correct copy, in English, on a screen that exists in 34
        // languages.
        val source = codeOnly(File("src/main/java/com/oshi/messenger/ui/viewmodel/PeersViewModel.kt").readText())
        for (leaked in listOf(
            "Searching...",
            "Permission needed",
            "Peers Found",
            "Bluetooth permission needed",
            "Not visible to nearby devices",
            "Scanning via BLE & Bonjour",
            "Mesh network disabled",
            "Looking for nearby peers",
            "Connected to \$",
        )) {
            assertFalse(
                "PeersViewModel still builds \"$leaked\" in Kotlin — it must come from resources",
                source.contains(leaked),
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A denial is explained, and names the half that is down
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a mesh with no Bluetooth grant never says Searching`() {
        val c = copy(MeshStatus.PERMISSION_REQUIRED, advertising = false, scanning = false)
        assertEquals(R.string.peers_status_permission_required, c.titleRes)
        assertNotEquals(R.string.peers_status_searching, c.titleRes)
        assertEquals(R.string.peers_subtitle_permission_both, c.subtitleRes)
    }

    @Test
    fun `MUTE is called out separately from DEAF`() {
        // Scanning but not advertising is the asymmetry that actually bit: we
        // can see the iPhone, it can never see us, because iOS learns an Android
        // peer only by reading GATT characteristic 0542 — served only while we
        // advertise. Folding it into one generic "permission needed" throws away
        // the only information that tells the user which way round it is broken.
        val mute = copy(MeshStatus.PERMISSION_REQUIRED, advertising = false, scanning = true)
        val deaf = copy(MeshStatus.PERMISSION_REQUIRED, advertising = true, scanning = false)
        assertEquals(R.string.peers_subtitle_permission_advertise, mute.subtitleRes)
        assertEquals(R.string.peers_subtitle_permission_scan, deaf.subtitleRes)
        assertNotEquals(mute.subtitleRes, deaf.subtitleRes)
    }

    @Test
    fun `Bluetooth switched off is not reported as a permission problem`() {
        // Sending the user to a permission dialog they have already answered,
        // for a radio that is simply off, is the wrong ask.
        val c = copy(
            MeshStatus.PERMISSION_REQUIRED,
            bluetoothEnabled = false, advertising = false, scanning = false,
        )
        assertEquals(R.string.peers_subtitle_bluetooth_off, c.subtitleRes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A peer actually found outranks everything
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a live connection outranks a missing permission`() {
        // NSD/TCP over a shared Wi-Fi needs no Bluetooth at all.
        val c = copy(
            MeshStatus.CONNECTED,
            connectedTotal = 1, connectedCrossPlatform = 1,
            advertising = false, scanning = false,
        )
        assertEquals(R.string.mesh_status_connected, c.titleRes)
        assertEquals(R.string.peers_subtitle_connected_cross, c.subtitleRes)
        assertEquals(listOf<Any>(1), c.subtitleArgs)
    }

    @Test
    fun `a mixed fleet reports both halves with positional arguments`() {
        val c = copy(
            MeshStatus.CONNECTED,
            connectedTotal = 3, connectedCrossPlatform = 1, connectedNearby = 2,
        )
        assertEquals(R.string.peers_subtitle_connected_mixed, c.subtitleRes)
        assertEquals(listOf<Any>(3, 2, 1), c.subtitleArgs)
    }

    @Test
    fun `Android-only peers do not claim a cross-platform link`() {
        val c = copy(MeshStatus.CONNECTED, connectedTotal = 2, connectedNearby = 2)
        assertEquals(R.string.peers_subtitle_connected, c.subtitleRes)
        assertEquals(listOf<Any>(2), c.subtitleArgs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The radio refusing us is visible
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a radio failure the user can act on reaches the card`() {
        val busy = copy(
            MeshStatus.SEARCHING,
            advertising = false,
            fault = BleRadioPolicy.RadioFault.RESOURCE_EXHAUSTED,
        )
        assertEquals(R.string.peers_subtitle_ble_busy, busy.subtitleRes)

        val unsupported = copy(
            MeshStatus.SEARCHING,
            advertising = false,
            fault = BleRadioPolicy.RadioFault.UNSUPPORTED,
        )
        assertEquals(R.string.peers_subtitle_ble_unsupported, unsupported.subtitleRes)
    }

    @Test
    fun `our own duplicate start is never shown to anyone`() {
        // errorCode=1 from the Galaxy A17. Real, worth fixing, and none of the
        // user's business — the scan is running.
        assertFalse(BleRadioPolicy.RadioFault.DUPLICATE_START.userVisible)
        val c = copy(MeshStatus.SEARCHING, fault = BleRadioPolicy.RadioFault.DUPLICATE_START)
        assertEquals(R.string.peers_subtitle_searching_lan, c.subtitleRes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The searching copy tells the truth about what the mesh needs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a healthy mesh searching says Wi-Fi, not merely nearby`() {
        val c = copy(MeshStatus.SEARCHING)
        assertEquals(R.string.peers_subtitle_searching_lan, c.subtitleRes)

        // BLE carries no message data on either platform: both characteristics
        // are read-only and hand over an ip and a port, and every byte of mesh
        // traffic is TCP/IP. "Scanning via BLE & Bonjour…" left a user sitting
        // next to a friend on cellular with no way to know why nothing happened.
        val english = stringValue("values", "peers_subtitle_searching_lan")
        assertTrue(
            "the searching subtitle must name the actual requirement: a shared network",
            english!!.contains("Wi-Fi", ignoreCase = true),
        )
    }

    @Test
    fun `nothing running is Offline, not Searching`() {
        val c = copy(MeshStatus.OFFLINE, crossPlatformRunning = false, state = MeshState.IDLE)
        assertEquals(R.string.mesh_status_offline, c.titleRes)
        assertEquals(R.string.peers_subtitle_disabled, c.subtitleRes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 34 locales
    // ─────────────────────────────────────────────────────────────────────────

    private val newKeys = listOf(
        "peers_status_searching",
        "peers_status_peers_found",
        "peers_status_permission_required",
        "peers_subtitle_connected",
        "peers_subtitle_connected_cross",
        "peers_subtitle_connected_mixed",
        "peers_subtitle_bluetooth_off",
        "peers_subtitle_permission_both",
        "peers_subtitle_permission_advertise",
        "peers_subtitle_permission_scan",
        "peers_subtitle_ble_unsupported",
        "peers_subtitle_ble_busy",
        "peers_subtitle_ble_down",
        "peers_subtitle_searching_lan",
        "peers_subtitle_disabled",
        "peers_subtitle_looking",
        "peers_permission_card_title",
        "peers_permission_card_body",
        "peers_permission_card_action",
        "peers_permission_card_settings",
        "peers_permission_card_denied_body",
        "peers_same_wifi_note",
        "peers_control_visible",
        "peers_control_scan",
        "peers_control_auto",
        "peers_section_devices",
        "peers_action_connect",
        "peers_action_reconnect",
        "peers_info_reach_title",
        "peers_info_reach_desc",
        "peers_info_connection_desc",
        "peers_info_tip1",
        "peers_info_tip2",
        "peers_info_tip3",
    )

    private fun stringValue(dir: String, key: String): String? {
        val f = File("src/main/res/$dir/strings_mesh_polish.xml")
        if (!f.exists()) return null
        return Regex("""<string name="$key"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(f.readText())?.groupValues?.get(1)
    }

    private fun localeDirs(): List<String> =
        File("src/main/res").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values-") }
            ?.map { it.name }
            // values-night / values-v31 are configuration qualifiers, not locales.
            ?.filterNot { it.startsWith("values-night") || it == "values-v31" }
            ?.sorted()
            .orEmpty()

    @Test
    fun `every new key exists in every locale`() {
        val dirs = localeDirs()
        assertTrue("expected the full locale set, found ${dirs.size}", dirs.size >= 30)
        val gaps = mutableListOf<String>()
        for (dir in dirs) {
            for (key in newKeys) {
                if (stringValue(dir, key).isNullOrBlank()) gaps.add("$dir/$key")
            }
        }
        assertTrue("missing translations: $gaps", gaps.isEmpty())
    }

    @Test
    fun `no locale ships the English string verbatim for a sentence`() {
        // A locale file that exists but simply copies English is a translation
        // that was never done — indistinguishable from a done one until a user
        // reads it. Only the sentences are checked; single words legitimately
        // coincide across languages, and "Auto"/"Scan" genuinely do.
        val sentences = listOf(
            "peers_subtitle_searching_lan",
            "peers_permission_card_body",
            "peers_same_wifi_note",
            "peers_info_connection_desc",
        )
        val untranslated = mutableListOf<String>()
        for (dir in localeDirs()) {
            for (key in sentences) {
                val english = stringValue("values", key)
                val local = stringValue(dir, key) ?: continue
                if (local == english) untranslated.add("$dir/$key")
            }
        }
        assertTrue("still English: $untranslated", untranslated.isEmpty())
    }

    @Test
    fun `the counted subtitles keep their positional specifiers in every locale`() {
        // %1$d before %2$d is not guaranteed across languages — that is exactly
        // why they are positional — but every one of them has to be PRESENT, or
        // getString throws / prints a number that is not there.
        val arities = mapOf(
            "peers_subtitle_connected" to 1,
            "peers_subtitle_connected_cross" to 1,
            "peers_subtitle_connected_mixed" to 3,
        )
        for (dir in listOf("values") + localeDirs()) {
            for ((key, arity) in arities) {
                val value = stringValue(dir, key) ?: continue
                for (n in 1..arity) {
                    assertTrue(
                        "$dir/$key is missing %$n\$d",
                        value.contains("%$n\$d"),
                    )
                }
                val extra = Regex("""%(\d)\$[ds]""").findAll(value)
                    .map { it.groupValues[1].toInt() }
                    .filter { it > arity }
                    .toList()
                assertTrue("$dir/$key references arguments that are not passed: $extra", extra.isEmpty())
            }
        }
    }

    @Test
    fun `PeersScreen renders no bare English label`() {
        val source = codeOnly(File("src/main/java/com/oshi/messenger/ui/screens/PeersScreen.kt").readText())
        for (leaked in listOf(
            "text = \"Devices\"",
            "text = \"Reconnect\"",
            "text = \"Disconnected\"",
            "title = \"Visible\"",
            "title = \"Scan\"",
            "title = \"Auto\"",
            "label = \"Connected\"",
            "label = \"Nearby\"",
        )) {
            assertFalse("PeersScreen still hardcodes $leaked", source.contains(leaked))
        }
    }

    @Test
    fun `the mesh info sheet no longer claims a ten-kilometre radio mesh`() {
        // "Up to ~10km via multi-hop relay (200m per hop, max 50 hops)" and
        // "Uses Bluetooth LE and WiFi Direct for peer discovery and data
        // transfer" described a transport this app does not have. Both
        // characteristics are read-only on both platforms; they carry an ip and
        // a port and nothing else.
        val source = File("src/main/java/com/oshi/messenger/ui/screens/PeersScreen.kt").readText()
        assertFalse(source.contains("R.string.peers_mesh_info_range_desc"))
        assertFalse(source.contains("R.string.peers_mesh_info_connection_desc"))
        assertFalse(source.contains("R.string.peers_mesh_info_tip1"))
        assertFalse(source.contains("R.string.peers_mesh_info_tip3"))

        val reach = stringValue("values", "peers_info_reach_desc")!!
        assertFalse("the reach claim must not be a distance in kilometres", reach.contains("km"))
        val tip3 = stringValue("values", "peers_info_tip3")!!
        assertTrue(
            "the offline tip has to say mobile data is not enough",
            tip3.contains("mobile data", ignoreCase = true),
        )
    }
}
