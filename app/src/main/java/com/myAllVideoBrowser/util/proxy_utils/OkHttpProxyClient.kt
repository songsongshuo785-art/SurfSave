package com.myAllVideoBrowser.util.proxy_utils

import com.myAllVideoBrowser.data.local.model.Proxy as AppProxy
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

    private var currentProxy: AppProxy
    private var httpClientCached: OkHttpClient? = null

    init {
        currentProxy = getProxy()
    }

    fun getProxyOkHttpClient(): OkHttpClient {
        val proxy = getProxy()

        if (httpClientCached == null || hasProxyChanged(proxy)) {
            currentProxy = proxy
            val proxyCredentials = getProxyCredentials()
            val proxyAuthenticator = Authenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", proxyCredentials)
                    .build()
            }
            httpClientCached =
                if (proxy == AppProxy.noProxy()) {
                    okHttpClient.newBuilder().build()
                } else {
                    okHttpClient.newBuilder()
                        .proxy(
                            JavaProxy(
                                JavaProxy.Type.HTTP,
                                InetSocketAddress(proxy.host, proxy.port.toIntOrNull() ?: 1)
                            )
                        )
                        .proxyAuthenticator(proxyAuthenticator)
                        .build()
                }
        }

        return requireNotNull(httpClientCached)

    }

    private fun hasProxyChanged(proxy: AppProxy): Boolean {
        return proxy.host != currentProxy.host ||
            proxy.port != currentProxy.port ||
            proxy.user != currentProxy.user ||
            proxy.password != currentProxy.password ||
            proxy.type != currentProxy.type
    }

    private fun getProxy(): AppProxy {
        return proxyProvider()
    }

    private fun getProxyCredentials(): String {
        val creds = proxyCredentialsProvider()
        return Credentials.basic(creds.first, creds.second)
    }
}
