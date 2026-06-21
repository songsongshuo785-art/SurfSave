package com.myAllVideoBrowser.data.local.room.entity

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*

@Entity(tableName = "VideoInfo")
data class VideoInfo(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "downloadUrls")
    @SerializedName("urls")
    @Expose
    var downloadUrls: List<DownloadRequestData> = emptyList(),

    @ColumnInfo(name = "title")
    @SerializedName("title")
    @Expose
    var title: String = "",

    @ColumnInfo(name = "ext")
    @SerializedName("ext")
    @Expose
    var ext: String = "",

    @ColumnInfo(name = "thumbnail")
    @SerializedName("thumbnail")
    @Expose
    var thumbnail: String = "",

    @ColumnInfo(name = "duration")
    @SerializedName("duration")
    @Expose
    var duration: Long = 0,

    @ColumnInfo(name = "originalUrl")
    var originalUrl: String = "",

    @ColumnInfo(name = "formats")
    @SerializedName("formats")
    @Expose
    var formats: VideFormatEntityList = VideFormatEntityList(emptyList()),

    @ColumnInfo(name = "isRegular")
    @SerializedName("isRegular")
    @Expose
    var isRegularDownload: Boolean = false,

    @ColumnInfo(name = "isLive", defaultValue = "0")
    @SerializedName("isLive")
    @Expose
    var isLive: Boolean = false,

    @ColumnInfo(name = "isDetectedBySuperX", defaultValue = "0")
    @SerializedName("isDetectedBySuperX")
    @Expose
    var isDetectedBySuperX: Boolean = false
) {

    val firstUrlToString: String
        get() {
            if (downloadUrls.isNotEmpty()) {
                return downloadUrls.firstOrNull()?.url.toString()
            }
            return ""
        }

    val name
        get() = "$title.$ext"

    val isM3u8: Boolean
        get() {
            return formats.formats.any { format -> format.isM3u8 }
        }

    val isMpd: Boolean
        get() {
            return formats.formats.any { format -> format.isMpd }
        }
    val isMaster get() = isM3u8 && formats.formats.size > 1

    fun isTikTokVideo(): Boolean {
        return originalUrl.contains("tiktok.com")
    }
}

class FormatsConverter {
    @TypeConverter
    fun convertFormatListToJSONString(formatList: VideFormatEntityList): String =
        Gson().toJson(formatList)

    @TypeConverter
    fun convertJSONStringToFormatList(jsonString: String): VideFormatEntityList =
        Gson().fromJson(jsonString, VideFormatEntityList::class.java)
}

data class DownloadRequestData(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
) {
    fun toOkHttpRequest(): Request {
        val requestBody = body
            ?.takeIf { it.isNotBlank() && !method.equals("GET", ignoreCase = true) }
            ?.toRequestBody(null)

        return Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .method(method.ifBlank { "GET" }, requestBody)
            .build()
    }

    companion object {
        fun fromRequest(source: Request): DownloadRequestData {
            val headers = mutableMapOf<String, String>()
            for (headerName in source.headers.names()) {
                headers[headerName] = source.headers[headerName] ?: ""
            }

            return DownloadRequestData(
                url = source.url.toString(),
                method = source.method,
                headers = headers,
                body = null
            )
        }
    }
}

fun Request.toDownloadRequestData(): DownloadRequestData = DownloadRequestData.fromRequest(this)

class DownloadUrlsConverter {
    companion object {
        const val URL_KEY = "url"
        const val METHOD = "method"
        const val BODY = "body"
        const val HEADERS = "headers"
    }

    @TypeConverter
    fun fromSource(sourceList: List<DownloadRequestData>): String = Gson().toJson(sourceList)

    @TypeConverter
    fun toSource(inputList: String): List<DownloadRequestData> {
        if (inputList.isBlank()) {
            return emptyList()
        }

        val type = object : TypeToken<List<DownloadRequestData>>() {}.type
        return runCatching {
            Gson().fromJson<List<DownloadRequestData>>(inputList, type).orEmpty()
        }.getOrElse {
            parseLegacySourceList(inputList)
        }
    }

    private fun parseLegacySourceList(inputList: String): List<DownloadRequestData> {
        return inputList.split(">^^^<")
            .filter { it.isNotBlank() }
            .mapNotNull { input ->
                runCatching {
                    val json = JSONObject(input)
                    val headersJson = JSONObject(json.optString(HEADERS, "{}"))
                    val headers = mutableMapOf<String, String>()
                    val keys = headersJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        headers[key] = headersJson.optString(key, "")
                    }

                    DownloadRequestData(
                        url = json.optString(URL_KEY),
                        method = json.optString(METHOD, "GET"),
                        headers = headers,
                        body = json.optString(BODY)
                            .takeIf { it.isNotBlank() && it != "null" }
                    )
                }.getOrNull()
            }
    }
}
