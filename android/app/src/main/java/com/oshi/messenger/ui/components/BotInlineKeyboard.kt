package com.oshi.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.data.model.BotInlineButton
import com.oshi.messenger.data.model.BotReplyMarkup

/**
 * A bot message's inline keyboard, drawn under its bubble.
 *
 * Visual language is the group thread's existing BOT language and nothing new: the same
 * deep indigo the bot bubble uses (`0xFF1A1A2E`), the same chip fill (`0xFF2A2A4A`) and
 * the same periwinkle ink (`0xFF9BA4E8`) as the "🤖 name" badge a few dp below, so a
 * keyboard reads as part of the bot's message rather than as a foreign control strip.
 *
 * Rendering is total by construction: [BotReplyMarkup] only ever holds buttons that
 * already satisfy the contract, the rows are already clamped, and a row is laid out with
 * `weight(1f)` per button so eight of them share the width instead of overflowing it.
 * There is no path here that can throw on a malformed keyboard, because a malformed
 * keyboard never becomes a `BotReplyMarkup`.
 */
@Composable
fun BotInlineKeyboard(
    markup: BotReplyMarkup,
    /** Action key ([BotInlineButton.actionKey]) of the button currently in flight, if any. */
    inFlightKey: String?,
    /** False while any button of this keyboard is working, or when the bot is unreachable. */
    enabled: Boolean,
    onCallback: (BotInlineButton) -> Unit,
    onUrl: (BotInlineButton) -> Unit,
    onWebApp: (BotInlineButton) -> Unit,
    modifier: Modifier = Modifier
) {
    if (markup.isEmpty) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        markup.rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { button ->
                    BotInlineButtonCell(
                        button = button,
                        isLoading = inFlightKey == button.actionKey,
                        enabled = enabled,
                        onTap = {
                            when (button.kind) {
                                BotInlineButton.Kind.CALLBACK -> onCallback(button)
                                BotInlineButton.Kind.URL -> onUrl(button)
                                BotInlineButton.Kind.WEB_APP -> onWebApp(button)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BotInlineButtonCell(
    button: BotInlineButton,
    isLoading: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A disabled button must LOOK disabled — a live-looking control that eats taps is
    // worse than a greyed one, because the user's model becomes "the bot is broken".
    val alpha = if (enabled || isLoading) 1f else 0.45f
    val ink = BotKeyboardInk.copy(alpha = alpha)

    Surface(
        color = BotKeyboardFill.copy(alpha = if (enabled || isLoading) 1f else 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .heightIn(min = 36.dp)
            // `clickable` is attached only when the button can actually act, so a
            // disabled one takes no ripple and no tap at all.
            .then(if (enabled && !isLoading) Modifier.clickable(onClick = onTap) else Modifier)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = BotKeyboardInk,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                } else if (button.kind != BotInlineButton.Kind.CALLBACK) {
                    // The two buttons that LEAVE the thread say so before they are
                    // tapped: one opens a browser, the other a mini-app.
                    Icon(
                        imageVector = if (button.kind == BotInlineButton.Kind.URL)
                            Icons.Default.OpenInNew else Icons.Default.Widgets,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(12.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = button.text,
                    color = ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Chip fill of the bot badge already drawn under a bot bubble in the group thread. */
private val BotKeyboardFill = Color(0xFF2A2A4A)

/** Periwinkle ink of that same badge. */
private val BotKeyboardInk = Color(0xFF9BA4E8)
