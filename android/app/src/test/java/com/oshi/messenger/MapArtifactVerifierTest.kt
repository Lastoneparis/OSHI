package com.oshi.messenger

import com.oshi.messenger.service.map.MapArtifactVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The download integrity gate.
 *
 * The predecessor (`MapStorageManager.verifyFile`) had two defects that made it
 * worse than nothing: it `return true` on a blank hash, and it had exactly one
 * reference in the codebase — its own definition. These tests pin the fail-closed
 * behaviour, in particular that "no hash" is NOT a pass.
 */
class MapArtifactVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** sha256("OSHI") — computed independently of the class under test. */
    private val payload = "OSHI".toByteArray()
    private val payloadSha = "cdc1acb5cd9f6c9ab7b2b1e2b0d4d5a2d7ae0e97f2f6a4f2e2b1c3f43e1c0f66"

    private fun file(bytes: ByteArray): File =
        tmp.newFile().apply { writeBytes(bytes) }

    private fun realSha(f: File): String = MapArtifactVerifier.sha256(f)

    @Test
    fun `a blank hash is NOT verified`() {
        val f = file(payload)
        assertEquals(MapArtifactVerifier.Result.NO_HASH, MapArtifactVerifier.verify(f, ""))
        assertEquals(MapArtifactVerifier.Result.NO_HASH, MapArtifactVerifier.verify(f, null))
        assertEquals(MapArtifactVerifier.Result.NO_HASH, MapArtifactVerifier.verify(f, "   "))
        assertFalse(MapArtifactVerifier.verify(f, "").isVerified)
    }

    @Test
    fun `the all-zeros manifest placeholder is NOT verified`() {
        // The live manifest writes 64 zeros for the `tiles` layer. Accepting it
        // would "verify" literally any bytes.
        val f = file(payload)
        assertEquals(MapArtifactVerifier.Result.NO_HASH, MapArtifactVerifier.verify(f, "0".repeat(64)))
        assertNull(MapArtifactVerifier.usableHash("0".repeat(64)))
        assertNull(MapArtifactVerifier.usableHash(""))
        assertEquals("abc", MapArtifactVerifier.usableHash(" abc "))
    }

    @Test
    fun `a matching digest verifies, case-insensitively`() {
        val f = file(payload)
        val sha = realSha(f)
        assertEquals(MapArtifactVerifier.Result.VERIFIED, MapArtifactVerifier.verify(f, sha))
        assertEquals(MapArtifactVerifier.Result.VERIFIED, MapArtifactVerifier.verify(f, sha.uppercase()))
    }

    @Test
    fun `a wrong digest fails`() {
        val f = file(payload)
        assertEquals(
            MapArtifactVerifier.Result.HASH_MISMATCH,
            MapArtifactVerifier.verify(f, payloadSha)
        )
    }

    @Test
    fun `a truncated download fails on size before the digest is even computed`() {
        // This is the exact scenario that used to report "ready": a short write
        // that opens fine and returns nothing.
        val full = ByteArray(4096) { (it % 251).toByte() }
        val f = file(full)
        val sha = realSha(f)
        f.writeBytes(full.copyOf(1024))
        assertEquals(
            MapArtifactVerifier.Result.SIZE_MISMATCH,
            MapArtifactVerifier.verify(f, sha, expectedSize = 4096L)
        )
    }

    @Test
    fun `a missing or empty file fails`() {
        assertEquals(
            MapArtifactVerifier.Result.MISSING,
            MapArtifactVerifier.verify(File(tmp.root, "nope.db"), "a".repeat(64))
        )
        assertEquals(
            MapArtifactVerifier.Result.MISSING,
            MapArtifactVerifier.verify(file(ByteArray(0)), "a".repeat(64))
        )
    }

    @Test
    fun `verifyOrDelete removes anything it could not verify`() {
        val bad = file(payload)
        MapArtifactVerifier.verifyOrDelete(bad, payloadSha)
        assertFalse("mismatched artifact must not survive", bad.exists())

        val unhashed = file(payload)
        MapArtifactVerifier.verifyOrDelete(unhashed, expectedSha256 = null, strict = true)
        assertFalse("unverifiable artifact must not survive in strict mode", unhashed.exists())

        // Non-strict is the one knowing exception: the country-level roadgraph
        // fallback, whose SHA is not in the sub-region's manifest entry
        // (iOS OfflineRoadGraphDownloader.swift:176-182).
        val lenient = file(payload)
        assertEquals(
            MapArtifactVerifier.Result.NO_HASH,
            MapArtifactVerifier.verifyOrDelete(lenient, expectedSha256 = null, strict = false)
        )
        assertTrue(lenient.exists())

        val good = file(payload)
        MapArtifactVerifier.verifyOrDelete(good, realSha(good))
        assertTrue(good.exists())
    }

    @Test
    fun `sha256 matches a known vector`() {
        // SHA-256 of the empty input.
        val f = file(ByteArray(0))
        f.writeBytes(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            MapArtifactVerifier.sha256(ByteArray(0).inputStream())
        )
    }
}
