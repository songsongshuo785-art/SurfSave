package com.myAllVideoBrowser.data.local.room

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownload
import com.myAllVideoBrowser.data.local.room.entity.BrowserFileDownloadStatus
import com.myAllVideoBrowser.di.module.MIGRATION_10_11
import com.myAllVideoBrowser.di.module.MIGRATION_11_12
import com.myAllVideoBrowser.di.module.MIGRATION_12_13
import com.myAllVideoBrowser.di.module.MIGRATION_13_14
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppDatabaseMigration10To11Test {
    private val databaseName = "migration-10-11.db"
    private lateinit var context: Application
    private var database: AppDatabase? = null

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
    }

    @After
    fun teardown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationCreatesPersistentFileDownloadTableWithoutSensitiveHeaderColumns() {
        createVersionTenDatabase()

        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()

        val db = requireNotNull(database)
        db.openHelper.writableDatabase
        val columns = tableColumns(db.openHelper.writableDatabase, "BrowserFileDownload")
        assertEquals(
            setOf(
                "id", "downloadManagerId", "url", "sourcePageUrl", "userAgent", "fileName", "mimeType",
                "expectedSize", "downloadedBytes", "totalBytes", "status", "createdAt",
                "completedAt", "localUri", "relativePath", "failureReason", "systemBindingTrusted"
            ),
            columns
        )
        assertFalse(columns.any { it.contains("cookie", ignoreCase = true) })
        assertFalse(columns.any { it.contains("authorization", ignoreCase = true) })
        assertTrue(
            tableColumns(db.openHelper.writableDatabase, "ProgressInfo")
                .containsAll(setOf("finalMediaUri", "mediaBindingTrusted"))
        )

        val rowId = db.browserFileDownloadDao().insert(
            BrowserFileDownload(
                downloadManagerId = 9001L,
                url = "https://download.example/app.apk",
                sourcePageUrl = "https://download.example",
                fileName = "app.apk",
                mimeType = "application/vnd.android.package-archive",
                relativePath = "SurfSave/Files/app.apk"
            )
        )
        val inserted = db.browserFileDownloadDao().getById(rowId)
        assertNotNull(inserted)
        assertEquals(BrowserFileDownloadStatus.PENDING, inserted?.status)
        assertEquals("", inserted?.userAgent)
        assertEquals("SurfSave/Files/app.apk", inserted?.relativePath)
        assertEquals(true, inserted?.systemBindingTrusted)

        val indexCursor = db.openHelper.writableDatabase.query(
            "PRAGMA index_list(`BrowserFileDownload`)"
        )
        indexCursor.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var uniqueIndexFound = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_BrowserFileDownload_downloadManagerId") {
                    uniqueIndexFound = true
                }
            }
            assertTrue(uniqueIndexFound)
        }
    }

    private fun createVersionTenDatabase() {
        val schema = readSchema(10)
        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val databaseJson = schema.getJSONObject("database")
                val entities = databaseJson.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val tableName = entity.getString("tableName")
                    db.execSQL(
                        entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
                    )
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (indexPosition in 0 until indices.length()) {
                        db.execSQL(
                            indices.getJSONObject(indexPosition)
                                .getString("createSql")
                                .replace("\${TABLE_NAME}", tableName)
                        )
                    }
                }
                val setupQueries = databaseJson.getJSONArray("setupQueries")
                for (index in 0 until setupQueries.length()) {
                    db.execSQL(setupQueries.getString(index))
                }
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Unexpected raw helper upgrade from $oldVersion to $newVersion")
            }
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase
        }
    }

    private fun tableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
        val columns = linkedSetOf<String>()
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        return columns
    }

    private fun readSchema(version: Int): JSONObject {
        val relativePath = "schemas/com.myAllVideoBrowser.data.local.room.AppDatabase/$version.json"
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val roots = generateSequence(File(workingDirectory)) { it.parentFile }.take(6)
        val schemaFile = roots
            .flatMap { root -> sequenceOf(File(root, relativePath), File(root, "app/$relativePath")) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate Room schema $relativePath from $workingDirectory")
        return schemaFile.inputStream().bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }
    }
}
