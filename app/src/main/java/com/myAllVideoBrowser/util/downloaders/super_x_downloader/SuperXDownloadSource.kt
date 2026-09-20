package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

internal data class SuperXDownloadSource(
    val url: String,
    val headers: Map<String, String>,
    val formatId: String?,
    val videoCodec: String?
)

internal object SuperXDownloadSourceResolver {
    fun resolve(videoInfo: VideoInfo): SuperXDownloadSource {
        val selectedFormat = videoInfo.formats.formats.firstOrNull()
            ?: throw IllegalArgumentException("No selected media format was provided for SuperX download.")
        val resolvedMediaUrl = selectedFormat.manifestUrl
            ?.takeIf { it.isNotBlank() }
            ?: selectedFormat.url?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "The selected media format has no manifest or media URL for SuperX download."
            )
        val requestUrl = selectedFormat.manifestRequestUrl
            ?.takeIf { it.isNotBlank() }
            ?: resolvedMediaUrl
        val requestHeaders = selectedFormat.manifestRequestHeaders
            ?: selectedFormat.httpHeaders.orEmpty()

        return SuperXDownloadSource(
            url = requestUrl,
            headers = requestHeaders,
            formatId = selectedFormat.formatId,
            videoCodec = selectedFormat.vcodec
        )
    }
}
