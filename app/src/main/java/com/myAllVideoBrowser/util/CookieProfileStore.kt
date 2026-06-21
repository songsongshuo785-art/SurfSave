package com.myAllVideoBrowser.util

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import java.io.File
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

    companion object {
        private const val PREF_NAME = "cookie_profile_prefs"
        private const val KEY_PROFILES = "COOKIE_PROFILES"
        private const val PROFILE_DIR = "cookie_profiles"
        private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"

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
    private val profileDir: File
        get() = File(context.filesDir, PROFILE_DIR).also { if (!it.exists()) it.mkdirs() }

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
        File(profileDir, fileName).writeText(normalizedContent, Charsets.UTF_8)

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

    fun writeBestProfileCookieFile(url: String, additionalUrl: String? = null): File? {
        val profile = findBestProfile(url) ?: additionalUrl?.let { findBestProfile(it) } ?: return null
        val source = File(profileDir, profile.fileName)
        if (!source.exists()) {
            return null
        }
        val target = File.createTempFile("cookie_profile_${profile.id}_", ".txt", context.cacheDir)
        source.copyTo(target, overwrite = true)
        return target
    }

    fun snapshot(includeContent: Boolean): List<CookieProfileBackup> {
        return getProfiles().map { profile ->
            CookieProfileBackup(
                id = profile.id,
                name = profile.name,
                domains = profile.domains,
                createdAt = profile.createdAt,
                updatedAt = profile.updatedAt,
                content = if (includeContent) readProfileContent(profile) else null
            )
        }
    }

    fun restore(backups: List<CookieProfileBackup>): Int {
        if (backups.isEmpty()) {
            return 0
        }
        val restored = backups.mapNotNull { backup ->
            val content = backup.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fileName = "${backup.id}.txt"
            File(profileDir, fileName).writeText(normalizeNetscapeContent(content), Charsets.UTF_8)
            CookieProfile(
                id = backup.id,
                name = backup.name,
                domains = backup.domains,
                createdAt = backup.createdAt,
                updatedAt = System.currentTimeMillis(),
                fileName = fileName
            )
        }
        if (restored.isNotEmpty()) {
            val byId = (getProfiles() + restored).associateBy { it.id }
            saveProfiles(byId.values.sortedBy { it.name.lowercase(Locale.US) })
        }
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
        val file = File(profileDir, profile.fileName)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
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
