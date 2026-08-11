package com.myAllVideoBrowser.util

import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL

object RedirectResolver {

    private const val MAX_REDIRECTS = 10
    private const val COOKIE = "Cookie"
    private const val AUTHORIZATION = "Authorization"
    private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
    private const val HOST = "Host"
    private const val REFERER = "Referer"

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
        httpClient: OkHttpClient,
        cookieProvider: (URL) -> String? = { null }
    ): Pair<URL, Headers>? {
        var currentHeaders = headers.toHeaders()
        var currentUrl = url
        var redirectCount = 0
        val noRedirectClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        try {
            while (true) {
                val requestHeaders = headersForTarget(currentHeaders, currentUrl, cookieProvider)
                val request = Request.Builder()
                    .url(currentUrl)
                    .headers(requestHeaders)
                    .build()

                noRedirectClient.newCall(request).execute().use { response ->
                    if (response.code !in REDIRECT_CODES) {
                        return Pair(currentUrl, requestHeaders)
                    }
                    if (redirectCount >= MAX_REDIRECTS) {
                        return null
                    }
                    val location = response.header("Location")
                        ?: return null
                    val nextUrl = URL(currentUrl, location)
                    val nextHeaders = requestHeaders.newBuilder()
                        .removeAll(COOKIE)

                    if (!isSameOrigin(currentUrl, nextUrl)) {
                        nextHeaders.removeAll(AUTHORIZATION)
                        nextHeaders.removeAll(PROXY_AUTHORIZATION)
                        nextHeaders.removeAll(HOST)
                    }

                    if (isHttpsDowngrade(currentUrl, nextUrl)) {
                        nextHeaders.removeAll(REFERER)
                    } else {
                        nextHeaders.set(REFERER, currentUrl.toExternalForm())
                    }

                    currentHeaders = nextHeaders.build()
                    currentUrl = nextUrl
                    redirectCount++
                }
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun headersForTarget(
        headers: Headers,
        target: URL,
        cookieProvider: (URL) -> String?
    ): Headers {
        return headers.newBuilder()
            .removeAll(COOKIE)
            .apply {
                cookieProvider(target)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { set(COOKIE, it) }
            }
            .build()
    }

    private fun isSameOrigin(first: URL, second: URL): Boolean {
        return first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)
    }

    private fun effectivePort(url: URL): Int {
        return url.port.takeIf { it >= 0 } ?: url.defaultPort
    }

    private fun isHttpsDowngrade(from: URL, to: URL): Boolean {
        return from.protocol.equals("https", ignoreCase = true) &&
            to.protocol.equals("http", ignoreCase = true)
    }
}
