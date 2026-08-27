package com.myAllVideoBrowser.util.telegram

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import java.net.URI
import java.util.UUID

object TelegramYtDlpMapper {
    fun parse(jsonText: String, requestedPost: TelegramPostUrl): TelegramPostResolution {
        val root = JsonParser.parseString(jsonText).asJsonObject
        val entryObjects = root.array("entries")
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(root)

        val mapped = entryObjects.mapIndexedNotNull { index, entry ->
            mapEntry(entry, root, requestedPost, index, entryObjects.size)
        }
        if (mapped.isEmpty()) {
            throw TelegramPostUnavailableException("Telegram post exposes no public video media.")
        }

        val videos = mapped.mapNotNull { it.video }
        val previewItems = mapped.map { it.previewItem }
        val channel = firstText(entryObjects, root, "channel", "uploader")
        val description = firstText(entryObjects, root, "description", "title")
        val thumbnail = previewItems.firstOrNull { it.thumbnail.isNotBlank() }?.thumbnail.orEmpty()

        return TelegramPostResolution(
            preview = TelegramPostPreview(
                postUrl = requestedPost.canonicalUrl,
                channel = channel,
                description = description,
                thumbnail = thumbnail,
                items = previewItems
            ),
            videos = videos
        )
    }

    private data class MappedEntry(
        val previewItem: TelegramPostPreviewItem,
        val video: VideoInfo?
    )

    private fun mapEntry(
        entry: JsonObject,
        root: JsonObject,
        requestedPost: TelegramPostUrl,
        index: Int,
        total: Int
    ): MappedEntry? {
        val originalPost = TelegramPostUrl.parse(entry.text("webpage_url")) ?: requestedPost
        val durationMs = secondsToMillis(entry.number("duration"))
        val thumbnail = entry.text("thumbnail")
        val rootHeaders = readHeaders(entry.obj("http_headers") ?: root.obj("http_headers"))
        val formats = entry.array("formats")
            ?.mapNotNull { element ->
                element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let { format ->
                    mapFormat(format, durationMs, rootHeaders)
                }
            }
            .orEmpty()
            .ifEmpty {
                listOfNotNull(mapDirectFormat(entry, durationMs, rootHeaders))
            }

        if (formats.isEmpty()) {
            return thumbnail.takeIf { it.isNotBlank() }?.let {
                MappedEntry(
                    previewItem = TelegramPostPreviewItem(
                        availability = TelegramMediaAvailability.POSTER_ONLY,
                        originalUrl = originalPost.singleUrl,
                        thumbnail = thumbnail,
                        durationMs = durationMs
                    ),
                    video = null
                )
            }
        }

        val baseTitle = entry.text("title")
            .ifBlank { entry.text("description") }
            .ifBlank { root.text("description") }
            .ifBlank { root.text("title") }
            .ifBlank { "Telegram ${originalPost.channel} ${originalPost.messageId}" }
        val title = if (total > 1) "$baseTitle (${index + 1}/$total)" else baseTitle
        val ext = entry.text("ext").ifBlank {
            formats.firstNotNullOfOrNull { it.ext?.takeIf(String::isNotBlank) } ?: "mp4"
        }
        val video = VideoInfo(
            id = UUID.randomUUID().toString(),
            title = title,
            ext = ext,
            thumbnail = thumbnail,
            duration = durationMs,
            originalUrl = originalPost.singleUrl,
            formats = VideFormatEntityList(formats),
            isRegularDownload = false,
            isLive = entry.boolean("is_live")
        )
        val firstMediaUrl = formats.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) }.orEmpty()
        return MappedEntry(
            previewItem = TelegramPostPreviewItem(
                availability = TelegramMediaAvailability.PLAYABLE,
                originalUrl = originalPost.singleUrl,
                mediaUrl = firstMediaUrl,
                thumbnail = thumbnail,
                durationMs = durationMs
            ),
            video = video
        )
    }

    private fun mapDirectFormat(
        entry: JsonObject,
        durationMs: Long,
        headers: Map<String, String>
    ): VideoFormatEntity? {
        val url = entry.text("url").takeIf(::isHttpUrl) ?: return null
        return VideoFormatEntity(
            formatId = entry.text("format_id").ifBlank { "best" },
            format = entry.text("format").ifBlank { "Telegram video" },
            formatNote = entry.text("format_note"),
            ext = entry.text("ext").ifBlank { inferExtension(url) },
            url = url,
            vcodec = entry.text("vcodec").ifBlank { "unknown" },
            acodec = entry.text("acodec").ifBlank { "unknown" },
            width = entry.int("width"),
            height = entry.int("height"),
            fileSize = entry.long("filesize"),
            fileSizeApproximate = entry.long("filesize_approx"),
            protocol = entry.text("protocol").ifBlank { null },
            httpHeaders = headers,
            duration = durationMs
        )
    }

    private fun mapFormat(
        format: JsonObject,
        durationMs: Long,
        rootHeaders: Map<String, String>
    ): VideoFormatEntity? {
        val url = format.text("url").takeIf(::isHttpUrl) ?: return null
        val headers = readHeaders(format.obj("http_headers")).ifEmpty { rootHeaders }
        val tbr = format.int("tbr")
        return VideoFormatEntity(
            formatId = format.text("format_id").ifBlank { "best" },
            format = format.text("format").ifBlank { "Telegram video" },
            formatNote = format.text("format_note"),
            ext = format.text("ext").ifBlank { inferExtension(url) },
            url = url,
            manifestUrl = format.text("manifest_url").ifBlank { null },
            vcodec = format.text("vcodec").ifBlank { "unknown" },
            acodec = format.text("acodec").ifBlank { "unknown" },
            width = format.int("width"),
            height = format.int("height"),
            fps = format.int("fps"),
            asr = format.int("asr"),
            tbr = tbr,
            abr = format.int("abr"),
            fileSize = format.long("filesize"),
            fileSizeApproximate = format.long("filesize_approx"),
            bitrate = tbr.takeIf { it > 0 }?.toLong()?.times(1_000L),
            protocol = format.text("protocol").ifBlank { null },
            httpHeaders = headers,
            duration = durationMs
        )
    }

    private fun firstText(entries: List<JsonObject>, root: JsonObject, vararg keys: String): String {
        keys.forEach { key ->
            entries.firstNotNullOfOrNull { it.text(key).takeIf(String::isNotBlank) }?.let {
                return it
            }
            root.text(key).takeIf(String::isNotBlank)?.let { return it }
        }
        return ""
    }

    private fun readHeaders(value: JsonObject?): Map<String, String> {
        if (value == null) return emptyMap()
        return value.entrySet().mapNotNull { (key, element) ->
            element.takeIf { it.isJsonPrimitive }?.asString?.let { key to it }
        }.toMap()
    }

    private fun secondsToMillis(seconds: Double): Long {
        if (!seconds.isFinite() || seconds <= 0.0) return 0L
        return (seconds * 1_000.0).toLong().coerceAtLeast(0L)
    }

    private fun inferExtension(url: String): String {
        return runCatching {
            URI(url).path.substringAfterLast('.', "mp4").takeIf { it.length in 2..5 } ?: "mp4"
        }.getOrDefault("mp4")
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    }

    private fun JsonObject.text(key: String): String = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        .orEmpty()

    private fun JsonObject.number(key: String): Double = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asDouble
        ?: 0.0

    private fun JsonObject.int(key: String): Int = number(key).toInt()

    private fun JsonObject.long(key: String): Long = number(key).toLong()

    private fun JsonObject.boolean(key: String): Boolean = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean
        ?: false

    private fun JsonObject.obj(key: String): JsonObject? = get(key)
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject

    private fun JsonObject.array(key: String) = get(key)
        ?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray
}
