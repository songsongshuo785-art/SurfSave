package com.myAllVideoBrowser.util

import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL

object RedirectResolver {

    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,  // 301
        HttpURLConnection.HTTP_MOVED_TEMP,  // 302
        HttpURLConnection.HTTP_SEE_OTHER,   // 303
        307,
        308
    )

    fun getFinalRedirectURL(
        url: URL,
        headers: Map<String, String>,
        httpClient: OkHttpClient
    ): Pair<URL, Headers>? {
        val currentHeaders = headers.toMutableMap()
        var currentUrl = url

        try {
            val maxRedirects = 10
            repeat(maxRedirects) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .headers(currentHeaders.toHeaders())
                    .build()

                val noRedirectClient = httpClient.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                val response = noRedirectClient.newCall(request).execute()
                val code = response.code
                response.close()

                if (code in REDIRECT_CODES) {
                    val location = response.header("Location")
                        ?: return Pair(currentUrl, currentHeaders.toHeaders())

                    val origin = response.header("Access-Control-Allow-Origin")
                    if (origin != null) {
                        currentHeaders["Referer"] = origin
                    }

                    currentUrl = URL(currentUrl, location)
                } else {
                    return Pair(currentUrl, currentHeaders.toHeaders())
                }
            }
        } catch (_: Exception) {
        }

        return Pair(currentUrl, currentHeaders.toHeaders())
    }
}
