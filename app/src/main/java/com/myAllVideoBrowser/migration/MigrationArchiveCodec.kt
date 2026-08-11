package com.myAllVideoBrowser.migration

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.util.CookieProfileStore
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class MigrationArchiveLimits(
    val maxArchiveBytes: Long = 128L * 1024L * 1024L,
    val maxEntryCount: Int = 256,
    val maxEntryBytes: Long = 32L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 128L * 1024L * 1024L
)

internal class MigrationArchiveCodec(
    private val gson: Gson = Gson(),
    limits: MigrationArchiveLimits = MigrationArchiveLimits()
) {
    companion object {
        const val SCHEMA_V1 = 1
        const val SCHEMA_V2 = 2
        const val ENCRYPTION_NONE = "none"
        const val SENSITIVE_COOKIE_CONTENT = "cookie_profile_content"

        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_SETTINGS_PREFS = "prefs/settings_prefs.json"
        const val ENTRY_PLAYBACK_PREFS = "prefs/playback_state_prefs.json"
        const val ENTRY_BOOKMARKS = "db/bookmarks.json"
        const val ENTRY_HISTORY = "db/history.json"
        const val ENTRY_VIDEOS = "db/videos.json"
        const val ENTRY_PROGRESS = "db/progress.json"
        const val ENTRY_BROWSER_SESSION = "session/browser_session.json"
        const val ENTRY_COOKIE_PROFILES = "prefs/cookie_profiles.json"
        const val ENTRY_THUMBNAILS_PREFIX = "session/thumbnails/"

        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private val SHA_256 = Regex("[a-f0-9]{64}")
        private val REQUIRED_PAYLOAD_ENTRIES = linkedSetOf(
            ENTRY_SETTINGS_PREFS,
            ENTRY_PLAYBACK_PREFS,
            ENTRY_BOOKMARKS,
            ENTRY_HISTORY,
            ENTRY_VIDEOS,
            ENTRY_PROGRESS,
            ENTRY_BROWSER_SESSION,
            ENTRY_COOKIE_PROFILES
        )

        fun thumbnailEntryName(tabId: String): String {
            require(SAFE_ID.matches(tabId)) { "Migration package has an unsafe tab id." }
            return "$ENTRY_THUMBNAILS_PREFIX$tabId.jpg"
        }
    }

    private val zipIo = MigrationZipIo(limits)

    val maxArchiveBytes: Long = limits.maxArchiveBytes

    fun writeV2(
        destination: File,
        archive: MigrationArchive,
        thumbnailsByTabId: Map<String, ByteArray>
    ): ValidatedMigrationPackage {
        val payloads = linkedMapOf<String, ByteArray>()
        payloads[ENTRY_SETTINGS_PREFS] = jsonBytes(archive.settingsPrefs)
        payloads[ENTRY_PLAYBACK_PREFS] = jsonBytes(archive.playbackPrefs)
        payloads[ENTRY_BOOKMARKS] = jsonBytes(archive.bookmarks)
        payloads[ENTRY_HISTORY] = jsonBytes(archive.history)
        payloads[ENTRY_VIDEOS] = jsonBytes(archive.videos)
        payloads[ENTRY_PROGRESS] = jsonBytes(archive.progress)
        payloads[ENTRY_BROWSER_SESSION] = jsonBytes(archive.browserSession)
        payloads[ENTRY_COOKIE_PROFILES] = jsonBytes(archive.cookieProfiles)
        thumbnailsByTabId.toSortedMap().forEach { (tabId, bytes) ->
            require(bytes.isNotEmpty()) { "Migration thumbnail cannot be empty." }
            payloads[thumbnailEntryName(tabId)] = bytes
        }

        val hasCookieContent = archive.cookieProfiles.any { it.content != null }
        val descriptors = payloads.mapValues { (_, bytes) -> descriptor(bytes) }
        val manifest = archive.manifest.copy(
            schemaVersion = SCHEMA_V2,
            bookmarkCount = archive.bookmarks.size,
            historyCount = archive.history.size,
            videoCount = archive.videos.size,
            progressCount = archive.progress.size,
            browserSessionCount = archive.browserSession.tabs.size,
            thumbnailCount = thumbnailsByTabId.size,
            cookieProfileCount = archive.cookieProfiles.size,
            cookieContentIncluded = hasCookieContent,
            encryption = ENCRYPTION_NONE,
            payloads = descriptors,
            sensitiveCategories = if (hasCookieContent) {
                listOf(SENSITIVE_COOKIE_CONTENT)
            } else {
                emptyList()
            }
        )

        val entries = linkedMapOf(ENTRY_MANIFEST to jsonBytes(manifest))
        entries.putAll(payloads)
        zipIo.write(destination, entries)
        return read(destination)
    }

    fun read(source: File): ValidatedMigrationPackage {
        val entries = zipIo.read(source)
        val manifestBytes = entries[ENTRY_MANIFEST]
            ?: throw IllegalArgumentException("Migration package is invalid: missing $ENTRY_MANIFEST.")
        val manifestJson = parseJsonObject(manifestBytes, ENTRY_MANIFEST)
        val manifest = parseManifest(manifestJson)

        REQUIRED_PAYLOAD_ENTRIES.forEach { required ->
            require(entries.containsKey(required)) {
                "Migration package is invalid: missing $required."
            }
        }
        entries.keys.forEach { name ->
            require(
                name == ENTRY_MANIFEST ||
                    name in REQUIRED_PAYLOAD_ENTRIES ||
                    isThumbnailEntry(name)
            ) { "Migration package contains an unknown entry: $name." }
        }

        if (manifest.schemaVersion == SCHEMA_V2) {
            validateV2Descriptors(manifestJson, manifest, entries)
        }

        val settingsPrefs = parseList<PreferenceEntry>(
            entries.getValue(ENTRY_SETTINGS_PREFS),
            ENTRY_SETTINGS_PREFS
        )
        val playbackPrefs = parseList<PreferenceEntry>(
            entries.getValue(ENTRY_PLAYBACK_PREFS),
            ENTRY_PLAYBACK_PREFS
        )
        val bookmarks = parseList<PageInfo>(entries.getValue(ENTRY_BOOKMARKS), ENTRY_BOOKMARKS)
        val history = parseList<HistoryItem>(entries.getValue(ENTRY_HISTORY), ENTRY_HISTORY)
        val videos = parseList<VideoInfo>(entries.getValue(ENTRY_VIDEOS), ENTRY_VIDEOS)
        val progress = ProgressInfoMigrationNormalizer.normalize(
            parseList<ProgressInfo>(entries.getValue(ENTRY_PROGRESS), ENTRY_PROGRESS)
        )
        val browserSession = parseObject<BrowserSessionSnapshot>(
            entries.getValue(ENTRY_BROWSER_SESSION),
            ENTRY_BROWSER_SESSION
        )
        val cookieProfiles = parseList<CookieProfileStore.CookieProfileBackup>(
            entries.getValue(ENTRY_COOKIE_PROFILES),
            ENTRY_COOKIE_PROFILES
        )
        val thumbnails = entries
            .filterKeys(::isThumbnailEntry)
            .mapKeys { (entryName, _) -> thumbnailTabId(entryName) }

        val archive = MigrationArchive(
            manifest = manifest,
            settingsPrefs = settingsPrefs,
            playbackPrefs = playbackPrefs,
            bookmarks = bookmarks,
            history = history,
            videos = videos,
            progress = progress,
            browserSession = browserSession,
            cookieProfiles = cookieProfiles
        )
        validateArchive(archive, thumbnails)
        return ValidatedMigrationPackage(archive, thumbnails)
    }

    private fun parseManifest(json: JsonObject): MigrationManifest {
        val schemaVersion = requiredInt(json, "schemaVersion")
        require(schemaVersion == SCHEMA_V1 || schemaVersion == SCHEMA_V2) {
            "Unsupported migration schema version: $schemaVersion."
        }
        requiredLong(json, "exportedAtEpochMs")
        requiredString(json, "exportedByPackage")
        requiredString(json, "exportedByRole")
        requiredString(json, "appVersionName")
        requiredInt(json, "bookmarkCount")
        requiredInt(json, "historyCount")
        requiredInt(json, "videoCount")
        requiredInt(json, "progressCount")
        requiredInt(json, "browserSessionCount")
        requiredInt(json, "thumbnailCount")
        requiredInt(json, "cookieProfileCount")
        requiredBoolean(json, "cookieContentIncluded")

        val encryption = json.get("encryption")
            ?.takeUnless(JsonElement::isJsonNull)
            ?.let { element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    "Migration encryption must be a string."
                }
                element.asString
            }
        if (schemaVersion == SCHEMA_V2) {
            require(encryption == ENCRYPTION_NONE) {
                "Unsupported migration encryption: ${encryption ?: "missing"}."
            }
            val categories = json.get("sensitiveCategories")
            require(
                categories != null && categories.isJsonArray &&
                    categories.asJsonArray.all { item ->
                        item.isJsonPrimitive && item.asJsonPrimitive.isString
                    }
            ) { "Migration v2 sensitiveCategories must be a string array." }
        } else if (encryption != null) {
            require(encryption == ENCRYPTION_NONE) {
                "Unsupported migration encryption: $encryption."
            }
        }

        return runCatching { gson.fromJson(json, MigrationManifest::class.java) }
            .getOrElse { throw IllegalArgumentException("Migration manifest is invalid.", it) }
    }

    private fun validateV2Descriptors(
        manifestJson: JsonObject,
        manifest: MigrationManifest,
        entries: Map<String, ByteArray>
    ) {
        require(manifestJson.has("payloads") && manifestJson.get("payloads").isJsonObject) {
            "Migration v2 manifest is missing payload descriptors."
        }
        manifestJson.getAsJsonObject("payloads").entrySet().forEach { (name, value) ->
            require(value.isJsonObject) { "Migration payload descriptor is invalid for $name." }
            val descriptorJson = value.asJsonObject
            requiredLong(descriptorJson, "sizeBytes")
            requiredString(descriptorJson, "sha256")
        }
        val expectedNames = entries.keys - ENTRY_MANIFEST
        val payloadDescriptors = requireNotNull(manifest.payloads) {
            "Migration v2 payload descriptors are missing."
        }
        require(payloadDescriptors.keys == expectedNames) {
            "Migration payload descriptor set does not match the archive entries."
        }
        expectedNames.forEach { name ->
            val expected = payloadDescriptors[name]
                ?: throw IllegalArgumentException("Migration payload descriptor is missing for $name.")
            require(expected.sizeBytes >= 0L && expected.sizeBytes == entries.getValue(name).size.toLong()) {
                "Migration payload size does not match for $name."
            }
            require(!expected.sha256.isNullOrBlank() && SHA_256.matches(expected.sha256)) {
                "Migration payload SHA-256 is invalid for $name."
            }
            require(expected.sha256 == sha256(entries.getValue(name))) {
                "Migration payload checksum does not match for $name."
            }
        }
    }

    private fun validateArchive(
        archive: MigrationArchive,
        thumbnailsByTabId: Map<String, ByteArray>
    ) {
        val manifest = archive.manifest
        require(manifest.exportedAtEpochMs >= 0L) { "Migration export timestamp is invalid." }
        require(!manifest.exportedByPackage.isNullOrBlank()) { "Migration package name is missing." }
        require(!manifest.exportedByRole.isNullOrBlank()) { "Migration role is missing." }
        require(!manifest.appVersionName.isNullOrBlank()) { "Migration app version is missing." }

        validatePreferences(archive.settingsPrefs, ENTRY_SETTINGS_PREFS)
        validatePreferences(archive.playbackPrefs, ENTRY_PLAYBACK_PREFS)
        requireUniqueNonBlank(archive.bookmarks.map { it.link }, "bookmark primary key")
        requireUniqueNonBlank(archive.history.map { it.id }, "history primary key")
        requireUniqueNonBlank(archive.videos.map { it.id }, "video primary key")
        requireUniqueNonBlank(archive.progress.map { it.id }, "progress primary key")
        archive.progress.forEach { item ->
            require(item.downloadStatus == VideoTaskState.SUCCESS) {
                "Migration progress contains a non-completed download."
            }
            val progressVideo = requireNotNull(item.videoInfo) {
                "Migration progress contains no video metadata."
            }
            require(!progressVideo.id.isNullOrBlank()) {
                "Migration progress contains invalid video metadata."
            }
        }
        archive.videos.forEach(::validateVideoInfo)
        archive.progress.forEach { item -> validateVideoInfo(requireNotNull(item.videoInfo)) }

        val rawTabs = requireNotNull(archive.browserSession.tabs) {
            "Migration browser session tabs are missing."
        }
        val tabs = rawTabs.map { tab ->
            requireNotNull(tab) { "Migration browser session contains a null tab." }
        }
        require(tabs.size <= 100) { "Migration browser session has too many tabs." }
        requireUniqueNonBlank(tabs.map { it.id }, "browser tab id")
        tabs.forEach { tab ->
            val tabId = requireNotNull(tab.id as String?) { "Migration browser tab id is missing." }
            val tabUrl = requireNotNull(tab.url as String?) { "Migration browser tab URL is missing." }
            require(SAFE_ID.matches(tabId)) { "Migration package has an unsafe tab id." }
            require(tabUrl.startsWith("http://") || tabUrl.startsWith("https://")) {
                "Migration browser session contains an invalid URL."
            }
        }
        require(
            if (tabs.isEmpty()) {
                archive.browserSession.currentIndex == 0
            } else {
                archive.browserSession.currentIndex in 0..tabs.size
            }
        ) {
            "Migration browser session currentIndex is out of bounds."
        }

        requireUniqueNonBlank(archive.cookieProfiles.map { it.id }, "cookie profile id")
        archive.cookieProfiles.forEach { rawProfile ->
            val profile = requireNotNull(rawProfile) { "Migration Cookie profile is null." }
            val profileId = requireNotNull(profile.id as String?) {
                "Migration Cookie profile id is missing."
            }
            val profileName = requireNotNull(profile.name as String?) {
                "Migration Cookie profile name is missing."
            }
            require(SAFE_ID.matches(profileId)) { "Migration package has an unsafe Cookie profile id." }
            require(profileName.isNotBlank()) { "Migration Cookie profile name is missing." }
            val domains = requireNotNull(profile.domains) {
                "Migration Cookie profile domains are missing."
            }
            require(domains.isNotEmpty() && domains.none { rawDomain ->
                val domain = rawDomain as String?
                domain.isNullOrBlank() || domain.length > 253
            }) {
                "Migration Cookie profile domains are invalid."
            }
        }

        val tabIds = tabs.map { it.id }.toSet()
        thumbnailsByTabId.forEach { (tabId, bytes) ->
            require(tabId in tabIds) { "Migration thumbnail does not belong to a browser tab." }
            require(bytes.isNotEmpty()) { "Migration thumbnail cannot be empty." }
        }

        requireCount(manifest.bookmarkCount, archive.bookmarks.size, "bookmarks")
        requireCount(manifest.historyCount, archive.history.size, "history")
        requireCount(manifest.videoCount, archive.videos.size, "videos")
        requireCount(manifest.progressCount, archive.progress.size, "progress")
        requireCount(manifest.browserSessionCount, tabs.size, "browser session")
        requireCount(manifest.thumbnailCount, thumbnailsByTabId.size, "thumbnails")
        requireCount(manifest.cookieProfileCount, archive.cookieProfiles.size, "Cookie profiles")

        val hasCookieContent = archive.cookieProfiles.any { it.content != null }
        require(manifest.cookieContentIncluded == hasCookieContent) {
            "Migration Cookie content declaration does not match the payload."
        }
        if (manifest.schemaVersion == SCHEMA_V2) {
            val expectedSensitive = if (hasCookieContent) {
                listOf(SENSITIVE_COOKIE_CONTENT)
            } else {
                emptyList()
            }
            val declaredSensitive = requireNotNull(manifest.sensitiveCategories) {
                "Migration sensitive category declaration is missing."
            }
            require(declaredSensitive == expectedSensitive) {
                "Migration sensitive category declaration does not match the payload."
            }
        }
    }

    private fun validatePreferences(entries: List<PreferenceEntry>, entryName: String) {
        requireUniqueNonBlank(entries.map { it.key }, "$entryName preference key")
        entries.forEach { entry ->
            val values = listOf(
                entry.stringValue,
                entry.intValue,
                entry.longValue,
                entry.floatValue,
                entry.booleanValue,
                entry.stringSetValue
            )
            val selectedIndex = when (entry.valueType) {
                "string" -> 0
                "int" -> 1
                "long" -> 2
                "float" -> 3
                "boolean" -> 4
                "string_set" -> 5
                else -> throw IllegalArgumentException(
                    "Migration preference ${entry.key} has an unsupported type."
                )
            }
            require(values[selectedIndex] != null && values.withIndex().all { (index, value) ->
                index == selectedIndex || value == null
            }) { "Migration preference ${entry.key} has an invalid typed value." }
            entry.floatValue?.let { value ->
                require(value.isFinite()) { "Migration preference ${entry.key} is not finite." }
            }
        }
    }

    private fun validateVideoInfo(video: VideoInfo) {
        requireNotNull(video)
        require(!video.id.isNullOrBlank()) { "Migration video id is missing." }
        requireNotNull(video.downloadUrls) { "Migration video download URLs are missing." }
            .forEach { rawRequest ->
                val request = requireNotNull(rawRequest) {
                    "Migration download request is null."
                }
                require(!request.url.isNullOrBlank()) { "Migration download request URL is missing." }
                requireNotNull(request.headers) { "Migration download request headers are missing." }
            }
        val formats = requireNotNull(video.formats) { "Migration video formats are missing." }
        requireNotNull(formats.formats) { "Migration video format list is missing." }
            .forEach { format -> requireNotNull(format) { "Migration video format is null." } }
    }

    private fun requireUniqueNonBlank(values: List<String?>, label: String) {
        require(values.none { it.isNullOrBlank() }) { "Migration $label is blank." }
        require(values.toSet().size == values.size) { "Migration $label is duplicated." }
    }

    private fun requireCount(expected: Int, actual: Int, label: String) {
        require(expected >= 0 && expected == actual) {
            "Migration manifest count does not match $label."
        }
    }

    private fun requiredString(json: JsonObject, field: String): String {
        val element = json.get(field)
        require(
            element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString
        ) { "Migration manifest field $field must be a string." }
        return element.asString
    }

    private fun requiredBoolean(json: JsonObject, field: String): Boolean {
        val element = json.get(field)
        require(
            element != null && element.isJsonPrimitive && element.asJsonPrimitive.isBoolean
        ) { "Migration manifest field $field must be a boolean." }
        return element.asBoolean
    }

    private fun requiredInt(json: JsonObject, field: String): Int {
        val value = requiredIntegerText(json, field)
        return value.toIntOrNull()
            ?: throw IllegalArgumentException("Migration manifest field $field is outside the Int range.")
    }

    private fun requiredLong(json: JsonObject, field: String): Long {
        val value = requiredIntegerText(json, field)
        return value.toLongOrNull()
            ?: throw IllegalArgumentException("Migration manifest field $field is outside the Long range.")
    }

    private fun requiredIntegerText(json: JsonObject, field: String): String {
        val element = json.get(field)
        require(
            element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber
        ) { "Migration manifest field $field must be an integer." }
        val value = element.asString
        require(Regex("-?(0|[1-9][0-9]*)").matches(value)) {
            "Migration manifest field $field must be an integer."
        }
        return value
    }

    private inline fun <reified T> parseList(bytes: ByteArray, entryName: String): List<T> {
        val json = parseJson(bytes, entryName)
        require(json.isJsonArray && json.asJsonArray.all { it.isJsonObject }) {
            "Migration entry $entryName must be a JSON object array."
        }
        val type = object : TypeToken<List<T>>() {}.type
        return runCatching { gson.fromJson<List<T>>(json, type) }
            .getOrElse { throw IllegalArgumentException("Migration entry $entryName is invalid.", it) }
            ?: throw IllegalArgumentException("Migration entry $entryName cannot be null.")
    }

    private inline fun <reified T> parseObject(bytes: ByteArray, entryName: String): T {
        val json = parseJson(bytes, entryName)
        require(json.isJsonObject) { "Migration entry $entryName must be a JSON object." }
        return runCatching { gson.fromJson(json, T::class.java) }
            .getOrElse { throw IllegalArgumentException("Migration entry $entryName is invalid.", it) }
            ?: throw IllegalArgumentException("Migration entry $entryName cannot be null.")
    }

    private fun parseJsonObject(bytes: ByteArray, entryName: String): JsonObject {
        val json = parseJson(bytes, entryName)
        require(json.isJsonObject) { "Migration entry $entryName must be a JSON object." }
        return json.asJsonObject
    }

    private fun parseJson(bytes: ByteArray, entryName: String): JsonElement {
        require(bytes.isNotEmpty()) { "Migration entry $entryName is empty." }
        val jsonText = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException(
                "Migration entry $entryName is not valid UTF-8.",
                error
            )
        }
        val reader = JsonReader(StringReader(jsonText)).apply {
            isLenient = false
        }
        return try {
            val value = JsonParser.parseReader(reader)
            require(!value.isJsonNull && reader.peek() == JsonToken.END_DOCUMENT) {
                "Migration entry $entryName is not a single JSON value."
            }
            value
        } catch (error: Exception) {
            throw IllegalArgumentException("Migration entry $entryName contains invalid JSON.", error)
        } finally {
            reader.close()
        }
    }

    private fun jsonBytes(payload: Any): ByteArray =
        gson.toJson(payload).toByteArray(Charsets.UTF_8)

    private fun descriptor(bytes: ByteArray): MigrationPayloadDescriptor =
        MigrationPayloadDescriptor(bytes.size.toLong(), sha256(bytes))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    private fun isThumbnailEntry(entryName: String): Boolean {
        if (!entryName.startsWith(ENTRY_THUMBNAILS_PREFIX) || !entryName.endsWith(".jpg")) {
            return false
        }
        val tabId = entryName.removePrefix(ENTRY_THUMBNAILS_PREFIX).removeSuffix(".jpg")
        return SAFE_ID.matches(tabId) && entryName == thumbnailEntryName(tabId)
    }

    private fun thumbnailTabId(entryName: String): String =
        entryName.removePrefix(ENTRY_THUMBNAILS_PREFIX).removeSuffix(".jpg")
}

internal class MigrationZipIo(
    private val limits: MigrationArchiveLimits = MigrationArchiveLimits()
) {
    fun read(source: File): LinkedHashMap<String, ByteArray> {
        require(source.isFile) { "Migration package does not exist." }
        require(source.length() in 1..limits.maxArchiveBytes) {
            "Migration package exceeds the compressed size limit."
        }

        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Migration package contains an unexpected directory entry." }
                validateEntryName(entry.name)
                require(entries.size < limits.maxEntryCount) {
                    "Migration package contains too many entries."
                }
                require(!entries.containsKey(entry.name)) {
                    "Migration package contains a duplicate entry: ${entry.name}."
                }
                if (entry.size >= 0L) {
                    require(entry.size <= limits.maxEntryBytes) {
                        "Migration entry ${entry.name} exceeds the size limit."
                    }
                }
                val bytes = readEntry(zip, entry.name, limits.maxTotalUncompressedBytes - totalBytes)
                totalBytes += bytes.size
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "Migration package is empty." }
        return entries
    }

    fun write(destination: File, entries: Map<String, ByteArray>) {
        require(entries.isNotEmpty()) { "Migration package cannot be empty." }
        require(entries.size <= limits.maxEntryCount) { "Migration package contains too many entries." }
        var totalBytes = 0L
        entries.forEach { (name, bytes) ->
            validateEntryName(name)
            require(bytes.size.toLong() <= limits.maxEntryBytes) {
                "Migration entry $name exceeds the size limit."
            }
            totalBytes += bytes.size
            require(totalBytes <= limits.maxTotalUncompressedBytes) {
                "Migration package exceeds the uncompressed size limit."
            }
        }
        destination.parentFile?.let { parent ->
            require(parent.exists() || parent.mkdirs()) { "Unable to create migration staging directory." }
        }

        val fileOutput = FileOutputStream(destination, false)
        val buffered = BufferedOutputStream(fileOutput)
        val zip = ZipOutputStream(buffered)
        try {
            try {
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                buffered.flush()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        if (destination.length() !in 1..limits.maxArchiveBytes) {
            destination.delete()
            throw IllegalArgumentException("Migration package exceeds the compressed size limit.")
        }
    }

    private fun readEntry(zip: ZipInputStream, entryName: String, totalRemaining: Long): ByteArray {
        require(totalRemaining >= 0L) { "Migration package exceeds the uncompressed size limit." }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) {
                break
            }
            if (count == 0) {
                continue
            }
            entryBytes += count
            require(entryBytes <= limits.maxEntryBytes) {
                "Migration entry $entryName exceeds the size limit."
            }
            require(entryBytes <= totalRemaining) {
                "Migration package exceeds the uncompressed size limit."
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateEntryName(name: String) {
        require(name.isNotBlank()) { "Migration package contains an empty entry name." }
        require(!name.contains('\u0000')) { "Migration package contains a NUL path." }
        require(!name.contains('\\')) { "Migration package contains a backslash path." }
        require(!name.startsWith('/')) { "Migration package contains an absolute path." }
        require(!Regex("^[A-Za-z]:").containsMatchIn(name)) {
            "Migration package contains a drive-qualified path."
        }
        val segments = name.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Migration package contains an unsafe path: $name."
        }
    }
}
