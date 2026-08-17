package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserBackPolicyTest {
    @Test
    fun detectedVideos_takePriorityOverAddressEditorAndHistory() {
        assertEquals(
            WebTabBackAction.CLOSE_DETECTED_VIDEOS,
            BrowserBackPolicy.resolveWebTabAction(
                isDetectedVideosVisible = true,
                isAddressEditorOpen = true,
                canGoBack = true
            )
        )
    }

    @Test
    fun addressEditor_takesPriorityOverWebHistory() {
        assertEquals(
            WebTabBackAction.CLOSE_ADDRESS_EDITOR,
            BrowserBackPolicy.resolveWebTabAction(
                isDetectedVideosVisible = false,
                isAddressEditorOpen = true,
                canGoBack = true
            )
        )
    }

    @Test
    fun history_isUsedBeforeClosingTab() {
        assertEquals(
            WebTabBackAction.GO_BACK,
            BrowserBackPolicy.resolveWebTabAction(
                isDetectedVideosVisible = false,
                isAddressEditorOpen = false,
                canGoBack = true
            )
        )
    }

    @Test
    fun rootPage_closesCurrentTab() {
        assertEquals(
            WebTabBackAction.CLOSE_TAB,
            BrowserBackPolicy.resolveWebTabAction(
                isDetectedVideosVisible = false,
                isAddressEditorOpen = false,
                canGoBack = false
            )
        )
    }

    @Test
    fun closeSelectedTab_selectsPreviousTab() {
        assertEquals(
            1,
            BrowserTabIndexPolicy.selectedIndexAfterClose(
                currentIndex = 2,
                closedIndex = 2,
                remainingTabCount = 3
            )
        )
    }

    @Test
    fun closeTabBeforeSelection_shiftsSelectionLeft() {
        assertEquals(
            2,
            BrowserTabIndexPolicy.selectedIndexAfterClose(
                currentIndex = 3,
                closedIndex = 1,
                remainingTabCount = 3
            )
        )
    }

    @Test
    fun closeTabAfterSelection_keepsSelection() {
        assertEquals(
            1,
            BrowserTabIndexPolicy.selectedIndexAfterClose(
                currentIndex = 1,
                closedIndex = 3,
                remainingTabCount = 3
            )
        )
    }

    @Test
    fun undoInsertion_neverReplacesHomeOrExceedsTail() {
        assertEquals(1, BrowserTabIndexPolicy.restoredInsertionIndex(0, 3))
        assertEquals(3, BrowserTabIndexPolicy.restoredInsertionIndex(8, 3))
        assertEquals(2, BrowserTabIndexPolicy.restoredInsertionIndex(2, 3))
    }

    @Test
    fun closedTabUndo_remainsVisibleForFifteenSeconds() {
        assertEquals(15_000, BrowserTabUndoPolicy.DURATION_MS)
    }
}
