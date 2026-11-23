package com.oshi.messenger.ui.components

import androidx.compose.runtime.collectAsState
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.oshi.messenger.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.viewmodel.ForwardPickerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPickerSheet(
    content: String,
    mediaType: MediaType?,
    mediaData: ByteArray?,
    onDismiss: () -> Unit,
    onForwarded: (String) -> Unit,  // target name, for toast
    /** __VIDEO_NOTE_2026_09_24__ non-null ⇒ forward as a round video note (0 = unknown length). */
    videoNoteDurationMs: Long? = null,
    /** The attachment's real name when the source row knows it (a group document). */
    fileName: String? = null,
    viewModel: ForwardPickerViewModel = hiltViewModel()
) {
    val targets by viewModel.targets.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    // __FORWARD_SEARCH_BY_KEY_2026_11_05__ The predicate was `it.name.contains`,
    // which could never find anyone by key: `name` is alias > nickname >
    // `publicKey.take(8) + "..."`, so the most key material it ever holds is 8
    // characters. Pasting a full key returned nothing — the worst answer, since
    // the user is holding the exact identifier.
    //
    // Alias and key match under different case rules on purpose; see
    // `ForwardTargetSearch` for why (base64 case is significant, and the action
    // at the end of this list is forwarding a private message).
    val affinities by com.oshi.messenger.service.ForwardAffinityStore.entries.collectAsState()

    val filtered = remember(targets, searchQuery) {
        targets.filter {
            com.oshi.messenger.service.ForwardTargetSearch.matches(searchQuery, it.name, it.publicKey)
        }
    }
    // __FORWARD_MOST_USED_FIRST_2026_11_05__ Most-forwarded first, then the
    // order the lists already arrived in (groups by recency, contacts
    // alphabetically) — a re-rank, so a user who has never forwarded anything
    // still sees a sensibly ordered list.
    val groups = remember(filtered, affinities) {
        com.oshi.messenger.service.ForwardAffinityStore.rank(
            filtered.filter { it.isGroup }, affinities
        ) { it.id }
    }
    val contacts = remember(filtered, affinities) {
        com.oshi.messenger.service.ForwardAffinityStore.rank(
            filtered.filter { !it.isGroup }, affinities
        ) { it.id }
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.forward_to_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.accessibility_close))
                }
            }

            // Search
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.forward_picker_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OSHIPurple,
                        cursorColor = OSHIPurple
                    )
                )
            }

            // List
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (groups.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.forward_picker_groups),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    items(groups, key = { it.id }) { target ->
                        ForwardTargetRow(
                            target = target,
                            onClick = {
                                viewModel.forward(target, content, mediaType, mediaData, fileName = fileName, videoNoteDurationMs = videoNoteDurationMs)
                                onForwarded(target.name)
                                onDismiss()
                            }
                        )
                    }
                }
                if (contacts.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.forward_picker_contacts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                    items(contacts, key = { it.id }) { target ->
                        ForwardTargetRow(
                            target = target,
                            onClick = {
                                viewModel.forward(target, content, mediaType, mediaData, fileName = fileName, videoNoteDurationMs = videoNoteDurationMs)
                                onForwarded(target.name)
                                onDismiss()
                            }
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.forward_no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardTargetRow(
    target: ForwardPickerViewModel.ForwardTarget,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            ForwardAvatar(
                avatarBytes = target.avatarBytes,
                name = target.name,
                isGroup = target.isGroup
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (target.isGroup) "Group" else "Contact",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun ForwardAvatar(
    avatarBytes: ByteArray?,
    name: String,
    isGroup: Boolean
) {
    val bitmap = remember(avatarBytes) {
        avatarBytes?.let { bytes ->
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null }
        }
    }

    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val gradientColors = if (isGroup) {
                listOf(OSHIPurple, OSHIPurple.copy(alpha = 0.7f))
            } else {
                listOf(Color(0xFF4CAF50), Color(0xFF2E7D32))
            }
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(gradientColors)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isGroup) Icons.Default.Group else Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
