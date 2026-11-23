package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.oshi.messenger.R
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-chat media gallery (P2 #20).
 *
 * 3-column grid of all image/video attachments in a 1-on-1 chat (peerPublicKey)
 * or a group (groupId), newest first. Tapping a cell navigates to the existing
 * MediaViewerScreen via [onOpenMedia].
 *
 * Thumbnail loading uses Coil's AsyncImage (in-memory + disk cache) and lets Coil
 * downsample to the display size, so full-res blobs never need to be decoded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(
    peerPublicKey: String?,
    groupId: String?,
    onBack: () -> Unit,
    onOpenMedia: (mediaData: ByteArray, mediaType: String, caption: String) -> Unit,
    viewModel: MediaGalleryViewModel = hiltViewModel()
) {
    LaunchedEffect(peerPublicKey, groupId) {
        viewModel.load(peerPublicKey = peerPublicKey, groupId = groupId)
    }

    val items by viewModel.items.collectAsState()
    val galleryScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.media_gallery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back_button))
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.media_gallery_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items, key = { it.messageId }) { item ->
                MediaGalleryCell(
                    item = item,
                    onClick = {
                        val mediaTypeString = if (item.isVideo) "VIDEO" else "IMAGE"
                        val inMemory = item.data
                        if (inMemory != null) onOpenMedia(inMemory, mediaTypeString, "")
                        // __GROUP_MEDIA_MEMORY_2026_11_11__ a large video kept on disk.
                        else galleryScope.launch {
                            viewModel.loadGroupMediaBytes(item.messageId)?.let { onOpenMedia(it, mediaTypeString, "") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MediaGalleryCell(
    item: MediaGalleryItem,
    onClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Coil handles downsampling / caching — we never decode full-res for the grid.
        // Passing the raw ByteArray as `data` triggers Coil's built-in ByteArray fetcher.
        // Two corrections here, both silent failures:
        //
        //  * `.data(item.data)` handed Coil a raw `ByteArray`, and Coil 2's default
        //    component registry has a `ByteBufferFetcher` and NO `ByteArray` fetcher —
        //    the model resolved to nothing, so the grid drew empty cells. Wrapped.
        //  * it used the process-default `ImageLoader`, whose disk cache writes every
        //    decoded source to `cacheDir/image_cache`. In an end-to-end encrypted
        //    messenger that persists plaintext photos where nothing in the app's media
        //    lifecycle (view-once burn, orphan purge, account delete) will ever find
        //    them. `BubbleMedia`'s loader is memory-only.
        //
        // The key is explicit for the same reason as in `BubbleMedia.request`:
        // `HeapByteBuffer.toString()` is identical for any two buffers of equal length.
        val cellData = item.data
        // __GROUP_MEDIA_MEMORY_2026_11_11__ no bytes in memory (large video on disk):
        // the cell is the surface plus the play badge below; the bytes load on tap.
        if (cellData != null) AsyncImage(
            model = com.oshi.messenger.ui.media.BubbleMedia.request(
                context = context,
                messageId = com.oshi.messenger.ui.media.BubbleMedia.contentKey(cellData),
                data = cellData,
                // Explicit small target size: Coil downsamples to ~300px.
                maxDimensionPx = 300
            ),
            imageLoader = com.oshi.messenger.ui.media.BubbleMedia.imageLoader(context),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// =========================================================================
// ViewModel
// =========================================================================

data class MediaGalleryItem(
    val messageId: String,
    /** Null for a large group video kept on disk (`GroupMediaMemory`); read on tap. */
    val data: ByteArray?,
    val isVideo: Boolean,
    val timestamp: Long
) {
    // Room data classes with ByteArray need custom equals/hashCode for the
    // `items` key lookup — we only compare by messageId since that's unique.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaGalleryItem) return false
        return messageId == other.messageId
    }
    override fun hashCode(): Int = messageId.hashCode()
}

/**
 * The grid's whole content decision, as two pure functions.
 *
 * Extracted from the ViewModel so it is exercisable from a JVM unit test with
 * hand-written rows: the ViewModel itself needs Hilt, a Room database and a
 * `GroupManager`, none of which exist in this module's test source set.
 */
internal object MediaGalleryProjection {

    private fun isRenderable(mediaType: MediaType?): Boolean =
        mediaType == MediaType.IMAGE || mediaType == MediaType.VIDEO

    /** 1-on-1 rows. Callers must pass rows whose blobs are already hydrated. */
    fun fromMessages(messages: List<Message>): List<MediaGalleryItem> =
        messages
            .filter { it.mediaData != null && isRenderable(it.mediaType) && !it.isDeleted && !it.isDeletedForEveryone }
            .sortedByDescending { it.timestamp }
            .map {
                MediaGalleryItem(
                    messageId = it.id,
                    data = it.mediaData!!,
                    isVideo = it.mediaType == MediaType.VIDEO,
                    timestamp = it.timestamp
                )
            }

    /** Group rows, straight off [com.oshi.messenger.service.GroupManager.groupMessages]. */
    fun fromGroupMessages(messages: List<GroupMessage>): List<MediaGalleryItem> =
        messages
            // __GROUP_MEDIA_MEMORY_2026_11_11__ a VIDEO with no bytes is a large one kept
            // on disk — still listed; an image without bytes has nothing to show.
            .filter {
                (it.mediaData != null || it.mediaType == MediaType.VIDEO) &&
                    isRenderable(it.mediaType) && !it.isDeleted
            }
            .sortedByDescending { it.timestamp }
            .map {
                MediaGalleryItem(
                    messageId = it.id,
                    data = it.mediaData,
                    isVideo = it.mediaType == MediaType.VIDEO,
                    timestamp = it.timestamp
                )
            }
}

@HiltViewModel
class MediaGalleryViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val groupManager: com.oshi.messenger.service.GroupManager
) : ViewModel() {

    /** __GROUP_MEDIA_MEMORY_2026_11_11__ */
    suspend fun loadGroupMediaBytes(messageId: String): ByteArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { groupManager.loadMediaBytes(messageId) }

    private val _items = MutableStateFlow<List<MediaGalleryItem>>(emptyList())
    val items: StateFlow<List<MediaGalleryItem>> = _items.asStateFlow()

    /**
     * Load all image+video attachments for the chat identified by either
     * [peerPublicKey] (1-on-1) or [groupId] (group). Sorted newest-first.
     *
     * 🔧 P0 FIX (2026-08-24 feature check). The group branch used to read
     * `GroupRepository.getGroupMessages`, i.e. the Room `group_messages` table —
     * a table **nothing in the app wrote**. Its only writer,
     * `GroupRepository.sendGroupMessage`, had zero call sites; `GroupRepository` as a
     * whole had exactly one consumer in the tree, this screen. (`GroupRepository` was
     * DELETED on 2026-08-25 once this screen stopped using it; the table now has a real
     * writer, `GroupManager.startRoomMirror`, for the readers that remain — the web
     * bridge and group FTS search. This branch still reads the manager directly, which is
     * what iOS does.) The group's real messages live in
     * `GroupManager._groupMessages` (`service/GroupManager.kt:377`), persisted to the
     * `groups` SharedPreferences under `group_messages_data`
     * (`service/GroupManager.kt:3314`) — which is also the source `GroupChatViewModel`
     * renders from (`ui/viewmodel/GroupChatViewModel.kt:116`). So the group gallery was
     * **always empty**, on a screen whose bubbles right behind it were full of photos.
     *
     * iOS reads the same store the thread reads: `MediaGalleryView.swift` filters
     * `GroupManager.shared.messages(for:)` / `MessageManager.shared.messages`, never a
     * second table.
     *
     * The key lookup is an EXACT map lookup, deliberately identical to
     * `GroupChatViewModel.kt:117` (`allMessages[id]`): the gallery is opened from the
     * group thread with the very same `groupId` string, so if the thread found rows the
     * gallery finds the same ones. A case-insensitive fallback here would only hide a
     * divergence between the two screens.
     */
    fun load(peerPublicKey: String?, groupId: String?) {
        viewModelScope.launch {
            when {
                groupId != null -> {
                    groupManager.groupMessages.collect { allMessages ->
                        _items.value =
                            MediaGalleryProjection.fromGroupMessages(allMessages[groupId] ?: emptyList())
                    }
                }
                peerPublicKey != null -> {
                    messageRepository.getMessagesForContact(peerPublicKey).collect { msgs ->
                        _items.value = MediaGalleryProjection.fromMessages(msgs)
                    }
                }
                else -> {
                    _items.value = emptyList()
                }
            }
        }
    }
}
