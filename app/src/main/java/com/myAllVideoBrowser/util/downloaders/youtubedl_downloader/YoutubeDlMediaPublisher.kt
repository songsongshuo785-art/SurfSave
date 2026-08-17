package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.DownloadedMediaValidator
import com.myAllVideoBrowser.util.ErrorLogRecorder
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * yt-dlp 下载完成后的媒体发布（移动到目标目录）。
 *
 * 失败时的诊断贯通（单一报告源，避免二次覆盖）：
 * - FileUtil.moveMediaWithReason 返回 MoveResult(reason, detail)：reason 为简洁原因（进 lastError），
 *   detail 为完整结构化报告（含现场上下文与堆栈）。
 * - 本类把同一份 detail 写入当前任务的 DownloadTaskLogger 与全局最近错误日志，不重复构造，
 *   避免底层详细现场被上层简化消息覆盖。
 */
@Singleton
open class YoutubeDlMediaPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileUtil: FileUtil,
    private val taskLogger: DownloadTaskLogger
) {
    /** 由调用方绑定当前任务（Worker 构造时设置），保证多任务并发时日志归属正确。 */
    @Volatile
    var currentTaskId: String = ""
        private set

    fun bindTask(taskId: String) {
        currentTaskId = taskId
    }

    open fun publish(sourcePath: String, targetPath: String): String? {
        if (targetPath.isBlank()) return fail("Finalization target is missing")

        val target = File(targetPath)
        val source = sourcePath.takeIf { it.isNotBlank() }?.let(::File)
        val existingTargetUri = fileUtil.resolveMediaUri(context, target)
        if (existingTargetUri != null) {
            if (source?.exists() == true) {
                return fail("Finalization target already exists while source remains")
            }
            val validationError = validateTarget(existingTargetUri)
            if (validationError != null) {
                return fail("Finalization target validation failed: $validationError")
            }
            return null
        }
        if (source == null) return fail("Finalization source is missing")

        if (!source.isFile) return fail("Finalization source no longer exists: $sourcePath")

        val moveResult = try {
            fileUtil.moveMediaWithReason(context, source.toUri(), target.toUri())
        } catch (error: Throwable) {
            FileUtil.MoveResult(
                ok = false,
                reason = error.message ?: "Error moving file",
                detail = error.stackTraceToString()
            )
        } ?: FileUtil.MoveResult(false, "moveMediaWithReason returned null")
        if (!moveResult.ok) {
            return failWithDetail(moveResult.reason ?: "Error moving file", moveResult.detail)
        }
        if (source.exists()) {
            return fail("Finalization source still exists after publication: $sourcePath")
        }

        val finalUri = fileUtil.resolveMediaUri(context, target)
            ?: return fail("Finalization target is missing after publication: $targetPath")
        val validationError = validateTarget(finalUri)
        if (validationError != null) {
            return fail("Finalization target validation failed: $validationError")
        }
        return null
    }

    /** 记录失败（无独立 detail 时用 message 兜底）：写任务日志 + 全局最近错误日志，返回 message。 */
    private fun fail(message: String): String {
        return failWithDetail(message, message)
    }

    /** 记录失败（带完整 detail）：任务日志与全局日志共用同一份 detail，避免二次覆盖丢现场。 */
    private fun failWithDetail(message: String, detail: String?): String {
        ErrorLogRecorder.recordPublicationFailure(
            engine = "yt-dlp",
            taskId = currentTaskId,
            message = message,
            detail = detail,
            taskLogger = taskLogger
        )
        return message
    }

    private fun validateTarget(targetUri: Uri): String? {
        return try {
            DownloadedMediaValidator.validate(context, targetUri)
        } catch (error: Throwable) {
            // 保留异常类型、完整堆栈与被校验 URI，供诊断矩阵定位
            "Downloaded media validation failed: ${error::class.java.simpleName}: ${error.message}\n" +
                "uri=$targetUri\n" + error.stackTraceToString()
        }
    }
}
