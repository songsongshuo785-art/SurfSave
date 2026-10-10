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
    fun classify_tlsFailureWinsOverTheGenericYtDlpKeywords() {
        // 真实报错（任务日志 2026-10-10 02:25）：yt-dlp 的 SSL 报错正文里一定含 "yt-dlp"
        // （问题模板链接 + `yt-dlp -U` 提示），修复前会先命中 YTDLP_OUTDATED，把 TLS 问题
        // 说成"解析器可能过旧"。
        val sslFailure =
            "ERROR: [generic] watch?v=13080: Unable to download webpage: " +
                "[SSL: UNEXPECTED_EOF_WHILE_READING] EOF occurred in violation of protocol " +
                "(_ssl.c:1010) (caused by SSLError('[SSL: UNEXPECTED_EOF_WHILE_READING] EOF " +
                "occurred in violation of protocol (_ssl.c:1010)')); please report this issue on  " +
                "https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the appropriate issue " +
                "template. Confirm you are on the latest version using  yt-dlp -U"
        assertEquals(UserFacingError.Category.NETWORK, UserFacingError.classify(sslFailure))
        assertEquals(
            UserFacingError.Category.NETWORK,
            UserFacingError.classify("ERROR: [generic] Unable to download webpage: certificate verify failed")
        )
    }

    @Test
    fun classify_cloudflareStillWinsOverEverythingElse() {
        // 同一条 Cloudflare 报错也含 "yt-dlp"，ANTI_BOT 必须保持最高优先级。
        val cloudflare =
            "ERROR: [generic] Got HTTP Error 403 caused by Cloudflare anti-bot challenge; see  " +
                "https://github.com/yt-dlp/yt-dlp#impersonation  for how to install the required " +
                "impersonation dependency, and try again with  --extractor-args \"generic:impersonate\""
        assertEquals(UserFacingError.Category.ANTI_BOT, UserFacingError.classify(cloudflare))
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
