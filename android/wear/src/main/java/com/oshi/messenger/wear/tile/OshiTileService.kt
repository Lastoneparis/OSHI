package com.oshi.messenger.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.border
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.layoutString
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
import com.oshi.messenger.wear.data.WearInbox
import com.oshi.wearbridge.WearAvatar
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull

/**
 * __WEAR_BRIDGE_2026_11_17__ / __WEAR_POLISH_2026_11_25__ The OSHI tile,
 * Material 3 Expressive ("golden" contacts-style layout):
 *
 *   title    OSHI · 5 unread
 *   main     up to 3 recent chats as round avatar buttons (initials in the
 *            person's own avatar colour; a violet ring = unread) — tap opens
 *            that conversation
 *   label    how the PHONE is connected (internet / nearby / LoRa / offline)
 *   edge     Reply — straight to the reply screen of the newest unread chat
 *
 * Names never appear as text, only as accessibility labels, and never any
 * message text. In the phone's strict mode the avatars are dropped and only
 * the count remains. Asked of the phone on each tile request, kept nowhere.
 */
class OshiTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val source = DebugSources.fake(this@OshiTileService) ?: PhoneWearSource(this@OshiTileService)
            val outcome = try {
                withTimeoutOrNull(6_000) { source.inbox() } ?: Outcome.Fail(Reason.NO_PHONE)
            } finally {
                source.close()
            }
            val layout = TileLayout.layout(this@OshiTileService, requestParams.deviceConfiguration, outcome)
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

    companion object { const val RESOURCES_VERSION = "2" }
}

/** Pure layout, so tests and the service draw the same thing. */
object TileLayout {
    private const val INK = 0xFFB899FF.toInt()
    private const val TEXT = 0xFFF2F2F7.toInt()
    private const val MUTED = 0xFFA1A1AA.toInt()
    private const val VIOLET = 0xFF5616EC.toInt()
    private const val AMBER = 0xFFFFC266.toInt()

    private fun c(argb: Int) = LayoutColor(argb)

    /** The OSHI glass-on-black palette; dynamic colour off so the brand survives any watch face. */
    private val scheme = ColorScheme(
        primary = c(INK),
        onPrimary = c(0xFF1B0B4D.toInt()),
        primaryContainer = c(VIOLET),
        onPrimaryContainer = c(0xFFFFFFFF.toInt()),
        surfaceContainerLow = c(0xFF0E0E10.toInt()),
        surfaceContainer = c(0xFF141414.toInt()),
        surfaceContainerHigh = c(0xFF1C1C1E.toInt()),
        onSurface = c(TEXT),
        onSurfaceVariant = c(MUTED),
        background = c(0xFF000000.toInt()),
        onBackground = c(TEXT),
    )

    /** The conversation the edge button answers: newest unread, else newest repliable. */
    fun replyTarget(convs: List<WearConversation>): WearConversation? =
        convs.filter { it.canReply }.let { r -> r.firstOrNull { it.unread > 0 } ?: r.firstOrNull() }

    /** The chats drawn as avatars: unread first, then recent; 2 on a small round screen, 3 otherwise. */
    fun avatars(convs: List<WearConversation>, screenWidthDp: Int): List<WearConversation> {
        val n = if (screenWidthDp < 210) 2 else 3
        return (convs.filter { it.unread > 0 } + convs.filter { it.unread <= 0 }).take(n)
    }

    /** "Internet · 2 nearby" / "Offline · …", or null when the phone did not say. */
    fun netLine(context: Context, net: WearNet?): String? {
        net ?: return null
        if (net.isOffline) return context.getString(R.string.net_offline_short)
        return buildList {
            if (net.internet) add(context.getString(R.string.net_internet))
            if (net.meshPeers > 0) add(context.getString(R.string.net_nearby, net.meshPeers))
            if (net.lora) add(context.getString(R.string.net_lora))
        }.joinToString(" · ")
    }

    private fun launch(context: Context, conv: WearConversation? = null, reply: Boolean = false): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(context.packageName)
            .setClassName(MainActivity::class.java.name)
        if (conv != null) {
            activity.addKeyToExtraMapping(MainActivity.EXTRA_KIND, ActionBuilders.AndroidStringExtra.Builder().setValue(conv.kind.wire).build())
            activity.addKeyToExtraMapping(MainActivity.EXTRA_ID, ActionBuilders.AndroidStringExtra.Builder().setValue(conv.id).build())
            if (reply) {
                activity.addKeyToExtraMapping(
                    MainActivity.EXTRA_ACTION, ActionBuilders.AndroidStringExtra.Builder().setValue(MainActivity.ACTION_REPLY).build(),
                )
            }
        }
        val id = when {
            conv == null -> "open"
            reply -> "reply:" + conv.kind.wire + conv.id.take(16)
            else -> "open:" + conv.kind.wire + conv.id.take(16)
        }
        return clickable(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build(), id)
    }

    fun layout(context: Context, device: DeviceParameters, outcome: Outcome<WearInbox>): LayoutElement =
        materialScope(context, device, allowDynamicTheme = false, defaultColorScheme = scheme) {
            when (outcome) {
                is Outcome.Ok -> content(context, outcome.value)
                is Outcome.Fail -> status(context, outcome.reason)
            }
        }

    private fun MaterialScope.title(s: String): LayoutElement =
        text(s.layoutString, typography = Typography.TITLE_SMALL, color = c(INK), maxLines = 1)

    private fun MaterialScope.content(ctx: Context, inbox: WearInbox): LayoutElement {
        val convs = inbox.conversations
        val unread = convs.sumOf { it.unread }
        val countText = if (unread > 999) "999+" else unread.toString()
        val target = replyTarget(convs)
        val shown = if (inbox.strict) emptyList() else avatars(convs, deviceConfiguration.screenWidthDp)
        val net = netLine(ctx, inbox.net)
        return primaryLayout(
            titleSlot = {
                title(if (unread > 0) "OSHI · " + ctx.getString(R.string.unread_count, unread) else "OSHI")
            },
            mainSlot = {
                if (shown.isEmpty()) {
                    // Strict mode or no chats: the count, big, and nothing that names anyone.
                    LayoutElementBuilders.Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(text(countText.layoutString, typography = Typography.NUMERAL_MEDIUM,
                            color = c(if (unread > 0) TEXT else MUTED)))
                        .addContent(text(ctx.getString(if (unread > 0) R.string.unread_label else R.string.all_read).layoutString,
                            typography = Typography.LABEL_SMALL, color = c(MUTED)))
                        .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(launch(ctx)).build())
                        .build()
                } else {
                    buttonGroup(width = expand(), height = expand(), spacing = 6f) {
                        shown.forEach { conv -> buttonGroupItem { avatar(ctx, conv) } }
                    }
                }
            },
            bottomSlot = {
                if (target != null) {
                    textEdgeButton(
                        onClick = launch(ctx, target, reply = true),
                        modifier = LayoutModifier.contentDescription(
                            if (inbox.strict) ctx.getString(R.string.reply) else ctx.getString(R.string.tile_reply_to, target.name),
                        ),
                        colors = filledButtonColors(),
                    ) { text(ctx.getString(R.string.reply).layoutString) }
                } else {
                    textEdgeButton(onClick = launch(ctx), colors = filledTonalButtonColors()) {
                        text(ctx.getString(R.string.tile_open).layoutString)
                    }
                }
            },
            labelForBottomSlot = net?.let { line ->
                {
                    text(line.layoutString, typography = Typography.LABEL_SMALL,
                        color = c(if (inbox.net?.isOffline == true) AMBER else MUTED), maxLines = 1)
                }
            },
        )
    }

    /** One chat as a round button in its own avatar colour; a violet ring when it has unread messages. */
    private fun MaterialScope.avatar(ctx: Context, conv: WearConversation): LayoutElement {
        val entry = WearAvatar.entry(conv.avatarKey)
        var mod = LayoutModifier.contentDescription(
            if (conv.unread > 0) ctx.getString(R.string.tile_chat_unread, conv.name, conv.unread) else conv.name,
        )
        if (conv.unread > 0) mod = mod.border(3f, c(INK))
        return textButton(
            onClick = launch(ctx, conv),
            labelContent = {
                text(WearAvatar.initials(conv.name, conv.avatarKey).layoutString,
                    typography = Typography.TITLE_MEDIUM, color = c(entry.ink.toInt()))
            },
            modifier = mod,
            width = expand(),
            height = expand(),
            shape = shapes.full,
            colors = ButtonColors(containerColor = c(entry.start.toInt()), labelColor = c(entry.ink.toInt())),
        )
    }

    private fun MaterialScope.status(ctx: Context, reason: Reason): LayoutElement {
        val msg = when (reason) {
            Reason.DISABLED -> R.string.tile_off
            Reason.LOCKED -> R.string.tile_locked
            Reason.NO_PHONE -> R.string.status_no_phone_title
            Reason.UNAVAILABLE -> R.string.status_unavailable_body
            else -> R.string.status_error_title
        }
        return primaryLayout(
            titleSlot = { title("OSHI") },
            mainSlot = {
                text(ctx.getString(msg).layoutString, typography = Typography.BODY_MEDIUM, color = c(TEXT), maxLines = 3)
            },
            bottomSlot = {
                textEdgeButton(onClick = launch(ctx), colors = filledButtonColors()) {
                    text(ctx.getString(R.string.tile_open).layoutString)
                }
            },
        )
    }
}
