package com.myAllVideoBrowser.contentblock.nativebridge

import com.myAllVideoBrowser.contentblock.BrowserResourceType
import com.myAllVideoBrowser.contentblock.ContentBlockDecision
import com.myAllVideoBrowser.contentblock.ContentBlockEngine
import com.myAllVideoBrowser.contentblock.ContentBlockRequest
import com.myAllVideoBrowser.contentblock.CosmeticResources
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticAction
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticOperator
import com.myAllVideoBrowser.contentblock.ProceduralCosmeticRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

class AdblockRustEngine private constructor(
    handle: Long
) : ContentBlockEngine {
    private val nativeHandle = AtomicLong(handle)

    override val version: String = AdblockRustNative.nativeVersion()
        ?: error("Native content-block engine version is unavailable")

    override fun evaluate(request: ContentBlockRequest): ContentBlockDecision {
        if (request.isMainFrame || !request.isHttpRequest()) return ContentBlockDecision.Allow
        val handle = requireHandle()
        return when (
            AdblockRustNative.nativeEvaluate(
                handle,
                request.url,
                request.documentUrl.orEmpty(),
                request.resourceType.nativeName,
                request.method
            )
        ) {
            AdblockRustNative.DECISION_BLOCK -> ContentBlockDecision.Block("adblock-rust")
            AdblockRustNative.DECISION_ALLOW,
            AdblockRustNative.DECISION_INVALID_REQUEST -> ContentBlockDecision.Allow
            else -> error("Native content-block engine handle is invalid")
        }
    }

    override fun cosmeticResources(url: String): CosmeticResources {
        val json = AdblockRustNative.nativeCosmeticResources(requireHandle(), url)
            ?: return CosmeticResources()
        return JSON.decodeFromString<NativeCosmeticResources>(json).toDomain()
    }

    override fun hiddenSelectors(
        classes: Collection<String>,
        ids: Collection<String>,
        exceptions: Collection<String>
    ): List<String> {
        val json = AdblockRustNative.nativeHiddenSelectors(
            requireHandle(),
            classes.take(MAX_DYNAMIC_NAMES).toTypedArray(),
            ids.take(MAX_DYNAMIC_NAMES).toTypedArray(),
            exceptions.take(MAX_DYNAMIC_NAMES).toTypedArray()
        ) ?: return emptyList()
        return JSON.decodeFromString<List<String>>(json).take(MAX_DYNAMIC_SELECTORS)
    }

    override fun serialize(): ByteArray {
        return AdblockRustNative.nativeSerialize(requireHandle())
            ?: error("Native content-block engine serialization failed")
    }

    override fun statsJson(): String {
        return AdblockRustNative.nativeStats(requireHandle()) ?: "{}"
    }

    override fun close() {
        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L) AdblockRustNative.nativeDestroy(handle)
    }

    private fun requireHandle(): Long {
        return nativeHandle.get().takeIf { it != 0L }
            ?: error("Content-block engine is closed")
    }

    companion object {
        private const val MAX_DYNAMIC_NAMES = 4_096
        private const val MAX_DYNAMIC_SELECTORS = 2_048
        private val JSON = Json { ignoreUnknownKeys = false }

        fun fromLists(filterLists: List<String>, contextFree: Boolean): AdblockRustEngine {
            val handle = AdblockRustNative.nativeCreate(filterLists.toTypedArray(), contextFree)
            require(handle != 0L) { "Native content-block engine creation failed" }
            return AdblockRustEngine(handle)
        }

        fun fromSerialized(serialized: ByteArray): AdblockRustEngine {
            val handle = AdblockRustNative.nativeCreateFromSerialized(serialized)
            require(handle != 0L) { "Native content-block cache is incompatible" }
            return AdblockRustEngine(handle)
        }
    }
}

private fun ContentBlockRequest.isHttpRequest(): Boolean {
    return url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("http://", ignoreCase = true)
}

@Serializable
private data class NativeCosmeticResources(
    val hideSelectors: List<String>,
    val exceptions: List<String>,
    val generichide: Boolean,
    val proceduralActions: List<NativeProceduralCosmeticRule>,
    val proceduralIgnored: Int,
    val scriptletsIgnored: Boolean
) {
    fun toDomain() = CosmeticResources(
        hideSelectors = hideSelectors,
        exceptions = exceptions,
        generichide = generichide,
        proceduralRules = proceduralActions.map(NativeProceduralCosmeticRule::toDomain),
        proceduralIgnored = proceduralIgnored,
        scriptletsIgnored = scriptletsIgnored
    )
}

@Serializable
private data class NativeProceduralCosmeticRule(
    val selector: List<NativeProceduralCosmeticOperator>,
    val action: NativeProceduralCosmeticAction? = null
) {
    fun toDomain() = ProceduralCosmeticRule(
        selector = selector.map(NativeProceduralCosmeticOperator::toDomain),
        action = action?.toDomain()
    )
}

@Serializable
private data class NativeProceduralCosmeticOperator(
    val type: String,
    @SerialName("arg") val argument: String
) {
    fun toDomain() = ProceduralCosmeticOperator(type = type, argument = argument)
}

@Serializable
private data class NativeProceduralCosmeticAction(
    val type: String,
    @SerialName("arg") val argument: String? = null
) {
    fun toDomain() = ProceduralCosmeticAction(type = type, argument = argument)
}
