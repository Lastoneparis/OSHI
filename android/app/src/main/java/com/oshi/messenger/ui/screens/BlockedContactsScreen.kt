package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import coil.request.ImageRequest
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.viewmodel.SettingsViewModel

/**
 * BlockedContactsScreen - Manage blocked contacts
 * Compatible with iOS BlockedContactsView
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedContactsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val blockedContacts by viewModel.blockedContacts.collectAsState()

    var contactToUnblock by remember { mutableStateOf<Contact?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.key_backup_hide))
                    }
                },
                title = { Text(stringResource(R.string.blocked_contacts_title)) }
            )
        }
    ) { padding ->
        if (blockedContacts.isEmpty()) {
            // Empty state
            EmptyBlockedContacts(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header with count
                item {
                    Text(
                        text = if (blockedContacts.size == 1) stringResource(R.string.blocked_contacts_count_one, blockedContacts.size) else stringResource(R.string.blocked_contacts_count_other, blockedContacts.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(
                    items = blockedContacts,
                    key = { it.publicKey }
                ) { contact ->
                    BlockedContactItem(
                        contact = contact,
                        onUnblock = { contactToUnblock = contact }
                    )
                }

                // Info footer
                item {
                    InfoCard(
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }

    // Unblock confirmation dialog
    contactToUnblock?.let { contact ->
        OshiAlertDialog(
            onDismissRequest = { contactToUnblock = null },
            title = { Text(stringResource(R.string.blocked_contacts_unblock_title)) },
            text = {
                Text(stringResource(R.string.blocked_contacts_unblock_confirm, contact.alias))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unblockContact(contact.publicKey)
                        contactToUnblock = null
                    }
                ) {
                    Text(stringResource(R.string.blocked_contacts_unblock))
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToUnblock = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun BlockedContactItem(
    contact: Contact,
    onUnblock: () -> Unit
) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            if (contact.profilePicture != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(contact.profilePicture)
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(R.string.accessibility_profile),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Default avatar with initials
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.opacity(0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = getInitials(contact.alias),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Contact info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = contact.alias.ifEmpty { stringResource(R.string.blocked_contacts_unknown) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatPublicKey(contact.publicKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Unblock button
            Button(
                onClick = onUnblock,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OSHIRed,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.blocked_contacts_unblock),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun EmptyBlockedContacts(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.PersonOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.blocked_contacts_empty),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.blocked_contacts_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier
) {
    OshiCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = OSHIPurple.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Block,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = OSHIPurple
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = stringResource(R.string.blocked_contacts_about_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = OSHIPurple
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.blocked_contacts_about_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// HELPER FUNCTIONS
// =============================================================================

private fun getInitials(name: String): String {
    return name.take(2).uppercase()
}

private fun formatPublicKey(key: String): String {
    return if (key.length > 12) {
        "${key.take(8)}..."
    } else {
        key
    }
}

private fun Color.opacity(alpha: Float): Color {
    return this.copy(alpha = alpha)
}
