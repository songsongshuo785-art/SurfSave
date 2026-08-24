package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserMediaClassifierTest {
    @Test
    fun noExtensionResponse_usesMediaContentType() {
        assertEquals(
            ContentType.M3U8,
            BrowserMediaClassifier.classify(
                "https://cdn.example/api/play?id=1",
                "application/vnd.apple.mpegurl; charset=utf-8"
            )
        )
        assertEquals(
            ContentType.MPD,
            BrowserMediaClassifier.classify(
                "https://cdn.example/api/play?id=2",
                "application/dash+xml"
            )
        )
    }

    @Test
    fun ambiguousResponse_usesManifestBodyHint() {
        assertEquals(
            ContentType.M3U8,
            BrowserMediaClassifier.classify(
                "https://cdn.example/api/play",
                "application/octet-stream",
                "\uFEFF#EXTM3U\n#EXT-X-VERSION:3"
            )
        )
        assertEquals(
            ContentType.MPD,
            BrowserMediaClassifier.classify(
                "https://cdn.example/api/play",
                "text/plain",
                "<?xml version=\"1.0\"?><MPD type=\"static\">"
            )
        )
    }

    @Test
    fun segmentsAndBlob_areNotExposedAsStandaloneVideos() {
        assertEquals(
            ContentType.OTHER,
            BrowserMediaClassifier.classify("https://cdn.example/segment.ts", "video/mp2t")
        )
        assertEquals(
            ContentType.OTHER,
            BrowserMediaClassifier.classify("blob:https://example.com/id", "video/mp4")
        )
    }
}
