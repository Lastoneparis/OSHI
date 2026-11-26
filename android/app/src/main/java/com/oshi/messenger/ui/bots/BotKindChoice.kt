@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.oshi.messenger.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiModalBottomSheet

/**
 * __BOTS_PHASE1_2026_11_24__ The first question of "Create a bot": WHERE does it run?
 *
 * - On this phone: the keyword/command trigger bots, which answer with no server at all
 *   — over mesh and LoRa too. One of OSHI's strengths; BotFather must not hide it.
 * - On a server: BotFather, the token and the full Bot API, for developers.
 */
enum class BotHosting { LOCAL, SERVER }

object BotCreateEntry {
    /** The navigation route each choice opens (both reachable; tested). */
    fun route(choice: BotHosting): String = when (choice) {
        BotHosting.LOCAL -> "botPortal?createLocal=true"
        BotHosting.SERVER -> "botFather"
    }
}

@Composable
fun BotKindChoiceContent(onChoose: (BotHosting) -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.botf_choice_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            ChoiceCard(Icons.Default.PhoneAndroid, stringResource(R.string.botf_choice_local_title),
                stringResource(R.string.botf_choice_local_help)) { onChoose(BotHosting.LOCAL) }
            ChoiceCard(Icons.Default.Cloud, stringResource(R.string.botf_choice_server_title),
                stringResource(R.string.botf_choice_server_help)) { onChoose(BotHosting.SERVER) }
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, help: String, onClick: () -> Unit) {
    OshiCard(onClick = onClick, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(help, fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BotKindChoiceSheet(onDismiss: () -> Unit, onChoose: (BotHosting) -> Unit) {
    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            BotKindChoiceContent { choice -> onDismiss(); onChoose(choice) }
        }
    }
}
