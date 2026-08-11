package com.myAllVideoBrowser.util

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

class RoomConverter {

    private val gson = Gson()

    @TypeConverter
    fun convertJsonToVideo(json: String): VideoInfo {
        if (json.isBlank()) {
            throw IllegalArgumentException("Stored VideoInfo JSON is blank")
        }

        val root = try {
            JsonParser.parseString(json)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Stored VideoInfo JSON is invalid", error)
        }
        if (!root.isJsonObject) {
            throw IllegalArgumentException("Stored VideoInfo JSON must be an object")
        }

        val rootObject = root.asJsonObject
        val payload = if (rootObject.has(VERSION_KEY) || rootObject.has(PAYLOAD_KEY)) {
            readEnvelope(rootObject)
        } else {
            rootObject
        }

        return try {
            gson.fromJson(payload, VideoInfo::class.java)
                ?: throw IllegalArgumentException("Stored VideoInfo payload is null")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Stored VideoInfo payload is invalid", error)
        }
    }

    @TypeConverter
    fun convertListVideosToJson(video: VideoInfo): String {
        val envelope = JsonObject().apply {
            addProperty(VERSION_KEY, CURRENT_VERSION)
            add(PAYLOAD_KEY, gson.toJsonTree(video))
        }
        return gson.toJson(envelope)
    }

    private fun readEnvelope(envelope: JsonObject): JsonObject {
        val versionElement = envelope.get(VERSION_KEY)
            ?: throw IllegalArgumentException("Stored VideoInfo envelope is missing version")
        if (!versionElement.isJsonPrimitive || !versionElement.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("Stored VideoInfo envelope version must be a number")
        }
        val versionLiteral = versionElement.asString
        if (!INTEGER_VERSION.matches(versionLiteral)) {
            throw IllegalArgumentException(
                "Stored VideoInfo envelope version must be an integer"
            )
        }
        val version = versionLiteral.toIntOrNull()
            ?: throw IllegalArgumentException(
                "Stored VideoInfo envelope version is outside the supported integer range"
            )
        if (version != CURRENT_VERSION) {
            throw IllegalArgumentException("Unsupported VideoInfo envelope version: $version")
        }

        val payload = envelope.get(PAYLOAD_KEY)
            ?: throw IllegalArgumentException("Stored VideoInfo envelope is missing payload")
        if (!payload.isJsonObject) {
            throw IllegalArgumentException("Stored VideoInfo envelope payload must be an object")
        }
        return payload.asJsonObject
    }

    private companion object {
        const val CURRENT_VERSION = 1
        const val VERSION_KEY = "version"
        const val PAYLOAD_KEY = "payload"
        val INTEGER_VERSION = Regex("-?(0|[1-9]\\d*)")
    }
}
