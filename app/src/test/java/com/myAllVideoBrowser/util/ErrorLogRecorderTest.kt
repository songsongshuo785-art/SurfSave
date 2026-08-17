package com.myAllVideoBrowser.util

import android.app.Application
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ErrorLogRecorder 专项测试：脱敏、持久化、原子替换（无 .tmp 残留）。
 *
 * 覆盖同事审查提出的问题：日志必须脱敏（Cookie/token/签名参数不落盘）、
 * 覆盖式写入后能读回、并发安全（record 同步 + 临时文件 rename）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ErrorLogRecorderTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    private fun initContextUtils() {
        ContextUtils.initApplicationContext(context)
    }

    /**
     * 注入纯文件原子写器（tmp + rename + 回退覆盖），规避 Windows/Robolectric 下
     * AtomicFile.finishWrite 无法覆盖已存在文件的限制。业务逻辑（脱敏/覆盖/并发/格式）
     * 与 AtomicFile 自身的提交语义解耦：后者由 atomicWrite_whenCommitFails 单独验证。
     */
    private fun withPlainFileWriter(block: () -> Unit) {
        val original = ErrorLogRecorder.atomicWriter
        try {
            ErrorLogRecorder.atomicWriter = { target, bytes ->
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".testtmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
            }
            block()
        } finally {
            ErrorLogRecorder.atomicWriter = original
        }
    }

    private fun tmpArtifacts(): List<File> {
        val dir = File(context.filesDir, "error_logs")
        return listOf(
            File(dir, "${ErrorLogRecorder.FILE_NAME}.new"),
            File(dir, "${ErrorLogRecorder.FILE_NAME}.bak"),
            File(dir, "${ErrorLogRecorder.FILE_NAME}.testtmp")
        )
    }

    private fun logFile(): File {
        return File(File(context.filesDir, "error_logs"), ErrorLogRecorder.FILE_NAME)
    }

    @Test
    fun record_writesReadableLog() {
        initContextUtils()
        withPlainFileWriter {
            ErrorLogRecorder.record("测试标题", "sourceUri=file:///tmp/a.mp4 设备API=35")
        }
        val text = ErrorLogRecorder.readLatest()
        assertNotNull(text)
        assertTrue(text!!.contains("测试标题"))
        assertTrue(text.contains("sourceUri=file:///tmp/a.mp4"))
    }

    @Test
    fun record_redactsSensitiveValues() {
        initContextUtils()
        ErrorLogRecorder.record(
            "测试标题",
            "Cookie=session=secret; secondary=also-secret\nAuthorization=Bearer abc\n" +
                "query=https://x.com/v?a=1&token=xyz&signature=123\n" +
                "quality=720"
        )
        val text = ErrorLogRecorder.readLatest()
        assertNotNull(text)
        // 敏感值被脱敏
        assertFalse(text!!.contains("secret"))
        assertFalse(text.contains("also-secret"))
        assertFalse(text.contains("Bearer abc"))
        assertFalse(text.contains("xyz"))
        assertFalse(text.contains("signature=123"))
        // 非敏感内容保留
        assertTrue(text.contains("quality=720"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun record_overwritesPreviousEntry() {
        initContextUtils()
        withPlainFileWriter {
            ErrorLogRecorder.record("第一次", "detail=one")
            ErrorLogRecorder.record("第二次", "detail=two")
        }
        val text = ErrorLogRecorder.readLatest()
        assertTrue(text!!.contains("第二次"))
        assertFalse(text.contains("第一次"))
    }

    @Test
    fun record_leavesNoTmpArtifact() {
        initContextUtils()
        withPlainFileWriter {
            ErrorLogRecorder.record("标题", "detail=content")
        }
        tmpArtifacts().forEach { assertFalse(it.path, it.exists()) }
        assertTrue(logFile().exists())
    }

    @Test
    fun record_withThrowableWritesStack() {
        initContextUtils()
        withPlainFileWriter {
            val failure = IllegalStateException("boom")
            ErrorLogRecorder.record("标题", "detail", failure)
        }
        val text = ErrorLogRecorder.readLatest()
        assertTrue(text!!.contains("IllegalStateException"))
        assertTrue(text.contains("boom"))
    }

    @Test
    fun concurrentRecords_leaveCompleteNonTruncatedLog() {
        initContextUtils()
        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)

        withPlainFileWriter {
            // 多线程并发写入：每个线程写一大段内容，验证日志不截断、不混入半截内容
            for (i in 0 until threads) {
                executor.submit {
                    startGate.await()
                    val payload = "task-$i-" + "X".repeat(500) + "-end-$i"
                    ErrorLogRecorder.record("并发标题-$i", "detail=$payload")
                    doneGate.countDown()
                }
            }
            startGate.countDown()
            assertTrue("并发写入应在超时前完成", doneGate.await(10, TimeUnit.SECONDS))
            executor.shutdown()
        }

        val text = ErrorLogRecorder.readLatest()
        assertNotNull(text)
        // 最终内容必须是某个任务完整写入的（不是多任务内容拼接/截断）
        val complete = (0 until threads).any { i ->
            text!!.contains("task-$i-" + "X".repeat(500) + "-end-$i")
        }
        assertTrue("日志应包含某个完整任务的内容", complete)
        // 不包含任何任务的后半截（说明写入是原子的，未混入半截）
        assertFalse(text!!.contains("-end-") && text.split("-end-").size > 2)
        tmpArtifacts().forEach { assertFalse(it.path, it.exists()) }
    }

    @Test
    fun atomicWrite_whenCommitFails_restoresPreviousCompleteReport() {
        val target = File(context.cacheDir, "atomic-error-report.txt")
        target.writeText("stable-report", Charsets.UTF_8)

        var thrown: Throwable? = null
        try {
            ErrorLogRecorder.writeAtomically(
                target,
                "incomplete-new-report".toByteArray(Charsets.UTF_8)
            ) {
                throw IllegalStateException("simulated crash before commit")
            }
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(thrown is IllegalStateException)
        assertEquals("stable-report", target.readText(Charsets.UTF_8))
        assertFalse(File(target.path + ".new").exists())
        assertFalse(File(target.path + ".bak").exists())
    }
}
