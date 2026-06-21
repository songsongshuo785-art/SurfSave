package com.myAllVideoBrowser.util

import android.content.Context
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistExtractor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val proxyController: CustomProxyController
) {
    data class PlaylistDownloadItem(
        val playlistIndex: Int,
        val playlistTitle: String,
        val videoInfo: VideoInfo
    )

    data class Result(
        val title: String,
        val items: List<PlaylistDownloadItem>
    )

    fun extract(url: String): Result {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        request.addOption("--skip-download")
        request.addOption("--no-warnings")
        attachProxyToRequest(request)
        val cookieFile = CookieUtils.addCookiesToRequest(url, request)

        try {
            val response = YoutubeDL.getInstance().execute(request)
            val json = JSONObject(response.out)
            val playlistTitle = json.optString("title", "playlist").ifBlank { "playlist" }
            val entries = json.optJSONArray("entries") ?: error("No playlist entries found.")
            val items = mutableListOf<PlaylistDownloadItem>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val entryUrl = resolveEntryUrl(url, entry) ?: continue
                val title = entry.optString("title", "video ${i + 1}").ifBlank { "video ${i + 1}" }
                val ext = entry.optString("ext", "mp4").ifBlank { "mp4" }
                val durationSeconds = entry.optLong("duration", 0L)
                val thumbnail = entry.optString("thumbnail", "")
                val format = VideoFormatEntity(
                    formatId = "best",
                    format = "best",
                    formatNote = "best",
                    ext = ext,
                    url = entryUrl,
                    vcodec = "unknown",
                    acodec = "unknown",
                    duration = durationSeconds.takeIf { it > 0 }?.times(1000)
                )
                val videoInfo = VideoInfo(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    ext = ext,
                    thumbnail = thumbnail,
                    duration = durationSeconds * 1000,
                    originalUrl = entryUrl,
                    formats = VideFormatEntityList(listOf(format)),
                    isRegularDownload = false
                )
                items += PlaylistDownloadItem(
                    playlistIndex = i + 1,
                    playlistTitle = playlistTitle,
                    videoInfo = videoInfo
                )
            }
            if (items.isEmpty()) {
                error("No downloadable playlist entries were found.")
            }
            return Result(playlistTitle, items)
        } finally {
            CookieUtils.deleteTemporaryCookieFile(cookieFile)
        }
    }

    private fun resolveEntryUrl(originalUrl: String, entry: JSONObject): String? {
        val candidates = listOf(
            entry.optString("webpage_url", ""),
            entry.optString("url", "")
        )
        candidates.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.let {
            return it
        }

        val id = entry.optString("id", "")
        val originalHost = runCatching { URI(originalUrl).host.orEmpty() }.getOrDefault("")
        if (id.isNotBlank() && originalHost.contains("youtube", ignoreCase = true)) {
            return "https://www.youtube.com/watch?v=$id"
        }
        return null
    }

    private fun attachProxyToRequest(request: YoutubeDLRequest) {
        val currentProxy = proxyController.getCurrentRunningProxy()
        if (currentProxy == Proxy.noProxy()) {
            return
        }
        val (user, password) = proxyController.getProxyCredentials()
        if (user.isNotEmpty() && password.isNotEmpty()) {
            request.addOption(
                "--proxy",
                "http://${user}:${password}@${currentProxy.host}:${currentProxy.port}"
            )
        } else {
            request.addOption("--proxy", "${currentProxy.host}:${currentProxy.port}")
        }
    }
}
