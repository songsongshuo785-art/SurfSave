package com.myAllVideoBrowser.util.proxy_utils

import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.model.ProxyType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy as JavaProxy

class OkHttpProxyClientTest {

    private var currentProxy = Proxy.noProxy()

    private val proxyClient = OkHttpProxyClient(
        OkHttpClient.Builder().build(),
        proxyProvider = { currentProxy },
        proxyCredentialsProvider = { currentProxy.user to currentProxy.password }
    )

    @Test
    fun getProxyOkHttpClient_withoutProxyBuildsDirectClient() {
        val client = proxyClient.getProxyOkHttpClient()

        assertSame(JavaProxy.NO_PROXY, client.proxy)
    }

    @Test
    fun getProxyOkHttpClient_reusesClientWhenProxyIsUnchanged() {
        currentProxy = proxy(port = "8888")

        val firstClient = proxyClient.getProxyOkHttpClient()
        val secondClient = proxyClient.getProxyOkHttpClient()

        assertSame(firstClient, secondClient)
    }

    @Test
    fun getProxyOkHttpClient_rebuildsClientWhenOnlyPortChanges() {
        currentProxy = proxy(port = "8888")
        val firstClient = proxyClient.getProxyOkHttpClient()

        currentProxy = proxy(port = "9999")
        val secondClient = proxyClient.getProxyOkHttpClient()

        assertNotSame(firstClient, secondClient)
        assertProxyAddress(secondClient, "127.0.0.1", 9999)
    }

    @Test
    fun getProxyOkHttpClient_rebuildsClientWhenOnlyHostChanges() {
        currentProxy = proxy(host = "127.0.0.1")
        val firstClient = proxyClient.getProxyOkHttpClient()

        currentProxy = proxy(host = "127.0.0.2")
        val secondClient = proxyClient.getProxyOkHttpClient()

        assertNotSame(firstClient, secondClient)
        assertProxyAddress(secondClient, "127.0.0.2", 8888)
    }

    @Test
    fun getProxyOkHttpClient_rebuildsClientWhenOnlyCredentialsChange() {
        currentProxy = proxy(user = "user-a", password = "pass-a")
        val firstClient = proxyClient.getProxyOkHttpClient()

        currentProxy = proxy(user = "user-b", password = "pass-b")
        val secondClient = proxyClient.getProxyOkHttpClient()

        assertNotSame(firstClient, secondClient)
    }

    @Test
    fun getProxyOkHttpClient_rebuildsClientWhenOnlyPasswordChanges() {
        currentProxy = proxy(user = "user", password = "pass-a")
        val firstClient = proxyClient.getProxyOkHttpClient()

        currentProxy = proxy(user = "user", password = "pass-b")
        val secondClient = proxyClient.getProxyOkHttpClient()

        assertNotSame(firstClient, secondClient)
    }

    @Test
    fun getProxyOkHttpClient_mapsSocks5ToJavaSocksProxy() {
        currentProxy = proxy(user = "", password = "", type = ProxyType.HTTP)
        val httpClient = proxyClient.getProxyOkHttpClient()

        currentProxy = proxy(user = "", password = "", type = ProxyType.SOCKS5)
        val socksClient = proxyClient.getProxyOkHttpClient()

        assertNotSame(httpClient, socksClient)
        assertEquals(JavaProxy.Type.SOCKS, requireNotNull(socksClient.proxy).type())
    }

    @Test
    fun getProxyOkHttpClient_rejectsAuthenticatedSocks5Explicitly() {
        currentProxy = proxy(type = ProxyType.SOCKS5)

        try {
            proxyClient.getProxyOkHttpClient()
            fail("Expected authenticated SOCKS5 to be rejected.")
        } catch (error: IllegalArgumentException) {
            assertEquals(
                "Authenticated SOCKS5 is not supported by the OkHttp proxy authenticator.",
                error.message
            )
        }
    }

    @Test
    fun proxyConfigurationIsInheritedByChildClientsUsedForRedirectsAndSubrequests() {
        currentProxy = proxy()

        val selectedClient = proxyClient.getProxyOkHttpClient()
        val childClient = selectedClient.newBuilder().build()

        assertEquals(selectedClient.proxy, childClient.proxy)
        assertSame(selectedClient.proxyAuthenticator, childClient.proxyAuthenticator)
    }

    private fun proxy(
        host: String = "127.0.0.1",
        port: String = "8888",
        user: String = "user",
        password: String = "password",
        type: ProxyType = ProxyType.HTTP
    ): Proxy {
        return Proxy(
            host = host,
            port = port,
            user = user,
            password = password,
            type = type
        )
    }

    private fun assertProxyAddress(
        client: OkHttpClient,
        expectedHost: String,
        expectedPort: Int
    ) {
        val proxy = client.proxy as JavaProxy
        val address = proxy.address() as InetSocketAddress

        assertEquals(JavaProxy.Type.HTTP, proxy.type())
        assertEquals(expectedHost, address.hostString)
        assertEquals(expectedPort, address.port)
    }
}
