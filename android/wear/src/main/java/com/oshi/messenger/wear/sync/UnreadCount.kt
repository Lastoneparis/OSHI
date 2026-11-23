package com.oshi.messenger.wear.sync

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.oshi.messenger.wear.DebugSources
import com.oshi.messenger.wear.data.Outcome
import com.oshi.wearbridge.WearProtocol
import kotlinx.coroutines.tasks.await

/**
 * The unread count the phone publishes (one integer; the phone deletes it
 * when Smartwatch is turned off). Null = nothing published.
 */
object UnreadCount {
    suspend fun read(context: Context): Int? {
        DebugSources.fake(context)?.let { fake ->
            return (fake.conversations() as? Outcome.Ok)?.value?.sumOf { it.unread }
        }
        return runCatching {
            val uri = Uri.Builder().scheme("wear").path(WearProtocol.PATH_UNREAD).build()
            val items = Wearable.getDataClient(context).getDataItems(uri).await()
            try {
                items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getInt(WearProtocol.KEY_UNREAD, 0) }
            } finally {
                items.release()
            }
        }.getOrNull()
    }
}
