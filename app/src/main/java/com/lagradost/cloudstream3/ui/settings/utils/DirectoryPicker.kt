package com.lagradost.cloudstream3.ui.settings.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.safefile.SafeFile

fun Fragment.getChooseFolderLauncher(dirSelected: (uri: Uri?, path: String?) -> Unit) =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // It lies, it can be null if file manager quits.
        if (uri == null) return@registerForActivityResult
        val context = context ?: CloudStreamApp.context ?: return@registerForActivityResult
        // RW perms for the path
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        context.contentResolver.takePersistableUriPermission(uri, flags)

        var filePath = SafeFile.fromUri(context, uri)?.filePath()
        if (filePath.isNullOrBlank()) {
            // Some providers (e.g. OPPO's msd: Downloads provider) aren't
            // resolved by SafeFile — map the tree id to a real path so the app
            // gets a simple directory instead of a content:// URI.
            filePath = resolveTreeToPath(context, uri)
        }
        println("Selected URI path: $uri - Full path: $filePath")

        // store the actual URI instead of the path due to permissions.
        // filePath should only be used for cosmetic purposes.
        dirSelected(uri, filePath)
    }

/** Maps a SAF tree document id to a filesystem path on the primary storage. */
private fun resolveTreeToPath(context: Context, uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val (root, rel) = docId.split(':', limit = 2)
        val relPath = rel.trimEnd('/')
        val base = when (root) {
            // external storage root: primary:Download/Anime → /storage/emulated/0/Download/Anime
            "primary" -> "/storage/emulated/0"
            // MediaStore Downloads provider (some devices, e.g. OPPO's msd:):
            // ids are paths relative to the Downloads collection
            "msd" -> {
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ).absolutePath
                val stripped = relPath.removePrefix("primary:").removePrefix("Download/")
                if (stripped.isEmpty()) downloads else "$downloads/$stripped"
            }
            else -> return null
        }
        if (relPath.isEmpty()) base else "$base/$relPath"
    } catch (e: Exception) {
        null
    }
}