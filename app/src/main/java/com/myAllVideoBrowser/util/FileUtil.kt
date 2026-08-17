package com.myAllVideoBrowser.util

//import com.allVideoDownloaderXmaster.OpenForTesting
import android.annotation.SuppressLint
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.net.toFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import javax.inject.Inject

//@OpenForTesting
class FileUtil @Inject constructor() {

    companion object {
        var INITIIALIZED = false

        // For downloads and tmp data
        var IS_EXTERNAL_STORAGE_USE = true

        // For downloads
        var IS_APP_DATA_DIR_USE = false

        const val FOLDER_NAME = "SurfSave"

        const val TMP_DATA_FOLDER_NAME = "surfsave_tmp_data"
        const val LEGACY_FOLDER_NAME = "SuperX"
        const val LEGACY_TMP_DATA_FOLDER_NAME = "superx_tmp_data"
        val PUBLIC_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/"
        val LEGACY_PUBLIC_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/"

        private const val KB = 1024
        private const val MB = 1024 * 1024
        private const val GB = 1024 * 1024 * 1024

        // 10MB
        private const val FREE_SPACE_TRESHOLD = 10 * 1024 * 1024
        private val PUBLIC_MEDIA_EXTENSIONS = setOf(
            "mp4",
            "mp3",
            "m4a",
            "webm",
            "mkv",
            "aac",
            "wav",
            "ogg",
            "opus",
            "flac",
            "mov",
            "3gp"
        )

        fun getFileSizeReadable(length: Double): String {

            val decimalFormat = DecimalFormat("#.##")
            return when {
                length > GB -> decimalFormat.format(length / GB) + " GB"
                length > MB -> decimalFormat.format(length / MB) + " MB"
                length > KB -> decimalFormat.format(length / KB) + " KB"
                else -> decimalFormat.format(length) + " B"
            }
        }

        fun getFreeDiskSpace(path: File): Long {
            if (!path.exists()) {
                throw IllegalArgumentException("Path does not exist")
            }

            val stats = StatFs(path.absolutePath)
            return stats.availableBlocksLong * stats.blockSizeLong
        }

        fun calculateFolderSize(directory: File): Long {
            var length = 0L
            if (directory.isDirectory) {
                for (file in directory.listFiles() ?: emptyArray()) {
                    length += calculateFolderSize(file)
                }
            } else {
                length += directory.length()
            }
            return length
        }

        fun isExternalStorageWritable(): Boolean {
            return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        }
    }

    enum class MediaStorageClass {
        MANAGED_PUBLIC,
        LEGACY_PUBLIC,
        EXTERNAL_PRIVATE,
        INTERNAL_PRIVATE
    }

    data class MediaEntry(
        val id: Long,
        val displayName: String,
        val uri: Uri,
        val storageClass: MediaStorageClass
    )

    val folderDir: File
        get() {
            if (!INITIIALIZED) {
                throw Error("File Util Not Initialized")
            }

            val context = ContextUtils.getApplicationContext()

            when {
                IS_EXTERNAL_STORAGE_USE && !IS_APP_DATA_DIR_USE -> {
                    return publicDownloadsDir
                }

                IS_EXTERNAL_STORAGE_USE && IS_APP_DATA_DIR_USE -> {
                    return File(context.getExternalFilesDir(null), FOLDER_NAME)

                }

                else -> {
                    return File(context.filesDir.absolutePath, FOLDER_NAME)
                }
            }
        }

    val publicDownloadsDir: File
        get() = File(legacyPublicDownloadsDir, FOLDER_NAME)

    val legacyPublicDownloadsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    val tmpDir: File
        get() {
            if (!INITIIALIZED) {
                throw Error("File Util Not Initialized")
            }

            val context = ContextUtils.getApplicationContext()

            return getTmpDataDir(context, IS_EXTERNAL_STORAGE_USE)
        }

    fun migrateLegacyPrivateStorage(context: Context) {
        migratePrivateDirectory(
            legacy = getPrivateNamedDir(context, isExternal = true, folderName = LEGACY_FOLDER_NAME),
            current = getPrivateDownloadsDir(context, isExternal = true)
        )
        migratePrivateDirectory(
            legacy = getPrivateNamedDir(context, isExternal = false, folderName = LEGACY_FOLDER_NAME),
            current = getPrivateDownloadsDir(context, isExternal = false)
        )
        migratePrivateDirectory(
            legacy = getNamedTmpDataDir(context, isExternal = true, folderName = LEGACY_TMP_DATA_FOLDER_NAME),
            current = getTmpDataDir(context, isExternal = true)
        )
        migratePrivateDirectory(
            legacy = getNamedTmpDataDir(context, isExternal = false, folderName = LEGACY_TMP_DATA_FOLDER_NAME),
            current = getTmpDataDir(context, isExternal = false)
        )
    }

    val listFiles: List<MediaEntry>
        get() {
            val context = ContextUtils.getApplicationContext()
            val result = mutableListOf<MediaEntry>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                result.addAll(getPublicDownloadsDirFilesObjMediaStore(context))
            } else {
                result.addAll(listLegacyPublicFiles())
            }
            result.addAll(
                getPrivateDownloadsDirFilesObj(
                    context,
                    isExternal = true,
                    storageClass = MediaStorageClass.EXTERNAL_PRIVATE
                )
            )
            result.addAll(
                getPrivateDownloadsDirFilesObj(
                    context,
                    isExternal = false,
                    storageClass = MediaStorageClass.INTERNAL_PRIVATE
                )
            )

            return result.distinctBy { it.uri.toString() }

        }

    fun isFreeSpaceAvailable(): Boolean {
        var probe: File? = folderDir
        while (probe != null && !probe.exists()) {
            probe = probe.parentFile
        }
        return probe != null && getFreeDiskSpace(probe) > FREE_SPACE_TRESHOLD
    }

    fun ensureDownloadDestination(): Boolean {
        return if (isManagedPublicDestinationSelected() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isExternalStorageWritable()
        } else {
            folderDir.exists() || folderDir.mkdirs()
        }
    }

    fun ensurePublicDownloadDestination(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isExternalStorageWritable()
        } else {
            publicDownloadsDir.exists() || publicDownloadsDir.mkdirs()
        }
    }

    /** Exact, non-recursive duplicate lookup used by the queue and final publishers. */
    fun hasDownloadWithName(context: Context, displayName: String): Boolean {
        if (displayName.isBlank()) return false
        val publicExists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasVisiblePublicMediaStoreName(context.contentResolver, displayName)
        } else {
            File(publicDownloadsDir, displayName).isFile ||
                File(legacyPublicDownloadsDir, displayName).isFile
        }
        if (publicExists) return true
        return File(getPrivateDownloadsDir(context, true), displayName).isFile ||
            File(getPrivateDownloadsDir(context, false), displayName).isFile
    }

    fun isFileWithNameNotExists(context: Context, uri: Uri, newName: String): Boolean {
        if (newName.isBlank()) return false
        if (isFileApiSupportedByUri(context, uri)) {
            return !File(uri.toFile().parentFile, newName).exists()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val contentUri = findMediaStoreContentUri(context, uri)
        val relativePath = contentUri?.let {
            queryStringColumn(
                context.contentResolver,
                it,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
        } ?: return true
        return !hasMediaStoreNameAtPath(
            context.contentResolver,
            newName,
            relativePath,
            contentUri
        )
    }

    fun uniqueMediaTarget(context: Context, requestedTarget: File): File {
        val parent = requestedTarget.parentFile ?: publicDownloadsDir
        val normalizedName = sanitizeDisplayName(requestedTarget.name)
        var candidate = File(parent, normalizedName)
        if (!mediaTargetExists(context, candidate)) return candidate

        val extension = candidate.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val base = candidate.nameWithoutExtension.ifBlank { "download" }
        var counter = 1
        do {
            candidate = File(parent, "${base}_cp$counter$extension")
            counter += 1
        } while (mediaTargetExists(context, candidate))
        return candidate
    }

    fun resolveMediaUri(context: Context, target: File): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isManagedPublicFile(target)) {
            findManagedPublicMediaUri(context, target.name)
        } else {
            target.takeIf { it.isFile }?.let(Uri::fromFile)
        }
    }

    fun isManagedPublicMedia(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.let(::isManagedPublicFile) == true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.authority != MediaStore.AUTHORITY) {
            return false
        }
        return queryStringColumn(
            context.contentResolver,
            uri,
            MediaStore.MediaColumns.RELATIVE_PATH
        ) == PUBLIC_RELATIVE_PATH
    }

    fun isSharedPublicMedia(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.let(::isSharedPublicFile) == true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.authority != MediaStore.AUTHORITY) {
            return false
        }
        return queryStringColumn(
            context.contentResolver,
            uri,
            MediaStore.MediaColumns.RELATIVE_PATH
        ) in visiblePublicRelativePaths()
    }

    private fun mediaTargetExists(context: Context, target: File): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isManagedPublicFile(target)) {
            hasManagedMediaStoreName(context.contentResolver, target.name)
        } else {
            target.exists()
        }
    }

    /** 构造失败 MoveResult：reason 为简洁原因（进 lastError），detail 为完整诊断报告（进任务/全局日志）。 */
    private fun failMove(
        reason: String,
        contextDetail: String,
        throwable: Throwable? = null
    ): MoveResult {
        val stack = throwable?.let { "\n" + it.stackTraceToString() }.orEmpty()
        val timestamp =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val detail = "$timestamp\n[原因] $reason\n[现场] $contextDetail\n[堆栈] $stack"
        return MoveResult(false, reason, detail)
    }

    @Synchronized
    @SuppressLint("NewApi")
    fun moveMedia(context: Context, from: Uri, to: Uri): Boolean {
        return moveMediaWithReason(context, from, to).ok
    }

    /** 移动结果：ok 是否成功；reason 为失败时的简洁原因；detail 为完整结构化诊断报告（供任务日志/全局日志）。 */
    data class MoveResult(
        val ok: Boolean,
        val reason: String? = null,
        val detail: String? = null
    )

    @Synchronized
    @SuppressLint("NewApi")
    fun moveMediaWithReason(context: Context, from: Uri, to: Uri): MoveResult {
        if (to.scheme == "file") {
            val target = to.path?.let(::File) ?: return failMove(
                "moveMedia：目标无路径",
                "to=$to 设备API=${Build.VERSION.SDK_INT}"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isManagedPublicFile(target)) {
                // moveFileToDownloadsFolder 返回 MoveResult（含完整 detail）；此处不再重复写日志
                return moveFileToDownloadsFolder(context, from, target.name)
            }
            if (isFileApiSupportedByUri(context, to)) {
                val moveResult = moveToFileWithReason(context, from, target)
                if (moveResult.ok) {
                    return moveResult
                }
                val reason = moveResult.reason ?: "文件移动失败"
                val detail = moveResult.detail ?: failMove(
                    "moveMedia：文件移动失败",
                    "from=$from to=$target 设备API=${Build.VERSION.SDK_INT} " +
                        "fromExists=${uriExistence(context, from)} toExists=${uriExistence(context, to)} " +
                        "reason=$reason"
                ).detail.orEmpty()
                return MoveResult(false, reason, detail)
            }
        }
        AppLogger.e("Unsupported media move destination: $to")
        return failMove(
            "moveMedia：不支持的移动目标",
            "from=$from to=$to 设备API=${Build.VERSION.SDK_INT}"
        )
    }

    fun renameMedia(context: Context, from: Uri, newName: String): RenameMediaResult {
        if (newName.isBlank()) return RenameMediaResult.Invalid
        return try {
            when (val existence = uriExistence(context, from)) {
                UriExistence.Exists -> Unit
                UriExistence.NotFound -> return RenameMediaResult.Failed("Media not found")
                is UriExistence.Unknown -> return RenameMediaResult.Failed(existence.reason)
            }
            val currentName = queryDisplayName(context, from)
                ?: return RenameMediaResult.Failed("Unable to read the current media name")
            val cleanedFileName = sanitizeDisplayName(
                requestedName = newName,
                forcedExtension = currentName.substringAfterLast('.', "")
            )
            if (cleanedFileName == currentName) {
                return RenameMediaResult.Success(currentName, from)
            }
            if (mediaNameExistsBeside(context, from, cleanedFileName)) {
                return RenameMediaResult.AlreadyExists
            }

            if (isFileApiSupportedByUri(context, from)) {
                val fromFile = from.toFile()
                val toFile = File(fromFile.parentFile, cleanedFileName)
                if (toFile.exists()) {
                    return RenameMediaResult.AlreadyExists
                }
                val renamed = renameWithLock(fromFile, toFile)
                return if (
                    renamed && !fromFile.exists() && toFile.isFile && toFile.name == cleanedFileName
                ) {
                    RenameMediaResult.Success(toFile.name, Uri.fromFile(toFile))
                } else {
                    RenameMediaResult.Failed("File rename did not reach the requested final state")
                }
            }

            if (DocumentsContract.isDocumentUri(context, from)) {
                val renamedUri = DocumentsContract.renameDocument(
                    context.contentResolver,
                    from,
                    cleanedFileName
                ) ?: return RenameMediaResult.Failed("Document provider rejected the rename")
                return if (queryDisplayName(context, renamedUri) == cleanedFileName) {
                    RenameMediaResult.Success(cleanedFileName, renamedUri)
                } else {
                    RenameMediaResult.Failed("Document name was not updated")
                }
            }

            val contentUri = findMediaStoreContentUri(context, from)
                ?: return RenameMediaResult.Failed("MediaStore item was not found")
            val updated = context.contentResolver.update(
                contentUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanedFileName)
                },
                null,
                null
            )
            if (updated == 1 && queryDisplayName(context, contentUri) == cleanedFileName) {
                RenameMediaResult.Success(cleanedFileName, contentUri)
            } else {
                RenameMediaResult.Failed("MediaStore update did not reach the requested name")
            }
        } catch (error: SecurityException) {
            val contentUri = findMediaStoreContentUri(context, from) ?: from
            resolveRenameAuth(context, contentUri, newName, error)
        } catch (error: Throwable) {
            AppLogger.e("renameMedia failed for $from", error)
            RenameMediaResult.Failed(error.message ?: "Media rename failed")
        }
    }

    sealed class RenameMediaResult {
        data class Success(val name: String, val uri: Uri) : RenameMediaResult()
        data class NeedsAuth(
            val intentSender: IntentSender,
            val retryUri: Uri,
            val requestedName: String
        ) : RenameMediaResult()
        object AlreadyExists : RenameMediaResult()
        object Invalid : RenameMediaResult()
        data class Failed(val reason: String) : RenameMediaResult()
    }

    private fun resolveRenameAuth(
        context: Context,
        contentUri: Uri,
        requestedName: String,
        error: SecurityException
    ): RenameMediaResult {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                contentUri.authority == MediaStore.AUTHORITY -> RenameMediaResult.NeedsAuth(
                MediaStore.createWriteRequest(
                    context.contentResolver,
                    arrayListOf(contentUri)
                ).intentSender,
                contentUri,
                requestedName
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
                RenameMediaResult.NeedsAuth(
                    error.userAction.actionIntent.intentSender,
                    contentUri,
                    requestedName
                )
            else -> RenameMediaResult.Failed(
                error.message ?: "Write authorization is unavailable"
            )
        }
    }

    private fun mediaNameExistsBeside(context: Context, uri: Uri, displayName: String): Boolean {
        if (DocumentsContract.isDocumentUri(context, uri)) return false
        if (isFileApiSupportedByUri(context, uri)) {
            val source = uri.toFile()
            return File(source.parentFile, displayName).exists()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val contentUri = findMediaStoreContentUri(context, uri) ?: return false
        val relativePath = queryStringColumn(
            context.contentResolver,
            contentUri,
            MediaStore.MediaColumns.RELATIVE_PATH
        ) ?: return false
        if (relativePath !in visiblePublicRelativePaths()) return false
        return hasMediaStoreNameAtPath(
            context.contentResolver,
            displayName,
            relativePath,
            contentUri
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file" || uri.scheme == null) {
            return uri.path?.let(::File)?.name
        }
        return queryStringColumn(context.contentResolver, uri, OpenableColumns.DISPLAY_NAME)
    }

    private fun queryStringColumn(
        contentResolver: ContentResolver,
        uri: Uri,
        column: String
    ): String? {
        return contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
        }
    }

    private fun sanitizeDisplayName(
        requestedName: String,
        forcedExtension: String? = null
    ): String {
        val leafName = File(requestedName).name
        val requestedExtension = leafName.substringAfterLast('.', "")
        val baseName = if (requestedExtension.isBlank()) {
            leafName
        } else {
            leafName.substringBeforeLast('.')
        }
        val cleanBase = FileNameCleaner.cleanFileName(baseName)
        val extension = (forcedExtension ?: requestedExtension)
            .filter { it.isLetterOrDigit() }
        return if (extension.isBlank()) cleanBase else "$cleanBase.$extension"
    }

    /**
     * 删除指定 uri 的媒体文件，返回结构化结果。
     * - File.delete 能删（私有目录 / 非 Q 公共目录）→ Success
     * - File.delete 删不掉 → 查 MediaStore 拿 content Uri 再 contentResolver.delete：
     *   Android 11+ 需授权时返回 NeedsAuth(createDeleteRequest 的 IntentSender)，交 Fragment 弹系统确认；
     *   其他失败 → Failed
     */
    fun deleteMedia(context: Context, uri: Uri): DeleteMediaResult {
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                return try {
                    val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                    if (deleted && isUriDefinitelyAbsent(context, uri)) {
                        DeleteMediaResult.Success
                    } else {
                        DeleteMediaResult.Failed
                    }
                } catch (error: SecurityException) {
                    resolveDeleteAuth(context, uri, error)
                }
            }

            val operationUri = if (isFileApiSupportedByUri(context, uri)) {
                uri
            } else {
                findMediaStoreContentUri(context, uri) ?: run {
                    AppLogger.d("deleteMedia: no MediaStore mapping for $uri")
                    return DeleteMediaResult.Failed
                }
            }
            if (uriExistence(context, operationUri) != UriExistence.Exists) {
                AppLogger.d("deleteMedia: URI is not definitely present: $operationUri")
                return DeleteMediaResult.Failed
            }
            if (operationUri.scheme == "file") {
                val file = operationUri.toFile()
                return if (file.delete() && isUriDefinitelyAbsent(context, operationUri)) {
                    DeleteMediaResult.Success
                } else {
                    DeleteMediaResult.Failed
                }
            }

            try {
                val rows = context.contentResolver.delete(operationUri, null, null)
                if (rows == 1 && isUriDefinitelyAbsent(context, operationUri)) {
                    DeleteMediaResult.Success
                } else {
                    AppLogger.e(
                        "deleteMedia: expected one row and an absent URI, rows=$rows uri=$operationUri"
                    )
                    DeleteMediaResult.Failed
                }
            } catch (error: SecurityException) {
                resolveDeleteAuth(context, operationUri, error)
            }
        } catch (error: Throwable) {
            AppLogger.e("deleteMedia error for $uri", error)
            DeleteMediaResult.Failed
        }
    }

    /**
     * 解析删除所需的系统授权：
     * - Android 11+ (R)：MediaStore.createDeleteRequest
     * - Android 10 (Q)：RecoverableSecurityException.userAction.actionIntent.intentSender
     * - 其他：Failed
     */
    private fun resolveDeleteAuth(
        context: Context, contentUri: Uri, error: SecurityException
    ): DeleteMediaResult {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                contentUri.authority == MediaStore.AUTHORITY ->
                DeleteMediaResult.NeedsAuth(
                    MediaStore.createDeleteRequest(
                        context.contentResolver, arrayListOf(contentUri)
                    ).intentSender,
                    retryUri = null,
                    verificationUri = contentUri
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
                DeleteMediaResult.NeedsAuth(
                    error.userAction.actionIntent.intentSender,
                    retryUri = contentUri,
                    verificationUri = contentUri
                )
            else -> {
                AppLogger.e("deleteMedia authorization unavailable for $contentUri", error)
                DeleteMediaResult.Failed
            }
        }
    }

    /**
     * 把 file:// uri 映射成 MediaStore 的 content Uri（依次查 Downloads 与 Video：下载视频常落在 Downloads）；
     * content://（非 document，document 已在 deleteMedia 开头处理）直接返回。
     */
    private fun findMediaStoreContentUri(context: Context, uri: Uri): Uri? {
        if (uri.scheme == "content") return uri
        if (uri.scheme != "file") return null
        val file = uri.path?.let(::File) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = when {
                isManagedPublicFile(file) -> PUBLIC_RELATIVE_PATH
                isLegacyPublicFile(file) -> LEGACY_PUBLIC_RELATIVE_PATH
                else -> return null
            }
            return findPublicMediaUri(context, file.name, relativePath)
        }

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DATA} = ?",
            arrayOf(file.absolutePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0)
                )
            }
        }
        return null
    }

    /** deleteMedia 的结构化结果。NeedsAuth 需由 Fragment 发起 IntentSender 系统授权。 */
    sealed class DeleteMediaResult {
        object Success : DeleteMediaResult()
        data class NeedsAuth(
            val intentSender: IntentSender,
            val retryUri: Uri?,
            val verificationUri: Uri = retryUri ?: Uri.EMPTY
        ) : DeleteMediaResult()
        object Failed : DeleteMediaResult()
    }

    sealed class UriExistence {
        object Exists : UriExistence()
        object NotFound : UriExistence()
        data class Unknown(val reason: String) : UriExistence()
    }

    fun isUriExists(context: Context, uri: Uri): Boolean {
        return uriExistence(context, uri) == UriExistence.Exists
    }

    fun isUriDefinitelyAbsent(context: Context, uri: Uri): Boolean {
        return uriExistence(context, uri) == UriExistence.NotFound
    }

    fun uriExistence(context: Context, uri: Uri): UriExistence {
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: return UriExistence.Unknown("File URI has no path")
            return if (File(path).exists()) UriExistence.Exists else UriExistence.NotFound
        }
        if (uri.scheme != "content") {
            return UriExistence.Unknown("Unsupported URI scheme: ${uri.scheme}")
        }

        return try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor == null) {
                probeContentDescriptor(context, uri)
            } else {
                cursor.use {
                    if (it.moveToFirst()) UriExistence.Exists else UriExistence.NotFound
                }
            }
        } catch (_: FileNotFoundException) {
            UriExistence.NotFound
        } catch (error: SecurityException) {
            UriExistence.Unknown(error.message ?: "URI access denied")
        } catch (error: Throwable) {
            AppLogger.w("URI query failed for $uri: ${error.message}")
            probeContentDescriptor(context, uri)
        }
    }

    private fun probeContentDescriptor(context: Context, uri: Uri): UriExistence {
        return try {
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: return UriExistence.Unknown("Content provider returned no descriptor")
            descriptor.use { UriExistence.Exists }
        } catch (_: FileNotFoundException) {
            UriExistence.NotFound
        } catch (error: SecurityException) {
            UriExistence.Unknown(error.message ?: "URI access denied")
        } catch (error: Throwable) {
            UriExistence.Unknown(error.message ?: "Unable to verify URI existence")
        }
    }

    fun getContentLength(context: Context, uri: Uri): Long {
        return if (isFileApiSupportedByUri(context, uri)) {
            uri.toFile().length()
        } else {
            ContentLengthResolver.resolve(context, uri).length ?: -1L
        }
    }

    fun isFileApiSupportedByUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val file = uri.path?.let(::File) ?: return false
        return isDescendantOf(file, context.filesDir) ||
            context.getExternalFilesDir(null)?.let { isDescendantOf(file, it) } == true
    }

    private fun isManagedPublicFile(file: File): Boolean {
        return file.parentFile?.let { parent ->
            runCatching { parent.canonicalFile == publicDownloadsDir.canonicalFile }
                .getOrDefault(false)
        } == true
    }

    private fun isLegacyPublicFile(file: File): Boolean {
        return file.parentFile?.let { parent ->
            runCatching { parent.canonicalFile == legacyPublicDownloadsDir.canonicalFile }
                .getOrDefault(false)
        } == true
    }

    private fun isSharedPublicFile(file: File): Boolean {
        return isManagedPublicFile(file) || isLegacyPublicFile(file)
    }

    private fun isDescendantOf(file: File, root: File): Boolean {
        return runCatching {
            val canonicalFile = file.canonicalFile
            val canonicalRoot = root.canonicalFile
            val rootPath = canonicalRoot.path
            val rootPrefix = if (rootPath.endsWith(File.separator)) {
                rootPath
            } else {
                rootPath + File.separator
            }
            canonicalFile == canonicalRoot || canonicalFile.path.startsWith(rootPrefix)
        }.getOrDefault(false)
    }

    // WITHOUT LOCK EXISTS PROBABILITY OF CORRUPTED FILE AFTER renameTo()
    private fun renameWithLock(sourceFile: File, targetFile: File): Boolean {
        try {
            // 1. Acquire a lock on the source file
            val randomAccessFile = RandomAccessFile(sourceFile, "rw")
            val fileChannel: FileChannel = randomAccessFile.channel
            val fileLock: FileLock = fileChannel.lock()

            try {
                // 2. Perform the renameTo() operation while holding the lock
                val success = sourceFile.renameTo(targetFile)
                return success

            } finally {
                // 3. Release the lock in the finally block
                fileLock.release()
                randomAccessFile.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppLogger.d(e.message.toString())
            return false
        }
    }

    private fun getTmpDataDir(context: Context, isExternal: Boolean): File {
        val file = getNamedTmpDataDir(context, isExternal, TMP_DATA_FOLDER_NAME)
        if (!file.exists()) {
            file.mkdirs()
        }

        return file
    }

    private fun getPrivateDownloadsDirFilesObj(
        context: Context,
        isExternal: Boolean,
        storageClass: MediaStorageClass
    ): List<MediaEntry> {
        val directory = getPrivateDownloadsDir(context, isExternal)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory.listFiles().orEmpty()
            .filter(File::isFile)
            .map { mediaEntryForFile(it, storageClass) }
    }

    private fun getPrivateDownloadsDir(context: Context, isExternal: Boolean): File {
        return getPrivateNamedDir(context, isExternal, FOLDER_NAME)
    }

    private fun getPrivateNamedDir(context: Context, isExternal: Boolean, folderName: String): File {
        val path = if (isExternal) {
            "${context.getExternalFilesDir(null)}/$folderName"
        } else {
            "${context.filesDir.absolutePath}/$folderName"
        }

        return File(path)
    }

    private fun getNamedTmpDataDir(context: Context, isExternal: Boolean, folderName: String): File {
        val path = if (isExternal) {
            "${context.getExternalFilesDir(null)}/$folderName"
        } else {
            "${context.filesDir.absolutePath}/$folderName"
        }

        return File(path)
    }

    internal fun migratePrivateDirectory(
        legacy: File,
        current: File,
        moveOperation: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
        copyOperation: (File, File) -> Boolean = { source, target ->
            source.copyRecursively(
                target,
                overwrite = false,
                onError = { _, _ -> kotlin.io.OnErrorAction.TERMINATE }
            )
        }
    ) {
        if (!legacy.exists() || legacy.absolutePath == current.absolutePath) {
            return
        }

        if (!current.exists() && moveOperation(legacy, current)) {
            AppLogger.d("Migrated private directory ${legacy.absolutePath} -> ${current.absolutePath}")
            return
        }

        if (!current.exists() && !current.mkdirs()) {
            AppLogger.e("Failed to create migrated directory ${current.absolutePath}")
            return
        }

        legacy.listFiles()?.forEach { source ->
            val target = File(current, source.name)
            if (target.exists()) {
                AppLogger.w(
                    "Migration target already exists; preserving source ${source.absolutePath}"
                )
                return@forEach
            }

            val moved = runCatching { moveOperation(source, target) }
                .getOrElse { error ->
                    AppLogger.e("Failed to move ${source.absolutePath}: ${error.message}")
                    false
                }
            if (moved) {
                return@forEach
            }

            val copied = runCatching { copyOperation(source, target) }
                .getOrElse { error ->
                    AppLogger.e("Failed to copy ${source.absolutePath}: ${error.message}")
                    false
                }
            val verified = copied && copiedTreeMatches(source, target)
            if (!verified) {
                if (target.exists() && !target.deleteRecursively()) {
                    AppLogger.e("Failed to clean incomplete migration target ${target.absolutePath}")
                }
                AppLogger.e("Migration copy verification failed for ${source.absolutePath}")
                return@forEach
            }

            if (!source.deleteRecursively() || source.exists()) {
                AppLogger.w(
                    "Verified migration target but could not fully remove source ${source.absolutePath}"
                )
            }
        }

        legacy.listFiles()?.takeIf { it.isEmpty() }?.let {
            legacy.delete()
        }
    }

    private fun copiedTreeMatches(source: File, target: File): Boolean {
        return runCatching {
            when {
                source.isFile -> target.isFile && filesHaveSameContent(source, target)
                source.isDirectory -> {
                    if (!target.isDirectory) return@runCatching false
                    val sourceChildren = source.listFiles() ?: return@runCatching false
                    val targetChildren = target.listFiles() ?: return@runCatching false
                    val targetByName = targetChildren.associateBy { it.name }
                    sourceChildren.size == targetChildren.size && sourceChildren.all { child ->
                        val copiedChild = targetByName[child.name] ?: return@all false
                        copiedTreeMatches(child, copiedChild)
                    }
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun filesHaveSameContent(source: File, target: File): Boolean {
        if (source.length() != target.length()) return false
        return fileDigest(source).contentEquals(fileDigest(target))
    }

    private fun fileDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().buffered().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    internal fun listLegacyPublicFiles(): List<MediaEntry> {
        return publicMediaFilesIn(publicDownloadsDir, MediaStorageClass.MANAGED_PUBLIC) +
            publicMediaFilesIn(legacyPublicDownloadsDir, MediaStorageClass.LEGACY_PUBLIC)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getPublicDownloadsDirFilesObjMediaStore(
        context: Context
    ): List<MediaEntry> {
        val files = mutableListOf<MediaEntry>()
        val targetUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            targetUri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            visiblePublicRelativePaths().toTypedArray(),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                if (name.substringAfterLast('.', "").lowercase(Locale.US) in PUBLIC_MEDIA_EXTENSIONS) {
                    val contentUri = ContentUris.withAppendedId(targetUri, id)
                    val storageClass = when (cursor.getString(pathColumn)) {
                        PUBLIC_RELATIVE_PATH -> MediaStorageClass.MANAGED_PUBLIC
                        LEGACY_PUBLIC_RELATIVE_PATH -> MediaStorageClass.LEGACY_PUBLIC
                        else -> continue
                    }
                    files += MediaEntry(
                        id = stableMediaId(contentUri),
                        displayName = name,
                        uri = contentUri,
                        storageClass = storageClass
                    )
                }
            }
        }
        return files
    }

    private fun publicMediaFilesIn(
        directory: File,
        storageClass: MediaStorageClass
    ): List<MediaEntry> {
        return directory.listFiles().orEmpty()
            .filter { file ->
                file.isFile && file.extension.lowercase(Locale.US) in PUBLIC_MEDIA_EXTENSIONS
            }
            .map { mediaEntryForFile(it, storageClass) }
    }

    private fun mediaEntryForFile(file: File, storageClass: MediaStorageClass): MediaEntry {
        val uri = Uri.fromFile(file)
        return MediaEntry(
            id = stableMediaId(uri),
            displayName = file.name,
            uri = uri,
            storageClass = storageClass
        )
    }

    private fun stableMediaId(uri: Uri): Long {
        var hash = -0x340d631b7bdddcdbL
        uri.toString().forEach { character ->
            hash = hash xor character.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasManagedMediaStoreName(
        contentResolver: ContentResolver,
        displayName: String,
        excludeUri: Uri? = null
    ): Boolean {
        return hasMediaStoreNameAtPath(
            contentResolver,
            displayName,
            PUBLIC_RELATIVE_PATH,
            excludeUri
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasMediaStoreNameAtPath(
        contentResolver: ContentResolver,
        displayName: String,
        relativePath: String,
        excludeUri: Uri? = null
    ): Boolean {
        val excludedId = excludeUri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
        val baseSelection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
        val selection = if (excludedId == null) {
            baseSelection
        } else {
            "$baseSelection AND ${MediaStore.MediaColumns._ID} != ?"
        }
        val args = if (excludedId == null) {
            arrayOf(displayName, relativePath)
        } else {
            arrayOf(displayName, relativePath, excludedId.toString())
        }
        return contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            args,
            null
        )?.use { it.moveToFirst() } == true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasVisiblePublicMediaStoreName(
        contentResolver: ContentResolver,
        displayName: String
    ): Boolean {
        return contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(displayName, PUBLIC_RELATIVE_PATH, LEGACY_PUBLIC_RELATIVE_PATH),
            null
        )?.use { it.moveToFirst() } == true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun findManagedPublicMediaUri(context: Context, displayName: String): Uri? {
        return findPublicMediaUri(context, displayName, PUBLIC_RELATIVE_PATH)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findPublicMediaUri(
        context: Context,
        displayName: String,
        relativePath: String
    ): Uri? {
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(displayName, relativePath),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            )
        }
    }

    private fun visiblePublicRelativePaths(): List<String> {
        return listOf(PUBLIC_RELATIVE_PATH, LEGACY_PUBLIC_RELATIVE_PATH)
    }

    private fun isManagedPublicDestinationSelected(): Boolean {
        return IS_EXTERNAL_STORAGE_USE && !IS_APP_DATA_DIR_USE
    }

    private fun moveToFile(context: Context, sourceUri: Uri, target: File): Boolean {
        return moveToFileWithReason(context, sourceUri, target).ok
    }

    /** 文件移动（非 MediaStore 路径），返回 null=成功，非 null=失败原因。 */
    private fun moveToFileWithReason(context: Context, sourceUri: Uri, target: File): MoveResult {
        if (uriExistence(context, sourceUri) != UriExistence.Exists) {
            return failMove("moveToFile：源不存在", "sourceUri=$sourceUri target=$target")
        }
        if (target.exists()) {
            return failMove("moveToFile：目标已存在", "target=$target")
        }
        val parent = target.parentFile ?: return failMove("moveToFile：目标无父目录", "target=$target")
        if (!parent.exists() && !parent.mkdirs()) {
            return failMove("moveToFile：无法创建目标目录", "parent=$parent")
        }
        val sourceLength = getContentLength(context, sourceUri)

        if (sourceUri.scheme == "file") {
            val source = sourceUri.toFile()
            val moved = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Files.move(source.toPath(), target.toPath())
                    true
                } else {
                    renameWithLock(source, target)
                }
            } catch (error: Throwable) {
                AppLogger.e("File move failed from $source to $target", error)
                false
            }
            if (!moved || source.exists() || !target.isFile) {
                return failMove(
                    "moveToFile：移动后状态异常",
                    "moved=$moved sourceExists=${source.exists()} targetIsFile=${target.isFile} " +
                        "source=$source target=$target 设备API=${Build.VERSION.SDK_INT}"
                )
            }
            if (sourceLength >= 0L && target.length() != sourceLength) {
                return failMove(
                    "moveToFile：移动后长度不匹配",
                    "expected=$sourceLength actual=${target.length()} target=$target"
                )
            }
        } else {
            val copied = try {
                openSourceInputStream(context, sourceUri).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (error: Throwable) {
                AppLogger.e("Content copy failed from $sourceUri to $target", error)
                target.delete()
                return failMove("moveToFile：复制失败", "sourceUri=$sourceUri target=$target", error)
            }
            if (copied <= 0L || (sourceLength >= 0L && copied != sourceLength) || target.length() != copied) {
                target.delete()
                return failMove(
                    "moveToFile：复制字节数不匹配",
                    "expected=$sourceLength copied=$copied targetSize=${target.length()} target=$target"
                )
            }
            when (val deletion = deleteSourceAndVerify(context, sourceUri)) {
                UriExistence.NotFound -> Unit
                UriExistence.Exists -> {
                    target.delete()
                    return failMove("moveToFile：复制后源仍存在", "sourceUri=$sourceUri target=$target")
                }
                is UriExistence.Unknown -> return failMove(
                    "moveToFile：无法验证源删除",
                    "sourceUri=$sourceUri reason=${deletion.reason}"
                )
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && isManagedPublicFile(target)) {
            scanFile(context, target)
        }
        return if (target.isFile && (sourceLength < 0L || target.length() == sourceLength)) {
            MoveResult(true)
        } else {
            failMove(
                "moveToFile：最终状态异常",
                "targetIsFile=${target.isFile} targetSize=${target.length()} expected=$sourceLength target=$target"
            )
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun moveFileToDownloadsFolder(
        context: Context,
        sourceUri: Uri,
        fileName: String
    ): MoveResult {
        if (uriExistence(context, sourceUri) != UriExistence.Exists) {
            return failMove(
                "MediaStore 发布：源不存在",
                "sourceUri=$sourceUri fileName=$fileName 设备API=${Build.VERSION.SDK_INT}"
            )
        }
        val sourceLength = getContentLength(context, sourceUri)
        if (sourceLength == 0L) {
            return failMove(
                "MediaStore 发布：源长度为 0",
                "sourceUri=$sourceUri fileName=$fileName 设备API=${Build.VERSION.SDK_INT}"
            )
        }
        val displayName = sanitizeDisplayName(fileName)
        if (hasManagedMediaStoreName(context.contentResolver, displayName)) {
            return failMove(
                "MediaStore 发布：目标重名",
                "displayName=$displayName relativePath=$PUBLIC_RELATIVE_PATH"
            )
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeForName(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val insertedUri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: return failMove(
            "MediaStore 发布：insert 返回 null",
            "displayName=$displayName relativePath=$PUBLIC_RELATIVE_PATH " +
                "sourceUri=$sourceUri sourceLength=$sourceLength 设备API=${Build.VERSION.SDK_INT}"
        )

        var completed = false
        var retainTargetOnFailure = false
        var failure: MoveResult? = null
        var copiedBytes: Long? = null
        var targetLengthProbe: ContentLengthProbe? = null
        try {
            val copied = openSourceInputStream(context, sourceUri).use { input ->
                val output = context.contentResolver.openOutputStream(insertedUri, "w")
                    ?: throw IOException("MediaStore returned no output stream")
                output.use {
                    val count = input.copyTo(it)
                    it.flush()
                    count
                }
            }
            copiedBytes = copied
            if (copied <= 0L || (sourceLength >= 0L && copied != sourceLength)) {
                throw IOException("Copied byte count does not match the source")
            }

            val pending = queryMediaStoreRecord(context.contentResolver, insertedUri)
                ?: throw IOException("Pending MediaStore record disappeared")
            if (pending.displayName != displayName ||
                pending.relativePath != PUBLIC_RELATIVE_PATH ||
                pending.isPending != 1
            ) {
                throw IOException(
                    "Pending MediaStore record failed verification " +
                        "(expect name=$displayName path=$PUBLIC_RELATIVE_PATH pending=1; " +
                        "actual name=${pending.displayName} path=${pending.relativePath} " +
                        "pending=${pending.isPending} size=${pending.size})"
                )
            }

            val publishedRows = context.contentResolver.update(
                insertedUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            if (publishedRows != 1) {
                throw IOException("Expected one published MediaStore row, got $publishedRows")
            }
            val published = queryMediaStoreRecord(context.contentResolver, insertedUri)
                ?: throw IOException("Published MediaStore record disappeared")
            if (published.displayName != displayName ||
                published.relativePath != PUBLIC_RELATIVE_PATH ||
                published.isPending != 0
            ) {
                throw IOException(
                    "Published MediaStore record failed verification " +
                        "(expect name=$displayName path=$PUBLIC_RELATIVE_PATH pending=0; " +
                        "actual name=${published.displayName} path=${published.relativePath} " +
                        "pending=${published.isPending} size=${published.size}; publishedRows=$publishedRows)"
                )
            }

            targetLengthProbe = ContentLengthResolver.resolve(context, insertedUri)
            if (targetLengthProbe?.length != copied) {
                throw IOException(
                    "Published media length failed verification " +
                        "(expected=$copied actual=${targetLengthProbe?.length} " +
                        "mediaStoreReportedSize=${targetLengthProbe?.mediaStoreReportedSize} " +
                        "descriptorSize=${targetLengthProbe?.descriptorSize} " +
                        "assetDescriptorSize=${targetLengthProbe?.assetDescriptorSize} " +
                        "countedSize=${targetLengthProbe?.countedSize})"
                )
            }
            DownloadedMediaValidator.validate(context, insertedUri)?.let { validationError ->
                throw IOException("Published media validation failed: $validationError")
            }

            when (deleteSourceAndVerify(context, sourceUri)) {
                UriExistence.NotFound -> retainTargetOnFailure = true
                UriExistence.Exists -> throw IOException("Source still exists after publication")
                is UriExistence.Unknown -> {
                    retainTargetOnFailure = true
                    throw IOException("Unable to verify source deletion")
                }
            }
            if (uriExistence(context, insertedUri) != UriExistence.Exists) {
                throw IOException("Published media URI is not definitely present")
            }
            completed = true
        } catch (error: Throwable) {
            AppLogger.e("MediaStore publication failed for $displayName", error)
            failure = failMove(
                "MediaStore 发布失败：${error.message}",
                "displayName=$displayName relativePath=$PUBLIC_RELATIVE_PATH " +
                    "insertedUri=$insertedUri sourceUri=$sourceUri sourceLength=$sourceLength " +
                    "copiedBytes=$copiedBytes " +
                    "mediaStoreReportedSize=${targetLengthProbe?.mediaStoreReportedSize ?: runCatching { queryMediaStoreRecord(context.contentResolver, insertedUri)?.size }.getOrNull()} " +
                    "descriptorSize=${targetLengthProbe?.descriptorSize} " +
                    "assetDescriptorSize=${targetLengthProbe?.assetDescriptorSize} " +
                    "countedSize=${targetLengthProbe?.countedSize} " +
                    "设备API=${Build.VERSION.SDK_INT}",
                error
            )
        }

        if (!completed && !retainTargetOnFailure) {
            val cleanupFailure = cleanupInsertedMediaStoreRow(context, insertedUri)
            if (cleanupFailure != null) {
                val base = failure ?: failMove(
                    "MediaStore 发布失败",
                    "displayName=$displayName insertedUri=$insertedUri"
                )
                failure = base.copy(
                    detail = buildString {
                        append(base.detail.orEmpty())
                        append("\n[清理] ")
                        append(cleanupFailure)
                    }
                )
            }
        }
        return if (completed) MoveResult(true) else failure ?: failMove(
            "MediaStore 发布未完成",
            "displayName=$displayName insertedUri=$insertedUri sourceUri=$sourceUri"
        )
    }

    private fun openSourceInputStream(context: Context, uri: Uri) = when (uri.scheme) {
        "file", null -> File(uri.path ?: throw FileNotFoundException("Source path is missing"))
            .inputStream()
        "content" -> context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Unable to open source URI")
        else -> throw FileNotFoundException("Unsupported source URI scheme: ${uri.scheme}")
    }

    private fun deleteSourceAndVerify(context: Context, uri: Uri): UriExistence {
        try {
            when {
                uri.scheme == "file" || uri.scheme == null -> {
                    val file = File(uri.path ?: return UriExistence.Unknown("Source path is missing"))
                    if (!file.delete() && file.exists()) return UriExistence.Exists
                }
                DocumentsContract.isDocumentUri(context, uri) -> {
                    if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                        return uriExistence(context, uri)
                    }
                }
                uri.scheme == "content" -> {
                    if (context.contentResolver.delete(uri, null, null) != 1) {
                        return uriExistence(context, uri)
                    }
                }
                else -> return UriExistence.Unknown("Unsupported source URI scheme: ${uri.scheme}")
            }
        } catch (error: Throwable) {
            return UriExistence.Unknown(error.message ?: "Source deletion failed")
        }
        return uriExistence(context, uri)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cleanupInsertedMediaStoreRow(context: Context, uri: Uri): String? {
        val rows = runCatching { context.contentResolver.delete(uri, null, null) }
            .getOrElse { error ->
                AppLogger.e("Failed to clean MediaStore row $uri", error)
                -1
            }
        val absent = isUriDefinitelyAbsent(context, uri)
        return if (rows == 1 && absent) {
            null
        } else {
            val reason = "MediaStore row cleanup was incomplete: rows=$rows absent=$absent uri=$uri"
            AppLogger.e(reason)
            reason
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryMediaStoreRecord(
        contentResolver: ContentResolver,
        uri: Uri
    ): MediaStoreRecord? {
        return contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            MediaStoreRecord(
                displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                ),
                relativePath = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                ),
                isPending = cursor.getInt(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                ),
                size = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                )
            )
        }
    }

    private fun mimeTypeForName(displayName: String): String {
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(displayName.substringAfterLast('.', ""))
            ?: "application/octet-stream"
    }

    private data class MediaStoreRecord(
        val displayName: String,
        val relativePath: String,
        val isPending: Int,
        val size: Long
    )

    private fun scanFile(context: Context, file: File) {
        try {
            val mimeType =
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)

            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
                null
            )
        } catch (e: Exception) {
            AppLogger.e("Error triggering media scan ${e.message}")
        }
    }
}

object FileNameCleaner {
    private const val MAX_FILE_NAME_LENGTH = 100
    private val illegalChars = intArrayOf(
        34,
        60,
        62,
        124,
        0,
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        12,
        13,
        14,
        15,
        16,
        17,
        18,
        19,
        20,
        21,
        22,
        23,
        24,
        25,
        26,
        27,
        28,
        29,
        30,
        31,
        58,
        42,
        63,
        92,
        47
    )

    init {
        Arrays.sort(illegalChars)
    }

    fun cleanFileName(badFileName: String): String {
        val cleanName = StringBuilder()
        for (element in badFileName) {
            val c = element.code
            if (Arrays.binarySearch(illegalChars, c) < 0) {
                cleanName.append(c.toChar())
            }
        }
        var finalName = cleanName.toString()
            .replace(".mp3", "")
            .replace(".mp4", "")
            .replace("/", "").replace("\\", "")
            .replace(":", "")
            .replace("*", "")
            .replace("?", "")
            .replace("\"", "")
            .replace("`", "")
            .replace("\'", "")
            .replace("<", "")
            .replace(">", "")
            .replace(".", "_")
            .replace("|", "")
            .replace(Regex("\\s*-\\s*"), "-")
            .replace(" ", "_").trim()
        if (finalName.isEmpty()) {
            finalName = "Untitled"
        }

        if (finalName.length > MAX_FILE_NAME_LENGTH) {
            return finalName.substring(0, MAX_FILE_NAME_LENGTH)
        }

        return finalName
    }
}
