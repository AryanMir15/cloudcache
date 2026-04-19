package com.lagradost.cloudstream3.ui.result

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.discord.panels.OverlappingPanelsLayout
import com.discord.panels.PanelState
import com.discord.panels.PanelsChildGestureRegionObserver
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.databinding.FragmentResultBinding
import com.lagradost.cloudstream3.databinding.FragmentResultSwipeBinding
import com.lagradost.cloudstream3.databinding.MetadataPreviewDialogBinding
import com.lagradost.cloudstream3.databinding.ResultRecommendationsBinding
import com.lagradost.cloudstream3.databinding.ResultSyncBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_SHARE
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_DOWNLOAD
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_LONG_CLICK
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.source_priority.QualityProfileDialog
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.ui.result.ResultFragment.updateUIEvent
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.isCastApiAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.loadCache
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppContextUtils.updateHasTrailers
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker.openBatteryOptimizationSettings
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogInstant
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage
import com.lagradost.cloudstream3.utils.UIHelper.populateChips
import com.lagradost.cloudstream3.utils.UIHelper.popupMenuNoIconsAndNoStringRes
import com.lagradost.cloudstream3.utils.UIHelper.setListViewHeightBasedOnItems
import com.lagradost.cloudstream3.utils.UIHelper.setNavigationBarColorCompat
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.setTextHtml
import com.lagradost.cloudstream3.utils.txt
import java.net.URLEncoder
import kotlin.math.roundToInt

open class ResultFragmentPhone : FullScreenPlayer() {
    private val gestureRegionsListener =
        object : PanelsChildGestureRegionObserver.GestureRegionsListener {
            override fun onGestureRegionsUpdate(gestureRegions: List<Rect>) {
                binding?.resultOverlappingPanels?.setChildGestureRegions(gestureRegions)
            }
        }

    protected lateinit var viewModel: ResultViewModel2
    protected lateinit var syncModel: SyncViewModel

    protected var binding: FragmentResultSwipeBinding? = null
    protected var resultBinding: FragmentResultBinding? = null
    protected var recommendationBinding: ResultRecommendationsBinding? = null
    protected var syncBinding: ResultSyncBinding? = null

    override var layout = R.layout.fragment_result_swipe

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        viewModel = ViewModelProvider(this)[ResultViewModel2::class.java]
        syncModel = ViewModelProvider(this)[SyncViewModel::class.java]
        updateUIEvent += ::updateUI

        val root = super.onCreateView(inflater, container, savedInstanceState) ?: return null
        FragmentResultSwipeBinding.bind(root).let { bind ->
            resultBinding = bind.fragmentResult
            recommendationBinding = bind.resultRecommendations
            syncBinding = bind.resultSync
            binding = bind
        }

        return root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PanelsChildGestureRegionObserver.Provider.get().apply {
            resultBinding?.resultCastItems?.let { register(it) }
        }
    }

    var currentTrailers: List<Pair<ExtractorLink, String>> = emptyList()
    var currentTrailerIndex = 0

    override fun nextMirror() {
        currentTrailerIndex++
        loadTrailer()
    }

    override fun hasNextMirror(): Boolean {
        return currentTrailerIndex + 1 < currentTrailers.size
    }

    override fun playerError(exception: Throwable) {
        if (player.getIsPlaying()) { // because we don't want random toasts in player
            super.playerError(exception)
        } else {
            nextMirror()
        }
    }

    private fun loadTrailer(index: Int? = null) {

        val isSuccess =
            currentTrailers.getOrNull(index ?: currentTrailerIndex)
                ?.let { (extractedTrailerLink, _) ->
                    context?.let { ctx ->
                        player.onPause()
                        player.loadPlayer(
                            ctx,
                            false,
                            extractedTrailerLink,
                            null,
                            startPosition = 0L,
                            subtitles = emptySet(),
                            subtitle = null,
                            autoPlay = false,
                            preview = false
                        )
                        true
                    } ?: run {
                        false
                    }
                } ?: run {
                false
            }
        //result_trailer_thumbnail?.setImageBitmap(result_poster_background?.drawable?.toBitmap())


        // result_trailer_loading?.isVisible = isSuccess
        val turnVis = !isSuccess && !isFullScreenPlayer
        resultBinding?.apply {
            // If we load a trailer, then cancel the big logo and only show the small title
            if (isSuccess) {
                // This is still a bit of a race condition, but it should work if we have the
                // trailers observe after the page observe!
                bindLogo(
                    url = null,
                    headers = null,
                    logoView = backgroundPosterWatermarkBadge,
                    titleView = resultTitle
                )
            }
            resultSmallscreenHolder.isVisible = turnVis
            resultPosterBackgroundHolder.apply {
                val fadeIn: Animation = AlphaAnimation(alpha, if (turnVis) 1.0f else 0.0f).apply {
                    interpolator = DecelerateInterpolator()
                    duration = 200
                    fillAfter = true
                }
                clearAnimation()
                startAnimation(fadeIn)
            }

            // We don't want the trailer to be focusable if it's not visible
            resultSmallscreenHolder.descendantFocusability = if (isSuccess) {
                ViewGroup.FOCUS_AFTER_DESCENDANTS
            } else {
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            binding?.resultFullscreenHolder?.isVisible = !isSuccess && isFullScreenPlayer
        }
        //player_view?.apply {
        //alpha = 0.0f
        //ObjectAnimator.ofFloat(player_view, "alpha", 1f).apply {
        //    duration = 200
        //    start()
        //}

        //val fadeIn: Animation = AlphaAnimation(0.0f, 1f).apply {
        //    interpolator = DecelerateInterpolator()
        //    duration = 2000
        //    fillAfter = true
        //}
        //startAnimation(fadeIn)
        //}
    }

    private fun setTrailers(trailers: List<Pair<ExtractorLink, String>>?) {
        context?.updateHasTrailers()
        if (!LoadResponse.isTrailersEnabled) return
        currentTrailers = trailers?.sortedBy { -it.first.quality } ?: emptyList()
        loadTrailer()
    }

    override fun onDestroyView() {
        PanelsChildGestureRegionObserver.Provider.get().let { obs ->
            resultBinding?.resultCastItems?.let {
                obs.unregister(it)
            }

            obs.removeGestureRegionsUpdateListener(gestureRegionsListener)
        }

        updateUIEvent -= ::updateUI
        binding = null
        resultBinding?.resultScroll?.setOnClickListener(null)
        resultBinding = null
        syncBinding = null
        recommendationBinding = null
        activity?.detachBackPressedCallback(this@ResultFragmentPhone.toString())
        super.onDestroyView()
    }

    var loadingDialog: Dialog? = null
    var popupDialog: Dialog? = null

    /**
     * Sets next focus to allow navigation up and down between 2 views
     * if either of them is null nothing happens.
     **/
    private fun setFocusUpAndDown(upper: View?, down: View?) {
        if (upper == null || down == null) return
        upper.nextFocusDownId = down.id
        down.nextFocusUpId = upper.id
    }

    var selectSeason: String? = null
    var selectEpisodeRange: String? = null
    var selectSort: EpisodeSortType? = null

    private fun setUrl(url: String?) {
        if (url == null) {
            binding?.resultOpenInBrowser?.isVisible = false
            return
        }

        val valid = url.startsWith("http")

        binding?.resultOpenInBrowser?.apply {
            isVisible = valid
            setOnClickListener {
                context?.openBrowser(url)
            }
        }

        binding?.resultRefreshMetadata?.setOnClickListener {
            val metaProviders = viewModel.getAvailableMetaProviders()
            if (metaProviders.isEmpty()) {
                activity?.let { showToast(it, "No providers available") }
                return@setOnClickListener
            }
            activity?.showBottomDialog(
                metaProviders,
                -1,
                "Select metadata source",
                false,
                {},
                { providerIndex ->
                    val selectedProvider = metaProviders[providerIndex]
                    openSearchForMetadata(selectedProvider)
                }
            )
        }

        resultBinding?.resultReloadConnectionOpenInBrowser?.setOnClickListener {
            view?.context?.openBrowser(url)
        }

        resultBinding?.resultMetaSite?.setOnClickListener {
            view?.context?.openBrowser(url)
        }

        // Setup pull-to-refresh
        resultBinding?.resultSwipeRefresh?.setOnRefreshListener {
            android.util.Log.d("RefreshMetadata", "Pull-to-refresh triggered")
            val storedData = getStoredData()
            if (storedData != null) {
                // Get the first available meta provider for refresh
                val metaProviders = viewModel.getAvailableMetaProviders()
                if (metaProviders.isNotEmpty()) {
                    viewModel.refreshMetadata(metaProviders.first())
                } else {
                    android.util.Log.w("RefreshMetadata", "No meta providers available for refresh")
                    resultBinding?.resultSwipeRefresh?.isRefreshing = false
                    activity?.let { showToast(it, "No providers available for refresh") }
                }
            } else {
                resultBinding?.resultSwipeRefresh?.isRefreshing = false
            }
        }

        // Observe metadata loading state
        viewModel.metadataLoading.observe(viewLifecycleOwner) { isLoading ->
            resultBinding?.resultLoading?.visibility = if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
            // Sync SwipeRefreshLayout with metadata loading state
            if (!isLoading) {
                resultBinding?.resultSwipeRefresh?.isRefreshing = false
            }
        }

        // Observe refresh errors to show toast messages
        viewModel.refreshError.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                activity?.let { showToast(it, error) }
                resultBinding?.resultSwipeRefresh?.isRefreshing = false
            }
        }

        // Show swap metadata FAB when in metadata swap mode
        android.util.Log.d("MetadataSwap", "resultSwapMetadataFab reference: ${binding?.resultSwapMetadataFab}")
        binding?.resultSwapMetadataFab?.setOnClickListener {
            android.util.Log.d("MetadataSwap", "Swap metadata FAB clicked")
            swapMetadataAndReturn()
        }

        // Undo metadata swap button
        binding?.resultUndoMetadataFab?.setOnClickListener {
            android.util.Log.d("MetadataSwap", "Undo metadata FAB clicked")
            undoMetadataSwap()
        }

        // Observe metadata swap mode to show/hide swap metadata FAB
        viewModel.isMetadataSwapMode.observe(viewLifecycleOwner) { isSwapMode ->
            val isLibraryEntry = getStoredData() != null
            android.util.Log.d("MetadataSwap", "isMetadataSwapMode changed: $isSwapMode, isLibraryEntry: $isLibraryEntry, button visibility: ${if (isSwapMode && isLibraryEntry) "VISIBLE" else "GONE"}")
            android.util.Log.d("MetadataSwap", "resultSwapMetadataFab is null: ${binding?.resultSwapMetadataFab == null}")
            binding?.resultSwapMetadataFab?.visibility = if (isSwapMode && isLibraryEntry) android.view.View.VISIBLE else android.view.View.GONE
            // Hide bookmark FAB when in metadata swap mode to prevent overlap
            binding?.resultBookmarkFab?.visibility = if (isSwapMode && isLibraryEntry) android.view.View.GONE else android.view.View.VISIBLE
        }

        // Check cache for swapped metadata to show/hide undo button
        viewModel.page.observe(viewLifecycleOwner) { response ->
            if (response is com.lagradost.cloudstream3.mvvm.Resource.Success) {
                val url = response.value.url
                val cachedHeader = com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
                    com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                    url
                )
                android.util.Log.d("MetadataSwap", "Checking cache for undo button - url: $url, hasSwappedMetadata: ${cachedHeader?.hasSwappedMetadata}")
                binding?.resultUndoMetadataFab?.visibility = if (cachedHeader?.hasSwappedMetadata == true) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun swapMetadataAndReturn() {
        android.util.Log.d("MetadataSwap", "swapMetadataAndReturn called")
        android.util.Log.d("MetadataSwap", "originalResponse: ${viewModel.originalResponse?.name}")
        android.util.Log.d("MetadataSwap", "currentResponse: ${viewModel.currentResponse?.name}")
        val currentResponse = viewModel.currentResponse ?: run {
            android.util.Log.e("MetadataSwap", "currentResponse is null")
            return
        }
        val originalResponse = viewModel.originalResponse ?: run {
            android.util.Log.e("MetadataSwap", "originalResponse is null")
            return
        }

        android.util.Log.d("MetadataSwap", "Swapping metadata from ${currentResponse.name} to ${originalResponse.name}")

        // Show field selection modal dialog
        val fieldNames = arrayOf("Plot", "Poster", "Banner", "Logo", "Actors", "Score", "Status", "Year")
        val fieldChecked = booleanArrayOf(true, true, true, true, true, true, true, true) // All fields selected by default

        val context = activity ?: return
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
        builder.setTitle("Select fields to swap")
        builder.setMultiChoiceItems(fieldNames, fieldChecked) { _, which, isChecked ->
            fieldChecked[which] = isChecked
        }
        builder.setPositiveButton("Confirm") { _, _ ->
            // Get selected fields
            val selectedFields = mutableListOf<String>()
            fieldNames.forEachIndexed { index, name ->
                if (fieldChecked[index]) {
                    selectedFields.add(name)
                }
            }

            android.util.Log.d("MetadataSwap", "Selected fields: ${selectedFields.joinToString()}")

            // Convert selected field names to MetadataField enum values
            val fieldsToSwap = selectedFields.mapNotNull { fieldName ->
                when (fieldName) {
                    "Plot" -> MetadataField.PLOT
                    "Poster" -> MetadataField.POSTER
                    "Banner" -> MetadataField.BANNER
                    "Logo" -> MetadataField.LOGO
                    "Actors" -> MetadataField.ACTORS
                    "Score" -> MetadataField.SCORE
                    "Status" -> MetadataField.STATUS
                    "Year" -> MetadataField.YEAR
                    else -> null
                }
            }.toSet()

            android.util.Log.d("MetadataSwap", "Converted to MetadataField enum: $fieldsToSwap")

            // Swap selected metadata fields - merge currentResponse metadata into originalResponse
            val swappedResponse = viewModel.swapAllMetadata(originalResponse, currentResponse, fieldsToSwap)
            android.util.Log.d("MetadataSwap", "Swapped response: ${swappedResponse.name}")

            // Use new MetadataSwapManager if feature flag is enabled
            if (com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
                val cacheKey = com.lagradost.cloudstream3.ui.result.cache.CacheCoordinator.resolveKey(originalResponse.url, originalResponse.getId())
                android.util.Log.d("MetadataSwap", "Using new MetadataSwapManager - cacheKey: $cacheKey")
                
                lifecycleScope.launch {
                    com.lagradost.cloudstream3.ui.result.swap.MetadataSwapManager.recordSwap(
                        context = context,
                        entryKey = cacheKey,
                        originalResponse = originalResponse,
                        swapSourceResponse = currentResponse,
                        fieldsToSwap = fieldsToSwap
                    )
                    android.util.Log.d("MetadataSwap", "Swap state recorded in MetadataSwapManager")
                    
                    // Update cache using CacheCoordinator
                    val cachedHeader = com.lagradost.cloudstream3.ui.result.cache.CacheCoordinator.getHeaderCached(cacheKey)
                    val updatedCache = com.lagradost.cloudstream3.ui.result.cache.CacheCoordinator.mergeCacheEntry(
                        existing = cachedHeader,
                        newData = swappedResponse,
                        scenario = com.lagradost.cloudstream3.ui.result.cache.MergeScenario.SWAP_APPLY,
                        swapState = com.lagradost.cloudstream3.ui.result.swap.SwapState.create(cacheKey, originalResponse, fieldsToSwap)
                    )
                    com.lagradost.cloudstream3.CloudStreamApp.setKey(
                        com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                        cacheKey,
                        updatedCache
                    )
                    android.util.Log.d("MetadataSwap", "Cache updated with swapped metadata")
                }
            } else {
                // Old static variable approach
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse = swappedResponse
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap = fieldsToSwap
                android.util.Log.d("MetadataSwap", "Stored swapped response in sharedSwappedResponse (old system)")
            }

            // Update the original response with swapped metadata
            viewModel.currentResponse = swappedResponse
            viewModel.originalResponse = swappedResponse

            // Reset metadata swap mode and clear static variables
            viewModel.setMetadataSwapMode(false)
            viewModel.originalResponse = null
            if (!com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse = null
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive = false
            }

            // Return to original entry by going back twice (past QuickSearchFragment)
            context.onBackPressed()
            context.onBackPressed()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    private fun undoMetadataSwap() {
        android.util.Log.d("MetadataSwap", "undoMetadataSwap called")
        val storedData = getStoredData() ?: run {
            android.util.Log.e("MetadataSwap", "undoMetadataSwap - storedData is null")
            return
        }
        val cacheKey = storedData.url

        // Use new MetadataSwapManager if feature flag is enabled
        if (com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
            android.util.Log.d("MetadataSwap", "Using new MetadataSwapManager for undo")
            val resolvedCacheKey = com.lagradost.cloudstream3.ui.result.cache.CacheCoordinator.resolveKey(cacheKey, null)
            
            lifecycleScope.launch {
                com.lagradost.cloudstream3.ui.result.swap.MetadataSwapManager.undoSwap(context ?: return@launch, resolvedCacheKey)
                android.util.Log.d("MetadataSwap", "Swap undone via MetadataSwapManager")
                
                // Reload from provider to refresh the view with original metadata
                viewModel.load(
                    activity,
                    storedData.url,
                    storedData.apiName,
                    storedData.showFillers,
                    storedData.dubStatus,
                    storedData.start
                )
                android.util.Log.d("MetadataSwap", "Reloaded from provider after undo")
            }
            return
        }
        
        // Old approach: Restore original metadata from cache
        val cachedHeader = com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
            com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
            cacheKey
        )
        if (cachedHeader != null && cachedHeader.hasSwappedMetadata == true) {
            // Restore original values from the original* fields
            com.lagradost.cloudstream3.CloudStreamApp.setKey(
                com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                cacheKey,
                cachedHeader.copy(
                    poster = cachedHeader.originalPoster ?: cachedHeader.poster,
                    backgroundPosterUrl = cachedHeader.originalBanner ?: cachedHeader.backgroundPosterUrl,
                    logoUrl = cachedHeader.originalLogo ?: cachedHeader.logoUrl,
                    plot = cachedHeader.originalPlot ?: cachedHeader.plot,
                    actors = cachedHeader.originalActors ?: cachedHeader.actors,
                    score = cachedHeader.originalScore ?: cachedHeader.score,
                    year = cachedHeader.originalYear ?: cachedHeader.year,
                    showStatus = cachedHeader.originalShowStatus ?: cachedHeader.showStatus,
                    hasCustomPoster = false,
                    hasSwappedMetadata = false,
                    swappedFields = emptySet()
                )
            )
            android.util.Log.d("MetadataSwap", "undoMetadataSwap - Restored original metadata from cache")
        }

        // Force reload from provider to refresh the view with original metadata
        activity?.let { context ->
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        }
        android.util.Log.d("MetadataSwap", "undoMetadataSwap - Reloaded from provider")
    }

    private fun openSearchForMetadata(providerName: String) {
        android.util.Log.d("MetadataSwap", "openSearchForMetadata called with provider: $providerName")
        val currentResponse = viewModel.currentResponse ?: run {
            android.util.Log.e("MetadataSwap", "openSearchForMetadata - currentResponse is null")
            return
        }
        val currentName = currentResponse.name
        android.util.Log.d("MetadataSwap", "openSearchForMetadata - currentResponse: $currentName, provider: $providerName")

        android.util.Log.d("MetadataSwap", "openSearchForMetadata called - storing original response: ${currentResponse.name}")

        // Store original response in static variable BEFORE opening QuickSearchFragment
        viewModel.originalResponse = currentResponse
        com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse = currentResponse
        android.util.Log.d("MetadataSwap", "openSearchForMetadata - stored originalResponse in viewModel.originalResponse and sharedOriginalResponse")

        // Set static flag to indicate metadata swap is active
        com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive = true
        android.util.Log.d("MetadataSwap", "Set isMetadataSwapActive = true, sharedOriginalResponse = ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.name}")

        // Open QuickSearchFragment with provider pre-selected and title pre-filled
        com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment.pushSearch(
            activity,
            autoSearch = currentName,
            providers = arrayOf(providerName)
        )

        // Set up callback to handle search result selection
        com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment.clickCallback = { callback ->
            android.util.Log.d("MetadataSwap", "QuickSearchFragment callback received - action: ${callback.action}")
            if (callback.action == com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD) {
                // Open entry with metadata swap flag
                openEntryForMetadataSwap(callback.card, providerName)
            }
        }
    }

    private fun openEntryForMetadataSwap(
        searchResult: com.lagradost.cloudstream3.SearchResponse,
        providerName: String
    ) {
        android.util.Log.d("MetadataSwap", "openEntryForMetadataSwap called - searchResult: ${searchResult.name}, provider: $providerName")
        android.util.Log.d("MetadataSwap", "openEntryForMetadataSwap - viewModel.currentResponse: ${viewModel.currentResponse?.name}")
        // Store original response in static variable for swapping back
        viewModel.originalResponse = viewModel.currentResponse
        com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse = viewModel.currentResponse
        android.util.Log.d("MetadataSwap", "openEntryForMetadataSwap - stored viewModel.currentResponse in viewModel.originalResponse and sharedOriginalResponse")

        // Open the entry with metadata swap flag
        android.util.Log.d("MetadataSwap", "openEntryForMetadataSwap - calling loadSearchResult with metadataSwap=true")
        com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult(searchResult, metadataSwap = true)
    }

    private fun showMetadataPreview(providerName: String, metadata: LoadResponse) {
        val binding = com.lagradost.cloudstream3.databinding.MetadataPreviewDialogBinding.inflate(
            LayoutInflater.from(activity)
        )

        val dialog = BottomSheetDialog(activity ?: return, R.style.AlertDialogCustom)
        dialog.setContentView(binding.root)
        dialog.show()

        // Populate poster card
        binding.posterCard.imageView.loadImage(metadata.posterUrl)
        binding.posterCard.imageText.text = metadata.name

        // Show rating if available
        binding.posterCard.textRating.text = metadata.score?.toString() ?: ""
        binding.posterCard.textRating.visibility = if (metadata.score != null) android.view.View.VISIBLE else android.view.View.GONE

        // Hide other elements not needed for preview
        binding.posterCard.watchProgressContainer.visibility = android.view.View.GONE
        binding.posterCard.textQuality.visibility = android.view.View.GONE
        binding.posterCard.textIsDub.visibility = android.view.View.GONE
        binding.posterCard.textIsSub.visibility = android.view.View.GONE
        binding.posterCard.textFlag.visibility = android.view.View.GONE
        binding.posterCard.episodeText.visibility = android.view.View.GONE

        // Click on card to load entry page
        binding.posterCard.root.setOnClickListener {
            dialog.dismiss()
            // Load the entry from the provider and show the normal page
            val url = metadata.url
            if (url != null) {
                val storedData = getStoredData()
                viewModel.load(
                    activity,
                    url,
                    storedData?.apiName ?: "",
                    storedData?.showFillers ?: false,
                    storedData?.dubStatus ?: DubStatus.Dubbed,
                    storedData?.start
                )
            }
        }

        // Button handlers
        binding.closeBtt.setOnClickListener {
            dialog.dismiss()
        }

        binding.swapBtt.setOnClickListener {
            dialog.dismiss()
            // Open search UI with provider pre-selected and name pre-filled
            openSearchForMetadata(providerName)
        }

        binding.editSearchBtt.setOnClickListener {
            dialog.dismiss()
            openSearchForMetadata(providerName)
        }
    }

    private fun reloadViewModel(forceReload: Boolean) {
        if (!viewModel.hasLoaded() || forceReload) {
            val storedData = getStoredData() ?: return
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        }
    }

    override fun onResume() {
        afterPluginsLoadedEvent += ::reloadViewModel
        activity?.setNavigationBarColorCompat(R.attr.primaryBlackBackground)
        super.onResume()
        PanelsChildGestureRegionObserver.Provider.get()
            .addGestureRegionsUpdateListener(gestureRegionsListener)
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::reloadViewModel
        super.onStop()
    }

    private fun updateUI(id: Int?) {
        syncModel.updateUserData()
        viewModel.reloadEpisodes()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.let { fixSystemBarsPadding(it) }
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ===== setup =====
        fixSystemBarsPadding(view)
        val storedData = getStoredData() ?: return

        android.util.Log.d("MetadataSwap", "===== COMPREHENSIVE DEBUG START =====")
        android.util.Log.d("MetadataSwap", "storedData.name: ${storedData.name}")
        android.util.Log.d("MetadataSwap", "storedData.url: ${storedData.url}")
        android.util.Log.d("MetadataSwap", "storedData.apiName: ${storedData.apiName}")

        // Check if this is a metadata swap navigation (from bundle or static flag)
        val isMetadataSwapFromBundle = arguments?.getBoolean(com.lagradost.cloudstream3.ui.result.ResultFragment.METADATA_SWAP_BUNDLE) ?: false
        val isMetadataSwapFromStatic = com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive
        val isMetadataSwap = isMetadataSwapFromBundle || isMetadataSwapFromStatic
        android.util.Log.d("MetadataSwap", "onViewCreated - isMetadataSwap from bundle: $isMetadataSwapFromBundle, from static: $isMetadataSwapFromStatic")
        android.util.Log.d("MetadataSwap", "onViewCreated - sharedOriginalResponse: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.name}")
        android.util.Log.d("MetadataSwap", "onViewCreated - sharedOriginalResponse.url: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.url}")
        android.util.Log.d("MetadataSwap", "onViewCreated - sharedSwappedResponse: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse?.name}")
        android.util.Log.d("MetadataSwap", "onViewCreated - sharedSwappedResponse.url: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse?.url}")
        if (isMetadataSwap) {
            viewModel.setMetadataSwapMode(true)
            viewModel.originalResponse = com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse
            android.util.Log.d("MetadataSwap", "onViewCreated - Set isMetadataSwapMode to true, originalResponse: ${viewModel.originalResponse?.name}")
            android.util.Log.d("MetadataSwap", "onViewCreated - viewModel.originalResponse.url: ${viewModel.originalResponse?.url}")
            // Don't clear isMetadataSwapActive here - it should persist while navigating between search results
        }

        // Check if there's a swapped response available (from metadata swap completion)
        var swappedResponse = com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse
        var fieldsToSwap = com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap ?: emptySet()
        var skipNormalLoad = false
        
        // Use new MetadataSwapManager if feature flag is enabled
        if (com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
            val cacheKey = com.lagradost.cloudstream3.ui.result.cache.CacheCoordinator.resolveKey(storedData.url, null)
            android.util.Log.d("MetadataSwap", "onViewCreated - Using new MetadataSwapManager - cacheKey: $cacheKey")
            
            lifecycleScope.launch {
                val isSwapped = com.lagradost.cloudstream3.ui.result.swap.MetadataSwapManager.isSwapped(context ?: return@launch, cacheKey)
                android.util.Log.d("MetadataSwap", "onViewCreated - isSwapped: $isSwapped")
                
                if (isSwapped) {
                    // Let the normal load flow handle swap metadata application via ResultViewModel2
                    // The ViewModel will check MetadataSwapManager and apply metadata if needed
                    android.util.Log.d("MetadataSwap", "onViewCreated - Swap active, letting ViewModel handle metadata application")
                }
            }
        }
        
        android.util.Log.d("MetadataSwap", "onViewCreated - sharedSwappedResponse: ${swappedResponse?.name}")
        android.util.Log.d("MetadataSwap", "onViewCreated - sharedSwappedResponse.url: ${swappedResponse?.url}")
        android.util.Log.d("MetadataSwap", "onViewCreated - viewModel.originalResponse: ${viewModel.originalResponse?.name}")
        android.util.Log.d("MetadataSwap", "onViewCreated - viewModel.originalResponse.url: ${viewModel.originalResponse?.url}")
        if (swappedResponse != null && !com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
            android.util.Log.d("MetadataSwap", "onViewCreated - Found swapped response: ${swappedResponse.name}, updating page and cache")
            android.util.Log.d("MetadataSwap", "onViewCreated - swappedResponse.url: ${swappedResponse.url}")
            android.util.Log.d("MetadataSwap", "onViewCreated - swappedResponse actors: ${(swappedResponse as? com.lagradost.cloudstream3.AnimeLoadResponse)?.actors?.size ?: (swappedResponse as? com.lagradost.cloudstream3.TvSeriesLoadResponse)?.actors?.size}")
            android.util.Log.d("MetadataSwap", "onViewCreated - swappedResponse plot: ${(swappedResponse as? com.lagradost.cloudstream3.AnimeLoadResponse)?.plot?.take(30) ?: (swappedResponse as? com.lagradost.cloudstream3.TvSeriesLoadResponse)?.plot?.take(30)}")
            // Set the swapped response as the current response
            viewModel.currentResponse = swappedResponse
            android.util.Log.d("MetadataSwap", "onViewCreated - Set viewModel.currentResponse to swappedResponse")
            android.util.Log.d("MetadataSwap", "onViewCreated - viewModel.currentResponse.url: ${viewModel.currentResponse?.url}")
            // Get the API for the swapped response and wrap it in APIRepository
            val api = com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(swappedResponse.apiName)
            if (api != null) {
                val apiRepository = com.lagradost.cloudstream3.ui.APIRepository(api)
                // Post the swapped response to update the UI
                viewModel.postPage(swappedResponse, apiRepository)
                // Update cache with swapped metadata - preserve unswapped fields from existing cache
                val id = swappedResponse.getId()
                // Use the target entry URL (storedData.url) as cache key - this is the library entry we're swapping TO
                // swappedResponse.url is the SOURCE URL (from search result), not the target URL
                android.util.Log.d("MetadataSwap", "DEBUG - storedData.url: ${storedData.url}")
                android.util.Log.d("MetadataSwap", "DEBUG - swappedResponse.url: ${swappedResponse.url}")
                android.util.Log.d("MetadataSwap", "DEBUG - viewModel.originalResponse.url: ${viewModel.originalResponse?.url}")
                
                // Use existingCache.url as originalUrl (target URL) if it exists, otherwise fall back to storedData.url
                val existingCache = com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
                    com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                    storedData.url
                )
                
                val originalUrl = existingCache?.originalUrl ?: existingCache?.url ?: storedData.url
                android.util.Log.d("MetadataSwap", "DEBUG - originalUrl (target): $originalUrl")
                android.util.Log.d("MetadataSwap", "DEBUG - existingCache?.url: ${existingCache?.url}")
                android.util.Log.d("MetadataSwap", "DEBUG - existingCache?.originalUrl: ${existingCache?.originalUrl}")
                
                val cacheKey = originalUrl
                android.util.Log.d("MetadataSwap", "DEBUG - Using cacheKey: $cacheKey")
                android.util.Log.d("MetadataSwap", "DEBUG - cacheKey type: ${cacheKey::class.simpleName}")
                android.util.Log.d("MetadataSwap", "DEBUG - storedData.url type: ${storedData.url::class.simpleName}")
                android.util.Log.d("MetadataSwap", "DEBUG - swappedResponse.url type: ${swappedResponse.url::class.simpleName}")
                val fieldsToSwap = com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap ?: emptySet()

                // Extract metadata from swappedResponse for swapped fields, from swappedResponse for unswapped fields (if not null), otherwise from existing cache
                val finalPoster = if (com.lagradost.cloudstream3.ui.result.MetadataField.POSTER in fieldsToSwap) swappedResponse.posterUrl else (swappedResponse.posterUrl ?: existingCache?.poster)
                val finalBanner = if (com.lagradost.cloudstream3.ui.result.MetadataField.BANNER in fieldsToSwap) swappedResponse.backgroundPosterUrl else (swappedResponse.backgroundPosterUrl ?: existingCache?.backgroundPosterUrl)
                val finalLogo = if (com.lagradost.cloudstream3.ui.result.MetadataField.LOGO in fieldsToSwap) swappedResponse.logoUrl else (swappedResponse.logoUrl ?: existingCache?.logoUrl)
                val finalPlot = if (com.lagradost.cloudstream3.ui.result.MetadataField.PLOT in fieldsToSwap) {
                    swappedResponse.plot  // LoadResponse has plot property, no cast needed
                } else {
                    swappedResponse.plot ?: existingCache?.plot
                }
                val finalActors = if (com.lagradost.cloudstream3.ui.result.MetadataField.ACTORS in fieldsToSwap) {
                    swappedResponse.actors  // LoadResponse has actors property, no cast needed
                } else {
                    swappedResponse.actors ?: existingCache?.actors
                }
                val finalScore = if (com.lagradost.cloudstream3.ui.result.MetadataField.SCORE in fieldsToSwap) swappedResponse.score else (swappedResponse.score ?: existingCache?.score)
                val finalYear = if (com.lagradost.cloudstream3.ui.result.MetadataField.YEAR in fieldsToSwap) swappedResponse.year else (swappedResponse.year ?: existingCache?.year)
                val finalShowStatus = if (com.lagradost.cloudstream3.ui.result.MetadataField.STATUS in fieldsToSwap) {
                    if (swappedResponse is com.lagradost.cloudstream3.AnimeLoadResponse) swappedResponse.showStatus?.name else if (swappedResponse is com.lagradost.cloudstream3.TvSeriesLoadResponse) swappedResponse.showStatus?.name else null
                } else {
                    val swappedStatus = if (swappedResponse is com.lagradost.cloudstream3.AnimeLoadResponse) swappedResponse.showStatus?.name else if (swappedResponse is com.lagradost.cloudstream3.TvSeriesLoadResponse) swappedResponse.showStatus?.name else null
                    swappedStatus ?: existingCache?.showStatus
                }

                // Store original values before overwriting (for undo functionality)
                val originalPoster = if (com.lagradost.cloudstream3.ui.result.MetadataField.POSTER in fieldsToSwap) existingCache?.poster else null
                val originalBanner = if (com.lagradost.cloudstream3.ui.result.MetadataField.BANNER in fieldsToSwap) existingCache?.backgroundPosterUrl else null
                val originalLogo = if (com.lagradost.cloudstream3.ui.result.MetadataField.LOGO in fieldsToSwap) existingCache?.logoUrl else null
                val originalPlot = if (com.lagradost.cloudstream3.ui.result.MetadataField.PLOT in fieldsToSwap) existingCache?.plot else null
                val originalActors = if (com.lagradost.cloudstream3.ui.result.MetadataField.ACTORS in fieldsToSwap) existingCache?.actors else null
                val originalScore = if (com.lagradost.cloudstream3.ui.result.MetadataField.SCORE in fieldsToSwap) existingCache?.score else null
                val originalYear = if (com.lagradost.cloudstream3.ui.result.MetadataField.YEAR in fieldsToSwap) existingCache?.year else null
                val originalShowStatus = if (com.lagradost.cloudstream3.ui.result.MetadataField.STATUS in fieldsToSwap) existingCache?.showStatus else null

                // Convert List<ActorData> to List<String> for cache storage
                val finalActorsAsString = if (finalActors != null && finalActors.isNotEmpty() && finalActors.first() is com.lagradost.cloudstream3.ActorData) {
                    @Suppress("UNCHECKED_CAST")
                    (finalActors as List<com.lagradost.cloudstream3.ActorData>).map { actorData ->
                        "${actorData.actor.name}|${actorData.actor.image ?: ""}|${actorData.role?.name ?: ""}|${actorData.roleString ?: ""}|${actorData.voiceActor?.name ?: ""}|${actorData.voiceActor?.image ?: ""}"
                    }
                } else {
                    finalActors as? List<String>  // Already in string format or null
                }

                android.util.Log.d("MetadataSwap", "DEBUG - About to save to cache with cacheKey: $cacheKey")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.url will be: ${existingCache?.url ?: swappedResponse.url}")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.originalUrl will be: $originalUrl")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.name will be: ${existingCache?.name ?: swappedResponse.name}")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.actors will be: ${finalActorsAsString?.size}")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.plot will be: ${finalPlot?.take(30)}")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.logoUrl will be: ${finalLogo}")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.hasSwappedMetadata will be: true")
                android.util.Log.d("MetadataSwap", "DEBUG - DownloadHeaderCached.swappedFields will be: ${fieldsToSwap.map { it.name }.toSet()}")
                com.lagradost.cloudstream3.CloudStreamApp.setKey(
                    com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                    cacheKey,
                    com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached(
                        apiName = existingCache?.apiName ?: swappedResponse.apiName,
                        url = existingCache?.url ?: swappedResponse.url,
                        originalUrl = originalUrl,
                        type = existingCache?.type ?: swappedResponse.type,
                        name = existingCache?.name ?: swappedResponse.name,
                        poster = finalPoster,
                        backgroundPosterUrl = finalBanner,
                        logoUrl = finalLogo,
                        plot = finalPlot,
                        score = finalScore as? Int?,
                        showStatus = finalShowStatus,
                        year = finalYear,
                        episodeCount = existingCache?.episodeCount,
                        date = existingCache?.date,
                        actors = finalActorsAsString,
                        tags = existingCache?.tags ?: swappedResponse.tags,
                        id = id,
                        cacheTime = System.currentTimeMillis(),
                        hasCustomPoster = true,
                        hasSwappedMetadata = true,
                        swappedFields = fieldsToSwap.map { it.name }.toSet(),
                        originalPoster = originalPoster,
                        originalBanner = originalBanner,
                        originalLogo = originalLogo,
                        originalPlot = originalPlot,
                        originalActors = originalActors,
                        originalScore = originalScore,
                        originalYear = originalYear,
                        originalShowStatus = originalShowStatus
                    )
                )
                android.util.Log.d("MetadataSwap", "onViewCreated - Updated cache with swapped metadata for url: $cacheKey, hasCustomPoster: true")
                // Clear the shared variables after using them
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse = null
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap = null
                android.util.Log.d("MetadataSwap", "onViewCreated - Cleared sharedSwappedResponse and sharedFieldsToSwap")
                // Skip the normal load since we've already loaded the swapped response
                skipNormalLoad = true
            }
        }
        activity?.window?.decorView?.clearFocus()
        activity?.loadCache()
        context?.updateHasTrailers()
        hideKeyboard()
        android.util.Log.d("MetadataSwap", "load check - restart: ${storedData.restart}, hasLoaded: ${viewModel.hasLoaded()}, skipNormalLoad: $skipNormalLoad, willLoad: ${(storedData.restart || !viewModel.hasLoaded()) && !skipNormalLoad}")
        if ((storedData.restart || !viewModel.hasLoaded()) && !skipNormalLoad)
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        syncModel.addFromUrl(storedData.url)
        val api = APIHolder.getApiFromNameNull(storedData.apiName)

        // This may not be 100% reliable, and may delay for small period
        // before resultCastItems will be scrollable again, but this does work
        // most of the time.
        binding?.resultOverlappingPanels?.registerEndPanelStateListeners(
            object : OverlappingPanelsLayout.PanelStateListener {
                override fun onPanelStateChange(panelState: PanelState) {
                    PanelsChildGestureRegionObserver.Provider.get().apply {
                        resultBinding?.resultCastItems?.let { register(it) }
                    }
                }
            }
        )

        // ===== ===== =====

        binding?.resultSearch?.isGone = storedData.name.isBlank()
        binding?.resultSearch?.setOnClickListener {
            QuickSearchFragment.pushSearch(activity, storedData.name)
        }

        resultBinding?.apply {
            resultReloadConnectionerror.setOnClickListener {
                viewModel.load(
                    activity,
                    storedData.url,
                    storedData.apiName,
                    storedData.showFillers,
                    storedData.dubStatus,
                    storedData.start
                )
            }

            resultCastItems.setLinearListLayout(
                isHorizontal = true,
                nextLeft = FOCUS_SELF,
                nextRight = FOCUS_SELF
            )
            /*resultCastItems.layoutManager = object : LinearListLayout(view.context) {
                override fun onRequestChildFocus(
                    parent: RecyclerView,
                    state: RecyclerView.State,
                    child: View,
                    focused: View?
                ): Boolean {
                    // Make the cast always focus the first visible item when focused
                    // from somewhere else. Otherwise it jumps to the last item.
                    return if (parent.focusedChild == null) {
                        scrollToPosition(this.findFirstCompletelyVisibleItemPosition())
                        true
                    } else {
                        super.onRequestChildFocus(parent, state, child, focused)
                    }
                }
            }.apply {
                this.orientation = RecyclerView.HORIZONTAL
            }*/
            resultCastItems.setRecycledViewPool(ActorAdaptor.sharedPool)
            resultCastItems.adapter = ActorAdaptor()
            resultEpisodes.setRecycledViewPool(EpisodeAdapter.sharedPool)
            resultEpisodes.adapter =
                EpisodeAdapter(
                    api?.hasDownloadSupport == true,
                    { episodeClick ->
                        viewModel.handleAction(episodeClick)
                    },
                    { downloadClickEvent ->
                        DownloadButtonSetup.handleDownloadClick(downloadClickEvent)
                    }

                )

            observeNullable(viewModel.selectedSorting) {
                resultSortButton.setText(it)
            }

            observe(viewModel.sortSelections) { sort ->
                resultBinding?.resultSortButton?.setOnClickListener { view ->
                    view?.context?.let { ctx ->
                        val names = sort
                            .mapNotNull { (text, r) ->
                                r to (text.asStringNull(ctx) ?: return@mapNotNull null)
                            }

                        activity?.showDialog(
                            names.map { it.second },
                            viewModel.selectedSortingIndex.value ?: -1,
                            ctx.getString(R.string.sort_by),
                            false,
                            {}) { itemId ->
                            viewModel.setSort(names[itemId].first)
                        }
                    }
                }
            }

            resultScroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val dy = scrollY - oldScrollY
                if (dy > 0) { //check for scroll down
                    binding?.resultBookmarkFab?.shrink()
                } else if (dy < -5) {
                    binding?.resultBookmarkFab?.extend()
                }
                if (!isFullScreenPlayer && player.getIsPlaying()) {
                    if (scrollY > (resultBinding?.fragmentTrailer?.playerBackground?.height
                            ?: scrollY)
                    ) {
                        player.handleEvent(CSPlayerEvent.Pause)
                    }
                }
            })
        }

        binding?.apply {
            resultOverlappingPanels.setStartPanelLockState(OverlappingPanelsLayout.LockState.CLOSE)
            resultOverlappingPanels.setEndPanelLockState(OverlappingPanelsLayout.LockState.CLOSE)
            resultBack.setOnClickListener {
                activity?.popCurrentPage()
            }

            activity?.attachBackPressedCallback(this@ResultFragmentPhone.toString()) {
                if (resultOverlappingPanels.getSelectedPanel().ordinal == 1) {
                    runDefault()
                } else resultOverlappingPanels.closePanels()
            }

            resultMiniSync.setOnClickListener {
                if (resultOverlappingPanels.getSelectedPanel().ordinal == 1) {
                    resultOverlappingPanels.openStartPanel()
                } else resultOverlappingPanels.closePanels()
            }

            /*
            resultMiniSync.setRecycledViewPool(ImageAdapter.sharedPool)
            resultMiniSync.adapter = ImageAdapter(
                nextFocusDown = R.id.result_sync_set_score,
                clickCallback = { action ->
                    if (action == IMAGE_CLICK || action == IMAGE_LONG_CLICK) {
                        if (resultOverlappingPanels.getSelectedPanel().ordinal == 1) {
                            resultOverlappingPanels.openStartPanel()
                        } else resultOverlappingPanels.closePanels()
                    }
                })
            */
            resultSubscribe.setOnClickListener {
                viewModel.toggleSubscriptionStatus(context) { newStatus: Boolean? ->
                    if (newStatus == null) return@toggleSubscriptionStatus

                    val message = if (newStatus) {
                        // Episode check is now handled by EpisodeCheckWorkManager with configurable frequency
                        R.string.subscription_new
                    } else {
                        R.string.subscription_deleted
                    }

                    val name = (viewModel.page.value as? Resource.Success)?.value?.title
                        ?: com.lagradost.cloudstream3.utils.txt(R.string.no_data)
                            .asStringNull(context) ?: ""
                    showToast(
                        com.lagradost.cloudstream3.utils.txt(message, name),
                        Toast.LENGTH_SHORT
                    )
                }
                context?.let { openBatteryOptimizationSettings(it) }
            }
            resultFavorite.setOnClickListener {
                viewModel.toggleFavoriteStatus(context) { newStatus: Boolean? ->
                    if (newStatus == null) return@toggleFavoriteStatus

                    val message = if (newStatus) {
                        R.string.favorite_added
                    } else {
                        R.string.favorite_removed
                    }

                    val name = (viewModel.page.value as? Resource.Success)?.value?.title
                        ?: com.lagradost.cloudstream3.utils.txt(R.string.no_data)
                            .asStringNull(context) ?: ""
                    showToast(
                        com.lagradost.cloudstream3.utils.txt(message, name),
                        Toast.LENGTH_SHORT
                    )
                }
            }
            mediaRouteButton.apply {
                val chromecastSupport = api?.hasChromecastSupport == true
                alpha = if (chromecastSupport) 1f else 0.3f
                if (!chromecastSupport) {
                    setOnClickListener {
                        showToast(
                            R.string.no_chromecast_support_toast,
                            Toast.LENGTH_LONG
                        )
                    }
                }
                activity?.let { act ->
                    if (act.isCastApiAvailable()) {
                        try {
                            CastButtonFactory.setUpMediaRouteButton(act, this)
                            CastContext.getSharedInstance(act.applicationContext) {
                                it.run()
                            }.addOnCompleteListener {
                                isGone = !it.isSuccessful
                            }
                            // this shit leaks for some reason
                            //castContext.addCastStateListener { state ->
                            //    media_route_button?.isGone = state == CastState.NO_DEVICES_AVAILABLE
                            //}
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }
            }
        }

        playerBinding?.apply {
            playerOpenSource.setOnClickListener {
                currentTrailers.getOrNull(currentTrailerIndex)?.let { (_, ogTrailerLink) ->
                    context?.openBrowser(ogTrailerLink)
                }
            }
        }

        recommendationBinding?.apply {
            resultRecommendationsList.apply {
                spanCount = 3
                setRecycledViewPool(SearchAdapter.sharedPool)
                adapter =
                    SearchAdapter(
                        this,
                    ) { callback ->
                        SearchHelper.handleSearchClickCallback(callback)
                    }
            }
        }


        /*
        result_bookmark_button?.setOnClickListener {
            it.popupMenuNoIcons(
                items = WatchType.values()
                    .map { watchType -> Pair(watchType.internalId, watchType.stringRes) },
                //.map { watchType -> Triple(watchType.internalId, watchType.iconRes, watchType.stringRes) },
            ) {
                viewModel.updateWatchStatus(WatchType.fromInternalId(this.itemId))
            }
        }*/

        observeNullable(viewModel.resumeWatching) { resume ->
            resultBinding?.apply {
                if (resume == null) {
                    resultResumeParent.isVisible = false
                    resultPlayParent.isVisible = true
                    resultResumeProgressHolder.isVisible = false
                    return@observeNullable
                }
                resultResumeParent.isVisible = true
                resume.progress?.let { progress ->
                    resultNextSeriesButton.isVisible = false
                    resultResumeSeriesTitle.apply {
                        isVisible = !resume.isMovie
                        text =
                            if (resume.isMovie) null else context?.getNameFull(
                                resume.result.name,
                                resume.result.episode,
                                resume.result.season
                            )
                    }
                    if (resume.isMovie) {
                        resultPlayParent.isGone = true
                        resultResumeSeriesProgressText.isVisible = true
                        resultResumeSeriesProgressText.setText(progress.progressLeft)
                    }
                    resultResumeSeriesProgress.apply {
                        isVisible = true
                        this.max = progress.maxProgress
                        this.progress = progress.progress
                    }
                    resultResumeProgressHolder.isVisible = true
                } ?: run {
                    resultResumeProgressHolder.isVisible = false
                    if (!resume.isMovie) {
                        resultNextSeriesButton.isVisible = true
                        resultNextSeriesButton.text = context?.getString(R.string.action_continue)
                    }
                    resultResumeSeriesProgress.isVisible = false
                    resultResumeSeriesTitle.isVisible = false
                    resultResumeSeriesProgressText.isVisible = false
                }

                resultResumeSeriesButton.setOnClickListener {
                    resumeAction(storedData, resume)
                }
                resultNextSeriesButton.setOnClickListener {
                    resumeAction(storedData, resume)
                }
            }
        }

        observeNullable(viewModel.subscribeStatus) { isSubscribed ->
            binding?.resultSubscribe?.isVisible = isSubscribed != null
            if (isSubscribed == null) return@observeNullable

            val drawable = if (isSubscribed) {
                R.drawable.ic_baseline_notifications_active_24
            } else {
                R.drawable.baseline_notifications_none_24
            }

            binding?.resultSubscribe?.setImageResource(drawable)
        }

        observeNullable(viewModel.favoriteStatus) { isFavorite ->
            binding?.resultFavorite?.isVisible = isFavorite != null
            if (isFavorite == null) return@observeNullable

            val drawable = if (isFavorite) {
                R.drawable.ic_baseline_favorite_24
            } else {
                R.drawable.ic_baseline_favorite_border_24
            }

            binding?.resultFavorite?.setImageResource(drawable)
        }

        observeNullable(viewModel.episodes) { episodes ->
            resultBinding?.apply {
                // no failure?
                resultEpisodeLoading.isVisible = episodes is Resource.Loading
                resultEpisodes.isVisible = episodes is Resource.Success
                resultBatchDownloadButton.isVisible =
                    episodes is Resource.Success && episodes.value.isNotEmpty()

                if (episodes is Resource.Success) {
                    (resultEpisodes.adapter as? EpisodeAdapter)?.submitList(episodes.value)

                    // Show quality dialog with all sources
                    resultBatchDownloadButton.setOnLongClickListener {
                        ioSafe {
                            val defaultSources = QualityProfileDialog.getAllDefaultSources()
                            val activity = activity ?: return@ioSafe
                            activity.runOnUiThread {
                                QualityProfileDialog(
                                    activity,
                                    R.style.DialogFullscreenPlayer,
                                    defaultSources,
                                ).show()
                            }
                        }

                        true
                    }

                    resultBatchDownloadButton.setOnClickListener { view ->
                        val episodeStart =
                            episodes.value.firstOrNull()?.episode ?: return@setOnClickListener
                        val episodeEnd =
                            episodes.value.lastOrNull()?.episode ?: return@setOnClickListener

                        val episodeRange = if (episodeStart == episodeEnd) {
                            episodeStart.toString()
                        } else {
                            txt(
                                R.string.episodes_range,
                                episodeStart,
                                episodeEnd
                            ).asString(view.context)
                        }

                        val rangeMessage = txt(
                            R.string.download_episode_range,
                            episodeRange
                        ).asString(view.context)

                        AlertDialog.Builder(view.context, R.style.AlertDialogCustom)
                            .setTitle(R.string.download_all)
                            .setMessage(rangeMessage)
                            .setPositiveButton(R.string.yes) { _, _ ->
                                ioSafe {
                                    episodes.value.forEach { episode ->
                                        viewModel.handleAction(
                                            EpisodeClickEvent(
                                                ACTION_DOWNLOAD_EPISODE,
                                                episode
                                            )
                                        )
                                            // Join to make the episodes ordered
                                            .join()
                                    }
                                }
                            }
                            .setNegativeButton(R.string.cancel) { _, _ ->

                            }.show()

                    }

                }


            }

        }

        observeNullable(viewModel.movie) { data ->
            resultBinding?.apply {
                resultPlayMovie.isVisible = data is Resource.Success
                downloadButton.isVisible =
                    data is Resource.Success && viewModel.currentRepo?.api?.hasDownloadSupport == true

                (data as? Resource.Success)?.value?.let { (text, ep) ->
                    resultPlayMovie.setText(text)
                    resultPlayMovie.setOnClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_CLICK_DEFAULT, ep)
                        )
                    }
                    resultPlayMovie.setOnLongClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_SHOW_OPTIONS, ep)
                        )
                        return@setOnLongClickListener true
                    }
                    resultResumeSeriesButton.setOnLongClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_SHOW_OPTIONS, ep)
                        )
                        return@setOnLongClickListener true
                    }

                    val status = VideoDownloadManager.downloadStatus[ep.id]
                    downloadButton.setStatus(status)
                    downloadButton.setDefaultClickListener(
                        DownloadObjects.DownloadEpisodeCached(
                            name = ep.name,
                            poster = ep.poster,
                            episode = 0,
                            season = null,
                            id = ep.id,
                            parentId = ep.id,
                            score = ep.score,
                            description = ep.description,
                            date = ep.airDate,
                            cacheTime = System.currentTimeMillis(),
                        ),
                        null
                    ) { click ->
                        context?.let { openBatteryOptimizationSettings(it) }

                        when (click.action) {
                            DOWNLOAD_ACTION_DOWNLOAD -> {
                                viewModel.handleAction(
                                    EpisodeClickEvent(ACTION_DOWNLOAD_EPISODE, ep)
                                )
                            }

                            DOWNLOAD_ACTION_LONG_CLICK -> {
                                viewModel.handleAction(
                                    EpisodeClickEvent(
                                        ACTION_DOWNLOAD_MIRROR,
                                        ep
                                    )
                                )
                            }

                            else -> DownloadButtonSetup.handleDownloadClick(click)
                        }
                    }
                }
            }
        }

        observe(viewModel.page) { data ->
            android.util.Log.d("MetadataSwap", "Observer triggered with data: ${data?.javaClass?.simpleName}")
            if (data == null) {
                android.util.Log.d("MetadataSwap", "Observer received null, returning early")
                return@observe
            }
            android.util.Log.d("MetadataSwap", "Observer received data: ${(data as? Resource.Success)?.value?.titleText}")
            resultBinding?.apply {
                PanelsChildGestureRegionObserver.Provider.get().apply {
                    register(resultCastItems)
                }
                (data as? Resource.Success)?.value?.let { d ->
                    resultVpn.setText(d.vpnText)
                    resultInfo.setText(d.metaText)
                    resultNoEpisodes.setText(d.noEpisodesFoundText)
                    resultTitle.setText(d.titleText)
                    resultMetaSite.setText(d.apiName)
                    resultMetaType.setText(d.typeText)
                    resultMetaYear.setText(d.yearText)
                    resultMetaDuration.setText(d.durationText)
                    resultMetaRating.setText(d.ratingText)
                    resultMetaStatus.setText(d.onGoingText)
                    resultMetaContentRating.setText(d.contentRatingText)
                    resultCastText.setText(d.actorsText)
                    resultNextAiring.setText(d.nextAiringEpisode)
                    resultNextAiringTime.setText(d.nextAiringDate)
                    resultPoster.loadImage(d.posterImage, headers = d.posterHeaders) {
                        error {
                            getImageFromDrawable(
                                context ?: return@error null,
                                R.drawable.default_cover
                            )
                        }
                    }
                    resultPosterBackground.loadImage(
                        d.posterBackgroundImage,
                        headers = d.posterHeaders
                    ) {
                        error {
                            getImageFromDrawable(
                                context ?: return@error null,
                                R.drawable.default_cover
                            )
                        }
                    }

                    bindLogo(
                        url = d.logoUrl,
                        headers = d.posterHeaders,
                        titleView = resultTitle,
                        logoView = backgroundPosterWatermarkBadge
                    )

                    var isExpanded = false
                    resultDescription.apply {
                        setTextHtml(d.plotText)
                        setOnClickListener {
                            isExpanded = !isExpanded
                            maxLines = if (isExpanded) {
                                Integer.MAX_VALUE
                            } else 10
                        }
                    }

                    populateChips(resultTag, d.tags)

                    resultComingSoon.isVisible = d.comingSoon
                    resultDataHolder.isGone = d.comingSoon

                    val prefs =
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(root.context)
                    val showCast = prefs.getBoolean(
                        root.context.getString(R.string.show_cast_in_details_key),
                        true
                    )

                    resultCastItems.isGone = !showCast || d.actors.isNullOrEmpty()
                    (resultCastItems.adapter as? ActorAdaptor)?.submitList(if (showCast) d.actors else emptyList())

                    if (d.contentRatingText == null) {
                        // If there is no rating to display, we don't want an empty gap
                        resultMetaContentRating.width = 0
                    }

                    if (syncModel.addSyncs(d.syncData)) {
                        syncModel.updateMetaAndUser()
                        syncModel.updateSynced()
                    } else {
                        syncModel.addFromUrl(d.url)
                    }

                    binding?.apply {
                        resultSearch.isGone = d.title.isBlank()
                        resultSearch.setOnClickListener {
                            QuickSearchFragment.pushSearch(activity, d.title)
                        }

                        resultShare.setOnClickListener {
                            try {
                                val i = Intent(Intent.ACTION_SEND)
                                val nameBase64 =
                                    base64Encode(d.apiName.toString().toByteArray(Charsets.UTF_8))
                                val urlBase64 = base64Encode(d.url.toByteArray(Charsets.UTF_8))
                                val encodedUri = URLEncoder.encode(
                                    "$APP_STRING_SHARE:$nameBase64?$urlBase64",
                                    "UTF-8"
                                )
                                val redirectUrl =
                                    "https://recloudstream.github.io/csredirect?redirectto=$encodedUri"
                                i.type = "text/plain"
                                i.putExtra(Intent.EXTRA_SUBJECT, d.title)
                                i.putExtra(Intent.EXTRA_TEXT, redirectUrl)
                                startActivity(Intent.createChooser(i, d.title))
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                        setUrl(d.url)
                        resultBookmarkFab.apply {
                            isVisible = true
                            extend()
                        }
                    }
                }

                (data as? Resource.Failure)?.let { data ->
                    resultErrorText.text = storedData.url.plus("\n") + data.errorString
                }

                binding?.resultBookmarkFab?.isVisible = data is Resource.Success
                resultFinishLoading.isVisible = data is Resource.Success

                resultLoading.isVisible = data is Resource.Loading

                resultLoadingError.isVisible = data is Resource.Failure
                resultErrorText.isVisible = data is Resource.Failure
                resultReloadConnectionOpenInBrowser.isVisible = data is Resource.Failure

                resultTitle.setOnLongClickListener {
                    clipboardHelper(
                        com.lagradost.cloudstream3.utils.txt(R.string.title),
                        resultTitle.text
                    )
                    true
                }
            }
        }

        observeNullable(viewModel.episodesCountText) { count ->
            resultBinding?.resultEpisodesText.setText(count)
        }

        observeNullable(viewModel.selectPopup) { popup ->
            if (popup == null) {
                popupDialog?.dismissSafe(activity)
                popupDialog = null
                return@observeNullable
            }
            popupDialog?.dismissSafe(activity)

            popupDialog = activity?.let { act ->
                val options = popup.getOptions(act)
                val title = popup.getTitle(act)

                act.showBottomDialogInstant(
                    options, title, {
                        popupDialog = null
                        popup.callback(null)
                    }, {
                        popupDialog = null
                        popup.callback(it)
                    }
                )
            }
        }

        observe(viewModel.trailers) { trailers ->
            setTrailers(trailers.flatMap { it.mirros }) // I dont care about subtitles yet!
        }

        observe(syncModel.synced) { list ->
            syncBinding?.resultSyncNames?.text =
                list.filter { it.isSynced && it.hasAccount }.joinToString { it.name }

            val newList = list.filter { it.isSynced && it.hasAccount }

            binding?.resultMiniSync?.isVisible = newList.isNotEmpty()
            //(binding?.resultMiniSync?.adapter as? ImageAdapter)?.submitList(newList.mapNotNull { it.icon })
        }


        var currentSyncProgress = 0
        fun setSyncMaxEpisodes(totalEpisodes: Int?) {
            syncBinding?.resultSyncEpisodes?.max = (totalEpisodes ?: 0) * 1000

            safe {
                val ctx = syncBinding?.resultSyncEpisodes?.context
                syncBinding?.resultSyncMaxEpisodes?.text =
                    totalEpisodes?.let { episodes ->
                        ctx?.getString(R.string.sync_total_episodes_some)?.format(episodes)
                    } ?: run {
                        ctx?.getString(R.string.sync_total_episodes_none)
                    }
            }
        }
        observe(syncModel.metadata) { meta ->
            when (meta) {
                is Resource.Success -> {
                    val d = meta.value
                    syncBinding?.resultSyncEpisodes?.progress = currentSyncProgress * 1000
                    setSyncMaxEpisodes(d.totalEpisodes)

                    viewModel.setMeta(d, syncModel.getSyncs())
                }

                is Resource.Loading -> {
                    syncBinding?.resultSyncMaxEpisodes?.text =
                        syncBinding?.resultSyncMaxEpisodes?.context?.getString(R.string.sync_total_episodes_none)
                }

                else -> {}
            }
        }


        observe(syncModel.userData) { status ->
            var closed = false
            syncBinding?.apply {
                when (status) {
                    is Resource.Failure -> {
                        resultSyncLoadingShimmer.stopShimmer()
                        resultSyncLoadingShimmer.isVisible = false
                        resultSyncHolder.isVisible = false
                        closed = true
                    }

                    is Resource.Loading -> {
                        resultSyncLoadingShimmer.startShimmer()
                        resultSyncLoadingShimmer.isVisible = true
                        resultSyncHolder.isVisible = false
                    }

                    is Resource.Success -> {
                        resultSyncLoadingShimmer.stopShimmer()
                        resultSyncLoadingShimmer.isVisible = false
                        resultSyncHolder.isVisible = true

                        val d = status.value
                        val desiredScore = d.score?.toFloat(1) ?: 0.0f
                        val totalSteps = (resultSyncRating.valueTo / resultSyncRating.stepSize)
                        val desiredStep = (totalSteps * desiredScore).roundToInt()
                        resultSyncRating.value = desiredStep * resultSyncRating.stepSize

                        resultSyncCheck.setItemChecked(d.status.internalId + 1, true)
                        val watchedEpisodes = d.watchedEpisodes ?: 0
                        currentSyncProgress = watchedEpisodes

                        d.maxEpisodes?.let {
                            // don't directly call it because we don't want to override metadata observe
                            setSyncMaxEpisodes(it)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            resultSyncEpisodes.setProgress(watchedEpisodes * 1000, true)
                        } else {
                            resultSyncEpisodes.progress = watchedEpisodes * 1000
                        }
                        resultSyncCurrentEpisodes.text =
                            Editable.Factory.getInstance()?.newEditable(watchedEpisodes.toString())
                        safe { // format might fail
                            val text = d.score?.toFloat(10)?.roundToInt()?.let {
                                context?.getString(R.string.sync_score_format)?.format(it)
                            } ?: "?"
                            resultSyncScoreText.text = text
                        }
                    }

                    null -> {
                        closed = false
                    }
                }
            }
            binding?.resultOverlappingPanels?.setStartPanelLockState(if (closed) OverlappingPanelsLayout.LockState.CLOSE else OverlappingPanelsLayout.LockState.UNLOCKED)
        }
        observe(viewModel.recommendations) { recommendations ->
            setRecommendations(recommendations, null)
        }
        context?.let { ctx ->
            val arrayAdapter = ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
            /*
            -1 -> None
            0 -> Watching
            1 -> Completed
            2 -> OnHold
            3 -> Dropped
            4 -> PlanToWatch
            5 -> ReWatching
            */
            val items = listOf(
                R.string.none,
                R.string.type_watching,
                R.string.type_completed,
                R.string.type_on_hold,
                R.string.type_dropped,
                R.string.type_plan_to_watch,
                R.string.type_re_watching
            ).map { ctx.getString(it) }
            arrayAdapter.addAll(items)
            syncBinding?.apply {
                resultSyncCheck.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                resultSyncCheck.adapter = arrayAdapter
                setListViewHeightBasedOnItems(resultSyncCheck)

                resultSyncCheck.setOnItemClickListener { _, _, which, _ ->
                    syncModel.setStatus(which - 1)
                }

                resultSyncRating.addOnChangeListener { it, value, fromUser ->
                    if (fromUser) syncModel.setScore(Score.from(value, it.valueTo.roundToInt()))
                }

                resultSyncAddEpisode.setOnClickListener {
                    syncModel.setEpisodesDelta(1)
                }

                resultSyncSubEpisode.setOnClickListener {
                    syncModel.setEpisodesDelta(-1)
                }

                resultSyncCurrentEpisodes.doOnTextChanged { text, _, before, count ->
                    if (count == before) return@doOnTextChanged
                    text?.toString()?.toIntOrNull()?.let { ep ->
                        syncModel.setEpisodes(ep)
                    }
                }
            }
        }

        syncBinding?.resultSyncSetScore?.setOnClickListener {
            syncModel.publishUserData()
        }

        observe(viewModel.watchStatus) { watchType ->
            binding?.resultBookmarkFab?.apply {
                setText(watchType.stringRes)
                if (watchType == WatchType.NONE) {
                    context?.colorFromAttribute(R.attr.white)
                } else {
                    context?.colorFromAttribute(R.attr.colorPrimary)
                }?.let {
                    val colorState = ColorStateList.valueOf(it)
                    iconTint = colorState
                    setTextColor(colorState)
                }

                setOnClickListener { fab ->
                    activity?.showBottomDialog(
                        WatchType.entries.map { fab.context.getString(it.stringRes) }.toList(),
                        watchType.ordinal,
                        fab.context.getString(R.string.action_add_to_bookmarks),
                        showApply = false,
                        {}) {
                        viewModel.updateWatchStatus(WatchType.entries[it], context)
                    }
                }
            }
        }


        observeNullable(viewModel.loadedLinks) { load ->
            if (load == null) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
                return@observeNullable
            }
            if (loadingDialog?.isShowing != true) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
            }
            loadingDialog = loadingDialog ?: context?.let { ctx ->
                val builder = BottomSheetDialog(ctx)
                builder.setContentView(R.layout.bottom_loading)
                builder.setOnDismissListener {
                    loadingDialog = null
                    viewModel.cancelLinks()
                }
                builder.setCanceledOnTouchOutside(true)
                builder.show()
                builder
            }
            loadingDialog?.findViewById<MaterialButton>(R.id.overlay_loading_skip_button)?.apply {
                if (load.linksLoaded <= 0) {
                    isInvisible = true
                } else {
                    setOnClickListener {
                        viewModel.skipLoading()
                    }
                    isVisible = true
                    text = "${context.getString(R.string.skip_loading)} (${load.linksLoaded})"
                }
            }
        }

        observeNullable(viewModel.selectedSeason) { text ->
            resultBinding?.apply {
                resultSeasonButton.setText(text)

                selectSeason =
                    text?.asStringNull(resultSeasonButton.context)
                // If the season button is visible the result season button will be next focus down
                if (resultSeasonButton.isVisible && resultResumeParent.isVisible) {
                    setFocusUpAndDown(resultResumeSeriesButton, resultSeasonButton)
                }
            }
        }

        observeNullable(viewModel.selectedDubStatus) { status ->
            resultBinding?.apply {
                resultDubSelect.setText(status)

                if (resultDubSelect.isVisible && !resultSeasonButton.isVisible && !resultEpisodeSelect.isVisible && resultResumeParent.isVisible) {
                    setFocusUpAndDown(resultResumeSeriesButton, resultDubSelect)
                }
            }
        }
        observeNullable(viewModel.selectedRange) { range ->
            resultBinding?.apply {
                resultEpisodeSelect.setText(range)

                selectEpisodeRange = range?.asStringNull(resultEpisodeSelect.context)
                // If Season button is invisible then the bookmark button next focus is episode select
                if (resultEpisodeSelect.isVisible && !resultSeasonButton.isVisible && resultResumeParent.isVisible) {
                    setFocusUpAndDown(resultResumeSeriesButton, resultEpisodeSelect)
                }
            }
        }

//        val preferDub = context?.getApiDubstatusSettings()?.all { it == DubStatus.Dubbed } == true

        observe(viewModel.dubSubSelections) { range ->
            resultBinding?.resultDubSelect?.setOnClickListener { view ->
                view?.context?.let { ctx ->
                    view.popupMenuNoIconsAndNoStringRes(
                        range
                            .mapNotNull { (text, status) ->
                                Pair(
                                    status.ordinal,
                                    text?.asStringNull(ctx) ?: return@mapNotNull null
                                )
                            }) {
                        viewModel.changeDubStatus(DubStatus.entries[itemId])
                    }
                }
            }
        }

        observe(viewModel.rangeSelections) { range ->
            resultBinding?.resultEpisodeSelect?.setOnClickListener { view ->
                view?.context?.let { ctx ->
                    val names = range
                        .mapNotNull { (text, r) ->
                            r to (text?.asStringNull(ctx) ?: return@mapNotNull null)
                        }

                    activity?.showDialog(
                        names.map { it.second },
                        names.indexOfFirst { it.second == selectEpisodeRange },
                        ctx.getString(R.string.episodes),
                        false,
                        {}) { itemId ->
                        viewModel.changeRange(names[itemId].first)
                    }
                }
            }
        }

        observe(viewModel.seasonSelections) { seasonList ->
            resultBinding?.resultSeasonButton?.setOnClickListener { view ->

                view?.context?.let { ctx ->
                    val names = seasonList
                        .mapNotNull { (text, r) ->
                            r to (text?.asStringNull(ctx) ?: return@mapNotNull null)
                        }

                    activity?.showDialog(
                        names.map { it.second },
                        names.indexOfFirst { it.second == selectSeason },
                        ctx.getString(R.string.season),
                        false,
                        {}) { itemId ->
                        viewModel.changeSeason(names[itemId].first)
                    }


                    //view.popupMenuNoIconsAndNoStringRes(names.mapIndexed { index, (_, name) ->
                    //    index to name
                    //}) {
                    //    viewModel.changeSeason(names[itemId].first)
                    //}
                }
            }
        }
    }

    private fun resumeAction(
        storedData: ResultFragment.StoredData,
        resume: ResumeWatchingStatus
    ) {
        viewModel.handleAction(
            EpisodeClickEvent(
                storedData.playerAction, //?: ACTION_PLAY_EPISODE_IN_PLAYER,
                resume.result
            )
        )
    }

    override fun onPause() {
        super.onPause()
        // Clear metadata swap mode when navigating away within QuickSearchFragment
        if (com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive) {
            android.util.Log.d("MetadataSwap", "onPause - Clearing isMetadataSwapMode")
            viewModel.setMetadataSwapMode(false)
        }
        PanelsChildGestureRegionObserver.Provider.get()
            .addGestureRegionsUpdateListener(gestureRegionsListener)
    }

    private fun setRecommendations(rec: List<SearchResponse>?, validApiName: String?) {
        val isInvalid = rec.isNullOrEmpty()
        val matchAgainst = validApiName ?: rec?.firstOrNull()?.apiName

        recommendationBinding?.apply {
            root.isGone = isInvalid
            root.post {
                rec?.let { list ->
                    (resultRecommendationsList.adapter as? SearchAdapter)?.submitList(list.filter { it.apiName == matchAgainst })
                }
            }
        }

        binding?.apply {
            resultRecommendationsBtt.isGone = isInvalid
            resultRecommendationsBtt.setOnClickListener {
                val nextFocusDown = if (resultOverlappingPanels.getSelectedPanel().ordinal == 1) {
                    resultOverlappingPanels.openEndPanel()
                    R.id.result_recommendations
                } else {
                    resultOverlappingPanels.closePanels()
                    R.id.result_description
                }
                resultBinding?.apply {
                    resultRecommendationsBtt.nextFocusDownId = nextFocusDown
                    resultSearch.nextFocusDownId = nextFocusDown
                    resultOpenInBrowser.nextFocusDownId = nextFocusDown
                    resultShare.nextFocusDownId = nextFocusDown
                }
            }
            resultOverlappingPanels.setEndPanelLockState(if (isInvalid) OverlappingPanelsLayout.LockState.CLOSE else OverlappingPanelsLayout.LockState.UNLOCKED)

            rec?.map { it.apiName }?.distinct()?.let { apiNames ->
                // very dirty selection
                recommendationBinding?.resultRecommendationsFilterButton?.apply {
                    isVisible = apiNames.size > 1
                    text = matchAgainst
                    setOnClickListener { _ ->
                        activity?.showBottomDialog(
                            apiNames,
                            apiNames.indexOf(matchAgainst),
                            getString(R.string.home_change_provider_img_des), false, {}
                        ) {
                            setRecommendations(rec, apiNames[it])
                        }
                    }
                }
            } ?: run {
                recommendationBinding?.resultRecommendationsFilterButton?.isVisible = false
            }
        }
    }
}
