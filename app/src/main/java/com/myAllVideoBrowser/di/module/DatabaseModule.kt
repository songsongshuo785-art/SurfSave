package com.myAllVideoBrowser.di.module

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.myAllVideoBrowser.DLApplication
import com.myAllVideoBrowser.data.local.room.AppDatabase
import com.myAllVideoBrowser.data.local.room.dao.ConfigDao
import com.myAllVideoBrowser.data.local.room.dao.HistoryDao
import com.myAllVideoBrowser.data.local.room.dao.PageDao
import com.myAllVideoBrowser.data.local.room.dao.ProgressDao
import com.myAllVideoBrowser.data.local.room.dao.VideoDao
import com.myAllVideoBrowser.data.local.room.dao.BrowserFileDownloadDao
import com.myAllVideoBrowser.util.RoomConverter
import com.myAllVideoBrowser.util.downloaders.DownloadFingerprint
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

class UserSqlUtils {
    var createTable = "CREATE TABLE IF NOT EXISTS AdHost (host TEXT NOT NULL, PRIMARY KEY(host))"
}

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(UserSqlUtils().createTable)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ProgressInfo ADD progressDownloaded INTEGER DEFAULT 0 NOT NULL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ProgressInfo ADD progressTotal INTEGER DEFAULT 0 NOT NULL")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE PageInfo ADD COLUMN `order` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS AdHost")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE VideoInfo ADD COLUMN isLive INTEGER NOT NULL DEFAULT 0")
    }
}
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE VideoInfo ADD COLUMN isDetectedBySuperX INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN queuePosition INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN queuedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN downloadFingerprint TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN lastError TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN logPath TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN queuedForLater INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN stopReason INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN executionToken TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN removePartialOnCancel INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN finalizationSource TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN finalizationTarget TEXT NOT NULL DEFAULT ''")
        backfillMissingDownloadFingerprints(db)
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `BrowserFileDownload` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `downloadManagerId` INTEGER NOT NULL,
                `url` TEXT NOT NULL,
                `sourcePageUrl` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `expectedSize` INTEGER NOT NULL,
                `downloadedBytes` INTEGER NOT NULL,
                `totalBytes` INTEGER NOT NULL,
                `status` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `completedAt` INTEGER NOT NULL,
                `localUri` TEXT NOT NULL,
                `relativePath` TEXT NOT NULL,
                `failureReason` INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_BrowserFileDownload_downloadManagerId` " +
                "ON `BrowserFileDownload` (`downloadManagerId`)"
        )
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE BrowserFileDownload ADD COLUMN userAgent TEXT NOT NULL DEFAULT ''"
        )
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ProgressInfo ADD COLUMN finalMediaUri TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ProgressInfo ADD COLUMN mediaBindingTrusted INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL(
            "ALTER TABLE BrowserFileDownload ADD COLUMN systemBindingTrusted INTEGER NOT NULL DEFAULT 1"
        )
    }
}

private fun backfillMissingDownloadFingerprints(db: SupportSQLiteDatabase) {
    val converter = RoomConverter()
    val fingerprints = mutableListOf<Pair<String, String>>()
    db.query(
        "SELECT id, videoInfo FROM ProgressInfo WHERE downloadFingerprint = ''"
    ).use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow("id")
        val videoInfoColumn = cursor.getColumnIndexOrThrow("videoInfo")
        while (cursor.moveToNext()) {
            val id = cursor.getString(idColumn)
            val videoJson = cursor.getString(videoInfoColumn)
            val videoInfo = try {
                converter.convertJsonToVideo(videoJson)
            } catch (error: RuntimeException) {
                throw IllegalStateException(
                    "Cannot backfill download fingerprint for ProgressInfo '$id'",
                    error
                )
            }
            val fingerprint = DownloadFingerprint.fromVideoInfo(videoInfo)
            if (fingerprint.isBlank()) {
                throw IllegalStateException(
                    "Generated blank download fingerprint for ProgressInfo '$id'"
                )
            }
            fingerprints += id to fingerprint
        }
    }

    val update = db.compileStatement(
        "UPDATE ProgressInfo SET downloadFingerprint = ? " +
            "WHERE id = ? AND downloadFingerprint = ''"
    )
    fingerprints.forEach { (id, fingerprint) ->
        update.clearBindings()
        update.bindString(1, fingerprint)
        update.bindString(2, id)
        val updatedRows = update.executeUpdateDelete()
        if (updatedRows != 1) {
            throw IllegalStateException(
                "Expected to backfill one ProgressInfo row for '$id', updated $updatedRows"
            )
        }
    }
}


@Module
class DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(application: DLApplication): AppDatabase {
        return Room.databaseBuilder(application, AppDatabase::class.java, "dl.db").addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14
        ).build()
    }

    @Singleton
    @Provides
    fun provideConfigDao(database: AppDatabase): ConfigDao = database.configDao()

    @Singleton
    @Provides
    fun provideCommentDao(database: AppDatabase): VideoDao = database.videoDao()

    @Singleton
    @Provides
    fun provideProgressDao(database: AppDatabase): ProgressDao = database.progressDao()

    @Singleton
    @Provides
    fun provideHistoryDao(database: AppDatabase): HistoryDao = database.historyDao()

    @Singleton
    @Provides
    fun providePageDao(database: AppDatabase): PageDao = database.pageDao()

    @Singleton
    @Provides
    fun provideBrowserFileDownloadDao(database: AppDatabase): BrowserFileDownloadDao =
        database.browserFileDownloadDao()
}
