package com.myAllVideoBrowser.util

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CookieProfileStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    data class CookieProfile(
        val id: String,
        val name: String,
        val domains: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
        val fileName: String
    )

    data class CookieProfileBackup(
        val id: String,
        val name: String,
        val domains: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
        val content: String? = null
    )

    data class StoreSnapshot(
        val profiles: List<CookieProfile>,
        val fileContents: Map<String, String>
    )

    companion object {
        private const val PREF_NAME = "cookie_profile_prefs"
        private const val KEY_PROFILES = "COOKIE_PROFILES"
        private const val PROFILE_DIR = "cookie_profiles"
        private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"
        private const val MAX_PROFILE_BYTES = 8L * 1024L * 1024L
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,200}")

        fun parseDomainsFromNetscape(content: String): List<String> {
            return content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("# Netscape") && !it.startsWith("# https://") }
                .mapNotNull { line ->
                    val normalized = line.removePrefix("#HttpOnly_")
                    val parts = normalized.split("\t")
                    parts.firstOrNull()
                        ?.trim()
                        ?.trimStart('.')
                        ?.lowercase(Locale.US)
                        ?.takeIf { it.contains(".") }
                }
                .distinct()
                .sorted()
                .toList()
        }

        fun matchesHost(host: String, domain: String): Boolean {
            val cleanHost = host.trim().trim('.').lowercase(Locale.US)
            val cleanDomain = domain.trim().trim('.').lowercase(Locale.US)
            return cleanHost == cleanDomain || cleanHost.endsWith(".$cleanDomain")
        }
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val profileDirFile: File
        get() = File(context.filesDir, PROFILE_DIR)
    private val profileDir: File
        get() = profileDirFile.also { dir ->
            check(dir.exists() || dir.mkdirs()) { "Unable to create Cookie profile directory." }
        }

    fun getProfiles(): List<CookieProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val type = object : TypeToken<List<CookieProfile>>() {}.type
        return runCatching {
            gson.fromJson<List<CookieProfile>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun importNetscapeProfile(displayName: String, content: String): CookieProfile {
        val normalizedContent = normalizeNetscapeContent(content)
        val domains = parseDomainsFromNetscape(normalizedContent)
        require(domains.isNotEmpty()) { "No cookie domains were found in this file." }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val safeName = FileNameCleaner.cleanFileName(displayName.substringBeforeLast('.').ifBlank { domains.first() })
        val fileName = "$id.txt"
        writeProfileFile(resolveProfileFile(profileDir, fileName), normalizedContent)

        val profile = CookieProfile(
            id = id,
            name = safeName,
            domains = domains,
            createdAt = now,
            updatedAt = now,
            fileName = fileName
        )
        saveProfiles(getProfiles().filterNot { it.name == profile.name || it.domains == profile.domains } + profile)
        return profile
    }

    fun exportAllProfiles(): String {
        val builder = StringBuilder()
        getProfiles().forEachIndexed { index, profile ->
            if (index > 0) {
                builder.appendLine()
            }
            builder.appendLine("# SurfSave cookie profile: ${profile.name}")
            builder.append(readProfileContent(profile).trim())
            builder.appendLine()
        }
        return builder.toString().ifBlank { NETSCAPE_HEADER + "\n" }
    }

    fun writeBestProfileCookieFile(url: String): File? {
        val profile = findBestProfile(url) ?: return null
        val source = resolveProfileFile(profileDir, profile.fileName)
        if (!source.exists()) {
            return null
        }
        val target = File.createTempFile("cookie_profile_${profile.id}_", ".txt", context.cacheDir)
        return try {
            source.copyTo(target, overwrite = true)
            target
        } catch (error: Throwable) {
            if (target.exists() && !target.delete()) {
                AppLogger.w("Failed to clean incomplete cookie profile temp file")
            }
            throw error
        }
    }

    fun snapshot(includeContent: Boolean): List<CookieProfileBackup> {
        val snapshot = createRollbackSnapshot()
        return snapshot.profiles.map { profile ->
            CookieProfileBackup(
                id = profile.id,
                name = profile.name,
                domains = profile.domains,
                createdAt = profile.createdAt,
                updatedAt = profile.updatedAt,
                content = if (includeContent) snapshot.fileContents[profile.fileName] else null
            )
        }
    }

    fun restore(backups: List<CookieProfileBackup>): Int {
        return replaceFromMigration(backups)
    }

    fun createRollbackSnapshot(): StoreSnapshot {
        val profiles = getProfiles()
        validateProfiles(profiles)
        val contents = linkedMapOf<String, String>()
        profiles.forEach { profile ->
            val file = resolveProfileFile(profileDirFile, profile.fileName)
            if (file.exists()) {
                require(file.isFile && file.length() <= MAX_PROFILE_BYTES) {
                    "Cookie profile file is invalid or too large."
                }
                contents[profile.fileName] = file.readText(Charsets.UTF_8)
            }
        }
        return StoreSnapshot(profiles, contents)
    }

    fun restoreRollbackSnapshot(snapshot: StoreSnapshot) {
        replaceStore(snapshot)
    }

    fun replaceFromMigration(backups: List<CookieProfileBackup>): Int {
        val withContent = backups.filter { !it.content.isNullOrBlank() }
        if (withContent.isEmpty()) {
            return 0
        }

        val current = createRollbackSnapshot()
        val importedIds = withContent.map { it.id }.toSet()
        val retainedProfiles = current.profiles.filterNot { it.id in importedIds }
        val retainedFileNames = retainedProfiles.map { it.fileName }.toSet()
        val targetContents = current.fileContents.filterKeys { it in retainedFileNames }.toMutableMap()
        val usedFileNames = retainedFileNames.toMutableSet()
        val restored = withContent.map { backup ->
            require(SAFE_ID.matches(backup.id)) { "Cookie profile id is unsafe." }
            require(backup.name.isNotBlank()) { "Cookie profile name is missing." }
            require(backup.domains.isNotEmpty() && backup.domains.none { it.isBlank() }) {
                "Cookie profile domains are invalid."
            }
            val normalized = normalizeNetscapeContent(requireNotNull(backup.content))
            require(parseDomainsFromNetscape(normalized).isNotEmpty()) {
                "Cookie profile content has no valid domains."
            }
            val fileName = generateInternalFileName(usedFileNames)
            targetContents[fileName] = normalized
            CookieProfile(
                id = backup.id,
                name = backup.name,
                domains = backup.domains,
                createdAt = backup.createdAt,
                updatedAt = backup.updatedAt,
                fileName = fileName
            )
        }

        replaceStore(
            StoreSnapshot(
                profiles = (retainedProfiles + restored).sortedBy { it.name.lowercase(Locale.US) },
                fileContents = targetContents
            )
        )
        return restored.size
    }

    fun summary(): String {
        val profiles = getProfiles()
        if (profiles.isEmpty()) {
            return "No cookie profiles"
        }
        val domainCount = profiles.flatMap { it.domains }.distinct().size
        return "${profiles.size} profiles, $domainCount domains"
    }

    private fun findBestProfile(url: String): CookieProfile? {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) {
            return null
        }
        return getProfiles().firstOrNull { profile ->
            profile.domains.any { matchesHost(host, it) }
        }
    }

    private fun readProfileContent(profile: CookieProfile): String {
        val file = resolveProfileFile(profileDirFile, profile.fileName)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    private fun replaceStore(snapshot: StoreSnapshot) {
        validateProfiles(snapshot.profiles)
        val referencedNames = snapshot.profiles.map { it.fileName }.toSet()
        require(snapshot.fileContents.keys.all { it in referencedNames && SAFE_FILE_NAME.matches(it) }) {
            "Cookie profile snapshot contains an unsafe file."
        }

        val stageDir = File(context.filesDir, "$PROFILE_DIR.import-${UUID.randomUUID()}")
        val backupDir = File(context.filesDir, "$PROFILE_DIR.rollback-${UUID.randomUUID()}")
        check(stageDir.mkdir()) { "Unable to create Cookie profile staging directory." }
        var oldMoved = false
        var newMoved = false
        try {
            snapshot.fileContents.forEach { (fileName, content) ->
                require(content.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_BYTES) {
                    "Cookie profile file is too large."
                }
                writeProfileFile(resolveProfileFile(stageDir, fileName), content)
            }

            val targetDir = profileDirFile
            if (targetDir.exists()) {
                check(targetDir.renameTo(backupDir)) { "Unable to stage existing Cookie profiles." }
                oldMoved = true
            }
            check(stageDir.renameTo(targetDir)) { "Unable to publish Cookie profiles." }
            newMoved = true
            check(
                prefs.edit()
                    .putString(KEY_PROFILES, gson.toJson(snapshot.profiles))
                    .commit()
            ) { "Unable to commit Cookie profile metadata." }
            if (oldMoved) {
                backupDir.deleteRecursively()
            }
        } catch (error: Throwable) {
            if (newMoved && profileDirFile.exists() && !profileDirFile.deleteRecursively()) {
                error.addSuppressed(
                    IllegalStateException("Unable to remove partially imported Cookie profiles.")
                )
            }
            if (oldMoved && backupDir.exists() && !backupDir.renameTo(profileDirFile)) {
                error.addSuppressed(
                    IllegalStateException("Unable to restore previous Cookie profiles.")
                )
            }
            if (stageDir.exists() && !stageDir.deleteRecursively()) {
                error.addSuppressed(
                    IllegalStateException("Unable to clean Cookie profile staging directory.")
                )
            }
            throw error
        }
    }

    private fun validateProfiles(profiles: List<CookieProfile>) {
        require(profiles.map { it.id }.toSet().size == profiles.size) {
            "Cookie profile ids are duplicated."
        }
        require(profiles.map { it.fileName }.toSet().size == profiles.size) {
            "Cookie profile files are duplicated."
        }
        profiles.forEach { profile ->
            require(SAFE_ID.matches(profile.id)) { "Cookie profile id is unsafe." }
            require(profile.name.isNotBlank()) { "Cookie profile name is missing." }
            require(profile.domains.isNotEmpty() && profile.domains.none { it.isBlank() }) {
                "Cookie profile domains are invalid."
            }
            require(SAFE_FILE_NAME.matches(profile.fileName)) { "Cookie profile file name is unsafe." }
        }
    }

    private fun resolveProfileFile(root: File, fileName: String): File {
        require(SAFE_FILE_NAME.matches(fileName)) { "Cookie profile file name is unsafe." }
        val file = File(root, fileName)
        require(file.canonicalFile.parentFile == root.canonicalFile) {
            "Cookie profile file escapes its private directory."
        }
        return file
    }

    private fun generateInternalFileName(usedNames: MutableSet<String>): String {
        while (true) {
            val candidate = "${UUID.randomUUID()}.txt"
            if (usedNames.add(candidate)) {
                return candidate
            }
        }
    }

    private fun writeProfileFile(file: File, content: String) {
        FileOutputStream(file, false).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun saveProfiles(profiles: List<CookieProfile>) {
        prefs.edit {
            putString(KEY_PROFILES, gson.toJson(profiles.sortedBy { it.name.lowercase(Locale.US) }))
        }
    }

    private fun normalizeNetscapeContent(content: String): String {
        val trimmed = content.trim()
        return if (trimmed.startsWith(NETSCAPE_HEADER)) {
            trimmed + "\n"
        } else {
            "$NETSCAPE_HEADER\n$trimmed\n"
        }
    }
}
