package com.myAllVideoBrowser.data.repository

import java.io.File

internal object BrowserFileDestinationPolicy {
    const val ROOT_RELATIVE_PATH = "SurfSave/Files"
    internal const val MAX_FILE_NAME_LENGTH = 180
    private const val MAX_PRESERVED_EXTENSION_LENGTH = 16

    fun requireNormalized(relativePath: String, fileName: String): String {
        require(relativePath.isNotBlank() && relativePath == relativePath.trim()) {
            "Browser download destination is missing or padded"
        }
        require(fileName.isNotBlank() && fileName == fileName.trim()) {
            "Browser download file name is missing or padded"
        }
        require(relativePath.none(Char::isISOControl) && fileName.none(Char::isISOControl)) {
            "Browser download destination contains control characters"
        }
        require('\\' !in relativePath && '/' !in fileName && '\\' !in fileName) {
            "Browser download destination contains an unsafe separator"
        }
        require(!relativePath.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.containsMatchIn(relativePath)) {
            "Browser download destination must be relative"
        }

        val segments = relativePath.split('/')
        require(segments.size == 3 && segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Browser download destination must be a direct child of $ROOT_RELATIVE_PATH"
        }
        require(segments[0] == "SurfSave" && segments[1] == "Files") {
            "Browser download destination is outside $ROOT_RELATIVE_PATH"
        }
        require(segments[2] == fileName && fileName.length <= MAX_FILE_NAME_LENGTH) {
            "Browser download file name does not match its destination"
        }
        return segments.joinToString("/")
    }

    fun resolve(downloadsRoot: File, relativePath: String, fileName: String): File {
        val normalized = requireNormalized(relativePath, fileName)
        val canonicalRoot = downloadsRoot.canonicalFile
        val allowedParent = File(canonicalRoot, ROOT_RELATIVE_PATH).canonicalFile
        val target = File(canonicalRoot, normalized).canonicalFile
        require(target.parentFile == allowedParent) {
            "Browser download destination escapes its managed directory"
        }
        return target
    }

    fun withCollisionSuffix(fileName: String, index: Int): String {
        require(index > 0) { "Browser download collision index must be positive" }
        val suffix = " ($index)"
        val dotIndex = fileName.lastIndexOf('.')
        val extensionLength = fileName.length - dotIndex - 1
        val hasConventionalExtension = dotIndex in 1 until fileName.lastIndex &&
            extensionLength in 1..MAX_PRESERVED_EXTENSION_LENGTH
        val extension = if (hasConventionalExtension) fileName.substring(dotIndex) else ""
        val stem = if (hasConventionalExtension) fileName.substring(0, dotIndex) else fileName
        val stemBudget = MAX_FILE_NAME_LENGTH - suffix.length - extension.length
        require(stemBudget > 0) { "Browser download collision suffix exceeds the file name limit" }
        val fittedStem = stem.take(stemBudget).dropLastWhile(Char::isHighSurrogate)
        require(fittedStem.isNotBlank()) { "Browser download file name has no usable stem" }
        return "$fittedStem$suffix$extension"
    }

    private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:")
}
