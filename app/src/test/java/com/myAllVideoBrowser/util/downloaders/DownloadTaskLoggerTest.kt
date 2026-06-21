package com.myAllVideoBrowser.util.downloaders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskLoggerTest {

    @Test
    fun redact_hidesSensitiveHeadersAndQueryParams() {
        val raw = """
            Cookie: session=secret
            Authorization: Bearer abc
            https://example.com/video.mp4?token=secret&quality=720&signature=abc
        """.trimIndent()

        val redacted = DownloadTaskLogger.redact(raw)

        assertFalse(redacted.contains("session=secret"))
        assertFalse(redacted.contains("Bearer abc"))
        assertFalse(redacted.contains("token=secret"))
        assertFalse(redacted.contains("signature=abc"))
        assertTrue(redacted.contains("quality=720"))
        assertTrue(redacted.contains("<redacted>"))
    }
}
