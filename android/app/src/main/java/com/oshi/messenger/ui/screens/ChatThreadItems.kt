package com.oshi.messenger.ui.screens

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.OSHIControlPayload

/**
 * One row of the 1:1 thread, in the order the `LazyColumn` emits it.
 *
 * 🔧 P0 FIX (F-1). Three places used to jump with
 * `messages.indexOfFirst { it.id == target }` — the reply quote, the pinned
 * banner and the search ▼ — and fed that number to `animateScrollToItem`. That
 * index space is wrong twice over: the list is built from `messages` MINUS every
 * silent control payload, and it interleaves an extra date-header item after
 * every day boundary. So the rendered index of a message is
 * `filteredIndex + headersAlreadyEmitted`, and every jump landed short by the
 * number of hidden rows above it — the further up the thread, the bigger the
 * miss.
 *
 * iOS does not have this class of bug because it never computes an index at
 * all: it scrolls by id through `ScrollViewReader` (`ChatView.swift:1330`,
 * `:4165-4170`). Compose's `LazyListState` only takes an index, so the
 * equivalent is to make ONE list the source of both the rendering and the
 * lookup — which is what this file is. [build] is the only thing that decides
 * what rows exist; [positionOf] reads that same list back. A row cannot be
 * rendered at one index and looked up at another because there is no second
 * list to drift from.
 *
 * Pure and `Message`-only, so the mapping is unit-testable off-device.
 */
sealed interface ChatThreadItem {
    /** The `LazyColumn` item key. Must match what `ChatScreen` passes to `item(key = …)`. */
    val key: String

    /**
     * @param isTail this message is the LAST of a run from the same sender, so
     *   it is the one that gets the tailed corner. iOS: `BubbleStyleManager`'s
     *   `outline(outgoing:tail:)` — "only the LAST bubble of a run is anchored,
     *   so a burst of five messages reads as one column that touches down once
     *   instead of five identical lozenges" (`:296-304`). Computed here rather
     *   than at the bubble, because the bubble cannot see its neighbours and
     *   this list already knows the order and the day boundaries.
     */
    data class Bubble(val message: Message, val isTail: Boolean = true) : ChatThreadItem {
        override val key: String get() = message.id
    }

    data class DateHeader(val anchorId: String, val timestamp: Long) : ChatThreadItem {
        override val key: String get() = DATE_HEADER_PREFIX + anchorId
    }

    /**
     * (N17) "Pouvoir avoir des piles de photos lorsque plusieurs sont envoyées
     * en même temps pour ne pas encombrer la discussion." A run of 2+
     * consecutive, caption-less photos from the same sender, close enough in
     * time to read as "sent together" (see [ChatThreadItems.PHOTO_STACK_WINDOW_MS]).
     * [messages] is kept OLDEST-first regardless of the thread's own layout
     * direction, so a stack always reads left-to-right the way it was taken —
     * callers must not assume it matches [ChatThreadItems.renderable]'s order.
     */
    data class PhotoStack(val messages: List<Message>, val isTail: Boolean = true) : ChatThreadItem {
        override val key: String get() = PHOTO_STACK_PREFIX + messages.first().id
    }

    companion object {
        const val DATE_HEADER_PREFIX = "date_header_"
        const val PHOTO_STACK_PREFIX = "photo_stack_"
    }
}

object ChatThreadItems {

    /**
     * The messages that get a bubble. Mirrors `ChatView.swift:280-286`: `isSilent`
     * (NOT `isControl` — call summaries, locations, check-ins and scheduled calls
     * are sentinels too and each has its own renderer), plus the two profile /
     * wallpaper markers iOS hides here as well.
     */
    fun renderable(messages: List<Message>): List<Message> = messages.filter { msg ->
        val raw = msg.content
        !OSHIControlPayload.isSilent(raw) &&
            !raw.startsWith(OSHIControlPayload.PROFILE_UPDATE) &&
            !raw.startsWith(OSHIControlPayload.WALLPAPER_UPDATE)
    }

    /** (N17) How close together two photos have to be to read as "sent together", not just "sent on the same day". */
    const val PHOTO_STACK_WINDOW_MS = 120_000L

    /**
     * (N17) A photo with nothing else going on: an image, with no caption
     * (iOS and Android both mark a captionless photo with a bracketed
     * placeholder in `content`, same convention ChatScreen's own caption
     * extraction already relies on — see `caption = displayContent.takeIf
     * { !it.startsWith("[") }`), not failed, not deleted, and not view-once
     * (that mode's one-look-then-gone semantics do not compose with sitting
     * in a grid someone can glance at repeatedly).
     */
    private fun isStackablePhoto(m: Message): Boolean =
        m.mediaType == MediaType.IMAGE &&
            m.content.startsWith("[") &&
            !m.isFailed &&
            !m.isDeletedForEveryone &&
            !m.isViewOnce

    /**
     * Every row the thread renders, in `LazyColumn` order.
     *
     * The list is drawn with `reverseLayout = true`, so index 0 is the NEWEST
     * message and the entry at `index + 1` is OLDER. A date header therefore
     * follows the message it belongs to in list order (it draws above it on
     * screen), and is emitted when that message's day differs from the older
     * one's — plus always after the oldest message. (N17) A run of 2+
     * consecutive [isStackablePhoto] messages is emitted as one
     * [ChatThreadItem.PhotoStack] instead of one [ChatThreadItem.Bubble] each.
     *
     * [dayOf] is injected rather than read from `Calendar` so the mapping can be
     * tested without a timezone.
     */
    fun <D> build(messages: List<Message>, dayOf: (Long) -> D): List<ChatThreadItem> {
        val bubbles = renderable(messages)
        // __CHAT_OPEN_PERF_2026_11_14__ ONE day lookup per message. The comparisons
        // below used to call [dayOf] four times per row, and the production [dayOf]
        // built a fresh `SimpleDateFormat` on every call: ~12 000 formatter
        // allocations for a 3 000-message thread, on the main thread, on every
        // recomposition of `ChatScreen`.
        val days = ArrayList<D>(bubbles.size)
        for (b in bubbles) days.add(dayOf(b.timestamp))
        val tails = tailFlags(size = bubbles.size, newestFirst = true) { a, b ->
            bubbles[a].isFromMe == bubbles[b].isFromMe && days[a] == days[b]
        }

        fun dateHeaderAfter(lastIndexOfRow: Int, lastMessage: Message): ChatThreadItem.DateHeader? {
            val showHeader = if (lastIndexOfRow < bubbles.size - 1) {
                days[lastIndexOfRow] != days[lastIndexOfRow + 1]
            } else {
                true
            }
            return if (showHeader) ChatThreadItem.DateHeader(lastMessage.id, lastMessage.timestamp) else null
        }

        return buildList {
            var index = 0
            while (index < bubbles.size) {
                // A run of 2+ stackable photos becomes one PhotoStack row.
                // `bubbles` is newest-first (reverseLayout); "adjacent" below
                // means adjacent in THAT order — grouping never reorders
                // anything, it only decides whether consecutive rows become
                // one item or stay separate.
                if (isStackablePhoto(bubbles[index])) {
                    var end = index
                    while (end + 1 < bubbles.size &&
                        isStackablePhoto(bubbles[end + 1]) &&
                        bubbles[end + 1].isFromMe == bubbles[index].isFromMe &&
                        days[end + 1] == days[index] &&
                        kotlin.math.abs(bubbles[end].timestamp - bubbles[end + 1].timestamp) <= PHOTO_STACK_WINDOW_MS
                    ) {
                        end++
                    }
                    if (end > index) {
                        val lastInRun = bubbles[end]
                        // Oldest-first within the stack, regardless of the thread's
                        // own newest-first order — see [ChatThreadItem.PhotoStack].
                        add(ChatThreadItem.PhotoStack(bubbles.subList(index, end + 1).reversed(), isTail = tails[end]))
                        dateHeaderAfter(end, lastInRun)?.let(::add)
                        index = end + 1
                        continue
                    }
                }
                val message = bubbles[index]
                add(ChatThreadItem.Bubble(message, isTail = tails[index]))
                dateHeaderAfter(index, message)?.let(::add)
                index++
            }
        }
    }

    /**
     * Which rows END a run, i.e. which bubbles get the tailed corner.
     *
     * iOS: "only the LAST bubble of a run is anchored, so a burst of five
     * messages reads as one column that touches down once instead of five
     * identical lozenges" (`BubbleStyleManager.swift:296-304`). Android tailed
     * every bubble, which is why a burst of messages looked like a stack of
     * separate cards rather than one utterance.
     *
     * A pure function over INDICES rather than over messages, because the two
     * threads disagree about everything except this rule: the 1:1 thread is
     * `reverseLayout = true` (index 0 is the NEWEST row) and holds `Message`,
     * the group thread is forward-layout and holds `GroupMessage`. Sharing the
     * rule rather than the types is what stops the two from drifting the way
     * the scroll predicates did (F-2).
     *
     * @param newestFirst index 0 is the newest row. `true` for the 1:1 thread.
     * @param sameRun do the two adjacent rows at these indices belong to one
     *   run — same author AND same day? A day boundary ends a run because a
     *   date header is drawn between them, and a bubble whose tail points at a
     *   date separator reads as pointing at nothing.
     */
    fun tailFlags(size: Int, newestFirst: Boolean, sameRun: (Int, Int) -> Boolean): List<Boolean> =
        List(size) { index ->
            val newer = if (newestFirst) index - 1 else index + 1
            // The row with no newer neighbour is always the end of its run.
            if (newer < 0 || newer >= size) true else !sameRun(index, newer)
        }

    /**
     * Where [messageId] actually sits in the rendered list, or -1 when it is not
     * rendered at all (a silent payload, or a message the current date filter
     * excludes). Callers must treat -1 as "do not scroll".
     */
    fun positionOf(items: List<ChatThreadItem>, messageId: String?): Int {
        if (messageId.isNullOrEmpty()) return -1
        return items.indexOfFirst {
            when (it) {
                is ChatThreadItem.Bubble -> it.message.id == messageId
                // (N17) A message grouped into a stack is still "rendered" —
                // just not as its own row — so a reply quote or search hit
                // on one of its photos must still resolve to a real,
                // scrollable position: the stack's.
                is ChatThreadItem.PhotoStack -> it.messages.any { m -> m.id == messageId }
                is ChatThreadItem.DateHeader -> false
            }
        }
    }

    /**
     * __OPEN_SCROLL_2026_11_16__ The newest message the thread DRAWS — row 0 under
     * `reverseLayout`. A photo stack keeps its photos oldest-first, so its newest is
     * the last. Null while nothing is rendered yet (rows still being built).
     */
    fun newestMessage(items: List<ChatThreadItem>): Message? {
        for (item in items) {
            when (item) {
                is ChatThreadItem.Bubble -> return item.message
                is ChatThreadItem.PhotoStack -> return item.messages.last()
                is ChatThreadItem.DateHeader -> {}
            }
        }
        return null
    }

    /**
     * __OPEN_AT_THE_FIRST_UNREAD__ Where an opening thread lands: the oldest of
     * [unreadIds] (oldest first) that the thread actually draws. Null = no unread
     * row on screen, land on the newest message.
     */
    fun openUnreadAnchor(items: List<ChatThreadItem>, unreadIds: List<String>): String? =
        unreadIds.firstOrNull { positionOf(items, it) >= 0 }

    /**
     * __OPEN_SCROLL_2026_11_16__ Does a new newest row pull the reader down? Always
     * for their own send (iOS `isMyOwnSend`); for anyone else's only when one of the
     * rows that were the newest before it is still on screen (iOS
     * `wasPinnedToBottom`). Judged by KEYS, so the answer does not depend on whether
     * LazyColumn has already re-measured around the inserted rows — an index test
     * (`firstVisibleItemIndex <= 2`) failed once a burst of 3+ messages landed at once.
     */
    fun shouldFollowNewest(
        newestIsMine: Boolean,
        previousNewestKeys: List<String>,
        visibleKeys: Collection<Any?>
    ): Boolean = newestIsMine || previousNewestKeys.any { it in visibleKeys }

    /**
     * The message a visible-item key belongs to, for the floating date pill.
     * Date-header keys resolve to their anchor message.
     */
    fun messageForKey(items: List<ChatThreadItem>, key: String?): Message? {
        if (key == null) return null
        val id = key.removePrefix(ChatThreadItem.DATE_HEADER_PREFIX)
        for (item in items) {
            when (item) {
                is ChatThreadItem.Bubble -> if (item.message.id == id) return item.message
                is ChatThreadItem.PhotoStack -> item.messages.firstOrNull { it.id == id }?.let { return it }
                is ChatThreadItem.DateHeader -> {}
            }
        }
        return null
    }
}

/**
 * __CHAT_OPEN_PERF_2026_11_14__ `LazyColumn` content types for [ChatThreadItem], so a
 * scrolled-away date header is recycled into a date header and a photo grid into a
 * photo grid instead of re-composing one row type from the slot of another.
 * Media bubbles are split by kind because their layouts differ the most.
 */
fun ChatThreadItem.contentType(): String = when (this) {
    is ChatThreadItem.DateHeader -> "date"
    is ChatThreadItem.PhotoStack -> "photo_stack"
    is ChatThreadItem.Bubble -> when (message.mediaType) {
        null -> "text"
        else -> "media_" + message.mediaType.name
    }
}

/**
 * __CHAT_OPEN_PERF_2026_11_14__ The calendar day a message belongs to, for date
 * headers and bubble runs — WITHOUT a `SimpleDateFormat`.
 *
 * Both threads used `SimpleDateFormat("yyyy-MM-dd").format(...)` and compared the
 * strings, building a new formatter per call (four calls per row in the 1:1 thread,
 * four per row in the group thread, on the main thread). Only EQUALITY of the day is
 * ever used, so the local epoch day — `floor((ms + zoneOffset(ms)) / 1 day)` — answers
 * the same question: two instants share a local `yyyy-MM-dd` exactly when they share
 * this number. `getOffset(ms)` is evaluated per instant, so DST transitions land on
 * the same day boundaries the formatter used.
 *
 * Timestamps in seconds (≤ 10^12, legacy iOS rows) are normalised exactly as the old
 * helpers did.
 */
object ChatDayKey {
    private const val DAY_MS = 86_400_000L

    fun normalize(timestamp: Long): Long =
        if (timestamp in 1..1_000_000_000_000L) timestamp * 1000 else timestamp

    fun of(timestamp: Long, zone: java.util.TimeZone): Long {
        val ms = normalize(timestamp)
        return Math.floorDiv(ms + zone.getOffset(ms), DAY_MS)
    }

    /** A day function bound to the CURRENT default zone, resolved once per call site. */
    fun inDefaultZone(): (Long) -> Long {
        val zone = java.util.TimeZone.getDefault()
        return { of(it, zone) }
    }
}
