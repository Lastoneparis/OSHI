package com.oshi.messenger.wear.sync

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.oshi.messenger.wear.complication.UnreadComplicationService
import com.oshi.messenger.wear.tile.OshiTileService
import com.oshi.wearbridge.WearProtocol

/** The phone's unread count changed: redraw the tile and the complication. */
class WearDataListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val relevant = events.any { it.dataItem.uri.path == WearProtocol.PATH_UNREAD }
        events.release()
        if (!relevant) return
        TileService.getUpdater(this).requestUpdate(OshiTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, UnreadComplicationService::class.java))
            .requestUpdateAll()
    }
}
