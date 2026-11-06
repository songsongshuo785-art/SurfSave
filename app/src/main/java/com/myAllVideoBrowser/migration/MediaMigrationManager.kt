package com.myAllVideoBrowser.migration

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.FileUtil
import java.io.File

enum class StorageTarget {
    PUBLIC,
    PRIVATE_INTERNAL,
    PRIVATE_EXTERNAL,
}

sealed class MigrationResult {
    data class Success(val targetUri: Uri) : MigrationResult()
    /** DB 已指向新目标，但旧文件（通常是公共 MediaStore）没有删除成功。 */
    data class SuccessSourceNotDeleted(val targetUri: Uri) : MigrationResult()
    data class NeedsAuth(
        val intentSender: IntentSender,
        val pending: PendingMigration,
    ) : MigrationResult()

    data class Failed(val reason: String) : MigrationResult()
    data object Skipped : MigrationResult()
}

data class PendingMigration(
    val video: LocalVideo,
    val target: StorageTarget,
    val targetUri: Uri,
    val retryUri: Uri?,
    val verificationUri: Uri,
)

/**
 * 公共下载目录 / 内部私有 / 外部私有之间的事务化迁移。
 *
 * 顺序固定为：copy → verify → 更新 ProgressInfo → 删除源。
 * 一旦 DB 已指向新目标，后续删源失败只返回 [MigrationResult.SuccessSourceNotDeleted]，
 * 不回滚新文件，避免把已经一致的新状态再次破坏。
 */
class MediaMigrationManager(
    private val context: Context,
    private val fileUtil: FileUtil,
    private val progressRepository: ProgressRepository,
) {
    fun currentTarget(uri: Uri): StorageTarget? {
        val path = uri.path.orEmpty()
        val internalDir = File(context.filesDir, FileUtil.FOLDER_NAME).absolutePath
        val externalDir = context.getExternalFilesDir(null)
            ?.let { File(it, FileUtil.FOLDER_NAME).absolutePath }
        return when {
            uri.scheme == "content" && path.contains("/downloads/") -> StorageTarget.PUBLIC
            path.startsWith(fileUtil.publicDownloadsDir.absolutePath) -> StorageTarget.PUBLIC
            path.startsWith(internalDir) -> StorageTarget.PRIVATE_INTERNAL
            externalDir != null && path.startsWith(externalDir) -> StorageTarget.PRIVATE_EXTERNAL
            else -> null
        }
    }

    fun availableTargets(current: StorageTarget?): List<StorageTarget> =
        StorageTarget.entries.filter { it != current && targetDir(it) != null }

    fun targetDir(target: StorageTarget): File? = when (target) {
        StorageTarget.PUBLIC -> fileUtil.publicDownloadsDir
        StorageTarget.PRIVATE_INTERNAL -> File(context.filesDir, FileUtil.FOLDER_NAME)
        StorageTarget.PRIVATE_EXTERNAL -> context.getExternalFilesDir(null)
            ?.let { File(it, FileUtil.FOLDER_NAME) }
    }

    fun migrate(video: LocalVideo, target: StorageTarget): MigrationResult {
        val source = video.uri
        val current = currentTarget(source)
        if (current == target) return MigrationResult.Skipped
        val dir = targetDir(target)
            ?: return MigrationResult.Failed("Target storage is unavailable")

        val targetUri = when (target) {
            StorageTarget.PUBLIC -> {
                when (val published = fileUtil.publishMediaCopy(context, source, video.name)) {
                    is FileUtil.PublishResult.Failed -> return MigrationResult.Failed(published.reason)
                    is FileUtil.PublishResult.Success -> published.uri
                }
            }

            StorageTarget.PRIVATE_INTERNAL,
            StorageTarget.PRIVATE_EXTERNAL -> {
                val copied = fileUtil.copyMediaToPrivateDir(context, source, dir, video.name)
                if (!copied.ok || copied.uri == null) {
                    return MigrationResult.Failed(copied.reason ?: "Copy failed")
                }
                copied.uri
            }
        }

        val replacedRows = runCatching {
            progressRepository.replaceFinalMediaUri(source.toString(), targetUri.toString())
        }.getOrElse { error ->
            AppLogger.e("Migration: failed to update metadata", error)
            0
        }
        if (replacedRows <= 0) {
            cleanupTarget(target, targetUri)
            return MigrationResult.Failed("Persistent metadata could not be updated")
        }

        return when (current) {
            StorageTarget.PUBLIC -> when (val deleted = fileUtil.deleteMedia(context, source)) {
                is FileUtil.DeleteMediaResult.Success -> MigrationResult.Success(targetUri)
                is FileUtil.DeleteMediaResult.NeedsAuth -> MigrationResult.NeedsAuth(
                    deleted.intentSender,
                    PendingMigration(
                        video = video,
                        target = target,
                        targetUri = targetUri,
                        retryUri = deleted.retryUri,
                        verificationUri = deleted.verificationUri,
                    ),
                )

                is FileUtil.DeleteMediaResult.Failed ->
                    MigrationResult.SuccessSourceNotDeleted(targetUri)
            }

            else -> {
                if (fileUtil.deletePublishedSource(context, source)) {
                    MigrationResult.Success(targetUri)
                } else {
                    MigrationResult.SuccessSourceNotDeleted(targetUri)
                }
            }
        }
    }

    /** 授权完成后重试删除公共源；返回是否已确认源消失。 */
    fun completeSourceDeletion(pending: PendingMigration): Boolean {
        return if (pending.retryUri == null) {
            fileUtil.isUriDefinitelyAbsent(context, pending.verificationUri)
        } else {
            fileUtil.deleteMedia(context, pending.retryUri) is FileUtil.DeleteMediaResult.Success
        }
    }

    private fun cleanupTarget(target: StorageTarget, uri: Uri) {
        when (target) {
            StorageTarget.PUBLIC -> fileUtil.cleanupPublishedMedia(context, uri)
            StorageTarget.PRIVATE_INTERNAL,
            StorageTarget.PRIVATE_EXTERNAL -> uri.path?.let(::File)?.delete()
        }
    }
}
