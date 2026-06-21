package com.myAllVideoBrowser.ui.main.migration

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentMigrationCenterBinding
import com.myAllVideoBrowser.migration.MigrationOverview
import com.myAllVideoBrowser.migration.MigrationReport
import com.myAllVideoBrowser.migration.MigrationStage
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.FileUtil
import java.text.DateFormat
import javax.inject.Inject

class MigrationCenterFragment : BaseFragment() {

    companion object {
        const val TAG = "migration_center"

        fun newInstance() = MigrationCenterFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var binding: FragmentMigrationCenterBinding
    private lateinit var viewModel: MigrationCenterViewModel
    private val pickMigrationPackageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        }
        viewModel.importMigrationPackage(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMigrationCenterBinding.inflate(inflater, container, false)
        viewModel = ViewModelProvider(this, viewModelFactory)[MigrationCenterViewModel::class.java]
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonMovePrivateVideos.setOnClickListener {
            viewModel.movePrivateVideosToSharedDownloads()
        }
        binding.buttonExport.setOnClickListener {
            viewModel.exportMigrationPackage(binding.includeCookieProfiles.isChecked)
        }
        binding.buttonImport.setOnClickListener {
            importFromAutoOrPicker()
        }
        binding.buttonReimport.setOnClickListener {
            importFromAutoOrPicker()
        }
        binding.buttonChoosePackage.setOnClickListener {
            openMigrationPackagePicker()
        }
        binding.buttonDeletePackage.setOnClickListener {
            viewModel.deleteMigrationPackage()
        }
        binding.buttonUninstallLegacy.setOnClickListener {
            viewModel.requestUninstallLegacy()
        }
        binding.buttonRefresh.setOnClickListener {
            viewModel.refresh()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }

        observeViewModel()
        viewModel.start()
    }

    override fun onDestroyView() {
        viewModel.stop()
        super.onDestroyView()
    }

    private fun observeViewModel() {
        viewModel.overview.observe(viewLifecycleOwner) { overview ->
            renderOverview(overview)
        }
        viewModel.isWorking.observe(viewLifecycleOwner) { isWorking ->
            binding.migrationProgress.isVisible = isWorking == true
            binding.buttonMovePrivateVideos.isEnabled = isWorking != true
            binding.buttonExport.isEnabled = isWorking != true
            binding.buttonImport.isEnabled = isWorking != true
            binding.buttonReimport.isEnabled = isWorking != true
            binding.buttonChoosePackage.isEnabled = isWorking != true
            binding.buttonDeletePackage.isEnabled = isWorking != true
            binding.buttonUninstallLegacy.isEnabled = isWorking != true
            binding.buttonRefresh.isEnabled = isWorking != true
        }
        viewModel.messageEvent.observe(viewLifecycleOwner) { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
        viewModel.uninstallLegacyEvent.observe(viewLifecycleOwner) { packageName ->
            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(uninstallIntent)
        }
    }

    private fun renderOverview(overview: MigrationOverview) {
        binding.toolbar.title = getString(R.string.migration_center_title)
        binding.migrationSubtitle.text = getString(R.string.migration_center_subtitle)
        binding.statusText.text = buildStatusText(overview)
        binding.packageText.text = buildPackageText(overview)
        binding.privateVideosText.text = buildPrivateVideosText(overview)

        val report = overview.lastReport
        binding.reportCard.isVisible = report != null
        binding.reportText.text = report?.let(::buildReportText).orEmpty()

        binding.buttonMovePrivateVideos.isVisible =
            overview.exportEnabled && overview.privateVideoSummary.hasPrivateVideos
        binding.buttonExport.isVisible = overview.exportEnabled
        binding.includeCookieProfiles.isVisible = overview.exportEnabled
        binding.buttonImport.isVisible = overview.importEnabled && overview.stage != MigrationStage.IMPORTED
        binding.buttonReimport.isVisible = overview.importEnabled && overview.stage == MigrationStage.IMPORTED
        binding.buttonChoosePackage.isVisible = overview.importEnabled
        binding.buttonDeletePackage.isVisible = overview.packageInfo != null
        binding.buttonUninstallLegacy.isVisible = false
    }

    private fun buildStatusText(overview: MigrationOverview): String {
        val statusLabel = getString(
            when (overview.stage) {
                MigrationStage.NOT_STARTED -> R.string.migration_center_status_not_started
                MigrationStage.EXPORT_READY -> R.string.migration_center_status_export_ready
                MigrationStage.IMPORTED -> R.string.migration_center_status_imported
            }
        )
        val lines = mutableListOf(statusLabel)
        when (overview.stage) {
            MigrationStage.IMPORTED -> {
                lines += getString(R.string.migration_center_current_data_saved)
                lines += getString(R.string.migration_center_downloads_retained)
                if (overview.exportEnabled) {
                    lines += getString(R.string.migration_center_export_after_import)
                }
            }

            MigrationStage.EXPORT_READY -> {
                lines += getString(R.string.migration_center_package_ready_hint)
            }

            MigrationStage.NOT_STARTED -> {
                lines += getString(
                    when {
                        overview.exportEnabled && overview.importEnabled ->
                            R.string.migration_center_capability_import_export

                        overview.exportEnabled ->
                            R.string.migration_center_capability_export_only

                        else ->
                            R.string.migration_center_capability_import_only
                    }
                )
            }
        }
        return lines.joinToString("\n")
    }

    private fun buildPackageText(overview: MigrationOverview): String {
        val packageInfo = overview.packageInfo ?: return getString(R.string.migration_center_no_package)
        val readableSize = FileUtil.getFileSizeReadable(packageInfo.sizeBytes.toDouble())
        return listOf(
            getString(R.string.migration_center_package_path, packageInfo.displayPath),
            getString(R.string.migration_center_package_size, readableSize)
        ).joinToString("\n")
    }

    private fun buildPrivateVideosText(overview: MigrationOverview): String {
        val summary = overview.privateVideoSummary
        if (!summary.hasPrivateVideos) {
            return getString(R.string.migration_center_private_videos_none)
        }
        return getString(
            R.string.migration_center_private_videos_summary,
            summary.count,
            FileUtil.getFileSizeReadable(summary.totalBytes.toDouble())
        )
    }

    private fun buildReportText(report: MigrationReport): String {
        val generatedAt = DateFormat.getDateTimeInstance().format(report.generatedAtEpochMs)
        val lines = mutableListOf(
            getString(R.string.migration_center_report_generated, generatedAt),
            getString(
                R.string.migration_center_report_counts,
                report.bookmarkCount,
                report.historyCount,
                report.videoCount,
                report.progressCount,
                report.browserSessionCount,
                report.thumbnailCount
            ),
            getString(
                R.string.migration_center_report_private,
                report.privateVideoCount,
                FileUtil.getFileSizeReadable(report.privateVideoBytes.toDouble())
            ),
            getString(
                R.string.migration_center_report_cookies,
                report.cookieProfileCount,
                if (report.cookieContentIncluded) {
                    getString(R.string.migration_center_cookie_content_included)
                } else {
                    getString(R.string.migration_center_cookie_content_excluded)
                }
            )
        )
        report.packageInfo?.let { info ->
            lines += getString(R.string.migration_center_report_package, info.displayPath)
        }
        return lines.joinToString("\n")
    }

    private fun importFromAutoOrPicker() {
        val packageInfo = viewModel.overview.value?.packageInfo
        if (packageInfo != null) {
            viewModel.importMigrationPackage(Uri.parse(packageInfo.uriString))
        } else {
            openMigrationPackagePicker()
        }
    }

    private fun openMigrationPackagePicker() {
        pickMigrationPackageLauncher.launch(arrayOf("application/zip"))
    }
}
