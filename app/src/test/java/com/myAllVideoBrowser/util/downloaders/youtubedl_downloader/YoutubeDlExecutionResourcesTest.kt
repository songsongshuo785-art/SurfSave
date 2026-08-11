package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.util.FileUtil
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class YoutubeDlExecutionResourcesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun prepare_createsTaskRootBeforeTransferringLegacyFragments() {
        val resources = resources()
        val legacy = temporaryFolder.root.resolve("legacy-task")
        assertTrue(legacy.mkdirs())
        legacy.resolve("fragment.part").writeText("partial", Charsets.UTF_8)

        val execution = resources.prepare("legacy-task", "new-execution", isContinue = true)

        assertTrue(execution.isDirectory)
        assertTrue(execution.resolve("fragment.part").isFile)
    }

    @Test
    fun deleteExecution_reportsExecutionDirectoryFailure() {
        val resources = resources(deleteRecursively = { false })
        val execution = resources.executionDirectory("task", "execution")
        assertTrue(execution.mkdirs())
        execution.resolve("fragment.part").writeText("partial", Charsets.UTF_8)

        assertThrows(IOException::class.java) {
            resources.deleteExecution("task", "execution")
        }
        assertTrue(execution.exists())
    }

    @Test
    fun deleteExecution_reportsEmptyTaskDirectoryFailure() {
        val resources = resources(deleteEmptyDirectory = { false })
        val taskRoot = requireNotNull(
            resources.executionDirectory("task", "execution").parentFile
        )
        assertTrue(taskRoot.mkdirs())

        assertThrows(IOException::class.java) {
            resources.deleteExecution("task", "execution")
        }
        assertTrue(taskRoot.exists())
    }

    private fun resources(
        deleteRecursively: (java.io.File) -> Boolean = { it.deleteRecursively() },
        deleteEmptyDirectory: (java.io.File) -> Boolean = { it.delete() }
    ): YoutubeDlExecutionResources {
        val fileUtil = Mockito.mock(FileUtil::class.java)
        Mockito.`when`(fileUtil.tmpDir).thenReturn(temporaryFolder.root)
        return YoutubeDlExecutionResources(
            fileUtil,
            deleteRecursively,
            deleteEmptyDirectory
        )
    }
}
