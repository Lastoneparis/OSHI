package com.oshi.messenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.data.model.ContentReport
import com.oshi.messenger.data.model.ReportDeliveryState
import com.oshi.messenger.service.ReportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs [com.oshi.messenger.ui.screens.ReportedItemsScreen]. */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reportManager: ReportManager
) : ViewModel() {

    val reports: StateFlow<List<ContentReport>> = reportManager.reports

    /** iOS retries pending deliveries on `onAppear` (ContactActionsView.swift:726). */
    fun retryPending() {
        viewModelScope.launch { reportManager.retryPending() }
    }

    fun withdraw(reportId: String) = reportManager.withdraw(reportId)

    fun pendingCount(list: List<ContentReport>): Int =
        list.count { it.state == ReportDeliveryState.PENDING }
}
