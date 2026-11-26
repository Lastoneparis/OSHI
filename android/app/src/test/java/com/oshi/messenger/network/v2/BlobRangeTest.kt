package com.oshi.messenger.network.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.Base64

/** __BLOB_RANGE_2026_11_19__ docs/CENSORSHIP_2026_THREAT_MODEL.md §7. */
class BlobRangeTest {
    @Test fun partsOldServerAndFailures() {
        assertEquals(BlobRange.Result.Part(2688, false), BlobRange.accept(206, "bytes 0-2687/12000", 0, 2688))
        assertEquals(BlobRange.Result.Part(12000, true), BlobRange.accept(206, "bytes 10752-11999/12000", 10752, 1248))
        assertEquals(BlobRange.Result.Whole, BlobRange.accept(200, null, 0, 12000))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(206, "bytes 5-10/12000", 0, 6))       // wrong start
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(206, "bytes 0-9/12000", 0, 9))        // short body
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(206, null, 0, 10))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(416, "bytes */12000", 12000, 0))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(404, null, 0, 0))
        assertEquals(BlobRange.Result.Part(10, true), BlobRange.accept(206, "BYTES 0-9/10", 0, 10))
    }

    /** The cover record of a 206 part must stay in the 4 KiB bucket (record + 4 <= 4096). */
    @Test fun aCoverPartFitsTheSmallestBucket() {
        val body = Base64.getEncoder().encodeToString(ByteArray(BlobRange.COVER_PART))
        val record = JSONObject().put("v", 1).put("status", 206).put("headers", JSONObject()
            .put("content-type", "application/octet-stream").put("content-length", BlobRange.COVER_PART)
            .put("content-range", "bytes 2097152-2099839/2097168")).put("body", body)
        val size = record.toString().toByteArray().size
        assertTrue("record $size B", size + 4 <= 4096)
        assertEquals(4096, com.oshi.messenger.service.CoverCodec.minimalBucket(size))
    }
}
