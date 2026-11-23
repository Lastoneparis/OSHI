package com.oshi.messenger

import com.oshi.messenger.service.MediaCompressionManager
import com.oshi.messenger.service.media.VideoPresetLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * __VIDEO_MESH_BUDGET_2026_09_18__ + __PICKER_LATENCY_2026_09_18__
 *
 * Two complaints from the owner, one screen apart:
 *
 *  * *"a video sent over OSHI mesh should be compressed hard, like a photo"* — it
 *    was not. The photo branch asks who is on the other end and hands the mesh's
 *    10 MB down when the peer is reachable there; the video branch was pinned to
 *    the 200 MB cloud ceiling. The ladder picks the best rung whose estimate fits
 *    the budget it is GIVEN, so 200 MB meant "1080p at 5 Mbps" for every clip — a
 *    file then pushed through a link sized for 10 MB.
 *
 *  * *"tap a video in the gallery and for a few seconds there is nothing on
 *    screen"* — the picker callback is dispatched to Main and read the whole file
 *    there. The image picker has hopped to a background dispatcher since M-14; the
 *    video picker never got the same treatment, which is precisely why photos feel
 *    instant and videos feel broken.
 *
 * The arithmetic is tested for real. The wiring is tested against the source, for
 * the reason `V2EnvelopeTimestampWiringTest` gives: these call sites need the Hilt
 * graph and a Compose runtime.
 */
class VideoSendBudgetTest {

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
        error("unreachable")
    }

    private val repositorySource by lazy {
        moduleFile("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt").readText()
    }
    private val chatScreenSource by lazy {
        moduleFile("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt").readText()
    }

    // ── What the budget actually buys ──────────────────────────────────────────

    @Test
    fun `the mesh budget is what makes the plan come down`() {
        // A three-minute clip — an ordinary thing to send. At the chat bitrate it
        // costs ~55 MB, which the 200 MB budget waves through and a 10 MB link
        // cannot carry. This is the whole defect in one assertion.
        //
        // __CHAT_VIDEO_BITRATE_2026_10_09__ The duration moved from one minute to
        // three because the top rung is no longer 1080p at 5 Mbps: a minute of
        // 720p at 2.35 Mbps is ~18 MB, so the cloud budget and the mesh budget
        // would now BOTH have to come down and the comparison would prove nothing.
        val threeMinutes = 180_000L
        val cloud = VideoPresetLadder.plan(
            1920, 1080, threeMinutes, 30, MediaCompressionManager.IPFS_MAX_SIZE)
        val mesh = VideoPresetLadder.plan(
            1920, 1080, threeMinutes, 30, MediaCompressionManager.MESH_MAX_SIZE)

        assertEquals("the cloud plan is the chat target", 1280, maxOf(cloud.width, cloud.height))
        assertTrue("the cloud budget fits the chat target", cloud.fitsBudget)

        assertTrue(
            "the mesh budget must pick a smaller frame than the cloud one, or the " +
                "fix buys nothing: got ${mesh.label}",
            maxOf(mesh.width, mesh.height) < maxOf(cloud.width, cloud.height),
        )
        assertTrue(
            "the point is FEWER BYTES: ${mesh.estimatedBytes} vs ${cloud.estimatedBytes}",
            mesh.estimatedBytes < cloud.estimatedBytes,
        )
    }

    @Test
    fun `a clip too long for any rung is still sent, never truncated`() {
        // Twenty minutes fits nothing on the ladder at 10 MB. The contract is the
        // smallest rung and `fitsBudget = false` — NOT a failure and NOT a cut
        // video, because "a shorter video is a worse failure than a larger one".
        val plan = VideoPresetLadder.plan(
            1920, 1080, 20 * 60_000L, 30, MediaCompressionManager.MESH_MAX_SIZE)
        assertEquals(640, maxOf(plan.width, plan.height))
        assertFalse(plan.fitsBudget)
    }

    // ── The wiring that was missing ────────────────────────────────────────────

    @Test
    fun `the video branch asks who is on the other end, like the photo branch`() {
        val branch = videoBranch()
        assertTrue(
            "the video branch must consult the mesh peers — it was pinned to " +
                "DeliveryTarget.VPS whatever the transport",
            branch.contains("meshManager.connectedPeers"),
        )
        assertTrue(
            "and it must be able to choose MESH",
            branch.contains("MediaCompressionManager.DeliveryTarget.MESH"),
        )
        assertFalse(
            "an unconditional VPS target is the bug: $branch",
            Regex("val target = MediaCompressionManager\\.DeliveryTarget\\.VPS")
                .containsMatchIn(branch),
        )
    }

    @Test
    fun `no picker callback reads its file on the main thread`() {
        // `rememberLauncherForActivityResult` dispatches its callback to Main, so
        // every read INSIDE one of those lambdas has to hop off it. Found the
        // document picker doing exactly what the video picker did, which is why
        // this asks about all four rather than the one that was reported.
        val launchers = listOf(
            // __ATTACH_PANEL_2027_2026_11_16__ one gallery picker (photos + videos)
            // replaced the separate image and video pickers.
            "galleryPickerLauncher",
            "videoCaptureLauncher", "documentPickerLauncher",
        )
        for (name in launchers) {
            val body = launcherBody(name)
            if (!body.contains("readBytes()")) continue
            assertTrue(
                "$name reads its file without leaving the main thread: that is " +
                    "seconds of I/O with the picker already dismissed and nothing " +
                    "on screen, which is what the owner reported for video",
                body.contains("withContext(kotlinx.coroutines.Dispatchers.IO)") ||
                    body.contains("withContext(kotlinx.coroutines.Dispatchers.Default)"),
            )
            assertTrue(
                "$name must also SAY it is working — an unexplained pause is the " +
                    "complaint, not the thread it happens on",
                body.contains("isPreparingMedia = true"),
            )
        }
        // The gallery picker and the attachment panel read their files through
        // `RecentMedia.load`, which must itself leave the main thread — and the
        // picker must still put the progress strip up while it runs.
        val loader = moduleFile("src/main/java/com/oshi/messenger/ui/components/attach/RecentMedia.kt").readText()
        val load = loader.substringAfter("suspend fun load(").substringBefore("private fun decodeSampledJpeg(")
        assertTrue("RecentMedia.load reads on the main thread", load.contains("withContext(Dispatchers.IO)"))
        val gallery = launcherBody("galleryPickerLauncher")
        assertTrue(gallery.contains("RecentMedia.load(") && gallery.contains("isPreparingMedia = true"))
    }

    /** The lambda body of `val <name> = rememberLauncherForActivityResult(…) { … }`. */
    private fun launcherBody(name: String): String {
        val decl = chatScreenSource.indexOf("val $name = rememberLauncherForActivityResult")
        assertTrue("$name is gone from ChatScreen", decl > 0)
        // Skip the contract argument list, then take the trailing lambda.
        var i = chatScreenSource.indexOf('{', chatScreenSource.indexOf(')', decl))
        val open = i
        var depth = 0
        while (i < chatScreenSource.length) {
            when (chatScreenSource[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        return chatScreenSource.substring(open, minOf(i + 1, chatScreenSource.length))
    }

    @Test
    fun `the progress strip covers the read, not only the compression`() {
        assertTrue(
            "the strip must turn on while the file is being read — that is the " +
                "gap the owner saw",
            chatScreenSource.contains("visible = isMediaProcessing || isPreparingMedia"),
        )
        assertTrue(
            "a ContentResolver read reports no progress, so the indicators have to " +
                "be indeterminate: a determinate bar frozen at 0% reads as stuck",
            chatScreenSource.contains("val indeterminate = isPreparingMedia && !isMediaProcessing"),
        )
    }

    // ── What may leave the app at all ──────────────────────────────────────────

    @Test
    fun `only H264 plus AAC counts as sendable without a re-encode`() {
        // The intersection an iPhone, a Mac and an Android phone all decode.
        assertTrue(VideoPresetLadder.isInteroperable("video/avc", "audio/mp4a-latm"))
        // Silence is portable.
        assertTrue(VideoPresetLadder.isInteroperable("video/avc", null))
        // Everything a gallery legitimately holds and an iPhone cannot open, or
        // an MPEG-4 muxer will not accept:
        for ((v, a) in listOf(
            "video/x-vnd.on2.vp9" to "audio/opus",      // downloaded WebM
            "video/av01" to "audio/mp4a-latm",          // AV1
            "video/hevc" to "audio/mp4a-latm",          // the phone's own camera
            "video/avc" to "audio/opus",
            "video/avc" to "audio/vorbis",
            "video/avc" to "audio/3gpp",                // AMR from a voice recorder
        )) {
            assertFalse("$v + $a must not be sent untouched", VideoPresetLadder.isInteroperable(v, a))
        }
    }

    @Test
    fun `an unreadable probe is not treated as a promise`() {
        // Nulls mean "could not tell". The cost of an unnecessary re-encode is one
        // generation of quality; the cost of skipping a necessary one is a video
        // that arrives and will not play.
        assertFalse(VideoPresetLadder.isInteroperable(null, null))
        assertFalse(VideoPresetLadder.isInteroperable("video/avc", "audio/unknown"))
    }

    @Test
    fun `a small 720p clip still transcodes when its codec is wrong`() {
        // 3 MB, 720p — under every budget on the ladder. Before the codec question
        // was asked, this went out byte-for-byte, and on an iPhone it was a bubble
        // that would not play. No error on either side: the worst shape a bug takes.
        val small = 3L * 1024 * 1024
        assertTrue(
            "a small VP9 clip must still be re-encoded",
            VideoPresetLadder.needsTranscode(
                width = 1280, height = 720, sizeBytes = small,
                hardCeilingBytes = MediaCompressionManager.IPFS_MAX_SIZE,
                videoMime = "video/x-vnd.on2.vp9", audioMime = "audio/opus",
            ),
        )
        assertFalse(
            "…while a small H.264/AAC clip is left alone, as it always was",
            VideoPresetLadder.needsTranscode(
                width = 1280, height = 720, sizeBytes = small,
                hardCeilingBytes = MediaCompressionManager.IPFS_MAX_SIZE,
                videoMime = "video/avc", audioMime = "audio/mp4a-latm",
            ),
        )
    }

    @Test
    fun `the transcoder re-encodes foreign audio instead of handing it to the muxer`() {
        val src = moduleFile(
            "src/main/java/com/oshi/messenger/service/media/VideoTranscoder.kt").readText()
        assertTrue(
            "non-AAC audio must be rebuilt as AAC — passing it straight to an " +
                "MPEG-4 muxer throws, which returns false, which sends the ORIGINAL " +
                "file: the one the peer cannot open",
            src.contains("encodeAudioToAacFile("),
        )
        assertTrue(
            "the passthrough must be gated on the source already being AAC",
            src.contains("VideoPresetLadder.INTEROP_AUDIO_MIME.equals(probedAudioMime"),
        )
        assertTrue(
            "the probe has to report the codecs, or the decision above is blind",
            src.contains("val videoMime: String?") && src.contains("val audioMime: String?"),
        )
    }

    @Test
    fun `iOS cannot send anything but H264 AAC mp4, which is why this is the target`() {
        // Not decoration: the Android contract above is only worth having because
        // the other end is fixed. AVAssetExportPreset<W>x<H> are H.264/AAC presets,
        // and the container is pinned. If iOS ever moves to HEVC, Android's
        // "already interoperable, skip it" answer becomes wrong in the other
        // direction and this test is where that shows up.
        var dir = java.io.File(".").absoluteFile
        var swift: java.io.File? = null
        while (dir.parentFile != null && swift == null) {
            val c = java.io.File(dir, "OSHI/MediaManager.swift")
            if (c.isFile) swift = c
            dir = dir.parentFile
        }
        val text = swift?.readText() ?: return   // iOS tree not beside us; nothing to check
        assertTrue("iOS must pin the container to mp4", text.contains("outputFileType = .mp4"))
        for (preset in listOf(
            "AVAssetExportPreset1920x1080", "AVAssetExportPreset1280x720", "AVAssetExportPreset960x540",
        )) {
            assertTrue("iOS ladder lost $preset", text.contains(preset))
        }
        assertFalse(
            "iOS switched to an HEVC preset — Android's interop rule must follow",
            text.contains("AVAssetExportPresetHEVC"),
        )
    }

    /** The `MediaType.VIDEO ->` arm of the compression `when`, braces balanced. */
    private fun videoBranch(): String {
        val start = repositorySource.indexOf("MediaType.VIDEO -> {")
        assertTrue("the video compression branch is gone", start > 0)
        var depth = 0
        var i = repositorySource.indexOf('{', start)
        val open = i
        while (i < repositorySource.length) {
            when (repositorySource[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        return repositorySource.substring(open, minOf(i + 1, repositorySource.length))
    }
}
