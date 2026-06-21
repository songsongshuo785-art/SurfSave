package com.myAllVideoBrowser.util.downloaders

import androidx.core.content.FileProvider
import com.myAllVideoBrowser.DLApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadTaskLogger @Inject constructor(
    private val application: DLApplication
) {
    companion object {
        private const val MAX_LOG_BYTES = 256 * 1024
        private const val LOG_DIR = "download_logs"

        private val sensitiveLineRegex = Regex(
            "(?i)\\b(cookie|authorization|x-auth-token|set-cookie)\\b\\s*[:=]\\s*[^\\r\\n;]+"
        )
        private val sensitiveQueryRegex = Regex(
            "(?i)([?&](token|access_token|refresh_token|auth|authorization|signature|sig|key|password|pass|session|cookie)=)[^&\\s]+"
        )

        fun redact(input: String): String {
            return input
                .replace(sensitiveLineRegex) { match ->
                    "${match.groupValues[1]}=<redacted>"
                }
                .replace(sensitiveQueryRegex) { match ->
                    "${match.groupValues[1]}<redacted>"
                }
        }
    }

    fun logFile(taskId: String): File {
        val safeName = taskId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(application.filesDir, LOG_DIR).resolve("$safeName.log")
    }

    fun logPath(taskId: String): String = logFile(taskId).absolutePath

    fun info(taskId: String, message: String) {
        append(taskId, "INFO", message, null)
    }

    fun warn(taskId: String, message: String, throwable: Throwable? = null) {
        append(taskId, "WARN", message, throwable)
    }

    fun error(taskId: String, message: String, throwable: Throwable? = null) {
        append(taskId, "ERROR", message, throwable)
    }

    fun readTail(taskId: String, maxLines: Int = 120): String {
        val file = logFile(taskId)
        if (!file.exists()) {
            return ""
        }
        return file.readLines(Charsets.UTF_8).takeLast(maxLines).joinToString("\n")
    }

    fun shareUri(taskId: String) =
        FileProvider.getUriForFile(application, "${application.packageName}.provider", logFile(taskId))

    @Synchronized
    private fun append(taskId: String, level: String, message: String, throwable: Throwable?) {
        val file = logFile(taskId)
        file.parentFile?.mkdirs()
        rotateIfNeeded(file)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val stack = throwable?.let { "\n${stackTraceToString(it)}" }.orEmpty()
        val line = redact("$timestamp $level $message$stack")
        file.appendText("$line\n", Charsets.UTF_8)
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            file.writeText(file.readLines(Charsets.UTF_8).takeLast(120).joinToString("\n"), Charsets.UTF_8)
            file.appendText("\n--- log truncated ---\n", Charsets.UTF_8)
        }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
