package com.myAllVideoBrowser.ui.main.bookmarks


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.addCallback
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.FragmentBookmarksBinding
import com.myAllVideoBrowser.ui.component.adapter.BookmarksAdapter
import com.myAllVideoBrowser.ui.component.adapter.BookmarksListener
import com.myAllVideoBrowser.ui.component.adapter.ReorderableItemTouchHelperCallback
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import javax.inject.Inject

class BookmarksFragment : BaseFragment() {

    private lateinit var dataBinding: FragmentBookmarksBinding

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var bookmarksAdapter: BookmarksAdapter

    private var bookmarksCached = mutableListOf<PageInfo>()

    private var hasChanged = false

    companion object {
        @JvmStatic
        fun newInstance() = BookmarksFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val color = getThemeBackgroundColor()
        val mainModel = mainActivity.mainViewModel

        bookmarksAdapter = BookmarksAdapter(mutableListOf(), listener)
        val touchHelperCallback = ReorderableItemTouchHelperCallback(bookmarksAdapter)
        val itemTouchHelper = ItemTouchHelper(touchHelperCallback)

        val layoutManager =
            WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        dataBinding = FragmentBookmarksBinding.inflate(inflater, container, false).apply {
            this.bookmarksContainer.setBackgroundColor(color)
            this.mainVModel = mainModel
            this.bookmarksList.layoutManager = layoutManager
            this.bookmarksList.adapter = bookmarksAdapter
            itemTouchHelper.attachToRecyclerView(this.bookmarksList)
            this.addBookmark.setOnClickListener { showBookmarkEditor(null) }
            this.refreshBookmarks.setOnClickListener { refreshBookmarks() }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        return dataBinding.root
    }

    val listener = object : BookmarksListener {
        override fun onBookmarkOpenClicked(view: View, bookmarkItem: PageInfo) {
            mainActivity.mainViewModel.browserServicesProvider?.openInCurrentTab(bookmarkItem.link)
            parentFragmentManager.popBackStack()
        }

        override fun onBookmarkMoreClicked(view: View, bookmarkItem: PageInfo, position: Int) {
            PopupMenu(requireContext(), view).apply {
                menu.add(R.string.open)
                menu.add(R.string.edit_bookmark)
                menu.add(R.string.delete_bookmark)
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        getString(R.string.open) -> onBookmarkOpenClicked(view, bookmarkItem)
                        getString(R.string.edit_bookmark) -> showBookmarkEditor(bookmarkItem)
                        getString(R.string.delete_bookmark) -> confirmDeleteBookmark(bookmarkItem, position)
                    }
                    true
                }
                show()
            }
        }

        override fun onBookmarkMove(bookmarks: MutableList<PageInfo>) {
            bookmarksCached = bookmarks.toMutableList()
            hasChanged = true
            mainActivity.mainViewModel.updateBookmarks(bookmarksCached)
        }

        override fun onBookmarkDelete(bookmarks: MutableList<PageInfo>, position: Int) {
            bookmarks.removeAt(position)
            bookmarksCached = bookmarks
            hasChanged = true
            mainActivity.mainViewModel.updateBookmarks(bookmarksCached)
        }
    }

    override fun onDestroy() {
        if (hasChanged) {
            mainActivity.mainViewModel.updateBookmarks(bookmarksCached)
        }

        super.onDestroy()
    }

    private fun showBookmarkEditor(bookmark: PageInfo?) {
        BookmarkEditor.show(
            context = requireContext(),
            bookmark = bookmark,
            currentBookmarks = {
                mainActivity.mainViewModel.bookmarksList.get().orEmpty()
            },
            onSaved = { updated ->
                bookmarksCached = updated.toMutableList()
                hasChanged = true
                mainActivity.mainViewModel.updateBookmarks(bookmarksCached)
            }
        )
    }

    private fun confirmDeleteBookmark(bookmark: PageInfo, position: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_bookmark)
            .setMessage(bookmark.name.ifBlank { bookmark.link })
            .setPositiveButton(R.string.remove) { dialog, _ ->
                val current = mainActivity.mainViewModel.bookmarksList.get()?.toMutableList()
                    ?: mutableListOf()
                val index = current.indexOfFirst { it.link == bookmark.link }
                    .takeIf { it >= 0 } ?: position
                if (index in current.indices) {
                    current.removeAt(index)
                    bookmarksCached = current
                    hasChanged = true
                    mainActivity.mainViewModel.updateBookmarks(bookmarksCached)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.all_text_cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun refreshBookmarks() {
        mainActivity.mainViewModel.refreshBookmarks()
        Toast.makeText(requireContext(), R.string.bookmarks_refreshed, Toast.LENGTH_SHORT).show()
    }

}
