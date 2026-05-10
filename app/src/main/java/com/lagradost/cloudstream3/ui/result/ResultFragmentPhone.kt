package com.lagradost.cloudstream3.ui.result

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.withContext
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
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
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
    companion object {
        // Tag key for tracking panel listener registration on the view
        private const val PANEL_LISTENER_TAG_KEY = "panel_listener_registered"
    }

    // FIX: Track registration state to prevent infinite loops
    private var isCastItemsRegistered = false
    // FIX: Track panel state listener registration to prevent multiple registrations
    private var isPanelStateListenerRegistered = false
    // [RACE_CONDITION_FIX] Debounce rapid sync button clicks
    private var lastSyncButtonClick = 0L
    private val SYNC_CLICK_DEBOUNCE_MS = 500L

    // [PANEL_FIX] Reusable PanelStateListener to prevent accumulation of listeners
    private var panelStateListener: OverlappingPanelsLayout.PanelStateListener? = null
    // [PANEL_FIX] Counter to track listener invocations
    private var panelStateListenerInvocationCount = 0

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

    // Sticky flag - once a name-based match is found, keep button visible
    // This prevents the "sync gap" where IDs are found but isSynced hasn't updated yet
    private var wasNameMatchFound = false

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
        // [PANEL_FIX] Removed registration here - resultBinding is null in onCreate anyway
        // Registration happens in updateUI when metadata loads and in PanelStateListener
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
        android.util.Log.d("[GESTURE_DEBUG]", "onDestroyView - unregistering gesture regions")
        PanelsChildGestureRegionObserver.Provider.get().let { obs ->
            resultBinding?.resultCastItems?.let {
                obs.unregister(it)
                android.util.Log.d("[GESTURE_DEBUG]", "onDestroyView - unregistered resultCastItems")
                isCastItemsRegistered = false
            }
            isPanelStateListenerRegistered = false
            // [PANEL_FIX] Clear the panel state listener reference to prevent leaks
            panelStateListener = null

            obs.removeGestureRegionsUpdateListener(gestureRegionsListener)
            android.util.Log.d("[GESTURE_DEBUG]", "onDestroyView - removed gesture regions listener")
        }

        // [PANEL_FIX] Reset the view tags so new fragments can register listeners and initialize lock states
        binding?.resultOverlappingPanels?.setTag(PANEL_LISTENER_TAG_KEY.hashCode(), null)
        binding?.resultOverlappingPanels?.setTag(PANEL_LISTENER_TAG_KEY.hashCode() + 1, null)

        // Clear metadata swap state when fragment is destroyed (user abandoned swap flow)
        if (com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive) {
            android.util.Log.d("MetadataSwap", "onDestroyView - Clearing swap state")
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive = false
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse = null
            viewModel.setMetadataSwapMode(false)
            android.util.Log.d("MetadataSwap", "onDestroyView - Swap state cleared")
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
        // Show Open in Browser only if URL is valid and setting is enabled
        binding?.resultOpenInBrowser?.apply {
            val showButton = context?.let { ctx ->
                PreferenceManager.getDefaultSharedPreferences(ctx)
                    .getBoolean(getString(R.string.show_open_in_browser_key), true)
            } ?: true
            val hasValidUrl = url?.startsWith("http") == true
            isVisible = hasValidUrl && showButton
            isEnabled = hasValidUrl && showButton
            if (hasValidUrl) {
                setOnClickListener {
                    context?.openBrowser(url)
                }
            }
        }
    }

    private fun setupUiListeners() {
        val url = getStoredData()?.url
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
            url?.let { view?.context?.openBrowser(it) }
        }

        resultBinding?.resultMetaSite?.setOnClickListener {
            url?.let { view?.context?.openBrowser(it) }
        }

        // Setup pull-to-refresh
        resultBinding?.resultSwipeRefresh?.setOnRefreshListener {
            android.util.Log.d("RefreshMetadata", "Pull-to-refresh triggered")
            val storedData = getStoredData()
            if (storedData != null) {
                // Use the original provider from currentResponse.apiName first
                val originalProvider = viewModel.currentResponse?.apiName
                val metaProviders = viewModel.getAvailableMetaProviders()

                android.util.Log.d(
                    "RefreshMetadata",
                    "Original provider: $originalProvider, Available providers: $metaProviders"
                )

                if (metaProviders.isNotEmpty()) {
                    // Prioritize the original provider if it's in the available list
                    val providerToUse =
                        if (originalProvider != null && originalProvider in metaProviders) {
                            android.util.Log.d(
                                "RefreshMetadata",
                                "Using original provider: $originalProvider"
                            )
                            originalProvider
                        } else {
                            android.util.Log.d(
                                "RefreshMetadata",
                                "Original provider not available or null, using first available: ${metaProviders.first()}"
                            )
                            metaProviders.first()
                        }
                    viewModel.refreshMetadata(providerToUse)
                } else {
                    android.util.Log.w("RefreshMetadata", "No meta providers available for refresh")
                    resultBinding?.resultSwipeRefresh?.isRefreshing = false
                    activity?.let { showToast(it, "No providers available for refresh") }
                }
            } else {
                resultBinding?.resultSwipeRefresh?.isRefreshing = false
            }
        }

        // AGGRESSIVE OBSERVER BATCHING: Set up all observers in a single coroutine to minimize main thread fragmentation
        lifecycleScope.launch {
            // Batch all observer setup operations to reduce main thread overhead
            val observers = listOf(
                // Observer 1: Metadata loading state
                viewModel.metadataLoading.observe(viewLifecycleOwner) { isLoading ->
                    resultBinding?.resultLoading?.visibility =
                        if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
                    // Sync SwipeRefreshLayout with metadata loading state
                    if (!isLoading) {
                        resultBinding?.resultSwipeRefresh?.isRefreshing = false
                        // Hide undo swap button after refresh completes
                        binding?.resultUndoMetadataFab?.visibility = android.view.View.GONE
                    }
                },

                // Observer 2: Refresh errors
                viewModel.refreshError.observe(viewLifecycleOwner) { error ->
                    if (error != null) {
                        activity?.let { showToast(it, error) }
                        resultBinding?.resultSwipeRefresh?.isRefreshing = false
                    }
                },

                // Observer 3: API fetch in progress (load on demand)
                viewModel.apiFetchInProgress.observe(viewLifecycleOwner) { isInProgress ->
                    resultBinding?.resultLoading?.visibility =
                        if (isInProgress) android.view.View.VISIBLE else android.view.View.GONE
                    // Sync SwipeRefreshLayout with API fetch state
                    if (!isInProgress) {
                        resultBinding?.resultSwipeRefresh?.isRefreshing = false
                    }
                }
            )

            // All observers are now set up in a single batch
            android.util.Log.d("ObserverBatch", "All observers set up in single batch")
        }

        // Show swap metadata FAB when in metadata swap mode
        android.util.Log.d(
            "MetadataSwap",
            "resultSwapMetadataFab reference: ${binding?.resultSwapMetadataFab}"
        )
        binding?.resultSwapMetadataFab?.setOnClickListener {
            android.util.Log.d("MetadataSwap", "Swap metadata FAB clicked")
            val currentResponse = viewModel.currentResponse
            val originalResponse = viewModel.originalResponse
            if (currentResponse != null && originalResponse != null) {
                showFieldSelectionAndSwap(requireContext(), currentResponse, originalResponse, null)
            } else {
                android.util.Log.e("MetadataSwap", "currentResponse or originalResponse is null")
            }
        }

        // Reset metadata button - triggers full refresh to get original data
        binding?.resultUndoMetadataFab?.setOnClickListener {
            android.util.Log.d("MetadataSwap", "Reset metadata FAB clicked - triggering full refresh")
            viewModel._metadataLoading.value = true
            val providerName = viewModel.currentResponse?.apiName
            if (providerName != null) {
                viewModel.refreshMetadata(providerName)
            } else {
                android.util.Log.e("MetadataSwap", "Cannot refresh metadata - provider name is null")
                viewModel._metadataLoading.value = false
            }
        }

        // Observe metadata swap mode to show/hide swap metadata FAB
        viewModel.isMetadataSwapMode.observe(viewLifecycleOwner) { isSwapMode ->
            val isLibraryEntry = getStoredData() != null
            val hasOriginalResponse =
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse != null
            // Show button if in swap mode OR if we have an original response (user is in swapped entry)
            val shouldShow = (isSwapMode || hasOriginalResponse) && isLibraryEntry
            android.util.Log.d(
                "MetadataSwap",
                "isMetadataSwapMode changed: $isSwapMode, isLibraryEntry: $isLibraryEntry, hasOriginalResponse: $hasOriginalResponse, button visibility: ${if (shouldShow) "VISIBLE" else "GONE"}"
            )
            android.util.Log.d(
                "MetadataSwap",
                "resultSwapMetadataFab is null: ${binding?.resultSwapMetadataFab == null}"
            )
            binding?.resultSwapMetadataFab?.visibility =
                if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
            // Hide bookmark FAB when in metadata swap mode to prevent overlap
            binding?.resultBookmarkFab?.visibility =
                if (shouldShow) android.view.View.GONE else android.view.View.VISIBLE

            // Check cache for swapped metadata to show/hide reset button
            val storedData = getStoredData()
            if (storedData != null) {
                val cachedHeader =
                    com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
                        com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                        storedData.url
                    )
                android.util.Log.d(
                    "MetadataSwap",
                    "Checking cache for reset button - url: ${storedData.url}, hasSwappedMetadata: ${cachedHeader?.hasSwappedMetadata}"
                )
                binding?.resultUndoMetadataFab?.visibility =
                    if (cachedHeader?.hasSwappedMetadata == true) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun resetMetadata() {
        android.util.Log.d("ResetMetadata", "resetMetadata called")
        val storedData = getStoredData() ?: run {
            android.util.Log.e("ResetMetadata", "resetMetadata - storedData is null")
            return
        }
        val cacheKey = storedData.url

        // Get library ID from cached header BEFORE clearing cache
        val cachedHeader =
            com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
                com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                cacheKey
            )
        val libraryId = cachedHeader?.id
        android.util.Log.d("ResetMetadata", "Reset - Retrieved library ID from cache: $libraryId")

        // Clear the cache entry to trigger fresh fetch from provider
        android.util.Log.d("ResetMetadata", "Reset - Clearing cache entry for url: $cacheKey")
        com.lagradost.cloudstream3.CloudStreamApp.removeKey(
            com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
            cacheKey
        )
        android.util.Log.d("ResetMetadata", "Reset - Cache entry cleared")

        // Clear library entry metadata (plot, score, tags) to prevent swapped data from persisting
        if (libraryId != null) {
            android.util.Log.d(
                "ResetMetadata",
                "Reset - Clearing library entry metadata for id: $libraryId"
            )
            val bookmarkedData =
                com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData(libraryId)
            if (bookmarkedData != null) {
                android.util.Log.d(
                    "ResetMetadata",
                    "Reset - Found bookmarked data, clearing plot, score, tags"
                )
                com.lagradost.cloudstream3.utils.DataStoreHelper.setBookmarkedData(
                    libraryId,
                    bookmarkedData.copy(
                        plot = null,
                        score = null,
                        tags = null
                    )
                )
            } else {
                android.util.Log.d(
                    "ResetMetadata",
                    "Reset - No bookmarked data found for id: $libraryId"
                )
            }
        } else {
            android.util.Log.d(
                "ResetMetadata",
                "Reset - No library ID found in cache, skipping library metadata clear"
            )
        }

        // Clear current response to force provider fetch (otherwise load() sees existing data and skips fetch)
        android.util.Log.d(
            "ResetMetadata",
            "Reset - Clearing viewModel.currentResponse, currentMeta, and currentSync to force provider fetch"
        )
        viewModel.clear()

        // Reload from provider to get fresh metadata
        android.util.Log.d(
            "ResetMetadata",
            "resetMetadata - Reloading from provider with forceRefresh=true"
        )
        viewModel.load(
            activity,
            storedData.url,
            storedData.apiName,
            storedData.showFillers,
            storedData.dubStatus,
            storedData.start,
            forceRefresh = true
        )
        android.util.Log.d("ResetMetadata", "resetMetadata - Reloaded from provider")
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

        android.util.Log.d(
            "MetadataSwap",
            "Swapping metadata from ${currentResponse.name} to ${originalResponse.name}"
        )

        val context = activity ?: return

        // Show search mode selection dialog
        val searchModeOptions = arrayOf("Full Search", "Select Providers")
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
        builder.setTitle("Swap Metadata - Search Mode")
        builder.setItems(searchModeOptions) { _, which ->
            when (which) {
                0 -> {
                    // Full Search - proceed with current behavior
                    android.util.Log.d("MetadataSwap", "Full Search selected")
                    showFieldSelectionAndSwap(context, currentResponse, originalResponse, null)
                }

                1 -> {
                    // Select Providers - show provider selection dialog
                    android.util.Log.d("MetadataSwap", "Select Providers selected")
                    showProviderSelectionDialog(context, currentResponse, originalResponse)
                }
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            // Reset metadata swap mode when cancelled
            viewModel.setMetadataSwapMode(false)
            viewModel.originalResponse = null
            dialog.dismiss()
        }
        builder.setOnCancelListener {
            // Reset metadata swap mode when dialog is cancelled via back button
            viewModel.setMetadataSwapMode(false)
            viewModel.originalResponse = null
        }
        builder.show()
    }

    private fun showProviderSelectionDialog(
        context: Context,
        currentResponse: com.lagradost.cloudstream3.LoadResponse,
        originalResponse: com.lagradost.cloudstream3.LoadResponse
    ) {
        // Get list of all providers
        val providerNames = synchronized(com.lagradost.cloudstream3.APIHolder.apis) {
            com.lagradost.cloudstream3.APIHolder.apis.map { it.name }
        }

        // Show multi-select dialog for providers
        activity?.showMultiDialog(
            items = providerNames,
            selectedIndex = emptyList<Int>(), // No providers selected by default
            name = "Select Providers to Search",
            dismissCallback = {
                // Reset metadata swap mode when cancelled
                viewModel.setMetadataSwapMode(false)
                viewModel.originalResponse = null
            }
        ) { selectedIndices ->
            val selectedProviders = selectedIndices.map { providerNames[it] }.toSet()
            android.util.Log.d("MetadataSwap", "Selected providers: $selectedProviders")
            showFieldSelectionAndSwap(
                activity ?: return@showMultiDialog,
                currentResponse,
                originalResponse,
                selectedProviders
            )
        }
    }

    private fun showFieldSelectionAndSwap(
        context: Context,
        currentResponse: com.lagradost.cloudstream3.LoadResponse,
        originalResponse: com.lagradost.cloudstream3.LoadResponse,
        selectedProviders: Set<String>?
    ) {
        // Show field selection modal dialog
        val fieldNames =
            arrayOf("Plot", "Poster", "Banner", "Logo", "Actors", "Score", "Status", "Year")
        val fieldChecked = booleanArrayOf(
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true
        ) // All fields selected by default

        val builder = androidx.appcompat.app.AlertDialog.Builder(context, R.style.Theme_AlertDialog_FieldSelection)
        builder.setTitle("Select fields to swap")
        builder.setMultiChoiceItems(fieldNames, fieldChecked) { _, which, isChecked ->
            // Prevent unselecting the last field
            if (!isChecked) {
                var selectedCount = 0
                fieldChecked.forEach { if (it) selectedCount++ }
                if (selectedCount <= 1) {
                    // Don't allow unselecting the last field
                    fieldChecked[which] = true
                    activity?.let { showToast(it, "At least one field must be selected") }
                    return@setMultiChoiceItems
                }
            }
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
            android.util.Log.d("MetadataSwap", "Selected providers: $selectedProviders")

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

            // If providers were selected, use them to search for metadata
            if (selectedProviders != null && selectedProviders.isNotEmpty()) {
                android.util.Log.d(
                    "MetadataSwap",
                    "Using selected providers for metadata search: $selectedProviders"
                )
                // Store selected providers for use in QuickSearchFragment
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.selectedProvidersForSwap =
                    selectedProviders
                // Open QuickSearch with selected providers
                openSearchForMetadataWithProviders(currentResponse, selectedProviders)
            } else {
                // Full search or no providers selected - proceed with direct swap
                android.util.Log.d(
                    "MetadataSwap",
                    "No providers selected or full search - proceeding with direct swap"
                )
                performDirectSwap(currentResponse, originalResponse, fieldsToSwap, context)
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            // Don't reset metadata swap mode - user is still in the swapped entry
            // The button should remain visible as long as sharedOriginalResponse is set
            android.util.Log.d(
                "MetadataSwap",
                "Field selection dialog cancelled - keeping metadata swap mode active"
            )
            dialog.dismiss()
        }
        builder.setOnCancelListener {
            // Don't reset metadata swap mode - user is still in the swapped entry
            // The button should remain visible as long as sharedOriginalResponse is set
            android.util.Log.d(
                "MetadataSwap",
                "Field selection dialog cancelled via back button - keeping metadata swap mode active"
            )
        }
        builder.show()
    }

    private fun openSearchForMetadataWithProviders(
        currentResponse: com.lagradost.cloudstream3.LoadResponse,
        selectedProviders: Set<String>
    ) {
        val currentName = currentResponse.name
        android.util.Log.d(
            "MetadataSwap",
            "openSearchForMetadataWithProviders - currentResponse: $currentName, providers: $selectedProviders"
        )

        // Store original response in static variable BEFORE opening QuickSearchFragment
        viewModel.originalResponse = currentResponse
        com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse =
            currentResponse
        android.util.Log.d(
            "MetadataSwap",
            "openSearchForMetadataWithProviders - stored originalResponse in viewModel.originalResponse and sharedOriginalResponse"
        )

        // DO NOT set isMetadataSwapActive here - only set it when user actually selects an entry
        // This prevents the swap button from appearing on all search results
        android.util.Log.d(
            "MetadataSwap",
            "openSearchForMetadataWithProviders - NOT setting isMetadataSwapActive yet, will set on entry selection"
        )

        // Open QuickSearchFragment with selected providers and title pre-filled, passing metadata swap context via bundle
        com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment.pushSearch(
            activity,
            autoSearch = currentName,
            providers = selectedProviders.toTypedArray(),
            isMetadataSwap = true,
            originalResponseName = currentResponse.name,
            originalResponseUrl = currentResponse.url
        )

        // Set up callback to handle search result selection
        com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment.clickCallback = { callback ->
            android.util.Log.d(
                "MetadataSwap",
                "QuickSearchFragment callback received - action: ${callback.action}"
            )
            if (callback.action == com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD) {
                // Set isMetadataSwapActive only when user actually selects an entry
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive = true
                android.util.Log.d(
                    "MetadataSwap",
                    "User selected entry - setting isMetadataSwapActive = true"
                )
                // Open entry with metadata swap flag
                openEntryForMetadataSwap(
                    callback.card,
                    selectedProviders.firstOrNull() ?: "Unknown"
                )
            }
        }
    }

    private fun performDirectSwap(
        currentResponse: com.lagradost.cloudstream3.LoadResponse,
        originalResponse: com.lagradost.cloudstream3.LoadResponse,
        fieldsToSwap: Set<MetadataField>,
        context: Context
    ) {
        android.util.Log.d("swapfix", "===== performDirectSwap START =====")
        android.util.Log.d("swapfix", "performDirectSwap - currentResponse: ${currentResponse.name}, url: ${currentResponse.url}")
        android.util.Log.d("swapfix", "performDirectSwap - originalResponse: ${originalResponse.name}, url: ${originalResponse.url}")
        android.util.Log.d("swapfix", "performDirectSwap - fieldsToSwap: $fieldsToSwap")
        
        // Swap selected metadata fields - merge currentResponse metadata into originalResponse
        val swappedResponse =
            viewModel.swapAllMetadata(originalResponse, currentResponse, fieldsToSwap)
        android.util.Log.d("swapfix", "performDirectSwap - swappedResponse: ${swappedResponse.name}, url: ${swappedResponse.url}")
        android.util.Log.d("swapfix", "performDirectSwap - swappedResponse actors: ${swappedResponse.actors?.size}, plot: ${swappedResponse.plot?.take(50)}")

        // Old static variable approach
        // sharedTrueOriginal is already saved at the start of the swap flow in openSearchForMetadata
        com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse =
            swappedResponse
        com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap = fieldsToSwap
        android.util.Log.d(
            "swapfix",
            "performDirectSwap - Stored swapped response in sharedSwappedResponse"
        )

        // Update the original response with swapped metadata
        viewModel.currentResponse = swappedResponse
        viewModel.originalResponse = swappedResponse
        android.util.Log.d("swapfix", "performDirectSwap - Updated viewModel.currentResponse and viewModel.originalResponse")

        // Store original response reference before clearing static variables
        val originalResponseRef = com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse
        android.util.Log.d("swapfix", "performDirectSwap - originalResponseRef: ${originalResponseRef?.name}, url: ${originalResponseRef?.url}")

        // Reset metadata swap mode and clear static variables
        viewModel.setMetadataSwapMode(false)
        viewModel.originalResponse = null
        if (!com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse = null
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive = false
            android.util.Log.d("swapfix", "performDirectSwap - Cleared sharedOriginalResponse and isMetadataSwapActive")
        }

        // Navigate to TARGET entry to ensure cache is saved with correct URL, then pop back to return to original
        if (originalResponseRef != null && swappedResponse != null) {
            android.util.Log.d("swapfix", "performDirectSwap - Detaching back pressed callback")
            activity?.detachBackPressedCallback(this@ResultFragmentPhone.toString())
            android.util.Log.d("swapfix", "performDirectSwap - onBackPressed #1 (from Source to QuickSearch)")
            activity?.onBackPressed()
            android.util.Log.d("swapfix", "performDirectSwap - onBackPressed #2 (from QuickSearch to Original)")
            activity?.onBackPressed()
            // Navigate to TARGET to ensure cache is saved with correct URL
            android.util.Log.d("swapfix", "performDirectSwap - Navigating to TARGET to fix cache key")
            @Suppress("DEPRECATION_ERROR")
            com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult(
                com.lagradost.cloudstream3.AnimeSearchResponse(
                    name = originalResponseRef.name,
                    url = swappedResponse.url,
                    apiName = swappedResponse.apiName,
                    type = swappedResponse.type,
                    posterUrl = swappedResponse.posterUrl,
                    year = swappedResponse.year
                )
            )
            android.util.Log.d("swapfix", "performDirectSwap - onBackPressed #3 (from TARGET to Original)")
            activity?.onBackPressed()
            android.util.Log.d("swapfix", "===== performDirectSwap END - RETURNED TO ORIGINAL =====")
        } else {
            android.util.Log.e("swapfix", "performDirectSwap - originalResponseRef or swappedResponse is null, falling back to onBackPressed")
            activity?.onBackPressed()
            activity?.onBackPressed()
        }
    }

    private fun openSearchForMetadata(providerName: String) {
        android.util.Log.d("swapfix", "===== openSearchForMetadata START =====")
        android.util.Log.d("swapfix", "openSearchForMetadata - provider: $providerName")
        val currentResponse = viewModel.currentResponse ?: run {
            android.util.Log.e("swapfix", "openSearchForMetadata - currentResponse is null")
            return
        }
        val currentName = currentResponse.name
        android.util.Log.d(
            "swapfix",
            "openSearchForMetadata - currentResponse: $currentName, url: ${currentResponse.url}"
        )

        android.util.Log.d(
            "swapfix",
            "openSearchForMetadata - storing original response: ${currentResponse.name}, url: ${currentResponse.url}"
        )

        // Store original response in static variable BEFORE opening QuickSearchFragment
        viewModel.originalResponse = currentResponse
        com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse =
            currentResponse
        android.util.Log.d(
            "swapfix",
            "openSearchForMetadata - stored in viewModel.originalResponse and sharedOriginalResponse"
        )

        // DO NOT set isMetadataSwapActive here - only set it when user actually selects an entry
        // This prevents the swap button from appearing on all search results
        android.util.Log.d(
            "swapfix",
            "openSearchForMetadata - NOT setting isMetadataSwapActive yet"
        )

        // Open QuickSearchFragment with provider pre-selected and title pre-filled, passing metadata swap context via bundle
        android.util.Log.d("swapfix", "openSearchForMetadata - calling QuickSearchFragment.pushSearch")
        com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment.pushSearch(
            activity,
            autoSearch = currentName,
            providers = arrayOf(providerName),
            isMetadataSwap = true,
            originalResponseName = currentResponse.name,
            originalResponseUrl = currentResponse.url
        )
        android.util.Log.d("swapfix", "openSearchForMetadata - QuickSearchFragment.pushSearch called")

        // Set up callback to handle search result selection
        android.util.Log.d("swapfix", "openSearchForMetadata - setting up clickCallback")
        com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment.clickCallback = { callback ->
            android.util.Log.d(
                "swapfix",
                "openSearchForMetadata - callback received - action: ${callback.action}, card: ${callback.card.name}"
            )
            if (callback.action == com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD) {
                // Set isMetadataSwapActive only when user actually selects an entry
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive = true
                android.util.Log.d(
                    "swapfix",
                    "openSearchForMetadata - User selected entry, setting isMetadataSwapActive = true"
                )
                // Open entry with metadata swap flag
                openEntryForMetadataSwap(callback.card, providerName)
            }
        }
        android.util.Log.d("swapfix", "openSearchForMetadata - clickCallback set successfully")
        android.util.Log.d("swapfix", "===== openSearchForMetadata END =====")
    }

    private fun openEntryForMetadataSwap(
        searchResult: com.lagradost.cloudstream3.SearchResponse,
        providerName: String
    ) {
        android.util.Log.d("swapfix", "===== openEntryForMetadataSwap START =====")
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - searchResult: ${searchResult.name}, url: ${searchResult.url}")
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - provider: $providerName")
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - viewModel.currentResponse: ${viewModel.currentResponse?.name}")
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - isMetadataSwapActive before: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive}")
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - sharedOriginalResponse before: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.name}")

        // Store current response as original before navigating to new entry
        viewModel.currentResponse?.let {
            viewModel.originalResponse = it
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse = it
            android.util.Log.d(
                "swapfix",
                "openEntryForMetadataSwap - stored in viewModel.originalResponse and sharedOriginalResponse"
            )
            android.util.Log.d(
                "swapfix",
                "openEntryForMetadataSwap - sharedOriginalResponse after set: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.name}, url: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.url}"
            )
        }

        com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive = true
        android.util.Log.d(
            "swapfix",
            "openEntryForMetadataSwap - Set isMetadataSwapActive to true"
        )
        android.util.Log.d(
            "swapfix",
            "openEntryForMetadataSwap - isMetadataSwapActive after: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive}"
        )

        android.util.Log.d(
            "swapfix",
            "openEntryForMetadataSwap - calling loadSearchResult with metadataSwap=true"
        )
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - originalResponseName: ${viewModel.currentResponse?.name}")
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - originalResponseUrl: ${viewModel.currentResponse?.url}")
        com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult(
            searchResult,
            metadataSwap = true,
            originalResponseName = viewModel.currentResponse?.name,
            originalResponseUrl = viewModel.currentResponse?.url
        )
        android.util.Log.d("swapfix", "openEntryForMetadataSwap - loadSearchResult called")
        android.util.Log.d("swapfix", "===== openEntryForMetadataSwap END =====")
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
        binding.posterCard.textRating.visibility =
            if (metadata.score != null) android.view.View.VISIBLE else android.view.View.GONE

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
        android.util.Log.d("[GESTURE_DEBUG]", "onResume - adding gesture regions listener")
        PanelsChildGestureRegionObserver.Provider.get()
            .addGestureRegionsUpdateListener(gestureRegionsListener)

        // Force refresh episode adapter to update download status icons
        // This fixes the issue where download scan icons don't update when navigating away during scan
        android.util.Log.d("DownloadStatusRefresh", "=== RESULTFRAGMENTPHONE ONRESUME ===")
        android.util.Log.d(
            "DownloadStatusRefresh",
            "Forcing episode adapter refresh to update download status icons"
        )
        android.util.Log.d(
            "DownloadStatusRefresh",
            "Current downloadStatus map size: ${com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatus.size}"
        )
        viewModel.reloadEpisodes()
        android.util.Log.d("DownloadStatusRefresh", "=== RESULTFRAGMENTPHONE ONRESUME COMPLETE ===")
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::reloadViewModel
        // FIX: Unregister gesture regions to prevent multiple registrations
        android.util.Log.d("[GESTURE_DEBUG]", "onStop - unregistering gesture regions, isCastItemsRegistered: $isCastItemsRegistered")
        PanelsChildGestureRegionObserver.Provider.get().let { obs ->
            resultBinding?.resultCastItems?.let {
                obs.unregister(it)
                android.util.Log.d("[GESTURE_DEBUG]", "onStop - unregistered resultCastItems")
                isCastItemsRegistered = false
            }
            isPanelStateListenerRegistered = false
        }
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
        // Don't clear swap state here - onPause is called during normal navigation within swap flow
        // Clearing here causes multiple fragment instances to be created
        PanelsChildGestureRegionObserver.Provider.get()
            .addGestureRegionsUpdateListener(gestureRegionsListener)
    }

    private fun setRecommendations(rec: List<SearchResponse>?, validApiName: String?) {
        val isEmpty = rec.isNullOrEmpty()
        val matchAgainst = validApiName ?: rec?.firstOrNull()?.apiName

        recommendationBinding?.apply {
            // UI RESILIENCE: Always show the recommendations section, even if empty
            root.isGone = false
            root.post {
                rec?.let { list ->
                    (resultRecommendationsList.adapter as? SearchAdapter)?.submitList(list.filter { it.apiName == matchAgainst })
                }
            }
        }

        binding?.apply {
            // Always visible - user can click to trigger fetch if empty
            resultRecommendationsBtt.isGone = false
            resultRecommendationsBtt.setOnClickListener {
                // If empty, try to fetch recommendations
                if (isEmpty) {
                    android.util.Log.d("CacheFlow", "Recommendations empty - triggering fetch")
                    viewModel.fetchRecommendationsIfNeeded()
                }
                
                val nextFocusDown =
                    if (resultOverlappingPanels.getSelectedPanel().ordinal == 1) {
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
            // Always unlock the panel - user can access recommendations even if empty
            android.util.Log.d("[PANEL_LOCK_DEBUG]", "Setting end panel lock state - isEmpty: $isEmpty, new state: UNLOCKED")
            resultOverlappingPanels.setEndPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)

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

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ===== setup =====
        fixSystemBarsPadding(view)
        val storedData = getStoredData() ?: return

        // Setup refresh metadata click listener
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

        // Setup UI listeners including swap metadata FAB observer
        setupUiListeners()

        // Reset sticky flag when loading a new entry to prevent ghosting previous sync state
        wasNameMatchFound = false
        android.util.Log.d(
            "[MINI_SYNC_FIX]",
            "Reset wasNameMatchFound to false for new entry: ${storedData.name}"
        )

        // [SIMKL_DEFINITIVE_FIX][PHASE3] Clear sync model state using blocking version
        syncModel.clearBlocking()
        android.util.Log.d("[SIMKL_DEFINITIVE_FIX]", "Cleared sync model state for new entry")

        android.util.Log.d("MetadataSwap", "===== COMPREHENSIVE DEBUG START =====")
        android.util.Log.d("MetadataSwap", "storedData.name: ${storedData.name}")
        android.util.Log.d("MetadataSwap", "storedData.url: ${storedData.url}")
        android.util.Log.d("MetadataSwap", "storedData.apiName: ${storedData.apiName}")

        // Check if this is a metadata swap navigation (from bundle or static flag or sharedOriginalResponse)
        val isMetadataSwapFromBundle =
            arguments?.getBoolean(com.lagradost.cloudstream3.ui.result.ResultFragment.METADATA_SWAP_BUNDLE)
                ?: false
        val isMetadataSwapFromStatic =
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.isMetadataSwapActive
        val hasSharedOriginalResponse =
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse != null
        val hasOriginalResponseInBundle = arguments?.getString("original_response_name") != null
        val isMetadataSwap =
            isMetadataSwapFromBundle || isMetadataSwapFromStatic || hasSharedOriginalResponse || hasOriginalResponseInBundle
        android.util.Log.d(
            "MetadataSwap",
            "onViewCreated - isMetadataSwap from bundle: $isMetadataSwapFromBundle, from static: $isMetadataSwapFromStatic, hasSharedOriginalResponse: $hasSharedOriginalResponse, hasOriginalResponseInBundle: $hasOriginalResponseInBundle"
        )
        android.util.Log.d(
            "MetadataSwap",
            "onViewCreated - sharedOriginalResponse: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.name}"
        )
        android.util.Log.d(
            "MetadataSwap",
            "onViewCreated - sharedOriginalResponse.url: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse?.url}"
        )
        android.util.Log.d(
            "MetadataSwap",
            "onViewCreated - bundle original_response_name: ${arguments?.getString("original_response_name")}"
        )
        android.util.Log.d(
            "MetadataSwap",
            "onViewCreated - bundle original_response_url: ${arguments?.getString("original_response_url")}"
        )
        android.util.Log.d(
            "MetadataSwap",
            "onViewCreated - sharedSwappedResponse: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse?.name}"
        )
        android.util.Log.d(
            "MetadataSwap",
            "onViewCreated - sharedSwappedResponse.url: ${com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse?.url}"
        )
        if (isMetadataSwap) {
            viewModel.setMetadataSwapMode(true)
            viewModel.originalResponse =
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse
            android.util.Log.d(
                "MetadataSwap",
                "onViewCreated - Set isMetadataSwapMode to true, originalResponse: ${viewModel.originalResponse?.name}"
            )
            android.util.Log.d(
                "MetadataSwap",
                "onViewCreated - viewModel.originalResponse.url: ${viewModel.originalResponse?.url}"
            )
            // Don't clear isMetadataSwapActive here - it should persist while navigating between search results
        }

        // Check if there's a swapped response available (from metadata swap completion)
        var swappedResponse =
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse
        var fieldsToSwap =
            com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap ?: emptySet()
        var skipNormalLoad = false

        // Use new MetadataSwapManager if feature flag is enabled
        if (com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
            val cacheKey = com.lagradost.cloudstream3.ui.result.cache.CacheCoordinator.resolveKey(
                storedData.url,
                null
            )
            android.util.Log.d(
                "MetadataSwap",
                "onViewCreated - Using new MetadataSwapManager - cacheKey: $cacheKey"
            )

            lifecycleScope.launch {
                val isSwapped =
                    com.lagradost.cloudstream3.ui.result.swap.MetadataSwapManager.isSwapped(
                        context ?: return@launch, cacheKey
                    )
                android.util.Log.d("MetadataSwap", "onViewCreated - isSwapped: $isSwapped")

                if (isSwapped) {
                    // Let the normal load flow handle swap metadata application via ResultViewModel2
                    // The ViewModel will check MetadataSwapManager and apply metadata if needed
                    android.util.Log.d(
                        "MetadataSwap",
                        "onViewCreated - Swap active, letting ViewModel handle metadata application"
                    )
                }
            }
        }

        android.util.Log.d(
            "swapfix",
            "onViewCreated - sharedSwappedResponse: ${swappedResponse?.name}"
        )
        android.util.Log.d(
            "swapfix",
            "onViewCreated - sharedSwappedResponse.url: ${swappedResponse?.url}"
        )
        android.util.Log.d(
            "swapfix",
            "onViewCreated - viewModel.originalResponse: ${viewModel.originalResponse?.name}"
        )
        android.util.Log.d(
            "swapfix",
            "onViewCreated - viewModel.originalResponse.url: ${viewModel.originalResponse?.url}"
        )
        if (swappedResponse != null && !com.lagradost.cloudstream3.ui.result.ResultViewModel2.USE_NEW_SWAP_SYSTEM) {
            android.util.Log.d(
                "swapfix",
                "===== onViewCreated - HANDLING SWAPPED RESPONSE ====="
            )
            android.util.Log.d(
                "swapfix",
                "onViewCreated - Found swapped response: ${swappedResponse.name}, url: ${swappedResponse.url}"
            )
            android.util.Log.d(
                "swapfix",
                "onViewCreated - swappedResponse actors: ${swappedResponse.actors?.size}, plot: ${swappedResponse.plot?.take(30)}"
            )
            // Set the swapped response as the current response
            viewModel.currentResponse = swappedResponse
            android.util.Log.d(
                "swapfix",
                "onViewCreated - Set viewModel.currentResponse to swappedResponse"
            )
            // Get the API for the swapped response and wrap it in APIRepository
            android.util.Log.d("swapfix", "onViewCreated - Getting API for swappedResponse.apiName: ${swappedResponse.apiName}")
            val api =
                com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(swappedResponse.apiName)
            if (api != null) {
                android.util.Log.d("swapfix", "onViewCreated - API found: ${api.name}")
                val apiRepository = com.lagradost.cloudstream3.ui.APIRepository(api)
                // Post the swapped response to update the UI
                android.util.Log.d("swapfix", "onViewCreated - Calling viewModel.postPage with swappedResponse")
                viewModel.postPage(swappedResponse, apiRepository)
                // Update cache with swapped metadata - preserve unswapped fields from existing cache
                val id = swappedResponse.getId()
                android.util.Log.d("swapfix", "onViewCreated - swappedResponse.getId(): $id")
                // Use the target entry URL (storedData.url) as cache key - this is the library entry we're swapping TO
                // swappedResponse.url is the SOURCE URL (from search result), not the target URL
                android.util.Log.d("swapfix", "onViewCreated - storedData.url: ${storedData.url}")
                android.util.Log.d("swapfix", "onViewCreated - swappedResponse.url: ${swappedResponse.url}")
                android.util.Log.d("swapfix", "onViewCreated - viewModel.originalResponse.url: ${viewModel.originalResponse?.url}")

                // Use existingCache.url as originalUrl (target URL) if it exists, otherwise fall back to storedData.url
                android.util.Log.d("swapfix", "onViewCreated - Getting existing cache for storedData.url")
                val existingCache =
                    com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
                        com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                        storedData.url
                    )
                android.util.Log.d("swapfix", "onViewCreated - existingCache: ${existingCache?.name}, url: ${existingCache?.url}")

                val originalUrl = existingCache?.originalUrl ?: existingCache?.url ?: storedData.url
                android.util.Log.d("swapfix", "onViewCreated - originalUrl (target): $originalUrl")
                android.util.Log.d("swapfix", "onViewCreated - existingCache?.url: ${existingCache?.url}")
                android.util.Log.d("swapfix", "onViewCreated - existingCache?.originalUrl: ${existingCache?.originalUrl}")

                val cacheKey = originalUrl
                android.util.Log.d("swapfix", "onViewCreated - Using cacheKey: $cacheKey")
                android.util.Log.d("swapfix", "onViewCreated - cacheKey type: ${cacheKey::class.simpleName}")
                android.util.Log.d("swapfix", "onViewCreated - storedData.url type: ${storedData.url::class.simpleName}")
                android.util.Log.d("swapfix", "onViewCreated - swappedResponse.url type: ${swappedResponse.url::class.simpleName}")
                val fieldsToSwap =
                    com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap
                        ?: emptySet()
                android.util.Log.d("swapfix", "onViewCreated - fieldsToSwap: $fieldsToSwap")

                // Extract metadata from swappedResponse for swapped fields, from swappedResponse for unswapped fields (if not null), otherwise from existing cache
                val finalPoster =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.POSTER in fieldsToSwap) swappedResponse.posterUrl else (swappedResponse.posterUrl
                        ?: existingCache?.poster)
                val finalBanner =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.BANNER in fieldsToSwap) swappedResponse.backgroundPosterUrl else (swappedResponse.backgroundPosterUrl
                        ?: existingCache?.backgroundPosterUrl)
                val finalLogo =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.LOGO in fieldsToSwap) swappedResponse.logoUrl else (swappedResponse.logoUrl
                        ?: existingCache?.logoUrl)
                val finalPlot =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.PLOT in fieldsToSwap) {
                        swappedResponse.plot  // LoadResponse has plot property, no cast needed
                    } else {
                        swappedResponse.plot ?: existingCache?.plot
                    }
                val finalActors =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.ACTORS in fieldsToSwap) {
                        swappedResponse.actors  // LoadResponse has actors property, no cast needed
                    } else {
                        swappedResponse.actors ?: existingCache?.actors
                    }
                val finalScore =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.SCORE in fieldsToSwap) swappedResponse.score else (swappedResponse.score
                        ?: existingCache?.score)
                val finalYear =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.YEAR in fieldsToSwap) swappedResponse.year else (swappedResponse.year
                        ?: existingCache?.year)
                val finalShowStatus =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.STATUS in fieldsToSwap) {
                        if (swappedResponse is com.lagradost.cloudstream3.AnimeLoadResponse) swappedResponse.showStatus?.name else if (swappedResponse is com.lagradost.cloudstream3.TvSeriesLoadResponse) swappedResponse.showStatus?.name else null
                    } else {
                        val swappedStatus =
                            if (swappedResponse is com.lagradost.cloudstream3.AnimeLoadResponse) swappedResponse.showStatus?.name else if (swappedResponse is com.lagradost.cloudstream3.TvSeriesLoadResponse) swappedResponse.showStatus?.name else null
                        swappedStatus ?: existingCache?.showStatus
                    }

                // Store original values before overwriting (for undo functionality)
                val originalPoster =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.POSTER in fieldsToSwap) existingCache?.poster else null
                val originalBanner =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.BANNER in fieldsToSwap) existingCache?.backgroundPosterUrl else null
                val originalLogo =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.LOGO in fieldsToSwap) existingCache?.logoUrl else null
                val originalPlot =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.PLOT in fieldsToSwap) existingCache?.plot else null
                val originalActors =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.ACTORS in fieldsToSwap) existingCache?.actors else null
                val originalScore =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.SCORE in fieldsToSwap) existingCache?.score else null
                val originalYear =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.YEAR in fieldsToSwap) existingCache?.year else null
                val originalShowStatus =
                    if (com.lagradost.cloudstream3.ui.result.MetadataField.STATUS in fieldsToSwap) existingCache?.showStatus else null

                // Convert List<ActorData> to List<String> for cache storage
                val finalActorsAsString =
                    if (finalActors != null && finalActors.isNotEmpty() && finalActors.first() is com.lagradost.cloudstream3.ActorData) {
                        @Suppress("UNCHECKED_CAST")
                        (finalActors as List<com.lagradost.cloudstream3.ActorData>).map { actorData ->
                            "${actorData.actor.name}|${actorData.actor.image ?: ""}|${actorData.role?.name ?: ""}|${actorData.roleString ?: ""}|${actorData.voiceActor?.name ?: ""}|${actorData.voiceActor?.image ?: ""}"
                        }
                    } else {
                        finalActors as? List<String>  // Already in string format or null
                    }

                android.util.Log.d(
                    "MetadataSwap",
                    "DEBUG - About to save to cache with cacheKey: $cacheKey"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.url will be: ${existingCache?.url ?: swappedResponse.url}"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.originalUrl will be: $originalUrl"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.name will be: ${existingCache?.name ?: swappedResponse.name}"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.actors will be: ${finalActorsAsString?.size}"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.plot will be: ${finalPlot?.take(30)}"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.logoUrl will be: ${finalLogo}"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.hasSwappedMetadata will be: true"
                )
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - DownloadHeaderCached.swappedFields will be: ${
                        fieldsToSwap.map { it.name }.toSet()
                    }"
                )
                android.util.Log.d("swapfix", "onViewCreated - Calling CloudStreamApp.setKey to save cache")
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
                        metadataOnlyMode = existingCache?.metadataOnlyMode ?: false,
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
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - Updated cache with swapped metadata for url: $cacheKey"
                )
                // Clear the shared variables after using them
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedSwappedResponse = null
                com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedFieldsToSwap = null
                android.util.Log.d(
                    "swapfix",
                    "onViewCreated - Cleared sharedSwappedResponse and sharedFieldsToSwap"
                )
                // Skip the normal load since we've already loaded the swapped response
                skipNormalLoad = true
                android.util.Log.d("swapfix", "onViewCreated - skipNormalLoad set to true")
            }
        }
        activity?.window?.decorView?.clearFocus()
        activity?.loadCache()
        context?.updateHasTrailers()
        hideKeyboard()
        android.util.Log.d(
            "MetadataSwap",
            "load check - restart: ${storedData.restart}, hasLoaded: ${viewModel.hasLoaded()}, skipNormalLoad: $skipNormalLoad, willLoad: ${(storedData.restart || !viewModel.hasLoaded()) && !skipNormalLoad}"
        )
        if ((storedData.restart || !viewModel.hasLoaded()) && !skipNormalLoad)
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        // SIMKL_DEFINITIVE_FIX: Clean sync data loading - no session URL resolution
        // Phase 1: Removed false session URL resolution logic
        // Uses ID-based lookup via existing bookmark sync data or title search
        lifecycleScope.launch {
            val allBookmarked = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.lagradost.cloudstream3.utils.DataStoreHelper.getAllBookmarkedData()
            }

            // Match bookmark by name and apiName for sync data
            val matchingBookmark = allBookmarked.find { bookmark ->
                bookmark.name == storedData.name && bookmark.apiName == storedData.apiName
            }

            val hasBookmarkSyncData = matchingBookmark?.syncData?.isNotEmpty() == true
            if (hasBookmarkSyncData) {
                // [SIMKL_DEFINITIVE_FIX][PHASE1+3] Using existing bookmark sync data with thread-safe addSyncs
                wasNameMatchFound = true
                syncModel.addSyncs(matchingBookmark.syncData)
                syncModel.updateMetaAndUser()
                syncModel.updateSynced()
            } else {
                // Try URL-based sync lookup for HTTP URLs
                if (storedData.url.startsWith("http")) {
                    syncModel.addFromUrl(storedData.url)
                }

                // Fallback: Name-based ID lookup for MAL/AniList matching
                com.lagradost.cloudstream3.utils.Coroutines.ioSafe {
                    try {
                        val trackerResult = com.lagradost.cloudstream3.APIHolder.getTracker(
                            listOfNotNull(storedData.name)
                                .filter { it.length > 2 }
                                .distinct()
                                .map { it.lowercase().trim() },
                            com.lagradost.cloudstream3.TrackerType.getTypes(com.lagradost.cloudstream3.TvType.Anime),
                            null
                        )

                        if (trackerResult != null) {
                            wasNameMatchFound = true
                            val syncMap = mutableMapOf<String, String>()
                            trackerResult.malId?.let {
                                syncMap[com.lagradost.cloudstream3.syncproviders.AccountManager.malApi.idPrefix] = it.toString()
                            }
                            trackerResult.aniId?.let {
                                syncMap[com.lagradost.cloudstream3.syncproviders.AccountManager.aniListApi.idPrefix] = it
                            }

                            if (syncMap.isNotEmpty()) {
                                syncModel.addSyncs(syncMap)
                                syncModel.updateMetaAndUser()
                                syncModel.updateSynced()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("[SIMKL_DEFINITIVE_FIX]", "Name-based lookup error", e)
                    }
                }
            }
        }
        val api = APIHolder.getApiFromNameNull(storedData.apiName)

            // This may not be 100% reliable, and may delay for small period
        // before resultCastItems will be scrollable again, but this does work
        // most of the time.
        // [PANEL_FIX] Use view tags to prevent duplicate registration across fragment recreations
        binding?.resultOverlappingPanels?.let { panelsLayout ->
            // Check if a listener has already been registered for this view instance
            val isAlreadyRegistered = panelsLayout.getTag(PANEL_LISTENER_TAG_KEY.hashCode()) as? Boolean ?: false
            if (!isAlreadyRegistered) {
                android.util.Log.d("[MINI_SYNC_PANEL]", "Registering panel state listener - not yet registered on this view")
                // Create reusable listener if not already created
                if (panelStateListener == null) {
                    panelStateListener = object : OverlappingPanelsLayout.PanelStateListener {
                        override fun onPanelStateChange(panelState: PanelState) {
                            PanelsChildGestureRegionObserver.Provider.get().apply {
                                resultBinding?.resultCastItems?.let {
                                    try {
                                        unregister(it)
                                    } catch (e: Exception) {
                                        // View wasn't registered, ignore
                                    }
                                    try {
                                        register(it)
                                        isCastItemsRegistered = true
                                    } catch (e: Exception) {
                                        // Registration failed, ignore
                                    }
                                }
                            }
                        }
                    }
                }
                panelsLayout.registerEndPanelStateListeners(panelStateListener!!)
                panelsLayout.setTag(PANEL_LISTENER_TAG_KEY.hashCode(), true)
                isPanelStateListenerRegistered = true
            } else {
                android.util.Log.d("[MINI_SYNC_PANEL]", "Panel state listener already registered on this view, skipping")
            }
        }

        // ===== ===== =====

        binding?.resultSearch?.isGone = storedData.name.isBlank()
        binding?.resultSearch?.setOnClickListener {
            android.util.Log.d(
                "RESULT_SEARCH_REDIRECT",
                "resultSearch clicked - navigating to main search with query: '${storedData.name}'"
            )
            val activity = activity
            if (activity is com.lagradost.cloudstream3.MainActivity) {
                com.lagradost.cloudstream3.MainActivity.nextSearchQuery = storedData.name
                val bottomNav =
                    activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                            R.id.nav_view
                        )
                    val navRail =
                        activity.findViewById<com.google.android.material.navigationrail.NavigationRailView>(
                            R.id.nav_rail_view
                        )
                    bottomNav?.selectedItemId = R.id.navigation_search
                    navRail?.selectedItemId = R.id.navigation_search
                }
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
                resultEpisodes.layoutManager = LinearLayoutManager(view.context)
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

                // Scroll-to-load-more: detect when user scrolls near bottom
                resultEpisodes.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (dy > 0) { // Only trigger when scrolling down
                            val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                            layoutManager?.let {
                                val visibleItemCount = it.childCount
                                val totalItemCount = it.itemCount
                                val firstVisibleItemPosition = it.findFirstVisibleItemPosition()

                                // Trigger load more when scrolled to near bottom (5 items from end)
                                if (visibleItemCount + firstVisibleItemPosition >= totalItemCount - 5) {
                                    viewModel.loadMoreEpisodes()
                                }
                            }
                        }
                    }
                })

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
                // [PANEL_FIX] Use view tag to prevent duplicate initialization
                val isLockStateInitialized = resultOverlappingPanels.getTag(PANEL_LISTENER_TAG_KEY.hashCode() + 1) as? Boolean ?: false
                android.util.Log.d("[PANEL_LOCK_DEBUG]", "Panel lock state check - isLockStateInitialized: $isLockStateInitialized")
                if (!isLockStateInitialized) {
                    android.util.Log.d("[MINI_SYNC_PANEL]", "Initializing panel lock states - setting both panels to CLOSE")
                    resultOverlappingPanels.setStartPanelLockState(OverlappingPanelsLayout.LockState.CLOSE)
                    resultOverlappingPanels.setEndPanelLockState(OverlappingPanelsLayout.LockState.CLOSE)
                    resultOverlappingPanels.setTag(PANEL_LISTENER_TAG_KEY.hashCode() + 1, true)
                    android.util.Log.d("[MINI_SYNC_PANEL]", "Panel lock states initialized")
                } else {
                    android.util.Log.d("[MINI_SYNC_PANEL]", "Panel lock states already initialized, skipping")
                }
                resultBack.setOnClickListener {
                    activity?.popCurrentPage()
                }

                activity?.attachBackPressedCallback(this@ResultFragmentPhone.toString()) {
                    val panelState = resultOverlappingPanels.getSelectedPanel().ordinal
                    android.util.Log.d("[MINI_SYNC_PANEL]", "Back pressed - current panel state: $panelState")
                    if (panelState == 1) {
                        android.util.Log.d("[MINI_SYNC_PANEL]", "Back pressed with panel open - running default action")
                        runDefault()
                    } else {
                        android.util.Log.d("[MINI_SYNC_PANEL]", "Back pressed with panel closed - closing panels")
                        resultOverlappingPanels.closePanels()
                    }
                }

                resultMiniSync.setOnClickListener {
                    // [RACE_CONDITION_FIX] Debounce rapid clicks
                    val now = System.currentTimeMillis()
                    if (now - lastSyncButtonClick < SYNC_CLICK_DEBOUNCE_MS) {
                        android.util.Log.d("[SYNC_CLICK_DEBUG]", "Click debounced - too soon (${now - lastSyncButtonClick}ms)")
                        return@setOnClickListener
                    }
                    lastSyncButtonClick = now

                    // Check if user is logged in before opening sync panel
                    val syncIds = syncModel.getSyncs()
                    if (syncIds.isEmpty()) {
                        showToast("Login to sync")
                        return@setOnClickListener
                    }

                    val currentPanelState = resultOverlappingPanels.getSelectedPanel()
                    val currentPanelStateOrdinal = currentPanelState.ordinal
                    val buttonVisibility = binding?.resultMiniSync?.isVisible
                    android.util.Log.d("[SYNC_CLICK_DEBUG]", "resultMiniSync clicked - panel state: $currentPanelState (ordinal: $currentPanelStateOrdinal), button visible: $buttonVisibility, sync ids: ${syncIds.keys}")

                    if (currentPanelStateOrdinal == 1) {
                        android.util.Log.d("[SYNC_CLICK_DEBUG]", "Opening start panel - preparing sync data")
                        // FIX: Check if we need to refresh sync data when opening from cache
                        // If we have sync IDs but userData is null/loading, trigger a refresh
                        val hasSyncIds = syncIds.isNotEmpty()
                        val userData = syncModel.userData.value
                        val isEmptySuccess = userData is Resource.Success && userData.value is com.lagradost.cloudstream3.syncproviders.SyncAPI.EmptySyncStatus
                        val needsRefresh = hasSyncIds && (userData == null || userData is Resource.Loading || (userData is Resource.Failure && !isEmptySuccess))
                        
                        android.util.Log.d("[MINI_SYNC_DATA]", "Sync data validation - hasSyncIds: $hasSyncIds, userData: ${userData?.javaClass?.simpleName ?: "null"}, isEmptySuccess: $isEmptySuccess, needsRefresh: $needsRefresh")
                        
                        if (needsRefresh) {
                            android.util.Log.d("[MINI_SYNC_FIX]", "Sync data needs refresh - has IDs but user data is: ${userData?.javaClass?.simpleName ?: "null"}")
                            android.util.Log.d("[MINI_SYNC_DATA]", "Triggering updateUserData() and updateSynced()")
                            syncModel.updateUserData()
                            syncModel.updateSynced()
                        } else {
                            android.util.Log.d("[MINI_SYNC_FIX]", "Sync data OK - hasSyncIds=$hasSyncIds, userData=${userData?.javaClass?.simpleName ?: "null"}")
                        }
                        
                        android.util.Log.d("[MINI_SYNC_PANEL]", "Unlocking start panel and requesting layout")
                        // [SIMKL_BUG_FIX] Unlock both panels and force layout refresh before opening
                        resultOverlappingPanels.setStartPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
                        android.util.Log.d("[PANEL_LOCK_DEBUG]", "Set start panel lock state to UNLOCKED")
                        resultOverlappingPanels.setEndPanelLockState(OverlappingPanelsLayout.LockState.UNLOCKED)
                        android.util.Log.d("[PANEL_LOCK_DEBUG]", "Set end panel lock state to UNLOCKED")
                        resultOverlappingPanels.requestLayout()
                        resultOverlappingPanels.invalidate()

                        android.util.Log.d("[MINI_SYNC_PANEL]", "Scheduling panel open via post()")
                        resultOverlappingPanels.post {
                            android.util.Log.d("[MINI_SYNC_PANEL]", "Executing panel open - current panel state before open: ${resultOverlappingPanels.getSelectedPanel()}")

                            // [PANEL_FIX] Always attempt to open - library handles duplicates gracefully
                            // getSelectedPanel() is unreliable (returns START even when locked closed)
                            val opened = resultOverlappingPanels.openStartPanel()
                            android.util.Log.d("[MINI_SYNC_PANEL]", "openStartPanel returned: $opened, panel state after open: ${resultOverlappingPanels.getSelectedPanel()}")
                        }
                    } else {
                        android.util.Log.d("[SYNC_CLICK_DEBUG]", "Closing panels")
                        android.util.Log.d("[MINI_SYNC_PANEL]", "Panel close initiated - current panel state: ${resultOverlappingPanels.getSelectedPanel()}")
                        resultOverlappingPanels.closePanels()
                        resultOverlappingPanels.post {
                            android.util.Log.d("[MINI_SYNC_PANEL]", "Panel close completed (in post) - panel state after close: ${resultOverlappingPanels.getSelectedPanel()}")
                        }
                    }
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
                    android.util.Log.d(
                        "[SUBSCRIBE_DEBUG]",
                        "ResultFragmentPhone - Subscribe button CLICKED - current status: ${viewModel.subscribeStatus.value}"
                    )
                    viewModel.toggleSubscriptionStatus(context) { newStatus: Boolean? ->
                        android.util.Log.d(
                            "[SUBSCRIBE_DEBUG]",
                            "ResultFragmentPhone - toggleSubscriptionStatus callback - newStatus: $newStatus"
                        )
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
                        android.util.Log.d(
                            "[SUBSCRIBE_DEBUG]",
                            "ResultFragmentPhone - showing toast - message: ${if (newStatus) "subscription_new" else "subscription_deleted"}, name: $name"
                        )
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
                                    val showCastButton = act.let { ctx ->
                                        PreferenceManager.getDefaultSharedPreferences(ctx)
                                            .getBoolean(ctx.getString(R.string.show_cast_key), true)
                                    } ?: true
                                    isGone = !it.isSuccessful || !showCastButton
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
                            resultNextSeriesButton.text =
                                context?.getString(R.string.action_continue)
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
                android.util.Log.d(
                    "[SUBSCRIBE_DEBUG]",
                    "ResultFragmentPhone - subscribeStatus observer - isSubscribed: $isSubscribed"
                )
                // UI RESILIENCE: Always show the subscribe button
                binding?.resultSubscribe?.isVisible = true
                
                // If no subscription data, show as unsubscribed (can click to subscribe)
                val displaySubscribed = isSubscribed ?: false
                android.util.Log.d(
                    "[SUBSCRIBE_DEBUG]",
                    "ResultFragmentPhone - subscribeStatus observer - button visibility set to: true, displaySubscribed: $displaySubscribed"
                )
                if (isSubscribed == null) {
                    // Show as not subscribed - user can click to subscribe
                    // Don't return early, allow the click handler to work
                }

                val drawable = if (isSubscribed == true) {
                    R.drawable.ic_baseline_notifications_active_24
                } else {
                    R.drawable.baseline_notifications_none_24
                }
                android.util.Log.d(
                    "[SUBSCRIBE_DEBUG]",
                    "ResultFragmentPhone - subscribeStatus observer - setting drawable: ${if (isSubscribed == true) "notifications_active" else "notifications_none"}"
                )

                binding?.resultSubscribe?.setImageResource(drawable)
            }

            observeNullable(viewModel.favoriteStatus) { isFavorite ->
                // Check settings to show/hide Favorites button
                val showFavoritesButton = context?.let { ctx ->
                    PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getBoolean(getString(R.string.show_favorites_key), true)
                } ?: true

                // UI RESILIENCE: Always show the favorite button if setting is enabled
                binding?.resultFavorite?.isVisible = showFavoritesButton
                
                // If no favorite data, show as not favorited
                val displayFavorite = isFavorite ?: false
                val drawable = if (displayFavorite) {
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
                android.util.Log.d(
                    "MetadataSwap",
                    "Observer triggered with data: ${data?.javaClass?.simpleName}"
                )
                if (data == null) {
                    android.util.Log.d("MetadataSwap", "Observer received null, returning early")
                    return@observe
                }
                android.util.Log.d(
                    "MetadataSwap",
                    "Observer received data: ${(data as? Resource.Success)?.value?.titleText}"
                )
                resultBinding?.apply {
                    android.util.Log.d("[UPDATEUI_DEBUG]", "updateUI observer fired - resultCastItems: ${resultCastItems != null}, viewId: ${resultCastItems?.id}")
                    PanelsChildGestureRegionObserver.Provider.get().apply {
                        // [PANEL_FIX] Always try to unregister first, then register
                        // This prevents "already registered" errors when fragment recreates
                        try {
                            unregister(resultCastItems)
                            android.util.Log.d("[UPDATEUI_DEBUG]", "Unregistered resultCastItems in updateUI")
                        } catch (e: Exception) {
                            android.util.Log.d("[UPDATEUI_DEBUG]", "Unregister failed in updateUI: ${e.message}")
                        }
                        try {
                            register(resultCastItems)
                            isCastItemsRegistered = true
                            android.util.Log.d("[GESTURE_DEBUG]", "Registered resultCastItems in updateUI")
                        } catch (e: Exception) {
                            android.util.Log.d("[GESTURE_DEBUG]", "Failed to register resultCastItems: ${e.message}")
                        }
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

                        // FIX: Always process sync data from API response, even if some IDs already exist
                        // This ensures we get all sync providers (simkl, kitsu) from the response
                        // [SIMKL_DEFINITIVE_FIX] Use blocking version since we're in an observer callback
                        val hadNewSyncData = syncModel.addSyncsBlocking(d.syncData)
                        if (hadNewSyncData) {
                            android.util.Log.d("[MINI_SYNC_FIX]", "Processing API sync data - new: $hadNewSyncData, total: ${d.syncData}")
                            
                                                        
                            // FIX: Update the cache with full sync data so cached loads have complete data
                            viewModel.currentRepo?.updateCacheSyncData(d.url, d.syncData)
                            
                            // FIX: Also update offline cache (DownloadHeaderCached) with sync data
                            // This ensures offline/cached loads have complete sync data
                            try {
                                if (d.syncData.isNotEmpty()) {
                                    android.util.Log.d("[MINI_SYNC_FIX]", "OFFLINE_CACHE_DEBUG: url=${d.url}, syncData=${d.syncData}")
                                    
                                    // FIX: Search ALL cache entries to find matching URL
                                    // The cache key may be url, parentId, or session-based - we need to find the right one
                                    val allKeys = com.lagradost.cloudstream3.CloudStreamApp.getKeys(com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE) ?: emptySet()
                                    android.util.Log.d("[MINI_SYNC_FIX]", "OFFLINE_CACHE_DEBUG: total cache keys=${allKeys.size}")
                                    
                                    var updatedCount = 0
                                    for (key in allKeys) {
                                        val header = com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
                                            com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                                            key
                                        )
                                        // Match by URL (handle both direct URL and session URL)
                                        if (header?.url == d.url) {
                                            com.lagradost.cloudstream3.CloudStreamApp.setKey(
                                                com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                                                key,
                                                header.copy(syncData = d.syncData)
                                            )
                                            updatedCount++
                                            android.util.Log.d("[MINI_SYNC_FIX]", "OFFLINE_CACHE_DEBUG: Updated cache entry with key=$key")
                                        }
                                    }
                                    
                                    if (updatedCount > 0) {
                                        android.util.Log.d("[MINI_SYNC_FIX]", "Updated $updatedCount offline cache entries with sync data for url: ${d.url}")
                                    } else {
                                        // Try direct URL lookup as fallback (for new cache entries)
                                        val directHeader = com.lagradost.cloudstream3.CloudStreamApp.getKey<com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached>(
                                            com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                                            d.url
                                        )
                                        if (directHeader != null) {
                                            com.lagradost.cloudstream3.CloudStreamApp.setKey(
                                                com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE,
                                                d.url,
                                                directHeader.copy(syncData = d.syncData)
                                            )
                                            android.util.Log.d("[MINI_SYNC_FIX]", "Updated offline cache with sync data using direct URL key")
                                        } else {
                                            android.util.Log.d("[MINI_SYNC_FIX]", "OFFLINE_CACHE_DEBUG: No matching cache entry found for url: ${d.url}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("[MINI_SYNC_FIX]", "Failed to update offline cache: ${e.message}")
                            }
                            
                            syncModel.updateMetaAndUser()
                            syncModel.updateSynced()
                        } else {
                            syncModel.addFromUrl(d.url)
                        }

                        binding?.apply {
                            resultSearch.isGone = d.title.isBlank()
                            resultSearch.setOnClickListener {
                                android.util.Log.d(
                                    "RESULT_SEARCH_REDIRECT",
                                    "resultSearch clicked (recommendation) - navigating to main search with query: '${d.title}'"
                                )
                                val activity = activity
                                if (activity is com.lagradost.cloudstream3.MainActivity) {
                                    com.lagradost.cloudstream3.MainActivity.nextSearchQuery =
                                        d.title
                                    val bottomNav =
                                        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                                            R.id.nav_view
                                        )
                                    val navRail =
                                        activity.findViewById<com.google.android.material.navigationrail.NavigationRailView>(
                                            R.id.nav_rail_view
                                        )
                                    bottomNav?.selectedItemId = R.id.navigation_search
                                    navRail?.selectedItemId = R.id.navigation_search
                                }
                            }

                            // Check settings to show/hide Share button
                            val showShareButton = context?.let { ctx ->
                                PreferenceManager.getDefaultSharedPreferences(ctx)
                                    .getBoolean(getString(R.string.show_share_key), true)
                            } ?: true
                            resultShare.isVisible = showShareButton

                            resultShare.setOnClickListener {
                                try {
                                    val i = Intent(Intent.ACTION_SEND)
                                    val nameBase64 =
                                        base64Encode(
                                            d.apiName.toString().toByteArray(Charsets.UTF_8)
                                        )
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

                    // Hide bookmark FAB during metadata swap mode
                    val hasOriginalResponse =
                        com.lagradost.cloudstream3.ui.result.ResultViewModel2.sharedOriginalResponse != null
                    val isSwapMode = viewModel.isMetadataSwapMode.value == true
                    val shouldHideBookmark = hasOriginalResponse || isSwapMode
                    binding?.resultBookmarkFab?.isVisible =
                        data is Resource.Success && !shouldHideBookmark
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
            observe(syncModel.synced) { list ->
                android.util.Log.d("[SYNC_OBSERVER_LIFECYCLE]", "syncModel.synced observer fired - list size: ${list.size}, binding: ${binding != null}, resultMiniSync: ${binding?.resultMiniSync != null}")
                syncBinding?.resultSyncNames?.text = "Sync"

                // Note: Status text is now handled by providersWithValidStatus observer
                // Do not set resultSyncStatus here to avoid conflicts

                val newList = list.filter { it.isSynced && it.hasAccount }

                // Show bell icon only if sync data is available (sync IDs exist)
                val syncIds = syncModel.getSyncs()
                val shouldBeVisible = syncIds.isNotEmpty()
                binding?.resultMiniSync?.isVisible = shouldBeVisible

                // Populate provider selector dropdown
                syncBinding?.resultSyncProviderSelector?.let { spinner ->
                    val providersWithAccounts = list.filter { it.hasAccount }
                    val providerNames = providersWithAccounts.map { it.name }
                    val providerPrefixes = providersWithAccounts.map { it.idPrefix }
                    
                    if (providerNames.isNotEmpty()) {
                        val adapter = ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_spinner_item,
                            providerNames.toMutableList().apply { add(0, "All Providers") }
                        )
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        spinner.adapter = adapter
                        
                        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Spinner onItemSelected - position: $position")
                                if (position == 0) {
                                    // "All Providers" selected - trigger initial load behavior
                                    android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "All Providers selected - calling updateUserData")
                                    syncModel.setSelectedProvider(null)
                                    syncModel.updateUserData()
                                } else {
                                    // Specific provider selected
                                    val selectedPrefix = providerPrefixes[position - 1]
                                    android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Setting selectedProvider to: $selectedPrefix")
                                    syncModel.setSelectedProvider(selectedPrefix)
                                }
                            }
                            
                            override fun onNothingSelected(parent: AdapterView<*>?) {
                                android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Spinner onNothingSelected")
                                syncModel.setSelectedProvider(null)
                            }
                        }
                    }
                }

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
                android.util.Log.d("[SYNC_METADATA_DEBUG]", "Metadata observer fired: ${meta?.javaClass?.simpleName}")
                when (meta) {
                    is Resource.Success -> {
                        val d = meta.value
                        android.util.Log.d("[SYNC_METADATA_DEBUG]", "Metadata Success - totalEpisodes: ${d.totalEpisodes}, publicScore: ${d.publicScore}")
                        syncBinding?.resultSyncEpisodes?.progress = currentSyncProgress * 1000
                        setSyncMaxEpisodes(d.totalEpisodes)

                        viewModel.setMeta(d, syncModel.getSyncs())
                    }

                    is Resource.Loading -> {
                        android.util.Log.d("[SYNC_METADATA_DEBUG]", "Metadata Loading")
                        syncBinding?.resultSyncMaxEpisodes?.text =
                            syncBinding?.resultSyncMaxEpisodes?.context?.getString(R.string.sync_total_episodes_none)
                    }

                    is Resource.Failure -> {
                        android.util.Log.d("[SYNC_METADATA_DEBUG]", "Metadata Failure: ${meta.errorString}")
                    }

                    null -> {
                        android.util.Log.d("[SYNC_METADATA_DEBUG]", "Metadata null")
                    }
                }
            }


            observe(syncModel.userData) { status ->
                android.util.Log.d("[SYNC_USERDATA_DEBUG]", "UserData observer fired: ${status?.javaClass?.simpleName}")
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
                            
                            val d = status.value
                            val selectedProvider = syncModel.selectedProvider.value
                            
                            // Check if user is not logged in (EmptySyncStatus)
                            if (d is SyncAPI.EmptySyncStatus) {
                                resultSyncHolder.isVisible = false
                                // Don't show toast here - only show when user clicks sync button
                                closed = true
                            } else {
                                resultSyncHolder.isVisible = true
                                
                                // Check if entry is not synced with the selected provider
                                val isNotSynced = d.status == SyncWatchType.NONE && d.watchedEpisodes == 0
                                if (isNotSynced && selectedProvider != null) {
                                    // Show "Not tracked in [Provider]" message
                                    val providerName = syncModel.synced.value?.firstOrNull { it.idPrefix == selectedProvider.lowercase() }?.name ?: selectedProvider
                                    resultSyncStatus.text = "Not tracked in $providerName"
                                    resultSyncStatus.isVisible = true
                                } else if (selectedProvider != null) {
                                    // Show "Synced to [Provider]" when synced
                                    val providerName = syncModel.synced.value?.firstOrNull { it.idPrefix == selectedProvider.lowercase() }?.name ?: selectedProvider
                                    resultSyncStatus.text = "Synced to $providerName"
                                    resultSyncStatus.isVisible = true
                                }
                                // Note: For "All providers" (selectedProvider == null), status text is handled by providersWithValidStatus observer
                                
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
                                    Editable.Factory.getInstance()
                                        ?.newEditable(watchedEpisodes.toString())
                                safe { // format might fail
                                    val text = d.score?.toFloat(10)?.roundToInt()?.let {
                                        context?.getString(R.string.sync_score_format)?.format(it)
                                    } ?: "?"
                                    resultSyncScoreText.text = text
                                }
                            }
                        }

                        null -> {
                            closed = false
                        }
                    }
                }
                binding?.resultOverlappingPanels?.setStartPanelLockState(if (closed) OverlappingPanelsLayout.LockState.CLOSE else OverlappingPanelsLayout.LockState.UNLOCKED)
            }
            observe(syncModel.successMessage) { message ->
                if (message != null) {
                    showToast(message)
                    syncModel.clearSuccessMessage()
                }
            }
            observe(syncModel.isSyncing) { isSyncing ->
                syncBinding?.apply {
                    if (isSyncing) {
                        resultSyncLoadingShimmer.startShimmer()
                        resultSyncLoadingShimmer.isVisible = true
                        resultSyncHolder.isVisible = false
                    } else {
                        resultSyncLoadingShimmer.stopShimmer()
                        resultSyncLoadingShimmer.isVisible = false
                        resultSyncHolder.isVisible = true
                    }
                }
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
                loadingDialog?.findViewById<MaterialButton>(R.id.overlay_loading_skip_button)
                    ?.apply {
                        if (load.linksLoaded <= 0) {
                            isInvisible = true
                        } else {
                            setOnClickListener {
                                viewModel.skipLoading()
                            }
                            isVisible = true
                            text =
                                "${context.getString(R.string.skip_loading)} (${load.linksLoaded})"
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
                resultBinding?.apply {
                    // Only show when there are multiple dub statuses
                    resultDubSelect.visibility = if (range.size > 1) android.view.View.VISIBLE else android.view.View.GONE
                }
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
                resultBinding?.apply {
                    // Only show when there are more than 1 range
                    resultEpisodeSelect.visibility = if (range.size > 1) android.view.View.VISIBLE else android.view.View.GONE
                }
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
                resultBinding?.apply {
                    // Only show when there are multiple seasons
                    resultSeasonButton.visibility = if (seasonList.size > 1) android.view.View.VISIBLE else android.view.View.GONE
                }
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

            // Function to update sync panel based on provider selection
            fun updateSyncPanelForProvider(provider: String?) {
                syncBinding?.apply {
                    if (provider == null) {
                        // "All providers" selected - show loading skeleton briefly then reset to defaults
                        resultSyncLoadingShimmer.startShimmer()
                        resultSyncLoadingShimmer.isVisible = true
                        resultSyncHolder.isVisible = false
                        
                        // Delay slightly to show loading state, then reset to defaults
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            resultSyncLoadingShimmer.stopShimmer()
                            resultSyncLoadingShimmer.isVisible = false
                            resultSyncHolder.isVisible = true
                            
                            // Show default layout (episodes: 0, rating: 0)
                            resultSyncCurrentEpisodes.setText("0")
                            resultSyncScoreText.text = "0/10"
                            resultSyncRating.value = 0f
                            
                            // Show which providers the entry is actually tracked based on valid status
                            val validProviderPrefixes = syncModel.providersWithValidStatus.value ?: emptySet()
                            val syncedProviders = syncModel.synced.value?.filter { 
                                it.idPrefix in validProviderPrefixes && it.hasAccount 
                            }
                            if (syncedProviders != null && syncedProviders.isNotEmpty()) {
                                resultSyncStatus.text = "Currently tracked on ${syncedProviders.joinToString { it.name }}"
                                resultSyncStatus.isVisible = true
                            } else {
                                resultSyncStatus.isVisible = false
                            }
                        }, 300)
                    } else {
                        // Specific provider selected - fetch status immediately
                        // Show loading skeleton while fetching
                        resultSyncLoadingShimmer.startShimmer()
                        resultSyncLoadingShimmer.isVisible = true
                        resultSyncHolder.isVisible = false
                        syncModel.fetchProviderStatus(provider)
                    }
                }
            }

            // Observe selected provider to update UI based on selection
            observe(syncModel.selectedProvider) { provider ->
                android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Selected provider: $provider")
                updateSyncPanelForProvider(provider)
            }
            
            // Observe providers with valid status to update "All providers" text
            observe(syncModel.providersWithValidStatus) { validProviders ->
                android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Providers with valid status observer fired: $validProviders, selectedProvider: ${syncModel.selectedProvider.value}")
                syncBinding?.apply {
                    val syncedProviders = syncModel.synced.value?.filter { 
                        it.idPrefix in validProviders && it.hasAccount 
                    }
                    android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Filtered synced providers: ${syncedProviders?.map { it.name }}")
                    if (syncedProviders != null && syncedProviders.isNotEmpty()) {
                        resultSyncStatus.text = "Currently tracked on ${syncedProviders.joinToString { it.name }}"
                        resultSyncStatus.isVisible = true
                        android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Updated status text to: ${resultSyncStatus.text}")
                    } else {
                        resultSyncStatus.isVisible = false
                        android.util.Log.d("[SYNC_PROVIDER_DEBUG]", "Hiding status text - no synced providers")
                    }
                }
            }
        }

    }}