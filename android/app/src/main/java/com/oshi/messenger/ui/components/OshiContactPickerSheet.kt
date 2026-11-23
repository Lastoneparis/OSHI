package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.ui.theme.OSHIPurple

/**
 * Pick an **OSHI** contact to share into a conversation.
 *
 * 🔴 Why this replaced `ActivityResultContracts.PickContact()`. The attachment
 * menu used to open the SYSTEM address book, read `DISPLAY_NAME`, and send
 * `"[Contact] Alice"` as plain text — a message with no public key in it, that no
 * recipient on either platform could act on. iOS shares a **reachable identity**:
 * `ContactSelectorView` (`OSHI/ContactSelectorView.swift:11-43`) lists
 * `messageManager.conversations` and hands back `(publicKey, alias)` to
 * `MediaManager.createContactCard`. This is that list.
 *
 * Deliberately NOT a general-purpose picker: it reports `(publicKey, alias)` and
 * nothing else, because that pair is exactly what the wire carries
 * ([com.oshi.messenger.data.model.ContactCardWire]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OshiContactPickerSheet(
    contacts: List<Contact>,
    onContactSelected: (publicKey: String, alias: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(contacts, searchQuery) {
        OshiContactPickerFilter.filter(contacts, searchQuery)
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.share_contact),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.accessibility_close)
                    )
                }
            }

            if (contacts.isNotEmpty()) {
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
            }

            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_contacts_to_share),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filtered, key = { it.publicKey }) { contact ->
                        OshiContactPickerRow(
                            contact = contact,
                            onClick = {
                                onContactSelected(contact.publicKey, contact.alias)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OshiContactPickerRow(contact: Contact, onClick: () -> Unit) {
    // __SHARED_NICKNAME_2026_09_22__ alias > peer's shared nickname > key prefix.
    val displayName = com.oshi.messenger.data.model.PeerNicknameStore.displayName(contact.alias, contact.publicKey)
    val initial = displayName.firstOrNull()?.uppercase() ?: "?"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        // __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__ was one OSHIPurple
        // disc for every contact; now the colour the conversation list uses.
        com.oshi.messenger.ui.theme.ContactAvatar(
            publicKey = contact.publicKey,
            name = displayName,
            size = 44.dp,
            initialCount = 1,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = displayName,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = contact.publicKey.take(16) + "…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(0.dp))
    }
}

/**
 * The search rule, lifted out of the composable so it can be unit-tested on the
 * JVM. Matches iOS's filter (`OSHI/ContactSelectorView.swift:34-41`): alias OR
 * public key, case-insensitively, empty query = everything.
 */
object OshiContactPickerFilter {
    fun filter(contacts: List<Contact>, query: String): List<Contact> {
        val q = query.trim()
        if (q.isEmpty()) return contacts
        return contacts.filter {
            it.alias.contains(q, ignoreCase = true) || it.publicKey.contains(q, ignoreCase = true) ||
                // __SHARED_NICKNAME_2026_09_22__ the row shows the shared nickname, so it must be searchable.
                com.oshi.messenger.data.model.PeerNicknameStore.lookup(it.publicKey)
                    ?.contains(q, ignoreCase = true) == true
        }
    }
}
