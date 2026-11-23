package com.oshi.messenger.ui.screens

import androidx.compose.foundation.layout.*
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.data.model.ContentReport
import com.oshi.messenger.data.model.ReportDeliveryState
import com.oshi.messenger.data.model.ReportReason
import com.oshi.messenger.data.model.ReportSubject
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.viewmodel.ReportsViewModel

/**
 * Settings → Privacy → Reports. Port of iOS `ReportedItemsView`
 * (ContactActionsView.swift:688-760): what the user reported, whether it
 * reached the moderation team, and a way to withdraw an entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportedItemsScreen(
    onBack: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val reports by viewModel.reports.collectAsState()
    var reportToWithdraw by remember { mutableStateOf<ContentReport?>(null) }

    // iOS retries pending deliveries when the view appears; a report filed
    // offline is otherwise stuck in the local queue with nothing to drain it.
    LaunchedEffect(Unit) { viewModel.retryPending() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_reports)) }
            )
        }
    ) { padding ->
        if (reports.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_reports_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.settings_reports_count, reports.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(reports, key = { it.id }) { report ->
                    ReportRow(report = report, onWithdraw = { reportToWithdraw = report })
                }
                if (viewModel.pendingCount(reports) > 0) {
                    item {
                        TextButton(onClick = { viewModel.retryPending() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.report_retry_all))
                        }
                    }
                }
            }
        }
    }

    reportToWithdraw?.let { report ->
        OshiAlertDialog(
            onDismissRequest = { reportToWithdraw = null },
            title = { Text(stringResource(R.string.report_withdraw_title)) },
            text = { Text(stringResource(R.string.report_withdraw_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.withdraw(report.id)
                    reportToWithdraw = null
                }) {
                    Text(stringResource(R.string.report_withdraw_confirm), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { reportToWithdraw = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ReportRow(report: ContentReport, onWithdraw: () -> Unit) {
    OshiCard(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (report.subject == ReportSubject.GROUP) Icons.Default.Group else Icons.Default.Person,
                contentDescription = null,
                tint = OSHIOrange,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = report.subjectName ?: report.subjectId.take(12),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(reasonLabel(report.reason)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(stateLabel(report.state)),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (report.state) {
                    ReportDeliveryState.SENT -> OSHIGreen
                    ReportDeliveryState.REJECTED -> OSHIRed
                    ReportDeliveryState.PENDING -> OSHIOrange
                }
            )
            IconButton(onClick = onWithdraw) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = stringResource(R.string.report_withdraw_confirm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun reasonLabel(reason: ReportReason): Int = when (reason) {
    ReportReason.SPAM -> R.string.block_reason_spam
    ReportReason.HARASSMENT -> R.string.block_reason_harassment
    ReportReason.INAPPROPRIATE -> R.string.block_reason_inappropriate
    ReportReason.OTHER -> R.string.block_reason_other
}

private fun stateLabel(state: ReportDeliveryState): Int = when (state) {
    ReportDeliveryState.SENT -> R.string.report_status_sent
    ReportDeliveryState.REJECTED -> R.string.report_status_rejected
    ReportDeliveryState.PENDING -> R.string.report_status_pending
}
