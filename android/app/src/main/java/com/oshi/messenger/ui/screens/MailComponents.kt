package com.oshi.messenger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import com.oshi.messenger.ui.theme.OshiFilterChip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oshi.messenger.mail.MailDisplay
import com.oshi.messenger.mail.MailMime
import com.oshi.messenger.mail.OshiMailClient

/*
 * Stateless building blocks of the mail screens. They take plain values and callbacks —
 * no ViewModel — so MailScreen wires them to data and the screenshot test renders them
 * with fixtures. Strings are English literals, like the rest of MailScreen.
 */

/** Folder id → label + icon, in the order the chips show them. */
internal val MAIL_FOLDERS: List<Triple<String, String, ImageVector>> = listOf(
    Triple("inbox", "Inbox", Icons.Outlined.Inbox),
    Triple("archive", "Archive", Icons.Outlined.Archive),
    Triple("draft", "Drafts", Icons.Outlined.Drafts),
    Triple("sent", "Sent", Icons.Outlined.Send),
    Triple("trash", "Trash", Icons.Outlined.Delete),
)

@Composable
internal fun MailAvatar(name: String, address: String, size: Int = 40, sealed: Boolean = false) {
    val hue = MailDisplay.avatarHue(address)
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val bg = if (sealed) MaterialTheme.colorScheme.surfaceVariant
    else Color.hsl(hue, 0.45f, if (dark) 0.32f else 0.78f)
    val fg = if (sealed) MaterialTheme.colorScheme.onSurfaceVariant
    else Color.hsl(hue, 0.55f, if (dark) 0.88f else 0.25f)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (sealed) Icon(Icons.Default.Lock, contentDescription = null, tint = fg, modifier = Modifier.size((size / 2).dp))
        else Text(
            MailDisplay.initial(name), color = fg, fontWeight = FontWeight.SemiBold,
            style = if (size >= 40) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

/**
 * The mailbox list's row for one server message: maps the decoded envelope onto [MailRow].
 * Shared by MailScreen and the debug fixture harness, so what the harness screenshots is
 * exactly what the inbox renders.
 */
@Composable
internal fun MailListRow(
    message: OshiMailClient.Message,
    outgoing: Boolean,
    primaryLocalpart: String?,
    openedBody: String?,
    onClick: () -> Unit,
) {
    val envelope = message.envelope
    val party = envelope?.let { if (outgoing) it.to else it.from }.orEmpty()
    MailRow(
        name = if (envelope == null) "Sealed for another device" else MailMime.displayName(party),
        address = party,
        subject = envelope?.subject.orEmpty(),
        snippet = MailDisplay.listSnippet(envelope?.snippet, openedBody),
        time = MailDisplay.listTime(message.receivedAt),
        unread = !message.seen && message.folder == "inbox",
        sealed = envelope == null,
        viaAlias = if (message.folder == "inbox" && primaryLocalpart != null && message.deliveredTo != primaryLocalpart) message.deliveredTo else null,
        attachmentCount = envelope?.attachmentCount ?: 0,
        hasImages = envelope?.hasImages ?: false,
        onClick = onClick,
    )
}

/** One conversation row: avatar, sender, time, subject, snippet, state markers. */
@Composable
internal fun MailRow(
    name: String,
    address: String,
    subject: String,
    snippet: String?,
    time: String,
    unread: Boolean,
    sealed: Boolean,
    viaAlias: String?,
    attachmentCount: Int = 0,
    hasImages: Boolean = false,
    onClick: () -> Unit,
) {
    val markers = MailDisplay.attachmentDescription(attachmentCount, hasImages)
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open message", onClick = onClick)
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    if (unread) append("Unread. ")
                    append("From $name. ")
                    append(subject.ifEmpty { "No subject" })
                    if (!snippet.isNullOrBlank()) append(". ").append(snippet)
                    if (markers != null) append(". ").append(markers)
                    append(". ").append(time)
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        MailAvatar(name, address, sealed = sealed)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = weight,
                    color = if (sealed) subtle else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                // Attachment / image markers sit right before the date, like every mail app.
                // Sized from the label's line height so they scale with the font setting.
                if (hasImages) {
                    Icon(
                        Icons.Outlined.Image, contentDescription = null, tint = subtle,
                        modifier = Modifier.size(with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (attachmentCount > 0) {
                    Icon(
                        Icons.Outlined.AttachFile, contentDescription = null, tint = subtle,
                        modifier = Modifier.size(with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    time, style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else subtle,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (sealed) "Can't be opened on this device" else subject.ifEmpty { "(no subject)" },
                    Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = weight,
                    color = if (sealed) subtle else MaterialTheme.colorScheme.onSurface,
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
            if (!snippet.isNullOrBlank()) {
                Text(
                    snippet.replace(Regex("\\s+"), " ").trim(),
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = subtle,
                )
            }
            if (viaAlias != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Outlined.AlternateEmail, null, Modifier.size(14.dp), tint = subtle)
                    Spacer(Modifier.width(4.dp))
                    Text("via $viaAlias", style = MaterialTheme.typography.labelSmall, color = subtle)
                }
            }
        }
    }
}

/** Folder switcher: a scrollable row of chips with counts (unread in Inbox, total elsewhere). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailFolderChips(selected: String, count: (String) -> Int, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MAIL_FOLDERS.forEach { (id, label, icon) ->
            val n = count(id)
            OshiFilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(if (n > 0) "$label  $n" else label) },
                leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}

/** Pill search field with the "Deep" (search opened bodies) toggle inside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailSearchField(query: String, deep: Boolean, onQuery: (String) -> Unit, onToggleDeep: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search mail") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                }
                OshiFilterChip(
                    selected = deep, onClick = onToggleDeep,
                    label = { Text("Deep") },
                    modifier = Modifier.padding(end = 8.dp).semantics {
                        contentDescription = if (deep) "Deep search on: also searches opened messages" else "Deep search off"
                    },
                )
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Centered illustration + title + one line, for empty, no-result and sealed states. */
@Composable
internal fun MailEmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

/** Swipe background: the action revealed under a row as it slides. */
@Composable
internal fun SwipeActionBackground(icon: ImageVector, label: String, color: Color, alignEnd: Boolean) {
    Box(
        Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Reader header: avatar, sender, date; tap to reveal full From / To / Date. */
@Composable
internal fun ReaderHeader(subject: String, from: String, to: String, date: String) {
    var expanded by remember { mutableStateOf(false) }
    val name = com.oshi.messenger.mail.MailMime.displayName(from)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SelectionContainer {
            Text(subject, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MailAvatar(name, from, size = 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (to.isNotBlank()) "to ${com.oshi.messenger.mail.MailMime.displayName(MailDisplay.splitRecipients(to).firstOrNull() ?: to)}" +
                        (if (MailDisplay.splitRecipients(to).size > 1) " +${MailDisplay.splitRecipients(to).size - 1}" else "")
                    else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DetailLine("From", from)
                        if (to.isNotBlank()) DetailLine("To", to)
                        if (date.isNotBlank()) DetailLine("Date", date)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("Decrypted on this device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** An attachment as a tappable card: type icon, name, size, and a save glyph. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentCard(
    filename: String,
    bytes: Long,
    onClick: () -> Unit,
    trailing: ImageVector = Icons.Outlined.FileDownload,
    /** When set, [trailing] is its own button (e.g. Save) and the card itself opens. */
    onTrailing: (() -> Unit)? = null,
    trailingLabel: String? = null,
) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(iconFor(filename), null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(filename, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(MailDisplay.size(bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onTrailing != null) {
                IconButton(onClick = onTrailing) {
                    Icon(trailing, contentDescription = trailingLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun iconFor(filename: String): ImageVector = when (filename.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "heic", "webp" -> Icons.Outlined.Image
    "pdf" -> Icons.Outlined.PictureAsPdf
    "ics", "vcs" -> Icons.Outlined.Event
    "zip", "gz", "7z", "rar" -> Icons.Outlined.FolderZip
    "mp3", "m4a", "wav", "ogg", "opus" -> Icons.Outlined.AudioFile
    "mp4", "mov", "mkv", "webm" -> Icons.Outlined.VideoFile
    else -> Icons.Outlined.Description
}

/**
 * The reader's bottom action bar: Reply, Reply all, Forward. Icon-over-label buttons of
 * equal width, so the labels never truncate on a phone or at a large font scale; each is
 * at least 56dp tall (above the 48dp touch minimum).
 */
@Composable
internal fun ReaderActionBar(showReplyAll: Boolean, onReply: () -> Unit, onReplyAll: () -> Unit, onForward: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        // navigationBarsPadding: on Android 15+ the dialog window draws under the gesture
        // bar, which covered the "Reply"/"Forward" labels (seen on the API 36 emulator).
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            ReaderAction(Icons.Default.Reply, "Reply", primary = true, onClick = onReply)
            if (showReplyAll) ReaderAction(Icons.Default.ReplyAll, "Reply all", onClick = onReplyAll)
            ReaderAction(Icons.Default.Forward, "Forward", onClick = onForward)
        }
    }
}

@Composable
private fun RowScope.ReaderAction(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = label, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The "To" field as chips: each committed address is a chip with a remove button, and the
 * text box after them takes the next one (committed on comma, space, semicolon or Enter).
 * [value] stays the plain comma-joined string the composer already saves and sends.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun RecipientField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "To",
) {
    val committed = MailDisplay.splitRecipients(value.substringBeforeLast(',', ""))
    val pending = if (value.contains(',')) value.substringAfterLast(',').trimStart() else value
    fun rebuild(list: List<String>, tail: String) =
        onValueChange(if (list.isEmpty()) tail else list.joinToString(", ") + ", " + tail)

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            committed.forEachIndexed { index, address ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    avatar = { MailAvatar(address, address, size = 24) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close, contentDescription = "Remove $address",
                            modifier = Modifier.size(18.dp).clickable { rebuild(committed.filterIndexed { i, _ -> i != index }, pending) },
                        )
                    },
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = pending,
                onValueChange = { typed ->
                    val last = typed.lastOrNull()
                    if (last == ',' || last == ';' || last == ' ') {
                        val address = typed.dropLast(1).trim()
                        if (address.isNotEmpty()) rebuild(committed + address, "") else rebuild(committed, "")
                    } else rebuild(committed, typed)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                ),
                modifier = Modifier.widthIn(min = 120.dp).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = "$label recipients" },
            )
        }
    }
}

/**
 * Makes the full-screen Dialog that hosts it really cover the screen, edge to edge, so the
 * content can pad itself with `systemBarsPadding()`.
 *
 * With targetSdk 36 on Android 15+, a Compose Dialog with `usePlatformDefaultWidth =
 * false` was placed BELOW the status bar yet kept the full screen height, so its bottom
 * edge hung off the screen by the status-bar height: the reader's Reply / Forward labels
 * were cut in half by the screen edge (seen on the API 36 emulator, 2026-11-16).
 */
@Composable
internal fun EdgeToEdgeDialogWindow(lightBars: Boolean = !MaterialTheme.colorScheme.background.luminanceIsDark()) {
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
}
