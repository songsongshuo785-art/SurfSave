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
        file.writeBytes(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
            )
        )
    }

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

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            displayName = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
            relativePath = values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty()
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
            return updateResult
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (!rowExists) throw FileNotFoundException("media row is absent")
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
            MediaStore.MediaColumns.SIZE -> backingFile.length()
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
