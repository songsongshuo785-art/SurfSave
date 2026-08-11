package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.app.Application
import android.net.Uri
import com.myAllVideoBrowser.util.FileUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YoutubeDlMediaPublisherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun sourceAndTargetBothExist_rejectsRecoveryWithoutMoving() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = mediaFile("source.mp4")
        val target = mediaFile("target.mp4")
        Mockito.`when`(fileUtil.resolveMediaUri(context, target)).thenReturn(Uri.fromFile(target))

        val error = YoutubeDlMediaPublisher(context, fileUtil)
            .publish(source.absolutePath, target.absolutePath)

        assertTrue(error.orEmpty().contains("source remains"))
        Mockito.verify(fileUtil, Mockito.never()).moveMedia(
            context,
            Uri.fromFile(source),
            Uri.fromFile(target)
        )
    }

    @Test
    fun targetExistsAndSourceIsAbsent_recoversAfterValidatingFinalUri() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = temporaryFolder.root.resolve("missing-source.mp4")
        val target = mediaFile("recovered.mp4")
        Mockito.`when`(fileUtil.resolveMediaUri(context, target)).thenReturn(Uri.fromFile(target))

        val error = YoutubeDlMediaPublisher(context, fileUtil)
            .publish(source.absolutePath, target.absolutePath)

        assertNull(error)
        Mockito.verify(fileUtil, Mockito.never()).moveMedia(
            context,
            Uri.fromFile(source),
            Uri.fromFile(target)
        )
    }

    @Test
    fun moveFailure_staysFailedEvenWhenTargetAppears() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = mediaFile("move-source.mp4")
        val target = temporaryFolder.root.resolve("move-target.mp4")
        Mockito.`when`(fileUtil.resolveMediaUri(context, target)).thenReturn(null)
        Mockito.`when`(
            fileUtil.moveMedia(
                context,
                Uri.fromFile(source),
                Uri.fromFile(target)
            )
        ).thenAnswer {
            writeMedia(target)
            false
        }

        val error = YoutubeDlMediaPublisher(context, fileUtil)
            .publish(source.absolutePath, target.absolutePath)

        assertNotNull(error)
        assertTrue(target.isFile)
        Mockito.verify(fileUtil, Mockito.times(1)).resolveMediaUri(context, target)
    }

    @Test
    fun successfulMove_resolvesAndValidatesFinalUri() {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        val source = mediaFile("successful-source.mp4")
        val target = temporaryFolder.root.resolve("successful-target.mp4")
        Mockito.`when`(fileUtil.resolveMediaUri(context, target))
            .thenReturn(null, Uri.fromFile(target))
        Mockito.`when`(
            fileUtil.moveMedia(
                context,
                Uri.fromFile(source),
                Uri.fromFile(target)
            )
        ).thenAnswer {
            writeMedia(target)
            assertTrue(source.delete())
            true
        }

        val error = YoutubeDlMediaPublisher(context, fileUtil)
            .publish(source.absolutePath, target.absolutePath)

        assertNull(error)
        Mockito.verify(fileUtil, Mockito.times(2)).resolveMediaUri(context, target)
    }

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
