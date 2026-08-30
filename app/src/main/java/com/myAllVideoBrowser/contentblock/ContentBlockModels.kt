package com.myAllVideoBrowser.contentblock

enum class BrowserResourceType(val nativeName: String) {
    DOCUMENT("document"),
    SUBDOCUMENT("subdocument"),
    SCRIPT("script"),
    STYLESHEET("stylesheet"),
    IMAGE("image"),
    FONT("font"),
    MEDIA("media"),
    XML_HTTP_REQUEST("xmlhttprequest"),
    WEBSOCKET("websocket"),
    PING("ping"),
    OTHER("other"),
    UNKNOWN("other")
}

enum class ContentBlockRequestSource {
    WEB_VIEW,
    SERVICE_WORKER
}

data class ContentBlockRequest(
    val url: String,
    val documentUrl: String?,
    val method: String,
    val resourceType: BrowserResourceType,
    val isMainFrame: Boolean,
    val source: ContentBlockRequestSource
)

sealed interface ContentBlockDecision {
    data object Allow : ContentBlockDecision

    data class Block(
        val engine: String,
        val reason: String = "matched-rule"
    ) : ContentBlockDecision
}

data class CosmeticResources(
    val hideSelectors: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val generichide: Boolean = false,
    val proceduralRules: List<ProceduralCosmeticRule> = emptyList(),
    val proceduralIgnored: Int = 0,
    val scriptletsIgnored: Boolean = false
)

data class ProceduralCosmeticRule(
    val selector: List<ProceduralCosmeticOperator>,
    val action: ProceduralCosmeticAction? = null
)

data class ProceduralCosmeticOperator(
    val type: String,
    val argument: String
)

data class ProceduralCosmeticAction(
    val type: String,
    val argument: String? = null
)

enum class ContentBlockEngineStatus {
    DISABLED,
    INITIALIZING,
    UPDATING,
    BUNDLED,
    UP_TO_DATE,
    STALE,
    UPDATE_FAILED,
    ENGINE_FAILED,
    FALLBACK
}

enum class ContentBlockRulesOrigin {
    BUNDLED,
    UPDATED,
    FALLBACK
}

data class ContentBlockState(
    val status: ContentBlockEngineStatus = ContentBlockEngineStatus.INITIALIZING,
    val engineVersion: String = "",
    val rulesVersion: String = "",
    val updatedAtEpochMillis: Long = 0L,
    val blockedRequests: Long = 0L,
    val lastError: String? = null,
    val rulesOrigin: ContentBlockRulesOrigin = ContentBlockRulesOrigin.FALLBACK,
    val isUpdating: Boolean = false
)

internal fun String.isHttpOrHttpsUrl(): Boolean {
    return startsWith("https://", ignoreCase = true) ||
        startsWith("http://", ignoreCase = true)
}
