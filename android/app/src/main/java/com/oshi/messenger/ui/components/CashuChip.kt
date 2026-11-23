package com.oshi.messenger.ui.components

import android.content.Intent
import com.oshi.messenger.ui.theme.OshiCard
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.util.CashuTokens

/**
 * 🪙 The eCash card under a message that carries a Cashu token.
 *
 * __CASHU_2026_10_24__ Scope on purpose, and it is the same scope the feature
 * has elsewhere in this space: **recognise, preview, hand off**. OSHI reads the
 * token locally — amount, unit, mint, memo — and redeeming opens the wallet the
 * user already trusts through the `cashu:` URI that NUT-00 defines. OSHI holds
 * no balance, signs nothing and never touches a mint, so there is no custody
 * here to regulate and nothing of the user's to lose.
 *
 * The bearer warning is not decoration. A Cashu token IS the money: whoever
 * reads the string can spend it, including anyone who later sees this
 * conversation. Saying so under the amount is the honest thing to do, and it is
 * the one piece of the card that must never be removed to save a line.
 */
@Composable
fun CashuTokenCardsForText(
    text: String,
    modifier: Modifier = Modifier,
) {
    // Cheap bail before anything is allocated, exactly like TickerChipsForText.
    if (!text.contains("cashu", ignoreCase = true)) return
    val matches = remember(text) { CashuTokens.find(text) }
    if (matches.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Distinct by the raw string: the same token quoted twice is one card.
        matches.distinctBy { it.token.raw }.forEach { CashuTokenCard(it.token) }
    }
}

@Composable
private fun CashuTokenCard(token: CashuTokens.Token) {
    val context = LocalContext.current
    OshiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "  ${token.totalAmount} ${token.unit ?: "sat"}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
            // The mint is WHO owes the money. A token is only worth what its
            // mint will honour, so it is shown, never hidden behind "eCash".
            Text(
                text = token.mints.joinToString(", ") { it.removePrefix("https://").removePrefix("http://") },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            token.memo?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Text(
                text = stringResource(R.string.cashu_bearer_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            // OSHI performs NO cryptographic validation: it reads the amount the
            // token claims and never asks the mint whether that amount is still
            // there. A card that showed a figure without saying so would be
            // presenting an unredeemable or already-spent token as a payment
            // received, which is the one thing a bearer instrument must never
            // look like before it is redeemed.
            Text(
                text = stringResource(R.string.cashu_not_verified),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { redeemInWallet(context, token) }) {
                    Text(stringResource(R.string.cashu_redeem))
                }
                TextButton(onClick = { copyToken(context, token) }) {
                    Text(stringResource(R.string.cashu_copy_token))
                }
                // Separate, and never automatic. Opening the web page puts the
                // bearer token in a URL on someone else's server: that is the
                // user's decision to take, not a fallback to slide into.
                TextButton(onClick = { redeemOnWeb(context, token) }) {
                    Text(stringResource(R.string.cashu_redeem_web))
                }
            }
        }
    }
}

/**
 * Hand the token to a wallet installed on this device, and nothing else.
 *
 * NO automatic web fallback. If no app claims `cashu:` the user is told so and
 * the token stays where it is — sending a bearer instrument to a third-party
 * site is a decision, and a decision belongs to the person whose money it is.
 */
private fun redeemInWallet(context: android.content.Context, token: CashuTokens.Token) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(token.redeemUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!opened) Toast.makeText(context, R.string.cashu_no_wallet, Toast.LENGTH_LONG).show()
}

/** The token on the clipboard, so it can be pasted into any wallet by hand. */
private fun copyToken(context: android.content.Context, token: CashuTokens.Token) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("cashu", token.raw))
    Toast.makeText(context, R.string.cashu_copied, Toast.LENGTH_SHORT).show()
}

/**
 * The public redemption page, opened only because the user asked for it.
 *
 * The token travels in the URL, so it reaches that site and whatever sits in
 * front of it. OSHI itself still contacts no mint and no server: this is the
 * browser loading a page the user chose to open.
 */
private fun redeemOnWeb(context: android.content.Context, token: CashuTokens.Token) {
    val url = "https://redeem.cashu.me/?token=" + Uri.encode(token.raw)
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * The one-line answer to a `/pay` that did not send.
 *
 * A Toast rather than a system bubble in the thread: a refusal is feedback to
 * the person typing, not a message the other side should ever see — and a
 * failed payment attempt pinned into a conversation would be read as a payment.
 */
fun cashuToast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
