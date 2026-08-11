package com.myAllVideoBrowser.migration

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MigrationArchiveCodecTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gson = Gson()

    @Test
    fun v2RoundTrip_acceptsExplicitEmptyPayloadsAndDeclaresNoEncryption() {
        val file = temporaryFolder.newFile("v2.zip")
        val validated = MigrationArchiveCodec(gson).writeV2(file, emptyArchive(), emptyMap())

        assertEquals(MigrationArchiveCodec.SCHEMA_V2, validated.archive.manifest.schemaVersion)
        assertEquals(MigrationArchiveCodec.ENCRYPTION_NONE, validated.archive.manifest.encryption)
        assertEquals(8, validated.archive.manifest.payloads.size)
        assertTrue(validated.archive.bookmarks.isEmpty())
        assertTrue(validated.archive.browserSession.tabs.isEmpty())
    }

    @Test
    fun v1Reader_acceptsAppFormatButRejectsMissingRequiredManifestField() {
        val codec = MigrationArchiveCodec(gson)
        val valid = temporaryFolder.newFile("v1-valid.zip")
        writeZip(valid, v1Entries())
        assertEquals(MigrationArchiveCodec.SCHEMA_V1, codec.read(valid).archive.manifest.schemaVersion)

        val invalid = temporaryFolder.newFile("v1-missing-count.zip")
        val entries = v1Entries().toMutableMap()
        val manifest = JsonParser.parseString(entries.getValue(MigrationArchiveCodec.ENTRY_MANIFEST)).asJsonObject
        manifest.remove("historyCount")
        entries[MigrationArchiveCodec.ENTRY_MANIFEST] = gson.toJson(manifest)
        writeZip(invalid, entries)
        assertThrows(IllegalArgumentException::class.java) { codec.read(invalid) }
    }

    @Test
    fun reader_distinguishesMissingPayloadFromLegalEmptyArray() {
        val codec = MigrationArchiveCodec(gson)
        val valid = temporaryFolder.newFile("empty-arrays.zip")
        writeZip(valid, v1Entries())
        assertTrue(codec.read(valid).archive.history.isEmpty())

        val missing = temporaryFolder.newFile("missing-history.zip")
        writeZip(missing, v1Entries().filterKeys { it != MigrationArchiveCodec.ENTRY_HISTORY })
        assertThrows(IllegalArgumentException::class.java) { codec.read(missing) }
    }

    @Test
    fun reader_rejectsDuplicateAndTraversalEntries() {
        val codec = MigrationArchiveCodec(gson)
        val duplicate = temporaryFolder.newFile("duplicate.zip")
        val duplicatedEntries = v1Entries().map { (name, value) -> name to value.toByteArray() }.toMutableList()
        duplicatedEntries += MigrationArchiveCodec.ENTRY_SETTINGS_PREFS to "[]".toByteArray()
        writeRawStoredZip(duplicate, duplicatedEntries)
        assertThrows(IllegalArgumentException::class.java) { codec.read(duplicate) }

        val traversal = temporaryFolder.newFile("traversal.zip")
        val entries = v1Entries().toMutableMap()
        entries["../outside.json"] = "{}"
        writeZip(traversal, entries)
        assertThrows(IllegalArgumentException::class.java) { codec.read(traversal) }
    }

    @Test
    fun v2Reader_rejectsChecksumUnknownVersionAndUnknownEncryption() {
        val codec = MigrationArchiveCodec(gson)
        val original = temporaryFolder.newFile("original.zip")
        codec.writeV2(original, emptyArchive(), emptyMap())

        val checksum = temporaryFolder.newFile("bad-checksum.zip")
        val checksumEntries = readZip(original).toMutableMap()
        checksumEntries[MigrationArchiveCodec.ENTRY_HISTORY] = "[{}]".toByteArray()
        writeZipBytes(checksum, checksumEntries)
        assertThrows(IllegalArgumentException::class.java) { codec.read(checksum) }

        val version = temporaryFolder.newFile("bad-version.zip")
        writeWithManifestChange(original, version) { it.addProperty("schemaVersion", 99) }
        assertThrows(IllegalArgumentException::class.java) { codec.read(version) }

        val encryption = temporaryFolder.newFile("bad-encryption.zip")
        writeWithManifestChange(original, encryption) { it.addProperty("encryption", "password") }
        assertThrows(IllegalArgumentException::class.java) { codec.read(encryption) }
    }

    @Test
    fun reader_rejectsUnsafeCookieIdNonSuccessProgressAndNullSessionTab() {
        val codec = MigrationArchiveCodec(gson)
        val unsafeCookie = temporaryFolder.newFile("unsafe-cookie.zip")
        val unsafeEntries = v1Entries().toMutableMap()
        unsafeEntries[MigrationArchiveCodec.ENTRY_COOKIE_PROFILES] = gson.toJson(
            listOf(
                CookieProfileStore.CookieProfileBackup(
                    id = "../../escape",
                    name = "bad",
                    domains = listOf("example.com"),
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
        unsafeEntries[MigrationArchiveCodec.ENTRY_MANIFEST] = v1Manifest(cookieProfileCount = 1)
        writeZip(unsafeCookie, unsafeEntries)
        assertThrows(IllegalArgumentException::class.java) { codec.read(unsafeCookie) }

        val activeProgress = temporaryFolder.newFile("active-progress.zip")
        val activeEntries = v1Entries().toMutableMap()
        activeEntries[MigrationArchiveCodec.ENTRY_PROGRESS] = gson.toJson(
            listOf(
                ProgressInfo(
                    id = "progress-1",
                    videoInfo = VideoInfo(id = "video-1"),
                    downloadStatus = VideoTaskState.DOWNLOADING
                )
            )
        )
        activeEntries[MigrationArchiveCodec.ENTRY_MANIFEST] = v1Manifest(progressCount = 1)
        writeZip(activeProgress, activeEntries)
        assertThrows(IllegalArgumentException::class.java) { codec.read(activeProgress) }

        val nullTab = temporaryFolder.newFile("null-tab.zip")
        val nullEntries = v1Entries().toMutableMap()
        nullEntries[MigrationArchiveCodec.ENTRY_BROWSER_SESSION] =
            "{\"tabs\":[null],\"currentIndex\":0}"
        nullEntries[MigrationArchiveCodec.ENTRY_MANIFEST] = v1Manifest(browserSessionCount = 1)
        writeZip(nullTab, nullEntries)
        assertThrows(IllegalArgumentException::class.java) { codec.read(nullTab) }
    }

    @Test
    fun reader_rejectsMalformedUtf8Json() {
        val file = temporaryFolder.newFile("malformed-utf8.zip")
        val entries = v1Entries()
            .mapValues { (_, value) -> value.toByteArray(Charsets.UTF_8) }
            .toMutableMap()
        entries[MigrationArchiveCodec.ENTRY_HISTORY] = byteArrayOf(
            '['.code.toByte(),
            '"'.code.toByte(),
            0xc3.toByte(),
            0x28,
            '"'.code.toByte(),
            ']'.code.toByte()
        )
        writeZipBytes(file, entries)

        assertThrows(IllegalArgumentException::class.java) {
            MigrationArchiveCodec(gson).read(file)
        }
    }

    @Test
    fun browserSessionIndex_accountsForHomeSlotAndRejectsOutOfRange() {
        val codec = MigrationArchiveCodec(gson)
        val tab = SharedPrefHelper.BrowserSessionTab(
            id = "tab-1",
            url = "https://example.com/video",
            title = "Example",
            faviconBase64 = null,
            thumbnailPath = null
        )
        val valid = temporaryFolder.newFile("home-slot-valid.zip")
        codec.writeV2(
            valid,
            emptyArchive().copy(browserSession = BrowserSessionSnapshot(listOf(tab), currentIndex = 1)),
            emptyMap()
        )
        assertEquals(1, codec.read(valid).archive.browserSession.currentIndex)

        val invalid = temporaryFolder.newFile("home-slot-invalid.zip")
        assertThrows(IllegalArgumentException::class.java) {
            codec.writeV2(
                invalid,
                emptyArchive().copy(browserSession = BrowserSessionSnapshot(listOf(tab), currentIndex = 2)),
                emptyMap()
            )
        }
    }

    @Test
    fun zipWriter_removesOversizedStagingFile() {
        val file = temporaryFolder.newFile("too-large.zip")
        val io = MigrationZipIo(
            MigrationArchiveLimits(
                maxArchiveBytes = 8,
                maxEntryCount = 2,
                maxEntryBytes = 1024,
                maxTotalUncompressedBytes = 1024
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            io.write(file, mapOf("a" to ByteArray(128) { 1 }))
        }
        assertTrue(!file.exists())
    }

    private fun emptyArchive(): MigrationArchive {
        val manifest = MigrationManifest(
            schemaVersion = MigrationArchiveCodec.SCHEMA_V2,
            exportedAtEpochMs = 1,
            exportedByPackage = "com.surfsave.browser",
            exportedByRole = "new_identity",
            appVersionName = "test",
            bookmarkCount = 0,
            historyCount = 0,
            videoCount = 0,
            progressCount = 0,
            browserSessionCount = 0,
            thumbnailCount = 0,
            cookieProfileCount = 0,
            cookieContentIncluded = false,
            encryption = MigrationArchiveCodec.ENCRYPTION_NONE
        )
        return MigrationArchive(manifest = manifest)
    }

    private fun v1Entries(): LinkedHashMap<String, String> = linkedMapOf(
        MigrationArchiveCodec.ENTRY_MANIFEST to v1Manifest(),
        MigrationArchiveCodec.ENTRY_SETTINGS_PREFS to "[]",
        MigrationArchiveCodec.ENTRY_PLAYBACK_PREFS to "[]",
        MigrationArchiveCodec.ENTRY_BOOKMARKS to "[]",
        MigrationArchiveCodec.ENTRY_HISTORY to "[]",
        MigrationArchiveCodec.ENTRY_VIDEOS to "[]",
        MigrationArchiveCodec.ENTRY_PROGRESS to "[]",
        MigrationArchiveCodec.ENTRY_BROWSER_SESSION to "{\"tabs\":[],\"currentIndex\":0}",
        MigrationArchiveCodec.ENTRY_COOKIE_PROFILES to "[]"
    )

    private fun v1Manifest(
        progressCount: Int = 0,
        browserSessionCount: Int = 0,
        cookieProfileCount: Int = 0
    ): String = """
        {
          "schemaVersion": 1,
          "exportedAtEpochMs": 1,
          "exportedByPackage": "com.surfsave.browser",
          "exportedByRole": "new_identity",
          "appVersionName": "legacy",
          "bookmarkCount": 0,
          "historyCount": 0,
          "videoCount": 0,
          "progressCount": $progressCount,
          "browserSessionCount": $browserSessionCount,
          "thumbnailCount": 0,
          "cookieProfileCount": $cookieProfileCount,
          "cookieContentIncluded": false
        }
    """.trimIndent()

    private fun writeWithManifestChange(
        source: File,
        target: File,
        change: (com.google.gson.JsonObject) -> Unit
    ) {
        val entries = readZip(source).toMutableMap()
        val manifest = JsonParser.parseString(
            entries.getValue(MigrationArchiveCodec.ENTRY_MANIFEST).toString(Charsets.UTF_8)
        ).asJsonObject
        change(manifest)
        entries[MigrationArchiveCodec.ENTRY_MANIFEST] = gson.toJson(manifest).toByteArray()
        writeZipBytes(target, entries)
    }

    private fun readZip(file: File): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun writeZip(file: File, entries: Map<String, String>) =
        writeZipBytes(file, entries.mapValues { (_, value) -> value.toByteArray() })

    private fun writeZipBytes(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun writeRawStoredZip(file: File, entries: List<Pair<String, ByteArray>>) {
        data class Central(val name: ByteArray, val data: ByteArray, val crc: Long, val offset: Int)
        val central = mutableListOf<Central>()
        FileOutputStream(file).use { raw ->
            val output = DataOutputStream(raw)
            entries.forEach { (nameText, data) ->
                val name = nameText.toByteArray(Charsets.UTF_8)
                val crc = CRC32().apply { update(data) }.value
                val offset = raw.channel.position().toInt()
                output.writeLeInt(0x04034b50)
                output.writeLeShort(20); output.writeLeShort(0); output.writeLeShort(0)
                output.writeLeShort(0); output.writeLeShort(0)
                output.writeLeInt(crc.toInt()); output.writeLeInt(data.size); output.writeLeInt(data.size)
                output.writeLeShort(name.size); output.writeLeShort(0)
                output.write(name); output.write(data)
                central += Central(name, data, crc, offset)
            }
            val centralOffset = raw.channel.position().toInt()
            central.forEach { item ->
                output.writeLeInt(0x02014b50)
                output.writeLeShort(20); output.writeLeShort(20); output.writeLeShort(0)
                output.writeLeShort(0); output.writeLeShort(0); output.writeLeShort(0)
                output.writeLeInt(item.crc.toInt())
                output.writeLeInt(item.data.size); output.writeLeInt(item.data.size)
                output.writeLeShort(item.name.size); output.writeLeShort(0); output.writeLeShort(0)
                output.writeLeShort(0); output.writeLeShort(0); output.writeLeInt(0)
                output.writeLeInt(item.offset); output.write(item.name)
            }
            val centralSize = raw.channel.position().toInt() - centralOffset
            output.writeLeInt(0x06054b50)
            output.writeLeShort(0); output.writeLeShort(0)
            output.writeLeShort(central.size); output.writeLeShort(central.size)
            output.writeLeInt(centralSize); output.writeLeInt(centralOffset); output.writeLeShort(0)
        }
    }

    private fun DataOutputStream.writeLeShort(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
    }

    private fun DataOutputStream.writeLeInt(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
        writeByte((value ushr 16) and 0xff)
        writeByte((value ushr 24) and 0xff)
    }
}
