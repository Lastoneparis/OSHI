package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.R
import com.oshi.messenger.data.local.GroupDao
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.bot.BotPlatformClient
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.service.ActiveConversation
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.NLPManager
import com.oshi.messenger.service.TypingIndicatorManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * GroupChatViewModel - Manages group chat state
 * Uses GroupManager (SharedPreferences) as the single source of truth
 */
@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val groupManager: GroupManager,
    private val cryptoManager: CryptoManager,
    private val messageRepository: MessageRepository,
    private val groupDao: GroupDao,
    private val typingIndicatorManager: TypingIndicatorManager,
    private val nlpManager: NLPManager,
    private val contactShareManager: com.oshi.messenger.service.ContactShareManager,
    private val vpsClient: com.oshi.messenger.network.vps.VPSClient,
    private val botPlatformClient: BotPlatformClient,
    val locationSharingManager: LocationSharingManager,
    // __REPORT_OUTCOME_2026_09_23__ group report (was contact-only on Android).
    private val reportManager: com.oshi.messenger.service.ReportManager,
    @com.oshi.messenger.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
    // __BOT_IN_THE_PIPELINE_2026_11_09__ for the `/` menu and the no-bot hint. Injecting it
    // also guarantees the singleton (and with it GroupManager's bot hook) exists.
    private val botManager: com.oshi.messenger.service.BotManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""

    /**
     * __BOT_IN_THE_PIPELINE_2026_11_09__ This group's bot commands for the `/` menu.
     * iOS lists them beside `/pay` (GroupViews.swift:4797-4800); Android listed `/pay` only,
     * so a bot's commands were something you had to be told about.
     */
    val botCommands: StateFlow<List<com.oshi.messenger.util.SlashCommandCatalog.Entry>> =
        botManager.slashCommandsForGroup(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** iOS __BOT_NO_BOT_HINT__: a `/command` sent where no bot listens says so, once per group. */
    private val _noBotHint = MutableStateFlow(false)
    val noBotHint: StateFlow<Boolean> = _noBotHint.asStateFlow()
    fun dismissNoBotHint() { _noBotHint.value = false }

    /** __GROUP_MEDIA_MEMORY_2026_11_11__ Bytes of a large attachment kept on disk, read on tap. */
    suspend fun loadMediaBytes(messageId: String): ByteArray? =
        withContext(Dispatchers.IO) { groupManager.loadMediaBytes(messageId) }

    private val _group = MutableStateFlow<Group?>(null)
    val group: StateFlow<Group?> = _group.asStateFlow()

    /**
     * __GROUP_E2E_V2_2026_09_23__ members of THIS group the last send could not reach because
     * they have no v2 bundle (old build). They receive nothing — GROUP_E2E_V2_SPEC §6 — and the
     * screen says so instead of silently falling back to a readable legacy path.
     */
    val unreachableMembers: StateFlow<Map<String, Set<String>>> get() = groupManager.unreachableMembers

    private val _messages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val messages: StateFlow<List<GroupMessage>> = _messages.asStateFlow()

    private val _myPublicKey = MutableStateFlow(cryptoManager.getPublicKeyString())
    val myPublicKey: StateFlow<String> = _myPublicKey.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<GroupMessage>>(emptyList())
    val searchResults: StateFlow<List<GroupMessage>> = _searchResults.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    // Live map of publicKey -> alias for resolving sender names at render time
    // __SHARED_NICKNAME_2026_09_22__ values are DISPLAY names (alias > member's shared
    // nickname > placeholder), recomputed when a member renames.
    val contactsMap: StateFlow<Map<String, String>> = combine(
        messageRepository.getAllContacts(),
        com.oshi.messenger.data.model.PeerNicknameStore.nicknames
    ) { contacts, nicks ->
        // Built by [PeerNickname.senderDisplayMap]: keyed by the stored key AND its folded
        // spelling (a base64url sender key used to miss the row), and it also carries the
        // shared nickname of members who are NOT contacts — those fell back to the
        // `senderName` frozen into the message row at receive time and never followed a
        // rename. Read it with [PeerNickname.lookupSender].
        com.oshi.messenger.data.model.PeerNickname.senderDisplayMap(
            contacts.map { it.publicKey to it.alias }, nicks
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Typing indicator text for this group's header ("Alice is typing...", etc.)
    val typingText: StateFlow<String?> = combine(
        typingIndicatorManager.groupTypingStatus,
        contactsMap
    ) { allTyping, contacts ->
        val senders = allTyping[groupId] ?: emptySet()
        if (senders.isEmpty()) return@combine null
        val names = senders.map { pk ->
            com.oshi.messenger.data.model.PeerNickname.lookupSender(contacts, pk) ?: pk.take(8)
        }
        when (names.size) {
            1 -> "${names[0]} is typing..."
            2 -> "${names[0]} and ${names[1]} are typing..."
            else -> "Several people are typing..."
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var messagesJob: kotlinx.coroutines.Job? = null

    /**
     * __OPEN_AT_THE_FIRST_UNREAD__ (iOS GroupViews parity) The unread incoming
     * message ids of this group as they were when the screen OPENED, oldest first.
     * Read synchronously here, before [observeGroup] / [loadGroup] / [onGroupVisible]
     * can mark anything read — every one of those runs later than this constructor.
     */
    val openUnreadIds: List<String> =
        if (groupId.isEmpty()) emptyList()
        else (groupManager.groupMessages.value[groupId] ?: emptyList())
            .filter { !it.isRead && !it.isFromMe && !it.isDeleted }
            .sortedBy { it.timestamp }
            .map { it.id }

    /**
     * __OPEN_SCROLL_2026_11_16__ True once [messages] has received this group's list.
     * [messages] starts empty and is filled from a Dispatchers.Default sort, so the
     * screen drew "No messages yet" on every open until that sort came back.
     */
    private val _messagesLoaded = MutableStateFlow(false)
    val messagesLoaded: StateFlow<Boolean> = _messagesLoaded.asStateFlow()

    init {
        if (groupId.isNotEmpty()) {
            observeGroup(groupId)
        }
    }

    /** Start observing group state (groups + messages) from GroupManager. */
    private fun observeGroup(id: String) {
        // Observe group metadata changes
        viewModelScope.launch {
            groupManager.groups.collect { groups ->
                _group.value = groups.find { it.id == id }
            }
        }
        // Observe messages (single subscription, cancelled on re-call)
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            // ✅ PERF: track previous size + last id so we only mark-as-read when the message
            // set actually grew. Previously every emission of the upstream Map<groupId,List>
            // (fired on ANY group's update — typing, reactions, other groups) re-ran the full
            // list copy + a no-op markGroupMessagesAsRead. With hundreds of messages this was
            // a measurable hitch on entry while the IPFS catch-up was draining.
            var lastSize = -1
            var lastTailId: String? = null
            groupManager.groupMessages.collect { allMessages ->
                val raw = allMessages[id] ?: emptyList()
                // ✅ PERF: sort on a worker — sortedBy on thousands of messages on the main
                // dispatcher is what makes the first frame feel laggy. The StateFlow setter
                // is thread-safe so we can hand the sorted list back from any context.
                val sorted = withContext(Dispatchers.Default) {
                    raw.sortedBy { it.timestamp }
                }
                _messages.value = sorted
                _messagesLoaded.value = true
                val tailId = sorted.lastOrNull()?.id
                val grew = sorted.size > lastSize || tailId != lastTailId
                lastSize = sorted.size
                lastTailId = tailId
                // Mark as read only when the visible set actually changed and the
                // user is actually LOOKING at this group.
                //
                // 🔧 2026-08-24. The gate used to be a local `_isGroupOpen` flag set
                // in `loadGroup` and cleared in `onGroupClosed` (the screen's
                // `onDispose`). `onDispose` does NOT run when the app is
                // backgrounded with the chat open, so the flag stayed true in the
                // user's pocket and every arriving message was stamped read by a
                // collector that outlives the screen. `ActiveConversation` is
                // written from the screen's `ON_RESUME` / `ON_PAUSE` instead, so it
                // means "foregrounded AND this group on screen".
                //
                // iOS decides the same thing at insert time:
                // `GroupMessaging.swift:3330-3339` stamps
                // `incomingMessage.isRead = (activeGroupId == message.groupId)`.
                if (grew && ActiveConversation.isGroupActive(id)) {
                    groupManager.markGroupMessagesAsRead(id)
                }
            }
        }
    }

    fun loadGroup(id: String) {
        _isLoading.value = true
        val group = groupManager.getGroupById(id)
        _group.value = group
        _isLoading.value = false
        // Mark all messages as read when conversation is opened
        groupManager.markGroupMessagesAsRead(id)
        // Request fresh state from iOS members (name, picture, member list)
        groupManager.requestGroupStateSync(id)
        // Skip the 5 s polling wait when the user opens this chat — typically
        // they tapped an FCM notification and the message hasn't been pulled
        // yet. Pre-fix this could wait up to 30 s before the new message
        // appeared and the screen looked frozen.
        try { vpsClient.triggerImmediatePoll() } catch (_: Exception) {}
    }

    /**
     * The group thread is on screen and the app is foregrounded (screen `ON_RESUME`).
     *
     * Takes the id from the SCREEN rather than `_group.value`, because the lifecycle
     * observer can fire before `loadGroup` has resolved the group.
     */
    fun onGroupVisible(id: String) {
        if (id.isEmpty()) return
        ActiveConversation.enterGroup(id)
        // Anything that landed while we were away (or a moment ago, before the
        // observer registered) is read now that the thread is on screen.
        groupManager.markGroupMessagesAsRead(id)
    }

    /** The group left the screen — backgrounded, navigated away, or disposed. */
    fun onGroupHidden(id: String) {
        if (id.isEmpty()) return
        ActiveConversation.leaveGroup(id)
    }

    fun onGroupClosed() {
        val currentGroup = _group.value
        if (currentGroup != null) {
            groupManager.sendGroupTypingStatus(currentGroup.id, isTyping = false)
        }
    }

    fun sendMessage(content: String, mentions: List<com.oshi.messenger.util.MentionWire.Mention> = emptyList()) {
        val currentGroup = _group.value ?: return
        groupManager.sendGroupMessage(currentGroup.id, content, mentions = mentions)
        // Clear typing state on this device and notify peers
        groupManager.sendGroupTypingStatus(currentGroup.id, isTyping = false)
        viewModelScope.launch {
            if (runCatching { botManager.consumeNoBotHint(content, currentGroup.id) }.getOrDefault(false)) {
                _noBotHint.value = true
            }
        }
    }

    /**
     * 📊 Send a poll to the group. An ordinary group message: it lands in the
     * thread and draws as a card because its prefix is in the catalog.
     */
    fun sendPoll(poll: com.oshi.messenger.data.model.PollMessage) {
        val currentGroup = _group.value ?: return
        groupManager.sendGroupMessage(currentGroup.id, poll.toMessageString())
    }

    /**
     * 📊 Cast a ballot in the group.
     *
     * Recorded locally first so the bar moves under the finger, then sent
     * through [GroupManager.sendGroupPollBallot], which does not store it.
     */
    fun sendPollVote(poll: com.oshi.messenger.data.model.PollMessage, picks: List<Int>) {
        val currentGroup = _group.value ?: return
        val me = runCatching { cryptoManager.getPublicKeyString() }.getOrDefault("")
        val vote = com.oshi.messenger.data.model.PollVotePayload(pollId = poll.id, picks = picks)
        if (me.isNotEmpty()) com.oshi.messenger.service.PollStore.record(vote, me)
        groupManager.sendGroupPollBallot(currentGroup.id, vote.toMessageString())
    }

    /**
     * A location share from the sheet: the first message of a live share goes out as a
     * message, every later tick and the stop as an in-place edit of it
     * ([GroupManager.sendGroupLocation]).
     */
    fun sendLocation(content: String) {
        val currentGroup = _group.value ?: return
        groupManager.sendGroupLocation(currentGroup.id, content)
    }

    /**
     * Called by the UI on each keystroke.
     *
     * Now goes through [GroupManager.sendGroupTypingStatus], which wraps the ping in the
     * GROUP envelope — the only ingress iOS has
     * (`OSHI/GroupMessaging.swift:2905-2912`). The old path fanned out one separate
     * encrypted 1:1 `⌨️TYPING⌨️` per member, which cost N sends per keystroke window
     * and was silently discarded by every iPhone. Throttling (2 s, matching iOS's
     * `sendDebounce`) lives inside `sendGroupTypingStatus`, so there is nothing to
     * debounce here.
     */
    fun onTextChanged(text: String) {
        val currentGroup = _group.value ?: return
        groupManager.sendGroupTypingStatus(currentGroup.id, isTyping = text.isNotEmpty())
    }

    /**
     * Send media to the group, with the caption the user typed over it.
     *
     * `content` on a media message IS the caption: `GroupManager.sendMessageToMembers`
     * puts it in the media JSON's `caption` key for Android and in the message's
     * `plaintextContent`, which is the only key iOS reads a caption from
     * (`GroupViews.resolveText` returns nil for a media JSON, `GroupViews.swift:2641`;
     * the caption is drawn from `plaintextContent` at `:2617`). This used to pass `""`
     * unconditionally, so a caption on Android group media was invisible everywhere —
     * audit G-7.
     *
     * ARMED: `GroupMediaPreviewDialog` (`ui/screens/GroupMediaPreviewDialog.kt`) is the
     * group twin of the 1:1 `MediaPreviewDialog`, and the four image/video call sites in
     * `GroupChatScreen` now route through it.
     */
    /**
     * The OSHI contacts that can be shared into this group — everyone in the local
     * contact book who is not blocked. The 1-on-1 twin
     * ([ChatViewModel.shareableContacts]) additionally drops the person you are
     * talking to; a group has no single counterpart to drop.
     */
    val shareableContacts: StateFlow<List<com.oshi.messenger.data.model.Contact>> =
        messageRepository.getAllContacts()
            .map { contacts -> contacts.filterNot { it.isBlocked }.sortedBy { it.alias.lowercase() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Share an OSHI contact card into the group.
     *
     * Same body as the 1-on-1 path — `ContactShareManager.contactCardBytes` is the
     * single writer, so the two surfaces cannot drift — carried in the group media
     * envelope with `mediaType: "contact"`, the raw value of iOS's group
     * `MediaType.contact` (`OSHI/GroupMessaging.swift:406-412`). iOS renders it at
     * `GroupViews.swift:3201`.
     *
     * The caption is the shared person's name, which is what the group row and the
     * iPhone's caption line under the card both print (`plaintextContent`,
     * `GroupViews.swift:2617`).
     */
    fun sendContactCard(publicKey: String, alias: String?) {
        val currentGroup = _group.value ?: return
        if (publicKey.isBlank()) return
        val displayName = alias?.takeIf { it.isNotBlank() } ?: publicKey.take(8)
        groupManager.sendGroupMessage(
            groupId = currentGroup.id,
            content = displayName,
            mediaType = MediaType.CONTACT,
            mediaData = contactShareManager.contactCardBytes(displayName, publicKey)
        )
    }

    fun sendMediaMessage(mediaData: ByteArray, mediaType: String, caption: String = "") {
        val currentGroup = _group.value ?: return
        val type = try { MediaType.valueOf(mediaType.uppercase()) } catch (_: Exception) { null }
        groupManager.sendGroupMessage(
            groupId = currentGroup.id,
            content = normalizeCaption(caption),
            mediaType = type,
            mediaData = mediaData
        )
    }

    /**
     * __GROUP_GIF_2026_11_16__ A GIF / sticker from [com.oshi.messenger.ui.components.StickerGifPicker]
     * (OSHI mascots, the local pack, GIPHY).
     *
     * Rides the ordinary group media path as `photo` — the group wire has no `gif` type on
     * any platform, and iOS's group composer sends its GIFs exactly this way. The bytes go
     * out UNTOUCHED (nothing on the group path recompresses, and re-encoding a GIF keeps
     * only its first frame); receivers sniff `GIF8xa` and animate. The name keeps `.gif`
     * and the blob MIME becomes `image/gif` ([GroupManager.groupMime]). No caption: the
     * body would land in `plaintextContent` and print under the sticker on every iPhone.
     */
    fun sendGifMessage(gifData: ByteArray, fileName: String) {
        val currentGroup = _group.value ?: return
        if (gifData.isEmpty()) return
        groupManager.sendGroupMessage(
            groupId = currentGroup.id,
            content = "",
            mediaType = MediaType.IMAGE,
            mediaData = gifData,
            mediaFileName = GroupManager.groupGifFileName(fileName)
        )
    }

    /**
     * The file name goes in `mediaFileName`, NOT in the body.
     *
     * It used to be sent as `content = "[DOCUMENT] name.pdf"`, and since the groups pass
     * copies `content` into `plaintextContent` — the only key iOS reads a caption from
     * (`GroupViews.swift:2617`) — every iPhone drew that literal under the document.
     * iOS puts the name in the media JSON's `fileName` (`GroupMessaging.swift:2542`) and
     * leaves the caption empty, which is what this now does.
     */
    fun sendDocumentMessage(documentData: ByteArray, filename: String) {
        val currentGroup = _group.value ?: return
        groupManager.sendGroupMessage(
            groupId = currentGroup.id,
            content = "",
            mediaType = MediaType.DOCUMENT,
            mediaData = documentData,
            mediaFileName = filename.trim().takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Send a voice message to the group.
     *
     * The duration goes in `mediaDurationMs`, NOT in the body — the same fix as
     * [sendDocumentMessage], one field over. It used to be sent as
     * `content = "[AUDIO] 0:05"`, and since `content` is copied into `plaintextContent`
     * — the only key iOS draws a caption from (`GroupViews.swift:2617`) — **every iPhone
     * printed that literal under the voice note**. iOS sends a voice note with an empty
     * caption and reads the length back off the decoded audio (`GroupViews.swift:5265-5276`,
     * `AudioRecorderManager.swift:228`), which is exactly what this now does; Android's own
     * recorder already knows the number, so it is stored rather than re-derived.
     *
     * Nothing new crosses the wire: the group media JSON keeps iOS's key set
     * (`GroupMessaging.swift:2534-2546`), and a peer that has only the bytes probes them
     * (`VoiceNoteAudio.probeDurationSeconds`).
     */
    fun sendVoiceMessage(audioData: ByteArray, durationSeconds: Float) {
        val currentGroup = _group.value ?: return
        groupManager.sendGroupMessage(
            groupId = currentGroup.id,
            content = "",
            mediaType = MediaType.AUDIO,
            mediaData = audioData,
            mediaDurationMs = (durationSeconds.coerceAtLeast(0f) * 1000f).toLong().takeIf { it > 0L }
        )
    }

    /** __VIDEO_NOTE_2026_09_24__ a round video note (docs/VIDEO_NOTE_SPEC.md §2.B). */
    fun sendVideoNote(videoData: ByteArray, durationMs: Long) {
        val currentGroup = _group.value ?: return
        if (videoData.isEmpty()) return
        groupManager.sendGroupMessage(
            groupId = currentGroup.id,
            content = "",
            mediaType = MediaType.VIDEO,
            mediaData = videoData,
            mediaFileName = com.oshi.messenger.service.media.VideoNote.fileName(),
            mediaDurationMs = durationMs.takeIf { it > 0L }
                ?.coerceAtMost(com.oshi.messenger.service.media.VideoNote.MAX_DURATION_MS),
            isVideoNote = true
        )
    }

    /** Add a reaction, persist via GroupManager (SharedPreferences + broadcast) — tasks 2.5.3-2.5.4 */
    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            // Broadcast + persist via GroupManager (handles local state + SharedPreferences + network)
            groupManager.broadcastReaction(groupId, messageId, emoji, isRemoval = false)
        }
    }

    /**
     * __OSHI_SMILE_REACTION_2026_11_09__ Toggle [emoji] as my reaction: remove it
     * if it is already mine, otherwise set it (replacing any other of mine, one
     * reaction per member — the map is sender → emoji). [addReaction] alone never
     * removes, so a second double-tap would have re-sent the same reaction.
     */
    fun toggleReaction(messageId: String, emoji: String) {
        val mine = _messages.value
            .firstOrNull { it.id.equals(messageId, ignoreCase = true) }
            ?.reactions?.entries
            ?.firstOrNull { it.key.equals(myPublicKey.value, ignoreCase = true) }
            ?.value
        if (com.oshi.messenger.data.model.ReactionToggle.next(mine, emoji) == null) {
            removeReaction(messageId)
        } else {
            addReaction(messageId, emoji)
        }
    }

    /** Remove a reaction, persist via GroupManager — tasks 2.5.3-2.5.4 */
    fun removeReaction(messageId: String) {
        viewModelScope.launch {
            // __GROUP_REACTION_ID_2026_09_27__ iOS removes the emoji the payload NAMES
            // and falls back to 👍 on an empty one (MessageReactionManager.swift:165), so
            // an empty removal only ever cleared a thumbs-up. Name the one I placed.
            val mine = _messages.value
                .firstOrNull { it.id.equals(messageId, ignoreCase = true) }
                ?.reactions?.entries
                ?.firstOrNull { it.key.equals(myPublicKey.value, ignoreCase = true) }
                ?.value
                .orEmpty()
            groupManager.broadcastReaction(groupId, messageId, mine, isRemoval = true)
        }
    }

    /**
     * RP-4 · Delete a group message.
     *
     * `forEveryone = true` now actually BROADCASTS. Before, both branches did the same
     * thing — drop the row from this ViewModel's in-memory list — so "delete for
     * everyone" deleted it for nobody, and the bubble came back on the next
     * `observeGroup` emission because `GroupManager` still held it.
     *
     * `broadcastMessageDeletion` tombstones locally (`applyMessageDeletionLocally`) and
     * sends the `message_deleted` GROUP_UPDATE, which is what an iPhone applies at
     * `OSHI/GroupMessaging.swift:3285-3292`.
     */
    fun deleteMessage(messageId: String, forEveryone: Boolean) {
        if (forEveryone) {
            groupManager.broadcastMessageDeletion(groupId, messageId)
        } else {
            _messages.value = _messages.value.filterNot { it.id.equals(messageId, ignoreCase = true) }
        }
    }

    /**
     * RP-4, second half · Edit one of my own group messages, for everyone.
     *
     * The half the previous pass deferred for want of a column: `GroupMessage` now has
     * `editedContent` / `editedAt` (Room v15 → v16), so an edit both persists and travels.
     *
     * Reachability: long-press an own text bubble → Edit (offered only when
     * [GroupManager.canEditGroupMessage] agrees) → the edit dialog → Save → here →
     * `GroupManager.broadcastMessageEdit`, which applies locally and emits the
     * `.messageEdited` system message an iPhone applies at
     * `OSHI/GroupMessaging.swift:3292-3307`.
     *
     * @return false when the edit was refused (not mine, media, tombstoned, or past the
     *         10-minute window) so the caller can leave the dialog open.
     */
    fun editMessage(messageId: String, newContent: String): Boolean {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) return false
        return groupManager.broadcastMessageEdit(groupId, messageId, trimmed)
    }

    /**
     * May this message be edited right now? The SAME predicate the sender enforces
     * ([GroupManager.canEditGroupMessage]) — the menu cannot offer what the sender would
     * refuse.
     */
    fun canEdit(message: GroupMessage): Boolean =
        GroupManager.canEditGroupMessage(message, _myPublicKey.value, System.currentTimeMillis())

    // ============================================================================
    // TRANSLATE (RP-2) — the same on-device cascade the 1:1 surface uses
    // ============================================================================

    /** Mirrors [ChatViewModel.TranslationState]; the group sheet renders the same states. */
    sealed class TranslationState {
        object Idle : TranslationState()
        object Loading : TranslationState()
        data class Done(val text: String, val noteRes: Int? = null) : TranslationState()
        data class Failed(val messageRes: Int) : TranslationState()
    }

    private val _translationState = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val translationState: StateFlow<TranslationState> = _translationState.asStateFlow()

    /**
     * Translate a received group message into the device language.
     *
     * iOS sanitises the source through the control-payload catalog BEFORE translating
     * (`OSHI/GroupViews.swift:4431`); callers here must pass
     * `OSHIControlPayload.previewText(...)` for the same reason.
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

    fun clearTranslation() {
        _translationState.value = TranslationState.Idle
    }

    /**
     * __REPORT_OUTCOME_2026_09_23__ Report this GROUP (parity with iOS `ReportSheet(subject:
     * .group)`). Same privacy contract as the contact report: reason + what the reporter
     * typed + the group id — nothing from the conversation. App scope, so leaving the
     * screen does not cancel the POST; the record is persisted before it is attempted.
     */
    fun reportGroup(
        reason: com.oshi.messenger.data.model.ReportReason,
        details: String,
        onResult: (com.oshi.messenger.data.model.ReportDeliveryState) -> Unit = {}
    ) {
        val g = _group.value ?: return
        appScope.launch {
            val filed = reportManager.fileReport(
                subject = com.oshi.messenger.data.model.ReportSubject.GROUP,
                subjectId = g.id,
                subjectName = g.name,
                reason = reason,
                details = details
            )
            withContext(Dispatchers.Main) { onResult(filed.state) }
        }
    }

    fun hasReportedGroup(): Boolean = _group.value?.let { reportManager.hasReportedGroup(it.id) } ?: false

    /**
     * __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__
     *
     * Report ONE MEMBER, as a contact. Until now the only report reachable from
     * inside a group was `reportGroup`, so a member being spammed by one person
     * could flag nothing but the whole room.
     *
     * The abuse reports filed on 2026-11-06 are what this is for: four reports
     * from one reporter in 33 minutes, every one `subjectType: "group"`, with
     * the free-text field carrying a PERSON'S NAME — "Danielle Bespoke",
     * "Sarah", "Detee now". Three of them were the same group id, 14 and 25
     * seconds apart. That is what filing looks like when the only available
     * subject is the wrong one: the reporter typed the spammer into a comment
     * box because there was no field for them, and moderation received a room
     * id instead of a key it could act on.
     *
     * Same privacy contract as the other two: reason, what the reporter typed,
     * and the member's public key — which is already public. Nothing from the
     * conversation. App scope, so leaving the screen does not cancel the POST.
     */
    fun reportMember(
        memberPublicKey: String,
        memberName: String?,
        reason: com.oshi.messenger.data.model.ReportReason,
        details: String,
        onResult: (com.oshi.messenger.data.model.ReportDeliveryState) -> Unit = {}
    ) {
        if (memberPublicKey.isEmpty()) return
        appScope.launch {
            val filed = reportManager.fileReport(
                subject = com.oshi.messenger.data.model.ReportSubject.CONTACT,
                subjectId = memberPublicKey,
                subjectName = memberName,
                reason = reason,
                details = details
            )
            withContext(Dispatchers.Main) { onResult(filed.state) }
        }
    }

    /** True when this member is already in the local report log (as a contact). */
    fun hasReportedMember(memberPublicKey: String): Boolean =
        memberPublicKey.isNotEmpty() && reportManager.hasReportedContact(memberPublicKey)

    /**
     * Block ONE MEMBER from inside the group.
     *
     * The enforcement has existed since __BLOCKED_MEMBER_DROP_2026_09_24__ — an
     * owner decision, `BlockedMemberGate.drops` at group receive, their words
     * never stored. What did not exist was any way to REACH it from a group:
     * `MemberActionsDialog` offered reply-in-private, add-to-contacts, copy key,
     * promote/demote and (admins only) remove. A non-admin had no action at all
     * against a spamming member. Enforced but unreachable is not shipped.
     *
     * Deliberately goes through `messageRepository.blockContact`, not the
     * contact table directly: a group member usually is NOT a contact, and that
     * function writes `BlockedKeysStore` as well as the row precisely because
     * "the block must outlive the contact row". `isBlockedCached` reads the
     * store first, so this works for someone never added as a contact.
     */
    fun blockMember(memberPublicKey: String) {
        if (memberPublicKey.isEmpty()) return
        viewModelScope.launch { messageRepository.blockContact(memberPublicKey) }
    }

    fun unblockMember(memberPublicKey: String) {
        if (memberPublicKey.isEmpty()) return
        viewModelScope.launch { messageRepository.unblockContact(memberPublicKey) }
    }

    /** Non-suspending, for the member dialog: the durable store, then the cache. */
    fun isMemberBlocked(memberPublicKey: String): Boolean =
        memberPublicKey.isNotEmpty() &&
            com.oshi.messenger.service.CallAdmissionPolicy.isBlockedCached(memberPublicKey)

    fun toggleMute() {
        val currentGroup = _group.value ?: return
        val newMuted = groupManager.toggleGroupMute(currentGroup.id)
        _group.value = currentGroup.copy(isMuted = newMuted)
    }

    fun addContact(publicKey: String, alias: String) {
        viewModelScope.launch {
            messageRepository.addContact(publicKey, alias)
        }
    }

    /** Promote a member to admin — task 2.6.4 */
    fun promoteToAdmin(memberPublicKey: String) {
        groupManager.promoteToAdmin(groupId, memberPublicKey)
    }

    /** Demote an admin to regular member — task 2.6.4 */
    fun demoteFromAdmin(memberPublicKey: String) {
        groupManager.demoteFromAdmin(groupId, memberPublicKey)
    }

    fun searchMessages(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                // Try FTS first, then fall back to in-memory search
                val ftsResults = try {
                    groupDao.searchMessages(groupId, "$query*")
                } catch (e: Exception) {
                    emptyList()
                }

                if (ftsResults.isNotEmpty()) {
                    ftsResults
                } else {
                    // In-memory search across all loaded messages
                    _messages.value.filter {
                        it.content.contains(query, ignoreCase = true) ||
                        it.senderName.contains(query, ignoreCase = true)
                    }.sortedByDescending { it.timestamp }
                }
            }
            _searchResults.value = results
        }
    }

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) {
            _searchQuery.value = ""
            _searchResults.value = emptyList()
        }
    }

    /** Pin a message (admin only) — task 3.8.4 */
    fun pinMessage(messageId: String) {
        groupManager.pinMessage(groupId, messageId)
        _group.value = _group.value?.copy(pinnedMessageId = messageId)
    }

    /** Unpin the currently pinned message — task 3.8.4 */
    fun unpinMessage() {
        groupManager.unpinMessage(groupId)
        _group.value = _group.value?.copy(pinnedMessageId = null)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 🤖 BOT PLATFORM — inline keyboards, callback queries, mini-apps
    // ══════════════════════════════════════════════════════════════════════════

    /** A mini-app session that is ready to be shown. */
    data class BotWebAppRequest(val botName: String, val url: String, val initData: String)

    /** A one-shot thing to say to the user about a bot action. */
    sealed interface BotNotice {
        /** A localised line for the snackbar. */
        data class Message(@androidx.annotation.StringRes val resId: Int) : BotNotice

        /** The bot's own answer to a callback query. */
        data class Answer(val answer: com.oshi.messenger.data.model.BotCallbackAnswer) : BotNotice
    }

    /** messageId → [com.oshi.messenger.data.model.BotInlineButton.actionKey] currently in flight. */
    private val _botInFlight = MutableStateFlow<Map<String, String>>(emptyMap())
    val botInFlight: StateFlow<Map<String, String>> = _botInFlight.asStateFlow()

    private val _botWebApp = MutableStateFlow<BotWebAppRequest?>(null)
    val botWebApp: StateFlow<BotWebAppRequest?> = _botWebApp.asStateFlow()

    private val _botNotices = kotlinx.coroutines.flow.MutableSharedFlow<BotNotice>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val botNotices: kotlinx.coroutines.flow.SharedFlow<BotNotice> = _botNotices

    /** callback_query_id we are still waiting on → the message whose button is spinning. */
    private val awaitedCallbacks = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        // A `bot_callback_answer` is the bot's reply to a tap: it both stops the
        // spinner and carries the text to show. Collected here rather than in the
        // screen so a rotation mid-flight does not strand a button in its loading
        // state — the ViewModel outlives the composition, the composition does not.
        viewModelScope.launch {
            com.oshi.messenger.service.BotCallbackAnswers.answers.collect { answer ->
                val messageId = awaitedCallbacks.remove(answer.callbackQueryId)
                if (messageId != null) clearInFlight(messageId)
                _botNotices.tryEmit(BotNotice.Answer(answer))
            }
        }
    }

    /**
     * Tap on a `callback_data` button → `POST /api/bot/dispatch`.
     *
     * The button stays in its loading state after the POST returns, because the POST
     * returning only means the SERVER took it — the thing the user is waiting for is
     * the bot's `bot_callback_answer`, which arrives later through the queue. Clearing
     * on the HTTP response would blink the spinner for 200 ms and then show nothing,
     * which reads as "the tap did nothing". A timeout is what stops a button spinning
     * forever when a bot never answers.
     */
    fun onBotCallbackTap(message: GroupMessage, button: com.oshi.messenger.data.model.BotInlineButton) {
        val callbackData = button.callbackData ?: return
        if (_botInFlight.value.containsKey(message.id)) return  // one tap at a time per message
        _botInFlight.value = _botInFlight.value + (message.id to button.actionKey)

        viewModelScope.launch {
            val auth = botPlatformClient.resolveAuth(
                botSenderKey = message.senderPublicKey,
                botName = message.senderName,
                botId = message.botId
            )
            if (auth == null) {
                clearInFlight(message.id)
                _botNotices.tryEmit(BotNotice.Message(R.string.bot_action_unavailable))
                return@launch
            }
            when (val result = botPlatformClient.dispatchCallbackQuery(
                auth = auth,
                groupId = groupId,
                fromPublicKey = _myPublicKey.value,
                callbackData = callbackData,
                messageId = message.id
            )) {
                is BotPlatformClient.DispatchResult.Ok -> {
                    val queryId = result.callbackQueryId
                    if (queryId == null) {
                        // Accepted, but this bot will not be answering — nothing will
                        // ever arrive to stop the spinner, so stop it now.
                        clearInFlight(message.id)
                    } else {
                        awaitedCallbacks[queryId] = message.id
                        launch {
                            kotlinx.coroutines.delay(CALLBACK_ANSWER_TIMEOUT_MS)
                            if (awaitedCallbacks.remove(queryId) != null) {
                                clearInFlight(message.id)
                                _botNotices.tryEmit(BotNotice.Message(R.string.bot_action_no_answer))
                            }
                        }
                    }
                }
                is BotPlatformClient.DispatchResult.NoAuth -> {
                    clearInFlight(message.id)
                    _botNotices.tryEmit(BotNotice.Message(R.string.bot_action_unavailable))
                }
                is BotPlatformClient.DispatchResult.NotAMember -> {
                    clearInFlight(message.id)
                    _botNotices.tryEmit(BotNotice.Message(R.string.bot_action_not_member))
                }
                is BotPlatformClient.DispatchResult.UnknownBot -> {
                    clearInFlight(message.id)
                    _botNotices.tryEmit(BotNotice.Message(R.string.bot_action_unknown_bot))
                }
                is BotPlatformClient.DispatchResult.Failed -> {
                    clearInFlight(message.id)
                    _botNotices.tryEmit(BotNotice.Message(R.string.bot_action_failed))
                }
            }
        }
    }

    /**
     * Tap on a `web_app` button → `POST /api/bot/miniapp/session`, then open the
     * returned URL in the hardened WebView sheet.
     */
    fun onBotWebAppTap(message: GroupMessage, button: com.oshi.messenger.data.model.BotInlineButton) {
        if (button.webAppUrl == null) return
        if (_botInFlight.value.containsKey(message.id)) return
        _botInFlight.value = _botInFlight.value + (message.id to button.actionKey)

        viewModelScope.launch {
            try {
                val auth = botPlatformClient.resolveAuth(
                    botSenderKey = message.senderPublicKey,
                    botName = message.senderName,
                    botId = message.botId
                )
                if (auth == null) {
                    _botNotices.tryEmit(BotNotice.Message(R.string.bot_action_unavailable))
                    return@launch
                }
                val session = botPlatformClient.createMiniAppSession(
                    auth = auth,
                    userPublicKey = _myPublicKey.value,
                    groupId = groupId
                )
                if (session == null) {
                    _botNotices.tryEmit(BotNotice.Message(R.string.bot_webapp_failed))
                    return@launch
                }
                // The session's own url is authoritative — the button's `web_app.url`
                // is what the bot ASKED for, the session is what the server AUTHORISED,
                // and only the latter goes with an initData that means anything.
                _botWebApp.value = BotWebAppRequest(
                    botName = message.senderName.ifBlank { "" },
                    url = session.url,
                    initData = session.initData
                )
            } finally {
                clearInFlight(message.id)
            }
        }
    }

    fun dismissBotWebApp() {
        _botWebApp.value = null
    }

    private fun clearInFlight(messageId: String) {
        _botInFlight.value = _botInFlight.value - messageId
    }

    companion object {
        /**
         * How long a callback button keeps spinning while waiting for the bot's
         * `bot_callback_answer`. Generous, because the answer travels through the same
         * poll cycle every other message does — but finite, because a bot that never
         * answers must not leave a permanently dead button in the thread.
         */
        private const val CALLBACK_ANSWER_TIMEOUT_MS = 20_000L

        /**
         * A caption that is only whitespace is no caption.
         *
         * `GroupManager.sendMessageToMembers` emits `caption` and `plaintextContent`
         * on the `content.isNotEmpty()` test alone, so a blank string would ship a
         * `plaintextContent: " "` that iOS draws as an empty caption row under the
         * photo, and Android as an empty text line.
         */
        fun normalizeCaption(raw: String): String = raw.trim()
    }

    // __MENTIONS_2026_09_23__ `@name` person mentions (util/MentionWire).
    /** Bumps whenever mentions are recorded; read it to recompose bubbles. */
    val mentionsVersion: kotlinx.coroutines.flow.StateFlow<Int> get() = groupManager.mentionsVersion

    fun mentionsFor(messageId: String): List<com.oshi.messenger.util.MentionWire.Mention> =
        groupManager.mentionsFor(messageId)

    fun mentionCandidates(): List<com.oshi.messenger.util.MentionWire.Mention> =
        _group.value?.let { groupManager.mentionCandidates(it.id) }.orEmpty()
}
