package com.oshi.messenger

import com.oshi.messenger.data.model.OfflineOnlyRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** __OFFLINE_ONLY_FUTURE_CLAMP_2026_11_20__ */
class OfflineOnlyFutureClampTest {
    private val now = 1_800_000_000.0

    @Test fun aFarFutureTimestampIsClampedToFiveMinutesAhead() {
        assertEquals(now + 300.0, OfflineOnlyRequest.clampedAt(32_503_680_000.0, now), 0.0)
    }

    @Test fun pastAndNearFutureTimestampsAreKept() {
        assertEquals(now - 60.0, OfflineOnlyRequest.clampedAt(now - 60.0, now), 0.0)
        assertEquals(now + 120.0, OfflineOnlyRequest.clampedAt(now + 120.0, now), 0.0)
    }

    @Test fun nanIsTreatedAsTheOldestPossibleChange() {
        assertEquals(0.0, OfflineOnlyRequest.clampedAt(Double.NaN, now), 0.0)
    }
}
