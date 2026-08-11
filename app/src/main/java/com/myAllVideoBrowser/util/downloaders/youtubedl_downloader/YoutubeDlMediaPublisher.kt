package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.util.DownloadedMediaValidator
import com.myAllVideoBrowser.util.FileUtil
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class YoutubeDlMediaPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileUtil: FileUtil
) {
    open fun publish(sourcePath: String, targetPath: String): String? {
        if (targetPath.isBlank()) return "Finalization target is missing"

        val target = File(targetPath)
        val source = sourcePath.takeIf { it.isNotBlank() }?.let(::File)
        val existingTargetUri = fileUtil.resolveMediaUri(context, target)
        if (existingTargetUri != null) {
            if (source?.exists() == true) {
                return "Finalization target already exists while source remains"
            }
            return validateTarget(existingTargetUri)
        }
        if (source == null) return "Finalization source is missing"

        if (!source.isFile) return "Finalization source no longer exists"

        val moveError = try {
            if (fileUtil.moveMedia(context, source.toUri(), target.toUri())) null
            else "Error moving file"
        } catch (error: Throwable) {
            error.message ?: "Error moving file"
        }
        if (moveError != null) return moveError
        if (source.exists()) return "Finalization source still exists after publication"

        val finalUri = fileUtil.resolveMediaUri(context, target)
            ?: return "Finalization target is missing after publication"
        return validateTarget(finalUri)
    }

    private fun validateTarget(targetUri: Uri): String? {
        return try {
            DownloadedMediaValidator.validate(context, targetUri)
        } catch (error: Throwable) {
            error.message ?: "Downloaded media validation failed"
        }
    }
}
