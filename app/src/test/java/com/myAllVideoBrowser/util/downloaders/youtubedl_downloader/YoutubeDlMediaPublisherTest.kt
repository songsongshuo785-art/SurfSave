package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.app.Application
import android.content.Context
import android.net.Uri
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.DownloadTaskLogger
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 发布安全回归测试：防止视频丢失和错误终态。
 * - 源和目标同时存在时禁止误恢复；
 * - 源消失、目标存在时允许崩溃恢复；
 * - 移动返回失败，即使目标出现也不能误报成功；
 * - 移动成功后必须解析并验证最终 URI。
 *
 * Mock 说明：Kotlin 非空参数与 Mockito matcher 交互脆弱，此处统一使用
 * `doAnswer {}.when(mock).method(anyContext(), ...)` 后缀形式（与
 * DownloadQueueManagerTest 一致），helper 先注册 matcher 再返回非 null 占位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YoutubeDlMediaPublisherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    private val taskLogger: DownloadTaskLogger = Mockito.mock(DownloadTaskLogger::class.java)


    private fun publisher(fileUtil: FileUtil): YoutubeDlMediaPublisher {
        return YoutubeDlMediaPublisher(context, fileUtil, taskLogger)
            .apply { bindTask("test-task") }
    }

    @Test
    fun sourceAndTargetBothExist_rejectsRecoveryWithoutMoving() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = mediaFile("source.mp4")
        val target = mediaFile("target.mp4")
        Mockito.doAnswer { uri(target) }
            .`when`(fileUtil).resolveMediaUri(anyContext(), anyFile())

        val error = publisher(fileUtil).publish(source.absolutePath, target.absolutePath)

        assertTrue("error=$error", error.orEmpty().contains("source remains"))
    }

    @Test
    fun targetExistsAndSourceIsAbsent_recoversAfterValidatingFinalUri() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = temporaryFolder.root.resolve("missing-source.mp4")
        val target = mediaFile("recovered.mp4")
        Mockito.doAnswer { uri(target) }
            .`when`(fileUtil).resolveMediaUri(anyContext(), anyFile())

        val error = publisher(fileUtil).publish(source.absolutePath, target.absolutePath)

        // 源消失、目标存在 → 允许恢复，不报错
        assertNull(error)
    }

    @Test
    fun moveFailure_staysFailedEvenWhenTargetAppears() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = mediaFile("move-source.mp4")
        val target = temporaryFolder.root.resolve("move-target.mp4")
        Mockito.doAnswer { null }
            .`when`(fileUtil).resolveMediaUri(anyContext(), anyFile())
        Mockito.doAnswer {
            writeMedia(target)
            FileUtil.MoveResult(
                ok = false,
                reason = "MediaStore publication failed (actual name=x path=y)",
                detail = "2026-01-01 00:00:00.000\n[原因] MediaStore publication failed\n[现场] actual name=x"
            )
        }.`when`(fileUtil).moveMediaWithReason(anyContext(), anyUri(), anyUri())

        val error = publisher(fileUtil).publish(source.absolutePath, target.absolutePath)

        // 移动失败 → 即使目标文件出现也不能误报成功
        assertNotNull(error)
        assertTrue("error=$error", error.orEmpty().contains("MediaStore publication failed"))
        assertTrue(target.isFile)
        // 详细报告写入任务日志（贯通到任务详情/分享）
        Mockito.verify(taskLogger).error(eq("test-task"), anyString(), ArgumentMatchers.isNull())
    }

    @Test
    fun successfulMove_resolvesAndValidatesFinalUri() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = mediaFile("successful-source.mp4")
        val target = temporaryFolder.root.resolve("successful-target.mp4")
        // 第一次 resolveMediaUri（移动前）返回 null，第二次（移动后）返回目标 uri
        var callCount = 0
        Mockito.doAnswer {
            callCount++
            if (callCount == 1) null else uri(target)
        }.`when`(fileUtil).resolveMediaUri(anyContext(), anyFile())
        Mockito.doAnswer {
            writeMedia(target)
            assertTrue(source.delete())
            FileUtil.MoveResult(true)
        }.`when`(fileUtil).moveMediaWithReason(anyContext(), anyUri(), anyUri())

        val error = publisher(fileUtil).publish(source.absolutePath, target.absolutePath)

        assertNull(error)
    }

    // Mockito 的 any(Class) 对对象类型返回 null，传给 Kotlin 非空参数会触发 Intrinsics NPE。
    // 先注册 matcher（Mockito 只认 matcher 注册），再返回非 null 占位值。
    private fun anyContext(): Context {
        ArgumentMatchers.any(Context::class.java)
        return context
    }

    private fun anyFile(): File {
        ArgumentMatchers.any(File::class.java)
        return temporaryFolder.root
    }

    private fun anyUri(): Uri {
        ArgumentMatchers.any(Uri::class.java)
        return Uri.fromFile(temporaryFolder.root)
    }

    private fun eq(value: String): String {
        ArgumentMatchers.eq(value)
        return value
    }

    private fun anyString(): String {
        ArgumentMatchers.anyString()
        return "placeholder"
    }

    private fun uri(f: File) = Uri.fromFile(f)

    private fun mediaFile(name: String) = temporaryFolder.newFile(name).also(::writeMedia)

    private fun writeMedia(file: java.io.File) {
        file.writeBytes(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
            )
        )
    }
}
