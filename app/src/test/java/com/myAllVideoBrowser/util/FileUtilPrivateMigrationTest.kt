package com.myAllVideoBrowser.util

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUtilPrivateMigrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun copyFailure_preservesSourceAndCleansPartialTarget() {
        val legacy = temporaryFolder.newFolder("legacy-failure")
        val current = temporaryFolder.newFolder("current-failure")
        val source = File(legacy, "video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val expected = source.readBytes()

        FileUtil().migratePrivateDirectory(
            legacy,
            current,
            moveOperation = { _, _ -> false },
            copyOperation = { _, target ->
                target.writeBytes(byteArrayOf(1))
                false
            }
        )

        assertTrue(source.isFile)
        assertArrayEquals(expected, source.readBytes())
        assertFalse(File(current, source.name).exists())
    }

    @Test
    fun copyVerificationFailure_preservesSourceAndCleansTarget() {
        val legacy = temporaryFolder.newFolder("legacy-mismatch")
        val current = temporaryFolder.newFolder("current-mismatch")
        val source = File(legacy, "video.mp4").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        FileUtil().migratePrivateDirectory(
            legacy,
            current,
            moveOperation = { _, _ -> false },
            copyOperation = { _, target ->
                target.writeBytes(byteArrayOf(6, 5, 4))
                true
            }
        )

        assertTrue(source.isFile)
        assertFalse(File(current, source.name).exists())
    }

    @Test
    fun verifiedRecursiveCopy_deletesSourceAfterTargetMatches() {
        val legacy = temporaryFolder.newFolder("legacy-success")
        val current = temporaryFolder.newFolder("current-success")
        val sourceDirectory = File(legacy, "capture").apply { mkdirs() }
        val source = File(sourceDirectory, "video.mp4").apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }

        FileUtil().migratePrivateDirectory(
            legacy,
            current,
            moveOperation = { _, _ -> false }
        )

        val target = File(current, "capture${File.separator}video.mp4")
        assertFalse(sourceDirectory.exists())
        assertTrue(target.isFile)
        assertArrayEquals(byteArrayOf(7, 8, 9), target.readBytes())
    }
}
