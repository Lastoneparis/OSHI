package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.util.SlashCommandCatalog

/**
 * ⌨️ The list that opens when a message starts with `/`, above the composer.
 *
 * __CASHU_2026_10_25__ Android had no slash menu at all, which is why `/pay`
 * was a command you had to be TOLD about. Port of iOS's `SlashCommandBar`, same
 * behaviour: it appears, it is tapped, it disappears, and it is never drawn
 * empty — a bar with nothing in it, floating over the keyboard, is worse than
 * no bar.
 *
 * __BOT_COMMANDS_2026_11_24__ Telegram-style: a bot's command is SENT on tap
 * ([onSend]); a built-in that takes an argument (`/pay <token>`) is inserted
 * ([onPick]) so the argument can be typed. Each row carries the bot's avatar, the
 * command and its description; the list is capped in height and scrolls, so ten
 * commands never push the composer off screen.
 *
 * The caller passes the composer's current text; everything else is
 * [SlashCommandCatalog], which is pure and unit-tested.
 */
@Composable
fun SlashCommandBar(
    text: String,
    modifier: Modifier = Modifier,
    /** __BOT_IN_THE_PIPELINE_2026_11_09__ this conversation's bot commands ([SlashCommandCatalog.botEntries]). */
    botCommands: List<SlashCommandCatalog.Entry> = emptyList(),
    /** Send a bot command right away; null = insert it like a built-in. */
    onSend: ((SlashCommandCatalog.Entry) -> Unit)? = null,
    /**
     * __BOTS_PHASE1_2026_11_24__ hardware-keyboard state ([rememberSlashPickerState] +
     * [slashPickerKeys]); null = touch only.
     */
    state: SlashPickerState? = null,
    onPick: (String) -> Unit,
) {
    val matches = SlashCommandCatalog.matches(text, botCommands)
    if (matches.isEmpty() || state?.isDismissed(text) == true) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            matches.forEachIndexed { index, entry ->
                val sends = entry.sendsOnPick && onSend != null
                BotCommandRow(
                    entry = entry,
                    onClick = {
                        if (sends) onSend!!(entry)
                        else onPick(SlashCommandCatalog.completion(entry))
                    },
                    sends = sends,
                    // __BOTS_PHASE1_2026_11_24__ contract §4.4: the arrow inserts `/name `
                    // so arguments can be typed, on every row that would otherwise send.
                    onInsert = if (sends) ({ onPick(SlashCommandCatalog.completion(entry)) }) else null,
                    selected = state != null && index == state.selected.coerceIn(0, matches.lastIndex),
                )
            }
        }
    }
}

/**
 * One command: avatar badge · `/command` + description · send glyph when a tap sends.
 * Shared by the `/` bar and the commands sheet so both read the same.
 */
@Composable
fun BotCommandRow(
    entry: SlashCommandCatalog.Entry,
    onClick: () -> Unit,
    sends: Boolean,
    modifier: Modifier = Modifier,
    /** Insert `/name ` instead of sending (the trailing arrow). Null = no arrow. */
    onInsert: (() -> Unit)? = null,
    /** Highlighted by the hardware keyboard. */
    selected: Boolean = false,
) {
    val description = entry.descriptionText?.takeIf { it.isNotBlank() }
        ?: entry.descriptionRes?.let { stringResource(it) }
        ?: ""
    val sendLabel = stringResource(R.string.bot_cmd_send_a11y, entry.command)
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .clickable(role = Role.Button, onClickLabel = if (sends) sendLabel else null, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandBadge(entry.botEmoji)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.command,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    // brandInk, not `primary`: #5616EC on OLED black is under 3:1.
                    color = com.oshi.messenger.ui.theme.brandInk(),
                    maxLines = 1,
                )
                entry.argumentHintRes?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(it),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (description.isNotEmpty()) {
                Text(
                    description,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.botLabel?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onInsert != null) {
            androidx.compose.material3.IconButton(onClick = onInsert, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.NorthWest,
                    contentDescription = stringResource(R.string.bot_cmd_insert_a11y, entry.command),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).graphicsLayer { if (rtl) scaleX = -1f },
                )
            }
        } else if (sends) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Send,
                contentDescription = null,
                tint = com.oshi.messenger.ui.theme.brandInk().copy(alpha = 0.8f),
                modifier = Modifier
                    .size(18.dp)
                    // The glyph points "forward": mirror it in RTL (no AutoMirrored on this BOM).
                    .graphicsLayer { if (rtl) scaleX = -1f },
            )
        }
    }
}

/** The bot's emoji in a soft tinted circle; a terminal glyph for a built-in command. */
@Composable
fun CommandBadge(emoji: String?, size: androidx.compose.ui.unit.Dp = 38.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji != null) {
            Text(emoji, fontSize = (size.value * 0.5f).sp)
        } else {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}


/**
 * __BOTS_PHASE1_2026_11_24__ Highlight + "closed by Esc" for the `/` picker on a hardware
 * keyboard. Esc hides the picker for the CURRENT text only: typing again reopens it.
 */
class SlashPickerState {
    var selected by androidx.compose.runtime.mutableIntStateOf(0)
    private var dismissedText by androidx.compose.runtime.mutableStateOf<String?>(null)

    fun isDismissed(text: String): Boolean = dismissedText == text
    fun dismiss(text: String) { dismissedText = text }
    fun reset() { selected = 0 }
}

@Composable
fun rememberSlashPickerState(): SlashPickerState = androidx.compose.runtime.remember { SlashPickerState() }

/**
 * Put on the composer (or any ancestor of it): routes ↑ ↓ Return Tab → Esc to the picker
 * while it is open, and lets every other key through.
 */
fun Modifier.slashPickerKeys(
    text: String,
    botCommands: List<SlashCommandCatalog.Entry>,
    state: SlashPickerState,
    onSend: ((SlashCommandCatalog.Entry) -> Unit)?,
    onInsert: (String) -> Unit,
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (state.isDismissed(text)) return@onPreviewKeyEvent false
    val matches = SlashCommandCatalog.matches(text, botCommands)
    val key = when (event.key) {
        androidx.compose.ui.input.key.Key.DirectionUp -> SlashCommandCatalog.Key.UP
        androidx.compose.ui.input.key.Key.DirectionDown -> SlashCommandCatalog.Key.DOWN
        androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter -> SlashCommandCatalog.Key.ENTER
        androidx.compose.ui.input.key.Key.Tab -> SlashCommandCatalog.Key.TAB
        androidx.compose.ui.input.key.Key.DirectionRight -> SlashCommandCatalog.Key.RIGHT
        androidx.compose.ui.input.key.Key.Escape -> SlashCommandCatalog.Key.ESCAPE
        else -> SlashCommandCatalog.Key.OTHER
    }
    when (val a = SlashCommandCatalog.onKey(key, matches, state.selected)) {
        is SlashCommandCatalog.KeyAction.Move -> { state.selected = a.index; true }
        is SlashCommandCatalog.KeyAction.Pick -> {
            if (a.entry.sendsOnPick && onSend != null) onSend(a.entry)
            else onInsert(SlashCommandCatalog.completion(a.entry))
            state.reset(); true
        }
        is SlashCommandCatalog.KeyAction.Insert -> { onInsert(SlashCommandCatalog.completion(a.entry)); state.reset(); true }
        SlashCommandCatalog.KeyAction.Close -> { state.dismiss(text); true }
        SlashCommandCatalog.KeyAction.None -> false
    }
}
