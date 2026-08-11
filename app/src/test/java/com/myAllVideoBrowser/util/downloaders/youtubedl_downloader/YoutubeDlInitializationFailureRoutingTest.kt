package com.myAllVideoBrowser.util.downloaders.youtubedl_downloader

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeDlInitializationFailureRoutingTest {

    @Test
    fun availableTerminalEffects_ownQueueAdvance() {
        var effectsCalls = 0
        var queueCalls = 0

        dispatchYoutubeDlInitializationTerminalCommit(
            terminalEffectsAvailable = true,
            applyTerminalEffects = { effectsCalls++ },
            advanceQueue = { queueCalls++ }
        )

        assertEquals(1, effectsCalls)
        assertEquals(0, queueCalls)
    }

    @Test
    fun missingTerminalEffects_advancesQueueDirectly() {
        var effectsCalls = 0
        var queueCalls = 0

        dispatchYoutubeDlInitializationTerminalCommit(
            terminalEffectsAvailable = false,
            applyTerminalEffects = { effectsCalls++ },
            advanceQueue = { queueCalls++ }
        )

        assertEquals(0, effectsCalls)
        assertEquals(1, queueCalls)
    }
}
