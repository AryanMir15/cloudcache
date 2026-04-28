package com.lagradost.cloudstream3.ui.browse

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.databinding.FragmentBrowseBinding
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.ui.AniListFilterUtils
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import java.util.Locale
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import androidx.lifecycle.observe
import kotlinx.coroutines.launch

class BrowseFragment : BaseFragment<FragmentBrowseBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentBrowseBinding::inflate)
) {

    private val viewModel: BrowseViewModel by activityViewModels()

    // AniList filter state - synced with ViewModel
    private var selectedGenres = mutableSetOf<String>()
    private var excludedGenres = mutableSetOf<String>()
    private var selectedTags = mutableSetOf<String>()
    private var excludedTags = mutableSetOf<String>()
    private var selectedYear = "All"
    private var selectedSeason = "All"
    private var selectedFormat = "All"
    private var selectedSort = "Popularity"
    private var selectedNsfw = false

    // Track chip visibility to only update margin when it actually changes
    private var wereChipsVisible = false
    
    // Track last known top bar height to prevent redundant padding updates
    private var lastKnownTopBarHeight = -1
    
    // Track ongoing padding animation to prevent conflicts
    private var isAnimatingPadding = false

    private var resultsList = emptyList<SearchResponse>()
    private var currentAniListPage = 1
    private var hasMoreResults = false
    private var isLoadingMoreResults = false
    private var searchQuery: String? = null

    // RecyclerView layout state preservation
    private var recyclerViewLayoutState: android.os.Parcelable? = null
    private companion object {
        private const val RECYCLER_LAYOUT_STATE_KEY = "recycler_layout_state"
    }

    // Top bar hide/show on scroll
    private var isTopBarVisible = true
    private var isAnimatingTopBar = false
    private var scrollAccumulator = 0 // Track total scroll distance

    private val aniListApi = AccountManager.aniListApi

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    binding?.browseSearch?.setQuery(recognizedText, true)
                }
            }
        }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padTop = true,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Set span count based on user preference
        val currentSpan = view.context.getSpanCount()
        binding?.browseResults?.spanCount = currentSpan
    }

    override fun onBindingCreated(
        binding: FragmentBrowseBinding,
        savedInstanceState: Bundle?
    ) {
        android.util.Log.d("BrowseFragment", "========== onBindingCreated called ==========")
        android.util.Log.d("BrowseFragment", "onBindingCreated: savedInstanceState=$savedInstanceState")
        android.util.Log.d("BrowseFragment", "onBindingCreated: viewModel.uiState.value.results.size=${viewModel.uiState.value?.results?.size}")
        android.util.Log.d("BrowseFragment", "onBindingCreated: viewModel.uiState.value.isLoading=${viewModel.uiState.value?.isLoading}")
        android.util.Log.d("BrowseFragment", "onBindingCreated: viewModel.uiState.value.hasMore=${viewModel.uiState.value?.hasMore}")
        android.util.Log.d("BrowseFragment", "onBindingCreated: viewModel.uiState.value.currentPage=${viewModel.uiState.value?.currentPage}")

        // Load saved default filters
        loadDefaultFilters()

        setupUI()
        // Auto-load results with default filters only if ViewModel has no data
        if (viewModel.uiState.value?.results?.isEmpty() == true) {
            android.util.Log.d("BrowseFragment", "onBindingCreated: ViewModel has no data, calling loadAniListResults()")
            loadAniListResults()
        } else {
            android.util.Log.d("BrowseFragment", "onBindingCreated: ViewModel has data, restoring state")
            android.util.Log.d("STATE_SYNC_FIX", "========== Starting filter state restoration from ViewModel ==========")
            // Restore filter state from ViewModel
            resultsList = viewModel.uiState.value?.results ?: emptyList()
            currentAniListPage = viewModel.uiState.value?.currentPage ?: 1
            hasMoreResults = viewModel.uiState.value?.hasMore ?: false

            // Restore filter selections
            val filters = viewModel.uiState.value?.filters
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: ViewModel filters = $filters")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: ViewModel filters.genres = ${filters?.genres}")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: ViewModel filters.tags = ${filters?.tags}")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: ViewModel filters.excludedGenres = ${filters?.excludedGenres}")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: ViewModel filters.excludedTags = ${filters?.excludedTags}")
            
            selectedGenres = filters?.genres?.toMutableSet() ?: mutableSetOf()
            selectedTags = filters?.tags?.toMutableSet() ?: mutableSetOf()
            excludedGenres = filters?.excludedGenres?.toMutableSet() ?: mutableSetOf()
            excludedTags = filters?.excludedTags?.toMutableSet() ?: mutableSetOf()
            selectedYear = filters?.year ?: "All"
            selectedSeason = filters?.season ?: "All"
            selectedFormat = filters?.format ?: "All"
            selectedSort = filters?.sort ?: "Popularity"
            
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local selectedGenres = $selectedGenres")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local selectedTags = $selectedTags")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local excludedGenres = $excludedGenres")
            android.util.Log.d("STATE_SYNC_FIX", "onBindingCreated: Restored local excludedTags = $excludedTags")
            android.util.Log.d("STATE_SYNC_FIX", "========== Filter state restoration completed ==========")

            updateUI()
        }

        // Observe ViewModel state changes
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            android.util.Log.d("BrowseFragment", "ViewModel state changed: results=${state.results.size}, isLoading=${state.isLoading}, hasMore=${state.hasMore}, currentPage=${state.currentPage}")
            resultsList = state.results
            currentAniListPage = state.currentPage
            hasMoreResults = state.hasMore
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onResume called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: MainActivity.nextSearchQuery = ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: searchQuery = $searchQuery")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: local genres=$selectedGenres, excludedGenres=$excludedGenres")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: ViewModel filters=${viewModel.uiState.value?.filters}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: resultsList size = ${resultsList.size}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: currentAniListPage = $currentAniListPage")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: hasMoreResults = $hasMoreResults")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isLoading = ${viewModel.uiState.value?.isLoading}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isLoadingMoreResults = $isLoadingMoreResults")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isAdded = ${isAdded}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isDetached = ${isDetached}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: view != null = ${view != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: activity != null = ${activity != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: context != null = ${context != null}")
        
        // Force RecyclerView to recalculate layout after configuration change
        // Since fragment is retained, we need to manually trigger layout recalculation
        binding?.browseResults?.let { recyclerView ->
            android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Scheduling RecyclerView layout recalculation")
            recyclerView.post {
                android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Forcing RecyclerView layout recalculation")
                android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: RecyclerView width = ${recyclerView.width}, height = ${recyclerView.height}")
                recyclerView.layoutManager?.requestLayout()
                recyclerView.adapter?.notifyDataSetChanged()
            }
        }
        
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onResume completed ==========")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onDestroy called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isAdded = ${isAdded}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isDetached = ${isDetached}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: view != null = ${view != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: activity != null = ${activity != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: context != null = ${context != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onDestroy completed ==========")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onCreate called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: savedInstanceState=$savedInstanceState")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isAdded = ${isAdded}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isDetached = ${isDetached}")
        
        // Restore RecyclerView layout state
        savedInstanceState?.let { bundle ->
            recyclerViewLayoutState = bundle.getParcelable<android.os.Parcelable>(RECYCLER_LAYOUT_STATE_KEY)
            android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Restored RecyclerView layout state")
        }
        
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onCreate completed ==========")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onConfigurationChanged called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: New orientation = ${newConfig.orientation}")
        
        // Force RecyclerView to recalculate layout after configuration change
        binding?.browseResults?.let { recyclerView ->
            android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Scheduling RecyclerView layout recalculation")
            recyclerView.post {
                android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Forcing RecyclerView layout recalculation")
                android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: RecyclerView width = ${recyclerView.width}, height = ${recyclerView.height}")
                recyclerView.layoutManager?.requestLayout()
                recyclerView.adapter?.notifyDataSetChanged()
            }
        }
        
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onConfigurationChanged completed ==========")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onSaveInstanceState called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        
        // Save RecyclerView layout state
        binding?.browseResults?.layoutManager?.onSaveInstanceState()?.let { layoutState ->
            outState.putParcelable(RECYCLER_LAYOUT_STATE_KEY, layoutState)
            android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Saved RecyclerView layout state")
        } ?: android.util.Log.w("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: RecyclerView layout state is null, cannot save")
        
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onSaveInstanceState completed ==========")
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onPause called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: MainActivity.nextSearchQuery = ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isAdded = ${isAdded}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isDetached = ${isDetached}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: view != null = ${view != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: activity != null = ${activity != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: context != null = ${context != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onPause completed ==========")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onStop called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: MainActivity.nextSearchQuery = ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isAdded = ${isAdded}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isDetached = ${isDetached}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: view != null = ${view != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: activity != null = ${activity != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: context != null = ${context != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onStop completed ==========")
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onStart called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: MainActivity.nextSearchQuery = ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isAdded = ${isAdded}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isDetached = ${isDetached}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: view != null = ${view != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: activity != null = ${activity != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: context != null = ${context != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onStart completed ==========")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onDestroyView called ==========")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Fragment instance = ${this.hashCode()}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isAdded = ${isAdded}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: isDetached = ${isDetached}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: view != null = ${view != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: activity != null = ${activity != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: context != null = ${context != null}")
        android.util.Log.d("CONFIG_CHANGE_FIX", "========== BrowseFragment.onDestroyView completed ==========")
    }

    
    private fun navigateToSearch(query: String) {
        android.util.Log.d("NAV_STATE_LOSS_FIX", "========== navigateToSearch called ==========")
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: query = '$query'")
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: query length = ${query.length}")
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: query isBlank = ${query.isBlank()}")
        val activity = requireActivity()
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: activity = $activity")
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: activity is MainActivity = ${activity is com.lagradost.cloudstream3.MainActivity}")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Attempting to use navigation arguments instead of static variable")
        
        // Try to use NavController to navigate with arguments
        val navController = findNavController()
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: navController = $navController")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: currentDestination = ${navController.currentDestination?.id}")
        android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: search destination id = ${R.id.navigation_search}")
        
        if (navController.currentDestination?.id != R.id.navigation_search) {
            // We're not currently on SearchFragment, so we can navigate with arguments
            android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Not on SearchFragment, attempting navigation with arguments")
            try {
                val bundle = android.os.Bundle()
                bundle.putString("search_query", query)
                android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Created bundle with search_query = '$query'")
                
                // Navigate using NavController
                navController.navigate(R.id.navigation_search, bundle)
                android.util.Log.d("NAV_ARGS_FIX", "NAV_ARGS_FIX: Successfully navigated with bundle = $bundle")
                android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: Navigation with arguments completed")
                return
            } catch (e: Exception) {
                android.util.Log.e("NAV_ARGS_FIX", "NAV_ARGS_FIX: Navigation with arguments failed, falling back to static variable", e)
                android.util.Log.e("NAV_ARGS_FIX", "NAV_ARGS_FIX: Exception: ${e.message}")
            }
        } else {
            android.util.Log.w("NAV_ARGS_FIX", "NAV_ARGS_FIX: Already on SearchFragment, cannot navigate with arguments, using static variable fallback")
        }
        
        // Fallback to static variable (existing behavior)
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: Using static variable fallback")
        // Set the search query in MainActivity
        if (activity is com.lagradost.cloudstream3.MainActivity) {
            android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: MainActivity.nextSearchQuery before = ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
            
            // Safety check: warn if overwriting an existing query
            if (com.lagradost.cloudstream3.MainActivity.nextSearchQuery != null) {
                android.util.Log.w("NAV_STATE_LOSS_FIX", "navigateToSearch: WARNING - overwriting existing nextSearchQuery: ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
            }
            
            android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: setting MainActivity.nextSearchQuery = '$query'")
            com.lagradost.cloudstream3.MainActivity.nextSearchQuery = query
            android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: MainActivity.nextSearchQuery after = ${com.lagradost.cloudstream3.MainActivity.nextSearchQuery}")
        } else {
            android.util.Log.e("NAV_STATE_LOSS_FIX", "navigateToSearch: ERROR - activity is not MainActivity!")
        }
        
        // Navigate to Search tab using bottom navigation only
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: selecting Search tab in bottom navigation")
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.nav_view)
        val navRail = activity.findViewById<NavigationRailView>(R.id.nav_rail_view)
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: bottomNav = $bottomNav, navRail = $navRail")
        bottomNav?.selectedItemId = R.id.navigation_search
        navRail?.selectedItemId = R.id.navigation_search
        android.util.Log.d("NAV_STATE_LOSS_FIX", "navigateToSearch: tab selection completed")
        android.util.Log.d("NAV_STATE_LOSS_FIX", "========== navigateToSearch completed ==========")
    }

    private fun updateResultsPadding(estimatedHeight: Int? = null) {
        binding?.apply {
            // The topbar height already includes chips when they're visible
            // Just use the topbar height as the padding
            val topBarHeight = if (estimatedHeight != null) estimatedHeight else topBarContainer.height
            
            // Guard: skip if top bar height hasn't changed since last call (only for actual measurements)
            if (estimatedHeight == null && topBarHeight == lastKnownTopBarHeight && lastKnownTopBarHeight != -1) {
                android.util.Log.d("PADDING_DEBUG", "updateResultsPadding: SKIPPING - height unchanged ($topBarHeight)")
                return@apply
            }
            
            val targetPadding = topBarHeight
            val currentPadding = browseResults.paddingTop
            
            // Cancel ongoing animation only if it's also an estimated update
            // Don't cancel actual measurement animations
            if (isAnimatingPadding && estimatedHeight != null) {
                android.util.Log.d("PADDING_DEBUG", "updateResultsPadding: Cancelling ongoing estimated animation")
                browseResults.clearAnimation()
                isAnimatingPadding = false
            }
            
            // Skip if animation is already running for actual measurement
            if (isAnimatingPadding && estimatedHeight == null) {
                android.util.Log.d("PADDING_DEBUG", "updateResultsPadding: SKIPPING - actual measurement animation already running")
                return@apply
            }
            
            // Animate top bar height and RecyclerView padding together
            if (currentPadding != targetPadding) {
                android.util.Log.d("PADDING_DEBUG", "ANIMATING padding from $currentPadding to $targetPadding (estimated=${estimatedHeight != null})")
                isAnimatingPadding = true
                
                val animator = android.animation.ValueAnimator.ofInt(currentPadding, targetPadding)
                animator.duration = 200 // 200ms smooth animation
                animator.interpolator = android.view.animation.DecelerateInterpolator()
                animator.addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Int
                    // Animate RecyclerView padding
                    browseResults.setPadding(
                        browseResults.paddingLeft,
                        animatedValue,
                        browseResults.paddingRight,
                        browseResults.paddingBottom
                    )
                    // Animate top bar height if this is an estimated update (we control the height)
                    if (estimatedHeight != null) {
                        topBarContainer.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        topBarContainer.minimumHeight = animatedValue
                    }
                }
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        isAnimatingPadding = false
                        android.util.Log.d("PADDING_DEBUG", "Animation completed")
                        // Reset minimum height after animation
                        if (estimatedHeight != null) {
                            topBarContainer.minimumHeight = 0
                        }
                        // Scroll to top after animation completes
                        browseResults.scrollToPosition(0)
                    }
                })
                animator.start()
            } else {
                android.util.Log.d("PADDING_DEBUG", "SKIPPING - padding already correct")
            }
            
            // Update last known height only if this is the actual height (not estimated)
            if (estimatedHeight == null) {
                lastKnownTopBarHeight = topBarHeight
            }
        }
    }

    private fun setupUI() {
        android.util.Log.d("BrowseFragment", "========== setupUI called ==========")
        binding?.apply {
            // Initialize loading bar
            browseSearchLoadingBar.alpha = 0f
            android.util.Log.d("BrowseFragment", "setupUI: Initialized loading bar alpha to 0f")

            // Setup search bar
            browseSearch.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    android.util.Log.d("BrowseFragment", "onQueryTextSubmit: query=$query")
                    searchQuery = query
                    currentAniListPage = 1
                    android.util.Log.d("BrowseFragment", "onQueryTextSubmit: currentAniListPage reset to 1, calling loadAniListResults()")
                    loadAniListResults()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    android.util.Log.d("BrowseFragment", "onQueryTextChange: newText=$newText")
                    return true
                }
            })

            // Setup filter button click listener
            filterButton.setOnClickListener {
                android.util.Log.d("BrowseFragment", "filterButton clicked")
                showFilterDialog()
            }

            // Setup voice search click listener
            voiceSearch.setOnClickListener { view ->
                view?.context?.let { ctx ->
                    try {
                        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                            Toast.makeText(ctx, R.string.speech_recognition_unavailable, Toast.LENGTH_SHORT).show()
                        } else {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                putExtra(
                                    RecognizerIntent.EXTRA_PROMPT,
                                    ctx.getString(R.string.begin_speaking)
                                )
                            }
                            speechRecognizerLauncher.launch(intent)
                        }
                    } catch (_: Throwable) {
                        Toast.makeText(ctx, R.string.speech_recognition_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Setup results grid
            val adapter = SearchAdapter(
                browseResults, // RecyclerView from layout
                isHorizontal = false,
            ) { callback ->
                // Navigate to Search tab with title as query
                val title = callback.card.name
                android.util.Log.d("GENRE_FILTER_REDIRECT", "SearchAdapter callback triggered")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "SearchAdapter callback: title = '$title'")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "SearchAdapter callback: callback.action = ${callback.action}")
                android.util.Log.d("GENRE_FILTER_REDIRECT", "SearchAdapter callback: callback.card = ${callback.card}")
                navigateToSearch(title)
            }

            browseResults.setRecycledViewPool(SearchAdapter.sharedPool)
            browseResults.adapter = adapter
            android.util.Log.d("BrowseFragment", "setupUI: Set adapter to browseResults")

            // Restore RecyclerView layout state if available
            recyclerViewLayoutState?.let { state ->
                try {
                    browseResults.layoutManager?.onRestoreInstanceState(state)
                    android.util.Log.d("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Restored RecyclerView layout state to layoutManager")
                } catch (e: Exception) {
                    android.util.Log.e("CONFIG_CHANGE_FIX", "CONFIG_CHANGE_FIX: Failed to restore RecyclerView layout state", e)
                }
                recyclerViewLayoutState = null // Clear after restoration
            }

            // Debug: Log top bar container and children
            topBarContainer.post {
                android.util.Log.d("BrowseFragment", "========== TOP BAR DEBUG ==========")
                android.util.Log.d("BrowseFragment", "topBarContainer height: ${topBarContainer.height}")
                android.util.Log.d("BrowseFragment", "topBarContainer width: ${topBarContainer.width}")
                android.util.Log.d("BrowseFragment", "topBarContainer elevation: ${topBarContainer.elevation}")
                android.util.Log.d("BrowseFragment", "topBarContainer child count: ${topBarContainer.childCount}")
                
                for (i in 0 until topBarContainer.childCount) {
                    val child = topBarContainer.getChildAt(i)
                    android.util.Log.d("BrowseFragment", "Child $i: ${child.javaClass.simpleName}")
                    android.util.Log.d("BrowseFragment", "  - height: ${child.height}")
                    android.util.Log.d("BrowseFragment", "  - width: ${child.width}")
                    android.util.Log.d("BrowseFragment", "  - visibility: ${child.visibility}")
                    android.util.Log.d("BrowseFragment", "  - elevation: ${child.elevation}")
                    android.util.Log.d("BrowseFragment", "  - isClickable: ${child.isClickable}")
                    android.util.Log.d("BrowseFragment", "  - isFocusable: ${child.isFocusable}")
                    android.util.Log.d("BrowseFragment", "  - alpha: ${child.alpha}")
                }
                android.util.Log.d("BrowseFragment", "=====================================")

                // Debug: Log SearchView internal structure
                android.util.Log.d("BrowseFragment", "========== SEARCH VIEW DEBUG ==========")
                android.util.Log.d("BrowseFragment", "browseSearch height: ${browseSearch.height}")
                android.util.Log.d("BrowseFragment", "browseSearch width: ${browseSearch.width}")
                android.util.Log.d("BrowseFragment", "browseSearch child count: ${browseSearch.childCount}")
                android.util.Log.d("BrowseFragment", "browseSearch.isIconified: ${browseSearch.isIconified}")
                android.util.Log.d("BrowseFragment", "browseSearch.query: '${browseSearch.query}'")
                
                for (i in 0 until browseSearch.childCount) {
                    val child = browseSearch.getChildAt(i)
                    android.util.Log.d("BrowseFragment", "SearchView Child $i: ${child.javaClass.simpleName}")
                    android.util.Log.d("BrowseFragment", "  - height: ${child.height}")
                    android.util.Log.d("BrowseFragment", "  - width: ${child.width}")
                    android.util.Log.d("BrowseFragment", "  - visibility: ${child.visibility}")
                    android.util.Log.d("BrowseFragment", "  - elevation: ${child.elevation}")
                    android.util.Log.d("BrowseFragment", "  - isClickable: ${child.isClickable}")
                    android.util.Log.d("BrowseFragment", "  - isFocusable: ${child.isFocusable}")
                    android.util.Log.d("BrowseFragment", "  - alpha: ${child.alpha}")
                    
                    // Log grandchildren if child is a ViewGroup
                    if (child is android.view.ViewGroup) {
                        android.util.Log.d("BrowseFragment", "  - Grandchild count: ${child.childCount}")
                        for (j in 0 until child.childCount) {
                            val grandchild = child.getChildAt(j)
                            android.util.Log.d("BrowseFragment", "    - Grandchild $j: ${grandchild.javaClass.simpleName}")
                            android.util.Log.d("BrowseFragment", "      - visibility: ${grandchild.visibility}")
                            android.util.Log.d("BrowseFragment", "      - alpha: ${grandchild.alpha}")
                        }
                    }
                }
                android.util.Log.d("BrowseFragment", "==========================================")

                // Set initial top padding based on top bar height and visible chips
                updateResultsPadding()
            }

            // Update padding when top bar height changes (e.g., when tags are shown/hidden)
            topBarContainer.addOnLayoutChangeListener { _, _, top, _, _, bottom, oldBottom, oldTop, _ ->
                val newHeight = bottom - top
                val oldHeight = oldBottom - oldTop
                android.util.Log.d("BrowseFragment", "========== topBarContainer layout change ==========")
                android.util.Log.d("BrowseFragment", "topBarContainer: oldHeight=$oldHeight, newHeight=$newHeight, heightChanged=${newHeight != oldHeight}")
                android.util.Log.d("BrowseFragment", "topBarContainer: top=$top, bottom=$bottom, oldTop=$oldTop, oldBottom=$oldBottom")
                if (newHeight > 0) {
                    android.util.Log.d("BrowseFragment", "topBarContainer: Calling updateResultsPadding()")
                    updateResultsPadding()
                }
            }

            // Add scroll listener for auto-reload
            browseResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
                    val topBarHeight = binding?.topBarContainer?.height ?: 0
                    val showThreshold = 150 // Pixels to scroll up to show topbar
                    
                    // Track total scroll distance
                    scrollAccumulator += dy
                    
                    // Clamp accumulator to prevent unbounded growth
                    if (scrollAccumulator > topBarHeight * 2) {
                        scrollAccumulator = topBarHeight * 2
                    } else if (scrollAccumulator < -showThreshold * 2) {
                        scrollAccumulator = -showThreshold * 2
                    }
                    
                    // Hide top bar when scrolled past 70% of topbar height
                    if (scrollAccumulator > (topBarHeight * 0.7).toInt() && isTopBarVisible && !isAnimatingTopBar) {
                        android.util.Log.d("BrowseFragment", "onScrolled: HIDING top bar (scrollAccumulator=$scrollAccumulator, topBarHeight=$topBarHeight)")
                        isAnimatingTopBar = true
                        binding?.topBarContainer?.let { topBar ->
                            topBar.animate()
                                .translationY(-topBar.height.toFloat())
                                .setDuration(300)
                                .withEndAction { isAnimatingTopBar = false }
                                .start()
                        }
                        isTopBarVisible = false
                        scrollAccumulator = 0
                    }
                    // Show top bar when scrolling up past threshold
                    else if (scrollAccumulator < -showThreshold && !isTopBarVisible && !isAnimatingTopBar) {
                        android.util.Log.d("BrowseFragment", "onScrolled: SHOWING top bar (scrollAccumulator=$scrollAccumulator, showThreshold=$showThreshold)")
                        isAnimatingTopBar = true
                        binding?.topBarContainer?.let { topBar ->
                            topBar.animate()
                                .translationY(0f)
                                .setDuration(300)
                                .withEndAction { isAnimatingTopBar = false }
                                .start()
                        }
                        isTopBarVisible = true
                        scrollAccumulator = 0
                    }

                    val adapter = recyclerView.adapter as? SearchAdapter ?: return
                    val count = adapter.itemCount

                    if (layoutManager != null && hasMoreResults && !isLoadingMoreResults) {
                        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                        if (lastVisiblePosition >= count - 7) {
                            loadMoreResults()
                        }
                    }
                }
            })
        }
        android.util.Log.d("BrowseFragment", "========== setupUI completed ==========")
    }

    private fun updateUI() {
        android.util.Log.d("BrowseFragment", "========== updateUI called ==========")
        binding?.apply {
            // Update filter labels
            yearLabel.text = "Year: $selectedYear"
            seasonLabel.text = "Season: $selectedSeason"
            formatLabel.text = "Format: $selectedFormat"
            sortLabel.text = "Sort: $selectedSort"

            // Update results
            (browseResults.adapter as? SearchAdapter)?.submitList(resultsList)
            android.util.Log.d("BrowseFragment", "updateUI: Submitted ${resultsList.size} results to adapter")

            // Show/hide no results text and end of results toast
            if (resultsList.isEmpty() && viewModel.uiState.value?.isLoading != true) {
                // No results at all and not loading
                noResultsText.visibility = View.VISIBLE
                endOfResultsToast.visibility = View.GONE
            } else if (!hasMoreResults && viewModel.uiState.value?.isLoading != true && resultsList.isNotEmpty()) {
                // We have results but no more pages to load
                noResultsText.visibility = View.GONE
                endOfResultsToast.visibility = View.VISIBLE
                endOfResultsToast.alpha = 0f
                endOfResultsToast.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
                // Auto-hide after 2 seconds
                endOfResultsToast.postDelayed({
                    endOfResultsToast.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction {
                            endOfResultsToast.visibility = View.GONE
                        }
                        .start()
                }, 2000)
            } else {
                // We have results and more to load, or currently loading
                noResultsText.visibility = View.GONE
                endOfResultsToast.visibility = View.GONE
            }

            // Show/hide loading bar in search bar
            browseSearchLoadingBar.alpha = if (viewModel.uiState.value?.isLoading == true) 1f else 0f
        }

        // Update genre and tag chips
        updateGenreChips()
        updateTagsChips()
        android.util.Log.d("BrowseFragment", "========== updateUI completed ==========")
    }

    private fun updateGenreChips() {
        binding?.genreChips?.removeAllViews()
        val chipGroup = binding?.genreChips ?: return

        if (selectedGenres.isNotEmpty()) {
            chipGroup.visibility = View.VISIBLE
            selectedGenres.forEach { genre ->
                val chip = com.google.android.material.chip.Chip(requireContext(), null, R.style.ChipFilled).apply {
                    text = genre
                    isCloseIconVisible = true
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primaryBlackBackground))
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primaryBlackBackground))
                    chipStrokeWidth = 1f
                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white))
                    setOnCloseIconClickListener {
                        selectedGenres.remove(genre)
                        updateGenreChips()
                        // Reset ViewModel page and reload results
                        viewModel.resetPage()
                        currentAniListPage = 1
                        loadAniListResults()
                    }
                }
                chipGroup.addView(chip)
            }
            // Calculate estimate based on chip visibility
            val tagsVisible = selectedTags.isNotEmpty()
            val chipRowHeight = if (tagsVisible) 240 else 120 // 240 for both rows, 120 for single row
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
        } else {
            chipGroup.visibility = View.GONE
            // Calculate estimate based on chip visibility
            val tagsVisible = selectedTags.isNotEmpty()
            val chipRowHeight = if (tagsVisible) 120 else 0 // 120 if tags still visible, 0 if both gone
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
        }
    }

    private fun updateTagsChips() {
        binding?.tagsChips?.removeAllViews()
        val chipGroup = binding?.tagsChips ?: return

        if (selectedTags.isNotEmpty()) {
            chipGroup.visibility = View.VISIBLE
            selectedTags.forEach { tag ->
                val chip = com.google.android.material.chip.Chip(requireContext(), null, R.style.ChipFilledSemiTransparent).apply {
                    text = tag
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        selectedTags.remove(tag)
                        updateTagsChips()
                        // Reset ViewModel page and reload results
                        viewModel.resetPage()
                        currentAniListPage = 1
                        loadAniListResults()
                    }
                }
                chipGroup.addView(chip)
            }
            // Calculate estimate based on chip visibility
            val genresVisible = selectedGenres.isNotEmpty()
            val chipRowHeight = if (genresVisible) 240 else 120 // 240 for both rows, 120 for single row
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
        } else {
            chipGroup.visibility = View.GONE
            // Calculate estimate based on chip visibility
            val genresVisible = selectedGenres.isNotEmpty()
            val chipRowHeight = if (genresVisible) 120 else 0 // 120 if genres still visible, 0 if both gone
            val baseHeight = 272 // Base height of top bar without chips
            val estimatedHeight = baseHeight + chipRowHeight
            updateResultsPadding(estimatedHeight)
        }
    }

    private fun showFilterDialog() {
        val genres = AniListFilterUtils.GENRES
        val tags = AniListFilterUtils.TAGS
        val years = AniListFilterUtils.YEARS
        val seasons = AniListFilterUtils.SEASONS
        val formats = AniListFilterUtils.FORMATS
        val sortOptions = AniListFilterUtils.SORT_OPTIONS

        val dialogGenres = selectedGenres.toMutableSet()
        val dialogExcludedGenres = excludedGenres.toMutableSet()
        val dialogTags = selectedTags.toMutableSet()
        val dialogExcludedTags = excludedTags.toMutableSet()
        var dialogYear = selectedYear
        var dialogSeason = selectedSeason
        var dialogFormat = selectedFormat
        var dialogSort = selectedSort
        var dialogNsfw = selectedNsfw

        activity?.let { ctx ->
            val dialog = AlertDialog.Builder(ctx, R.style.AlertDialogCustom).create()
            val dialogBinding = com.lagradost.cloudstream3.databinding.BottomAnilistGenreTagSelectorBinding.inflate(
                dialog.layoutInflater,
                null,
                false
            )
            dialog.setView(dialogBinding.root)

            // Setup NSFW toggle
            dialogBinding.nsfwToggle.isChecked = dialogNsfw
            dialogBinding.nsfwToggle.setOnCheckedChangeListener { _, isChecked ->
                dialogNsfw = isChecked
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            }

            // Setup genres adapter with 3-state support
            var genresAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            genresAdapter = AniListFilterUtils.AniListCheckboxAdapter(genres, dialogGenres, dialogExcludedGenres, { item, state ->
                android.util.Log.d("BrowseFragment", "Genres callback: item=$item, state=$state")
                when (state) {
                    0 -> { // unchecked
                        dialogGenres.remove(item)
                        dialogExcludedGenres.remove(item)
                    }
                    1 -> { // include
                        dialogGenres.add(item)
                        dialogExcludedGenres.remove(item)
                    }
                    2 -> { // exclude
                        dialogGenres.remove(item)
                        dialogExcludedGenres.add(item)
                    }
                }
                val totalCount = dialogGenres.size + dialogExcludedGenres.size
                dialogBinding.genresCount.text = if (totalCount > 0) totalCount.toString() else "0"
                android.util.Log.d("BrowseFragment", "Genres count updated: included=${dialogGenres.size}, excluded=${dialogExcludedGenres.size}, total=$totalCount")
                // Update only the single item that was clicked to avoid animating all checkboxes
                genresAdapter?.updateSingleItem(item, state)
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            })
            dialogBinding.genresRecycler.adapter = genresAdapter
            dialogBinding.genresRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.genresRecycler.itemAnimator = null

            // Setup tags adapter with 3-state support
            var tagsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            tagsAdapter = AniListFilterUtils.AniListCheckboxAdapter(tags, dialogTags, dialogExcludedTags, { item, state ->
                android.util.Log.d("BrowseFragment", "Tags callback: item=$item, state=$state")
                when (state) {
                    0 -> { // unchecked
                        dialogTags.remove(item)
                        dialogExcludedTags.remove(item)
                    }
                    1 -> { // include
                        dialogTags.add(item)
                        dialogExcludedTags.remove(item)
                    }
                    2 -> { // exclude
                        dialogTags.remove(item)
                        dialogExcludedTags.add(item)
                    }
                }
                val totalCount = dialogTags.size + dialogExcludedTags.size
                dialogBinding.tagsCount.text = if (totalCount > 0) totalCount.toString() else "0"
                android.util.Log.d("BrowseFragment", "Tags count updated: included=${dialogTags.size}, excluded=${dialogExcludedTags.size}, total=$totalCount")
                // Update only the single item that was clicked to avoid animating all checkboxes
                tagsAdapter?.updateSingleItem(item, state)
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            })
            dialogBinding.tagsRecycler.adapter = tagsAdapter
            dialogBinding.tagsRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.tagsRecycler.itemAnimator = null

            // Setup years adapter (radio mode)
            val selectedYearsSet = if (dialogYear != "All") setOf(dialogYear) else setOf("All")
            var yearsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            yearsAdapter = AniListFilterUtils.AniListCheckboxAdapter(years, selectedYearsSet, emptySet(), { item, state ->
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Years callback: item=$item, state=$state")
                if (state == 1) {
                    dialogYear = item
                } else {
                    dialogYear = "All"
                }
                val newSelectedSet = setOf(dialogYear).filterNotNull().toSet()
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Years: calling updateSelectedSet with set=$newSelectedSet")
                dialogBinding.yearRecycler.post {
                    yearsAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.yearCount.text = dialogYear
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            }, radioMode = true)
            dialogBinding.yearRecycler.adapter = yearsAdapter
            dialogBinding.yearRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.yearRecycler.itemAnimator = null

            // Setup seasons adapter (radio mode)
            val selectedSeasonsSet = if (dialogSeason != "All") setOf(dialogSeason) else setOf("All")
            var seasonsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            seasonsAdapter = AniListFilterUtils.AniListCheckboxAdapter(seasons, selectedSeasonsSet, emptySet(), { item, state ->
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Seasons callback: item=$item, state=$state")
                if (state == 1) {
                    dialogSeason = item
                } else {
                    dialogSeason = "All"
                }
                val newSelectedSet = setOf(dialogSeason).filterNotNull().toSet()
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Seasons: calling updateSelectedSet with set=$newSelectedSet")
                dialogBinding.seasonRecycler.post {
                    seasonsAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.seasonCount.text = dialogSeason
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            }, radioMode = true)
            dialogBinding.seasonRecycler.adapter = seasonsAdapter
            dialogBinding.seasonRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.seasonRecycler.itemAnimator = null

            // Setup formats adapter (radio mode)
            val selectedFormatsSet = if (dialogFormat != "All") setOf(dialogFormat) else setOf("All")
            var formatsAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            formatsAdapter = AniListFilterUtils.AniListCheckboxAdapter(formats, selectedFormatsSet, emptySet(), { item, state ->
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Formats callback: item=$item, state=$state")
                if (state == 1) {
                    dialogFormat = item
                } else {
                    dialogFormat = "All"
                }
                val newSelectedSet = setOf(dialogFormat).filterNotNull().toSet()
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Formats: calling updateSelectedSet with set=$newSelectedSet")
                dialogBinding.formatRecycler.post {
                    formatsAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.formatCount.text = dialogFormat
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            }, radioMode = true)
            dialogBinding.formatRecycler.adapter = formatsAdapter
            dialogBinding.formatRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.formatRecycler.itemAnimator = null

            // Setup sort adapter (radio mode)
            val selectedSortSet = if (dialogSort != "All") setOf(dialogSort) else setOf("All")
            var sortAdapter: AniListFilterUtils.AniListCheckboxAdapter? = null
            sortAdapter = AniListFilterUtils.AniListCheckboxAdapter(sortOptions, selectedSortSet, emptySet(), { item, state ->
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Sort callback: item=$item, state=$state")
                if (state == 1) {
                    dialogSort = item
                } else {
                    dialogSort = "All"
                }
                val newSelectedSet = setOf(dialogSort).filterNotNull().toSet()
                android.util.Log.d("ADAPTER_UPDATE_DEBUG", "Sort: calling updateSelectedSet with set=$newSelectedSet")
                dialogBinding.sortRecycler.post {
                    sortAdapter?.updateSelectedSet(newSelectedSet)
                }
                dialogBinding.sortCount.text = dialogSort
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            }, radioMode = true)
            dialogBinding.sortRecycler.adapter = sortAdapter
            dialogBinding.sortRecycler.layoutManager = LinearLayoutManager(ctx)
            dialogBinding.sortRecycler.itemAnimator = null

            // Update initial counts and subtext
            val initialGenresTotal = dialogGenres.size + dialogExcludedGenres.size
            val initialTagsTotal = dialogTags.size + dialogExcludedTags.size
            dialogBinding.genresCount.text = if (initialGenresTotal > 0) initialGenresTotal.toString() else "0"
            dialogBinding.tagsCount.text = if (initialTagsTotal > 0) initialTagsTotal.toString() else "0"
            // Show selected values as subtext for single-select fields (year, season, format, sort)
            dialogBinding.yearCount.visibility = View.VISIBLE
            dialogBinding.yearCount.text = dialogYear
            dialogBinding.seasonCount.visibility = View.VISIBLE
            dialogBinding.seasonCount.text = dialogSeason
            dialogBinding.formatCount.visibility = View.VISIBLE
            dialogBinding.formatCount.text = dialogFormat
            dialogBinding.sortCount.visibility = View.VISIBLE
            dialogBinding.sortCount.text = dialogSort

            // Set initial Load Defaults button visibility
            updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)

            // Accordion toggle for genres
            dialogBinding.genresHeader.setOnClickListener {
                toggleAccordion(dialogBinding.genresRecycler, dialogBinding.genresExpandIcon)
            }

            // Accordion toggle for tags
            dialogBinding.tagsHeader.setOnClickListener {
                toggleAccordion(dialogBinding.tagsRecycler, dialogBinding.tagsExpandIcon)
            }

            // Accordion toggle for year
            dialogBinding.yearHeader.setOnClickListener {
                toggleAccordion(dialogBinding.yearRecycler, dialogBinding.yearExpandIcon)
            }

            // Accordion toggle for season
            dialogBinding.seasonHeader.setOnClickListener {
                toggleAccordion(dialogBinding.seasonRecycler, dialogBinding.seasonExpandIcon)
            }

            // Accordion toggle for format
            dialogBinding.formatHeader.setOnClickListener {
                toggleAccordion(dialogBinding.formatRecycler, dialogBinding.formatExpandIcon)
            }

            // Accordion toggle for sort
            dialogBinding.sortHeader.setOnClickListener {
                toggleAccordion(dialogBinding.sortRecycler, dialogBinding.sortExpandIcon)
            }

            // Load Defaults button
            dialogBinding.loadDefaultButton.setOnClickListener {
                android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: Load Defaults button clicked")
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                val defaultGenres = prefs.getStringSet("default_genres", null) ?: emptySet()
                val defaultExcludedGenres = prefs.getStringSet("default_excluded_genres", null) ?: emptySet()
                val defaultTags = prefs.getStringSet("default_tags", null) ?: emptySet()
                val defaultExcludedTags = prefs.getStringSet("default_excluded_tags", null) ?: emptySet()
                val defaultYear = prefs.getString("default_year", "All") ?: "All"
                val defaultSeason = prefs.getString("default_season", "All") ?: "All"
                val defaultFormat = prefs.getString("default_format", "All") ?: "All"
                val defaultSort = prefs.getString("default_sort", "Popularity") ?: "Popularity"
                val defaultNsfw = prefs.getBoolean("default_nsfw", false)

                android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: Loading defaults into dialog state")
                android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: defaultGenres=$defaultGenres, defaultExcludedGenres=$defaultExcludedGenres")
                android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: defaultTags=$defaultTags, defaultExcludedTags=$defaultExcludedTags")
                android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: defaultYear=$defaultYear, defaultSeason=$defaultSeason, defaultFormat=$defaultFormat, defaultSort=$defaultSort, defaultNsfw=$defaultNsfw")

                // Update dialog state with defaults
                dialogGenres.clear()
                dialogGenres.addAll(defaultGenres)
                dialogExcludedGenres.clear()
                dialogExcludedGenres.addAll(defaultExcludedGenres)
                dialogTags.clear()
                dialogTags.addAll(defaultTags)
                dialogExcludedTags.clear()
                dialogExcludedTags.addAll(defaultExcludedTags)
                dialogYear = defaultYear
                dialogSeason = defaultSeason
                dialogFormat = defaultFormat
                dialogSort = defaultSort
                dialogNsfw = defaultNsfw
                dialogBinding.nsfwToggle.isChecked = defaultNsfw

                // Update adapters
                genresAdapter?.updateSelectedSet(dialogGenres)
                genresAdapter?.updateExcludedSet(dialogExcludedGenres)
                tagsAdapter?.updateSelectedSet(dialogTags)
                tagsAdapter?.updateExcludedSet(dialogExcludedTags)
                yearsAdapter?.updateSelectedSet(setOf(dialogYear))
                seasonsAdapter?.updateSelectedSet(setOf(dialogSeason))
                formatsAdapter?.updateSelectedSet(setOf(dialogFormat))
                sortAdapter?.updateSelectedSet(setOf(dialogSort))

                // Update counts
                val genresTotal = dialogGenres.size + dialogExcludedGenres.size
                val tagsTotal = dialogTags.size + dialogExcludedTags.size
                dialogBinding.genresCount.text = if (genresTotal > 0) genresTotal.toString() else "0"
                dialogBinding.tagsCount.text = if (tagsTotal > 0) tagsTotal.toString() else "0"
                dialogBinding.yearCount.text = dialogYear
                dialogBinding.seasonCount.text = dialogSeason
                dialogBinding.formatCount.text = dialogFormat
                dialogBinding.sortCount.text = dialogSort

                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)

                Toast.makeText(requireContext(), "Defaults loaded", Toast.LENGTH_SHORT).show()
            }

            // Set Default button with confirmation dialog
            dialogBinding.setDefaultButton.setOnClickListener {
                val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_default, null)
                val confirmDialog = android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                    .setView(dialogView)
                    .create()

                dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancel_button).setOnClickListener {
                    confirmDialog.dismiss()
                }

                dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.confirm_button).setOnClickListener {
                    // Save to SharedPreferences for persistence
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    prefs.edit().apply {
                        putStringSet("default_genres", dialogGenres)
                        putStringSet("default_excluded_genres", dialogExcludedGenres)
                        putStringSet("default_tags", dialogTags)
                        putStringSet("default_excluded_tags", dialogExcludedTags)
                        putString("default_year", dialogYear)
                        putString("default_season", dialogSeason)
                        putString("default_format", dialogFormat)
                        putString("default_sort", dialogSort)
                        putBoolean("default_nsfw", dialogNsfw)
                        apply()
                    }

                    // Apply immediately to class-level variables
                    selectedGenres.clear()
                    selectedGenres.addAll(dialogGenres)
                    excludedGenres.clear()
                    excludedGenres.addAll(dialogExcludedGenres)
                    selectedTags.clear()
                    selectedTags.addAll(dialogTags)
                    excludedTags.clear()
                    excludedTags.addAll(dialogExcludedTags)
                    selectedYear = dialogYear
                    selectedSeason = dialogSeason
                    selectedFormat = dialogFormat
                    selectedSort = dialogSort
                    selectedNsfw = dialogNsfw

                    // Reset ViewModel page and reload results
                    viewModel.resetPage()
                    currentAniListPage = 1
                    loadAniListResults()

                    // Scroll to top to prevent hollow space when topbar height changes
                    binding?.browseResults?.scrollToPosition(0)

                    Toast.makeText(requireContext(), "Defaults saved and applied", Toast.LENGTH_SHORT).show()
                    confirmDialog.dismiss()
                    dialog.dismiss()
                }

                confirmDialog.show()
            }

            // Clear button
            dialogBinding.clearButton.setOnClickListener {
                dialogGenres.clear()
                dialogExcludedGenres.clear()
                dialogTags.clear()
                dialogExcludedTags.clear()
                dialogYear = "All"
                dialogSeason = "All"
                dialogFormat = "All"
                dialogSort = "Popularity"
                dialogNsfw = false
                dialogBinding.nsfwToggle.isChecked = false
                genresAdapter.notifyDataSetChanged()
                genresAdapter.updateExcludedSet(emptySet())
                tagsAdapter.notifyDataSetChanged()
                tagsAdapter.updateExcludedSet(emptySet())
                yearsAdapter.updateSelectedSet(setOf("All"))
                seasonsAdapter.updateSelectedSet(setOf("All"))
                formatsAdapter.updateSelectedSet(setOf("All"))
                sortAdapter.updateSelectedSet(setOf("Popularity"))
                dialogBinding.genresCount.text = "0"
                dialogBinding.tagsCount.text = "0"
                // Update Load Defaults button visibility
                updateLoadDefaultsButtonVisibility(dialogBinding, dialogGenres, dialogExcludedGenres, dialogTags, dialogExcludedTags, dialogYear, dialogSeason, dialogFormat, dialogSort, dialogNsfw)
            }

            // Apply button
            dialogBinding.applyButton.setOnClickListener {
                android.util.Log.d("STATE_SYNC_DEBUG", "========== Apply button clicked ==========")
                android.util.Log.d("STATE_SYNC_DEBUG", "STATE_SYNC_DEBUG: Before update - local genres=$selectedGenres, excludedGenres=$excludedGenres, tags=$selectedTags, excludedTags=$excludedTags")
                android.util.Log.d("STATE_SYNC_DEBUG", "STATE_SYNC_DEBUG: Before update - ViewModel filters=${viewModel.uiState.value?.filters}")
                
                // Update class-level variables
                selectedGenres.clear()
                selectedGenres.addAll(dialogGenres)
                excludedGenres.clear()
                excludedGenres.addAll(dialogExcludedGenres)
                selectedTags.clear()
                selectedTags.addAll(dialogTags)
                excludedTags.clear()
                excludedTags.addAll(dialogExcludedTags)
                selectedYear = dialogYear
                selectedSeason = dialogSeason
                selectedFormat = dialogFormat
                selectedSort = dialogSort
                selectedNsfw = dialogNsfw

                android.util.Log.d("STATE_SYNC_DEBUG", "STATE_SYNC_DEBUG: After local update - genres=$selectedGenres, excludedGenres=$excludedGenres, tags=$selectedTags, excludedTags=$excludedTags")
                
                // Update ViewModel filter state to keep in sync
                android.util.Log.d("STATE_SYNC_FIX", "========== Syncing filter state to ViewModel ==========")
                android.util.Log.d("STATE_SYNC_FIX", "Creating filterState with: genres=$selectedGenres, excludedGenres=$excludedGenres, tags=$selectedTags, excludedTags=$excludedTags")
                val filterState = BrowseFilterState(
                    genres = selectedGenres,
                    tags = selectedTags,
                    excludedGenres = excludedGenres,
                    excludedTags = excludedTags,
                    year = selectedYear,
                    season = selectedSeason,
                    format = selectedFormat,
                    sort = selectedSort
                )
                android.util.Log.d("STATE_SYNC_FIX", "Calling viewModel.updateFilters with filterState=$filterState")
                viewModel.updateFilters(filterState)
                
                android.util.Log.d("STATE_SYNC_DEBUG", "STATE_SYNC_DEBUG: After ViewModel update - ViewModel filters=${viewModel.uiState.value?.filters}")
                android.util.Log.d("STATE_SYNC_FIX", "========== Filter state sync completed ==========")

                // Reset ViewModel page to 1 when filters change
                viewModel.resetPage()

                // Reload results with new filters
                currentAniListPage = 1
                loadAniListResults()
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun toggleAccordion(recyclerView: RecyclerView, expandIcon: ImageView) {
        if (recyclerView.visibility == View.VISIBLE) {
            recyclerView.visibility = View.GONE
            expandIcon.animate()
                .rotation(0f)
                .setDuration(250)
                .start()
        } else {
            recyclerView.visibility = View.VISIBLE
            expandIcon.animate()
                .rotation(90f)
                .setDuration(250)
                .start()
        }
    }

    private fun loadDefaultFilters() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val defaultGenres = prefs.getStringSet("default_genres", null)
        val defaultExcludedGenres = prefs.getStringSet("default_excluded_genres", null)
        val defaultTags = prefs.getStringSet("default_tags", null)
        val defaultExcludedTags = prefs.getStringSet("default_excluded_tags", null)
        val defaultYear = prefs.getString("default_year", "All")
        val defaultSeason = prefs.getString("default_season", "All")
        val defaultFormat = prefs.getString("default_format", "All")
        val defaultSort = prefs.getString("default_sort", "Popularity")
        val defaultNsfw = prefs.getBoolean("default_nsfw", false)

        if (defaultGenres != null && defaultGenres.isNotEmpty()) {
            selectedGenres.clear()
            selectedGenres.addAll(defaultGenres)
        }
        if (defaultExcludedGenres != null && defaultExcludedGenres.isNotEmpty()) {
            excludedGenres.clear()
            excludedGenres.addAll(defaultExcludedGenres)
        }
        if (defaultTags != null && defaultTags.isNotEmpty()) {
            selectedTags.clear()
            selectedTags.addAll(defaultTags)
        }
        if (defaultExcludedTags != null && defaultExcludedTags.isNotEmpty()) {
            excludedTags.clear()
            excludedTags.addAll(defaultExcludedTags)
        }
        selectedYear = defaultYear ?: "All"
        selectedSeason = defaultSeason ?: "All"
        selectedFormat = defaultFormat ?: "All"
        selectedSort = defaultSort ?: "Popularity"
        selectedNsfw = defaultNsfw

        android.util.Log.d("BrowseFragment", "loadDefaultFilters: loaded genres=$selectedGenres, excludedGenres=$excludedGenres, tags=$selectedTags, excludedTags=$excludedTags, year=$selectedYear, season=$selectedSeason, format=$selectedFormat, sort=$selectedSort, nsfw=$selectedNsfw")
    }

    private fun hasCustomDefaults(): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val defaultGenres = prefs.getStringSet("default_genres", null)
        val defaultExcludedGenres = prefs.getStringSet("default_excluded_genres", null)
        val defaultTags = prefs.getStringSet("default_tags", null)
        val defaultExcludedTags = prefs.getStringSet("default_excluded_tags", null)
        val defaultYear = prefs.getString("default_year", "All")
        val defaultSeason = prefs.getString("default_season", "All")
        val defaultFormat = prefs.getString("default_format", "All")
        val defaultSort = prefs.getString("default_sort", "Popularity")
        val defaultNsfw = prefs.getBoolean("default_nsfw", false)

        // Check if any default has been set to non-default values
        return (defaultGenres != null && defaultGenres.isNotEmpty()) ||
               (defaultExcludedGenres != null && defaultExcludedGenres.isNotEmpty()) ||
               (defaultTags != null && defaultTags.isNotEmpty()) ||
               (defaultExcludedTags != null && defaultExcludedTags.isNotEmpty()) ||
               (defaultYear != "All") ||
               (defaultSeason != "All") ||
               (defaultFormat != "All") ||
               (defaultSort != "Popularity") ||
               defaultNsfw
    }

    private fun currentSettingsDifferFromDefaults(
        currentGenres: Set<String>,
        currentExcludedGenres: Set<String>,
        currentTags: Set<String>,
        currentExcludedTags: Set<String>,
        currentYear: String,
        currentSeason: String,
        currentFormat: String,
        currentSort: String,
        currentNsfw: Boolean
    ): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val defaultGenres = prefs.getStringSet("default_genres", emptySet())
        val defaultExcludedGenres = prefs.getStringSet("default_excluded_genres", emptySet())
        val defaultTags = prefs.getStringSet("default_tags", emptySet())
        val defaultExcludedTags = prefs.getStringSet("default_excluded_tags", emptySet())
        val defaultYear = prefs.getString("default_year", "All")
        val defaultSeason = prefs.getString("default_season", "All")
        val defaultFormat = prefs.getString("default_format", "All")
        val defaultSort = prefs.getString("default_sort", "Popularity")
        val defaultNsfw = prefs.getBoolean("default_nsfw", false)

        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: Comparing current to defaults")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentGenres=$currentGenres, defaultGenres=$defaultGenres")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentExcludedGenres=$currentExcludedGenres, defaultExcludedGenres=$defaultExcludedGenres")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentTags=$currentTags, defaultTags=$defaultTags")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentExcludedTags=$currentExcludedTags, defaultExcludedTags=$defaultExcludedTags")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentYear=$currentYear, defaultYear=$defaultYear")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentSeason=$currentSeason, defaultSeason=$defaultSeason")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentFormat=$currentFormat, defaultFormat=$defaultFormat")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentSort=$currentSort, defaultSort=$defaultSort")
        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: currentNsfw=$currentNsfw, defaultNsfw=$defaultNsfw")

        val differs = currentGenres != defaultGenres ||
                     currentExcludedGenres != defaultExcludedGenres ||
                     currentTags != defaultTags ||
                     currentExcludedTags != defaultExcludedTags ||
                     currentYear != defaultYear ||
                     currentSeason != defaultSeason ||
                     currentFormat != defaultFormat ||
                     currentSort != defaultSort ||
                     currentNsfw != defaultNsfw

        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: Settings differ from defaults: $differs")
        return differs
    }

    private fun updateLoadDefaultsButtonVisibility(
        dialogBinding: com.lagradost.cloudstream3.databinding.BottomAnilistGenreTagSelectorBinding,
        currentGenres: Set<String>,
        currentExcludedGenres: Set<String>,
        currentTags: Set<String>,
        currentExcludedTags: Set<String>,
        currentYear: String,
        currentSeason: String,
        currentFormat: String,
        currentSort: String,
        currentNsfw: Boolean
    ) {
        val hasDefaults = hasCustomDefaults()
        val differs = currentSettingsDifferFromDefaults(
            currentGenres, currentExcludedGenres, currentTags, currentExcludedTags,
            currentYear, currentSeason, currentFormat, currentSort, currentNsfw
        )
        val shouldShow = hasDefaults && differs

        android.util.Log.d("BrowseFragment", "LOAD_DEFAULTS_DEBUG: updateLoadDefaultsButtonVisibility: hasDefaults=$hasDefaults, differs=$differs, shouldShow=$shouldShow")
        dialogBinding.loadDefaultButton.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun loadAniListResults() {
        android.util.Log.d("BrowseFragment", "========== loadAniListResults called ==========")
        android.util.Log.d("BrowseFragment", "loadAniListResults: isLoadingMoreResults=$isLoadingMoreResults, currentAniListPage=$currentAniListPage, hasMoreResults=$hasMoreResults")

        viewModel.setLoading(true)
        android.util.Log.d("BrowseFragment", "loadAniListResults: Set viewModel loading to true")

        // Save current filter state to ViewModel only on first page load
        if (currentAniListPage == 1) {
            android.util.Log.d("STATE_SYNC_FIX", "loadAniListResults: Syncing filter state to ViewModel on first page load")
            android.util.Log.d("STATE_SYNC_FIX", "loadAniListResults: Creating currentFilters with: genres=$selectedGenres, excludedGenres=$excludedGenres, tags=$selectedTags, excludedTags=$excludedTags")
            val currentFilters = BrowseFilterState(
                genres = selectedGenres,
                tags = selectedTags,
                excludedGenres = excludedGenres,
                excludedTags = excludedTags,
                year = selectedYear,
                season = selectedSeason,
                format = selectedFormat,
                sort = selectedSort
            )
            android.util.Log.d("STATE_SYNC_FIX", "loadAniListResults: Calling viewModel.updateFilters with currentFilters=$currentFilters")
            viewModel.updateFilters(currentFilters)
            android.util.Log.d("BrowseFragment", "loadAniListResults: Updated filters: genres=$selectedGenres, excludedGenres=$excludedGenres, tags=$selectedTags, excludedTags=$excludedTags, year=$selectedYear, season=$selectedSeason, format=$selectedFormat, sort=$selectedSort")
        } else {
            android.util.Log.d("BrowseFragment", "loadAniListResults: Skipping filter update (loading more results, page=$currentAniListPage)")
        }

        ioSafe {
            try {
                android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: Starting API call with error handling")
                main {
                    // Only use search bar spinner now, no center loader
                    android.util.Log.d("BrowseFragment", "loadAniListResults: Using search bar spinner only")
                }

                val seasonYear = if (selectedYear == "All") null else selectedYear?.toIntOrNull()
                val season = AniListFilterUtils.convertSeasonToApi(selectedSeason)
                val format = AniListFilterUtils.convertFormatToApi(selectedFormat)
                val sort = AniListFilterUtils.convertSortToApi(selectedSort)

                android.util.Log.d("BrowseFragment", "loadAniListResults: Calling aniListApi.getMediaByGenre with page=$currentAniListPage, seasonYear=$seasonYear, season=$season, format=$format, sort=$sort, searchQuery=$searchQuery, isAdult=$selectedNsfw, excludedGenres=$excludedGenres, excludedTags=$excludedTags")
                android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: API call parameters - genres=${selectedGenres.toList()}, tags=${selectedTags.toList()}, excludedGenres=${excludedGenres.toList()}, excludedTags=${excludedTags.toList()}, page=$currentAniListPage")

                val response: AniListApi.MediaByGenreResponse? = aniListApi.getMediaByGenre(
                    selectedGenres.toList(),
                    selectedTags.toList(),
                    excludedGenres.toList(),
                    excludedTags.toList(),
                    currentAniListPage,
                    seasonYear,
                    season,
                    format,
                    sort,
                    searchQuery,
                    selectedNsfw
                )

                android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: API call completed, response = $response")
                
                if (response == null) {
                    android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: API response is null")
                    throw Exception("API response is null - possible network error or API failure")
                }

                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Starting null safety checks for API response fields")
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: response.data = ${response.data}")
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: response.data.page = ${response.data?.page}")
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: response.data.page.media = ${response.data?.page?.media}")
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: response.data.page.pageInfo = ${response.data?.page?.pageInfo}")
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: response.data.page.pageInfo.hasNextPage = ${response.data?.page?.pageInfo?.hasNextPage}")

                // Null safety checks with detailed logging
                if (response.data == null) {
                    android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - response.data is null")
                    throw Exception("API response data is null - API returned invalid response structure")
                }

                if (response.data?.page == null) {
                    android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - response.data.page is null")
                    throw Exception("API response page is null - API returned invalid page structure")
                }

                if (response.data?.page?.media == null) {
                    android.util.Log.w("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: WARNING - response.data.page.media is null, treating as empty list")
                }

                if (response.data?.page?.pageInfo == null) {
                    android.util.Log.w("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: WARNING - response.data.page.pageInfo is null, treating hasNextPage as false")
                }

                val mediaItems = response.data?.page?.media ?: emptyList()
                val hasNextPage = response.data?.page?.pageInfo?.hasNextPage ?: false

                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: After null safety checks - mediaItems.size=${mediaItems.size}, hasNextPage=$hasNextPage")
                android.util.Log.d("BrowseFragment", "loadAniListResults: Received ${mediaItems.size} media items, hasNextPage=$hasNextPage")
                android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: Successfully parsed response with ${mediaItems.size} items")

                // Additional null safety for individual media items
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Starting null safety checks for individual media items")
                val validMediaItems = mutableListOf<AniListApi.MediaByGenreItem>()
                var nullItemCount = 0
                var invalidItemCount = 0
                
                mediaItems.forEachIndexed { index, mediaItem ->
                    android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Checking media item $index")
                    android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: mediaItem = $mediaItem")
                    android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: mediaItem.id = ${mediaItem?.id}")
                    android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: mediaItem.title = ${mediaItem?.title}")
                    android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: mediaItem.coverImage = ${mediaItem?.coverImage}")
                    
                    when {
                        mediaItem == null -> {
                            nullItemCount++
                            android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - media item $index is null")
                        }
                        mediaItem.id == null -> {
                            invalidItemCount++
                            android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - media item $index has null id")
                        }
                        mediaItem.title == null -> {
                            invalidItemCount++
                            android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - media item $index has null title")
                        }
                        mediaItem.coverImage == null -> {
                            android.util.Log.w("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: WARNING - media item $index has null coverImage")
                            // Still include items with null coverImage, just log warning
                            validMediaItems.add(mediaItem)
                        }
                        else -> {
                            android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: media item $index is valid")
                            validMediaItems.add(mediaItem)
                        }
                    }
                }
                
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Null safety summary - total=${mediaItems.size}, valid=${validMediaItems.size}, null=$nullItemCount, invalid=$invalidItemCount")

                val searchResponses = validMediaItems.mapNotNull { it.toSearchResponse() }
                android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Converted ${validMediaItems.size} valid media items to ${searchResponses.size} SearchResponse items")
                android.util.Log.d("BrowseFragment", "loadAniListResults: Converted to ${searchResponses.size} SearchResponse items")

                main {
                    binding?.browseLoadingBar?.visibility = View.GONE
                    android.util.Log.d("BrowseFragment", "loadAniListResults: Set browseLoadingBar visibility to GONE")

                    if (currentAniListPage == 1) {
                        android.util.Log.d("BrowseFragment", "loadAniListResults: Calling viewModel.updateResults with ${searchResponses.size} items")
                        viewModel.updateResults(searchResponses, hasNextPage)
                    } else {
                        android.util.Log.d("BrowseFragment", "loadAniListResults: Calling viewModel.appendResults with ${searchResponses.size} items")
                        viewModel.appendResults(searchResponses, hasNextPage)
                    }

                    isLoadingMoreResults = false
                    hasMoreResults = hasNextPage
                    viewModel.setLoading(false)
                    android.util.Log.d("BrowseFragment", "loadAniListResults: Set isLoadingMoreResults to false, hasMoreResults to $hasNextPage, viewModel loading to false")
                    android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: Successfully updated UI with results")
                }
            } catch (e: Exception) {
                android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: ERROR in loadAniListResults", e)
                android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: Exception message: ${e.message}")
                android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: Exception type: ${e.javaClass.simpleName}")
                android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: Stack trace: ${e.stackTraceToString()}")
                
                main {
                    binding?.browseLoadingBar?.visibility = View.GONE
                    android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: Hid loading bar due to error")
                    
                    isLoadingMoreResults = false
                    viewModel.setLoading(false)
                    android.util.Log.d("API_ERROR_HANDLING", "API_ERROR_HANDLING: Reset loading flags due to error")
                    
                    // Show error message to user
                    android.util.Log.e("API_ERROR_HANDLING", "API_ERROR_HANDLING: Showing error toast to user")
                    com.lagradost.cloudstream3.CommonActivity.showToast("Failed to load results: ${e.message}")
                }
            }
        }
        android.util.Log.d("BrowseFragment", "========== loadAniListResults completed ==========")
    }

    private fun loadMoreResults() {
        android.util.Log.d("BrowseFragment", "========== loadMoreResults called ==========")
        android.util.Log.d("BrowseFragment", "loadMoreResults: isLoadingMoreResults=$isLoadingMoreResults, hasMoreResults=$hasMoreResults")
        if (isLoadingMoreResults || !hasMoreResults) {
            android.util.Log.d("BrowseFragment", "loadMoreResults: Returning early - isLoadingMoreResults=$isLoadingMoreResults, hasMoreResults=$hasMoreResults")
            return
        }

        android.util.Log.d("BrowseFragment", "loadMoreResults: Setting isLoadingMoreResults to true and incrementing page")
        isLoadingMoreResults = true
        viewModel.incrementPage()
        android.util.Log.d("BrowseFragment", "loadMoreResults: Page incremented to ${viewModel.uiState.value?.currentPage}")
        loadAniListResults()
        android.util.Log.d("BrowseFragment", "========== loadMoreResults completed ==========")
    }

    private fun AniListApi.MediaByGenreItem.toSearchResponse(): SearchResponse? {
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Converting MediaByGenreItem to SearchResponse")
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: this.id = ${this.id}")
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: this.title = ${this.title}")
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: this.title.romaji = ${this.title?.romaji}")
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: this.title.english = ${this.title?.english}")
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: this.coverImage = ${this.coverImage}")
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: this.coverImage.large = ${this.coverImage?.large}")
        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: this.coverImage.medium = ${this.coverImage?.medium}")

        // Null safety checks for required fields
        if (this.id == null) {
            android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - Cannot convert item with null id")
            return null
        }

        if (this.title == null) {
            android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - Cannot convert item with null title")
            return null
        }

        // Extract name with fallbacks
        val name = this.title?.romaji ?: this.title?.english ?: ""
        if (name.isBlank()) {
            android.util.Log.e("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: ERROR - Both romaji and english titles are null or blank")
            return null
        }

        // Extract poster URL with fallbacks
        val posterUrl = this.coverImage?.large ?: this.coverImage?.medium
        if (posterUrl == null) {
            android.util.Log.w("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: WARNING - Both coverImage.large and coverImage.medium are null, using null posterUrl")
        }

        android.util.Log.d("NULL_SAFETY_CHECK", "NULL_SAFETY_CHECK: Successfully converted - name='$name', posterUrl=$posterUrl")
        
        @Suppress("DEPRECATION_ERROR")
        return AnimeSearchResponse(
            name = name,
            url = "https://anilist.co/anime/${this.id}",
            apiName = "AniList",
            type = TvType.Anime,
            id = this.id,
            posterUrl = posterUrl
        )
    }
}
