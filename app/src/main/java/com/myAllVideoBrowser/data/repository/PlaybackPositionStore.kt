package com.myAllVideoBrowser.data.repository

import android.content.Context
import androidx.core.content.edit
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.ui.main.player.SavedPlaybackPosition
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class PlaybackPositionStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(mediaKey: String): SavedPlaybackPosition? {
        if (mediaKey.isBlank()) return null
        val encoded = preferences.getString(storageKey(mediaKey), null) ?: return null
        return runCatching { JSON.decodeFromString<PersistedPosition>(encoded).toDomain() }
            .getOrNull()
    }

    fun save(mediaKey: String, positionMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (mediaKey.isBlank() || positionMs < 0L) return
        preferences.edit {
            putString(
                storageKey(mediaKey),
                JSON.encodeToString(
                    PersistedPosition(
                        positionMs = positionMs,
                        durationMs = durationMs.coerceAtLeast(0L),
                        updatedAtMs = nowMs
                    )
                )
            )
        }
        prune(nowMs)
    }

    fun remove(mediaKey: String) {
        if (mediaKey.isBlank()) return
        preferences.edit { remove(storageKey(mediaKey)) }
    }

    private fun prune(nowMs: Long) {
        val entries = mutableListOf<Pair<String, PersistedPosition>>()
        val invalidKeys = mutableListOf<String>()
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith(ENTRY_PREFIX)) return@forEach
            if (value !is String) {
                invalidKeys += key
                return@forEach
            }
            val position = runCatching { JSON.decodeFromString<PersistedPosition>(value) }.getOrNull()
            if (position == null) invalidKeys += key else entries += key to position
        }
        val expired = entries.filter { (_, position) ->
            nowMs - position.updatedAtMs > MAX_AGE_MS
        }.map { it.first }
        val overflow = entries
            .filter { it.first !in expired }
            .sortedByDescending { it.second.updatedAtMs }
            .drop(MAX_ENTRIES)
            .map { it.first }
        val keysToRemove = (invalidKeys + expired + overflow).distinct()
        if (keysToRemove.isNotEmpty()) preferences.edit { keysToRemove.forEach { remove(it) } }
    }

    /**
     * 诊断用短标识：与存储键同源（同一个 sha256），但**不包含也不暴露媒体 URL**。
     * 日志只能打印本方法的返回值，不得直接打印 mediaKey。
     */
    fun shortId(mediaKey: String): String {
        if (mediaKey.isBlank()) return ""
        return storageKey(mediaKey).removePrefix(ENTRY_PREFIX).take(SHORT_ID_LENGTH)
    }

    private fun storageKey(mediaKey: String): String = ENTRY_PREFIX + sha256(mediaKey)

    @Serializable
    private data class PersistedPosition(
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long
    ) {
        fun toDomain() = SavedPlaybackPosition(positionMs, durationMs, updatedAtMs)
    }

    companion object {
        internal const val PREFS_NAME = "playback_position_store"
        private const val ENTRY_PREFIX = "entry_"
        private const val SHORT_ID_LENGTH = 8
        private const val MAX_ENTRIES = 200
        private const val MAX_AGE_MS = 90L * 24L * 60L * 60L * 1_000L
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun sha256(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
