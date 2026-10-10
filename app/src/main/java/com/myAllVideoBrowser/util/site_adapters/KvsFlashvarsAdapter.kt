package com.myAllVideoBrowser.util.site_adapters

import com.google.gson.JsonParser
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.util.MediaRequestHeaderPolicy
import com.myAllVideoBrowser.util.media.DownloadStrategy
import java.util.Locale

/**
 * 站点适配器：KVS 播放器页面（`#kt_player` + `window.flashvars`）的多清晰度清单。
 *
 * 为什么必须独立于 `WebTabFragment.KVS_PLAYER_RECOVERY_SCRIPT`：
 * 那个脚本会删除并重建播放器 DOM、改写 `video.src`，**只在播放器坏掉时**才允许跑，而且播放器健康时
 * 直接 `return 'video-ok'`。检测路径需要的是「只读地把页面已经拿到的清晰度清单读出来」，
 * 两者目标相反，共用脚本会导致正常播放的页面永远拿不到清晰度，或把正在播放的视频打断。
 *
 * 这里只做三件事：只读提取（[SCRIPT]）、字段解析与校验（[parse]）、映射成下载格式（[toVideoFormats]）。
 * 不判断“该挂到哪条候选”，也不碰播放器状态。
 */
internal object KvsFlashvarsAdapter {

    const val STATUS_OK = "ok"

    /**
     * 只读脚本。绝不修改 DOM、不设置 `video.src`、不改 `window.__superx*` 状态。
     * 返回 JSON 字符串；`status` 非 `ok` 时调用方直接当“本页无贡献”处理。
     */
    val SCRIPT: String = """
        (function() {
          try {
            if (!document.getElementById('kt_player')) {
              return JSON.stringify({ status: 'not-kvs' });
            }
            var vars = window.flashvars;
            if (!vars || !vars.video_url) {
              return JSON.stringify({ status: 'no-flashvars' });
            }

            var items = [];
            function add(url, label) {
              if (typeof url !== 'string') {
                return;
              }
              var value = url.replace(/&amp;/g, '&').trim();
              if (!/^https?:\/\//i.test(value)) {
                return;
              }
              for (var i = 0; i < items.length; i++) {
                if (items[i].url === value) {
                  return;
                }
              }
              items.push({
                url: value,
                label: typeof label === 'string' ? label.trim() : ''
              });
            }

            add(vars.video_url, vars.video_url_text);
            add(vars.video_alt_url, vars.video_alt_url_text);
            add(vars.video_alt_url2, vars.video_alt_url2_text);
            add(vars.video_alt_url3, vars.video_alt_url3_text);
            add(vars.video_alt_url4, vars.video_alt_url4_text);
            add(vars.video_alt_url5, vars.video_alt_url5_text);

            return JSON.stringify({ status: 'ok', pageUrl: location.href, items: items });
          } catch (e) {
            return JSON.stringify({
              status: 'error',
              message: String(e && e.message ? e.message : e)
            });
          }
        })()
    """.trimIndent()

    /** 解析结果。`items` 为空也是合法结果（页面是 KVS 但清单不可用），调用方据此写日志。 */
    data class Payload(
        val pageUrl: String,
        val items: List<Item>,
        val skippedUnknownTypeCount: Int,
        val invalidUrlCount: Int
    )

    data class Item(
        val url: String,
        val label: String,
        val kind: ResourceKind,
        val extension: String
    )

    /** 资源类型。无法判定的类型不会出现在结果里（[parse] 直接丢弃并计数）。 */
    enum class ResourceKind { DIRECT, HLS, DASH }

    private val directExtensions = setOf(
        "mp4", "m4v", "mov", "webm", "mkv", "avi", "flv", "ogv", "3gp",
        "mp3", "m4a", "aac", "ogg", "wav"
    )

    /**
     * 解析脚本回包。
     *
     * @return `null` 表示「本页没有可用的 KVS 清单」（非 KVS 页面、脚本报错、回包损坏），
     *         调用方必须原样退回既有网络嗅探链路。
     */
    fun parse(payload: String?): Payload? {
        val raw = payload?.trim().orEmpty()
        if (raw.isBlank()) {
            return null
        }
        // 用 Gson 而不是 org.json：本组件是纯解析层，必须能在普通 JVM 单测里跑
        // （org.json 在 unit test 里是 android.jar 的 stub）。
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
        if (!root.isJsonObject) {
            return null
        }
        val json = root.asJsonObject
        if (json.stringOrNull("status") != STATUS_OK) {
            return null
        }

        val array = json.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val items = mutableListOf<Item>()
        var skippedUnknownType = 0
        var invalidUrl = 0
        array.forEach { element ->
            if (!element.isJsonObject) {
                return@forEach
            }
            val entry = element.asJsonObject
            // 页面里的 flashvars 值可能是 HTML 转义过的（`&amp;`），脚本已做一次，这里再兜一次。
            val url = entry.stringOrNull("url").orEmpty().trim().replace("&amp;", "&")
            val label = entry.stringOrNull("label").orEmpty().trim().replace("&amp;", "&")
            if (!isAbsoluteHttpUrl(url)) {
                invalidUrl++
                return@forEach
            }
            val kind = classify(url)
            if (kind == null) {
                // 约束：类型无法确定时不猜（尤其不能当成 mp4）。
                skippedUnknownType++
                return@forEach
            }
            if (items.any { it.url == url }) {
                return@forEach
            }
            items += Item(
                url = url,
                // 标签原样保留（只去掉 HTML 转义），不解析、不推断分辨率。
                label = label,
                kind = kind,
                extension = extensionOf(url, kind)
            )
        }

        return Payload(
            pageUrl = json.stringOrNull("pageUrl").orEmpty().trim(),
            items = items,
            skippedUnknownTypeCount = skippedUnknownType,
            invalidUrlCount = invalidUrl
        )
    }

    /**
     * 映射成下载格式。
     *
     * - 类型决定策略：mp4 等直链 → `DIRECT_HTTP`；`.m3u8` → `HLS_MANIFEST`；`.mpd` → `DASH_MANIFEST`。
     * - `_text` 标签原样放进 `formatNote`/`format`；**不设置 height**（不凭空推断分辨率）。
     * - 请求头按 `MediaRequestHeaderPolicy` 的同源边界生成：同源才带 Cookie，跨域只带 Referer/UA。
     */
    fun toVideoFormats(
        payload: Payload,
        pageUrl: String,
        userAgent: String?,
        cookie: String?
    ): List<VideoFormatEntity> {
        val sourceHeaders = linkedMapOf<String, String>()
        if (!userAgent.isNullOrBlank()) {
            sourceHeaders["User-Agent"] = userAgent
        }
        if (pageUrl.isNotBlank()) {
            sourceHeaders["Referer"] = pageUrl
        }
        if (!cookie.isNullOrBlank()) {
            sourceHeaders["Cookie"] = cookie
        }

        return payload.items.mapIndexed { index, item ->
            val headers = MediaRequestHeaderPolicy.mergeForFormat(
                sourceHeaders = sourceHeaders,
                formatHeaders = emptyMap(),
                sourceUrl = pageUrl,
                mediaUrl = item.url
            )
            val label = item.label.takeIf { it.isNotBlank() }
            when (item.kind) {
                ResourceKind.DIRECT -> VideoFormatEntity(
                    formatId = "kvs-$index",
                    format = label,
                    formatNote = label,
                    ext = item.extension,
                    url = item.url,
                    downloadStrategy = DownloadStrategy.DIRECT_HTTP.name,
                    sourcePageUrl = pageUrl,
                    httpHeaders = headers
                )

                ResourceKind.HLS -> VideoFormatEntity(
                    formatId = "kvs-$index",
                    format = label,
                    formatNote = label,
                    ext = MANIFEST_EXTENSION,
                    url = item.url,
                    manifestUrl = item.url,
                    protocol = HLS_PROTOCOL,
                    manifestRequestUrl = item.url,
                    manifestRequestHeaders = headers,
                    httpHeaders = headers,
                    downloadStrategy = DownloadStrategy.HLS_MANIFEST.name,
                    sourcePageUrl = pageUrl
                )

                ResourceKind.DASH -> VideoFormatEntity(
                    formatId = "kvs-$index",
                    format = label,
                    formatNote = label,
                    ext = MANIFEST_EXTENSION,
                    url = item.url,
                    manifestUrl = item.url,
                    protocol = DASH_PROTOCOL,
                    manifestRequestUrl = item.url,
                    manifestRequestHeaders = headers,
                    httpHeaders = headers,
                    downloadStrategy = DownloadStrategy.DASH_MANIFEST.name,
                    sourcePageUrl = pageUrl
                )
            }
        }
    }

    private fun com.google.gson.JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        return element.asString
    }

    private fun classify(url: String): ResourceKind? {
        val extension = rawExtension(url)
        return when {
            extension == "m3u8" -> ResourceKind.HLS
            extension == "mpd" -> ResourceKind.DASH
            extension in directExtensions -> ResourceKind.DIRECT
            else -> null
        }
    }

    private fun extensionOf(url: String, kind: ResourceKind): String = when (kind) {
        // 清单下载落地后是分片拼接的容器，与 VideoServiceSuperX 的既有取值保持一致。
        ResourceKind.HLS, ResourceKind.DASH -> MANIFEST_EXTENSION
        ResourceKind.DIRECT -> rawExtension(url)
    }

    /**
     * 取「路径部分的扩展名」。
     * KVS 的直链常带尾部斜杠和签名参数（`.../1080.mp4/?br=..&hash=..`），因此先去掉 query/fragment 与尾部斜杠。
     */
    private fun rawExtension(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val path = withoutQuery.substringAfter("://", withoutQuery).substringAfter('/', "")
        return path.trimEnd('/').substringAfterLast('.', "").lowercase(Locale.US)
    }

    private fun isAbsoluteHttpUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return (lower.startsWith("http://") || lower.startsWith("https://")) && url.length > 8
    }

    private const val MANIFEST_EXTENSION = "mp4"
    private const val HLS_PROTOCOL = "m3u8_native"
    private const val DASH_PROTOCOL = "http_dash_segments"
}
