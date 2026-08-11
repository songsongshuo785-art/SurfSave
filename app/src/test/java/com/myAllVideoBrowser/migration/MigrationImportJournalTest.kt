package com.myAllVideoBrowser.migration

import android.app.Application
import com.myAllVideoBrowser.util.CookieProfileStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationImportJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commitFailure_rollsBackCleansSnapshotAndClearsJournal() {
        val store = MigrationImportJournalStore(temporaryFolder.newFolder("journal-failure"))
        val coordinator = MigrationCommitCoordinator(store)
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            coordinator.execute(
                rollbackFileName = "rollback-00000000-0000-0000-0000-000000000000.zip",
                report = report(),
                commit = {
                    events += "commit"
                    error("injected commit failure")
                },
                rollback = { events += "rollback" },
                afterCommit = { events += "after" },
                cleanup = { events += "cleanup" }
            )
        }

        assertEquals(listOf("commit", "rollback", "cleanup"), events)
        assertNull(store.read())
    }

    @Test
    fun committedJournal_isRetriedAndCleanedAfterAfterCommitFailure() {
        val store = MigrationImportJournalStore(temporaryFolder.newFolder("journal-committed"))
        val coordinator = MigrationCommitCoordinator(store)
        var afterCommitAttempts = 0

        assertThrows(IllegalStateException::class.java) {
            coordinator.execute(
                rollbackFileName = "rollback-00000000-0000-0000-0000-000000000001.zip",
                report = report(),
                commit = {},
                rollback = { error("committed import must not roll back") },
                afterCommit = {
                    afterCommitAttempts += 1
                    error("injected report persistence failure")
                },
                cleanup = {}
            )
        }
        assertEquals(MigrationImportState.COMMITTED, store.read()?.state)

        val recovered = coordinator.recover(
            rollback = { error("committed import must not roll back") },
            afterCommit = { afterCommitAttempts += 1 },
            cleanup = {}
        )

        assertEquals(MigrationImportState.COMMITTED, recovered)
        assertEquals(2, afterCommitAttempts)
        assertNull(store.read())
    }

    @Test
    fun committingJournal_rollsBackDuringRecovery() {
        val root = temporaryFolder.newFolder("journal-recovery")
        val store = MigrationImportJournalStore(root)
        store.prepare(
            "rollback-00000000-0000-0000-0000-000000000002.zip",
            report()
        )
        store.markCommitting()
        assertPublishedState(root, MigrationImportState.COMMITTING)
        val events = mutableListOf<String>()

        val recovered = MigrationCommitCoordinator(store).recover(
            rollback = { events += "rollback" },
            afterCommit = { events += "after" },
            cleanup = { events += "cleanup" }
        )

        assertEquals(MigrationImportState.COMMITTING, recovered)
        assertEquals(listOf("rollback", "cleanup"), events)
        assertNull(store.read())
    }

    @Test
    fun interruptedReplacement_restoresBackupAndDropsUncommittedNewFile() {
        val root = temporaryFolder.newFolder("journal-interrupted-write")
        val store = MigrationImportJournalStore(root)
        store.prepare(
            "rollback-00000000-0000-0000-0000-000000000003.zip",
            report()
        )
        val base = java.io.File(root, "import-journal.json")
        val backup = java.io.File(root, "import-journal.json.bak")
        val pending = java.io.File(root, "import-journal.json.new")
        assertTrue(base.renameTo(backup))
        pending.writeText(
            baseJournalJson(MigrationImportState.COMMITTING),
            Charsets.UTF_8
        )

        assertEquals(MigrationImportState.PREPARED, store.read()?.state)
        assertPublishedState(root, MigrationImportState.PREPARED)
    }

    @Test
    fun rollbackStore_roundTripsDataAndThumbnailIntegrity() {
        val root = temporaryFolder.newFolder("rollback-store")
        val store = MigrationRollbackStore(root)
        val data = MigrationRollbackData(
            bookmarks = emptyList(),
            history = emptyList(),
            videos = emptyList(),
            progress = emptyList(),
            settingsPrefs = listOf(
                PreferenceEntry("theme", "string", stringValue = "dark")
            ),
            playbackPrefs = emptyList(),
            cookieProfiles = CookieProfileStore.StoreSnapshot(emptyList(), emptyMap())
        )
        val thumbnail = byteArrayOf(1, 2, 3, 4)

        val file = store.create(data, mapOf("tab-1.jpg" to thumbnail))
        val loaded = store.load(file)

        assertEquals("dark", loaded.data.settingsPrefs.single().stringValue)
        assertArrayEquals(thumbnail, loaded.thumbnailFiles.getValue("tab-1.jpg"))
        store.delete(file)
        assertTrue(!file.exists())
    }

    private fun report(): MigrationReport = MigrationReport(
        stage = MigrationStage.IMPORTED,
        generatedAtEpochMs = 1
    )

    private fun assertPublishedState(root: java.io.File, state: MigrationImportState) {
        val base = java.io.File(root, "import-journal.json")
        assertTrue(base.exists())
        assertTrue(base.readText(Charsets.UTF_8).contains("\"state\":\"${state.name}\""))
        assertTrue(!java.io.File(root, "import-journal.json.new").exists())
        assertTrue(!java.io.File(root, "import-journal.json.bak").exists())
    }

    private fun baseJournalJson(state: MigrationImportState): String =
        """{"state":"${state.name}","rollbackFileName":"rollback-00000000-0000-0000-0000-000000000003.zip","report":{"stage":"IMPORTED","generatedAtEpochMs":1}}"""
}
