package com.myAllVideoBrowser.util.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A′ 回归网：只有「generic/html5 + 单条 + 普通 http(s) 媒体地址」才改盖 `DIRECT_HTTP`。
 *
 * 真实样本（任务日志 2026-10-11 01:52）：
 * - Hanime1 `22554`：`extractor=html5`、单条 format、`url=https://vdownload.hembed.com/….mp4`、
 *   `protocol=https`、`ext=.mp4`、`vcodec=null` → 应走 REGULAR 直链（带 Referer），
 *   而不是重新解析页面后用 `-f '1+bestaudio'` 去匹配。
 * - Rule34 `4391917`：`url=https://rule34video.com/get_file/….mp4` 同理。
 */
class PlainMediaStrategyPolicyTest {

    private fun decide(
        extractor: String? = "html5",
        formatCount: Int = 1,
        url: String? = "https://vdownload.hembed.com/abc/def.mp4?token=x",
        protocol: String? = "https",
        ext: String? = "mp4",
        vcodec: String? = null,
        acodec: String? = "none"
    ) = PlainMediaStrategyPolicy.shouldStampDirectHttp(
        extractor = extractor,
        formatCount = formatCount,
        url = url,
        protocol = protocol,
        ext = ext,
        vcodec = vcodec,
        acodec = acodec
    )

    @Test
    fun genericAndHtml5SinglePlainMediaBecomesDirectHttp() {
        assertTrue(decide(extractor = "html5"))
        assertTrue(decide(extractor = "HTML5MediaEmbed"))
        assertTrue(decide(extractor = "generic"))
        assertTrue(decide(extractor = "Generic", ext = null))
        // 没有 codec 字段（html5 的典型形态）不能阻止直链化：它恰恰是必须直链的样本。
        assertTrue(decide(vcodec = "null", acodec = "none"))
    }

    @Test
    fun nonGenericExtractorsStayOnYtDlp() {
        assertFalse(decide(extractor = "youtube"))
        assertFalse(decide(extractor = "Rule34Video"))
        assertFalse(decide(extractor = null))
        assertFalse(decide(extractor = ""))
    }

    @Test
    fun multiFormatResultsKeepTheFormatSelectorSemantics() {
        // 多格式说明 yt-dlp 结果本身带质量维度，`-f` 语义必须保留。
        assertFalse(decide(formatCount = 2))
        assertFalse(decide(url = "https://cdn.example/playlist.m3u8", ext = "m3u8"))
        assertFalse(decide(url = "https://cdn.example/manifest.mpd", ext = "mpd"))
        assertFalse(decide(url = "https://cdn.example/index.html", ext = "html"))
        assertFalse(decide(url = "https://cdn.example/stream", ext = ""))
    }

    @Test
    fun onlyPlainHttpProtocolsAreAccepted() {
        assertTrue(decide(protocol = "https"))
        assertTrue(decide(protocol = "http"))
        // protocol 缺失时靠 URL 与扩展名判定；清单协议一律不接管。
        assertTrue(decide(protocol = null))
        assertTrue(decide(protocol = ""))
        assertFalse(decide(protocol = "m3u8_native", ext = "mp4"))
        assertFalse(decide(protocol = "http_dash_segments", ext = "mp4"))
        assertFalse(decide(protocol = "mhtml", ext = "mp4"))
    }

    @Test
    fun videoOnlyAndAudioOnlyStayOnYtDlp() {
        // video-only：必须留在 yt-dlp 合并音轨，直链下载会得到无声视频。
        assertFalse(decide(vcodec = "avc1.640028", acodec = "none"))
        // audio-only：既有链路会 --extract-audio --audio-format mp3，直链会静默改变产物格式。
        assertFalse(decide(vcodec = "none", acodec = "mp4a.40.2"))
    }

    @Test
    fun nonHttpUrlsAreRejected() {
        assertFalse(decide(url = "rtmp://cdn.example/live"))
        assertFalse(decide(url = "data:video/mp4;base64,AAAA"))
        assertFalse(decide(url = null))
        assertFalse(decide(url = ""))
    }
}
