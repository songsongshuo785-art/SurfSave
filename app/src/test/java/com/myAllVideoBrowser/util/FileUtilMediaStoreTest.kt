package com.myAllVideoBrowser.util

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FileUtilMediaStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun visibleNameLookup_scopesNameToLegacyAndSurfSavePublishedRows() {
        val provider = mediaProvider(displayName = "clip.mp4", rowExists = true)
        register(MediaStore.AUTHORITY, provider)

        assertTrue(FileUtil().hasDownloadWithName(context, "clip.mp4"))
        assertEquals(
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            provider.lastSelection
        )
        assertArrayEquals(
            arrayOf(
                "clip.mp4",
                FileUtil.PUBLIC_RELATIVE_PATH,
                FileUtil.LEGACY_PUBLIC_RELATIVE_PATH
            ),
            provider.lastSelectionArgs
        )
    }

    @Test
    fun deleteWithUnknownExistence_doesNotReportSuccessOrDelete() {
        val provider = mediaProvider(displayName = "protected.mp4", rowExists = true).apply {
            throwSecurityOnQuery = true
        }
        register(MediaStore.AUTHORITY, provider)

        val result = FileUtil().deleteMedia(context, provider.itemUri)

        assertSame(FileUtil.DeleteMediaResult.Failed, result)
        assertEquals(0, provider.deleteCalls)
        assertTrue(provider.rowExists)
    }

    @Test
    fun renameRequiresExactlyOneUpdatedRow() {
        val provider = mediaProvider(displayName = "old.mp4", rowExists = true).apply {
            updateResult = 0
        }
        register(MediaStore.AUTHORITY, provider)

        val result = FileUtil().renameMedia(context, provider.itemUri, "new.mp4")

        assertTrue(result is FileUtil.RenameMediaResult.Failed)
        assertEquals(1, provider.updateCalls)
        assertEquals("old.mp4", provider.displayName)
    }

    @Test
    fun renameRequiresRequestedFinalDisplayName() {
        val provider = mediaProvider(displayName = "old.mp4", rowExists = true).apply {
            updateResult = 1
            applyDisplayNameOnUpdate = false
        }
        register(MediaStore.AUTHORITY, provider)

        val result = FileUtil().renameMedia(context, provider.itemUri, "new.mp4")

        assertTrue(result is FileUtil.RenameMediaResult.Failed)
        assertEquals(1, provider.updateCalls)
        assertEquals("old.mp4", provider.displayName)
    }

    @Test
    fun publishUpdateFailure_cleansPendingRowAndPreservesSource() {
        val provider = mediaProvider(displayName = "unused.mp4", rowExists = false).apply {
            updateResult = 0
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "publish-update-failure.mp4").apply {
            writeMedia(this)
        }
        val target = File(FileUtil().publicDownloadsDir, "publish-update-failure.mp4")

        try {
            val moved = FileUtil().moveMedia(context, Uri.fromFile(source), Uri.fromFile(target))

            assertFalse(moved)
            assertTrue(source.isFile)
            assertFalse(provider.rowExists)
            assertEquals(1, provider.deleteCalls)
        } finally {
            source.delete()
        }
    }

    @Test
    fun sourceDeleteFailure_rejectsPublicationAndCleansTarget() {
        val targetProvider = mediaProvider(displayName = "unused.mp4", rowExists = false).apply {
            updateResult = 1
        }
        register(MediaStore.AUTHORITY, targetProvider)
        val sourceFile = temporaryFolder.newFile("content-source.mp4").also(::writeMedia)
        val sourceProvider = SourceProvider(sourceFile, deleteResult = 0)
        register(SOURCE_AUTHORITY, sourceProvider)
        val sourceUri = Uri.parse("content://$SOURCE_AUTHORITY/media/1")
        val target = File(FileUtil().publicDownloadsDir, "source-delete-failure.mp4")

        val moved = FileUtil().moveMedia(context, sourceUri, Uri.fromFile(target))

        assertFalse(moved)
        assertTrue(sourceProvider.rowExists)
        assertEquals(1, sourceProvider.deleteCalls)
        assertFalse(targetProvider.rowExists)
        assertEquals(1, targetProvider.deleteCalls)
    }

    // ---- MediaStore 发布诊断矩阵：验证失败时 MoveResult.detail 包含实际元数据 ----

    @Test
    fun insertNull_reportsExactStageSourceAndDisplayName() {
        val insertNullProvider = mediaProvider(
            displayName = "insert-null.mp4", rowExists = false
        ).apply {
            insertResultNull = true
        }
        register(MediaStore.AUTHORITY, insertNullProvider)
        val source = File(context.filesDir, "insert-null-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "insert-null.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertFalse(result.ok)
            assertTrue("reason=${result.reason}", result.reason.orEmpty().contains("insert 返回 null"))
            assertTrue(
                "detail 应包含 displayName 与相对路径",
                result.detail.orEmpty().contains("insert-null.mp4") &&
                    result.detail.orEmpty().contains(FileUtil.PUBLIC_RELATIVE_PATH) &&
                    result.detail.orEmpty().contains("insert-null-source.mp4")
            )
            assertEquals(0, insertNullProvider.deleteCalls)
        } finally {
            source.delete()
        }
    }

    @Test
    fun publishUpdateZero_reportsReturnedRowCountAndCleansPendingRow() {
        val provider = mediaProvider(displayName = "pending-zero.mp4", rowExists = false).apply {
            updateResult = 0
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "pending-zero-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "pending-zero.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertFalse(result.ok)
            assertTrue("reason=${result.reason}", result.reason.orEmpty().contains("got 0"))
            assertTrue(result.detail.orEmpty().contains("sourceLength="))
            assertFalse(provider.rowExists)
            assertEquals(1, provider.deleteCalls)
        } finally {
            source.delete()
        }
    }

    @Test
    fun relativePathNormalized_failureDetailIncludesActualPath() {
        // 模拟 RELATIVE_PATH 被系统归一化（末尾斜杠差异）：provider 返回不同相对路径
        val provider = mediaProvider(displayName = "path-norm.mp4", rowExists = false).apply {
            normalizeRelativePath = true // 模拟系统归一化：存缺末尾斜杠的相对路径
            updateResult = 1
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "path-norm-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "path-norm.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertFalse(result.ok)
            // 失败消息应包含实际路径与预期路径（区分归一化差异）
            assertTrue(
                "reason=${result.reason}",
                result.reason.orEmpty().contains("failed verification")
            )
            assertTrue(result.reason.orEmpty().contains("Download/SurfSave"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun outputStreamUnavailable_reportsStageAndCleansPendingRow() {
        val provider = mediaProvider(displayName = "no-output.mp4", rowExists = false).apply {
            outputUnavailable = true
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "no-output-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "no-output.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertFalse(result.ok)
            assertTrue(result.reason.orEmpty().contains("no output stream"))
            assertTrue(result.detail.orEmpty().contains("insertedUri="))
            assertTrue(source.isFile)
            assertFalse(provider.rowExists)
        } finally {
            source.delete()
        }
    }

    @Test
    fun delayedSizeMetadata_usesDescriptorLengthAndPublishesSuccessfully() {
        val provider = mediaProvider(displayName = "size-delay.mp4", rowExists = false).apply {
            reportedSizeOverride = 0L
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "size-delay-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "size-delay.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertTrue("reason=${result.reason} detail=${result.detail}", result.ok)
            assertFalse(source.exists())
            assertTrue(provider.rowExists)
            assertEquals(0L, provider.reportedSizeOverride)
        } finally {
            source.delete()
        }
    }

    @Test
    fun delayedSizeMetadata_withEmptyBackingFile_failsAndPreservesSource() {
        val provider = mediaProvider(displayName = "empty-target.mp4", rowExists = false).apply {
            reportedSizeOverride = 0L
            truncateOnPublish = true
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "empty-target-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "empty-target.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertFalse(result.ok)
            assertTrue(result.reason.orEmpty().contains("length failed verification"))
            assertTrue(result.detail.orEmpty().contains("copiedBytes=12"))
            assertTrue(result.detail.orEmpty().contains("mediaStoreReportedSize=0"))
            assertTrue(source.isFile)
            assertFalse(provider.rowExists)
        } finally {
            source.delete()
        }
    }

    @Test
    fun validator_sizeMetadataZeroWithRealMedia_usesDescriptorLength() {
        val provider = mediaProvider(displayName = "validator.mp4", rowExists = true).apply {
            reportedSizeOverride = 0L
        }
        register(MediaStore.AUTHORITY, provider)
        context.contentResolver.openOutputStream(provider.itemUri, "w")!!.use { output ->
            output.write(mediaBytes())
        }

        assertNull(DownloadedMediaValidator.validate(context, provider.itemUri))
    }

    @Test
    fun validator_sizeMetadataZeroWithEmptyContent_reportsEmptyFile() {
        val provider = mediaProvider(displayName = "validator-empty.mp4", rowExists = true).apply {
            reportedSizeOverride = 0L
        }
        register(MediaStore.AUTHORITY, provider)

        assertEquals(
            "Downloaded file is empty",
            DownloadedMediaValidator.validate(context, provider.itemUri)
        )
    }

    @Test
    fun providerAutoRename_reportsExpectedAndActualDisplayName() {
        val provider = mediaProvider(displayName = "rename.mp4", rowExists = false).apply {
            autoRenameOnInsert = true
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "rename-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "rename.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertFalse(result.ok)
            assertTrue(result.reason.orEmpty().contains("expect name=rename.mp4"))
            assertTrue(result.reason.orEmpty().contains("actual name=rename (1).mp4"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun pendingCleanupFailure_isIncludedInDiagnosticDetail() {
        val provider = mediaProvider(displayName = "cleanup.mp4", rowExists = false).apply {
            reportedSizeOverride = 0L
            truncateOnPublish = true
            deleteResultOverride = 0
        }
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "cleanup-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, "cleanup.mp4")

        try {
            val result = FileUtil().moveMediaWithReason(
                context, Uri.fromFile(source), Uri.fromFile(target)
            )

            assertFalse(result.ok)
            assertTrue(result.detail.orEmpty().contains("[清理]"))
            assertTrue(result.detail.orEmpty().contains("rows=0"))
            assertTrue(provider.rowExists)
        } finally {
            source.delete()
        }
    }

    @Test
    fun chineseDisplayName_publishesWithoutDiagnosticFailure() {
        val displayName = "中文视频-测试.mp4"
        val provider = mediaProvider(displayName = displayName, rowExists = false)
        register(MediaStore.AUTHORITY, provider)
        val source = File(context.filesDir, "chinese-source.mp4").apply { writeMedia(this) }
        val target = File(FileUtil().publicDownloadsDir, displayName)

        val result = FileUtil().moveMediaWithReason(
            context, Uri.fromFile(source), Uri.fromFile(target)
        )

        assertTrue("reason=${result.reason} detail=${result.detail}", result.ok)
        assertEquals(displayName, provider.displayName)
        assertFalse(source.exists())
        assertTrue(provider.rowExists)
    }


    private fun mediaProvider(displayName: String, rowExists: Boolean): RecordingMediaProvider {
        return RecordingMediaProvider(
            backingFile = temporaryFolder.newFile("media-provider-${System.nanoTime()}.bin"),
            displayName = displayName,
            rowExists = rowExists
        )
    }

    private fun register(authority: String, provider: ContentProvider) {
        provider.attachInfo(
            context,
            ProviderInfo().apply { this.authority = authority }
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private fun writeMedia(file: File) {
        file.writeBytes(mediaBytes())
    }

    private fun mediaBytes() = byteArrayOf(
        0x00, 0x00, 0x00, 0x18,
        'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
        'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
    )

    private class RecordingMediaProvider(
        private val backingFile: File,
        var displayName: String,
        var rowExists: Boolean
    ) : ContentProvider() {
        val itemUri: Uri = ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MEDIA_ID
        )
        var relativePath: String = FileUtil.PUBLIC_RELATIVE_PATH
        var pending: Int = 0
        var updateResult: Int = 1
        var applyDisplayNameOnUpdate: Boolean = true
        var insertResultNull: Boolean = false
        var normalizeRelativePath: Boolean = false
        var outputUnavailable: Boolean = false
        var reportedSizeOverride: Long? = null
        var autoRenameOnInsert: Boolean = false
        var truncateOnPublish: Boolean = false
        var deleteResultOverride: Int? = null
        var throwSecurityOnQuery: Boolean = false
        var lastSelection: String? = null
        var lastSelectionArgs: Array<out String>? = null
        var updateCalls: Int = 0
        var deleteCalls: Int = 0

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            if (throwSecurityOnQuery) throw SecurityException("query denied")
            lastSelection = selection
            lastSelectionArgs = selectionArgs
            val columns = projection?.copyOf() ?: DEFAULT_MEDIA_COLUMNS
            val cursor = MatrixCursor(columns)
            if (!rowExists) return cursor
            if (
                selection?.contains(MediaStore.MediaColumns.DISPLAY_NAME) == true &&
                selectionArgs?.firstOrNull() != displayName
            ) {
                return cursor
            }
            if (
                selection?.contains("${MediaStore.MediaColumns.IS_PENDING} = 0") == true &&
                pending != 0
            ) {
                return cursor
            }
            cursor.addRow(columns.map(::valueFor).toTypedArray())
            return cursor
        }

        override fun getType(uri: Uri): String = "video/mp4"

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (insertResultNull) {
                displayName = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
                return null
            }
            displayName = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
            if (autoRenameOnInsert) {
                displayName =
                    "${displayName.substringBeforeLast('.', displayName)} (1)." +
                        displayName.substringAfterLast('.', "mp4")
            }
            relativePath = if (normalizeRelativePath) {
                values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
                    ?.removeSuffix("/").orEmpty()
            } else {
                values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty()
            }
            pending = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) ?: 0
            rowExists = true
            backingFile.delete()
            return itemUri
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            deleteCalls += 1
            deleteResultOverride?.let { return it }
            if (!rowExists) return 0
            rowExists = false
            backingFile.delete()
            return 1
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            updateCalls += 1
            if (updateResult != 1) return updateResult
            values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)?.let { newName ->
                if (applyDisplayNameOnUpdate) displayName = newName
            }
            values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)?.let { pending = it }
            if (truncateOnPublish && pending == 0) {
                backingFile.writeBytes(byteArrayOf())
            }
            return updateResult
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (!rowExists) throw FileNotFoundException("media row is absent")
            if (outputUnavailable && mode.contains('w')) return null
            val flags = if (mode.contains('w')) {
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            return ParcelFileDescriptor.open(backingFile, flags)
        }

        private fun valueFor(column: String): Any? = when (column) {
            MediaStore.MediaColumns._ID -> MEDIA_ID
            MediaStore.MediaColumns.DISPLAY_NAME -> displayName
            MediaStore.MediaColumns.RELATIVE_PATH -> relativePath
            MediaStore.MediaColumns.IS_PENDING -> pending
            MediaStore.MediaColumns.SIZE -> reportedSizeOverride ?: backingFile.length()
            else -> null
        }
    }

    private class SourceProvider(
        private val sourceFile: File,
        private val deleteResult: Int
    ) : ContentProvider() {
        var rowExists: Boolean = true
        var deleteCalls: Int = 0

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val columns = projection?.copyOf() ?: arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
            )
            val cursor = MatrixCursor(columns)
            if (rowExists) {
                cursor.addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> sourceFile.name
                        OpenableColumns.SIZE -> sourceFile.length()
                        else -> null
                    }
                }.toTypedArray())
            }
            return cursor
        }

        override fun getType(uri: Uri): String = "video/mp4"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            deleteCalls += 1
            if (deleteResult == 1) {
                rowExists = false
                sourceFile.delete()
            }
            return deleteResult
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (!rowExists) throw FileNotFoundException("source row is absent")
            return ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private companion object {
        const val MEDIA_ID = 7L
        const val SOURCE_AUTHORITY = "surfsave.test.source"
        val DEFAULT_MEDIA_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE
        )
    }
}
