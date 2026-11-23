package com.oshi.messenger.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.oshi.messenger.wear.R
import com.oshi.messenger.wear.data.Load
import com.oshi.messenger.wear.data.Reason
import com.oshi.messenger.wear.data.WearSession
import com.oshi.wearbridge.WearConversation

@Composable
fun ConversationListScreen(session: WearSession, onOpen: (WearConversation) -> Unit) {
    val state by session.list.collectAsState()
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        when (val s = state) {
            is Load.Loading -> Loading()
            is Load.Failed -> StatusPanel(s.reason, onRetry = { session.refreshList() })
            is Load.Ready -> if (s.value.isEmpty()) {
                EmptyPanel()
            } else {
                ScalingLazyColumn(
                    state = listState,
                    contentPadding = padding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        ListHeader {
                            Text(
                                stringResource(R.string.chats_title),
                                fontWeight = FontWeight.SemiBold,
                                color = OshiColors.BrandInk,
                            )
                        }
                    }
                    items(s.value, key = { it.kind.wire + it.id }) { c -> ConversationRow(c) { onOpen(c) } }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: WearConversation, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .glass(shape)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(key = c.avatarKey, name = c.name, size = 34.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.name,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = OshiColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = if (c.lastFromMe && c.preview.isNotBlank()) stringResource(R.string.you_prefix, c.preview) else c.preview
            Text(
                preview,
                fontSize = 12.sp,
                color = if (c.unread > 0) OshiColors.TextPrimary.copy(alpha = 0.86f) else OshiColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.unread > 0) {
            Spacer(Modifier.width(6.dp))
            UnreadBadge(c.unread)
        }
    }
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .glassGlow(CircleShape, spread = 4.dp)
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(OutgoingBrush, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.White,
        )
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun EmptyPanel() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.mascot_wave), contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.empty_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.empty_body), fontSize = 12.sp, color = OshiColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Off / locked / unreachable: the mascot, one title, one sentence telling
 * the person what to do on the phone, and Retry.
 */
@Composable
fun StatusPanel(reason: Reason, onRetry: () -> Unit) {
    val (title, body) = when (reason) {
        Reason.DISABLED -> R.string.status_disabled_title to R.string.status_disabled_body
        Reason.LOCKED -> R.string.status_locked_title to R.string.status_locked_body
        Reason.UNAVAILABLE -> R.string.status_unavailable_title to R.string.status_unavailable_body
        Reason.NO_PHONE -> R.string.status_no_phone_title to R.string.status_no_phone_body
        Reason.NOT_FOUND -> R.string.status_error_title to R.string.status_not_found
        else -> R.string.status_error_title to R.string.status_error_body
    }
    val mascot = when (reason) {
        Reason.LOCKED, Reason.DISABLED -> R.drawable.mascot_sleep
        else -> R.drawable.mascot_cry
    }
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { Image(painterResource(mascot), contentDescription = null, modifier = Modifier.size(44.dp)) }
        item {
            Text(
                stringResource(title), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                textAlign = TextAlign.Center, color = OshiColors.TextPrimary,
            )
        }
        item {
            Text(
                stringResource(body), fontSize = 12.sp, color = OshiColors.TextSecondary,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        item {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = OshiColors.Purple, contentColor = androidx.compose.ui.graphics.Color.White),
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(stringResource(R.string.retry)) }
        }
    }
}
