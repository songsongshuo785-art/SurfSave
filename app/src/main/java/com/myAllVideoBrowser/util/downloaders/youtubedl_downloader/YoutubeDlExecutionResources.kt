package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import com.myAllVideoBrowser.util.FileUtil
import java.io.File
import java.io.IOException

internal class YoutubeDlExecutionResources(
    private val fileUtil: FileUtil,
    private val deleteRecursively: (File) -> Boolean = { it.deleteRecursively() },
    private val deleteEmptyDirectory: (File) -> Boolean = { it.delete() }
) {
    fun prepare(taskId: String, executionKey: String, isContinue: Boolean): File {
        val current = executionDirectory(taskId, executionKey)
        val taskRoot = current.parentFile
            ?: throw IOException("yt-dlp execution directory has no parent")
        if (!taskRoot.exists() && !taskRoot.mkdirs()) {
            throw IOException("Unable to create the yt-dlp task directory")
        }
        if (!taskRoot.isDirectory) throw IOException("yt-dlp task path is not a directory")
        if (isContinue && current.listFiles().isNullOrEmpty()) {
            if (current.exists() && !current.delete()) {
                throw IOException("Unable to prepare an empty yt-dlp execution directory")
            }
            previousDirectory(taskId, current)?.let { previous ->
                if (!previous.renameTo(current)) {
                    throw IOException("Unable to transfer paused yt-dlp fragments")
                }
            }
        }
        if (!current.exists() && !current.mkdirs()) {
            throw IOException("Unable to create the yt-dlp execution directory")
        }
        if (!current.isDirectory) throw IOException("yt-dlp execution path is not a directory")
        return current
    }

    fun executionDirectory(taskId: String, executionKey: String): File {
        val taskRootKey = YoutubeDlDownloader.executionKey(taskId, RESOURCE_ROOT_TOKEN)
        return File(File(fileUtil.tmpDir, RESOURCE_ROOT_DIR), taskRootKey).resolve(executionKey)
    }

    fun deleteExecution(taskId: String, executionKey: String) {
        val directory = executionDirectory(taskId, executionKey)
        var failure: IOException? = null

        fun record(error: IOException) {
            if (failure == null) {
                failure = error
            } else {
                failure?.addSuppressed(error)
            }
        }

        if (directory.exists()) {
            try {
                if (!deleteRecursively(directory) && directory.exists()) {
                    record(IOException("Unable to delete the yt-dlp execution directory"))
                }
            } catch (error: Throwable) {
                record(IOException("Unable to delete the yt-dlp execution directory", error))
            }
        }

        directory.parentFile
            ?.takeIf { it.exists() && it.listFiles().isNullOrEmpty() }
            ?.let { taskRoot ->
                try {
                    if (!deleteEmptyDirectory(taskRoot) && taskRoot.exists()) {
                        record(IOException("Unable to delete the empty yt-dlp task directory"))
                    }
                } catch (error: Throwable) {
                    record(IOException("Unable to delete the empty yt-dlp task directory", error))
                }
            }

        failure?.let { throw it }
    }

    fun findFinalMedia(directory: File): File? {
        return directory.walkTopDown()
            .filter { file ->
                file.isFile &&
                    !file.name.endsWith(".part", ignoreCase = true) &&
                    file.extension.lowercase() in FINAL_EXTENSIONS
            }
            .maxByOrNull(File::length)
    }

    fun findStopAndSaveSource(directory: File): File? {
        return directory.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".ytdl", ignoreCase = true) }
            .maxByOrNull(File::length)
    }

    private fun previousDirectory(taskId: String, current: File): File? {
        val tokenDirectories = current.parentFile
            ?.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it != current && !it.listFiles().isNullOrEmpty() }
        val legacy = safeLegacyDirectory(taskId)
            ?.takeIf { it.isDirectory && !it.listFiles().isNullOrEmpty() }
        return (tokenDirectories + listOfNotNull(legacy)).maxByOrNull(::latestModifiedAt)
    }

    private fun safeLegacyDirectory(taskId: String): File? {
        return try {
            val root = fileUtil.tmpDir.canonicalFile
            val candidate = File(root, taskId).canonicalFile
            candidate.takeIf { it.path.startsWith(root.path + File.separator) }
        } catch (_: IOException) {
            null
        }
    }

    private fun latestModifiedAt(directory: File): Long {
        return directory.walkTopDown().maxOfOrNull(File::lastModified) ?: directory.lastModified()
    }

    private companion object {
        const val RESOURCE_ROOT_DIR = "ytdlp"
        const val RESOURCE_ROOT_TOKEN = "resource-root"
        val FINAL_EXTENSIONS = setOf("mp4", "mp3", "m4a", "webm", "mkv", "mov", "ts")
    }
}
