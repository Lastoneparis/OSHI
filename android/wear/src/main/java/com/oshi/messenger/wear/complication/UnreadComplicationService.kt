package com.oshi.messenger.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.oshi.messenger.wear.MainActivity
import com.oshi.messenger.wear.R
import com.oshi.messenger.wear.sync.UnreadCount

/** Unread count on the watch face (a number only). Tap opens OSHI. */
class UnreadComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build(3, null) else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val count = UnreadCount.read(this) ?: 0
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return build(count, tap)
    }

    private fun build(count: Int, tap: PendingIntent?): ComplicationData {
        val label = if (count > 99) "99+" else count.toString()
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(label).build(),
            PlainComplicationText.Builder(getString(R.string.complication_label)).build(),
        )
            .setMonochromaticImage(
                MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_notification)).build()
            )
            .setTitle(PlainComplicationText.Builder("OSHI").build())
            .setTapAction(tap)
            .build()
    }
}
