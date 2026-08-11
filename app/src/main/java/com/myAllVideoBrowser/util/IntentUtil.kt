package com.myAllVideoBrowser.util

//import com.allVideoDownloaderXmaster.OpenForTesting

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import com.myAllVideoBrowser.R
import java.io.File
import javax.inject.Inject

//@OpenForTesting
class IntentUtil @Inject constructor(private val fileUtil: FileUtil) {

    fun openVideoFolder(context: Context?, path: String) {
        context?.let {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val photoURI = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider",
                File(path)
            )

            intent.setDataAndType(photoURI, DocumentsContract.Document.MIME_TYPE_DIR)

            if (intent.resolveActivity(it.packageManager) != null) {
                it.startActivity(intent)
            } else {
                Toast.makeText(
                    it,
                    it.getString(R.string.settings_message_open_folder),
                    Toast.LENGTH_SHORT
                )
                    .show()
            }
        }
    }

    fun shareVideo(context: Context, uri: Uri) {
        val sharedUri = if (fileUtil.isFileApiSupportedByUri(context, uri)) {
            FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider",
                uri.toFile()
            )
        } else {
            uri
        }
        val mimeType = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.', "").orEmpty())
            ?: "video/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            clipData = ClipData.newRawUri("shared_video", sharedUri)
            putExtra(Intent.EXTRA_STREAM, sharedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivityInfo(context.packageManager, 0) != null) {
            context.startActivity(Intent.createChooser(intent, "Share via:"))
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.video_share_message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
