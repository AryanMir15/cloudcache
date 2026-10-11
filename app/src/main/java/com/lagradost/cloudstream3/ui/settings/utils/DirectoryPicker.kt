package com.lagradost.cloudstream3.ui.settings.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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
            // resolved by SafeFile — ask the provider what the folder actually
            // is (path/display name) so the app gets a real directory.
            filePath = resolveTreeToPath(context, uri)
        }
        println("Selected URI path: $uri - Full path: $filePath")

        // store the actual URI instead of the path due to permissions.
        // filePath should only be used for cosmetic purposes.
        dirSelected(uri, filePath)
    }

/**
 * Resolves a SAF tree document id to a filesystem path on primary storage.
 * Path-form ids (primary:/raw:) map directly. Opaque ids (msd: etc.) are
 * database row ids, NOT paths — string surgery on them produces garbage, so
 * we query the provider for the document's real path/name instead.
 */
private fun resolveTreeToPath(context: Context, uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val (root, rel) = docId.split(':', limit = 2)
        val relPath = rel.trimEnd('/')

        when (root) {
            // external storage root: primary:Download/Anime → /storage/emulated/0/Download/Anime
            "primary" -> pathFromPrimary(relPath)
            // AOSP-style Downloads provider: raw ids already carry the real path
            "raw" -> relPath.takeIf { it.isNotEmpty() }
            else -> resolveOpaque(context, uri, docId)
        }
    } catch (e: Exception) {
        null
    }
}

private fun pathFromPrimary(rel: String): String {
    val base = Environment.getExternalStorageDirectory().absolutePath
    return if (rel.isEmpty()) base else "$base/$rel"
}

/** Queries the provider for what an opaque tree root actually is. */
private fun resolveOpaque(context: Context, uri: Uri, docId: String): String? {
    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)

    // Some providers hand back a path-form document id once queried, even
    // when the tree id itself was opaque (e.g. raw:/… or primary:…).
    queryColumn(context, docUri, DocumentsContract.Document.COLUMN_DOCUMENT_ID)?.let { nested ->
        val (r, p) = nested.split(':', limit = 2)
        when (r) {
            "raw" -> p.trimEnd('/').takeIf { it.isNotEmpty() }?.let { return it }
            "primary" -> return pathFromPrimary(p.trimEnd('/'))
        }
    }

    val downloads = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
    ).absolutePath
    val name = queryColumn(context, docUri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    // MediaStore fallback (API 29+): the folder's true relative path from the
    // files it contains (handles nested folders like Download/Sub/Anime).
    // RELATIVE_PATH is relative to the storage root (e.g. "Download/Anime/").
    if (name != null && Build.VERSION.SDK_INT >= 29) {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.RELATIVE_PATH),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("Download/$name/%"),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.trim('/')?.takeIf { it.isNotEmpty() }?.let { rel ->
                    return "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
                }
            }
        }
    }

    // Last resort: the folder name directly under Downloads.
    name?.takeIf { it.isNotEmpty() }?.let { return "$downloads/$it" }
    return null
}

private fun queryColumn(context: Context, uri: Uri, column: String): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(column)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}