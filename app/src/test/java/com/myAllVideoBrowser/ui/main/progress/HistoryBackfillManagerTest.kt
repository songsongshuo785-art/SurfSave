package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import android.net.Uri
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HistoryBackfillManagerTest {

    private lateinit var context: Context
    private val repository = Mockito.mock(ProgressRepository::class.java)
    private val fileUtil = Mockito.mock(FileUtil::class.java)
    private lateinit var manager: HistoryBackfillManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = HistoryBackfillManager(context, repository, fileUtil) { 100L }
    }

    @Test
    fun backfillSkipsLegacyPublicAndIsIdempotent() {
        val managed = entry("a.mp4", "file:///tmp/public/a.mp4", FileUtil.MediaStorageClass.MANAGED_PUBLIC)
        val legacy = entry("b.mp4", "file:///tmp/downloads/b.mp4", FileUtil.MediaStorageClass.LEGACY_PUBLIC)
        Mockito.`when`(fileUtil.listFiles).thenReturn(listOf(managed, legacy))
        Mockito.`when`(repository.getProgressInfosOnce()).thenReturn(emptyList())
        Mockito.`when`(repository.findDuplicateByFingerprint(Mockito.anyString())).thenReturn(null)

        val first = manager.backfillIfNeeded()
        val second = manager.backfillIfNeeded()

        assertEquals(1, first.inserted)
        assertEquals(0, second.inserted)
        val saveCalls = Mockito.mockingDetails(repository).invocations
            .count { it.method.name == "saveProgressInfo" }
        assertEquals(1, saveCalls)
    }

    @Test
    fun duplicateFileNameWithoutExactUriIsNotTreatedAsBound() {
        val first = entry("foo.mp4", "file:///tmp/public/foo.mp4", FileUtil.MediaStorageClass.MANAGED_PUBLIC)
        val second = entry("foo.mp4", "file:///tmp/files/SurfSave/foo.mp4", FileUtil.MediaStorageClass.INTERNAL_PRIVATE)
        Mockito.`when`(fileUtil.listFiles).thenReturn(listOf(first, second))
        Mockito.`when`(repository.getProgressInfosOnce()).thenReturn(
            listOf(
                ProgressInfo(
                    id = "existing",
                    videoInfo = VideoInfo(title = "foo", ext = "mp4"),
                    downloadStatus = VideoTaskState.SUCCESS,
                    finalMediaUri = first.uri.toString(),
                )
            )
        )
        Mockito.`when`(repository.findDuplicateByFingerprint(Mockito.anyString())).thenReturn(null)

        val result = manager.backfillIfNeeded()

        // 第一个文件已由 finalMediaUri 精确命中；第二个同名文件必须补录。
        assertEquals(1, result.inserted)
    }

    private fun entry(
        name: String,
        uri: String,
        storageClass: FileUtil.MediaStorageClass,
    ): FileUtil.MediaEntry = FileUtil.MediaEntry(
        id = name.hashCode().toLong(),
        displayName = name,
        uri = Uri.parse(uri),
        storageClass = storageClass,
    )
}
