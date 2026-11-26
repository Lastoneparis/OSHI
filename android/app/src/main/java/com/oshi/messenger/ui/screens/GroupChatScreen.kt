package com.oshi.messenger.ui.screens

import com.oshi.messenger.ui.theme.thenIf
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassLift
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassEdge
import com.oshi.messenger.ui.components.bubbleClickable
import com.oshi.messenger.ui.theme.OshiDropdownMenu
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.oshi.messenger.ui.components.LinkifyText
import com.oshi.messenger.ui.components.LinkPreviewCard
import com.oshi.messenger.ui.components.TickerChipsForText
import com.oshi.messenger.util.TickerLinks
import com.oshi.messenger.ui.components.detectFirstUrl
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.oshi.messenger.ui.viewmodel.SettingsViewModel
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.flow.map
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.ui.components.ComposerShortcut
import com.oshi.messenger.util.MessageSanitizer
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.ui.components.AIQuickBar
import com.oshi.messenger.ui.components.SmartRepliesBar
import com.oshi.messenger.data.model.BotCallbackAnswer
import com.oshi.messenger.data.model.BotInlineButton
import com.oshi.messenger.data.model.BotReplyMarkupParser
import com.oshi.messenger.ui.components.BotInlineKeyboard
import com.oshi.messenger.ui.components.slashPickerKeys
import com.oshi.messenger.ui.components.BotWebAppDialog
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.ui.theme.LocalBubbleStyle
import com.oshi.messenger.ui.theme.LocalBubbleAppearance
import com.oshi.messenger.ui.theme.BubbleAppearance
import com.oshi.messenger.ui.theme.BubbleAppearancePrefs
import com.oshi.messenger.ui.theme.bubbleSurface
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.components.OshiEmptyState
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.media.bleed
import com.oshi.messenger.ui.media.MediaTimePill
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.GroupComments
import com.oshi.messenger.service.MessageActionsManager
import com.oshi.messenger.ui.components.swipeToReply
import com.oshi.messenger.ui.viewmodel.GroupChatViewModel
import com.oshi.messenger.ui.viewmodel.GroupsViewModel
import com.oshi.messenger.ui.viewmodel.NLPViewModel
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalDensity
import com.oshi.messenger.ui.components.frostedBackdropSource
import com.oshi.messenger.ui.components.extendUp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.network.location.LocationMessage
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.oshi.messenger.ui.components.LocationShareSheet
import com.oshi.messenger.ui.components.ForwardPickerSheet
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIRed
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.Intent
import org.json.JSONObject
import java.io.File
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * GroupChatScreen - iOS-style group messaging
 * Matches iOS GroupViews.swift GroupChatView
 */

private val BrandColor = OSHIPurple

private fun saveMediaToGallery(context: android.content.Context, mediaData: ByteArray, mediaType: MediaType?): Boolean {
    // 🛡 __STRICT_MODE_2026_11_22__ nothing decrypted leaves OSHI while strict mode is on.
    if (!com.oshi.messenger.security.StrictModeGate.mediaExportAllowed) return false
    return try {
        val isVideo = mediaType == MediaType.VIDEO
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
 * Android [MediaType] → the raw value iOS uses for a **group** message
 * (`MediaType` in GroupMessaging.swift:376-382: photo/video/audio/document/contact).
 * `ReplyMessage.originalMediaType` (MessageActionsManager.swift:20) carries this spelling —
 * iOS's quote renderer matches both "image" and "photo" (MessageActionsManager.swift:47) but
 * only "photo" draws a thumbnail (`quotesAPicture`, :59).
 */
private fun iosGroupMediaTypeRaw(type: MediaType?): String? = when (type) {
    MediaType.IMAGE -> "photo"
    MediaType.VIDEO -> "video"
    MediaType.AUDIO -> "audio"
    MediaType.DOCUMENT -> "document"
    MediaType.CONTACT -> "contact"
    MediaType.LOCATION -> "location"
    null -> null
}

/**
 * The name shown on a group document bubble, or null when there is none to show.
 *
 * `mediaFileName` is where the name lives now, on both platforms (iOS
 * `GroupViews.swift:3087`). The `"[DOCUMENT] name.pdf"` body is the legacy channel: rows
 * already on disk and messages from older Android builds carry the name only there, and
 * dropping the fallback would blank every document a user already has.
 */
fun groupDocumentLabel(mediaFileName: String?, content: String): String? {
    mediaFileName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return content.removePrefix("[DOCUMENT]").trim().takeIf { it.isNotEmpty() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GroupChatScreen(
    groupId: String,
    /** __CHAT_BACKDROP_BLUR_2026_11_21__ the status-bar strip the host padded us out of. */
    statusBarUnderlap: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
    onGroupInfo: () -> Unit,
    onNavigateToChat: (String) -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    onNavigateToAIChat: () -> Unit = {},
    /** __BOT_COMMANDS_2026_11_24__ open a bot's settings (owner's "Set up commands"). */
    onOpenBot: (String) -> Unit = {},
    /**
     * __BOTS_PHASE1_2026_11_24__ "Add a bot" › "Create a bot" › where it runs: this phone
     * (local wizard) or a server (BotFather).
     */
    onCreateBot: (com.oshi.messenger.ui.bots.BotHosting) -> Unit = {},
    viewModel: GroupChatViewModel = hiltViewModel(),
    nlpViewModel: NLPViewModel = hiltViewModel(),
    // Group membership / rename / typing actions live here because GroupChatViewModel has no
    // access to them; both view models talk to the same GroupManager singleton.
    groupsViewModel: GroupsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val group by viewModel.group.collectAsState()
    val groupMediaScope = rememberCoroutineScope()
    // __GROUP_E2E_V2_2026_09_23__ GROUP_E2E_V2_SPEC §6 — members who must update.
    val unreachableByGroup by viewModel.unreachableMembers.collectAsState()
    // __GROUP_RESEND_2026_11_25__ own messages some members have not received yet.
    val pendingDeliveries by viewModel.pendingDeliveries.collectAsState()
    var pendingDetailsFor by remember { mutableStateOf<String?>(null) }
    val messages by viewModel.messages.collectAsState()
    val messagesLoaded by viewModel.messagesLoaded.collectAsState()
    val myPublicKey by viewModel.myPublicKey.collectAsState()
    val autoTranslateGroups by groupsViewModel.autoTranslateGroups.collectAsState()
    val autoTranslateOn = groupId.uppercase() in autoTranslateGroups
    val autoTranslations by viewModel.autoTranslations.collectAsState()
    // __GROUP_SETTINGS_2026_11_20__ a reaction the group does not allow
    val reactionNotice by viewModel.reactionNotice.collectAsState()
    LaunchedEffect(reactionNotice) {
        reactionNotice?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show(); viewModel.consumeReactionNotice() }
    }
    // __BOT_IN_THE_PIPELINE_2026_11_09__ bot commands in the `/` menu + the one-time no-bot hint.
    val botCommands by viewModel.botCommands.collectAsState()
    // __BOT_COMMANDS_2026_11_24__ the "Commands" menu button + sheet.
    val botsHere by viewModel.botsHere.collectAsState()
    var showBotCommands by remember { mutableStateOf(false) }
    // __BOTS_PHASE1_2026_11_24__ "Add a bot" sheet (search, my bots, create).
    var showAddBot by remember { mutableStateOf(false) }
    var showBotHostingChoice by remember { mutableStateOf(false) }
    if (showBotHostingChoice) {
        com.oshi.messenger.ui.bots.BotKindChoiceSheet(onDismiss = { showBotHostingChoice = false }) { onCreateBot(it) }
    }
    val showNoBotHint by viewModel.noBotHint.collectAsState()
    val contactsMap by viewModel.contactsMap.collectAsState()
    // __REACTION_WHO_SHEET_2026_11_16__ a member who is not a contact is named from the
    // group definition / shared nickname, not shown as a key prefix.
    val listMemberNameInputs by groupsViewModel.memberNameInputs.collectAsState()

    // F-4: localized copy for every control-payload summary, resolved once per
    // screen. GroupChatScreen used to route everything through MessageSanitizer,
    // whose every user-facing string is a hardcoded English literal - so a shared
    // location read "Location shared" in all 34 languages.
    val controlLabels = remember(context) { OSHIControlPayload.labels(context) }
    val youLabel = stringResource(R.string.chat_you)
    // __GROUP_COMMENTS_2026_11_21__ (spec §2.12) bars under posts + the thread dialog.
    val gsAllForComments by groupsViewModel.groupSettings.collectAsState()
    val commentsActiveHere = group?.let { gsAllForComments[it.id.uppercase()]?.commentsActive } == true
    // __BUBBLE_MEDIA_2026_11_21__ channel mode (only admins post): posts render as cards.
    val isChannelHere = group?.let {
        gsAllForComments[it.id.uppercase()]?.postPolicy == com.oshi.messenger.data.model.GroupSettings.PostPolicy.ADMINS
    } == true
    val commentsByPost by viewModel.comments.collectAsState()
    var openCommentsFor by remember { mutableStateOf<String?>(null) }
    var channelEmbedFor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val typingText by viewModel.typingText.collectAsState()
    var messageText by remember { mutableStateOf("") }
    if (showBotCommands) {
        com.oshi.messenger.ui.components.BotCommandsSheet(
            bots = botsHere,
            commands = com.oshi.messenger.util.SlashCommandCatalog.botOnly(botCommands),
            onSend = { entry ->
                showBotCommands = false
                viewModel.sendBotCommand(entry)
            },
            onSetUp = { id ->
                showBotCommands = false
                onOpenBot(id)
            },
            onDismiss = { showBotCommands = false },
            onInsert = { completion ->
                showBotCommands = false
                messageText = completion
            },
        )
    }
    group?.let { g ->
        if (showAddBot) {
            com.oshi.messenger.ui.bots.AddBotSheet(
                group = g,
                onDismiss = { showAddBot = false },
                onCreateBot = { showAddBot = false; showBotHostingChoice = true },
            )
        }
    }
    /**
     * __CASHU_2026_10_25__ A token the user is about to put in front of the whole
     * group, held for one confirmation.
     *
     * `/pay` already asks before sending into a group. That protection was
     * worthless on its own: PASTING a token and pressing send skipped it
     * entirely, which is how most people would actually do it. The guard belongs
     * to the act of sending money to a room, not to one way of typing it.
     */
    var cashuAboutToGoPublic by remember { mutableStateOf<String?>(null) }
    // __MENTIONS_2026_09_23__ `@` picker state: members picked for this draft, and the recorded-mentions tick.
    var pickedMentions by remember { mutableStateOf(listOf<com.oshi.messenger.util.MentionWire.Mention>()) }
    val mentionsVersion by viewModel.mentionsVersion.collectAsState()
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showGroupInfoSheet by remember { mutableStateOf(false) }
    var showGroupSettings by remember { mutableStateOf(false) }
    // __REPORT_OUTCOME_2026_09_23__ group report dialog (reuses the contact dialog).
    var showReportGroupDialog by remember { mutableStateOf(false) }
    if (showReportGroupDialog) {
        ReportContactDialog(
            alreadyReported = viewModel.hasReportedGroup(),
            onDismiss = { showReportGroupDialog = false },
            onSubmit = { reason, details ->
                showReportGroupDialog = false
                viewModel.reportGroup(reason, details) { state ->
                    Toast.makeText(context, context.getString(reportOutcomeText(state)), Toast.LENGTH_LONG).show()
                }
            },
            titleRes = R.string.report_group_dialog_title,
            alreadyFiledRes = R.string.report_group_already_filed,
        )
    }
    var showAISheet by remember { mutableStateOf(false) }

    // Per-chat media gallery (P2 #20)
    var showMediaGallery by remember { mutableStateOf(false) }
    var galleryViewerMedia by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }

    val listState = rememberLazyListState()

    // Message actions state
    var selectedMessage by remember { mutableStateOf<GroupMessage?>(null) }
    var showMessageActions by remember { mutableStateOf(false) }
    // __GROUP_RECEIPTS_2026_11_02__ (N8) the message whose receipts are on screen.
    var receiptsFor by remember { mutableStateOf<GroupMessage?>(null) }

    // Translate sheet state (RP-2) — groups had no Translate action at all
    var showTranslateSheet by remember { mutableStateOf(false) }
    var translateMessageContent by remember { mutableStateOf("") }
    val groupTranslationState by viewModel.translationState.collectAsState()

    // ✏️ Edit state (RP-4, second half). `editingMessage` is the message under edit;
    // `editDraft` is the text field's contents.
    var editingMessage by remember { mutableStateOf<GroupMessage?>(null) }
    var editDraft by remember { mutableStateOf("") }

    // Member actions state
    var showMemberActions by remember { mutableStateOf(false) }
    // __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__ the member being
    // reported. Held separately from `selectedMemberKey` because the actions
    // dialog closes before the report dialog opens, and clearing the selection
    // first would leave the report with no subject.
    var reportMemberKey by remember { mutableStateOf<String?>(null) }
    var selectedMemberKey by remember { mutableStateOf<String?>(null) }

    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    // __GROUP_GIF_2026_11_16__ GIFs & stickers (OSHI mascots first), as in a 1:1.
    var showGifPicker by remember { mutableStateOf(false) }
    // __GROUP_CHECK_IN_2026_11_16__ Check-In armed for the whole group (iOS parity).
    var showCheckInSheet by remember { mutableStateOf(false) }
    // 🪙 __CASHU_SEND_READY_2026_11_09__ Non-null = the Send-bitcoin sheet is open.
    var cashuDraft by remember { mutableStateOf<String?>(null) }
    // __VIDEO_NOTE_2026_09_24__ the round recorder the composer's video button opens.
    var showVideoNoteRecorder by remember { mutableStateOf(false) }
    var showLocationSheet by remember { mutableStateOf(false) }
    var showForwardSheet by remember { mutableStateOf(false) }
    var forwardMessage by remember { mutableStateOf<GroupMessage?>(null) }

    // Reply state
    var replyToMessage by remember { mutableStateOf<GroupMessage?>(null) }

    // Camera state
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    // Media staged for the caption sheet (audit G-7). Every image/video path parks its
    // bytes here instead of sending straight away, so the caption the ViewModel has
    // accepted since the viewmodels pass finally has somewhere to be typed.
    var pendingImageData by remember { mutableStateOf<ByteArray?>(null) }
    var pendingVideoData by remember { mutableStateOf<ByteArray?>(null) }
    var showMediaPreview by remember { mutableStateOf(false) }
    // __IMAGE_CROP_2026_11_02__ (N13)
    var showCrop by remember { mutableStateOf(false) }

    // Location manager — process-wide singleton (iOS `LocationSharingManager.shared`),
    // shared with 1:1 chats so one live share is visible and stoppable from either.
    val locationManager = viewModel.locationSharingManager

    // Camera launcher (TakePicture) - declared before permission launcher so it can be referenced
    // Downsamples large camera photos to prevent OOM
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ The capture target is plaintext in cacheDir;
        // once its bytes are in memory (or the capture was cancelled) it goes.
        val captureName = cameraImageUri?.lastPathSegment
        if (success && cameraImageUri != null) {
            try {
                val uri = cameraImageUri!!
                val maxDim = 1920
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { s ->
                    BitmapFactory.decodeStream(s, null, boundsOpts)
                }
                val origW = boundsOpts.outWidth
                val origH = boundsOpts.outHeight

                val imageData = if (origW > maxDim || origH > maxDim) {
                    var inSampleSize = 1
                    var halfW = origW / 2; var halfH = origH / 2
                    while (halfW / inSampleSize >= maxDim && halfH / inSampleSize >= maxDim) {
                        inSampleSize *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                    val sampled = context.contentResolver.openInputStream(uri)?.use { s ->
                        BitmapFactory.decodeStream(s, null, decodeOpts)
                    }
                    if (sampled != null) {
                        val longest = maxOf(sampled.width, sampled.height)
                        val bitmap = if (longest > maxDim) {
                            val scale = maxDim.toFloat() / longest
                            val sw = (sampled.width * scale).toInt().coerceAtLeast(1)
                            val sh = (sampled.height * scale).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(sampled, sw, sh, true)
                            sampled.recycle()
                            scaled
                        } else sampled
                        val baos = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        bitmap.recycle()
                        baos.toByteArray()
                    } else null
                } else {
                    context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() }
                }

                if (imageData != null) {
                    pendingImageData = imageData
                    pendingVideoData = null
                    showMediaPreview = true
                }
            } catch (e: OutOfMemoryError) {
                Toast.makeText(context, context.getString(R.string.chat_failed_load_image), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.chat_failed_load_image), Toast.LENGTH_SHORT).show()
            }
        }
        com.oshi.messenger.data.local.atrest.PlaintextScratch.deleteCapture(context.cacheDir, captureName)
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Create temp file for camera image
            val photoFile = File.createTempFile("oshi_photo_", ".jpg", context.cacheDir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, context.getString(R.string.permission_camera_message), Toast.LENGTH_SHORT).show()
        }
    }

    // __ANATOLE_N17_CAMERA_IN_COMPOSER_2026_11_13__ One way in to the camera for
    // the composer button AND the sheet. Both used to call `cameraLauncher.launch`
    // straight away: with CAMERA declared in the manifest, ACTION_IMAGE_CAPTURE
    // throws SecurityException until the permission is granted, so the first tap
    // on a fresh install was a crash instead of a permission prompt. Now: ask
    // first, launch on grant (the launcher above already does that part).
    val launchGroupCamera: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val photoFile = File.createTempFile("oshi_photo_", ".jpg", context.cacheDir)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
                cameraImageUri = uri
                cameraLauncher.launch(uri)
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.error_camera_unavailable), Toast.LENGTH_SHORT).show()
            }
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // __ATTACH_PANEL_2027_2026_11_16__ Photos and videos from the attachment panel or the
    // system picker, through the panel's one loader (GIFs untouched, photos downsampled,
    // videos capped at the media ceiling). One item → the caption dialog, as before;
    // several → straight out, the caption (if any) on the first ([AttachmentPanelLogic.captions]).
    val sendGroupPick: (List<com.oshi.messenger.ui.components.attach.RecentMediaItem>, String, Boolean) -> Unit =
        { picked, caption, direct ->
            groupMediaScope.launch {
                val loaded = com.oshi.messenger.ui.components.attach.RecentMedia.load(context, picked, viewOnce = false)
                if (loaded.tooLarge) {
                    Toast.makeText(context, context.getString(R.string.chat_video_too_large), Toast.LENGTH_SHORT).show()
                } else if (loaded.failed) {
                    Toast.makeText(context, context.getString(R.string.chat_failed_load_image), Toast.LENGTH_SHORT).show()
                }
                val media = loaded.media
                if (media.isEmpty()) return@launch
                if (!direct && media.size == 1) {
                    pendingImageData = media[0].bytes.takeUnless { media[0].isVideo }
                    pendingVideoData = media[0].bytes.takeIf { media[0].isVideo }
                    showMediaPreview = true
                    return@launch
                }
                val caps = com.oshi.messenger.ui.components.attach.AttachmentPanelLogic.captions(media.size, caption)
                media.forEachIndexed { n, m ->
                    viewModel.sendMediaMessage(m.bytes, if (m.isVideo) "video" else "image", caps[n].orEmpty())
                }
                Toast.makeText(context, context.getString(R.string.chat_sending_image), Toast.LENGTH_SHORT).show()
            }
        }
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(com.oshi.messenger.ui.components.ChatMediaSelection.MAX_SELECTION)
    ) { uris ->
        if (uris.isNotEmpty()) {
            sendGroupPick(com.oshi.messenger.ui.components.attach.RecentMedia.fromPickerUris(context, uris), "", false)
        }
    }

    // Video CAPTURE launcher — system camera in video mode. On result we read bytes
    // from the temp URI, enforce a 50 MB ceiling, then send via groupManager (through
    // the ViewModel's sendMediaMessage) which mirrors the audio-send path.
    var pendingCameraVideoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val videoCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success: Boolean ->
        val uri = pendingCameraVideoUri
        pendingCameraVideoUri = null
        if (success && uri != null) {
            try {
                val videoData = try {
                    context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes() }
                } finally {
                    // __PLAINTEXT_LEFTOVERS_2026_09_24__ Sent from memory: drop the plaintext capture.
                    com.oshi.messenger.data.local.atrest.PlaintextScratch.deleteCapture(context.cacheDir, uri.lastPathSegment)
                }
                if (videoData != null) {
                    // M-16: one ceiling for every media path (200 MB), not a
                    // second 50 MB one that refuses what the picker accepts.
                    if (videoData.size > com.oshi.messenger.service.MediaCompressionManager.IPFS_MAX_SIZE) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_video_too_large),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        pendingVideoData = videoData
                        pendingImageData = null
                        showMediaPreview = true
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_failed_load_video),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            com.oshi.messenger.data.local.atrest.PlaintextScratch.deleteCapture(context.cacheDir, uri?.lastPathSegment)
        }
    }

    // Document picker launcher
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val documentData = inputStream?.readBytes()
                inputStream?.close()
                val fileName = context.contentResolver.query(it, null, null, null, null)?.use { c ->
                    val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && nameIndex >= 0) c.getString(nameIndex) else "document"
                } ?: "document"
                if (documentData != null) {
                    viewModel.sendDocumentMessage(documentData, fileName)
                    Toast.makeText(context, context.getString(R.string.chat_sending_document), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.chat_failed_load_document), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 🔧 FIX (2026-08-24 three-gaps): OSHI contact picker — see the long note on the
    // 1-on-1 twin in `ChatScreen.kt`. This launched the SYSTEM address book and sent
    // a plain-text `"[Contact] Alice"`; iOS shares a reachable identity, and the
    // group wire has carried `mediaType: "contact"` all along
    // (`OSHI/GroupMessaging.swift:406-412`, `GroupViews.swift:3201`).
    val shareableContacts by viewModel.shareableContacts.collectAsState()
    var showContactPicker by remember { mutableStateOf(false) }

    if (showContactPicker) {
        com.oshi.messenger.ui.components.OshiContactPickerSheet(
            contacts = shareableContacts,
            onContactSelected = { pk, alias ->
                viewModel.sendContactCard(pk, alias)
                Toast.makeText(context, context.getString(R.string.contact_shared), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showContactPicker = false }
        )
    }

    // AI NLP state
    val nlpState by nlpViewModel.uiState.collectAsState()
    var aiProcessingAction by remember { mutableStateOf<NLPViewModel.AIAction?>(null) }

    // Schedule sheet state (iOS parity)
    var showScheduleSheet by remember { mutableStateOf(false) }
    var showPollSheet by remember { mutableStateOf(false) }
    val scheduledMessageManager: com.oshi.messenger.service.ScheduledMessageManager = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.oshi.messenger.service.ScheduledMessageWorkerEntryPoint::class.java
        ).scheduledMessageManager()
    }

    // Voice recording (iOS parity)
    val audioRecorder: com.oshi.messenger.service.AudioRecorderManager = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            GroupVoiceEntryPoint::class.java
        ).audioRecorder()
    }
    val recordingDuration by audioRecorder.recordingDuration.collectAsState()
    var isRecording by remember { mutableStateOf(false) }

    // Audio permission launcher (RECORD_AUDIO)
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = audioRecorder.startRecording()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.chat_mic_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Apply AI result back to message text
    LaunchedEffect(nlpState.correctedText, nlpState.isLoading) {
        if (!nlpState.isLoading && nlpState.correctedText.isNotEmpty() && aiProcessingAction != null) {
            messageText = nlpState.correctedText
            aiProcessingAction = null
        }
    }

    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Observed: any member's change flips every
    // gate below (AI, save, FLAG_SECURE) without reopening the group.
    val advPrivacy = com.oshi.messenger.ui.components.rememberAdvancedChatPrivacy(groupId)
    com.oshi.messenger.ui.components.AdvancedChatPrivacyWindowGuard(groupId, advPrivacy)

    // Build conversation context for AI (group messages)
    val conversationContext = remember(messages.size, advPrivacy) {
        // F-16: the group screen had NO control-payload filter at all, so every
        // sentinel body (locations, receipts, reaction payloads) went verbatim
        // into the on-device LLM prompt. One catalog, same question as 1:1 -
        // iOS ChatView.swift:320-327.
        val readableMessages = messages
            .filter { msg ->
                msg.content.isNotBlank() &&
                    !OSHIControlPayload.isControl(msg.content) &&
                    msg.content.length > 1
            }
            .takeLast(5)
            .map { Pair(it.isFromMe, it.content) }
        // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ nothing from a protected group reaches the model.
        nlpViewModel.buildConversationContext(
            com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.aiReadable(advPrivacy, readableMessages)
        )
    }

    // Trigger smart replies on new group message from others
    LaunchedEffect(messages.size) {
        // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ (the clear-on-switch effect is just below)
        if (!com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.aiAllowed(advPrivacy)) return@LaunchedEffect
        // H-5. The CONTEXT builder above was fixed by F-16 and this trigger, two
        // lines below it, was not: a shared location or a call summary went into
        // the on-device LLM prompt verbatim (`NLPManager.kt:1124-1126`, `:1192`).
        // `lastOrNull` — not `firstOrNull` as in the 1:1 screen — because the group
        // list is built in ARRIVAL order (the context builder above uses
        // `takeLast(5)` for the same reason), while `MessageDao` hands the 1:1
        // screen `ORDER BY timestamp DESC`.
        val lastMsg = messages.lastOrNull { !OSHIControlPayload.isControl(it.content) }
        if (lastMsg != null && !lastMsg.isFromMe && lastMsg.content.isNotBlank() && messageText.isEmpty()) {
            nlpViewModel.generateSmartReplies(lastMsg.content)
            nlpViewModel.suggestToneForLastMessage(lastMsg.content)
        }
    }

    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ suggestions already on screen go the moment it is switched on.
    LaunchedEffect(advPrivacy) {
        if (!com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.aiAllowed(advPrivacy)) {
            nlpViewModel.clearSmartReplies()
        }
    }

    // Load group on first composition; mark closed when leaving
    LaunchedEffect(groupId) {
        viewModel.loadGroup(groupId)
    }
    DisposableEffect(groupId) {
        onDispose { viewModel.onGroupClosed() }
    }

    // 👀 READ STATE (2026-08-24). The arrival-driven mark-read in
    // `GroupChatViewModel.observeGroup` used to be gated on a flag set in
    // `loadGroup` and cleared in the `onDispose` above — and `onDispose` does not
    // run when the app is merely BACKGROUNDED with the chat open, so messages
    // arriving while the phone was in a pocket were stamped read. Driving
    // `ActiveConversation` from `ON_RESUME` / `ON_PAUSE` makes the gate mean
    // "foregrounded AND this group on screen", which is what iOS's `activeGroupId`
    // means (`OSHI/GroupMessaging.swift:461`, set at `GroupViews.swift:4717,4870`).
    val readStateLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(groupId, readStateLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onGroupVisible(groupId)
                Lifecycle.Event.ON_PAUSE -> viewModel.onGroupHidden(groupId)
                else -> Unit
            }
        }
        readStateLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            readStateLifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onGroupHidden(groupId)
        }
    }

    // Is the last row actually on screen?
    //
    // F-2 (P0). Both call sites below used to ask
    // `firstVisibleItemIndex >= lastItemIndex - 3`, which is the correct question
    // only in a REVERSE-layout list (ChatScreen.kt, where index 0 is the newest
    // row). This LazyColumn is forward-layout, so `firstVisibleItemIndex` is the
    // TOPMOST visible row: parked at the very bottom with eight bubbles on screen
    // it reads `lastIndex - 7`. The result was a group thread that never followed
    // a new message and a scroll-to-bottom arrow that never hid.
    //
    // iOS answers this with a permanently-identified bottom anchor whose
    // onAppear/onDisappear drive the arrow (GroupViews.swift:3722-3735). The
    // Compose equivalent of "the anchor is in view" is "the last item is in
    // view", which is what this reads - and it stays correct whatever the row
    // height or the screen size.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total == 0 || info.visibleItemsInfo.lastOrNull()?.index == total - 1
        }
    }

    // __GROUP_OPEN_ANCHOR_2026_09_23__ Land on — and STAY on — the newest message.
    //
    // The previous effect fired once per `messages.size` change and read
    // `layoutInfo.totalItemsCount` before the LazyColumn had measured the new
    // rows (stale → it scrolled to the OLD last row), then asked `isAtBottom`
    // AFTER the batch had been appended (the new last row is never on screen
    // yet → false → no follow). A cold open whose history lands in several
    // batches (legacy history, v2 pull, bot/pending rows) or whose last bubbles
    // grow after layout (images, link previews) therefore parked above the
    // newest message. `rememberFollowNewest` pins on every layout change while
    // the reader has not scrolled away, and stops the moment they drag up.
    //
    // __OPEN_AT_THE_FIRST_UNREAD__ (iOS GroupViews parity) A group opened with unread
    // messages lands on the FIRST unread, under an "Unread messages" line, instead of
    // the newest — so following starts OFF in that case and the landing below aims at
    // the anchor. In this forward-layout list the anchor is the TOP row, which is the
    // row LazyColumn keeps still, so photos decoding below it cannot move it.
    val followNewest = rememberFollowNewest(
        listState,
        key = groupId,
        initialFollow = viewModel.openUnreadIds.isEmpty()
    )
    // Saveable, like the list position: coming back from another screen must not land again.
    var unreadDividerId by rememberSaveable(groupId) { mutableStateOf<String?>(null) }
    var unreadLanded by rememberSaveable(groupId) { mutableStateOf(viewModel.openUnreadIds.isEmpty()) }
    LaunchedEffect(groupId, messages.size) {
        if (unreadLanded || messages.isEmpty()) return@LaunchedEffect
        val anchor = viewModel.openUnreadIds.firstOrNull { id -> messages.any { it.id == id } }
        val index = anchor?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (index < 0) {
            followNewest.value = true // nothing to land on: behave as before
        } else {
            unreadDividerId = anchor
            listState.scrollToItem(index)
        }
        unreadLanded = true
    }
    // __OPEN_SCROLL_2026_11_16__ Both landings (pinned to the newest row, or on the
    // unread line) are scrolls made by effects, which run AFTER the first frame with
    // rows has been drawn — so every open showed the OLDEST messages first, for a
    // frame or more, then jumped. The list stays invisible until it has landed
    // (bounded, so a landing that never settles still shows the thread).
    var openSettled by rememberSaveable(groupId) { mutableStateOf(false) }
    LaunchedEffect(groupId) {
        kotlinx.coroutines.withTimeoutOrNull(1_500L) {
            snapshotFlow {
                listState.layoutInfo.totalItemsCount > 0 && unreadLanded &&
                    (!followNewest.value || !listState.canScrollForward)
            }.first { it }
        }
        openSettled = true
    }
    // __CHANNEL_WEB_2026_11_26__ (PUBLIC_CHANNEL_WEB_SPEC §9.8) land on post n when we were opened
    // by a /c/<handle>/<n> link. Merely reading a channel never contacts the web server.
    val channelWebOn = group?.let { gsAllForComments[it.id.uppercase()]?.webActive } == true
    LaunchedEffect(groupId, channelWebOn) {
        if (!channelWebOn) return@LaunchedEffect
        val n = com.oshi.messenger.service.channelweb.ChannelWebDeepLinks.take(groupId) ?: return@LaunchedEffect
        val id = viewModel.channelWebMessageFor(n) ?: return@LaunchedEffect
        val index = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            snapshotFlow { messages.indexOfFirst { it.id == id } }.first { it >= 0 }
        } ?: return@LaunchedEffect
        followNewest.value = false
        listState.scrollToItem(index)
    }
    val lastIsMine = messages.lastOrNull()?.isFromMe == true
    val lastMessageId = messages.lastOrNull()?.id
    var seenLastMessageId by remember(groupId) { mutableStateOf<String?>(null) }
    LaunchedEffect(lastMessageId) {
        // Your OWN send always brings you back down (iOS ChatView.swift:864-870).
        // Keyed on the newest id, not the count: a size change that is not a new
        // message of yours (a delete, a late older batch) must not re-arm following.
        // The first list is the OPEN, not a send — it must not override the
        // unread landing above when the newest row happens to be yours.
        val previous = seenLastMessageId
        seenLastMessageId = lastMessageId
        if (previous == null) return@LaunchedEffect
        if (lastIsMine && messages.isNotEmpty()) followNewest.value = true
    }

    // F-13: same appearance the 1:1 thread reads, from the same store.
    val bubbleAppearance = remember {
        BubbleAppearance(
            accent = BubbleAppearancePrefs.accent(context),
            silhouette = BubbleAppearancePrefs.silhouette(context),
            boldText = BubbleAppearancePrefs.boldText(context)
        )
    }

    // ── 🤖 Bot platform: inline keyboards, callback answers, mini-apps ────────
    // __BOT_E2E_2026_09_23__ How this group's latest bot message was protected
    // (BOT_SEAL_SPEC §3), shown once under the latest bot message. Unknown (nothing
    // received since this build) = no caption rather than a guess.
    val botSealKinds by com.oshi.messenger.network.bot.BotSealStatus.kinds.collectAsState()
    val botSealCaption: String? = remember(botSealKinds, groupId) {
        com.oshi.messenger.network.bot.BotSealStatus.get(context, groupId)
            ?.let { context.getString(com.oshi.messenger.network.bot.BotSealStatus.captionRes(it)) }
    }
    val botInFlight by viewModel.botInFlight.collectAsState()
    val botWebApp by viewModel.botWebApp.collectAsState()
    val botSnackbarHost = remember { SnackbarHostState() }
    var botAlert by remember { mutableStateOf<BotCallbackAnswer?>(null) }
    LaunchedEffect(Unit) {
        // A bot's answer is either a transient line (snackbar) or something it wants
        // acknowledged (`showAlert`). `showSnackbar` suspends until the line is gone,
        // which is what serialises a burst of answers instead of flashing them.
        viewModel.botNotices.collect { notice ->
            when (notice) {
                is GroupChatViewModel.BotNotice.Message ->
                    botSnackbarHost.showSnackbar(context.getString(notice.resId))
                is GroupChatViewModel.BotNotice.Answer -> {
                    val answer = notice.answer
                    when {
                        answer.text.isBlank() -> Unit  // an answer that only stops the spinner
                        answer.showAlert -> botAlert = answer
                        else -> botSnackbarHost.showSnackbar(answer.text)
                    }
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalBubbleAppearance provides bubbleAppearance,
        com.oshi.messenger.ui.components.LocalAdvancedChatPrivacy provides advPrivacy,
    ) {
    // __CASHU_2026_10_25__ One confirmation before money reaches a room.
    // A dialog rather than making the user retype the message with the word
    // `public`: the point is that they UNDERSTAND what is about to happen, not
    // that they prove it by typing. The amount is named, because "a token" and
    // "47 000 sat" are not the same decision.
    cashuAboutToGoPublic?.let { pending ->
        val summary = remember(pending) {
            com.oshi.messenger.util.CashuTokens.find(pending)
                .joinToString(", ") { "${it.token.totalAmount} ${it.token.unit ?: "sat"}" }
        }
        OshiAlertDialog(
            onDismissRequest = { cashuAboutToGoPublic = null },
            title = { Text(stringResource(R.string.cashu_group_confirm_title)) },
            text = { Text(stringResource(R.string.cashu_group_confirm_body, summary)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendMessage(pending, mentions = pickedMentions)
                    pickedMentions = emptyList()
                    groupsViewModel.sendGroupTyping(groupId, false)
                    messageText = ""
                    replyToMessage = null
                    cashuAboutToGoPublic = null
                }) { Text(stringResource(R.string.cashu_group_confirm_send)) }
            },
            dismissButton = {
                TextButton(onClick = { cashuAboutToGoPublic = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    botAlert?.let { alert ->
        OshiAlertDialog(
            onDismissRequest = { botAlert = null },
            title = if (alert.botName.isNotBlank()) {
                { Text(alert.botName) }
            } else null,
            text = { Text(alert.text) },
            confirmButton = {
                TextButton(onClick = { botAlert = null }) {
                    Text(stringResource(R.string.bot_alert_ok))
                }
            }
        )
    }
    botWebApp?.let { session ->
        BotWebAppDialog(
            botName = session.botName,
            url = session.url,
            initData = session.initData,
            onDismiss = { viewModel.dismissBotWebApp() }
        )
    }
    // __WALLPAPER_PERSIST_2026_11_21__ Groups and channels had no wallpaper of
    // their own: only the image an admin can push to every member (group
    // settings), and no way in from the chat. Now: this user's choice for this
    // group (⋮ → Chat Wallpaper, this device only), else the admin's shared one,
    // else the app-wide default, exactly as a 1:1 chat falls back to it.
    val personalWallpaper by viewModel.personalWallpaper.collectAsState()
    val chosenDefaultWallpaper by viewModel.chosenDefaultWallpaper.collectAsState()
    val syncedGroupWallpaper = remember(group?.groupWallpaperData) {
        group?.groupWallpaperData?.takeIf { it.isNotEmpty() }
            ?.let { com.oshi.messenger.ui.components.chatWallpaperFromBytes(it) }
    }
    val groupWallpaper: com.oshi.messenger.ui.components.ChatWallpaper? =
        personalWallpaper ?: syncedGroupWallpaper ?: chosenDefaultWallpaper
    var showWallpaperPicker by remember { mutableStateOf(false) }
    // __CHAT_BACKDROP_BLUR_2026_11_21__ live frost of the thread under the bars.
    val liveBackdrop = com.oshi.messenger.ui.components.rememberLiveChatBackdrop()
    // The thread in the wallpaper's tone, one scheme for the whole screen
    // (__ONE_SCHEME_FOR_THE_WHOLE_CHAT_2026_11_07__, as in the 1:1 chat).
    val groupToneContext = LocalContext.current
    val groupChatDark = groupWallpaper?.let {
        com.oshi.messenger.ui.components.WallpaperToneResolver.resolve(
            it,
            com.oshi.messenger.ui.components.WallpaperTonePrefs.tone(groupToneContext, groupId),
            isDarkScheme()
        ).contentDark
    } ?: isDarkScheme()
    if (showWallpaperPicker) {
        com.oshi.messenger.ui.components.WallpaperPickerSheet(
            conversationId = groupId,
            currentWallpaper = groupWallpaper ?: com.oshi.messenger.ui.components.ChatWallpaper.Default,
            onSelectWallpaper = { viewModel.setPersonalWallpaper(it) },
            onDismiss = { showWallpaperPicker = false },
            note = stringResource(R.string.wallpaper_personal_note),
            onReset = if (personalWallpaper != null) { { viewModel.clearPersonalWallpaper() } } else null,
        )
    }
    com.oshi.messenger.ui.components.WithChatScheme(dark = groupChatDark) {
    Scaffold(
        modifier = Modifier.extendUp(statusBarUnderlap),
        snackbarHost = { SnackbarHost(botSnackbarHost) },
        topBar = {
            // __CHAT_BACKDROP_BLUR_2026_11_21__ The bar, the search field and the
            // banners (search count, "must update", pinned post) are one frosted
            // band the thread scrolls under — as in the 1:1 chat.
            Box {
            com.oshi.messenger.ui.components.FrostedChatBarBackdrop(
                wallpaper = groupWallpaper,
                scrimColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.matchParentSize(),
                live = liveBackdrop
            )
            Column {
                Spacer(modifier = Modifier.height(statusBarUnderlap))
                GroupChatTopBar(
                    group = group,
                    typingText = typingText,
                    onBack = onBack,
                    onGroupInfo = { showGroupInfoSheet = true },
                    onToggleMute = { viewModel.toggleMute() },
                    onReport = { showReportGroupDialog = true },
                    onSearchToggle = { viewModel.setSearchActive(!isSearchActive) },
                    searchActive = isSearchActive,
                    onNavigateToMap = onNavigateToMap,
                    onNavigateToAIChat = onNavigateToAIChat,
                    onWallpaper = { showWallpaperPicker = true },
                    // __BOTS_PHASE1_2026_11_24__ admins add bots (with rights) to their group / channel.
                    onAddBot = if (group != null && groupsViewModel.isGroupAdmin(group!!.id)) ({ showAddBot = true }) else null,
                    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ any member, not only admins.
                    advPrivacy = advPrivacy,
                    onToggleAdvPrivacy = { on -> viewModel.setAdvancedChatPrivacy(on) }
                )
                // Search bar with slide animation
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.searchMessages(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text(stringResource(R.string.group_chat_search_placeholder)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.searchMessages("") }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.accessibility_clear_search),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandColor,
                                cursorColor = BrandColor
                            )
                        )
                    }
                }
                // Search results count
                if (isSearchActive && searchQuery.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            // Was an inline "add an s unless the count is
                            // exactly 1" - English grammar hardcoded into a
                            // 34-language app. No other plural rule in the
                            // world works that way; Arabic has six forms.
                            text = pluralStringResource(
                                R.plurals.search_results_found,
                                searchResults.size,
                                searchResults.size
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            // Pinned message banner (task 3.8.4)
            val pinnedId = group?.pinnedMessageId
            // Case-insensitive, same reason as the reply-quote lookup below: an iPhone's
            // `pinnedMessageId` comes out of `UUID.uuidString`, i.e. UPPERCASE, while an
            // Android-originated message id is stored lowercase.
            val pinnedMessage = if (pinnedId != null) {
                messages.find { it.id.equals(pinnedId, ignoreCase = true) }
            } else null
            val myPubKey = myPublicKey
            val isAdmin = group?.let { g ->
                myPubKey == g.creatorPublicKey || g.adminPublicKeys.contains(myPubKey)
            } ?: false
            val mustUpdate = group?.let { g ->
                unreachableByGroup.entries.firstOrNull { it.key.equals(g.id, ignoreCase = true) }?.value
            }.orEmpty()
            if (mustUpdate.isNotEmpty()) {
                val names = mustUpdate.joinToString(", ") { k -> contactsMap[k]?.takeIf { it.isNotBlank() } ?: (k.take(8) + "…") }
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.group_member_must_update, names),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            if (pinnedMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            // NOT the brand, and not even the lightened brand.
                            // This banner's ground is a SATURATED brand
                            // container (`primaryContainer` = OSHIPurpleDark
                            // #4410D9 in dark), and #5616EC on #4410D9 is
                            // **1.35:1** — the two hexes are 8 % apart, so the
                            // pin was a brand shape on a brand ground. The
                            // container has an `onPrimaryContainer` for exactly
                            // this, and it is what the label beside it already
                            // used: 10.4:1.
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pinnedMessage.content.take(80),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isAdmin) {
                            IconButton(
                                onClick = { viewModel.unpinMessage() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.accessibility_unpin),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
            }
            }
        },
        bottomBar = {
            Box {
            com.oshi.messenger.ui.components.FrostedChatBarBackdrop(
                wallpaper = groupWallpaper,
                scrimColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.matchParentSize(),
                edgeOnTop = true,
                live = liveBackdrop
            )
            // __BOTS_PHASE1_2026_11_24__ ↑ ↓ Return Tab Esc drive the `/` picker on a hardware keyboard.
            val slashState = com.oshi.messenger.ui.components.rememberSlashPickerState()
            val slashSend: (com.oshi.messenger.util.SlashCommandCatalog.Entry) -> Unit = { entry ->
                viewModel.sendBotCommand(entry)
                messageText = ""
                groupsViewModel.sendGroupTyping(groupId, false)
            }
            Column(modifier = Modifier.navigationBarsPadding().imePadding().slashPickerKeys(
                text = messageText,
                botCommands = botCommands,
                state = slashState,
                onSend = slashSend,
                onInsert = { messageText = it },
            )) {
                // __KEYBOARD_PRIVACY_2026_10_01__ Third-party keyboard warning: it can read
                // what is typed here before OSHI encrypts it. Renders nothing when trusted.
                com.oshi.messenger.ui.keyboard.ThirdPartyKeyboardBanner()
                // 🛡 __STRICT_MODE_2026_11_22__ strict mode on + an app that can read/drive the screen.
                StrictModeThreatBanner()
                val gsAll by groupsViewModel.groupSettings.collectAsState()
                val gsHere = group?.let { gsAll[it.id.uppercase()] }
                val composerBlockedBy: Int? = when {
                    gsHere?.dissolved == true -> R.string.gs_dissolved_banner
                    gsHere != null && group != null && !gsHere.mayPost(groupsViewModel.isGroupAdmin(group!!.id)) -> R.string.gs_only_admins_banner
                    else -> null
                }
                // Smart Replies Bar. __INTEROP_2026_11_21__ not offered to someone who may not
                // post here (a channel member saw "AI Quick Replies" above the admins-only banner).
                if (messageText.isEmpty() && composerBlockedBy == null && !advPrivacy) {
                    SmartRepliesBar(
                        replies = nlpState.smartReplies,
                        onReplySelected = { reply ->
                            messageText = reply
                            nlpViewModel.clearSmartReplies()
                        }
                    )
                }
                if (composerBlockedBy == null) AIQuickBar(
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
                // Reply-to banner
                if (replyToMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .background(BrandColor, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = replyToMessage!!.senderName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = brandInk(),
                                    maxLines = 1
                                )
                                Text(
                                    text = com.oshi.messenger.util.MessageFormatting.strip(replyToMessage!!.content),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyToMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.accessibility_cancel_reply),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                // __MENTIONS_2026_09_23__ `@` picker while an `@query` is typed (cursor = end of draft).
                val mentionQuery = remember(messageText) {
                    com.oshi.messenger.util.MentionWire.activeQuery(messageText, messageText.length)
                }
                val mentionChoices = remember(mentionQuery, group) {
                    mentionQuery?.let { q ->
                        com.oshi.messenger.ui.components.mentionCandidates(viewModel.mentionCandidates(), q.second)
                    }.orEmpty()
                }
                com.oshi.messenger.ui.components.MentionPicker(
                    candidates = mentionChoices,
                    onPick = { m ->
                        mentionQuery?.let { q ->
                            pickedMentions = pickedMentions + m
                            messageText = com.oshi.messenger.ui.components.insertMention(messageText, q.first, m)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                // __CASHU_2026_10_25__ The `/` menu, same place as the mention
                // picker it sits beside. `/pay` was undiscoverable before this.
                if (showNoBotHint) {
                    // iOS `noBotHintBanner` (GroupViews.swift:5884): "there is no bot"
                    // must not look the same as "the bot is broken".
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.82f))
                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.bot_hint_none_in_group),
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.dismissNoBotHint() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                com.oshi.messenger.ui.components.SlashCommandBar(
                    text = messageText,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    botCommands = botCommands,
                    // __BOT_COMMANDS_2026_11_24__ a bot command is sent on tap, as in Telegram.
                    onSend = slashSend,
                    state = slashState,
                ) { completion -> messageText = completion }
                // __BOT_COMMANDS_2026_11_24__ "Commands" pill: shown while the composer is
                // empty in a conversation that has a bot, so the menu is findable without
                // knowing to type `/`.
                if (botsHere.isNotEmpty() && messageText.isEmpty() && composerBlockedBy == null) {
                    com.oshi.messenger.ui.components.BotCommandsButton(
                        onClick = { showBotCommands = true },
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                    )
                }
                // __GROUP_SETTINGS_2026_11_20__ channel mode / dissolved group (spec §2.4, §2.9):
                // the composer is replaced by a banner for anyone the group does not let post
                // (composerBlockedBy is computed at the top of this bottom bar).
                if (composerBlockedBy != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("group_composer_blocked")) {
                        Text(stringResource(composerBlockedBy), modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else GroupMessageInput(
                    messageText = messageText,
                    mentionHighlight = if (pickedMentions.isEmpty()) null
                        else com.oshi.messenger.ui.components.MentionHighlight(pickedMentions, BrandColor),
                    isRecording = isRecording,
                    recordingDurationMs = recordingDuration,
                    onMessageChange = {
                        messageText = it
                        viewModel.onTextChanged(it)
                        // ⌨️ Group typing must ride the GROUP envelope: that is iOS's only
                        // ingress (GroupMessaging.swift:2905-2912). The 1:1 ping the
                        // TypingIndicatorManager sends can never reach an iPhone.
                        groupsViewModel.sendGroupTyping(groupId, it.isNotEmpty())
                    },
                    onSend = {
                        if (com.oshi.messenger.util.CashuPayCommand.handles(messageText)) {
                            // __CASHU_2026_10_24__ `/pay <token> [public]` in a group.
                            // isGroup = true is the whole difference: everyone who
                            // can read this thread can spend the token, and the first
                            // to redeem wins. So the plain form does NOT send — it
                            // says what is about to be exposed and asks for the word
                            // `public`. One extra word against giving a stranger
                            // money by reflex.
                            val ctx = context
                            when (val r = com.oshi.messenger.util.CashuPayCommand.parse(messageText, isGroup = true)) {
                                is com.oshi.messenger.util.CashuPayCommand.Outcome.Send -> {
                                    viewModel.sendMessage(r.token.raw, mentions = emptyList())
                                    com.oshi.messenger.ui.components.cashuToast(
                                        ctx, ctx.getString(R.string.cashu_pay_sent, r.summary))
                                    messageText = ""
                                }
                                is com.oshi.messenger.util.CashuPayCommand.Outcome.NeedsPublicConfirm ->
                                    com.oshi.messenger.ui.components.cashuToast(
                                        ctx, ctx.getString(R.string.cashu_pay_public_confirm, r.summary))
                                // `/pay` alone opens the send sheet rather than explaining syntax.
                                com.oshi.messenger.util.CashuPayCommand.Outcome.Usage -> {
                                    cashuDraft = ""
                                    messageText = ""
                                }
                                com.oshi.messenger.util.CashuPayCommand.Outcome.NotAToken ->
                                    com.oshi.messenger.ui.components.cashuToast(
                                        ctx, ctx.getString(R.string.cashu_pay_not_token))
                                com.oshi.messenger.util.CashuPayCommand.Outcome.Invalid ->
                                    com.oshi.messenger.ui.components.cashuToast(
                                        ctx, ctx.getString(R.string.cashu_pay_invalid))
                            }
                        } else if (messageText.isNotBlank() &&
                            com.oshi.messenger.util.CashuTokens.containsToken(messageText)
                        ) {
                            // Whatever brought it here — pasted, forwarded, typed —
                            // a bearer token in a group is spendable by everyone who
                            // can read the thread, first come first served.
                            cashuAboutToGoPublic = messageText
                        } else if (messageText.isNotBlank()) {
                            // 💬 Wrap with reply metadata exactly like iOS's group composer
                            // (GroupViews.swift:4695-4701 → `MessageWithReply.toMessageString()`).
                            // Before this the banner was cosmetic: `replyToId` never left the
                            // device, so the quote was lost on iPhones AND on other Androids.
                            val outgoing = replyToMessage?.let { quoted ->
                                GroupManager.buildReplyEnvelope(
                                    content = messageText,
                                    // iOS finds the quoted photo's thumbnail by the exact
                                    // `uuidString` (UPPERCASE); Android matches either case.
                                    originalMessageId = GroupManager.canonicalGroupId(quoted.id),
                                    originalSenderKey = quoted.senderPublicKey,
                                    originalText = quoted.content,
                                    originalTimestampMs = quoted.timestamp,
                                    originalMediaType = iosGroupMediaTypeRaw(quoted.mediaType)
                                )
                            } ?: messageText
                            viewModel.sendMessage(outgoing, mentions = pickedMentions)
                            pickedMentions = emptyList()
                            // Clear the peer's indicator immediately (iOS does the same on send).
                            groupsViewModel.sendGroupTyping(groupId, false)
                            messageText = ""
                            replyToMessage = null
                            keyboardController?.hide()
                        }
                    },
                    onAttach = { showAttachmentMenu = true },
                    onCamera = { launchGroupCamera() },
                    onEmoji = { showEmojiPicker = !showEmojiPicker },
                    onSchedule = {
                        if (messageText.isBlank()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.chat_schedule_empty),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showScheduleSheet = true
                        }
                    },
                    onStartRecording = {
                        if (audioRecorder.hasRecordPermission()) {
                            isRecording = audioRecorder.startRecording()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopRecording = {
                        val result = audioRecorder.stopRecording()
                        isRecording = false
                        result?.let { (audioData, duration) ->
                            if (duration < 1.0f) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.chat_voice_too_short),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                viewModel.sendVoiceMessage(audioData, duration)
                            }
                        }
                    },
                    onCancelRecording = {
                        audioRecorder.cancelRecording()
                        isRecording = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_voice_canceled),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // (N17) The round video note is opened from the attachment
                    // sheet now; this bar's slot is the camera (`onCamera`).
                )
            }
            }
        }
    ) { padding ->
        // __CHAT_BACKDROP_BLUR_2026_11_21__ The thread fills the screen, under both
        // bars; `padding` becomes the list's content padding.
        val density = LocalDensity.current
        val barTopPx = with(density) { padding.calculateTopPadding().toPx() }
        val barBottomPx = with(density) { padding.calculateBottomPadding().toPx() }
        val imeInsets = WindowInsets.ime
        val navInsets = WindowInsets.navigationBars
        Box(
            modifier = Modifier
                .frostedBackdropSource(
                    enabled = liveBackdrop,
                    topPx = { barTopPx },
                    bottomPx = { barBottomPx },
                    bottomCoveredPx = {
                        (imeInsets.getBottom(density) - navInsets.getBottom(density)).toFloat().coerceAtLeast(0f)
                    }
                )
                .fillMaxSize()
        ) {
            // __BUBBLE_MEDIA_2026_11_21__ (BUBBLE_MEDIA_SPEC §2) the conversation
            // ground the 1:1 thread already has: a soft lavender ramp in light,
            // neutral near-black in dark, flat black in OLED.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            Oshi.Chat.groundColors(isDarkScheme(), com.oshi.messenger.ui.theme.isOledScheme())
                        )
                    )
            )
            // __WALLPAPER_PERSIST_2026_11_21__ The wallpaper: this user's own choice for
            // the group, else the one an admin set for everyone, else the app-wide
            // default once the user has chosen one. Drawn under the empty state too
            // (it used to exist only once there were messages).
            groupWallpaper?.let { wp ->
                com.oshi.messenger.ui.components.ChatWallpaperBackground(
                    wallpaper = wp,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (messagesLoaded && messages.isEmpty()) {
                // Was a 64 dp glyph at 50% alpha over two paragraphs of default
                // body text, while the 1:1 thread three metres away had a glyph
                // in a tinted disc, a semibold title and a muted subtitle. Two
                // designs for the same state in the same app is what "looks
                // unfinished" means. Both now draw the same component.
                OshiEmptyState(
                    icon = Icons.Default.Group,
                    title = stringResource(R.string.group_chat_empty),
                    subtitle = stringResource(R.string.group_chat_start),
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            } else {

                // Messages list (show search results when searching, otherwise all messages)
                val displayMessages = if (isSearchActive && searchQuery.isNotEmpty()) searchResults else messages
                val scrollCoroutineScope = rememberCoroutineScope()
                Box(modifier = Modifier.fillMaxSize()) {
                    // ✅ PERF: precompute the "show date header" boolean per index ONCE per
                    // displayMessages change, instead of inside the LazyColumn lambda where it
                    // re-ran on every recomposition. With thousands of messages, the previous
                    // forEachIndexed { item(key=...) } block was a known Compose anti-pattern:
                    // it forced the lambda to re-execute the entire list (O(n)) every time
                    // any item changed (typing indicator, reaction, scroll position derived
                    // state…) before LazyColumn could decide what to actually compose.
                    // __CHAT_OPEN_PERF_2026_11_14__ one day key per message, computed
                    // once and shared by both flag arrays. `getCalendarDay` built a new
                    // SimpleDateFormat per call and the two arrays called it 4× per row.
                    val dayKeys = remember(displayMessages) {
                        val dayOf = ChatDayKey.inDefaultZone()
                        LongArray(displayMessages.size) { i -> dayOf(displayMessages[i].timestamp) }
                    }
                    val dateHeaderFlags = remember(dayKeys) {
                        BooleanArray(dayKeys.size) { i ->
                            if (i == 0) true
                            else dayKeys[i - 1] != dayKeys[i]
                        }
                    }
                    // Which bubbles END a run, and so get the tailed corner
                    // (iOS BubbleStyleManager.swift:296-304). Same pure rule as
                    // the 1:1 thread, but this LazyColumn is FORWARD-layout, so
                    // the newer neighbour is index + 1 — hence `newestFirst =
                    // false`. Sharing the rule rather than re-deriving it is
                    // what stops the two threads drifting the way their scroll
                    // predicates did (F-2). Safe to remember on `displayMessages`
                    // for the same reason `dateHeaderFlags` is: neither the
                    // sender nor the timestamp of an existing row ever changes.
                    val tailFlags = remember(displayMessages, dayKeys) {
                        ChatThreadItems.tailFlags(
                            size = displayMessages.size,
                            newestFirst = false
                        ) { a, b ->
                            displayMessages[a].senderPublicKey == displayMessages[b].senderPublicKey &&
                                dayKeys[a] == dayKeys[b]
                        }
                    }

                    // __BOT_E2E_2026_09_23__ the seal caption rides under the latest bot message only.
                    val lastBotMessageId = remember(displayMessages) {
                        displayMessages.lastOrNull { it.senderPublicKey.startsWith("bot:") }?.id
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (openSettled || messages.isEmpty()) 1f else 0f }
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(
                            top = 8.dp + padding.calculateTopPadding(),
                            bottom = 8.dp + padding.calculateBottomPadding()
                        )
                    ) {
                        // ✅ PERF: itemsIndexed with stable `key` lets LazyColumn skip
                        // composing off-screen items entirely. Content type lets it reuse
                        // bubble vs. date-header view holders across scroll.
                        itemsIndexed(
                            items = displayMessages,
                            key = { _, message -> message.id },
                            contentType = { _, message ->
                                if (message.senderPublicKey == "system") "system" else "bubble"
                            }
                        ) { index, message ->
                            Column {
                                if (index < dateHeaderFlags.size && dateHeaderFlags[index]) {
                                    DateSectionHeader(timestamp = message.timestamp)
                                }
                                // __OPEN_AT_THE_FIRST_UNREAD__ drawn from the id captured
                                // at open, never from a live `!isRead` (read a beat later).
                                if (message.id == unreadDividerId) {
                                    UnreadMessagesDivider()
                                }
                                if (OSHIControlPayload.matchedPrefix(message.content) == OSHIControlPayload.ADV_PRIVACY) {
                                    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ centred notice naming the member.
                                    val change = remember(message.content) {
                                        com.oshi.messenger.data.model.AdvancedChatPrivacyChange.fromMessageString(message.content)
                                    }
                                    com.oshi.messenger.ui.components.AdvancedChatPrivacyNotice(
                                        on = change?.on ?: true,
                                        actorName = if (message.isFromMe) null
                                            else message.senderName.takeIf { it.isNotBlank() && it != "Me" }
                                                ?: message.senderPublicKey.take(8)
                                    )
                                } else if (message.senderPublicKey == "system") {
                                    // F-4: the catalog answers first, localized, exactly as
                                    // ChatScreen does. MessageSanitizer stays as the residual
                                    // net for what it knows and the catalog does not.
                                    val systemText = OSHIControlPayload.displaySummary(message.content, controlLabels)
                                        ?: MessageSanitizer.sanitizeForDisplay(message.content)
                                    if (systemText.isNotBlank()) SystemEventNotice(text = systemText)
                                } else {
                                    // RP-1 · swipe-right-to-reply, the iOS gesture Android
                                    // never had (`OSHI/GroupViews.swift:8607` attaches
                                    // `SwipeToReply` through `GroupMessageActions`).
                                    // Wrapped rather than passed as a modifier because
                                    // `GroupMessageBubble` owns its own layout.
                                    Box(
                                        modifier = Modifier.swipeToReply(
                                            enabled = !message.isDeleted
                                        ) {
                                            replyToMessage = message
                                        }
                                    ) {
                                    GroupMessageBubble(
                                        message = message,
                                        isOwnMessage = message.senderPublicKey == myPublicKey,
                                        myPublicKey = myPublicKey,
                                        pendingDelivery = pendingDeliveries[message.id],
                                        onPendingTap = { pendingDetailsFor = message.id },
                                        allMessages = messages,
                                        mentions = remember(message.id, mentionsVersion) { viewModel.mentionsFor(message.id) },
                                        controlLabels = controlLabels,
                                        onPollVote = { poll, picks -> viewModel.sendPollVote(poll, picks) },
                                        // __SHARED_NICKNAME_2026_09_22__ folded-key lookup
                                        resolvedSenderName = com.oshi.messenger.data.model.PeerNickname
                                            .lookupSender(contactsMap, message.senderPublicKey)
                                            ?: message.senderName.ifEmpty { message.senderPublicKey.take(8) },
                                        senderNameFor = { key ->
                                            when {
                                                key.isEmpty() -> ""
                                                key.equals(myPublicKey, ignoreCase = true) -> youLabel
                                                else -> com.oshi.messenger.data.model.PeerNickname
                                                    .lookupSender(contactsMap, key)
                                                    ?: group?.id?.let { gid ->
                                                        groupsViewModel.memberDisplayName(gid, key, listMemberNameInputs)
                                                    }
                                                    ?: key.take(8)
                                            }
                                        },
                                        // __REACTION_BAR_ONE_COMPONENT_2026_11_15__ a chip tap
                                        // TOGGLES, as in 1:1: addReaction never removed, so
                                        // tapping your own chip re-sent it.
                                        onReact = { emoji -> viewModel.toggleReaction(message.id, emoji) },
                                        // __OSHI_SMILE_REACTION_2026_11_09__ double-tap
                                        // TOGGLES the OSHI O (was a one-way 👍).
                                        onDoubleTapReact = {
                                            viewModel.toggleReaction(
                                                message.id,
                                                com.oshi.messenger.data.model.OshiReaction.OSHI_SMILE
                                            )
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onLongPress = {
                                            selectedMessage = message
                                            showMessageActions = true
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onContactCardTap = { pk, _ -> onNavigateToChat(pk) },
                                        botInFlightKey = botInFlight[message.id],
                                        onBotCallback = { button -> viewModel.onBotCallbackTap(message, button) },
                                        onBotUrl = { button -> openBotUrl(context, button.url) },
                                        onBotWebApp = { button -> viewModel.onBotWebAppTap(message, button) },
                                        onOpenMedia = { data, type, caption ->
                                            galleryViewerMedia = Triple(data, type, caption)
                                        },
                                        onLoadMedia = { id -> viewModel.loadMediaBytes(id) },
                                        highlightQuery = if (isSearchActive && searchQuery.isNotEmpty()) searchQuery else null,
                                        isTail = index >= tailFlags.size || tailFlags[index],
                                        botSealCaption = if (message.id == lastBotMessageId) botSealCaption else null,
                                        isChannel = isChannelHere,
                                        // __GROUP_COMMENTS_2026_11_21__ the comments bar under an
                                        // admin's post — now INSIDE the post card (BUBBLE_MEDIA_SPEC §2).
                                        footer = if (commentsActiveHere && !message.isDeleted &&
                                            viewModel.isAdminKey(message.senderPublicKey)) {
                                            {
                                                CommentsBar(
                                                    count = commentsByPost[message.id.lowercase()]?.count { !it.isDeleted } ?: 0,
                                                ) { openCommentsFor = message.id }
                                            }
                                        } else null
                                    )
                                    }
                                    // __GROUP_SETTINGS_2026_11_20__ auto-translate (local, spec §2.10)
                                    if (autoTranslateOn && message.senderPublicKey != myPublicKey && message.mediaType == null &&
                                        !message.isDeleted && message.displayContent.isNotBlank()) {
                                        LaunchedEffect(message.id) { viewModel.ensureAutoTranslation(message.id, message.displayContent) }
                                        autoTranslations[message.id]?.let { t ->
                                            Text(t, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 4.dp).testTag("group_auto_translation"))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Floating date header (Telegram-style)
                    // __CHAT_OPEN_PERF_2026_11_14__ not keyed on `firstVisibleItemIndex`
                    // (that recomposed the whole screen per row scrolled past);
                    // `derivedStateOf` tracks `layoutInfo` itself.
                    val floatingDate = remember(displayMessages) {
                        derivedStateOf {
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            val topItem = visibleItems.firstOrNull { it.key is String && !(it.key as String).startsWith("group_date_") }
                            val topKey = topItem?.key as? String
                            val topMessage = if (topKey != null) displayMessages.firstOrNull { it.id == topKey } else null
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
                    // F-2: hide the arrow exactly when the last row is on screen,
                    // the Compose reading of iOS's bottom-anchor sighting.
                    val showScrollToBottom = !isAtBottom && displayMessages.size > 1
                    if (showScrollToBottom) {
                        FloatingActionButton(
                            onClick = {
                                // Instant jump to the newest message (iOS made the
                                // arrow snap rather than glide), then keep following.
                                followNewest.value = true
                                scrollCoroutineScope.launch {
                                    listState.scrollToItem(displayMessages.size - 1)
                                }
                            },
                            containerColor = BrandColor,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = padding.calculateBottomPadding())
                                .padding(16.dp)
                                .size(44.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.accessibility_scroll_to_bottom),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    } // WithChatScheme(groupChatDark)

    // __GROUP_COMMENTS_2026_11_21__ the comment thread of one post (spec §2.12.6).
    openCommentsFor?.let { postId ->
        val post = messages.firstOrNull { it.id.equals(postId, ignoreCase = true) }
        GroupCommentsThread(
            post = post,
            comments = commentsByPost[postId.lowercase()].orEmpty(),
            active = commentsActiveHere,
            myPublicKey = myPublicKey,
            iAmAdmin = group?.let { groupsViewModel.isGroupAdmin(it.id) } == true,
            controlLabels = controlLabels,
            nameFor = { key ->
                when {
                    key.isEmpty() -> ""
                    key.equals(myPublicKey, ignoreCase = true) -> youLabel
                    else -> com.oshi.messenger.data.model.PeerNickname.lookupSender(contactsMap, key)
                        ?: group?.id?.let { gid -> groupsViewModel.memberDisplayName(gid, key, listMemberNameInputs) }
                        ?: key.take(8)
                }
            },
            onReact = { id, emoji -> viewModel.toggleReaction(id, emoji) },
            allowedReactions = group?.let { gsAllForComments[it.id.uppercase()]?.pickerReactions },
            onDelete = { id -> viewModel.deleteMessage(id, forEveryone = true) },
            onSend = { text, replyTo -> viewModel.sendComment(postId, text, replyTo) },
            onDismiss = { openCommentsFor = null },
        )
    }

    // Group Info Bottom Sheet
    if (showGroupInfoSheet && group != null) {
        // __GROUP_MEMBER_NAMES_2026_09_23__ live inputs so a member row shows a name, not an address.
        val memberNameInputs by groupsViewModel.memberNameInputs.collectAsState()
        GroupChatInfoSheet(
            group = group!!,
            myPublicKey = myPublicKey,
            memberName = { key -> groupsViewModel.memberDisplayName(group!!.id, key, memberNameInputs) },
            onDismiss = { showGroupInfoSheet = false },
            onMemberClick = { memberKey ->
                selectedMemberKey = memberKey
                showMemberActions = true
            },
            onOpenGallery = {
                showGroupInfoSheet = false
                showMediaGallery = true
            },
            onRename = { newName -> groupsViewModel.renameGroup(group!!.id, newName) },
            onAddMember = { key -> groupsViewModel.addMemberToGroup(group!!.id, key) },
            // __GROUP_SETTINGS_2026_11_20__
            inviteLink = groupsViewModel.inviteLink(group!!.id),
            onOpenSettings = { showGroupInfoSheet = false; showGroupSettings = true },
            // __GROUP_HEADER_ISLAND_2026_11_24__ the old ⋯ menu's actions, now that the
            // bar shows the group's picture where the ⋯ was.
            quickActions = GroupInfoQuickActions(
                onToggleMute = { viewModel.toggleMute() },
                onWallpaper = { showGroupInfoSheet = false; showWallpaperPicker = true },
                onReport = { showGroupInfoSheet = false; showReportGroupDialog = true },
                onMap = { showGroupInfoSheet = false; onNavigateToMap() },
                onAI = { showGroupInfoSheet = false; onNavigateToAIChat() },
            ),
            advPrivacy = advPrivacy,
            onToggleAdvPrivacy = { on -> viewModel.setAdvancedChatPrivacy(on) },
            advPrivacyOlderCount = remember(group, advPrivacy) { viewModel.advancedPrivacyNonReaderCount() }
        )
    }
    // __GROUP_SETTINGS_2026_11_20__ Telegram-style settings page (GROUP_INFO_SETTINGS_SPEC §5)
    if (showGroupSettings && group != null) {
        val memberNameInputs by groupsViewModel.memberNameInputs.collectAsState()
        GroupSettingsScreen(
            group = group!!,
            groupsViewModel = groupsViewModel,
            memberName = { key -> groupsViewModel.memberDisplayName(group!!.id, key, memberNameInputs) },
            onDismiss = { showGroupSettings = false },
            onOpenChat = { key -> showGroupSettings = false; onNavigateToChat(key) },
            onGroupGone = { showGroupSettings = false; onBack() }
        )
    }

    // Per-chat media gallery (P2 #20)
    if (showMediaGallery) {
        MediaGalleryScreen(
            peerPublicKey = null,
            groupId = groupId,
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

    // AI Writing Assistant Sheet (3 tabs: Improve / Generate / Thinking of you)
    if (showAISheet) {
        com.oshi.messenger.ui.components.AIWritingAssistantSheet(
            initialText = messageText,
            conversationContext = conversationContext,
            contactName = group?.name ?: "",
            onApply = { result ->
                messageText = result
                showAISheet = false
            },
            onDismiss = { showAISheet = false }
        )
    }

    // __ATTACH_PANEL_2027_2026_11_16__ The shared attachment panel — the same component,
    // and the same tiles, as a 1:1 chat. What a group could not do before: GIFs & stickers
    // and Check-In. What it still cannot: view-once (the group wire has no such flag on
    // any platform, so a receiver would keep the photo for ever — see the report).
    if (showAttachmentMenu) {
        val panelActions = listOf(
            com.oshi.messenger.ui.components.attach.AttachAction(
                "gallery", Icons.Rounded.PhotoLibrary, stringResource(R.string.attach_gallery), Color(0xFF0A84FF)
            ) { galleryPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "camera", Icons.Rounded.PhotoCamera, stringResource(R.string.attach_camera), Color(0xFF5E5CE6)
            ) { launchGroupCamera() },
            com.oshi.messenger.ui.components.attach.AttachAction(
                "file", Icons.Rounded.Description, stringResource(R.string.attach_file), Color(0xFFFF9F0A)
            ) { documentPickerLauncher.launch("*/*") },
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
            onSendMedia = { picked, caption, _ -> sendGroupPick(picked, caption, true) },
            onBrowseGallery = {
                galleryPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onCamera = { launchGroupCamera() },
            showViewOnce = false,
        )
    }

    // __GROUP_GIF_2026_11_16__ The 1:1 picker, unchanged; the pick rides the group media path.
    if (showGifPicker) {
        com.oshi.messenger.ui.components.StickerGifPicker(
            onSelected = { bytes, fileName -> viewModel.sendGifMessage(bytes, fileName) },
            onDismiss = { showGifPicker = false }
        )
    }

    // __GROUP_CHECK_IN_2026_11_16__ Same sheet as a 1:1, addressed to the GROUP id: the
    // manager routes every check-in message of the session to the group
    // (CheckInManager.isGroupRecipient), as iOS's group sheet does.
    if (showCheckInSheet) {
        OshiModalBottomSheet(
            onDismissRequest = { showCheckInSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = null
        ) {
            CheckInSheet(
                contactPublicKey = group?.id ?: groupId,
                contactName = group?.name ?: "",
                onDismiss = { showCheckInSheet = false },
                onCheckInStarted = { _ -> showCheckInSheet = false }
            )
        }
    }

    // The sheet states that everyone here can redeem the token, so its Send
    // button IS the group confirmation (the role the dialog plays for a paste).
    cashuDraft?.let { draft ->
        com.oshi.messenger.ui.components.CashuSendSheet(
            initialText = draft,
            isGroup = true,
            onSend = { raw, onDone ->
                viewModel.sendMessage(raw, mentions = emptyList())
                onDone(null)
            },
            onDismiss = { cashuDraft = null },
        )
    }

    // Media preview with caption, before sending (audit G-7). Same gate the 1:1 screen
    // uses: it stands between every image/video picker and `sendMediaMessage`.
    // __IMAGE_CROP_2026_11_02__ (N13) crop the pending group photo in place.
    if (showCrop) {
        val data = pendingImageData
        if (data != null) {
            com.oshi.messenger.ui.components.ImageCropForBytes(
                bytes = data,
                onCancel = { showCrop = false },
                onCropped = { cropped ->
                    pendingImageData = cropped
                    showCrop = false
                },
            )
        } else {
            LaunchedEffect(Unit) { showCrop = false }
        }
    }

    if (showMediaPreview) {
        GroupMediaPreviewDialog(
            imageData = pendingImageData,
            videoData = pendingVideoData,
            onCrop = { showCrop = true },
            onSend = { caption ->
                pendingImageData?.let { data ->
                    viewModel.sendMediaMessage(data, "image", caption)
                }
                pendingVideoData?.let { data ->
                    viewModel.sendMediaMessage(data, "video", caption)
                }
                Toast.makeText(context, context.getString(R.string.chat_sending_document), Toast.LENGTH_SHORT).show()
                pendingImageData = null
                pendingVideoData = null
                showMediaPreview = false
            },
            onDismiss = {
                pendingImageData = null
                pendingVideoData = null
                showMediaPreview = false
            }
        )
    }

    // __VIDEO_NOTE_2026_09_24__ Round video note recorder.
    if (showVideoNoteRecorder) {
        com.oshi.messenger.ui.components.VideoNoteRecorder(
            onDismiss = { showVideoNoteRecorder = false },
            onSend = { bytes, durationMs -> viewModel.sendVideoNote(bytes, durationMs) }
        )
    }

    // Forward Sheet
    if (showForwardSheet && forwardMessage != null) {
        val fwd = forwardMessage!!
        // __GROUP_MEDIA_MEMORY_2026_11_11__ a large document/video is on disk, not on the row.
        val fwdBytes by produceState<ByteArray?>(fwd.mediaData, fwd.id) {
            if (value == null && fwd.mediaType != null) value = viewModel.loadMediaBytes(fwd.id)
        }
        ForwardPickerSheet(
            content = fwd.content,
            mediaType = fwd.mediaType,
            mediaData = fwdBytes,
            videoNoteDurationMs = if (fwd.isVideoNote) (fwd.mediaDurationMs ?: 0L) else null,
            fileName = fwd.mediaFileName,
            onDismiss = { showForwardSheet = false; forwardMessage = null },
            onForwarded = { targetName ->
                Toast.makeText(context, "${context.getString(R.string.message_forwarded)} → $targetName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Location Sheet
    if (showLocationSheet) {
        LocationShareSheet(
            locationManager = locationManager,
            conversationId = groupId,
            onShareLocation = { locationMessage ->
                viewModel.sendLocation(locationMessage.toMessageString())
            },
            onDismiss = { showLocationSheet = false },
            recipientName = group?.name ?: ""
        )
    }

    // __GROUP_RESEND_2026_11_25__ "Sent to X of Y": who is still waiting, and Retry now.
    pendingDetailsFor?.let { id ->
        val entry = pendingDeliveries[id]
        if (entry == null) { pendingDetailsFor = null } else {
            val names = entry.pending.map { key ->
                com.oshi.messenger.data.model.PeerNickname.lookupSender(contactsMap, key)
                    ?: group?.id?.let { gid -> groupsViewModel.memberDisplayName(gid, key, listMemberNameInputs) }
                    ?: key.take(8)
            }.sorted()
            OshiAlertDialog(
                onDismissRequest = { pendingDetailsFor = null },
                title = { Text(stringResource(R.string.group_sent_partial, entry.delivered, entry.total)) },
                text = {
                    Column {
                        Text(
                            if (entry.gaveUp) stringResource(R.string.group_sent_partial_gave_up, entry.pending.size)
                            else stringResource(R.string.group_sent_partial_body, entry.pending.size)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            names.take(20).joinToString(", ") + if (names.size > 20) " …" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.retryPendingDelivery(id); pendingDetailsFor = null }) {
                        Text(stringResource(R.string.group_retry_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDetailsFor = null }) { Text(stringResource(R.string.ok)) }
                }
            )
        }
    }

    // Message Actions Dialog
    // __GROUP_RECEIPTS_2026_11_02__ (N8) Who received and who read this one.
    receiptsFor?.let { m ->
        com.oshi.messenger.ui.components.GroupReceiptsSheet(
            groupId = m.groupId,
            msgId = m.id,
            // `Group` keys its roster as `memberPublicKeys`; there is no
            // `members` list on this platform, unlike iOS's `MessageGroup`.
            members = group?.memberPublicKeys.orEmpty(),
            sender = m.senderPublicKey,
            // The SAME resolver the thread's bubbles use, so the sheet and the
            // messages cannot disagree about what someone is called.
            displayName = { key ->
                when {
                    key.isEmpty() -> ""
                    key.equals(myPublicKey, ignoreCase = true) -> youLabel
                    else -> com.oshi.messenger.data.model.PeerNickname
                        .lookupSender(contactsMap, key) ?: key.take(8)
                }
            },
            onDismiss = { receiptsFor = null }
        )
    }

    if (showMessageActions && selectedMessage != null) {
        // iOS `canPinMessages` == `canAddMembers` (GroupMessaging.swift:123): any member in a
        // collaborative/public group, admins only in an admin_only group. The old check was
        // admin-only for every group type, which is stricter than iOS on both sides.
        val iAmAdmin = group?.canAddMembers(myPublicKey) ?: false

        // 🔴 CAPTURE THE MESSAGE ONCE — this dialog used to crash the app.
        //
        // Every row fires `onDismiss(); onAction()`, and `onDismiss` sets
        // `selectedMessage = null`. A `MutableState` write is visible to the very
        // next read in the same event handler, so a handler that then read
        // `selectedMessage!!` dereferenced the null it had just been handed:
        // long-press an own bubble → Delete, or a received bubble → Translate or
        // Copy, and the app died with a KotlinNullPointerException. Forward read it
        // WITHOUT `!!` and so failed silently instead — a dead button that opened
        // nothing and reported nothing. Edit and Save survived only because they
        // happened to capture into a `?.let`, which is why this outlived the edit pass.
        //
        // iOS has never had it: `groupMessageActionsOverlay` binds `let m =
        // actionMessage` once and every closure uses `m` (`GroupViews.swift:4369-4400`).
        // Capturing here also makes the fix independent of the order the dialog
        // happens to call its callbacks in, which is the part that kept regressing.
        val actionMessage = selectedMessage!!
    // Opt-in privacy switch, read straight from the same DataStore key the
    // Settings screen writes, so the two cannot drift. Default false.
    val blockForwardCopy by remember(context) {
        context.dataStore.data.map { it[SettingsViewModel.KEY_BLOCK_FORWARD_COPY] ?: false }
    }.collectAsState(initial = false)

        // __CHANNEL_WEB_2026_11_26__ n is resolved on tap (admin map, else the public feed).
        val webHandle = viewModel.channelWebHandle()
        val webPost = webHandle != null && !actionMessage.isDeleted &&
            actionMessage.mediaType != MediaType.LOCATION && actionMessage.mediaType != MediaType.CONTACT &&
            !OSHIControlPayload.isControl(actionMessage.content) && !actionMessage.senderPublicKey.startsWith("bot:")
        fun withWebN(action: (String, Int) -> Unit): (() -> Unit)? = if (!webPost) null else {
            {
                val id = actionMessage.id
                showMessageActions = false
                selectedMessage = null
                groupMediaScope.launch {
                    val n = viewModel.resolveChannelWebN(id)
                    if (n == null) Toast.makeText(context, context.getString(R.string.cw_not_on_web), Toast.LENGTH_LONG).show()
                    else action(webHandle!!, n)
                }
            }
        }
        GroupMessageActionsDialog(
            onWebCopyLink = withWebN { h, n ->
                clipboardManager.setText(AnnotatedString(com.oshi.messenger.service.channelweb.ChannelWebLinks.postUrl(h, n)))
                Toast.makeText(context, context.getString(R.string.gs_link_copied), Toast.LENGTH_SHORT).show()
            },
            onWebShareLink = withWebN { h, n ->
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, com.oshi.messenger.service.channelweb.ChannelWebLinks.postUrl(h, n))
                context.startActivity(android.content.Intent.createChooser(i, null))
            },
            onWebEmbed = withWebN { h, n -> channelEmbedFor = h to n },
            blockForwardCopy = blockForwardCopy,
            allowedReactions = group?.let { gsAllForComments[it.id.uppercase()]?.pickerReactions },
            message = actionMessage,
            isOwnMessage = actionMessage.senderPublicKey == myPublicKey,
            myPublicKey = myPublicKey,
            onDismiss = {
                showMessageActions = false
                selectedMessage = null
            },
            // __GROUP_RECEIPTS_2026_11_02__ (N8) own messages only.
            onInfo = if (actionMessage.senderPublicKey == myPublicKey) {
                {
                    receiptsFor = actionMessage
                    showMessageActions = false
                    selectedMessage = null
                }
            } else null,
            onReactWith = { emoji ->
                // __REACTION_BAR_ONE_COMPONENT_2026_11_15__ toggle, as in 1:1: the
                // highlighted reaction in the picker removes it when tapped.
                viewModel.toggleReaction(actionMessage.id, emoji)
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                showMessageActions = false
                selectedMessage = null
            },
            onReply = {
                replyToMessage = actionMessage
                showMessageActions = false
                selectedMessage = null
            },
            onCopy = {
                // H-4, group half. Copy put the raw wire body on the clipboard, so
                // pasting a shared location anywhere else produced
                // `📍LOCATION📍{"latitude":…}`. iOS routes the same action through
                // the catalog and guards on the result (`ChatView.swift:4539-4553`):
                // copying a silent payload copies NOTHING and shows no confirmation.
                val displayContent =
                    OSHIControlPayload.previewText(actionMessage.content, controlLabels)
                if (displayContent.isNotEmpty()) {
                    // __CLIPBOARD_HYGIENE_2026_10_01__ decrypted message ⇒ sensitive clip, cleared after 60 s.
                    if (com.oshi.messenger.security.SensitiveClipboard.copyMessageText(context, displayContent))
                        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }
                showMessageActions = false
                selectedMessage = null
            },
            onReplyInPrivate = {
                val senderKey = actionMessage.senderPublicKey
                showMessageActions = false
                selectedMessage = null
                onNavigateToChat(senderKey)
            },
            onAddToContacts = {
                val msg = actionMessage
                viewModel.addContact(msg.senderPublicKey, msg.senderName.ifEmpty { "${msg.senderPublicKey.take(8)}..." })
                Toast.makeText(context, context.getString(R.string.group_add_to_contacts), Toast.LENGTH_SHORT).show()
                showMessageActions = false
                selectedMessage = null
            },
            onSave = selectedMessage?.takeIf {
                com.oshi.messenger.security.StrictModeGate.mediaExportAllowed &&  // 🛡 __STRICT_MODE_2026_11_22__
                    // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ others' media stays in OSHI.
                    com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.mediaSaveAllowed(advPrivacy, it.isFromMe)
            }?.let { msg ->
                val mediaData = msg.mediaData
                if (mediaData != null && (msg.mediaType == MediaType.IMAGE || msg.mediaType == MediaType.VIDEO)) {
                    {
                        val saved = saveMediaToGallery(context, mediaData, msg.mediaType)
                        val toastMsg = if (saved) context.getString(R.string.save_to_gallery) + " ✓"
                            else context.getString(R.string.chat_failed_load_image)
                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                    }
                } else if (msg.mediaType == MediaType.VIDEO) {
                    // __GROUP_MEDIA_MEMORY_2026_11_11__ large video kept on disk: read, then save.
                    {
                        groupMediaScope.launch {
                            val bytes = viewModel.loadMediaBytes(msg.id)
                            val saved = bytes != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                saveMediaToGallery(context, bytes, msg.mediaType)
                            }
                            Toast.makeText(context,
                                if (saved) context.getString(R.string.save_to_gallery) + " ✓"
                                else context.getString(R.string.chat_failed_load_image),
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                } else null
            },
            onForward = {
                forwardMessage = actionMessage
                showForwardSheet = true
                showMessageActions = false
                selectedMessage = null
            },
            onPin = if (iAmAdmin) {
                {
                    viewModel.pinMessage(actionMessage.id)
                    showMessageActions = false
                    selectedMessage = null
                }
            } else null,
            // RP-2 · Translate, sanitised through the control-payload catalog first —
            // the same guard iOS applies at `GroupViews.swift:4431` so a shared location
            // never asks the model to translate its own coordinate JSON.
            // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ translation is on-device AI: hidden in a protected group.
            onTranslate = if (advPrivacy) null else {
                {
                translateMessageContent =
                    OSHIControlPayload.previewText(actionMessage.content, controlLabels)
                viewModel.translateMessage(translateMessageContent)
                showTranslateSheet = true
                showMessageActions = false
                selectedMessage = null
                }
            },
            // ✏️ RP-4, second half · Edit for everyone. Offered only when the SENDER's
            // own predicate agrees, so the menu can never present an action
            // `broadcastMessageEdit` would refuse.
            onEdit = actionMessage.takeIf { viewModel.canEdit(it) }?.let { msg ->
                {
                    editingMessage = msg
                    editDraft = msg.displayContent
                    showMessageActions = false
                    selectedMessage = null
                }
            },
            // RP-4 · Delete for everyone (own messages only, enforced in the dialog too).
            onDelete = {
                viewModel.deleteMessage(actionMessage.id, forEveryone = true)
                showMessageActions = false
                selectedMessage = null
            }
        )
    }

    channelEmbedFor?.let { (h, n) ->
        ChannelEmbedDialog(handle = h, n = n, onDismiss = { channelEmbedFor = null })
    }

    // ✏️ Edit dialog (RP-4, second half)
    editingMessage?.let { target ->
        GroupEditMessageDialog(
            draft = editDraft,
            onDraftChange = { editDraft = it },
            onDismiss = { editingMessage = null; editDraft = "" },
            onSave = {
                // `editMessage` re-checks ownership and the 10-minute window: this dialog
                // can sit open past the deadline, and iOS enforces the same guard on its
                // own sender (`OSHI/GroupMessaging.swift:1304-1310`).
                val ok = viewModel.editMessage(target.id, editDraft)
                if (!ok) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.group_edit_window_expired),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                editingMessage = null
                editDraft = ""
            }
        )
    }

    // Translate Bottom Sheet — RP-2, groups
    if (showTranslateSheet) {
        // Same DataStore key as the message-actions dialog: "Block forward and
        // copy" also removes Copy from the translation (iOS parity).
        val blockTranslationCopy by remember(context) {
            context.dataStore.data.map { it[SettingsViewModel.KEY_BLOCK_FORWARD_COPY] ?: false }
        }.collectAsState(initial = false)
        GroupTranslateBottomSheet(
            messageContent = translateMessageContent,
            state = groupTranslationState,
            onRetry = { viewModel.translateMessage(translateMessageContent) },
            onCopy = if (blockTranslationCopy) null else { translated ->
                // __CLIPBOARD_HYGIENE_2026_10_01__ a translation is the message in clear ⇒ same hygiene.
                if (com.oshi.messenger.security.SensitiveClipboard.copyMessageText(context, translated))
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showTranslateSheet = false
                viewModel.clearTranslation()
            }
        )
    }


    // Member Actions Dialog (from info sheet)
    if (showMemberActions && selectedMemberKey != null) {
        val selKey = selectedMemberKey!!
        val currentGroup = group
        val isMeAdmin = currentGroup != null &&
            (currentGroup.adminPublicKeys.contains(myPublicKey) || currentGroup.creatorPublicKey == myPublicKey)
        val isMemberAdmin = currentGroup?.adminPublicKeys?.contains(selKey) == true
        val isMemberCreator = currentGroup?.creatorPublicKey == selKey
        MemberActionsDialog(
            onDismiss = {
                showMemberActions = false
                selectedMemberKey = null
            },
            onReplyInPrivate = {
                showMemberActions = false
                selectedMemberKey = null
                showGroupInfoSheet = false
                onNavigateToChat(selKey)
            },
            onAddToContacts = {
                viewModel.addContact(selKey, "${selKey.take(8)}...")
                Toast.makeText(context, context.getString(R.string.group_add_to_contacts), Toast.LENGTH_SHORT).show()
                showMemberActions = false
                selectedMemberKey = null
            },
            onCopyKey = {
                clipboardManager.setText(AnnotatedString(selKey))
                Toast.makeText(context, context.getString(R.string.group_chat_key_copied), Toast.LENGTH_SHORT).show()
                showMemberActions = false
                selectedMemberKey = null
            },
            // __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__
            onBlockMember = {
                viewModel.blockMember(selKey)
                Toast.makeText(context, context.getString(R.string.group_member_blocked), Toast.LENGTH_LONG).show()
                showMemberActions = false
                selectedMemberKey = null
            },
            onUnblockMember = {
                viewModel.unblockMember(selKey)
                Toast.makeText(context, context.getString(R.string.group_member_unblocked), Toast.LENGTH_SHORT).show()
                showMemberActions = false
                selectedMemberKey = null
            },
            onReportMember = {
                // Keep the key: the dialog below needs it after this one closes.
                reportMemberKey = selKey
                showMemberActions = false
                selectedMemberKey = null
            },
            isMemberBlocked = viewModel.isMemberBlocked(selKey),
            alreadyReportedMember = viewModel.hasReportedMember(selKey),
            isMeAdmin = isMeAdmin,
            isMemberAdmin = isMemberAdmin,
            isMemberCreator = isMemberCreator,
            onPromoteToAdmin = {
                viewModel.promoteToAdmin(selKey)
                showMemberActions = false
                selectedMemberKey = null
            },
            onDemoteFromAdmin = {
                viewModel.demoteFromAdmin(selKey)
                showMemberActions = false
                selectedMemberKey = null
            },
            onRemoveMember = if (currentGroup?.canRemoveMembers(myPublicKey) == true) {
                {
                    val ok = groupsViewModel.removeMemberFromGroup(currentGroup.id, selKey)
                    Toast.makeText(
                        context,
                        context.getString(if (ok) R.string.group_remove_member else R.string.error),
                        Toast.LENGTH_SHORT
                    ).show()
                    showMemberActions = false
                    selectedMemberKey = null
                }
            } else null
        )
    }

    // __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__ one MEMBER, filed
    // as a contact so moderation receives the key it can act on. Reuses the same
    // dialog as the contact and group reports — one definition of what a report
    // asks for.
    reportMemberKey?.let { memberKey ->
        // The same resolver the member rows use — `Group` holds only
        // `memberPublicKeys`, there is no member object with an alias on it.
        // Moderation gets the key either way; the name is what makes the local
        // report log readable.
        val memberNameInputsForReport by groupsViewModel.memberNameInputs.collectAsState()
        val memberName = group?.let {
            groupsViewModel.memberDisplayName(it.id, memberKey, memberNameInputsForReport)
        }
        ReportContactDialog(
            alreadyReported = viewModel.hasReportedMember(memberKey),
            onDismiss = { reportMemberKey = null },
            onSubmit = { reason, details ->
                reportMemberKey = null
                viewModel.reportMember(memberKey, memberName, reason, details) { state ->
                    Toast.makeText(context, context.getString(reportOutcomeText(state)), Toast.LENGTH_LONG).show()
                }
            },
            titleRes = R.string.report_member_dialog_title,
            alreadyFiledRes = R.string.report_member_already_filed,
        )
    }

    // "Je pense à toi" / Thinking of You — group twin of the 1-on-1 sheet; see the
    // long note in `ChatScreen.kt` for what this replaced. The group delivery lane
    // (`isGroup = true` → `GroupManager.sendGroupMessage`) already existed.
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
            onGenerate = { nlpViewModel.generateThinkingOfYou(group?.name ?: "") },
            onSchedule = { text, whenMs, wasAi ->
                scheduledMessageManager.scheduleMessage(
                    recipientKey = groupId,
                    content = text,
                    scheduledTimeMs = whenMs,
                    tone = thinkingTone,
                    isAiGenerated = wasAi,
                    isGroup = true
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
    // 📊 Sondage de groupe — même feuille que dans un 1:1.
    if (showPollSheet) {
        OshiModalBottomSheet(
            onDismissRequest = { showPollSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                com.oshi.messenger.ui.components.PollComposerSheet(
                    creatorPublicKey = myPublicKey,
                    onDismiss = { showPollSheet = false },
                    onSend = { poll -> viewModel.sendPoll(poll) },
                )
            }
        }
    }
    } // CompositionLocalProvider(LocalBubbleAppearance)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatTopBar(
    group: Group?,
    typingText: String? = null,
    onBack: () -> Unit,
    onGroupInfo: () -> Unit,
    onToggleMute: () -> Unit = {},
    onReport: () -> Unit = {},
    onSearchToggle: () -> Unit = {},
    searchActive: Boolean = false,
    onNavigateToMap: () -> Unit = {},
    onNavigateToAIChat: () -> Unit = {},
    onWallpaper: () -> Unit = {},
    onAddBot: (() -> Unit)? = null,
    advPrivacy: Boolean = false,
    onToggleAdvPrivacy: (Boolean) -> Unit = {}
) {
    var showOverflow by remember { mutableStateOf(false) }
    // __GROUP_HEADER_ISLAND_2026_11_24__ back + search, the floating name island,
    // the group's picture on the right (tap = info, long-press = the old ⋯ menu).
    // The frosted backdrop behind the bar (FrostedChatBarBackdrop in the
    // Scaffold's topBar) is still the band the pieces float on.
    val memberCount = group?.memberPublicKeys?.size ?: 0
    val countText = if (group?.effectiveGroupType == com.oshi.messenger.data.model.GroupType.ADMIN_ONLY) {
        pluralStringResource(R.plurals.group_header_subscribers, memberCount, memberCount)
    } else {
        pluralStringResource(R.plurals.group_members_count_plural, memberCount, memberCount)
    }
    val typing = typingText?.takeIf { it.isNotBlank() }
    com.oshi.messenger.ui.components.GroupHeaderBar(
        title = group?.name ?: stringResource(R.string.group_chat_group_fallback),
        subtitle = typing ?: countText,
        subtitleIsActivity = typing != null,
        searchActive = searchActive,
        onBack = onBack,
        onSearch = onSearchToggle,
        onInfo = onGroupInfo,
        onAvatarLongPress = { showOverflow = true },
        avatar = { size -> GroupAvatar(group = group, size = size) },
        menu = {
            OshiDropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_chat_info)) },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = { showOverflow = false; onGroupInfo() }
                )
                if (onAddBot != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.botf_add_bot)) },
                        leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                        onClick = { showOverflow = false; onAddBot() }
                    )
                }
                // __WALLPAPER_PERSIST_2026_11_21__ the way in, from the chat itself,
                // as in the 1:1 chat's ⋯ sheet.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.wallpaper_title)) },
                    leadingIcon = { Icon(Icons.Default.Wallpaper, contentDescription = null) },
                    onClick = { showOverflow = false; onWallpaper() }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (group?.isMuted == true) stringResource(R.string.group_chat_unmute)
                            else stringResource(R.string.group_chat_mute)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (group?.isMuted == true) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                            contentDescription = null
                        )
                    },
                    onClick = { showOverflow = false; onToggleMute() }
                )
                // __REPORT_OUTCOME_2026_09_23__ iOS has "Report group"; Android had the
                // model and the server route but no way to reach them for a group.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_report_group)) },
                    leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                    onClick = { showOverflow = false; onReport() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.accessibility_map_button)) },
                    leadingIcon = { Icon(Icons.Default.Map, contentDescription = null, tint = OSHIPurple) },
                    onClick = { showOverflow = false; onNavigateToMap() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.accessibility_ai_button)) },
                    leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = OSHIPurple) },
                    onClick = { showOverflow = false; onNavigateToAIChat() }
                )
                // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__
                com.oshi.messenger.ui.components.AdvancedChatPrivacyToggle(
                    on = advPrivacy,
                    onToggle = { on -> showOverflow = false; onToggleAdvPrivacy(on) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 300.dp)
                )
            }
        },
    )
}

/**
 * __GROUP_HEADER_ISLAND_2026_11_24__ What the group chat's ⋯ menu used to hold,
 * as a row of tiles at the top of the info sheet (Telegram's info buttons).
 * "Info" itself is the sheet; long-press on the header's avatar still opens
 * the old menu.
 */
private class GroupInfoQuickActions(
    val onToggleMute: () -> Unit,
    val onWallpaper: () -> Unit,
    val onReport: () -> Unit,
    val onMap: () -> Unit,
    val onAI: () -> Unit,
)

@Composable
private fun GroupInfoQuickActionRow(isMuted: Boolean, actions: GroupInfoQuickActions) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("group_info_quick_actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GroupInfoQuickTile(
            icon = if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
            label = stringResource(if (isMuted) R.string.group_chat_unmute else R.string.group_chat_mute),
            onClick = actions.onToggleMute,
        )
        GroupInfoQuickTile(Icons.Default.Wallpaper, stringResource(R.string.wallpaper_title), actions.onWallpaper)
        GroupInfoQuickTile(Icons.Default.Map, stringResource(R.string.accessibility_map_button), actions.onMap)
        GroupInfoQuickTile(Icons.Default.Psychology, stringResource(R.string.accessibility_ai_button), actions.onAI)
        GroupInfoQuickTile(Icons.Default.Flag, stringResource(R.string.chat_report_group), actions.onReport, destructive = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.GroupInfoQuickTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val ink = if (destructive) MaterialTheme.colorScheme.error else brandInk()
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.weight(1f).heightIn(min = 64.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (destructive) ink else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun GroupAvatar(
    group: Group?,
    size: androidx.compose.ui.unit.Dp
) {
    // __GROUP_PHOTO_FLICKERED_2026_11_04__ (Anatole) "la photo du groupe clignote".
    // This used to key `remember`/`LaunchedEffect` on the ByteArray itself, whose
    // equality is by REFERENCE, so every inbound group definition — each one carrying
    // a freshly base64-decoded copy of the very same image — reset the state to null
    // and re-decoded. Opening a group chat asks every member for a full definition,
    // so the photo blinked once per answer. See `rememberAvatarBitmap`.
    val groupBitmap = com.oshi.messenger.ui.components.rememberAvatarBitmap(group?.groupImage)

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val gb = groupBitmap
        if (gb != null) {
            Image(
                bitmap = gb.asImageBitmap(),
                contentDescription = stringResource(R.string.group_chat_group_avatar),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // __ONE_AVATAR_NOT_THREE_2026_11_07__ was BrandColor → BrandColor,
            // i.e. one colour for every group. Keyed on the group id so a room
            // keeps the colour the group LIST already gives it.
            // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ the comment above
            // was not true until now: the list hashed `String.hashCode()` into
            // its own six colours. Both now go through `GroupAvatar`/`groupKey`.
            com.oshi.messenger.ui.theme.GroupAvatar(
                groupId = group?.id ?: "group",
                name = group?.name,
                size = size,
            )
        }
    }
}

@Composable
private fun SystemEventNotice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    isOwnMessage: Boolean,
    myPublicKey: String,
    /** __MENTIONS_2026_09_23__ admitted `@name` targets of this message. */
    mentions: List<com.oshi.messenger.util.MentionWire.Mention> = emptyList(),
    /** The thread, so a reply can quote the message it points at (F-6). */
    allMessages: List<GroupMessage> = emptyList(),
    controlLabels: OSHIControlPayload.Labels = OSHIControlPayload.Labels(),
    resolvedSenderName: String = message.senderName.ifEmpty { message.senderPublicKey.take(8) },
    /** Resolve any member key to a display name, for the reply quote's author line. */
    senderNameFor: (String) -> String = { it.take(8) },
    onReact: (String) -> Unit,
    /** __OSHI_SMILE_REACTION_2026_11_09__ double-tap on the bubble. */
    onDoubleTapReact: () -> Unit = { onReact(com.oshi.messenger.data.model.OshiReaction.OSHI_SMILE) },
    onLongPress: () -> Unit,
    /** 📊 A ballot to cast; the screen knows which group to send it to. */
    onPollVote: (com.oshi.messenger.data.model.PollMessage, List<Int>) -> Unit = { _, _ -> },
    /**
     * Tap on a shared contact card: open (or create) a 1-on-1 chat with the
     * embedded identity. Defaults to a no-op only so previews compile — the live
     * caller passes `onNavigateToChat`.
     */
    onContactCardTap: (publicKey: String, alias: String?) -> Unit = { _, _ -> },
    /**
     * 🤖 Action key of the inline-keyboard button of THIS message that is currently in
     * flight, or null. Non-null also disables every other button of the same keyboard —
     * one tap at a time per bot message.
     */
    botInFlightKey: String? = null,
    onBotCallback: (BotInlineButton) -> Unit = {},
    onBotUrl: (BotInlineButton) -> Unit = {},
    onBotWebApp: (BotInlineButton) -> Unit = {},
    /** Open a video or document full screen: (bytes, "VIDEO"|"DOCUMENT", caption). */
    onOpenMedia: (data: ByteArray, type: String, caption: String) -> Unit = { _, _, _ -> },
    highlightQuery: String? = null,
    /**
     * Last bubble of a run from this sender, so this is the one that gets the
     * tailed corner (iOS `BubbleStyleManager.swift:296-304`). Defaults to `true`
     * so a caller that does not group still renders as before.
     */
    isTail: Boolean = true,
    /** __BOT_E2E_2026_09_23__ BOT_SEAL_SPEC §3 caption, under a bot message's chip. */
    botSealCaption: String? = null,
    /**
     * __GROUP_MEDIA_MEMORY_2026_11_11__ Reads a large document/video that is kept on disk
     * (`mediaData == null`). Called on tap only.
     */
    onLoadMedia: (suspend (String) -> ByteArray?)? = null,
    /**
     * __BUBBLE_MEDIA_2026_11_21__ (BUBBLE_MEDIA_SPEC §1-2) A channel post (only admins
     * post) is a CARD, not a chat line: received surface for everyone (own posts too),
     * no tail, left-aligned, up to 90 % of the chat.
     */
    isChannel: Boolean = false,
    /** Drawn INSIDE the card under a hairline — the channel comments bar. */
    footer: (@Composable () -> Unit)? = null,
    /**
     * __GROUP_RESEND_2026_11_25__ members this own message has not reached yet; non-null replaces
     * the Sent tick with a clock + "X/Y" that opens the details ([onPendingTap]).
     */
    pendingDelivery: com.oshi.messenger.service.GroupPendingDeliveryStore.Entry? = null,
    onPendingTap: () -> Unit = {}
) {
    val isBot = message.senderPublicKey.startsWith("bot:")
    val mediaScope = rememberCoroutineScope()
    // Tap on a bubble whose bytes are in memory → open now; kept on disk → read, then open.
    val openWithBytes: (ByteArray?, String, String) -> Unit = { inMemory, type, caption ->
        if (inMemory != null) onOpenMedia(inMemory, type, caption)
        else onLoadMedia?.let { load ->
            mediaScope.launch { load(message.id)?.let { onOpenMedia(it, type, caption) } }
        }
    }
    val canOpen: (ByteArray?) -> Boolean = { it != null || onLoadMedia != null }
    val alignment = if (isOwnMessage && !isChannel) Alignment.End else Alignment.Start
    // A channel post paints like a received bubble whoever wrote it.
    val outgoingLook = isOwnMessage && !isChannel
    val darkNow = isDarkScheme()
    val accentNow = LocalBubbleAppearance.current.accent
    // Received: the brand blended INTO the system surface rather than Material's
    // default grey, so a group thread reads as this app
    // (BubbleStyleManager.swift:250-268). Opaque, because a translucent bubble
    // over an animated wallpaper shimmers.
    val backgroundColor = if (outgoingLook) BrandColor
        else if (isBot) Color(0xFF1A1A2E)
        else Oshi.Bubble.received(isDarkScheme(), com.oshi.messenger.ui.theme.isOledScheme())
    // __INK_FROM_THE_SAME_SOURCE_AS_THE_PAINT_2026_11_07__ The outgoing bubble
    // is painted with `appearance.accent.brush` (below); its ink must come from
    // that same accent, not from a hard-coded white that happens to match.
    // Agreement by coincidence is how the 1:1 thread ended up white-on-white.
    // The bot bubble keeps its own white: it is painted with a fixed colour, not
    // with the accent.
    val textColor = when {
        outgoingLook -> accentNow.ink(darkNow)
        isBot -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    // Timestamp / edited / tick ink inside the bubble, measured per surface
    // (BubbleContrastTest): the tonal outgoing ramp has its own, the received
    // surface takes the conversation's meta ink.
    val metaColor = when {
        outgoingLook -> accentNow.metaInk(darkNow)
        isBot -> Color.White.copy(alpha = 0.7f)
        else -> Oshi.Chat.metaInk(darkNow)
    }

    // ✏️ An edited message draws its NEW text. iOS puts the same test first in
    // `GroupViews.resolveText` — "Edited content takes priority"
    // (`OSHI/GroupViews.swift:2613-2615`) — and leaves the original body untouched
    // underneath, which is why `GroupMessage.displayContent` and not `content` is the
    // read path here. Every derived value below (`controlPrefix`, the location branch,
    // the sanitiser) therefore sees the edited text, exactly as an iPhone does.
    val bodyText = message.displayContent

    // Strip legacy "[BotName] " prefix if present (old stored messages)
    val displayContent = if (isBot && bodyText.startsWith("[") && bodyText.contains("] ")) {
        bodyText.substringAfter("] ")
    } else {
        bodyText
    }

    // F-4/F-5: detect on the RAW string, before any sanitising. iOS does the same
    // (MediaMessageView.swift:88-90 for the type, GroupViews.swift:2705-2716 for
    // the forward info, which it reads before previewText unwraps the envelope).
    val controlPrefix = remember(displayContent) { OSHIControlPayload.matchedPrefix(displayContent) }
    val forwardInfo = remember(message.content) {
        MessageActionsManager.parseForwardEnvelope(message.content)
    }

    // F-4: the four bubble types the group thread had no renderer for at all -
    // a check-in, a scheduled call, a call summary and the three system notices
    // all collapsed to a flat text bubble in hardcoded English.
    val groupSystemType = remember(controlPrefix) {
        when (controlPrefix) {
            OSHIControlPayload.PROFILE_UPDATE -> SystemMessageType.PROFILE_UPDATE
            OSHIControlPayload.WALLPAPER_UPDATE -> SystemMessageType.WALLPAPER_UPDATE
            OSHIControlPayload.KEY_ROTATION -> SystemMessageType.KEY_ROTATION
            else -> null
        }
    }
    val isCallSummary = controlPrefix == OSHIControlPayload.CALL_SUMMARY ||
        controlPrefix == OSHIControlPayload.MISSED_CALL
    // Both spellings are live on the wire (OSHIControlPayload.swift:98-99).
    val isCheckIn = controlPrefix == OSHIControlPayload.CHECK_IN ||
        controlPrefix == OSHIControlPayload.CHECKIN
    val isScheduledCall = controlPrefix == OSHIControlPayload.SCHEDULED_CALL
    // 📊 Un sondage de groupe. `displayContent` est le corps BRUT ici (seul le
    // préfixe de bot en est retiré), donc la carte peut l'analyser elle-même.
    val isPoll = controlPrefix == OSHIControlPayload.POLL

    if (groupSystemType != null) {
        SystemInfoBubble(type = groupSystemType)
        return
    }
    if (isCallSummary) {
        CallSummaryBubble(content = displayContent, timestamp = message.timestamp, isFromMe = isOwnMessage)
        return
    }
    if (isPoll) {
        com.oshi.messenger.ui.components.PollBubble(
            content = displayContent,
            myPublicKey = myPublicKey,
            onVote = { poll, picks -> onPollVote(poll, picks) },
            // Un groupe sait nommer ses membres, ce qu'un 1:1 ne peut pas faire
            // pour vingt personnes.
            displayName = { key ->
                if (com.oshi.messenger.service.CallAdmissionPolicy.normalizeKey(key) ==
                    com.oshi.messenger.service.CallAdmissionPolicy.normalizeKey(myPublicKey)
                ) controlLabels.you else senderNameFor(key)
            },
        )
        return
    }
    if (isCheckIn) {
        CheckInBubble(content = displayContent, timestamp = message.timestamp)
        return
    }
    if (isScheduledCall) {
        ScheduledCallBubble(content = displayContent, timestamp = message.timestamp)
        return
    }

    // __LONG_PRESS_ANYWHERE_ON_THE_BUBBLE_2026_11_09__ inner tappables give
    // long-press back to the bubble instead of swallowing it (BubbleGestures.kt).
    androidx.compose.runtime.CompositionLocalProvider(
        com.oshi.messenger.ui.components.LocalBubbleLongPress provides onLongPress
    ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {

        // Message bubble — corner geometry follows the user's
        // Settings → Style de bulles preference (Modern / Classic / Minimal).
        // F-13: same appearance source as the 1:1 bubble, so a chosen accent or
        // silhouette does not stop at the group thread.
        val bubbleStyle = LocalBubbleStyle.current
        val appearance = LocalBubbleAppearance.current
        val darkScheme = isDarkScheme()
        // Only the last bubble of a run is tailed, and the tail is on the BOTTOM
        // corner — see BubbleStyle.bubbleShape. `isTail` is passed in by the
        // thread, which is the only thing that can see a bubble's neighbours.
        val bubbleShape = if (isChannel) Oshi.Radius.lgShape
            else bubbleStyle.bubbleShape(isFromMe = isOwnMessage, isTail = isTail)
        val bubbleBrush = if (outgoingLook) appearance.accent.surfaceBrush(darkScheme) else SolidColor(backgroundColor)
        val tonal = outgoingLook && appearance.accent.isTonal(darkScheme)
        // The rim is what keeps a pale bubble from dissolving into a photo
        // wallpaper. It reached the screen only through the silhouette markings
        // until 2026-08-23, i.e. never, for anyone on the default style.
        val bubbleRim = Oshi.Bubble.rimInk(outgoing = outgoingLook || isBot, darkTheme = darkScheme, tonal = tonal)
        // Telegram-style width: a group bubble may run up to ~82% of the screen
        // (iOS raised this from the old ~55%), so a paragraph no longer wraps into
        // a narrow column. Computed from the live screen width rather than a flat
        // dp so it tracks tablets and landscape.
        // __BUBBLE_MEDIA_2026_11_21__ one rule with the 1:1 thread (75 %, cap 420);
        // a channel card 90 %, cap 560 (MediaBubbleSizing).
        val maxBubbleWidth = Oshi.Bubble.maxWidth(LocalConfiguration.current.screenWidthDp, isChannel)
        // __BUBBLE_MEDIA_2026_11_21__ A photo SIZES the bubble (BUBBLE_MEDIA_SPEC §1).
        // Before: `fillMaxWidth().heightIn(max = 250.dp)` inside a 12 dp-padded column —
        // the bubble was always the full max width, every picture was cropped to a
        // ≤ 250 dp-tall strip (a 1080×1920 portrait lost 60 % of its height) and the
        // padding left a band of bubble colour on both sides of it. Now the picture's
        // own aspect decides the box, the bubble is exactly that wide, and the picture
        // bleeds over the padding to the bubble's edges.
        val edgeMediaBytes = message.mediaData?.takeIf {
            message.mediaType == MediaType.IMAGE && it.isNotEmpty() && !message.isDeleted &&
                appearance.silhouette == null
        }
        val mediaSize = edgeMediaBytes?.let { bytes ->
            val natural = remember(message.id, bytes.size) {
                com.oshi.messenger.ui.media.MediaNaturalSize.read(message.id, bytes)
            }
            com.oshi.messenger.ui.media.rememberBubbleMediaSize(natural, isChannel)
        }
        val mediaCaption = if (mediaSize != null && message.content.isNotBlank() && !message.content.startsWith("[")) {
            OSHIControlPayload.displaySummary(message.content, controlLabels)
                ?: MessageSanitizer.sanitizeForDisplay(message.content)
        } else ""
        val mediaOnly = mediaSize != null && mediaCaption.isBlank()
        val hasHeader = (!isOwnMessage && !isBot) || forwardInfo != null || message.replyToId != null
        // The edited marker, the time and (own messages) the tick — once, for the
        // in-bubble row and for the pill over a picture.
        val editedLabel = stringResource(R.string.message_edited).lowercase()
        val readLabel = stringResource(R.string.message_read)
        val sentLabel = stringResource(R.string.message_sent)
        val groupMetaContent: @Composable (Color, Boolean) -> Unit = { ink, onPill ->
            // ✏️ iOS draws the marker off `editedAt != nil`, immediately before the
            // timestamp (`OSHI/GroupViews.swift:2761-2763`). A live location moves by
            // edits; that is not a user edit. __EDITED_MARKER_CASE_2026_11_05__ lowercase
            // (locale-independent) like the 1:1 bubble and iOS.
            if (message.isEdited && !displayContent.startsWith(OSHIControlPayload.LOCATION)) {
                Text(
                    text = editedLabel,
                    fontSize = 10.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = ink
                )
            }
            Text(
                text = formatMessageTime(message.timestamp),
                fontSize = if (onPill) 11.sp else 10.sp,
                color = ink
            )
            if (isOwnMessage && pendingDelivery != null) {
                // __GROUP_RESEND_2026_11_25__ not "Sent" while members are still owed it.
                val partialLabel = stringResource(R.string.group_sent_partial, pendingDelivery.delivered, pendingDelivery.total)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClickLabel = partialLabel, onClick = onPendingTap)
                        .semantics { contentDescription = partialLabel }
                ) {
                    Icon(
                        imageVector = if (pendingDelivery.gaveUp) Icons.Default.ErrorOutline else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(if (onPill) 13.dp else 12.dp),
                        tint = ink
                    )
                    Text(
                        text = "${pendingDelivery.delivered}/${pendingDelivery.total}",
                        fontSize = if (onPill) 11.sp else 10.sp,
                        color = ink,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            } else if (isOwnMessage) {
                Icon(
                    imageVector = if (message.isRead) Icons.Default.DoneAll else Icons.Default.Done,
                    contentDescription = if (message.isRead) readLabel else sentLabel,
                    modifier = Modifier.size(if (onPill) 13.dp else 12.dp),
                    // Was a fixed #4FC3F7 — 1.5:1 on the lavender bubble. Read is the
                    // conversation's read-tick token, measured per surface.
                    tint = when {
                        !message.isRead -> ink
                        // On the photo pill the double tick itself says "read";
                        // green on a 55 % black pill would be 2:1.
                        onPill -> ink
                        outgoingLook && !tonal -> ink
                        else -> Oshi.Chat.readTick(darkScheme)
                    }
                )
            }
        }
        // __VIDEO_NOTE_2026_09_24__ Round video note (docs/VIDEO_NOTE_SPEC.md §4): the
        // circle replaces the bubble; sender line above, time below, reactions as usual.
        if (message.isVideoNote && message.mediaType == MediaType.VIDEO && !message.isDeleted) {
            if (!isOwnMessage && !isBot) {
                Text(
                    text = resolvedSenderName,
                    // __A_NAME_COLOUR_PER_PERSON_2026_11_07__ (Anatole N11) was
                    // brandInk() — one colour for everyone in the room.
                    color = com.oshi.messenger.ui.theme.rememberNameColor(message.senderPublicKey),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            com.oshi.messenger.ui.media.VideoNoteBubble(
                messageId = message.id,
                videoData = message.mediaData,
                durationMs = message.mediaDurationMs,
                onLongPress = onLongPress,
                onDoubleTap = onDoubleTapReact
            )
            Text(
                text = formatMessageTime(message.timestamp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else Box(
            modifier = Modifier
                .then(
                    if (mediaSize != null) Modifier.width(mediaSize.widthDp.dp)
                    else Modifier.widthIn(max = maxBubbleWidth)
                )
                .bubbleSurface(
                    silhouette = appearance.silhouette,
                    brush = bubbleBrush,
                    fallbackShape = bubbleShape,
                    rim = bubbleRim,
                    rimAlpha = Oshi.Bubble.rimAlpha(
                        outgoing = outgoingLook || isBot,
                        darkTheme = darkScheme,
                        tonal = tonal
                    ),
                    gloss = outgoingLook,
                    glass = !outgoingLook && !isBot && com.oshi.messenger.ui.theme.oledGlassOn()
                )
                // Media and the comments bar reach the edges; the shape keeps them inside.
                .then(if (appearance.silhouette == null) Modifier.clip(bubbleShape) else Modifier)
                .combinedClickable(
                    onClick = { },
                    onDoubleClick = onDoubleTapReact,
                    onLongClick = onLongPress
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Telegram-style sender line: the name sits at the TOP of the bubble,
                // in the group accent colour at medium weight, rather than in a grey
                // icon-chip below it. Only for incoming, non-bot messages — an own
                // bubble needs no name and a bot keeps its distinct chip below.
                if (!isOwnMessage && !isBot) {
                    Text(
                        text = resolvedSenderName,
                        // __A_NAME_COLOUR_PER_PERSON_2026_11_07__ (Anatole N11)
                        color = com.oshi.messenger.ui.theme.rememberNameColor(message.senderPublicKey),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                // F-5: a forwarded message must say so. iOS ForwardedBadgeView
                // (MessageActionsManager.swift:579-600), placed INSIDE the bubble
                // above the text (ChatView.swift:4285-4292), fed from the RAW
                // string before the envelope is unwrapped.
                if (forwardInfo != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Forward,
                            contentDescription = null,
                            tint = textColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.message_forwarded),
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Italic,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // F-6: the reply pill used to read "Replying to message" and nothing
                // else - no author, no quoted text. iOS draws the full quote
                // (GroupViews.swift:2691-2730). Only the id is persisted on
                // GroupMessage today, so the quote is resolved from the thread; when
                // the original has scrolled out of the retained window we fall back
                // to the bare label rather than inventing one.
                message.replyToId?.let { replyId ->
                    val quoted = remember(replyId, allMessages) {
                        // Case-insensitive: iOS's GroupMessage.id is a UUID, so a reply
                        // relayed through an iPhone carries the UPPERCASE uuidString of an
                        // id Android minted lowercase. A `==` here degraded every such
                        // quote to the bare "Reply to" label.
                        allMessages.firstOrNull { it.id.equals(replyId, ignoreCase = true) }
                    }
                    // __BUBBLE_MEDIA_2026_11_21__ (BUBBLE_MEDIA_SPEC §2) a tint of the
                    // bubble's own ink, and a 3 dp bar + author line in the QUOTED
                    // person's name colour (their ink on your own bubble, where a name
                    // palette tuned for the received surface is not measured).
                    val quotedInk = if (outgoingLook || isBot || quoted == null) textColor
                        else com.oshi.messenger.ui.theme.AvatarNameColors.forKey(quoted.senderPublicKey, darkScheme)
                    Surface(
                        color = textColor.copy(alpha = 0.07f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (quoted == null) {
                            Text(
                                text = stringResource(R.string.message_reply_to),
                                fontSize = 11.sp,
                                color = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        } else {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(30.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(quotedInk)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = senderNameFor(quoted.senderPublicKey)
                                            .ifEmpty { quoted.senderName },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = quotedInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        // Same rule as 1:1: quote what the catalog
                                        // says, so a quoted call summary reads
                                        // "Outgoing call - 1:23", not a sentinel.
                                        text = com.oshi.messenger.util.MessageFormatting.strip(OSHIControlPayload
                                            .previewText(quoted.content, controlLabels))
                                            .take(80),
                                        fontSize = 11.sp,
                                        color = textColor.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Media or text content
                val ctx = LocalContext.current
                when (message.mediaType) {
                    MediaType.IMAGE -> {
                        // AUDIT M-7, group half. This used to be an undownsampled
                        // `BitmapFactory.decodeByteArray` into `remember(mediaBytes)` —
                        // full-resolution ARGB_8888 for a 250 dp bubble, no shared cache,
                        // and keyed on a ByteArray whose `equals` is IDENTITY, so every
                        // emission re-decoded every visible photo. Same fix as the 1:1
                        // bubble: downsampled, memory-only, keyed by message id.
                        val mediaBytes = message.mediaData
                        if (mediaBytes != null && mediaBytes.isNotEmpty()) {
                            var showFullscreen by remember { mutableStateOf(false) }
                            // __GROUP_GIF_2026_11_16__ A GIF (sticker, mascot, GIPHY) is
                            // FIT, as in the 1:1 bubble: `Crop` cut the transparent
                            // mascots' edges off and zoomed a square sticker into a strip.
                            // Decided from the bytes, like every receiver of a group GIF.
                            val isGif = remember(message.id, mediaBytes.size) {
                                com.oshi.messenger.service.media.GifBytes.isGif(mediaBytes)
                            }
                            if (mediaSize != null) {
                                if (hasHeader) Spacer(modifier = Modifier.height(4.dp))
                                com.oshi.messenger.ui.media.MediaBox(
                                    size = mediaSize,
                                    modifier = Modifier
                                        .bleed(
                                            start = 12.dp, end = 12.dp,
                                            top = if (hasHeader) 0.dp else 8.dp,
                                            bottom = if (mediaOnly && footer == null) 8.dp else 0.dp
                                        )
                                        .bubbleClickable { showFullscreen = true }
                                ) {
                                    com.oshi.messenger.ui.media.BubbleAsyncImage(
                                        messageId = message.id,
                                        data = mediaBytes,
                                        contentDescription = stringResource(R.string.group_chat_image),
                                        modifier = Modifier.fillMaxSize(),
                                        // The box already has the picture's aspect; Crop only
                                        // bites when MediaBubbleSizing clamped it. A GIF keeps
                                        // Fit so a transparent sticker is never trimmed.
                                        contentScale = if (isGif) ContentScale.Fit else ContentScale.Crop
                                    )
                                    if (mediaOnly) {
                                        MediaTimePill {
                                            groupMetaContent(Oshi.Bubble.mediaPillInk, true)
                                        }
                                    }
                                }
                            } else com.oshi.messenger.ui.media.BubbleAsyncImage(
                                messageId = message.id,
                                data = mediaBytes,
                                contentDescription = stringResource(R.string.group_chat_image),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 250.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .bubbleClickable { showFullscreen = true },
                                contentScale = if (isGif) ContentScale.Fit else ContentScale.Crop
                            )
                            if (showFullscreen) {
                                FullscreenImageDialog(
                                    messageId = message.id,
                                    data = mediaBytes,
                                    onDismiss = { showFullscreen = false }
                                )
                            }
                        } else {
                            // Placeholder when image data is unavailable
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    tint = textColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.group_chat_image),
                                    color = textColor.copy(alpha = 0.7f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                        if (message.content.isNotBlank() && !message.content.startsWith("[")) {
                            val sanitized = OSHIControlPayload.displaySummary(message.content, controlLabels)
                                ?: MessageSanitizer.sanitizeForDisplay(message.content)
                            if (sanitized.isNotBlank()) {
                                Spacer(modifier = Modifier.height(if (mediaSize != null) 6.dp else 4.dp))
                                LinkifyText(
                                    text = sanitized,
                                    color = textColor,
                                    linkColor = if (outgoingLook) accentNow.linkInk(darkNow) else null
                                )
                            }
                        }
                    }
                    MediaType.DOCUMENT -> {
                        // __GROUP_MEDIA_TAP_2026_09_27__ This row had no action and the
                        // bubble's own onClick is a no-op, so a group document (from an
                        // iPhone or not) could be neither opened nor saved. Same viewer the
                        // media gallery opens, which offers share for a document.
                        val docData = message.mediaData
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (canOpen(docData)) {
                                Modifier.bubbleClickable {
                                    // The viewer shares a document under this name
                                    // (__SHARE_REAL_NAME_2026_09_27__), not share_media.bin.
                                    openWithBytes(
                                        docData,
                                        MediaType.DOCUMENT.name,
                                        groupDocumentLabel(message.mediaFileName, message.content) ?: "document"
                                    )
                                }
                            } else Modifier
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = groupDocumentLabel(message.mediaFileName, message.content)
                                    ?: stringResource(R.string.group_chat_document_fallback),
                                color = textColor,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    MediaType.VIDEO -> {
                        // __GROUP_MEDIA_TAP_2026_09_27__ Was a label with no action: a
                        // group video could not be played. Opens the full-screen viewer.
                        val videoData = message.mediaData
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (canOpen(videoData)) {
                                Modifier.bubbleClickable { openWithBytes(videoData, MediaType.VIDEO.name, "") }
                            } else Modifier
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.group_chat_video), color = textColor, fontSize = 14.sp)
                        }
                    }
                    MediaType.AUDIO -> {
                        GroupVoiceMessagePlayer(
                            messageId = message.id,
                            audioData = message.mediaData,
                            content = message.content,
                            durationMs = message.mediaDurationMs,
                            isFromMe = isOwnMessage,
                            textColor = textColor,
                            senderName = message.senderName
                        )
                    }
                    MediaType.CONTACT -> {
                        // 🔧 FIX (2026-08-24 three-gaps): a shared contact card in a group
                        // used to fall into the `else` branch and render as text, because
                        // `GroupManager.mapIOSMediaType` returned null for "contact" and
                        // nothing here claimed the type. iOS has drawn one since
                        // `GroupViews.swift:3201`. Same bytes, same codec, same bubble as
                        // the 1-on-1 thread.
                        val card = remember(message.mediaData) {
                            message.mediaData?.let { bytes ->
                                com.oshi.messenger.data.model.ContactCardWire
                                    .decode(String(bytes, Charsets.UTF_8))
                            }
                        }
                        if (card != null) {
                            com.oshi.messenger.ui.components.ContactCardBubble(
                                payload = card,
                                onTap = { onContactCardTap(card.publicKey, card.alias) }
                            )
                        } else {
                            // __CONTACT_CARD_COMPAT_2026_09_27__ Unreadable card bytes:
                            // still openable (share / save) instead of a dead label.
                            val raw = message.mediaData
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = if (raw != null) {
                                    Modifier.bubbleClickable { onOpenMedia(raw, MediaType.DOCUMENT.name, "contact.vcf") }
                                } else Modifier
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.share_contact),
                                    color = textColor,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                    MediaType.LOCATION -> {
                        // `displayContent`, not `content`: live-location ticks arrive as
                        // edits of the first message (__GROUP_LIVE_LOCATION_2026_09_27__).
                        GroupLocationBubble(content = displayContent, context = ctx)
                    }
                    else -> {
                        // Check if content is a location message (fallback: mediaType not set but prefix present)
                        val locationPrefix = OSHIControlPayload.LOCATION
                        // Backward-compat: legacy iOS clients may send voice notes as plain
                        // text with "[AUDIO]" / "MEDIA|||audio|||" prefix and audio bytes in
                        // mediaData. Auto-route to the inline player.
                        val looksLikeAudio = (
                            message.content.startsWith("[AUDIO]") ||
                            message.content.startsWith("MEDIA|||audio|||")
                        ) && message.mediaData != null
                        if (looksLikeAudio) {
                            GroupVoiceMessagePlayer(
                                messageId = message.id,
                                audioData = message.mediaData,
                                content = message.content,
                                durationMs = message.mediaDurationMs,
                                isFromMe = isOwnMessage,
                                textColor = textColor,
                                senderName = message.senderName
                            )
                        } else if (displayContent.startsWith(locationPrefix)) {
                            GroupLocationBubble(content = displayContent, context = ctx)
                        } else {
                        // Regular text message (use displayContent to strip legacy [BotName] prefix)
                        if (displayContent.isNotBlank()) {
                            val sanitized = OSHIControlPayload.displaySummary(displayContent, controlLabels)
                                ?: MessageSanitizer.sanitizeForDisplay(displayContent)
                            if (sanitized.isNotBlank()) {
                                if (!highlightQuery.isNullOrBlank()) {
                                    // 🔴 SEARCH USED TO CRASH ON TURKISH TEXT.
                                    //
                                    // It computed indices on `sanitized.lowercase()` and then
                                    // applied them to `sanitized`. `lowercase()` is NOT
                                    // length-preserving: U+0130 `İ` lowercases to `i` +
                                    // U+0307, one char becoming two (Unicode SpecialCasing).
                                    // Every index after an `İ` shifted right, and
                                    // `substring(idx, idx + len)` then ran past the end —
                                    // StringIndexOutOfBoundsException on the MAIN thread,
                                    // inside composition, as the user typed.
                                    //
                                    // Verified numerically: "İx" is 2 chars and lowercases to
                                    // 3, so a match on "x" reports index 2 and asks for
                                    // substring(2, 3) of a 2-char string.
                                    //
                                    // The fix is to never leave the original string's index
                                    // space: `indexOf(..., ignoreCase = true)` matches
                                    // case-insensitively but returns offsets INTO `sanitized`.
                                    val annotated = buildAnnotatedString {
                                        var start = 0
                                        var idx = sanitized.indexOf(highlightQuery, 0, ignoreCase = true)
                                        while (idx >= 0) {
                                            append(sanitized.substring(start, idx))
                                            // Still clamped: a case-insensitive match can span a
                                            // different number of chars than the query for some
                                            // scripts, and running off the end must not be fatal.
                                            val end = (idx + highlightQuery.length).coerceAtMost(sanitized.length)
                                            if (end <= idx) break   // zero-width match: never loop forever
                                            withStyle(SpanStyle(background = Color(0xFFFFEB3B), color = Color.Black)) {
                                                append(sanitized.substring(idx, end))
                                            }
                                            start = end
                                            idx = sanitized.indexOf(highlightQuery, start, ignoreCase = true)
                                        }
                                        append(sanitized.substring(start))
                                    }
                                    Text(
                                        text = annotated,
                                        color = textColor,
                                        fontSize = 16.sp
                                    )
                                } else if (mentions.isNotEmpty() &&
                                    com.oshi.messenger.util.MentionWire.spans(
                                        com.oshi.messenger.ui.components.BodyMarkup.render(sanitized).text, mentions
                                    ).isNotEmpty()) {
                                    // __MENTIONS_2026_09_23__ picked members highlighted; a tap names them.
                                    com.oshi.messenger.ui.components.MentionText(
                                        text = sanitized,
                                        mentions = mentions,
                                        color = textColor,
                                        tint = if (outgoingLook) accentNow.linkInk(darkNow) else brandInk(),
                                        style = androidx.compose.ui.text.TextStyle(
                                            fontSize = 16.sp,
                                            fontWeight = if (appearance.boldText) FontWeight.SemiBold else null
                                        )
                                    )
                                } else {
                                    LinkifyText(
                                        text = sanitized,
                                        color = textColor,
                                        fontWeight = if (appearance.boldText) FontWeight.SemiBold else null,
                                        linkColor = if (outgoingLook) accentNow.linkInk(darkNow) else null
                                    )
                                }

                                // 📈 Ticker chips — the same chip the 1:1 thread draws,
                                // in the same place, as iOS does in both surfaces.
                                // Nothing is drawn and nothing is fetched unless the
                                // message actually carries an `@TICKER`.
                                if (TickerLinks.hasTicker(sanitized)) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    // __MENTIONS_2026_09_23__ a picked person's `@name` is never a stock chip.
                                    TickerChipsForText(
                                        text = sanitized, messageId = message.id,
                                        exclude = com.oshi.messenger.ui.components.mentionRanges(sanitized, mentions)
                                    )
                                }

                                // __CASHU_2026_10_24__ eCash card. A token pasted in a
                                // group is readable by every member, which is exactly
                                // what the bearer warning on the card says out loud.
                                com.oshi.messenger.ui.components.CashuTokenCardsForText(
                                    text = sanitized,
                                    modifier = Modifier.padding(top = 6.dp)
                                )

                                // Link preview (OG image) for URLs
                                val detectedUrl = remember(sanitized) { sanitized.detectFirstUrl() }
                                detectedUrl?.let { url ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinkPreviewCard(url = url, autoFetch = isOwnMessage)  // __LEAK_FIX_2026_09_25__ incoming: tap to load
                                }
                            }
                        }
                        } // end location fallback
                    }
                }

                // Timestamp + read indicator — task 1.3.4
                // __BUBBLE_MEDIA_2026_11_21__ a media-only bubble carries it on the
                // picture instead (MediaTimePill), not on a strip of bubble under it.
                if (!mediaOnly) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) { groupMetaContent(metaColor, false) }
                }
                // __BUBBLE_MEDIA_2026_11_21__ channel comments bar, inside the card under
                // a hairline (BUBBLE_MEDIA_SPEC §2), edge to edge.
                if (footer != null) {
                    Column(modifier = Modifier.bleed(start = 12.dp, end = 12.dp, bottom = 8.dp).padding(top = if (mediaOnly) 0.dp else 6.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(Oshi.Bubble.rimInk(false, darkScheme).copy(alpha = 0.14f))
                        )
                        footer()
                    }
                }
            }
        }

        // 🤖 Inline keyboard — directly under the bot's bubble, on the bubble's own
        // width, above the reaction chips and the bot badge. Only a bot sender may have
        // one (`GroupManager` drops the field for anyone else), and the parse is
        // memoised on the raw JSON so scrolling does not re-validate every keyboard on
        // every frame.
        // A retracted message keeps no buttons: the bubble is a tombstone, and a live
        // control under it would dispatch against a message that no longer exists.
        if (isBot && !message.isDeleted) {
            val parsedMarkup = remember(message.replyMarkup) {
                BotReplyMarkupParser.parse(message.replyMarkup)
            }
            if (parsedMarkup != null) {
                Spacer(modifier = Modifier.height(4.dp))
                BotInlineKeyboard(
                    markup = parsedMarkup,
                    inFlightKey = botInFlightKey,
                    enabled = botInFlightKey == null,
                    onCallback = onBotCallback,
                    onUrl = onBotUrl,
                    onWebApp = onBotWebApp,
                    modifier = Modifier.widthIn(max = maxBubbleWidth)
                )
            }
        }

        // Reactions — iOS-style capsule chips, floating slightly over bubble
        if (message.reactions.isNotEmpty()) {
            val grouped = message.reactions.entries.groupBy { it.value }
            // __REACTION_WHO_SHEET_2026_11_16__ long-press a chip: who reacted with what.
            var showWho by remember { mutableStateOf(false) }
            if (showWho) {
                com.oshi.messenger.ui.components.ReactionWhoSheet(
                    rows = com.oshi.messenger.ui.components.reactionWhoRows(
                        grouped.mapValues { (_, entries) -> entries.map { it.key } },
                        myPublicKey, controlLabels.you, senderNameFor
                    ),
                    onDismiss = { showWho = false },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .offset(y = (-6).dp)
                    .padding(horizontal = 4.dp)
            ) {
                grouped.entries.sortedByDescending { it.value.size }.forEach { (emoji, users) ->
                    // __REACTION_BAR_ONE_COMPONENT_2026_11_15__ same chip as 1:1: the
                    // count is always shown (it was hidden for a lone reaction), and
                    // "mine" ignores key case like toggleReaction does.
                    com.oshi.messenger.ui.components.ReactionChip(
                        code = emoji,
                        count = users.size,
                        isMine = users.any { it.key.equals(myPublicKey, ignoreCase = true) },
                        onClick = { onReact(emoji) },
                        onLongClick = { showWho = true },
                    )
                }
            }
        }

        // Sender badge — a bot keeps its distinct chip below the bubble; a regular
        // sender's name now rides at the TOP of the bubble (Telegram style), so no
        // grey chip is drawn for them here any more.
        if (!isOwnMessage && isBot) {
            Spacer(modifier = Modifier.height(4.dp))
            // Bot chip: dark purple with CPU icon
            Surface(
                color = Color(0xFF2A2A4A),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = Color(0xFF9BA4E8),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = message.senderName.ifEmpty { "Bot" },
                        fontSize = 11.sp,
                        color = Color(0xFF9BA4E8),
                        fontWeight = FontWeight.Medium
                    )
                    // __BOTS_PHASE1_2026_11_24__ the BOT badge on every bot post (groups and channels).
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.botf_badge),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = Color(0xFF1A1A2E),
                        modifier = Modifier
                            .background(Color(0xFF9BA4E8), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp)
                    )
                }
            }
            if (botSealCaption != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = botSealCaption,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }
        }
    }
}
}

@Composable
private fun GroupMessageInput(
    messageText: String,
    /** __MENTIONS_2026_09_23__ highlight for picked `@name` tokens, or null. */
    mentionHighlight: androidx.compose.ui.text.input.VisualTransformation? = null,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit = {},
    onEmoji: () -> Unit,
    isRecording: Boolean = false,
    recordingDurationMs: Long = 0L,
    onSchedule: () -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {}
) {
    // __OLED_GLASS_2026_11_16__ Under OLED black the composer is a glass bar: a
    // near-black fill, a lit hairline along its top edge, no drop shadow (a
    // shadow on #000000 is invisible and only costs a layer).
    // __CHAT_BACKDROP_BLUR_2026_11_21__ Transparent: the composer sits on the
    // frosted bottom band (FrostedChatBarBackdrop in the Scaffold's bottomBar),
    // which carries the fill, the glass rim and the fallback opacity.
    val glass = com.oshi.messenger.ui.theme.glassOn()
    Surface(
        color = Color.Transparent,
    ) {
        if (isRecording) {
            // Recording UI: timer + cancel + send
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancelRecording) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = stringResource(R.string.chat_voice_canceled),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val seconds = (recordingDurationMs / 1000) % 60
                val minutes = (recordingDurationMs / 1000) / 60
                Text(
                    text = "%d:%02d".format(minutes, seconds),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.chat_voice_release_to_send),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = onStopRecording,
                    modifier = Modifier.size(48.dp),
                    containerColor = BrandColor
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = stringResource(R.string.accessibility_send_button),
                        tint = Color.White
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // __VIDEO_NOTE_2026_09_24__ While typing, "+" and the video-note
                // shortcut step aside and the field takes the row (see ChatScreen).
                val shortcutsVisible = com.oshi.messenger.service.media.ComposerShortcuts.visible(messageText)

                // Single "+" entry — opens the group attachment menu (camera, schedule,
                // location etc. live there). Frees the text field to span the row,
                // matching the 1:1 ChatScreen composer.
                // __COMPOSER_PILL_2026_11_23__ "+", field, emoji and camera share ONE pill
                // (Google-app style); mic / send is the round button beside it.
                com.oshi.messenger.ui.components.ComposerPill(glass = glass, modifier = Modifier.weight(1f)) {
                ComposerShortcut(visible = shortcutsVisible) {
                    IconButton(onClick = onAttach, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.accessibility_attach),
                            tint = brandInk()
                        )
                    }
                }
                if (!shortcutsVisible) Spacer(modifier = Modifier.width(10.dp))

                // Text field
                // __TEXT_FORMATTING_2026_11_24__ Bold / Underline in the selection menu.
                com.oshi.messenger.ui.components.FormattingComposerField(
                    text = messageText,
                    onTextChange = onMessageChange,
                ) { fieldValue, onFieldValueChange ->
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = onFieldValueChange,
                    visualTransformation = mentionHighlight ?: androidx.compose.ui.text.input.VisualTransformation.None,
                    placeholder = { Text(stringResource(R.string.composer_placeholder), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                    shape = com.oshi.messenger.ui.components.ComposerPillDefaults.Shape,
                    // The pill carries fill, outline and glass; the field is bare.
                    colors = com.oshi.messenger.ui.components.composerPillFieldColors(),
                    trailingIcon = {
                        IconButton(onClick = onEmoji) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = stringResource(R.string.accessibility_emoji),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    maxLines = com.oshi.messenger.service.media.ComposerShortcuts.maxLines(messageText)
                )
                }

                // (N17, Anatole 13/11) Take a PHOTO — where nearly every messenger
                // puts the camera, beside the mic. This slot used to open the round
                // video-note recorder, which now lives in the attachment sheet.
                // Visible only while the field is empty, as before.
                ComposerShortcut(visible = shortcutsVisible) {
                    IconButton(onClick = onCamera, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = stringResource(R.string.profile_take_photo),
                            tint = brandInk()
                        )
                    }
                }
                } // __COMPOSER_PILL_2026_11_23__ end pill

                Spacer(modifier = Modifier.width(8.dp))

                // Send (text) or Mic (empty) button
                if (messageText.isNotBlank()) {
                    com.oshi.messenger.ui.components.ComposerRoundButton(
                        onClick = onSend, primary = true, glass = glass
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = stringResource(R.string.accessibility_send_button),
                        )
                    }
                } else {
                    com.oshi.messenger.ui.components.ComposerRoundButton(
                        onClick = onStartRecording, primary = false, glass = glass
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = stringResource(R.string.chat_voice_record),
                            tint = brandInk()
                        )
                    }
                }
            }
        }
    }
}

private fun formatMessageTime(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Date()
    val diff = now.time - timestamp

    return when {
        diff < 60_000 -> "Now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(date)
    }
}

// ============================================================================
// GROUP CHAT INFO SHEET
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatInfoSheet(
    group: Group,
    myPublicKey: String,
    onDismiss: () -> Unit,
    onMemberClick: (String) -> Unit,
    memberName: (String) -> String? = { null },
    onOpenGallery: () -> Unit = {},
    onRename: (String) -> Boolean = { false },
    onAddMember: (String) -> Boolean = { false },
    inviteLink: String? = null,
    onOpenSettings: () -> Unit = {},
    quickActions: GroupInfoQuickActions? = null,
    advPrivacy: Boolean = false,
    onToggleAdvPrivacy: ((Boolean) -> Unit)? = null,
    /** Other members whose build will not apply the switch (no cap bit 16, never sent one). */
    advPrivacyOlderCount: Int = 0
) {
    val context = LocalContext.current
    var isEditingName by remember(group.id) { mutableStateOf(false) }
    var nameDraft by remember(group.id, group.name) { mutableStateOf(group.name) }
    var showAddMember by remember(group.id) { mutableStateOf(false) }
    var newMemberKey by remember(group.id) { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isAdmin = group.adminPublicKeys.contains(myPublicKey) || group.creatorPublicKey == myPublicKey
    val groupTypePublic = stringResource(R.string.group_chat_type_public)
    val groupTypeAdminOnly = stringResource(R.string.group_chat_type_admin_only)
    val groupTypeCollaborative = stringResource(R.string.group_chat_type_collaborative)
    // Use the real, synced group type. The old heuristic ("everyone is an admin ⇒ admin_only")
    // mislabelled a 1-member group and never reflected the `type` iOS actually sent.
    val groupType = when (group.effectiveGroupType) {
        com.oshi.messenger.data.model.GroupType.PUBLIC -> groupTypePublic
        com.oshi.messenger.data.model.GroupType.ADMIN_ONLY -> groupTypeAdminOnly
        com.oshi.messenger.data.model.GroupType.COLLABORATIVE -> groupTypeCollaborative
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // __GROUP_CHAT_INFO_SCROLL_2026_10_13__ A ModalBottomSheet does not scroll
        // its slot. This body is fixed-height content that comes to roughly 1230 dp
        // for a ten-member group — avatar 96, title 48, add-member 48, type row 44,
        // gallery card 68, members card ~508, QR card ~324 — while a 360 x 780 dp
        // phone leaves the sheet about 740. Everything from the last member rows
        // down was therefore unreachable, QR invite and "copy invite link" included,
        // and no gesture could bring it up: dragging the sheet dismisses it.
        // This is the SAME sheet as GroupsScreen.GroupInfoSheet, which was fixed on
        // 2026-08-13; this in-chat copy was missed, and CreateGroupSheet was missed
        // again on 2026-10-12. `navigationBarsPadding` stops the gesture bar eating
        // the last row.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done), color = brandInk(), fontWeight = FontWeight.SemiBold)
                }
            }

            // Group Avatar
            GroupAvatar(group = group, size = 80.dp)

            Spacer(modifier = Modifier.height(16.dp))

            // Inline rename — iOS GroupInfoView has the same editable title
            // (GroupViews.swift:6757-6805), gated by `canChangeName` (GroupMessaging.swift:120).
            if (isEditingName) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                    // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it.take(60) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.group_name)) }
                        )
                    }
                    IconButton(onClick = {
                        val trimmed = nameDraft.trim()
                        if (trimmed.isNotEmpty() && trimmed != group.name) {
                            val ok = onRename(trimmed)
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.group_system_name_changed else R.string.error
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        isEditingName = false
                    }) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = OSHIGreen)
                    }
                    IconButton(onClick = {
                        nameDraft = group.name
                        isEditingName = false
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = group.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (group.canChangeName(myPublicKey)) {
                        IconButton(
                            onClick = {
                                nameDraft = group.name
                                isEditingName = true
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                tint = brandInk(),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Add member to an existing group — iOS AddMemberView (GroupViews.swift:6456).
            if (group.canAddMembers(myPublicKey)) {
                if (showAddMember) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                        // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                        com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                            OutlinedTextField(
                                value = newMemberKey,
                                onValueChange = { newMemberKey = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.groups_member_public_key)) }
                            )
                        }
                        IconButton(onClick = {
                            val key = newMemberKey.trim()
                            if (key.isNotEmpty()) {
                                val ok = onAddMember(key)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        if (ok) R.string.group_add_members else R.string.error
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (ok) {
                                    newMemberKey = ""
                                    showAddMember = false
                                }
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = OSHIGreen)
                        }
                    }
                } else {
                    TextButton(onClick = { showAddMember = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, tint = BrandColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.group_add_members), color = BrandColor)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Type and member count
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = groupType,
                    color = brandInk(),
                    fontSize = 14.sp
                )
                Text("\u2022", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.group_chat_members_count_label, group.memberPublicKeys.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                if (isAdmin) {
                    Text("\u2022", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = stringResource(R.string.group_chat_admin),
                        color = Color(0xFFF5A623),
                        fontSize = 14.sp
                    )
                }
            }

            // __GROUP_SETTINGS_2026_11_20__ description (synced on every platform) + settings entry
            if (group.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(group.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (quickActions != null) {
                Spacer(modifier = Modifier.height(16.dp))
                GroupInfoQuickActionRow(isMuted = group.isMuted, actions = quickActions)
            }
            // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Any member may switch it (WhatsApp parity).
            if (onToggleAdvPrivacy != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    com.oshi.messenger.ui.components.AdvancedChatPrivacyToggle(
                        on = advPrivacy,
                        onToggle = onToggleAdvPrivacy,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                // Same footer as iOS: the switch is not sent to them, so say so.
                if (advPrivacyOlderCount > 0) {
                    Text(
                        stringResource(R.string.adv_privacy_older_members, advPrivacyOlderCount),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("adv_privacy_older_members")
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { onOpenSettings() }
                    .testTag("group_info_open_settings")
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = brandInk(), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.gs_open_settings), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Media gallery entry (P2 #20)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenGallery() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = brandInk(),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.media_gallery_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Members list
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = null,
                                tint = brandInk(),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.group_chat_members_label), fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "${group.memberPublicKeys.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // __GROUP_MEMBERS_STOPPED_AT_TEN_2026_11_04__ Anatole:
                    // "au delà de 10 personnes on ne peut plus voir qui il y a
                    // dans le groupe."
                    //
                    // The header above prints `memberPublicKeys.size`, so a
                    // group of fifteen said "15" and then listed ten. The cap
                    // was presumably there because this sheet could not scroll;
                    // it has scrolled since __GROUP_CHAT_INFO_SCROLL_2026_10_13__
                    // added `verticalScroll` to the Column, and the `.take(10)`
                    // simply outlived the reason for it.
                    group.memberPublicKeys.forEach { publicKey ->
                        val isCreator = publicKey == group.creatorPublicKey
                        val isMemberAdmin = group.adminPublicKeys.contains(publicKey) && !isCreator
                        val isMe = publicKey == myPublicKey

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .then(
                                    if (!isMe) Modifier.clickable { onMemberClick(publicKey) }
                                    else Modifier
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // __ONE_AVATAR_NOT_THREE_2026_11_07__ every
                                // member of a group shared one brand gradient,
                                // which is the opposite of what a member row is
                                // for — you are scanning it to tell people apart.
                                com.oshi.messenger.ui.theme.ContactAvatar(
                                    publicKey = publicKey,
                                    name = memberName(publicKey),
                                    size = 36.dp,
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isMe) stringResource(R.string.group_chat_you) else memberName(publicKey) ?: "${publicKey.take(6)}...${publicKey.takeLast(4)}",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                if (isCreator) {
                                    Text(stringResource(R.string.group_chat_creator), fontSize = 11.sp, color = Color(0xFFF5A623))
                                } else if (isMemberAdmin) {
                                    Text(stringResource(R.string.group_chat_admin), fontSize = 11.sp, color = Color(0xFFF5A623))
                                }
                            }

                            // Chevron for non-self members
                            if (!isMe) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // The "+N other members" footer that used to sit here
                    // belonged to the `.take(10)` removed above: with every
                    // member now listed, a group of fifteen printed all
                    // fifteen rows and then claimed five more were hidden.
                    // Removing the cap and leaving its footer is the same
                    // defect in reverse — the count was never wrong, the
                    // sentence was.
                }
            }

            // QR Code / Invite Section
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = brandInk(),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.group_chat_invite_to_group), fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // __GROUP_SETTINGS_2026_11_20__ the real invite link (https, parsed by every
                    // platform, carries `t` once reset) — `oshi://group/<id>` had no inviter, so
                    // the joiner had nobody to ask.
                    val inviteLink = inviteLink ?: "oshi://group/${group.id}"
                    val qrBitmap = remember(group.id) {
                        generateGroupQRCode(inviteLink)
                    }

                    qrBitmap?.let { bitmap ->
                        OshiCard(
                            modifier = Modifier.size(180.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.group_chat_group_qr_code),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.group_chat_scan_to_join),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val clipboardManager = LocalClipboardManager.current
                    var showCopied by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(inviteLink))
                            showCopied = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (showCopied) stringResource(R.string.copied) else stringResource(R.string.group_chat_copy_invite_link))
                    }

                    LaunchedEffect(showCopied) {
                        if (showCopied) {
                            kotlinx.coroutines.delay(2000)
                            showCopied = false
                        }
                    }
                }
            }
        }
    }
}

private fun generateGroupQRCode(content: String): android.graphics.Bitmap? {
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

// ============================================================================
// ATTACHMENT MENU SHEET
// ============================================================================

// ============================================================================
// DIALOGS
// ============================================================================

/**
 * ✏️ Edit one of my own group messages (RP-4, second half).
 *
 * Deliberately a dialog rather than an inline "editing" banner over the composer: the
 * group composer is shared with the reply-quote bar and the attachment sheet, and iOS's
 * group edit is also a modal (`OSHI/GroupViews.swift:4390` → its edit alert). Save is
 * disabled for an empty or unchanged draft, so the confirm button can never fire a
 * broadcast that does nothing.
 */
@Composable
private fun GroupEditMessageDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun GroupMessageActionsDialog(
    message: GroupMessage,
    isOwnMessage: Boolean,
    myPublicKey: String,
    onDismiss: () -> Unit,
    onReactWith: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onReplyInPrivate: () -> Unit,
    onAddToContacts: () -> Unit,
    onSave: (() -> Unit)? = null,
    onForward: () -> Unit = {},
    /** Opt-in privacy switch: hides Forward and Copy in group threads too. */
    blockForwardCopy: Boolean = false,
    onPin: (() -> Unit)? = null,
    /** RP-2: iOS gates Translate to RECEIVED messages (`MessageReactionManager.swift:941`). */
    onTranslate: (() -> Unit)? = null,
    /**
     * RP-4, second half: iOS's group overlay has Edit (`GroupViews.swift:4390`).
     * Pass null when the message is not editable — the caller asks
     * `GroupChatViewModel.canEdit`, which is `GroupManager.canEditGroupMessage`, the
     * same predicate `broadcastMessageEdit` enforces.
     */
    onEdit: (() -> Unit)? = null,
    /** RP-4: iOS's group overlay has Delete (`GroupViews.swift:4372`). Own messages. */
    onDelete: (() -> Unit)? = null,
    /**
     * __GROUP_RECEIPTS_2026_11_02__ (N8) "who has read it and who has received
     * it". Null on anyone else's message, because a receipt is addressed to
     * the sender: this device knows nothing about a message it did not send,
     * and a row that opened an always-empty sheet would be a lie.
     */
    onInfo: (() -> Unit)? = null,
    /** __INTEROP_2026_11_21__ §2.3: null = every reaction, empty = none, else only these. */
    allowedReactions: List<String>? = null,
    /**
     * __CHANNEL_WEB_2026_11_26__ Public web copy of a channel post (spec §9.7): null unless
     * the channel's web preview is on and this message is a publishable post.
     */
    onWebCopyLink: (() -> Unit)? = null,
    onWebShareLink: (() -> Unit)? = null,
    onWebEmbed: (() -> Unit)? = null,
) {
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
                val currentReaction = message.reactions.entries
                    .firstOrNull { it.key.equals(myPublicKey, ignoreCase = true) }?.value
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
                    restrictTo = allowedReactions,
                )
                Divider(modifier = Modifier.padding(bottom = 4.dp))
                // Reply
                TextButton(onClick = onReply, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Reply, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.message_reply))
                    }
                }
                // Copy and Forward — one switch, same as the 1:1 thread.
                if (!blockForwardCopy) {
                    TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.copy))
                        }
                    }
                    TextButton(onClick = { onDismiss(); onForward() }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Forward, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.message_forward))
                        }
                    }
                }
                // __CHANNEL_WEB_2026_11_26__ Telegram-style public link + embed code.
                listOfNotNull(
                    onWebCopyLink?.let { Triple(Icons.Default.Link, R.string.gs_copy_link, it) },
                    onWebShareLink?.let { Triple(Icons.Default.Share, R.string.gs_share_link, it) },
                    onWebEmbed?.let { Triple(Icons.Default.Code, R.string.cw_embed_code, it) },
                ).forEach { (icon, label, action) ->
                    TextButton(onClick = { onDismiss(); action() }, modifier = Modifier.fillMaxWidth().testTag("cw_menu_$label")) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(label))
                        }
                    }
                }
                // Save to device (only for media messages)
                if (onSave != null) {
                    TextButton(onClick = { onDismiss(); onSave() }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.save_to_gallery))
                        }
                    }
                }
                // Translate — RP-2. `GroupMessageActionsDialog` had NO Translate item at
                // all, while iOS offers one on every received group bubble
                // (`OSHI/GroupViews.swift:4373` → `:4431-4440`). Placed between Forward
                // and Pin, mirroring iOS's ordering.
                if (onTranslate != null && !isOwnMessage) {
                    TextButton(onClick = { onDismiss(); onTranslate() }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GTranslate, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.message_translate))
                        }
                    }
                }
                // Pin (admin only, shown when onPin != null)
                if (onPin != null) {
                    TextButton(onClick = onPin, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PushPin, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.group_chat_pin_message))
                        }
                    }
                }
                // Reply in Private (only for others' messages)
                if (!isOwnMessage) {
                    TextButton(onClick = onReplyInPrivate, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Chat, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.group_reply_in_private))
                        }
                    }
                    // Add to Contacts
                    TextButton(onClick = onAddToContacts, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.group_add_to_contacts))
                        }
                    }
                }

                // ✏️ RP-4, second half · Edit for everyone. iOS's group overlay has had
                // this row all along (`OSHI/GroupViews.swift:4390`); Android could not
                // offer it because `GroupMessage` had nowhere to put the result. It now
                // has `editedContent` / `editedAt` (Room v15 → v16) and
                // `GroupManager.broadcastMessageEdit` emits the `.messageEdited` system
                // message an iPhone applies at `GroupMessaging.swift:3292-3307`.
                //
                // The whole predicate — own, text-only, not a tombstone, inside iOS's
                // 600 s window (`GroupMessaging.swift:1307`) — lives in
                // `GroupManager.canEditGroupMessage`, which the caller consults; a null
                // `onEdit` means "not editable" and this row simply is not drawn.
                if (onInfo != null) {
                    TextButton(onClick = { onDismiss(); onInfo() }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DoneAll, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.group_receipts_action))
                        }
                    }
                }

                if (onEdit != null) {
                    TextButton(onClick = { onDismiss(); onEdit() }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.edit))
                        }
                    }
                }

                // RP-4 · Delete for everyone. The back end already existed
                // (`GroupManager.broadcastMessageDeletion`) and had no menu row, so a
                // mis-sent group message could not be unsent at all, while iOS offers it
                // on every own bubble (`OSHI/GroupViews.swift:4372`).
                if (onDelete != null && isOwnMessage && !message.isDeleted) {
                    TextButton(onClick = { onDismiss(); onDelete() }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
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


@Composable
private fun MemberActionsDialog(
    onDismiss: () -> Unit,
    onReplyInPrivate: () -> Unit,
    onAddToContacts: () -> Unit,
    onCopyKey: () -> Unit,
    // __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__ Required, not
    // defaulted: these two are the whole point of the change and a `= {}` would
    // let a future call site drop them and still compile, which is how a dead
    // menu entry ships (see `defaulted-closure-is-an-undetectable-dead-button`).
    onBlockMember: () -> Unit,
    onUnblockMember: () -> Unit,
    onReportMember: () -> Unit,
    isMemberBlocked: Boolean,
    alreadyReportedMember: Boolean,
    isMeAdmin: Boolean = false,
    isMemberAdmin: Boolean = false,
    isMemberCreator: Boolean = false,
    onPromoteToAdmin: (() -> Unit)? = null,
    onDemoteFromAdmin: (() -> Unit)? = null,
    onRemoveMember: (() -> Unit)? = null
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_member_actions)) },
        text = {
            Column {
                TextButton(onClick = onReplyInPrivate, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Chat, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.group_reply_in_private))
                    }
                }
                TextButton(onClick = onAddToContacts, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.group_add_to_contacts))
                    }
                }
                TextButton(onClick = onCopyKey, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.copy))
                    }
                }
                // __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__
                //
                // Block and Report, available to EVERY member, not just admins.
                // Before this, a non-admin being spammed by one person in a
                // group had no action against that person at all: the only
                // report reachable from a group was the group itself, and the
                // 06/11 reports show exactly what that produces — a room id
                // where a key should be, and the spammer's name typed into the
                // free-text box.
                //
                // Blocking is not new behaviour, only newly reachable: group
                // receive has dropped blocked members' messages since
                // __BLOCKED_MEMBER_DROP_2026_09_24__.
                //
                // Not gated on `isMemberCreator`: the creator of a group you did
                // not create is exactly someone you may need to block.
                if (isMemberBlocked) {
                    TextButton(onClick = onUnblockMember, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.group_unblock_member))
                        }
                    }
                } else {
                    TextButton(onClick = onBlockMember, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Block, contentDescription = null, tint = OSHIRed)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.group_block_member), color = OSHIRed)
                        }
                    }
                }
                TextButton(onClick = onReportMember, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, contentDescription = null, tint = OSHIRed)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.group_report_member),
                            color = if (alreadyReportedMember) MaterialTheme.colorScheme.onSurfaceVariant else OSHIRed
                        )
                    }
                }
                // Admin management actions — task 2.6.4
                if (isMeAdmin && !isMemberCreator) {
                    if (!isMemberAdmin && onPromoteToAdmin != null) {
                        TextButton(onClick = onPromoteToAdmin, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = Color(0xFFF5A623))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stringResource(R.string.group_chat_promote_admin), color = Color(0xFFF5A623))
                            }
                        }
                    } else if (isMemberAdmin && onDemoteFromAdmin != null) {
                        TextButton(onClick = onDemoteFromAdmin, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.RemoveModerator, contentDescription = null, tint = OSHIRed)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stringResource(R.string.group_chat_remove_admin), color = OSHIRed)
                            }
                        }
                    }
                }
                // Kick — iOS `removeMember` (GroupMessaging.swift:1309), gated by
                // `canRemoveMembers` (:119). The creator is protected, like iOS's demote guard.
                if (onRemoveMember != null && !isMemberCreator) {
                    TextButton(onClick = onRemoveMember, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PersonRemove, contentDescription = null, tint = OSHIRed)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.group_remove_member), color = OSHIRed)
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

/**
 * Fullscreen image viewer — iOS GroupImageFullScreenView equivalent.
 * Black background, pinch-to-zoom, double-tap to toggle 2.5x, close button.
 */
@Composable
private fun FullscreenImageDialog(
    messageId: String,
    data: ByteArray,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }

            com.oshi.messenger.ui.media.BubbleAsyncImage(
                messageId = messageId,
                data = data,
                contentDescription = null,
                maxDimensionPx = com.oshi.messenger.ui.media.FULLSCREEN_MAX_DIMENSION_PX,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .transformable(
                        state = rememberTransformableState { zoomChange, panChange, _ ->
                            scale = (scale * zoomChange).coerceIn(0.5f, 5f)
                            offsetX += panChange.x * scale
                            offsetY += panChange.y * scale
                        }
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    },
                contentScale = ContentScale.Fit
            )

            // Close button top-right
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.accessibility_close),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun GroupLocationBubble(content: String, context: android.content.Context) {
    val locMsg = remember(content) { LocationMessage.fromMessageString(content) }
    // PERF: previously JSONObject(content) was constructed 3× per recomposition.
    // Parse once, cached by content, and pull all three fields from it.
    val fallbackJson = remember(content) {
        if (locMsg == null) try { JSONObject(content) } catch (_: Exception) { null } else null
    }
    val lat = locMsg?.latitude ?: fallbackJson?.optDouble("latitude", Double.NaN) ?: Double.NaN
    val lng = locMsg?.longitude ?: fallbackJson?.optDouble("longitude", Double.NaN) ?: Double.NaN
    val address = locMsg?.address ?: fallbackJson?.optString("address", "") ?: ""
    val isLive = locMsg?.isLive ?: false
    // __GROUP_LIVE_LOCATION_2026_09_27__ An iPhone never sends group live updates
    // or a stop (its sender applies them locally only), so from an iPhone the tile
    // is the initial share and nothing else: it must end ON ITS OWN at `expiresAt`,
    // which means re-evaluating the clock while it is on screen.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(locMsg?.expiresAt, locMsg?.isStopped) {
        val expiresAt = locMsg?.expiresAt ?: return@LaunchedEffect
        if (!isLive || locMsg.isStopped) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            val leftMs = ((expiresAt + 978307200.0) * 1000.0 - nowMillis).toLong()
            if (leftMs <= 0) break
            kotlinx.coroutines.delay(leftMs.coerceAtMost(30_000L))
        }
    }
    val isExpired = locMsg?.let { msg ->
        if (msg.expiresAt != null) {
            val nowApple = (nowMillis / 1000.0) - 978307200.0
            nowApple > msg.expiresAt
        } else false
    } ?: false
    val isStopped = locMsg?.isStopped ?: false
    val showLive = isLive && !isExpired && !isStopped

    // staticmap.openstreetmap.de is NXDOMAIN — it rendered a blank grey box for
    // every group location bubble. OshiMapPreview composites from the local tile
    // cache first, falling back to the app's own first-party tile proxy, and draws
    // the pin locally so no coordinate ever leaves the device.
    val hasCoords = !lat.isNaN() && !lng.isNaN()

    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // The bubble used to go straight to Google Maps, ignoring the user's choice; the
    // 1:1 bubble has always honoured it and iOS routes both through the same setting.
    val mapPreference by com.oshi.messenger.ui.components.rememberMapOpenPreference()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .bubbleClickable {
                if (!lat.isNaN() && !lng.isNaN()) {
                    com.oshi.messenger.ui.components.openLocationInPreferredApp(
                        context = context,
                        preference = mapPreference,
                        lat = lat,
                        lon = lng,
                        label = address.ifBlank { null }
                    )
                }
            }
    ) {
        Column {
            // Map preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (hasCoords) {
                    com.oshi.messenger.ui.components.OshiMapPreview(
                        lat = lat,
                        lon = lng,
                        contentDescription = stringResource(R.string.accessibility_map_preview),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // No coordinates — show placeholder
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = OSHIGreen,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                // LIVE badge top-right
                if (showLive) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(OSHIGreen.copy(alpha = pulseAlpha), CircleShape)
                        )
                        Text(
                            text = stringResource(R.string.chat_live_badge),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Info bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = OSHIGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (showLive) "Live Location" else "Shared Location",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val displayAddr = when {
                        !address.isNullOrEmpty() -> address
                        !lat.isNaN() && !lng.isNaN() -> "%.5f, %.5f".format(lat, lng)
                        else -> null
                    }
                    if (displayAddr != null) {
                        Text(
                            text = displayAddr,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.chat_open_link),
                    color = OSHIGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── Date helpers for GroupChatScreen ──

private fun formatDateHeader(timestamp: Long): String {
    val normalizedTs = if (timestamp in 1..1_000_000_000_000L) timestamp * 1000 else timestamp
    val messageDate = java.util.Calendar.getInstance().apply { timeInMillis = normalizedTs }
    val today = java.util.Calendar.getInstance()
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }

    return when {
        messageDate.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            messageDate.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
        messageDate.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
            messageDate.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(normalizedTs))
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

/**
 * Hilt entry point for GroupChatScreen to access AudioRecorderManager and
 * AudioPlayerManager singletons without touching the @HiltViewModel constructor.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface GroupVoiceEntryPoint {
    fun audioRecorder(): com.oshi.messenger.service.AudioRecorderManager
    fun audioPlayer(): com.oshi.messenger.service.AudioPlayerManager
}

/**
 * Inline voice message player used inside group chat bubbles.
 * Mirrors VoiceMessageContent in ChatScreen but pulls AudioPlayerManager
 * lazily via Hilt so it can be used in nested composables without
 * threading through screen-level params.
 */
@Composable
private fun GroupVoiceMessagePlayer(
    messageId: String,
    audioData: ByteArray?,
    content: String,
    durationMs: Long?,
    isFromMe: Boolean,
    textColor: Color,
    /** __VOICE_MINIPLAYER_2026_10_09__ what the floating player names this note. */
    senderName: String? = null,
) {
    val context = LocalContext.current
    val audioPlayer = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            GroupVoiceEntryPoint::class.java
        ).audioPlayer()
    }
    // __VOICE_MINIPLAYER_2026_10_09__ While this bubble is on screen it IS the player
    // — the floating pill would be a duplicate of the controls right here, so it hides.
    // Leaving the group (or the app's tab) takes the bubble away and the pill over.
    androidx.compose.runtime.DisposableEffect(messageId) {
        audioPlayer.ownerAppeared(messageId)
        onDispose { audioPlayer.ownerDisappeared(messageId) }
    }
    val miniPlayerTitle = when {
        isFromMe -> stringResource(R.string.chat_you)
        !senderName.isNullOrBlank() -> senderName
        else -> stringResource(R.string.group_chat_voice_message)
    }
    val playingId by audioPlayer.currentMessageId.collectAsState()
    val playbackState by audioPlayer.playbackState.collectAsState()
    val playbackProgress by audioPlayer.playbackProgress.collectAsState()
    val playbackSpeed by audioPlayer.playbackSpeed.collectAsState()
    val isThisPlaying = playingId == messageId &&
        playbackState == com.oshi.messenger.service.AudioPlayerManager.PlaybackState.PLAYING

    // Duration: the stored field, then the legacy `"[AUDIO] 0:05"` body (rows already on
    // disk, and anything from an older Android build), then the audio container itself —
    // which is the ONLY source for an iPhone voice note, whose caption is empty. The old
    // code read the body and nothing else, so every inbound iOS note showed 0:00.
    val analysis = com.oshi.messenger.ui.media.rememberVoiceNoteAnalysis(
        messageId = messageId,
        data = audioData,
        storedDurationMs = durationMs,
        legacyBody = content
    )
    val durationSec = analysis?.durationSeconds
        ?: com.oshi.messenger.ui.media.VoiceNoteAudio.resolveDurationSeconds(durationMs, content)
        ?: 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { audioData?.let { audioPlayer.play(messageId, it, miniPlayerTitle) } },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(textColor.copy(alpha = 0.15f))
        ) {
            Icon(
                imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isThisPlaying)
                    stringResource(R.string.chat_pause)
                else
                    stringResource(R.string.chat_play),
                tint = textColor
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            // iOS draws a real waveform here (`AudioMessageView.swift:83-90`), not a bar.
            // The flat bar stays as the fallback for the frame or two before the analysis
            // lands, and for a note whose bytes are not (yet) hydrated.
            val progressNow = if (playingId == messageId) playbackProgress else 0f
            if (analysis != null) {
                com.oshi.messenger.ui.media.VoiceWaveform(
                    levels = analysis.waveform,
                    progress = progressNow,
                    color = textColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    // __VOICE_SCRUB_2026_10_26__ Tap the strip to play from a
                    // point, drag it to move the head. The title is in scope
                    // here, so a scrub that starts the note still names the
                    // sender in the floating player.
                    onSeek = { p, startIfIdle ->
                        audioData?.let { bytes ->
                            audioPlayer.scrubTo(messageId, bytes, miniPlayerTitle, p, startIfIdle)
                        }
                    }
                )
            } else {
                LinearProgressIndicator(
                    progress = progressNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = textColor,
                    trackColor = textColor.copy(alpha = 0.3f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val totalSec = (durationSec).toInt()
            val mm = totalSec / 60
            val ss = totalSec % 60
            Text(
                text = "%d:%02d".format(mm, ss),
                fontSize = 12.sp,
                color = textColor.copy(alpha = 0.7f)
            )
        }

        // Playback-speed toggle: cycles 1x → 1.5x → 2x.
        Spacer(modifier = Modifier.width(4.dp))
        val speedLabel = if (playbackSpeed % 1f == 0f) "${playbackSpeed.toInt()}x" else "${playbackSpeed}x"
        TextButton(
            onClick = { audioPlayer.cyclePlaybackSpeed() },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.height(24.dp)
        ) {
            Text(
                text = speedLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}

// ============================================================================
// TRANSLATE BOTTOM SHEET (RP-2, groups)
//
// `GroupMessageActionsDialog` had no Translate item at all, while iOS offers one
// on every received group bubble (`OSHI/GroupViews.swift:4373` → `:4431-4440`).
// Same three-tier on-device cascade as the 1:1 sheet
// (`NLPManager.translateReceived`: Gemini Nano → local LLM → ML Kit) and the same
// honest terminal states — ML Kit downloads a ~30 MB language pack on first use,
// so "still loading" is not a state a user should be left in forever.
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupTranslateBottomSheet(
    messageContent: String,
    state: GroupChatViewModel.TranslationState,
    onRetry: () -> Unit,
    // null = 'Block forward and copy' is on: no Copy button (__CLIPBOARD_HYGIENE_2026_10_01__)
    onCopy: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        // __GROUP_CHAT_INFO_SCROLL_2026_10_13__ Original + translation are
        // user-length: a long message pushed the Copy button off the sheet.
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (state) {
                        is GroupChatViewModel.TranslationState.Idle,
                        is GroupChatViewModel.TranslationState.Loading -> {
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

                        is GroupChatViewModel.TranslationState.Done -> {
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

                        is GroupChatViewModel.TranslationState.Failed -> {
                            Icon(
                                Icons.Default.GTranslate,
                                contentDescription = null,
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

/**
 * 🤖 A bot inline button of kind `url` — hand the link to the system.
 *
 * `ACTION_VIEW` rather than an `androidx.browser` Custom Tab: this module does not
 * depend on `androidx.browser` and adding it is a dependency decision, not a rendering
 * one. On a device with Chrome (or any browser that implements the protocol) the tab
 * the user gets is the same one; what is lost is the tinted toolbar, not the isolation.
 *
 * The scheme is re-checked here even though [BotReplyMarkupParser] already dropped
 * anything that is not http(s): this is the function that fires an implicit Intent, so
 * it is the function that must be safe to call with anything. A bot must never be able
 * to make a phone resolve `intent:`, `market:` or a private deep link by putting it in
 * a button.
 */
private fun openBotUrl(context: android.content.Context, url: String?) {
    if (url.isNullOrEmpty()) return
    val uri = Uri.parse(url)
    when (uri.scheme?.lowercase()) {
        "http", "https" -> Unit
        else -> return
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, R.string.bot_link_open_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * __GROUP_OPEN_ANCHOR_2026_09_23__ Keep a forward-layout chat LazyColumn pinned to
 * its newest row while the reader has not scrolled away — the Compose reading of
 * iOS's `.defaultScrollAnchor(.bottom, for: .sizeChanges)` + bottom-anchor sighting.
 *
 * - Starts `true`, so a cold open lands at the bottom on the first measured layout,
 *   and again after every late batch or bubble that grows after layout.
 * - A user drag clears it immediately; any scroll that settles away from the end
 *   (touch, fling, mouse wheel) clears it, one that settles at the end sets it back.
 *   Our own pinning scrolls never count, so a pin that lands short while rows are
 *   still materialising cannot switch following off.
 * - Never scrolls while the user's own scroll is in progress: reading history is
 *   never yanked.
 *
 * Returns the flag so callers can re-arm it (own send, scroll-to-bottom arrow).
 */
@Composable
private fun rememberFollowNewest(
    listState: androidx.compose.foundation.lazy.LazyListState,
    key: Any,
    initialFollow: Boolean = true,
): MutableState<Boolean> {
    val follow = remember(key) { mutableStateOf(initialFollow) }
    // [0] = a pin is running; [1] = end of the grace window after it (nanoTime).
    // The settle signal for our own scroll can be delivered after `finally`, so
    // "is it ours" is a short window, not just the in-flight flag.
    val ownScroll = remember(key) { longArrayOf(0L, 0L) }
    LaunchedEffect(listState, key) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) follow.value = false
        }
    }
    LaunchedEffect(listState, key) {
        // `drop(1)`: the first value is the idle state at composition, not a scroll
        // that settled — read before the first layout, `canScrollForward` is false
        // and it switched following ON, overriding a start at the first unread.
        snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling ->
            val ours = ownScroll[0] != 0L || System.nanoTime() < ownScroll[1]
            if (!scrolling && !ours) follow.value = !listState.canScrollForward
        }
    }
    LaunchedEffect(listState, key) {
        snapshotFlow {
            Triple(listState.layoutInfo.totalItemsCount, listState.canScrollForward, follow.value)
        }.collect { (total, canScrollForward, following) ->
            if (!following || total == 0 || !canScrollForward) return@collect
            if (listState.isScrollInProgress && ownScroll[0] == 0L) return@collect
            ownScroll[0] = 1L
            try {
                listState.scrollToItem(total - 1)
                // scrollToItem top-aligns the last row: a row taller than the
                // viewport would show its top. Finish the way down.
                // __LONG_SEND_LANDING_2026_09_28__ 8 screens was the old cap: a
                // 16 KB message (~8 000 chars, 200+ lines) is taller than that on
                // a phone with the keyboard up, the loop stopped short, and since
                // nothing in the snapshot triple changed afterwards the thread
                // stayed parked inside the bubble. 64 screens covers the limit.
                var guard = 0
                while (listState.canScrollForward && guard++ < 64) {
                    val step = listState.layoutInfo.viewportSize.height.coerceAtLeast(1).toFloat()
                    listState.scrollBy(step)
                }
            } finally {
                ownScroll[0] = 0L
                ownScroll[1] = System.nanoTime() + 300_000_000L
            }
        }
    }
    return follow
}


// =============================================================================
// __GROUP_COMMENTS_2026_11_21__ Comments under channel posts (GROUP_INFO_SETTINGS_SPEC §2.12.6)
// =============================================================================

@Composable
private fun CommentsBar(count: Int, onClick: () -> Unit) {
    val label = if (count == 0) stringResource(R.string.cm_leave_comment)
    else pluralStringResource(R.plurals.cm_comments_count, count, count)
    // __BUBBLE_MEDIA_2026_11_21__ The bottom row of the post card (the card clips it to
    // its corners): OSHI O mark, the count in brand INK (raw BrandColor was 2.0:1 on the
    // dark card), a chevron. Was a separate grey pill offset 48/64 dp from the post.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("group_comments_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.oshi.messenger.ui.components.OshiOMark(size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = brandInk())
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Oshi.Chat.metaInk(isDarkScheme()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCommentsThread(
    post: GroupMessage?,
    comments: List<GroupMessage>,
    active: Boolean,
    myPublicKey: String,
    iAmAdmin: Boolean,
    controlLabels: OSHIControlPayload.Labels,
    nameFor: (String) -> String,
    onReact: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSend: (String, GroupMessage?) -> GroupManager.CommentSendResult,
    onDismiss: () -> Unit,
    /** __INTEROP_2026_11_21__ §2.3: null = the default row, empty = none, else only these. */
    allowedReactions: List<String>? = null,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<GroupMessage?>(null) }
    var actionsFor by remember { mutableStateOf<GroupMessage?>(null) }
    val listState = rememberLazyListState()
    val visible = remember(comments, post?.id) {
        // A top-level comment quotes the post on the wire (old builds); here it quotes nothing.
        // Deleted comments are not drawn (they were an empty bubble with ticks).
        GroupComments.threadRows(comments, post?.id)
    }
    val quoteable = remember(visible) { visible }
    LaunchedEffect(visible.size) { if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size + 1) }

    // An in-tree overlay, not a Dialog: a full-width Dialog window is measured at display
    // height but placed below the status bar, which pushed the composer off-screen.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    run {
        Surface(Modifier.fillMaxSize().testTag("group_comments_thread"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(stringResource(R.string.cm_thread_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cm_thread_title))
                        }
                    }
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item(key = "post") {
                        if (post == null || post.isDeleted) {
                            Text(stringResource(R.string.cm_post_unavailable), Modifier.fillMaxWidth().padding(24.dp),
                                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            GroupMessageBubble(
                                message = post,
                                isChannel = true,
                                isOwnMessage = post.senderPublicKey == myPublicKey,
                                myPublicKey = myPublicKey,
                                controlLabels = controlLabels,
                                resolvedSenderName = nameFor(post.senderPublicKey),
                                senderNameFor = nameFor,
                                onReact = { e -> onReact(post.id, e) },
                                onLongPress = {},
                            )
                        }
                    }
                    item(key = "divider") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Divider()
                            Text(
                                if (visible.isEmpty()) stringResource(R.string.cm_empty)
                                else pluralStringResource(R.plurals.cm_comments_count, visible.size, visible.size),
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                textAlign = TextAlign.Center, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(visible, key = { it.id }) { c ->
                        Box(Modifier.testTag("group_comment_row")) {
                            GroupMessageBubble(
                                message = c,
                                isOwnMessage = c.senderPublicKey == myPublicKey,
                                myPublicKey = myPublicKey,
                                allMessages = quoteable,
                                controlLabels = controlLabels,
                                resolvedSenderName = nameFor(c.senderPublicKey),
                                senderNameFor = nameFor,
                                onReact = { e -> onReact(c.id, e) },
                                onLongPress = { if (!c.isDeleted) actionsFor = c },
                            )
                        }
                    }
                }
                if (!active) {
                    Text(stringResource(R.string.cm_disabled), Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (post != null && !post.isDeleted) {
                    replyTo?.let { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.cm_replying_to, nameFor(r.senderPublicKey)), fontSize = 12.sp,
                                    color = BrandColor, fontWeight = FontWeight.SemiBold)
                                Text(r.displayContent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { replyTo = null }) { Icon(Icons.Default.Close, contentDescription = null) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(GroupComments.MAX_LENGTH) },
                            modifier = Modifier.weight(1f).testTag("group_comment_input"),
                            placeholder = { Text(stringResource(R.string.cm_composer_hint)) },
                            maxLines = 5,
                            shape = RoundedCornerShape(20.dp)
                        )
                        IconButton(
                            onClick = {
                                when (onSend(draft, replyTo)) {
                                    GroupManager.CommentSendResult.SENT -> { draft = ""; replyTo = null }
                                    GroupManager.CommentSendResult.TOO_FAST ->
                                        android.widget.Toast.makeText(context, R.string.cm_too_fast, android.widget.Toast.LENGTH_SHORT).show()
                                    GroupManager.CommentSendResult.INACTIVE ->
                                        android.widget.Toast.makeText(context, R.string.cm_disabled, android.widget.Toast.LENGTH_SHORT).show()
                                    GroupManager.CommentSendResult.NO_POST ->
                                        android.widget.Toast.makeText(context, R.string.cm_post_unavailable, android.widget.Toast.LENGTH_SHORT).show()
                                    GroupManager.CommentSendResult.EMPTY -> Unit
                                }
                            },
                            enabled = draft.isNotBlank(),
                            modifier = Modifier.testTag("group_comment_send")
                        ) { Icon(Icons.Default.Send, contentDescription = null, tint = BrandColor) }
                    }
                }
            }
        }
    }

    actionsFor?.let { c ->
        val mine = c.senderPublicKey == myPublicKey
        OshiAlertDialog(
            onDismissRequest = { actionsFor = null },
            confirmButton = {
                TextButton(onClick = { replyTo = c; actionsFor = null }) { Text(stringResource(R.string.message_reply)) }
            },
            dismissButton = if (mine || iAmAdmin) {
                {
                    TextButton(onClick = { onDelete(c.id); actionsFor = null }) {
                        Text(stringResource(R.string.cm_delete_comment), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else null,
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (allowedReactions ?: listOf("👍", "❤️", "🔥", "😂", "😮", "🙏")).forEach { e ->
                        Text(e, fontSize = 26.sp, modifier = Modifier.clickable { onReact(c.id, e); actionsFor = null }.padding(4.dp))
                    }
                }
            }
        )
    }
}


/**
 * __CHANNEL_WEB_2026_11_26__ "Embed code" sheet (PUBLIC_CHANNEL_WEB_SPEC §8): the one-line
 * widget snippet for a post, a dark-theme switch (adds `data-dark="1"`), and Copy.
 */
@Composable
internal fun ChannelEmbedDialog(handle: String, n: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var dark by remember { mutableStateOf(false) }
    val code = com.oshi.messenger.service.channelweb.ChannelWebLinks.embedSnippet(handle, n, dark)
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cw_embed_title)) },
        text = {
            Column {
                Text(stringResource(R.string.cw_embed_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        code,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .testTag("cw_embed_code")
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cw_embed_dark), Modifier.weight(1f))
                    Switch(checked = dark, onCheckedChange = { dark = it }, modifier = Modifier.testTag("cw_embed_dark"))
                }
                Text(
                    com.oshi.messenger.service.channelweb.ChannelWebLinks.postUrl(handle, n),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(code))
                Toast.makeText(context, context.getString(R.string.cw_code_copied), Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.testTag("cw_embed_copy")) { Text(stringResource(R.string.cw_copy_code)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
