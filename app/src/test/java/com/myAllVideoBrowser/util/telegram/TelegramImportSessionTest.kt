package com.myAllVideoBrowser.util.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramImportSessionTest {
    @Test
    fun publishesOnlyCurrentGenerationAndConsumesAutoOpenOnce() {
        val session = TelegramImportSession(autoOpenRequested = true)
        val firstUrl = "https://t.me/cctv1/17551"
        session.beginPage(1L, firstUrl)
        val stale = requireNotNull(session.startResolution(1L, firstUrl))

        val secondUrl = "https://t.me/cctv1/17552"
        session.beginPage(2L, secondUrl)
        assertFalse(session.publishIfCurrent(stale, 2L, secondUrl))

        val current = requireNotNull(session.startResolution(2L, secondUrl))
        assertTrue(session.publishIfCurrent(current, 2L, secondUrl))
        assertNull(session.startResolution(2L, secondUrl))
        assertTrue(session.consumeAutoOpen())
        assertFalse(session.consumeAutoOpen())
    }

    @Test
    fun leavingTelegramCancelsPendingImportAutoOpen() {
        val session = TelegramImportSession(autoOpenRequested = true)
        session.beginPage(1L, "https://t.me/cctv1/17551")
        assertTrue(session.isMediaImportPage())

        session.beginPage(2L, "https://example.com/")

        assertFalse(session.isMediaImportPage())
        assertFalse(session.consumeAutoOpen())
    }

    @Test
    fun normalBrowseNeverAutoOpensDetails() {
        val session = TelegramImportSession(autoOpenRequested = false)
        session.beginPage(1L, "https://t.me/cctv1/17551")

        assertNotNull(session.startResolution(1L, "https://t.me/cctv1/17551"))
        assertFalse(session.isMediaImportPage())
        assertFalse(session.consumeAutoOpen())
    }
}
