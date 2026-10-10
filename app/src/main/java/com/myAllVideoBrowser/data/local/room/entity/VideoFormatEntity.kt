package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName
import java.util.*

@Entity(tableName = "VideoFormat")
data class VideoFormatEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "asr")
    @SerializedName("asr")
    @Expose
    val asr: Int = 0,

    @ColumnInfo(name = "tbr")
    @SerializedName("tbr")
    @Expose
    val tbr: Int = 0,

    @ColumnInfo(name = "abr")
    @SerializedName("abr")
    @Expose
    val abr: Int = 0,

    @ColumnInfo(name = "format")
    @SerializedName("format")
    @Expose
    val format: String? = null,

    @ColumnInfo(name = "formatId")
    @SerializedName("formatId")
    @Expose
    val formatId: String? = null,

    @ColumnInfo(name = "formatNote")
    @SerializedName("formatNote")
    @Expose
    val formatNote: String? = null,

    @ColumnInfo(name = "ext")
    @SerializedName("ext")
    @Expose
    val ext: String? = null,

    @ColumnInfo(name = "preference")
    @SerializedName("preference")
    @Expose
    val preference: Int = 0,

    @ColumnInfo(name = "vcodec")
    @SerializedName("vcodec")
    @Expose
    val vcodec: String? = null,

    @ColumnInfo(name = "acodec")
    @SerializedName("acodec")
    @Expose
    val acodec: String? = null,

    @ColumnInfo(name = "width")
    @SerializedName("width")
    @Expose
    val width: Int = 0,

    @ColumnInfo(name = "height")
    @SerializedName("height")
    @Expose
    val height: Int = 0,

    @ColumnInfo(name = "fileSize")
    @SerializedName("fileSize")
    @Expose
    val fileSize: Long = 0,

    @ColumnInfo(name = "fileSizeApproximate")
    @SerializedName("fileSizeApproximate")
    @Expose
    val fileSizeApproximate: Long = 0,

    @ColumnInfo(name = "fps")
    @SerializedName("fps")
    @Expose
    val fps: Int = 0,

    @ColumnInfo(name = "url")
    @SerializedName("url")
    @Expose
    val url: String? = null,

    @ColumnInfo(name = "manifestUrl")
    @SerializedName("manifestUrl")
    @Expose
    val manifestUrl: String? = null,

    @ColumnInfo(name = "protocol")
    @SerializedName("protocol")
    @Expose
    val protocol: String? = null,

    @ColumnInfo(name = "httpHeaders")
    @SerializedName("httpHeaders")
    @Expose
    val httpHeaders: Map<String, String>? = null,

    /** Original manifest request URL, retained so authenticated redirects can be replayed safely. */
    @ColumnInfo(name = "manifestRequestUrl")
    @SerializedName("manifestRequestUrl")
    @Expose
    val manifestRequestUrl: String? = null,

    /** Headers scoped to [manifestRequestUrl]; concrete targets must re-apply origin isolation. */
    @ColumnInfo(name = "manifestRequestHeaders")
    @SerializedName("manifestRequestHeaders")
    @Expose
    val manifestRequestHeaders: Map<String, String>? = null,

    @ColumnInfo(name = "bitrate")
    @SerializedName("bitrate")
    @Expose
    val bitrate: Long? = null,

    @ColumnInfo(name = "duration")
    @SerializedName("duration")
    @Expose
    val duration: Long? = null,

    @ColumnInfo(name = "videoOnlyUrl")
    @SerializedName("videoOnlyUrl")
    @Expose
    val videoOnlyUrl: String? = null,

    @ColumnInfo(name = "audioOnlyUrl")
    @SerializedName("audioOnlyUrl")
    @Expose
    val audioOnlyUrl: String? = null,

    /**
     * 下载计划（[com.myAllVideoBrowser.util.media.DownloadStrategy] 的 name）。
     *
     * 必须 nullable：`RoomConverter` 用裸 Gson 反序列化旧 JSON，Gson 不会注入 Kotlin 默认参数值，
     * 因此非空默认值在旧数据上会变成 null（NPE 风险）。旧数据统一交给
     * [com.myAllVideoBrowser.util.media.DownloadStrategyResolver] 推断，并打 LEGACY_STRATEGY 日志。
     *
     * 本字段是 format 上的 JSON 属性（`VideoFormatEntity` 不在 Room 的 entities 列表里），
     * 所以新增字段**不需要** DB_VERSION 迁移；同时禁止提升 `RoomConverter.CURRENT_VERSION`。
     */
    @SerializedName("downloadStrategy")
    @Expose
    val downloadStrategy: String? = null,

    /** 媒体来源页面，仅用于展示 / Referer / 诊断，**不参与下载路由**。 */
    @SerializedName("sourcePageUrl")
    @Expose
    val sourcePageUrl: String? = null,

    /**
     * 产生本 format 的 formatId 的那一次 yt-dlp 实际输入 URL。
     * 仅 YTDLP_FORMAT / PAGE_EXTRACTOR 有意义；显式策略下缺失即不变量违规（执行路径直接失败）。
     */
    @SerializedName("extractorInputUrl")
    @Expose
    val extractorInputUrl: String? = null
) {
    val isM3u8: Boolean
        get() {
            val protocolName = protocol.orEmpty().lowercase(Locale.US)
            return formatId?.startsWith("hls", ignoreCase = true) == true ||
                protocolName.contains("m3u8") ||
                sequenceOf(url, manifestUrl).filterNotNull().any {
                    it.substringBefore('#').substringBefore('?')
                        .endsWith(".m3u8", ignoreCase = true)
                }
        }

    val isMpd: Boolean
        get() {
            val protocolName = protocol.orEmpty().lowercase(Locale.US)
            return formatId?.startsWith("mpd", ignoreCase = true) == true ||
                formatId?.startsWith("dash", ignoreCase = true) == true ||
                protocolName.contains("dash") ||
                sequenceOf(url, manifestUrl).filterNotNull().any {
                    it.substringBefore('#').substringBefore('?')
                        .endsWith(".mpd", ignoreCase = true)
                }
        }
}

data class VideFormatEntityList(
    val formats: List<VideoFormatEntity>
)
