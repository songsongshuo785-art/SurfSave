package com.myAllVideoBrowser.data.repository

import android.app.Application
import com.myAllVideoBrowser.ui.main.player.SavedPlaybackPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackPositionStoreTest {
    @Test
    fun saveReadAndRemove_roundTripsPosition() {
        val key = "test-${System.nanoTime()}"
        val store = PlaybackPositionStore(org.robolectric.RuntimeEnvironment.getApplication())

        store.save(key, positionMs = 42_000L, durationMs = 120_000L, nowMs = 100L)

        assertEquals(
            SavedPlaybackPosition(42_000L, 120_000L, 100L),
            store.get(key)
        )
        store.remove(key)
        assertNull(store.get(key))
    }

    /**
     * P1 → P2 覆盖回归：同一媒体跨会话/不同时间点必须能反复覆写，而不是像旧实现那样
     * 停在第一次写入的值上。
     */
    @Test
    fun saveTwice_keepsLatestPosition() {
        val key = "test-overwrite-${System.nanoTime()}"
        val store = PlaybackPositionStore(org.robolectric.RuntimeEnvironment.getApplication())

        store.save(key, positionMs = 60_000L, durationMs = 120_000L, nowMs = 100L)
        store.save(key, positionMs = 90_000L, durationMs = 120_000L, nowMs = 200L)

        assertEquals(
            SavedPlaybackPosition(90_000L, 120_000L, 200L),
            store.get(key)
        )
        store.remove(key)
    }

    @Test
    fun shortId_isStableAndDoesNotLeakMediaKey() {
        val store = PlaybackPositionStore(org.robolectric.RuntimeEnvironment.getApplication())
        val mediaKey =
            "online:page=https://example.com/secret/watch/42|thumbnail=https://img.example/42.jpg|duration=60"

        val shortId = store.shortId(mediaKey)

        assertEquals(8, shortId.length)
        assertEquals(shortId, store.shortId(mediaKey))
        assertEquals(false, shortId.contains("secret"))
        assertEquals("", store.shortId(""))
    }
}
