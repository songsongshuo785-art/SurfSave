package com.myAllVideoBrowser.ui.main.home.browser

internal enum class WebTabBackAction {
    CLOSE_DETECTED_VIDEOS,
    CLOSE_ADDRESS_EDITOR,
    GO_BACK,
    CLOSE_TAB
}

internal object BrowserBackPolicy {
    fun resolveWebTabAction(
        isDetectedVideosVisible: Boolean,
        isAddressEditorOpen: Boolean,
        canGoBack: Boolean
    ): WebTabBackAction {
        return when {
            isDetectedVideosVisible -> WebTabBackAction.CLOSE_DETECTED_VIDEOS
            isAddressEditorOpen -> WebTabBackAction.CLOSE_ADDRESS_EDITOR
            canGoBack -> WebTabBackAction.GO_BACK
            else -> WebTabBackAction.CLOSE_TAB
        }
    }
}

internal object BrowserTabIndexPolicy {
    fun selectedIndexAfterClose(
        currentIndex: Int,
        closedIndex: Int,
        remainingTabCount: Int
    ): Int {
        require(remainingTabCount > 0) { "At least the home tab must remain" }
        val candidate = when {
            currentIndex == closedIndex -> closedIndex - 1
            currentIndex > closedIndex -> currentIndex - 1
            else -> currentIndex
        }
        return candidate.coerceIn(HOME_TAB_INDEX, remainingTabCount - 1)
    }

    fun restoredInsertionIndex(originalIndex: Int, currentTabCount: Int): Int {
        require(currentTabCount > 0) { "At least the home tab must exist" }
        return originalIndex.coerceIn(HOME_TAB_INDEX + 1, currentTabCount)
    }
}

internal object BrowserTabUndoPolicy {
    const val DURATION_MS = 15_000
}
