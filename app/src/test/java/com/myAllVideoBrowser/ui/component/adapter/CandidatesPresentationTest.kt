package com.myAllVideoBrowser.ui.component.adapter

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.databinding.ObservableField
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.*
import com.myAllVideoBrowser.util.VideoFormatUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CandidatesPresentationTest {
    private val formats = listOf(2160, 1440, 1080, 720, 480).map { height ->
        VideoFormatEntity(formatId = "$height", height = height, url = "https://example.org/$height.mp4", ext = "mp4")
    }
    private val video = VideoInfo(id = "video", formats = VideFormatEntityList(formats))
    private val selection = ObservableField<Map<String, String>>(
        mapOf(video.id to VideoFormatUi.selectionKey(video, formats.last()))
    )
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    @Test fun collapsedListKeepsSelectedLowerQualityVisible() {
        val adapter = CandidatesListRecyclerViewAdapter(video, selection, mock(CandidateFormatListener::class.java))
        assertEquals(3, adapter.itemCount)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 2)
        assertEquals(VideoFormatUi.selectionKey(video, formats.last()), holder.binding.downloadCandidate)
        assertEquals(true, holder.binding.isCandidateSelected)
        adapter.setExpanded(true)
        assertEquals(5, adapter.itemCount)
        adapter.setExpanded(false)
        adapter.onBindViewHolder(holder, 2)
        assertEquals(true, holder.binding.isCandidateSelected)
    }

    @Test fun diffUpdatesDescribeOnlyTheVisibleListWhenFormatsChange() {
        val adapter = CandidatesListRecyclerViewAdapter(video, selection, mock(CandidateFormatListener::class.java))
        var observerCount = adapter.itemCount
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { observerCount += itemCount }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { observerCount -= itemCount }
        })
        adapter.setData(formats.take(4))
        assertEquals(adapter.itemCount, observerCount)
        adapter.setExpanded(true)
        assertEquals(4, observerCount)
        adapter.setData(formats.take(1))
        assertEquals(1, observerCount)
        adapter.setExpanded(false)
        assertEquals(1, observerCount)
    }
}
