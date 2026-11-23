package com.oshi.messenger.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.oshi.messenger.mail.MailDisplay
import com.oshi.messenger.mail.MailMime
import com.oshi.messenger.mail.OshiMailClient
import com.oshi.messenger.ui.screens.MailListRow
import com.oshi.messenger.ui.screens.MailRow
import com.oshi.messenger.ui.screens.MessageReader
import com.oshi.messenger.ui.theme.OSHITheme
import com.oshi.messenger.ui.viewmodel.MailViewModel
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * DEBUG BUILDS ONLY (src/debug). Renders raw RFC 822 fixtures through the REAL mail list
 * row and reader composables, so how a message looks can be checked on an emulator
 * without a mailbox, a server or a second device. Nothing is sent or stored.
 *
 *   adb push fixtures /data/local/tmp/mf; adb shell run-as com.oshi.messenger cp -r /data/local/tmp/mf files/mail-fixtures
 *   adb shell am start -n com.oshi.messenger/.debug.DebugMailFixtureActivity [--es open 02_newsletter.eml]
 *
 * Inbox-list mode: `files/mail-fixtures/inbox.jsonl` holds one server inbox item per line,
 * `{"id","receivedAt","seen","folder","deliveredTo","envelope":{…opened envelope JSON…}}`.
 * Each envelope goes through the real decoder (OshiMailClient.EnvelopeJson) and the real
 * list row (MailListRow), i.e. exactly what MailScreen renders after opening a sealed
 * envelope — no server, no key. Look options: --ez dark true, --ez rtl true, --ef font 1.6
 *
 *   adb shell am start -n com.oshi.messenger/.debug.DebugMailFixtureActivity --es mode inbox --ez dark true
 */
class DebugMailFixtureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, "mail-fixtures")
        val files = dir.listFiles { f -> f.name.endsWith(".eml") }?.sortedBy { it.name }.orEmpty()
        val initial = intent.getStringExtra("open")
        if (intent.getStringExtra("mode") == "inbox") {
            showInbox(File(dir, "inbox.jsonl"))
            return
        }
        setContent {
            OSHITheme {
                var open by remember { mutableStateOf<MailViewModel.OpenMessage?>(null) }
                remember(initial) {
                    files.firstOrNull { it.name == initial }?.let { open = load(it) }
                    0
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Text("Mail fixtures (debug)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                        LazyColumn {
                            itemsIndexed(files) { index, file ->
                                val mail = remember(file) { MailMime.parse(file.readBytes()) }
                                MailRow(
                                    name = MailMime.displayName(mail.from),
                                    address = mail.from,
                                    subject = mail.subject,
                                    snippet = mail.text,
                                    time = MailDisplay.listTime(iso(mail.date)),
                                    unread = index % 2 == 0,
                                    sealed = false,
                                    viaAlias = null,
                                    onClick = { open = load(file) },
                                )
                                Divider(Modifier.padding(start = 70.dp))
                            }
                        }
                    }
                }
                open?.let { message ->
                    MessageReader(
                        message = message, own = setOf("hugo@oshi-messenger.com"),
                        onDismiss = { open = null }, onDelete = { open = null }, onArchive = { open = null },
                        onRestore = { open = null }, onMarkUnread = { open = null },
                        onReply = { open = null }, onForward = { open = null },
                    )
                }
            }
        }
    }

    private fun showInbox(file: File) {
        val items = file.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map { line ->
            val o = JSONObject(line)
            OshiMailClient.Message(
                id = o.getString("id"),
                receivedAt = o.getString("receivedAt"),
                bytes = o.optLong("bytes"),
                seen = o.optBoolean("seen"),
                folder = o.optString("folder", "inbox"),
                deliveredTo = o.optString("deliveredTo", "hugo"),
                envelope = o.optJSONObject("envelope")?.let { OshiMailClient.EnvelopeJson.parseEnvelope(it.toString()) },
            )
        }
        val dark = intent.getBooleanExtra("dark", false)
        val rtl = intent.getBooleanExtra("rtl", false)
        val font = intent.getFloatExtra("font", 0f)
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides if (font > 0f) Density(base.density, font) else base,
            ) {
                OSHITheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            Text("Inbox envelopes (debug)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                            LazyColumn {
                                itemsIndexed(items) { _, message ->
                                    MailListRow(message, outgoing = message.folder == "sent", primaryLocalpart = "hugo", openedBody = null) {}
                                    Divider(Modifier.padding(start = 70.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun load(file: File): MailViewModel.OpenMessage =
        MailViewModel.openMessageOf(file.name, "inbox", MailMime.parse(file.readBytes()))

    private fun iso(date: String): String =
        runCatching { ZonedDateTime.parse(date.substringBefore(" (").trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }
            .getOrDefault(date)
}
