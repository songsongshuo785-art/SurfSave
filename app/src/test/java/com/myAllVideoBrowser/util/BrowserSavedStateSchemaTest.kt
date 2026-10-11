package com.myAllVideoBrowser.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSavedStateSchemaTest {

    @Test
    fun `recognizes the current format`() {
        assertTrue(BrowserSavedStateSchema.isCurrentFormat(BrowserSavedStateSchema.CURRENT))
    }

    @Test
    fun `treats a missing marker as a legacy saved state`() {
        // Bundle.getInt(key, 0) returns the default when the marker is absent.
        assertFalse(BrowserSavedStateSchema.isCurrentFormat(0))
        assertFalse(BrowserSavedStateSchema.isCurrentFormat(-1))
    }

    @Test
    fun `tolerates a newer marker from a future build`() {
        assertTrue(BrowserSavedStateSchema.isCurrentFormat(BrowserSavedStateSchema.CURRENT + 1))
    }

    @Test
    fun `marker key does not collide with the snapshot key`() {
        assertFalse(BrowserSavedStateSchema.KEY == "webview_state_snapshot")
        assertTrue(BrowserSavedStateSchema.KEY.isNotBlank())
    }
}
