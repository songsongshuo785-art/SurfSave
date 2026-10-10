package com.myAllVideoBrowser.util.site_adapters

import com.myAllVideoBrowser.util.media.DownloadStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KvsFlashvarsAdapterTest {

    @Test
    fun parse_keepsEveryFlashvarsQualityInPageOrder() {
        val payload = payload(
            "https://rule34video.com/get_file/1/480.mp4/?br=800&hash=a" to "480p",
            "https://rule34video.com/get_file/1/720.mp4/?br=2500&hash=b" to "720p",
            "https://rule34video.com/get_file/1/1080.mp4/?br=5200&hash=c" to "1080p"
        )

        val parsed = requireNotNull(KvsFlashvarsAdapter.parse(payload))

        assertEquals(
            listOf(480, 720, 1080),
            parsed.items.map { Regex("""/(\d+)\.mp4""").find(it.url)!!.groupValues[1].toInt() }
        )
        assertEquals(listOf("480p", "720p", "1080p"), parsed.items.map { it.label })
        assertTrue(parsed.items.all { it.kind == KvsFlashvarsAdapter.ResourceKind.DIRECT })
        assertTrue(parsed.items.all { it.extension == "mp4" })
    }

    @Test
    fun parse_dropsQualitiesWhoseResourceTypeCannotBeDetermined() {
        val payload = """
            {"status":"ok","pageUrl":"https://site.example/video/1/","items":[
              {"url":"https://cdn.example/get_file/1/1080.mp4","label":"1080p"},
              {"url":"https://cdn.example/player/embed/1","label":"Unknown"},
              {"url":"https://cdn.example/thumb.jpg","label":"Thumb"}
            ]}
        """.trimIndent()

        val parsed = requireNotNull(KvsFlashvarsAdapter.parse(payload))

        assertEquals(1, parsed.items.size)
        assertEquals("https://cdn.example/get_file/1/1080.mp4", parsed.items.single().url)
        // 未判定类型的条目必须被丢弃并计数：绝不猜成 mp4。
        assertEquals(2, parsed.skippedUnknownTypeCount)
        assertEquals(0, parsed.invalidUrlCount)
    }

    @Test
    fun parse_classifiesManifestsAsHlsAndDash() {
        val payload = """
            {"status":"ok","pageUrl":"https://site.example/video/1/","items":[
              {"url":"https://cdn.example/hls/master.m3u8","label":"HLS"},
              {"url":"https://cdn.example/dash/manifest.mpd","label":"DASH"}
            ]}
        """.trimIndent()

        val parsed = requireNotNull(KvsFlashvarsAdapter.parse(payload))

        assertEquals(KvsFlashvarsAdapter.ResourceKind.HLS, parsed.items[0].kind)
        assertEquals(KvsFlashvarsAdapter.ResourceKind.DASH, parsed.items[1].kind)
        // 清单落地后是分片拼接的容器，与 VideoServiceSuperX 的既有取值一致。
        assertEquals("mp4", parsed.items[0].extension)
        assertEquals("mp4", parsed.items[1].extension)
    }

    @Test
    fun parse_returnsNullWhenThePageIsNotKvs() {
        assertNull(KvsFlashvarsAdapter.parse("""{"status":"not-kvs"}"""))
        assertNull(KvsFlashvarsAdapter.parse("""{"status":"no-flashvars"}"""))
        assertNull(KvsFlashvarsAdapter.parse(""))
        assertNull(KvsFlashvarsAdapter.parse("null"))
        assertNull(KvsFlashvarsAdapter.parse("not-json"))
    }

    @Test
    fun parse_unescapesHtmlEntitiesAndSkipsRelativeUrls() {
        val payload = """
            {"status":"ok","pageUrl":"https://site.example/video/1/","items":[
              {"url":"https://cdn.example/1080.mp4?br=1&amp;hash=z","label":"1080p &amp; HD"},
              {"url":"/relative/720.mp4","label":"720p"}
            ]}
        """.trimIndent()

        val parsed = requireNotNull(KvsFlashvarsAdapter.parse(payload))

        assertEquals("https://cdn.example/1080.mp4?br=1&hash=z", parsed.items.single().url)
        assertEquals("1080p & HD", parsed.items.single().label)
        assertEquals(1, parsed.invalidUrlCount)
    }

    @Test
    fun parse_reportsAnEmptyListInsteadOfInventingQualities() {
        val payload = """{"status":"ok","pageUrl":"https://site.example/video/1/","items":[]}"""

        val parsed = requireNotNull(KvsFlashvarsAdapter.parse(payload))

        assertTrue(parsed.items.isEmpty())
        assertEquals(0, parsed.skippedUnknownTypeCount)
    }

    @Test
    fun toVideoFormats_stampsDirectQualitiesWithoutInventingResolutionOrHeaders() {
        val pageUrl = "https://rule34video.com/video/4391917/2-hours-of-eve/"
        val parsed = requireNotNull(
            KvsFlashvarsAdapter.parse(
                payload(
                    "https://rule34video.com/get_file/1/720.mp4/?br=2500&hash=b" to "720p",
                    "https://rule34video.com/get_file/1/1080.mp4/?br=5200&hash=c" to "1080p"
                )
            )
        )

        val formats = KvsFlashvarsAdapter.toVideoFormats(
            payload = parsed,
            pageUrl = pageUrl,
            userAgent = "TestAgent/1.0",
            cookie = "session=abc"
        )

        assertEquals(2, formats.size)
        formats.forEach { format ->
            assertEquals(DownloadStrategy.DIRECT_HTTP.name, format.downloadStrategy)
            assertEquals(pageUrl, format.sourcePageUrl)
            assertEquals("mp4", format.ext)
            // 不凭空推断分辨率：height 保持 0，标签只来自站点自己的 `_text`。
            assertEquals(0, format.height)
            // 同源 + 页面 Referer/UA/Cookie 都必须带上，否则 CDN 会拒绝下载。
            assertEquals(pageUrl, format.httpHeaders?.get("Referer"))
            assertEquals("TestAgent/1.0", format.httpHeaders?.get("User-Agent"))
            assertEquals("session=abc", format.httpHeaders?.get("Cookie"))
        }
        assertEquals("720p", formats[0].formatNote)
        assertEquals("1080p", formats[1].formatNote)
    }

    @Test
    fun toVideoFormats_doesNotLeakPageCookiesToAnotherHost() {
        val pageUrl = "https://rule34video.com/video/4391917/2-hours-of-eve/"
        val parsed = requireNotNull(
            KvsFlashvarsAdapter.parse(
                """{"status":"ok","pageUrl":"$pageUrl","items":[
                     {"url":"https://cdn.other.example/1080.mp4","label":"1080p"}
                   ]}"""
            )
        )

        val format = KvsFlashvarsAdapter.toVideoFormats(
            payload = parsed,
            pageUrl = pageUrl,
            userAgent = "TestAgent/1.0",
            cookie = "session=abc"
        ).single()

        assertEquals(pageUrl, format.httpHeaders?.get("Referer"))
        assertNull(format.httpHeaders?.get("Cookie"))
    }

    @Test
    fun toVideoFormats_stampsManifestQualitiesWithSuperXStrategies() {
        val pageUrl = "https://site.example/video/1/"
        val parsed = requireNotNull(
            KvsFlashvarsAdapter.parse(
                """{"status":"ok","pageUrl":"$pageUrl","items":[
                     {"url":"https://cdn.example/hls/master.m3u8","label":"HLS"},
                     {"url":"https://cdn.example/dash/manifest.mpd","label":"DASH"}
                   ]}"""
            )
        )

        val formats = KvsFlashvarsAdapter.toVideoFormats(parsed, pageUrl, "TestAgent/1.0", null)

        assertEquals(DownloadStrategy.HLS_MANIFEST.name, formats[0].downloadStrategy)
        assertEquals("m3u8_native", formats[0].protocol)
        assertEquals(formats[0].url, formats[0].manifestUrl)
        assertEquals(formats[0].url, formats[0].manifestRequestUrl)
        assertEquals(DownloadStrategy.DASH_MANIFEST.name, formats[1].downloadStrategy)
        assertEquals("http_dash_segments", formats[1].protocol)
    }

    /** 构造与脚本回包同形的 JSON（video_url + video_alt_url..5，按传入顺序）。 */
    private fun payload(vararg items: Pair<String, String>): String {
        val encoded = items.joinToString(",") { (url, label) ->
            """{"url":"$url","label":"$label"}"""
        }
        return """{"status":"ok","pageUrl":"https://rule34video.com/video/4391917/2-hours-of-eve/","items":[$encoded]}"""
    }
}
