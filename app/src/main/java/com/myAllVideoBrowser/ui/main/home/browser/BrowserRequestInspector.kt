package com.myAllVideoBrowser.ui.main.home.browser

import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.VideoUtils

enum class ContentType {
    M3U8,
    MPD,
    VIDEO,
    AUDIO,
    OTHER
}

data class BrowserRequestInspection(
    val url: String,
    val pageUrl: String,
    val contentType: ContentType,
    val isTxtHlsCandidate: Boolean,
    val shouldCheckStream: Boolean,
    val shouldCheckRegular: Boolean,
    val shouldCheckAudio: Boolean,
    val shouldCheckVideo: Boolean,
    val shouldInterruptResource: Boolean
) {
    val shouldInspectMedia: Boolean = shouldCheckStream || shouldCheckRegular
    val isM3u8: Boolean = contentType == ContentType.M3U8 || isTxtHlsCandidate
    val isMpd: Boolean = contentType == ContentType.MPD || url.contains(".mpd")
}

class BrowserRequestInspector(
    private val settingsModel: SettingsViewModel
) {
    @Suppress("UNUSED_PARAMETER")
    fun inspect(
        url: String,
        pageUrl: String,
        isMainFrame: Boolean
    ): BrowserRequestInspection {
        val normalizedUrl = url.trim()
        val contentType = VideoUtils.getContentTypeByUrlPath(normalizedUrl)
        val isTxtHlsCandidate = normalizedUrl.contains(".txt") && normalizedUrl.contains("hentaihaven")
        val shouldCheckM3u8 = settingsModel.isCheckIfEveryRequestOnM3u8.get()
        val shouldCheckMp4 = settingsModel.getIsCheckEveryRequestOnMp4Video().get()
        val shouldCheckAudio = settingsModel.isCheckOnAudio.get()
        val isStreamCandidate = contentType == ContentType.M3U8 ||
            contentType == ContentType.MPD ||
            isTxtHlsCandidate
        val shouldCheckStream = isStreamCandidate && shouldCheckM3u8
        val shouldCheckRegular = (contentType == ContentType.VIDEO && shouldCheckMp4) ||
            (contentType == ContentType.AUDIO && shouldCheckAudio)

        return BrowserRequestInspection(
            url = normalizedUrl,
            pageUrl = pageUrl,
            contentType = contentType,
            isTxtHlsCandidate = isTxtHlsCandidate,
            shouldCheckStream = shouldCheckStream,
            shouldCheckRegular = shouldCheckRegular,
            shouldCheckAudio = shouldCheckAudio,
            shouldCheckVideo = shouldCheckMp4,
            shouldInterruptResource = settingsModel.isInterruptIntreceptedResources.get()
        )
    }
}
