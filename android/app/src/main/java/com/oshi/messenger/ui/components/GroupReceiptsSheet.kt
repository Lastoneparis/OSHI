package com.oshi.messenger.ui.components

import androidx.compose.foundation.layout.*
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.messenger.R
import com.oshi.messenger.service.GroupReceipts
import java.text.DateFormat
import java.util.Date

/**
 * "Who has seen this" for one of your own group messages.
 *
 * __GROUP_RECEIPTS_2026_11_02__ (N8) Port of `OSHI/GroupReceipts.swift`'s
 * sheet. Three sections, in the order the question is actually asked: read,
 * then delivered-but-not-read, then nothing known.
 *
 * The third section is the one that matters and the easy one to drop. A member
 * nobody has heard from is not "delivered", and a list that quietly omitted
 * them would read as everyone having received the message — the opposite of
 * the truth, and worse than showing no sheet at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupReceiptsSheet(
    groupId: String,
    msgId: String,
    members: List<String>,
    sender: String,
    /** Resolves a public key to whatever the user calls that person. */
    displayName: (String) -> String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Read once per opening. Receipts arrive over the network, but a sheet that
    // re-sorted itself under the reader's finger would be worse than one that
    // shows the state as at the moment they asked.
    val rows = remember(groupId, msgId) {
        GroupReceipts.rows(members, sender, GroupReceipts.states(context, groupId, msgId))
    }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.group_receipts_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                section(
                    titleRes = R.string.group_receipts_read,
                    icon = Icons.Default.DoneAll,
                    rows = rows.filter { it.hasRead },
                    stamp = { it.readAt },
                    displayName = displayName,
                    format = { timeFormat.format(Date(it)) },
                )
                section(
                    titleRes = R.string.group_receipts_delivered,
                    icon = Icons.Default.Check,
                    rows = rows.filter { !it.hasRead && it.hasReceived },
                    stamp = { it.receivedAt },
                    displayName = displayName,
                    format = { timeFormat.format(Date(it)) },
                )
                section(
                    titleRes = R.string.group_receipts_pending,
                    icon = Icons.Default.Schedule,
                    rows = rows.filter { !it.hasReceived },
                    stamp = { null },
                    displayName = displayName,
                    format = { timeFormat.format(Date(it)) },
                )
            }
        }
    }
}

/**
 * An empty section is omitted rather than drawn empty: three headers with
 * nothing under two of them reads as a broken screen.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    titleRes: Int,
    icon: ImageVector,
    rows: List<GroupReceipts.Row>,
    stamp: (GroupReceipts.Row) -> Long?,
    displayName: (String) -> String,
    format: (Long) -> String,
) {
    if (rows.isEmpty()) return
    item(key = "h$titleRes") {
        Text(
            "${stringResource(titleRes)} (${rows.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
    }
    items(rows, key = { "$titleRes${it.memberKey}" }) { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                displayName(row.memberKey),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            stamp(row)?.let {
                Text(
                    format(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
