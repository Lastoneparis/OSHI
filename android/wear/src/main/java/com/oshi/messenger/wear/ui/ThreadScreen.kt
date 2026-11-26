package com.oshi.messenger.wear.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.oshi.wearbridge.WearDelivery
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
/** Test hooks: the list, the Reply edge button, and each bubble row ("bubble:<id>"). */
const val THREAD_LIST_TAG = "thread_list"
const val REPLY_EDGE_TAG = "reply_edge"
const val BUBBLE_TAG_PREFIX = "bubble:"

@Composable
fun ThreadScreen(session: WearSession, kind: WearConvKind, id: String, onReply: (String?) -> Unit) {
    LaunchedEffect(kind, id) { session.refreshThread(kind, id) }
    val threads by session.threads.collectAsState()
    val state = threads[session.key(kind, id)] ?: Load.Loading
    val canReply = session.conversation(kind, id)?.canReply ?: true
    val messages = (state as? Load.Ready)?.value?.messages.orEmpty()
    val thumbs by session.thumbs.collectAsState()
    // __WEAR_POLISH_2026_11_25__ On screen = read, as opening it on the phone (phone 1.7.4+).
    LaunchedEffect(state is Load.Ready) { if (state is Load.Ready) session.markRead(kind, id) }
    val listState = rememberScalingLazyListState(
        initialCenterItemIndex = (messages.size).coerceAtLeast(1),
    )
    val replyTarget = messages.lastOrNull { !it.fromMe }?.id ?: messages.lastOrNull()?.id
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            if (state is Load.Ready && canReply) {
                EdgeButton(
                    modifier = Modifier.testTag(REPLY_EDGE_TAG),
                    onClick = { onReply(replyTarget) },
                    buttonSize = EdgeButtonSize.Small,
                    colors = ButtonDefaults.buttonColors(containerColor = OshiColors.Purple, contentColor = Color.White),
                ) { Text(stringResource(R.string.reply), fontWeight = FontWeight.SemiBold) }
            }
        },
    ) { padding ->
        // Re-anchor at the newest message once the thread has arrived — and AGAIN when
        // the bottom padding grows: the Reply edge button only appears with the data,
        // so the scaffold's padding goes 13 dp → ~75 dp a frame after the first
        // scroll-to-end, and a list left where it was would keep its last bubble
        // behind the button. Keyed on both, the resting state is the true end.
        val bottomPadding = padding.calculateBottomPadding()
        LaunchedEffect(messages.size, bottomPadding) {
            if (messages.isEmpty()) return@LaunchedEffect
            // Wait for the list to know its items, then step to the very end (one
            // scrollBy only reaches what is laid out; the overscroll is clamped).
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > messages.size }
            listState.scrollToItem(messages.size)
            repeat(20) {
                if (!listState.canScrollForward) return@LaunchedEffect
                listState.scrollBy(400f)
                withFrameNanos { }
            }
        }
        when (state) {
            is Load.Loading -> Loading()
            is Load.Failed -> StatusPanel(state.reason, listState, padding) { session.refreshThread(kind, id) }
            is Load.Ready -> ScalingLazyColumn(
                state = listState,
                contentPadding = padding,
                anchorType = ScalingLazyListAnchorType.ItemStart,
                // No auto-centering: with it the list's end is "last item's START at the
                // centre", so a tall last bubble hung down behind the edge button. Without
                // it the end is the content end + the scaffold's bottom padding, i.e. the
                // whole last bubble sits above the fully grown Reply button.
                autoCentering = null,
                modifier = Modifier.fillMaxSize().testTag(THREAD_LIST_TAG),
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
                    if (m.hasThumb) LaunchedEffect(m.id) { session.requestThumb(kind, id, m.id) }
                    Box(Modifier.testTag(BUBBLE_TAG_PREFIX + m.id)) {
                        Bubble(
                            m, isGroup = kind == WearConvKind.GROUP,
                            thumb = if (m.hasThumb) thumbs[m.id] else null,
                        ) { if (canReply) onReply(m.id) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Bubble(m: WearMessage, isGroup: Boolean, thumb: ByteArray? = null, onClick: () -> Unit) {
    val mine = m.fromMe
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    val context = LocalContext.current
    val time = remember(m.timestamp) { shortTime(context, m.timestamp) }
    // The whole row is the touch target (≥ 48 dp tall, full width), so a short
    // "ok" bubble is as easy to hit as a long one (Wear quality: touch targets).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
      Row(verticalAlignment = Alignment.Bottom) {
        // Group: the sender's face next to their bubble, as on the phone.
        if (isGroup && !mine) {
            Avatar(key = m.senderKey ?: m.sender.orEmpty(), name = m.sender, size = 18.dp)
            Spacer(Modifier.width(4.dp))
        }
        Column(
            modifier = Modifier
                .widthIn(max = if (isGroup && !mine) 146.dp else 168.dp)
                .then(
                    if (mine) Modifier.clip(shape).background(OutgoingBrush, shape).glassHairline(shape)
                    else Modifier.glass(shape, OshiColors.Bubble)
                )
                .padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            if (isGroup && !mine && !m.sender.isNullOrBlank()) {
                Text(
                    m.sender, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = nameColor(m.senderKey), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val bitmap = remember(thumb) {
                thumb?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
            }
            when {
                bitmap != null -> Image(
                    bitmap, contentDescription = stringResource(R.string.media_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .widthIn(max = 140.dp)
                        .aspectRatio((bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).coerceIn(0.75f, 1.6f))
                        .clip(RoundedCornerShape(12.dp)),
                )
                m.media != null -> MediaPlaceholder(m.media!!, mine, m.durationSec)
            }
            val text = if (m.text == "👁") stringResource(R.string.view_once) else m.text
            if (text.isNotBlank()) {
                Text(text, fontSize = 13.sp, color = if (mine) Color.White else OshiColors.TextPrimary)
            }
            BubbleFooter(time, if (mine) m.delivery else null, mine)
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

/** Time, and for my messages where it stands: ✓ sent, ✓✓ delivered, violet ✓✓ read, ! not delivered. */
@Composable
private fun ColumnScope.BubbleFooter(time: String, delivery: WearDelivery?, mine: Boolean) {
    if (time.isEmpty() && delivery == null) return
    val muted = if (mine) Color.White.copy(alpha = 0.72f) else OshiColors.TextSecondary
    val (mark, color, label) = when (delivery) {
        null -> Triple("", muted, null)
        WearDelivery.SENDING -> Triple("…", muted, R.string.delivery_sending)
        WearDelivery.SENT -> Triple("✓", muted, R.string.delivery_sent)
        WearDelivery.DELIVERED -> Triple("✓✓", muted, R.string.delivery_delivered)
        WearDelivery.READ -> Triple("✓✓", Color(0xFFD9C9FF), R.string.delivery_read)
        WearDelivery.FAILED -> Triple("!", OshiColors.Error, R.string.delivery_failed)
    }
    val desc = label?.let { stringResource(it) }
    Row(
        modifier = Modifier.align(Alignment.End).semantics(mergeDescendants = true) {
            if (desc != null) contentDescription = "$time, $desc"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (time.isNotEmpty()) Text(time, fontSize = 10.sp, color = muted, maxLines = 1)
        if (mark.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(mark, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun MediaPlaceholder(media: WearMedia, mine: Boolean, durationSec: Int = 0) {
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
        val ink = if (mine) Color.White else OshiColors.TextPrimary
        if (media == WearMedia.VOICE && durationSec > 0) {
            // A voice note with a known length: mic + "0:14" on one line, so the bubble
            // stays short enough to fit whole above the Reply button on 192 dp at 1.24×.
            // The spoken label keeps the word ("Voice, 0:14").
            val spoken = stringResource(label) + ", " + clock(durationSec)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
            ) {
                MicGlyph(14.dp, ink)
                Spacer(Modifier.width(5.dp))
                Text(clock(durationSec), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
            }
        } else {
            Text(stringResource(label), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ink)
        }
    }
}
