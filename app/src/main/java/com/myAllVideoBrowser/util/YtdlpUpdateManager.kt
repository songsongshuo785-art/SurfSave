package com.myAllVideoBrowser.util

import android.content.Context
import androidx.core.content.edit
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YtdlpUpdateManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    data class State(
        val versionName: String,
        val lastResult: String,
        val lastCheckedAt: Long
    )

    companion object {
        private const val PREF_NAME = "ytdlp_update_prefs"
        private const val KEY_LAST_RESULT = "LAST_RESULT"
        private const val KEY_LAST_CHECKED_AT = "LAST_CHECKED_AT"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun currentState(): State {
        val version = runCatching {
            YoutubeDL.getInstance().versionName(context)
        }.getOrElse {
            "unknown"
        } ?: "unknown"
        return State(
            versionName = version,
            lastResult = prefs.getString(KEY_LAST_RESULT, "") ?: "",
            lastCheckedAt = prefs.getLong(KEY_LAST_CHECKED_AT, 0L)
        )
    }

    suspend fun updateStable(): State = withContext(Dispatchers.IO) {
        val result = runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        }.fold(
            onSuccess = { status -> "Stable update: $status" },
            onFailure = { error -> "Stable update failed: ${error.message ?: error::class.java.simpleName}" }
        )
        prefs.edit {
            putString(KEY_LAST_RESULT, result)
            putLong(KEY_LAST_CHECKED_AT, System.currentTimeMillis())
        }
        currentState()
    }
}
