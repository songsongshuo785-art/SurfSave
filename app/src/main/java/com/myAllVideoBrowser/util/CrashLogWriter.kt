package com.myAllVideoBrowser.util

import android.content.Context
import android.os.Build
import android.os.Environment
import com.myAllVideoBrowser.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashLogWriter private constructor(
    private val context: Context,
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        writeCrash(thread, throwable)
        previousHandler?.uncaughtException(thread, throwable)
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val report = buildReport(thread, throwable)
        val files = listOf(
            File(context.filesDir, "crash_logs/latest_crash.txt"),
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SuperVideoDownloaderCrash/latest_crash.txt"
            )
        )

        files.forEach { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(report)
            }
        }
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val stack = StringWriter().also {
            throwable.printStackTrace(PrintWriter(it))
        }.toString()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())

        return buildString {
            appendLine("time=$now")
            appendLine("thread=${thread.name} identity=${System.identityHashCode(thread)}")
            appendLine("appId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            appendLine("diagnosticBuild=webview-intercept-thread-fix-20260503-2315")
            appendLine("debug=${BuildConfig.DEBUG}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine()
            appendLine(stack)
        }
    }

    companion object {
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashLogWriter) {
                return
            }
            Thread.setDefaultUncaughtExceptionHandler(
                CrashLogWriter(context.applicationContext, current)
            )
        }
    }
}
