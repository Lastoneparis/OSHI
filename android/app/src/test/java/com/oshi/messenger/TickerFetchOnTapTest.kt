package com.oshi.messenger

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __TICKER_FETCH_ON_TAP_2026_11_20__ Source guard: a received `@SYMBOL` chip must not
 * call the third-party quote API until the person taps it (it leaked the reader's IP
 * and read time to Yahoo, on a symbol the SENDER chose).
 */
class TickerFetchOnTapTest {
    private val src = File("src/main/java/com/oshi/messenger/ui/components/TickerChip.kt").readText()

    @Test fun fetchIsGatedOnATap() {
        assertTrue(src.contains("if (frozen != null || !requested) return@LaunchedEffect"))
        assertTrue(src.contains("?.let { ChipState.Loaded(it) } ?: ChipState.Idle"))
        assertTrue(src.contains("if (state is ChipState.Idle) requested = true"))
    }
}
