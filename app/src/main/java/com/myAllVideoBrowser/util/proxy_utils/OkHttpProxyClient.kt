package com.myAllVideoBrowser.util.proxy_utils

import com.myAllVideoBrowser.data.local.model.Proxy as AppProxy
import com.myAllVideoBrowser.data.local.model.ProxyType
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy as JavaProxy
import javax.inject.Inject

class OkHttpProxyClient internal constructor(
    private val okHttpClient: OkHttpClient,
    private val proxyProvider: () -> AppProxy,
    private val proxyCredentialsProvider: () -> Pair<String, String>
) {
    @Inject
    constructor(
        okHttpClient: OkHttpClient,
        proxyController: CustomProxyController
    ) : this(
        okHttpClient,
        { proxyController.getCurrentRunningProxy() },
        { proxyController.getProxyCredentials() }
    )

    private data class ProxyCacheKey(
        val type: ProxyType,
        val host: String,
        val port: String,
        val username: String,
        val password: String
    ) {
        val isDirect: Boolean
            get() = host.isBlank() && port.isBlank()
    }

    private var currentConfig: ProxyCacheKey? = null
    private var httpClientCached: OkHttpClient? = null

    @Synchronized
    fun getProxyOkHttpClient(): OkHttpClient {
        val proxy = getProxy()
        val credentials = proxyCredentialsProvider()
        val config = ProxyCacheKey(
            type = proxy.type,
            host = proxy.host,
            port = proxy.port,
            username = credentials.first,
            password = credentials.second
        )

        if (httpClientCached == null || config != currentConfig) {
            httpClientCached = buildClient(config)
            currentConfig = config
        }

        return requireNotNull(httpClientCached)
    }

    private fun buildClient(config: ProxyCacheKey): OkHttpClient {
        if (config.isDirect) {
            return okHttpClient.newBuilder()
                .proxy(JavaProxy.NO_PROXY)
                .proxyAuthenticator(Authenticator { _, _ -> null })
                .build()
        }
        if (config.host.isBlank()) {
            throw IllegalArgumentException("Proxy host cannot be blank when a proxy port is configured.")
        }
        val port = config.port.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Proxy port must be between 1 and 65535.")
        val javaProxyType = when (config.type) {
            ProxyType.HTTP -> JavaProxy.Type.HTTP
            ProxyType.SOCKS5 -> JavaProxy.Type.SOCKS
        }
        if (javaProxyType == JavaProxy.Type.SOCKS &&
            (config.username.isNotBlank() || config.password.isNotBlank())
        ) {
            throw IllegalArgumentException(
                "Authenticated SOCKS5 is not supported by the OkHttp proxy authenticator."
            )
        }

        val builder = okHttpClient.newBuilder()
            .proxy(JavaProxy(javaProxyType, InetSocketAddress(config.host, port)))
        if (javaProxyType == JavaProxy.Type.HTTP &&
            (config.username.isNotBlank() || config.password.isNotBlank())
        ) {
            val proxyCredentials = Credentials.basic(config.username, config.password)
            builder.proxyAuthenticator(Authenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", proxyCredentials)
                        .build()
                }
            })
        } else {
            builder.proxyAuthenticator(Authenticator { _, _ -> null })
        }
        return builder.build()
    }

    private fun getProxy(): AppProxy {
        return proxyProvider()
    }
}
