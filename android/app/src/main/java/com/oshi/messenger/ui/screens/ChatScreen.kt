package com.oshi.messenger.ui.screens

import com.oshi.messenger.ui.theme.glassDialogRim
import com.oshi.messenger.ui.theme.thenIf
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassLift
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.components.bubbleClickable
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.oshi.messenger.ui.viewmodel.SettingsViewModel
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.flow.map
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.ui.components.ComposerShortcut
import com.oshi.messenger.ui.components.BubbleMediaKind
import com.oshi.messenger.ui.components.ChatMediaSelection
import com.oshi.messenger.ui.components.PendingMedia
import com.oshi.messenger.util.MessageSanitizer
import com.oshi.messenger.data.model.CheckInMessage
import com.oshi.messenger.data.model.CheckInMessageType
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.ReportReason
import com.oshi.messenger.data.model.ReportWireFormat
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.data.model.ReactionSummary
import com.oshi.messenger.network.location.LocationMessage
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.service.AudioPlayerManager
import com.oshi.messenger.service.AudioRecorderManager
import com.oshi.messenger.service.MessageReactionManager
import com.oshi.messenger.service.audio.VoiceEffectProcessor.VoiceEffect
import com.oshi.messenger.ui.components.AIQuickBar
import com.oshi.messenger.ui.components.SmartRepliesBar
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.emojiPattern
import com.oshi.messenger.ui.components.frostedBackdropSource
import com.oshi.messenger.ui.components.extendUp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.oshi.messenger.ui.components.OshiDynamicStyle
import com.oshi.messenger.ui.components.OshiDynamicWallpaperView
import com.oshi.messenger.ui.components.LinkPreviewCard
import com.oshi.messenger.ui.components.CashuTokenCardsForText
import com.oshi.messenger.ui.components.SlashCommandBar
import com.oshi.messenger.ui.components.cashuToast
import com.oshi.messenger.util.CashuPayCommand
import com.oshi.messenger.ui.components.TickerChipsForText
import com.oshi.messenger.util.TickerLinks
import com.oshi.messenger.ui.components.LinkifyText
import com.oshi.messenger.ui.components.LiveLocationBanner
import com.oshi.messenger.ui.components.ChatOverflowSheet
import com.oshi.messenger.ui.components.ChatSheetDivider
import com.oshi.messenger.ui.components.ChatSheetItem
import com.oshi.messenger.ui.components.VersionNotices
import com.oshi.messenger.ui.components.LocationMessageBubble
import com.oshi.messenger.ui.components.LocationShareSheet
import com.oshi.messenger.ui.components.WallpaperPickerSheet
import com.oshi.messenger.ui.components.WithChatScheme
import com.oshi.messenger.ui.components.WallpaperTonePrefs
import com.oshi.messenger.ui.components.WallpaperToneResolver
import com.oshi.messenger.ui.components.ProfileLinksPinnedBar
import com.oshi.messenger.ui.components.swipeToReply
import com.oshi.messenger.ui.components.swipeToRevealTimestamp
import com.oshi.messenger.ui.components.SwipeToReplyDefaults
import com.oshi.messenger.ui.components.detectFirstUrl
import com.oshi.messenger.ui.components.ForwardPickerSheet
import com.oshi.messenger.ui.components.StickerGifPicker
import com.oshi.messenger.service.MessageActionsManager
import com.oshi.messenger.ui.viewmodel.ChatViewModel
import com.oshi.messenger.ui.viewmodel.LoRaRadioProvider
import com.oshi.messenger.ui.viewmodel.NLPViewModel
import com.oshi.messenger.service.lora.LoRaEnvelope
import com.oshi.messenger.service.lora.LoRaNodeIdentityMap
import com.oshi.messenger.service.lora.MeshtasticManager
import com.oshi.messenger.ui.theme.LocalBubbleStyle
import com.oshi.messenger.ui.theme.LocalBubbleAppearance
import com.oshi.messenger.ui.theme.BubbleAppearance
import com.oshi.messenger.ui.theme.BubbleAppearancePrefs
import com.oshi.messenger.ui.theme.bubbleSurface
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.HeightClass
import com.oshi.messenger.ui.theme.rememberWindowSize
import com.oshi.messenger.ui.theme.OSHIPurpleLight
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.media.bleed
import org.json.JSONObject
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

private fun saveChatMediaToGallery(context: android.content.Context, mediaData: ByteArray, mediaType: com.oshi.messenger.data.model.MediaType?): Boolean {
    return try {
        val isVideo = mediaType == com.oshi.messenger.data.model.MediaType.VIDEO
        val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
        val fileName = "OSHI_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, contentValues) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { it.write(mediaData) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, cv, null, null)
        }
        true
    } catch (_: Exception) { false }
}

/**
 * True when the phone has an active network that reports the INTERNET capability
 * — the Android mirror of iOS's `NetworkReachabilityManager.hasPhysicalPath`
 * (ChatView.swift:2442). When this is false AND a LoRa radio is connected AND
 * media-over-LoRa is opted in, a media send is about to ride LoRa — slow, small
 * media only — so [ChatScreen] confirms first. Any other path (any internet, or
 * no radio) sends straight through. Returns false on any lookup failure, which
 * only makes the confirmation MORE likely to appear, never less — the safe way
 * to be wrong here.
 */
private fun hasPhysicalInternet(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ChatScreen(
    publicKey: String,
    /** __CHAT_BACKDROP_BLUR_2026_11_21__ the status-bar strip the host padded us out of. */
    statusBarUnderlap: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
    onCallClick: (Boolean) -> Unit,
    onNavigateToMap: () -> Unit = {},
    onOpenLocationInMap: ((lat: Double, lon: Double, name: String?, contactName: String?, contactPicture: ByteArray?, isLive: Boolean, sessionId: String?) -> Unit)? = null,
    onNavigateToAIChat: () -> Unit = {},
    onNavigateToChat: ((publicKey: String) -> Unit)? = null,
    /**
     * 🔐 Opens the safety-number / verification screen for the peer whose RAW public
     * key is handed back. The route is `"safetyNumber/{theirPublicKey}"`; escaping the
     * key for the nav graph is the caller's job, exactly as it already is for
     * `onNavigateToChat` (MainActivity wraps it in `encodeKeyForNav`).
     *
     * Defaulted so this file still compiles on its own; MainActivity has to supply it
     * or the overflow entry is inert.
     */
    onOpenSafetyNumber: (theirPublicKey: String) -> Unit = {},
    /** __MESHCORE_INTEROP_2026_11_25__ The radio settings, from a MeshCore thread whose radio is not connected. */
    onOpenRadioSettings: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    nlpViewModel: NLPViewModel = hiltViewModel()
) {
    // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ A LoRa channel is a room: several
    // radios speak into the same thread, so each bubble has to say who sent it.
    val isLoRaChannel = remember(publicKey) {
        com.oshi.messenger.service.lora.LoRaChannelThread.isChannelKey(publicKey)
    }
    // Only collected for a channel: in a one-to-one thread nothing reads it, and
    // subscribing to the radio's node table from every chat screen would be a
    // needless recomposition source.
    val loRaNodes by if (isLoRaChannel) {
        com.oshi.messenger.service.lora.MeshtasticManager
            .getInstance(LocalContext.current).nodes.collectAsState()
    } else {
        remember { mutableStateOf(emptyList<com.oshi.messenger.service.lora.LoRaMeshNode>()) }
    }

    // Get dependencies from ViewModel (injected via Hilt)
    val audioRecorder = viewModel.audioRecorder
    val audioPlayer = viewModel.audioPlayer
    val reactionManager = viewModel.reactionManager
    val context = LocalContext.current
    // Localized control-payload labels for THIS composable's own surfaces — the
    // clipboard (H-4), the pinned-message banner and the edit banner (H-6).
    // `MessageBubble` and `ReplyPreviewBanner` resolve their own; this is the
    // screen-level one, and it did not exist before this pass.
    val controlLabels = remember(context) { OSHIControlPayload.labels(context) }
    val messages by viewModel.filteredMessages.collectAsState()
    val contact by viewModel.contact.collectAsState()
    // __SHARED_NICKNAME_2026_09_22__ user alias > peer's shared nickname > fallback,
    // for every DISPLAY surface below. The alias editor still reads `contact.alias`.
    val contactDisplayName by viewModel.contactDisplayName.collectAsState()
    val sharedNickname by viewModel.sharedNickname.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchMatches by viewModel.searchMatches.collectAsState()
    val searchMatchIndex by viewModel.searchMatchIndex.collectAsState()
    val currentSearchMatchId by viewModel.currentSearchMatchId.collectAsState()
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val peerIsTyping by viewModel.peerIsTyping.collectAsState()

    // Error message display (e.g. image too large to send, send refused).
    // `errorMessage` is a string RESOURCE id, never free text — see the field's
    // doc in ChatViewModel for why (Play "broken functionality", 2026-09-16).
    val errorMessage by viewModel.errorMessage.collectAsState()
    LaunchedEffect(errorMessage) {
        errorMessage?.let { resId ->
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // Open-chat request (fired when user taps an in-app contact card)
    LaunchedEffect(Unit) {
        viewModel.openChatRequest.collect { targetPublicKey ->
            onNavigateToChat?.invoke(targetPublicKey)
        }
    }

    // AUDIT M-8, the half that is already on disk. The old video bubble wrote one full
    // copy of every video into `cacheDir` per BYTE-ARRAY INSTANCE — `thumb_<identityHash>.mp4`
    // for the poster frame and `playback_<identityHash>.mp4` for the player — and nothing
    // ever deleted them. Neither name is produced any more; this clears the backlog.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.oshi.messenger.ui.media.VideoThumbnails.sweepLegacyTempFiles(context.cacheDir)
            }
        }
    }

    // Reply-to state
    val replyingToMessage by viewModel.replyingToMessage.collectAsState()
    val messageActionsManager = viewModel.messageActions
    val textFieldFocusRequester = remember { FocusRequester() }

    // AI NLP state
    val nlpState by nlpViewModel.uiState.collectAsState()
    var aiProcessingAction by remember { mutableStateOf<NLPViewModel.AIAction?>(null) }

    // Apply AI result back to message text — uses resultVersion to always trigger,
    // even when correctedText is identical to previous value (e.g., text was already correct)
    LaunchedEffect(nlpState.resultVersion) {
        if (!nlpState.isLoading && nlpState.correctedText.isNotEmpty() && aiProcessingAction != null) {
            messageText = nlpState.correctedText
            aiProcessingAction = null
        }
    }

    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Observed: a peer's change flips every gate
    // below (AI, save, FLAG_SECURE) without reopening the chat.
    val advPrivacy = com.oshi.messenger.ui.components.rememberAdvancedChatPrivacy(publicKey)
    com.oshi.messenger.ui.components.AdvancedChatPrivacyWindowGuard(publicKey, advPrivacy)

    // Build conversation context for AI and trigger smart replies on new received messages
    val conversationContext = remember(messages.size, advPrivacy) {
        // F-16. This was a hand-rolled 7-prefix copy of the catalog, and one of
        // its entries could never match (pushpin + U+FE0F where the wire prefix
        // has the bare codepoint), so every shared location went verbatim into
        // the LLM prompt. iOS deleted the same list - ChatView.swift:320-327, now
        // `!OSHIControlPayload.isControl(text)`. One question, one catalog.
        val readableMessages = messages
            .filter { msg ->
                msg.content.isNotBlank() &&
                    !OSHIControlPayload.isControl(msg.content) &&
                    msg.content.length > 1
            }
            // DESC order: the five most recent are at the HEAD, and the LLM wants
            // them oldest-first.
            .take(5)
            .reversed()
            .map { Pair(it.isFromMe, it.content) }
        // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ nothing from a protected chat reaches the model.
        nlpViewModel.buildConversationContext(
            com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.aiReadable(advPrivacy, readableMessages)
        )
    }

    // Trigger smart replies when the newest message is from the other party.
    // `getMessagesForContact` is ORDER BY timestamp DESC (MessageDao:436), so the
    // newest row is index 0 - this read `lastOrNull()`, i.e. the OLDEST message
    // in the whole conversation, and generated replies to it.
    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ no smart replies on a protected chat, and
    // any already on screen go the moment it is switched on.
    LaunchedEffect(advPrivacy) {
        if (!com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.aiAllowed(advPrivacy)) {
            nlpViewModel.clearSmartReplies()
        }
    }
    LaunchedEffect(messages.size) {
        if (!com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.aiAllowed(advPrivacy)) return@LaunchedEffect
        // H-5. F-16 fixed the CONTEXT builder above and left this trigger raw, so
        // when the newest received message was a shared location or a call summary
        // the on-device LLM was handed `📍LOCATION📍{"latitude":48.85,…}` verbatim
        // (`NLPManager.kt:1124-1126` and `:1192` interpolate it straight into the
        // prompt). Same question, same catalog, two lines apart.
        val lastMsg = messages.firstOrNull { !OSHIControlPayload.isControl(it.content) }
        if (lastMsg != null && !lastMsg.isFromMe && lastMsg.content.isNotBlank() && messageText.isEmpty()) {
            nlpViewModel.generateSmartReplies(lastMsg.content)
            nlpViewModel.suggestToneForLastMessage(lastMsg.content)
        }
    }

    // Location sharing — process-wide singleton (iOS `LocationSharingManager.shared`),
    // so a live share survives leaving this screen and stays stoppable.
    val locationManager = viewModel.locationSharingManager
    var showLocationSheet by remember { mutableStateOf(false) }
    var showCheckInSheet by remember { mutableStateOf(false) }
    // "Disguise as photo" (STEGO-v1) composer sheet.
    var showDisguiseSheet by remember { mutableStateOf(false) }
    var showPollSheet by remember { mutableStateOf(false) }
    // Read once per composition rather than per bubble: it is a Keychain-backed
    // call, and a long thread would make it on every row.
    val myOwnPublicKey = remember { viewModel.myPublicKey() }

    // Wallpaper — synced from repository via ViewModel
    val currentWallpaper by viewModel.currentWallpaper.collectAsState()
    var showWallpaperPicker by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    // 💓 __ALIVE_CHECK_IN_2026_11_04__
    var showAliveSheet by remember { mutableStateOf(false) }
    val aliveEntry by viewModel.aliveCheckIn.collectAsState()
    // __TAP_THE_FACE_2026_10_27__ Reporter's N9: the contact card, opened by
    // pressing the face in the top bar — which until now ignored every tap.
    var showContactCard by remember { mutableStateOf(false) }
    // __OPEN_CONTACT_FROM_THE_LIST_2026_11_13__ (Anatole N8) "Open contact" on a
    // conversation row lands here with the card already up.
    LaunchedEffect(publicKey) {
        if (com.oshi.messenger.ui.components.PendingContactCard.consume(publicKey)) {
            showContactCard = true
        }
    }

    // (N11) Pinch-to-zoom TEXT SIZE, scoped to this one conversation — reset
    // when `publicKey` changes (a fresh chat), never written to any global
    // setting. LocalTextStyle is provided only around this screen's own
    // LazyColumn below, so it cannot leak into any other screen's Text.
    var chatTextScale by rememberSaveable(publicKey) { mutableFloatStateOf(1f) }
    // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Observed, so the label flips the
    // moment the switch moves — including when the move arrived from the peer.
    val offlineOnlyStates by com.oshi.messenger.service.OfflineOnlyStore.states.collectAsState()
    val offlineOnly = remember(offlineOnlyStates, publicKey) {
        com.oshi.messenger.service.OfflineOnlyStore.isOfflineOnly(publicKey)
    }
    // __MESH_ONLY_UX_2026_11_03__ Who is reachable over the radios right now, so
    // the indicator in the title can be about THIS person rather than about the
    // mesh in general.
    val meshPeerKeys by viewModel.meshPeerKeys.collectAsState()
    // __SCHEDULE_CALL_REDESIGN_2026_09_29__ "Schedule a call", iOS parity.
    var showScheduleCallSheet by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showBubbleStyleSheet by remember { mutableStateOf(false) }
    var showAISheet by remember { mutableStateOf(false) }

    // Per-chat media gallery (P2 #20)
    var showMediaGallery by remember { mutableStateOf(false) }
    // When non-null, show the full-screen media viewer on top of the gallery.
    var galleryViewerMedia by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }

    // Message actions (long press menu)
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var showMessageActions by remember { mutableStateOf(false) }
    var showForwardPicker by remember { mutableStateOf(false) }

    // Translate sheet state (6B.9)
    var showTranslateSheet by remember { mutableStateOf(false) }
    var translateMessageContent by remember { mutableStateOf("") }

    // Recording hint overlay state (6B.14)
    val recordingHintShown = remember {
        context.getSharedPreferences("oshi_prefs", Context.MODE_PRIVATE)
            .getBoolean("recording_hint_shown", false)
    }
    var showRecordingHint by remember { mutableStateOf(false) }

    // Edit message state
    var editingMessage by remember { mutableStateOf<Message?>(null) }
    // __SHOW_WHAT_IS_BEING_EDITED_2026_11_05__ The body as it stood when Edit
    // was tapped, so the banner can show the BEFORE while the field holds the
    // after. The banner used to print `editedContent ?: content`, which is
    // exactly what the field was prefilled with — the same sentence twice, which
    // told the user nothing about what they were changing.
    var editingOriginal by remember { mutableStateOf("") }
    val conversations by viewModel.allConversations.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current

    // Voice recording state
    val recordingState by audioRecorder.recordingState.collectAsState()
    val recordingDuration by audioRecorder.recordingDuration.collectAsState()
    val waveformAmplitudes by audioRecorder.waveformAmplitudes.collectAsState()
    val selectedEffect by audioRecorder.selectedEffect.collectAsState()
    var isRecording by remember { mutableStateOf(false) }
    // __VOICE_PREVIEW_2026_09_29__ A finished take waiting in the preview sheet.
    var pendingVoice by remember { mutableStateOf<com.oshi.messenger.service.AudioRecorderManager.VoiceClip?>(null) }
    var recordingCancelOffset by remember { mutableStateOf(0f) }

    // Audio playback state
    val playbackState by audioPlayer.playbackState.collectAsState()
    val currentPlayingMessageId by audioPlayer.currentMessageId.collectAsState()
    val playbackProgress by audioPlayer.playbackProgress.collectAsState()
    val isSpeakerOn by audioPlayer.isSpeakerOn.collectAsState()
    val playbackSpeed by audioPlayer.playbackSpeed.collectAsState()

    // Reactions state
    val reactionsMap by reactionManager.reactions.collectAsState()

    // Blocked contact state
    val isContactBlocked by viewModel.isContactBlocked.collectAsState()

    // The seeded welcome thread. Its "sender" (`oshi.welcome`) is a label, not a
    // public key, so there is nobody to send to — see ChatViewModel.isSystemThread.
    val isSystemThread by viewModel.isSystemThread.collectAsState()
    // __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ notice, not a lock
    val isWelcomeThread by viewModel.isWelcomeThread.collectAsState()

    // A `lora!<nodehex>` pseudo-identity thread — a bare Meshtastic node, not an
    // OSHI contact. Unlike the welcome thread this one CAN be sent into (the
    // interop reply lane), so it drives a marker, not a banner that hides the
    // composer. See ChatViewModel.isLoRaInteropThread.
    val isLoRaInteropThread by viewModel.isLoRaInteropThread.collectAsState()

    // __MESHCORE_INTEROP_2026_11_25__ A MeshCore user or channel: never "E2E", always the MeshCore label;
    // a public / hashtag channel also says that anyone who knows its name reads it.
    val isMeshCoreThread by viewModel.isMeshCoreThread.collectAsState()
    val isMeshCoreKey = remember(publicKey) { com.oshi.messenger.service.lora.MeshCoreThread.isMeshCoreKey(publicKey) }
    val isMeshCoreChannel = remember(publicKey) { com.oshi.messenger.service.lora.MeshCoreThread.isChannelKey(publicKey) }
    // Collected only in a MeshCore thread (the key never changes for this screen), so an ordinary chat never
    // starts the MeshCore radio manager.
    val meshCore = if (isMeshCoreKey) {
        remember { com.oshi.messenger.service.lora.MeshCoreManager.getInstance(context.applicationContext) }
    } else null
    val meshCoreChannels by (meshCore?.channels ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.oshi.messenger.service.lora.MeshCore.ChannelInfo>()) }).collectAsState()
    val meshCoreChatReady by (meshCore?.chatReady ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val meshCoreSelfName by (meshCore?.selfName ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    val meshCorePending by (meshCore?.pendingTexts ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    val meshCoreNote = if (isMeshCoreThread) MeshCoreUi.threadNote(publicKey, meshCoreChannels) else null

    // Pinned message state (6A.6)
    val pinnedMessage by viewModel.pinnedMessage.collectAsState()

    // Translate sheet state (RP-2) — the real translator, not a placeholder
    val translationState by viewModel.translationState.collectAsState()

    // Pull-to-refresh state (6B.13)
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    // Toolbar dialog states (6B.12)
    var showAliasDialog by remember { mutableStateOf(false) }
    var aliasText by remember { mutableStateOf("") }
    var showResetSessionConfirm by remember { mutableStateOf(false) }
    var showDeleteConversationConfirm by remember { mutableStateOf(false) }
    // 🪦 Stale-identity confirm — prevents accidental drops; explicit
    // confirmation step before adding the key to the local stale set.
    var showMarkStaleConfirm by remember { mutableStateOf(false) }

    // Conversation deleted navigation trigger (6B.12)
    val conversationDeleted by viewModel.conversationDeleted.collectAsState()
    LaunchedEffect(conversationDeleted) {
        if (conversationDeleted) {
            onBack()
        }
    }

    // Coroutine scope for scroll-to-bottom
    val coroutineScope = rememberCoroutineScope()

    // Media attachment state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isViewOnceEnabled by remember { mutableStateOf(false) }
    // 🪙 __CASHU_SEND_READY_2026_11_09__ Non-null = the Send-bitcoin sheet is
    // open, prefilled with this text ("" from the + menu).
    var cashuDraft by remember { mutableStateOf<String?>(null) }

    // __LOCAL_GIF_LIBRARY_2026_09_13__ GIF & sticker picker, iOS parity
    // (`ChatView.showGifPicker` → `.sheet { StickerGifPicker { … } }`).
    var showGifPicker by remember { mutableStateOf(false) }

    // Schedule sheet state (iOS parity)
    var showScheduleSheet by remember { mutableStateOf(false) }
    val scheduledMessageManager: com.oshi.messenger.service.ScheduledMessageManager = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.oshi.messenger.service.ScheduledMessageWorkerEntryPoint::class.java
        ).scheduledMessageManager()
    }

    // Media preview with caption (before sending)
    //
    // 🖼️ M-14 (2026-08-25). This used to be one image OR one video. iOS lets you
    // pick several in one pass, each with its OWN view-once toggle
    // (`MediaPickerView.swift:73-86` `allowMultipleSelection`, the per-item strip at
    // `:383-404`, delivered by `ChatView.sendMultipleMediaMessages`, `:2329-2353`),
    // and Android sent them one at a time through a dialog that could only hold one.
    // A LIST now, always — a single pick is a list of one, so there is exactly one
    // code path and no "multi" special case for the camera and share-sheet producers
    // to fall out of sync with.
    var pendingMedia by remember { mutableStateOf<List<PendingMedia>>(emptyList()) }
    // __PHOTO_MARKUP_2026_11_08__ which picked image is being drawn on, if any
    var markupIndex by remember { mutableStateOf<Int?>(null) }
    // __IMAGE_CROP_2026_11_02__ (N13) which pending item the crop screen is open on.
    var cropIndex by remember { mutableStateOf<Int?>(null) }
    var showMediaPreview by remember { mutableStateOf(false) }
    // Slow-send guard for media over LoRa. When a send is about to ride LoRa
    // (opted in, radio connected, no physical internet) the actual send lambda is
    // stashed here so a confirmation dialog can run first; null means nothing is
    // awaiting confirmation. Mirrors iOS `pendingLoRaMedia` (ChatView.swift:2443).
    var pendingLoRaSend by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Upload/compression progress
    val isMediaProcessing by viewModel.mediaCompression.isProcessing.collectAsState()
    val compressionProgress by viewModel.mediaCompression.compressionProgress.collectAsState()

    // __PICKER_LATENCY_2026_09_18__ True while a picked file is being read off the
    // provider, BEFORE any compression starts.
    //
    // Reading a gallery video is not instant: `readBytes()` on a 100 MB clip is
    // seconds of I/O, and until this flag existed those seconds were spent with
    // the picker already dismissed and nothing at all on screen — the app looked
    // dead, and the obvious thing for a person to do is tap again. The progress
    // strip below is the one already used for compression; this just lets it turn
    // on a step earlier, indeterminate, because a `ContentResolver` read reports
    // no progress.
    var isPreparingMedia by remember { mutableStateOf(false) }

    // Permission launcher for RECORD_AUDIO
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = audioRecorder.startRecording()
        } else {
            Toast.makeText(context, context.getString(R.string.chat_mic_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    // __ATTACH_PANEL_2027_2026_11_16__ "Gallery" / "All photos": ONE system picker for
    // photos and videos together (the old menu had two). Each URI is sorted by its MIME
    // and read by the panel's loader (GIFs untouched, photos downsampled, videos capped),
    // then lands in the same preview dialog as before.
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(com.oshi.messenger.ui.components.ChatMediaSelection.MAX_SELECTION)
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                isPreparingMedia = true
                val items = com.oshi.messenger.ui.components.attach.RecentMedia.fromPickerUris(context, uris)
                val loaded = com.oshi.messenger.ui.components.attach.RecentMedia.load(context, items, isViewOnceEnabled)
                isPreparingMedia = false
                if (loaded.tooLarge) {
                    Toast.makeText(context, context.getString(R.string.chat_video_too_large), Toast.LENGTH_SHORT).show()
                } else if (loaded.failed) {
                    Toast.makeText(context, context.getString(R.string.chat_failed_load_image), Toast.LENGTH_SHORT).show()
                }
                if (loaded.media.isNotEmpty()) {
                    pendingMedia = loaded.media
                    showMediaPreview = true
                }
            }
        }
    }

    // Video CAPTURE launcher (1-1): system camera in video mode. On result we read
    // bytes from the temp URI, enforce the ceiling, and route the bytes through the
    // same MediaPreviewDialog used by gallery picks.
    //
    // M-16: this path capped at 50 MB where every other media path in the app
    // allows 200 MB, so a capture that the gallery picker would have accepted was
    // refused for being "too large". One ceiling, named once.
    var pendingCameraVideoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // __VIDEO_NOTE_2026_09_24__ the round recorder the composer's video button now opens.
    var showVideoNoteRecorder by remember { mutableStateOf(false) }
    val videoCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success: Boolean ->
        val uri = pendingCameraVideoUri
        pendingCameraVideoUri = null
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Cancelled capture: drop the empty/partial target.
        if (!success) com.oshi.messenger.data.local.atrest.PlaintextScratch.deleteCapture(context.cacheDir, uri?.lastPathSegment)
        if (success && uri != null) {
          // __PICKER_LATENCY_2026_09_18__ Same read, same dispatcher, same reason
          // as the gallery picker above: a minute of 4K out of the camera is tens
          // of megabytes to copy, and the main thread must not be the one doing it.
          coroutineScope.launch {
            isPreparingMedia = true
            try {
                val videoData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { st -> st.readBytes() }
                    } finally {
                        // The send works on the in-memory bytes: the plaintext capture
                        // in cacheDir is not needed past this read.
                        com.oshi.messenger.data.local.atrest.PlaintextScratch.deleteCapture(context.cacheDir, uri.lastPathSegment)
                    }
                }
                if (videoData != null) {
                    if (videoData.size > com.oshi.messenger.service.MediaCompressionManager.IPFS_MAX_SIZE) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_video_too_large),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        pendingMedia = listOf(
                            PendingMedia(bytes = videoData, isVideo = true, viewOnce = isViewOnceEnabled)
                        )
                        showMediaPreview = true
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_failed_load_video),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isPreparingMedia = false
            }
          }
        }
    }

    // ── Take photo (audit M-10) ────────────────────────────────────────────────
    //
    // A 1:1 conversation could record a VIDEO from the composer and pick a photo from
    // the gallery, but there was no way to TAKE a photo. Group chat has had one all
    // along (`GroupChatScreen.kt:267`, `ActivityResultContracts.TakePicture`), and so
    // does iOS (`MediaPickerView.swift:92-113` offers `media_picker.take_photo` next to
    // `media_picker.record_video`). `CAMERA` is already declared in the manifest, so
    // nothing was blocking it.
    //
    // The bytes are routed into the SAME MediaPreviewDialog the gallery pick uses, so
    // the view-once toggle and the caption apply to a captured photo too.
    var cameraImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val photoCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = cameraImageUri
        cameraImageUri = null
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Cancelled capture: drop the empty target.
        if (!success) com.oshi.messenger.data.local.atrest.PlaintextScratch.deleteCapture(context.cacheDir, uri?.lastPathSegment)
        if (success && uri != null) {
            coroutineScope.launch {
                val data = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                      try {
                        // Same 1920 px cap as the gallery path: a modern camera frame is
                        // 40+ MP and decoding it whole is an OOM on a mid-range device.
                        val maxDim = 1920
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { st ->
                            BitmapFactory.decodeStream(st, null, bounds)
                        }
                        val origW = bounds.outWidth
                        val origH = bounds.outHeight
                        if (origW > maxDim || origH > maxDim) {
                            var inSampleSize = 1
                            while (origW / inSampleSize >= maxDim * 2 && origH / inSampleSize >= maxDim * 2) {
                                inSampleSize *= 2
                            }
                            val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                            val sampled = context.contentResolver.openInputStream(uri)?.use { st ->
                                BitmapFactory.decodeStream(st, null, opts)
                            }
                            if (sampled == null) null else {
                                val longest = maxOf(sampled.width, sampled.height)
                                val bmp = if (longest > maxDim) {
                                    val scale = maxDim.toFloat() / longest
                                    val sw = (sampled.width * scale).toInt().coerceAtLeast(1)
                                    val sh = (sampled.height * scale).toInt().coerceAtLeast(1)
                                    val scaled = android.graphics.Bitmap.createScaledBitmap(sampled, sw, sh, true)
                                    sampled.recycle()
                                    scaled
                                } else sampled
                                val baos = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                                bmp.recycle()
                                baos.toByteArray()
                            }
                        } else {
                            context.contentResolver.openInputStream(uri)?.use { st -> st.readBytes() }
                        }
                      } finally {
                        // Bytes are in memory now; the plaintext JPEG in cacheDir goes.
                        com.oshi.messenger.data.local.atrest.PlaintextScratch.deleteCapture(context.cacheDir, uri.lastPathSegment)
                      }
                    }
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (data != null) {
                    pendingMedia = listOf(
                        PendingMedia(bytes = data, isVideo = false, viewOnce = isViewOnceEnabled)
                    )
                    showMediaPreview = true
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.chat_failed_load_image),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val launchPhotoCapture: () -> Unit = {
        try {
            val photoFile = java.io.File.createTempFile("oshi_photo_", ".jpg", context.cacheDir)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            cameraImageUri = uri
            photoCaptureLauncher.launch(uri)
        } catch (_: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.error_camera_unavailable),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            launchPhotoCapture()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.permission_camera_message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Camera with its permission asked first (the composer button and the panel).
    val openCamera: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            launchPhotoCapture()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Document picker launcher
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
          // __PICKER_LATENCY_2026_09_18__ Third picker, same read, same thread.
          //
          // Found by the test written for the video one: a document is picked by
          // exactly the same kind of callback, dispatched to Main, and a 200 MB
          // PDF or zip is copied out of the provider right there. Nobody had
          // reported it because a document is usually small — which is precisely
          // the sort of "usually" that makes a freeze look random.
          coroutineScope.launch {
            isPreparingMedia = true
            try {
                val documentData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(it)?.use { st -> st.readBytes() }
                }
                // Get filename from URI
                val cursor = context.contentResolver.query(it, null, null, null, null)
                val fileName = cursor?.use { c ->
                    val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst()
                    if (nameIndex >= 0) c.getString(nameIndex) else "document"
                } ?: "document"
                if (documentData != null) {
                    viewModel.sendDocumentMessage(documentData, fileName)
                    Toast.makeText(context, context.getString(R.string.chat_sending_document), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.chat_failed_load_document), Toast.LENGTH_SHORT).show()
            } finally {
                isPreparingMedia = false
            }
          }
        }
    }

    // 🔧 FIX (2026-08-24 three-gaps): the OSHI contact picker.
    //
    // What was here: `ActivityResultContracts.PickContact()` — the SYSTEM address
    // book — whose result was `viewModel.sendMessage("[Contact] $name")`, a plain
    // TEXT message. No public key, no `MediaType.CONTACT`, nothing the recipient
    // could tap. Meanwhile `ContactShareManager.shareContactInApp` (the correct
    // implementation) and `ContactCardBubble` (the correct renderer) both existed
    // with zero reachable callers — the bubble could only ever draw a message
    // Android had no way to send.
    //
    // iOS shares a REACHABLE IDENTITY, not a phonebook row: `ContactSelectorView`
    // over `messageManager.conversations` (`OSHI/ChatView.swift:4981-4985`,
    // `ContactSelectorView.swift:11-43`). `OshiContactPickerSheet` is that list.
    val shareableContacts by viewModel.shareableContacts.collectAsState()
    var showContactPicker by remember { mutableStateOf(false) }

    if (showContactPicker) {
        com.oshi.messenger.ui.components.OshiContactPickerSheet(
            contacts = shareableContacts,
            onContactSelected = { pk, alias ->
                viewModel.shareOshiContact(pk, alias)
                Toast.makeText(context, context.getString(R.string.contact_shared), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showContactPicker = false }
        )
    }

    LaunchedEffect(publicKey) {
        viewModel.loadConversation(publicKey)
    }

    // 👀 READ STATE (2026-08-24). `loadConversation` above marks the thread read on
    // ENTRY and that was the only trigger there was, which is why a message landing
    // in an already-open chat stayed pending until the user left and came back.
    //
    // This is the second trigger, and the reason it hangs off the lifecycle rather
    // than off the message list: `ActiveConversation` must mean "foregrounded AND
    // this thread on screen" and nothing looser, or the peer gets a ✓✓ Read for a
    // message that was never seen. `ON_RESUME`/`ON_PAUSE` on the screen's own
    // lifecycle owner answers exactly that — backgrounding the app pauses it, and
    // navigating to another chat pauses it. `addObserver` replays up to the current
    // state, so a screen composed while already resumed still gets its `ON_RESUME`.
    //
    // iOS: `ChatView.swift:826-831` (`isVisible` on `.onAppear` / `.onDisappear`)
    // feeding `:863-866` (`if isVisible { markMessagesAsRead() }`).
    val readStateLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(publicKey, readStateLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onChatVisible(publicKey)
                Lifecycle.Event.ON_PAUSE -> viewModel.onChatHidden(publicKey)
                else -> Unit
            }
        }
        readStateLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            readStateLifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onChatHidden(publicKey)
        }
    }

    // Every row the thread renders, in LazyColumn order — the ONE list that both
    // draws the list and answers "where is message X?" (F-1, see ChatThreadItems).
    //
    // Deliberately NOT `remember(messages)`: `Message.equals` compares ids only
    // (Message.kt:36-44), so an edit, a delivery tick or a read receipt yields a
    // list that compares EQUAL and a remembered value would hand the bubbles
    // stale rows. Rebuilding costs the same O(n) filter the LazyColumn body used
    // to run on every recomposition anyway, and the rebuilt list still compares
    // equal, so `LaunchedEffect(threadItems)` below does not restart on it.
    //
    // __CHAT_OPEN_PERF_2026_11_14__ Now built in the ViewModel, on Dispatchers.Default,
    // once per list change (`ChatViewModel.threadItems`). The NOTE above is obsolete:
    // `Message.equals` compares every rendered field since the fix in Message.kt, so a
    // delivery tick or an edit DOES produce an unequal list and a new emission.
    val threadItems by viewModel.threadItems.collectAsState()
    val threadLoaded by viewModel.threadLoaded.collectAsState()

    // reverseLayout = true, so index 0 = newest. firstVisibleItemIndex == 0 means at bottom.
    // - First non-empty render: instant scroll (feels like "opened already at the bottom").
    // - Your OWN send always scrolls: iOS states the rule at ChatView.swift:864-870 and
    //   applies it at :908-928 (`isMyOwnSend || wasPinnedToBottom`). Only an INCOMING
    //   message is gated on being near the bottom, otherwise we'd steal scroll while
    //   they're reading history.
    //
    // __OPEN_SCROLL_2026_11_16__ Driven by the RENDERED rows (`threadItems`), not by
    // `messages`. `threadItems` is built on Dispatchers.Default a beat after
    // `messages` emits, so keying the landing on `messages.size` scrolled a list that
    // held only the E2E notice; when the rows then arrived, LazyColumn kept that
    // notice (the topmost row) in place and the chat opened on its OLDEST message,
    // then jumped down ~0.5 s later (seen frame by frame on the emulator).
    // Keyed on the newest row's id rather than a count: a size change that is not a
    // new message (a delete higher up) must not yank anyone, and a burst of several
    // messages is judged by whether the reader could see the previous newest rows,
    // which stays true whichever frame the layout lands on.
    // Saveable: coming back from another screen restores the list position
    // (rememberLazyListState is saveable) and must not land a second time.
    var hasLandedAtBottom by rememberSaveable(publicKey) { mutableStateOf(false) }
    // __OPEN_AT_THE_FIRST_UNREAD__ (iOS parity) the row that carries the
    // "Unread messages" line, captured once at landing.
    var unreadDividerKey by rememberSaveable(publicKey) { mutableStateOf<String?>(null) }
    var pinUnreadUntil by remember(publicKey) { mutableStateOf(0L) }
    val previousNewestKeys = remember(publicKey) { mutableListOf<String>() }
    val followInterrupted = remember(publicKey) { booleanArrayOf(false) }
    val unreadTopMarginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    // A thread whose rows are all silent payloads never lands (no newest row): show
    // the list anyway shortly after the load rather than keep it invisible.
    var showListAnyway by remember(publicKey) { mutableStateOf(false) }
    LaunchedEffect(publicKey, threadLoaded) {
        if (threadLoaded) { kotlinx.coroutines.delay(1_000L); showListAnyway = true }
    }
    val openUnreadIds by viewModel.openUnreadIds.collectAsState()
    val newestRow = ChatThreadItems.newestMessage(threadItems)
    LaunchedEffect(newestRow?.id, openUnreadIds != null) {
        if (newestRow == null) return@LaunchedEffect
        if (!hasLandedAtBottom) {
            val unreadIds = openUnreadIds ?: return@LaunchedEffect // capture still running
            val items = threadItems
            val anchor = ChatThreadItems.openUnreadAnchor(items, unreadIds)
            val anchorIndex = ChatThreadItems.positionOf(items, anchor)
            if (anchor != null && anchorIndex >= 0) {
                unreadDividerKey = items[anchorIndex].key
                pinUnreadUntil = System.currentTimeMillis() + 2_500L
                landWithRowAtTop(listState, anchorIndex, unreadTopMarginPx)
            } else {
                listState.scrollToItem(0)
            }
            hasLandedAtBottom = true
            previousNewestKeys.clear()
            previousNewestKeys.addAll(threadItems.take(3).map { it.key })
        } else if (previousNewestKeys.isEmpty()) {
            // Back on this screen with its saved position (hasLandedAtBottom is
            // saveable, these keys are not): nothing new arrived, keep the position.
            previousNewestKeys.addAll(threadItems.take(3).map { it.key })
        } else {
            pinUnreadUntil = 0L
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }
            // A burst arrives as several inserts: each restarts this effect and cancels
            // the glide the previous one started, before the rows it was judged on
            // could come back into view. `followInterrupted` carries that decision
            // over instead of re-judging from a half-finished scroll.
            val follow = followInterrupted[0] ||
                ChatThreadItems.shouldFollowNewest(newestRow.isFromMe, previousNewestKeys, visibleKeys)
            previousNewestKeys.clear()
            previousNewestKeys.addAll(threadItems.take(3).map { it.key })
            if (follow) {
                followInterrupted[0] = true
                listState.animateScrollToItem(0)
                followInterrupted[0] = false
            }
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            // The reader took over: a cancelled glide is theirs to cancel.
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) followInterrupted[0] = false
        }
    }
    // Keep the unread line where it landed while the rows under it settle (photos
    // decoding, the quick-reply strip shrinking the viewport) — reverseLayout pins
    // the BOTTOM row, so growth below the line pushes it off the top. Stops at the
    // reader's first drag, at the next new message, or after 2.5 s.
    LaunchedEffect(unreadDividerKey) {
        val key = unreadDividerKey ?: return@LaunchedEffect
        launch {
            listState.interactionSource.interactions.collect {
                if (it is androidx.compose.foundation.interaction.DragInteraction.Start) pinUnreadUntil = 0L
            }
        }
        var landed: Triple<Int, Int?, Int?>? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == key }
            Triple(info.viewportSize.height, row?.offset, row?.size)
        }.collect { now ->
            if (System.currentTimeMillis() > pinUnreadUntil) return@collect
            if (landed == null) { landed = now; return@collect } // the landing itself
            if (now == landed || listState.isScrollInProgress) return@collect
            val index = threadItems.indexOfFirst { it.key == key }
            if (index < 0) return@collect
            landWithRowAtTop(listState, index, unreadTopMarginPx)
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == key }
            landed = Triple(info.viewportSize.height, row?.offset, row?.size)
        }
    }

    // Auto-scroll to current search match when query changes.
    // Resolved against the RENDERED list (F-1), not `messages`.
    LaunchedEffect(currentSearchMatchId, threadItems) {
        if (currentSearchMatchId != null) {
            val targetIndex = ChatThreadItems.positionOf(threadItems, currentSearchMatchId)
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    // Report dialog
    if (showReportDialog) {
        ReportContactDialog(
            alreadyReported = viewModel.hasReportedContact(),
            onDismiss = { showReportDialog = false },
            onSubmit = { reason, details ->
                showReportDialog = false
                viewModel.reportContact(reason, details) { state ->
                    Toast.makeText(context, context.getString(reportOutcomeText(state)), Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // Location Share Sheet
    if (showLocationSheet) {
        LocationShareSheet(
            locationManager = locationManager,
            conversationId = publicKey,
            onShareLocation = { locationMessage ->
                viewModel.sendLocationMessage(locationMessage)
            },
            onDismiss = { showLocationSheet = false },
            recipientName = contactDisplayName ?: contact?.alias ?: ""
        )
    }

    // Check-In (accompagnement) Sheet — iOS parity
    //
    // __MAP_OPENER_2026_09_23__ CheckInSheet is a plain full-size Column, and it was
    // emitted here, BEFORE the chat's Scaffold — which then drew over it. Tapping
    // "Check-In" in the attachment menu did nothing visible: the feature was
    // unreachable from a chat. Presented in a full-height ModalBottomSheet, like the
    // location sheet next to it (a Dialog window was tried first: it sat one status
    // bar lower than the screen and hid "Start Check-In" under the bottom edge).
    // __TAP_THE_FACE_2026_10_27__ Everything it needs is already loaded here, so
    // this is a sheet rather than a navigation destination.
    if (showContactCard) {
        com.oshi.messenger.ui.components.ContactProfileSheet(
            publicKey = publicKey,
            // Exactly the expression the top bar renders, so the card and the
            // bar can never disagree about what someone is called.
            displayName = contactDisplayName ?: contact?.alias?.takeIf { it.isNotEmpty() } ?: publicKey.take(8),
            profilePicture = contact?.profilePicture,
            profileLinksManager = viewModel.profileLinksManager,
            // __LAST_SEEN_ON_THE_CARD_2026_10_28__ (N16)
            lastSeen = contact?.lastSeen,
            // __RENAME_ON_THE_CARD_2026_11_02__ (N4) the SAME dialog the overflow
            // opens, so there is one notion of renaming, not two.
            onRename = {
                aliasText = contact?.alias.orEmpty()
                showAliasDialog = true
            },
            // (N16) same guarded call path as the top bar's own buttons —
            // one notion of "can I call this contact", not two.
            onCall = { video ->
                if (com.oshi.messenger.service.BlockedByPeerStore.get(context).isBlockedBy(publicKey)) {
                    Toast.makeText(context, context.getString(R.string.call_error_unavailable), Toast.LENGTH_SHORT).show()
                } else onCallClick(video)
            },
            // (N5) the line the top bar no longer carries; a LoRa interop thread
            // gets its own warning instead, never an E2E claim.
            encryptionLabel = if (isMeshCoreThread) {
                stringResource(R.string.meshcore_not_e2e)
            } else if (isLoRaInteropThread) {
                stringResource(R.string.lora_interop_unverified)
            } else {
                stringResource(R.string.encryption_e2e)
            },
            onDismiss = { showContactCard = false }
        )
    }

    if (showDisguiseSheet) {
        DisguiseAsPhotoSheet(
            contactPublicKey = publicKey,
            contactName = contactDisplayName ?: contact?.alias?.takeIf { it.isNotEmpty() } ?: publicKey.take(8),
            initialText = messageText,
            onDismiss = { showDisguiseSheet = false },
        )
    }

    if (showCheckInSheet) {
        OshiModalBottomSheet(
            onDismissRequest = { showCheckInSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = null
        ) {
            CheckInSheet(
                contactPublicKey = publicKey,
                contactName = contactDisplayName ?: contact?.alias ?: publicKey.take(8),
                onDismiss = { showCheckInSheet = false },
                onCheckInStarted = { _ ->
                    showCheckInSheet = false
                }
            )
        }
    }

    // 📊 Sondage — même présentation que la feuille Check-In juste au-dessus.
    if (showPollSheet) {
        OshiModalBottomSheet(
            onDismissRequest = { showPollSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                com.oshi.messenger.ui.components.PollComposerSheet(
                    creatorPublicKey = myOwnPublicKey,
                    onDismiss = { showPollSheet = false },
                    onSend = { poll -> viewModel.sendPoll(poll) },
                )
            }
        }
    }

    // Wallpaper Picker Sheet — syncs wallpaper to both sides via repository
    if (showWallpaperPicker) {
        WallpaperPickerSheet(
            conversationId = publicKey,
            currentWallpaper = currentWallpaper,
            onSelectWallpaper = { wallpaper ->
                viewModel.setWallpaper(wallpaper)
            },
            onDismiss = { showWallpaperPicker = false },
            onSetAsDefaultForAllChats = { wallpaper ->
                viewModel.setWallpaperForAllChats(wallpaper)
            }
        )
    }

    // (Media gallery + viewer used to be emitted HERE — see the note at the end
    // of this composable for why they had to move below the Scaffold.)

    cashuDraft?.let { draft ->
        com.oshi.messenger.ui.components.CashuSendSheet(
            initialText = draft,
            isGroup = false,
            onSend = { raw, onDone -> viewModel.sendCashuToken(raw, onDone) },
            onDismiss = { cashuDraft = null },
        )
    }

    // __LOCAL_GIF_LIBRARY_2026_09_13__ Deux onglets, GIFs et Autocollants; les
    // résultats GIPHY s'ajoutent DANS ces onglets, à la suite du paquet local.
    if (showGifPicker) {
        StickerGifPicker(
            onSelected = { bytes, fileName -> viewModel.sendGifMessage(bytes, fileName) },
            onDismiss = { showGifPicker = false }
        )
    }


    // Opt-in privacy switch, read straight from the same DataStore key the
    // Settings screen writes, so the two cannot drift. Default false.
    val blockForwardCopy by remember(context) {
        context.dataStore.data.map { it[SettingsViewModel.KEY_BLOCK_FORWARD_COPY] ?: false }
    }.collectAsState(initial = false)

    // Message Actions Dialog (Long Press)
    if (showMessageActions && selectedMessage != null) {
        MessageActionsDialog(
            message = selectedMessage!!,
            blockForwardCopy = blockForwardCopy,
            onDismiss = {
                showMessageActions = false
                selectedMessage = null
            },
            onReactWith = { emoji ->
                reactionManager.addReaction(
                    messageId = selectedMessage!!.id,
                    emoji = emoji,
                    recipientPublicKey = publicKey
                )
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                showMessageActions = false
                selectedMessage = null
            },
            currentReaction = reactionManager.getReactionSummary(selectedMessage!!.id).myReaction,
            onCopy = {
                // H-4. Copy used to put the raw wire body on the clipboard, so
                // pasting a shared location into any other app produced
                // `📍LOCATION📍{"latitude":48.85,"longitude":2.35,…}` — same for a
                // call summary, a reaction, a check-in, and a reply envelope (whose
                // paste carried the QUOTED message's JSON as well as the body).
                // iOS closed this deliberately at `ChatView.swift:4539-4553`,
                // including the guard: copying a silent payload copies NOTHING and
                // shows no confirmation, rather than putting "" on the pasteboard
                // and claiming success.
                val raw = selectedMessage!!.editedContent ?: selectedMessage!!.content
                val displayContent = OSHIControlPayload.previewText(raw, controlLabels)
                if (displayContent.isNotEmpty()) {
                    val textWithLink = "$displayContent\n\n--- Sent via OSHI Messenger\nhttps://oshimessenger.com"
                    // __CLIPBOARD_HYGIENE_2026_10_01__ decrypted message ⇒ sensitive clip, cleared after 60 s.
                    if (com.oshi.messenger.security.SensitiveClipboard.copyMessageText(context, textWithLink))
                        Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
                }
                showMessageActions = false
                selectedMessage = null
            },
            onForward = {
                showMessageActions = false
                showForwardPicker = true
            },
            onReply = {
                viewModel.setReplyingTo(selectedMessage)
                showMessageActions = false
                selectedMessage = null
                // Focus the text input field
                try { textFieldFocusRequester.requestFocus() } catch (_: Exception) {}
            },
            onEdit = {
                val msg = selectedMessage!!
                editingMessage = msg
                messageText = msg.editedContent ?: msg.content
                editingOriginal = msg.editedContent ?: msg.content
                showMessageActions = false
                selectedMessage = null
                try { textFieldFocusRequester.requestFocus() } catch (_: Exception) {}
            },
            onTranslate = {
              // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ translation runs the message through
              // on-device AI (Gemini Nano / local LLM / ML Kit): not in a protected chat.
              if (advPrivacy) {
                Toast.makeText(context, context.getString(R.string.adv_privacy_blocked_toast), Toast.LENGTH_SHORT).show()
                showMessageActions = false
                selectedMessage = null
              } else {
                // Surface #11, the third H-6 site. The sheet shows this string as
                // "original text" AND sends it to the translator; raw, a shared
                // location asked the model to translate its own coordinate JSON.
                // Prose comes back from the catalog unchanged, so ordinary
                // translation is untouched.
                translateMessageContent = OSHIControlPayload.previewText(
                    selectedMessage!!.editedContent ?: selectedMessage!!.content,
                    controlLabels
                )
                // RP-2: actually RUN the translator. `NLPManager.processTranslateReceived`
                // — a complete Gemini Nano → local LLM → ML Kit cascade — had ZERO call
                // sites, and this sheet printed a hard-coded "coming soon" instead.
                viewModel.translateMessage(translateMessageContent)
                showMessageActions = false
                selectedMessage = null
                showTranslateSheet = true
              }
            },
            onDeleteForMe = {
                viewModel.deleteMessage(selectedMessage!!.id)
                showMessageActions = false
                selectedMessage = null
            },
            onDeleteForEveryone = {
                viewModel.deleteMessageForEveryone(selectedMessage!!.id)
                showMessageActions = false
                selectedMessage = null
            },
            onSave = selectedMessage?.takeIf {
                com.oshi.messenger.security.StrictModeGate.mediaExportAllowed &&  // 🛡 __STRICT_MODE_2026_11_22__
                    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ others' media stays in OSHI.
                    com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.mediaSaveAllowed(advPrivacy, it.isFromMe)
            }?.let { msg ->
                // __CHAT_OPEN_PERF_2026_11_14__ The thread's rows carry attachments
                // only up to 64 KB now, so the bytes are read on tap (off Main) when
                // the row does not have them. Deleted-for-everyone rows have none.
                if (!msg.isDeletedForEveryone && (msg.mediaType == com.oshi.messenger.data.model.MediaType.IMAGE || msg.mediaType == com.oshi.messenger.data.model.MediaType.VIDEO)) {
                    {
                        coroutineScope.launch {
                            val mediaData = msg.mediaData
                                ?: runCatching { viewModel.loadMediaData(msg.id) }.getOrNull()
                            val saved = mediaData != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                saveChatMediaToGallery(context, mediaData, msg.mediaType)
                            }
                            Toast.makeText(context,
                                if (saved) context.getString(R.string.save_to_gallery) + " ✓"
                                else context.getString(R.string.chat_failed_load_image),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else null
            },
            // 📌 RP-3: Pin/Unpin in the 1:1 menu — iOS has had it at
            // `ChatView.swift:4605`; Android offered it only in groups.
            isPinned = pinnedMessage?.id?.equals(selectedMessage!!.id, ignoreCase = true) == true,
            onTogglePin = { viewModel.togglePin(selectedMessage!!.id) }
        )
    }

    // Translate Bottom Sheet (6B.9)
    if (showTranslateSheet) {
        TranslateBottomSheet(
            messageContent = translateMessageContent,
            state = translationState,
            onRetry = { viewModel.translateMessage(translateMessageContent) },
            onCopy = if (blockForwardCopy) null else { translated ->
                // __CLIPBOARD_HYGIENE_2026_10_01__ a translation is the message in clear ⇒ same hygiene.
                if (com.oshi.messenger.security.SensitiveClipboard.copyMessageText(context, translated))
                    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showTranslateSheet = false
                viewModel.clearTranslation()
            }
        )
    }

    // Forward Sheet
    if (showForwardPicker && selectedMessage != null) {
        val fwdMsg = selectedMessage!!
        // __DOC_STREAM_2026_11_11__ Documents (and anything past the hydration budget)
        // reach the UI with `mediaData = null`; forwarding that sent the bare caption as
        // TEXT. Read the bytes from the store when the sheet opens.
        val fwdBytes by produceState<ByteArray?>(fwdMsg.mediaData, fwdMsg.id) {
            if (value == null && fwdMsg.mediaType != null) {
                value = runCatching { viewModel.loadMediaData(fwdMsg.id) }.getOrNull()
            }
        }
        ForwardPickerSheet(
            content = fwdMsg.editedContent ?: fwdMsg.content,
            mediaType = fwdMsg.mediaType,
            mediaData = fwdBytes,
            videoNoteDurationMs = if (fwdMsg.isVideoNote) (fwdMsg.mediaDurationMs ?: 0L) else null,
            onDismiss = { showForwardPicker = false; selectedMessage = null },
            onForwarded = { targetName ->
                Toast.makeText(context, "${context.getString(R.string.message_forwarded)} → $targetName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Media Preview with Caption (before sending). 🖼️ M-14: N items, each with its
    // own view-once toggle, sent as N separate messages exactly like iOS's
    // `sendMultipleMediaMessages` (`ChatView.swift:2329-2353`).
    // __IMAGE_CROP_2026_11_02__ (N13) The crop screen for ONE pending photo.
    // Hosted here, not inside the preview dialog, because the bytes it replaces
    // belong to `pendingMedia` — the dialog only reads them.
    cropIndex?.let { i ->
        val target = pendingMedia.getOrNull(i)
        if (target != null && !target.isVideo) {
            com.oshi.messenger.ui.components.ImageCropForBytes(
                bytes = target.bytes,
                onCancel = { cropIndex = null },
                onCropped = { cropped ->
                    pendingMedia = pendingMedia.mapIndexed { j, m ->
                        if (j == i) m.copy(bytes = cropped) else m
                    }
                    cropIndex = null
                },
            )
        } else {
            // The item was removed under the open screen: close it in an effect,
            // never by writing state during composition.
            LaunchedEffect(i) { cropIndex = null }
        }
    }

    // __PHOTO_MARKUP_2026_11_08__ Same host shape as the crop screen above,
    // including the "item removed under the open editor" case — a dialog whose
    // subject disappeared must close in an effect, never by writing state while
    // composing.
    markupIndex?.let { i ->
        val target = pendingMedia.getOrNull(i)
        if (target != null && !target.isVideo) {
            com.oshi.messenger.ui.components.PhotoMarkupDialog(
                imageBytes = target.bytes,
                onCancel = { markupIndex = null },
                onDone = { drawn ->
                    pendingMedia = pendingMedia.mapIndexed { j, m ->
                        if (j == i) m.copy(bytes = drawn) else m
                    }
                    markupIndex = null
                },
            )
        } else {
            LaunchedEffect(i) { markupIndex = null }
        }
    }

    // __ATTACH_PANEL_2027_2026_11_16__ The one media send, for the preview dialog AND the
    // attachment panel's "Send N" bar. [albumCaption]: the panel's single caption rides
    // the FIRST item ([AttachmentPanelLogic.captions]); the dialog keeps its own rule
    // ([ChatMediaSelection.plan]: a caption only on a single item).
    val sendPendingMedia: (List<PendingMedia>, String, Boolean) -> Unit = { toSend, caption, albumCaption ->
        // The real send, unchanged. One outbound message per picked item,
        // with the caption rule and the per-item view-once flag decided by
        // [ChatMediaSelection.plan] — a pure function, so the fan-out is
        // asserted off-device. Snapshot the current pick so the closure
        // still sends the right bytes if `pendingMedia` is cleared while a
        // confirmation dialog is up.
        val reallySend: (String) -> Unit = { cap ->
            var sentImage = false
            var sentVideo = false
            val planned = if (albumCaption) {
                val caps = com.oshi.messenger.ui.components.attach.AttachmentPanelLogic.captions(toSend.size, cap)
                toSend.mapIndexed { n, m -> com.oshi.messenger.ui.components.MediaSendPlan(m.bytes, m.isVideo, m.viewOnce, caps[n]) }
            } else ChatMediaSelection.plan(toSend, cap)
            planned.forEach { item ->
                if (item.isVideo) {
                    viewModel.sendVideoMessage(item.bytes, item.caption, item.viewOnce)
                    sentVideo = true
                } else {
                    viewModel.sendImageMessage(item.bytes, item.caption, item.viewOnce)
                    sentImage = true
                }
            }
            if (sentImage) {
                Toast.makeText(context, context.getString(R.string.chat_sending_image), Toast.LENGTH_SHORT).show()
            }
            if (sentVideo) {
                Toast.makeText(context, context.getString(R.string.chat_sending_video), Toast.LENGTH_SHORT).show()
            }
            isViewOnceEnabled = false
            pendingMedia = emptyList()
            showMediaPreview = false
        }

        // Slow-send guard: opted in, a radio connected, and no physical
        // internet at all ⇒ this media is about to crawl over LoRa. Confirm
        // first (ChatView.swift:2440-2447). Every other path sends straight
        // through. The .value reads are one-shot and outside recomposition.
        val radio = LoRaRadioProvider.current
        val shouldConfirm = radio.mediaOverLoRaEnabled.value &&
            radio.connectedName.value != null &&
            !hasPhysicalInternet(context)
        if (shouldConfirm) {
            // Hide the preview and hold the send until the user confirms;
            // `toSend` is still needed by the dialog's estimate, so it is
            // kept in `pendingMedia` until the send actually fires/cancels.
            showMediaPreview = false
            pendingLoRaSend = { reallySend(caption) }
        } else {
            reallySend(caption)
        }
    }

    // __ATTACH_PANEL_2027_2026_11_16__ The shared attachment panel (recent photos +
    // action tiles), the same component as the group composer's. Every former row of the
    // old list is a tile here, in the same order of importance.
    if (showAttachmentMenu) {
        val panelActions = listOf(
            com.oshi.messenger.ui.components.attach.AttachAction(
                "gallery", Icons.Rounded.PhotoLibrary, stringResource(R.string.attach_gallery), Color(0xFF0A84FF)
            ) { galleryPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "camera", Icons.Rounded.PhotoCamera, stringResource(R.string.attach_camera), Color(0xFF5E5CE6)
            ) { openCamera() },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "file", Icons.Rounded.Description, stringResource(R.string.attach_file), Color(0xFFFF9F0A)
            ) { documentPickerLauncher.launch(arrayOf("*/*")) },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "gif", Icons.Rounded.Gif, stringResource(R.string.attach_gif), Color(0xFFAF52DE)
            ) { showGifPicker = true },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "location", Icons.Rounded.LocationOn, stringResource(R.string.attach_location), Color(0xFF30D158)
            ) { showLocationSheet = true },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "contact", Icons.Rounded.Person, stringResource(R.string.attach_contact), Color(0xFFA2845E)
            ) { showContactPicker = true },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "poll", Icons.Rounded.BarChart, stringResource(R.string.attach_poll), Color(0xFF32ADE6)
            ) { showPollSheet = true },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "video_note", Icons.Rounded.Videocam, stringResource(R.string.attach_video_note), Color(0xFF00C7BE)
            ) { showVideoNoteRecorder = true },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "checkin", Icons.Rounded.Shield, stringResource(R.string.attach_checkin), Color(0xFFFF453A)
            ) { showCheckInSheet = true },
            // STEGO-v1: hide an encrypted message for this contact in a PNG, shared out of band.
            com.oshi.messenger.ui.components.attach.AttachAction(
                "disguise", Icons.Rounded.VisibilityOff, stringResource(R.string.disguise_photo_short), Color(0xFF8E8E93)
            ) { showDisguiseSheet = true },
            // No blank-composer gate: the sheet WRITES the message ("je pense à toi").
            com.oshi.messenger.ui.components.attach.AttachAction(
                "schedule", Icons.Rounded.Schedule, stringResource(R.string.attach_schedule), Color(0xFFFF2D55)
            ) { showScheduleSheet = true },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "bitcoin", Icons.Rounded.CurrencyBitcoin, stringResource(R.string.attach_bitcoin), Color(0xFFF7931A)
            ) { cashuDraft = "" },
        )
        com.oshi.messenger.ui.components.attach.AttachmentPanel(
            actions = panelActions,
            onDismiss = { showAttachmentMenu = false },
            onSendMedia = { picked, caption, viewOnce ->
                coroutineScope.launch {
                    isPreparingMedia = true
                    val loaded = com.oshi.messenger.ui.components.attach.RecentMedia.load(context, picked, viewOnce)
                    isPreparingMedia = false
                    if (loaded.tooLarge) {
                        Toast.makeText(context, context.getString(R.string.chat_video_too_large), Toast.LENGTH_SHORT).show()
                    } else if (loaded.failed) {
                        Toast.makeText(context, context.getString(R.string.chat_failed_load_image), Toast.LENGTH_SHORT).show()
                    }
                    if (loaded.media.isNotEmpty()) {
                        pendingMedia = loaded.media
                        sendPendingMedia(loaded.media, caption, true)
                    }
                }
            },
            onBrowseGallery = {
                galleryPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onCamera = { openCamera() },
            showViewOnce = true,
            initialViewOnce = isViewOnceEnabled,
        )
    }

    if (showMediaPreview && pendingMedia.isNotEmpty()) {
        MediaPreviewDialog(
            items = pendingMedia,
            onToggleViewOnce = { index ->
                pendingMedia = ChatMediaSelection.toggleViewOnce(pendingMedia, index)
            },
            onCrop = { index -> cropIndex = index },
            onMarkup = { index -> markupIndex = index },
            onRemove = { index ->
                val next = ChatMediaSelection.removeAt(pendingMedia, index)
                pendingMedia = next
                if (next.isEmpty()) showMediaPreview = false
            },
            onSend = { caption -> sendPendingMedia(pendingMedia, caption, false) },
            onDismiss = {
                pendingMedia = emptyList()
                showMediaPreview = false
            }
        )
    }

    // Slow-send confirmation for media over LoRa. Shown only when the send above
    // stashed an action (opted in, radio connected, off-grid). The estimate is
    // computed from the RAW media size with [LoRaEnvelope.mediaEstimate]; the true
    // .tooLarge refusal still runs on the real wire bytes and remains the
    // authority. Mirrors iOS `LoRaMediaConfirmModifier` / `loRaConfirmMessage`.
    if (pendingLoRaSend != null) {
        val estimate = LoRaEnvelope.mediaEstimate(pendingMedia.sumOf { it.bytes.size })
        OshiAlertDialog(
            onDismissRequest = {
                // Dismiss = cancel: never send, and drop the abandoned pick.
                pendingLoRaSend = null
                pendingMedia = emptyList()
            },
            title = { Text(stringResource(R.string.lora_media_confirm_title)) },
            text = {
                Text(
                    if (estimate.third) {
                        // `fits` caps at MAX_CHUNKS frames, so the airtime is always
                        // seconds here, never minutes — the honest word to show.
                        stringResource(R.string.lora_media_confirm_body, estimate.second)
                    } else {
                        stringResource(R.string.lora_media_confirm_toobig)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = pendingLoRaSend
                    pendingLoRaSend = null
                    send?.invoke()
                }) {
                    Text(stringResource(R.string.lora_media_confirm_send))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingLoRaSend = null
                    pendingMedia = emptyList()
                }) {
                    Text(stringResource(R.string.lora_cancel))
                }
            }
        )
    }

    // AI Writing Assistant Sheet (3 tabs: Improve / Generate / Thinking of you)
    if (showAISheet) {
        com.oshi.messenger.ui.components.AIWritingAssistantSheet(
            initialText = messageText,
            conversationContext = conversationContext,
            contactName = contactDisplayName ?: contact?.alias ?: "",
            onApply = { result ->
                messageText = result
                showAISheet = false
            },
            onDismiss = { showAISheet = false }
        )
    }

    // Set Alias Dialog (6B.12)
    if (showAliasDialog) {
        OshiAlertDialog(
            onDismissRequest = { showAliasDialog = false },
            title = { Text(stringResource(R.string.chat_set_alias_title)) },
            text = {
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = aliasText,
                        onValueChange = { aliasText = it },
                        placeholder = { Text(sharedNickname ?: stringResource(R.string.chat_set_alias_hint)) },
                        // __SHARED_NICKNAME_2026_09_22__ The name the peer chose for
                        // themselves, so the user sees what shows when no alias is set
                        // and can still override it here.
                        supportingText = sharedNickname?.let { nick ->
                            { Text(stringResource(R.string.contact_shared_nickname_hint, nick)) }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (aliasText.isNotBlank()) {
                            viewModel.setContactAlias(aliasText)
                        }
                        showAliasDialog = false
                        aliasText = ""
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAliasDialog = false
                    aliasText = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Reset Session Confirmation Dialog (6B.12)
    if (showResetSessionConfirm) {
        OshiAlertDialog(
            onDismissRequest = { showResetSessionConfirm = false },
            title = { Text(stringResource(R.string.chat_reset_session)) },
            text = { Text(stringResource(R.string.chat_reset_session_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSession()
                        showResetSessionConfirm = false
                        Toast.makeText(context, context.getString(R.string.chat_session_reset_done), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.confirm), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetSessionConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete Conversation Confirmation Dialog (6B.12)
    if (showDeleteConversationConfirm) {
        OshiAlertDialog(
            onDismissRequest = { showDeleteConversationConfirm = false },
            title = { Text(stringResource(R.string.chat_delete_conversation)) },
            text = { Text(stringResource(R.string.chat_delete_conversation_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation()
                        showDeleteConversationConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConversationConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 🪦 Mark-old-identity confirmation
    if (showMarkStaleConfirm) {
        OshiAlertDialog(
            onDismissRequest = { showMarkStaleConfirm = false },
            title = { Text(stringResource(R.string.chat_mark_old_identity_title)) },
            text = { Text(stringResource(R.string.chat_mark_old_identity_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.markContactIdentityStale()
                        showMarkStaleConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.chat_mark_old_identity_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMarkStaleConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // F-13: the user's bubble accent / silhouette / bold-text choice, read once
    // and re-read when the picker writes it. Provided here rather than in
    // MainActivity so the feature is self-contained in this pass; moving the
    // provider up later changes nothing at the bubbles.
    var bubbleAppearance by remember {
        mutableStateOf(
            BubbleAppearance(
                accent = BubbleAppearancePrefs.accent(context),
                silhouette = BubbleAppearancePrefs.silhouette(context),
                boldText = BubbleAppearancePrefs.boldText(context)
            )
        )
    }
    if (showBubbleStyleSheet) {
        BubbleAppearanceSheet(
            onDismiss = { showBubbleStyleSheet = false },
            onChanged = { bubbleAppearance = it }
        )
    }

    // 💓 __ALIVE_CHECK_IN_2026_11_04__ Declared beside the other sheets rather
    // than inside the `Column` above: `ModalBottomSheet` is a popup window, and
    // one declared inside the scrolling body would be clipped by it.
    if (showAliveSheet) {
        val peerLabel = contactDisplayName
            ?: contact?.alias?.takeIf { it.isNotEmpty() }
            ?: publicKey.take(8)
        com.oshi.messenger.ui.components.AliveCheckInSheet(
            peerName = peerLabel,
            entry = aliveEntry,
            onConfirm = {
                viewModel.confirmAlive()
                showAliveSheet = false
                Toast.makeText(
                    context,
                    context.getString(R.string.alive_confirmed_toast),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onEnable = { viewModel.enableAliveCheckIn(it) },
            onDisable = { viewModel.disableAliveCheckIn() },
            onDismiss = { showAliveSheet = false },
        )
    }

    // __ONE_SCHEME_FOR_THE_WHOLE_CHAT_2026_11_07__ (Anatole N8: a band under the
    // header, black in dark and white in light; and text "white on white or
    // black on black".)
    //
    // Both come from ONE fault: this screen was painted from TWO colour schemes.
    // The thread wrapped itself in `WithChatScheme(threadDark)` — the WALLPAPER's
    // tone — while the bar, the Scaffold's own container and everything else kept
    // the APP's scheme. When those disagree (dark app, light-toned wallpaper, or
    // the reverse) two things follow, and Anatole reported both:
    //
    //   - the strip between the frosted bar and the thread is painted by the
    //     Scaffold in the APP scheme while the thread beside it is painted in the
    //     CHAT scheme — a band of the wrong colour, exactly where he circled;
    //   - message ink comes from the chat scheme and some of the ground it lands
    //     on from the app scheme, so the pair can invert: black ink on a dark
    //     ground, white ink on a light one.
    //
    // Measuring the shipped palettes found every bubble/ink pair above 4.5:1, so
    // the constants were never the problem — the DISAGREEMENT was. Resolving the
    // tone once, above the Scaffold, and painting the whole screen from that one
    // scheme removes the band and makes the inversion unrepresentable.
    val chatToneContext = LocalContext.current
    val chatDark = WallpaperToneResolver.resolve(
        currentWallpaper,
        WallpaperTonePrefs.tone(chatToneContext, publicKey),
        isDarkScheme()
    ).contentDark
    // __CHAT_BACKDROP_BLUR_2026_11_21__ live frost of the thread under the bars
    // (API 31+, no battery saver / high contrast / low-RAM device).
    val liveBackdrop = com.oshi.messenger.ui.components.rememberLiveChatBackdrop()
    CompositionLocalProvider(
        LocalBubbleAppearance provides bubbleAppearance,
        com.oshi.messenger.ui.components.LocalAdvancedChatPrivacy provides advPrivacy,
    ) {
    WithChatScheme(dark = chatDark) {
    // __MESHCORE_INTEROP_2026_11_25__ the bubbles' clock and "out of range?" read the radio through this.
    CompositionLocalProvider(LocalMeshCoreBubbles provides MeshCoreBubbleState(meshCorePending, meshCoreChatReady)) {
    Scaffold(
        // Transparent, so the Scaffold cannot paint a strip of its own container
        // colour between the frosted bar and the thread. The ground is painted
        // once, below, from the single scheme resolved above.
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .extendUp(statusBarUnderlap)
            .background(MaterialTheme.colorScheme.surface),
        topBar = {
            // __FROSTED_BARS_2026_11_02__ (N12) The wallpaper runs under the bar,
            // softened, instead of stopping at an opaque band. The backdrop is a
            // SECOND copy of the same wallpaper, blurred — Compose has no
            // backdrop blur, and `Modifier.blur` on the bar itself would blur the
            // title and buttons and leave the background crisp. See
            // `FrostedChatBarBackdrop`, which also explains why this still looks
            // deliberate below API 31, where `Modifier.blur` does nothing at all.
            Box {
            com.oshi.messenger.ui.components.FrostedChatBarBackdrop(
                wallpaper = currentWallpaper,
                scrimColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.matchParentSize(),
                live = liveBackdrop
            )
            Column {
            Spacer(modifier = Modifier.height(statusBarUnderlap))
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
                title = {
                    // PERF: decode profile bitmap off Main — top bar appears with
                    // initials placeholder, then swaps to image when ready.
                    val profilePicBytes = contact?.profilePicture
                    var profileBitmap by remember(profilePicBytes) {
                        mutableStateOf<android.graphics.Bitmap?>(null)
                    }
                    LaunchedEffect(profilePicBytes) {
                        profileBitmap = profilePicBytes?.let { bytes ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // __TAP_THE_FACE_2026_10_27__ The whole title is the target,
                        // not just the 36dp circle: a tap that misses the avatar by
                        // two pixels and does nothing reads as the feature being broken.
                        modifier = Modifier
                            .clickable { showContactCard = true }
                            .padding(vertical = 4.dp)
                    ) {
                        // __PADLOCK_OFF_THE_TOP_BAR_2026_11_13__ (Anatole N5) 42 dp, was
                        // 36: the face and the name are what this bar is for, and the
                        // encryption line that shared their row has moved out.
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val pb = profileBitmap
                            if (pb != null) {
                                Image(
                                    bitmap = pb.asImageBitmap(),
                                    contentDescription = stringResource(R.string.profile_picture),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                // __ONE_AVATAR_NOT_THREE_2026_11_07__ was a flat
                                // `primaryContainer` surface — ONE colour for
                                // every contact in the app, while the
                                // conversation list gave that same person one of
                                // fourteen. Anatole's N12, and the owner's
                                // "someone with no picture shows up blue".
                                com.oshi.messenger.ui.theme.ContactAvatar(
                                    publicKey = publicKey,
                                    name = contactDisplayName ?: contact?.alias,
                                    size = 42.dp,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.padding(bottom = 2.dp)) {
                            // 🛡️ `Contact.isVerified` has been persisted (and compared in
                            // `Contact.equals`) since the safety-number work landed, and
                            // NOTHING in the app ever drew it — a user who had done the
                            // fingerprint comparison got no acknowledgement anywhere.
                            // The badge sits beside the name, and the name keeps its own
                            // single-line ellipsis: `weight(1f, fill = false)` lets the
                            // Text shrink for the badge but never stretch past it, so a
                            // long alias truncates instead of pushing the shield out of
                            // the bar.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = contactDisplayName ?: contact?.alias?.takeIf { it.isNotEmpty() } ?: publicKey.take(8) + "...",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (contact?.isVerified == true) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = stringResource(R.string.encryption_verified),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            // __PADLOCK_OFF_THE_TOP_BAR_2026_11_13__ (Anatole N5) The
                            // "🔒 E2E" line is gone from the bar so the name has the
                            // width: it said the same thing for every conversation,
                            // and it still says it in the notice at the top of the
                            // thread and on the contact card. What stays is what
                            // DIFFERS per thread: the unsigned-radio warning and the
                            // mesh-only state — so the row only exists for those.
                            if (isLoRaInteropThread || isMeshCoreThread || offlineOnly) Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 1.dp)
                            ) {
                                // 🔧 (device walkthrough #2, 2026-08-13) The purple
                                // vector padlock that used to sit here rendered
                                // immediately next to the 🔒 emoji baked into the
                                // `encryption_e2e` string ("🔒 E2E"), so the subtitle
                                // showed TWO padlocks side by side. The string is
                                // already translated in every locale, so the vector
                                // icon is the half that goes.
                                //
                                // A `lora!` thread gets the OPPOSITE claim here: this is
                                // precisely the one subtitle spot a user checks to tell
                                // "my friend" from "a stranger's radio", so it must never
                                // say "🔒 E2E" for a packet nobody signed.
                                // __MESHCORE_INTEROP_2026_11_25__ MeshCore: a fact, in the subtitle's own
                                // quiet ink (it IS encrypted, by MeshCore): red read as "danger".
                                if (isMeshCoreThread) {
                                    MeshCoreThreadSubtitle()
                                } else if (isLoRaInteropThread) {
                                    Text(
                                        text = stringResource(R.string.lora_interop_unverified),
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                // __MESH_ONLY_UX_2026_11_03__ In a mesh-only
                                // conversation, say so here and say whether the
                                // radio path to THIS person is alive.
                                //
                                // Until now the switch was invisible once set: the
                                // menu label flipped, one system line appeared, and
                                // after that a mesh-only thread looked exactly like
                                // a normal one. At a demonstration that is the
                                // difference between "sent" and "sent nowhere", and
                                // the person holding the phone could not tell.
                                //
                                // Icons and a colour, no new strings: the lock says
                                // the relay is off, the antenna goes green when the
                                // person is in radio range. Adding words here would
                                // mean 34 locales and a frozen string count, for
                                // less signal than the colour gives.
                                if (offlineOnly) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = stringResource(R.string.offline_only_summary_on),
                                        modifier = Modifier.size(11.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    val peerOnMesh = publicKey in meshPeerKeys
                                    Icon(
                                        Icons.Default.SettingsInputAntenna,
                                        contentDescription =
                                            if (peerOnMesh) stringResource(R.string.mesh_peers_nearby, 1)
                                            else stringResource(R.string.mesh_status_searching),
                                        modifier = Modifier.size(11.dp),
                                        tint = if (peerOnMesh) androidx.compose.ui.graphics.Color(0xFF34C759)
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    // 🔧 P1 FIX (device walkthrough #2, 2026-08-13): this row carried
                    // FIVE icons (Map, AI, Voice, Video, ⋮) beside the avatar, which
                    // squeezed the title down to five glyphs — "7iMLx…".
                    //
                    // iOS's 1:1 header does not have Map or AI at all, and says so in
                    // as many words at OSHI/ChatView.swift:1741-1743:
                    //
                    //     // Map + AI shortcuts were removed from 1:1 chat —
                    //     // they live in the main tab bar.
                    //
                    // Both are one tap away on the bottom navigation bar, and the AI
                    // button in particular was actively harmful here: it navigated to
                    // the GLOBAL AI tab, highlighting AI in the bottom nav and
                    // abandoning the conversation, so it was never an in-chat
                    // assistant. Dropping the two shortcuts matches iOS's affordance
                    // set and gives the title back ~96 dp.
                    //
                    // Remaining set — Voice, Video, overflow — mirrors iOS, where
                    // voice/video live in the retractable action bar
                    // (ChatView.swift:855-884) and everything else is in the overflow
                    // Menu (ChatView.swift:1757+).
                    // __BLOCKED_BY_PEER_2026_09_24__ this contact told us it blocks us: refuse
                    // locally ("Contact unavailable"), nothing is sent and no call screen opens.
                    val placeCall: (Boolean) -> Unit = { video ->
                        if (com.oshi.messenger.service.BlockedByPeerStore.get(context).isBlockedBy(publicKey)) {
                            Toast.makeText(context, context.getString(R.string.call_error_unavailable), Toast.LENGTH_SHORT).show()
                        } else onCallClick(video)
                    }
                    IconButton(onClick = { placeCall(false) }) {
                        Icon(Icons.Default.Call, contentDescription = stringResource(R.string.call_voice))
                    }
                    IconButton(onClick = { placeCall(true) }) {
                        Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.call_video))
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
                    }
                    // __CHAT_OVERFLOW_IS_A_SHEET_2026_11_02__ the fifteen-item ⋮ menu is a
                    // Material 3 modal bottom sheet, like every other menu this size in the
                    // app. `ModalBottomSheet` is a popup window, so it is declared here next
                    // to its button and still draws over the whole screen.
                    if (showMoreMenu) {
                        ChatOverflowSheet(onDismissRequest = { showMoreMenu = false }) {
                            // __MUTE_A_CONVERSATION_2026_11_13__ (Anatole N8) the same
                            // switch as the conversation list's long-press sheet, so a
                            // thread muted there can be unmuted from inside it.
                            val muteStore = remember(context) {
                                com.oshi.messenger.data.model.ConversationMutes.get(context)
                            }
                            val mutedKeys by muteStore.keys.collectAsState()
                            val isMuted = com.oshi.messenger.data.model.ConversationKeySet
                                .normalize(publicKey) in mutedKeys
                            if (!isLoRaChannel) ChatSheetItem(
                                text = {
                                    Text(stringResource(
                                        if (isMuted) R.string.group_chat_unmute else R.string.group_chat_mute
                                    ))
                                },
                                onClick = {
                                    showMoreMenu = false
                                    muteStore.toggle(publicKey)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isMuted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                        contentDescription = null
                                    )
                                }
                            )
                            // 💓 __ALIVE_CHECK_IN_2026_11_04__ The only way in while
                            // nothing is configured, and the way back to the settings
                            // afterwards.
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.alive_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    showAliveSheet = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = com.oshi.messenger.ui.components.AliveCheckInColors.confirmed,
                                    )
                                }
                            )
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.chat_schedule_call)) },
                                onClick = {
                                    showMoreMenu = false
                                    showScheduleCallSheet = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.EditCalendar, contentDescription = null)
                                }
                            )
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.wallpaper_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    showWallpaperPicker = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Wallpaper, contentDescription = null)
                                }
                            )
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.bubble_silhouette_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    showBubbleStyleSheet = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null)
                                }
                            )
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.search)) },
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.toggleSearch()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                }
                            )
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.chat_clear_chat)) },
                                onClick = {
                                    showMoreMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                            )
                            // Per-chat media gallery (P2 #20)
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.media_gallery_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    showMediaGallery = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                }
                            )
                            ChatSheetDivider()
                            // 🔐 Safety number — the verification screen had no entry
                            // point anywhere in the app, so `SafetyNumberManager` was a
                            // backend nobody could reach. Route: "safetyNumber/{theirPublicKey}".
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.encryption_safety_number)) },
                                onClick = {
                                    showMoreMenu = false
                                    onOpenSafetyNumber(publicKey)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null)
                                }
                            )
                            // Set Alias (6B.12)
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.chat_set_alias)) },
                                onClick = {
                                    showMoreMenu = false
                                    // __SHARED_NICKNAME_2026_09_22__ Only an alias the user
                                    // TYPED pre-fills the field. The stored placeholder
                                    // ("AbCd1234...") used to be offered, and one tap on Save
                                    // froze it as a real alias that then beat the peer's
                                    // shared nickname forever.
                                    aliasText = contact?.let {
                                        com.oshi.messenger.data.model.PeerNickname.userAlias(it.alias, it.publicKey)
                                    } ?: ""
                                    showAliasDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Badge, contentDescription = null)
                                }
                            )
                            // Copy contact's public key (for paste into group invites etc.)
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.chat_copy_public_key)) },
                                onClick = {
                                    showMoreMenu = false
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.clipboard_label_oshi_public_key), publicKey))
                                    Toast.makeText(context, context.getString(R.string.chat_public_key_copied), Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                }
                            )
                            // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Take THIS
                            // conversation off the relay. A toggle, not a
                            // one-way action: a conversation left restricted
                            // after someone meant to lift it would strand
                            // messages with no explanation. Mirrors the entry in
                            // `ChatView.swift`'s ellipsis menu.
                            ChatSheetItem(
                                text = {
                                    Text(stringResource(
                                        if (offlineOnly) R.string.offline_only_menu_off
                                        else R.string.offline_only_menu_on
                                    ))
                                },
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.setOfflineOnly(!offlineOnly)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (offlineOnly) Icons.Default.LockOpen else Icons.Default.Lock,
                                        contentDescription = null
                                    )
                                }
                            )
                            // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ A toggle with a subtitle
                            // saying what it does, for both sides of the conversation.
                            com.oshi.messenger.ui.components.AdvancedChatPrivacyToggle(
                                on = advPrivacy,
                                onToggle = { on ->
                                    showMoreMenu = false
                                    viewModel.setAdvancedChatPrivacy(on)
                                },
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                            )
                            // Reset Session (6B.12)
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.chat_reset_session)) },
                                onClick = {
                                    showMoreMenu = false
                                    showResetSessionConfirm = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                            )
                            // Report. The entry point that made ReportManager and the
                            // Settings -> Privacy -> Reported Items screen reachable at
                            // all: both were built and tested, and nothing opened them.
                            // iOS pairs report with block on the same sheet
                            // (ContactActionsView.swift:253+), which is also the pair a
                            // user-generated-content app is graded on at store review.
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.chat_report_contact)) },
                                onClick = {
                                    showMoreMenu = false
                                    showReportDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Flag, contentDescription = null, tint = OSHIOrange)
                                }
                            )
                            // Block/Unblock Contact (6B.12)
                            ChatSheetItem(
                                text = {
                                    Text(
                                        if (isContactBlocked) stringResource(R.string.chat_unblock_contact)
                                        else stringResource(R.string.chat_block_contact)
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.toggleBlockContact()
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isContactBlocked) Icons.Default.CheckCircle else Icons.Default.Block,
                                        contentDescription = null,
                                        tint = if (isContactBlocked) OSHIGreen else OSHIRed
                                    )
                                }
                            )
                            // 🪦 Mark old identity — see StaleIdentityStore.
                            // Different from Block: stale = "this key isn't
                            // this person anymore" (rotation), block = "I
                            // don't want this person at all".
                            ChatSheetItem(
                                text = { Text(stringResource(R.string.chat_mark_old_identity)) },
                                onClick = {
                                    showMoreMenu = false
                                    showMarkStaleConfirm = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            )
                            // Delete Conversation (6B.12)
                            ChatSheetItem(
                                text = {
                                    Text(
                                        stringResource(R.string.chat_delete_conversation),
                                        color = OSHIRed
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showDeleteConversationConfirm = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                                },
                                destructive = true
                            )
                        }
                    }
                }
            )
            // __CHAT_BACKDROP_BLUR_2026_11_21__ The banners that sat between the bar and
            // the thread (check-in, search, pinned message, live location, version
            // notices) now belong to the frosted top band: the thread scrolls under
            // the bar AND them, which is what lets the messages show through.
            // 💓 __ALIVE_CHECK_IN_2026_11_04__ The liveness strip, above the search
            // bar and the pinned-message banner, and present ONLY once a check-in
            // exists in one direction or the other. The way IN when there is none
            // is the ⋯ sheet below: a bar in every conversation, for a feature most
            // of a user's conversations will never use, is the clutter the design
            // brief rules out.
            if (!aliveEntry.isIdle) {
                val peerLabel = contactDisplayName
                    ?: contact?.alias?.takeIf { it.isNotEmpty() }
                    ?: publicKey.take(8)
                com.oshi.messenger.ui.components.AliveCheckInBar(
                    peerName = peerLabel,
                    entry = aliveEntry,
                    onConfirm = {
                        viewModel.confirmAlive()
                        Toast.makeText(
                            context,
                            context.getString(R.string.alive_confirmed_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onOpenSettings = { showAliveSheet = true },
                )
            }

            // 🔍 Search Bar (toggleable)
            AnimatedVisibility(
                visible = isSearchActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.search)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // "X of Y" counter
                                        if (searchMatches.isNotEmpty()) {
                                            Text(
                                                text = "${searchMatchIndex + 1} of ${searchMatches.size}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else if (searchQuery.isNotBlank()) {
                                            Text(
                                                text = stringResource(R.string.search_no_results),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = stringResource(R.string.cancel)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        // Up/down navigation arrows (scrolling handled by LaunchedEffect)
                        if (searchMatches.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.previousSearchMatch() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.search_previous),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { viewModel.nextSearchMatch() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.search_next),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.closeSearch() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    }
                }
            }

            // Pinned Message Banner (6A.6)
            AnimatedVisibility(
                visible = pinnedMessage != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                pinnedMessage?.let { pinMsg ->
                    // 🔴 BRAND INK ON A BRAND WASH, instance #4 of the F-14 class.
                    // Three brand inks and a brand accent bar sat on an 8% brand
                    // fill: over the dark scheme that composites to luminance
                    // 0.0119, and OSHIPurple's own is 0.086 — 2.20:1, on text.
                    // `brandInk()` keeps the hue and moves the luminance (iOS
                    // BubbleStyleManager.swift:339-344), giving 7.31:1.
                    val pinInk = brandInk()
                    Surface(
                        color = OSHIPurple.copy(alpha = 0.08f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Scroll to pinned message — rendered position, not
                                // the index in `messages` (F-1).
                                val targetIndex = ChatThreadItems.positionOf(threadItems, pinMsg.id)
                                if (targetIndex >= 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(targetIndex)
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Purple accent bar
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(pinInk)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                tint = pinInk,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.chat_pinned_message),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = pinInk
                                )
                                // H-6. `MessageSanitizer` is the retired fourth
                                // copy of the catalog: pinning a call summary
                                // printed its hardcoded English literal "📞 Call"
                                // in all 34 locales, and pinning a reply printed the
                                // raw `💬REPLY💬{…}` envelope (the sanitiser tests
                                // the dead "↩️REPLY↩️" spelling and has no
                                // `📢GROUP_UPDATE📢` branch at all).
                                val pinPreview =
                                    OSHIControlPayload.previewText(pinMsg.content, controlLabels).take(60)
                                LinkifyText(
                                    text = pinPreview,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                // 0.5f was on top of the 2.20:1 above — the
                                // chevron was the least visible thing on screen.
                                tint = pinInk.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                            // 📌 Unpin, straight off the banner. Without it the only
                            // way out of a pin was to find the pinned bubble again and
                            // long-press it — and a pin that arrived from an iPhone
                            // could be several hundred rows up. Mirrors the group
                            // banner (`GroupChatScreen.kt:999`), same string.
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { viewModel.togglePin(pinMsg.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.accessibility_unpin),
                                    tint = pinInk,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Profile Links Pinned Bar (iOS interop)
            ProfileLinksPinnedBar(
                profileLinksManager = viewModel.profileLinksManager,
                conversationKey = publicKey
            )

            // Live Location Banner (if sharing)
            LiveLocationBanner(
                locationManager = locationManager,
                conversationId = publicKey,
                // The `stopped` message goes out from the manager's onStop hook, which
                // is also what natural expiry fires; sending it here too would double it
                // — and would send it into THIS conversation even when the share belongs
                // to another one.
                onStop = { locationManager.stopLiveSharingForConversation(publicKey) }
            )

            // 🔴 P0 (2026-08-24 feature check): the in-app off-ramp for an armed
            // check-in — "I've arrived" / "+15 min" / "Stop", plus the "Are you
            // okay?" dialog when the ETA passes.
            //
            // `CheckInActiveBanner` had existed, fully written, with ZERO call
            // sites, and `CheckInManager.showWarningAlert` was published with no
            // collector. So a user who armed a check-in had no way to end it and
            // every overdue session ran to `triggerEmergency()`, sending their
            // position and battery level to this very contact.
            //
            // Placed exactly where iOS puts its banner — above the thread, under the
            // live-location banner (`OSHI/CheckInView.swift:536`) — and reading the
            // process-wide singleton, not this screen's ViewModel, so it is the same
            // session the notification actions act on.
            CheckInOffRamp(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            // 📦 Version notices: "this contact is on an older build" and, when the
            // server offers a floor, "an update is available". Both are inline and
            // dismissible; neither is a dialog or a sheet, so predictive back has
            // nothing to swallow, and neither gates the composer below.
            VersionNotices(
                peerPublicKey = publicKey,
                peerDisplayName = contactDisplayName
                    ?: contact?.alias?.takeIf { it.isNotEmpty() }
                    ?: publicKey.take(8),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            }
            }
        },
        bottomBar = {
            // __CHAT_BACKDROP_BLUR_2026_11_21__ Everything under the thread — progress,
            // reply / edit banners, notices, the composer — is one frosted band the
            // thread scrolls under. The insets are taken HERE, once (the composer's
            // own `navigationBarsPadding().imePadding()` find them consumed).
            Box {
            com.oshi.messenger.ui.components.FrostedChatBarBackdrop(
                wallpaper = currentWallpaper,
                scrimColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.matchParentSize(),
                edgeOnTop = true,
                live = liveBackdrop
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
            // Upload/compression progress indicator.
            //
            // __PICKER_LATENCY_2026_09_18__ It now also covers the step BEFORE
            // compression — reading the picked file out of the content provider —
            // which for a video is the slow one and used to show nothing at all.
            // That read reports no progress, so while it runs both indicators are
            // INDETERMINATE: a determinate bar frozen at 0% says "stuck", which is
            // the exact impression this is here to remove.
            AnimatedVisibility(visible = isMediaProcessing || isPreparingMedia) {
                val indeterminate = isPreparingMedia && !isMediaProcessing
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (indeterminate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = brandInk()
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = compressionProgress.toFloat(),
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = brandInk()
                            )
                        }
                        Text(
                            text = stringResource(
                                if (indeterminate) R.string.chat_preparing_media else R.string.loading
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val barModifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                    if (indeterminate) {
                        LinearProgressIndicator(modifier = barModifier, color = brandInk())
                    } else {
                        LinearProgressIndicator(
                            progress = compressionProgress.toFloat(),
                            modifier = barModifier,
                            color = brandInk()
                        )
                    }
                }
            }

            // Reply Preview Banner (above input, below messages)
            AnimatedVisibility(
                visible = replyingToMessage != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                replyingToMessage?.let { replyMsg ->
                    ReplyPreviewBanner(
                        message = replyMsg,
                        contact = contact,
                        messageActionsManager = messageActionsManager,
                        onDismiss = { viewModel.clearReplyingTo() }
                    )
                }
            }

            // Editing message banner (shown above input when editing)
            AnimatedVisibility(
                visible = editingMessage != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                editingMessage?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.message_editing),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                // __SHOW_WHAT_IS_BEING_EDITED_2026_11_05__ The
                                // BEFORE text, and only once the draft has
                                // actually diverged from it. Printing it while
                                // they match showed the same sentence twice.
                                //
                                // Two lines rather than 50 characters: the case
                                // this banner exists for is a long message, and
                                // 50 characters of a long message is not enough
                                // to check a change against.
                                //
                                // H-6. Routed through the control-payload
                                // catalog so prose comes back unchanged while a
                                // sentinel never shows raw.
                                if (messageText != editingOriginal && editingOriginal.isNotEmpty()) {
                                    Text(
                                        text = com.oshi.messenger.util.MessageFormatting.strip(OSHIControlPayload
                                            .previewText(editingOriginal, controlLabels)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    editingMessage = null
                                    messageText = ""
                                    editingOriginal = ""
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Blocked contact banner (shown above input area, replaces input when blocked)
            if (isContactBlocked) {
                Surface(
                    color = OSHIRed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.chat_contact_blocked),
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                        TextButton(
                            onClick = { viewModel.toggleBlockContact() }
                        ) {
                            Text(
                                text = stringResource(R.string.chat_unblock),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Welcome-thread notice (replaces the input, like the blocked banner).
            // Without this the user can type into a thread whose recipient is not a
            // key; the send throws and every bubble goes "Not delivered". That is
            // what Play's reviewer saw on 2026-09-16.
            if (isSystemThread) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.chat_system_thread_readonly),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Typing indicator (matching iOS)
            if (peerIsTyping) {
                val typingInk = brandInk()
                // The translated string is "%1$s is typing…" — the three animated dots
                // ARE the ellipsis, so the static one is trimmed off rather than shown
                // twice. Only trailing ellipsis/period/space glyphs are removed, so a
                // locale that ends its sentence differently keeps its own punctuation.
                val typingLabel = stringResource(
                    R.string.chat_is_typing,
                    contactDisplayName ?: contact?.alias ?: stringResource(R.string.chat_peer)
                ).trimEnd('…', '.', '。', ' ')
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = typingLabel,
                        fontSize = 12.sp,
                        color = typingInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    TypingBouncingDots(tint = typingInk)
                }
            }

            // __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__ Who actually answers.
            // Sits ABOVE a WORKING composer — the point is that this message can
            // be replied to, by a person. The greeting is written by the app but
            // goes to the author's real account, and saying so plainly is a
            // better answer to that than an unreachable sender was.
            if (isWelcomeThread && !isContactBlocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.chat_welcome_creator_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 📡 Reminder right above the box the user is about to type into —
            // sending here is allowed (see ChatViewModel.sendMessage's
            // `lora!` branch), but it is NOT the same thing as texting an
            // OSHI contact, and the compose box is the last place to say so
            // before the message is gone.
            if (meshCoreNote != null && !isContactBlocked) {
                // __MESHCORE_INTEROP_2026_11_25__ One calm line (or "radio not connected · Connect"), with the
                // counter for one radio message (133 characters) once the text nears it.
                val count = remember(messageText, isMeshCoreChannel, meshCoreSelfName) {
                    MeshCoreUi.composerCount(messageText, isMeshCoreChannel, meshCoreSelfName)
                }
                MeshCoreComposerNote(
                    note = meshCoreNote,
                    count = count,
                    radioConnected = meshCoreChatReady,
                    onConnect = onOpenRadioSettings,
                )
            } else if (isLoRaInteropThread && !isContactBlocked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.lora_interop_unverified_detail),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Message input or Recording UI (hidden when the contact is blocked,
            // and for a LEGACY un-migrated welcome row, which has no real recipient)
            if (!isContactBlocked && !isSystemThread) {
            // __FROSTED_BARS_2026_11_02__ (N12) the composer, same treatment as
            // the top bar: the wallpaper continues under it, softened.
            // __CHAT_BACKDROP_BLUR_2026_11_21__ the frosted backdrop now spans the
            // whole bottom bar (reply / edit banners included), see `bottomBar`.
            Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()  // Move input up when keyboard shows
            ) {
                AnimatedContent(
                    targetState = isRecording,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "input_mode"
                ) { recording ->
                    if (recording) {
                        // Recording UI
                        VoiceRecordingBar(
                            duration = recordingDuration,
                            amplitudes = waveformAmplitudes,
                            cancelOffset = recordingCancelOffset,
                            selectedEffect = selectedEffect,
                            availableEffects = audioRecorder.getAvailableEffects(),
                            onEffectSelected = { effect -> audioRecorder.setVoiceEffect(effect) },
                            onCancel = {
                                audioRecorder.cancelRecording()
                                isRecording = false
                                recordingCancelOffset = 0f
                            },
                            onSend = {
                                // __VOICE_PREVIEW_2026_09_29__ Stop → preview sheet (hear it,
                                // change the voice, view once) → send. Like iOS.
                                val clip = audioRecorder.stopRecordingForPreview()
                                isRecording = false
                                recordingCancelOffset = 0f
                                pendingVoice = clip
                            },
                            onDrag = { offset ->
                                recordingCancelOffset = offset
                                if (offset < -150f) {
                                    audioRecorder.cancelRecording()
                                    isRecording = false
                                    recordingCancelOffset = 0f
                                }
                            }
                        )
                    } else {
                        Column {
                            // __KEYBOARD_PRIVACY_2026_10_01__ Third-party keyboard warning: it can read
                            // what is typed here before OSHI encrypts it. Renders nothing when trusted.
                            com.oshi.messenger.ui.keyboard.ThirdPartyKeyboardBanner()
                            // 🛡 __STRICT_MODE_2026_11_22__ strict mode on + an app that can read/drive the screen.
                            StrictModeThreatBanner()
                            // Smart Replies Bar (shown when text field is empty and replies available)
                            if (messageText.isEmpty() && !advPrivacy) {
                                SmartRepliesBar(
                                    replies = nlpState.smartReplies,
                                    onReplySelected = { reply ->
                                        messageText = reply
                                        nlpViewModel.clearSmartReplies()
                                    }
                                )
                            }
                            // AI Quick Bar
                            AIQuickBar(
                                messageText = messageText,
                                isProcessing = aiProcessingAction != null && nlpState.isLoading,
                                onCorrect = {
                                    aiProcessingAction = NLPViewModel.AIAction.CORRECT
                                    nlpViewModel.setOriginalText(messageText)
                                    nlpViewModel.selectAction(NLPViewModel.AIAction.CORRECT)
                                    nlpViewModel.processCurrentAction()
                                },
                                onImprove = {
                                    aiProcessingAction = NLPViewModel.AIAction.IMPROVE
                                    nlpViewModel.setOriginalText(messageText)
                                    nlpViewModel.selectAction(NLPViewModel.AIAction.IMPROVE)
                                    nlpViewModel.selectStyle(com.oshi.messenger.service.NLPManager.ImprovementStyle.CASUAL)
                                    nlpViewModel.processCurrentAction()
                                },
                                onOpenFullAI = { showAISheet = true }
                            )
                            // __CASHU_2026_10_25__ The `/` menu, above the field as
                            // iOS draws it. It is what makes `/pay` findable at all:
                            // before this the only way to know the command existed
                            // was to be told. Draws nothing unless the message
                            // starts with `/`.
                            SlashCommandBar(
                                text = messageText,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) { completion -> messageText = completion }
                            // Normal message input
                            Row(
                                modifier = Modifier
                                    // __COMPOSER_HEIGHT_2026_11_07__ (Anatole N10: a
                                    // shorter, more compact bar.) 8dp all round becomes
                                    // 8 horizontal / 2 vertical: 12dp of height handed
                                    // back to the thread, horizontal rhythm untouched.
                                    //
                                    // It stops there on purpose. The field keeps
                                    // Material's minimum height because that minimum IS
                                    // the touch target — shaving it would buy a few
                                    // pixels of thread by making the control harder to
                                    // hit, a bad trade on a phone held one-handed.
                                    //
                                    // The blur he asked for is already here: the
                                    // composer sits on `FrostedChatBarBackdrop` (N12),
                                    // and the text field keeps its own opaque container,
                                    // so the blur stays BEHIND it — "sauf dans la case
                                    // de texte", which is exactly what he asked for.
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            // __VIDEO_NOTE_2026_09_24__ While typing, the "+" and the
                            // video-note shortcut step aside (spring shrink + fade) and the
                            // field grows into the row and up to 6 lines; they spring back
                            // when the field is cleared. `ComposerShortcuts` is the rule.
                            val shortcutsVisible = com.oshi.messenger.service.media.ComposerShortcuts
                                .visible(messageText, isEditing = editingMessage != null)
                            val composerGlass = com.oshi.messenger.ui.theme.glassOn()

                            // __COMPOSER_PILL_2026_11_23__ "+", the field and the camera share ONE
                            // pill (Google-app style); mic / send is the round button beside it.
                            com.oshi.messenger.ui.components.ComposerPill(
                                glass = composerGlass,
                                modifier = Modifier.weight(1f).animateContentSize()
                            ) {
                            // Single "+" entry point — opens attachment menu (iOS parity).
                            // Location, schedule, check-in, contact, document, gallery all live there
                            // so the text field gets the full available width.
                            ComposerShortcut(visible = shortcutsVisible) {
                                IconButton(onClick = { showAttachmentMenu = true }) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.chat_attach),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (!shortcutsVisible) Spacer(modifier = Modifier.width(10.dp))

                            // __TEXT_FORMATTING_2026_11_24__ Bold / Underline in the selection menu.
                            com.oshi.messenger.ui.components.FormattingComposerField(
                                text = messageText,
                                onTextChange = {
                                    messageText = it
                                    viewModel.onTextChanged(it)
                                },
                            ) { fieldValue, onFieldValueChange ->
                            OutlinedTextField(
                                value = fieldValue,
                                onValueChange = onFieldValueChange,
                                placeholder = { Text(stringResource(R.string.message_placeholder)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(textFieldFocusRequester),
                                shape = com.oshi.messenger.ui.components.ComposerPillDefaults.Shape,
                                // The pill carries fill, outline and glass; the field is bare.
                                colors = com.oshi.messenger.ui.components.composerPillFieldColors(),
                                maxLines = com.oshi.messenger.service.media.ComposerShortcuts
                                    .maxLines(messageText, isEditing = editingMessage != null)
                            )
                            }

                            // (N17, Anatole 13/11) Take a PHOTO — where nearly every
                            // messenger puts the camera, beside the mic. This slot used to
                            // open the round video-note recorder, which now lives in the
                            // attachment sheet. Same permission path as the sheet's own
                            // "Take photo" row. Visible only while the field is empty.
                            ComposerShortcut(visible = shortcutsVisible) {
                                IconButton(onClick = {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                                            context, android.Manifest.permission.CAMERA
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    ) {
                                        launchPhotoCapture()
                                    } else {
                                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.PhotoCamera,
                                        contentDescription = stringResource(R.string.profile_take_photo),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            } // __COMPOSER_PILL_2026_11_23__ end pill

                            Spacer(modifier = Modifier.width(8.dp))

                            // Send or Mic button
                            if (messageText.isNotBlank()) {
                                // __OLED_GLASS_2026_11_16__ the one lit thing in the bar.
                                com.oshi.messenger.ui.components.ComposerRoundButton(
                                    primary = true,
                                    glass = composerGlass,
                                    onClick = {
                                        val editing = editingMessage
                                        if (editing != null) {
                                            // Edit mode: update existing message
                                            viewModel.editMessage(editing.id, messageText)
                                            editingMessage = null
                                        } else if (CashuPayCommand.handles(messageText)) {
                                            // __CASHU_2026_10_24__ `/pay <token>`.
                                            // Nothing that fails to decode goes on
                                            // the wire: a typo in a pasted token is
                                            // money thrown away, and neither side
                                            // would ever learn why.
                                            // __CASHU_SEND_READY_2026_11_09__ A decoded
                                            // token opens the send sheet, which reads
                                            // the amount and mint back BEFORE it leaves
                                            // (as the guide promises); `/pay` alone
                                            // opens it empty. A refusal KEEPS the text —
                                            // it used to be wiped below with the token in it.
                                            when (val r = CashuPayCommand.parse(messageText, isGroup = false)) {
                                                is CashuPayCommand.Outcome.Send -> {
                                                    cashuDraft = r.token.raw
                                                    messageText = ""
                                                }
                                                CashuPayCommand.Outcome.Usage -> {
                                                    cashuDraft = ""
                                                    messageText = ""
                                                }
                                                CashuPayCommand.Outcome.NotAToken ->
                                                    cashuToast(context, context.getString(R.string.cashu_pay_not_token))
                                                CashuPayCommand.Outcome.Invalid ->
                                                    cashuToast(context, context.getString(R.string.cashu_pay_invalid))
                                                // A 1:1 thread never asks for the
                                                // `public` word; this branch cannot
                                                // be reached with isGroup = false.
                                                is CashuPayCommand.Outcome.NeedsPublicConfirm -> Unit
                                            }
                                            return@ComposerRoundButton
                                        } else if (isMeshCoreKey &&
                                            MeshCoreUi.composerCount(messageText, isMeshCoreChannel, meshCoreSelfName).tooLong
                                        ) {
                                            // __MESHCORE_INTEROP_2026_11_25__ more than 20 radio messages: kept in the
                                            // field (the counter already says "too long"), nothing sent.
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(R.string.meshcore_too_long, com.oshi.messenger.service.lora.MeshCoreText.MAX_PARTS),
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                            return@ComposerRoundButton
                                        } else {
                                            // Normal send
                                            viewModel.sendMessage(messageText)
                                        }
                                        messageText = ""
                                    }
                                ) {
                                    Icon(
                                        if (editingMessage != null) Icons.Default.Check else Icons.Default.Send,
                                        contentDescription = stringResource(R.string.chat_send)
                                    )
                                }
                            } else {
                                com.oshi.messenger.ui.components.ComposerRoundButton(
                                    primary = false,
                                    glass = composerGlass,
                                    onClick = {
                                        // Recording hint overlay (6B.14) - show once per install
                                        if (!recordingHintShown && !showRecordingHint) {
                                            showRecordingHint = true
                                            return@ComposerRoundButton
                                        }
                                        if (audioRecorder.hasRecordPermission()) {
                                            isRecording = audioRecorder.startRecording()
                                        } else {
                                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.chat_record_voice))
                                }
                            }
                        }
                        }
                    }
                }
            }
            } // __FROSTED_BARS_2026_11_02__ end composer frosted Box
            } // end if (!isContactBlocked && !isSystemThread)
            }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Messages list with wallpaper background + pull-to-refresh (6B.13)
            val wallpaper = currentWallpaper
            val pullRefreshState = rememberPullRefreshState(
                refreshing = isRefreshing,
                onRefresh = { viewModel.refreshMessages() }
            )
            // __WALLPAPER_TONE_2026_09_24__ The thread renders in the wallpaper's
            // tone: a white mesh kept in dark mode carries light-mode bubbles,
            // text and timestamps (the palette tuned for that ground).
            val toneContext = LocalContext.current
            val threadDark = WallpaperToneResolver.resolve(
                wallpaper,
                WallpaperTonePrefs.tone(toneContext, publicKey),
                isDarkScheme()
            ).contentDark
            // __CHAT_BACKDROP_BLUR_2026_11_21__ The thread fills the WHOLE screen,
            // under both bars; `padding` (the bars' heights) moves into the list's
            // content padding and the overlays, and the bars frost what passes
            // under them. The band heights are read at draw time.
            val density = LocalDensity.current
            val barTopPx = with(density) { padding.calculateTopPadding().toPx() }
            val barBottomPx = with(density) { padding.calculateBottomPadding().toPx() }
            val imeInsets = WindowInsets.ime
            val navInsets = WindowInsets.navigationBars
            val layoutDir = LocalLayoutDirection.current
            WithChatScheme(dark = threadDark) {
                Box(
                    modifier = Modifier
                        .frostedBackdropSource(
                            enabled = liveBackdrop,
                            topPx = { barTopPx },
                            bottomPx = { barBottomPx },
                            // The keyboard covers this part of the bottom band.
                            bottomCoveredPx = {
                                (imeInsets.getBottom(density) - navInsets.getBottom(density)).toFloat().coerceAtLeast(0f)
                            }
                        )
                        .fillMaxSize()
                        .pullRefresh(pullRefreshState)
                        .then(
                            when (wallpaper) {
                                is ChatWallpaper.Default -> Modifier.background(Color(0xFFE8E0D5))
                                is ChatWallpaper.SolidColor -> Modifier.background(wallpaper.color)
                                is ChatWallpaper.Gradient -> Modifier.background(
                                    Brush.linearGradient(wallpaper.colors)
                                )
                                is ChatWallpaper.EmojiPattern -> Modifier.background(wallpaper.backgroundColor)
                                // Drawn as a Canvas child below, not as a background
                                // brush — same as the image case.
                                // __THE_THREAD_HAD_NO_FLOOR_2026_11_07__ (Anatole N8:
                                // "between a person's profile and the messages there is a
                                // black space that does not exist in groups".)
                                //
                                // These two were a bare `Modifier` — NO background at all.
                                // Both draw their backdrop as a CHILD of this Box, and
                                // neither child is guaranteed to be there:
                                //
                                //   * the image wallpaper is decoded off the main thread,
                                //     so `wallpaperBitmap` is null for the first frames;
                                //   * and its decode is wrapped in `catch { null }`, so a
                                //     corrupt or unreadable wallpaper means it is null FOR
                                //     EVER — the thread then never gets painted at all and
                                //     shows whatever is behind it, which on the dark scheme
                                //     is near-black.
                                //
                                // Groups do not have wallpapers, which is exactly why the
                                // band appears in 1:1 and nowhere else — the asymmetry in
                                // the report is the clue to the cause.
                                //
                                // A floor underneath costs nothing: the dynamic backdrop
                                // and the image both paint over it when they arrive.
                                is ChatWallpaper.OshiDynamic ->
                                    Modifier.background(MaterialTheme.colorScheme.surface)
                                is ChatWallpaper.ImageWallpaper ->
                                    Modifier.background(MaterialTheme.colorScheme.surface)
                            }
                        )
                ) {
                    // 🌌 Animated OSHI backdrop. It sits BEHIND the thread and draws
                    // itself; bubbles need no change because they are already opaque
                    // for exactly this reason (`ui/theme/Tokens.kt:150-165`).
                    // __WALLPAPER_PERSIST_2026_11_21__ the emoji of a pattern wallpaper
                    // (only its background colour was ever drawn).
                    if (wallpaper is ChatWallpaper.EmojiPattern) {
                        Box(Modifier.fillMaxSize().emojiPattern(wallpaper.emoji))
                    }
                    if (wallpaper is ChatWallpaper.OshiDynamic) {
                        OshiDynamicWallpaperView(
                            style = OshiDynamicStyle.fromId(wallpaper.styleId)
                                ?: OshiDynamicStyle.DEFAULT,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Image wallpaper background — decode off Main to avoid stalling
                    // the chat screen open when wallpapers are large.
                    if (wallpaper is ChatWallpaper.ImageWallpaper) {
                        val wallpaperBytes = wallpaper.imageData
                        var wallpaperBitmap by remember(wallpaperBytes) {
                            mutableStateOf<android.graphics.Bitmap?>(null)
                        }
                        LaunchedEffect(wallpaperBytes) {
                            wallpaperBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                try { BitmapFactory.decodeByteArray(wallpaperBytes, 0, wallpaperBytes.size) } catch (_: Exception) { null }
                            }
                        }
                        wallpaperBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    // __OLED_GLASS_2026_11_16__ OLED black: the user's wallpaper,
                    // kept, but dimmed so its average stays near-black.
                    com.oshi.messenger.ui.components.OledWallpaperDimLayer(wallpaper, Modifier.matchParentSize())

                    // 🔧 P1 FIX (device walkthrough #2, 2026-08-13): the 1:1 chat had NO
                    // empty state — ~1400 px of flat beige on a fresh conversation —
                    // while the GROUP chat has one (GroupChatScreen.kt:855-886), so the
                    // app disagreed with itself. Ported from iOS
                    // `ChatView.chatEmptyState` (ChatView.swift:1494-1521), called at
                    // ChatView.swift:1482 with the same `messages.isEmpty` condition:
                    // a brand-tinted `lock.fill` in a 12%-opacity circle, then the
                    // encryption reassurance title and subtitle. Strings are the iOS
                    // values verbatim (en.lproj/Localizable.strings:4562-4563).
                    //
                    // Drawn INSIDE the wallpaper Box and BEFORE the LazyColumn so any
                    // custom chat wallpaper still shows behind it, and
                    // `allowsHitTesting(false)` on iOS maps to no clickable modifier
                    // here — it must never eat a tap meant for the composer.
                    if (threadLoaded && messages.isEmpty()) {
                        // The 80 dp bottom bias lifts the block off the composer on a
                        // phone held upright. In landscape the thread area is only
                        // ~250 dp tall, so reserving 80 dp of it left less room than
                        // the block needs and the subtitle was sliced through the
                        // middle of its glyphs — visible on the very first screen of
                        // every new conversation.
                        val emptyStateBottomBias =
                            if (rememberWindowSize().height == HeightClass.Compact) 8.dp else 80.dp
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(horizontal = 24.dp)
                                .padding(bottom = emptyStateBottomBias),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(OSHIPurple.copy(alpha = 0.22f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    // F-14 (iOS e790c77): the brand colour is a SURFACE
                                    // TINT, not an ink, once the surface already carries
                                    // it. A 12% brand fill over near-black stays near
                                    // black and brand ink dissolves in it.
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.chat_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.chat_empty_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // (N11) CompositionLocalProvider wraps ONLY this LazyColumn — every
                    // Text inside the messages list (LinkifyText's ClickableText reads
                    // `LocalTextStyle.current`, see LinkPreview.kt:530) picks up the
                    // scaled size, and nothing outside this screen's composition ever
                    // sees a different LocalTextStyle than before.
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalTextStyle provides LocalTextStyle.current.copy(
                            fontSize = LocalTextStyle.current.fontSize * chatTextScale
                        )
                    ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            // __OPEN_AT_THE_FIRST_UNREAD__ rows can be composed a beat
                            // before the landing is decided (the unread capture is a DB
                            // read); drawing them would flash the newest messages and then
                            // jump to the unread line. Invisible for that beat only.
                            .graphicsLayer {
                                alpha = if (hasLandedAtBottom || (threadLoaded && messages.isEmpty()) || showListAnyway) 1f else 0f
                            }
                            // Two-finger pinch only — `changes.size >= 2` is the gate that
                            // keeps this from ever seeing (let alone consuming) the single-
                            // finger drag LazyColumn's own scrolling already owns.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.size >= 2) {
                                            val zoom = event.calculateZoom()
                                            if (zoom != 1f) {
                                                chatTextScale = (chatTextScale * zoom).coerceIn(0.75f, 2.5f)
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(
                            start = 16.dp + padding.calculateStartPadding(layoutDir),
                            end = 16.dp + padding.calculateEndPadding(layoutDir),
                            top = 8.dp + padding.calculateTopPadding(),
                            bottom = 8.dp + padding.calculateBottomPadding()
                        )
                    ) {
                        // Rows come from `threadItems` (ChatThreadItems.build), which is
                        // also what every scroll target is resolved against — the whole
                        // point of F-1. The silent-payload filter and the date-header
                        // interleaving live there; nothing decides row membership here.
                        // __CHAT_OPEN_PERF_2026_11_14__ ONE `items` interval with stable
                        // keys and content types. `threadItems.forEach { item(...) }`
                        // registered every row of the conversation (3 000+ intervals)
                        // each time this lambda re-ran, i.e. on every recomposition of
                        // the screen, before LazyColumn could compose the ten it shows.
                        items(
                            items = threadItems,
                            key = { it.key },
                            contentType = { it.contentType() }
                        ) { entry ->
                          val threadRow: @Composable () -> Unit = {
                          when (entry) {
                            is ChatThreadItem.DateHeader -> {
                                DateSectionHeader(timestamp = entry.timestamp)
                            }
                            // (N17) Several photos sent together, grouped into one
                            // compact row instead of one full-width bubble each.
                            // No swipe-to-reply/reveal-timestamp here — those read
                            // naturally on a single bubble, not on a grid standing
                            // in for several messages at once; tapping a photo
                            // still opens the exact same full-screen viewer a lone
                            // photo bubble already does.
                            is ChatThreadItem.PhotoStack -> {
                                Box(
                                    modifier = Modifier.padding(
                                        start = if (entry.messages.first().isFromMe) 64.dp else 8.dp,
                                        end = if (entry.messages.first().isFromMe) 8.dp else 64.dp,
                                        top = 1.dp,
                                        bottom = if (entry.isTail) 2.dp else 1.dp
                                    ),
                                    contentAlignment = if (entry.messages.first().isFromMe) {
                                        Alignment.CenterEnd
                                    } else {
                                        Alignment.CenterStart
                                    }
                                ) {
                                    PhotoStackGrid(
                                        messages = entry.messages,
                                        onLoadMedia = { id -> viewModel.loadMediaData(id) }
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            is ChatThreadItem.Bubble -> {
                            val message = entry.message
                            run {
                                val reactionSummary = remember(reactionsMap[message.id]) {
                                    reactionManager.getReactionSummary(message.id)
                                }

                                // (N15) Progress of a leftward swipe, 0..1 — drives
                                // RevealedTimestampChip below. Local to this one row,
                                // like reactionSummary above it.
                                var revealProgress by remember(message.id) { mutableFloatStateOf(0f) }
                                // How far the bubble itself slides at full reveal. The
                                // same distance the gesture normalises against, so
                                // progress 1 means "slid the whole way".
                                val revealShiftPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                                    SwipeToReplyDefaults.MAX_OFFSET_DP.dp.toPx()
                                }

                                // RP-1 · swipe-right-to-reply, the iOS gesture Android never had
                                // (`OSHI/ChatView.swift:1798` attaches `SwipeToReply` here).
                                // Wrapped rather than passed as a modifier because
                                // `MessageBubble` owns its own layout; the Box is layout-neutral.
                                Box(
                                    modifier = Modifier
                                        .swipeToReply(
                                            enabled = !message.isDeletedForEveryone
                                        ) {
                                            viewModel.setReplyingTo(message)
                                            try { textFieldFocusRequester.requestFocus() } catch (_: Exception) {}
                                        }
                                        .swipeToRevealTimestamp(
                                            enabled = !message.isDeletedForEveryone
                                        ) { revealProgress = it }
                                ) {
                                // (N15) The bubble is what MOVES. Without this the
                                // gesture computed an offset nothing ever read: the
                                // chip faded in ON TOP of the message instead of
                                // being uncovered beside it, which for an outgoing
                                // bubble (flush to the trailing edge, exactly where
                                // the chip sits) meant swiping to see the time hid
                                // the last words of what you sent. Translating here
                                // rather than on the Box above, because that Box
                                // holds the chip too and would carry it along.
                                Box(
                                    modifier = Modifier.graphicsLayer {
                                        // Outgoing only, and not out of timidity:
                                        // a bubble is at most 70 % of the screen
                                        // (Oshi.Bubble.maxWidth), so an OUTGOING
                                        // one has a gutter of at least 22 % on its
                                        // left and slides into it with room to
                                        // spare, while an INCOMING one starts 8 dp
                                        // from the left edge and sliding it 72 dp
                                        // would carry its first words off-screen.
                                        // Incoming needs no slide anyway: that same
                                        // 22 % gutter is on its right, exactly where
                                        // the chip appears, so nothing is covered
                                        // there in the first place.
                                        translationX =
                                            if (message.isFromMe) -revealProgress * revealShiftPx else 0f
                                    }
                                ) {
                                // __MESHCORE_INTEROP_2026_11_25__ A MeshCore channel row keeps the
                                // "<name>: " its sender's app wrote; the name goes above the bubble
                                // like a LoRa channel's speaker, the text inside it.
                                val meshCoreSpeaker = if (isMeshCoreChannel && !message.isFromMe) {
                                    remember(message.content) { MeshCoreUi.channelSpeaker(message.content) }
                                } else null
                                MessageBubble(
                                    message = if (meshCoreSpeaker?.first != null) message.copy(content = meshCoreSpeaker.second) else message,
                                    // Only the last bubble of a run is tailed.
                                    isTail = entry.isTail,
                                    // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ In a
                                    // channel, every bubble says who sent it;
                                    // elsewhere the name is the screen title.
                                    senderLabel = if (isLoRaChannel) {
                                        loRaSpeakerName(message.senderPublicKey, loRaNodes)
                                    } else meshCoreSpeaker?.first,
                                    messageActionsManager = messageActionsManager,
                                    contact = contact,
                                    allMessages = messages,
                                    onScrollToMessage = { targetId ->
                                        val targetIndex = ChatThreadItems.positionOf(threadItems, targetId)
                                        if (targetIndex >= 0) {
                                            coroutineScope.launch {
                                                listState.animateScrollToItem(targetIndex)
                                            }
                                        }
                                    },
                                    locationManager = locationManager,
                                    conversationId = publicKey,
                                    onSendLocation = { locationMessage ->
                                        viewModel.sendLocationMessage(locationMessage)
                                    },
                                    // 📊 Le sondage remonte le choix; ici on sait à qui l'envoyer.
                                    onPollVote = { poll, picks ->
                                        viewModel.sendPollVote(poll, picks)
                                    },
                                    myPublicKey = myOwnPublicKey,
                                    onLongPress = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedMessage = message
                                        showMessageActions = true
                                    },
                                    onDoubleTap = {
                                        // __OSHI_SMILE_REACTION_2026_11_09__ double-tap
                                        // toggles the OSHI smiling O (was 👍). The
                                        // gesture itself is unchanged: it was already
                                        // `onDoubleClick` on the bubble's row, so the
                                        // single-tap (footer reveal) wait is not new,
                                        // and links/media keep their own clickables,
                                        // which consume their taps first.
                                        // `addReaction` toggles: same emoji = remove.
                                        reactionManager.addReaction(
                                            messageId = message.id,
                                            emoji = com.oshi.messenger.data.model.OshiReaction.OSHI_SMILE,
                                            recipientPublicKey = publicKey
                                        )
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    reactionSummary = reactionSummary,
                                    onReactionClick = { emoji ->
                                        reactionManager.addReaction(
                                            messageId = message.id,
                                            emoji = emoji,
                                            recipientPublicKey = publicKey
                                        )
                                    },
                                    // Audio playback
                                    audioPlayer = audioPlayer,
                                    isPlaying = currentPlayingMessageId == message.id &&
                                        playbackState == AudioPlayerManager.PlaybackState.PLAYING,
                                    playbackProgress = if (currentPlayingMessageId == message.id) playbackProgress else 0f,
                                    isSpeakerOn = isSpeakerOn,
                                    playbackSpeed = playbackSpeed,
                                    onPlayPause = { audioData ->
                                        // __VOICE_MINIPLAYER_2026_10_09__ Name the note
                                        // so the floating player can say what is playing
                                        // once the user has left this conversation.
                                        audioPlayer.play(
                                            message.id,
                                            audioData,
                                            if (message.isFromMe) {
                                                context.getString(R.string.chat_you)
                                            } else {
                                                contactDisplayName
                                                    ?: contact?.alias?.takeIf { it.isNotEmpty() }
                                                    ?: publicKey.take(8)
                                            }
                                        )
                                    },
                                    onToggleSpeaker = { audioPlayer.toggleSpeaker() },
                                    onCycleSpeed = { audioPlayer.cyclePlaybackSpeed() },
                                    onRetry = {
                                        viewModel.retrySendMessage(message)
                                    },
                                    isSearchHighlighted = currentSearchMatchId == message.id,
                                    onOpenInMap = onOpenLocationInMap,
                                    onContactCardTap = { pk, alias ->
                                        viewModel.openChatWithContact(pk, alias)
                                    },
                                    onViewOnceOpened = { id -> viewModel.markViewOnceOpened(id) },
                                    // 📎 M-17: the re-read the hydration budget needs to
                                    // degrade to lazy loading instead of to a blank bubble.
                                    onLoadMedia = { id -> viewModel.loadMediaData(id) },
                                    onCopyMedia = { id, out -> viewModel.copyMediaTo(id, out) }
                                )
                                } // (N15) closes the translating Box
                                // (N15) The revealed time + transport dot, uncovered
                                // at the trailing edge by the bubble sliding off it
                                // rather than taking a row of its own — see
                                // BubbleMetaRow below for why the always-on version
                                // stays a small dot rather than disappearing.
                                run {
                                    val revealNormalizedTs = if (message.timestamp in 1..1_000_000_000_000L) {
                                        message.timestamp * 1000
                                    } else message.timestamp
                                    com.oshi.messenger.ui.components.RevealedTimestampChip(
                                        progress = revealProgress,
                                        label = remember(revealNormalizedTs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(revealNormalizedTs)) },
                                        dotColor = transportInkFor(message.deliveryMethod, message.isFailed, isDarkScheme()),
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 8.dp)
                                    )
                                }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            }
                          }
                          }
                          // __OPEN_AT_THE_FIRST_UNREAD__ the line sits ABOVE the first
                          // unread row, inside that row's item so every index the
                          // thread resolves (F-1) stays the index in `threadItems`.
                          if (entry.key == unreadDividerKey) {
                              Column(modifier = Modifier.fillMaxWidth()) {
                                  UnreadMessagesDivider()
                                  threadRow()
                              }
                          } else {
                              threadRow()
                          }
                        }

                        // 🔒 End-to-end-encryption notice, emitted LAST because the list is
                        // `reverseLayout = true`: the final item is the one that sits at the
                        // very top of the thread, above the oldest message — the same place
                        // iOS and every other messenger puts it. Both strings already ship
                        // in all 33 locales, so nothing new to translate.
                        item(key = "e2e_notice") {
                            EncryptionNoticeChip(onClick = { onOpenSafetyNumber(publicKey) })
                        }
                    }
                    } // (N11) closes CompositionLocalProvider

                    // Floating date header (Telegram-style) — shows during scroll
                    // __CHAT_OPEN_PERF_2026_11_14__ NOT keyed on `firstVisibleItemIndex`:
                    // reading it here made the WHOLE screen recompose each time a row
                    // crossed the top edge while scrolling. `derivedStateOf` already
                    // tracks `layoutInfo` by itself.
                    val floatingDate = remember(threadItems) {
                        derivedStateOf {
                            // In reverse layout, firstVisibleItemIndex 0 = newest.
                            // We need to find which message is at the top of the visible list.
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            // Find the topmost message item (skip date headers)
                            val topItem = visibleItems.lastOrNull {
                                it.key is String && !(it.key as String).startsWith(ChatThreadItem.DATE_HEADER_PREFIX)
                            }
                            val topMessage = ChatThreadItems.messageForKey(threadItems, topItem?.key as? String)
                            topMessage?.let { formatDateHeader(it.timestamp) }
                        }
                    }

                    val isScrolling by remember {
                        derivedStateOf { listState.isScrollInProgress }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isScrolling && floatingDate.value != null,
                        enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.8f, animationSpec = tween(150)),
                        exit = fadeOut(animationSpec = tween(300)),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp + padding.calculateTopPadding())
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                text = floatingDate.value ?: "",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Scroll-to-bottom FAB
                    val showScrollToBottom by remember {
                        derivedStateOf { listState.firstVisibleItemIndex > 2 }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = padding.calculateBottomPadding())
                            .padding(16.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    // Instant jump to the newest message (iOS made the
                                    // arrow snap rather than glide). reverseLayout, so
                                    // index 0 is the newest row.
                                    listState.scrollToItem(0)
                                }
                            },
                            containerColor = OSHIPurple,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Pull-to-refresh indicator (6B.13)
                    PullRefreshIndicator(
                        refreshing = isRefreshing,
                        state = pullRefreshState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = padding.calculateTopPadding()),
                        contentColor = OSHIPurple
                    )
                }
            }
        }
    }
    }

    pendingVoice?.let { clip ->
        com.oshi.messenger.ui.components.VoiceMessagePreviewSheet(
            voiceClip = clip,
            recorder = audioRecorder,
            player = audioPlayer,
            allowViewOnce = true,
            onSend = { bytes, viewOnce ->
                viewModel.sendVoiceMessage(bytes, clip.durationSeconds, isViewOnce = viewOnce)
                audioRecorder.setVoiceEffect(com.oshi.messenger.service.audio.VoiceEffectProcessor.VoiceEffect.NORMAL)
                pendingVoice = null
            },
            onDiscard = { pendingVoice = null }
        )
    }

    // __VIDEO_NOTE_2026_09_24__ Round video note recorder.
    if (showVideoNoteRecorder) {
        com.oshi.messenger.ui.components.VideoNoteRecorder(
            onDismiss = { showVideoNoteRecorder = false },
            onSend = { bytes, durationMs -> viewModel.sendVideoNote(bytes, durationMs) }
        )
    }

    // --- Recording Hint Overlay (6B.14) ---
    if (showRecordingHint) {
        RecordingHintOverlay(
            onDismiss = {
                showRecordingHint = false
                // Save flag so it never shows again
                context.getSharedPreferences("oshi_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("recording_hint_shown", true)
                    .apply()
            }
        )
    }

    // --- "Je pense à toi" / Thinking of You (iOS CreateScheduledMessageView) ---
    //
    // 🔧 FIX (2026-08-24 three-gaps). This used to be `ScheduleMessageSheet`: a date
    // picker over whatever was already in the composer, gated behind "Type your
    // message first". iOS's `scheduled_msg.menu` item is not "send this later" — it
    // is the AI-crafted message (`OSHI/ChatView.swift:2757-2762` →
    // `CreateScheduledMessageView` → 5 tones → "Generate with AI" →
    // `NLPManager.processGenerateThinkingOfYou` → edit → schedule).
    //
    // The three Android pieces existed and were not joined; the join is here.
    // Anything the user had typed still prefills the preview, so the old behaviour
    // is a subset of the new one.
    if (showScheduleCallSheet) {
        val peerName = contactDisplayName ?: contact?.alias?.takeIf { it.isNotEmpty() } ?: publicKey.take(8)
        com.oshi.messenger.ui.components.ScheduleCallSheet(
            peerName = peerName,
            peerPublicKey = publicKey,
            onDismiss = { showScheduleCallSheet = false },
            onSchedule = { whenMs, durationMin, title ->
                viewModel.sendScheduledCall(peerName, myName = "", whenMs = whenMs,
                    durationMin = durationMin, title = title)
                showScheduleCallSheet = false
            }
        )
    }

    if (showScheduleSheet) {
        var thinkingTone by remember { mutableStateOf(com.oshi.messenger.service.MessageTone.FRIENDLY) }
        com.oshi.messenger.ui.components.ThinkingOfYouSheet(
            initialText = messageText,
            generatedText = nlpState.thinkingOfYouResult,
            isGenerating = nlpState.isGeneratingThinkingOfYou,
            selectedTone = thinkingTone,
            onSelectTone = { tone ->
                thinkingTone = tone
                nlpViewModel.selectThinkingOfYouTone(tone.writingStyle())
            },
            onGenerate = {
                // The generator detects the language from the CONVERSATION, not from
                // the device locale — `buildConversationContext` above is what feeds it.
                nlpViewModel.generateThinkingOfYou(contactDisplayName ?: contact?.alias ?: "")
            },
            onSchedule = { text, whenMs, wasAi ->
                scheduledMessageManager.scheduleMessage(
                    recipientKey = publicKey,
                    content = text,
                    scheduledTimeMs = whenMs,
                    tone = thinkingTone,
                    isAiGenerated = wasAi
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_schedule_added),
                    Toast.LENGTH_SHORT
                ).show()
                messageText = ""
                nlpViewModel.clearThinkingOfYou()
                showScheduleSheet = false
            },
            onDismiss = {
                nlpViewModel.clearThinkingOfYou()
                showScheduleSheet = false
            }
        )
    }

    // 🔧 P1 FIX (device walkthrough #2, 2026-08-13): "Media Gallery" was a DEAD
    // control — tapped 3× at its exact dumped bounds, the menu dismissed and
    // nothing happened, with zero logcat output. The item was wired correctly all
    // along (`showMediaGallery = true`); the bug was pure z-order. These two
    // full-screen composables were emitted near the TOP of `ChatScreen`'s body,
    // i.e. as siblings drawn BEFORE — and therefore UNDERNEATH — the opaque
    // `Scaffold` below them. `GroupChatScreen` emits the identical call AFTER its
    // Scaffold, which is exactly why the same menu item works there and not here.
    // Moving them to the end of the body puts them on top, as intended.
    // iOS presents the equivalent as a sheet (`ChatView.swift:1817-1822`
    // `showMediaGallery = true`), which has no such ordering hazard.
    if (showMediaGallery) {
        MediaGalleryScreen(
            peerPublicKey = publicKey,
            groupId = null,
            onBack = { showMediaGallery = false },
            onOpenMedia = { data, type, caption ->
                galleryViewerMedia = Triple(data, type, caption)
            }
        )
    }
    galleryViewerMedia?.let { (data, type, caption) ->
        MediaViewerScreen(
            mediaData = data,
            mediaType = type,
            caption = caption,
            onBack = { galleryViewerMedia = null },
            // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ the gallery does not know who sent it.
            allowSave = !advPrivacy
        )
    }
    } // WithChatScheme(chatDark) — __ONE_SCHEME_FOR_THE_WHOLE_CHAT_2026_11_07__
    } // CompositionLocalProvider(LocalBubbleAppearance)
}


// ============================================================================
// TYPING INDICATOR / ENCRYPTION NOTICE
// ============================================================================

/**
 * Three staggered bouncing dots, the animated half of the typing indicator.
 *
 * The indicator used to be a bare `Text("… is typing…")`, which is indistinguishable
 * from an ordinary line of chrome: it appears and disappears with no motion, so on a
 * busy screen it simply is not noticed. This is the same shape the launch screen's
 * loading dots use (`ui/screens/LaunchScreen.kt:92-101`, itself a port of
 * `OSHI/LaunchScreenView.swift:152-158`) — 0.4 s, auto-reversing, staggered 0.15 s —
 * re-implemented here rather than shared because that one is private to its file.
 *
 * Reduced motion is honoured exactly as the launch screen honours it: the Android
 * analogue of iOS's `accessibilityReduceMotion` is the system animator duration scale
 * being switched off, in which case the dots render statically.
 */
@Composable
private fun TypingBouncingDots(
    tint: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    val transition = rememberInfiniteTransition(label = "typing_dots")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = if (reduceMotion) 0f else -3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400,
                        delayMillis = i * 150,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "typing_dot_lift_$i"
            )
            val fade by transition.animateFloat(
                initialValue = if (reduceMotion) 0.9f else 0.45f,
                targetValue = 0.9f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400,
                        delayMillis = i * 150,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "typing_dot_fade_$i"
            )
            if (i > 0) Spacer(modifier = Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .offset(y = lift.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = fade))
            )
        }
    }
}

/**
 * The one-off system line at the very top of a conversation: this chat is
 * end-to-end encrypted, and here is where you go to prove it.
 *
 * Both strings already ship translated in all 33 locales
 * (`web_session_security_encrypted_short`, `safety_number_description`), so this adds
 * no localisation debt. Tapping it opens the same safety-number screen as the
 * overflow entry — that screen previously had no entry point at all.
 */
@Composable
private fun EncryptionNoticeChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.web_session_security_encrypted_short),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.safety_number_description),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}


// ============================================================================
// VOICE RECORDING BAR
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceRecordingBar(
    duration: Long,
    amplitudes: List<Float>,
    cancelOffset: Float,
    selectedEffect: VoiceEffect = VoiceEffect.NORMAL,
    availableEffects: List<VoiceEffect> = emptyList(),
    onEffectSelected: (VoiceEffect) -> Unit = {},
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onDrag: (Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Voice effect picker row (matching iOS VoiceEffect selector)
        if (availableEffects.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(availableEffects) { effect ->
                    val isSelected = effect == selectedEffect
                    OshiFilterChip(
                        selected = isSelected,
                        onClick = { onEffectSelected(effect) },
                        label = {
                            Text(
                                text = getEffectLabel(effect),
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Text(
                                text = getEffectEmoji(effect),
                                fontSize = 14.sp
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (cancelOffset > -150f) {
                                onSend()
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            onDrag(cancelOffset + dragAmount)
                        }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel hint (slide left)
            AnimatedVisibility(
                visible = cancelOffset < -50f,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.chat_slide_to_cancel),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Recording indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = pulseAlpha))
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Duration
            Text(
                text = formatDuration(duration),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Waveform visualization
            WaveformVisualizer(
                amplitudes = amplitudes,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Send button
            FilledIconButton(
                onClick = onSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.chat_send_voice))
            }
        }
    }
}

/** Get display label for voice effect */
private fun getEffectLabel(effect: VoiceEffect): String = effect.displayName

/** Get emoji icon for voice effect */
private fun getEffectEmoji(effect: VoiceEffect): String = when (effect) {
    VoiceEffect.NORMAL -> "\uD83C\uDFA4"    // 🎤
    VoiceEffect.ROBOT -> "\uD83E\uDD16"      // 🤖
    VoiceEffect.ALIEN -> "\uD83D\uDC7D"      // 👽
    VoiceEffect.DEEP -> "\uD83D\uDC3B"       // 🐻
    VoiceEffect.CHIPMUNK -> "\uD83D\uDC3F"   // 🐿
    VoiceEffect.ECHO -> "\uD83D\uDD0A"       // 🔊
    VoiceEffect.REVERB -> "\u26EA"            // ⛪
}

@Composable
private fun WaveformVisualizer(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val displayAmplitudes = if (amplitudes.isEmpty()) {
            List(30) { 0.1f }
        } else {
            amplitudes.takeLast(30)
        }

        displayAmplitudes.forEach { amplitude ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction = (amplitude * 0.8f + 0.2f).coerceIn(0.1f, 1f))
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(barColor.copy(alpha = 0.7f + amplitude * 0.3f))
            )
        }
    }
}

// ============================================================================
// M-17 — THE HYDRATION BUDGET MUST DEGRADE TO LAZY LOADING, NOT TO DATA LOSS
// ============================================================================

/**
 * What a bubble knows about its attachment's bytes.
 *
 * `MessageRepository.hydrateMedia` re-attaches file-backed blobs newest-first under
 * iOS's own cache bounds (60 items / 96 MB — `MediaAttachmentCache.swift:37-38`).
 * The bound itself is right; what was wrong is that on Android it was the **only**
 * read path the message list had, so the 61st-oldest photo in a thread arrived with
 * `mediaData = null`, rendered as an empty bubble, and stayed that way forever — no
 * spinner, no error, no retry, and the bytes sitting untouched on disk the whole
 * time. On iOS the same budget governs a cache, and a miss is simply re-read inside
 * the bubble's own `.task`.
 */
private sealed class BubbleMediaState(
    /** Ask again. A no-op except on [Unavailable], where it is the retry affordance. */
    val retry: () -> Unit
) {
    /** Bytes in hand — either inline on the row, or just re-read from the store. */
    class Ready(val bytes: ByteArray) : BubbleMediaState({}) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Ready && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** The re-read is in flight. */
    object Loading : BubbleMediaState({})

    /** No bytes, and asking again is the user's call. */
    class Unavailable(onRetry: () -> Unit = {}) : BubbleMediaState(onRetry)
}

/**
 * Resolve a bubble's attachment bytes: the row's own [inline] bytes when the
 * hydration budget kept them, otherwise ONE re-read through [onLoadMedia].
 *
 * Keyed on the message id, so scrolling a bubble out and back re-reads at most once
 * per composition and `MediaAttachmentStore`'s LRU usually answers from memory.
 * `attempt` exists so the retry affordance can force a genuinely new read after a
 * failure — a bare `LaunchedEffect(messageId)` would never run twice.
 */
@Composable
private fun rememberBubbleMedia(
    messageId: String,
    inline: ByteArray?,
    onLoadMedia: (suspend (String) -> ByteArray?)?
): BubbleMediaState {
    var attempt by remember(messageId) { mutableStateOf(0) }
    val retry = { attempt += 1 }
    // __CHAT_OPEN_PERF_2026_11_14__ seeded from the bounded bytes cache, so a bubble
    // that scrolls back into view draws in its first frame (see [BubbleBytesCache]).
    var loaded by remember(messageId) {
        mutableStateOf(if (inline == null) com.oshi.messenger.ui.media.BubbleBytesCache.get(messageId) else null)
    }
    var loading by remember(messageId) { mutableStateOf(onLoadMedia != null && loaded == null) }

    // 🚦 The read fires ONLY when there is something to read and a reader to do it.
    //
    // This gate is the whole reason `onLoadMedia` is nullable at the call site. The
    // `when (message.mediaType)` this feeds is entered by EVERY bubble, including
    // plain text (its `else` branch handles legacy voice notes sent as text), so an
    // ungated re-read would fire one `getMessageById` per text bubble per scroll —
    // trading M-17's blank bubble for a database query on every line of the
    // conversation. The caller passes null unless the row really is a media row with
    // no bytes on it.
    val shouldLoad = inline == null && onLoadMedia != null
    LaunchedEffect(messageId, attempt, shouldLoad) {
        if (!shouldLoad || (attempt == 0 && loaded != null)) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loaded = try {
            onLoadMedia!!(messageId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        loaded?.let { com.oshi.messenger.ui.media.BubbleBytesCache.put(messageId, it) }
        loading = false
    }

    if (inline != null) return BubbleMediaState.Ready(inline)
    val bytes = loaded
    // The decision itself is a pure function, asserted off-device — see
    // [BubbleMediaKind.of]. The composable only supplies the inputs and the bytes.
    return when (BubbleMediaKind.of(
        hasInline = false,
        hasLoader = onLoadMedia != null,
        loading = loading,
        loadedSize = bytes?.size
    )) {
        BubbleMediaKind.READY -> BubbleMediaState.Ready(bytes!!)
        BubbleMediaKind.LOADING -> BubbleMediaState.Loading
        BubbleMediaKind.UNAVAILABLE -> BubbleMediaState.Unavailable(retry)
    }
}

/**
 * The visible face of a bubble whose bytes are not (yet) here. Replaces an empty
 * box, which said nothing at all.
 */
@Composable
private fun MediaPlaceholder(
    state: BubbleMediaState,
    onRetry: () -> Unit
) {
    val loading = state is BubbleMediaState.Loading
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .then(if (loading) Modifier else Modifier.clickable { onRetry() })
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = stringResource(R.string.chat_media_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.chat_media_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// MESSAGE BUBBLE WITH MEDIA AND REACTIONS
// ============================================================================

/**
 * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ What to call a speaker in a channel.
 *
 * Prefers the radio's own long name, then its short name, then the `!a1b2c3d4`
 * node id, which is what every Meshtastic client falls back to and is always
 * available. Never blank: an unattributed bubble in a room is the thing this
 * label exists to prevent.
 *
 * A bound OSHI identity keeps its stored key as the conversation key, so it will
 * not match a node number and falls through to the key's own short form. That is
 * correct rather than unfortunate: if we know them as a contact, the contact
 * name is what the rest of the app shows for them.
 */
private fun loRaSpeakerName(
    senderKey: String,
    nodes: List<com.oshi.messenger.service.lora.LoRaMeshNode>,
): String {
    val nodeNum = senderKey.removePrefix("lora!").toUIntOrNull(16)
    if (nodeNum != null) {
        nodes.firstOrNull { it.num == nodeNum }?.let { node ->
            node.longName.ifEmpty { node.shortName }.ifEmpty { null }?.let { return it }
        }
        return "!" + nodeNum.toString(16).padStart(8, '0')
    }
    return senderKey.take(8) + "\u2026"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: Message,
    messageActionsManager: MessageActionsManager? = null,
    contact: com.oshi.messenger.data.model.Contact? = null,
    allMessages: List<Message> = emptyList(),
    onScrollToMessage: ((String) -> Unit)? = null,
    locationManager: LocationSharingManager? = null,
    conversationId: String? = null,
    onSendLocation: ((LocationMessage) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    reactionSummary: ReactionSummary? = null,
    onReactionClick: ((String) -> Unit)? = null,
    audioPlayer: AudioPlayerManager? = null,
    isPlaying: Boolean = false,
    playbackProgress: Float = 0f,
    isSpeakerOn: Boolean = true,
    playbackSpeed: Float = 1.0f,
    onPlayPause: ((ByteArray) -> Unit)? = null,
    onToggleSpeaker: (() -> Unit)? = null,
    onCycleSpeed: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    isSearchHighlighted: Boolean = false,
    /**
     * Last message of a run from this sender, so this is the bubble that gets
     * the tailed corner. Computed by [ChatThreadItems.build], which is the only
     * thing that can see a bubble's neighbours. Defaults to `true` so a caller
     * that does not group still gets the previous behaviour.
     */
    isTail: Boolean = true,
    onOpenInMap: ((lat: Double, lon: Double, name: String?, contactName: String?, contactPicture: ByteArray?, isLive: Boolean, sessionId: String?) -> Unit)? = null,
    onContactCardTap: ((publicKey: String, alias: String?) -> Unit)? = null,
    /**
     * 📊 A ballot to cast. The bubble does not know WHO to send it to — the
     * conversation does — so it hands the picks back up.
     */
    onPollVote: ((com.oshi.messenger.data.model.PollMessage, List<Int>) -> Unit)? = null,
    /** This device's own key, so a poll can mark the answer it chose. */
    myPublicKey: String = "",
    /**
     * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ Who said this, drawn above the
     * bubble, for a thread where more than one person speaks.
     *
     * Null in an ordinary one-to-one conversation, where the name is already the
     * title of the screen and repeating it over every bubble would be noise. Set
     * only for a LoRa channel, where the whole point is that several radios talk
     * at once and a bubble with no name is unattributable.
     */
    senderLabel: String? = null,
    /**
     * 🔒 Durable consumption of a view-once message: persists the flag AND destroys
     * the bytes (`MessageRepository.markViewOnceOpened`). The local
     * [ViewOnceOpenedStore] write below stays as the belt-and-braces UI latch.
     */
    onViewOnceOpened: ((String) -> Unit)? = null,
    /**
     * 📎 M-17. On-demand re-read of an attachment the hydration budget skipped.
     * See [rememberBubbleMedia]. Null (the default) keeps the old behaviour for
     * any caller that has no repository to ask — the bubble then simply shows the
     * "unavailable" state instead of an empty box.
     */
    onLoadMedia: (suspend (String) -> ByteArray?)? = null,
    /**
     * __DOC_STREAM_2026_11_11__ Streams an attachment into an OutputStream. A document
     * bubble saves through this instead of holding the whole file on the heap.
     */
    onCopyMedia: (suspend (String, java.io.OutputStream) -> Boolean)? = null
) {
    val isFromMe = message.isFromMe
    val context = LocalContext.current
    // __SHARED_NICKNAME_2026_09_22__ user alias > peer's shared nickname > placeholder.
    val bubbleNicknames by com.oshi.messenger.data.model.PeerNicknameStore.nicknames.collectAsState()
    val bubbleContactName = contact?.let {
        com.oshi.messenger.data.model.PeerNickname.resolve(
            it.alias,
            com.oshi.messenger.data.model.PeerNickname.lookup(bubbleNicknames, it.publicKey),
            it.publicKey
        )
    }

    // Parse reply data if message content is a reply
    val replyData = remember(message.content) {
        messageActionsManager?.let { manager ->
            if (manager.isReply(message.content)) manager.parseReply(message.content) else null
        }
    }

    // Localized copy for every control-payload summary, resolved once.
    val controlLabels = remember(context) { OSHIControlPayload.labels(context) }

    // The RAW payload this bubble is about: prefer editedContent, then the body
    // inside a reply envelope, then the original content.
    //
    // 🔧 SANITISE-THEN-DETECT FIX. This used to be sanitised on the spot and every
    // bubble-type test below ran on the SANITISED string — but the sanitizer has
    // already replaced "📱CALL_SUMMARY📱…" with "📞 Call", "🛡️CHECK_IN🛡️…" with
    // "🛡️ Check-In", etc., so `startsWith("📱CALL_SUMMARY📱")` could never be
    // true. Four renderers (call summary, check-in, scheduled call, system
    // notice) were unreachable and every one of those events collapsed to a flat
    // text bubble — which is also why the 1.4.6 check-in epoch fix and the 1.4.8
    // Apple-epoch scheduled-call fix were invisible: both live inside the dead
    // composables. Detect on RAW; sanitise only for the text branch.
    // (iOS detects on raw `plaintextContent` — MediaMessageView.swift:88-90.)
    val rawContent = remember(message.content, message.editedContent, replyData) {
        message.editedContent ?: replyData?.replyContent ?: message.content
    }
    val controlPrefix = remember(rawContent) { OSHIControlPayload.matchedPrefix(rawContent) }

    // F-5. Read the forward envelope off message.content, NOT displayContent:
    // by then displaySummary has already unwrapped it to the bare body and the
    // badge information is gone. iOS reads it at the same point
    // (GroupViews.swift:2705-2716).
    val forwardInfo = remember(message.content) {
        MessageActionsManager.parseForwardEnvelope(message.content)
    }

    // What the TEXT branch shows. The catalog answers first (one source of truth,
    // localized); MessageSanitizer stays as the residual net for the things it
    // knows and the catalog deliberately does not (encrypted blobs, stray JSON).
    val displayContent = remember(rawContent, controlLabels) {
        OSHIControlPayload.displaySummary(rawContent, controlLabels)
            ?: MessageSanitizer.sanitizeForDisplay(rawContent)
    }

    // Resolve the original sender name for reply quote
    val youLabel = context.getString(R.string.chat_you)
    val unknownLabel = context.getString(R.string.chat_unknown)
    val replySenderName = remember(replyData, contact, allMessages) {
        if (replyData == null) null
        else {
            // Try to find the original message to determine sender
            val originalMsg = allMessages.find { it.id == replyData.originalId }
            if (originalMsg != null) {
                if (originalMsg.isFromMe) youLabel else (bubbleContactName ?: replyData.originalSender.take(8) + "...")
            } else {
                // Fallback: if the sender key matches the contact, use their alias
                if (replyData.originalSender == contact?.publicKey) (bubbleContactName ?: contact.alias)
                else if (replyData.originalSender.isEmpty()) unknownLabel
                else replyData.originalSender.take(8) + "..."
            }
        }
    }

    // Check if this is a location message — parse from RAW content, not sanitized displayContent
    // (MessageSanitizer strips the JSON payload, replacing it with "📍 Location shared")
    val locationMessage = remember(message.content) {
        LocationMessage.fromMessageString(message.content)
    }

    // Bubble type — all four resolved from the ONE catalog against RAW content.
    val systemMessageType = remember(controlPrefix) {
        when (controlPrefix) {
            OSHIControlPayload.PROFILE_UPDATE -> SystemMessageType.PROFILE_UPDATE
            OSHIControlPayload.WALLPAPER_UPDATE -> SystemMessageType.WALLPAPER_UPDATE
            OSHIControlPayload.KEY_ROTATION -> SystemMessageType.KEY_ROTATION
            // __BLOCKED_NOTICE_2026_09_18__ A centred capsule, not a bubble: it is
            // not something the other person said, it is the thread telling you
            // what happens to everything you send into it from now on.
            OSHIControlPayload.BLOCKED_NOTICE -> SystemMessageType.BLOCKED_NOTICE
            else -> null
        }
    }

    val isCallSummary = controlPrefix == OSHIControlPayload.CALL_SUMMARY ||
        controlPrefix == OSHIControlPayload.MISSED_CALL

    // Both spellings are live on the wire (OSHIControlPayload.swift:98-99).
    val isCheckIn = controlPrefix == OSHIControlPayload.CHECK_IN ||
        controlPrefix == OSHIControlPayload.CHECKIN

    val isScheduledCall = controlPrefix == OSHIControlPayload.SCHEDULED_CALL

    // 📊 Un sondage. Passe par le préfixe résolu, donc par le catalogue.
    val isPoll = controlPrefix == OSHIControlPayload.POLL

    // Detect URL in message
    val detectedUrl = remember(displayContent) {
        if (locationMessage == null && !isCallSummary && !isCheckIn && !isScheduledCall && !isPoll && systemMessageType == null && message.mediaType == null) {
            displayContent.detectFirstUrl()
        } else null
    }

    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ a centred notice naming who switched it.
    if (controlPrefix == OSHIControlPayload.ADV_PRIVACY) {
        val change = remember(rawContent) {
            com.oshi.messenger.data.model.AdvancedChatPrivacyChange.fromMessageString(rawContent)
        }
        com.oshi.messenger.ui.components.AdvancedChatPrivacyNotice(
            on = change?.on ?: true,
            actorName = if (isFromMe) null else (bubbleContactName ?: stringResource(R.string.chat_unknown)),
        )
        return
    }

    // --- System Info Bubble (6B.11): centered capsule, no avatar/timestamp/reactions ---
    if (systemMessageType != null) {
        SystemInfoBubble(type = systemMessageType)
        return
    }

    // __BUBBLE_FOOTER_FOLLOWS_THE_RUN_2026_10_28__ (N10) Whether this bubble wears its
    // time/transport row unconditionally, or only when tapped.
    //
    // `isTail` already arrives from `ChatThreadItems.build` — the only place that can see a
    // bubble's neighbours — and was used for the tailed CORNER and nothing else. The
    // exemptions are iOS's, unchanged: a failed message keeps its footer because the footer
    // carries tap-to-retry, and an edited one keeps it because it must go on saying so.
    val alwaysShowsFooter = com.oshi.messenger.data.model.BubbleFooterRule.showsFooter(
        isTail = isTail,
        isFailed = message.isFailed,
        // Android has no distinct "pending" row: a message of mine that has not been
        // delivered or read and has not failed is still in flight, which is the state
        // iOS's `.pending` exemption exists to keep visible.
        isPending = isFromMe && !message.isFailed && !message.isDelivered && !message.isRead,
        isEdited = message.editedAt != null,
    )
    var metaRevealed by remember(message.id) { mutableStateOf(false) }

    // __LONG_PRESS_ANYWHERE_ON_THE_BUBBLE_2026_11_09__ inner tappables (quote,
    // media, cards, chips, previews) read this and give long-press back to the
    // bubble instead of swallowing it. See `BubbleGestures.kt`.
    val bubbleLongPress: (() -> Unit)? = remember(message.id, message.isDeletedForEveryone, onLongPress) {
        if (message.isDeletedForEveryone || onLongPress == null) null else ({ onLongPress.invoke() })
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.oshi.messenger.ui.components.LocalBubbleLongPress provides bubbleLongPress
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isFromMe) 64.dp else 8.dp,
                end = if (isFromMe) 8.dp else 64.dp,
                top = 2.dp,
                bottom = 2.dp
            )
            .combinedClickable(
                // __BUBBLE_FOOTER_FOLLOWS_THE_RUN_2026_10_28__ (N10) The tap that reveals
                // an interior bubble's time and transport. This handler was already here
                // and already empty; folding the footer without it would have hidden the
                // timestamp with no way back, which is precisely why the row used to be
                // drawn on every bubble.
                onClick = { if (!alwaysShowsFooter) metaRevealed = !metaRevealed },
                onDoubleClick = {
                    if (!message.isDeletedForEveryone) onDoubleTap?.invoke()
                },
                onLongClick = {
                    // No actions on deleted-for-everyone messages
                    if (!message.isDeletedForEveryone) {
                        onLongPress?.invoke()
                    }
                }
            ),
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start
    ) {
        // __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ The speaker's name, for a room
        // where several radios talk at once. Only on INCOMING bubbles: our own
        // are already on the right-hand side and labelling them "you" is noise.
        if (senderLabel != null && !isFromMe) {
            Text(
                text = senderLabel,
                style = MaterialTheme.typography.labelSmall,
                // Brand purple on the dark wallpaper was too faint for a 11 sp name (design shots).
                color = meshCoreAccent(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
            )
        }

        // Deleted-for-everyone placeholder
        if (message.isDeletedForEveryone) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\uD83D\uDEAB",  // prohibited sign
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.message_deleted),
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
        }
        // Location message bubble
        else if (locationMessage != null && locationManager != null) {
            LocationMessageBubble(
                locationMessage = locationMessage,
                isFromCurrentUser = isFromMe,
                locationManager = locationManager,
                conversationId = conversationId,
                contactName = bubbleContactName ?: contact?.publicKey?.take(8),
                contactProfilePicture = contact?.profilePicture,
                onSendLocation = onSendLocation,
                onOpenInMap = onOpenInMap
            )
        } else if (isCallSummary) {
            // RAW, not displayContent: each of these bubbles parses the payload
            // itself, and the sanitised string has no payload left.
            CallSummaryBubble(
                content = rawContent,
                timestamp = message.timestamp,
                isFromMe = isFromMe
            )
        } else if (isPoll) {
            // RAW, not displayContent: the card parses the payload itself, and
            // the sanitised string has no payload left.
            com.oshi.messenger.ui.components.PollBubble(
                content = rawContent,
                myPublicKey = myPublicKey,
                onVote = { poll, picks -> onPollVote?.invoke(poll, picks) },
                displayName = { key ->
                    // Only two people can vote in a 1:1.
                    if (com.oshi.messenger.service.CallAdmissionPolicy.normalizeKey(key) ==
                        com.oshi.messenger.service.CallAdmissionPolicy.normalizeKey(myPublicKey)
                    ) context.getString(R.string.you)
                    else contact?.alias ?: context.getString(R.string.poll_voter)
                },
            )
        } else if (isCheckIn) {
            CheckInBubble(
                content = rawContent,
                timestamp = message.timestamp
            )
        } else if (isScheduledCall) {
            ScheduledCallBubble(
                content = rawContent,
                timestamp = message.timestamp,
                onJoinCall = { /* Handled by parent */ }
            )
        } else if (message.isVideoNote && message.mediaType == MediaType.VIDEO && !message.isViewOnce) {
            // __VIDEO_NOTE_2026_09_24__ Round video note (docs/VIDEO_NOTE_SPEC.md §4): no
            // bubble, no tail — the circle IS the message; time + ticks under it.
            val noteMedia = rememberBubbleMedia(
                messageId = message.id,
                inline = message.mediaData,
                onLoadMedia = onLoadMedia?.takeIf { message.mediaData == null }
            )
            com.oshi.messenger.ui.media.VideoNoteBubble(
                messageId = message.id,
                videoData = (noteMedia as? BubbleMediaState.Ready)?.bytes,
                durationMs = message.mediaDurationMs,
                isLoading = noteMedia is BubbleMediaState.Loading,
                onRetry = { noteMedia.retry() },
                onLongPress = { onLongPress?.invoke() },
                onDoubleTap = onDoubleTap
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp)
            ) {
                val metaInk = Oshi.Chat.metaInk(isDarkScheme())
                val normalizedTs = if (message.timestamp in 1..1_000_000_000_000L) message.timestamp * 1000 else message.timestamp
                Text(
                    text = remember(normalizedTs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(normalizedTs)) },
                    fontSize = 11.sp,
                    color = metaInk
                )
                if (isFromMe && !message.isFailed) {
                    Spacer(modifier = Modifier.width(4.dp))
                    // __READ_TICK_NEEDED_MORE_THAN_COLOUR_2026_11_03__ Anatole:
                    // "en thème clair, la coche de message lu ne change pas de
                    // couleur, contrairement au thème sombre."
                    //
                    // The state machine was never wrong and neither was the
                    // palette. Both ticks have to clear AA against the worst
                    // outgoing-bubble ground (`ChatPaletteContrastTest`), which
                    // in the light scheme forces BOTH to be dark — read is
                    // #0B5A2E, delivered is #524D66, and at 14dp a hue-only
                    // difference between two dark inks is not a difference. In
                    // the dark scheme the same pair is bright green on pale
                    // lavender and reads instantly: hence "unlike the dark
                    // theme". Brightening the green fixes the symptom and
                    // fails the contrast test, which is how the first attempt
                    // at this went.
                    //
                    // So the cue is shape, not hue: when a message has been
                    // READ the ticks sit in a tinted halo. It appears and
                    // disappears, which needs no colour discrimination at all,
                    // and because the tint LIGHTENS the ground under a dark
                    // glyph it raises the tick's contrast rather than spending
                    // it. The three accessibility labels already said read,
                    // delivered and sent correctly throughout — only the eye
                    // was unserved.
                    val read = message.isRead
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = if (read) {
                            Modifier
                                .size(20.dp)
                                .background(
                                    Oshi.Chat.readTick(isDarkScheme()).copy(alpha = 0.18f),
                                    CircleShape
                                )
                        } else {
                            Modifier.size(20.dp)
                        }
                    ) {
                        // __COVER_WAITING_CLOCK_2026_11_18__ parked for the disguised path ⇒ clock, not "sent".
                        val coverParked by com.oshi.messenger.service.CoverOutbox.awaiting.collectAsState()
                        val tick = com.oshi.messenger.ui.components.OutgoingTick.of(read, message.isDelivered, message.id in coverParked)
                        Icon(
                            when (tick) {
                                com.oshi.messenger.ui.components.OutgoingTick.WAITING -> Icons.Default.Schedule
                                com.oshi.messenger.ui.components.OutgoingTick.SENT -> Icons.Default.Done
                                else -> Icons.Default.DoneAll
                            },
                            contentDescription = when (tick) {
                                com.oshi.messenger.ui.components.OutgoingTick.READ -> stringResource(R.string.message_read)
                                com.oshi.messenger.ui.components.OutgoingTick.DELIVERED -> stringResource(R.string.message_delivered)
                                com.oshi.messenger.ui.components.OutgoingTick.WAITING -> stringResource(R.string.cover_waiting_bubble)
                                else -> stringResource(R.string.message_sent)
                            },
                            modifier = Modifier.size(14.dp),
                            tint = if (read) Oshi.Chat.readTick(isDarkScheme()) else metaInk
                        )
                    }
                }
            }
        } else {
            // Message bubble. Corner geometry comes from the user's
            // Settings -> bubble style preference (Modern / Classic / Minimal);
            // the accent gradient and the playful silhouette come from
            // LocalBubbleAppearance (F-13, iOS f0fd24e / 60d3ed0).
            //
            // The silhouette's coreInsets are applied by bubbleSurface BEFORE the
            // background, so the message keeps a clean rectangle to live in and no
            // glyph lands in a wheel or an ear. See BubbleFunShapes.kt's header.
            val bubbleStyle = LocalBubbleStyle.current
            val appearance = LocalBubbleAppearance.current
            val darkScheme = isDarkScheme()
            // `isTail` comes from ChatThreadItems: only the LAST bubble of a run
            // from one sender is anchored, so five messages in a row read as one
            // column that touches down once (BubbleStyleManager.swift:296-304).
            val bubbleShape = bubbleStyle.bubbleShape(isFromMe = isFromMe, isTail = isTail)
            val bubbleBrush = if (isFromMe)
                appearance.accent.surfaceBrush(darkScheme)
            else
                // Not a flat `surfaceVariant`: iOS blends the brand INTO the
                // system surface so a received bubble belongs to this app rather
                // than to Material's default grey, and blends rather than
                // overlays so the result is opaque — a translucent bubble over an
                // animated wallpaper shimmers, and OSHI ships animated
                // wallpapers (BubbleStyleManager.swift:250-268).
                SolidColor(Oshi.Bubble.received(darkScheme, com.oshi.messenger.ui.theme.isOledScheme()))
            // Hairline rim: a pale bubble must not vanish over a photo wallpaper
            // (BubbleStyleManager.swift:307-323). It also inks the markings.
            val tonal = isFromMe && appearance.accent.isTonal(darkScheme)
            val bubbleRim = Oshi.Bubble.rimInk(outgoing = isFromMe, darkTheme = darkScheme, tonal = tonal)
            // __BUBBLE_MEDIA_2026_11_21__ A photo SIZES the bubble (BUBBLE_MEDIA_SPEC §1).
            // Before: `fillMaxWidth().heightIn(max = 250.dp)` + `ContentScale.Fit` inside a
            // 12 dp-padded column — a portrait photo was letterboxed, with a band of bubble
            // colour left and right of it, plus the padding frame all round. Now the box
            // has the picture's aspect, the bubble is exactly that wide and the picture
            // bleeds to its edges. The natural size is hoisted here (the bubble's width
            // depends on it) and filled in by ImageMessageContent when lazily-hydrated
            // bytes land.
            val edgeMedia = appearance.silhouette == null && !message.isViewOnce &&
                message.mediaType == MediaType.IMAGE && !message.isDeletedForEveryone
            var mediaNatural by remember(message.id) {
                mutableStateOf(
                    if (!edgeMedia) null
                    else com.oshi.messenger.ui.media.MediaNaturalSize.cached(message.id)
                        ?: message.mediaData?.let { com.oshi.messenger.ui.media.MediaNaturalSize.read(message.id, it) }
                )
            }
            val mediaSize = if (edgeMedia) com.oshi.messenger.ui.media.rememberBubbleMediaSize(mediaNatural, channel = false) else null
            Box(
                modifier = Modifier
                    .then(
                        if (mediaSize != null) Modifier.width(mediaSize.widthDp.dp)
                        else Modifier.widthIn(max = Oshi.Bubble.maxWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp))
                    )
                    .bubbleSurface(
                        silhouette = appearance.silhouette,
                        brush = bubbleBrush,
                        fallbackShape = bubbleShape,
                        rim = bubbleRim,
                        rimAlpha = Oshi.Bubble.rimAlpha(outgoing = isFromMe, darkTheme = darkScheme, tonal = tonal),
                        gloss = isFromMe,
                        glass = !isFromMe && com.oshi.messenger.ui.theme.oledGlassOn()
                    )
                    .then(if (appearance.silhouette == null) Modifier.clip(bubbleShape) else Modifier)
                    .then(
                        if (isSearchHighlighted)
                            Modifier.border(2.dp, Color(0xFFFFD700), bubbleShape)
                        else Modifier
                    )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // F-5: the badge lives INSIDE the bubble, above the text
                    // (iOS ForwardedBadgeView, ChatView.swift:4285-4292).
                    if (forwardInfo != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Forward,
                                contentDescription = null,
                                tint = if (isFromMe)
                                    LocalBubbleAppearance.current.accent.ink(isDarkScheme()).copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.message_forwarded),
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic,
                                color = if (isFromMe)
                                    LocalBubbleAppearance.current.accent.ink(isDarkScheme()).copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Reply quote block (shown when this message is a reply).
                    //
                    // F-10 (iOS 6e1ed53, ChatView.swift:4126-4160): the quote is an
                    // ACCENT, not a box. No fill at all on your own bubble - the
                    // bubble is already brand-coloured and a brand wash on top of it
                    // is invisible - and a 7% wash on a received one. No border.
                    // `clickable` on the Row keeps the tap target the box used to
                    // give it.
                    if (replyData != null) {
                        Surface(
                            color = if (isFromMe) Color.Transparent else OSHIPurple.copy(alpha = 0.07f),
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp, horizontal = 0.dp)
                                .bubbleClickable {
                                    if (replyData.originalId.isNotEmpty()) {
                                        onScrollToMessage?.invoke(replyData.originalId)
                                    }
                                }
                        ) {
                            Row(modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp)) {
                                // 🔴 The quote's accent and its author line were
                                // raw OSHIPurple sitting on a 7% brand wash over
                                // the received bubble: 1.95:1 in dark mode, on a
                                // name the whole quote exists to tell you. On
                                // YOUR OWN bubble it is worse still — the ground
                                // is the accent gradient itself.
                                val quoteInk = if (isFromMe)
                                    appearance.accent.ink(isDarkScheme())
                                else
                                    brandInk()
                                // Purple left accent bar
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(quoteInk)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = replySenderName ?: unknownLabel,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = quoteInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        // iOS quotes OSHIControlPayload.previewText
                                        // (MessageActionsManager.swift:66-72), so a
                                        // quoted call summary reads "Outgoing call •
                                        // 1:23" rather than a stripped sentinel.
                                        text = com.oshi.messenger.util.MessageFormatting.strip(OSHIControlPayload
                                            .previewText(replyData.originalContent, controlLabels))
                                            .take(80),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isFromMe)
                                            LocalBubbleAppearance.current.accent.ink(isDarkScheme()).copy(alpha = 0.8f)
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                    }

                    // Media content
                    //
                    // 🔒 VIEW-ONCE. `isViewOnce` arrived on the wire and was stored
                    // (MessageRepository.kt:1796 → Message.kt:32) but NOTHING read it
                    // here: an iPhone "view once" photo was rendered like any other
                    // and lived on the device forever. iOS branches on it before
                    // anything else (MediaMessageView.swift:30-46).
                    if (message.isViewOnce &&
                        (message.mediaType == MediaType.IMAGE || message.mediaType == MediaType.VIDEO ||
                            // __VOICE_PREVIEW_2026_09_29__ A view-once VOICE note (iOS has sent
                            // and played these; Android rendered them as a normal, replayable
                            // voice bubble and kept the bytes).
                            message.mediaType == MediaType.AUDIO)
                    ) {
                        ViewOnceMediaContent(
                            message = message,
                            isFromMe = isFromMe,
                            onConsumed = onViewOnceOpened,
                            audioPlayer = audioPlayer,
                            onLoadMedia = onLoadMedia
                        )
                    } else {
                    // 📎 M-17. One resolution for every media branch below: the
                    // hydration budget hands the UI `mediaData = null` for anything
                    // past the 60th / 96 MB newest-first, and the bytes are still on
                    // disk. Resolve once, here, rather than in five branches.
                    val mediaState = rememberBubbleMedia(
                        messageId = message.id,
                        inline = message.mediaData,
                        // Null unless a re-read could possibly help: a row with no
                        // media type has nothing in the store, LOCATION never had
                        // bytes, and a message deleted for everyone must stay empty
                        // (`hydrateMedia` skips it for the same reason).
                        onLoadMedia = onLoadMedia?.takeIf {
                            message.mediaData == null &&
                                message.mediaType != null &&
                                message.mediaType != MediaType.LOCATION &&
                                !message.isDeletedForEveryone &&
                                // __DOC_STREAM_2026_11_11__ a document is read on SAVE, streamed.
                                !(message.mediaType == MediaType.DOCUMENT && onCopyMedia != null)
                        }
                    )
                    val mediaBytes = (mediaState as? BubbleMediaState.Ready)?.bytes
                    when (message.mediaType) {
                        MediaType.AUDIO -> {
                            VoiceMessageContent(
                                messageId = message.id,
                                audioData = mediaBytes,
                                duration = extractAudioDuration(displayContent),
                                isPlaying = isPlaying,
                                progress = playbackProgress,
                                isFromMe = isFromMe,
                                isSpeakerOn = isSpeakerOn,
                                playbackSpeed = playbackSpeed,
                                onPlayPause = {
                                    mediaBytes?.let { onPlayPause?.invoke(it) }
                                },
                                onToggleSpeaker = onToggleSpeaker,
                                onCycleSpeed = onCycleSpeed
                            )
                        }
                        MediaType.IMAGE -> {
                            ImageMessageContent(
                                messageId = message.id,
                                imageData = mediaBytes,
                                caption = displayContent.takeIf { !it.startsWith("[") },
                                state = mediaState,
                                onRetryLoad = { mediaState.retry() },
                                size = mediaSize,
                                hasHeader = forwardInfo != null || replyData != null,
                                captionColor = if (isFromMe) appearance.accent.ink(darkScheme)
                                    else MaterialTheme.colorScheme.onSurface,
                                linkColor = if (isFromMe) appearance.accent.linkInk(darkScheme) else null,
                                onNaturalSize = { if (mediaNatural == null) mediaNatural = it }
                            )
                        }
                        MediaType.VIDEO -> {
                            VideoMessageContent(
                                messageId = message.id,
                                videoData = mediaBytes,
                                caption = displayContent.takeIf { !it.startsWith("[") },
                                state = mediaState,
                                onRetryLoad = { mediaState.retry() }
                            )
                        }
                        MediaType.DOCUMENT -> {
                            DocumentMessageContent(
                                filename = displayContent,
                                documentData = mediaBytes,
                                onStream = onCopyMedia?.let { copy -> { out -> copy(message.id, out) } }
                            )
                        }
                        MediaType.CONTACT -> {
                            // In-app shared contact card. `mediaData` is the SAME body an
                            // iPhone writes — `{publicKey, alias?, timestamp}` — decoded by
                            // the one codec both ends of this feature share
                            // (`ContactCardWire`, which also keeps the vCard fallback for a
                            // card forwarded out of the system share sheet).
                            //
                            // 🔧 FIX (2026-08-24): this used `Json.Default`
                            // (`ignoreUnknownKeys = false`), and iOS's `ContactCard`
                            // (`OSHI/MediaManager.swift:913-917`) carries a `timestamp`
                            // Android did not declare — so EVERY iPhone-shared contact threw
                            // `SerializationException` and rendered as a raw text bubble
                            // with no tap-to-chat.
                            val contactPayload = remember(mediaBytes) {
                                mediaBytes?.let { bytes ->
                                    com.oshi.messenger.data.model.ContactCardWire
                                        .decode(String(bytes, Charsets.UTF_8))
                                }
                            }
                            if (contactPayload != null) {
                                com.oshi.messenger.ui.components.ContactCardBubble(
                                    payload = contactPayload,
                                    onTap = {
                                        onContactCardTap?.invoke(
                                            contactPayload.publicKey,
                                            contactPayload.alias
                                        )
                                    }
                                )
                            } else if (mediaBytes != null && mediaBytes.isNotEmpty()) {
                                // __CONTACT_CARD_COMPAT_2026_09_27__ Bytes no parser
                                // understands are still a file: offer them as one
                                // (open / save) instead of a caption nobody can act on.
                                DocumentMessageContent(
                                    filename = "contact.vcf",
                                    documentData = mediaBytes
                                )
                            } else {
                                LinkifyText(
                                    text = displayContent,
                                    // __INK_FROM_THE_SAME_SOURCE_AS_THE_PAINT_2026_11_07__
                                    // The bubble is painted with `appearance.accent.brush`;
                                    // taking the ink from `colorScheme.onPrimary` meant one
                                    // pair with two owners. They agree today and nothing
                                    // keeps them agreeing. `onAccent` belongs to the accent
                                    // that is actually painted.
                                    color = if (isFromMe)
                                        appearance.accent.ink(isDarkScheme())
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        MediaType.LOCATION -> {
                            // Location with mediaType set but no parseable LocationMessage
                            // (fallback — normally handled by LocationMessageBubble above)
                            val fallbackLoc = remember(message.content) {
                                LocationMessage.fromMessageString(message.content)
                            }
                            if (fallbackLoc != null && locationManager != null) {
                                LocationMessageBubble(
                                    locationMessage = fallbackLoc,
                                    isFromCurrentUser = isFromMe,
                                    locationManager = locationManager,
                                    conversationId = conversationId,
                                    contactName = bubbleContactName ?: contact?.publicKey?.take(8),
                                    contactProfilePicture = contact?.profilePicture,
                                    onSendLocation = onSendLocation,
                                    onOpenInMap = onOpenInMap
                                )
                            } else {
                                // Final fallback: show "📍 Location shared" text
                                LinkifyText(
                                    text = displayContent,
                                    // __INK_FROM_THE_SAME_SOURCE_AS_THE_PAINT_2026_11_07__
                                    // The bubble is painted with `appearance.accent.brush`;
                                    // taking the ink from `colorScheme.onPrimary` meant one
                                    // pair with two owners. They agree today and nothing
                                    // keeps them agreeing. `onAccent` belongs to the accent
                                    // that is actually painted.
                                    color = if (isFromMe)
                                        appearance.accent.ink(isDarkScheme())
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> {
                            // Backward-compat: legacy iOS clients send voice notes as plain text
                            // with "[AUDIO]" / "MEDIA|||audio|||" prefix and the audio bytes in
                            // mediaData. Auto-route to VoiceMessageContent so they render as
                            // a player rather than a literal "🎤 Voice message" string.
                            val rawForCheck = message.editedContent ?: message.content
                            val looksLikeAudio = (
                                rawForCheck.startsWith("[AUDIO]") ||
                                rawForCheck.startsWith("MEDIA|||audio|||") ||
                                displayContent.startsWith("\uD83C\uDFA4") // 🎤
                            ) && mediaBytes != null
                            if (looksLikeAudio) {
                                VoiceMessageContent(
                                    messageId = message.id,
                                    audioData = mediaBytes,
                                    duration = extractAudioDuration(rawForCheck),
                                    isPlaying = isPlaying,
                                    progress = playbackProgress,
                                    isFromMe = isFromMe,
                                    isSpeakerOn = isSpeakerOn,
                                    playbackSpeed = playbackSpeed,
                                    onPlayPause = {
                                        mediaBytes?.let { onPlayPause?.invoke(it) }
                                    },
                                    onToggleSpeaker = onToggleSpeaker,
                                    onCycleSpeed = onCycleSpeed
                                )
                            } else {
                                // Regular text message with clickable URLs.
                                // __CHAT_POLISH_2026_09_24__ Incoming body text is
                                // `onSurface` (full-strength ink, iOS `.label`), not
                                // the secondary grey it used to be.
                                LinkifyText(
                                    text = displayContent,
                                    // __INK_FROM_THE_SAME_SOURCE_AS_THE_PAINT_2026_11_07__
                                    // The bubble is painted with `appearance.accent.brush`;
                                    // taking the ink from `colorScheme.onPrimary` meant one
                                    // pair with two owners. They agree today and nothing
                                    // keeps them agreeing. `onAccent` belongs to the accent
                                    // that is actually painted.
                                    color = if (isFromMe)
                                        appearance.accent.ink(isDarkScheme())
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (appearance.boldText) FontWeight.SemiBold else null,
                                    linkColor = if (isFromMe) appearance.accent.linkInk(isDarkScheme()) else null
                                )
                            }
                        }
                    }
                    }

                    // 📈 Ticker chips. Same chip as the group thread, and the same
                    // position iOS uses — under the text, above the timestamp
                    // (`ChatView.swift:4340`). Draws nothing at all when the message
                    // carries no `@TICKER`: `TickerChipsForText` bails on a cheap
                    // `hasTicker` before it allocates or touches the network.
                    if (TickerLinks.hasTicker(displayContent)) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TickerChipsForText(text = displayContent, messageId = message.id)
                    }

                    // __CASHU_2026_10_24__ eCash card, same position as the ticker
                    // chips. Bails on a cheap substring test before it parses, so a
                    // message without a token costs nothing as the list scrolls.
                    CashuTokenCardsForText(
                        text = displayContent,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    // Link preview (if URL detected)
                    detectedUrl?.let { url ->
                        Spacer(modifier = Modifier.height(8.dp))
                        LinkPreviewCard(url = url, autoFetch = isFromMe)  // __LEAK_FIX_2026_09_25__ incoming: tap to load
                    }

                }
            }

            // __CHAT_POLISH_2026_09_24__ The metadata row sits UNDER the bubble, on the
            // wallpaper, exactly where iOS puts it (ChatView bubble footer):
            // [edited] time [transport chip] [ticks]. Every ink is an `Oshi.Chat` token
            // measured ≥ 4.5:1 against the worst wallpaper ground.
            //
            // __BUBBLE_FOOTER_FOLLOWS_THE_RUN_2026_10_28__ (N10) It used to be drawn on
            // EVERY bubble, and the note here explained why: "Android has no
            // swipe-to-reveal timestamp, so hiding interior ones (as iOS does) would lose
            // it." That was correct, and the answer was to add the reveal rather than keep
            // the repetition — which is what the reporter asked for in the same breath
            // ("afficher lors de l'appui l'heure et le 'envoyé via …'"). Tap an interior
            // bubble and its row appears; see the `onClick` above.
            if (alwaysShowsFooter || metaRevealed) {
                BubbleMetaRow(message = message, isFromMe = isFromMe, dark = darkScheme)
            }
        }

        // Reactions display (not on deleted messages)
        if (!message.isDeletedForEveryone && reactionSummary != null && reactionSummary.totalCount > 0) {
            val peerName = bubbleContactName ?: contact?.alias?.takeIf { it.isNotBlank() }
                ?: contact?.publicKey?.take(8) ?: ""
            ReactionsDisplay(
                reactionSummary = reactionSummary,
                isFromMe = isFromMe,
                myPublicKey = myPublicKey,
                // Only two people can react in a 1:1: anyone not me is the contact.
                nameFor = { peerName },
                onClick = { emoji -> onReactionClick?.invoke(emoji) }
            )
        }

        // __LORA_AIRTIME_HINT_2026_11_18__ The OSHI Mesh radio accepted this message but has held it
        // for over a minute: the hourly duty-cycle budget (EU_868) is spent and it goes out when the
        // budget refills. Said here because the phone only ever hears QUEUED, which looked like a hang.
        if (isFromMe && !message.isFailed && !message.isRead) {
            val awaitingAirtime by MeshtasticManager.awaitingAirtime.collectAsState()
            if (message.id in awaitingAirtime) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.lora_waiting_airtime),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }

        // __COVER_FAIL_CLOSED_2026_11_18__ Cover mode refused the legacy relay for this
        // message; it is waiting for the disguised path (CoverOutbox). Say so instead
        // of an unexplained clock.
        val awaitingCover by com.oshi.messenger.service.CoverOutbox.awaiting.collectAsState()
        if (isFromMe && !message.isFailed && !message.isDelivered && message.id in awaitingCover) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.cover_waiting_bubble),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        // __RESTRICTED_NETWORK_HINT_2026_11_18__ the sender is on a restricted network
        // (RN-HINT, end-to-end encrypted, docs/RESTRICTED_NETWORK_HINT.md).
        if (!isFromMe && com.oshi.messenger.ui.components.rememberRestrictedNetworkFlag(message.id)) {
            Spacer(modifier = Modifier.height(3.dp))
            com.oshi.messenger.ui.components.RestrictedNetworkChip()
        }

        // Failed message retry indicator (6B.7)
        if (message.isFailed && isFromMe) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onRetry?.invoke() }
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                // __MESHCORE_INTEROP_2026_11_25__ A MeshCore DM unanswered 4 times: say "out of range?".
                val meshCoreFailed = com.oshi.messenger.service.lora.MeshCoreThread.isMeshCoreKey(message.recipientPublicKey)
                val radioUp = meshCoreFailed && LocalMeshCoreBubbles.current.radioUp
                Text(
                    text = stringResource(if (meshCoreFailed) MeshCoreUi.failedText(message, radioUp) else R.string.chat_not_delivered),
                    color = Color.Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
    }
}

// ============================================================================
// CALL SUMMARY BUBBLE (matches iOS CallSummaryBubble)
// ============================================================================

@Composable
internal fun CallSummaryBubble(
    content: String,
    timestamp: Long,
    isFromMe: Boolean
) {
    val context = LocalContext.current
    val isMissed = OSHIControlPayload.matchedPrefix(content) == OSHIControlPayload.MISSED_CALL

    // Direction icon + localized label + duration, built by the ONE catalog
    // (OSHIControlPayload.callSummary, ported from OSHIControlPayload.swift:321-392).
    // The wire carries the localization KEY (`📞↗️call.outgoing|1:23`), not
    // translated text, so each device renders the call in its own language — and
    // the catalog additionally understands the `{"direction":…,"duration":…}` JSON
    // shape other clients send, which this bubble's hand-rolled parser did not.
    val localizedContent = remember(content) {
        OSHIControlPayload.previewText(content, OSHIControlPayload.labels(context))
    }

    // Format timestamp
    val timeText = remember(timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(timestamp))
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier.widthIn(max = 280.dp)
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Phone icon circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isMissed) Color.Red.copy(alpha = 0.15f)
                        else OSHIGreen.copy(alpha = 0.15f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMissed) Icons.Default.PhoneMissed
                    else Icons.Default.Phone,
                    contentDescription = null,
                    tint = if (isMissed) Color.Red else OSHIGreen,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Text(
                    text = localizedContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================================
// CHECK-IN SAFETY BUBBLE (6A.7 - matches iOS Check-In messages)
// ============================================================================

@Composable
internal fun CheckInBubble(
    content: String,
    timestamp: Long
) {
    val context = LocalContext.current

    // 🔧 Parse with the REAL model, not an ad-hoc JSONObject.
    //
    // This used to read `type` / `duration` / `startTime` — two of which do not
    // exist on the wire at all. `CheckInMessage` (iOS CheckInMessage.swift:211-221,
    // Android CheckInMessage.kt:256) carries
    // `type / sessionId / destinationAddress / transportMode / estimatedArrival /
    //  currentLatitude / currentLongitude / emergencyData / timestamp / senderName`
    // and its types are `started|update|arrived|extended|cancelled|warning|
    // emergency` — NOT `started|completed|expired|cancelled`. So an iPhone
    // check-in would have rendered the generic "Check-In" even once this bubble
    // became reachable. `fromMessageString` also performs the Apple-epoch → Unix
    // millis conversion (the 1.4.6 fix), which is why the ETA below is correct.
    val checkIn = remember(content) { CheckInMessage.fromMessageString(content) }

    // Colour by type, mirroring CheckInView.swift:653-700 `iconColor`.
    val accent: Color = when (checkIn?.type) {
        CheckInMessageType.arrived -> OSHIGreen
        CheckInMessageType.warning, CheckInMessageType.emergency -> OSHIRed
        CheckInMessageType.started, CheckInMessageType.update, CheckInMessageType.extended -> OSHIOrange
        CheckInMessageType.cancelled -> Color.Gray
        null -> Color.Gray
    }
    val title = checkIn?.let { context.getString(it.displayTitleRes) }
        ?: context.getString(R.string.checkin_title)

    val timeText = remember(timestamp) {
        val normalizedTs = if (timestamp in 1..1_000_000_000_000L) timestamp * 1000 else timestamp
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(normalizedTs))
    }
    val etaText = remember(checkIn?.estimatedArrival) {
        checkIn?.estimatedArrival?.takeIf { it > 0 }?.let {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = accent.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = stringResource(R.string.accessibility_check_in),
                    tint = accent,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = accent
                )

                // Destination (CheckInView.swift:675-685)
                checkIn?.destinationAddress?.takeIf { it.isNotBlank() }?.let { address ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = address,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Transport mode (CheckInView.swift:687-696)
                checkIn?.transportMode?.let { mode ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${mode.emoji} ${stringResource(mode.localizedNameRes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ETA (CheckInView.swift:698-707)
                etaText?.let { eta ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = eta,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Position (update / warning / emergency) → open it with the app
                // chosen in "Open locations in" (iOS CheckInView.swift openInMaps,
                // via MapOpener). Android had no way to see WHERE the contact was.
                val point = checkIn?.let { c ->
                    val lat = c.currentLatitude ?: c.emergencyData?.lastKnownLatitude
                    val lon = c.currentLongitude ?: c.emergencyData?.lastKnownLongitude
                    if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) lat to lon else null
                }
                if (point != null) {
                    val pointLabel = stringResource(R.string.checkin_open_map)
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = {
                        com.oshi.messenger.ui.components.MapOpener.open(
                            context, point.first, point.second, title
                        )
                    }) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = accent
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = pointLabel, fontSize = 13.sp, color = accent)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================================
// SCHEDULED CALL EVENT BUBBLE (6A.8 - matches iOS Scheduled Call events)
// ============================================================================

@Composable
internal fun ScheduledCallBubble(
    content: String,
    timestamp: Long,
    onJoinCall: (() -> Unit)? = null
) {
    val jsonStr = OSHIControlPayload.stripPrefix(content).trim()

    data class ScheduledCallData(
        val title: String,
        val scheduledTime: Long,
        val duration: Int,
        val status: String,
        val createdBy: String
    )

    val callData = remember(jsonStr) {
        try {
            val json = JSONObject(jsonStr)
            ScheduledCallData(
                title = json.optString("title", ""),
                // iOS (the only sender of 📅SCHEDULED_CALL📅) encodes scheduledTime as
                // Apple reference-date SECONDS (Codable Date), not Unix millis. Reading
                // it raw showed a 1970 date and broke "joinable". Normalise to Unix
                // millis, tolerating a Unix-millis sender too (same detector as
                // CheckInMessage): Apple-secs today ≈7.7e8, Unix-ms ≈1.7e12.
                scheduledTime = run {
                    val raw = json.optDouble("scheduledTime", 0.0)
                    when {
                        raw == 0.0 -> 0L
                        kotlin.math.abs(raw) > 1e11 -> raw.toLong()
                        else -> ((raw + 978307200.0) * 1000.0).toLong()
                    }
                },
                duration = json.optInt("duration", 30),
                status = json.optString("status", "pending"),
                // iOS ScheduledCallEvent carries BOTH `createdBy` (a public key)
                // and `createdByName` (ScheduledCallManager.swift:20-21). Showing
                // the key printed 64 hex chars under the title.
                createdBy = json.optString("createdByName", "")
                    .ifEmpty { json.optString("createdBy", "").take(8) }
            )
        } catch (e: Exception) { null }
    }

    if (callData == null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Event,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = content,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val statusColor = when (callData.status) {
        "confirmed" -> OSHIGreen
        "cancelled" -> OSHIRed
        else -> OSHIOrange
    }

    val formattedTime = remember(callData.scheduledTime) {
        if (callData.scheduledTime == 0L) "Time TBD"
        else SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
            .format(Date(callData.scheduledTime))
    }

    val isJoinable = remember(callData.status, callData.scheduledTime) {
        if (callData.status != "confirmed") false
        else {
            val now = System.currentTimeMillis()
            val diff = callData.scheduledTime - now
            diff in -1_800_000..300_000
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Top row: Event icon + title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Same F-14 recipe, no iOS twin: brand ink on a brand wash.
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = OSHIPurple.copy(alpha = 0.22f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Event,
                            contentDescription = stringResource(R.string.accessibility_scheduled_call),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = callData.title.ifEmpty { "Scheduled Call" },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (callData.createdBy.isNotEmpty()) {
                            Text(
                                text = "by ${callData.createdBy}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Date/time row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formattedTime,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Duration row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${callData.duration} minutes",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Status badge + Join button row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = callData.status.replaceFirstChar { it.uppercase() },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = statusColor
                        )
                    }

                    if (isJoinable) {
                        Button(
                            onClick = { onJoinCall?.invoke() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OSHIPurple
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(
                                horizontal = 16.dp,
                                vertical = 6.dp
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(R.string.chat_join_call), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// VOICE MESSAGE CONTENT
// ============================================================================

@Composable
private fun VoiceMessageContent(
    messageId: String,
    audioData: ByteArray?,
    duration: Float,
    isPlaying: Boolean,
    progress: Float,
    isFromMe: Boolean,
    isSpeakerOn: Boolean = true,
    playbackSpeed: Float = 1.0f,
    onPlayPause: () -> Unit,
    onToggleSpeaker: (() -> Unit)? = null,
    onCycleSpeed: (() -> Unit)? = null
) {
    // __VOICE_MINIPLAYER_2026_10_09__ While this bubble is on screen it IS the player,
    // so the floating pill stays hidden — it would only duplicate the controls below.
    // Leaving the conversation disposes this and the pill takes over, which is the
    // whole point: `AudioPlayerManager` is a singleton and the audio keeps going.
    //
    // Reached through the same Hilt entry point the group bubble uses rather than
    // threaded down as a parameter: it is the identical @Singleton instance either
    // way, and both inline-audio branches below would otherwise need the plumbing.
    val miniPlayerOwner = LocalContext.current.applicationContext
    val sharedPlayer = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            miniPlayerOwner,
            GroupVoiceEntryPoint::class.java
        ).audioPlayer()
    }
    androidx.compose.runtime.DisposableEffect(messageId) {
        sharedPlayer.ownerAppeared(messageId)
        onDispose { sharedPlayer.ownerDisappeared(messageId) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play/Pause button
        // The play/pause disc on a RECEIVED voice note was the textbook shape:
        // `primary.copy(alpha = 0.2f)` circle with a `primary` glyph inside it.
        // The outgoing half is fine — white on white-at-20% over the accent
        // gradient — so only the incoming ink moves.
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isFromMe) LocalBubbleAppearance.current.accent.ink(isDarkScheme()).copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = Oshi.ContainerTintAlpha)
                )
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.chat_pause) else stringResource(R.string.chat_play),
                tint = if (isFromMe) LocalBubbleAppearance.current.accent.ink(isDarkScheme()) else brandInk()
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            // AUDIT M-13. Two halves, both here:
            //   * iOS draws a real waveform (`AudioMessageView.swift:83-90` →
            //     `AudioWaveformView`); Android drew a flat bar.
            //   * the duration was scraped out of the message BODY, and an iPhone voice
            //     note has an EMPTY body (the V2 file path carries no caption), so every
            //     inbound iOS note showed 0:00. The container is now asked directly.
            val analysis = com.oshi.messenger.ui.media.rememberVoiceNoteAnalysis(
                messageId = messageId,
                data = audioData,
                storedDurationMs = null,
                legacyBody = null
            )
            val activeColor = if (isFromMe) LocalBubbleAppearance.current.accent.ink(isDarkScheme())
                              else MaterialTheme.colorScheme.primary
            if (analysis != null) {
                com.oshi.messenger.ui.media.VoiceWaveform(
                    levels = analysis.waveform,
                    progress = progress,
                    color = activeColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    // __VOICE_SCRUB_2026_10_26__ Reported on iOS and shared here:
                    // the waveform looked like a scrubber and was not one, so
                    // there was no way to replay the middle of a long note.
                    onSeek = { p, startIfIdle ->
                        audioData?.let { bytes ->
                            val loaded = sharedPlayer.currentMessageId.value == messageId
                            if (!loaded && startIfIdle) {
                                // Start through the caller's own play path so the
                                // floating player still names the sender, and arm
                                // the position for `onPrepared` — seeking now
                                // would hit a player that is still PREPARING.
                                sharedPlayer.armPendingSeek(messageId, p)
                                onPlayPause()
                            } else {
                                sharedPlayer.scrubTo(messageId, bytes, null, p, startIfIdle)
                            }
                        }
                    }
                )
            } else {
                // Fallback bar for the frame or two before the analysis lands.
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = activeColor,
                    trackColor = activeColor.copy(alpha = 0.3f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Duration: probed length first, the caller's scraped value only as a fallback.
            val shownDuration = analysis?.durationSeconds?.takeIf { it > 0f } ?: duration
            Text(
                text = formatDuration((shownDuration * 1000).toLong()),
                fontSize = 12.sp,
                color = if (isFromMe) LocalBubbleAppearance.current.accent.ink(isDarkScheme()).copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        // Speaker/Earpiece toggle (visible during playback)
        if (isPlaying || progress > 0f) {
            onToggleSpeaker?.let { toggle ->
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = toggle,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.HearingDisabled,
                        contentDescription = if (isSpeakerOn) stringResource(R.string.speaker_on) else stringResource(R.string.speaker_off),
                        modifier = Modifier.size(18.dp),
                        tint = if (isFromMe) LocalBubbleAppearance.current.accent.ink(isDarkScheme()).copy(alpha = 0.7f)
                               else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Playback-speed toggle: cycles 1x → 1.5x → 2x. Always visible so the
        // user can set speed before starting playback.
        onCycleSpeed?.let { cycle ->
            Spacer(modifier = Modifier.width(4.dp))
            val label = if (playbackSpeed % 1f == 0f) "${playbackSpeed.toInt()}x"
                        else "${playbackSpeed}x"
            TextButton(
                onClick = cycle,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFromMe) LocalBubbleAppearance.current.accent.ink(isDarkScheme())
                            else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ============================================================================
// MEDIA MESSAGE CONTENT
// ============================================================================

// ============================================================================
// VIEW-ONCE MEDIA (receive side) — mirrors MediaMessageView.swift:28-46, :168-240
// ============================================================================

/**
 * Where "this view-once message has been opened" lives.
 *
 * The AUTHORITATIVE record is now `Message.viewOnceOpened` / `viewOnceOpenedAt`
 * (Message.kt:33-34), written — together with `mediaData = NULL` in the same
 * statement — by `MessageDao.markViewOnceOpened` via
 * `MessageRepository.markViewOnceOpened`, which also drops the decrypted RAM copy
 * and the file-backed blob (iOS `MessageManager.swift:6978-7009`).
 *
 * This SharedPreferences set is kept as the UI latch in front of it: the Room write
 * is asynchronous, so the placeholder must flip on the same frame as the dismiss and
 * must still read "opened" if the process dies before the Flow re-emits. It is no
 * longer load-bearing for privacy — the bytes are gone either way.
 */
private object ViewOnceOpenedStore {
    private const val PREFS = "oshi_view_once_opened"

    fun isOpened(context: Context, messageId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(messageId, false)

    fun markOpened(context: Context, messageId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(messageId, true)
            .putLong("$messageId#at", System.currentTimeMillis())
            .apply()
    }
}

@Composable
private fun ViewOnceMediaContent(
    message: Message,
    isFromMe: Boolean,
    onConsumed: ((String) -> Unit)? = null,
    audioPlayer: AudioPlayerManager? = null,
    onLoadMedia: (suspend (String) -> ByteArray?)? = null
) {
    val context = LocalContext.current
    // Room's flag first (once a repository writes it), then the local store.
    var opened by remember(message.id) {
        mutableStateOf(message.viewOnceOpened || ViewOnceOpenedStore.isOpened(context, message.id))
    }
    var showFullScreen by remember(message.id) { mutableStateOf(false) }

    val isVideo = message.mediaType == MediaType.VIDEO
    // __CHAT_OPEN_PERF_2026_11_14__ The thread's rows no longer carry attachments over
    // 64 KB, so an unopened view-once reads its own bytes — and ONLY while it can still
    // be opened, and never through `BubbleBytesCache`: spent bytes must not outlive the
    // row's own destruction in a UI cache.
    val canStillOpen = !isFromMe && !opened && !message.isDeletedForEveryone
    val mediaDataState = produceState(message.mediaData, message.id, canStillOpen) {
        if (value == null && canStillOpen && onLoadMedia != null) {
            value = try {
                onLoadMedia(message.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val mediaData = mediaDataState.value

    // __VOICE_PREVIEW_2026_09_29__ Voice: a voice-sized pill, then a one-shot player.
    if (message.mediaType == MediaType.AUDIO) {
        com.oshi.messenger.ui.components.ViewOnceVoicePill(
            isFromMe = isFromMe,
            opened = opened,
            onTap = { if (mediaData != null && audioPlayer != null) showFullScreen = true }
        )
        if (showFullScreen && mediaData != null && audioPlayer != null && !isFromMe && !opened) {
            com.oshi.messenger.ui.components.ViewOnceVoiceDialog(
                messageId = message.id,
                audio = mediaData,
                player = audioPlayer,
                onConsumed = {
                    showFullScreen = false
                    ViewOnceOpenedStore.markOpened(context, message.id)
                    onConsumed?.invoke(message.id)
                    opened = true
                }
            )
        }
        return
    }

    when {
        // SENDER: sees the picture plus a "View Once" badge
        // (MediaMessageView.swift:33-35, :122-165).
        isFromMe -> {
            Column {
                if (isVideo) {
                    VideoMessageContent(messageId = message.id, videoData = mediaData, caption = null)
                } else {
                    ImageMessageContent(messageId = message.id, imageData = mediaData, caption = null)
                }
                Spacer(modifier = Modifier.height(4.dp))
                ViewOnceBadge(text = stringResource(R.string.media_view_once))
            }
        }

        // RECEIVER, already consumed: a permanent placeholder, never the media
        // again (MediaMessageView.swift:38-41, :170-182).
        opened -> {
            Row(
                modifier = Modifier
                    .background(
                        color = Color.Gray.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(15.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.media_view_once_opened),
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        }

        // RECEIVER, not yet consumed: tap-to-view placeholder.
        else -> {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(15.dp))
                    // Deliberately a gradient, NOT the blurred bitmap iOS uses
                    // (MediaMessageView.swift:191-199): Compose's `Modifier.blur`
                    // is a NO-OP below API 31, which would render the photo in
                    // full on every older device — the exact leak this screen is
                    // meant to close. iOS falls back to the same gradient when it
                    // cannot decode (swift:200-210).
                    .background(
                        Brush.linearGradient(
                            listOf(
                                OSHIOrange.copy(alpha = 0.35f),
                                OSHIPurple.copy(alpha = 0.35f)
                            )
                        )
                    )
                    .bubbleClickable { showFullScreen = true },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(50.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.media_view_once_tap),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ViewOnceBadge(text = "1", light = true)
                }
            }
        }
    }

    // Consume on dismiss, exactly like iOS (MediaMessageView.swift:102-111):
    // the message is spent once the viewer closes, not when it opens, so a
    // mis-tap that closes instantly still burns it — same as the iPhone.
    if (showFullScreen && mediaData != null && !isFromMe) {
        val consume: () -> Unit = {
            showFullScreen = false
            ViewOnceOpenedStore.markOpened(context, message.id)
            // Durable half: persists the flag and DESTROYS the bytes (row, RAM cache
            // and file store). iOS `MessageManager.swift:6978-7009`.
            onConsumed?.invoke(message.id)
            opened = true
        }
        if (isVideo) {
            VideoPlayerDialog(messageId = message.id, videoData = mediaData, onDismiss = consume)
        } else {
            FullScreenImageViewer(messageId = message.id, imageData = mediaData, onDismiss = consume)
        }
    }
}

@Composable
private fun ViewOnceBadge(text: String, light: Boolean = false) {
    Row(
        modifier = Modifier
            .background(
                color = if (light) Color.Black.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = if (light) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (light) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * (N17) The grid a [ChatThreadItem.PhotoStack] renders as. Up to 4 thumbnails
 * in a 2-column grid; a 5th-or-later photo collapses into a "+N" badge on
 * the last visible cell rather than growing the grid without bound. Each
 * thumbnail reuses [rememberBubbleMedia] (the same lazy-hydration a lone
 * photo bubble already uses) and [com.oshi.messenger.ui.media.BubbleAsyncImage]
 * (the same decode-and-cache a lone photo bubble already uses) — nothing
 * about how a photo's bytes get on screen changes here, only how many of
 * them share one row. Tapping any thumbnail opens the exact same
 * [FullScreenImageViewer] a lone photo bubble opens, for that one message.
 */
@Composable
private fun PhotoStackGrid(
    messages: List<Message>,
    onLoadMedia: suspend (String) -> ByteArray?
) {
    var fullScreenIndex by remember(messages.first().id) { mutableStateOf<Int?>(null) }
    val maxShown = 4
    val shown = messages.take(maxShown)
    val overflow = messages.size - maxShown

    Column(
        modifier = Modifier
            .widthIn(max = 250.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        shown.chunked(2).forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIndex, message ->
                    val indexInShown = rowIndex * 2 + colIndex
                    val media = rememberBubbleMedia(message.id, message.mediaData, onLoadMedia)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .bubbleClickable { fullScreenIndex = indexInShown }
                    ) {
                        when (media) {
                            is BubbleMediaState.Ready -> com.oshi.messenger.ui.media.BubbleAsyncImage(
                                messageId = message.id,
                                data = media.bytes,
                                contentDescription = stringResource(R.string.chat_photo),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            else -> MediaPlaceholder(state = media, onRetry = {})
                        }
                        if (overflow > 0 && indexInShown == shown.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+$overflow",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fullScreenIndex?.let { idx ->
        val tapped = shown[idx]
        val tappedMedia = rememberBubbleMedia(tapped.id, tapped.mediaData, onLoadMedia)
        if (tappedMedia is BubbleMediaState.Ready) {
            FullScreenImageViewer(
                messageId = tapped.id,
                imageData = tappedMedia.bytes,
                onDismiss = { fullScreenIndex = null }
            )
        }
    }
}

@Composable
/**
 * AUDIT M-7. The old body was:
 *
 * ```kotlin
 * var bitmap by remember(data) { mutableStateOf<Bitmap?>(null) }
 * LaunchedEffect(data) { bitmap = … BitmapFactory.decodeByteArray(data, 0, data.size) }
 * ```
 *
 * — full resolution (~11 MB of ARGB_8888 for a 1920×1440 JPEG, into a 250 dp bubble, with
 * no `largeHeap`), no shared cache (re-decoded on every screen entry), and keyed on a
 * `ByteArray` whose `equals` is IDENTITY, so every Room emission — every delivery receipt,
 * every read flag — re-decoded every visible photo.
 *
 * iOS names this exact bug in `MediaAttachmentCache.swift:70-77` and fixes it with a
 * cache keyed `"<messageId>@<maxDimension>"`. So does this now — see [BubbleMedia].
 */
private fun ImageMessageContent(
    messageId: String,
    imageData: ByteArray?,
    caption: String?,
    state: BubbleMediaState = BubbleMediaState.Unavailable(),
    onRetryLoad: () -> Unit = {},
    /** __BUBBLE_MEDIA_2026_11_21__ display box; null = legacy layout (silhouette bubbles). */
    size: com.oshi.messenger.ui.media.MediaBubbleSizing.Size? = null,
    /** Something (forward badge, reply quote) sits above the picture inside the bubble. */
    hasHeader: Boolean = false,
    captionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    linkColor: Color? = null,
    onNaturalSize: (IntArray) -> Unit = {}
) {
    var showFullScreen by remember { mutableStateOf(false) }
    val hasCaption = !caption.isNullOrBlank()

    Column {
        // 📎 M-17: the budget's degradation is now VISIBLE. It used to render an
        // empty Column — a bubble with nothing in it and no way to tell whether the
        // photo was loading, gone, or never there.
        if (imageData == null) {
            if (size != null) {
                // Same box the picture will take, so the thread does not jump.
                com.oshi.messenger.ui.media.MediaBox(
                    size = size,
                    modifier = Modifier
                        .bleed(start = 12.dp, end = 12.dp, top = if (hasHeader) 0.dp else 12.dp,
                            bottom = if (hasCaption) 0.dp else 12.dp)
                        .background(captionColor.copy(alpha = 0.08f))
                ) {
                    Box(Modifier.align(Alignment.Center).padding(12.dp)) {
                        MediaPlaceholder(state = state, onRetry = onRetryLoad)
                    }
                }
            } else MediaPlaceholder(state = state, onRetry = onRetryLoad)
        }
        imageData?.let { data ->
            if (size != null) {
                val natural = remember(messageId, data.size) {
                    com.oshi.messenger.ui.media.MediaNaturalSize.read(messageId, data)
                }
                SideEffect { natural?.let(onNaturalSize) }
                com.oshi.messenger.ui.media.MediaBox(
                    size = size,
                    modifier = Modifier
                        .bleed(start = 12.dp, end = 12.dp, top = if (hasHeader) 0.dp else 12.dp,
                            bottom = if (hasCaption) 0.dp else 12.dp)
                        .bubbleClickable { showFullScreen = true }
                ) {
                    com.oshi.messenger.ui.media.BubbleAsyncImage(
                        messageId = messageId,
                        data = data,
                        contentDescription = stringResource(R.string.chat_photo),
                        modifier = Modifier.fillMaxSize(),
                        // The box already has the picture's aspect: Crop only bites where
                        // MediaBubbleSizing clamped a very tall / very wide picture.
                        contentScale = ContentScale.Crop
                    )
                }
            } else com.oshi.messenger.ui.media.BubbleAsyncImage(
                messageId = messageId,
                data = data,
                contentDescription = stringResource(R.string.chat_photo),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .bubbleClickable { showFullScreen = true },
                contentScale = ContentScale.Fit
            )
        }

        caption?.let {
            if (it.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                LinkifyText(text = it, color = captionColor, linkColor = linkColor)
            }
        }
    }

    // Fullscreen image viewer
    if (showFullScreen && imageData != null) {
        FullScreenImageViewer(
            messageId = messageId,
            imageData = imageData,
            onDismiss = { showFullScreen = false }
        )
    }
}

@Composable
private fun FullScreenImageViewer(
    messageId: String,
    imageData: ByteArray,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ no Download from a protected chat.
    val advPrivacyHere = com.oshi.messenger.ui.components.LocalAdvancedChatPrivacy.current

    // 🔍 Pinch-to-zoom. The gallery-reached viewer has had it since
    // `MediaViewerScreen.kt:112`; the in-chat one — the one people actually reach,
    // by tapping a photo in the thread — was a plain `Image` with a tap-to-dismiss,
    // so a received screenshot could not be read.
    //
    // Three differences from the gallery version, all deliberate:
    //  • the floor is 1x, not 0.5x. Below 1x the photo shrinks away from the viewport
    //    for no reason and the pan clamp has nothing to clamp against.
    //  • the pan is CLAMPED. `MediaViewerScreen` adds raw pan with no bound, so a
    //    flick at 1x throws the photo off-screen and there is no way to get it back
    //    other than closing the viewer.
    //  • double-tap toggles fill (1x ⇄ 2.5x) and re-centres, the standard shortcut.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        // The zoomed photo has to be able to use the whole screen; the platform
        // default width would clip it to a dialog-sized window.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        // At scale s the content occupies s × the viewport, so it may
                        // be translated by at most (s − 1)/2 of the viewport in each
                        // direction before an edge crosses into view. At s == 1 that
                        // bound is zero, i.e. the image cannot be stranded off-screen.
                        val maxX = (viewport.width * (newScale - 1f) / 2f).coerceAtLeast(0f)
                        val maxY = (viewport.height * (newScale - 1f) / 2f).coerceAtLeast(0f)
                        scale = newScale
                        offset = Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY)
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset.Zero
                            }
                        },
                        // Tap-to-dismiss only while un-zoomed: closing the viewer on
                        // every tap made zooming pointless, since letting go of a
                        // one-finger inspection closed the photo.
                        onTap = { if (scale <= 1f) onDismiss() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Same cache, a second rung: iOS decodes the full-screen copy at
            // `maxDimension: 1920` (`MediaManager.swift:378`) and keys it separately from
            // the bubble's, which is why the key carries the dimension.
            com.oshi.messenger.ui.media.BubbleAsyncImage(
                messageId = messageId,
                data = imageData,
                contentDescription = stringResource(R.string.chat_photo),
                maxDimensionPx = com.oshi.messenger.ui.media.FULLSCREEN_MAX_DIMENSION_PX,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Bottom action row
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Share button
                IconButton(
                    onClick = { shareImage(context, imageData) }
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.accessibility_share),
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                // Save button
                if (!advPrivacyHere) IconButton(
                    onClick = { saveImageToDownloads(context, imageData) }
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.chat_download),
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun shareImage(context: Context, imageData: ByteArray) {
    // 🛡 __STRICT_MODE_2026_11_22__ nothing decrypted leaves OSHI while strict mode is on.
    if (!com.oshi.messenger.security.StrictModeGate.mediaExportAllowed) {
        Toast.makeText(context, context.getString(R.string.strict_blocked_toast), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val filename = "OSHI_${System.currentTimeMillis()}.jpg"
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Dedicated share dir, purged at the next share
        // and at the next launch (the receiving app reads it after we return).
        val file = com.oshi.messenger.data.local.atrest.PlaintextScratch.newShareFile(context.cacheDir, filename)
        java.io.FileOutputStream(file).use { it.write(imageData) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.accessibility_share)))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.chat_share_image_failed), Toast.LENGTH_SHORT).show()
    }
}

private fun saveImageToDownloads(context: Context, imageData: ByteArray) {
    if (!com.oshi.messenger.security.StrictModeGate.mediaExportAllowed) {
        Toast.makeText(context, context.getString(R.string.strict_blocked_toast), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val filename = "OSHI_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OSHI")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os -> os.write(imageData) }
                Toast.makeText(context, context.getString(R.string.document_saved), Toast.LENGTH_SHORT).show()
            }
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val oshiDir = java.io.File(picturesDir, "OSHI").apply { mkdirs() }
            val file = java.io.File(oshiDir, filename)
            java.io.FileOutputStream(file).use { it.write(imageData) }
            Toast.makeText(context, context.getString(R.string.document_saved), Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.document_save_failed), Toast.LENGTH_SHORT).show()
    }
}

/**
 * AUDIT M-8. The poster frame used to be built inside `remember { }` — i.e. **on the
 * composition (main) thread** — by writing the entire video to
 * `cacheDir/thumb_${'$'}{data.hashCode()}.mp4` and parsing it back. Two defects:
 * a multi-megabyte `FileOutputStream.write` plus a container parse blocking the frame,
 * and `ByteArray.hashCode()` being the IDENTITY hash, so every new array instance minted
 * a NEW file name and wrote yet another full copy that nothing ever deleted.
 *
 * Now: no temp file at all (the retriever reads from RAM), off the main thread, cached by
 * message id. `VideoThumbnails.sweepLegacyThumbnails` deletes what the old code leaked.
 */
@Composable
private fun VideoMessageContent(
    messageId: String,
    videoData: ByteArray?,
    caption: String?,
    state: BubbleMediaState = BubbleMediaState.Unavailable(),
    onRetryLoad: () -> Unit = {}
) {
    var showVideoPlayer by remember { mutableStateOf(false) }

    val thumbnail by com.oshi.messenger.ui.media.rememberVideoThumbnail(messageId, videoData)

    // 📎 M-17. Without bytes there is nothing to play, and a play button that does
    // nothing is worse than saying so.
    if (videoData == null) {
        Column {
            MediaPlaceholder(state = state, onRetry = onRetryLoad)
            caption?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.8f))
                .bubbleClickable { showVideoPlayer = true },
            contentAlignment = Alignment.Center
        ) {
            // Show thumbnail if extracted
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.chat_play_video),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Play button overlay
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.chat_play_video),
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
            }
        }

        caption?.let {
            if (it.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Fullscreen video player dialog
    if (showVideoPlayer && videoData != null) {
        VideoPlayerDialog(
            messageId = messageId,
            videoData = videoData,
            onDismiss = { showVideoPlayer = false }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerDialog(
    messageId: String,
    videoData: ByteArray,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // ExoPlayer needs a file. The name used to be `playback_${'$'}{videoData.hashCode()}.mp4`
    // and `ByteArray.hashCode()` is the IDENTITY hash — a fresh array from Room, or a new
    // process, produced a NEW name, `exists()` never hit, and another full copy of the
    // video was written and never deleted. Same defect as the thumbnail path (audit M-8).
    // Keyed on the message id it is written once and reused; `sweepPlaybackTempFiles`
    // clears what the old code left behind.
    val videoFile = remember(messageId, videoData.size) {
        val file = java.io.File(context.cacheDir, com.oshi.messenger.ui.media.VideoThumbnails.playbackTempName(messageId))
        if (!file.exists() || file.length() != videoData.size.toLong()) {
            java.io.FileOutputStream(file).use { it.write(videoData) }
        }
        file
    }

    // Create ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
            // Plaintext copy of the video: gone as soon as the player is (at-rest
            // encryption, 2026-09-22). PlaintextScratch.sweepAtStartup catches a crash.
            runCatching { videoFile.delete() }
        }
    }

    Dialog(onDismissRequest = {
        exoPlayer.release()
        onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            Box {
                // ExoPlayer view
                AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Close button
                IconButton(
                    onClick = {
                        exoPlayer.release()
                        onDismiss()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.video_player_close),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentMessageContent(
    filename: String,
    documentData: ByteArray? = null,
    /** __DOC_STREAM_2026_11_11__ Writes the file into the stream; used when no bytes are in memory. */
    onStream: (suspend (java.io.OutputStream) -> Boolean)? = null
) {
    val context = LocalContext.current
    val saveScope = rememberCoroutineScope()
    // 🔒 The name is PEER-CONTROLLED: on the V2 blob path it is `inbound.filename`
    // written straight onto `content` (`MessageRepository.dispatchV2InboundFile`),
    // and `saveDocumentToDownloads` hands it to `java.io.File(oshiDir, filename)`
    // on API < 29. Sanitize once, here, so both the label and the save use the same
    // traversal-free leaf. Sender-side sanitizing (`AttachmentFileName.forWire`)
    // covers what WE emit; this covers what anyone else does.
    val displayName = remember(filename) {
        com.oshi.messenger.util.AttachmentFileName
            .sanitize(filename.removePrefix("[DOCUMENT]").trim())
            ?: "document"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .bubbleClickable {
                val data = documentData
                val writer: (suspend (java.io.OutputStream) -> Boolean)? = when {
                    data != null -> { out -> out.write(data); true }
                    else -> onStream
                }
                if (writer != null) {
                    saveScope.launch { saveDocumentToDownloads(context, displayName, writer) }
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    getFileTypeIcon(displayName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.chat_document_title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            Icons.Default.Download,
            contentDescription = stringResource(R.string.chat_download),
            tint = brandInk()
        )
    }
}

private fun getFileTypeIcon(filename: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> Icons.Default.PictureAsPdf
        "jpg", "jpeg", "png", "gif", "webp" -> Icons.Default.Image
        "mp4", "mov", "avi", "mkv" -> Icons.Default.VideoFile
        "mp3", "m4a", "wav", "aac" -> Icons.Default.AudioFile
        "zip", "rar", "7z", "tar" -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}

/**
 * __DOC_STREAM_2026_11_11__ Writes on the IO dispatcher through [write] (a stream from
 * the attachment store, or in-memory bytes), so a 60 MB document is neither held whole
 * on the heap nor written on the main thread. A failed or empty copy removes the
 * half-written Downloads entry and says "failed".
 */
private suspend fun saveDocumentToDownloads(
    context: Context,
    filename: String,
    write: suspend (java.io.OutputStream) -> Boolean
) {
    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val mimeType = getMimeType(filename)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: Use MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OSHI")
            }
            val uri = try {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } catch (e: Exception) { null } ?: return@withContext false
            val written = try {
                context.contentResolver.openOutputStream(uri)?.use { os -> write(os) } ?: false
            } catch (e: Exception) { false }
            if (!written) runCatching { context.contentResolver.delete(uri, null, null) }
            written
        } else {
            // Legacy: Write to Downloads directly
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val oshiDir = java.io.File(downloadsDir, "OSHI").apply { mkdirs() }
            val file = java.io.File(oshiDir, filename)
            val written = try {
                java.io.FileOutputStream(file).use { write(it) }
            } catch (e: Exception) { false }
            if (!written) runCatching { file.delete() }
            written
        }
    }
    Toast.makeText(
        context,
        context.getString(if (ok) R.string.document_saved else R.string.document_save_failed),
        Toast.LENGTH_SHORT
    ).show()
}

private fun getMimeType(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        else -> "application/octet-stream"
    }
}

// ============================================================================
// REACTIONS
// ============================================================================

@Composable
private fun ReactionsDisplay(
    reactionSummary: ReactionSummary,
    isFromMe: Boolean,
    myPublicKey: String,
    nameFor: (String) -> String,
    onClick: (String) -> Unit
) {
    // __REACTION_WHO_SHEET_2026_11_16__ long-press a chip: who reacted with what.
    var showWho by remember { mutableStateOf(false) }
    if (showWho) {
        val you = stringResource(R.string.you)
        com.oshi.messenger.ui.components.ReactionWhoSheet(
            rows = com.oshi.messenger.ui.components.reactionWhoRows(
                reactionSummary.reactions, myPublicKey, you, nameFor
            ),
            onDismiss = { showWho = false },
        )
    }
    Row(
        modifier = Modifier
            .offset(y = (-6).dp, x = if (isFromMe) (-4).dp else 4.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reactionSummary.reactions.entries.sortedByDescending { it.value.size }.forEach { (emoji, senders) ->
            // R-5 · the count is always shown, including "1" (iOS draws
            // `emoji + count` unconditionally, MessageReactionManager.swift:710-718).
            // __REACTION_BAR_ONE_COMPONENT_2026_11_15__ same chip as the group thread.
            com.oshi.messenger.ui.components.ReactionChip(
                code = emoji,
                count = senders.size,
                isMine = reactionSummary.myReaction == emoji,
                onClick = { onClick(emoji) },
                onLongClick = { showWho = true },
            )
        }
    }
}

// ============================================================================
// MESSAGE ACTIONS DIALOG
// ============================================================================

@Composable
private fun MessageActionsDialog(
    message: Message,
    onDismiss: () -> Unit,
    onReactWith: (String) -> Unit,
    currentReaction: String?,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    /** Opt-in privacy switch: hides Forward and Copy. Hidden, not disabled —
     *  a greyed row advertises a capability the user turned off on purpose. */
    blockForwardCopy: Boolean = false,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onTranslate: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onSave: (() -> Unit)? = null,
    /** RP-3: null keeps the row hidden; non-null shows Pin/Unpin as iOS does. */
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null
) {
    // __EDIT_WINDOW_WAS_COSMETIC_2026_11_05__ Own message, text only. No clock.
    //
    // The 10-minute window that used to be here protected nothing. The RECEIVE
    // path checks that the sender owns the target message and ONLY that — no
    // timestamp — on every platform, so a contact on Desktop (`row.fromMe`,
    // no window) could already edit a three-day-old message and this device
    // would accept and apply it. The window restrained the Android user against
    // a rule nothing in the system enforced.
    //
    // It is also the wrong shape for this app: delivery can lag for hours over
    // store-and-forward relay, mesh or LoRa, so the window could expire before
    // the message had even ARRIVED — exactly when editing is most harmless.
    //
    // Removing it also removes a latent bug: this comparison used the RAW
    // timestamp while the footer next door normalises seconds-vs-millis, so a
    // seconds-stamped row hid Edit permanently.
    //
    // GROUPS keep their 10 minutes on both phones (`GROUP_EDIT_WINDOW_MS`,
    // `GroupViews.swift:5031`): that path has its own enforcement in
    // GroupManager, so loosening one side alone would desync them.
    val canEdit = message.isFromMe && message.mediaType == null

    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_message_actions)) },
        text = {
            Column {
                // 🔧 P0 FIX (device walkthrough #2, 2026-08-13): this iterated
                // `ReactionEmojis.ALL`, so the one-tap row rendered `ALL.take(6)`
                // = ✅ ❌ 😢 🖕 😘 👍 — i.e. the middle finger shipped as a default
                // one-tap reaction. iOS deliberately excludes it from the quick bar:
                //   OSHI/MessageReactionManager.swift:83
                //   static var quickReactions: [ReactionEmoji] {
                //       [.check, .cross, .sad, .thumbsUp, .kiss, .hundred]
                //   }
                // `ReactionEmojis.QUICK` (Reaction.kt:59) is that exact list and was
                // defined right beside ALL but never used. The full set stays
                // reachable through the expanded picker, as on iOS.
                // (The row used to scroll sideways; it now wraps, see ReactionPicker.)
                //
                // F-7: the full set is now actually REACHABLE. The comment above
                // has claimed "the full set stays reachable through the expanded
                // picker" since the quick row landed, and there was no expanded
                // picker: ReactionEmojis.ALL had zero UI call sites, so an Android
                // user could see an iPhone's heart or laugh and had no way to send
                // one back. The "+" chip opens it, one glance at a time, which is
                // the constraint iOS states at MessageReactionManager.swift:36-39.
                // __REACTION_BAR_ONE_COMPONENT_2026_11_15__ one picker for 1:1 and
                // groups (48dp cells, wraps instead of hiding "+" off the edge,
                // OSHI O first). See ui/components/ReactionBar.kt.
                com.oshi.messenger.ui.components.ReactionPicker(
                    currentReaction = currentReaction,
                    onPick = { emoji -> onReactWith(emoji); onDismiss() },
                )
                Divider(modifier = Modifier.padding(bottom = 4.dp))

                // Reply
                TextButton(
                    onClick = onReply,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Reply, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.message_reply))
                    }
                }

                // Edit (only for own text messages within 10 minutes)
                if (canEdit) {
                    TextButton(
                        onClick = onEdit,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.message_edit))
                        }
                    }
                }

                // Forward and Copy — both hidden by the one privacy switch.
                if (!blockForwardCopy) {
                    TextButton(
                        onClick = onForward,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Forward, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.message_forward))
                        }
                    }

                    TextButton(
                        onClick = onCopy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.copy))
                        }
                    }
                }

                // Save to device (only for media messages)
                if (onSave != null) {
                    TextButton(
                        onClick = { onDismiss(); onSave() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.save_to_gallery))
                        }
                    }
                }

                // Translate (6B.9) — RECEIVED messages only, matching iOS
                // (`OSHI/MessageReactionManager.swift:941`,
                //  `if let onTranslate = onTranslate, !isFromCurrentUser`).
                // Offering "translate" on text the user just typed themselves is noise.
                if (!message.isFromMe) {
                    TextButton(
                        onClick = onTranslate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.GTranslate, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.message_translate))
                        }
                    }
                }

                // 📌 Pin / Unpin — RP-3. iOS offers this on both own and received
                // bubbles (`OSHI/ChatView.swift:4605-4623`), between Translate and Delete.
                if (onTogglePin != null) {
                    TextButton(
                        // Pin BEFORE dismiss: the caller's onDismiss clears
                        // `selectedMessage`, which the pin lambda still reads.
                        onClick = { onTogglePin(); onDismiss() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(
                                    if (isPinned) R.string.unpin_message else R.string.pin_message
                                )
                            )
                        }
                    }
                }

                // Delete for Me (always available)
                TextButton(
                    onClick = onDeleteForMe,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.message_delete_for_me),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Delete for Everyone (only for own messages)
                if (message.isFromMe) {
                    TextButton(
                        onClick = onDeleteForEveryone,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.message_delete_for_everyone),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// ============================================================================
// REPLY PREVIEW BANNER
// ============================================================================

@Composable
private fun ReplyPreviewBanner(
    message: Message,
    contact: com.oshi.messenger.data.model.Contact?,
    messageActionsManager: MessageActionsManager,
    onDismiss: () -> Unit
) {
    // F-11: the quoted line goes through the CATALOG, not MessageSanitizer.
    // Replying to a call summary used to show the English literal "Call" instead
    // of the localized "Outgoing call - 1:23" - a real bug, not styling.
    val context = LocalContext.current
    val controlLabels = remember(context) { OSHIControlPayload.labels(context) }
    val previewContent = remember(message.content, controlLabels) {
        val replyData = if (messageActionsManager.isReply(message.content)) {
            messageActionsManager.parseReply(message.content)
        } else null
        val raw = replyData?.replyContent ?: message.content
        com.oshi.messenger.util.MessageFormatting.strip(OSHIControlPayload.previewText(raw, controlLabels))
    }

    val senderName = if (message.isFromMe) {
        stringResource(R.string.chat_you)
    } else {
        // __SHARED_NICKNAME_2026_09_22__ alias > shared nickname > fallback.
        contact?.let { com.oshi.messenger.data.model.PeerNicknameStore.displayName(it.alias, it.publicKey) }
            ?: message.senderPublicKey.take(8) + "..."
    }

    // F-11 (iOS 9b56ff7, MessageActionsManager.swift:303-402): the banner is the
    // COMPOSER'S HAT, not a panel floating above it. It shares the input row's
    // width, rounds only its TOP corners, fills with a 9% brand wash, and is
    // separated by a hairline rather than a border or an elevation step.
    // Same F-14 recipe as the pinned banner: brand ink on a 9% brand wash.
    val hatInk = brandInk()
    Surface(
        color = OSHIPurple.copy(alpha = 0.09f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
      Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Purple left accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(hatInk)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Reply info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.message_replying_to, senderName),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = hatInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = previewContent.take(100),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Close/cancel button. iOS draws an 11pt glyph inside a 44pt hit box
            // (MessageActionsManager.swift:370-380); 44 dp is also Android's own
            // minimum touch target, so the small glyph costs no reachability.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        // Hairline, not a border (MessageActionsManager.swift:395-397).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        )
      }
    }
}

// ============================================================================
// REPORT CONTACT DIALOG
// ============================================================================

/**
 * Reason picker plus optional free text, matching iOS's report sheet
 * (ContactActionsView.swift:253+). Nothing from the conversation is attached:
 * the payload carries the reason, what the reporter typed, and the subject id.
 */
/** __REPORT_OUTCOME_2026_09_23__ The toast matches the real delivery state. */
internal fun reportOutcomeText(state: com.oshi.messenger.data.model.ReportDeliveryState): Int = when (state) {
    com.oshi.messenger.data.model.ReportDeliveryState.SENT -> R.string.report_sent_confirmation
    com.oshi.messenger.data.model.ReportDeliveryState.REJECTED -> R.string.report_rejected_confirmation
    else -> R.string.report_queued_confirmation
}

@Composable
internal fun ReportContactDialog(
    alreadyReported: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ReportReason, String) -> Unit,
    // __REPORT_OUTCOME_2026_09_23__ reused for groups (was contact-only).
    titleRes: Int = R.string.report_dialog_title,
    alreadyFiledRes: Int = R.string.report_already_filed,
) {
    var reason by remember { mutableStateOf(ReportReason.SPAM) }
    var details by remember { mutableStateOf("") }

    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.report_dialog_message),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (alreadyReported) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(alreadyFiledRes),
                        fontSize = 13.sp,
                        color = OSHIOrange
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                ReportReason.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { reason = option }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = reason == option, onClick = { reason = option })
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                when (option) {
                                    ReportReason.SPAM -> R.string.block_reason_spam
                                    ReportReason.HARASSMENT -> R.string.block_reason_harassment
                                    ReportReason.INAPPROPRIATE -> R.string.block_reason_inappropriate
                                    ReportReason.OTHER -> R.string.block_reason_other
                                }
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = details,
                        onValueChange = {
                            // iOS caps the free text at 2000 characters.
                            if (it.length <= ReportWireFormat.MAX_DETAIL_LENGTH) details = it
                        },
                        label = { Text(stringResource(R.string.report_details_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(reason, details) }) {
                Text(stringResource(R.string.report_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ============================================================================
// FORWARD CONTACT PICKER DIALOG
// ============================================================================

@Composable
private fun ForwardContactPickerDialog(
    conversations: List<com.oshi.messenger.data.model.Conversation>,
    onSelectContact: (String) -> Unit,
    onDismiss: () -> Unit
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_forward_to)) },
        text = {
            if (conversations.isEmpty()) {
                Text(
                    stringResource(R.string.chat_no_conversations),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(conversations) { conversation ->
                        TextButton(
                            onClick = { onSelectContact(conversation.participantPublicKey) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was
                                // one flat `primaryContainer` for every contact.
                                com.oshi.messenger.ui.theme.ContactAvatar(
                                    publicKey = conversation.participantPublicKey,
                                    name = conversation.participantAlias,
                                    size = 40.dp,
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = conversation.participantAlias.ifEmpty {
                                            "${conversation.participantPublicKey.take(8)}..."
                                        },
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / 1000) / 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * The legacy `"[AUDIO] 0:05"` body convention, and nothing else.
 *
 * This used to match `\d+:\d+` ANYWHERE in the body, so a photo caption reading
 * "call me at 3:30" turned into a 3½-minute voice note. The marker is now required
 * (`VoiceNoteAudio.resolveDurationSeconds`), and 0 means "ask the audio itself".
 */
private fun extractAudioDuration(content: String): Float =
    com.oshi.messenger.ui.media.VoiceNoteAudio.resolveDurationSeconds(null, content) ?: 0f

// ============================================================================
// MEDIA PREVIEW WITH CAPTION (before sending)
// ============================================================================

@Composable
private fun MediaPreviewDialog(
    items: List<PendingMedia>,
    onToggleViewOnce: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    /** __IMAGE_CROP_2026_11_02__ (N13) crop the item at this index, images only. */
    onCrop: (Int) -> Unit,
    /** __PHOTO_MARKUP_2026_11_08__ draw on the item at this index, images only. */
    onMarkup: (Int) -> Unit,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var caption by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    // Removing the last item shrinks the list under the cursor; clamp rather than
    // crash on an index the strip no longer has.
    val index = selectedIndex.coerceIn(0, items.lastIndex)
    val current = items[index]
    val imageData = if (current.isVideo) null else current.bytes
    val videoData = if (current.isVideo) current.bytes else null
    val isViewOnce = current.viewOnce

    Dialog(onDismissRequest = onDismiss) {
        // __OLED_GLASS_2026_11_16__ glass dialog under OLED black.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = com.oshi.messenger.ui.theme.glassDialogSurfaceColor(),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .glassDialogRim(RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    imageData?.let { data ->
                        // PERF: decode preview off Main thread.
                        var bitmap by remember(data) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(data) {
                            bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                try { BitmapFactory.decodeByteArray(data, 0, data.size) } catch (_: Exception) { null }
                            }
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.chat_photo),
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    videoData?.let {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = stringResource(R.string.chat_video),
                            modifier = Modifier.size(64.dp),
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    // __IMAGE_CROP_2026_11_02__ (N13) OPTIONAL, unlike the profile
                    // photo: a crop forced on every pick would add a step to the
                    // multi-send that nobody asked for. Images only — cropping a
                    // video means re-encoding it, which is a different feature.
                    if (imageData != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clickable { onCrop(index) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Crop,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.crop_edit), color = Color.White, fontSize = 12.sp)
                            }
                        }
                        // __PHOTO_MARKUP_2026_11_08__ Beside crop, same shape and
                        // the same rule: optional, images only. Circling the thing
                        // you mean is most of why people send a screenshot at all.
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .clickable { onMarkup(index) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Brush,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.markup_draw), color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    if (isViewOnce) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.media_view_once),
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 🖼️ M-14 — the per-item options strip. iOS
                // `MediaPickerView.swift:378-404`: one thumbnail per attachment,
                // each removable and each with its OWN view-once toggle. Drawn only
                // for a multi-pick, so a single pick looks exactly as it did.
                if (items.size > 1) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(items) { i, item ->
                            MediaStripThumbnail(
                                item = item,
                                isSelected = i == index,
                                onSelect = { selectedIndex = i },
                                onToggleViewOnce = { onToggleViewOnce(i) },
                                onRemove = { onRemove(i) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Caption input.
                //
                // Hidden on a multi-pick: iOS sends each attachment with an EMPTY
                // body (`ChatView.swift:2343`), so a caption typed here would be
                // typed and then silently thrown away — the exact failure shape this
                // codebase keeps producing. No field, nothing lost.
                if (items.size == 1) {
                    // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                    // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            value = caption,
                            onValueChange = { caption = it },
                            placeholder = { Text(stringResource(R.string.caption_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        // Brand #5616EC is ~2:1 on this dark dialog; the brand INK clears AA.
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandInk())
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = { onSend(caption) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_send))
                    }
                }
            }
        }
    }
}

/**
 * One thumbnail in the multi-pick strip: tap to preview it, the eye to arm/disarm
 * view-once on THIS item, the cross to drop it. iOS `MediaThumbnailWithViewOnce`
 * (`MediaPickerView.swift:864-907`).
 */
@Composable
private fun MediaStripThumbnail(
    item: PendingMedia,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleViewOnce: () -> Unit,
    onRemove: () -> Unit
) {
    Box(modifier = Modifier.size(72.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable { onSelect() },
            contentAlignment = Alignment.Center
        ) {
            if (item.isVideo) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = stringResource(R.string.chat_video),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(28.dp)
                )
            } else {
                // PERF: decode off Main, once per item, keyed on the bytes.
                var bitmap by remember(item.bytes) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(item.bytes) {
                    bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        try { BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size) } catch (_: Exception) { null }
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_photo),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        // Per-item view-once toggle
        Surface(
            color = if (item.viewOnce) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(2.dp)
                .size(22.dp)
                .clickable { onToggleViewOnce() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = stringResource(R.string.media_view_once),
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // Remove
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .clickable { onRemove() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

// ============================================================================
// DATE SECTION HEADERS
// ============================================================================

private fun formatDateHeader(timestamp: Long): String {
    val normalizedTs = if (timestamp in 1..1_000_000_000_000L) timestamp * 1000 else timestamp
    val messageDate = Calendar.getInstance().apply { timeInMillis = normalizedTs }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            messageDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
        messageDate.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
            messageDate.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        else -> SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(normalizedTs))
    }
}

@Composable
private fun DateSectionHeader(timestamp: Long) {
    val label = remember(timestamp) { formatDateHeader(timestamp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// DELIVERY METHOD BADGE (6B.4) - colored pill below timestamp for received msgs
// ============================================================================

internal enum class SystemMessageType {
    PROFILE_UPDATE,
    WALLPAPER_UPDATE,
    KEY_ROTATION,
    BLOCKED_NOTICE
}

/**
 * 🔧 P1 FIX (device walkthrough #2, 2026-08-13).
 *
 * Two defects, both visible in `t51-bubble.png`:
 *
 *  1. **The badge reported the wrong transport.** `R.string.delivery_badge_vps`
 *     is literally `"IPFS"` (`res/values/strings.xml:1013`), so a message logcat
 *     recorded as `📤 Message sent via VPS` was labelled **IPFS** on screen. The
 *     wording now comes from the iOS strings, which never say "IPFS" at all:
 *     `delivery_sent_vps = "Sent via Internet"` / `delivery_received_vps =
 *     "Via Internet"` (`OSHI/en.lproj/Localizable.strings:629,632`), and iOS maps
 *     its own `.ipfs` case to the *same* wording
 *     (`DeliveryMethodBadge.swift:86-87`). The existing keys are left untouched so
 *     no translated file has to change; the new ones live in
 *     `strings_device_fixes.xml`.
 *
 *  2. **A message that failed to send still advertised a transport.** iOS gives
 *     the badge a status axis (`DeliveryMethodBadge.swift:50-90`): `.failed`
 *     wins over the method and renders `delivery_status_failed` ("Failed"). It
 *     is reported here too, so a message that never left the device can no
 *     longer claim it went out over anything.
 *
 * Outgoing/incoming wording follows iOS's `isOutgoing` split.
 */
/**
 * The colour half of [DeliveryMethodBadge]'s mapping, for (N15)'s
 * swipe-reveal chip to show the same colour for the same message.
 * [DeliveryMethodBadge]'s own `when` was left untouched rather than
 * rewired to call this — it already shipped and was reviewed for N14 — so
 * this is a verified-matching mirror of it, not a single source of truth;
 * if one changes, check the other.
 */
private fun transportInkFor(deliveryMethod: DeliveryMethod, isFailed: Boolean, dark: Boolean): Color {
    if (isFailed) return Oshi.Chat.transportInk(Oshi.Chat.Transport.FAILED, dark)
    return when (deliveryMethod) {
        DeliveryMethod.VPS, DeliveryMethod.IPFS, DeliveryMethod.TOR ->
            Oshi.Chat.transportInk(Oshi.Chat.Transport.INTERNET, dark)
        DeliveryMethod.MESH -> Oshi.Chat.transportInk(Oshi.Chat.Transport.MESH, dark)
        DeliveryMethod.LORA -> Oshi.Chat.transportInk(Oshi.Chat.Transport.LORA, dark)
        DeliveryMethod.LORA_PLAINTEXT -> Oshi.Chat.transportInk(Oshi.Chat.Transport.PENDING, dark)
        DeliveryMethod.LOCAL -> Color.Transparent
    }
}

@Composable
private fun DeliveryMethodBadge(
    deliveryMethod: DeliveryMethod,
    isFromMe: Boolean,
    isFailed: Boolean = false
) {
    // A row generated on this device never travelled, so there is no transport
    // to name. iOS writes `deliveryMethod: nil` for exactly these rows and draws
    // nothing (WelcomeMessageManager.swift:117); LOCAL is that nil. A failed
    // LOCAL row cannot exist — nothing was attempted — but the check keeps the
    // status axis above this one, as iOS does.
    if (deliveryMethod == DeliveryMethod.LOCAL && !isFailed) return

    // Status beats method — a failed message has no transport to boast about.
    // iOS: DeliveryMethodBadge.swift:71-75.
    // __CHAT_POLISH_2026_09_24__ Colours are now the `Oshi.Chat` transport
    // TOKENS (a chip on the wallpaper: ink on a 12 % wash of itself, iOS
    // `DeliveryBadgeMinimal`), not white 6 sp text on a saturated fill — white
    // on the old green was 2.3:1 and 6 sp is below any readable size.
    val dark = isDarkScheme()
    val (label, bgColor) = if (isFailed) {
        Pair(stringResource(R.string.delivery_status_failed), Oshi.Chat.transportInk(Oshi.Chat.Transport.FAILED, dark))
    } else when (deliveryMethod) {
        // VPS and IPFS are both "the Internet" from the user's point of view,
        // exactly as on iOS (DeliveryMethodBadge.swift:78-87).
        DeliveryMethod.VPS, DeliveryMethod.IPFS -> Pair(
            stringResource(
                if (isFromMe) R.string.delivery_sent_internet
                else R.string.delivery_received_internet
            ),
            Oshi.Chat.transportInk(Oshi.Chat.Transport.INTERNET, dark)
        )
        DeliveryMethod.MESH -> Pair(
            stringResource(
                if (isFromMe) R.string.delivery_sent_mesh
                else R.string.delivery_received_mesh
            ),
            Oshi.Chat.transportInk(Oshi.Chat.Transport.MESH, dark)
        )
        DeliveryMethod.TOR -> Pair(
            stringResource(
                if (isFromMe) R.string.delivery_sent_tor
                else R.string.delivery_received_tor
            ),
            Oshi.Chat.transportInk(Oshi.Chat.Transport.INTERNET, dark)
        )
        // 📡 LoRa gets its own badge rather than being folded into MESH: "this
        // went out over the radio" is the one delivery fact a person off-grid
        // actually checks, and calling it "Mesh" would name a transport that was
        // not used. Teal matches iOS (DeliveryMethodBadge.swift:99-100,
        // `case .lora: return .teal`).
        //
        // The label is the bare literal on purpose. iOS says "Sent via LoRa" /
        // "Via LoRa" (en.lproj/Localizable.strings:628,631) but no Android
        // `delivery_*_lora` key exists and this task may not touch a strings
        // file, so the badge shows the one token that is identical in every
        // locale — "LoRa" is a proper noun, like "OSHI" or "Bluetooth". A
        // translatable "Sent via …" wrapper is the follow-up, not a fabrication
        // of the wrong existing string.
        DeliveryMethod.LORA -> Pair("LoRa", Oshi.Chat.transportInk(Oshi.Chat.Transport.LORA, dark))
        // __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ Raw Meshtastic
        // text, and the badge says so. Amber rather than the LoRa teal because
        // this is a caution, not a transport: the packet is plaintext on a
        // public-key channel and its sender is an integer anyone in range can
        // forge. The marker is what lets such a message be filed in a contact's
        // thread at all — `LoRaNodeIdentityMap.adoptRawTextIntoContactThread`
        // required it before it could be turned on, so that unverified text is
        // never drawn identically to a message the ratchet proved.
        DeliveryMethod.LORA_PLAINTEXT -> Pair(
            "LoRa · " + stringResource(R.string.lora_unverified),
            Oshi.Chat.transportInk(Oshi.Chat.Transport.PENDING, dark)
        )
        // Unreachable — the early return above owns this case. Present only
        // because the `when` is exhaustive, and an `else` here would silently
        // swallow the NEXT transport someone adds.
        DeliveryMethod.LOCAL -> Pair("", Color.Transparent)
    }

    // (N14) "Envoyé via Internet"/"Via mesh" was legible but still a full
    // word under every single message — on a screen full of bubbles that
    // reads as noise. A failed send stays a word (it's the one state that
    // needs to be noticed, not glanced past); every normal transport is now
    // a small colour-coded dot, the colour already carried by
    // Oshi.Chat.transportInk. The word itself survives as the a11y label.
    if (isFailed) {
        Text(
            text = label,
            modifier = Modifier
                .background(Oshi.Chat.chipSurface(dark, com.oshi.messenger.ui.theme.isOledScheme()), RoundedCornerShape(percent = 50))
                .background(bgColor.copy(alpha = Oshi.Chat.chipFill), RoundedCornerShape(percent = 50))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = bgColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    } else {
        Box(
            modifier = Modifier
                .size(7.dp)
                .semantics { contentDescription = label }
                .background(bgColor, CircleShape)
        )
    }
}

/**
 * __CHAT_POLISH_2026_09_24__ The row under a bubble: [edited] time [chip]
 * [ticks], in iOS's order and on the wallpaper, with `Oshi.Chat` inks.
 */
@Composable
private fun BubbleMetaRow(message: Message, isFromMe: Boolean, dark: Boolean) {
    val metaInk = Oshi.Chat.metaInk(dark)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 3.dp, start = 6.dp, end = 6.dp)
    ) {
        if (message.editedAt != null) {
            Text(
                text = stringResource(R.string.message_edited).lowercase(),
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                color = metaInk
            )
        }
        // Normalize timestamp (seconds vs milliseconds)
        val normalizedTs = if (message.timestamp in 1..1_000_000_000_000L) message.timestamp * 1000 else message.timestamp
        Text(
            text = remember(normalizedTs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(normalizedTs)) },
            fontSize = 11.sp,
            color = metaInk
        )
        val meshCoreRow = com.oshi.messenger.service.lora.MeshCoreThread.isMeshCoreKey(message.senderPublicKey) ||
            com.oshi.messenger.service.lora.MeshCoreThread.isMeshCoreKey(message.recipientPublicKey)
        // A MeshCore bubble names its transport in words below (its amber "LoRa · unverified" dot would be a
        // second, wrong name); a failed one keeps the failed chip.
        if (!meshCoreRow || message.isFailed) DeliveryMethodBadge(
            deliveryMethod = message.deliveryMethod,
            isFromMe = isFromMe,
            isFailed = message.isFailed
        )
        // 📡 __INTEROP_REPLY_2026_11_09__ A `lora!<nodehex>` pseudo-identity has no
        // key either side has proved (raw Meshtastic text is unauthenticated, and a
        // reply has no OSHI key to encrypt to — see
        // MessageRepository.sendLoRaInteropReply), so every bubble in such a thread,
        // both directions, says so. iOS says it via `.loraPlaintext` on this same row.
        // __MESHCORE_INTEROP_2026_11_25__ A MeshCore bubble says only where it went, in the meta ink: the
        // thread's subtitle and the note above the composer carry the encryption fact once, not per bubble.
        if (meshCoreRow) {
            Text(text = "MeshCore", fontSize = 10.sp, color = metaInk)
        } else if (
            LoRaNodeIdentityMap.isFallbackConversationKey(message.senderPublicKey) ||
            LoRaNodeIdentityMap.isFallbackConversationKey(message.recipientPublicKey)
        ) {
            Text(
                text = stringResource(R.string.lora_interop_unverified),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        // `!message.isFailed`: a message that never left the device gets no
        // tick at all — the red "Not delivered · tap to retry" row is its status.
        if (isFromMe && !message.isFailed) {
            // __READ_TICK_NEEDED_MORE_THAN_COLOUR_2026_11_03__ Same halo as the
            // other tick site above; both rows show the same state and must not
            // disagree about how they show it.
            val read = message.isRead
            Box(
                contentAlignment = Alignment.Center,
                modifier = if (read) {
                    Modifier
                        .size(20.dp)
                        .background(Oshi.Chat.readTick(dark).copy(alpha = 0.18f), CircleShape)
                } else {
                    Modifier.size(20.dp)
                }
            ) {
                // __COVER_WAITING_CLOCK_2026_11_18__ same rule as the other tick site.
                val coverParked by com.oshi.messenger.service.CoverOutbox.awaiting.collectAsState()
                // __MESHCORE_INTEROP_2026_11_25__ queued for the MeshCore radio, not on the air yet ⇒ clock.
                val meshCorePending = if (meshCoreRow) LocalMeshCoreBubbles.current.pending else emptySet()
                val tick = com.oshi.messenger.ui.components.OutgoingTick.of(
                    read, message.isDelivered,
                    message.id in coverParked || MeshCoreUi.bubbleStatus(message, meshCorePending) == MeshCoreUi.BubbleStatus.SENDING,
                )
                Icon(
                    when (tick) {
                        com.oshi.messenger.ui.components.OutgoingTick.WAITING -> Icons.Default.Schedule
                        com.oshi.messenger.ui.components.OutgoingTick.SENT -> Icons.Default.Done
                        else -> Icons.Default.DoneAll
                    },
                    contentDescription = when (tick) {
                        com.oshi.messenger.ui.components.OutgoingTick.READ -> stringResource(R.string.message_read)
                        com.oshi.messenger.ui.components.OutgoingTick.DELIVERED -> stringResource(R.string.message_delivered)
                        com.oshi.messenger.ui.components.OutgoingTick.WAITING ->
                            stringResource(if (meshCoreRow) R.string.meshcore_sending else R.string.cover_waiting_bubble)
                        else -> stringResource(R.string.message_sent)
                    },
                    modifier = Modifier.size(14.dp),
                    // Green = read, on both platforms (was light blue #4FC3F7 here,
                    // 1.9:1 on white, and green on iOS).
                    tint = if (read) Oshi.Chat.readTick(dark) else metaInk
                )
            }
        }
    }
}

// ============================================================================
// TRANSLATE BOTTOM SHEET (6B.9)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateBottomSheet(
    messageContent: String,
    state: ChatViewModel.TranslationState,
    onRetry: () -> Unit,
    // null = 'Block forward and copy' is on: no Copy button (__CLIPBOARD_HYGIENE_2026_10_01__)
    onCopy: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // __GROUP_CHAT_INFO_SCROLL_2026_10_13__ The 1:1 twin of
        // GroupChatScreen.GroupTranslateBottomSheet, fixed in the same pass.
        // Original + translation are user-length, so a long message pushed the
        // Copy button off a sheet that does not scroll its slot.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.translation_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.translation_original),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = messageContent,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.translation_title),
                style = MaterialTheme.typography.labelMedium,
                color = brandInk(),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = OSHIPurple.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // RP-2. This block used to be an icon plus the hard-coded string
                // "Translation will appear here.\nML translation coming soon." while a
                // finished three-tier on-device translator sat unused in `NLPManager`.
                //
                // Every branch below is a state a real device actually reaches:
                // ML Kit downloads a ~30 MB language pack on FIRST use, so the very
                // first translate on a fresh phone legitimately takes seconds — and
                // fails outright when that phone is offline. A spinner with no terminal
                // state, or one dead-end sentence for all three, is what made this look
                // broken. Each failure names its own cause.
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (state) {
                        is ChatViewModel.TranslationState.Loading,
                        is ChatViewModel.TranslationState.Idle -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = brandInk()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.translation_in_progress),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        is ChatViewModel.TranslationState.Done -> {
                            Text(
                                text = state.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                            state.noteRes?.let { noteRes ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(noteRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // iOS puts a Copy action on the translation alert
                            // (`OSHI/ChatView.swift:4632-4635`).
                            if (onCopy != null) {
                                TextButton(onClick = { onCopy(state.text) }) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.copy))
                                }
                            }
                        }

                        is ChatViewModel.TranslationState.Failed -> {
                            Icon(
                                Icons.Default.GTranslate,
                                contentDescription = null,
                                // Was brand at HALF alpha over an 8% brand fill — the
                                // lowest-contrast pixel in the app at ~1.5:1 in dark.
                                tint = brandInk().copy(alpha = 0.7f),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(state.messageRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ============================================================================
// SYSTEM INFO BUBBLE (6B.11) - centered capsule for system messages
// ============================================================================

@Composable
internal fun SystemInfoBubble(type: SystemMessageType) {
    val (icon, labelRes) = when (type) {
        SystemMessageType.PROFILE_UPDATE -> Pair(Icons.Default.Person, R.string.system_profile_update)
        SystemMessageType.WALLPAPER_UPDATE -> Pair(Icons.Default.Palette, R.string.system_wallpaper_update)
        SystemMessageType.KEY_ROTATION -> Pair(Icons.Default.VpnKey, R.string.system_key_rotation)
        SystemMessageType.BLOCKED_NOTICE ->
            Pair(Icons.Default.Block, R.string.chat_blocked_by_contact)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(labelRes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ============================================================================
// RECORDING HINT OVERLAY (6B.14) - one-time tutorial overlay
// ============================================================================

@Composable
private fun RecordingHintOverlay(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(onClick = onDismiss)
            )

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(max = 300.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Same F-14 recipe.
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                color = OSHIPurple.copy(alpha = 0.22f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = stringResource(R.string.recording_hint_hold),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.recording_hint_slide),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OSHIPurple
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.recording_hint_dismiss),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * __OPEN_AT_THE_FIRST_UNREAD__ Scroll a `reverseLayout` thread so row [index] sits
 * just under the top edge, with the newer rows below it — or, for a row taller than
 * the screen, so its top shows. `scrollToItem` alone puts the row at the BOTTOM
 * (index 0 is the bottom in a reversed list); a negative `scrollBy` then moves
 * toward the newer rows, and LazyColumn clamps it at the newest one, so a short
 * unread tail simply lands at the bottom.
 */
internal suspend fun landWithRowAtTop(
    listState: androidx.compose.foundation.lazy.LazyListState,
    index: Int,
    topMarginPx: Int
) {
    listState.scrollToItem(index)
    val info = listState.layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    val delta = row.size + topMarginPx - viewport
    if (delta != 0) listState.scrollBy(delta.toFloat())
}

/** The "Unread messages" line drawn above the first unread row (iOS `chat_unread_divider`). */
@Composable
internal fun UnreadMessagesDivider() {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.35f)))
        Text(
            text = stringResource(R.string.chat_unread_divider),
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
        Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.35f)))
    }
}
