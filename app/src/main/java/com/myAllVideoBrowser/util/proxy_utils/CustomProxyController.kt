package com.myAllVideoBrowser.util.proxy_utils

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import javax.inject.Inject
import javax.inject.Singleton

private data class LastConfig(val proxy: Proxy, val isDohEnabled: Boolean)

@Singleton
class CustomProxyController @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
) {
    @Volatile
    private var lastAppliedConfig: LastConfig? = null

    init {
        updateProxyState()
    }

    fun getCurrentRunningProxy(): Proxy {
        return if (isProxyOn()) {
            return getLocalProxy()
        } else {
            Proxy.noProxy()
        }
    }

    fun getProxyCredentials(): Pair<String, String> {
        val currProx = getCurrentRunningProxy()
        return Pair(currProx.user, currProx.password)
    }

    fun updateProxyState() {
        setCurrentProxy(getCurrentRunningProxy())
    }

    @Synchronized
    private fun setCurrentProxy(proxy: Proxy) {
        val isDohEnabled = sharedPrefHelper.getIsDohOn()
        val newConfig = LastConfig(proxy, isDohEnabled)

        if (newConfig == lastAppliedConfig) {
            AppLogger.d("Proxy config is unchanged. No action needed.")
            return
        }

        val isProxyActive = proxy != Proxy.noProxy() || isDohEnabled

        if (isProxyActive) {
            AppLogger.d("Applying WebView proxy override (127.0.0.1:8888).")
            val localProxy = getLocalProxy()

            val proxyConfig =
                ProxyConfig.Builder().addProxyRule("${localProxy.host}:${localProxy.port}").build()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    ProxyController.getInstance().setProxyOverride(proxyConfig, { }) {}
                } catch (e: Exception) {
                    AppLogger.d("ERROR SETTING PROXY: $e")
                }
            }
        } else {
            AppLogger.d("Clearing WebView proxy override.")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride({ }) {}
            }
        }

        lastAppliedConfig = newConfig
    }

    private fun isProxyOn(): Boolean {
        return sharedPrefHelper.getIsProxyOn() || sharedPrefHelper.getIsDohOn()
    }

    private fun getLocalProxy(): Proxy {
        val creds = sharedPrefHelper.getGeneratedCreds()
        val localProxy = Proxy(
            host = "127.0.0.1",
            port = "8888",
            user = creds.localUser,
            password = creds.localPassword
        )
        return localProxy
    }
}
