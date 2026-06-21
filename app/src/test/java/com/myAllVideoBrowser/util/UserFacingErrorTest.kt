package com.myAllVideoBrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UserFacingErrorTest {

    @Test
    fun classify_mapsNetworkFailures() {
        assertEquals(UserFacingError.Category.NETWORK, UserFacingError.classify(UnknownHostException("dns")))
        assertEquals(UserFacingError.Category.NETWORK, UserFacingError.classify(SocketTimeoutException("timeout")))
    }

    @Test
    fun classify_mapsAuthAndExtractorFailures() {
        assertEquals(
            UserFacingError.Category.AUTH_OR_COOKIE,
            UserFacingError.classify("HTTP 403 Forbidden")
        )
        assertEquals(
            UserFacingError.Category.YTDLP_OUTDATED,
            UserFacingError.classify("yt-dlp extractor failed, please update")
        )
    }

    @Test
    fun cleanDetail_redactsSensitiveValues() {
        val cleaned = UserFacingError.cleanDetail(
            "https://example.com/video.mp4?token=secret&quality=720 Cookie: session=secret"
        )

        assertFalse(cleaned.contains("token=secret"))
        assertFalse(cleaned.contains("session=secret"))
    }

    @Test
    fun classify_internalDependencyErrorFallsBackToUnknown() {
        assertEquals(
            UserFacingError.Category.UNKNOWN,
            UserFacingError.classify("IllegalStateException: Unable to load PublicSuffixDatabase.list resource")
        )
    }
}
