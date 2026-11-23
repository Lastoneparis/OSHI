package com.oshi.messenger.service.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.oshi.messenger.ui.viewmodel.SettingsViewModel
import com.oshi.messenger.ui.viewmodel.dataStore
import com.oshi.wearbridge.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * __WEAR_BRIDGE_2026_11_17__ Pushes the unread COUNT (one integer, nothing
 * else) to the watch's Tile and complication through a DataClient item.
 *
 * Inert while Settings → Smartwatch is off: it only observes the switch.
 * The repository is resolved, and the Wearable API touched, only once the
 * switch is on. Turning it off deletes the item, so the watch's complication
 * stops showing a number.
 */
object WearUnreadPublisher {

    private const val TAG = "WearUnread"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            var published = false
            app.dataStore.data
                .map { it[SettingsViewModel.KEY_SMARTWATCH_ENABLED] ?: false }
                .distinctUntilChanged()
                .flatMapLatest { on ->
                    if (!on) flowOf(-1) else {
                        val deps = dagger.hilt.android.EntryPointAccessors
                            .fromApplication(app, PhoneWearBackend.Deps::class.java)
                        combine(
                            deps.messageRepository().getTotalUnreadCount(),
                            deps.groupManager().groupConversations.map { list -> list.sumOf { it.unreadCount } },
                        ) { dm, grp -> (dm + grp).coerceAtLeast(0) }
                            .debounce(750)
                    }
                }
                .distinctUntilChanged()
                .collect { count ->
                    runCatching {
                        val client = Wearable.getDataClient(app)
                        if (count < 0) {
                            if (published) {
                                client.deleteDataItems(
                                    android.net.Uri.Builder().scheme("wear").path(WearProtocol.PATH_UNREAD).build()
                                )
                                published = false
                            }
                        } else {
                            val req = PutDataMapRequest.create(WearProtocol.PATH_UNREAD).apply {
                                dataMap.putInt(WearProtocol.KEY_UNREAD, count)
                            }.asPutDataRequest().setUrgent()
                            client.putDataItem(req)
                            published = true
                        }
                    }.onFailure { Log.w(TAG, "unread publish failed: ${it.message}") }
                }
        }
    }
}
