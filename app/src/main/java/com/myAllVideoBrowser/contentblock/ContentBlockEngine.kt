package com.myAllVideoBrowser.contentblock

interface ContentBlockEngine : AutoCloseable {
    val version: String

    fun evaluate(request: ContentBlockRequest): ContentBlockDecision

    fun cosmeticResources(url: String): CosmeticResources

    fun hiddenSelectors(
        classes: Collection<String>,
        ids: Collection<String>,
        exceptions: Collection<String>
    ): List<String>

    fun serialize(): ByteArray

    fun statsJson(): String
}
