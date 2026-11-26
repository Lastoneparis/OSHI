package com.oshi.messenger.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.mail.MailContacts
import com.oshi.messenger.mail.MailMime
import com.oshi.messenger.mail.MailSearch
import com.oshi.messenger.mail.MailUnread
import com.oshi.messenger.mail.OshiMailClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * State for the mail screens.
 *
 * Everything the UI reads is a StateFlow, never a plain `var`. A bare var written before a
 * suspension and read from a composable does not recompose, and the screen silently stops
 * at whatever it drew first — a failure mode this codebase has hit before.
 */
@HiltViewModel
class MailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OshiMailClient,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val hasMailbox: Boolean = false,
        val account: OshiMailClient.Account? = null,
        val messages: List<OshiMailClient.Message> = emptyList(),
        val folder: String = "inbox",
        val error: String? = null,
        /**
         * __MAIL_OFFLINE_KEEPS_MAILBOX_2026_11_25__ The last account refresh could not reach
         * the server (offline, timeout, 5xx). With no remembered mailbox the screen shows
         * "can't reach mail" + Retry — never the address form, which is for a server-confirmed
         * `no-mailbox` only.
         */
        val unreachable: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _open = MutableStateFlow<OpenMessage?>(null)
    val open: StateFlow<OpenMessage?> = _open.asStateFlow()

    data class OpenMessage(
        val id: String,
        val folder: String,
        val subject: String,
        val from: String,
        val to: String,
        val date: String,
        val body: String,
        val attachments: List<MailMime.Attachment>,
        val messageId: String?,
        /** The HTML alternative when the message has one; the reader prefers it. */
        val html: String? = null,
    )

    // ---- search ----------------------------------------------------------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _deepSearch = MutableStateFlow(false)
    val deepSearch: StateFlow<Boolean> = _deepSearch.asStateFlow()

    /** id -> "subject\nfrom\nbody", for messages already decrypted this session. */
    private val _bodyCache = MutableStateFlow<Map<String, String>>(emptyMap())
    val bodyCache: StateFlow<Map<String, String>> = _bodyCache.asStateFlow()

    fun setQuery(text: String) { _query.value = text }

    fun toggleDeepSearch() { _deepSearch.value = !_deepSearch.value }

    fun visibleMessages(state: UiState, query: String, deep: Boolean, bodies: Map<String, String>): List<OshiMailClient.Message> =
        state.messages
            .filter { it.folder == state.folder }
            .filter { MailSearch.matches(it, query, deep, bodies) }

    // ---- address book ------------------------------------------------------------

    private val _contacts = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val contacts: StateFlow<List<Pair<String, String>>> = _contacts.asStateFlow()

    fun addressSuggestions(prefix: String): List<Pair<String, String>> =
        MailContacts.suggestions(_contacts.value, prefix)

    // ---- drafts --------------------------------------------------------------------

    data class DraftContent(val id: String, val to: String, val subject: String, val body: String)

    private val _editingDraft = MutableStateFlow<DraftContent?>(null)
    val editingDraft: StateFlow<DraftContent?> = _editingDraft.asStateFlow()

    // ---- drive preview -------------------------------------------------------------

    sealed class PreviewContent {
        data class Image(val bitmap: Bitmap) : PreviewContent()
        data class Pdf(val bitmap: Bitmap) : PreviewContent()
        data class Text(val text: String) : PreviewContent()
        object Unsupported : PreviewContent()
    }

    data class PreviewState(val name: String, val bytes: Int, val content: PreviewContent)

    private val _preview = MutableStateFlow<PreviewState?>(null)
    val preview: StateFlow<PreviewState?> = _preview.asStateFlow()

    private val _drive = MutableStateFlow<List<OshiMailClient.DriveFile>>(emptyList())
    val drive: StateFlow<List<OshiMailClient.DriveFile>> = _drive.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    val domain: String get() = OshiMailClient.MAIL_DOMAIN
    val maxAttachmentBytes: Int get() = client.maxAttachmentTotalBytes
    val keyBasedLocalpart: String get() = client.keyBasedLocalpart()

    init {
        // A mailbox this identity already had is shown at once, network or not.
        client.cachedAccount()?.let { cached ->
            _state.value = _state.value.copy(loading = false, hasMailbox = true, account = cached)
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val account = client.account()
                _state.value = _state.value.copy(loading = false, hasMailbox = true, account = account, error = null, unreachable = false)
                loadInbox()
            } catch (e: Exception) {
                _state.value = MailRefreshOutcome.afterFailure(_state.value, e)
            }
        }
    }

    private suspend fun loadInbox() {
        runCatching { client.inbox() }
            .onSuccess {
                _state.value = _state.value.copy(messages = it)
                // Authoritative unread count for the Email-tab badge (and any optimistic
                // push bump gets reconciled here). Only Inbox counts unseen — the same
                // rule badgeCount() uses.
                MailUnread.set(it.count { m -> m.folder == "inbox" && !m.seen })
                // Harvested from every folder, not just the one on screen: the address
                // book is "everyone in this mailbox", and switching folders should not
                // make someone's address disappear from autocomplete.
                // Exclude this mailbox's own addresses, primary and aliases alike.
                val account = _state.value.account
                val own = buildSet {
                    account?.address?.let(::add)
                    account?.aliases?.forEach(::add)
                }
                _contacts.value = MailContacts.harvest(it, own)
            }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    /** Unread count for a folder. Only Inbox counts it — elsewhere "unread" is permanently
     *  0 and would make a non-empty folder look empty, which is what iOS/web both avoid. */
    fun badgeCount(state: UiState, folder: String): Int {
        val rows = state.messages.filter { it.folder == folder }
        return if (folder == "inbox") rows.count { !it.seen } else rows.size
    }

    fun setFolder(folder: String) { _state.value = _state.value.copy(folder = folder) }

    suspend fun isAvailable(localpart: String): Boolean =
        runCatching { client.isAvailable(localpart) }.getOrDefault(false)

    fun register(localpart: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                client.register(localpart)
                refresh()
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: "could not create that address")
            }
        }
    }

    fun openMessage(id: String) {
        val folder = _state.value.messages.find { it.id == id }?.folder ?: "inbox"
        if (folder == "draft") { openDraft(id); return }
        viewModelScope.launch {
            _busy.value = "Decrypting…"
            try {
                val raw = client.message(id)
                val mail = MailMime.parse(raw)
                _open.value = openMessageOf(id, folder, mail)
                // Kept so a "deep" search can look inside mail already opened this
                // session, without ever downloading the rest of the mailbox to search it.
                _bodyCache.value = _bodyCache.value + (id to "${mail.subject}\n${mail.from}\n${mail.text}")
                if (folder == "inbox") {
                    runCatching { client.markSeen(id) }
                    loadInbox()
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _busy.value = null
            }
        }
    }

    fun closeMessage() { _open.value = null }

    /** First tap moves the message to Trash; a second tap (already in Trash) destroys it
     *  — the caller gates that second tap behind a confirmation. */
    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { client.delete(id) }
            _open.value = null
            refresh()
        }
    }

    fun archive(id: String) = move(id, "archive")

    fun restore(id: String) = move(id, "inbox")

    private fun move(id: String, folder: String) {
        viewModelScope.launch {
            try {
                client.move(id, folder)
                _open.value = null
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun markUnread(id: String) {
        viewModelScope.launch {
            runCatching { client.markSeen(id, seen = false) }
            _open.value = null
            refresh()
        }
    }

    fun send(
        to: List<String>,
        subject: String,
        text: String,
        inReplyTo: String?,
        attachments: List<OshiMailClient.Attachment>,
        draftId: String?,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = "Sending…"
            try {
                client.send(to = to, subject = subject, text = text, inReplyTo = inReplyTo, attachments = attachments)
                // The draft became a real message; remove it so it does not linger
                // alongside the sent copy.
                draftId?.let { runCatching { client.delete(it, permanent = true) } }
                _editingDraft.value = null
                refresh()
                onDone(null)
            } catch (e: OshiMailClient.MailException) {
                onDone(
                    if (e.code == "send-quota-exceeded") "You have used all 150 messages this month."
                    else e.message,
                )
            } catch (e: Exception) {
                onDone(e.message)
            } finally {
                _busy.value = null
            }
        }
    }

    // ---- drafts --------------------------------------------------------------------

    /** Opens a draft in the composer — its body was sealed as plain text, not MIME. */
    fun openDraft(id: String) {
        viewModelScope.launch {
            _busy.value = "Decrypting…"
            try {
                val row = _state.value.messages.find { it.id == id }
                val raw = client.message(id)
                _editingDraft.value = DraftContent(
                    id = id,
                    to = row?.envelope?.to.orEmpty(),
                    subject = row?.envelope?.subject.orEmpty(),
                    body = String(raw, Charsets.UTF_8),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _busy.value = null
            }
        }
    }

    fun clearEditingDraft() { _editingDraft.value = null }

    /**
     * Seals and saves the composer as a draft, reusing [id] when it is non-empty so
     * repeated autosaves edit the same draft instead of creating a new one every time.
     * Returns the (possibly new) draft id, or null on failure.
     */
    suspend fun saveDraft(id: String?, to: String, subject: String, body: String): String? {
        if (to.isBlank() && subject.isBlank() && body.isBlank()) return id?.takeIf { it.isNotEmpty() }
        return try {
            val envelope = client.seal(
                JSONObject()
                    .put("from", _state.value.account?.address ?: "")
                    .put("to", to)
                    .put("subject", subject)
                    .put("date", java.util.Date().toString())
                    // Same list preview field the server puts in delivered mail.
                    .put("snippet", com.oshi.messenger.mail.MailDisplay.snippetOf(body))
                    .toString().toByteArray(Charsets.UTF_8),
            )
            val sealedBody = client.seal(body.toByteArray(Charsets.UTF_8))
            client.saveDraft(id?.takeIf { it.isNotEmpty() }, envelope, sealedBody)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteDraft(id: String) {
        viewModelScope.launch {
            runCatching { client.delete(id, permanent = true) }
            _editingDraft.value = null
            refresh()
        }
    }

    fun addAlias(alias: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try { client.addAlias(alias); refresh(); onDone(null) }
            catch (e: Exception) { onDone(e.message) }
        }
    }

    fun removeAlias(alias: String) {
        viewModelScope.launch { runCatching { client.removeAlias(alias) }; refresh() }
    }

    // ---- drive ---------------------------------------------------------------

    fun loadDrive() {
        viewModelScope.launch {
            _busy.value = "Decrypting names…"
            runCatching { client.driveFiles() }.onSuccess { _drive.value = it }
            _busy.value = null
        }
    }

    fun upload(uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            _busy.value = "Encrypting…"
            try {
                val (name, bytes) = readUri(uri)
                // Sealed HERE: what leaves the device is ciphertext, filename included.
                val sealedBody = client.seal(bytes)
                val sealedName = client.seal("""{"name":${org.json.JSONObject.quote(name)}}""".toByteArray())
                client.uploadToDrive(sealedBody, sealedName) { p ->
                    _busy.value = "Uploading ${(p * 100).toInt()}%"
                }
                loadDrive(); refresh(); onDone(null)
            } catch (e: Exception) {
                onDone(e.message)
            } finally {
                _busy.value = null
            }
        }
    }

    fun deleteDriveFile(id: String) {
        viewModelScope.launch { runCatching { client.deleteFromDrive(id) }; loadDrive(); refresh() }
    }

    /** Re-seals the metadata HERE and PATCHes the new blob — the name lives only inside
     *  the sealed bytes, so the server swaps one opaque value for another. */
    fun renameDriveFile(id: String, newName: String, onDone: (String?) -> Unit) {
        val clean = newName.trim()
        if (clean.isEmpty()) { onDone("A file needs a name."); return }
        viewModelScope.launch {
            try {
                val sealed = client.seal(JSONObject().put("name", clean).toString().toByteArray(Charsets.UTF_8))
                client.renameDriveFile(id, sealed)
                loadDrive()
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message)
            }
        }
    }

    /** Decrypts a drive file and renders it: images and PDFs draw, text shows inline,
     *  anything else falls back to a message (the row's own Download still works). */
    fun previewDriveFile(file: OshiMailClient.DriveFile) {
        viewModelScope.launch {
            _busy.value = "Decrypting…"
            try {
                val bytes = client.downloadFromDrive(file.id)
                val name = file.name ?: "file"
                val ext = name.substringAfterLast('.', "").lowercase()
                val content = withContext(Dispatchers.Default) {
                    when {
                        ext in IMAGE_EXTENSIONS -> {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) PreviewContent.Image(bitmap) else PreviewContent.Unsupported
                        }
                        ext == "pdf" -> renderPdfFirstPage(bytes)?.let { PreviewContent.Pdf(it) } ?: PreviewContent.Unsupported
                        ext in TEXT_EXTENSIONS || bytes.size < 200_000 ->
                            PreviewContent.Text(String(bytes, Charsets.UTF_8))
                        else -> PreviewContent.Unsupported
                    }
                }
                _preview.value = PreviewState(name, bytes.size, content)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _busy.value = null
            }
        }
    }

    fun closePreview() { _preview.value = null }

    private fun renderPdfFirstPage(bytes: ByteArray): Bitmap? = runCatching {
        val file = File.createTempFile("mail-drive-preview", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) return@runCatching null
                    renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } finally {
            file.delete()
        }
    }.getOrNull()

    /** Fetches and decrypts a drive file so it can be attached to an email. */
    suspend fun driveFileAsAttachment(file: OshiMailClient.DriveFile): OshiMailClient.Attachment {
        val plain = client.downloadFromDrive(file.id)
        return OshiMailClient.Attachment(file.name ?: "file", "application/octet-stream", plain)
    }

    suspend fun readUri(uri: Uri): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val i = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && cursor.moveToFirst()) cursor.getString(i) else null
        } ?: uri.lastPathSegment ?: "file"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("could not read that file")
        name to bytes
    }

    fun mimeTypeOf(uri: Uri): String =
        context.contentResolver.getType(uri) ?: "application/octet-stream"

    // ---- browser pairing -----------------------------------------------------

    private val _pairing = MutableStateFlow<OshiMailClient.PairingRequest?>(null)
    val pairing: StateFlow<OshiMailClient.PairingRequest?> = _pairing.asStateFlow()

    private val _sessions = MutableStateFlow<List<OshiMailClient.WebSession>>(emptyList())
    val sessions: StateFlow<List<OshiMailClient.WebSession>> = _sessions.asStateFlow()

    fun inspectPairing(code: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            try { _pairing.value = client.inspectPairing(code); onDone(null) }
            catch (e: OshiMailClient.MailException) {
                onDone(
                    if (e.code == "no-such-pairing")
                        "No browser is waiting with that code. Check it, or refresh the page for a new one."
                    else e.message,
                )
            }
            catch (e: Exception) { onDone(e.message) }
        }
    }

    fun clearPairing() { _pairing.value = null }

    fun approvePairing(label: String, onDone: (String?, String?) -> Unit) {
        val request = _pairing.value ?: return
        viewModelScope.launch {
            try {
                val address = client.approvePairing(request, label)
                _pairing.value = null
                loadSessions()
                onDone(address, null)
            } catch (e: Exception) {
                onDone(null, e.message)
            }
        }
    }

    fun loadSessions() {
        viewModelScope.launch { runCatching { client.webSessions() }.onSuccess { _sessions.value = it } }
    }

    fun revokeSession(id: String) {
        viewModelScope.launch { runCatching { client.revokeWebSession(id) }; loadSessions() }
    }

    companion object {
        /** What the reader shows for a parsed message (also used by the debug fixture viewer). */
        fun openMessageOf(id: String, folder: String, mail: MailMime.Mail): OpenMessage = OpenMessage(
            id = id,
            folder = folder,
            subject = mail.subject.ifEmpty { "(no subject)" },
            from = mail.from,
            to = mail.to,
            date = mail.date,
            body = mail.text.ifEmpty { "(empty message)" },
            attachments = mail.attachments,
            messageId = mail.headers["message-id"],
            html = mail.html?.takeIf { it.isNotBlank() },
        )

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
        private val TEXT_EXTENSIONS = setOf("txt", "md", "json", "csv", "log", "xml", "html")
    }
}

/**
 * __MAIL_OFFLINE_KEEPS_MAILBOX_2026_11_25__ What a failed account refresh does to the screen.
 * Pure, so the three cases are unit-tested:
 *  - server says `no-mailbox`  → address form (hasMailbox=false), no error;
 *  - anything else, mailbox known (cached or loaded earlier) → keep showing it, with the error;
 *  - anything else, nothing known → `unreachable` (retry screen), NOT the address form.
 */
internal object MailRefreshOutcome {
    const val UNREACHABLE_MESSAGE = "Can't reach mail right now. Showing your mailbox as last loaded."
    fun afterFailure(current: MailViewModel.UiState, e: Exception): MailViewModel.UiState {
        val noMailbox = e is OshiMailClient.MailException && e.code == "no-mailbox"
        return if (noMailbox) {
            current.copy(loading = false, hasMailbox = false, account = null, error = null, unreachable = false)
        } else {
            current.copy(
                loading = false,
                hasMailbox = current.hasMailbox || current.account != null,
                error = UNREACHABLE_MESSAGE,
                unreachable = true,
            )
        }
    }
}
