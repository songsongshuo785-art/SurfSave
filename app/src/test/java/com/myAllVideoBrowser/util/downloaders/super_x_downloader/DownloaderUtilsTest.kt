package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import android.app.Application
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DownloaderUtilsTest {
    private lateinit var server: MockWebServer
    private lateinit var temporaryDirectory: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        temporaryDirectory = Files.createTempDirectory("downloader-utils-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun prepareHlsEncryptionKeys_downloadsEveryDistinctRotatedKey() {
        val firstBytes = "0123456789abcdef".toByteArray()
        val secondBytes = "fedcba9876543210".toByteArray()
        server.enqueue(MockResponse().setBody(String(firstBytes, Charsets.UTF_8)))
        server.enqueue(MockResponse().setBody(String(secondBytes, Charsets.UTF_8)))
        val firstKey = key(server.url("/keys/first").toString())
        val secondKey = key(server.url("/keys/second").toString())
        val segments = listOf(
            segment("https://media.example/0.ts", 10L, firstKey),
            segment("https://media.example/1.ts", 11L, secondKey),
            segment("https://media.example/2.ts", 12L, firstKey)
        )

        DownloaderUtils.prepareHlsEncryptionKeys(
            OkHttpClient.Builder().build(),
            temporaryDirectory,
            Headers.Builder().build(),
            segments
        )

        assertArrayEquals(
            firstBytes,
            temporaryDirectory.resolve(DownloaderUtils.encryptionKeyFileName(firstKey.uri)).readBytes()
        )
        assertArrayEquals(
            secondBytes,
            temporaryDirectory.resolve(DownloaderUtils.encryptionKeyFileName(secondKey.uri)).readBytes()
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun localPlaylist_usesSequenceIvPerSegmentAndEmitsMethodNone() {
        val encryptionKey = key("https://keys.example/key.bin")
        temporaryDirectory.resolve(DownloaderUtils.encryptionKeyFileName(encryptionKey.uri))
            .writeBytes("0123456789abcdef".toByteArray())
        repeat(3) { index ->
            temporaryDirectory.resolve("segment_${"%05d".format(index)}.ts")
                .writeText("segment-$index", Charsets.UTF_8)
        }
        val segments = listOf(
            segment("https://media.example/43.ts", 43L, encryptionKey),
            segment("https://media.example/44.ts", 44L, encryptionKey),
            segment("https://media.example/45.ts", 45L, null)
        )

        val playlist = DownloaderUtils.createLocalHlsPlaylistFile(
            hlsTmpDir = temporaryDirectory,
            segments = segments,
            filePrefix = "segment_",
            playlistName = "video.m3u8"
        ).readText(Charsets.UTF_8)

        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:43"))
        assertTrue(playlist.contains("IV=0x0000000000000000000000000000002b"))
        assertTrue(playlist.contains("IV=0x0000000000000000000000000000002c"))
        assertTrue(playlist.contains("#EXT-X-KEY:METHOD=NONE"))
    }

    @Test
    fun failedSnapshotPreparation_preservesPreviouslyPublishedPlaylistAndMarker() {
        temporaryDirectory.resolve("segment_00000.ts").writeText("complete", Charsets.UTF_8)
        val initialSegments = listOf(segment("https://media.example/0.ts", 0L, null))
        val snapshot = DownloaderUtils.createLocalHlsPlaylistFile(
            hlsTmpDir = temporaryDirectory,
            segments = initialSegments,
            filePrefix = "segment_",
            playlistName = "video.m3u8"
        )
        val marker = temporaryDirectory.resolve("video.capture")
        val previousSnapshot = snapshot.readBytes()
        val previousMarker = marker.readBytes()

        expectIOException {
            DownloaderUtils.createLocalHlsPlaylistFile(
                hlsTmpDir = temporaryDirectory,
                segments = initialSegments + segment("https://media.example/1.ts", 1L, null),
                filePrefix = "segment_",
                playlistName = "video.m3u8"
            )
        }

        assertArrayEquals(previousSnapshot, snapshot.readBytes())
        assertArrayEquals(previousMarker, marker.readBytes())
        assertFalse(temporaryDirectory.resolve("video.txt.tmp").exists())
    }

    @Test
    fun captureManifest_advancesOnlyAfterBothStreamsPublishTheSameGeneration() {
        temporaryDirectory.resolve("segment_00000.ts").writeText("video-0", Charsets.UTF_8)
        temporaryDirectory.resolve("audio_segment_00000.ts").writeText("audio-0", Charsets.UTF_8)
        val initialVideo = listOf(segment("https://media.example/video-0.ts", 0L, null))
        val initialAudio = listOf(segment("https://media.example/audio-0.ts", 0L, null))
        DownloaderUtils.publishHlsCaptureSnapshot(
            temporaryDirectory,
            initialVideo,
            initialAudio
        )
        val manifest = temporaryDirectory.resolve("hls_capture.json")
        val committedManifest = manifest.readBytes()
        val committedVideo = requireNotNull(
            DownloaderUtils.preparedHlsInputFile(temporaryDirectory, "video")
        )
        val committedAudio = requireNotNull(
            DownloaderUtils.preparedHlsInputFile(temporaryDirectory, "audio")
        )

        temporaryDirectory.resolve("segment_00001.ts").writeText("video-1", Charsets.UTF_8)
        val candidateVideo = initialVideo + segment("https://media.example/video-1.ts", 1L, null)
        val candidateAudio = initialAudio + segment("https://media.example/audio-1.ts", 1L, null)

        expectIOException {
            DownloaderUtils.publishHlsCaptureSnapshot(
                temporaryDirectory,
                candidateVideo,
                candidateAudio
            )
        }

        assertArrayEquals(committedManifest, manifest.readBytes())
        assertEquals(
            committedVideo.absolutePath,
            DownloaderUtils.preparedHlsInputFile(temporaryDirectory, "video")?.absolutePath
        )
        assertEquals(
            committedAudio.absolutePath,
            DownloaderUtils.preparedHlsInputFile(temporaryDirectory, "audio")?.absolutePath
        )
        assertEquals(
            1,
            temporaryDirectory.listFiles { file -> file.name.startsWith("video_capture_") }
                ?.size
        )
        assertEquals(
            1,
            temporaryDirectory.listFiles { file -> file.name.startsWith("audio_capture_") }
                ?.size
        )
    }

    @Test
    fun captureManifest_recoversCommittedBackupAfterInterruptedPublish() {
        temporaryDirectory.resolve("segment_00000.ts").writeText("video-0", Charsets.UTF_8)
        val segments = listOf(segment("https://media.example/video-0.ts", 0L, null))
        DownloaderUtils.publishHlsCaptureSnapshot(
            temporaryDirectory,
            segments,
            emptyList()
        )
        val committedInput = requireNotNull(
            DownloaderUtils.preparedHlsInputFile(temporaryDirectory, "video")
        )
        val manifest = temporaryDirectory.resolve("hls_capture.json")
        val backup = temporaryDirectory.resolve("hls_capture.json.bak")
        assertTrue(manifest.renameTo(backup))

        val recoveredInput = DownloaderUtils.preparedHlsInputFile(temporaryDirectory, "video")

        assertEquals(committedInput.absolutePath, recoveredInput?.absolutePath)
        assertTrue(manifest.isFile)
        assertFalse(backup.exists())
    }

    @Test
    fun fmp4Concatenation_preservesCancellationTypeAndRemovesPartialOutput() {
        val initialization = HlsPlaylistParser.InitializationSegment(
            "https://media.example/init.mp4",
            null
        )
        temporaryDirectory.resolve("init_video.mp4").writeText("init", Charsets.UTF_8)
        temporaryDirectory.resolve("segment_00000.m4s").writeText("segment", Charsets.UTF_8)
        val segments = listOf(
            segment(
                url = "https://media.example/0.m4s",
                sequence = 0L,
                encryptionKey = null,
                initializationSegment = initialization
            )
        )

        try {
            DownloaderUtils.createConcatenatedFmp4File(
                temporaryDirectory,
                segments,
                "video",
                shouldAbort = { true }
            )
            throw AssertionError("Expected HLS concatenation to be canceled.")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertFalse(temporaryDirectory.resolve("concatenated_video.mp4").exists())
    }

    private fun key(uri: String): HlsPlaylistParser.HlsEncryptionKey {
        return HlsPlaylistParser.HlsEncryptionKey("AES-128", uri)
    }

    private fun segment(
        url: String,
        sequence: Long,
        encryptionKey: HlsPlaylistParser.HlsEncryptionKey?,
        initializationSegment: HlsPlaylistParser.InitializationSegment? = null
    ): HlsPlaylistParser.UrlMediaSegment {
        return HlsPlaylistParser.UrlMediaSegment(
            url = url,
            duration = 6.0,
            title = null,
            discontinuity = false,
            initializationSegment = initializationSegment,
            byteRange = null,
            parts = emptyList(),
            encryptionKey = encryptionKey,
            mediaSequence = sequence
        )
    }

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected IOException.")
    }
}
