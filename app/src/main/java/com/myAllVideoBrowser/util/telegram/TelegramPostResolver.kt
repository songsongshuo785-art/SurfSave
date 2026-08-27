package com.myAllVideoBrowser.util.telegram

import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramPostResolver @Inject constructor(
    private val proxyController: CustomProxyController,
    private val okHttpProxyClient: OkHttpProxyClient
) {
    companion object {
        internal fun mapPreviewToResolution(
            preview: TelegramPostPreview,
            post: TelegramPostUrl
        ): TelegramPostResolution {
            val videos = preview.items.mapIndexedNotNull { index, item ->
                if (item.availability != TelegramMediaAvailability.PLAYABLE ||
                    item.mediaUrl.isBlank()
                ) {
                    return@mapIndexedNotNull null
                }

                val baseTitle = preview.description.ifBlank {
                    preview.channel.ifBlank { "Telegram ${post.channel} ${post.messageId}" }
                }
                val title = if (preview.items.size > 1) {
                    "$baseTitle (${index + 1}/${preview.items.size})"
                } else {
                    baseTitle
                }
                val headers = mapOf(
                    "Referer" to post.canonicalUrl,
                    "User-Agent" to BrowserFragment.MOBILE_USER_AGENT
                )
                val format = VideoFormatEntity(
                    formatId = "best",
                    format = "Telegram video",
                    formatNote = "Telegram",
                    ext = "mp4",
                    url = item.mediaUrl,
                    vcodec = "unknown",
                    acodec = "unknown",
                    httpHeaders = headers,
                    duration = item.durationMs
                )
                VideoInfo(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    ext = "mp4",
                    thumbnail = item.thumbnail,
                    duration = item.durationMs,
                    originalUrl = item.originalUrl,
                    formats = VideFormatEntityList(listOf(format)),
                    isRegularDownload = false
                )
            }
            return TelegramPostResolution(preview, videos)
        }
    }

    fun resolve(post: TelegramPostUrl): TelegramPostResolution {
        var ytDlpFailure: Throwable? = null
        try {
            return resolveWithYtDlp(post)
        } catch (error: Exception) {
            ytDlpFailure = error
            AppLogger.w(
                "Telegram yt-dlp extraction failed for public post: " +
                    error::class.java.simpleName
            )
        }

        return try {
            resolveFromEmbedPreview(post)
        } catch (fallbackError: Exception) {
            ytDlpFailure?.let(fallbackError::addSuppressed)
            throw TelegramPostUnavailableException(
                "Telegram post could not be resolved from its public embed page.",
                fallbackError
            )
        }
    }

    private fun resolveWithYtDlp(post: TelegramPostUrl): TelegramPostResolution {
        val request = YoutubeDLRequest(post.canonicalUrl).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        attachProxyToRequest(request)
        val response = YoutubeDL.getInstance().execute(request)
        return TelegramYtDlpMapper.parse(response.out, post)
    }

    private fun resolveFromEmbedPreview(post: TelegramPostUrl): TelegramPostResolution {
        val request = Request.Builder()
            .url(post.embedUrl)
            .header("User-Agent", BrowserFragment.MOBILE_USER_AGENT)
            .build()
        val html = okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Telegram embed returned HTTP ${response.code}.")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Telegram embed returned an empty response.")
        }
        val preview = TelegramPostPreviewParser.parse(html, post)
        if (preview.items.isEmpty()) {
            throw TelegramPostUnavailableException("Telegram post exposes no public video media.")
        }

        return mapPreviewToResolution(preview, post)
    }

    private fun attachProxyToRequest(request: YoutubeDLRequest) {
        val currentProxy = proxyController.getCurrentRunningProxy()
        if (currentProxy == Proxy.noProxy()) return

        val (user, password) = proxyController.getProxyCredentials()
        val proxyUrl = if (user.isNotEmpty() && password.isNotEmpty()) {
            "http://$user:$password@${currentProxy.host}:${currentProxy.port}"
        } else {
            "${currentProxy.host}:${currentProxy.port}"
        }
        request.addOption("--proxy", proxyUrl)
    }
}
