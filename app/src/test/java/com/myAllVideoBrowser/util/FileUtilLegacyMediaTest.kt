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
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FileUtilLegacyMediaTest {

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun mediaStoreList_keepsSameNameInManagedAndLegacyPaths() {
        val provider = ListingMediaProvider(
            mutableListOf(
                MediaRow(1L, "same.mp4", FileUtil.PUBLIC_RELATIVE_PATH),
                MediaRow(2L, "same.mp4", FileUtil.LEGACY_PUBLIC_RELATIVE_PATH),
                MediaRow(3L, "nested.mp4", "${FileUtil.LEGACY_PUBLIC_RELATIVE_PATH}Other/"),
                MediaRow(4L, "pending.mp4", FileUtil.PUBLIC_RELATIVE_PATH, pending = 1),
                MediaRow(5L, "notes.txt", FileUtil.PUBLIC_RELATIVE_PATH)
            )
        )
        register(provider)
        initializeFileUtil()

        val publicEntries = FileUtil().listFiles.filter {
            it.storageClass == FileUtil.MediaStorageClass.MANAGED_PUBLIC ||
                it.storageClass == FileUtil.MediaStorageClass.LEGACY_PUBLIC
        }

        assertEquals(2, publicEntries.size)
        assertEquals(listOf("same.mp4", "same.mp4"), publicEntries.map { it.displayName })
        assertEquals(
            setOf(
                FileUtil.MediaStorageClass.MANAGED_PUBLIC,
                FileUtil.MediaStorageClass.LEGACY_PUBLIC
            ),
            publicEntries.map { it.storageClass }.toSet()
        )
        assertNotEquals(publicEntries[0].id, publicEntries[1].id)
        assertEquals(
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            provider.listSelection
        )
    }

    @Test
    fun legacyPublicMedia_isSharedButNotManaged() {
        val provider = ListingMediaProvider(
            mutableListOf(MediaRow(11L, "legacy.mp4", FileUtil.LEGACY_PUBLIC_RELATIVE_PATH))
        )
        register(provider)
        initializeFileUtil()
        val uri = provider.itemUri(11L)

        assertTrue(FileUtil().isSharedPublicMedia(context, uri))
        assertFalse(FileUtil().isManagedPublicMedia(context, uri))
    }

    @Test
    fun renameLegacyMedia_detectsSiblingNameInLegacyPath() {
        val provider = ListingMediaProvider(
            mutableListOf(
                MediaRow(21L, "old.mp4", FileUtil.LEGACY_PUBLIC_RELATIVE_PATH),
                MediaRow(22L, "taken.mp4", FileUtil.LEGACY_PUBLIC_RELATIVE_PATH),
                MediaRow(23L, "taken.mp4", FileUtil.PUBLIC_RELATIVE_PATH)
            )
        )
        register(provider)
        initializeFileUtil()

        val result = FileUtil().renameMedia(context, provider.itemUri(21L), "taken.mp4")

        assertTrue(result === FileUtil.RenameMediaResult.AlreadyExists)
        assertEquals(0, provider.updateCalls)
        assertEquals(FileUtil.LEGACY_PUBLIC_RELATIVE_PATH, provider.lastExactPathLookup)
    }

    @Test
    fun legacyFileList_readsRootAndSurfSaveWithoutOtherSubdirectories() {
        initializeFileUtil()
        val fileUtil = FileUtil()
        val suffix = UUID.randomUUID().toString()
        val legacyFile = File(fileUtil.legacyPublicDownloadsDir, "legacy-$suffix.mp4")
        val managedFile = File(fileUtil.publicDownloadsDir, "managed-$suffix.mp4")
        val otherDirectory = File(fileUtil.legacyPublicDownloadsDir, "other-$suffix")
        val nestedFile = File(otherDirectory, "nested-$suffix.mp4")

        try {
            legacyFile.parentFile?.mkdirs()
            managedFile.parentFile?.mkdirs()
            otherDirectory.mkdirs()
            legacyFile.writeBytes(byteArrayOf(1))
            managedFile.writeBytes(byteArrayOf(2))
            nestedFile.writeBytes(byteArrayOf(3))

            val matchingEntries = fileUtil.listLegacyPublicFiles()
                .filter { it.displayName.contains(suffix) }

            assertEquals(
                setOf(legacyFile.name, managedFile.name),
                matchingEntries.map { it.displayName }.toSet()
            )
        } finally {
            legacyFile.delete()
            managedFile.delete()
            nestedFile.delete()
            otherDirectory.delete()
        }
    }

    private fun initializeFileUtil() {
        ContextUtils.initApplicationContext(context)
        FileUtil.INITIIALIZED = true
        FileUtil.IS_EXTERNAL_STORAGE_USE = true
        FileUtil.IS_APP_DATA_DIR_USE = false
    }

    private fun register(provider: ContentProvider) {
        provider.attachInfo(
            context,
            ProviderInfo().apply { authority = MediaStore.AUTHORITY }
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    private data class MediaRow(
        val id: Long,
        var displayName: String,
        val relativePath: String,
        val pending: Int = 0
    )

    private class ListingMediaProvider(
        private val rows: MutableList<MediaRow>
    ) : ContentProvider() {
        var listSelection: String? = null
        var lastExactPathLookup: String? = null
        var updateCalls: Int = 0

        override fun onCreate(): Boolean = true

        fun itemUri(id: Long): Uri = ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            id
        )

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val columns = projection?.copyOf() ?: MEDIA_COLUMNS
            val cursor = MatrixCursor(columns)
            if (selection?.startsWith("${MediaStore.MediaColumns.RELATIVE_PATH} IN") == true) {
                listSelection = selection
            }
            val itemId = uri.lastPathSegment?.toLongOrNull()
            val filtered = rows.asSequence()
                .filter { itemId == null || it.id == itemId }
                .filter { row -> matchesSelection(row, selection, selectionArgs) }
                .toList()
            filtered.forEach { row ->
                cursor.addRow(columns.map { column -> valueFor(row, column) }.toTypedArray())
            }
            return cursor
        }

        private fun matchesSelection(
            row: MediaRow,
            selection: String?,
            args: Array<out String>?
        ): Boolean {
            if (selection == null) return true
            val values = args.orEmpty()
            var index = 0
            if (selection.contains("${MediaStore.MediaColumns.DISPLAY_NAME} = ?")) {
                if (values.getOrNull(index++) != row.displayName) return false
            }
            if (selection.contains("${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)")) {
                val paths = setOf(values.getOrNull(index++), values.getOrNull(index++))
                if (row.relativePath !in paths) return false
            } else if (selection.contains("${MediaStore.MediaColumns.RELATIVE_PATH} = ?")) {
                val expectedPath = values.getOrNull(index++)
                lastExactPathLookup = expectedPath
                if (row.relativePath != expectedPath) return false
            }
            if (selection.contains("${MediaStore.MediaColumns.IS_PENDING} = 0") && row.pending != 0) {
                return false
            }
            if (selection.contains("${MediaStore.MediaColumns._ID} != ?")) {
                if (values.getOrNull(index)?.toLongOrNull() == row.id) return false
            }
            return true
        }

        override fun getType(uri: Uri): String = "video/mp4"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            updateCalls += 1
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            val row = rows.firstOrNull { it.id == id } ?: return 0
            values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)?.let {
                row.displayName = it
            }
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            throw FileNotFoundException("No backing file")
        }

        private fun valueFor(row: MediaRow, column: String): Any? = when (column) {
            MediaStore.MediaColumns._ID -> row.id
            MediaStore.MediaColumns.DISPLAY_NAME -> row.displayName
            MediaStore.MediaColumns.RELATIVE_PATH -> row.relativePath
            MediaStore.MediaColumns.IS_PENDING -> row.pending
            MediaStore.MediaColumns.SIZE -> 1L
            else -> null
        }
    }

    private companion object {
        val MEDIA_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE
        )
    }
}
