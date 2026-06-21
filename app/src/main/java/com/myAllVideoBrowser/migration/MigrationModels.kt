package com.myAllVideoBrowser.migration

import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.SharedPrefHelper

enum class MigrationStage {
    NOT_STARTED,
    EXPORT_READY,
    IMPORTED
}

data class PreferenceEntry(
    val key: String,
    val valueType: String,
    val stringValue: String? = null,
    val intValue: Int? = null,
    val longValue: Long? = null,
    val floatValue: Float? = null,
    val booleanValue: Boolean? = null,
    val stringSetValue: Set<String>? = null
)

data class BrowserSessionSnapshot(
    val tabs: List<SharedPrefHelper.BrowserSessionTab> = emptyList(),
    val currentIndex: Int = 0
)

data class MigrationManifest(
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val exportedByPackage: String,
    val exportedByRole: String,
    val appVersionName: String,
    val bookmarkCount: Int,
    val historyCount: Int,
    val videoCount: Int,
    val progressCount: Int,
    val browserSessionCount: Int,
    val thumbnailCount: Int,
    val cookieProfileCount: Int = 0,
    val cookieContentIncluded: Boolean = false
)

data class MigrationArchive(
    val manifest: MigrationManifest,
    val settingsPrefs: List<PreferenceEntry> = emptyList(),
    val playbackPrefs: List<PreferenceEntry> = emptyList(),
    val bookmarks: List<PageInfo> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val videos: List<VideoInfo> = emptyList(),
    val progress: List<ProgressInfo> = emptyList(),
    val browserSession: BrowserSessionSnapshot = BrowserSessionSnapshot(),
    val cookieProfiles: List<CookieProfileStore.CookieProfileBackup> = emptyList()
)

data class PrivateVideoSummary(
    val count: Int = 0,
    val totalBytes: Long = 0
) {
    val hasPrivateVideos: Boolean
        get() = count > 0
}

data class PrivateVideoMoveResult(
    val movedCount: Int = 0,
    val failedCount: Int = 0,
    val movedBytes: Long = 0,
    val remainingPrivateVideos: PrivateVideoSummary = PrivateVideoSummary()
)

data class MigrationPackageInfo(
    val uriString: String,
    val displayPath: String,
    val sizeBytes: Long
)

data class MigrationReport(
    val stage: MigrationStage,
    val generatedAtEpochMs: Long,
    val packageInfo: MigrationPackageInfo? = null,
    val bookmarkCount: Int = 0,
    val historyCount: Int = 0,
    val videoCount: Int = 0,
    val progressCount: Int = 0,
    val browserSessionCount: Int = 0,
    val thumbnailCount: Int = 0,
    val cookieProfileCount: Int = 0,
    val cookieContentIncluded: Boolean = false,
    val privateVideoCount: Int = 0,
    val privateVideoBytes: Long = 0,
    val notes: List<String> = emptyList()
)

data class MigrationOverview(
    val migrationRole: String,
    val exportEnabled: Boolean,
    val importEnabled: Boolean,
    val stage: MigrationStage,
    val packageInfo: MigrationPackageInfo?,
    val companionPackage: String,
    val companionPackageInstalled: Boolean,
    val privateVideoSummary: PrivateVideoSummary,
    val lastReport: MigrationReport?
)
