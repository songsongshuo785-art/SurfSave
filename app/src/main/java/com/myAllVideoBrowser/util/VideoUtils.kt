package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.ui.main.home.browser.ContentType
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import okhttp3.Headers
import okhttp3.Request
import java.util.Locale

class VideoUtils {
    companion object {
        private val VIDEO_EXTENSION_REGEX = Regex(".*\\.(mp4|m4v|webm|mov|flv|3gp|mkv)$")
        private val AUDIO_EXTENSION_REGEX = Regex(".*\\.(mp3|m4a|aac|ogg|opus|wav)$")

        fun getContentTypeByUrlPath(url: String): ContentType {
            val cleanUrl = url
                .substringBefore('#')
                .substringBefore('?')
                .lowercase(Locale.US)

            if (cleanUrl.startsWith("blob:")) {
                return ContentType.OTHER
            }

            return when {
                cleanUrl.contains(".m3u8") -> ContentType.M3U8
                cleanUrl.contains(".mpd") -> ContentType.MPD
                cleanUrl.matches(VIDEO_EXTENSION_REGEX) -> {
                    ContentType.VIDEO
                }
                cleanUrl.matches(AUDIO_EXTENSION_REGEX) -> {
                    ContentType.AUDIO
                }
                else -> ContentType.OTHER
            }
        }

        fun getContentTypeByUrl(
            url: String,
            headers: Headers?,
            okHttpProxyClient: OkHttpProxyClient
        ): ContentType {
            val regex = Regex("\\.(js|css|m4s|ts)$|^blob:")
            if (regex.containsMatchIn(url)) {
                return ContentType.OTHER
            }

            val request = Request.Builder()
                .url(url)
                .headers(headers ?: Headers.headersOf())
                .get()
                .build()

            return runCatching {
                okHttpProxyClient.getProxyOkHttpClient().newCall(request).execute()
                    .use { response ->
                        val contentTypeStr = response.header("Content-Type")

                        when {
                            contentTypeStr?.contains("mpegurl") == true -> ContentType.M3U8
                            contentTypeStr?.contains("dash") == true -> ContentType.MPD
                            contentTypeStr?.contains("video") == true -> ContentType.VIDEO
                            contentTypeStr?.contains(
                                "audio",
                                ignoreCase = true
                            ) == true -> ContentType.AUDIO

                            contentTypeStr?.contains("application/octet-stream") == true -> {
                                response.body.charStream().use { reader ->
                                    val buffer = CharArray(256)
                                    val readCount = reader.read(buffer, 0, buffer.size)
                                    val content = readCount
                                        .takeIf { it > 0 }
                                        ?.let { String(buffer, 0, it) }
                                        ?: ""
                                    when {
                                        content.startsWith("#EXTM3U") -> ContentType.M3U8
                                        content.contains("<MPD") -> ContentType.MPD
                                        else -> ContentType.OTHER
                                    }
                                }
                            }

                            else -> ContentType.OTHER
                        }
                    }
            }.getOrDefault(ContentType.OTHER)
        }
    }
}
