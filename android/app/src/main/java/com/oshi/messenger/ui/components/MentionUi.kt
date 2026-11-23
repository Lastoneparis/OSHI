package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.util.MentionWire

/**
 * `@name` person mentions — __MENTIONS_2026_09_23__. Wire and trust rules live in
 * [MentionWire] (byte-identical to OSHI-Desktop's `group/MentionWire.kt`); this file only
 * draws them: the highlighted token in a bubble (a tap names the member), the composer
 * highlight, the picker while an `@query` is typed in a group, and the chat-list "@" pill.
 */

private const val TAG_MENTION = "mention"

/** [text] with every admitted mention bold and tinted, annotated with its key. */
fun mentionAnnotated(text: String, spans: List<MentionWire.Span>, tint: Color): AnnotatedString =
    buildAnnotatedString {
        var at = 0
        for (s in spans) {
            if (s.start < at) continue
            append(text.substring(at, s.start))
            pushStringAnnotation(TAG_MENTION, s.publicKey)
            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold, background = tint.copy(alpha = 0.14f))) {
                append(text.substring(s.start, s.endExclusive))
            }
            pop()
            at = s.endExclusive
        }
        append(text.substring(at))
    }

/** The ranges a ticker chip must not claim: a picked person always wins over a symbol. */
fun mentionRanges(text: String, mentions: List<MentionWire.Mention>): List<IntRange> =
    MentionWire.spans(text, mentions).map { it.start until it.endExclusive }

/**
 * A group bubble's body when it carries mentions: tokens highlighted, a tap opens a small
 * dialog naming the member. Callers fall back to their normal text when [mentions] is empty.
 */
@Composable
fun MentionText(text: String, mentions: List<MentionWire.Mention>, color: Color, tint: Color, style: TextStyle) {
    val spans = remember(text, mentions) { MentionWire.spans(text, mentions) }
    var shown by remember { mutableStateOf<MentionWire.Span?>(null) }
    ClickableText(
        text = mentionAnnotated(text, spans, tint),
        style = style.copy(color = color),
        onClick = { offset -> shown = spans.firstOrNull { offset >= it.start && offset < it.endExclusive } },
    )
    shown?.let { s ->
        OshiAlertDialog(
            onDismissRequest = { shown = null },
            confirmButton = { TextButton(onClick = { shown = null }) { Text(androidx.compose.ui.res.stringResource(android.R.string.ok)) } },
            title = { Text("@" + s.name) },
            text = { Text(if (s.publicKey.length > 16) s.publicKey.take(16) + "…" else s.publicKey, fontSize = 12.sp) },
        )
    }
}

/**
 * Members offered while `@query` is typed: case-insensitive prefix match first, then
 * substring, at most 8. Same order as the desktop picker.
 */
fun mentionCandidates(members: List<MentionWire.Mention>, query: String): List<MentionWire.Mention> {
    val q = query.trim().lowercase()
    val usable = members.filter { MentionWire.nameOk(it.name) }
    if (q.isEmpty()) return usable.take(8)
    val prefix = usable.filter { it.name.lowercase().startsWith(q) }
    val inner = usable.filter { it !in prefix && it.name.lowercase().contains(q) }
    return (prefix + inner).take(8)
}

/** Replace the `@query` at [atIndex]..end of [draft] with the picked member's token. */
fun insertMention(draft: String, atIndex: Int, picked: MentionWire.Mention): String =
    draft.substring(0, atIndex) + "@" + picked.name + " "

@Composable
fun MentionPicker(candidates: List<MentionWire.Mention>, onPick: (MentionWire.Mention) -> Unit, modifier: Modifier = Modifier) {
    if (candidates.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        for (c in candidates) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(c) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("@" + c.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(c.publicKey.take(12) + "…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Chat-list pill: an unread message in this group mentions you. */
@Composable
fun MentionBadge(tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(tint, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text("@", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Composer highlight for the tokens of members picked in this draft. */
class MentionHighlight(private val picked: List<MentionWire.Mention>, private val tint: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = MentionWire.spans(text.text, picked)
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            append(text)
            for (s in spans) addStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold), s.start, s.endExclusive)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is MentionHighlight && other.picked == picked && other.tint == tint
    override fun hashCode(): Int = picked.hashCode() * 31 + tint.hashCode()
}
