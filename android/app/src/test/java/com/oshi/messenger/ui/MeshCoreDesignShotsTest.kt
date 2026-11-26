package com.oshi.messenger.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.service.lora.MeshCore
import com.oshi.messenger.service.lora.MeshCoreManager
import com.oshi.messenger.service.lora.MeshCoreThread
import com.oshi.messenger.ui.screens.ConversationItem
import com.oshi.messenger.ui.screens.JoinChannelFields
import com.oshi.messenger.ui.screens.LoRaSection
import com.oshi.messenger.ui.screens.LocalMeshCoreBubbles
import com.oshi.messenger.ui.screens.MeshCoreBubbleState
import com.oshi.messenger.ui.screens.MeshCoreComposerNote
import com.oshi.messenger.ui.screens.MeshCoreDeviceRow
import com.oshi.messenger.ui.screens.MeshCorePinExplainer
import com.oshi.messenger.ui.screens.MeshCoreSectionActions
import com.oshi.messenger.ui.screens.MeshCoreSectionContent
import com.oshi.messenger.ui.screens.MeshCoreSectionUi
import com.oshi.messenger.ui.screens.MeshCoreThreadSubtitle
import com.oshi.messenger.ui.screens.MeshCoreUi
import com.oshi.messenger.ui.screens.MessageBubble
import com.oshi.messenger.ui.theme.OSHITheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * __MESHCORE_INTEROP_2026_11_25__ The MeshCore screens rendered to PNG for design review (opt-in, like
 * DesignShotsTest): `./gradlew :app:testDebugUnitTest --tests '*MeshCoreDesignShotsTest' -Pshots=<dir>`.
 *
 * Settings are the real [MeshCoreSectionContent]. Threads are assembled from the real pieces ChatScreen uses
 * (MessageBubble with its meta row and ticks, MeshCoreThreadSubtitle, MeshCoreComposerNote): ChatScreen itself
 * needs a Hilt view-model. Dialog bodies are drawn in a card because a Dialog is a separate window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class MeshCoreDesignShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val dir = System.getProperty("design.shots")
        assumeTrue("design shots are opt-in (-Pshots=<dir>)", dir != null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OSHITheme(darkTheme = dark) {
                // A Surface, as the app's Scaffold is: it sets the content colour that untinted text uses.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box { content() } }
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ─────────────────────────── Data ───────────────────────────

    private val now = 1_764_072_000_000L
    private val keyB = ByteArray(32) { (0x26 + it).toByte() }
    private val keyC = ByteArray(32) { (0x51 + it).toByte() }
    private fun contact(key: ByteArray, name: String, type: Int = MeshCore.ADV_TYPE_CHAT) =
        MeshCore.Contact(key, type, 0, name, now / 1000, now / 1000)
    private val contacts = listOf(contact(keyB, "Lena Alpine"), contact(keyC, "Base camp"), contact(ByteArray(32) { 9 }, "Hilltop RPT", MeshCore.ADV_TYPE_REPEATER))
    private val channels = listOf(
        MeshCore.ChannelInfo(0, "Public", MeshCore.PUBLIC_CHANNEL_SECRET),
        MeshCore.ChannelInfo(2, "#grenoble", MeshCore.hashtagSecret("#grenoble")),
        MeshCore.ChannelInfo(3, "Rope team", ByteArray(16) { 7 }),
    )
    private val connected = MeshCoreSectionUi(
        state = MeshCoreManager.ConnState.READY, radioName = "MeshCore-OSHI-TEA-MC", paired = true,
        chatReady = true, bridgeReady = true, selfName = "OSHI-TEA-MC", channels = channels, contacts = contacts,
        heardAt = mapOf(MeshCore.hex(keyB) to now - 4 * 60_000L), now = now,
    )

    private fun settings(ui: MeshCoreSectionUi) = @Composable {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(top = 24.dp)) {
            MeshCoreSectionContent(ui, MeshCoreSectionActions())
        }
    }

    // ─────────────────────────── Settings ───────────────────────────

    @Test fun scanLight() = shot("meshcore_01_scan_found_light", false) { scan() }
    @Test fun scanDark() = shot("meshcore_01_scan_found_dark", true) { scan() }

    @Composable private fun scan() {
        Column(Modifier.padding(16.dp).padding(top = 24.dp)) {
            LoRaSection(header = stringResource(R.string.meshtastic_nearby)) {
                MeshCoreDeviceRow(MeshCoreManager.Device("A", "MeshCore-OSHI-TEA-MC", -58)) {}
                MeshCoreDeviceRow(MeshCoreManager.Device("B", "MeshCore-Lena", -91)) {}
            }
            Spacer(Modifier.height(24.dp))
            DialogCard(stringResource(R.string.meshcore_pin_title, "MeshCore-OSHI-TEA-MC"), stringResource(R.string.meshcore_pin_continue)) {
                MeshCorePinExplainer()
            }
        }
    }

    @Test fun pinLight() = shot("meshcore_02_pin_waiting_light", false, settings(MeshCoreSectionUi(
        state = MeshCoreManager.ConnState.CONFIGURING, radioName = "MeshCore-OSHI-TEA-MC", awaitingPin = true)))
    @Test fun pinDark() = shot("meshcore_02_pin_waiting_dark", true, settings(MeshCoreSectionUi(
        state = MeshCoreManager.ConnState.CONFIGURING, radioName = "MeshCore-OSHI-TEA-MC", awaitingPin = true)))
    @Test fun pinFailedLight() = shot("meshcore_03_pairing_failed_light", false, settings(MeshCoreSectionUi(
        error = MeshCoreManager.ERROR_PAIRING_FAILED, ownScanList = true)))
    @Test fun pinFailedDark() = shot("meshcore_03_pairing_failed_dark", true, settings(MeshCoreSectionUi(
        error = MeshCoreManager.ERROR_PAIRING_FAILED, ownScanList = true)))
    @Test fun connectedLight() = shot("meshcore_04_settings_connected_light", false, settings(connected))
    @Test fun connectedDark() = shot("meshcore_04_settings_connected_dark", true, settings(connected))
    @Test fun emptyLight() = shot("meshcore_05_settings_no_contacts_light", false, settings(connected.copy(contacts = emptyList(), heardAt = emptyMap())))
    @Test fun emptyDark() = shot("meshcore_05_settings_no_contacts_dark", true, settings(connected.copy(contacts = emptyList(), heardAt = emptyMap())))
    private val lost = MeshCoreSectionUi(radioName = "MeshCore-OSHI-TEA-MC", paired = true, error = MeshCoreManager.ERROR_CONNECTION_LOST)
    @Test fun lostLight() = shot("meshcore_06_connection_lost_light", false, settings(lost))
    @Test fun lostDark() = shot("meshcore_06_connection_lost_dark", true, settings(lost))

    @Test fun joinLight() = shot("meshcore_07_join_channel_light", false) { join() }
    @Test fun joinDark() = shot("meshcore_07_join_channel_dark", true) { join() }

    @Composable private fun join() {
        Column(Modifier.padding(16.dp).padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            DialogCard(stringResource(R.string.meshcore_join_hashtag), stringResource(R.string.meshcore_join)) {
                JoinChannelFields(true, "#grenoble", {}, "", {}, null)
            }
            DialogCard(stringResource(R.string.meshcore_join_private), stringResource(R.string.meshcore_join)) {
                JoinChannelFields(false, "Rope team", {}, "abcd", {}, stringResource(R.string.meshcore_join_bad_secret))
            }
        }
    }

    // ─────────────────────────── Chats ───────────────────────────

    private fun msg(id: String, key: String, text: String, fromMe: Boolean, minutes: Int, delivered: Boolean = !fromMe, failed: Boolean = false) =
        Message(
            id = id, senderPublicKey = if (fromMe) "me" else key, recipientPublicKey = if (fromMe) key else "me",
            content = text, timestamp = now - minutes * 60_000L, isFromMe = fromMe, isDelivered = delivered, isFailed = failed,
            deliveryMethod = DeliveryMethod.LORA_PLAINTEXT,
        )

    @Test fun listLight() = shot("meshcore_08_chat_list_light", false) { list() }
    @Test fun listDark() = shot("meshcore_08_chat_list_dark", true) { list() }

    @Composable private fun list() {
        val dm = MeshCoreThread.contactKey(keyB)
        val rows = listOf(
            Conversation(dm, "Lena Alpine", null, msg("1", dm, "On est au refuge, tout va bien", false, 3), 2),
            Conversation("mcchan!#grenoble", "#grenoble", null, msg("2", "mcchan!#grenoble", "Base camp: route ouverte jusqu'au col", false, 12), 0),
            Conversation("mcchan!Public", "Public", null, msg("3", "mcchan!Public", "anyone on 869.525?", false, 40), 0),
            Conversation("AAAAC3NzaC1lZDI1NTE5AAAAIOSHIcontactkey000000000000=", "Camille", null,
                msg("4", "AAAA", "À ce soir", true, 90).copy(deliveryMethod = DeliveryMethod.VPS, isDelivered = true), 0),
        )
        Column(Modifier.padding(top = 40.dp)) {
            rows.forEach { ConversationItem(conversation = it, now = now, onClick = {}) }
        }
    }

    @Test fun dmLight() = shot("meshcore_09_dm_thread_light", false) { dm(radioUp = true) }
    @Test fun dmDark() = shot("meshcore_09_dm_thread_dark", true) { dm(radioUp = true) }
    @Test fun dmOfflineLight() = shot("meshcore_10_dm_radio_offline_light", false) { dm(radioUp = false) }
    @Test fun dmOfflineDark() = shot("meshcore_10_dm_radio_offline_dark", true) { dm(radioUp = false) }

    @Composable private fun dm(radioUp: Boolean) {
        val key = MeshCoreThread.contactKey(keyB)
        val typed = "We are leaving the hut at six tomorrow morning, weather permitting, and should reach the col by about ten. Keep the radio on."
        Thread("Lena Alpine", MeshCoreBubbleState(pending = setOf("s1"), radioUp = radioUp), listOf(
            msg("r1", key, "Are you at the hut yet?", false, 30),
            msg("d1", key, "Yes, all good. Battery 60 %.", true, 28, delivered = true),
            msg("t1", key, "Weather looks fine for tomorrow", true, 10, delivered = false),
            msg("f1", key, "Can you hear me?", true, 6, delivered = false, failed = true),
            msg("s1", key, "Sending this one now", true, 0, delivered = false),
        ), MeshCoreUi.threadNote(key, channels), MeshCoreUi.composerCount(typed, false, "OSHI-TEA-MC"), radioUp, typed)
    }

    @Test fun channelLight() = shot("meshcore_11_channel_thread_light", false) { channel() }
    @Test fun channelDark() = shot("meshcore_11_channel_thread_dark", true) { channel() }

    @Composable private fun channel() {
        val key = "mcchan!#grenoble"
        val typed = "Le refuge du col est ouvert ce soir, il reste six places et du bois pour le poêle. Qui monte avec nous demain matin ? On part à 7 h du parking."
        Thread("#grenoble", MeshCoreBubbleState(radioUp = true), listOf(
            msg("c1", key, "Base camp: route ouverte jusqu'au col", false, 12),
            msg("c2", key, "Lena Alpine: on y est dans 20 min", false, 8),
            msg("c3", key, "Super, on vous attend au refuge", true, 5, delivered = false),
        ), MeshCoreUi.threadNote(key, channels), MeshCoreUi.composerCount(typed, true, "OSHI-TEA-MC"), true, typed, channel = true)
    }

    /** A chat thread as ChatScreen lays it out: header, bubbles, the MeshCore note and the composer. */
    @Composable
    private fun Thread(
        title: String, state: MeshCoreBubbleState, messages: List<Message>, note: Int,
        count: MeshCoreUi.ComposerCount, radioUp: Boolean, typed: String, channel: Boolean = false,
    ) {
        CompositionLocalProvider(LocalMeshCoreBubbles provides state) {
            Column(Modifier.fillMaxSize()) {
                Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 36.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text(title.take(2).uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            MeshCoreThreadSubtitle()
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.Bottom) {
                    messages.forEach { m ->
                        val speaker = if (channel && !m.isFromMe) MeshCoreUi.channelSpeaker(m.content) else null
                        MessageBubble(
                            message = if (speaker?.first != null) m.copy(content = speaker.second) else m,
                            senderLabel = speaker?.first,
                            onRetry = {},
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                MeshCoreComposerNote(note = note, count = count, radioConnected = radioUp, onConnect = {})
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    OutlinedTextField(value = typed, onValueChange = {}, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                        shape = RoundedCornerShape(24.dp), placeholder = { Text("Message") })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun DialogCard(title: String, confirm: String, body: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                body()
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {}) { Text(stringResource(R.string.lora_cancel)) }
                    TextButton(onClick = {}) { Text(confirm, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
