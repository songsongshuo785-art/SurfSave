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
}
