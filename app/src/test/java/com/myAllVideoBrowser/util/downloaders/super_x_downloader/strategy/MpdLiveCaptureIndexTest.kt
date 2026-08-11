package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import android.app.Application
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MpdLiveCaptureIndexTest {
    private lateinit var downloadDirectory: File

    @Before
    fun setUp() {
        downloadDirectory = Files.createTempDirectory("mpd-live-capture-index-test").toFile()
    }

    @After
    fun tearDown() {
        downloadDirectory.deleteRecursively()
    }

    @Test
    fun filesInOrder_usesExplicitSequenceWhenModificationTimesAreEqual() {
        var snapshot = MpdLiveCaptureIndex.Snapshot()
        val first = MpdLiveCaptureIndex.nextEntry(
            snapshot,
            MpdLiveCaptureIndex.Stream.VIDEO,
            "https://media.example/z-last-name.m4s"
        )
        val firstBytes = "first-segment".toByteArray()
        downloadDirectory.resolve(first.fileName).writeBytes(firstBytes)
        snapshot = MpdLiveCaptureIndex.publishEntry(
            downloadDirectory,
            snapshot,
            MpdLiveCaptureIndex.Stream.VIDEO,
            first
        )

        val second = MpdLiveCaptureIndex.nextEntry(
            snapshot,
            MpdLiveCaptureIndex.Stream.VIDEO,
            "https://media.example/a-first-name.m4s"
        )
        val secondBytes = "second-segment".toByteArray()
        downloadDirectory.resolve(second.fileName).writeBytes(secondBytes)
        snapshot = MpdLiveCaptureIndex.publishEntry(
            downloadDirectory,
            snapshot,
            MpdLiveCaptureIndex.Stream.VIDEO,
            second
        )

        val orphan = downloadDirectory.resolve("segment_9999999999.m4s")
        orphan.writeText("uncommitted-orphan", Charsets.UTF_8)
        val sameTimestamp = 1_700_000_000_000L
        assertTrue(downloadDirectory.resolve(first.fileName).setLastModified(sameTimestamp))
        assertTrue(downloadDirectory.resolve(second.fileName).setLastModified(sameTimestamp))
        assertTrue(orphan.setLastModified(sameTimestamp))

        val reloaded = MpdLiveCaptureIndex.loadOrMigrate(downloadDirectory)
        val ordered = MpdLiveCaptureIndex.filesInOrder(
            downloadDirectory,
            reloaded,
            MpdLiveCaptureIndex.Stream.VIDEO
        )

        assertEquals(listOf(first.fileName, second.fileName), ordered.map { it.name })
        assertArrayEquals(firstBytes, ordered[0].readBytes())
        assertArrayEquals(secondBytes, ordered[1].readBytes())
        assertFalse(ordered.any { it.name == orphan.name })
        assertTrue(
            MpdLiveCaptureIndex.containsUrl(
                reloaded,
                MpdLiveCaptureIndex.Stream.VIDEO,
                first.url!!
            )
        )
    }

    @Test
    fun missingSegment_doesNotAdvanceCommittedIndex() {
        var snapshot = MpdLiveCaptureIndex.Snapshot()
        val committed = MpdLiveCaptureIndex.nextEntry(
            snapshot,
            MpdLiveCaptureIndex.Stream.AUDIO,
            "https://media.example/audio-0.m4s"
        )
        downloadDirectory.resolve(committed.fileName).writeText("audio-0", Charsets.UTF_8)
        snapshot = MpdLiveCaptureIndex.publishEntry(
            downloadDirectory,
            snapshot,
            MpdLiveCaptureIndex.Stream.AUDIO,
            committed
        )
        val manifest = downloadDirectory.resolve("mpd_capture.json")
        val manifestBeforeFailure = manifest.readBytes()
        val missing = MpdLiveCaptureIndex.nextEntry(
            snapshot,
            MpdLiveCaptureIndex.Stream.AUDIO,
            "https://media.example/audio-1.m4s"
        )

        expectIOException {
            MpdLiveCaptureIndex.publishEntry(
                downloadDirectory,
                snapshot,
                MpdLiveCaptureIndex.Stream.AUDIO,
                missing
            )
        }

        assertArrayEquals(manifestBeforeFailure, manifest.readBytes())
        val reloaded = MpdLiveCaptureIndex.loadOrMigrate(downloadDirectory)
        assertEquals(listOf(committed), reloaded.audio)
        assertFalse(
            MpdLiveCaptureIndex.containsUrl(
                reloaded,
                MpdLiveCaptureIndex.Stream.AUDIO,
                requireNotNull(missing.url)
            )
        )
    }

    @Test
    fun emptyIndex_isPublishedBeforeAnUncommittedFirstSegmentCanAppear() {
        val initial = MpdLiveCaptureIndex.loadOrMigrate(downloadDirectory)
        val manifest = downloadDirectory.resolve("mpd_capture.json")

        assertEquals(MpdLiveCaptureIndex.Snapshot(), initial)
        assertTrue(manifest.isFile)
        assertTrue(manifest.length() > 0L)

        val orphan = downloadDirectory.resolve("segment_0000000000.m4s")
        orphan.writeText("downloaded-before-index-commit", Charsets.UTF_8)

        val reloaded = MpdLiveCaptureIndex.loadOrMigrate(downloadDirectory)

        assertTrue(reloaded.video.isEmpty())
        assertTrue(
            MpdLiveCaptureIndex.filesInOrder(
                downloadDirectory,
                reloaded,
                MpdLiveCaptureIndex.Stream.VIDEO
            ).isEmpty()
        )
    }

    @Test
    fun loadOrMigrate_restoresCommittedBackupBeforeConsideringLegacyFiles() {
        val initial = MpdLiveCaptureIndex.loadOrMigrate(downloadDirectory)
        val manifest = downloadDirectory.resolve("mpd_capture.json")
        val backup = downloadDirectory.resolve("mpd_capture.json.bak")
        assertTrue(manifest.renameTo(backup))
        downloadDirectory.resolve("segment_0000000000.m4s")
            .writeText("uncommitted-orphan", Charsets.UTF_8)

        val reloaded = MpdLiveCaptureIndex.loadOrMigrate(downloadDirectory)

        assertEquals(initial, reloaded)
        assertTrue(reloaded.video.isEmpty())
        assertTrue(manifest.isFile)
        assertFalse(backup.exists())
    }

    @Test
    fun loadOrMigrate_normalizesManifestEntriesBySequence() {
        val firstFileName = "segment_0000000000.m4s"
        val secondFileName = "segment_0000000001.m4s"
        val manifest = JSONObject()
            .put("version", 1)
            .put(
                "video",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("sequence", 1L)
                            .put("url", "https://media.example/video-1.m4s")
                            .put("file", secondFileName)
                    )
                    .put(
                        JSONObject()
                            .put("sequence", 0L)
                            .put("url", "https://media.example/video-0.m4s")
                            .put("file", firstFileName)
                    )
            )
            .put("audio", JSONArray())
        downloadDirectory.resolve("mpd_capture.json")
            .writeText(manifest.toString(), Charsets.UTF_8)

        val reloaded = MpdLiveCaptureIndex.loadOrMigrate(downloadDirectory)

        assertEquals(listOf(0L, 1L), reloaded.video.map { it.sequence })
        assertEquals(listOf(firstFileName, secondFileName), reloaded.video.map { it.fileName })
        assertEquals(
            2L,
            MpdLiveCaptureIndex.nextEntry(
                reloaded,
                MpdLiveCaptureIndex.Stream.VIDEO,
                "https://media.example/video-2.m4s"
            ).sequence
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
