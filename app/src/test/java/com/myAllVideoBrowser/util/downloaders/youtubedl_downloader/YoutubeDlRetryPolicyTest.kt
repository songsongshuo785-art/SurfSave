package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class YoutubeDlRetryPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failedPublicationWithExistingSource_retriesPublication() {
        val source = temporaryFolder.newFile("downloaded.mp4")

        assertEquals(
            YoutubeDlRetryAction.RETRY_PUBLICATION,
            youtubeDlRetryAction(failedTask(source.absolutePath))
        )
    }

    @Test
    fun failedPublicationWithoutSource_requeuesDownload() {
        assertEquals(
            YoutubeDlRetryAction.REQUEUE_DOWNLOAD,
            youtubeDlRetryAction(failedTask(File(temporaryFolder.root, "missing.mp4").absolutePath))
        )
    }

    @Test
    fun failedPublicationWithPublishedTarget_retriesPublicationRecovery() {
        val missingSource = File(temporaryFolder.root, "missing.mp4")

        assertEquals(
            YoutubeDlRetryAction.RETRY_PUBLICATION,
            youtubeDlRetryAction(
                failedTask(missingSource.absolutePath),
                publishedTargetExists = true
            )
        )
    }

    @Test
    fun failedPublicationWithPreviouslyRejectedTarget_requeuesDownload() {
        val missingSource = File(temporaryFolder.root, "missing.mp4")
        val task = failedTask(missingSource.absolutePath).copy(
            lastError = "Finalization target validation failed: Downloaded file is empty"
        )

        assertEquals(
            YoutubeDlRetryAction.REQUEUE_DOWNLOAD,
            youtubeDlRetryAction(task, publishedTargetExists = true)
        )
    }

    @Test
    fun failedPublicationWithRejectedTargetAndRemainingSource_retriesPublication() {
        val source = temporaryFolder.newFile("downloaded-after-validation-error.mp4")
        val task = failedTask(source.absolutePath).copy(
            lastError = "Finalization target validation failed: Downloaded file is empty"
        )

        assertEquals(
            YoutubeDlRetryAction.RETRY_PUBLICATION,
            youtubeDlRetryAction(task, publishedTargetExists = true)
        )
    }

    private fun failedTask(sourcePath: String) = ProgressInfo(
        id = "task",
        videoInfo = VideoInfo(id = "video"),
        downloadStatus = VideoTaskState.ERROR,
        executionToken = "token",
        finalizationSource = sourcePath,
        finalizationTarget = File(temporaryFolder.root, "target.mp4").absolutePath
    )
}
