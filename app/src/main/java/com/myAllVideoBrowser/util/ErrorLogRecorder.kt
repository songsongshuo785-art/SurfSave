package com.myAllVideoBrowser.util

import android.util.AtomicFile
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近一次下载发布/移动失败的系统级诊断日志（覆盖式，仅保留最后一次）。
 *
 * 独立成对象的原因：它被 FileUtil 的多个 API 级别上下文调用（含 @RequiresApi(Q) 的
 * MediaStore 发布路径），抽离后避免 lint 将 API 29 的调用关系传播到普通方法调用点。
 *
 * 写入应用私有目录 error_logs/latest_download_error.txt；内容先经 DownloadTaskLogger.redact()
 * 脱敏。原子性由 Android AtomicFile 保证：提交前异常或进程中断时恢复上一份完整报告。
 * record/readLatest 均在同一把锁内执行，避免应用内并发读写看到中间状态。
 */
object ErrorLogRecorder {

    const val FILE_NAME = "latest_download_error.txt"

    private val lock = Any()

    /**
     * 原子写器：默认使用 Android AtomicFile（真机 POSIX rename 原子覆盖）。
     * 测试可注入纯文件实现（tmp + rename + 回退），规避 Windows/Robolectric 下
     * AtomicFile.finishWrite 无法覆盖已存在文件的限制，同时验证业务逻辑。
     */
    internal var atomicWriter: (File, ByteArray) -> Unit = { target, bytes ->
        writeAtomically(target, bytes)
    }
    /** 写入最近一次失败日志（覆盖式）。锁内进行，防止多任务并发失败互相覆盖。 */
    fun record(title: String, detail: String, throwable: Throwable? = null) {
        synchronized(lock) {
            runCatching {
                val dir = File(ContextUtils.getApplicationContext().filesDir, "error_logs")
                dir.mkdirs()
                val file = File(dir, FILE_NAME)
                val timestamp =
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val stack = throwable?.let { "\n" + it.stackTraceToString() }.orEmpty()
                val raw = buildString {
                    appendLine(timestamp)
                    appendLine("[标题] $title")
                    appendLine("[详情] $detail")
                    if (stack.isNotEmpty()) appendLine("[堆栈]$stack")
                }
                val redacted = DownloadTaskLogger.redact(raw)
                atomicWriter(file, redacted.toByteArray(Charsets.UTF_8))
            }.onFailure { error ->
                AppLogger.e("Failed to write latest download error log", error)
            }
        }
    }

    /**
     * 三种下载引擎共享的发布失败出口。任务日志与全局报告使用同一份 detail，避免跨层丢字段。
     */
    fun recordPublicationFailure(
        engine: String,
        taskId: String?,
        message: String,
        detail: String? = null,
        taskLogger: DownloadTaskLogger? = null,
        throwable: Throwable? = null
    ) {
        val normalizedTaskId = taskId.orEmpty()
        val report = detail?.takeIf { it.isNotBlank() } ?: message
        if (normalizedTaskId.isNotBlank() && taskLogger != null) {
            val taskMessage = buildString {
                append(engine)
                append(" media publication failed: ")
                append(message)
                if (report != message) {
                    append('\n')
                    append(report)
                }
            }
            taskLogger.error(
                normalizedTaskId,
                taskMessage,
                throwable
            )
        }
        val globalDetail = buildString {
            appendLine("引擎=$engine")
            if (normalizedTaskId.isNotBlank()) appendLine("任务ID=$normalizedTaskId")
            appendLine("原因=$message")
            append(report)
        }
        record("$engine 媒体发布失败", globalDetail, throwable)
    }

    /**
     * 可直接单测的 AtomicFile 写入边界。beforeCommit 用于模拟写完临时文件、提交前崩溃。
     */
    internal fun writeAtomically(
        target: File,
        bytes: ByteArray,
        beforeCommit: () -> Unit = {}
    ) {
        target.parentFile?.mkdirs()
        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            stream.write(bytes)
            stream.fd.sync()
            beforeCommit()
            atomicFile.finishWrite(stream)
            output = null
        } catch (error: Throwable) {
            output?.let { atomicFile.failWrite(it) }
            throw error
        }
    }

    /** 读取最近一次失败日志文本；无记录时返回 null。 */
    fun readLatest(): String? {
        synchronized(lock) {
            return runCatching {
                val dir = File(ContextUtils.getApplicationContext().filesDir, "error_logs")
                val file = File(dir, FILE_NAME)
                val atomicFile = AtomicFile(file)
                if (!file.exists()) return@runCatching null
                atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
