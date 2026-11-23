package com.oshi.messenger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oshi.messenger.ui.screens.landWithRowAtTop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * __OPEN_AT_THE_FIRST_UNREAD__ The 1:1 thread is a `reverseLayout` LazyColumn, where
 * `scrollToItem(i)` puts row i at the BOTTOM. Opening on the first unread needs that
 * row at the TOP with the newer rows under it — what `landWithRowAtTop` computes.
 * 60 rows of 50 dp in a 400 dp viewport: exactly 8 rows fit.
 */
@RunWith(AndroidJUnit4::class)
class ChatOpenLandingTest {

    @get:Rule
    val rule = createComposeRule()

    private fun thread(): LazyListState {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            LazyColumn(state = state, reverseLayout = true, modifier = Modifier.height(400.dp)) {
                items((0 until 60).toList(), key = { it }) { i ->
                    Box(Modifier.fillMaxWidth().height(50.dp)) { Text("row $i") }
                }
            }
        }
        rule.waitForIdle()
        return state
    }

    @Test
    fun anchorRowLandsAtTheTopWithNewerRowsBelowIt() {
        val state = thread()
        rule.runOnIdle { runBlocking { landWithRowAtTop(state, 30, topMarginPx = 0) } }
        rule.runOnIdle {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo.map { it.index }
            // Row 30 is the topmost row and it is whole; the rows under it are the
            // newer ones down to 23 (22 may show a sliver: 50 dp does not round to
            // a whole number of pixels on every density).
            assertEquals(30, visible.max())
            val anchor = info.visibleItemsInfo.first { it.index == 30 }
            assertTrue(anchor.offset >= info.viewportStartOffset)
            assertTrue(anchor.offset + anchor.size <= info.viewportEndOffset)
            assertTrue(visible.min() in 22..23)
        }
    }

    @Test
    fun aShortUnreadTailClampsToTheNewestRow() {
        val state = thread()
        rule.runOnIdle { runBlocking { landWithRowAtTop(state, 3, topMarginPx = 0) } }
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }
    }
}
