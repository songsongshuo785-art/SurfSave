package com.myAllVideoBrowser.ui.main.home.browser

import android.os.Build
import android.os.Environment
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.BrowserFileDownloadRepository
import com.myAllVideoBrowser.data.repository.BrowserFileEnqueueRequest
import javax.inject.Inject
import javax.inject.Singleton

data class BrowserSystemDownloadSpec(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val headers: Map<String, String>,
    val contentLength: Long,
    val sourcePageUrl: String,
    val destinationDirectory: String = Environment.DIRECTORY_DOWNLOADS,
    val destinationRelativePath: String = "${BrowserDownloadCoordinator.FILE_DOWNLOAD_DIRECTORY}/$fileName"
)

sealed interface BrowserDownloadPlan {
    data class DirectMedia(val request: BrowserDownloadRequest) : BrowserDownloadPlan
    data class Manifest(val request: BrowserDownloadRequest) : BrowserDownloadPlan
    data class SystemFile(val spec: BrowserSystemDownloadSpec) : BrowserDownloadPlan
    data object Ignore : BrowserDownloadPlan
}

sealed interface BrowserConfirmedDownload {
    data class MediaSubmitted(val videoInfo: VideoInfo) : BrowserConfirmedDownload
    data class SystemSubmitted(val downloadId: Long) : BrowserConfirmedDownload
}

/**
 * Routes WebView download callbacks to a real consumer. Media stays in SurfSave's queue/format
 * flow; non-media attachments use Android's public Downloads provider.
 */
@Singleton
class BrowserDownloadCoordinator @Inject constructor(
    private val fileDownloadRepository: BrowserFileDownloadRepository
) {
    fun plan(request: BrowserDownloadRequest): BrowserDownloadPlan {
        if (!request.isHttpRequest()) return BrowserDownloadPlan.Ignore

        return when (request.mediaType()) {
            ContentType.VIDEO,
            ContentType.AUDIO -> BrowserDownloadPlan.DirectMedia(request)

            ContentType.M3U8,
            ContentType.MPD -> BrowserDownloadPlan.Manifest(request)

            ContentType.OTHER -> BrowserDownloadPlan.SystemFile(
                BrowserSystemDownloadSpec(
                    url = request.url,
                    fileName = request.safeFileName(),
                    mimeType = request.normalizedMimeType(),
                    headers = request.allowedDownloadHeaders(),
                    contentLength = request.contentLength.coerceAtLeast(0L),
                    sourcePageUrl = request.pageUrl.orEmpty()
                )
            )
        }
    }

    fun enqueueSystemFile(spec: BrowserSystemDownloadSpec): Result<Long> =
        fileDownloadRepository.enqueue(
            BrowserFileEnqueueRequest(
                url = spec.url,
                sourcePageUrl = spec.sourcePageUrl,
                fileName = spec.fileName,
                mimeType = spec.mimeType.orEmpty(),
                expectedSize = spec.contentLength,
                headers = spec.headers,
                relativePath = spec.destinationRelativePath
            )
        )

    fun executeConfirmed(
        plan: BrowserDownloadPlan,
        fallbackTitle: String? = null,
        submitMedia: (VideoInfo) -> Unit
    ): Result<BrowserConfirmedDownload> = runCatching {
        when (plan) {
            is BrowserDownloadPlan.DirectMedia -> {
                val videoInfo = requireNotNull(
                    plan.request.toDirectMediaVideoInfo(fallbackTitle)
                ) { "The confirmed request is not a direct media download" }
                submitMedia(videoInfo)
                BrowserConfirmedDownload.MediaSubmitted(videoInfo)
            }

            is BrowserDownloadPlan.SystemFile -> BrowserConfirmedDownload.SystemSubmitted(
                enqueueSystemFile(plan.spec).getOrThrow()
            )

            is BrowserDownloadPlan.Manifest -> error(
                "Manifest downloads require format resolution before confirmation"
            )

            BrowserDownloadPlan.Ignore -> error("Ignored downloads cannot be confirmed")
        }
    }

    companion object {
        const val FILE_DOWNLOAD_DIRECTORY = "SurfSave/Files"

        internal fun requiresLegacyWritePermission(
            sdkInt: Int,
            permissionGranted: Boolean
        ): Boolean = sdkInt <= Build.VERSION_CODES.P && !permissionGranted
    }
}
