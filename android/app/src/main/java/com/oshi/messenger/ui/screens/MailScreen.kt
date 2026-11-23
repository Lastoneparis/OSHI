package com.oshi.messenger.ui.screens

import android.net.Uri
import com.oshi.messenger.ui.theme.OshiDropdownMenu
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshi.messenger.mail.MailDisplay
import com.oshi.messenger.mail.MailMime
import com.oshi.messenger.mail.OshiMailClient
import com.oshi.messenger.ui.viewmodel.MailViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mail on Android.
 *
 * Everything on screen was decrypted on this device — the subjects in the list arrived as
 * sealed envelopes. That is why empty and failure states talk about THIS DEVICE: on a
 * zero-access mailbox, "cannot open" means the wrong device, not the wrong password.
 *
 * Layout (2026-09-24 redesign): Mail/Drive tabs under the title (the app's own bottom bar
 * is already below this screen, so a second NavigationBar here stacked two bars), folder
 * chips, a pill search field, avatar rows with swipe actions and pull-to-refresh. The
 * reader and composer are FULL-SCREEN DIALOGS rather than bottom sheets: a dialog window
 * fits the system bars, so their action bars can never sit under the navigation bar
 * (the call-screen bug on the Samsung). Bodies: see MailReaderBody.kt — HTML in a WebView
 * with no JavaScript and no network unless the reader loads remote images.
 */
/** What the composer opens with: a blank message, a reply, a forward or an existing draft. */
private data class ComposeInit(
    val draftId: String?,
    val to: String,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val attachments: List<OshiMailClient.Attachment> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    /**
     * __MAILBOX_HAD_NO_WAY_OUT_2026_11_06__ Pops the mailbox off the back stack.
     *
     * Required, with no default: a `= {}` default here would be the same shape
     * as the bug this parameter exists to fix — an affordance that looks like a
     * way out and silently isn't. (On iOS the mailbox carried a leading cross
     * wired to a `dismiss` that had nothing to dismiss; the owner reported it
     * as "useless". Checking Android turned up the mirror image: `"mail"` is in
     * neither BOTTOM_NAV_ROUTES nor BOTTOM_BAR_SECONDARY_ROUTES, so the bar is
     * hidden here, and the TopAppBar declared no navigationIcon — the system
     * back gesture was the only exit, on a screen whose own sibling routes
     * `groups`/`peers`/`botPortal` are explicitly listed so the user "can leave
     * them without hunting for the back arrow".)
     */
    onBack: () -> Unit,
    viewModel: MailViewModel = hiltViewModel(),
    /** When set (a mail-notification deep link), open this message once the mailbox is ready. */
    openMessageId: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val editingDraft by viewModel.editingDraft.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()

    // Deep-link from a mail-notification tap: open the specific message exactly once,
    // as soon as the mailbox has loaded. openMessage() fetches by id directly, so it
    // works even for a freshly-arrived message not yet in the list.
    var handledOpenId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openMessageId, state.hasMailbox, state.loading) {
        val id = openMessageId
        if (!id.isNullOrEmpty() && id != handledOpenId && state.hasMailbox && !state.loading) {
            handledOpenId = id
            viewModel.openMessage(id)
        }
    }

    var tab by rememberSaveable { mutableStateOf(0) }         // 0 = mail, 1 = drive
    var composeInit by remember { mutableStateOf<ComposeInit?>(null) }
    var showAccount by rememberSaveable { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }

    // A tapped draft decrypts asynchronously in the ViewModel; once it lands here, open it.
    LaunchedEffect(editingDraft) {
        editingDraft?.let { composeInit = ComposeInit(it.id.ifEmpty { null }, it.to, it.subject, it.body, null) }
    }

    // Both early returns bypass the Scaffold above, so they bypassed its top bar
    // too — a spinner and a sign-up form, each with the bottom bar hidden and no
    // visible exit. The address form is the worse of the two: a user who opens
    // the mailbox out of curiosity landed on a name-your-mailbox field with
    // nothing to tap to back out.
    if (state.loading) {
        MailBackdrop(title = "Mail", onBack = onBack) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }

    if (!state.hasMailbox) {
        MailBackdrop(title = "Mail", onBack = onBack) { MailSetup(viewModel) }
        return
    }

    val own = remember(state.account) {
        buildSet { state.account?.address?.let(::add); state.account?.aliases?.forEach(::add) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // The app's outer Scaffold already applied the system-bar insets to this route.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                if (tab == 0) MAIL_FOLDERS.firstOrNull { it.first == state.folder }?.second ?: "Mail" else "Drive",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            state.account?.address?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = { showAccount = true }) {
                            val name = state.account?.address.orEmpty()
                            Box(Modifier.mailboxLabel(name)) { MailAvatar(name, name, size = 32) }
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mail") }, icon = { Icon(Icons.Default.Email, null) })
                    Tab(selected = tab == 1, onClick = { tab = 1; viewModel.loadDrive() }, text = { Text("Drive") }, icon = { Icon(Icons.Default.Folder, null) })
                }
                busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { composeInit = ComposeInit(null, "", "", "", null) },
                    icon = { Icon(Icons.Default.Edit, null) },
                    text = { Text("Compose") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tab == 0) {
                val query by viewModel.query.collectAsStateWithLifecycle()
                val deep by viewModel.deepSearch.collectAsStateWithLifecycle()
                MailSearchField(query, deep, onQuery = { viewModel.setQuery(it) }, onToggleDeep = { viewModel.toggleDeepSearch() })
                MailFolderChips(state.folder, count = { viewModel.badgeCount(state, it) }) { viewModel.setFolder(it) }
                MailList(state, viewModel, snackbar)
            } else {
                DrivePane(viewModel)
            }
        }
    }

    open?.let { message ->
        MessageReader(
            message = message,
            own = own,
            onDismiss = { viewModel.closeMessage() },
            onDelete = {
                viewModel.delete(message.id)
                if (message.folder != "trash") scope.launch { snackbar.showSnackbar("Moved to Trash") }
            },
            onArchive = { viewModel.archive(message.id); scope.launch { snackbar.showSnackbar("Archived") } },
            onRestore = { viewModel.restore(message.id) },
            onMarkUnread = { viewModel.markUnread(message.id) },
            onReply = { all ->
                val to = if (all) MailDisplay.replyAllRecipients(message.from, message.to, own) else MailMime.emailAddress(message.from)
                viewModel.closeMessage()
                composeInit = ComposeInit(null, to, MailDisplay.replySubject(message.subject), "", message.messageId)
            },
            onForward = {
                viewModel.closeMessage()
                composeInit = ComposeInit(
                    null, "", MailDisplay.forwardSubject(message.subject),
                    MailDisplay.forwardBody(message.from, message.to, message.date, message.subject, message.body),
                    null,
                    attachments = message.attachments.map { OshiMailClient.Attachment(it.filename, it.mimeType, it.bytes) },
                )
            },
        )
    }

    composeInit?.let { init ->
        ComposeScreen(
            viewModel = viewModel,
            init = init,
            onClose = {
                composeInit = null
                viewModel.clearEditingDraft()
                viewModel.refresh()
            },
            onSent = {
                composeInit = null
                viewModel.clearEditingDraft()
                scope.launch { snackbar.showSnackbar("Sent.") }
            },
        )
    }

    if (showAccount) {
        MailAccountSheet(viewModel) { showAccount = false }
    }

    preview?.let { DrivePreviewSheet(it, onDismiss = { viewModel.closePreview() }) }
}

private fun Modifier.mailboxLabel(label: String): Modifier =
    this.semantics { contentDescription = "Mailbox $label" }

@Composable
private fun QuotaStrip(account: OshiMailClient.Account) {
    Column(Modifier.padding(vertical = 6.dp)) {
        LinearProgressIndicator(
            progress = (account.storageUsed.toFloat() / account.storageQuota).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${fmt(account.storageUsed)} of ${fmt(account.storageQuota)} · " +
                "${account.sent}/${account.sendQuota} sent this month",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MailList(state: MailViewModel.UiState, viewModel: MailViewModel, snackbar: SnackbarHostState) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val deep by viewModel.deepSearch.collectAsStateWithLifecycle()
    val bodies by viewModel.bodyCache.collectAsStateWithLifecycle()
    val rows = viewModel.visibleMessages(state, query, deep, bodies)
    val outgoing = state.folder == "sent" || state.folder == "draft"
    val scope = rememberCoroutineScope()

    // Pull-to-refresh: the spinner stays until the list (or an error) comes back.
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.messages, state.error) { refreshing = false }
    LaunchedEffect(refreshing) { if (refreshing) { delay(10_000); refreshing = false } }
    val pull = rememberPullRefreshState(refreshing, onRefresh = { refreshing = true; viewModel.refresh() })

    Box(Modifier.fillMaxSize().pullRefresh(pull)) {
        if (rows.isEmpty()) {
            // Scrollable so a pull still works on an empty folder.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
                    when {
                        query.isNotEmpty() -> MailEmptyState(
                            Icons.Outlined.SearchOff, "No results",
                            if (deep) "Nothing matches in senders, subjects or messages opened on this device."
                            else "Nothing matches in senders or subjects. Turn on Deep to also search messages you have opened.",
                        )
                        state.folder == "inbox" -> MailEmptyState(Icons.Outlined.Inbox, "Your inbox is empty", "New mail is sealed for this device and appears here.")
                        state.folder == "trash" -> MailEmptyState(Icons.Outlined.Delete, "Trash is empty", "Deleted messages wait here until you remove them for ever.")
                        state.folder == "draft" -> MailEmptyState(Icons.Outlined.Drafts, "No drafts", "Messages you start are saved here automatically.")
                        state.folder == "sent" -> MailEmptyState(Icons.Outlined.Send, "Nothing sent yet", "Messages you send appear here.")
                        else -> MailEmptyState(Icons.Outlined.Archive, "Nothing archived", "Swipe a message right to archive it.")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { message ->
                    val primary = state.account?.localpart
                    val row: @Composable () -> Unit = {
                        MailListRow(message, outgoing, primary, bodies[message.id]) { viewModel.openMessage(message.id) }
                    }
                    if (message.folder == "draft") {
                        row()
                    } else {
                        SwipeableMailRow(
                            folder = message.folder,
                            onArchive = {
                                viewModel.archive(message.id)
                                scope.launch {
                                    if (snackbar.showSnackbar("Archived", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                        viewModel.restore(message.id)
                                    }
                                }
                            },
                            onRestore = { viewModel.restore(message.id); scope.launch { snackbar.showSnackbar("Moved to Inbox") } },
                            onTrash = {
                                val from = message.folder
                                viewModel.delete(message.id)
                                scope.launch {
                                    // Undo only where the way back is exact: Inbox and Archive.
                                    val undo = if (from == "inbox" || from == "archive") "Undo" else null
                                    if (snackbar.showSnackbar("Moved to Trash", actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                        if (from == "archive") viewModel.archive(message.id) else viewModel.restore(message.id)
                                    }
                                }
                            },
                        ) { row() }
                    }
                    Divider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        PullRefreshIndicator(refreshing, pull, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * Swipe right: archive (Inbox) or back to Inbox (Archive/Trash). Swipe left: to Trash.
 * In Trash, left-swipe is not offered — destroying a message needs the reader's confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMailRow(
    folder: String,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onTrash: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rightIsRestore = folder == "archive" || folder == "trash"
    val dismissState = rememberDismissState(confirmValueChange = { value ->
        when (value) {
            DismissValue.DismissedToEnd -> { if (rightIsRestore) onRestore() else onArchive(); true }
            DismissValue.DismissedToStart -> { onTrash(); true }
            else -> false
        }
    })
    val directions = buildSet {
        if (folder != "sent") add(DismissDirection.StartToEnd)
        if (folder != "trash") add(DismissDirection.EndToStart)
    }
    SwipeToDismiss(
        state = dismissState,
        directions = directions,
        background = {
            when (dismissState.dismissDirection) {
                DismissDirection.StartToEnd ->
                    if (rightIsRestore) SwipeActionBackground(Icons.Outlined.Unarchive, "Inbox", MaterialTheme.colorScheme.tertiary, alignEnd = false)
                    else SwipeActionBackground(Icons.Outlined.Archive, "Archive", MaterialTheme.colorScheme.primary, alignEnd = false)
                DismissDirection.EndToStart ->
                    SwipeActionBackground(Icons.Outlined.Delete, "Trash", MaterialTheme.colorScheme.error, alignEnd = true)
                null -> {}
            }
        },
        dismissContent = { content() },
    )
}

/** Full-screen reader: top bar with folder actions, header, body, attachments, reply bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageReader(
    message: MailViewModel.OpenMessage,
    own: Set<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onMarkUnread: () -> Unit,
    onReply: (all: Boolean) -> Unit,
    onForward: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmingDelete by remember { mutableStateOf(false) }
    val inTrash = message.folder == "trash"
    val showReplyAll = MailDisplay.splitRecipients(MailDisplay.replyAllRecipients(message.from, message.to, own)).size > 1
    var viewing by remember(message.id) { mutableStateOf<MailMime.Attachment?>(null) }
    var pendingLink by remember(message.id) { mutableStateOf<String?>(null) }
    var loadRemote by remember(message.id) { mutableStateOf(MailRemoteImagePrefs.allowed(context, message.from)) }
    val remoteBlocked = !loadRemote && message.html?.let(MailDisplay::hasRemoteContent) == true
    // Parts the HTML shows in place (cid:) are not listed again as attachments.
    val listed = remember(message.id) {
        val shown = message.html?.let(MailDisplay::referencedCids).orEmpty()
        message.attachments.filter { it.contentId == null || it.contentId !in shown }
    }
    LaunchedEffect(message.id) { clearMailShareCache(context) }

    // decorFitsSystemWindows = false + systemBarsPadding: with targetSdk 36 the dialog
    // window is drawn edge to edge, and the gesture bar sat on the Reply/Forward labels.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EdgeToEdgeDialogWindow()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                modifier = Modifier.systemBarsPadding(),
                topBar = {
                    TopAppBar(
                        title = {},
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                        actions = {
                            if (inTrash) {
                                IconButton(onClick = onRestore) { Icon(Icons.Outlined.Unarchive, contentDescription = "Restore to Inbox") }
                            } else {
                                if (message.folder != "archive") IconButton(onClick = onArchive) { Icon(Icons.Outlined.Archive, contentDescription = "Archive") }
                                IconButton(onClick = onMarkUnread) { Icon(Icons.Outlined.MarkEmailUnread, contentDescription = "Mark as unread") }
                            }
                            IconButton(onClick = { if (inTrash) confirmingDelete = true else onDelete() }) {
                                Icon(Icons.Outlined.Delete, contentDescription = if (inTrash) "Delete for ever" else "Move to Trash")
                            }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                },
                bottomBar = { ReaderActionBar(showReplyAll, onReply = { onReply(false) }, onReplyAll = { onReply(true) }, onForward = onForward) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                    ReaderHeader(message.subject, message.from, message.to, message.date)
                    Divider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    val html = message.html
                    if (html != null) {
                        if (remoteBlocked) {
                            RemoteImagesBanner(
                                onLoad = { loadRemote = true },
                                onAlways = { MailRemoteImagePrefs.allow(context, message.from); loadRemote = true },
                            )
                        }
                        MailHtmlBody(html, message.attachments, allowRemote = !remoteBlocked, onLink = { pendingLink = it })
                    } else {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            MailTextBody(
                                message.body,
                                onLink = { openExternalLink(context, it) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (listed.isNotEmpty()) {
                        Text(
                            "${listed.size} attachment${if (listed.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                        val (images, files) = listed.partition { it.isImage && it.mimeType != "image/svg+xml" }
                        if (images.isNotEmpty()) {
                            AttachmentImageGrid(images, onOpen = { viewing = it })
                            Spacer(Modifier.height(8.dp))
                        }
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            files.forEach { attachment ->
                                AttachmentCard(
                                    attachment.filename, attachment.bytes.size.toLong(),
                                    onClick = { openAttachment(context, attachment) },
                                    onTrailing = { saveAttachment(context, attachment) },
                                    trailingLabel = "Save ${attachment.filename} to Downloads",
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    viewing?.let { ImageViewer(it, onDismiss = { viewing = null }) }
    pendingLink?.let { url ->
        if (MailDisplay.isOpenableLink(url)) {
            OpenLinkDialog(url, onDismiss = { pendingLink = null }, onOpen = { pendingLink = null; openExternalLink(context, url) })
        } else {
            // javascript:, file:, intent:, about:… never leave the reader.
            LaunchedEffect(url) { pendingLink = null }
        }
    }

    if (confirmingDelete) {
        OshiAlertDialog(
            onDismissRequest = { confirmingDelete = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Delete this message for ever?") },
            text = { Text("There is no server copy to recover it from.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text("Delete for ever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Full-screen composer: close + title + attach + send in the top bar, chips for recipients. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeScreen(
    viewModel: MailViewModel,
    init: ComposeInit,
    onClose: () -> Unit,
    onSent: () -> Unit,
) {
    val inReplyTo = init.inReplyTo
    var draftId by rememberSaveable { mutableStateOf(init.draftId) }
    var to by rememberSaveable { mutableStateOf(init.to) }
    var subject by rememberSaveable { mutableStateOf(init.subject) }
    var body by rememberSaveable { mutableStateOf(init.body) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var draftStatus by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(init.attachments) }
    var pickingFromDrive by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Debounced autosave: one sealed write per pause in typing, not one per keystroke.
    LaunchedEffect(to, subject, body) {
        if (to.isBlank() && subject.isBlank() && body.isBlank()) return@LaunchedEffect
        draftStatus = "Saving…"
        delay(1500)
        val savedId = viewModel.saveDraft(draftId, to, subject, body)
        draftId = savedId
        draftStatus = if (savedId != null) "Draft saved" else ""
    }

    // Dismissing the composer — back, close button — SAVES rather than discards:
    // losing a half-written message because a sheet was dismissed is the oldest complaint
    // in email.
    fun requestClose() {
        scope.launch {
            val savedId = viewModel.saveDraft(draftId, to, subject, body)
            draftId = savedId
            onClose()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { viewModel.readUri(uri) }
                .onSuccess { (name, bytes) ->
                    val total = attachments.sumOf { it.bytes.size } + bytes.size
                    if (total > viewModel.maxAttachmentBytes) {
                        error = "Attachments can total ${fmt(viewModel.maxAttachmentBytes.toLong())}."
                    } else {
                        attachments = attachments + OshiMailClient.Attachment(name, viewModel.mimeTypeOf(uri), bytes)
                        error = null
                    }
                }
                .onFailure { error = it.message }
        }
    }

    fun send() {
        sending = true
        viewModel.send(
            to = MailDisplay.splitRecipients(to),
            subject = subject, text = body, inReplyTo = inReplyTo, attachments = attachments,
            draftId = draftId,
        ) { failure ->
            sending = false
            if (failure == null) onSent() else error = failure
        }
    }

    Dialog(onDismissRequest = { requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // __KEYBOARD_PRIVACY_2026_10_01__ A Dialog is its own window and re-provides the
        // stock text input, so the app-wide incognito keyboard must be re-applied here:
        // this is where mail bodies are typed.
        com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (draftId != null && init.draftId != null) "Edit draft" else if (inReplyTo != null) "Reply" else "New message")
                            if (draftStatus.isNotEmpty()) Text(draftStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { requestClose() }) { Icon(Icons.Default.Close, contentDescription = "Close and keep draft") } },
                    actions = {
                        Box {
                            IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.AttachFile, contentDescription = "Attach") }
                            OshiDropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(text = { Text("File from this device") }, leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                                    onClick = { attachMenu = false; picker.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text("From Drive") }, leadingIcon = { Icon(Icons.Default.Folder, null) },
                                    onClick = { attachMenu = false; viewModel.loadDrive(); pickingFromDrive = true })
                            }
                        }
                        if (sending) {
                            CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(22.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { send() }, enabled = MailDisplay.splitRecipients(to).isNotEmpty()) {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = if (MailDisplay.splitRecipients(to).isNotEmpty()) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
                    RecipientField(to, { to = it })
                    val lastPiece = if (to.contains(',')) to.substringAfterLast(',').trimStart() else to
                    val suggestions = if (lastPiece.isNotBlank()) viewModel.addressSuggestions(lastPiece) else emptyList()
                    suggestions.take(5).forEach { (address, name) ->
                        ListItem(
                            leadingContent = { MailAvatar(name.ifEmpty { address }, address, size = 32) },
                            headlineContent = { Text(name.ifEmpty { address }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { if (name.isNotEmpty() && name != address) Text(address, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.clickableRow {
                                val prefix = to.substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it, " }
                                to = "$prefix$address, "
                            },
                        )
                    }
                    Divider()
                    androidx.compose.foundation.text.BasicTextField(
                        value = subject, onValueChange = { subject = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                                if (subject.isEmpty()) Text("Subject", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        },
                    )
                    Divider()
                    if (attachments.isNotEmpty()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            attachments.forEachIndexed { index, attachment ->
                                AttachmentCard(
                                    attachment.filename, attachment.bytes.size.toLong(),
                                    onClick = { attachments = attachments.filterIndexed { i, _ -> i != index } },
                                    trailing = Icons.Default.Close,
                                )
                            }
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = body, onValueChange = { body = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(16.dp)) {
                                if (body.isEmpty()) Text("Write your message", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        },
                    )
                }
            }
        }
        } // IncognitoKeyboardScope
    }

    if (pickingFromDrive) {
        DrivePickerDialog(viewModel, onDismiss = { pickingFromDrive = false }) { attachment ->
            val total = attachments.sumOf { it.bytes.size } + attachment.bytes.size
            if (total > viewModel.maxAttachmentBytes) {
                error = "Attachments can total ${fmt(viewModel.maxAttachmentBytes.toLong())}."
            } else {
                attachments = attachments + attachment
            }
            pickingFromDrive = false
        }
    }
}

@Composable
private fun DrivePickerDialog(
    viewModel: MailViewModel,
    onDismiss: () -> Unit,
    onPick: (OshiMailClient.Attachment) -> Unit,
) {
    val files by viewModel.drive.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf<String?>(null) }

    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach from Drive") },
        text = {
            if (files.isEmpty()) Text("Your drive is empty.")
            else LazyColumn {
                items(files, key = { it.id }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name ?: "Sealed for another device") },
                        supportingContent = { Text(fmt(file.bytes)) },
                        trailingContent = { if (fetching == file.id) CircularProgressIndicator(Modifier.size(18.dp)) },
                        modifier = Modifier.clickableRow {
                            if (file.name == null) return@clickableRow
                            fetching = file.id
                            // The file is stored sealed, so it must be fetched and
                            // decrypted here before it can be attached.
                            scope.launch {
                                runCatching { viewModel.driveFileAsAttachment(file) }
                                    .onSuccess(onPick)
                                fetching = null
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DrivePane(viewModel: MailViewModel) {
    val files by viewModel.drive.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<OshiMailClient.DriveFile?>(null) }

    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.upload(it) { failure -> error = failure } }
    }

    LaunchedEffect(Unit) { viewModel.loadDrive() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { uploader.launch(arrayOf("*/*")) }) { Text("Upload") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }

        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing stored yet.") }
        } else {
            LazyColumn {
                items(files, key = { it.id }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name ?: "Sealed for another device") },
                        supportingContent = { Text(fmt(file.bytes)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { renaming = file }, enabled = file.name != null) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename")
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        runCatching { viewModel.driveFileAsAttachment(file) }
                                            .onSuccess { saveAttachment(context, MailMime.Attachment(it.filename, it.mimeType, it.bytes)) }
                                            .onFailure { error = it.message }
                                    }
                                }) {
                                    Icon(Icons.Default.Download, contentDescription = "Download")
                                }
                                IconButton(onClick = { viewModel.deleteDriveFile(file.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        },
                        modifier = Modifier.clickableRow { viewModel.previewDriveFile(file) },
                    )
                    Divider()
                }
            }
        }
    }

    renaming?.let { file ->
        RenameDialog(
            currentName = file.name.orEmpty(),
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                viewModel.renameDriveFile(file.id, newName) { failure -> error = failure }
                renaming = null
            },
        )
    }
}

@Composable
private fun RenameDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrivePreviewSheet(preview: MailViewModel.PreviewState, onDismiss: () -> Unit) {
    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(preview.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(fmt(preview.bytes.toLong()), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))
            when (val content = preview.content) {
                is MailViewModel.PreviewContent.Image -> Image(
                    bitmap = content.bitmap.asImageBitmap(),
                    contentDescription = preview.name,
                    modifier = Modifier.fillMaxWidth(),
                )
                is MailViewModel.PreviewContent.Pdf -> Image(
                    bitmap = content.bitmap.asImageBitmap(),
                    contentDescription = preview.name,
                    modifier = Modifier.fillMaxWidth(),
                )
                is MailViewModel.PreviewContent.Text -> Box(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(content.text, style = MaterialTheme.typography.bodySmall)
                }
                MailViewModel.PreviewContent.Unsupported ->
                    Text("No preview for this file type.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------

private fun fmt(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    else -> String.format("%.2f GB", bytes / 1073741824.0)
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.fillMaxWidth().clickable(onClick = onClick)

/**
 * Minimal chrome for the mailbox states that return before the main Scaffold:
 * a top bar carrying nothing but the title and the back arrow, so "loading" and
 * "no mailbox yet" are leavable without the system gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailBackdrop(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        // The app's outer Scaffold already applied the system-bar insets to this route.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

/**
 * Claiming an address. The copy is blunt about the trade-off on purpose: a mailbox only
 * this device can read is also a mailbox nobody can recover for you.
 */
@Composable
fun MailSetup(viewModel: MailViewModel) {
    var localpart by rememberSaveable { mutableStateOf("") }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Debounced: the availability route is rate-limited, and one request per keystroke
    // would start earning 429s.
    LaunchedEffect(localpart) {
        available = null
        if (localpart.length < 3) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        checking = true
        available = viewModel.isAvailable(localpart.lowercase())
        checking = false
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Your private mailbox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Mail arriving here is encrypted with a key only this device holds. The server can deliver your mail and cannot read it — which also means nobody, including us, can recover it for you.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = localpart,
            onValueChange = { localpart = it.filter { c -> !c.isWhitespace() } },
            label = { Text("Address") },
            supportingText = { Text("@${viewModel.domain}", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        when {
            checking -> Text("Checking…", style = MaterialTheme.typography.labelSmall)
            available == true -> Text("Available", style = MaterialTheme.typography.labelSmall)
            available == false -> Text("Already taken", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            localpart.isNotEmpty() -> Text("3–32 characters: letters, digits, dot, dash, underscore.", style = MaterialTheme.typography.labelSmall)
        }

        Button(
            enabled = available == true && !creating,
            onClick = {
                creating = true
                viewModel.register(localpart.lowercase()) { failure ->
                    creating = false
                    error = failure
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (creating) "Creating…" else "Create this address") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(6.dp))
        Text("150 MB mailbox · 150 messages a month · 1 GB encrypted drive · up to 10 aliases",
             style = MaterialTheme.typography.labelSmall)
    }
}

/** Address, aliases, quotas, and the browsers allowed to read this mailbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailAccountSheet(viewModel: MailViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()

    var newAlias by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var approved by remember { mutableStateOf<String?>(null) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The webmail QR encodes "OSHIMAIL:PAIR:<code>". A scan of anything else is ignored,
    // so pointing at a contact's identity QR here does nothing.
    val pairScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@rememberLauncherForActivityResult
        val prefix = "OSHIMAIL:PAIR:"
        if (content.startsWith(prefix)) {
            code = content.removePrefix(prefix)
            viewModel.inspectPairing(code) { failure -> message = failure }
        } else {
            message = "That QR code is not an OSHI Mail pairing code."
        }
    }

    LaunchedEffect(Unit) { viewModel.loadSessions() }

    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val account = state.account ?: return@Column
            Text(account.address, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            QuotaStrip(account)

            Text("Aliases (${account.aliases.size} of ${account.aliasLimit})", style = MaterialTheme.typography.labelMedium)
            account.aliases.forEach { alias ->
                ListItem(
                    headlineContent = { Text(alias, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeAlias(alias) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove alias")
                        }
                    },
                )
            }
            if (account.aliases.size < account.aliasLimit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                    // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            newAlias, { newAlias = it.filter { c -> !c.isWhitespace() } },
                            label = { Text("New alias") },
                            supportingText = { Text("@${viewModel.domain}", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        enabled = newAlias.length >= 3,
                        onClick = { viewModel.addAlias(newAlias.lowercase()) { failure -> message = failure; newAlias = "" } },
                    ) { Text("Add") }
                }
                // A one-tap alias that is your public key instead of a chosen name.
                TextButton(onClick = { newAlias = viewModel.keyBasedLocalpart }) {
                    Text("Use my public key (${viewModel.keyBasedLocalpart}…)", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "An alias is another address for THIS same mailbox — mail sent to it lands in your inbox here. New aliases appear in the list above.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Divider()

            Text("Connect a browser", style = MaterialTheme.typography.labelMedium)
            if (approved != null) {
                Text("Browser connected — it can read $approved until you sign it out.",
                     style = MaterialTheme.typography.bodySmall)
            } else if (pairing != null) {
                Text(
                    "A browser is asking to read ${account.address}. Approving sends your mail key to it, encrypted so only that browser can open it. Our server relays it and cannot read it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.approvePairing(android.os.Build.MODEL) { address, failure ->
                            approved = address; message = failure
                        }
                    }) { Text("Approve") }
                    TextButton(onClick = { viewModel.clearPairing() }) { Text("Cancel") }
                }
            } else {
                Button(
                    onClick = {
                        val options = ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Scan the QR code shown at oshi-messenger.com/mail")
                            setBeepEnabled(false)
                            setOrientationLocked(true)
                            setCaptureActivity(com.oshi.messenger.ui.scan.OshiScanActivity::class.java)
                            setBarcodeImageEnabled(false)
                        }
                        pairScanLauncher.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan a QR code") }
                Text("Open oshi-messenger.com/mail on your computer and scan the QR it shows.",
                     style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                    // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            code, { code = it.uppercase().filter { c -> !c.isWhitespace() } },
                            label = { Text("Or type the code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        enabled = code.length >= 8,
                        onClick = { viewModel.inspectPairing(code) { failure -> message = failure } },
                    ) { Text("Continue") }
                }
                Text("Open oshi-messenger.com/mail on your computer and type the code it shows.",
                     style = MaterialTheme.typography.labelSmall)
            }

            sessions.forEach { session ->
                ListItem(
                    headlineContent = { Text(session.label, style = MaterialTheme.typography.bodySmall) },
                    supportingContent = { Text("last used ${session.lastSeenAt.take(16)}", style = MaterialTheme.typography.labelSmall) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.revokeSession(session.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Sign out")
                        }
                    },
                )
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
