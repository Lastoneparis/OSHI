package com.oshi.messenger.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.oshi.messenger.wear.DebugSources
import com.oshi.messenger.wear.MainActivity
import com.oshi.messenger.wear.R
import com.oshi.messenger.wear.data.Outcome
import com.oshi.messenger.wear.data.PhoneWearSource
import com.oshi.messenger.wear.data.Reason
import com.oshi.wearbridge.WearConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull

/**
 * __WEAR_BRIDGE_2026_11_17__ The OSHI tile: unread count and the three most
 * recent conversations (names and unread count only — never message text).
 * Asked of the phone on each tile request, kept nowhere by this app; tapping
 * a row opens that conversation, tapping elsewhere opens the app.
 */
class OshiTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val source = DebugSources.fake(this@OshiTileService) ?: PhoneWearSource(this@OshiTileService)
            val outcome = try {
                withTimeoutOrNull(6_000) { source.conversations() } ?: Outcome.Fail(Reason.NO_PHONE)
            } finally {
                source.close()
            }
            val layout = when (outcome) {
                is Outcome.Ok -> TileLayout.content(this@OshiTileService, outcome.value)
                is Outcome.Fail -> TileLayout.status(this@OshiTileService, outcome.reason)
            }
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(15 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object { const val RESOURCES_VERSION = "1" }
}

/** Pure layout, so the screenshot harness and the service draw the same thing. */
object TileLayout {
    private const val INK = 0xFFB899FF.toInt()
    private const val TEXT = 0xFFF2F2F7.toInt()
    private const val MUTED = 0xFFA1A1AA.toInt()
    private const val CARD = 0xFF141414.toInt()
    private const val RIM = 0x2EFFFFFF
    private const val VIOLET = 0xFF5616EC.toInt()

    private fun launch(context: Context, conv: WearConversation? = null): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(context.packageName)
            .setClassName(MainActivity::class.java.name)
        if (conv != null) {
            activity.addKeyToExtraMapping(MainActivity.EXTRA_KIND, ActionBuilders.AndroidStringExtra.Builder().setValue(conv.kind.wire).build())
            activity.addKeyToExtraMapping(MainActivity.EXTRA_ID, ActionBuilders.AndroidStringExtra.Builder().setValue(conv.id).build())
        }
        return ModifiersBuilders.Clickable.Builder()
            .setId(conv?.let { "open:" + it.kind.wire + it.id.take(16) } ?: "open")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 1): Text =
        Text.Builder().setText(s).setMaxLines(maxLines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            ).build()

    private fun spacer(h: Float) = Spacer.Builder().setHeight(dp(h)).build()

    private fun row(context: Context, c: WearConversation): LayoutElement {
        val unread = c.unread > 0
        val name = Box.Builder().setWidth(dp(if (unread) 104f else 126f)).setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(text(c.name, 13f, if (unread) TEXT else MUTED, bold = unread))
            .build()
        val line = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(name)
        if (unread) {
            line.addContent(Spacer.Builder().setWidth(dp(4f)).build())
            line.addContent(
                Box.Builder().setWidth(dp(if (c.unread > 9) 26f else 20f)).setHeight(dp(20f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder().setBackground(
                            ModifiersBuilders.Background.Builder().setColor(argb(VIOLET))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(10f)).build()).build()
                        ).build()
                    )
                    .addContent(text(if (c.unread > 99) "99+" else c.unread.toString(), 11f, 0xFFFFFFFF.toInt(), bold = true))
                    .build()
            )
        }
        return Box.Builder()
            .setWidth(dp(150f)).setHeight(dp(30f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(launch(context, c))
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(CARD))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(15f)).build()).build()
                    )
                    .setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(1f)).setColor(argb(RIM)).build())
                    .build()
            )
            .addContent(line.build())
            .build()
    }

    private fun frame(context: Context, vararg children: LayoutElement): LayoutElement {
        val col = Column.Builder().setWidth(wrap()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        children.forEach { col.addContent(it) }
        return Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch(context)).build())
            .addContent(col.build())
            .build()
    }

    fun content(context: Context, convs: List<WearConversation>): LayoutElement {
        val unread = convs.sumOf { it.unread }
        val top = convs.take(3)
        val parts = mutableListOf<LayoutElement>(
            text("OSHI", 13f, INK, bold = true),
            spacer(2f),
            text(if (unread > 999) "999+" else unread.toString(), 34f, if (unread > 0) TEXT else MUTED, bold = true),
            text(context.getString(if (unread > 0) R.string.unread_label else R.string.all_read), 11f, MUTED),
            spacer(6f),
        )
        top.forEachIndexed { i, c ->
            if (i > 0) parts += spacer(4f)
            parts += row(context, c)
        }
        if (top.isEmpty()) parts += text(context.getString(R.string.tile_open), 13f, INK)
        return frame(context, *parts.toTypedArray())
    }

    fun status(context: Context, reason: Reason): LayoutElement {
        val msg = when (reason) {
            Reason.DISABLED -> R.string.tile_off
            Reason.LOCKED -> R.string.tile_locked
            Reason.NO_PHONE -> R.string.status_no_phone_title
            Reason.UNAVAILABLE -> R.string.status_unavailable_body
            else -> R.string.status_error_title
        }
        return frame(
            context,
            text("OSHI", 13f, INK, bold = true),
            spacer(8f),
            Box.Builder().setWidth(dp(150f)).setHeight(wrap())
                .addContent(text(context.getString(msg), 13f, TEXT, maxLines = 3)).build(),
            spacer(10f),
            Box.Builder().setWidth(dp(110f)).setHeight(dp(34f))
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(launch(context))
                        .setBackground(
                            ModifiersBuilders.Background.Builder().setColor(argb(VIOLET))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(17f)).build()).build()
                        ).build()
                )
                .addContent(text(context.getString(R.string.tile_open), 13f, 0xFFFFFFFF.toInt(), bold = true))
                .build(),
        )
    }
}
