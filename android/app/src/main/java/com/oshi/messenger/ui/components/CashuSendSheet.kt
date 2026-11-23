package com.oshi.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.util.CashuPayCommand
import com.oshi.messenger.util.CashuTokens

/**
 * 🪙 "Send bitcoin" from the + menu: paste a Cashu token, see what it is, then
 * send it.
 *
 * __CASHU_SEND_READY_2026_11_09__ Kotlin half of `OSHI/CashuSendSheet.swift`.
 * Two reasons it exists:
 *
 *  1. `/pay` was the only door, and a payment you have to be told how to type is
 *     a payment almost nobody makes.
 *  2. Nothing showed the amount BEFORE a token left the device, although the
 *     in-app guide promised exactly that. A bearer token cannot be recalled, so
 *     the amount and the mint are read back here first.
 *
 * Same scope as the card: OSHI reads the token locally and hands it to the
 * conversation. No mint is contacted, nothing is held, nothing is signed, and
 * the parse is `/pay`'s own ([CashuPayCommand.parsePasted]), so this sheet can
 * never send a string the command would refuse.
 *
 * @param initialText prefilled from `/pay <token>`; empty from the + menu, in
 *        which case a token already on the clipboard is picked up.
 * @param isGroup the sheet then says everyone in the thread can redeem it, and
 *        pressing Send IS the group confirmation.
 * @param onSend sends the bare token and calls back with null once it went out,
 *        or the string resource of the refusal to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashuSendSheet(
    initialText: String,
    isGroup: Boolean,
    onSend: (raw: String, onDone: (Int?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialText) }
    var refusal by remember { mutableStateOf<Int?>(null) }
    var sending by remember { mutableStateOf(false) }
    val outcome = remember(text) { CashuPayCommand.parsePasted(text) }
    val token = (outcome as? CashuPayCommand.Outcome.Send)?.token

    fun clipboardText(): String? {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    // Opened empty: a token copied from the wallet a moment ago is the
    // overwhelmingly likely thing to send, so it is offered straight away.
    // Only a TOKEN is taken — any other clipboard text is left alone.
    LaunchedEffect(Unit) {
        if (text.isBlank()) {
            clipboardText()?.let { CashuPayCommand.firstToken(it) }?.let { text = it }
        }
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.cashu_send_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.guide_cashu_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = {
                clipboardText()?.let { pasted ->
                    text = CashuPayCommand.firstToken(pasted) ?: pasted
                    refusal = null
                }
            }) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cashu_send_paste))
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it; refusal = null },
                placeholder = { Text(stringResource(R.string.cashu_send_field_hint)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                ),
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            when (outcome) {
                is CashuPayCommand.Outcome.Send -> CashuSendPreview(outcome.token, outcome.summary, isGroup)
                CashuPayCommand.Outcome.NotAToken -> ErrorLine(stringResource(R.string.cashu_pay_not_token))
                CashuPayCommand.Outcome.Invalid -> ErrorLine(stringResource(R.string.cashu_pay_invalid))
                else -> Unit
            }
            refusal?.let { ErrorLine(stringResource(it)) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = token != null && !sending,
                    onClick = {
                        val raw = token?.raw ?: return@Button
                        sending = true
                        onSend(raw) { error ->
                            sending = false
                            if (error == null) onDismiss() else refusal = error
                        }
                    },
                ) { Text(stringResource(R.string.cashu_group_confirm_send)) }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

/** Everything the receiver's card will say, shown to the sender first. */
@Composable
private fun CashuSendPreview(token: CashuTokens.Token, summary: String, isGroup: Boolean) {
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("  $summary", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text(
                token.mints.joinToString(", ") { it.removePrefix("https://").removePrefix("http://") },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            token.memo?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (isGroup) {
                Text(
                    stringResource(R.string.cashu_group_confirm_body, summary),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                stringResource(R.string.cashu_bearer_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            // OSHI never asks the mint: the figure is what the token CLAIMS.
            Text(
                stringResource(R.string.cashu_not_verified),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ErrorLine(line: String) {
    Text(line, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
}
