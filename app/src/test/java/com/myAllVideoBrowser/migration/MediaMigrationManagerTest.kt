package com.myAllVideoBrowser.migration

import android.content.Context
import android.net.Uri
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.FileUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaMigrationManagerTest {

    private val context = Mockito.mock(Context::class.java).apply {
        Mockito.`when`(filesDir).thenReturn(File("/tmp/files"))
        Mockito.`when`(getExternalFilesDir(null)).thenReturn(null)
    }
    private val fileUtil = Mockito.mock(FileUtil::class.java).apply {
        Mockito.`when`(publicDownloadsDir).thenReturn(File("/tmp/public"))
    }
    private val repository = Mockito.mock(ProgressRepository::class.java)
    private val manager = MediaMigrationManager(context, fileUtil, repository)

    @Test
    fun migrateWithoutProgressInfo_stillMovesFile() {
        val video = LocalVideo(1, Uri.parse("file:///tmp/files/SurfSave/foo.mp4"), "foo.mp4")
        val publishedUri = Uri.parse("content://media/external/downloads/1")
        Mockito.`when`(
            fileUtil.publishMediaCopy(context, video.uri, video.name)
        ).thenReturn(FileUtil.PublishResult.Success(publishedUri, "foo.mp4"))
        Mockito.`when`(
            repository.replaceFinalMediaUri(video.uri.toString(), publishedUri.toString())
        ).thenReturn(0)
        Mockito.`when`(
            fileUtil.deletePublishedSource(context, video.uri)
        ).thenReturn(true)

        val result = manager.migrate(video, StorageTarget.PUBLIC)

        assertTrue(result is MigrationResult.Success)
        assertEquals(publishedUri, (result as MigrationResult.Success).targetUri)
    }

    @Test
    fun metadataFailure_rollsBackPublishedTargetAndKeepsSource() {
        val video = LocalVideo(1, Uri.parse("file:///tmp/files/SurfSave/foo.mp4"), "foo.mp4")
        val publishedUri = Uri.parse("content://media/external/downloads/1")
        Mockito.`when`(
            fileUtil.publishMediaCopy(context, video.uri, video.name)
        ).thenReturn(FileUtil.PublishResult.Success(publishedUri, "foo.mp4"))
        Mockito.`when`(
            repository.replaceFinalMediaUri(video.uri.toString(), publishedUri.toString())
        ).thenThrow(RuntimeException("database is locked"))

        val result = manager.migrate(video, StorageTarget.PUBLIC)

        assertTrue(result is MigrationResult.Failed)
        Mockito.verify(fileUtil).cleanupPublishedMedia(context, publishedUri)
        Mockito.verify(fileUtil, Mockito.never()).deletePublishedSource(context, video.uri)
    }

    @Test
    fun sourceDeleteAuthorization_returnsNeedsAuthWithoutRollingBackTarget() {
        val video = LocalVideo(1, Uri.parse("file:///tmp/files/SurfSave/foo.mp4"), "foo.mp4")
        val publishedUri = Uri.parse("content://media/external/downloads/1")
        Mockito.`when`(
            fileUtil.publishMediaCopy(context, video.uri, video.name)
        ).thenReturn(FileUtil.PublishResult.Success(publishedUri, "foo.mp4"))
        Mockito.`when`(
            repository.replaceFinalMediaUri(video.uri.toString(), publishedUri.toString())
        ).thenReturn(1)
        // 源在公共目录时才会走 deleteMedia；这里用公共源模拟授权分支
        val publicSource = LocalVideo(2, Uri.parse("content://media/external/downloads/9"), "bar.mp4")
        Mockito.`when`(
            fileUtil.copyMediaToPrivateDir(context, publicSource.uri, File("/tmp/files/SurfSave"), publicSource.name)
        ).thenReturn(FileUtil.CopyResult(true, Uri.parse("file:///tmp/files/SurfSave/bar.mp4")))
        Mockito.`when`(
            repository.replaceFinalMediaUri(publicSource.uri.toString(), "file:///tmp/files/SurfSave/bar.mp4")
        ).thenReturn(1)
        Mockito.`when`(
            fileUtil.deleteMedia(context, publicSource.uri)
        ).thenReturn(
            FileUtil.DeleteMediaResult.NeedsAuth(
                Mockito.mock(android.content.IntentSender::class.java),
                retryUri = null,
                verificationUri = publicSource.uri,
            )
        )

        val result = manager.migrate(publicSource, StorageTarget.PRIVATE_INTERNAL)

        assertTrue(result is MigrationResult.NeedsAuth)
        Mockito.verify(fileUtil, Mockito.never()).cleanupPublishedMedia(context, publishedUri)
    }
}
