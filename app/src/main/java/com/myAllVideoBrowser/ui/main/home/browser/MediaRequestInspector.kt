package com.myAllVideoBrowser.ui.main.home.browser

import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
enum class ContentType {
    M3U8,
    MPD,
    VIDEO,
    AUDIO,
    OTHER
}

enum class BrowserRequestSource {
    WEB_VIEW,
    SERVICE_WORKER
}

data class MediaRequestInspection(
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
    val isM3u8: Boolean = contentType == ContentType.M3U8
    val shouldProbeAsM3u8: Boolean = isM3u8 || isTxtHlsCandidate
    val isMpd: Boolean = contentType == ContentType.MPD || url.contains(".mpd")
    val shouldBlockStreamRequest: Boolean =
        shouldCheckStream && shouldInterruptResource && isM3u8
}

/** Media-only stage that runs after content blocking has allowed the request. */
class MediaRequestInspector(
    private val settingsModel: SettingsViewModel
) {
    fun inspect(
        url: String,
        pageUrl: String
    ): MediaRequestInspection {
        val normalizedUrl = url.trim()
        val contentType = BrowserMediaClassifier.classify(normalizedUrl)
        val isTxtHlsCandidate = BrowserMediaClassifier.isTextPlaylistCandidate(normalizedUrl)
        val shouldCheckM3u8 = settingsModel.isCheckIfEveryRequestOnM3u8.get()
        val shouldCheckMp4 = settingsModel.getIsCheckEveryRequestOnMp4Video().get()
        val shouldCheckAudio = settingsModel.isCheckOnAudio.get()
        val isStreamCandidate = contentType == ContentType.M3U8 ||
            contentType == ContentType.MPD ||
            isTxtHlsCandidate
        val shouldCheckStream = isStreamCandidate && shouldCheckM3u8
        val shouldCheckRegular = (contentType == ContentType.VIDEO && shouldCheckMp4) ||
            (contentType == ContentType.AUDIO && shouldCheckAudio)

        return MediaRequestInspection(
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
