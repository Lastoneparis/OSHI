package com.oshi.messenger.ui.components

import android.content.Intent
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.messenger.R

/**
 * The two things people keep asking how to do.
 *
 * __IN_APP_GUIDES_2026_11_02__ Until now the only "help" in the app was a
 * Settings row that opened the website, which is no use to someone standing in
 * front of the screen they are stuck on — and no use at all offline, in an app
 * whose entire argument is that it works when the network does not.
 *
 * Two guides, because two questions actually arrive: "how do I add someone?"
 * and "how do I send bitcoin?". Each is written as steps a person can follow
 * while holding the phone, not as a description of the feature.
 *
 * ⚠️ THE CASHU GUIDE TELLS THE TRUTH ABOUT BEARER RISK, and that is not
 * optional. OSHI never holds anyone's money: it recognises a token, previews
 * the amount, and hands off to a wallet. A token is cash — whoever redeems it
 * first keeps it — and a guide that glossed over that to read more smoothly
 * would be the single most expensive sentence in the app. The wording follows
 * what the chip already says (`cashu_bearer_note`, `cashu_not_verified`)
 * rather than inventing a cheerier version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidesSheet(
    initialGuide: Guide? = null,
    onDismiss: () -> Unit,
) {
    var open by remember { mutableStateOf(initialGuide) }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val g = open) {
                null -> {
                    Text(
                        stringResource(R.string.guides_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    GuideRow(Icons.Default.PersonAdd, R.string.guide_add_contact_title,
                        R.string.guide_add_contact_sub) { open = Guide.ADD_CONTACT }
                    GuideRow(Icons.Default.Bolt, R.string.guide_cashu_title,
                        R.string.guide_cashu_sub) { open = Guide.CASHU }
                }
                else -> GuideBody(g) { open = null }
            }
        }
    }
}

enum class Guide { ADD_CONTACT, CASHU }

@Composable
private fun GuideRow(icon: ImageVector, title: Int, subtitle: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium)
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuideBody(guide: Guide, onBack: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
        }
        Text(
            stringResource(if (guide == Guide.ADD_CONTACT) R.string.guide_add_contact_title else R.string.guide_cashu_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }

    val steps = when (guide) {
        Guide.ADD_CONTACT -> listOf(
            R.string.guide_add_contact_s1, R.string.guide_add_contact_s2,
            R.string.guide_add_contact_s3, R.string.guide_add_contact_s4,
        )
        // __CASHU_GUIDE_SIX_STEPS_2026_11_02__ Four steps assumed the reader
        // already had a funded Cashu wallet, which is precisely what a
        // first-timer does not have — so the guide began at step three of its
        // own story. Two steps added at the front: get a wallet, put a little
        // money in it.
        Guide.CASHU -> listOf(
            R.string.guide_cashu_s1, R.string.guide_cashu_s2,
            R.string.guide_cashu_s3, R.string.guide_cashu_s4,
            R.string.guide_cashu_s5, R.string.guide_cashu_s6,
        )
    }
    steps.forEachIndexed { i, res ->
        Row(verticalAlignment = Alignment.Top) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(stringResource(res), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 3.dp))
        }
    }

    // The warning is part of the guide, not a footnote: it is the one thing a
    // first-time user must read before they move money.
    if (guide == Guide.CASHU) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.guide_cashu_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(14.dp),
            )
        }
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://cashu.space"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text(stringResource(R.string.guide_cashu_learn_more)) }
    }
}
