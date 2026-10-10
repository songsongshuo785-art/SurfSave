package com.myAllVideoBrowser.util.site_adapters

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.util.media.DownloadStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAdapterFormatMergeTest {

    private val urlIdentity: (String?) -> String = { it?.substringBefore('?')?.trimEnd('/').orEmpty() }
    /** 与 `FormatIdentity` 同构：直链看 URL，yt-dlp 格式看 extractorInputUrl + formatId。 */
    private val identityOf: (VideoFormatEntity) -> String = { format ->
        if (format.downloadStrategy == DownloadStrategy.DIRECT_HTTP.name) {
            "direct|${format.url?.substringBefore('?')}"
        } else {
            "ytdlp|${format.extractorInputUrl}|${format.formatId}"
        }
    }

    @Test
    fun addsNewQualitiesWithoutTouchingExistingFormats() {
        val existing = listOf(ytDlpFormat(url = "https://cdn.example/480.mp4", formatId = "18"))
        val incoming = listOf(
            directFormat("https://cdn.example/720.mp4", label = "720p"),
            directFormat("https://cdn.example/1080.mp4", label = "1080p")
        )

        val merged = SiteAdapterFormatMerge.merge(existing, incoming, urlIdentity, identityOf)

        assertTrue(merged.changed)
        assertEquals(2, merged.addedCount)
        assertEquals(0, merged.labelUpdateCount)
        assertEquals(3, merged.formats.size)
        // 既有行必须原样保留（策略/身份不变）。
        assertEquals(existing.single(), merged.formats.first())
    }

    @Test
    fun sameUrlOnlyEnrichesTheLabelAndKeepsTheExistingStrategy() {
        // 正在播放的那条清晰度可能已经被 yt-dlp 记录过（策略 YTDLP），站点清单里则是直链（DIRECT）。
        // 身份不同 ⇒ 不能新增第二行，只能把站点标签补上去。
        val existing = listOf(
            ytDlpFormat(url = "https://cdn.example/1080.mp4", formatId = "137", formatNote = null)
        )
        val incoming = listOf(directFormat("https://cdn.example/1080.mp4", label = "1080p"))

        val merged = SiteAdapterFormatMerge.merge(existing, incoming, urlIdentity, identityOf)

        assertTrue(merged.changed)
        assertEquals(0, merged.addedCount)
        assertEquals(1, merged.labelUpdateCount)
        assertEquals(1, merged.formats.size)
        assertEquals("1080p", merged.formats.single().formatNote)
        assertEquals(
            DownloadStrategy.YTDLP_FORMAT.name,
            merged.formats.single().downloadStrategy
        )
    }

    @Test
    fun sameUrlWithATrailingSlashOrSignatureIsStillTheSameResource() {
        val existing = listOf(directFormat("https://cdn.example/1080.mp4/?br=1&hash=a", label = null))
        val incoming = listOf(directFormat("https://cdn.example/1080.mp4", label = "1080p"))

        val merged = SiteAdapterFormatMerge.merge(existing, incoming, urlIdentity, identityOf)

        assertEquals(1, merged.formats.size)
        assertEquals(1, merged.labelUpdateCount)
        assertEquals("1080p", merged.formats.single().formatNote)
    }

    @Test
    fun identicalIdentityIsNotAddedTwiceEvenWhenUrlChanges() {
        // yt-dlp 格式的身份 = extractorInputUrl + formatId，与 CDN 地址无关：
        // 同一格式带两次签名 URL 时不能变成两行。
        val incoming = listOf(
            ytDlpFormat(url = "https://cdn-a.example/137.mp4", formatId = "137"),
            ytDlpFormat(url = "https://cdn-b.example/137.mp4", formatId = "137")
        )

        val merged = SiteAdapterFormatMerge.merge(emptyList(), incoming, urlIdentity, identityOf)

        assertEquals(1, merged.addedCount)
        assertEquals(1, merged.formats.size)
    }

    @Test
    fun anUnchangedListReportsNoChangeSoTheUiIsNotRebuilt() {
        val existing = listOf(directFormat("https://cdn.example/720.mp4", label = "720p"))
        val incoming = listOf(directFormat("https://cdn.example/720.mp4", label = "720p"))

        val merged = SiteAdapterFormatMerge.merge(existing, incoming, urlIdentity, identityOf)

        assertFalse(merged.changed)
        assertEquals(existing, merged.formats)
    }

    @Test
    fun aMissingLabelNeverOverwritesTheSiteLabel() {
        val existing = listOf(
            directFormat("https://cdn.example/720.mp4", label = "720p").copy(
                format = "MP4 720p"
            )
        )
        val incoming = listOf(directFormat("https://cdn.example/720.mp4", label = ""))

        val merged = SiteAdapterFormatMerge.merge(existing, incoming, urlIdentity, identityOf)

        assertFalse(merged.changed)
        assertEquals("720p", merged.formats.single().formatNote)
        assertEquals("MP4 720p", merged.formats.single().format)
    }

    private fun directFormat(url: String, label: String?): VideoFormatEntity = VideoFormatEntity(
        formatId = "kvs-0",
        format = label,
        formatNote = label,
        ext = "mp4",
        url = url,
        downloadStrategy = DownloadStrategy.DIRECT_HTTP.name
    )

    private fun ytDlpFormat(
        url: String,
        formatId: String,
        formatNote: String? = "360p"
    ): VideoFormatEntity = VideoFormatEntity(
        formatId = formatId,
        formatNote = formatNote,
        ext = "mp4",
        url = url,
        downloadStrategy = DownloadStrategy.YTDLP_FORMAT.name,
        extractorInputUrl = "https://site.example/video/4391917/"
    )
}
