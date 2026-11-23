package com.oshi.messenger.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.oshi.messenger.wear.R
import com.oshi.messenger.wear.data.Load
import com.oshi.messenger.wear.data.WearSession
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearMedia
import com.oshi.wearbridge.WearMessage

/**
 * The last ~20 messages, newest at the bottom (the list opens there). Media is
 * never on the watch: a labelled placeholder says what it was. Tap a bubble to
 * react to it or answer; the edge button answers the latest message.
 */
@Composable
fun ThreadScreen(session: WearSession, kind: WearConvKind, id: String, onReply: (String?) -> Unit) {
    LaunchedEffect(kind, id) { session.refreshThread(kind, id) }
    val threads by session.threads.collectAsState()
    val state = threads[session.key(kind, id)] ?: Load.Loading
    val canReply = session.conversation(kind, id)?.canReply ?: true
    val messages = (state as? Load.Ready)?.value?.messages.orEmpty()
    val listState = rememberScalingLazyListState(
        initialCenterItemIndex = (messages.size).coerceAtLeast(1),
    )
    // Re-anchor at the newest message once the thread has arrived.
    // The offset is clamped to the end of the list, so this lands on the true
    // bottom: last bubble above the fully grown edge button.
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        // Wait for the list to know its items, then go to the very end (the
        // overscroll is clamped): last bubble above the fully grown edge button.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > messages.size }
        listState.scrollToItem(messages.size)
        // One scrollBy only reaches what is laid out; step until the end.
        repeat(20) {
            if (!listState.canScrollForward) return@LaunchedEffect
            listState.scrollBy(400f)
            withFrameNanos { }
        }
    }

    val replyTarget = messages.lastOrNull { !it.fromMe }?.id ?: messages.lastOrNull()?.id
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (state is Load.Ready && canReply) {
                EdgeButton(
                    onClick = { onReply(replyTarget) },
                    buttonSize = EdgeButtonSize.Small,
                    colors = ButtonDefaults.buttonColors(containerColor = OshiColors.Purple, contentColor = Color.White),
                ) { Text(stringResource(R.string.reply), fontWeight = FontWeight.SemiBold) }
            }
        },
    ) { padding ->
        when (state) {
            is Load.Loading -> Loading()
            is Load.Failed -> StatusPanel(state.reason) { session.refreshThread(kind, id) }
            is Load.Ready -> ScalingLazyColumn(
                state = listState,
                contentPadding = padding,
                anchorType = ScalingLazyListAnchorType.ItemStart,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    ListHeader {
                        Text(
                            state.value.title.ifBlank { session.conversation(kind, id)?.name.orEmpty() },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = OshiColors.TextPrimary,
                        )
                    }
                }
                items(state.value.messages, key = { it.id }) { m ->
                    Bubble(m, isGroup = kind == WearConvKind.GROUP) { if (canReply) onReply(m.id) }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: WearMessage, isGroup: Boolean, onClick: () -> Unit) {
    val mine = m.fromMe
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 168.dp)
                .then(
                    if (mine) Modifier.clip(shape).background(OutgoingBrush, shape).glassHairline(shape)
                    else Modifier.glass(shape, OshiColors.Bubble)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            if (isGroup && !mine && !m.sender.isNullOrBlank()) {
                Text(
                    m.sender, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = nameColor(m.senderKey), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            m.media?.let { MediaPlaceholder(it, mine) }
            val text = if (m.text == "👁") stringResource(R.string.view_once) else m.text
            if (text.isNotBlank()) {
                Text(text, fontSize = 13.sp, color = if (mine) Color.White else OshiColors.TextPrimary)
            }
        }
        if (m.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .offset(y = (-3).dp)
                    .padding(horizontal = 6.dp)
                    .glass(RoundedCornerShape(10.dp), OshiColors.Bubble)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                m.reactions.forEach { ReactionGlyph(it, 18.dp) }
            }
        }
    }
}

@Composable
private fun MediaPlaceholder(media: WearMedia, mine: Boolean) {
    val label = when (media) {
        WearMedia.PHOTO -> R.string.media_photo
        WearMedia.VIDEO -> R.string.media_video
        WearMedia.VOICE -> R.string.media_voice
        WearMedia.GIF -> R.string.media_gif
        WearMedia.FILE -> R.string.media_file
        WearMedia.LOCATION -> R.string.media_location
        WearMedia.CONTACT -> R.string.media_contact
    }
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (mine) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            stringResource(label), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (mine) Color.White else OshiColors.TextPrimary,
        )
    }
}
