package com.myAllVideoBrowser.util.downloaders

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** 带 HTTP 状态码的下载异常，便于区分可重试与不可重试。 */
class DownloadHttpException(val code: Int, message: String) : IOException(message)

/**
 * 下载重试策略：只重试网络抖动和临时服务端错误。
 * 证书、鉴权、资源不存在、磁盘和解析错误一律不重试。
 */
object DownloadRetryPolicy {
    private val transientHttpCodes = setOf(408, 425, 429, 500, 502, 503, 504)

    fun isTransient(error: Throwable?): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is SSLException -> return false
                is DownloadHttpException -> return current.code in transientHttpCodes
                is SocketTimeoutException -> return true
                is ConnectException -> return true
                is EOFException -> return true
                is UnknownHostException -> return true
                is SocketException -> {
                    val message = current.message.orEmpty().lowercase(Locale.US)
                    if (message.contains("reset") ||
                        message.contains("broken pipe") ||
                        message.contains("network is unreachable") ||
                        message.contains("connection abort")
                    ) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return false
    }
}
