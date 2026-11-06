package com.myAllVideoBrowser.ui.main.video

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.databinding.SheetVideoMigrationBinding
import com.myAllVideoBrowser.migration.StorageTarget

/** 单个视频的迁移面板：显示当前位置和所有可移动目标。 */
class VideoMigrationSheet(
    private val context: Context,
    private val video: LocalVideo,
    private val current: StorageTarget?,
    private val targets: List<StorageTarget>,
    private val summary: String? = null,
    private val onMove: (StorageTarget) -> Unit,
) {
    fun show() {
        val binding = SheetVideoMigrationBinding.inflate(LayoutInflater.from(context))
        binding.tvMigrationSummary.visibility = if (summary.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvMigrationSummary.text = summary
        binding.tvMigrationCurrentValue.text = context.getString(
            current?.labelRes() ?: R.string.storage_target_unknown
        )
        binding.tvMigrationCurrentHint.text = context.getString(
            current?.hintRes() ?: R.string.storage_target_private_hint
        )

        targets.forEach { target ->
            val radio = RadioButton(context).apply {
                id = View.generateViewId()
                text = context.getString(target.labelRes())
                tag = target
            }
            binding.targetGroup.addView(radio)
        }
        if (targets.size == 1) {
            binding.targetGroup.check(binding.targetGroup.getChildAt(0).id)
        }

        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)
        binding.btnMigrationCancel.setOnClickListener { dialog.dismiss() }
        binding.btnMigrationMove.setOnClickListener {
            val checkedId = binding.targetGroup.checkedRadioButtonId
            val target = binding.targetGroup.findViewById<RadioButton>(checkedId)?.tag as? StorageTarget
            if (target != null) {
                dialog.dismiss()
                onMove(target)
            }
        }
        dialog.show()
    }

    private fun StorageTarget.labelRes(): Int = when (this) {
        StorageTarget.PUBLIC -> R.string.storage_target_public
        StorageTarget.PRIVATE_INTERNAL -> R.string.storage_target_private_internal
        StorageTarget.PRIVATE_EXTERNAL -> R.string.storage_target_private_external
    }

    private fun StorageTarget.hintRes(): Int = when (this) {
        StorageTarget.PUBLIC -> R.string.storage_target_public_hint
        else -> R.string.storage_target_private_hint
    }
}
