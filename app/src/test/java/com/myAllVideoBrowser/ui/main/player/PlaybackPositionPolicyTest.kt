package com.myAllVideoBrowser.ui.main.player

import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPositionPolicyTest {
    @Test
    fun mediaIdentity_ignoresTemporarySignatureChanges() {
        val first = VideoInfo(
            originalUrl = "https://example.com/watch/42",
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        url = "https://cdn.example/video.m3u8?token=old&expires=100&asset=42"
                    )
                )
            )
        )
        val refreshed = first.copy(
            formats = VideFormatEntityList(
                listOf(
                    VideoFormatEntity(
                        url = "https://cdn.example/video.m3u8?token=new&expires=200&asset=42"
                    )
                )
            )
        )

        assertEquals(
            PlaybackMediaIdentity.fromVideoInfo(first),
            PlaybackMediaIdentity.fromVideoInfo(refreshed)
        )
    }

    @Test
    fun sameMediaIdentity_survivesQualityAndTemporaryUrlChanges() {
        val first = PlaybackPositionKey.forMedia(
            source = VideoPlayerFragment.SOURCE_BROWSER,
            mediaUrl = "https://cdn.example/video.m3u8?token=old&quality=720",
            mediaIdentity = "page=https://example.com/watch/42|thumbnail=https://img.example/42.jpg|duration=60",
            mediaKind = PlaybackMediaKind.HLS
        )
        val refreshed = PlaybackPositionKey.forMedia(
            source = VideoPlayerFragment.SOURCE_BROWSER,
            mediaUrl = "https://cdn.example/video.m3u8?token=new&quality=1080",
            mediaIdentity = "page=https://example.com/watch/42|thumbnail=https://img.example/42.jpg|duration=60",
            mediaKind = PlaybackMediaKind.HLS
        )

        assertEquals(first, refreshed)
    }

    @Test
    fun differentVideosOnSamePage_doNotSharePosition() {
        val first = PlaybackPositionKey.forMedia(
            source = VideoPlayerFragment.SOURCE_BROWSER,
            mediaUrl = "https://cdn.example/first.mp4",
            mediaIdentity = "page=https://example.com/feed|media=https://cdn.example/first.mp4",
            mediaKind = PlaybackMediaKind.AUTO
        )
        val second = PlaybackPositionKey.forMedia(
            source = VideoPlayerFragment.SOURCE_BROWSER,
            mediaUrl = "https://cdn.example/second.mp4",
            mediaIdentity = "page=https://example.com/feed|media=https://cdn.example/second.mp4",
            mediaKind = PlaybackMediaKind.AUTO
        )

        assertTrue(first != second)
    }

    @Test
    fun localIdentity_prefersDownloadIdOverUri() {
        val first = PlaybackPositionKey.forMedia(
            source = VideoPlayerFragment.SOURCE_VIDEO_LIBRARY,
            mediaUrl = "content://media/external/video/media/7",
            mediaIdentity = "progress-uuid-a",
            mediaKind = PlaybackMediaKind.AUTO
        )
        val moved = PlaybackPositionKey.forMedia(
            source = VideoPlayerFragment.SOURCE_VIDEO_LIBRARY,
            mediaUrl = "file:///storage/emulated/0/Download/SurfSave/7.mp4",
            mediaIdentity = "progress-uuid-a",
            mediaKind = PlaybackMediaKind.AUTO
        )

        assertEquals("local-download:progress-uuid-a", first)
        assertEquals(first, moved)
    }

    @Test
    fun localIdentity_fallsBackToNormalizedUriWithoutDownloadId() {
        val key = PlaybackPositionKey.forMedia(
            source = VideoPlayerFragment.SOURCE_VIDEO_LIBRARY,
            mediaUrl = "content://media/external/video/media/7",
            mediaIdentity = "",
            mediaKind = PlaybackMediaKind.AUTO
        )

        assertEquals("local-uri:content://media/external/video/media/7", key)
    }

    @Test
    fun endMargin_isDynamicForShortVideos() {
        val tenSeconds = PlaybackPositionPolicy.endMarginMs(10_000L)
        val oneMinute = PlaybackPositionPolicy.endMarginMs(60_000L)
        val fiveMinutes = PlaybackPositionPolicy.endMarginMs(300_000L)

        assertEquals(1_000L, tenSeconds)
        assertEquals(3_000L, oneMinute)
        assertEquals(15_000L, fiveMinutes)
    }

    @Test
    fun shortVideo_keepsMiddlePosition() {
        val decision = PlaybackPositionPolicy.restore(
            SavedPlaybackPosition(30_000L, 60_000L, 1L),
            currentDurationMs = 60_000L
        )

        assertEquals(30_000L, decision.positionMs)
        assertTrue(!decision.shouldClear)
    }

    @Test
    fun shortVideo_clearsOnlyInsideDynamicMargin() {
        val nearEnd = PlaybackPositionPolicy.restore(
            SavedPlaybackPosition(59_000L, 60_000L, 1L),
            currentDurationMs = 60_000L
        )

        assertNull(nearEnd.positionMs)
        assertTrue(nearEnd.shouldClear)
    }

    @Test
    fun restore_clearsPositionsNearEnd() {
        val decision = PlaybackPositionPolicy.restore(
            SavedPlaybackPosition(118_000L, 120_000L, 1L),
            currentDurationMs = 120_000L
        )

        assertNull(decision.positionMs)
        assertTrue(decision.shouldClear)
    }

    @Test
    fun restore_keepsValidMiddlePosition() {
        val decision = PlaybackPositionPolicy.restore(
            SavedPlaybackPosition(45_000L, 120_000L, 1L),
            currentDurationMs = 120_000L
        )

        assertEquals(45_000L, decision.positionMs)
        assertTrue(!decision.shouldClear)
    }

    @Test
    fun restore_skipsDeclaredLiveWithoutClearingRecord() {
        val decision = PlaybackPositionPolicy.restore(
            SavedPlaybackPosition(45_000L, 120_000L, 1L),
            currentDurationMs = 0L,
            declaredLive = true
        )

        assertNull(decision.positionMs)
        assertTrue(!decision.shouldClear)
    }
}
