package com.oshi.messenger.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.oshi.messenger.wear.DebugSources
import com.oshi.messenger.wear.MainActivity
import com.oshi.messenger.wear.R
import com.oshi.messenger.wear.data.Outcome
import com.oshi.messenger.wear.data.PhoneWearSource
import com.oshi.messenger.wear.data.WearInbox
import com.oshi.messenger.wear.sync.UnreadCount
import com.oshi.wearbridge.WearConversation
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OSHI on the watch face. Never message text, in any type:
 *
 *  - SHORT_TEXT: the unread count + the OSHI glyph (a number only; from the
 *    phone's published count, no request needed).
 *  - LONG_TEXT (__WEAR_POLISH_2026_11_25__): "3 unread · Léa, Sam" — the
 *    NAMES of who wrote, asked of the phone at that moment and kept nowhere;
 *    counts only when the phone is in strict mode or does not answer.
 *  - MONOCHROMATIC_IMAGE / SMALL_IMAGE: a one-tap OSHI launcher.
 *
 * Tap opens OSHI — straight into the conversation when exactly one has
 * unread messages.
 */
class UnreadComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        ComplicationBuilder.build(this, type, count = 3, names = listOf("Léa", "Sam"), tap = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val type = request.complicationType
        val count = UnreadCount.read(this) ?: 0
        // Only the long slot (and a precise tap target) needs to ask the phone.
        val inbox: WearInbox? = if (type == ComplicationType.LONG_TEXT || count > 0) {
            val source = DebugSources.fake(this) ?: PhoneWearSource(this)
            try {
                (withTimeoutOrNull(4_000) { source.inbox() } as? Outcome.Ok)?.value
            } finally {
                source.close()
            }
        } else null
        val unread = inbox?.conversations?.filter { it.unread > 0 }.orEmpty()
        val names = if (inbox == null || inbox.strict) emptyList() else unread.map { it.name }
        return ComplicationBuilder.build(this, type, count, names, tap(this, unread.singleOrNull()))
    }

    private fun tap(context: Context, only: WearConversation?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (only != null) {
            intent.putExtra(MainActivity.EXTRA_KIND, only.kind.wire).putExtra(MainActivity.EXTRA_ID, only.id)
        }
        return PendingIntent.getActivity(
            context, if (only != null) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Pure-ish builder so tests can check every type without a watch face. */
object ComplicationBuilder {

    /** "3 unread · Léa, Sam" / "3 unread" / "No unread messages". Names only, at most three. */
    fun longText(context: Context, count: Int, names: List<String>): String {
        if (count <= 0) return context.getString(R.string.complication_none)
        val head = context.getString(R.string.unread_count, count)
        val who = names.filter { it.isNotBlank() }.take(3)
        return if (who.isEmpty()) head else head + " · " + who.joinToString(", ")
    }

    fun shortLabel(count: Int): String = if (count > 99) "99+" else count.toString()

    fun build(context: Context, type: ComplicationType, count: Int, names: List<String>, tap: PendingIntent?): ComplicationData? {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_notification)).build()
        val desc = PlainComplicationText.Builder(
            if (count > 0) context.getString(R.string.unread_count, count) else context.getString(R.string.complication_none)
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(shortLabel(count)).build(), desc,
            )
                .setMonochromaticImage(icon)
                .setTitle(PlainComplicationText.Builder("OSHI").build())
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(longText(context, count, names)).build(), desc,
            )
                .setTitle(PlainComplicationText.Builder("OSHI").build())
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(icon, desc)
                .setTapAction(tap)
                .build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(Icon.createWithResource(context, R.mipmap.ic_launcher_round), SmallImageType.ICON).build(),
                desc,
            )
                .setTapAction(tap)
                .build()
            else -> null
        }
    }
}
