package com.myAllVideoBrowser.data.repository

import android.app.DownloadManager
import android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
import android.app.DownloadManager.STATUS_FAILED
import android.app.DownloadManager.STATUS_PAUSED
import android.app.DownloadManager.STATUS_PENDING
import android.app.DownloadManager.STATUS_RUNNING
import android.app.DownloadManager.STATUS_SUCCESSFUL
import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import com.myAllVideoBrowser.data.local.room.dao.BrowserFileDownloadDao
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownloadStatus
import io.reactivex.rxjava3.core.Flowable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class BrowserFileEnqueueRequest(
    val url: String,
    val sourcePageUrl: String,
    val fileName: String,
    val mimeType: String,
    val expectedSize: Long,
    val headers: Map<String, String>,
    val relativePath: String
)

interface BrowserFileDownloadRepository {
    fun observeDownloads(): Flowable<List<BrowserFileDownload>>
    fun enqueue(request: BrowserFileEnqueueRequest): Result<Long>
    fun refreshAll(): Boolean
    fun cancel(download: BrowserFileDownload)
    fun retry(download: BrowserFileDownload): Result<Long>
    fun delete(download: BrowserFileDownload, deleteLocalFile: Boolean)
    fun getContentUri(download: BrowserFileDownload): Uri?
}

@Singleton
class BrowserFileDownloadRepositoryImpl @Inject constructor(
    private val application: Application,
    private val downloadManager: DownloadManager,
    private val dao: BrowserFileDownloadDao
) : BrowserFileDownloadRepository {
    @Volatile
    private var needsFullRefresh = true

    override fun observeDownloads(): Flowable<List<BrowserFileDownload>> = dao.observeAll()

    @Synchronized
    override fun enqueue(request: BrowserFileEnqueueRequest): Result<Long> = runCatching {
        val relativePath = uniqueRelativePath(request.relativePath, request.fileName)
        val persistedRequest = request.copy(
            fileName = relativePath.substringAfterLast('/'),
            relativePath = relativePath
        )
        val managerRequest = buildManagerRequest(persistedRequest)
        val managerId = downloadManager.enqueue(managerRequest)
        try {
            dao.insert(
                BrowserFileDownload(
                    downloadManagerId = managerId,
                    url = persistedRequest.url,
                    sourcePageUrl = persistedRequest.sourcePageUrl,
                    userAgent = persistedRequest.headers.headerValue("User-Agent").orEmpty(),
                    fileName = persistedRequest.relativePath.substringAfterLast('/'),
                    mimeType = persistedRequest.mimeType,
                    expectedSize = persistedRequest.expectedSize.coerceAtLeast(0),
                    totalBytes = persistedRequest.expectedSize.coerceAtLeast(0),
                    relativePath = persistedRequest.relativePath
                )
            )
        } catch (error: Throwable) {
            downloadManager.remove(managerId)
            throw error
        }
        managerId
    }

    @Synchronized
    override fun refreshAll(): Boolean {
        val downloads = if (needsFullRefresh) dao.getAll() else dao.getActive()
        needsFullRefresh = false
        downloads.forEach { download ->
            runCatching { refresh(download) }
        }
        return dao.getActive().any {
            it.status == BrowserFileDownloadStatus.PENDING ||
                it.status == BrowserFileDownloadStatus.RUNNING
        }
    }

    @Synchronized
    override fun cancel(download: BrowserFileDownload) {
        if (download.systemBindingTrusted) {
            downloadManager.remove(download.downloadManagerId)
        }
        dao.update(
            download.copy(
                status = BrowserFileDownloadStatus.CANCELED,
                completedAt = System.currentTimeMillis(),
                localUri = "",
                failureReason = 0,
                systemBindingTrusted = false
            )
        )
    }

    @Synchronized
    override fun retry(download: BrowserFileDownload): Result<Long> = runCatching {
        if (download.systemBindingTrusted) {
            downloadManager.remove(download.downloadManagerId)
        }
        val relativePath = uniqueRelativePath(
            download.relativePath,
            download.fileName,
            excludeId = download.id
        )
        val request = BrowserFileEnqueueRequest(
            url = download.url,
            sourcePageUrl = download.sourcePageUrl,
            fileName = relativePath.substringAfterLast('/'),
            mimeType = download.mimeType,
            expectedSize = download.expectedSize,
            headers = buildMap {
                download.userAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                if (download.sourcePageUrl.isNotBlank()) {
                    put("Referer", download.sourcePageUrl)
                }
                CookieManager.getInstance().getCookie(download.url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("Cookie", it) }
            },
            relativePath = relativePath
        )
        val managerId = downloadManager.enqueue(buildManagerRequest(request))
        try {
            dao.update(
                download.copy(
                    downloadManagerId = managerId,
                    downloadedBytes = 0,
                    totalBytes = download.expectedSize,
                    status = BrowserFileDownloadStatus.PENDING,
                    completedAt = 0,
                    localUri = "",
                    relativePath = request.relativePath,
                    fileName = request.fileName,
                    failureReason = 0,
                    systemBindingTrusted = true
                )
            )
        } catch (error: Throwable) {
            downloadManager.remove(managerId)
            throw error
        }
        managerId
    }

    @Synchronized
    override fun delete(download: BrowserFileDownload, deleteLocalFile: Boolean) {
        if (deleteLocalFile && download.systemBindingTrusted) {
            downloadManager.remove(download.downloadManagerId)
            deleteFallbackFile(download)
        }
        dao.delete(download)
    }

    @Synchronized
    override fun getContentUri(download: BrowserFileDownload): Uri? {
        if (!download.systemBindingTrusted) return null
        val managerUri = runCatching {
            downloadManager.getUriForDownloadedFile(download.downloadManagerId)
        }.getOrNull()
        if (managerUri != null && contentExists(managerUri)) return managerUri

        val storedUri = download.localUri.takeIf { it.isNotBlank() }?.toUri()
        if (storedUri != null && contentExists(storedUri)) return storedUri
        val fallbackUri = fallbackFileUri(download)
        if (fallbackUri != null) return fallbackUri
        if (download.status == BrowserFileDownloadStatus.SUCCESSFUL) {
            dao.update(
                download.copy(
                    status = BrowserFileDownloadStatus.MISSING,
                    completedAt = download.completedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    localUri = ""
                )
            )
        }
        return null
    }

    private fun refresh(download: BrowserFileDownload) {
        if (!download.systemBindingTrusted) {
            if (download.status != BrowserFileDownloadStatus.MISSING || download.localUri.isNotBlank()) {
                dao.update(
                    download.copy(
                        status = BrowserFileDownloadStatus.MISSING,
                        localUri = "",
                        failureReason = 0
                    )
                )
            }
            return
        }
        val query = DownloadManager.Query().setFilterById(download.downloadManagerId)
        val resultCursor = downloadManager.query(query)
        if (resultCursor == null) {
            handleMissingManagerRow(download)
            return
        }
        resultCursor.use { cursor ->
            if (!cursor.moveToFirst()) {
                handleMissingManagerRow(download)
                return
            }

            val managerStatus = cursor.longColumn(DownloadManager.COLUMN_STATUS).toInt()
            val mappedStatus = mapBrowserFileDownloadStatus(managerStatus)
            val downloadedBytes = cursor.longColumn(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                .coerceAtLeast(0)
            val reportedTotal = cursor.longColumn(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val totalBytes = when {
                reportedTotal > 0 -> reportedTotal
                download.expectedSize > 0 -> download.expectedSize
                else -> 0
            }
            val reason = cursor.longColumn(DownloadManager.COLUMN_REASON).toInt()
            val resolvedUri = if (mappedStatus == BrowserFileDownloadStatus.SUCCESSFUL) {
                runCatching { downloadManager.getUriForDownloadedFile(download.downloadManagerId) }
                    .getOrNull()
                    ?.takeIf(::contentExists)
                    ?: fallbackFileUri(download)
            } else {
                download.localUri.takeIf { it.isNotBlank() }?.toUri()
            }
            val finalStatus = if (
                mappedStatus == BrowserFileDownloadStatus.SUCCESSFUL &&
                (resolvedUri == null || !contentExists(resolvedUri))
            ) {
                BrowserFileDownloadStatus.MISSING
            } else {
                mappedStatus
            }
            val completionTime = if (
                finalStatus == BrowserFileDownloadStatus.SUCCESSFUL ||
                finalStatus == BrowserFileDownloadStatus.FAILED ||
                finalStatus == BrowserFileDownloadStatus.MISSING
            ) {
                download.completedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            } else {
                0
            }
            val refreshed = download.copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                status = finalStatus,
                completedAt = completionTime,
                localUri = resolvedUri?.toString().orEmpty(),
                failureReason = if (finalStatus == BrowserFileDownloadStatus.FAILED) reason else 0
            )
            if (refreshed != download) dao.update(refreshed)
        }
    }

    private fun handleMissingManagerRow(download: BrowserFileDownload) {
        if (download.status == BrowserFileDownloadStatus.CANCELED) return
        val storedUri = download.localUri.takeIf { it.isNotBlank() }?.toUri()
        val accessibleUri = storedUri?.takeIf(::contentExists) ?: fallbackFileUri(download)
        val status = if (accessibleUri != null) {
            BrowserFileDownloadStatus.SUCCESSFUL
        } else {
            BrowserFileDownloadStatus.MISSING
        }
        dao.update(
            download.copy(
                status = status,
                completedAt = download.completedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                localUri = accessibleUri?.toString().orEmpty(),
                systemBindingTrusted = accessibleUri != null
            )
        )
    }

    private fun buildManagerRequest(request: BrowserFileEnqueueRequest): DownloadManager.Request {
        require(request.url.startsWith("http://", true) || request.url.startsWith("https://", true)) {
            "Only HTTP(S) browser downloads are supported"
        }
        require(request.fileName.isNotBlank()) { "A download file name is required" }
        require(request.relativePath.isNotBlank()) { "A relative destination is required" }
        BrowserFileDestinationPolicy.requireNormalized(request.relativePath, request.fileName)

        return DownloadManager.Request(request.url.toUri())
            .setTitle(request.fileName)
            .setDescription(request.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.relativePath)
            .also { managerRequest ->
                request.mimeType.takeIf { it.isNotBlank() }?.let(managerRequest::setMimeType)
                request.headers.forEach { (name, value) -> managerRequest.addRequestHeader(name, value) }
            }
    }

    private fun uniqueRelativePath(
        requestedPath: String,
        requestedFileName: String,
        excludeId: Long = 0L
    ): String {
        val normalized = BrowserFileDestinationPolicy.requireNormalized(
            requestedPath,
            requestedFileName
        )
        val directory = normalized.substringBeforeLast('/', "")
        val fileName = normalized.substringAfterLast('/')
        val existingPaths = dao.getAll().filter { it.id != excludeId }.mapTo(mutableSetOf()) {
            it.relativePath.lowercase()
        }

        var candidateName = fileName
        var index = 1
        while (true) {
            val candidate = if (directory.isBlank()) candidateName else "$directory/$candidateName"
            val publicFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                candidate
            )
            if (candidate.lowercase() !in existingPaths && !publicFile.exists()) {
                return BrowserFileDestinationPolicy.requireNormalized(candidate, candidateName)
            }
            candidateName = BrowserFileDestinationPolicy.withCollisionSuffix(fileName, index)
            index += 1
        }
    }

    private fun contentExists(uri: Uri): Boolean = runCatching {
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { File(it) }?.exists() == true
            "content" -> application.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
            else -> false
        }
    }.getOrDefault(false)

    private fun deleteFallbackFile(download: BrowserFileDownload) {
        val expectedFile = runCatching { downloadFile(download) }.getOrNull() ?: return
        val storedFile = download.localUri
            .takeIf { it.startsWith("file:", true) }
            ?.toUri()
            ?.path
            ?.let(::File)
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?.takeIf { it == expectedFile }
        runCatching { storedFile?.delete() }
        runCatching { expectedFile.delete() }
    }

    private fun fallbackFileUri(download: BrowserFileDownload): Uri? {
        val file = runCatching { downloadFile(download) }.getOrNull() ?: return null
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(
                application,
                "${application.packageName}.provider",
                file
            )
        }.getOrNull()
    }

    private fun downloadFile(download: BrowserFileDownload): File =
        BrowserFileDestinationPolicy.resolve(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            download.relativePath,
            download.fileName
        )

    private fun Cursor.longColumn(name: String): Long {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0
    }
}

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

internal fun mapBrowserFileDownloadStatus(status: Int): Int = when (status) {
    STATUS_PENDING -> BrowserFileDownloadStatus.PENDING
    STATUS_RUNNING -> BrowserFileDownloadStatus.RUNNING
    STATUS_PAUSED -> BrowserFileDownloadStatus.PAUSED
    STATUS_SUCCESSFUL -> BrowserFileDownloadStatus.SUCCESSFUL
    STATUS_FAILED -> BrowserFileDownloadStatus.FAILED
    else -> BrowserFileDownloadStatus.FAILED
}
