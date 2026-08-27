package com.myAllVideoBrowser.util.telegram

class TelegramImportSession(autoOpenRequested: Boolean) {
    class ResolutionToken internal constructor(
        internal val generation: Long,
        internal val postUrl: String
    )

    private var autoOpenPending = autoOpenRequested
    private var currentGeneration = -1L
    private var currentPost: TelegramPostUrl? = null
    private var activeToken: ResolutionToken? = null
    private var completedToken: ResolutionToken? = null

    fun beginPage(generation: Long, pageUrl: String) {
        currentGeneration = generation
        currentPost = TelegramPostUrl.parse(pageUrl)
        activeToken = null
        completedToken = null
        if (currentPost == null && pageUrl.startsWith("http", ignoreCase = true)) {
            autoOpenPending = false
        }
    }

    fun startResolution(generation: Long, pageUrl: String): ResolutionToken? {
        val post = TelegramPostUrl.parse(pageUrl) ?: return null
        if (generation != currentGeneration || currentPost?.canonicalUrl != post.canonicalUrl) {
            return null
        }
        val token = ResolutionToken(generation, post.canonicalUrl)
        if (activeToken.matches(token) || completedToken.matches(token)) return null
        activeToken = token
        return token
    }

    fun publishIfCurrent(
        token: ResolutionToken,
        generation: Long,
        pageUrl: String
    ): Boolean {
        val post = TelegramPostUrl.parse(pageUrl) ?: return false
        if (activeToken !== token ||
            token.generation != generation ||
            generation != currentGeneration ||
            token.postUrl != post.canonicalUrl ||
            currentPost?.canonicalUrl != post.canonicalUrl
        ) {
            return false
        }
        activeToken = null
        completedToken = token
        return true
    }

    fun isMediaImportPage(): Boolean = autoOpenPending && currentPost != null

    fun consumeAutoOpen(): Boolean {
        if (!isMediaImportPage()) return false
        autoOpenPending = false
        return true
    }

    private fun ResolutionToken?.matches(other: ResolutionToken): Boolean {
        val current = this ?: return false
        return current.generation == other.generation && current.postUrl == other.postUrl
    }
}
