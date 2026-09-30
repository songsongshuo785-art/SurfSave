package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.component.adapter.DownloadTabListener
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DetectedMediaPanelRegistryTest {

    @Test
    fun panelRestoredFirstBindsWhenHostArrives() {
        val listener = fakeListener()
        val panel = FakePanel()
        DetectedMediaPanelRegistry.registerPanel(TAB, panel)
        assertNull(panel.listener)

        DetectedMediaPanelRegistry.registerHost(TAB, FakeHost(detectedMediaTabListener = listener))

        assertSame(listener, panel.listener)
        cleanup(panel)
    }

    @Test
    fun hostRestoredFirstBindsWhenPanelArrives() {
        val listener = fakeListener()
        DetectedMediaPanelRegistry.registerHost(TAB, FakeHost(detectedMediaTabListener = listener))
        val panel = FakePanel()

        DetectedMediaPanelRegistry.registerPanel(TAB, panel)

        assertSame(listener, panel.listener)
        cleanup(panel)
    }

    @Test
    fun detachedPanelIsNotBound() {
        DetectedMediaPanelRegistry.registerHost(TAB, FakeHost(detectedMediaTabListener = fakeListener()))
        val panel = FakePanel(attached = false)

        DetectedMediaPanelRegistry.registerPanel(TAB, panel)

        assertNull(panel.listener)
        cleanup(panel)
    }

    @Test
    fun unregisteredHostIsNoLongerResolvable() {
        val host = FakeHost(detectedMediaTabListener = fakeListener())
        DetectedMediaPanelRegistry.registerHost(TAB, host)

        DetectedMediaPanelRegistry.unregisterHost(TAB)

        assertNull(DetectedMediaPanelRegistry.resolveHost(TAB))
    }

    private fun cleanup(panel: FakePanel) {
        DetectedMediaPanelRegistry.unregisterHost(TAB)
        DetectedMediaPanelRegistry.unregisterPanel(TAB, panel)
    }

    private class FakeHost(
        override val detectedMediaTabViewModel: VideoDetectionTabViewModel? = null,
        override val detectedMediaTabListener: DownloadTabListener? = null
    ) : DetectedMediaPanelRegistry.Host

    private class FakePanel(private val attached: Boolean = true) :
        DetectedMediaPanelRegistry.Panel {
        var listener: DownloadTabListener? = null

        override val panelAttached: Boolean
            get() = attached

        override fun applyRuntimeDependencies(
            model: VideoDetectionTabViewModel?,
            listener: DownloadTabListener?
        ) {
            this.listener = listener
        }
    }

    private fun fakeListener(): DownloadTabListener = object : DownloadTabListener {
        override fun onCancel() = Unit

        override fun onDownloadVideo(videoInfo: VideoInfo, format: String, videoTitle: String) =
            Unit

        override fun onPreviewVideo(
            videoInfo: VideoInfo,
            sharedView: android.view.View,
            format: String,
            isForce: Boolean
        ) = Unit

        override fun onChoosePlayer(
            videoInfo: VideoInfo,
            sharedView: android.view.View,
            anchorView: android.view.View,
            format: String,
            isForce: Boolean
        ) = Unit

        override fun onSelectFormat(videoInfo: VideoInfo, format: String) = Unit

        override fun onFormatUrlShare(videoInfo: VideoInfo, format: String): Boolean = false
    }

    private companion object {
        const val TAB = 42
    }
}
