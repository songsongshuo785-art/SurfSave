package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadRequestTest {
    @Test
    fun mediaAttachment_preservesCallbackMetadataForCandidateCreation() {
        val request = BrowserDownloadRequest(
            url = "https://cdn.example/download?id=1",
            pageUrl = "https://page.example/watch/1",
            headers = mapOf("Referer" to "https://page.example/watch/1"),
            contentDisposition = "attachment; filename*=UTF-8''sample%20video.mp4",
            mimeType = "application/octet-stream",
            contentLength = 42L,
            suggestedFileName = "sample video.mp4"
        )

        assertTrue(request.isSupportedMedia())
        assertEquals(ContentType.VIDEO, request.mediaType())
        assertEquals("sample video", request.suggestedTitle())
        assertEquals("mp4", request.suggestedExtension())
        assertEquals(42L, request.contentLength)
    }

    @Test
    fun nonMediaAttachment_isNotClaimedByBrowserMediaPipeline() {
        val request = BrowserDownloadRequest(
            url = "https://cdn.example/download?id=2",
            pageUrl = null,
            headers = emptyMap(),
            contentDisposition = "attachment; filename=archive.zip",
            mimeType = "application/octet-stream",
            contentLength = -1L,
            suggestedFileName = "archive.zip"
        )

        assertFalse(request.isSupportedMedia())
        assertEquals(ContentType.OTHER, request.mediaType())
        assertEquals("archive.zip", request.safeFileName())
        assertEquals("application/zip", request.normalizedMimeType())
    }

    @Test
    fun systemHeaders_dropSecretsOutsideTheBrowserDownloadAllowList() {
        val request = BrowserDownloadRequest(
            url = "https://cdn.example/file.apk",
            pageUrl = "https://page.example",
            headers = linkedMapOf(
                "user-agent" to "SurfSave",
                "Referer" to "https://page.example",
                "Cookie" to "session=abc",
                "Authorization" to "secret",
                "X-Test" to "ignored"
            ),
            contentDisposition = null,
            mimeType = "application/vnd.android.package-archive",
            contentLength = 1L,
            suggestedFileName = "folder/unsafe:name.apk"
        )

        assertEquals("folder_unsafe_name.apk", request.safeFileName())
        assertEquals(
            mapOf(
                "User-Agent" to "SurfSave",
                "Referer" to "https://page.example",
                "Cookie" to "session=abc"
            ),
            request.allowedDownloadHeaders()
        )
    }

    @Test
    fun contentDisposition_fallsBackWithoutDependingOnPlatformGuessFileName() {
        val request = BrowserDownloadRequest(
            url = "https://download.example/download.php?id=7",
            pageUrl = "https://page.example/watch",
            headers = emptyMap(),
            contentDisposition =
                "attachment; filename=ignored.mp4; filename*=UTF-8'zh'%E6%B5%8B%E8%AF%95%2B%E8%A7%86%E9%A2%91.mp4",
            mimeType = "application/octet-stream",
            contentLength = 10L,
            suggestedFileName = "download.php"
        )

        assertEquals(ContentType.VIDEO, request.mediaType())
        assertEquals("测试+视频.mp4", request.safeFileName())
        assertEquals("测试+视频", request.suggestedTitle())
        assertEquals("mp4", request.suggestedExtension())
    }

    @Test
    fun quotedContentDisposition_preservesSemicolonAndRemovesPathAndControlCharacters() {
        val request = BrowserDownloadRequest(
            url = "https://download.example/file",
            pageUrl = null,
            headers = emptyMap(),
            contentDisposition = "attachment; filename=\"../folder/report;final\u0000.pdf\"; size=12",
            mimeType = "application/pdf",
            contentLength = 12L,
            suggestedFileName = null
        )

        assertEquals("_folder_report;final.pdf", request.safeFileName())
    }

    @Test
    fun directMediaTask_keepsOnlyExplicitBrowserDownloadHeaders() {
        val request = BrowserDownloadRequest(
            url = "https://media.example/movie.mp4",
            pageUrl = "https://page.example/watch",
            headers = linkedMapOf(
                "User-Agent" to "SurfSave",
                "Referer" to "https://page.example/watch",
                "Cookie" to "session=abc",
                "Authorization" to "secret",
                "X-Injected" to "value",
                "X-Newline" to "unsafe\r\nvalue"
            ),
            contentDisposition = "attachment; filename=movie.mp4",
            mimeType = "video/mp4",
            contentLength = 12L,
            suggestedFileName = "movie.mp4"
        )

        val info = requireNotNull(request.toDirectMediaVideoInfo())
        assertEquals(setOf("User-Agent", "Referer", "Cookie"), info.downloadUrls.single().headers.keys)
        assertEquals(info.downloadUrls.single().headers, info.formats.formats.single().httpHeaders)
    }

    @Test
    fun declaredAttachmentMime_addsUsableExtensionWhenServerProvidesNone() {
        val request = BrowserDownloadRequest(
            url = "https://download.example/file?id=7",
            pageUrl = null,
            headers = emptyMap(),
            contentDisposition = null,
            mimeType = "application/vnd.android.package-archive",
            contentLength = 1L,
            suggestedFileName = "TapTap"
        )

        assertEquals("TapTap.apk", request.safeFileName())
        assertEquals("application/vnd.android.package-archive", request.normalizedMimeType())
    }
}
