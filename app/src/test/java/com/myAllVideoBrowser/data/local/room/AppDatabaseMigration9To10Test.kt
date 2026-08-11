package com.myAllVideoBrowser.data.local.room

import android.app.Application
import android.content.ContentValues
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.myAllVideoBrowser.di.module.MIGRATION_9_10
import com.myAllVideoBrowser.util.downloaders.DownloadFingerprint
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.youtubedl_downloader.YoutubeDlStopReason
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
class AppDatabaseMigration9To10Test {

    private val databaseName = "migration-9-10.db"
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
    fun migrationAddsDefaultsPreservesDataAndMatchesExportedSchema() {
        createVersionNineDatabase()

        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()

        val migrated = requireNotNull(database?.progressDao()?.getProgressInfoById(LEGACY_ID))
        assertEquals(77L, migrated.downloadId)
        assertEquals(321L, migrated.progressDownloaded)
        assertEquals(654L, migrated.progressTotal)
        assertEquals(VideoTaskState.DOWNLOADING, migrated.downloadStatus)
        assertEquals(YoutubeDlStopReason.NONE, migrated.stopReason)
        assertEquals("", migrated.executionToken)
        assertEquals(false, migrated.removePartialOnCancel)
        assertEquals("", migrated.finalizationSource)
        assertEquals("", migrated.finalizationTarget)
        assertEquals("https://cdn.example/legacy.mp4", migrated.videoInfo.downloadUrls.single().url)
        assertEquals(true, migrated.videoInfo.isRegularDownload)
        assertEquals(
            DownloadFingerprint.fromVideoInfo(migrated.videoInfo),
            migrated.downloadFingerprint
        )
        assertEquals(LEGACY_ID, database?.progressDao()?.findDuplicateByFingerprint(migrated.downloadFingerprint)?.id)

        val expectedColumns = progressColumns(readSchema(10))
        val actualColumns = readProgressColumns(requireNotNull(database).openHelper.writableDatabase)
        assertEquals(expectedColumns, actualColumns)
        assertEquals(ColumnSpec("INTEGER", true, "0"), actualColumns.getValue("stopReason"))
        assertEquals(ColumnSpec("TEXT", true, "''"), actualColumns.getValue("executionToken"))
        assertEquals(ColumnSpec("INTEGER", true, "0"), actualColumns.getValue("removePartialOnCancel"))
        assertEquals(ColumnSpec("TEXT", true, "''"), actualColumns.getValue("finalizationSource"))
        assertEquals(ColumnSpec("TEXT", true, "''"), actualColumns.getValue("finalizationTarget"))

        val dao = requireNotNull(database).progressDao()
        assertEquals(1, dao.adoptLegacyYtDlpExecution(LEGACY_ID, "recovery-token"))
        val adopted = requireNotNull(dao.getProgressInfoById(LEGACY_ID))
        assertEquals(VideoTaskState.PAUSING, adopted.downloadStatus)
        assertEquals(YoutubeDlStopReason.PAUSE, adopted.stopReason)
        assertEquals("recovery-token", adopted.executionToken)
    }

    @Test
    fun migrationRejectsUnparseableLegacyVideoInfoInsteadOfLeavingBlankFingerprint() {
        createVersionNineDatabase(videoJson = "{not-valid-json")

        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()

        val failure = runCatching {
            requireNotNull(database).openHelper.writableDatabase
        }.exceptionOrNull()

        assertNotNull(failure)
        val messages = generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue(messages.contains("Cannot backfill download fingerprint for ProgressInfo '$LEGACY_ID'"))
    }

    private fun createVersionNineDatabase(videoJson: String = legacyVideoJson()) {
        val schema = readSchema(9)
        val callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val databaseJson = schema.getJSONObject("database")
                val entities = databaseJson.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val tableName = entity.getString("tableName")
                    val createSql = entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
                    db.execSQL(createSql)
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
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            insertLegacyProgress(helper.writableDatabase, videoJson)
        } finally {
            helper.close()
        }
    }

    private fun insertLegacyProgress(db: SupportSQLiteDatabase, videoJson: String) {
        val values = ContentValues().apply {
            put("id", LEGACY_ID)
            put("downloadId", 77L)
            put("videoInfo", videoJson)
            put("bytesDownloaded", 0)
            put("bytesTotal", 0)
            put("progressDownloaded", 321L)
            put("progressTotal", 654L)
            put("downloadStatus", VideoTaskState.DOWNLOADING)
            put("isLive", 0)
            put("isM3u8", 0)
            put("fragmentsDownloaded", 1)
            put("fragmentsTotal", 2)
            put("infoLine", "Downloading")
            put("queuePosition", 8L)
            put("queuedAt", 11L)
            put("startedAt", 22L)
            put("completedAt", 0L)
            put("downloadFingerprint", "")
            put("lastError", "")
            put("logPath", "/logs/legacy")
            put("queuedForLater", 0)
            put("progress", 49)
            put("progressSize", "321/654")
            put("downloadStatusFormatted", "downloading")
        }
        assertTrue(db.insert("ProgressInfo", 0, values) > 0L)
    }

    private fun legacyVideoJson(): String {
        return """
            {
              "id":"$LEGACY_ID",
              "urls":[{"url":"https://cdn.example/legacy.mp4","method":"GET","headers":{}}],
              "title":"Legacy",
              "ext":"mp4",
              "thumbnail":"",
              "duration":0,
              "originalUrl":"https://example/legacy",
              "formats":{"formats":[]},
              "isRegular":true,
              "isLive":false,
              "isDetectedBySuperX":false
            }
        """.trimIndent()
    }

    private fun progressColumns(schema: JSONObject): Map<String, ColumnSpec> {
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        val progressEntity = (0 until entities.length()).asSequence()
            .map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == "ProgressInfo" }
        val fields = progressEntity.getJSONArray("fields")
        return (0 until fields.length()).associate { index ->
            val field = fields.getJSONObject(index)
            field.getString("columnName") to ColumnSpec(
                type = field.getString("affinity"),
                notNull = field.optBoolean("notNull", false),
                defaultValue = field.optString("defaultValue").takeIf { field.has("defaultValue") }
            )
        }
    }

    private fun readProgressColumns(db: SupportSQLiteDatabase): Map<String, ColumnSpec> {
        val columns = linkedMapOf<String, ColumnSpec>()
        db.query("PRAGMA table_info(`ProgressInfo`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIndex)] = ColumnSpec(
                    type = cursor.getString(typeIndex),
                    notNull = cursor.getInt(notNullIndex) == 1,
                    defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
                )
            }
        }
        return columns
    }

    private fun readSchema(version: Int): JSONObject {
        val relativePath = "schemas/com.myAllVideoBrowser.data.local.room.AppDatabase/$version.json"
        val roots = mutableListOf<File>()
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var current: File? = File(workingDirectory)
        repeat(5) {
            current?.let(roots::add)
            current = current?.parentFile
        }
        val schemaFile = roots.asSequence()
            .flatMap { root -> sequenceOf(File(root, relativePath), File(root, "app/$relativePath")) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate Room schema $relativePath from $workingDirectory")
        val json = schemaFile.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(json)
    }

    private data class ColumnSpec(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?
    )

    private companion object {
        const val LEGACY_ID = "legacy-progress"
    }
}
