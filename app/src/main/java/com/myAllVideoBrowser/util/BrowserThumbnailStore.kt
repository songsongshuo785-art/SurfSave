package com.myAllVideoBrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object BrowserThumbnailStore {
    private const val THUMBNAILS_DIR = "browser_tab_thumbnails"
    private const val MAX_THUMBNAIL_COUNT = 80

    fun save(tabId: String, bitmap: Bitmap?): String? {
        if (bitmap == null || tabId.isBlank()) {
            return null
        }

        return runCatching {
            val dir = directory()
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, safeFileName(tabId))
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            }
            trimCache(dir, file.name)
            file.absolutePath
        }.getOrNull()
    }

    fun load(path: String?): Bitmap? {
        if (path.isNullOrBlank()) {
            return null
        }

        return runCatching {
            val file = File(path)
            if (!file.exists()) {
                null
            } else {
                BitmapFactory.decodeFile(file.absolutePath)
            }
        }.getOrNull()
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) {
            return
        }

        runCatching {
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    fun directory(): File {
        return File(ContextUtils.getApplicationContext().filesDir, THUMBNAILS_DIR)
    }

    fun clearAll() {
        runCatching {
            directory().listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
        }
    }

    fun importFile(fileName: String, bytes: ByteArray): String? {
        if (fileName.isBlank() || bytes.isEmpty()) {
            return null
        }

        return runCatching {
            val dir = directory()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val target = File(dir, safeImportedFileName(fileName))
            FileOutputStream(target).use { stream ->
                stream.write(bytes)
            }
            trimCache(dir, target.name)
            target.absolutePath
        }.getOrNull()
    }

    private fun safeFileName(tabId: String): String {
        return tabId.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".jpg"
    }

    private fun safeImportedFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun trimCache(dir: File, keepFileName: String) {
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (files.size <= MAX_THUMBNAIL_COUNT) {
            return
        }

        files.drop(MAX_THUMBNAIL_COUNT).forEach { file ->
            if (file.name != keepFileName) {
                file.delete()
            }
        }
    }
}
