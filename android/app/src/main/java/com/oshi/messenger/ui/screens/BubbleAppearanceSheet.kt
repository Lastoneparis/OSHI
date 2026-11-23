package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.BubbleAccent
import com.oshi.messenger.ui.theme.BubbleAppearance
import com.oshi.messenger.ui.theme.BubbleAppearancePrefs
import com.oshi.messenger.ui.theme.BubbleSilhouette
import com.oshi.messenger.ui.theme.bubbleSurface

/**
 * Picker for the three bubble preferences F-13 adds: accent, playful silhouette
 * and bold message text.
 *
 * WHERE THIS LIVES. iOS puts it in Settings (`BubbleStyleSelector`,
 * SettingsView). On Android the Settings screen reads its preferences through
 * `SettingsViewModel`, which is owned by another team this pass may not touch,
 * so the sheet is reachable from the chat overflow menu beside "Wallpaper" -
 * which is where this app already keeps its other per-conversation appearance
 * control. The sheet is self-contained: hand it to `SettingsScreen` with a
 * single call and it moves, no other change.
 *
 * Every swatch shows the localized NAME beside the sample, never the emoji
 * alone - the emoji is a recognition aid, not a label (BubbleFunShapes.swift:78-89).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleAppearanceSheet(
    onDismiss: () -> Unit,
    onChanged: (BubbleAppearance) -> Unit
) {
    val context = LocalContext.current
    var accent by remember { mutableStateOf(BubbleAppearancePrefs.accent(context)) }
    var silhouette by remember { mutableStateOf(BubbleAppearancePrefs.silhouette(context)) }
    var boldText by remember { mutableStateOf(BubbleAppearancePrefs.boldText(context)) }

    fun publish() = onChanged(BubbleAppearance(accent, silhouette, boldText))

    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        // ~336 dp of fixed content plus a live preview bubble, in a sheet that
        // gets far less than the window's height (top inset + drag handle). It
        // did not scroll and had no bottom inset, so the preview — the one thing
        // that tells you what you just chose — was the first casualty on a short
        // window.
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {

            Text(
                text = stringResource(R.string.bubble_accent_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(BubbleAccent.entries) { option ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(option.brush)
                                .then(
                                    if (option == accent)
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape
                                        )
                                    else Modifier
                                )
                                .clickable {
                                    accent = option
                                    BubbleAppearancePrefs.setAccent(context, option)
                                    publish()
                                }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = stringResource(option.labelRes), fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.bubble_silhouette_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // "off" first, so the ordinary bubble is one tap away again.
                item {
                    SilhouetteChip(
                        label = stringResource(R.string.bubble_shape_none),
                        sample = "💬",
                        selected = silhouette == null,
                        onClick = {
                            silhouette = null
                            BubbleAppearancePrefs.setSilhouette(context, null)
                            publish()
                        }
                    )
                }
                items(BubbleSilhouette.entries) { option ->
                    SilhouetteChip(
                        label = stringResource(option.labelRes),
                        sample = option.sample,
                        selected = silhouette == option,
                        onClick = {
                            silhouette = option
                            BubbleAppearancePrefs.setSilhouette(context, option)
                            publish()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.bubble_bold_text),
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = boldText,
                    onCheckedChange = {
                        boldText = it
                        BubbleAppearancePrefs.setBoldText(context, it)
                        publish()
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Live preview, drawn by the very code the thread uses - so a
            // silhouette that clips its text here clips it there too.
            Box(
                modifier = Modifier
                    .bubbleSurface(
                        silhouette = silhouette,
                        brush = accent.brush,
                        fallbackShape = RoundedCornerShape(18.dp),
                        rim = accent.onAccent
                    )
            ) {
                Text(
                    text = stringResource(R.string.chat_empty_title),
                    color = accent.onAccent,
                    fontSize = 15.sp,
                    fontWeight = if (boldText) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun SilhouetteChip(
    label: String,
    sample: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = sample, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 13.sp)
    }
}
