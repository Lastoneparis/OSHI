package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.ReportReason
import com.oshi.messenger.data.model.ReportSubject
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.di.ApplicationScope
import com.oshi.messenger.network.location.LocationMessage
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.service.AudioPlayerManager
import com.oshi.messenger.service.AudioRecorderManager
import com.oshi.messenger.service.MediaCompressionManager
import com.oshi.messenger.service.MessageActionsManager
import com.oshi.messenger.service.MessageReactionManager
import com.oshi.messenger.service.NLPManager
import com.oshi.messenger.service.ProfileLinksManager
import com.oshi.messenger.service.ReportManager
import com.oshi.messenger.service.WelcomeMessage
import com.oshi.messenger.service.lora.LoRaNodeIdentityMap
import com.oshi.messenger.service.TypingIndicatorManager
import com.oshi.messenger.service.WallpaperManager
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.chatWallpaperFromBytes
import com.oshi.messenger.ui.components.toSyncBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    val audioRecorder: AudioRecorderManager,
    val audioPlayer: AudioPlayerManager,
    val reactionManager: MessageReactionManager,
    val mediaCompression: MediaCompressionManager,
    val messageActions: MessageActionsManager,
    val profileLinksManager: ProfileLinksManager,
    private val reportManager: ReportManager,
    private val contactShareManager: com.oshi.messenger.service.ContactShareManager,
    private val typingIndicatorManager: TypingIndicatorManager,
    private val nlpManager: NLPManager,
    private val wallpaperManager: WallpaperManager,
    private val vpsClient: com.oshi.messenger.network.vps.VPSClient,
    val locationSharingManager: LocationSharingManager,
    private val cryptoManager: com.oshi.messenger.network.encryption.CryptoManager,
    // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ The sealed store needs a context
    // to open its file; everything else here already had one through a manager.
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    // __MESH_ONLY_UX_2026_11_03__ Whether THIS peer is reachable over the radios
    // right now. The antenna in the conversation list answers "is the mesh
    // alive"; inside a mesh-only conversation the only question that matters is
    // "will this message reach the person I am looking at".
    private val meshManager: com.oshi.messenger.network.mesh.MeshNetworkManager,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    /**
     * __MESH_ONLY_UX_2026_11_03__ Public keys currently reachable over the local
     * mesh, so a chat can colour its own indicator without reaching for
     * `ConversationsViewModel` (which would build a second conversation list
     * behind the chat screen just to read one boolean).
     */
    val meshPeerKeys: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        meshManager.connectedPeers
            .map { peers -> peers.map { it.publicKey }.toSet() }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                emptySet()
            )

    private val _publicKey = MutableStateFlow("")

    val publicKey: StateFlow<String> = _publicKey.asStateFlow()

    /**
     * True for the seeded welcome thread, whose "sender" is
     * [WelcomeMessage.SYSTEM_SENDER_KEY] (`oshi.welcome`) — a label, not a
     * public key.
     *
     * Nothing stopped the user replying to it. The send path then fed
     * `oshi.welcome` to `CryptoManager.deriveSharedSecret`, which base64-decoded
     * it to 8 bytes and threw; the bubble was stamped "Not delivered" and the
     * exception text was toasted. That is exactly what Play's reviewer
     * photographed on 2026-09-16 (three screenshots, every bubble "Not
     * Delivered. Tap to try again") and it is why the app read as broken: with
     * no second device, the welcome thread was the ONLY conversation they had.
     *
     * [ChatScreen] hides the composer when this is true; the guards in the send
     * functions below are the second line, for any path that does not.
     */
    val isSystemThread: StateFlow<Boolean> = _publicKey
        .map { it == WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ True for the greeting
     * thread now that it goes to a real account. Drives the notice above the
     * composer — NOT a lock on it. The composer works here.
     */
    val isWelcomeThread: StateFlow<Boolean> = _publicKey
        .map {
            MessageRepository.canonicalIdentity(it) ==
                MessageRepository.canonicalIdentity(WelcomeMessage.AUTHOR_PUBLIC_KEY)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True for a `lora!<nodehex>` pseudo-identity thread — a bare/stock
     * Meshtastic node that has proved no OSHI identity, not a normal OSHI
     * contact. `ChatScreen` reads this to show the "unverified · plaintext"
     * marker near the header and the compose box, same wording as the one on
     * each inbound interop bubble ([com.oshi.messenger.data.repository.MessageRepository.sendLoRaInteropReply]).
     */
    val isLoRaInteropThread: StateFlow<Boolean> = _publicKey
        .map { LoRaNodeIdentityMap.isFallbackConversationKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Refuse a send into the system thread and say why, in the user's language.
     * Returns true when the caller must stop.
     */
    private fun refuseIfSystemThread(pk: String): Boolean {
        // Only the LEGACY label is refused — it is not base64 and would throw
        // in `deriveSharedSecret`. The real key sends like any other contact.
        if (pk != WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY) return false
        _errorMessage.value = R.string.chat_system_thread_readonly
        return true
    }

    /**
     * User-facing error channel, as a STRING RESOURCE id rather than free text.
     *
     * Play rejected the 2026-09-16 production submission for "broken
     * functionality", and the reviewer's own evidence screenshot was a toast
     * reading `Public key must be 32 bytes, got 8 (input: oshi.welcome...)` —
     * a `require` message from [CryptoManager.importPublicKey] that reached the
     * screen because every producer here assigned `exception.message` directly.
     *
     * A resource id makes that impossible to do again by accident: there is no
     * way to put an exception into an `Int`. It is also the only way these
     * strings get translated — the channel used to emit English-only literals
     * ("Failed to send image") into all 34 locales. The full exception still
     * goes to Logcat at the point of failure.
     */
    private val _errorMessage = MutableStateFlow<Int?>(null)
    val errorMessage: StateFlow<Int?> = _errorMessage.asStateFlow()
    fun clearError() { _errorMessage.value = null }

    // Reply-to state
    private val _replyingToMessage = MutableStateFlow<Message?>(null)
    val replyingToMessage: StateFlow<Message?> = _replyingToMessage.asStateFlow()

    fun setReplyingTo(message: Message?) {
        _replyingToMessage.value = message
    }

    fun clearReplyingTo() {
        _replyingToMessage.value = null
    }

    // Typing indicator state
    val peerIsTyping: StateFlow<Boolean> = typingIndicatorManager.typingStatus
        .map { it[_publicKey.value] == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun onTextChanged(text: String) {
        val pk = _publicKey.value
        if (pk.isNotEmpty()) {
            typingIndicatorManager.onTextChanged(pk, text)
        }
    }

    // 🔍 Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    // Date-based search
    private val _dateFilterStart = MutableStateFlow<Long?>(null)
    val dateFilterStart: StateFlow<Long?> = _dateFilterStart.asStateFlow()
    private val _dateFilterEnd = MutableStateFlow<Long?>(null)
    val dateFilterEnd: StateFlow<Long?> = _dateFilterEnd.asStateFlow()
    private val _isDateSearchActive = MutableStateFlow(false)
    val isDateSearchActive: StateFlow<Boolean> = _isDateSearchActive.asStateFlow()

    /**
     * __OPEN_SCROLL_2026_11_16__ True once the thread's first DB read has arrived.
     * `messages` starts as an empty list, so "no messages yet" and "not loaded yet"
     * looked the same and every open flashed the empty-conversation card first.
     */
    private val _threadLoaded = MutableStateFlow(false)
    val threadLoaded: StateFlow<Boolean> = _threadLoaded.asStateFlow()

    val messages: StateFlow<List<Message>> = _publicKey
        .filter { it.isNotEmpty() }
        .flatMapLatest { pk ->
            // __CHAT_OPEN_PERF_2026_11_14__ light rows, no eager media hydration — the
            // bubbles on screen resolve their own bytes (`loadMediaData`).
            messageRepository.getThreadMessagesForContact(pk)
                .onEach { _threadLoaded.value = true }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search match navigation index
    private val _searchMatchIndex = MutableStateFlow(0)
    val searchMatchIndex: StateFlow<Int> = _searchMatchIndex.asStateFlow()

    /**
     * Filtered messages based on date range filter only.
     * Text search no longer filters the list; it highlights matches instead.
     */
    val filteredMessages: StateFlow<List<Message>> = combine(
        messages,
        _dateFilterStart,
        _dateFilterEnd
    ) { msgs, dateStart, dateEnd ->
        var result = msgs

        // Apply date filter
        if (dateStart != null || dateEnd != null) {
            result = result.filter { msg ->
                val afterStart = dateStart?.let { msg.timestamp >= it } ?: true
                val beforeEnd = dateEnd?.let { msg.timestamp <= it } ?: true
                afterStart && beforeEnd
            }
        }

        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * __CHAT_OPEN_PERF_2026_11_14__ The thread's rows (date headers, photo stacks,
     * bubbles), built OFF the main thread, once per list change.
     *
     * `ChatScreen` used to call `ChatThreadItems.build` in its body, so every
     * recomposition of that screen — each voice-playback progress tick, each recording
     * amplitude, each typing flag — re-filtered the whole conversation and formatted
     * four dates per message. `Message.equals` compares every rendered field
     * (Message.kt), so the `StateFlow` only re-emits when a row really changed.
     */
    val threadItems: StateFlow<List<com.oshi.messenger.ui.screens.ChatThreadItem>> = filteredMessages
        .map { msgs ->
            com.oshi.messenger.ui.screens.ChatThreadItems.build(
                msgs,
                com.oshi.messenger.ui.screens.ChatDayKey.inDefaultZone()
            )
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * List of message IDs that match the current search query.
     * Used for highlight + navigation (up/down arrows in search bar).
     *
     * F-19: derived from [filteredMessages], not from the raw [messages] flow.
     * The screen renders [filteredMessages]; matching against a different list
     * meant that with a date filter on, the search jump aimed at a message the
     * thread was not showing. Silent payloads are dropped for the same reason
     * (they have no row) - iOS searches an already-filtered list,
     * ChatView.swift:393-402.
     *
     * The match itself stays on the RAW string, which is parity, not a bug:
     * iOS matches raw `plaintextContent` at ChatView.swift:417-448.
     */
    val searchMatches: StateFlow<List<String>> = combine(
        filteredMessages,
        _searchQuery,
        _isSearchActive
    ) { msgs, query, active ->
        if (active && query.isNotBlank()) {
            msgs.filter {
                !OSHIControlPayload.isSilent(it.content) &&
                    it.content.contains(query, ignoreCase = true)
            }.map { it.id }
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The message ID of the currently focused search match (for highlight border).
     */
    val currentSearchMatchId: StateFlow<String?> = combine(
        searchMatches,
        _searchMatchIndex
    ) { matches, index ->
        if (matches.isNotEmpty() && index in matches.indices) matches[index] else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _searchMatchIndex.value = 0
    }

    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) {
            _searchQuery.value = ""
            _searchMatchIndex.value = 0
        }
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchMatchIndex.value = 0
    }

    /** Navigate to the next search match (down arrow). */
    fun nextSearchMatch() {
        val matches = searchMatches.value
        if (matches.isNotEmpty()) {
            _searchMatchIndex.value = (_searchMatchIndex.value + 1) % matches.size
        }
    }

    /** Navigate to the previous search match (up arrow). */
    fun previousSearchMatch() {
        val matches = searchMatches.value
        if (matches.isNotEmpty()) {
            _searchMatchIndex.value = if (_searchMatchIndex.value > 0) {
                _searchMatchIndex.value - 1
            } else {
                matches.size - 1
            }
        }
    }

    // Date filter methods

    fun setDateFilter(startMs: Long?, endMs: Long?) {
        _dateFilterStart.value = startMs
        _dateFilterEnd.value = endMs
        _isDateSearchActive.value = startMs != null || endMs != null
    }

    fun clearDateFilter() {
        _dateFilterStart.value = null
        _dateFilterEnd.value = null
        _isDateSearchActive.value = false
    }

    /** Quick date preset: filter messages from today only */
    fun filterToday() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        setDateFilter(calendar.timeInMillis, System.currentTimeMillis())
    }

    /** Quick date preset: filter messages from the last 7 days */
    fun filterThisWeek() {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -7)
        setDateFilter(calendar.timeInMillis, System.currentTimeMillis())
    }

    /** Quick date preset: filter messages from the last 30 days */
    fun filterThisMonth() {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.MONTH, -1)
        setDateFilter(calendar.timeInMillis, System.currentTimeMillis())
    }

    val contact: StateFlow<Contact?> = _publicKey
        .filter { it.isNotEmpty() }
        .flatMapLatest { pk ->
            messageRepository.getContact(pk)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * __SHARED_NICKNAME_2026_09_22__ The nickname the peer chose for themselves (from
     * their profile payload), or null. Shown as secondary text in the alias editor so
     * the user sees it and can still override it with an alias.
     */
    val sharedNickname: StateFlow<String?> = combine(
        _publicKey,
        com.oshi.messenger.data.model.PeerNicknameStore.nicknames
    ) { pk, map ->
        if (pk.isEmpty()) null else com.oshi.messenger.data.model.PeerNickname.lookup(map, pk)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * __SHARED_NICKNAME_2026_09_22__ What every DISPLAY surface of this chat shows:
     * user alias > peer's shared nickname > placeholder/key prefix. The alias EDITOR
     * keeps reading `contact.alias` so a nickname is never saved as an alias.
     * Null until the contact row is loaded (callers keep their old fallback then).
     */
    val contactDisplayName: StateFlow<String?> = combine(contact, sharedNickname) { c, nick ->
        c?.let { com.oshi.messenger.data.model.PeerNickname.resolve(it.alias, nick, it.publicKey) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Blocked state for the open conversation.
     *
     * 🔧 P0 FIX (2026-08-13 chat-1to1 audit #3). This used to be
     * `MutableStateFlow(false)` flipped by `toggleBlockContact()` and NOTHING ELSE:
     *
     *  1. nothing was persisted — `MessageRepository.blockContact()` had zero callers;
     *  2. it re-initialised to `false` on every screen mount, so re-opening a "blocked"
     *     chat offered **Block** again;
     *  3. no receive path consulted it, so a blocked contact still delivered, still
     *     rang and still notified.
     *
     * The only observable effect of "blocking" someone was that your own composer hid
     * until you left the screen. It is now derived from the persisted `Contact.isBlocked`
     * column (the same one `BlockedContactsScreen` lists via `WHERE isBlocked = 1`), so
     * it survives screen exit, process death and reinstall-from-backup, and the
     * repository enforces it on every inbound path.
     *
     * iOS equivalent: `BlockedContactsManager.swift:106,130` with enforcement at nine
     * receive/ring sites (`MessageManager.swift:5390,7858,7911`, etc.).
     */
    val isContactBlocked: StateFlow<Boolean> = contact
        .map { it?.isBlocked == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * File a report about this contact.
     *
     * `ReportManager` and its Settings -> Privacy -> Reported Items screen have
     * both existed and been tested since the reporting subsystem landed, and
     * nothing anywhere opened them: the chat overflow menu had no Report entry,
     * so the screen was unreachable and no report could ever be filed. iOS puts
     * the same action on the contact sheet (ContactActionsView.swift:253+).
     *
     * The record is written locally BEFORE delivery is attempted, so an offline
     * report is never lost.
     */
    fun reportContact(
        reason: ReportReason,
        details: String,
        // __REPORT_OUTCOME_2026_09_23__ the screen used to say "Report sent" before the
        // POST had even started; it now says what actually happened.
        onResult: (com.oshi.messenger.data.model.ReportDeliveryState) -> Unit = {}
    ) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        val name = contact.value?.alias
        appScope.launch {
            val filed = reportManager.fileReport(
                subject = ReportSubject.CONTACT,
                subjectId = pk,
                subjectName = name,
                reason = reason,
                details = details
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(filed.state) }
        }
    }

    /** True when this contact is already in the local report log. */
    fun hasReportedContact(): Boolean {
        val pk = _publicKey.value
        return pk.isNotEmpty() && reportManager.hasReportedContact(pk)
    }

    fun toggleBlockContact() {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        val blockNow = !isContactBlocked.value
        viewModelScope.launch {
            if (blockNow) messageRepository.blockContact(pk)
            else messageRepository.unblockContact(pk)
            // `isContactBlocked` re-emits from the Contact flow; no local mirror to
            // drift out of sync.
        }
    }

    // All conversations for forward picker
    val allConversations: StateFlow<List<Conversation>> = messageRepository.getConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ────────────────────────────────────────────────────────────────────────
    // READ STATE
    //
    // 🔧 2026-08-24. Before this, the ONLY thing that ever marked a 1:1 thread read
    // was `loadConversation` below — i.e. entering the screen. A message that
    // arrived while the chat was already open stayed pending until the user backed
    // out and reopened it. iOS has a second trigger: `ChatView.swift:863-866` runs
    // `onChange(of: messageManager.messages.count) { if isVisible { markMessagesAsRead() } }`,
    // where `isVisible` is set on `.onAppear` and cleared on `.onDisappear`
    // (`ChatView.swift:826-831`).
    //
    // [startReadWatch] is that trigger. It collects the thread's unread count — a
    // Room flow, so it re-emits the moment an incoming row is inserted — and marks
    // the thread read whenever the count is non-zero AND this thread is the one on
    // screen.
    //
    // ⚠️ The visibility gate is the whole safety story. `viewModelScope` outlives
    // backgrounding, so without it a message arriving while the phone is in the
    // user's pocket would be stamped read and the sender would be told ✓✓ Read for
    // something nobody saw — worse than the bug being fixed.
    // [ActiveConversation] is written ONLY from the screen's `ON_RESUME` /
    // `ON_PAUSE` observer (`ChatScreen.kt`), so it means "foregrounded AND this
    // exact thread on screen" and nothing looser.
    //
    // The flow is also its own idempotence: `markConversationAsRead` flips the rows,
    // the count re-emits as 0, and the next emission does nothing — and the receipt
    // is gated on rows actually flipping (`MessageRepository.markConversationAsRead`),
    // so an already-read thread puts nothing on the wire.
    // ────────────────────────────────────────────────────────────────────────

    private var readWatchJob: kotlinx.coroutines.Job? = null

    /**
     * __OPEN_AT_THE_FIRST_UNREAD__ The unread incoming ids of this thread as they were
     * WHEN IT OPENED, oldest first; `null` until that read has completed.
     *
     * iOS lands a thread on its first unread message and draws an "Unread messages"
     * line there (`ChatView.swift` `openUnreadAnchorId`, __OPEN_AT_THE_FIRST_UNREAD_2026_10_16__
     * + __UNREAD_HAD_NO_LINE_2026_11_07__). Android landed on the newest message and
     * marked the whole thread read in the same instant, so the boundary was gone
     * before anything could draw it. Both read-marks ([loadConversation] and the
     * [startReadWatch] collector) now wait for this capture first.
     */
    private val _openUnreadIds = MutableStateFlow<List<String>?>(null)
    val openUnreadIds: StateFlow<List<String>?> = _openUnreadIds.asStateFlow()
    private var unreadCaptureKey: String? = null
    private var unreadCaptureJob: kotlinx.coroutines.Job? = null

    /** Starts (once per thread) the capture behind [openUnreadIds]; join it before any read-mark. */
    private fun captureOpenUnread(publicKey: String): kotlinx.coroutines.Job {
        unreadCaptureJob?.let { if (unreadCaptureKey == publicKey) return it }
        unreadCaptureKey = publicKey
        _openUnreadIds.value = null
        return viewModelScope.launch {
            _openUnreadIds.value = runCatching { messageRepository.getUnreadIncomingIds(publicKey) }
                .getOrDefault(emptyList())
        }.also { unreadCaptureJob = it }
    }

    /**
     * The thread is on screen and the app is foregrounded (screen `ON_RESUME`).
     *
     * Takes the key from the SCREEN rather than reading `_publicKey`, because the
     * lifecycle observer can fire before `loadConversation` has populated it.
     */
    fun onChatVisible(publicKey: String) {
        if (publicKey.isEmpty()) return
        com.oshi.messenger.service.ActiveConversation.enterDirect(publicKey)
        // (N10) This thread's banner has no job left once the thread is on
        // screen — even when nothing flips to read (already read on another
        // device), which is the case `markConversationAsRead` skips.
        messageRepository.dismissMessageNotification(publicKey)
        startReadWatch(publicKey)
    }

    /** The thread left the screen — backgrounded, navigated away, or disposed. */
    fun onChatHidden(publicKey: String) {
        readWatchJob?.cancel()
        readWatchJob = null
        if (publicKey.isNotEmpty()) {
            com.oshi.messenger.service.ActiveConversation.leaveDirect(publicKey)
        }
    }

    private fun startReadWatch(publicKey: String) {
        readWatchJob?.cancel()
        val capture = captureOpenUnread(publicKey)
        readWatchJob = viewModelScope.launch {
            capture.join()
            messageRepository.getUnreadCountForContact(publicKey).collect { unread ->
                if (unread > 0 && com.oshi.messenger.service.ActiveConversation.isDirectActive(publicKey)) {
                    messageRepository.markConversationAsRead(publicKey)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        readWatchJob?.cancel()
        val pk = _publicKey.value
        if (pk.isNotEmpty()) com.oshi.messenger.service.ActiveConversation.leaveDirect(pk)
    }

    fun loadConversation(publicKey: String) {
        // Reset search state when loading new conversation
        _isSearchActive.value = false
        _searchQuery.value = ""
        clearDateFilter()

        _publicKey.value = publicKey

        // Mark as read — after the open-at-first-unread capture has seen the unread rows.
        val capture = captureOpenUnread(publicKey)
        viewModelScope.launch {
            capture.join()
            messageRepository.markConversationAsRead(publicKey)
        }
        // Skip the polling wait when the user just opened the chat —
        // typically they tapped an FCM notification and the message hasn't
        // been pulled yet.
        try { vpsClient.triggerImmediatePoll() } catch (_: Exception) {}
    }

    /**
     * __SCHEDULE_CALL_REDESIGN_2026_09_29__ Send a scheduled-call invitation, byte-for-byte
     * the JSON iOS's `ScheduledCallEvent` Codable decodes (iOS `sendScheduledCallEvent`):
     * dates are Apple reference-date SECONDS, `duration` is seconds, `id` an uppercase UUID.
     * Android's own bubble already reads either epoch.
     */
    fun sendScheduledCall(peerName: String, myName: String, whenMs: Long, durationMin: Int, title: String?) {
        val pk = _publicKey.value
        if (pk.isEmpty() || refuseIfSystemThread(pk)) return
        val appleEpochOffsetSec = 978_307_200.0
        fun apple(ms: Long) = ms / 1000.0 - appleEpochOffsetSec
        val me = runCatching { cryptoManager.getPublicKeyString() }.getOrDefault("")
        val json = org.json.JSONObject().apply {
            put("id", java.util.UUID.randomUUID().toString().uppercase())
            put("conversationId", pk)
            put("conversationName", peerName)
            put("createdBy", me)
            put("createdByName", myName.ifBlank { me.take(8) })
            put("scheduledTime", apple(whenMs))
            put("creatorTimezone", java.util.TimeZone.getDefault().id)
            if (!title.isNullOrBlank()) put("title", title.trim())
            put("duration", durationMin * 60.0)
            put("responses", org.json.JSONObject())
            put("createdAt", apple(System.currentTimeMillis()))
            put("status", "pending")
        }
        viewModelScope.launch {
            reportSendResult(messageRepository.sendTextMessage(pk, OSHIControlPayload.SCHEDULED_CALL + json.toString()))
        }
    }

    fun sendMessage(typed: String) {
        // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ Normalise pasted line breaks
        // BEFORE a reply wraps the text in JSON (the repository leaves envelopes
        // alone). See MessageSendPolicy.normalizeLineBreaks.
        val content = com.oshi.messenger.service.MessageSendPolicy.normalizeLineBreaks(typed)
        val pk = _publicKey.value
        if (pk.isEmpty() || content.isBlank()) return
        if (refuseIfSystemThread(pk)) return
        typingIndicatorManager.userStoppedTyping(pk)

        // __INTEROP_REPLY_2026_11_09__ A `lora!<nodehex>` thread is a bare
        // Meshtastic node, not an OSHI contact — there is no key to encrypt
        // to. Route to the plaintext lane INSTEAD of the normal encrypted
        // send, never through it: `sendTextMessage` would reach the ratchet
        // with a string that is not a public key and fail there. Reply-quote
        // formatting is skipped too — it is an OSHI-only convention a stock
        // Meshtastic client has no way to read.
        if (LoRaNodeIdentityMap.isFallbackConversationKey(pk)) {
            _replyingToMessage.value = null
            viewModelScope.launch {
                reportSendResult(messageRepository.sendLoRaInteropReply(pk, content))
            }
            return
        }

        val replyMsg = _replyingToMessage.value
        if (replyMsg != null) {
            // Send as reply with iOS-compatible prefix format
            val replyContent = messageActions.createReplyContent(replyMsg, content)
            viewModelScope.launch {
                reportSendResult(messageRepository.sendTextMessage(pk, replyContent))
            }
            _replyingToMessage.value = null
        } else {
            viewModelScope.launch {
                reportSendResult(messageRepository.sendTextMessage(pk, content))
            }
        }
    }

    /**
     * 🔧 P0 FIX (device walkthrough #2, 2026-08-13).
     *
     * `sendTextMessage` returns `Result<Message>` and every call site here simply
     * DROPPED it. A send that threw (`bad base-64` on a malformed recipient key)
     * produced no toast, no snackbar, no dialog and no state change — while the
     * bubble stayed on screen stamped ✓✓ Read.
     *
     * Failure now surfaces two ways, matching iOS:
     *  - persistently, on the bubble: `MessageRepository.markSendFailed` stamps the
     *    row `isFailed`, which is what `ChatScreen`'s red "Not delivered · tap to
     *    retry" row and the "Failed" delivery badge read (iOS equivalent:
     *    `DeliveryStatus.failed` → `DeliveryMethodBadge.swift:54,74`);
     *  - transiently, through the EXISTING `_errorMessage` channel, which
     *    `ChatScreen.kt:179-185` already turns into a toast (it was only ever fed
     *    by the image-too-large path). iOS raises `showingError` / `errorMessage`
     *    on the same failure (`NewMessageView.swift:740-779`). Reusing the
     *    channel rather than adding a second one keeps a single error surface.
     */
    /**
     * __CASHU_SEND_READY_2026_11_09__ The one place a Cashu token leaves a 1:1
     * composer. [onDone] gets null once it went out, or the string resource to
     * show. Not [sendMessage]: that one reports through a toast AFTER the
     * composer already said "Sent", and wraps a pending reply around the token.
     */
    fun sendCashuToken(raw: String, onDone: (Int?) -> Unit) {
        val pk = _publicKey.value
        if (pk.isEmpty() || refuseIfSystemThread(pk)) { onDone(R.string.chat_send_failed); return }
        viewModelScope.launch {
            val result = messageRepository.sendTextMessage(pk, raw)
            val error = result.exceptionOrNull()
            if (error != null) {
                android.util.Log.e("ChatViewModel", "❌ Cashu send failed: ${error.message}", error)
            }
            onDone(
                when (error) {
                    null -> null
                    is com.oshi.messenger.service.MessageTooLongException -> R.string.cashu_too_long
                    else -> R.string.chat_send_failed
                }
            )
        }
    }

    private fun reportSendResult(result: Result<*>) {
        val error = result.exceptionOrNull() ?: return
        android.util.Log.e("ChatViewModel", "❌ Send failed: ${error.message}", error)
        _errorMessage.value = R.string.chat_send_failed
    }

    /**
     * 📊 Send a poll. It is an ordinary message: it goes out as text, it lands
     * in the thread, and it draws as a card because its prefix is in the
     * catalog. Not routed through [sendMessage] on purpose — that one wraps the
     * content in a reply envelope when a reply is pending, and a poll is not a
     * reply to anything.
     */
    fun sendPoll(poll: com.oshi.messenger.data.model.PollMessage) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        if (refuseIfSystemThread(pk)) return
        viewModelScope.launch {
            reportSendResult(messageRepository.sendTextMessage(pk, poll.toMessageString()))
        }
    }

    /**
     * 📊 Cast a ballot.
     *
     * Recorded LOCALLY first, then sent: the bar has to move under the finger
     * without waiting for a round trip that, on a mesh, may never come back.
     * The ballot itself travels through [MessageRepository.sendPollBallot],
     * which does not insert a row — a ballot is not a line in the thread.
     *
     * `PollStore` is already attached by the time this runs: the bubble the tap
     * came from attaches it when it first composes.
     */
    fun sendPollVote(poll: com.oshi.messenger.data.model.PollMessage, picks: List<Int>) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        val me = runCatching { cryptoManager.getPublicKeyString() }.getOrDefault("")
        val vote = com.oshi.messenger.data.model.PollVotePayload(pollId = poll.id, picks = picks)
        if (me.isNotEmpty()) {
            com.oshi.messenger.service.PollStore.record(vote, me)
        }
        viewModelScope.launch {
            messageRepository.sendPollBallot(pk, vote.toMessageString())
        }
    }

    /**
     * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Take this conversation off the
     * relay, or put it back, and ask the other side to do the same.
     *
     * Our own state is set BEFORE the request goes out, deliberately: the
     * request itself must travel under the new rule. Setting it afterwards
     * would send the one message that asks for the relay to stop OVER the
     * relay. Mirrors `MessageManager.setOfflineOnly` in Swift.
     *
     * Sent through [MessageRepository.sendTextMessage], like a poll and like
     * every other sentinel the catalog marks `.rendered` — NOT through
     * `sendPollBallot`, which by design inserts no row. The request is a line
     * in the thread on purpose: seeing when this conversation left the relay,
     * and who asked, is the whole point of rendering it. iOS sends the same
     * payload with `skipLocalMessage: false` for the request and `true` for
     * the answer, and the two platforms have to agree on which of the two
     * appears — verified on the device: the ballot path toggled the menu
     * label and left the thread empty.
     */
    fun setOfflineOnly(on: Boolean) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        if (refuseIfSystemThread(pk)) return
        com.oshi.messenger.service.OfflineOnlyStore.attach(appContext)
        com.oshi.messenger.service.OfflineOnlyStore.set(on, pk)
        val req = com.oshi.messenger.data.model.OfflineOnlyRequest.create(on)
        viewModelScope.launch {
            reportSendResult(messageRepository.sendTextMessage(pk, req.toMessageString()))
        }
    }

    // ── __ALIVE_CHECK_IN_2026_11_04__ "I'm alive" ──────────────────────────────
    //
    // Three verbs and one flow. Each verb writes the LOCAL state first and sends
    // second, in that order and deliberately: the local half is what the
    // countdown and the deadline are computed from, and it is true the moment
    // the user presses the button. A send that fails leaves a user who did check
    // in with a deadline that did not move — which is the one outcome that turns
    // a safety feature into a false alarm about its own owner.
    //
    // Mirrors `MessageManager.confirmAlive` / `setAliveCheckIn` in Swift.

    /**
     * The check-in state of the conversation currently open, or an empty entry.
     *
     * Combined off the store's whole map rather than holding one entry, because
     * the peer's half arrives on the receive path and has to show up here
     * without the screen asking for it.
     */
    val aliveCheckIn: StateFlow<com.oshi.messenger.service.AliveCheckInStore.Entry> =
        combine(
            _publicKey,
            com.oshi.messenger.service.AliveCheckInStore.entries,
        ) { pk, entries ->
            if (pk.isEmpty()) {
                com.oshi.messenger.service.AliveCheckInStore.Entry()
            } else {
                com.oshi.messenger.service.AliveCheckInStore.entryFor(pk)
                    ?: com.oshi.messenger.service.AliveCheckInStore.Entry()
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.oshi.messenger.service.AliveCheckInStore.Entry(),
        )

    /** "I'm alive." */
    fun confirmAlive() {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        if (refuseIfSystemThread(pk)) return
        com.oshi.messenger.service.AliveCheckInStore.attach(appContext)
        val event = com.oshi.messenger.service.AliveCheckInStore.confirmMine(pk) ?: return
        com.oshi.messenger.service.AliveCheckInWatcher.reschedule(appContext)
        // appScope, not viewModelScope: a confirmation sent as the user leaves
        // the screen must still go out. This is the message whose absence is the
        // alarm.
        appScope.launch {
            reportSendResult(messageRepository.sendTextMessage(pk, event.toMessageString()))
        }
    }

    /** Start, or re-time, our own commitment in this conversation. */
    fun enableAliveCheckIn(intervalSeconds: Long) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        if (refuseIfSystemThread(pk)) return
        com.oshi.messenger.service.AliveCheckInStore.attach(appContext)
        val event = com.oshi.messenger.service.AliveCheckInStore.enableMine(pk, intervalSeconds)
        com.oshi.messenger.service.AliveCheckInWatcher.reschedule(appContext)
        appScope.launch {
            reportSendResult(messageRepository.sendTextMessage(pk, event.toMessageString()))
        }
    }

    /**
     * Stop our own commitment, and say so.
     *
     * The "off" event travels for the same reason the offline-only switch's does:
     * a schedule that went quiet without a word would leave the other side
     * counting down to a deadline nobody intends to meet, and the first thing
     * they would see is a red dot for a check-in that was deliberately ended.
     */
    fun disableAliveCheckIn() {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        if (refuseIfSystemThread(pk)) return
        com.oshi.messenger.service.AliveCheckInStore.attach(appContext)
        val event = com.oshi.messenger.service.AliveCheckInStore.disableMine(pk)
        com.oshi.messenger.service.AliveCheckInWatcher.reschedule(appContext)
        appScope.launch {
            reportSendResult(messageRepository.sendTextMessage(pk, event.toMessageString()))
        }
    }

    /** This device's own public key, for drawing "you" on a poll. */
    fun myPublicKey(): String = runCatching { cryptoManager.getPublicKeyString() }.getOrDefault("")

    /**
     * Send a location message
     */
    fun sendLocationMessage(locationMessage: LocationMessage) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        // Live updates and the stop signal keep arriving after the user has left the
        // chat; on viewModelScope they would be cancelled with the screen.
        appScope.launch {
            val messageContent = locationMessage.toMessageString()

            // For live location updates, update existing message instead of creating new
            if (locationMessage.isUpdate && locationMessage.sessionId != null) {
                messageRepository.updateLocationMessage(pk, messageContent, locationMessage.sessionId!!)
            } else if (locationMessage.isStopped && locationMessage.sessionId != null) {
                messageRepository.updateLocationMessage(pk, messageContent, locationMessage.sessionId!!)
            } else {
                // New location share (one-time or initial live)
                messageRepository.sendLocationMessage(pk, messageContent)
            }
        }
    }

    /**
     * Forward a message to another contact
     * Creates a forwarded message with prefix (compatible with iOS)
     */
    fun forwardMessage(content: String, recipientPublicKey: String) {
        if (content.isBlank() || recipientPublicKey.isBlank()) return

        viewModelScope.launch {
            // Create forwarded message with prefix (compatible with iOS ForwardedMessage format)
            val forwardedContent = createForwardedMessage(content)
            messageRepository.sendTextMessage(recipientPublicKey, forwardedContent)
        }
    }

    /**
     * Create forwarded message string (compatible with iOS)
     */
    private fun createForwardedMessage(originalContent: String): String {
        // Check if already forwarded
        val forwardPrefix = "➡️FORWARDED➡️"
        if (originalContent.startsWith(forwardPrefix)) {
            // Already forwarded - increment count would require parsing JSON
            // For simplicity, just forward as-is
            return originalContent
        }

        // Create new forwarded message JSON. Built with JSONObject so a sender
        // name or content containing " / \ / newline stays valid JSON — the old
        // string-interpolated form produced malformed JSON that iOS's Codable
        // ForwardedMessage failed to decode, rendering the raw bubble on iPhone.
        val senderName = contactDisplayName.value ?: contact.value?.alias ?: "Unknown"
        val json = org.json.JSONObject().apply {
            put("originalSenderName", senderName)
            put("content", originalContent)
            put("isForwarded", true)
            put("forwardCount", 1)
        }
        return "$forwardPrefix$json"
    }

    /**
     * Delete a message (for me only)
     */
    fun deleteMessage(messageId: String) {
        // __CHAT_OPEN_PERF_2026_11_14__ bytes a bubble resolved must not outlive the row.
        com.oshi.messenger.ui.media.BubbleBytesCache.evict(messageId)
        viewModelScope.launch {
            messageRepository.deleteMessage(messageId)
        }
    }

    /**
     * Delete a message for everyone
     * Marks message as deleted-for-everyone and notifies the other party
     */
    fun deleteMessageForEveryone(messageId: String) {
        // __CHAT_OPEN_PERF_2026_11_14__ bytes a bubble resolved must not outlive the row.
        com.oshi.messenger.ui.media.BubbleBytesCache.evict(messageId)
        viewModelScope.launch {
            messageRepository.deleteMessageForEveryone(messageId)
        }
    }

    /**
     * 🔒 A view-once photo/video has been closed by the viewer — destroy it.
     *
     * Fires on DISMISS, not on open, exactly like iOS
     * (`MediaMessageView.swift:102-111` → `:1108` → `MessageManager.markViewOnceAsViewed`,
     * `OSHI/MessageManager.swift:6978`), so a mis-tap that closes instantly still burns
     * the message — same as the iPhone.
     */
    fun markViewOnceOpened(messageId: String) {
        // __CHAT_OPEN_PERF_2026_11_14__ bytes a bubble resolved must not outlive the row.
        com.oshi.messenger.ui.media.BubbleBytesCache.evict(messageId)
        viewModelScope.launch {
            messageRepository.markViewOnceOpened(messageId)
        }
    }

    /**
     * The bytes of ONE attachment, on demand — M-17's escape hatch.
     *
     * `MessageRepository.hydrateMedia` re-attaches file-backed blobs newest-first
     * under iOS's own cache bounds (60 items / 96 MB, `MediaAttachmentCache.swift:37-38`).
     * On iOS that budget governs a *cache*, and a miss is re-read inside the bubble's
     * own `.task`. On Android it governed the ONLY read path the list had, so the
     * 61st-oldest photo in a thread reached the UI with `mediaData = null` and drew an
     * empty bubble **forever, with no retry** — the budget degraded to data loss
     * instead of to lazy loading.
     *
     * This is the missing re-read. `MessageRepository.loadMediaData` already existed
     * and had no caller outside the repository; the bubble now has one.
     * `MediaAttachmentStore`'s own LRU means a re-read usually never touches disk.
     */
    suspend fun loadMediaData(messageId: String): ByteArray? =
        messageRepository.loadMediaData(messageId)

    /** __DOC_STREAM_2026_11_11__ Stream an attachment into [out] (document save). */
    suspend fun copyMediaTo(messageId: String, out: java.io.OutputStream): Boolean =
        messageRepository.copyMediaTo(messageId, out)

    /**
     * Edit a message's content
     * Only allowed for own messages within 10 minutes of sending
     */
    fun editMessage(messageId: String, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            messageRepository.editMessage(messageId, newContent)
        }
    }

    /**
     * Send a voice message
     * @param audioData the raw audio bytes (m4a format)
     * @param durationSeconds duration of the recording in seconds
     */
    fun sendVoiceMessage(audioData: ByteArray, durationSeconds: Float, isViewOnce: Boolean = false) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        viewModelScope.launch {
            // Format duration as "M:SS"
            val minutes = (durationSeconds / 60).toInt()
            val seconds = (durationSeconds % 60).toInt()
            val durationFormatted = "$minutes:${seconds.toString().padStart(2, '0')}"

            messageRepository.sendMediaMessage(
                recipientPublicKey = pk,
                mediaType = MediaType.AUDIO,
                mediaData = audioData,
                caption = "[AUDIO] $durationFormatted",
                // __VOICE_PREVIEW_2026_09_29__ Same wire flag as photos/videos; iOS plays
                // it once in `ViewOnceAudioPlayer` and wipes it, as does Android.
                isViewOnce = isViewOnce
            )
        }
    }

    /**
     * Send an image message
     * @param isViewOnce If true, image disappears after viewing (iOS compatible)
     */
    fun sendImageMessage(imageData: ByteArray, caption: String? = null, isViewOnce: Boolean = false) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        if (refuseIfSystemThread(pk)) return

        viewModelScope.launch {
            val result = messageRepository.sendMediaMessage(
                recipientPublicKey = pk,
                mediaType = MediaType.IMAGE,
                mediaData = imageData,
                caption = caption,
                isViewOnce = isViewOnce
            )
            result.onFailure { e ->
                android.util.Log.e("ChatViewModel", "❌ Image send failed: ${e.message}", e)
                _errorMessage.value = R.string.chat_image_send_failed
            }
        }
    }

    /**
     * __LOCAL_GIF_LIBRARY_2026_09_13__ Envoyer un GIF (paquet local ou GIPHY).
     *
     * Passe par [MediaType.IMAGE] — le seul type dont la bulle sait dessiner des
     * pixels — mais le dépôt RENIFLE les octets ([GifBytes]) et, les reconnaissant
     * GIF, saute la recompression et étiquette le fil `gif`/`image/gif`, ce qui est
     * exactement ce qu'iOS émet. Le nom de fichier part avec: il porte
     * l'extension, et sur un pair qui se fie à l'extension c'est ce qui décide
     * entre une animation et une image morte.
     *
     * Pas de `isViewOnce`: iOS n'en propose pas non plus depuis ce sélecteur.
     */
    fun sendGifMessage(gifData: ByteArray, fileName: String) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        if (refuseIfSystemThread(pk)) return

        viewModelScope.launch {
            val result = messageRepository.sendMediaMessage(
                recipientPublicKey = pk,
                mediaType = MediaType.IMAGE,
                mediaData = gifData,
                caption = null,
                isViewOnce = false,
                attachmentFileName = fileName
            )
            result.onFailure { e ->
                android.util.Log.e("ChatViewModel", "❌ GIF send failed: ${e.message}", e)
                _errorMessage.value = R.string.chat_gif_send_failed
            }
        }
    }

    /**
     * Send a video message
     * @param isViewOnce If true, video disappears after viewing (iOS compatible)
     */
    fun sendVideoMessage(videoData: ByteArray, caption: String? = null, isViewOnce: Boolean = false) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        viewModelScope.launch {
            messageRepository.sendMediaMessage(
                recipientPublicKey = pk,
                mediaType = MediaType.VIDEO,
                mediaData = videoData,
                caption = caption,
                isViewOnce = isViewOnce
            )
        }
    }

    /**
     * __VIDEO_NOTE_2026_09_24__ Send a round video note (docs/VIDEO_NOTE_SPEC.md). [videoData]
     * is the final MP4 from `VideoNoteEncoder`; it rides the ordinary encrypted video path
     * with the two optional wire keys. On the app scope so leaving the chat right after
     * tapping send cannot cancel the upload.
     */
    fun sendVideoNote(videoData: ByteArray, durationMs: Long) {
        val pk = _publicKey.value
        if (pk.isEmpty() || videoData.isEmpty()) return
        if (refuseIfSystemThread(pk)) return
        appScope.launch {
            val result = messageRepository.sendMediaMessage(
                recipientPublicKey = pk,
                mediaType = MediaType.VIDEO,
                mediaData = videoData,
                caption = null,
                isViewOnce = false,
                attachmentFileName = com.oshi.messenger.service.media.VideoNote.fileName(),
                videoNoteDurationMs = durationMs
            )
            result.onFailure { _errorMessage.value = R.string.chat_send_failed }
        }
    }

    /**
     * Send a document message
     */
    fun sendDocumentMessage(documentData: ByteArray, filename: String) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        viewModelScope.launch {
            messageRepository.sendMediaMessage(
                recipientPublicKey = pk,
                mediaType = MediaType.DOCUMENT,
                mediaData = documentData,
                // Local display name. `DocumentMessageContent` strips the marker
                // (`ChatScreen.kt`, `filename.removePrefix("[DOCUMENT]")`), which is
                // this app's own convention for a column `Message` does not have.
                caption = "[DOCUMENT] $filename",
                isViewOnce = false,  // Documents don't support view once
                // 🔧 FIX (2026-08-24): and the name the PEER sees. Without this the
                // wire carried `<messageId>.dat` and both an iPhone and another
                // Android showed a UUID where the sender picked `Contrat-2026.pdf`.
                attachmentFileName = filename
            )
        }
    }

    /**
     * The OSHI contacts that can be SHARED into this conversation.
     *
     * iOS's picker lists its `messageManager.conversations`
     * (`OSHI/ContactSelectorView.swift:29-31`) — i.e. reachable OSHI identities,
     * NOT the phone's address book. This is the Android equivalent, minus the
     * person you are talking to: offering to share Alice's card *with Alice* is a
     * dead option, and iOS's list is likewise the conversation you are not in.
     */
    val shareableContacts: StateFlow<List<Contact>> = combine(
        messageRepository.getAllContacts(),
        _publicKey
    ) { contacts, currentPeer ->
        contacts.filter { it.publicKey != currentPeer && !it.isBlocked }
            .sortedBy { it.alias.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Share an OSHI contact into this conversation.
     *
     * 🔴 This is the call site `ContactShareManager` never had. The chain, end to
     * end: attachment menu "Share contact" (`ChatScreen.kt`, `onContactClick`) →
     * `OshiContactPickerSheet` → here → `ContactShareManager.shareContactInApp` →
     * [sendContactCard] → `MessageRepository.sendMediaMessage(CONTACT)` → the V2
     * blob / inline media wire → iOS `v2DeliverFile` (`MessageManager+V2.swift:1010`,
     * `mediaType = .contact`) → `MediaMessageView.swift:65` `decodeContactCard` →
     * `ContactCardView`.
     */
    fun shareOshiContact(publicKey: String, alias: String?) {
        if (publicKey.isBlank()) return
        contactShareManager.shareContactInApp(
            chatViewModel = this,
            displayName = alias?.takeIf { it.isNotBlank() } ?: publicKey.take(8),
            publicKey = publicKey
        )
    }

    /**
     * Send a CONTACT-typed media message carrying the iOS-shaped contact-card body
     * ([com.oshi.messenger.data.model.ContactCardWire]). The recipient renders it
     * as an in-app contact card (avatar + alias + key prefix, tap to open a chat
     * with that contact) — `ContactCardBubble` on Android, `ContactCardView` on iOS.
     *
     * The caption is a LOCAL display marker only
     * ([com.oshi.messenger.data.model.OSHIControlPayload.CONTACT_CAPTION_PREFIX]);
     * neither platform reads the card out of it.
     */
    fun sendContactCard(payloadJson: ByteArray, displayName: String) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        viewModelScope.launch {
            messageRepository.sendMediaMessage(
                recipientPublicKey = pk,
                mediaType = MediaType.CONTACT,
                mediaData = payloadJson,
                caption = OSHIControlPayload.CONTACT_CAPTION_PREFIX + displayName,
                isViewOnce = false
            )
        }
    }

    /**
     * Navigation request emitted when the user taps an in-app contact card
     * in a message bubble. The hosting screen (ChatScreen) collects this and
     * forwards to MainActivity, which navigates to chat/{publicKey}.
     *
     * The contact row is created in the local DB if missing so that the
     * destination chat resolves with a sensible alias.
     */
    private val _openChatRequest = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openChatRequest: SharedFlow<String> = _openChatRequest.asSharedFlow()

    fun openChatWithContact(publicKey: String, alias: String?) {
        if (publicKey.isBlank()) return
        viewModelScope.launch {
            // Ensure a contact row exists with the supplied alias so the
            // destination chat header / contacts list resolve a name.
            // - If contact missing: addContact() creates it
            // - If contact present and we have a non-blank alias: update it
            //   (no-op if user already has a custom alias they prefer)
            val resolvedAlias = alias?.takeIf { it.isNotBlank() }
                ?: (publicKey.take(8) + "...")
            runCatching {
                val existing = messageRepository.getContact(publicKey).first()
                if (existing == null) {
                    messageRepository.addContact(publicKey, resolvedAlias)
                }
            }
            _openChatRequest.emit(publicKey)
        }
    }

    // ============================================================================
    // PINNED MESSAGE (6A.6)
    // ============================================================================

    /**
     * The pinned message for the current conversation.
     * Combines the pinnedMessages map from the repository with the messages list
     * to resolve the actual Message object.
     */
    val pinnedMessage: StateFlow<Message?> = combine(
        messageRepository.pinnedMessages,
        messages,
        _publicKey
    ) { pinnedMap, msgs, pk ->
        val pinnedId = pinnedMap[pk] ?: return@combine null
        // Case-insensitive for the same reason the group paths are: an iPhone's pin
        // targets an id it renders from a UUID, so the spelling can differ from ours.
        msgs.find { it.id.equals(pinnedId, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** True when [messageId] is the pinned message of the open conversation. */
    fun isPinned(messageId: String): Boolean =
        pinnedMessage.value?.id?.equals(messageId, ignoreCase = true) == true

    /**
     * 📌 Pin / unpin a message in the open 1:1 conversation, and tell the peer.
     *
     * RP-3 of the 2026-08-24 react-reply audit: `MessageRepository.pinMessage` /
     * `unpinMessage` had ZERO callers, so the pinned-message banner in `ChatScreen`
     * (and this ViewModel's `pinnedMessage`) read a map nothing ever wrote. This is the
     * sender half; `MessageRepository.init`'s `addPinCallback` registration is the
     * receiver half, and `togglePinMessage` is what puts the 🔧ACTION🔧 on the wire.
     */
    fun togglePin(messageId: String) {
        val pk = _publicKey.value
        if (pk.isEmpty() || messageId.isEmpty()) return
        viewModelScope.launch {
            messageRepository.togglePinMessage(pk, messageId)
        }
    }

    // ============================================================================
    // TRANSLATE (RP-2)
    // ============================================================================

    /**
     * What the translate sheet should be showing.
     *
     * Errors carry a **string resource id**, not English text: the sheet has to speak the
     * user's language in all 34 locales, and each failure mode is a different problem
     * with a different remedy. A single "unavailable" line — or worse, a spinner that
     * never resolves — is what made Translate look broken.
     */
    sealed class TranslationState {
        object Idle : TranslationState()
        object Loading : TranslationState()
        /** [noteRes] explains an unusual success, e.g. "already in your language". */
        data class Done(val text: String, val noteRes: Int? = null) : TranslationState()
        data class Failed(val messageRes: Int) : TranslationState()
    }

    private val _translationState = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val translationState: StateFlow<TranslationState> = _translationState.asStateFlow()

    /**
     * Translate [text] into the device language via the three-tier on-device cascade
     * (Gemini Nano → local LLM → ML Kit) that `NLPManager` has always implemented and
     * that, until this call site existed, **nothing invoked** (Z-1).
     *
     * Callers must pass text already sanitised through `OSHIControlPayload.previewText`,
     * exactly as iOS does before translating (`OSHI/GroupViews.swift:4431`) — otherwise a
     * shared location asks the model to translate its own coordinate JSON.
     */
    fun translateMessage(text: String) {
        if (text.isBlank()) {
            _translationState.value = TranslationState.Failed(R.string.translation_unavailable)
            return
        }
        _translationState.value = TranslationState.Loading
        viewModelScope.launch {
            val outcome = try {
                nlpManager.translateReceived(text)
            } catch (e: Exception) {
                NLPManager.TranslationOutcome.Failed(e.message)
            }
            _translationState.value = when (outcome) {
                is NLPManager.TranslationOutcome.Translated ->
                    TranslationState.Done(outcome.text)
                is NLPManager.TranslationOutcome.AlreadyInTargetLanguage ->
                    TranslationState.Done(outcome.text, R.string.translation_same_language)
                is NLPManager.TranslationOutcome.UnsupportedLanguage ->
                    TranslationState.Failed(R.string.translation_unsupported_language)
                NLPManager.TranslationOutcome.ModelNotDownloaded ->
                    TranslationState.Failed(R.string.translation_needs_download)
                is NLPManager.TranslationOutcome.Failed ->
                    TranslationState.Failed(R.string.translation_unavailable)
            }
        }
    }

    /** Reset the sheet so the next Translate does not flash the previous result. */
    fun clearTranslation() {
        _translationState.value = TranslationState.Idle
    }

    // ============================================================================
    // FAILED MESSAGE RETRY (6B.7)
    // ============================================================================

    /**
     * Retry sending a failed message.
     *
     * The failed row is removed and the SAME message is sent again — same id, same
     * date (`resendOf`). It used to be re-sent as a brand-new message, which gave it
     * today's timestamp on this phone and, through the envelope, on the recipient's:
     * a text written this morning and finally delivered tonight appeared tonight, at
     * the bottom of the thread, underneath the replies that had already answered it.
     * A retry delivers the message that was written; it does not write another one.
     */
    fun retrySendMessage(message: Message) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        // A manual retry supersedes any disguised-path retry still scheduled for it.
        com.oshi.messenger.service.CoverOutbox.cancel(appContext, message.id)
        viewModelScope.launch {
            // Delete the failed row; `resendOf` re-inserts it under the same id.
            messageRepository.deleteMessage(message.id)
            if (LoRaNodeIdentityMap.isFallbackConversationKey(pk)) {
                // No key to encrypt to here either — same lane as `sendMessage`.
                messageRepository.sendLoRaInteropReply(pk, message.content)
            } else {
                messageRepository.sendTextMessage(pk, message.content, resendOf = message)
            }
        }
    }

    // ============================================================================
    // PULL-TO-REFRESH (6B.13)
    // ============================================================================

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Refresh messages by triggering a VPS poll.
     */
    fun refreshMessages() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                messageRepository.triggerMessagePoll()
            } catch (_: Exception) {
                // Silently handle poll failures
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // ============================================================================
    // TOOLBAR ITEMS (6B.12)
    // ============================================================================

    /**
     * Reset the Double Ratchet encryption session for this contact.
     */
    fun resetSession() {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        viewModelScope.launch {
            try {
                messageRepository.resetEncryptionSession(pk)
            } catch (_: Exception) {
                // Session reset may not be available yet
            }
        }
    }

    /**
     * Delete the entire conversation (all messages) and signal navigation back.
     */
    private val _conversationDeleted = MutableStateFlow(false)
    val conversationDeleted: StateFlow<Boolean> = _conversationDeleted.asStateFlow()

    fun deleteConversation() {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        viewModelScope.launch {
            messageRepository.deleteConversation(pk)
            _conversationDeleted.value = true
        }
    }

    /**
     * Set a local alias for the current contact.
     */
    fun setContactAlias(alias: String) {
        val pk = _publicKey.value
        if (pk.isEmpty() || alias.isBlank()) return

        viewModelScope.launch {
            messageRepository.updateContactAlias(pk, alias)
        }
    }

    /**
     * 🪦 Mark this contact's current public key as a stale (rotated) identity.
     * Future inbound calls / direct messages / group messages signed by this
     * key will be silently dropped at the inbound boundaries
     * (EnhancedCallManager, MessageRepository, GroupManager).
     * Stale ≠ blocked — see StaleIdentityStore for the distinction.
     */
    fun markContactIdentityStale() {
        val pk = _publicKey.value
        if (pk.isEmpty()) return
        viewModelScope.launch {
            messageRepository.markContactIdentityStale(pk)
        }
    }

    // ============================================================================
    // WALLPAPER SYNC
    // ============================================================================

    /**
     * Current wallpaper for the active conversation, derived from repository storage.
     * Converts stored ByteArray → ChatWallpaper for UI rendering.
     */
    //
    // __CHAT_POLISH_2026_09_24__ A conversation with no wallpaper of its own
    // now shows the app-wide default ([WallpaperManager.globalWallpaper]) —
    // which is Mesh for anyone who never picked one, as on iOS — instead of
    // flat beige. A wallpaper someone explicitly chose (per chat or global,
    // "Default" included) is stored and still wins.
    val currentWallpaper: StateFlow<ChatWallpaper> = combine(
        messageRepository.wallpapers,
        _publicKey,
        wallpaperManager.globalWallpaper
    ) { wallpaperMap, pk, global ->
        val data = wallpaperMap[pk]
        if (data != null) chatWallpaperFromBytes(data) else global
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), wallpaperManager.globalWallpaper.value)

    /**
     * Set wallpaper for the current conversation and broadcast to recipient.
     * Serializes ChatWallpaper → ByteArray, stores locally, and sends sync update.
     */
    fun setWallpaper(wallpaper: ChatWallpaper) {
        val pk = _publicKey.value
        if (pk.isEmpty()) return

        viewModelScope.launch {
            val syncJson = wallpaper.toSyncBytes()

            if (wallpaper is ChatWallpaper.ImageWallpaper) {
                // Store raw image bytes locally for display
                // Send JSON format + base64 image for cross-platform sync
                val imageBase64 = android.util.Base64.encodeToString(
                    wallpaper.imageData, android.util.Base64.NO_WRAP
                )
                messageRepository.setWallpaper(
                    conversationId = pk,
                    wallpaperData = wallpaper.imageData,
                    recipientPublicKey = pk,
                    senderName = null,
                    broadcast = true,
                    syncWallpaperJson = syncJson,
                    customImageBase64 = imageBase64
                )
            } else {
                // Non-image: JSON bytes serve as both storage and sync format
                messageRepository.setWallpaper(
                    conversationId = pk,
                    wallpaperData = syncJson,
                    recipientPublicKey = pk,
                    senderName = null,
                    broadcast = true
                )
            }
        }
    }

    /**
     * 🎨 Apply [wallpaper] as the default for EVERY chat.
     *
     * `WallpaperManager.setGlobalWallpaper` had no caller anywhere in the UI, so
     * "use this everywhere" could only be done one conversation at a time. Two
     * stores have to move for the result to be what the label promises:
     *
     *  1. `WallpaperManager` holds the app-wide default — what a conversation
     *     with no wallpaper of its own falls back to, what survives a restart,
     *     and what gets pushed to linked web sessions (its `onWallpaperChanged`
     *     hook fires with a null conversation id).
     *  2. `MessageRepository.wallpapers` holds the per-conversation overrides,
     *     and it is the flow [currentWallpaper] — the one the picker sheet
     *     renders — reads from. An override SHADOWS the global default, so
     *     setting only the global would leave every chat the user has already
     *     styled (this one included) visibly unchanged: the sheet would keep
     *     showing the old background and the action would read as broken.
     *
     * Only the conversation the user is actually looking at broadcasts the
     * change to its peer; sending a personal default to every contact would put
     * one encrypted message per contact on the wire the moment this is tapped.
     */
    fun setWallpaperForAllChats(wallpaper: ChatWallpaper) {
        viewModelScope.launch {
            wallpaperManager.setGlobalWallpaper(wallpaper)

            val syncJson = wallpaper.toSyncBytes()
            // Images are stored raw locally (that is what renders) but sync as JSON,
            // exactly as setWallpaper does for a single conversation.
            val storedBytes = if (wallpaper is ChatWallpaper.ImageWallpaper) {
                wallpaper.imageData
            } else {
                syncJson
            }
            val imageBase64 = (wallpaper as? ChatWallpaper.ImageWallpaper)?.let {
                android.util.Base64.encodeToString(it.imageData, android.util.Base64.NO_WRAP)
            }

            val pk = _publicKey.value
            val targets = LinkedHashSet(messageRepository.wallpapers.value.keys)
            if (pk.isNotEmpty()) targets.add(pk)

            for (conversationId in targets) {
                val isCurrent = conversationId == pk
                messageRepository.setWallpaper(
                    conversationId = conversationId,
                    wallpaperData = storedBytes,
                    recipientPublicKey = conversationId,
                    senderName = null,
                    broadcast = isCurrent,
                    syncWallpaperJson = syncJson,
                    customImageBase64 = if (isCurrent) imageBase64 else null
                )
            }
        }
    }
}
