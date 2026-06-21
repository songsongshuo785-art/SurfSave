package com.myAllVideoBrowser.migration

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MigrationStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "migration_state_prefs"
        private const val KEY_STAGE = "stage"
        private const val KEY_LAST_REPORT = "last_report"
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getStage(): MigrationStage {
        val stored = prefs.getString(KEY_STAGE, MigrationStage.NOT_STARTED.name)
        return MigrationStage.entries.firstOrNull { it.name == stored } ?: MigrationStage.NOT_STARTED
    }

    fun getLastReport(): MigrationReport? {
        val raw = prefs.getString(KEY_LAST_REPORT, null) ?: return null
        return runCatching {
            gson.fromJson(raw, MigrationReport::class.java)
        }.getOrNull()
    }

    fun markExportReady(report: MigrationReport) {
        save(MigrationStage.EXPORT_READY, report)
    }

    fun markImported(report: MigrationReport) {
        save(MigrationStage.IMPORTED, report)
    }

    fun clearLastReportPackageInfo() {
        val lastReport = getLastReport() ?: return
        save(getStage(), lastReport.copy(packageInfo = null))
    }

    fun reset(report: MigrationReport? = null) {
        prefs.edit {
            putString(KEY_STAGE, MigrationStage.NOT_STARTED.name)
            if (report == null) {
                remove(KEY_LAST_REPORT)
            } else {
                putString(KEY_LAST_REPORT, gson.toJson(report))
            }
        }
    }

    private fun save(stage: MigrationStage, report: MigrationReport) {
        prefs.edit {
            putString(KEY_STAGE, stage.name)
            putString(KEY_LAST_REPORT, gson.toJson(report))
        }
    }
}
