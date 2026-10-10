package com.myAllVideoBrowser.util.media

/**
 * A′：yt-dlp 的 generic/html5 解析在**只返回一条普通媒体地址**时，那条地址本身就是一个可独立执行的
 * 下载目标，应盖 [DownloadStrategy.DIRECT_HTTP]（交给 `CustomRegularDownloader` 带 Referer/Cookie
 * 直接下载），而不是盖 `YTDLP_FORMAT` 再让下载侧重新解析页面、用 `-f` 去匹配一个跨解析不稳定的
 * `format_id`。
 *
 * 判据刻意保守，任何一条不满足都退回既有 `YTDLP_FORMAT` 行为：
 * - **只认 generic/html5**：YouTube 这类多流站点（video-only + audio-only 需要合并）一律不动；
 * - **只认单条 format**：多格式说明 yt-dlp 结果本身带质量维度，`-f` 语义必须保留；
 * - **只认绝对 http(s) 且扩展名是已知媒体容器**：`.m3u8/.mpd` 等清单走 SuperX，`.html` 之类不是媒体；
 * - **明确 video-only 不接管**：需要 `+bestaudio` 合并音轨，必须留在 yt-dlp；
 * - **audio-only 不接管**：既有链路会 `--extract-audio --audio-format mp3`，改走直链会静默改变产物格式。
 */
internal object PlainMediaStrategyPolicy {

    private val acceptedExtractors = setOf("generic", "html5", "html5mediaembed")
    private val plainProtocols = setOf("http", "https")
    private val manifestExtensions = setOf("m3u8", "mpd")
    private val mediaExtensions = setOf(
        "mp4", "m4v", "mov", "webm", "mkv", "avi", "flv", "ogv", "3gp",
        "mp3", "m4a", "aac", "ogg", "wav"
    )

    fun shouldStampDirectHttp(
        extractor: String?,
        formatCount: Int,
        url: String?,
        protocol: String?,
        ext: String?,
        vcodec: String?,
        acodec: String?
    ): Boolean {
        if (formatCount != 1) {
            return false
        }
        if (normalizeExtractor(extractor) !in acceptedExtractors) {
            return false
        }
        if (!isAbsoluteHttpUrl(url)) {
            return false
        }
        if (!protocol.isNullOrBlank() && protocol.trim().lowercase() !in plainProtocols) {
            return false
        }
        val extension = effectiveExtension(ext, url)
        if (extension in manifestExtensions || extension !in mediaExtensions) {
            return false
        }
        if (FormatCodecEvidence.isVideoOnly(vcodec, acodec)) {
            return false
        }
        if (FormatCodecEvidence.isAudioOnly(vcodec, acodec)) {
            return false
        }
        return true
    }

    /** `extractor` / `extractor_key` 都可能是 `Generic` / `HTML5MediaEmbed` 这类写法。 */
    private fun normalizeExtractor(extractor: String?): String =
        extractor.orEmpty().trim().lowercase().filter { it.isLetterOrDigit() }

    private fun isAbsoluteHttpUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.length <= 8) {
            return false
        }
        val lower = value.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** 优先用 yt-dlp 给的 `ext`；缺失时退回 URL 路径的扩展名。 */
    private fun effectiveExtension(ext: String?, url: String?): String {
        val declared = ext?.trim()?.lowercase().orEmpty()
        if (declared.isNotEmpty()) {
            return declared
        }
        val withoutQuery = url.orEmpty().substringBefore('?').substringBefore('#')
        val path = withoutQuery.substringAfter("://", withoutQuery).substringAfter('/', "")
        return path.trimEnd('/').substringAfterLast('.', "").lowercase()
    }
}
