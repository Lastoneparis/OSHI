package com.oshi.messenger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.mail.MailDisplay
import com.oshi.messenger.ui.screens.*
import com.oshi.messenger.ui.theme.OSHITheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Before/after screenshots of the mail screens with FIXTURE data — no account, no network,
 * no decryption. "before" reproduces the pre-redesign layout from the old MailScreen code;
 * "after" is assembled from the same components MailScreen now uses. PNGs land in the
 * app's external files dir (`mail-shots/`) for `adb pull`.
 *
 * Only runs when asked: `-e mailShots true`.
 */
@RunWith(AndroidJUnit4::class)
class MailScreenshots {

    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private data class Row(val name: String, val address: String, val subject: String, val snippet: String?, val time: String, val unread: Boolean, val sealed: Boolean = false, val alias: String? = null)

    private val rows = listOf(
        Row("Camille Laurent", "camille@proton.me", "Dinner on Friday?", "Let me know if 8pm works for you, I booked the place near the canal", "14:02", true),
        Row("GitHub", "noreply@github.com", "[oshi-desktop] Release installers v1.1.0 succeeded", "All jobs passed: linux, windows", "11:47", true),
        Row("Hugo", "hugo@oshi.email", "Contract draft v3", "Attached is the updated version with the changes we discussed", "09:15", false, alias = "work"),
        Row("Sealed for another device", "", "", null, "Yesterday", false, sealed = true),
        Row("Air France", "info@airfrance.fr", "Your boarding pass — AF1234 Paris → Nice", "Gate closes 20 minutes before departure", "22 Sept", false),
        Row("Banque Populaire", "noreply@bp.fr", "Relevé de compte septembre", null, "21 Sept", false),
    )

    private fun enabled() = InstrumentationRegistry.getArguments().getString("mailShots") == "true"

    private var current by androidx.compose.runtime.mutableStateOf<(@Composable () -> Unit)?>(null)
    private var darkNow by androidx.compose.runtime.mutableStateOf(false)
    private var hosted = false

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        if (!hosted) {
            rule.setContent {
                OSHITheme(darkTheme = darkNow) {
                    Surface(Modifier.fillMaxSize().testTag("shot"), color = MaterialTheme.colorScheme.background) { current?.invoke() }
                }
            }
            hosted = true
        }
        rule.runOnIdle { darkNow = dark; current = content }
        rule.waitForIdle()
        val bmp: Bitmap = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "mail-shots").apply { mkdirs() }
        File(dir, "$name${if (dark) "-dark" else ""}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- BEFORE (old layout)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BeforeList() {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Mail") }, navigationIcon = { IconButton({}) { Icon(Icons.Default.AccountCircle, null) } }, actions = { IconButton({}) { Icon(Icons.Default.Refresh, null) } }) },
            bottomBar = { NavigationBar { NavigationBarItem(true, {}, { Icon(Icons.Default.Email, null) }, label = { Text("Mail") }); NavigationBarItem(false, {}, { Icon(Icons.Default.Folder, null) }, label = { Text("Drive") }) } },
            floatingActionButton = { FloatingActionButton({}) { Icon(Icons.Default.Edit, null) } },
        ) { p ->
            Column(Modifier.padding(p)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("me@oshi.email", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = 0.12f, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("12.3 MB of 100 MB · 4/300 sent this month", style = MaterialTheme.typography.labelSmall)
                }
                ScrollableTabRow(selectedTabIndex = 0, edgePadding = 8.dp) {
                    listOf("Inbox (2)", "Archive (5)", "Drafts", "Sent (8)", "Trash").forEachIndexed { i, t -> Tab(i == 0, {}, text = { Text(t) }) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField("", {}, placeholder = { Text("Search sender or subject") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.weight(1f))
                    FilterChip(false, {}, label = { Text("Deep") })
                }
                LazyColumn {
                    rows.forEach { r ->
                        item {
                            ListItem(
                                headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (r.unread) FontWeight.Bold else FontWeight.Normal) },
                                supportingContent = { Text(if (r.sealed) "" else r.subject.ifEmpty { "(no subject)" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Text("2026-09-24", style = MaterialTheme.typography.labelSmall) },
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BeforeReader() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 28.dp)) {
                    Text("Dinner on Friday?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Camille Laurent <camille@proton.me>", style = MaterialTheme.typography.bodySmall)
                    Text("Wed, 24 Sep 2026 14:02:11 +0200", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton({}) { Text("Reply") }; TextButton({}) { Text("Archive") }; TextButton({}) { Text("Unread") }; TextButton({}) { Text("Delete") }
                    }
                    Divider()
                    Text(BODY, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyMedium)
                    AssistChip({}, label = { Text("menu.pdf · 184 KB") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) })
                }
            }
        }
    }

    // ---------------------------------------------------------------- AFTER (new components)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AfterList(empty: Boolean = false) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Column { Text("Inbox", style = MaterialTheme.typography.titleLarge); Text("me@oshi.email", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        actions = { IconButton({}) { Icon(Icons.Default.Refresh, null) }; IconButton({}) { MailAvatar("me", "me@oshi.email", size = 32) } },
                    )
                    TabRow(0) { Tab(true, {}, text = { Text("Mail") }, icon = { Icon(Icons.Default.Email, null) }); Tab(false, {}, text = { Text("Drive") }, icon = { Icon(Icons.Default.Folder, null) }) }
                }
            },
            floatingActionButton = { ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Default.Edit, null) }, text = { Text("Compose") }) },
        ) { p ->
            Column(Modifier.padding(p)) {
                MailSearchField("", false, {}, {})
                MailFolderChips("inbox", count = { mapOf("inbox" to 2, "archive" to 5, "sent" to 8)[it] ?: 0 }) {}
                if (empty) MailEmptyState(Icons.Outlined.Inbox, "Your inbox is empty", "New mail is sealed for this device and appears here.")
                else LazyColumn {
                    rows.forEach { r ->
                        item {
                            MailRow(r.name, r.address, r.subject, r.snippet, r.time, r.unread, r.sealed, r.alias) {}
                            Divider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AfterSwipe() {
        Column {
            Box(Modifier.height(96.dp)) {
                SwipeActionBackground(Icons.Outlined.Archive, "Archive", MaterialTheme.colorScheme.primary, alignEnd = false)
                Box(Modifier.offset(x = 140.dp)) { MailRow(rows[0].name, rows[0].address, rows[0].subject, rows[0].snippet, rows[0].time, true, false, null) {} }
            }
            Box(Modifier.height(96.dp)) {
                SwipeActionBackground(Icons.Outlined.Delete, "Trash", MaterialTheme.colorScheme.error, alignEnd = true)
                Box(Modifier.offset(x = (-140).dp)) { MailRow(rows[1].name, rows[1].address, rows[1].subject, rows[1].snippet, rows[1].time, true, false, null) {} }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AfterReader() {
        Scaffold(
            topBar = {
                TopAppBar(title = {}, navigationIcon = { IconButton({}) { Icon(Icons.Default.ArrowBack, null) } },
                    actions = { IconButton({}) { Icon(Icons.Outlined.Archive, null) }; IconButton({}) { Icon(Icons.Outlined.MarkEmailUnread, null) }; IconButton({}) { Icon(Icons.Outlined.Delete, null) } })
            },
            bottomBar = { ReaderActionBar(true, {}, {}, {}) },
        ) { p ->
            Column(Modifier.padding(p)) {
                ReaderHeader("Dinner on Friday?", "Camille Laurent <camille@proton.me>", "me@oshi.email, Hugo <hugo@oshi.email>", "Wed, 24 Sep 2026 14:02:11 +0200")
                Divider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text(BODY, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f))
                Text("2 attachments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentCard("menu.pdf", 188_416, {}); AttachmentCard("canal.jpg", 2_400_000, {})
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AfterCompose() {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Column { Text("Reply"); Text("Draft saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton({}) { Icon(Icons.Default.Close, null) } },
                actions = { IconButton({}) { Icon(Icons.Default.AttachFile, null) }; IconButton({}) { Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary) } },
            )
            RecipientField("camille@proton.me, hugo@oshi.email, jul", {})
            Divider()
            Text("Re: Dinner on Friday?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            Divider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { AttachmentCard("reservation.pdf", 96_000, {}, trailing = Icons.Default.Close) }
            Text("Perfect, 8pm works. See you there!", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
        }
    }

    @Test fun shots() {
        if (!enabled()) return
        for (dark in listOf(false, true)) {
            shoot("before-list", dark) { BeforeList() }
            shoot("before-reader", dark) { BeforeReader() }
            shoot("after-list", dark) { AfterList() }
            shoot("after-list-empty", dark) { AfterList(empty = true) }
            shoot("after-swipe", dark) { AfterSwipe() }
            shoot("after-reader", dark) { AfterReader() }
            shoot("after-compose", dark) { AfterCompose() }
        }
    }

    private companion object {
        const val BODY = "Hi!\n\nLet me know if 8pm works for you — I booked the place near the canal. " +
            "I attached the menu so you can pick something in advance.\n\nSee you,\nCamille"
    }
}
