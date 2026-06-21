package com.myAllVideoBrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URL


class FaviconUtils {
    companion object {
        private const val DISPLAY_ICON_SIZE_PX = 144
        private const val MIN_CLEAR_ICON_SIZE_PX = 64

        fun bitmapToBytes(bitmap: Bitmap?): ByteArray {
            val stream = ByteArrayOutputStream()
            bitmap?.compress(Bitmap.CompressFormat.PNG, 90, stream)

            return stream.toByteArray()
        }

        fun isLowResolutionFavicon(bitmap: Bitmap?): Boolean {
            if (bitmap == null) {
                return true
            }

            return minOf(bitmap.width, bitmap.height) < MIN_CLEAR_ICON_SIZE_PX
        }

        fun prepareForDisplay(bitmap: Bitmap?): Bitmap? {
            if (bitmap == null) {
                return null
            }

            if (bitmap.width >= DISPLAY_ICON_SIZE_PX && bitmap.height >= DISPLAY_ICON_SIZE_PX) {
                return bitmap
            }

            return bitmap.scale(DISPLAY_ICON_SIZE_PX, DISPLAY_ICON_SIZE_PX)
        }

        suspend fun getEncodedFaviconFromUrl(okHttpClient: OkHttpClient, url: String): Bitmap? {
            delay(0)
            return fetchFavicon(okHttpClient, url)
        }

        private fun fetchFavicon(okHttpClient: OkHttpClient, url: String): Bitmap? {
            val potentialUrls = discoverIconUrls(okHttpClient, url)

            for (reqUrl in potentialUrls) {
                decodeBitmapFromUrl(okHttpClient, reqUrl)?.let {
                    return it
                }
            }

            return null
        }

        private fun discoverIconUrls(okHttpClient: OkHttpClient, url: String): List<String> {
            val parsed = runCatching { url.toUri() }.getOrNull()
            val host = parsed?.host.orEmpty()
            val origin = if (host.isBlank()) {
                ""
            } else {
                "${parsed?.scheme ?: "https"}://$host"
            }

            val pageIcons = runCatching {
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!response.isSuccessful || !contentType.contains("html", true)) {
                        return@use emptyList<String>()
                    }

                    val html = response.body.string()
                    val document = Jsoup.parse(html, url)
                    document.select(
                        "link[rel~=(?i).*icon.*][href], link[rel~=(?i).*apple-touch-icon.*][href]"
                    ).mapNotNull { element ->
                        val href = element.attr("abs:href").ifBlank { element.attr("href") }
                        href.takeIf { it.isNotBlank() && !it.endsWith(".svg", true) }?.let {
                            IconCandidate(
                                url = it,
                                score = iconScore(
                                    element.attr("rel"),
                                    element.attr("sizes"),
                                    it
                                )
                            )
                        }
                    }.sortedByDescending { it.score }
                        .map { it.url }
                }
            }.getOrDefault(emptyList())

            val fallbackIcons = listOfNotNull(
                origin.takeIf { it.isNotBlank() }?.let { "$it/apple-touch-icon.png" },
                origin.takeIf { it.isNotBlank() }?.let { "$it/apple-touch-icon-precomposed.png" },
                origin.takeIf { it.isNotBlank() }?.let { "$it/favicon-192x192.png" },
                origin.takeIf { it.isNotBlank() }?.let { "$it/favicon-32x32.png" },
                host.takeIf { it.isNotBlank() }?.let { "https://$it/favicon.ico" },
                host.takeIf { it.isNotBlank() }?.replaceFirst("www.", "")?.let {
                    "https://$it/favicon.ico"
                }
            )

            return (pageIcons + fallbackIcons)
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
        }

        private fun decodeBitmapFromUrl(okHttpClient: OkHttpClient, url: String): Bitmap? {
            return runCatching {
                val request = Request.Builder()
                    .url(URL(url))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use null
                    }

                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    BitmapFactory.decodeStream(response.body.byteStream(), null, options)
                }
            }.getOrNull()
        }

        private fun iconScore(rel: String, sizes: String, url: String): Int {
            val largestSize = Regex("""(\d{2,4})x(\d{2,4})""")
                .findAll(sizes)
                .mapNotNull { match ->
                    val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    minOf(width, height)
                }
                .maxOrNull() ?: 0

            var score = largestSize
            if (rel.contains("apple-touch-icon", true)) {
                score += 300
            }
            if (url.contains("192") || url.contains("180")) {
                score += 120
            }
            if (url.endsWith(".png", true)) {
                score += 30
            }
            if (url.endsWith(".ico", true)) {
                score -= 20
            }

            return score
        }

        private data class IconCandidate(
            val url: String,
            val score: Int
        )
    }
}
