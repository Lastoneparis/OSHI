package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.components.bubbleClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.util.TickerLinks
import com.oshi.messenger.util.TickerQuote
import com.oshi.messenger.util.TickerQuoteCache
import com.oshi.messenger.util.TickerQuoteSnapshots
import androidx.compose.ui.platform.LocalContext

/**
 * 📈 The tappable `$AAPL` chip under a message. Port of `OSHI/TickerChipView.swift`.
 *
 * The quote loads BY ITSELF when the bubble renders, and a tap opens the chart.
 * That is iOS's deliberate doctrine (`__TICKER_AUTOLOAD_2026_08_26__`) and it has a
 * cost worth naming: Yahoo learns which symbol is discussed and when, with nobody
 * touching anything. What limits it is that only the SYMBOL leaves — no message
 * text, no contact, no key — and that the cache is process-wide for 60 s, so the
 * same ticker in ten bubbles is one request.
 *
 * A chip that cannot price itself says so ("Price unavailable") and stays tappable.
 */
private sealed interface ChipState {
    data object Loading : ChipState
    data class Loaded(val quote: TickerQuote) : ChipState
    data object Failed : ChipState
}

@Composable
fun TickerChip(
    match: TickerLinks.Match,
    modifier: Modifier = Modifier,
    tint: Color = OSHIPurple,
    /**
     * The message this chip belongs to. With it, the price is FROZEN on the message
     * (`TickerQuoteSnapshots`, iOS `__TICKER_FROZEN_2026_09_13__`); without it the
     * chip falls back to the old live-price behaviour, as iOS does.
     */
    messageId: String? = null
) {
    val uriHandler = LocalUriHandler.current
    val snapshots = messageId?.let { TickerQuoteSnapshots.get(LocalContext.current) }

    // A frozen price always beats the loader: it is THE number this message was
    // about, and it must not be replaced by today's on the next render.
    val frozen = remember(messageId, match.symbol) {
        messageId?.let { snapshots?.snapshot(it, match.symbol)?.quote }
    }

    // Seeded from the process cache BEFORE the first frame, at ANY age. Without
    // this, reopening a conversation drew a bare chip for the length of a network
    // round trip — the "it disappears when I come back" that iOS's init exists to
    // prevent (`TickerQuoteFetcher.swift:74-78`).
    var state by remember(match.symbol, frozen) {
        mutableStateOf(
            frozen?.let { ChipState.Loaded(it) }
                ?: TickerQuoteCache.cached(match.symbol)
                    ?.let { ChipState.Loaded(it) } ?: ChipState.Loading
        )
    }

    // Stale-while-revalidate, both halves of it:
    //  • the refresh runs even when a price is already on screen (it used to bail
    //    out here, so a chip seeded from cache never updated again);
    //  • a refresh that FAILS leaves that price alone. Three seconds of dead
    //    network must not wipe a correct number.
    // `TickerQuoteCache.get` already returns fast when the entry is fresh, so this
    // is not an extra request per recomposition.
    //
    // A message that already has a frozen price skips this entirely: no request,
    // so reopening an old conversation no longer tells Yahoo which symbol is read.
    LaunchedEffect(match.symbol, frozen) {
        if (frozen != null) return@LaunchedEffect
        val hadPrice = state is ChipState.Loaded
        val q = TickerQuoteCache.get(match.symbol)
        when {
            q != null -> {
                state = ChipState.Loaded(q)
                // And as soon as a price arrives, engrave it on this message.
                if (messageId != null) snapshots?.capture(messageId, match.symbol, q)
            }
            !hadPrice -> state = ChipState.Failed
        }
    }

    Row(
        modifier = modifier
            .background(tint.copy(alpha = 0.20f), CircleShape)
            .bubbleClickable { runCatching { uriHandler.openUri(match.url) } }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = match.display,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        when (val s = state) {
            is ChipState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Say so, quietly. This used to render nothing at all, which left a
            // lone `$AAPL` that is indistinguishable from a chip still deciding —
            // the reader cannot tell "no price" from "loading". iOS carries a
            // reason precisely because "a silent failure on a tap the user made
            // deliberately reads as the app being broken"
            // (`TickerQuoteFetcher.swift:41-43`, rendered at `TickerChipView.swift:55`).
            is ChipState.Failed -> Text(
                text = stringResource(R.string.ticker_quote_unavailable),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            is ChipState.Loaded -> {
                val q = s.quote
                if (q.series.size > TickerQuote.MIN_SPARKLINE_POINTS) {
                    Sparkline(
                        values = q.series,
                        up = q.isUp,
                        modifier = Modifier.width(46.dp).height(16.dp)
                    )
                }
                Text(
                    text = q.formattedPrice(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = q.formattedChange(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (q.isUp) Color(0xFF1B9E4B) else Color(0xFFD13438),
                    maxLines = 1
                )
            }
        }
    }
}

/** Intraday closes as a single stroked path, normalised to its own range. */
@Composable
private fun Sparkline(values: List<Double>, up: Boolean, modifier: Modifier = Modifier) {
    val stroke = if (up) Color(0xFF1B9E4B) else Color(0xFFD13438)
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val dx = size.width / (values.size - 1).toFloat()
        var prev: Offset? = null
        for (i in values.indices) {
            // y is inverted: a higher price must draw HIGHER on screen.
            val y = size.height - ((values[i] - lo) / span).toFloat() * size.height
            val p = Offset(i * dx, y)
            prev?.let { drawLine(stroke, it, p, strokeWidth = 1.5f) }
            prev = p
        }
    }
}

/**
 * Every ticker in [text], as chips. Draws nothing when there is none, so a caller
 * can use it unconditionally — the cheap `hasTicker` bail runs first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TickerChipsForText(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = OSHIPurple,
    messageId: String? = null,
    /** __MENTIONS_2026_09_23__ ranges owned by a person mention; a ticker overlapping one is dropped. */
    exclude: List<IntRange> = emptyList()
) {
    if (!TickerLinks.hasTicker(text)) return
    val matches = remember(text, exclude) {
        TickerLinks.matches(text).filter { m -> exclude.none { r -> m.start <= r.last && r.first < m.end } }
    }
    if (matches.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Distinct: "@AAPL beats @AAPL" is one chip, not two identical ones.
        matches.distinctBy { it.symbol }.forEach {
            TickerChip(match = it, tint = tint, messageId = messageId)
        }
    }
}
