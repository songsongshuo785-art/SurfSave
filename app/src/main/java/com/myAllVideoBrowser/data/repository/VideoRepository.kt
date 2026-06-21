package com.myAllVideoBrowser.data.repository

import androidx.annotation.VisibleForTesting
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.di.qualifier.RemoteData
import okhttp3.Request
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
class VideoRepositoryImpl @Inject constructor(
    @param:RemoteData private val remoteDataSource: VideoRepository
) : VideoRepository {

    companion object {
        internal const val MAX_CACHE_SIZE = 100
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
        cachedVideosFfmpeg.get(url.url.toString())?.let { return it }

        return getAndCacheRemoteVideoFfmpeg(url, isM3u8, isMpd, isAudioCheck)
    }

    override fun getVideoInfo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        cachedVideos.get(url.url.toString())?.let { return it }

        return getAndCacheRemoteVideo(url, isM3u8OrMpd, isAudioCheck)
    }

    override fun saveVideoInfo(videoInfo: VideoInfo) {
        cachedVideos.put(videoInfo.originalUrl, videoInfo)
    }

    private fun getAndCacheRemoteVideoFfmpeg(
        url: Request,
        isM3u8: Boolean,
        isMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        val videoInfo = remoteDataSource.getVideoInfoBySuperXDetector(url, isM3u8, isMpd, isAudioCheck)
        if (videoInfo != null) {
            videoInfo.originalUrl = url.url.toString()
            cachedVideosFfmpeg.put(videoInfo.originalUrl, videoInfo)

            return videoInfo
        }
        return null
    }

    private fun getAndCacheRemoteVideo(
        url: Request,
        isM3u8OrMpd: Boolean,
        isAudioCheck: Boolean
    ): VideoInfo? {
        val videoInfo = remoteDataSource.getVideoInfo(url, isM3u8OrMpd, isAudioCheck)
        if (videoInfo != null) {
            videoInfo.originalUrl = url.url.toString()
            cachedVideos.put(videoInfo.originalUrl, videoInfo)

            return videoInfo
        }
        return null
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