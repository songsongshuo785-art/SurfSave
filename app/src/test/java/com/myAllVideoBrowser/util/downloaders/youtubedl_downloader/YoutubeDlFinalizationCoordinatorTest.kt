package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class YoutubeDlFinalizationCoordinatorTest {

    @Test
    fun repeatedClaim_publishesAndCommitsOnlyForWinner() {
        val repository = Mockito.mock(ProgressRepository::class.java)
        val publisher = Mockito.mock(YoutubeDlMediaPublisher::class.java)
        Mockito.`when`(
            repository.claimYtDlpFinalization(
                "task",
                "token",
                "/tmp/source",
                "/out/target"
            )
        ).thenReturn(1, 0)
        Mockito.`when`(publisher.publish("/tmp/source", "/out/target")).thenReturn(null)
        Mockito.`when`(
            repository.commitYtDlpFinalization(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()
            )
        ).thenReturn(1)
        val coordinator = YoutubeDlFinalizationCoordinator(repository, publisher)

        val first = coordinator.claimAndFinalize("task", "token", "/tmp/source", "/out/target")
        val second = coordinator.claimAndFinalize("task", "token", "/tmp/source", "/out/target")

        assertTrue(first is YoutubeDlFinalizationCoordinator.Result.Committed)
        assertEquals(YoutubeDlFinalizationCoordinator.Result.NotOwner, second)
        Mockito.verify(publisher, Mockito.times(1)).publish("/tmp/source", "/out/target")
        Mockito.verify(repository, Mockito.times(1)).commitYtDlpFinalization(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString()
        )
    }

    @Test
    fun publisherError_isRedactedAndBoundedBeforeCommit() {
        val repository = Mockito.mock(ProgressRepository::class.java)
        val publisher = Mockito.mock(YoutubeDlMediaPublisher::class.java)
        val rawError = "Cookie: session-secret\n" +
            "Authorization: Bearer auth-secret\n" +
            "https://example.test/video?token=query-secret&part=${"x".repeat(3_000)}"
        Mockito.`when`(
            repository.claimYtDlpFinalization(
                "task",
                "token",
                "/tmp/source",
                "/out/target"
            )
        ).thenReturn(1)
        Mockito.`when`(publisher.publish("/tmp/source", "/out/target")).thenReturn(rawError)
        Mockito.`when`(
            repository.commitYtDlpFinalization(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()
            )
        ).thenReturn(1)
        val coordinator = YoutubeDlFinalizationCoordinator(repository, publisher)

        val result = coordinator.claimAndFinalize(
            "task",
            "token",
            "/tmp/source",
            "/out/target"
        ) as YoutubeDlFinalizationCoordinator.Result.Committed

        assertEquals(VideoTaskState.ERROR, result.status)
        assertTrue(result.error.length <= 2_000)
        assertTrue(result.error.contains("<redacted>"))
        assertFalse(result.error.contains("session-secret"))
        assertFalse(result.error.contains("auth-secret"))
        assertFalse(result.error.contains("query-secret"))

        val commitInvocation = Mockito.mockingDetails(repository).invocations.single {
            it.method.name == "commitYtDlpFinalization"
        }
        assertEquals("task", commitInvocation.arguments[0])
        assertEquals("token", commitInvocation.arguments[1])
        assertEquals(VideoTaskState.ERROR, commitInvocation.arguments[2])
        assertTrue((commitInvocation.arguments[3] as Long) > 0L)
        assertEquals(result.error, commitInvocation.arguments[4])
        assertEquals(result.error, commitInvocation.arguments[5])
    }
}
