package com.oshi.messenger.wear.ui

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.oshi.messenger.wear.R
import com.oshi.messenger.wear.data.Load
import com.oshi.messenger.wear.data.Reason
import com.oshi.messenger.wear.data.SendState
import com.oshi.messenger.wear.data.WearSession
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearProtocol
import kotlinx.coroutines.delay

private const val REMOTE_INPUT_KEY = "oshi_reply"

/**
 * Answer one conversation: dictate or type (the system RemoteInput screen:
 * voice, keyboard, emoji), a quick reaction on [messageId] (the OSHI O and
 * the six O-bubble mascots — the exact wire emoji the phone's quick bar
 * sends), or one of the canned replies. The phone does the encrypting and
 * sending; this screen only says what to send.
 */
@Composable
fun ReplyScreen(session: WearSession, kind: WearConvKind, id: String, messageId: String?, onDone: () -> Unit) {
    val sendState by session.send.collectAsState()
    val threads by session.threads.collectAsState()
    val target = (threads[session.key(kind, id)] as? Load.Ready)?.value?.messages?.firstOrNull { it.id == messageId }
    val title = session.conversation(kind, id)?.name
        ?: (threads[session.key(kind, id)] as? Load.Ready)?.value?.title.orEmpty()

    DisposableEffect(Unit) { onDispose { session.resetSend() } }
    LaunchedEffect(sendState) {
        if (sendState is SendState.Sent) { delay(700); onDone() }
    }

    val label = stringResource(R.string.reply_voice)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.awaitingInput = false
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
        if (!text.isNullOrBlank()) session.sendText(kind, id, text.take(WearProtocol.MAX_SEND_CHARS))
    }
    val openInput = {
        val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(label)
            .wearableExtender {
                setEmojisAllowed(true)
                setInputActionType(EditorInfo.IME_ACTION_SEND)
            }
            .build()
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        session.awaitingInput = true
        launcher.launch(intent)
    }

    when (val s = sendState) {
        is SendState.Sending -> { Loading(); return }
        is SendState.Sent -> { Confirmation(stringResource(R.string.sent)); return }
        is SendState.Failed -> if (s.reason != Reason.ERROR && s.reason != Reason.NO_PHONE) {
            StatusPanel(s.reason) { session.resetSend() }; return
        }
        else -> Unit
    }

    val quick = listOf(
        R.string.qr_ok, R.string.qr_on_my_way, R.string.qr_thanks, R.string.qr_yes,
        R.string.qr_no, R.string.qr_call_later, R.string.qr_busy,
    ).map { stringResource(it) }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader {
                    Text(
                        title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = OshiColors.TextPrimary,
                    )
                }
            }
            if (sendState is SendState.Failed) {
                item {
                    Text(
                        stringResource(R.string.send_failed), color = OshiColors.Error, fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Button(
                    onClick = openInput,
                    modifier = Modifier.fillMaxWidth().glassGlow(RoundedCornerShape(26.dp), spread = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OshiColors.Purple, contentColor = Color.White),
                    icon = { Text("🎤", fontSize = 16.sp) },
                ) { Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
            if (messageId != null) {
                item {
                    val preview = target?.let { it.text.ifBlank { null } } ?: ""
                    Text(
                        if (preview.isNotEmpty()) "${stringResource(R.string.react_title)} · $preview" else stringResource(R.string.react_title),
                        fontSize = 11.sp, color = OshiColors.TextSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                    )
                }
                // 7 reactions on a round screen: 4 then 3, never clipped by the bezel.
                item { ReactionRow(WearProtocol.QUICK_REACTIONS.take(4), target?.myReaction) { session.react(kind, id, messageId, it) } }
                item { ReactionRow(WearProtocol.QUICK_REACTIONS.drop(4), target?.myReaction) { session.react(kind, id, messageId, it) } }
            }
            item {
                Text(
                    stringResource(R.string.quick_replies), fontSize = 11.sp, color = OshiColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(quick) { q ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(24.dp))
                        .clickable { session.sendText(kind, id, q) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(q, fontSize = 14.sp, color = OshiColors.TextPrimary, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ReactionRow(codes: List<String>, mine: String?, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        codes.forEach { code ->
            val selected = mine == code
            Box(
                modifier = Modifier
                    .then(if (selected) Modifier.glassGlow(CircleShape, spread = 5.dp) else Modifier)
                    .size(40.dp)
                    .glass(CircleShape, if (selected) Color(0xFF2A1F4D) else OshiColors.Bubble)
                    .clickable { onPick(code) },
                contentAlignment = Alignment.Center,
            ) { ReactionGlyph(code, 28.dp) }
        }
    }
}

@Composable
private fun Confirmation(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(72.dp).glassGlow(CircleShape, spread = 10.dp)
                .glass(CircleShape, OshiColors.Purple),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.54f)
                    lineTo(size.width * 0.40f, size.height * 0.80f)
                    lineTo(size.width * 0.90f, size.height * 0.24f)
                }
                drawPath(
                    path, Color.White,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = size.width * 0.13f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }
        Text(
            text, fontSize = 14.sp, color = OshiColors.TextPrimary,
            modifier = Modifier.align(Alignment.Center).padding(top = 110.dp),
        )
    }
}
