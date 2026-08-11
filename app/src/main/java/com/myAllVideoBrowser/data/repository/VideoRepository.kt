package com.myAllVideoBrowser.data.repository

import androidx.annotation.VisibleForTesting
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.di.qualifier.RemoteData
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import okhttp3.Request
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface VideoRepository {
    fun getVideoInfoBySuperXDetector(
        url: Request,
        isM3u8: Boolean = false,
        isMpd: Boolean = false,
        isAudioCheck: Boolean
    ): VideoInfo?

    fun getVideoInfo(url: Request, isM3u8OrMpd: Boolean = false, isAudioCheck: Boolean): VideoInfo?

    fun saveVideoInfo(videoInfo: VideoInfo)
}

@Singleton
class VideoRepositoryImpl internal constructor(
    private val remoteDataSource: VideoRepository,
    private val proxyProvider: () -> Proxy
) : VideoRepository {

    @Inject
    constructor(
        @RemoteData remoteDataSource: VideoRepository,
        proxyController: CustomProxyController
    ) : this(remoteDataSource, { proxyController.getCurrentRunningProxy() })

    companion object {
        internal const val MAX_CACHE_SIZE = 100
        private const val REGULAR_DETECTOR = "regular"
        private const val SUPER_X_DETECTOR = "super-x"
    }

    @VisibleForTesting
    internal val cachedVideos = SynchronizedLruCache<String, VideoInfo>(MAX_CACHE_SIZE)
    internal val cachedVideosFfmpeg = SynchronizedLruCache<String, VideoInfo>(MAX_CACHE_SIZE)

    override fun getVideoInfoBySuperXDetector(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        val cacheKey = buildCacheKey(
            detector = SUPER_X_DETECTOR,
            request = url,
            isM3u8 = isM3u8,
            isMpd = isMpd,
            isAudioCheck = isAudioCheck
        )
        cacheKey?.let { cachedVideosFfmpeg.get(it) }?.let { return it.deepCopy() }

        return getAndCacheRemoteVideoFfmpeg(url, isM3u8, isMpd, isAudioCheck, cacheKey)
    }

    override fun getVideoInfo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        // VideoServiceLocal resolves Cookie profile/WebView authentication during execution.
        // The repository cannot atomically key and execute against the same auth snapshot, so
        // regular detection deliberately bypasses cache reuse.
        return getAndCacheRemoteVideo(url, isM3u8OrMpd, isAudioCheck, cacheKey = null)
    }

    override fun saveVideoInfo(videoInfo: VideoInfo) {
        val request = Request.Builder().url(videoInfo.originalUrl).get().build()
        val cacheKey = requireNotNull(
            buildCacheKey(
                detector = REGULAR_DETECTOR,
                request = request,
                isM3u8 = false,
                isMpd = false,
                isAudioCheck = false
            )
        )
        cachedVideos.put(cacheKey, videoInfo.deepCopy())
    }

    private fun getAndCacheRemoteVideoFfmpeg(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean,
        cacheKey: String?
    ): VideoInfo? {
        val videoInfo = remoteDataSource.getVideoInfoBySuperXDetector(url, isM3u8, isMpd, isAudioCheck)
        if (videoInfo != null) {
            videoInfo.originalUrl = url.url.toString()
            cacheKey?.let { cachedVideosFfmpeg.put(it, videoInfo.deepCopy()) }

            return videoInfo
        }
        return null
    }

    private fun getAndCacheRemoteVideo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean,
        cacheKey: String?
    ): VideoInfo? {
        val videoInfo = remoteDataSource.getVideoInfo(url, isM3u8OrMpd, isAudioCheck)
        if (videoInfo != null) {
            videoInfo.originalUrl = url.url.toString()
            cacheKey?.let { cachedVideos.put(it, videoInfo.deepCopy()) }

            return videoInfo
        }
        return null
    }

    private fun buildCacheKey(
        detector: String,
        request: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): String? {
        // RequestBody may be one-shot. Bypass caching rather than consume it or reuse a
        // response for a different body that happens to share URL/method/length.
        if (request.body != null) {
            return null
        }

        val proxy = proxyProvider()
        val rawKey = buildString {
            appendCachePart(detector)
            appendCachePart(request.url.toString())
            appendCachePart(request.method.uppercase(Locale.US))
            appendCachePart(isM3u8.toString())
            appendCachePart(isMpd.toString())
            appendCachePart(isAudioCheck.toString())
            appendNormalizedHeaders(request)
            appendCachePart(proxy.id)
            appendCachePart(proxy.type.name)
            appendCachePart(proxy.host)
            appendCachePart(proxy.port)
            appendCachePart(proxy.user)
            appendCachePart(proxy.password)
        }
        return sha256(rawKey)
    }

    private fun StringBuilder.appendCachePart(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    private fun StringBuilder.appendNormalizedHeaders(request: Request) {
        val valuesByName = sortedMapOf<String, MutableList<String>>()
        for (index in 0 until request.headers.size) {
            val name = request.headers.name(index).lowercase(Locale.US)
            valuesByName.getOrPut(name) { mutableListOf() }
                .add(request.headers.value(index))
        }
        appendCachePart(valuesByName.size.toString())
        valuesByName.forEach { (name, values) ->
            appendCachePart(name)
            appendCachePart(values.size.toString())
            values.forEach { value -> appendCachePart(value) }
        }
    }

    private fun VideoInfo.deepCopy(): VideoInfo {
        return copy(
            downloadUrls = downloadUrls.map { requestData ->
                requestData.copy(headers = LinkedHashMap(requestData.headers))
            },
            formats = VideFormatEntityList(
                formats.formats.map { format ->
                    format.copy(
                        httpHeaders = format.httpHeaders?.let { LinkedHashMap(it) }
                    )
                }
            )
        )
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}

class SynchronizedLruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()
}
