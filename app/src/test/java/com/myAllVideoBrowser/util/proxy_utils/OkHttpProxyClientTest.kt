package com.myAllVideoBrowser.util.proxy_utils

import com.myAllVideoBrowser.data.local.model.Proxy
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

        assertNull(client.proxy)
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

    private fun proxy(
        host: String = "127.0.0.1",
        port: String = "8888",
        user: String = "user",
        password: String = "password"
    ): Proxy {
        return Proxy(
            host = host,
            port = port,
            user = user,
            password = password
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
