package com.myAllVideoBrowser.contentblock

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserResourceTypeResolverTest {
    @Test
    fun mainFrame_isAlwaysDocument() {
        assertEquals(
            BrowserResourceType.DOCUMENT,
            BrowserResourceTypeResolver.resolve(
                "https://example.org/video.mp4",
                mapOf("Accept" to "video/mp4"),
                isMainFrame = true
            )
        )
    }

    @Test
    fun fetchDestination_hasPriorityOverExtension() {
        assertEquals(
            BrowserResourceType.SCRIPT,
            BrowserResourceTypeResolver.resolve(
                "https://example.org/video.mp4",
                mapOf("sec-fetch-dest" to "script"),
                isMainFrame = false
            )
        )
    }

    @Test
    fun acceptAndExtension_conservativelyRecognizeMedia() {
        assertEquals(
            BrowserResourceType.MEDIA,
            BrowserResourceTypeResolver.resolve(
                "https://cdn.example.org/play?id=1",
                mapOf("ACCEPT" to "application/vnd.apple.mpegurl"),
                isMainFrame = false
            )
        )
        assertEquals(
            BrowserResourceType.MEDIA,
            BrowserResourceTypeResolver.resolve(
                "https://cdn.example.org/chunk.m4s?token=redacted",
                emptyMap(),
                isMainFrame = false
            )
        )
    }

    @Test
    fun ambiguousRequest_remainsUnknown() {
        assertEquals(
            BrowserResourceType.UNKNOWN,
            BrowserResourceTypeResolver.resolve(
                "https://example.org/api/data",
                mapOf("Accept" to "*/*"),
                isMainFrame = false
            )
        )
    }

    @Test
    fun emptyFetchDestination_isXmlHttpRequest() {
        assertEquals(
            BrowserResourceType.XML_HTTP_REQUEST,
            BrowserResourceTypeResolver.resolve(
                "https://example.org/api/ads",
                mapOf("sEc-FeTcH-DeSt" to "EmPtY", "Accept" to "*/*"),
                isMainFrame = false
            )
        )
    }

    @Test
    fun explicitMediaAccept_hasPriorityOverEmptyFetchDestination() {
        assertEquals(
            BrowserResourceType.MEDIA,
            BrowserResourceTypeResolver.resolve(
                "https://example.org/api/stream",
                mapOf(
                    "Sec-Fetch-Dest" to "empty",
                    "Accept" to "application/vnd.apple.mpegurl"
                ),
                isMainFrame = false
            )
        )
    }
}
