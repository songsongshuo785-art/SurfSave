package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.ui.main.video.CompletedVideoMetadataIndex
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/**
 * 首次打开下载历史时，把 SurfSave 管理目录里没有绑定记录的旧文件补录为历史。
 *
 * 只扫描 `FileUtil.listFiles`，也就是公共 Download/SurfSave、外部私有、内部私有三处；
 * 不扫描系统媒体库。手工放进这些目录的文件会被标记为“来源未知的历史文件”。
 */
class HistoryBackfillManager(
    private val context: Context,
    private val progressRepository: ProgressRepository,
    private val fileUtil: FileUtil,
) {
    data class BackfillResult(
        val inserted: Int,
        val skipped: Int,
        val failed: Int,
    )

    fun backfillIfNeeded(): BackfillResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) {
            return BackfillResult(0, 0, 0)
        }
        return backfill(prefs)
    }

    private fun backfill(prefs: SharedPreferences): BackfillResult {
        val existing = runCatching { progressRepository.getProgressInfosOnce() }
            .getOrElse { error ->
                AppLogger.e("History backfill: failed to load progress", error)
                return BackfillResult(0, 0, 1)
            }
        val index = CompletedVideoMetadataIndex.from(existing)
        val usedDownloadIds = existing.mapTo(mutableSetOf()) { it.downloadId }
        var inserted = 0
        var skipped = 0
        var failed = 0

        val entries = runCatching { fileUtil.listFiles }.getOrElse { error ->
            AppLogger.e("History backfill: failed to scan media", error)
            return BackfillResult(0, 0, 1)
        }

        entries.forEach { entry ->
            if (index.find(entry.uri.toString(), entry.displayName) != null) {
                skipped += 1
                return@forEach
            }
            val fingerprint = legacyFingerprint(entry.uri, entry.displayName)
            val duplicate = runCatching {
                progressRepository.findDuplicateByFingerprint(fingerprint)
            }.getOrNull()
            if (duplicate != null) {
                skipped += 1
                return@forEach
            }
            val size = runCatching {
                fileUtil.getContentLength(context, entry.uri)
            }.getOrDefault(-1L)
            if (size <= 0L) {
                skipped += 1
                return@forEach
            }

            val info = ProgressInfo(
                id = UUID.randomUUID().toString(),
                downloadId = stableDownloadId(fingerprint, usedDownloadIds),
                videoInfo = VideoInfo(
                    title = entry.displayName.substringBeforeLast('.', entry.displayName),
                    ext = entry.displayName.substringAfterLast('.', ""),
                    originalUrl = "",
                ),
                downloadStatus = VideoTaskState.SUCCESS,
                progressDownloaded = size,
                progressTotal = size,
                completedAt = resolveMediaTimeMillis(entry.uri),
                infoLine = LEGACY_INFO_LINE,
                finalizationSource = LEGACY_SOURCE,
                downloadFingerprint = fingerprint,
                finalMediaUri = entry.uri.toString(),
                mediaBindingTrusted = true,
            )
            runCatching { progressRepository.saveProgressInfo(info) }
                .onSuccess {
                    usedDownloadIds += info.downloadId
                    inserted += 1
                }
                .onFailure { error ->
                    AppLogger.e("History backfill: failed to insert ${entry.uri}", error)
                    failed += 1
                }
        }

        if (failed == 0) {
            prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).apply()
        }
        AppLogger.d("History backfill: inserted=$inserted skipped=$skipped failed=$failed")
        return BackfillResult(inserted, skipped, failed)
    }

    private fun legacyFingerprint(uri: Uri, displayName: String): String {
        return "$LEGACY_FINGERPRINT_PREFIX${sha256("$uri|$displayName")}"
    }

    /**
     * 稳定负数 downloadId：同一身份重复回填也得到同一个值；
     * 真正幂等仍靠 downloadFingerprint。
     */
    private fun stableDownloadId(fingerprint: String, used: Set<Long>): Long {
        var seed = fingerprint
        var attempt = 0
        while (true) {
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            val raw = ByteBuffer.wrap(digest).long
            val candidate = if (raw == Long.MIN_VALUE) 0L else -abs(raw)
            if (candidate != 0L && candidate !in used) return candidate
            attempt += 1
            seed = "$fingerprint#$attempt"
        }
    }

    private fun resolveMediaTimeMillis(uri: Uri): Long {
        return when (uri.scheme) {
            "file", null -> uri.path?.let(::File)?.lastModified() ?: 0L
            "content" -> runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) * 1000L else 0L
                } ?: 0L
            }.getOrDefault(0L)
            else -> 0L
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFS_NAME = "history_backfill"
        private const val KEY_VERSION = "history_backfill_version"
        private const val CURRENT_VERSION = 1
        private const val LEGACY_SOURCE = "legacy_import"
        private const val LEGACY_INFO_LINE = "历史文件"
        private const val LEGACY_FINGERPRINT_PREFIX = "legacy:"
    }
}
