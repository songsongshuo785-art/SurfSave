package com.myAllVideoBrowser.ui.main.player

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.myAllVideoBrowser.R

internal sealed interface PlaybackTargetMenuItem {
    data class Target(val target: PlaybackTarget) : PlaybackTargetMenuItem
    data object Add : PlaybackTargetMenuItem
}

internal class PlaybackTargetMenuAdapter(
    private val context: Context,
    private val items: List<PlaybackTargetMenuItem>
) : BaseAdapter() {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): PlaybackTargetMenuItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val holder: ViewHolder
        if (convertView == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_playback_target, parent, false)
            holder = ViewHolder(
                icon = view.findViewById(R.id.playback_target_icon),
                label = view.findViewById(R.id.playback_target_label),
                check = view.findViewById(R.id.playback_target_check)
            )
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        when (val item = getItem(position)) {
            is PlaybackTargetMenuItem.Target -> bindTarget(holder, item.target)
            PlaybackTargetMenuItem.Add -> bindAdd(holder)
        }
        return view
    }

    private fun bindTarget(holder: ViewHolder, target: PlaybackTarget) {
        holder.label.text = target.label
        holder.check.visibility = if (target.isDefault) View.VISIBLE else View.INVISIBLE
        val icon = if (target.isBuiltIn) {
            context.applicationInfo.loadIcon(context.packageManager)
        } else {
            runCatching {
                context.packageManager.getActivityIcon(requireNotNull(target.componentName))
            }.getOrNull()
        }
        holder.icon.setImageDrawable(icon)
        if (icon == null) {
            holder.icon.setImageResource(R.drawable.play_circle_24px)
        }
    }

    private fun bindAdd(holder: ViewHolder) {
        holder.label.setText(R.string.player_target_add)
        holder.icon.setImageResource(R.drawable.ic_add_24)
        holder.check.visibility = View.INVISIBLE
    }

    private data class ViewHolder(
        val icon: ImageView,
        val label: TextView,
        val check: ImageView
    )
}
