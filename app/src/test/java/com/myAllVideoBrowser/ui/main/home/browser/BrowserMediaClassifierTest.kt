package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun genericBinaryMime_keepsExplicitMp4Url() {
        assertEquals(
            ContentType.VIDEO,
            BrowserMediaClassifier.classify(
                "https://cdn.example/114835-720p.mp4?secure=temporary",
                "application/octet-stream"
            )
        )
    }

    @Test
    fun imageUrlOrMime_isClassifiedAsImageButNotPlaybackResource() {
        assertEquals(
            ContentType.IMAGE,
            BrowserMediaClassifier.classify("https://cdn.example/photo.webp?size=large")
        )
        assertEquals(
            ContentType.IMAGE,
            BrowserMediaClassifier.classify(
                "https://cdn.example/image?id=1",
                "image/jpeg"
            )
        )
        assertEquals(ContentType.IMAGE, BrowserMediaClassifier.classify("https://cdn.example/art.svg"))
        assertFalse(BrowserMediaClassifier.isLikelyPlaybackResource("https://cdn.example/photo.webp"))
    }

    @Test
    fun opaqueDownloadUrl_usesMediaFilenameButRejectsNonMediaAttachment() {
        assertEquals(
            ContentType.VIDEO,
            BrowserMediaClassifier.classify(
                url = "https://cdn.example/download?id=1",
                contentType = "application/octet-stream",
                contentDisposition = "attachment; filename*=UTF-8''sample%20video.mp4"
            )
        )
        assertEquals(
            ContentType.OTHER,
            BrowserMediaClassifier.classify(
                url = "https://cdn.example/download?id=2",
                contentType = "application/octet-stream",
                contentDisposition = "attachment; filename=archive.zip"
            )
        )
    }

    @Test
    fun unknownEndpointExtension_doesNotHideMediaAttachmentFilename() {
        assertEquals(
            ContentType.VIDEO,
            BrowserMediaClassifier.classify(
                url = "https://cdn.example/download.php?id=1",
                contentType = "application/octet-stream",
                contentDisposition = "attachment; filename=\"movie;final.mp4\""
            )
        )
        assertEquals(
            ContentType.M3U8,
            BrowserMediaClassifier.classify(
                url = "https://cdn.example/playlist.php?id=1",
                contentType = "application/octet-stream",
                contentDisposition = "attachment; filename*=UTF-8''master.m3u8"
            )
        )
    }

    @Test
    fun segmentAttachment_isNotPromotedToStandaloneMedia() {
        assertEquals(
            ContentType.OTHER,
            BrowserMediaClassifier.classify(
                url = "https://cdn.example/download.php?id=1",
                contentType = "video/mp2t",
                contentDisposition = "attachment; filename=segment.ts"
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

    @Test
    fun playbackSafety_isBroaderThanDetectionClassification() {
        assertTrue(BrowserMediaClassifier.isLikelyPlaybackResource("https://cdn.example/5.ts"))
        assertTrue(BrowserMediaClassifier.isLikelyPlaybackResource("https://cdn.example/5.m4s"))
        assertTrue(
            BrowserMediaClassifier.isLikelyPlaybackResource(
                "https://cdn.example/play?id=1",
                "audio/aac,*/*"
            )
        )
        assertFalse(
            BrowserMediaClassifier.isLikelyPlaybackResource(
                "https://cdn.example/app.js",
                "application/javascript"
            )
        )
    }
}
