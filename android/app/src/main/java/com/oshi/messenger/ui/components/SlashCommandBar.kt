package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The caller passes the composer's current text; everything else is
 * [SlashCommandCatalog], which is pure and unit-tested.
 */
@Composable
fun SlashCommandBar(
    text: String,
    modifier: Modifier = Modifier,
    /** __BOT_IN_THE_PIPELINE_2026_11_09__ this conversation's bot commands ([SlashCommandCatalog.botEntries]). */
    botCommands: List<SlashCommandCatalog.Entry> = emptyList(),
    onPick: (String) -> Unit,
) {
    val matches = SlashCommandCatalog.matches(text, botCommands)
    if (matches.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        matches.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(SlashCommandCatalog.completion(entry)) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.command,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        entry.argumentHintRes?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(it),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val description = entry.descriptionText
                        ?: entry.descriptionRes?.let { stringResource(it) }
                        ?: ""
                    // iOS draws the answering bot beside its command; a built-in has none.
                    val line = listOfNotNull(entry.botLabel, description.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                    if (line.isNotEmpty()) {
                        Text(
                            line,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
