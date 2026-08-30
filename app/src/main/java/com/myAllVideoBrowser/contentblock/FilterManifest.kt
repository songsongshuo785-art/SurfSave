package com.myAllVideoBrowser.contentblock

import kotlinx.serialization.Serializable

@Serializable
data class FilterManifest(
    val schemaVersion: Int,
    val engineVersion: String,
    val snapshotDate: String,
    val sources: List<FilterSourceManifest>
)

@Serializable
data class FilterSourceManifest(
    val id: String,
    val asset: String,
    val url: String,
    val license: String,
    val version: String,
    val sha256: String,
    val bytes: Long
)

@Serializable
internal data class BundledEngineManifest(
    val schemaVersion: Int,
    val engineVersion: String,
    val sourceManifestSha256: String,
    val cacheKey: String,
    val full: BundledEnginePayload,
    val contextFree: BundledEnginePayload
)

@Serializable
internal data class BundledEnginePayload(
    val asset: String,
    val sha256: String,
    val bytes: Long
)

data class VerifiedFilterSource(
    val manifest: FilterSourceManifest,
    val content: String,
    val sha256: String,
    val bytes: Long,
    val etag: String? = null,
    val lastModified: String? = null
)

data class ContentBlockRuleSet(
    val sources: List<VerifiedFilterSource>,
    val sourceManifestSha256: String,
    val cacheKey: String,
    val rulesVersion: String,
    val updatedAtEpochMillis: Long,
    val origin: ContentBlockRulesOrigin
) {
    val filterLists: List<String> get() = sources.map(VerifiedFilterSource::content)
}

data class RuleSetLoadResult(
    val ruleSet: ContentBlockRuleSet,
    val warning: String? = null
)

sealed interface FilterUpdateResult {
    data class Updated(val ruleSet: ContentBlockRuleSet) : FilterUpdateResult

    data class NotModified(
        val ruleSet: ContentBlockRuleSet,
        val checkedAtEpochMillis: Long
    ) : FilterUpdateResult

    data class Failed(val reason: FilterUpdateFailure) : FilterUpdateResult
}

enum class FilterUpdateFailure {
    NETWORK,
    HTTP,
    TOO_LARGE,
    INVALID_CONTENT,
    STORAGE,
    MANIFEST,
    ENGINE
}
