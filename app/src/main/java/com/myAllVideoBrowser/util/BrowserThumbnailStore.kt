package com.myAllVideoBrowser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object BrowserThumbnailStore {
    private const val THUMBNAILS_DIR = "browser_tab_thumbnails"
    private const val MAX_THUMBNAIL_COUNT = 160 // 100 open tabs, recent closures and in-flight replacements.

    @Synchronized
    fun save(tabId: String, bitmap: Bitmap?): String? {
        if (tabId.isBlank() || !BrowserThumbnailQuality.isUsable(bitmap)) {
            return null
        }

        return runCatching {
            val dir = directory()
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val temporary = File.createTempFile(safeFileName(tabId) + "_${bitmap!!.width}x${bitmap.height}_", ".tmp", dir)
            val file = File(dir, temporary.name.removeSuffix(".tmp") + ".jpg")
            try {
                FileOutputStream(temporary).use { stream ->
                    check(bitmap!!.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                }
                check(temporary.renameTo(file))
            } finally {
                temporary.delete()
            }
            check(file.length() > 0L)
            trimCache(dir, file.name)
            file.absolutePath
        }.onFailure { AppLogger.w("Browser thumbnail could not be saved", it) }.getOrNull()
    }

    /** Capture dimensions are part of the immutable cache key, readable without disk IO. */
    fun dimensions(path: String?): Pair<Int, Int>? {
        val match = path?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.let { Regex("_(\\d+)x(\\d+)_\\d+\\.jpg$").find(it) } ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return if (width > 0 && height > 0) width to height else null
    }

    fun load(path: String?, maxDimension: Int = Int.MAX_VALUE): Bitmap? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = calculateSample(bounds.outWidth, bounds.outHeight, maxDimension)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = FileInputStream(file).use { BitmapFactory.decodeStream(it, null, options) }
            if (BrowserThumbnailQuality.isUsable(bitmap, requireCaptureSize = sample == 1)) bitmap else {
                if (sample == 1) file.delete()
                null
            }
        }.onFailure { AppLogger.w("Browser thumbnail could not be loaded", it) }.getOrNull()
    }

    private fun calculateSample(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        val limit = maxDimension.coerceAtLeast(120)
        while (width / sample > limit || height / sample > limit) sample *= 2
        return sample
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
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
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
