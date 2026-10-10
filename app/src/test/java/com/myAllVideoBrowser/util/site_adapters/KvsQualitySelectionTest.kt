package com.myAllVideoBrowser.util.site_adapters

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.util.VideoFormatUi
import com.myAllVideoBrowser.util.downloaders.DownloadEngineKind
import com.myAllVideoBrowser.util.downloaders.DownloadEngineKindResolver
import com.myAllVideoBrowser.util.downloaders.SelectedFormatSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 把 A 的产物走一遍真实链路的关键判断：
 * 候选列表里的多清晰度 → 用户选择 → 入队（单条 format）→ 执行器 → 实际下载的 URL。
 *
 * 真机验收还需要确认文件本身的分辨率，但“选中哪条 + 交给哪个引擎 + 下载哪个 URL”
 * 这三件事必须在单测里被钉死，否则“列表有 4 个清晰度但下载到的永远是同一个”这种回归测不出来。
 */
class KvsQualitySelectionTest {

    private val pageUrl = "https://rule34video.com/video/4391917/2-hours-of-eve/"

    @Test
    fun choosingAQualityDownloadsExactlyThatUrl() {
        val candidate = candidateWithKvsQualities()

        val sevenTwenty = selectAndBuildQueuePayload(candidate, label = "720p")
        assertEquals(
            "https://rule34video.com/get_file/1/720.mp4/?br=2500&hash=b",
            sevenTwenty.formats.formats.single().url
        )
        assertEquals(DownloadEngineKind.REGULAR, DownloadEngineKindResolver.kindOf(sevenTwenty))

        val tenEighty = selectAndBuildQueuePayload(candidate, label = "1080p")
        assertEquals(
            "https://rule34video.com/get_file/1/1080.mp4/?br=5200&hash=c",
            tenEighty.formats.formats.single().url
        )
        assertEquals(DownloadEngineKind.REGULAR, DownloadEngineKindResolver.kindOf(tenEighty))
    }

    @Test
    fun requestHeadersSurviveSelectionSoTheCdnActuallyServesTheChosenFile() {
        val candidate = candidateWithKvsQualities()

        val payload = selectAndBuildQueuePayload(candidate, label = "1080p")
        val selected = SelectedFormatSelector.select(payload, "test")

        assertEquals(pageUrl, selected.httpHeaders?.get("Referer"))
        assertEquals("TestAgent/1.0", selected.httpHeaders?.get("User-Agent"))
        assertEquals("session=abc", selected.httpHeaders?.get("Cookie"))
        assertEquals("mp4", selected.ext)
    }

    @Test
    fun everyQualityKeepsItsOwnSelectionIdentity() {
        val candidate = candidateWithKvsQualities()

        val keys = candidate.formats.formats
            .map { VideoFormatUi.selectionKey(candidate, it) }
            .toSet()

        assertEquals(candidate.formats.formats.size, keys.size)
        assertNotEquals(keys.first(), keys.last())
    }

    @Test
    fun theSelectedQualityIsResolvableByItsSelectionKey() {
        val candidate = candidateWithKvsQualities()
        val sevenTwenty = candidate.formats.formats.first { it.formatNote == "720p" }
        val key = VideoFormatUi.selectionKey(candidate, sevenTwenty)

        assertEquals(sevenTwenty.id, VideoFormatUi.findFormat(candidate, key)?.id)
    }

    @Test
    fun manifestQualitiesGoToTheSuperXEngine() {
        val candidate = candidateFromPayload(
            """{"status":"ok","pageUrl":"$pageUrl","items":[
                 {"url":"https://cdn.example/hls/720.m3u8","label":"720p"},
                 {"url":"https://cdn.example/hls/1080.m3u8","label":"1080p"}
               ]}"""
        )

        val payload = selectAndBuildQueuePayload(candidate, label = "1080p")

        assertEquals(DownloadEngineKind.SUPERX, DownloadEngineKindResolver.kindOf(payload))
        assertEquals(
            "https://cdn.example/hls/1080.m3u8",
            SelectedFormatSelector.select(payload, "test").url
        )
    }

    private fun candidateWithKvsQualities(): VideoInfo = candidateFromPayload(
        """{"status":"ok","pageUrl":"$pageUrl","items":[
             {"url":"https://rule34video.com/get_file/1/480.mp4/?br=800&hash=a","label":"480p"},
             {"url":"https://rule34video.com/get_file/1/720.mp4/?br=2500&hash=b","label":"720p"},
             {"url":"https://rule34video.com/get_file/1/1080.mp4/?br=5200&hash=c","label":"1080p"}
           ]}"""
    )

    /** 站点适配器挂载完成后的候选：与网络嗅探到的候选合并后，多条清晰度挂在这一条候选下。 */
    private fun candidateFromPayload(rawPayload: String): VideoInfo {
        val parsed = requireNotNull(KvsFlashvarsAdapter.parse(rawPayload))
        val sniffed = VideoFormatEntity(
            formatId = "0",
            formatNote = null,
            ext = "mp4",
            url = "https://rule34video.com/get_file/1/480.mp4/?br=800&hash=a"
        )
        val formats = SiteAdapterFormatMerge.merge(
            existing = listOf(sniffed),
            incoming = KvsFlashvarsAdapter.toVideoFormats(
                payload = parsed,
                pageUrl = pageUrl,
                userAgent = "TestAgent/1.0",
                cookie = "session=abc"
            ),
            urlIdentity = { it?.substringBefore('?')?.trimEnd('/').orEmpty() },
            identityOf = { format ->
                if (format.downloadStrategy == null) {
                    "legacy|${format.url?.substringBefore('?')}"
                } else {
                    "${format.downloadStrategy}|${format.url?.substringBefore('?')}"
                }
            }
        ).formats

        return VideoInfo(
            id = "candidate",
            title = "2 Hours Of Eve",
            ext = "mp4",
            originalUrl = pageUrl,
            formats = VideFormatEntityList(formats)
        )
    }

    /**
     * 模拟 `WebTabFragment.onVideoDownloadPropagate`：把用户选中的那条 format 单独入队。
     * 入队后引擎只能按这一条执行，因此“下载哪条”完全由这里的 URL 决定。
     */
    private fun selectAndBuildQueuePayload(candidate: VideoInfo, label: String): VideoInfo {
        val chosen = candidate.formats.formats.first { it.formatNote == label }
        return candidate.copy(
            id = "queue-$label",
            formats = VideFormatEntityList(listOf(chosen))
        )
    }
}
