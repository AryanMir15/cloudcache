package com.lagradost.cloudstream3.ui.download

import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.DownloadFileGenerator
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.generateUniqueLocalId
import com.lagradost.cloudstream3.ui.player.scanFolderForEpisodes
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.SnackbarHelper.showSnackbar
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.MainScope

object DownloadButtonSetup {
    fun handleDownloadClick(click: DownloadClickEvent) {
        val id = click.data.id
        when (click.action) {
            DOWNLOAD_ACTION_DELETE_FILE -> {
                activity?.let { ctx ->
                    val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                    val dialogClickListener =
                        DialogInterface.OnClickListener { _, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> {
                                    VideoDownloadManager.deleteFilesAndUpdateSettings(
                                        ctx,
                                        setOf(id),
                                        MainScope()
                                    )
                                }

                                DialogInterface.BUTTON_NEGATIVE -> {
                                    // Do nothing on cancel
                                }
                            }
                        }

                    try {
                        builder.setTitle(R.string.delete_file)
                            .setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getNameFull(
                                        click.data.name,
                                        click.data.episode,
                                        click.data.season
                                    )
                                )
                            )
                            .setPositiveButton(R.string.delete, dialogClickListener)
                            .setNegativeButton(R.string.cancel, dialogClickListener)
                            .show().setDefaultFocus()
                    } catch (e: Exception) {
                        logError(e)
                        // ye you somehow fucked up formatting did you?
                    }
                }
            }

            DOWNLOAD_ACTION_PAUSE_DOWNLOAD -> {
                VideoDownloadManager.downloadEvent.invoke(
                    Pair(click.data.id, VideoDownloadManager.DownloadActionType.Pause)
                )
            }

            DOWNLOAD_ACTION_RESUME_DOWNLOAD -> {
                activity?.let { ctx ->
                    if (VideoDownloadManager.downloadStatus.containsKey(id) && VideoDownloadManager.downloadStatus[id] == VideoDownloadManager.DownloadType.IsPaused) {
                        VideoDownloadManager.downloadEvent.invoke(
                            Pair(click.data.id, VideoDownloadManager.DownloadActionType.Resume)
                        )
                    } else {
                        val pkg = VideoDownloadManager.getDownloadResumePackage(ctx, id)
                        if (pkg != null) {
                            DownloadQueueManager.addToQueue(pkg.toWrapper())
                        } else {
                            VideoDownloadManager.downloadEvent.invoke(
                                Pair(click.data.id, VideoDownloadManager.DownloadActionType.Resume)
                            )
                        }
                    }
                }
            }

            DOWNLOAD_ACTION_LONG_CLICK -> {
                activity?.let { act ->
                    val length =
                        VideoDownloadManager.getDownloadFileInfo(
                            act,
                            click.data.id
                        )?.fileLength
                            ?: 0
                    if (length > 0) {
                        showSnackbar(
                            act,
                            R.string.offline_file,
                            Snackbar.LENGTH_LONG
                        )
                    }
                }
            }

            DOWNLOAD_ACTION_CANCEL_PENDING -> {
                DownloadQueueManager.cancelDownload(id)
            }

            DOWNLOAD_ACTION_PLAY_FILE -> {
                activity?.let { act ->
                    val parent = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        click.data.parentId.toString()
                    ) ?: return

                    // Update the clicked episode cache with proper name from API if available
                    // This ensures the episode title is preserved for the player sidebar
                    if (click.data.name != null) {
                        val existingEpisode = getKey<DownloadObjects.DownloadEpisodeCached>(
                            DOWNLOAD_EPISODE_CACHE,
                            click.data.id.toString()
                        )
                        if (existingEpisode?.name == null) {
                            android.util.Log.d("LocalLibraryTest", "Updating episode ${click.data.id} cache with name: ${click.data.name}")
                            setKey(
                                DOWNLOAD_EPISODE_CACHE,
                                click.data.id.toString(),
                                click.data.copy(cacheTime = System.currentTimeMillis())
                            )
                        }
                    }

                    // 1. Load cached episodes for this show
                    val cachedEpisodes = getKeys(DOWNLOAD_EPISODE_CACHE)
                        ?.mapNotNull { getKey<DownloadObjects.DownloadEpisodeCached>(it) }
                        ?.filter { it.parentId == click.data.parentId }
                        ?: emptyList()

                    // Map for quick lookup by episode number
                    val cachedByEpisode = cachedEpisodes.associateBy { it.episode }
                    val existingIds = cachedEpisodes.map { it.id }.toSet()

                    android.util.Log.d("DownloadButtonSetup", "Found ${cachedEpisodes.size} cached episodes")

                    // 2. Get folder path from first cached episode's file info
                    // This is the proven pattern used in existing code
                    val sampleFileInfo = cachedEpisodes.firstOrNull()?.let { ep ->
                        getKey<DownloadObjects.DownloadedFileInfo>(
                            VideoDownloadManager.KEY_DOWNLOAD_INFO,
                            ep.id.toString()
                        )
                    }

                    val basePath = sampleFileInfo?.basePath
                    val relativePath = sampleFileInfo?.relativePath

                    android.util.Log.d("DownloadButtonSetup", "Base path: $basePath, Relative: $relativePath")

                    // 3. Build complete episode list
                    val allEpisodes = mutableListOf<ExtractorUri>()

                    // Add cached episodes first
                    cachedEpisodes.forEach { ep ->
                        val fileInfo = getKey<DownloadObjects.DownloadedFileInfo>(
                            VideoDownloadManager.KEY_DOWNLOAD_INFO,
                            ep.id.toString()
                        )

                        if (fileInfo != null) {
                            allEpisodes.add(
                                ExtractorUri(
                                    uri = Uri.EMPTY,  // Will be resolved in generateLinks
                                    name = ep.name ?: "Episode ${ep.episode}",
                                    basePath = fileInfo.basePath,
                                    relativePath = fileInfo.relativePath,
                                    displayName = fileInfo.displayName,
                                    id = ep.id,
                                    parentId = ep.parentId,
                                    episode = ep.episode,
                                    season = ep.season,
                                    headerName = parent.name,
                                    tvType = parent.type
                                )
                            )
                        }
                    }

                    // 4. Scan folder for additional episodes (if we have path info)
                    if (basePath != null && relativePath != null) {
                        val scannedEpisodes = scanFolderForEpisodes(
                            act,
                            basePath,
                            relativePath,
                            cachedByEpisode.keys  // Pass existing episode numbers to skip
                        )

                        android.util.Log.d("DownloadButtonSetup", "Found ${scannedEpisodes.size} additional episodes in folder")

                        scannedEpisodes.forEach { scanned ->
                            // Generate unique ID for this episode
                            val newId = generateUniqueLocalId(
                                scanned.episodeNumber,
                                click.data.parentId,
                                existingIds
                            )

                            // Cache the new episode for future use
                            val newCachedEp = DownloadObjects.DownloadEpisodeCached(
                                name = scanned.fileName,
                                episode = scanned.episodeNumber,
                                id = newId,
                                parentId = click.data.parentId,
                                poster = null,
                                season = null,
                                score = null,
                                description = null,
                                date = null,
                                cacheTime = System.currentTimeMillis(),
                                data = null
                            )
                            setKey(DOWNLOAD_EPISODE_CACHE, newId.toString(), newCachedEp)

                            // Cache file info for playback
                            val fileInfo = DownloadObjects.DownloadedFileInfo(
                                totalBytes = if (scanned.actualVideoUri.toString().startsWith("content://")) -1 else 0,
                                relativePath = relativePath,  // Use the same relativePath as existing episodes
                                displayName = scanned.fileName,
                                basePath = basePath,
                                linkHash = 0,
                                extraInfo = scanned.actualVideoUri.toString()
                            )
                            setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, newId.toString(), fileInfo)

                            // Set download status for UI
                            VideoDownloadManager.downloadStatus[newId] = VideoDownloadManager.DownloadType.IsDone

                            // Add to generator list
                            allEpisodes.add(
                                ExtractorUri(
                                    uri = Uri.EMPTY,  // Will be resolved in generateLinks
                                    name = scanned.fileName,
                                    basePath = basePath,
                                    relativePath = relativePath,  // Use the same relativePath as existing episodes
                                    displayName = scanned.fileName,
                                    id = newId,
                                    parentId = click.data.parentId,
                                    episode = scanned.episodeNumber,
                                    season = null,
                                    headerName = parent.name,
                                    tvType = parent.type
                                )
                            )

                            android.util.Log.d("DownloadButtonSetup", "Added scanned episode ${scanned.episodeNumber} with ID $newId")
                        }
                    } else {
                        android.util.Log.w("DownloadButtonSetup", "No path info available, using cached episodes only")
                    }

                    // 5. Sort and create generator
                    val sortedEpisodes = allEpisodes.sortedBy { it.episode ?: 0 }

                    if (sortedEpisodes.isEmpty()) {
                        android.util.Log.e("DownloadButtonSetup", "No episodes found at all!")
                        // Could show error toast here
                        return
                    }

                    // Find target index
                    val targetIndex = sortedEpisodes.indexOfFirst { it.id == click.data.id }
                    val finalIndex = if (targetIndex >= 0) targetIndex else 0

                    android.util.Log.d("DownloadButtonSetup", "Creating generator with ${sortedEpisodes.size} episodes")
                    android.util.Log.d("DownloadButtonSetup", "Target episode index: $finalIndex (id=${click.data.id})")

                    act.navigate(
                        R.id.global_to_navigation_player,
                        GeneratorPlayer.newInstance(
                            DownloadFileGenerator(sortedEpisodes).apply { goto(finalIndex) }
                        )
                    )
                }
            }
        }
    }
}